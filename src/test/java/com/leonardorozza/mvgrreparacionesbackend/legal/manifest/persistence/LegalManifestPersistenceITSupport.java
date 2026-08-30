package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidation;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialStateFingerprintCalculator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetRevisionCalculator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestDryRunService.DryRunResult;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Shared, test-only assembly and PostgreSQL probes for the legal persistence ITs. */
final class LegalManifestPersistenceITSupport {

    static final String GOLDEN_MANIFEST =
            "/legal/manifest/release-valid-v1/publication-manifest.json";
    static final Duration DATABASE_POLL_TIMEOUT = Duration.ofSeconds(10);
    static final Duration DATABASE_POLL_INTERVAL = Duration.ofMillis(25);

    private static final List<String> IMPORT_SEQUENCES = List.of(
            "legal_documento_contextos_id_seq",
            "legal_publicacion_documentos_id_seq",
            "legal_requisito_audiencias_id_seq",
            "legal_requisito_documentos_id_seq",
            "legal_publicacion_requisitos_id_seq",
            "legal_requisito_conjunto_miembros_id_seq");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LegalManifestPersistenceITSupport() { }

    static void migrate(PostgreSQLContainer postgres) {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    static HikariDataSource pooledDataSource(
            PostgreSQLContainer postgres,
            String applicationName) {
        HikariConfig configuration = new HikariConfig();
        configuration.setPoolName(applicationName + "-pool");
        configuration.setJdbcUrl(namedJdbcUrl(postgres, applicationName));
        configuration.setUsername(postgres.getUsername());
        configuration.setPassword(postgres.getPassword());
        configuration.setDriverClassName(postgres.getDriverClassName());
        configuration.setMinimumIdle(0);
        configuration.setMaximumPoolSize(6);
        configuration.setConnectionTimeout(TimeUnit.SECONDS.toMillis(5));
        return new HikariDataSource(configuration);
    }

    static DataSource directDataSource(
            PostgreSQLContainer postgres,
            String applicationName) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(namedJdbcUrl(postgres, applicationName));
        dataSource.setUsername(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        dataSource.setDriverClassName(postgres.getDriverClassName());
        return dataSource;
    }

    static Harness harness(DataSource dataSource, LegalDatabaseBudgets budgets) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
        manager.setRollbackOnCommitFailure(false);
        TransactionTemplate transaction = new TransactionTemplate(manager);
        transaction.setName("legal-manifest-import-hardening-it");
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(budgets.transactionTimeoutSeconds());
        transaction.setReadOnly(false);

        LegalRequiredSetRevisionCalculator calculator =
                new LegalRequiredSetRevisionCalculator();
        LegalManifestGraphWriter writer = new LegalManifestGraphWriter(jdbc, calculator);
        LegalManifestReplayVerifier replayVerifier =
                new LegalManifestReplayVerifier(jdbc, calculator);
        LegalManifestDatabaseGate gate = new LegalManifestDatabaseGate(
                transaction,
                jdbc,
                budgets,
                List.of());
        LegalManifestImportService importService = new LegalManifestImportService(
                gate,
                jdbc,
                writer,
                replayVerifier,
                new LegalImportFailureMapper());
        LegalManifestDryRunService dryRunService = new LegalManifestDryRunService(
                gate,
                jdbc,
                writer,
                new LegalDatabaseFailureMapper());
        return new Harness(
                dataSource,
                jdbc,
                transaction,
                gate,
                writer,
                replayVerifier,
                importService,
                dryRunService);
    }

    static ReadinessHarness readinessHarness(
            DataSource dataSource,
            LegalDatabaseBudgets budgets) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
        manager.setRollbackOnCommitFailure(false);
        TransactionTemplate transaction = new TransactionTemplate(manager);
        transaction.setName("legal-editorial-readiness-it");
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(budgets.transactionTimeoutSeconds());
        transaction.setReadOnly(true);

