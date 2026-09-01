package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidation;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalPublicationPlan.DocumentPlan;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestDryRunService.DryRunResult;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Stream;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class LegalManifestDryRunConcurrencyIT {

    private static final String GOLDEN_MANIFEST =
            "/legal/manifest/release-valid-v1/publication-manifest.json";
    private static final String DRY_RUN_APPLICATION_NAME =
            "ordenfix-legal-dry-run-concurrency-it";
    private static final long NEW_LINE_GATE_LOCK_ID = 6_742_903_115_821L;
    private static final Duration DATABASE_POLL_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DATABASE_POLL_INTERVAL = Duration.ofMillis(25);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_manifest_concurrency")
            .withUsername("ordenfix")
            .withPassword("ordenfix");

    private static AnnotationConfigApplicationContext context;
    private static LegalManifestDryRunService service;
    private static ValidatedRelease goldenRelease;
    private static JdbcTemplate jdbc;

    @TempDir
    private Path temporaryDirectory;

    @BeforeAll
    static void prepareDatabaseAndRelease() throws URISyntaxException {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target("27")
                .load()
                .migrate();
        context = context();
        service = context.getBean(LegalManifestDryRunService.class);
        jdbc = context.getBean(JdbcTemplate.class);
        LegalManifestValidation<ValidatedRelease> validation =
                new LegalManifestValidator().validate(goldenManifest());
        assertThat(validation.status())
                .as("issues=%s", validation.issues())
                .isEqualTo(LegalManifestStatus.PASS);
        goldenRelease = validation.value().orElseThrow();
    }

    @AfterAll
    static void closeContext() {
        if (context != null) {
            context.close();
        }
    }

    @BeforeEach
    void cleanDomainState() {
        dropTestObjects();
        jdbc.execute("""
                TRUNCATE TABLE legal_publicaciones, legal_documento_reemplazo_lotes
                RESTART IDENTITY CASCADE
                """);
    }

    @AfterEach
    void removeTestObjects() {
        dropTestObjects();
    }

    @Test
    void deferredConstraintFailureRollsBackTheCompleteProvisionalGraph() {
        jdbc.execute("CREATE SEQUENCE legal_dry_run_test_deferred_seq");
        jdbc.execute("""
                CREATE FUNCTION legal_dry_run_test_deferred_failure()
                RETURNS TRIGGER
                LANGUAGE plpgsql
                AS $$
                BEGIN
                    PERFORM nextval('legal_dry_run_test_deferred_seq');
                    RAISE EXCEPTION 'fallo diferido deliberado del test'
                        USING ERRCODE = '23514';
                END;
                $$
                """);
        jdbc.execute("""
                CREATE CONSTRAINT TRIGGER zz_legal_dry_run_test_deferred_failure
                AFTER UPDATE ON legal_publicaciones
                DEFERRABLE INITIALLY DEFERRED
                FOR EACH ROW
                WHEN (NEW.estado_construccion = 'SELLADO')
                EXECUTE FUNCTION legal_dry_run_test_deferred_failure()
                """);
        Map<String, Long> baseline = requiredTableCounts();

        LegalManifestValidation<DryRunResult> validation =
                serviceWithoutSchemaPreflight().dryRun(goldenRelease);

        assertFailure(validation, LegalManifestStatus.BLOCKED,
                LegalManifestIssueCode.DB_CONSTRAINT);
        assertThat(jdbc.queryForObject(
                "SELECT is_called FROM legal_dry_run_test_deferred_seq",
                Boolean.class)).isTrue();
        assertThat(requiredTableCounts()).isEqualTo(baseline);
    }

    @Test
    void serializableIsolationIsRejectedAndLeavesNoRows() {
        LegalManifestDryRunService serializableService = serviceWithTransaction(
                TransactionDefinition.ISOLATION_SERIALIZABLE,
                45);
        Map<String, Long> baseline = requiredTableCounts();

        LegalManifestValidation<DryRunResult> validation =
                serializableService.dryRun(goldenRelease);

        assertFailure(validation, LegalManifestStatus.ERROR,
                LegalManifestIssueCode.DB_ISOLATION);
        assertThat(requiredTableCounts()).isEqualTo(baseline);
    }

    @Test
    void retainedEditorialLockTimesOutBeforeGraphAccessAndRetryRecovers() throws Exception {
        Long editorialLockId = jdbc.queryForObject(
                "SELECT pg_catalog.hashtextextended(?, 0)",
                Long.class,
                LegalManifestDatabaseGate.EDITORIAL_LOCK_NAME);
        assertThat(editorialLockId).isNotNull();
        Map<String, Long> baseline = requiredTableCounts();
        Boolean sequenceCalledBefore = jdbc.queryForObject(
                "SELECT is_called FROM legal_documento_contextos_id_seq",
                Boolean.class);
        LegalDatabaseBudgets reducedBudgets = new LegalDatabaseBudgets(5, 2, 1, 1);
        LegalManifestDryRunService reducedService = serviceWithTransaction(
                TransactionDefinition.ISOLATION_READ_COMMITTED,
                5,
                reducedBudgets);

        try (Connection holder = context.getBean(DataSource.class).getConnection()) {
            acquireAdvisoryLock(holder, editorialLockId);

            LegalManifestValidation<DryRunResult> validation =
                    reducedService.dryRun(goldenRelease);

            assertFailure(validation, LegalManifestStatus.ERROR,
                    LegalManifestIssueCode.DB_LOCK_TIMEOUT);
            assertThat(requiredTableCounts()).isEqualTo(baseline);
            assertThat(jdbc.queryForObject(
                    "SELECT is_called FROM legal_documento_contextos_id_seq",
                    Boolean.class)).isEqualTo(sequenceCalledBefore);
            releaseAdvisoryLock(holder, editorialLockId);
        }

        assertPass(service.dryRun(goldenRelease));
        assertThat(requiredTableCounts()).isEqualTo(baseline);
    }

    @Test
    void retainedHistoricalLineLockMapsToLockTimeoutWithoutPartialRows() throws Exception {
        UUID publicationId = insertOpenPublication("lock-holder-publication");
        UUID lineId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO legal_documento_lineas
                    (id, clave, tipo, locale, publicacion_intro_id, creado_en)
                VALUES (?, 'terminos', 'TERMINOS_SERVICIO', 'es-AR', ?, CURRENT_TIMESTAMP)
                """, lineId, publicationId);
        Map<String, Long> baseline = requiredTableCounts();

        DataSource dataSource = context.getBean(DataSource.class);
        try (Connection holder = dataSource.getConnection();
             PreparedStatement lock = holder.prepareStatement("""
                     SELECT id FROM legal_documento_lineas WHERE id = ? FOR UPDATE
                     """)) {
            holder.setAutoCommit(false);
            lock.setObject(1, lineId);
            lock.executeQuery().close();

            LegalManifestValidation<DryRunResult> validation = service.dryRun(goldenRelease);

            assertFailure(validation, LegalManifestStatus.ERROR,
                    LegalManifestIssueCode.DB_LOCK_TIMEOUT);
            assertThat(requiredTableCounts()).isEqualTo(baseline);
            holder.rollback();
        }
    }

    @Test
    void versionTransitionLockIsRejectedBeforeTheDryRunCanLockItsLine() throws Exception {
        HistoricalDocument historical = insertSealedHistoricalTerms();
        Map<String, Long> baseline = requiredTableCounts();
        DataSource dataSource = context.getBean(DataSource.class);

        try (Connection transition = dataSource.getConnection();
             PreparedStatement lockVersion = transition.prepareStatement("""
                     SELECT id FROM legal_documento_versiones WHERE id = ? FOR UPDATE
                     """)) {
            transition.setAutoCommit(false);
            lockVersion.setObject(1, historical.versionId());
            lockVersion.executeQuery().close();

            long started = System.nanoTime();
            LegalManifestValidation<DryRunResult> validation = service.dryRun(goldenRelease);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

            assertFailure(validation, LegalManifestStatus.ERROR,
                    LegalManifestIssueCode.DB_LOCK_TIMEOUT);
            assertThat(elapsed).isLessThan(Duration.ofSeconds(2));
            try (PreparedStatement lockLine = transition.prepareStatement("""
                    SELECT id FROM legal_documento_lineas WHERE id = ? FOR UPDATE NOWAIT
                    """)) {
                lockLine.setObject(1, historical.lineId());
                lockLine.executeQuery().close();
            }
            assertThat(requiredTableCounts()).isEqualTo(baseline);
            transition.rollback();
        }
    }

    @Test
    void inverseManifestOrdersAreSerializedBeforeGraphAccessAndAlwaysRollBack() throws Exception {
        ValidatedRelease forward = copyAndValidateGolden(
                "concurrent-forward-order-v1",
                false);
        ValidatedRelease reverse = copyAndValidateGolden(
                "concurrent-reverse-order-v1",
                true);
        Map<String, Long> baseline = requiredTableCounts();
        JdbcTemplate observer = directJdbc();
        installNewLineGate(observer);
        LegalManifestDryRunService instrumentedService = serviceWithoutSchemaPreflight();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (Connection gate = directDataSource().getConnection()) {
            acquireAdvisoryLock(gate, NEW_LINE_GATE_LOCK_ID);
            Future<LegalManifestValidation<DryRunResult>> first =
                    submitDryRun(instrumentedService, executor, forward, ready, start);
            Future<LegalManifestValidation<DryRunResult>> second =
                    submitDryRun(instrumentedService, executor, reverse, ready, start);

            assertThat(ready.await(5, TimeUnit.SECONDS))
                    .as("ambos dry-runs quedaron listos")
                    .isTrue();
            start.countDown();
            awaitCondition(
                    "un writer en el grafo y el segundo esperando el lock editorial",
                    DATABASE_POLL_TIMEOUT,
                    () -> activeBlockedDocumentInserts(observer) == 1
                            && activeBlockedEditorialGateWaits(observer) == 1);
            releaseAdvisoryLock(gate, NEW_LINE_GATE_LOCK_ID);

            assertPass(first.get(30, TimeUnit.SECONDS));
            assertPass(second.get(30, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
            dropTestObjects();
        }

        assertThat(requiredTableCounts()).isEqualTo(baseline);
    }

    @Test
    void terminatedDatabaseSessionMapsToConnectionFailureRollsBackAndPoolRecovers()
            throws Exception {
        Map<String, Long> baseline = requiredTableCounts();
        JdbcTemplate observer = directJdbc();
        installDisconnectPause(observer);
        LegalManifestDryRunService instrumentedService = serviceWithoutSchemaPreflight();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<LegalManifestValidation<DryRunResult>> pending =
                executor.submit(() -> instrumentedService.dryRun(goldenRelease));

        try {
            int backendPid = awaitValue(
                    "sesión del dry-run dentro de pg_sleep",
                    DATABASE_POLL_TIMEOUT,
                    () -> sleepingPublicationInsertPid(observer));
            Boolean terminated = observer.queryForObject(
                    "SELECT pg_catalog.pg_terminate_backend(?)",
                    Boolean.class,
                    backendPid);
            assertThat(terminated).isTrue();

            LegalManifestValidation<DryRunResult> validation =
                    pending.get(15, TimeUnit.SECONDS);
            assertFailure(validation, LegalManifestStatus.ERROR,
                    LegalManifestIssueCode.DB_CONNECTION);
            assertThat(requiredTableCounts()).isEqualTo(baseline);
        } finally {
            pending.cancel(true);
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
            dropTestObjects();
        }

        assertPass(service.dryRun(goldenRelease));
        assertThat(requiredTableCounts()).isEqualTo(baseline);
    }

    @Test
    void springTransactionDeadlineMapsToStatementTimeoutAndRollsBack() {
        jdbc.execute("""
                CREATE FUNCTION legal_dry_run_test_slow_insert()
                RETURNS TRIGGER
                LANGUAGE plpgsql
                AS $$
                BEGIN
                    PERFORM pg_sleep(2);
                    RETURN NEW;
                END;
                $$
                """);
        jdbc.execute("""
                CREATE TRIGGER zz_legal_dry_run_test_slow_insert
                BEFORE INSERT ON legal_publicaciones
                FOR EACH ROW EXECUTE FUNCTION legal_dry_run_test_slow_insert()
                """);
        LegalManifestDryRunService shortDeadlineService = serviceWithTransaction(
                TransactionDefinition.ISOLATION_READ_COMMITTED,
                1,
                LegalDatabaseBudgets.production(),
                false);
        Map<String, Long> baseline = requiredTableCounts();

        LegalManifestValidation<DryRunResult> validation =
                shortDeadlineService.dryRun(goldenRelease);

        assertFailure(validation, LegalManifestStatus.ERROR,
                LegalManifestIssueCode.DB_STATEMENT_TIMEOUT);
        assertThat(requiredTableCounts()).isEqualTo(baseline);
    }

    private LegalManifestDryRunService serviceWithTransaction(int isolation, int timeoutSeconds) {
        return serviceWithTransaction(
                isolation,
                timeoutSeconds,
                LegalDatabaseBudgets.production());
    }

    private LegalManifestDryRunService serviceWithTransaction(
            int isolation,
            int timeoutSeconds,
            LegalDatabaseBudgets budgets) {
        return serviceWithTransaction(isolation, timeoutSeconds, budgets, true);
    }

    private LegalManifestDryRunService serviceWithTransaction(
            int isolation,
            int timeoutSeconds,
            LegalDatabaseBudgets budgets,
            boolean verifySchema) {
        TransactionTemplate transaction = new TransactionTemplate(
                context.getBean(JdbcTransactionManager.class));
        transaction.setName("legal-manifest-dry-run-test");
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(isolation);
        transaction.setTimeout(timeoutSeconds);
        LegalManifestDatabaseGate gate = new LegalManifestDatabaseGate(
                transaction,
                jdbc,
                budgets,
                verifySchema
                        ? List.of(context.getBean(LegalV27SchemaVerifier.class))
                        : List.of());
        return new LegalManifestDryRunService(
                gate,
                jdbc,
                context.getBean(LegalManifestGraphWriter.class),
                context.getBean(LegalDatabaseFailureMapper.class));
    }

    /**
     * Test-only boundary for deliberate catalog instrumentation. Product contexts always keep the
     * strict V27 preflight; these fixtures add temporary triggers/functions to exercise failures
     * that occur after graph access.
     */
    private LegalManifestDryRunService serviceWithoutSchemaPreflight() {
        LegalManifestDatabaseGate gate = new LegalManifestDatabaseGate(
                context.getBean(TransactionTemplate.class),
                jdbc,
                LegalDatabaseBudgets.production(),
                List.of());
        return new LegalManifestDryRunService(
                gate,
                jdbc,
                context.getBean(LegalManifestGraphWriter.class),
                context.getBean(LegalDatabaseFailureMapper.class));
    }

    private ValidatedRelease copyAndValidateGolden(String publicationId, boolean reverseOrder)
            throws Exception {
        Path sourceRoot = goldenManifest().getParent();
        Path releaseRoot = temporaryDirectory.resolve(publicationId);
        Files.createDirectories(releaseRoot);
        try (Stream<Path> entries = Files.list(sourceRoot)) {
            for (Path source : entries.toList()) {
                Files.copy(
                        source,
                        releaseRoot.resolve(source.getFileName().toString()),
                        StandardCopyOption.COPY_ATTRIBUTES);
            }
        }

        Path manifestPath = releaseRoot.resolve("publication-manifest.json");
        ObjectNode manifest = (ObjectNode) MAPPER.readTree(Files.readAllBytes(manifestPath));
        manifest.put("publicationId", publicationId);
        if (reverseOrder) {
            reverseArray(manifest, "documents");
            reverseArray(manifest, "requirements");
        }
        Files.write(
                manifestPath,
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));

        LegalManifestValidation<ValidatedRelease> validation =
                new LegalManifestValidator().validate(manifestPath);
        assertThat(validation.status())
                .as("incoming=%s issues=%s", publicationId, validation.issues())
                .isEqualTo(LegalManifestStatus.PASS);
        return validation.value().orElseThrow();
    }

    private static void reverseArray(ObjectNode manifest, String field) {
        ArrayNode source = (ArrayNode) manifest.path(field);
        ArrayNode reversed = MAPPER.createArrayNode();
        for (int index = source.size() - 1; index >= 0; index--) {
            reversed.add(source.get(index));
        }
        manifest.set(field, reversed);
    }

    private static Future<LegalManifestValidation<DryRunResult>> submitDryRun(
            LegalManifestDryRunService dryRunService,
            ExecutorService executor,
            ValidatedRelease release,
            CountDownLatch ready,
            CountDownLatch start) {
        return executor.submit(() -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("No se abrió la salida concurrente del dry-run");
            }
            return dryRunService.dryRun(release);
        });
    }

    private static void installNewLineGate(JdbcTemplate database) {
        database.execute("""
                CREATE FUNCTION legal_dry_run_test_new_line_gate()
                RETURNS TRIGGER
                LANGUAGE plpgsql
                AS $$
                BEGIN
                    PERFORM pg_catalog.pg_advisory_xact_lock_shared(6742903115821);
                    RETURN NULL;
                END;
                $$
                """);
        database.execute("""
                CREATE TRIGGER zz_legal_dry_run_test_new_line_gate
                AFTER INSERT ON legal_documento_lineas
                FOR EACH ROW EXECUTE FUNCTION legal_dry_run_test_new_line_gate()
                """);
    }

    private static void installDisconnectPause(JdbcTemplate database) {
        database.execute("""
                CREATE FUNCTION legal_dry_run_test_disconnect_pause()
                RETURNS TRIGGER
                LANGUAGE plpgsql
                AS $$
                BEGIN
                    PERFORM pg_catalog.pg_sleep(30);
                    RETURN NEW;
                END;
                $$
                """);
        database.execute("""
                CREATE TRIGGER zz_legal_dry_run_test_disconnect_pause
                BEFORE INSERT ON legal_publicaciones
                FOR EACH ROW EXECUTE FUNCTION legal_dry_run_test_disconnect_pause()
                """);
    }

    private static void acquireAdvisoryLock(Connection connection, long lockId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT pg_catalog.pg_advisory_lock(?)")) {
            statement.setLong(1, lockId);
            statement.executeQuery().close();
        }
    }

    private static void releaseAdvisoryLock(Connection connection, long lockId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT pg_catalog.pg_advisory_unlock(?)")) {
            statement.setLong(1, lockId);
            try (var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getBoolean(1)).isTrue();
            }
        }
    }

    private static int activeBlockedDocumentInserts(JdbcTemplate observer) {
        Integer count = observer.queryForObject("""
                SELECT count(*)::integer
                  FROM pg_catalog.pg_stat_activity
                 WHERE datname = pg_catalog.current_database()
                   AND application_name = ?
                   AND state = 'active'
                   AND wait_event_type = 'Lock'
                   AND query LIKE '%INSERT INTO legal_documento_lineas%'
                """, Integer.class, DRY_RUN_APPLICATION_NAME);
        return Objects.requireNonNull(count, "active blocked document inserts");
    }

    private static int activeBlockedEditorialGateWaits(JdbcTemplate observer) {
        Integer count = observer.queryForObject("""
                SELECT count(*)::integer
                  FROM pg_catalog.pg_stat_activity
                 WHERE datname = pg_catalog.current_database()
                   AND application_name = ?
                   AND state = 'active'
                   AND wait_event_type = 'Lock'
                   AND query LIKE '%pg_advisory_xact_lock%'
                   AND query LIKE '%hashtextextended%'
                """, Integer.class, DRY_RUN_APPLICATION_NAME);
        return Objects.requireNonNull(count, "active blocked editorial gate waits");
    }

    private static Optional<Integer> sleepingPublicationInsertPid(JdbcTemplate observer) {
        return observer.query("""
                SELECT pid
                  FROM pg_catalog.pg_stat_activity
                 WHERE datname = pg_catalog.current_database()
                   AND application_name = ?
                   AND state = 'active'
                   AND wait_event_type = 'Timeout'
                   AND wait_event = 'PgSleep'
                   AND query LIKE '%INSERT INTO legal_publicaciones%'
                 ORDER BY pid
                 LIMIT 1
                """, (resultSet, rowNumber) -> resultSet.getInt(1),
                DRY_RUN_APPLICATION_NAME).stream().findFirst();
    }

    private static void awaitCondition(
            String description,
            Duration timeout,
            BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        do {
            if (condition.getAsBoolean()) {
                return;
            }
            LockSupport.parkNanos(DATABASE_POLL_INTERVAL.toNanos());
        } while (System.nanoTime() < deadline);
        throw new AssertionError("No se observó a tiempo: " + description);
    }

    private static <T> T awaitValue(
            String description,
            Duration timeout,
            Supplier<Optional<T>> probe) {
        long deadline = System.nanoTime() + timeout.toNanos();
        do {
            Optional<T> value = probe.get();
            if (value.isPresent()) {
                return value.orElseThrow();
            }
            LockSupport.parkNanos(DATABASE_POLL_INTERVAL.toNanos());
        } while (System.nanoTime() < deadline);
        throw new AssertionError("No se observó a tiempo: " + description);
    }

    private static void assertPass(LegalManifestValidation<DryRunResult> validation) {
        assertThat(validation.status())
                .as("issues=%s", validation.issues())
                .isEqualTo(LegalManifestStatus.PASS);
        assertThat(validation.value()).isPresent();
    }

    private UUID insertOpenPublication(String externalId) {
        UUID publicationId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO legal_publicaciones
                    (id, publication_external_id, schema_version, locale,
                     manifest_sha256, manifest_canonico, razon_social, cuit,
                     domicilio_legal, jurisdiccion, horario_atencion,
                     email_legal, email_privacidad, email_soporte,
                     revision_legal_estado, revision_contable_estado, importado_en)
                VALUES (?, ?, 1, 'es-AR', ?, '{}', 'OrdenFix Test', '30000000000',
                        'Calle de prueba 100', 'CABA', 'Lunes a viernes',
                        'legal@ordenfix.test', 'privacidad@ordenfix.test',
                        'soporte@ordenfix.test', 'PENDIENTE', 'PENDIENTE',
                        CURRENT_TIMESTAMP)
                """, publicationId, externalId, "a".repeat(64));
        return publicationId;
    }

    private HistoricalDocument insertSealedHistoricalTerms() {
        DocumentPlan terms = goldenRelease.plan().documentByKey("terminos").orElseThrow();
        UUID publicationId = insertOpenPublication("historical-terms-v1");
        UUID lineId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO legal_documento_lineas
                    (id, clave, tipo, locale, publicacion_intro_id, creado_en)
                VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, lineId, terms.declaration().key(), terms.declaration().type().name(),
                terms.declaration().locale().getCodigo(), publicationId);
        jdbc.update("""
                INSERT INTO legal_documento_versiones
                    (id, documento_linea_id, publicacion_intro_id, version,
                     lineage_ordinal, titulo, contenido_markdown, sha256,
                     vigente_desde, requires_reacceptance)
                VALUES (?, ?, ?, ?, 1, ?, ?, ?, ?, ?)
                """, versionId, lineId, publicationId, terms.declaration().version(),
                terms.title(), terms.markdown(), terms.declaration().sha256(),
                terms.declaration().effectiveAt(),
                terms.declaration().requiresReacceptance());
        for (var documentContext : terms.declaration().contexts()) {
            jdbc.update("""
                    INSERT INTO legal_documento_contextos
                        (documento_version_id, contexto)
                    VALUES (?, ?)
                    """, versionId, documentContext.name());
        }
        jdbc.update("""
                INSERT INTO legal_publicacion_documentos
                    (publicacion_id, documento_version_id, manifest_ordinal)
                VALUES (?, ?, 1)
                """, publicationId, versionId);
        TransactionTemplate transaction = context.getBean(TransactionTemplate.class);
        transaction.executeWithoutResult(status -> {
            jdbc.update("""
                    UPDATE legal_publicaciones
                       SET estado_construccion = 'SELLADO', sellado_en = transaction_timestamp()
                     WHERE id = ?
                    """, publicationId);
            jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
        });
        return new HistoricalDocument(lineId, versionId);
    }

    private static void assertFailure(
            LegalManifestValidation<DryRunResult> validation,
            LegalManifestStatus expectedStatus,
            LegalManifestIssueCode expectedCode) {
        assertThat(validation.status()).isEqualTo(expectedStatus);
        assertThat(validation.value()).isEmpty();
        assertThat(validation.issues())
                .extracting(LegalManifestIssue::code)
                .containsExactly(expectedCode);
    }

    private Map<String, Long> requiredTableCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : new TreeSet<>(LegalV27SchemaVerifier.requiredTables())) {
            Long count = jdbc.queryForObject(
                    "SELECT count(*) FROM " + quoteIdentifier(table),
                    Long.class);
            counts.put(table, Objects.requireNonNull(count, "row count"));
        }
        return Map.copyOf(counts);
    }

    private void dropTestObjects() {
        if (!POSTGRES.isRunning()) {
            return;
        }
        JdbcTemplate cleanup = directJdbc();
        cleanup.execute("""
                DROP TRIGGER IF EXISTS zz_legal_dry_run_test_deferred_failure
                    ON legal_publicaciones
                """);
        cleanup.execute("""
                DROP TRIGGER IF EXISTS zz_legal_dry_run_test_slow_insert
                    ON legal_publicaciones
                """);
        cleanup.execute("""
                DROP TRIGGER IF EXISTS zz_legal_dry_run_test_new_line_gate
                    ON legal_documento_lineas
                """);
        cleanup.execute("""
                DROP TRIGGER IF EXISTS zz_legal_dry_run_test_disconnect_pause
                    ON legal_publicaciones
                """);
        cleanup.execute("DROP FUNCTION IF EXISTS legal_dry_run_test_deferred_failure()");
        cleanup.execute("DROP FUNCTION IF EXISTS legal_dry_run_test_slow_insert()");
        cleanup.execute("DROP FUNCTION IF EXISTS legal_dry_run_test_new_line_gate()");
        cleanup.execute("DROP FUNCTION IF EXISTS legal_dry_run_test_disconnect_pause()");
        cleanup.execute("DROP SEQUENCE IF EXISTS legal_dry_run_test_deferred_seq");
    }

    private static AnnotationConfigApplicationContext context() {
        AnnotationConfigApplicationContext applicationContext =
                new AnnotationConfigApplicationContext();
        Map<String, Object> properties = Map.of(
                LegalDryRunDatabaseConfiguration.ENABLED_PROPERTY, "true",
                "spring.datasource.url", dryRunJdbcUrl(),
                "spring.datasource.username", POSTGRES.getUsername(),
                "spring.datasource.password", POSTGRES.getPassword(),
                "spring.datasource.driver-class-name", POSTGRES.getDriverClassName());
        applicationContext.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("legal-dry-run-concurrency-it", properties));
        applicationContext.register(LegalDryRunDatabaseConfiguration.class);
        applicationContext.refresh();
        return applicationContext;
    }

    private static JdbcTemplate directJdbc() {
        return new JdbcTemplate(directDataSource());
    }

    private static DataSource directDataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        dataSource.setDriverClassName(POSTGRES.getDriverClassName());
        return dataSource;
    }

    private static String dryRunJdbcUrl() {
        String separator = POSTGRES.getJdbcUrl().contains("?") ? "&" : "?";
        return POSTGRES.getJdbcUrl()
                + separator
                + "ApplicationName="
                + DRY_RUN_APPLICATION_NAME;
    }

    private static Path goldenManifest() throws URISyntaxException {
        return Path.of(Objects.requireNonNull(
                LegalManifestDryRunConcurrencyIT.class.getResource(GOLDEN_MANIFEST)).toURI());
    }

    private static String quoteIdentifier(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    private record HistoricalDocument(UUID lineId, UUID versionId) { }
}
