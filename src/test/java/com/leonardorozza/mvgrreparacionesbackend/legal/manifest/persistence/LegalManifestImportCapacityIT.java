package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestLimits;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidation;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetRevisionCalculator;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class LegalManifestImportCapacityIT {

    private static final String GOLDEN_MANIFEST =
            "/legal/manifest/release-valid-v1/publication-manifest.json";
    private static final String IMPORT_ROLE = "ordenfix_legal_import_capacity_it";
    private static final String IMPORT_PASSWORD = "legal-capacity-password-must-not-leak";
    private static final int MAX_DOCUMENTS = LegalManifestLimits.MAX_DOCUMENTS;
    private static final int MAX_REQUIREMENTS = LegalManifestLimits.MAX_REQUIREMENTS;
    private static final int DOCUMENTS_PER_REQUIREMENT =
            LegalManifestLimits.MAX_DOCUMENTS_PER_REQUIREMENT;
    private static final String MAX_PUBLICATION_ID = "release-capacity-maximum-v1";
    private static final int MAX_SCOPES =
            ContextoLegal.values().length * AudienciaLegal.values().length;
    private static final int EXPECTED_DOCUMENT_ROWS_WITH_SENTINEL = MAX_DOCUMENTS + 1;
    private static final int EXTRA_CORRUPT_DOCUMENTS = 4_096;
    private static final long MAX_FRESH_ROUND_TRIPS = 1_000L;
    private static final long MAX_REPLAY_ROUND_TRIPS = 500L;
    private static final Duration ARTIFICIAL_ROUND_TRIP_DELAY = Duration.ofMillis(5);
    private static final Duration MAX_DELAYED_IMPORT = Duration.ofSeconds(70);
    private static final Duration MAX_STATEMENT = Duration.ofSeconds(30);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_manifest_capacity")
            .withUsername("ordenfix")
            .withPassword("ordenfix");

    @TempDir
    static Path temporaryDirectory;

    private static DataSource ownerDataSource;
    private static DataSource importDataSource;
    private static JdbcTemplate observer;
    private static ValidatedRelease maximumRelease;

    @BeforeAll
    static void migrateAndBuildMaximumRelease() throws Exception {
        Flyway.configure()
                .dataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        ownerDataSource = directDataSource(
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
        observer = new JdbcTemplate(ownerDataSource);
        LegalRestrictedImportRoleFixture.Credentials credentials =
                new LegalRestrictedImportRoleFixture(
                        observer,
                        POSTGRES.getJdbcUrl(),
                        IMPORT_ROLE,
                        IMPORT_PASSWORD,
                        POSTGRES.getDriverClassName())
                        .provisionAndVerify();
        importDataSource = directDataSource(
                credentials.username(),
                credentials.password());
        maximumRelease = createMaximumRelease();
        assertThat(observer.queryForObject(
                "SELECT current_setting('server_version_num')::integer / 10000",
                Integer.class)).isEqualTo(16);
    }

    @BeforeEach
    void cleanLegalState() {
        observer.execute("""
                TRUNCATE TABLE legal_publicaciones, legal_documento_reemplazo_lotes
                RESTART IDENTITY CASCADE
                """);
    }

    @Test
    void maximumReleaseMeasuresFreshImportAndReplayOnPostgres16() {
        ServiceFixture fixture = serviceFixture(Duration.ZERO);

        fixture.metrics().reset();
        TimedImport fresh = timedImport(fixture.service(), maximumRelease);
        MetricSnapshot freshMetrics = fixture.metrics().snapshot();

        fixture.metrics().reset();
        TimedImport replay = timedImport(fixture.service(), maximumRelease);
        MetricSnapshot replayMetrics = fixture.metrics().snapshot();

        assertConfirmed(fresh.result(), LegalManifestImportResult.Outcome.IMPORTED);
        assertConfirmed(replay.result(), LegalManifestImportResult.Outcome.ALREADY_IMPORTED);
        assertMaximumReceipt(fresh.result());
        assertThat(replay.result().receipt()).isEqualTo(fresh.result().receipt());
        assertThat(freshMetrics.roundTrips())
                .as("fresh duration=%s metrics=%s", fresh.elapsed(), freshMetrics)
                .isPositive()
                .isLessThanOrEqualTo(MAX_FRESH_ROUND_TRIPS);
        assertThat(replayMetrics.roundTrips())
                .as("replay duration=%s metrics=%s", replay.elapsed(), replayMetrics)
                .isPositive()
                .isLessThanOrEqualTo(MAX_REPLAY_ROUND_TRIPS)
                .isLessThan(freshMetrics.roundTrips());
    }

    @Test
    void fiveMillisecondRoundTripDelayKeepsMaximumImportInsideProductionMargin() {
        ServiceFixture fixture = serviceFixture(ARTIFICIAL_ROUND_TRIP_DELAY);

        fixture.metrics().reset();
        TimedImport fresh = timedImport(fixture.service(), maximumRelease);
        MetricSnapshot freshMetrics = fixture.metrics().snapshot();

        fixture.metrics().reset();
        TimedImport replay = timedImport(fixture.service(), maximumRelease);
        MetricSnapshot replayMetrics = fixture.metrics().snapshot();

        assertConfirmed(fresh.result(), LegalManifestImportResult.Outcome.IMPORTED);
        assertConfirmed(replay.result(), LegalManifestImportResult.Outcome.ALREADY_IMPORTED);
        assertArtificialDelayApplied(fresh, freshMetrics);
        assertArtificialDelayApplied(replay, replayMetrics);
        assertThat(fresh.elapsed())
                .as("fresh metrics=%s", freshMetrics)
                .isLessThan(MAX_DELAYED_IMPORT);
        assertThat(replay.elapsed())
                .as("replay metrics=%s", replayMetrics)
                .isLessThan(MAX_DELAYED_IMPORT);
        assertThat(freshMetrics.maximumStatementDuration())
                .isLessThan(MAX_STATEMENT);
        assertThat(replayMetrics.maximumStatementDuration())
                .isLessThan(MAX_STATEMENT);
    }

    @Test
    void oversizedPersistedRelationStopsAtExpectedPlusOneRows() throws Exception {
        ServiceFixture fixture = serviceFixture(Duration.ZERO);
        LegalManifestImportResult imported = fixture.service().importManifest(maximumRelease);
        assertConfirmed(imported, LegalManifestImportResult.Outcome.IMPORTED);
        UUID publicationId = imported.receipt().orElseThrow().publicationUuid();
        insertOversizedDocumentRelation(publicationId);
        observer.queryForList("SELECT legal_validar_publicacion_sellada(?)", publicationId);
        assertThat(observer.queryForObject("""
                SELECT count(*)
                  FROM legal_publicacion_documentos
                 WHERE publicacion_id = ?
                """, Integer.class, publicationId))
                .isEqualTo(MAX_DOCUMENTS + EXTRA_CORRUPT_DOCUMENTS);

        fixture.metrics().reset();
        LegalManifestImportResult replay = fixture.service().importManifest(maximumRelease);
        MetricSnapshot metrics = fixture.metrics().snapshot();

        assertThat(replay.status()).isEqualTo(LegalManifestStatus.BLOCKED);
        assertThat(replay.persisted()).isFalse();
        assertThat(replay.outcome()).isEmpty();
        assertThat(replay.receipt()).isEmpty();
        assertThat(replay.issues())
                .extracting(LegalManifestIssue::code)
                .containsExactly(LegalManifestIssueCode.IMPORT_DB_PERSISTED_CONFLICT);
        assertThat(metrics.rowsReadContaining(
                "SELECT pd.manifest_ordinal",
                "dl.id AS document_line_id"))
                .isEqualTo(EXPECTED_DOCUMENT_ROWS_WITH_SENTINEL);
    }

    private static ServiceFixture serviceFixture(Duration delay) {
        InstrumentedDataSource dataSource = new InstrumentedDataSource(importDataSource, delay);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
        manager.setRollbackOnCommitFailure(false);
        TransactionTemplate transaction = new TransactionTemplate(manager);
        transaction.setName("legal-manifest-capacity-it");
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(LegalDatabaseBudgets.production().transactionTimeoutSeconds());
        transaction.setReadOnly(false);
        LegalRequiredSetRevisionCalculator revisionCalculator =
                new LegalRequiredSetRevisionCalculator();
        LegalV27ImportSchemaVerifier schemaVerifier =
                new LegalV27ImportSchemaVerifier(jdbc, "public");
        LegalImportPrivilegeVerifier privilegeVerifier =
                new LegalImportPrivilegeVerifier(jdbc, IMPORT_ROLE, "public");
        LegalManifestDatabaseGate gate = new LegalManifestDatabaseGate(
                transaction,
                jdbc,
                LegalDatabaseBudgets.production(),
                List.of(schemaVerifier, privilegeVerifier));
        LegalManifestImportService service = new LegalManifestImportService(
                gate,
                jdbc,
                new LegalManifestGraphWriter(jdbc, revisionCalculator),
                new LegalManifestReplayVerifier(jdbc, revisionCalculator),
                new LegalImportFailureMapper(),
                schemaVerifier,
                privilegeVerifier);
        return new ServiceFixture(service, dataSource.metrics());
    }

    private static TimedImport timedImport(
            LegalManifestImportService service,
            ValidatedRelease release) {
        long started = System.nanoTime();
        LegalManifestImportResult result = service.importManifest(release);
        return new TimedImport(result, Duration.ofNanos(System.nanoTime() - started));
    }

    private static void assertArtificialDelayApplied(
            TimedImport execution,
            MetricSnapshot metrics) {
        assertThat(metrics.roundTrips()).isPositive();
        assertThat(metrics.maximumStatementDuration())
                .isGreaterThanOrEqualTo(ARTIFICIAL_ROUND_TRIP_DELAY);
        assertThat(execution.elapsed()).isGreaterThanOrEqualTo(
                ARTIFICIAL_ROUND_TRIP_DELAY.multipliedBy(metrics.roundTrips()));
    }

    private static ValidatedRelease createMaximumRelease() throws Exception {
        Path sourceRoot = goldenManifest().getParent();
        Path releaseRoot = temporaryDirectory.resolve(MAX_PUBLICATION_ID);
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
        ObjectNode manifest = (ObjectNode) JSON.readTree(Files.readAllBytes(manifestPath));
        manifest.put("publicationId", MAX_PUBLICATION_ID);
        ArrayNode documents = (ArrayNode) manifest.path("documents");
        ArrayNode allContexts = enumNames(ContextoLegal.values());
        for (int index = 0; index < documents.size(); index++) {
            ((ObjectNode) documents.get(index)).set("contexts", allContexts.deepCopy());
        }
        ObjectNode documentTemplate = ((ObjectNode) documents.get(0)).deepCopy();
        while (documents.size() < MAX_DOCUMENTS) {
            int index = documents.size();
            String key = "capacity-document-%03d".formatted(index);
            String source = key + ".md";
            String markdown = "# Documento de capacidad %03d%n%nContenido estable %03d.%n"
                    .formatted(index, index);
            Files.writeString(releaseRoot.resolve(source), markdown, StandardCharsets.UTF_8);
            ObjectNode document = documentTemplate.deepCopy();
            document.put("key", key);
            document.put("type", TipoDocumentoLegal.values()[
                    index % TipoDocumentoLegal.values().length].name());
            document.put("version", "1");
            document.put("source", source);
            document.put("sha256", sha256(markdown));
            document.set("contexts", allContexts.deepCopy());
            documents.add(document);
        }

        ArrayNode requirements = (ArrayNode) manifest.path("requirements");
        int originalRequirements = requirements.size();
        ObjectNode requirementTemplate = ((ObjectNode) requirements.get(0)).deepCopy();
        while (requirements.size() < MAX_REQUIREMENTS) {
            int index = requirements.size();
            String statement = "Acepto el requisito de capacidad %03d.".formatted(index);
            ObjectNode requirement = requirementTemplate.deepCopy();
            requirement.put("key", "capacity-requirement-%03d".formatted(index));
            requirement.put("version", "1");
            requirement.put("context", ContextoLegal.values()[
                    index % ContextoLegal.values().length].name());
            requirement.set("roles", enumNames(AudienciaLegal.values()));
            requirement.put("actType", "ACEPTACION");
            requirement.put("statement", statement);
            requirement.put("statementSha256", sha256(statement));
            requirement.set("documents", documentReferences(index));
            requirement.put("required", true);
            requirement.put("requiresReacceptance", false);
            requirements.add(requirement);
        }
        for (int index = 0; index < originalRequirements; index++) {
            ObjectNode requirement = (ObjectNode) requirements.get(index);
            requirement.set("roles", enumNames(AudienciaLegal.values()));
            requirement.set("documents", documentReferences(0));
        }

        Files.write(
                manifestPath,
                JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
        LegalManifestValidation<ValidatedRelease> validation =
                new LegalManifestValidator().validate(manifestPath);
        assertThat(validation.status())
                .as("issues=%s", validation.issues())
                .isEqualTo(LegalManifestStatus.PASS);
        ValidatedRelease release = validation.value().orElseThrow();
        assertThat(release.documentCount()).isEqualTo(MAX_DOCUMENTS);
        assertThat(release.requirementCount()).isEqualTo(MAX_REQUIREMENTS);
        assertThat(release.scopeCount()).isEqualTo(MAX_SCOPES);
        assertThat(release.plan().manifest().requirements())
                .allSatisfy(requirement -> assertThat(requirement.documents())
                        .hasSize(DOCUMENTS_PER_REQUIREMENT));
        return release;
    }

    private static ArrayNode documentReferences(int requirementIndex) {
        ArrayNode references = JSON.createArrayNode();
        int start = Math.floorMod(requirementIndex, MAX_DOCUMENTS / DOCUMENTS_PER_REQUIREMENT)
                * DOCUMENTS_PER_REQUIREMENT;
        for (int offset = 0; offset < DOCUMENTS_PER_REQUIREMENT; offset++) {
            int documentIndex = start + offset;
            if (documentIndex < 11) {
                references.add(maximumGoldenDocumentKey(documentIndex));
            } else {
                references.add("capacity-document-%03d".formatted(documentIndex));
            }
        }
        return references;
    }

    private static String maximumGoldenDocumentKey(int index) {
        return switch (index) {
            case 0 -> "terminos";
            case 1 -> "privacidad";
            case 2 -> "tratamiento-datos";
            case 3 -> "condiciones-pro";
            case 4 -> "cancelaciones-reembolsos";
            case 5 -> "cierre-cuenta";
            case 6 -> "aviso-clientes-taller";
            case 7 -> "terminos-usuario";
            case 8 -> "aviso-privacidad-usuario";
            case 9 -> "compromiso-confidencialidad";
            case 10 -> "atestacion-datos-cliente";
            default -> throw new IllegalArgumentException("Índice golden fuera de rango");
        };
    }

    private static <E extends Enum<E>> ArrayNode enumNames(E[] values) {
        ArrayNode names = JSON.createArrayNode();
        Arrays.stream(values).map(Enum::name).forEach(names::add);
        return names;
    }

    private static void insertOversizedDocumentRelation(UUID publicationId) throws SQLException {
        try (Connection connection = ownerDataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SET LOCAL session_replication_role = replica");
                }
                executeGeneratedInsert(connection, """
                        INSERT INTO legal_documento_lineas
                            (id, clave, tipo, locale, publicacion_intro_id, creado_en)
                        SELECT md5('capacity-extra-line-' || g::text)::uuid,
                               'capacity-extra-' || g::text,
                               'TERMINOS_SERVICIO', 'es-AR', ?, CURRENT_TIMESTAMP
                          FROM generate_series(1, ?) AS g
                        """, publicationId);
                executeGeneratedInsert(connection, """
                        INSERT INTO legal_documento_versiones
                            (id, documento_linea_id, publicacion_intro_id, version,
                             lineage_ordinal, titulo, contenido_markdown, sha256,
                             vigente_desde, requires_reacceptance)
                        SELECT md5('capacity-extra-version-' || g::text)::uuid,
                               md5('capacity-extra-line-' || g::text)::uuid,
                               ?, '1', 1, 'Documento extra', '# Documento extra',
                               repeat('0', 64), TIMESTAMPTZ '2026-08-25T00:00:00Z', false
                          FROM generate_series(1, ?) AS g
                        """, publicationId);
                executeGeneratedInsert(connection, """
                        INSERT INTO legal_documento_contextos
                            (documento_version_id, contexto)
                        SELECT md5('capacity-extra-version-' || g::text)::uuid, 'REGISTRO'
                          FROM generate_series(1, ?) AS g
                        """, null);
                executeGeneratedInsert(connection, """
                        INSERT INTO legal_publicacion_documentos
                            (publicacion_id, documento_version_id, manifest_ordinal)
                        SELECT ?, md5('capacity-extra-version-' || g::text)::uuid, ? + g
                          FROM generate_series(1, ?) AS g
                        """, publicationId, MAX_DOCUMENTS);
                connection.commit();
            } catch (SQLException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private static void executeGeneratedInsert(
            Connection connection,
            String sql,
            UUID publicationId) throws SQLException {
        executeGeneratedInsert(connection, sql, publicationId, null);
    }

    private static void executeGeneratedInsert(
            Connection connection,
            String sql,
            UUID publicationId,
            Integer baseOrdinal) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int parameter = 1;
            if (publicationId != null) {
                statement.setObject(parameter++, publicationId);
            }
            if (baseOrdinal != null) {
                statement.setInt(parameter++, baseOrdinal);
            }
            statement.setInt(parameter, EXTRA_CORRUPT_DOCUMENTS);
            assertThat(statement.executeUpdate()).isEqualTo(EXTRA_CORRUPT_DOCUMENTS);
        }
    }

    private static void assertConfirmed(
            LegalManifestImportResult result,
            LegalManifestImportResult.Outcome outcome) {
        assertThat(result.status()).isEqualTo(LegalManifestStatus.PASS);
        assertThat(result.persisted()).isTrue();
        assertThat(result.outcome()).contains(outcome);
        assertThat(result.receipt()).isPresent();
        assertThat(result.issues()).isEmpty();
    }

    private static void assertMaximumReceipt(LegalManifestImportResult result) {
        LegalManifestImportResult.Receipt receipt = result.receipt().orElseThrow();
        assertThat(result.outcome()).contains(LegalManifestImportResult.Outcome.IMPORTED);
        assertThat(maximumRelease.documentCount()).isEqualTo(MAX_DOCUMENTS);
        assertThat(maximumRelease.requirementCount()).isEqualTo(MAX_REQUIREMENTS);
        assertThat(maximumRelease.scopeCount()).isEqualTo(MAX_SCOPES);
        assertThat(receipt.publicationUuid()).isNotNull();
    }

    private static Path goldenManifest() throws URISyntaxException {
        return Path.of(Objects.requireNonNull(
                LegalManifestImportCapacityIT.class.getResource(GOLDEN_MANIFEST)).toURI());
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException failure) {
            throw new AssertionError(failure);
        }
    }

    private static DataSource directDataSource(String username, String password) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        dataSource.setDriverClassName(POSTGRES.getDriverClassName());
        return dataSource;
    }

    private record ServiceFixture(
            LegalManifestImportService service,
            RoundTripMetrics metrics
    ) { }

    private record TimedImport(
            LegalManifestImportResult result,
            Duration elapsed
    ) { }

    private record MetricSnapshot(
            long roundTrips,
            Duration maximumStatementDuration,
            Map<String, Long> rowsRead
    ) {
        long rowsReadContaining(String... fragments) {
            return rowsRead.entrySet().stream()
                    .filter(entry -> Arrays.stream(fragments).allMatch(
                            entry.getKey()::contains))
                    .mapToLong(Map.Entry::getValue)
                    .sum();
        }
    }

    @FunctionalInterface
    private interface SqlInvocation {
        Object invoke() throws Throwable;
    }

    private static final class RoundTripMetrics {

        private final Duration delay;
        private final AtomicLong roundTrips = new AtomicLong();
        private final AtomicLong maximumStatementNanos = new AtomicLong();
        private final Map<String, AtomicLong> rowsRead = new ConcurrentHashMap<>();

        private RoundTripMetrics(Duration delay) {
            this.delay = Objects.requireNonNull(delay, "delay");
        }

        Object execute(String sql, boolean statement, SqlInvocation invocation) throws Throwable {
            roundTrips.incrementAndGet();
            long started = System.nanoTime();
            delay();
            try {
                return invocation.invoke();
            } finally {
                if (statement) {
                    maximumStatementNanos.accumulateAndGet(
                            System.nanoTime() - started,
                            Math::max);
                }
            }
        }

        void rowRead(String sql) {
            rowsRead.computeIfAbsent(normalize(sql), ignored -> new AtomicLong())
                    .incrementAndGet();
        }

        void reset() {
            roundTrips.set(0L);
            maximumStatementNanos.set(0L);
            rowsRead.clear();
        }

        MetricSnapshot snapshot() {
            Map<String, Long> immutableRows = rowsRead.entrySet().stream()
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(
                            Map.Entry::getKey,
                            entry -> entry.getValue().get()));
            return new MetricSnapshot(
                    roundTrips.get(),
                    Duration.ofNanos(maximumStatementNanos.get()),
                    immutableRows);
        }

        private void delay() throws SQLException {
            if (delay.isZero()) {
                return;
            }
            try {
                Thread.sleep(delay);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new SQLException("La medición de latencia fue interrumpida", interrupted);
            }
        }

        private static String normalize(String sql) {
            return sql == null ? "<unknown>" : sql.replaceAll("\\s+", " ").trim();
        }
    }

    private static final class InstrumentedDataSource implements DataSource {

        private final DataSource delegate;
        private final RoundTripMetrics metrics;

        private InstrumentedDataSource(DataSource delegate, Duration delay) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.metrics = new RoundTripMetrics(delay);
        }

        RoundTripMetrics metrics() {
            return metrics;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return instrument(delegate.getConnection(), metrics);
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return instrument(delegate.getConnection(username, password), metrics);
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return delegate.getLogWriter();
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
            delegate.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            delegate.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return delegate.getLoginTimeout();
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            if (iface.isInstance(this)) {
                return iface.cast(this);
            }
            return delegate.unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return iface.isInstance(this) || delegate.isWrapperFor(iface);
        }

        private static Connection instrument(Connection connection, RoundTripMetrics metrics) {
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class},
                    (proxy, method, arguments) -> {
                        if (isStatementFactory(method)) {
                            Object statement = invoke(connection, method, arguments);
                            String sql = arguments != null
                                    && arguments.length > 0
                                    && arguments[0] instanceof String value
                                    ? value
                                    : "<statement>";
                            return instrumentStatement((Statement) statement, sql, metrics);
                        }
                        if ("commit".equals(method.getName())
                                || "rollback".equals(method.getName())) {
                            return metrics.execute(
                                    '<' + method.getName() + '>',
                                    false,
                                    () -> invoke(connection, method, arguments));
                        }
                        return invoke(connection, method, arguments);
                    });
        }

        private static Statement instrumentStatement(
                Statement statement,
                String preparedSql,
                RoundTripMetrics metrics) {
            Class<?> statementType = statement instanceof CallableStatement
                    ? CallableStatement.class
                    : statement instanceof PreparedStatement
                            ? PreparedStatement.class
                            : Statement.class;
            return (Statement) Proxy.newProxyInstance(
                    statementType.getClassLoader(),
                    new Class<?>[]{statementType},
                    (proxy, method, arguments) -> {
                        String sql = sqlForInvocation(preparedSql, arguments);
                        if (isExecution(method)) {
                            Object result = metrics.execute(
                                    sql,
                                    true,
                                    () -> invoke(statement, method, arguments));
                            return result instanceof ResultSet rows
                                    ? instrumentResultSet(rows, sql, metrics)
                                    : result;
                        }
                        Object result = invoke(statement, method, arguments);
                        if (result instanceof ResultSet rows) {
                            return instrumentResultSet(rows, sql, metrics);
                        }
                        return result;
                    });
        }

        private static ResultSet instrumentResultSet(
                ResultSet resultSet,
                String sql,
                RoundTripMetrics metrics) {
            return (ResultSet) Proxy.newProxyInstance(
                    ResultSet.class.getClassLoader(),
                    new Class<?>[]{ResultSet.class},
                    (proxy, method, arguments) -> {
                        Object result = invoke(resultSet, method, arguments);
                        if ("next".equals(method.getName()) && Boolean.TRUE.equals(result)) {
                            metrics.rowRead(sql);
                        }
                        return result;
                    });
        }

        private static boolean isStatementFactory(Method method) {
            return "createStatement".equals(method.getName())
                    || "prepareStatement".equals(method.getName())
                    || "prepareCall".equals(method.getName());
        }

        private static boolean isExecution(Method method) {
            return switch (method.getName()) {
                case "execute", "executeQuery", "executeUpdate", "executeLargeUpdate",
                        "executeBatch", "executeLargeBatch" -> true;
                default -> false;
            };
        }

        private static String sqlForInvocation(String preparedSql, Object[] arguments) {
            if (arguments != null
                    && arguments.length > 0
                    && arguments[0] instanceof String dynamicSql) {
                return dynamicSql;
            }
            return preparedSql;
        }

        private static Object invoke(Object target, Method method, Object[] arguments)
                throws Throwable {
            try {
                return method.invoke(target, arguments);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        }
    }
}