        LegalRequiredSetRevisionCalculator revisionCalculator =
                new LegalRequiredSetRevisionCalculator();
        LegalEditorialSchemaVerifier schemaVerifier =
                new LegalEditorialSchemaVerifier(jdbc, "public");
        LegalEditorialPrivilegeVerifier privilegeVerifier = mock(
                LegalEditorialPrivilegeVerifier.class);
        when(privilegeVerifier.usesJdbc(jdbc)).thenReturn(true);
        LegalManifestOriginGraphVerifier originVerifier =
                new LegalManifestOriginGraphVerifier(jdbc, revisionCalculator);
        LegalEditorialReadinessCore core = new LegalEditorialReadinessCore(
                jdbc,
                originVerifier,
                revisionCalculator,
                new LegalEditorialStateFingerprintCalculator());
        LegalManifestDatabaseGate gate = new LegalManifestDatabaseGate(
                transaction,
                jdbc,
                budgets,
                List.of(schemaVerifier, privilegeVerifier));
        LegalEditorialReadinessService service = new LegalEditorialReadinessService(
                gate,
                jdbc,
                core,
                new LegalEditorialFailureMapper(),
                schemaVerifier,
                privilegeVerifier);
        return new ReadinessHarness(
                dataSource,
                jdbc,
                transaction,
                gate,
                schemaVerifier,
                privilegeVerifier,
                originVerifier,
                core,
                service);
    }

    static PlannerHarness plannerHarness(
            DataSource dataSource,
            LegalDatabaseBudgets budgets) {
        ReadinessHarness readiness = readinessHarness(dataSource, budgets);
        LegalEditorialPlannerCore plannerCore = new LegalEditorialPlannerCore(
                readiness.jdbc(),
                readiness.core(),
                readiness.originVerifier());
        LegalEditorialPlanService plannerService = new LegalEditorialPlanService(
                readiness.gate(),
                readiness.jdbc(),
                plannerCore,
                new LegalEditorialReplaceScopeGuard(),
                new LegalEditorialFailureMapper(),
                readiness.schemaVerifier(),
                readiness.privilegeVerifier());
        return new PlannerHarness(
                readiness.dataSource(),
                readiness.jdbc(),
                readiness.transaction(),
                readiness.gate(),
                readiness.schemaVerifier(),
                readiness.privilegeVerifier(),
                readiness.originVerifier(),
                readiness.core(),
                readiness.service(),
                plannerCore,
                plannerService);
    }

    static ApplyHarness applyHarness(
            DataSource dataSource,
            LegalDatabaseBudgets budgets) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        LegalEditorialSchemaVerifier schemaVerifier =
                new LegalEditorialSchemaVerifier(jdbc, LegalV27EditorialInventory.DEFAULT_SCHEMA);
        LegalEditorialPrivilegeVerifier privilegeVerifier = mock(
                LegalEditorialPrivilegeVerifier.class);
        when(privilegeVerifier.usesJdbc(jdbc)).thenReturn(true);
        return applyHarness(jdbc, budgets, schemaVerifier, privilegeVerifier);
    }

    static ApplyHarness restrictedApplyHarness(
            DataSource dataSource,
            LegalDatabaseBudgets budgets,
            String username) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        LegalEditorialSchemaVerifier schemaVerifier =
                new LegalEditorialSchemaVerifier(jdbc, LegalV27EditorialInventory.DEFAULT_SCHEMA);
        LegalEditorialPrivilegeVerifier privilegeVerifier =
                new LegalEditorialPrivilegeVerifier(
                        jdbc,
                        username,
                        LegalV27EditorialInventory.DEFAULT_SCHEMA);
        return applyHarness(jdbc, budgets, schemaVerifier, privilegeVerifier);
    }

    static ApplyHarness applyHarness(
            JdbcTemplate jdbc,
            LegalDatabaseBudgets budgets,
            LegalEditorialSchemaVerifier schemaVerifier,
            LegalEditorialPrivilegeVerifier privilegeVerifier) {
        Objects.requireNonNull(jdbc, "jdbc");
        Objects.requireNonNull(budgets, "budgets");
        Objects.requireNonNull(schemaVerifier, "schemaVerifier");
        Objects.requireNonNull(privilegeVerifier, "privilegeVerifier");
        DataSource dataSource = Objects.requireNonNull(
                jdbc.getDataSource(),
                "jdbc dataSource");
        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
        manager.setRollbackOnCommitFailure(false);
        TransactionTemplate transaction = new TransactionTemplate(manager);
        transaction.setName("legal-editorial-apply-it");
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(budgets.transactionTimeoutSeconds());
        transaction.setReadOnly(false);

        LegalRequiredSetRevisionCalculator revisionCalculator =
                new LegalRequiredSetRevisionCalculator();
        LegalManifestOriginGraphVerifier originVerifier =
                new LegalManifestOriginGraphVerifier(jdbc, revisionCalculator);
        LegalEditorialReadinessCore readinessCore = new LegalEditorialReadinessCore(
                jdbc,
                originVerifier,
                revisionCalculator,
                new LegalEditorialStateFingerprintCalculator());
        LegalEditorialPlannerCore plannerCore = new LegalEditorialPlannerCore(
                jdbc,
                readinessCore,
                originVerifier);
        LegalInitialPromotionCore promotionCore = new LegalInitialPromotionCore(jdbc);
        LegalDocumentReplacementWriter replacementWriter =
                new LegalDocumentReplacementWriter(jdbc, readinessCore);
        LegalEditorialRetirementWriter retirementWriter =
                new LegalEditorialRetirementWriter(jdbc, readinessCore);
        LegalEditorialReplaceScopeGuard replaceScopeGuard =
                new LegalEditorialReplaceScopeGuard();
        LegalEditorialPostStateVerifier postStateVerifier =
                new LegalEditorialPostStateVerifier(jdbc);
        LegalManifestDatabaseGate gate = new LegalManifestDatabaseGate(
                transaction,
                jdbc,
                budgets,
                List.of(schemaVerifier, privilegeVerifier));
        LegalEditorialApplyService service = new LegalEditorialApplyService(
                gate,
                jdbc,
                plannerCore,
                promotionCore,
                replacementWriter,
                retirementWriter,
                postStateVerifier,
                readinessCore,
                replaceScopeGuard,
                new LegalEditorialFailureMapper(),
                schemaVerifier,
                privilegeVerifier);
        return new ApplyHarness(
                dataSource,
                jdbc,
                transaction,
                gate,
                schemaVerifier,
                privilegeVerifier,
                originVerifier,
                readinessCore,
                plannerCore,
                promotionCore,
                replacementWriter,
                retirementWriter,
                replaceScopeGuard,
                postStateVerifier,
                service);
    }

    static void promoteToReady(JdbcTemplate jdbc, UUID publicationId) {
        Objects.requireNonNull(jdbc, "jdbc");
        Objects.requireNonNull(publicationId, "publicationId");
        DataSourceTransactionManager manager = new DataSourceTransactionManager(
                Objects.requireNonNull(jdbc.getDataSource(), "dataSource"));
        TransactionTemplate transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(LegalDatabaseBudgets.production().transactionTimeoutSeconds());
        transaction.executeWithoutResult(status -> {
            jdbc.queryForList("""
                    SELECT pg_catalog.pg_advisory_xact_lock(
                        pg_catalog.hashtextextended(?, 0)
                    )
                    """, LegalManifestDatabaseGate.EDITORIAL_LOCK_NAME);
            OffsetDateTime occurredAt = Objects.requireNonNull(jdbc.queryForObject(
                    "SELECT transaction_timestamp()",
                    OffsetDateTime.class));
            jdbc.update("""
                    INSERT INTO legal_documento_transiciones
                        (documento_version_id, estado_anterior, estado_nuevo,
                         ocurrido_en)
                    SELECT pd.documento_version_id, 'BORRADOR', 'PUBLICADA', ?
                      FROM legal_publicacion_documentos pd
                     WHERE pd.publicacion_id = ?
                     ORDER BY pd.manifest_ordinal
                    """, occurredAt, publicationId);
            jdbc.update("""
                    INSERT INTO legal_documento_transiciones
                        (documento_version_id, estado_anterior, estado_nuevo,
                         ocurrido_en)
                    SELECT pd.documento_version_id, 'PUBLICADA', 'VIGENTE', ?
                      FROM legal_publicacion_documentos pd
                     WHERE pd.publicacion_id = ?
                     ORDER BY pd.manifest_ordinal
                    """, occurredAt, publicationId);
            jdbc.update("""
                    INSERT INTO legal_requisito_transiciones
                        (requisito_version_id, estado_anterior, estado_nuevo,
                         ocurrido_en)
                    SELECT pr.requisito_version_id, 'BORRADOR', 'PUBLICADA', ?
                      FROM legal_publicacion_requisitos pr
                     WHERE pr.publicacion_id = ?
                     ORDER BY pr.manifest_ordinal
                    """, occurredAt, publicationId);
            jdbc.update("""
                    INSERT INTO legal_requisito_transiciones
                        (requisito_version_id, estado_anterior, estado_nuevo,
                         ocurrido_en)
                    SELECT pr.requisito_version_id, 'PUBLICADA', 'VIGENTE', ?
                      FROM legal_publicacion_requisitos pr
                     WHERE pr.publicacion_id = ?
                     ORDER BY pr.manifest_ordinal
                    """, occurredAt, publicationId);
            jdbc.update("""
                    INSERT INTO legal_documento_vigentes
                        (tipo, locale, contexto, documento_version_id,
                         documento_linea_id, publicacion_id, estado_documento)
                    SELECT dl.tipo, dl.locale, dc.contexto, dv.id,
                           dv.documento_linea_id, pd.publicacion_id, 'VIGENTE'
                      FROM legal_publicacion_documentos pd
                      JOIN legal_documento_versiones dv
                        ON dv.id = pd.documento_version_id
                      JOIN legal_documento_lineas dl
                        ON dl.id = dv.documento_linea_id
                      JOIN legal_documento_contextos dc
                        ON dc.documento_version_id = dv.id
                     WHERE pd.publicacion_id = ?
                     ORDER BY dl.tipo, dl.locale, dc.contexto
                    """, publicationId);
            jdbc.update("""
                    INSERT INTO legal_requisito_conjuntos_actuales
                        (locale, contexto, audiencia, conjunto_id,
                         publicacion_id, actualizado_en)
                    SELECT locale, contexto, audiencia, id,
                           publicacion_id, ?
                      FROM legal_requisito_conjuntos
                     WHERE publicacion_id = ?
                     ORDER BY locale, contexto, audiencia
                    """, occurredAt, publicationId);
            jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
        });
    }

    static void withReplicaRole(JdbcTemplate jdbc, Runnable mutation) {
        Objects.requireNonNull(jdbc, "jdbc");
        Objects.requireNonNull(mutation, "mutation");
        DataSourceTransactionManager manager = new DataSourceTransactionManager(
                Objects.requireNonNull(jdbc.getDataSource(), "dataSource"));
        TransactionTemplate transaction = new TransactionTemplate(manager);
        transaction.executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            mutation.run();
        });
    }

    static ValidatedRelease goldenRelease(Class<?> resourceAnchor) throws URISyntaxException {
        return validate(goldenManifest(resourceAnchor));
    }

    static ValidatedRelease copyRelease(
            Path temporaryDirectory,
            Class<?> resourceAnchor,
            String publicationId,
            ReleaseMutation mutation) throws Exception {
        Objects.requireNonNull(mutation, "mutation");
        Path sourceRoot = goldenManifest(resourceAnchor).getParent();
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
        mutation.apply(manifestPath, manifest);
        Files.write(
                manifestPath,
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
        return validate(manifestPath);
    }

    static void replaceTermsWithConflictingValidContent(
            Path manifestPath,
            ObjectNode manifest) throws Exception {
        String markdown = """
                # Términos alternativos de OrdenFix

                Contenido jurídico alternativo para acreditar un conflicto persistido.
                """;
        Files.writeString(
                manifestPath.getParent().resolve("terminos.md"),
                markdown,
                StandardCharsets.UTF_8);
        document(manifest, "terminos").put("sha256", sha256(markdown));
    }

    static void cleanLegalState(JdbcTemplate jdbc) {
        jdbc.execute("""
                TRUNCATE TABLE legal_publicaciones, legal_documento_reemplazo_lotes
                RESTART IDENTITY CASCADE
                """);
    }

    static Map<String, Long> requiredTableCounts(JdbcTemplate jdbc) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : new TreeSet<>(LegalV27SchemaVerifier.requiredTables())) {
            Long count = jdbc.queryForObject(
                    "SELECT count(*) FROM " + quoteIdentifier(table),
                    Long.class);
            counts.put(table, Objects.requireNonNull(count, "row count"));
        }
        return Map.copyOf(counts);
    }

    static Map<String, SequenceState> sequenceStates(JdbcTemplate jdbc) {
        Map<String, SequenceState> states = new LinkedHashMap<>();
        for (String sequence : IMPORT_SEQUENCES) {
            SequenceState state = jdbc.queryForObject(
                    "SELECT last_value, is_called FROM " + quoteIdentifier(sequence),
                    (resultSet, rowNumber) -> new SequenceState(
                            resultSet.getLong("last_value"),
                            resultSet.getBoolean("is_called")));
            states.put(sequence, Objects.requireNonNull(state, "sequence state"));
        }
        return Map.copyOf(states);
    }

    static Map<String, Long> editorialTableCounts(JdbcTemplate jdbc) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : new TreeSet<>(LegalV27EditorialInventory.EDITORIAL_TABLES)) {
            Long count = jdbc.queryForObject(
                    "SELECT count(*) FROM " + quoteIdentifier(table),
                    Long.class);
            counts.put(table, Objects.requireNonNull(count, "row count"));
        }
        return Map.copyOf(counts);
    }

    static Map<String, SequenceState> editorialSequenceStates(JdbcTemplate jdbc) {
        Map<String, SequenceState> states = new LinkedHashMap<>();
        for (String sequence : new TreeSet<>(
                LegalV27EditorialInventory.IDENTITY_SEQUENCES.keySet())) {
            SequenceState state = jdbc.queryForObject(
                    "SELECT last_value, is_called FROM " + quoteIdentifier(sequence),
                    (resultSet, rowNumber) -> new SequenceState(
                            resultSet.getLong("last_value"),
                            resultSet.getBoolean("is_called")));
            states.put(sequence, Objects.requireNonNull(state, "sequence state"));
        }
        return Map.copyOf(states);
    }

    static PublicationGraphCounts publicationGraphCounts(
            JdbcTemplate jdbc,
            UUID publicationId) {
        Integer documents = jdbc.queryForObject("""
                SELECT count(*)::integer
                  FROM legal_publicacion_documentos
                 WHERE publicacion_id = ?
                """, Integer.class, publicationId);
        Integer newDocumentLines = jdbc.queryForObject("""
                SELECT count(*) FILTER (
                           WHERE dl.publicacion_intro_id = ?)::integer
                  FROM legal_publicacion_documentos pd
                  JOIN legal_documento_versiones dv
                    ON dv.id = pd.documento_version_id
                  JOIN legal_documento_lineas dl
                    ON dl.id = dv.documento_linea_id
                 WHERE pd.publicacion_id = ?
                """, Integer.class, publicationId, publicationId);
        Integer newDocumentVersions = jdbc.queryForObject("""
                SELECT count(*) FILTER (
                           WHERE dv.publicacion_intro_id = ?)::integer
                  FROM legal_publicacion_documentos pd
                  JOIN legal_documento_versiones dv
                    ON dv.id = pd.documento_version_id
                 WHERE pd.publicacion_id = ?
                """, Integer.class, publicationId, publicationId);
        Integer requirements = jdbc.queryForObject("""
                SELECT count(*)::integer
                  FROM legal_publicacion_requisitos
                 WHERE publicacion_id = ?
                """, Integer.class, publicationId);
        Integer newRequirementLines = jdbc.queryForObject("""
                SELECT count(*) FILTER (
                           WHERE rl.publicacion_intro_id = ?)::integer
                  FROM legal_publicacion_requisitos pr
                  JOIN legal_requisito_versiones rv
                    ON rv.id = pr.requisito_version_id
                  JOIN legal_requisito_lineas rl
                    ON rl.id = rv.requisito_linea_id
                 WHERE pr.publicacion_id = ?
                """, Integer.class, publicationId, publicationId);
        Integer newRequirementVersions = jdbc.queryForObject("""
                SELECT count(*) FILTER (
                           WHERE rv.publicacion_intro_id = ?)::integer
                  FROM legal_publicacion_requisitos pr
                  JOIN legal_requisito_versiones rv
                    ON rv.id = pr.requisito_version_id
                 WHERE pr.publicacion_id = ?
                """, Integer.class, publicationId, publicationId);
        Integer scopes = jdbc.queryForObject("""
                SELECT count(*)::integer
                  FROM legal_requisito_conjuntos
                 WHERE publicacion_id = ?
                """, Integer.class, publicationId);

        int documentCount = Objects.requireNonNull(documents, "document count");
        int requirementCount = Objects.requireNonNull(requirements, "requirement count");
        int documentVersionCount = Objects.requireNonNull(
                newDocumentVersions,
                "new document version count");
        int requirementVersionCount = Objects.requireNonNull(
                newRequirementVersions,
                "new requirement version count");
        return new PublicationGraphCounts(
                documentCount,
                requirementCount,
                Objects.requireNonNull(scopes, "scope count"),
                Objects.requireNonNull(newDocumentLines, "new document line count"),
                documentVersionCount,
                documentCount - documentVersionCount,
                Objects.requireNonNull(newRequirementLines, "new requirement line count"),
                requirementVersionCount,
                requirementCount - requirementVersionCount);
    }

    static long publicationCount(JdbcTemplate jdbc) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT count(*) FROM legal_publicaciones",
                Long.class));
    }

    static long openPublicationCount(JdbcTemplate jdbc) {
        return Objects.requireNonNull(jdbc.queryForObject("""
                SELECT count(*)
                  FROM legal_publicaciones
                 WHERE estado_construccion = 'ABIERTO'
                """, Long.class));
    }

    static int blockedEditorialGateWaits(JdbcTemplate observer, String applicationName) {
        Integer count = observer.queryForObject("""
                SELECT count(*)::integer
                  FROM pg_catalog.pg_stat_activity
                 WHERE datname = pg_catalog.current_database()
                   AND application_name = ?
                   AND state = 'active'
                   AND wait_event_type = 'Lock'
                   AND query LIKE '%pg_advisory_xact_lock%'
                   AND query LIKE '%hashtextextended%'
                """, Integer.class, applicationName);
        return Objects.requireNonNull(count, "blocked editorial gate waits");
    }

    static Optional<Integer> blockedDocumentInsertPid(
            JdbcTemplate observer,
            String applicationName) {
        return observer.query("""
                SELECT pid
                  FROM pg_catalog.pg_stat_activity
                 WHERE datname = pg_catalog.current_database()
                   AND application_name = ?
                   AND state = 'active'
                   AND wait_event_type = 'Lock'
                   AND query LIKE '%INSERT INTO legal_documento_lineas%'
                 ORDER BY pid
                 LIMIT 1
                """, (resultSet, rowNumber) -> resultSet.getInt(1), applicationName)
                .stream()
                .findFirst();
    }

    static void awaitCondition(String description, BooleanSupplier condition) {
        long deadline = System.nanoTime() + DATABASE_POLL_TIMEOUT.toNanos();
        do {
            if (condition.getAsBoolean()) {
                return;
            }
            LockSupport.parkNanos(DATABASE_POLL_INTERVAL.toNanos());
        } while (System.nanoTime() < deadline);
        throw new AssertionError("No se observó a tiempo: " + description);
    }

    static <T> T awaitValue(String description, Supplier<Optional<T>> probe) {
        long deadline = System.nanoTime() + DATABASE_POLL_TIMEOUT.toNanos();
        do {
            Optional<T> value = probe.get();
            if (value.isPresent()) {
                return value.orElseThrow();
            }
            LockSupport.parkNanos(DATABASE_POLL_INTERVAL.toNanos());
        } while (System.nanoTime() < deadline);
        throw new AssertionError("No se observó a tiempo: " + description);
    }

    static void awaitLatch(CountDownLatch latch, String description) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("No se abrió a tiempo: " + description);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Espera interrumpida: " + description, interrupted);
        }
    }

    static void assertConfirmed(
            LegalManifestImportResult result,
            LegalManifestImportResult.Outcome outcome) {
        assertThat(result.status()).isEqualTo(LegalManifestStatus.PASS);
        assertThat(result.persisted()).isTrue();
        assertThat(result.outcome()).contains(outcome);
        assertThat(result.receipt()).isPresent();
        assertThat(result.issues()).isEmpty();
    }

    static void assertKnownFailure(
            LegalManifestImportResult result,
            LegalManifestStatus status,
            LegalManifestIssueCode code) {
        assertThat(result.status()).isEqualTo(status);
        assertThat(result.persisted()).isFalse();
        assertThat(result.outcome()).isEmpty();
        assertThat(result.receipt()).isEmpty();
        assertThat(result.issues())
                .extracting(LegalManifestIssue::code)
                .containsExactly(code);
    }

    static void assertUnknown(LegalManifestImportResult result) {
        assertThat(result.status()).isEqualTo(LegalManifestStatus.ERROR);
        assertThat(result.persisted()).isNull();
        assertThat(result.outcome()).contains(LegalManifestImportResult.Outcome.UNKNOWN);
        assertThat(result.receipt()).isEmpty();
        assertThat(result.issues())
                .extracting(LegalManifestIssue::code)
                .containsExactly(LegalManifestIssueCode.IMPORT_DB_COMMIT_UNKNOWN);
    }

    static void assertEditorialConfirmed(
            LegalEditorialApplyResult result,
            LegalEditorialApplyResult.Outcome outcome) {
        assertThat(result.status()).isEqualTo(LegalManifestStatus.PASS);
        assertThat(result.persisted()).isTrue();
        assertThat(result.outcome()).isEqualTo(outcome);
        assertThat(result.receipt()).isPresent();
        assertThat(result.issues()).isEmpty();
    }

    static void assertEditorialKnownFailure(
            LegalEditorialApplyResult result,
            LegalManifestStatus status,
            LegalManifestIssueCode code) {
        LegalEditorialApplyResult.Outcome expectedOutcome = switch (status) {
            case BLOCKED -> LegalEditorialApplyResult.Outcome.BLOCKED;
            case ERROR -> LegalEditorialApplyResult.Outcome.ERROR;
            case PASS -> throw new IllegalArgumentException(
                    "Un fallo editorial conocido no puede tener status PASS");
        };
        assertThat(result.status()).isEqualTo(status);
        assertThat(result.persisted()).isFalse();
        assertThat(result.outcome()).isEqualTo(expectedOutcome);
        assertThat(result.receipt()).isEmpty();
        assertThat(result.issues())
                .extracting(LegalManifestIssue::code)
                .containsExactly(code);
    }

    static void assertEditorialUnknown(LegalEditorialApplyResult result) {
        assertThat(result.status()).isEqualTo(LegalManifestStatus.ERROR);
        assertThat(result.persisted()).isNull();
        assertThat(result.outcome()).isEqualTo(LegalEditorialApplyResult.Outcome.UNKNOWN);
        assertThat(result.receipt()).isEmpty();
        assertThat(result.issues())
                .extracting(LegalManifestIssue::code)
                .containsExactly(LegalManifestIssueCode.COMMIT_OUTCOME_UNKNOWN);
    }

    static EditorialLockHolder holdEditorialLock(DataSource dataSource) throws SQLException {
        Objects.requireNonNull(dataSource, "dataSource");
        Connection connection = dataSource.getConnection();
        try {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT pg_catalog.pg_advisory_xact_lock(
                        pg_catalog.hashtextextended(?, 0)
                    )
                    """)) {
                statement.setString(1, LegalManifestDatabaseGate.EDITORIAL_LOCK_NAME);
                try (var result = statement.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.next()).isFalse();
                }
            }
            return new EditorialLockHolder(connection);
        } catch (RuntimeException | SQLException failure) {
            try {
                connection.rollback();
            } finally {
                connection.close();
            }
            throw failure;
        }
    }

    static void assertDryRunPass(LegalManifestValidation<DryRunResult> validation) {
        assertThat(validation.status())
                .as("issues=%s", validation.issues())
                .isEqualTo(LegalManifestStatus.PASS);
        assertThat(validation.value()).isPresent();
    }

    static UncooperativeLineWriter holdUncommittedDocumentLine(
            DataSource dataSource,
            String externalId,
            String documentKey) throws SQLException {
        Connection connection = dataSource.getConnection();
        try {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("SET LOCAL deadlock_timeout = '10s'");
            }
            UUID publicationId = UUID.randomUUID();
            try (PreparedStatement publication = connection.prepareStatement("""
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
                    """)) {
                publication.setObject(1, publicationId);
                publication.setString(2, externalId);
                publication.setString(3, "a".repeat(64));
                assertThat(publication.executeUpdate()).isEqualTo(1);
            }
            try (PreparedStatement line = connection.prepareStatement("""
                    INSERT INTO legal_documento_lineas
                        (id, clave, tipo, locale, publicacion_intro_id, creado_en)
                    VALUES (?, ?, 'TERMINOS_SERVICIO', 'es-AR', ?, CURRENT_TIMESTAMP)
                    """)) {
                line.setObject(1, UUID.randomUUID());
                line.setString(2, documentKey);
                line.setObject(3, publicationId);
                assertThat(line.executeUpdate()).isEqualTo(1);
            }
            return new UncooperativeLineWriter(connection);
        } catch (RuntimeException | SQLException failure) {
            try {
                connection.rollback();
            } finally {
                connection.close();
            }
            throw failure;
        }
    }

    private static ValidatedRelease validate(Path manifest) {
        LegalManifestValidation<ValidatedRelease> validation =
                new LegalManifestValidator().validate(manifest);
        assertThat(validation.status())
                .as("manifest=%s issues=%s", manifest.getFileName(), validation.issues())
                .isEqualTo(LegalManifestStatus.PASS);
        return validation.value().orElseThrow();
    }

    private static Path goldenManifest(Class<?> resourceAnchor) throws URISyntaxException {
        return Path.of(Objects.requireNonNull(
                resourceAnchor.getResource(GOLDEN_MANIFEST),
                "golden manifest resource").toURI());
    }

    private static ObjectNode document(ObjectNode manifest, String key) {
        for (var candidate : manifest.path("documents")) {
            if (key.equals(candidate.path("key").textValue())) {
                return (ObjectNode) candidate;
            }
        }
        throw new IllegalArgumentException("Documento de test inexistente");
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 no disponible en el test", impossible);
        }
    }

    private static String namedJdbcUrl(PostgreSQLContainer postgres, String applicationName) {
        String separator = postgres.getJdbcUrl().contains("?") ? "&" : "?";
        return postgres.getJdbcUrl() + separator + "ApplicationName=" + applicationName;
    }

    private static String quoteIdentifier(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    @FunctionalInterface
    interface ReleaseMutation {

        ReleaseMutation NONE = (manifestPath, manifest) -> { };

        void apply(Path manifestPath, ObjectNode manifest) throws Exception;
    }

    record Harness(
            DataSource dataSource,
            JdbcTemplate jdbc,
            TransactionTemplate transaction,
            LegalManifestDatabaseGate gate,
            LegalManifestGraphWriter writer,
            LegalManifestReplayVerifier replayVerifier,
            LegalManifestImportService importService,
            LegalManifestDryRunService dryRunService
    ) { }

    record ReadinessHarness(
            DataSource dataSource,
            JdbcTemplate jdbc,
            TransactionTemplate transaction,
            LegalManifestDatabaseGate gate,
            LegalEditorialSchemaVerifier schemaVerifier,
            LegalEditorialPrivilegeVerifier privilegeVerifier,
            LegalManifestOriginGraphVerifier originVerifier,
            LegalEditorialReadinessCore core,
            LegalEditorialReadinessService service
    ) { }

    record PlannerHarness(
            DataSource dataSource,
            JdbcTemplate jdbc,
            TransactionTemplate transaction,
            LegalManifestDatabaseGate gate,
            LegalEditorialSchemaVerifier schemaVerifier,
            LegalEditorialPrivilegeVerifier privilegeVerifier,
            LegalManifestOriginGraphVerifier originVerifier,
            LegalEditorialReadinessCore readinessCore,
            LegalEditorialReadinessService readinessService,
            LegalEditorialPlannerCore plannerCore,
            LegalEditorialPlanService plannerService
    ) { }

    record ApplyHarness(
            DataSource dataSource,
            JdbcTemplate jdbc,
            TransactionTemplate transaction,
            LegalManifestDatabaseGate gate,
            LegalEditorialSchemaVerifier schemaVerifier,
            LegalEditorialPrivilegeVerifier privilegeVerifier,
            LegalManifestOriginGraphVerifier originVerifier,
            LegalEditorialReadinessCore readinessCore,
            LegalEditorialPlannerCore plannerCore,
            LegalInitialPromotionCore promotionCore,
            LegalDocumentReplacementWriter replacementWriter,
            LegalEditorialRetirementWriter retirementWriter,
            LegalEditorialReplaceScopeGuard replaceScopeGuard,
            LegalEditorialPostStateVerifier postStateVerifier,
            LegalEditorialApplyService service
    ) { }

    record SequenceState(long lastValue, boolean called) { }

    record PublicationGraphCounts(
            int documents,
            int requirements,
            int scopes,
            int newDocumentLines,
            int newDocumentVersions,
            int reusedDocumentVersions,
            int newRequirementLines,
            int newRequirementVersions,
            int reusedRequirementVersions
    ) { }

    static final class UncooperativeLineWriter implements AutoCloseable {

        private final Connection connection;
        private boolean open = true;

        private UncooperativeLineWriter(Connection connection) {
            this.connection = connection;
        }

        void release() throws SQLException {
            if (!open) {
                return;
            }
            open = false;
            try {
                connection.rollback();
            } finally {
                connection.close();
            }
        }

        void acquireEditorialLock() throws SQLException {
            if (!open) {
                throw new IllegalStateException("El writer externo ya fue liberado");
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT pg_advisory_xact_lock(hashtextextended(?, 0))
                    """)) {
                statement.setString(1, LegalManifestDatabaseGate.EDITORIAL_LOCK_NAME);
                try (var result = statement.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.next()).isFalse();
                }
            }
        }

        @Override
        public void close() throws SQLException {
            release();
        }
    }

    static final class EditorialLockHolder implements AutoCloseable {

        private final Connection connection;
        private boolean open = true;

        private EditorialLockHolder(Connection connection) {
            this.connection = connection;
        }

        void release() throws SQLException {
            if (!open) {
                return;
            }
            open = false;
            try {
                connection.rollback();
            } finally {
                connection.close();
            }
        }

        @Override
        public void close() throws SQLException {
            release();
        }
    }

    /**
     * Test-only boundary that models a commit acknowledged by PostgreSQL but not by the caller.
     * The production transaction manager remains the exact, accredited Spring class.
     */
    static final class CommitAcknowledgementLostDataSource extends DelegatingDataSource {

        private final AtomicBoolean armed = new AtomicBoolean(true);

        CommitAcknowledgementLostDataSource(DataSource targetDataSource) {
            super(Objects.requireNonNull(targetDataSource, "targetDataSource"));
        }

        @Override
        public Connection getConnection() throws SQLException {
            return wrap(super.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return wrap(super.getConnection(username, password));
        }

        boolean armed() {
            return armed.get();
        }

        private Connection wrap(Connection target) {
            return (Connection) Proxy.newProxyInstance(
                    LegalManifestPersistenceITSupport.class.getClassLoader(),
                    new Class<?>[]{Connection.class},
                    (proxy, method, arguments) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return switch (method.getName()) {
                                case "equals" -> proxy == arguments[0];
                                case "hashCode" -> System.identityHashCode(proxy);
                                case "toString" -> "commit-acknowledgement-loss-connection";
                                default -> throw new IllegalStateException(
                                        "Método Object inesperado en el proxy JDBC");
                            };
                        }
                        if ("commit".equals(method.getName())
                                && method.getParameterCount() == 0
                                && armed.compareAndSet(true, false)) {
                            invoke(target, method, arguments);
                            throw new SQLException(
                                    "confirmación de commit perdida por el test",
                                    "08006");
                        }
                        return invoke(target, method, arguments);
                    });
        }

        private static Object invoke(
                Connection target,
                java.lang.reflect.Method method,
                Object[] arguments) throws Throwable {
            try {
                return method.invoke(target, arguments);
            } catch (InvocationTargetException invocationFailure) {
                throw invocationFailure.getCause();
            }
        }
    }
}
