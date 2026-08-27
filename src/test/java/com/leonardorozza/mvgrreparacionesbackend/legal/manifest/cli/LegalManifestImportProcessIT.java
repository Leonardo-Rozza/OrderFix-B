package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli.LegalCliProcessSupport.Artifacts;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli.LegalCliProcessSupport.ProcessResult;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli.LegalCliProcessSupport.StdoutMode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidation;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalRestrictedImportRoleFixture;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalRestrictedImportRoleFixture.Credentials;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class LegalManifestImportProcessIT {

    private static final String GOLDEN_MANIFEST =
            "/legal/manifest/release-valid-v1/publication-manifest.json";
    private static final String IMPORT_ROLE = "ordenfix_legal_import_process_it";
    private static final String IMPORT_PASSWORD = "legal-process-password-must-not-leak";
    private static final String PROCESS_SECRET = "legal-process-secret-must-not-leak";
    private static final String LEGACY_DATASOURCE_SECRET =
            "legacy-spring-datasource-secret-must-not-leak";
    private static final String NORMAL_START_CLASS =
            "com.leonardorozza.mvgrreparacionesbackend.MvgrReparacionesBackendApplication";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> REPORT_FIELDS = List.of(
            "reportVersion",
            "command",
            "status",
            "persisted",
            "publication",
            "counts",
            "dryRun",
            "import",
            "issues",
            "omittedIssueCount");
    private static final List<String> PUBLICATION_FIELDS = List.of(
            "publicationId",
            "schemaVersion",
            "manifestSha256");
    private static final List<String> COUNT_FIELDS = List.of(
            "documents",
            "requirements",
            "scopes");
    private static final List<String> IMPORT_FIELDS = List.of(
            "outcome",
            "publicationUuid",
            "importedAt",
            "sealedAt",
            "sealed",
            "promotionChanged");
    private static final List<String> ISSUE_FIELDS = List.of(
            "severity",
            "code",
            "location",
            "message");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_manifest_import_process")
            .withUsername("ordenfix")
            .withPassword("ordenfix");

    private static Artifacts artifacts;
    private static JdbcTemplate owner;
    private static LegalRestrictedImportRoleFixture roleFixture;
    private static Credentials credentials;
    private static Connection observerConnection;

    @TempDir
    private Path temporaryDirectory;

    private Path copiedManifest;

    @BeforeAll
    static void packageDatabaseAndRestrictedRole() throws Exception {
        artifacts = LegalCliProcessSupport.locateArtifacts();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        owner = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        roleFixture = new LegalRestrictedImportRoleFixture(
                owner,
                POSTGRES.getJdbcUrl(),
                IMPORT_ROLE,
                IMPORT_PASSWORD,
                POSTGRES.getDriverClassName());
        credentials = roleFixture.provisionAndVerify();
        observerConnection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
    }

    @AfterAll
    static void closeObserver() throws Exception {
        if (observerConnection != null) {
            observerConnection.close();
        }
    }

    @BeforeEach
    void resetDatabaseAndCopyFixture() throws Exception {
        try (Statement statement = observerConnection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE legal_publicaciones, legal_documento_reemplazo_lotes
                    RESTART IDENTITY CASCADE
                    """);
        }
        Path sourceManifest = Path.of(Objects.requireNonNull(
                getClass().getResource(GOLDEN_MANIFEST)).toURI());
        Path sourceDirectory = sourceManifest.getParent();
        Path destinationDirectory = temporaryDirectory.resolve(sourceDirectory.getFileName());
        try (Stream<Path> sources = Files.walk(sourceDirectory)) {
            for (Path source : sources.toList()) {
                Path destination = destinationDirectory.resolve(sourceDirectory.relativize(source));
                if (Files.isDirectory(source)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(source, destination);
                }
            }
        }
        copiedManifest = destinationDirectory.resolve("publication-manifest.json");
        assertThat(copiedManifest).isRegularFile();
    }

    @Test
    void restrictedRoleImportsAndReplaysThroughThePackagedJar() throws Exception {
        ValidatedRelease release = validate(copiedManifest);

        JsonNode imported = assertImportReport(
                executeImport(release, List.of(), StdoutMode.CAPTURE),
                release,
                0,
                "PASS",
                true,
                "IMPORTED",
                null);
        assertThat(legalPublicationCount()).isEqualTo(1);
        assertThat(owner.queryForObject("""
                SELECT count(*)
                  FROM legal_publicaciones
                 WHERE estado_construccion = 'SELLADO'
                   AND sellado_en IS NOT NULL
                """, Integer.class)).isEqualTo(1);

        JsonNode replayed = assertImportReport(
                executeImport(release, List.of(), StdoutMode.CAPTURE),
                release,
                0,
                "PASS",
                true,
                "ALREADY_IMPORTED",
                null);

        assertSameReceipt(imported.path("import"), replayed.path("import"));
        assertThat(legalPublicationCount()).isEqualTo(1);
    }

    @Test
    void persistedConflictIsBlockedWithoutAnyDatabaseDelta() throws Exception {
        ValidatedRelease original = validate(copiedManifest);
        assertImportReport(
                executeImport(original, List.of(), StdoutMode.CAPTURE),
                original,
                0,
                "PASS",
                true,
                "IMPORTED",
                null);
        Map<String, Long> rowsBefore = legalTableCounts();

        ObjectNode manifest = (ObjectNode) JSON.readTree(Files.readAllBytes(copiedManifest));
        ((ObjectNode) manifest.path("publisherSnapshot"))
                .put("businessHours", "Lunes a viernes de 10:00 a 17:00");
        Files.write(
                copiedManifest,
                JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
        ValidatedRelease conflicting = validate(copiedManifest);

        assertImportReport(
                executeImport(conflicting, List.of(), StdoutMode.CAPTURE),
                conflicting,
                2,
                "BLOCKED",
                false,
                null,
                "IMPORT_DB_PERSISTED_CONFLICT");
        assertThat(legalTableCounts()).isEqualTo(rowsBefore);
    }

    @Test
    void privilegeDriftIsAKnownErrorAndNeverWrites() throws Exception {
        ValidatedRelease release = validate(copiedManifest);
        Map<String, Long> rowsBefore = legalTableCounts();
        owner.execute("GRANT DELETE ON legal_publicaciones TO " + quoted(IMPORT_ROLE));
        try {
            assertImportReport(
                    executeImport(release, List.of(), StdoutMode.CAPTURE),
                    release,
                    3,
                    "ERROR",
                    false,
                    null,
                    "IMPORT_DB_PRIVILEGES_INCOMPATIBLE");
        } finally {
            owner.execute("REVOKE DELETE ON legal_publicaciones FROM " + quoted(IMPORT_ROLE));
        }
        roleFixture.verify();
        assertThat(legalTableCounts()).isEqualTo(rowsBefore);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "spring.datasource.url",
            "spring.datasource.username",
            "spring.datasource.password",
            "spring.datasource.driver-class-name"
    })
    void everyDatasourceSystemPropertyFailsBeforeOpeningPostgreSql(String property)
            throws Exception {
        ValidatedRelease release = validate(copiedManifest);
        Map<String, Long> rowsBefore = legalTableCounts();
        long sessionsBefore = databaseSessions();
        String canary = "system-property-canary-"
                + property.replace('.', '-');

        ProcessResult process = executeImport(
                release,
                List.of("-D" + property + '=' + canary),
                StdoutMode.CAPTURE);

        assertImportReport(
                process,
                release,
                3,
                "ERROR",
                false,
                null,
                "IMPORT_DATASOURCE_SYSTEM_PROPERTY_FORBIDDEN",
                canary);
        assertThat(databaseSessions()).isEqualTo(sessionsBefore);
        assertThat(legalTableCounts()).isEqualTo(rowsBefore);
    }

    @Test
    void closingTheChildStdoutLosesTheReportButNotTheConfirmedCommit() throws Exception {
        ValidatedRelease release = validate(copiedManifest);

        ProcessResult process = executeImport(
                release,
                List.of(),
                StdoutMode.CLOSE_IMMEDIATELY);

        assertThat(process.exitCode()).isEqualTo(3);
        assertThat(process.stdout()).isEmpty();
        assertThat(process.stderr()).isEmpty();
        assertThat(process.stdoutLimitExceeded()).isFalse();
        assertThat(process.stderrLimitExceeded()).isFalse();
        Map<String, Object> persistedReceipt = owner.queryForMap("""
                SELECT id, importado_en, sellado_en, estado_construccion
                  FROM legal_publicaciones
                 WHERE publication_external_id = ?
                """, release.plan().manifest().publicationId());
        assertThat(persistedReceipt.get("estado_construccion")).isEqualTo("SELLADO");

        JsonNode replay = assertImportReport(
                executeImport(release, List.of(), StdoutMode.CAPTURE),
                release,
                0,
                "PASS",
                true,
                "ALREADY_IMPORTED",
                null);
        JsonNode details = replay.path("import");
        assertThat(UUID.fromString(details.path("publicationUuid").textValue()))
                .isEqualTo(persistedReceipt.get("id"));
        assertThat(Instant.parse(details.path("importedAt").textValue()))
                .isEqualTo(((Timestamp) persistedReceipt.get("importado_en")).toInstant());
        assertThat(Instant.parse(details.path("sealedAt").textValue()))
                .isEqualTo(((Timestamp) persistedReceipt.get("sellado_en")).toInstant());
    }

    @Test
    void posixLauncherRemovesAllPreMainJvmOptionVariables() throws Exception {
        ValidatedRelease release = validate(copiedManifest);
        Path launcher = artifacts.projectDirectory()
                .resolve("scripts/legal-manifest-import.sh");
        assertThat(launcher).isRegularFile().isExecutable();
        String urlCanary = "launcher-url-canary-must-not-leak";
        String usernameCanary = "launcher-username-canary-must-not-leak";
        String passwordCanary = "launcher-password-canary-must-not-leak";
        String driverCanary = "launcher-driver-canary-must-not-leak";
        Map<String, String> environment = importEnvironment();
        environment.put("ORDENFIX_LEGAL_CLI_JAR", artifacts.legalCliJar().toString());
        environment.put("ORDENFIX_JAVA_BIN", artifacts.javaExecutable().toString());
        environment.put("JAVA_TOOL_OPTIONS",
                "-Dspring.datasource.url=" + urlCanary);
        environment.put("JDK_JAVA_OPTIONS",
                "-Dspring.datasource.username=" + usernameCanary
                        + " -Dspring.datasource.driver-class-name=" + driverCanary);
        environment.put("_JAVA_OPTIONS",
                "-Dspring.datasource.password=" + passwordCanary);
        List<String> command = new ArrayList<>();
        command.add(launcher.toString());
        command.addAll(importFlags(release));

        ProcessResult process = LegalCliProcessSupport.execute(
                command,
                temporaryDirectory,
                environment,
                StdoutMode.CAPTURE);

        assertImportReport(
                process,
                release,
                0,
                "PASS",
                true,
                "IMPORTED",
                null,
                urlCanary,
                usernameCanary,
                passwordCanary,
                driverCanary,
                "Picked up JAVA_TOOL_OPTIONS",
                "NOTE: Picked up JDK_JAVA_OPTIONS",
                "Picked up _JAVA_OPTIONS");
    }

    private ProcessResult executeImport(
            ValidatedRelease release,
            List<String> jvmArguments,
            StdoutMode stdoutMode) throws Exception {
        List<String> cliArguments = new ArrayList<>();
        cliArguments.add("import");
        cliArguments.addAll(importFlags(release));
        return LegalCliProcessSupport.executeJar(
                artifacts,
                temporaryDirectory,
                jvmArguments,
                cliArguments,
                importEnvironment(),
                stdoutMode);
    }

    private List<String> importFlags(ValidatedRelease release) {
        return List.of(
                "--manifest=" + copiedManifest.toAbsolutePath(),
                "--confirm-publication-id="
                        + release.plan().manifest().publicationId(),
                "--confirm-manifest-sha256=" + release.plan().manifestSha256());
    }

    private static Map<String, String> importEnvironment() {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put(LegalImportEnvironment.ENABLED_VARIABLE, "true");
        environment.put(LegalImportEnvironment.URL_VARIABLE, credentials.jdbcUrl());
        environment.put(LegalImportEnvironment.USERNAME_VARIABLE, credentials.username());
        environment.put(LegalImportEnvironment.PASSWORD_VARIABLE, credentials.password());
        environment.put(LegalImportEnvironment.DRIVER_VARIABLE, credentials.driverClassName());
        environment.put("ORDENFIX_CLI_PROCESS_SECRET", PROCESS_SECRET);

        environment.put("SPRING_DATASOURCE_URL", "jdbc:postgresql://127.0.0.1:1/legacy");
        environment.put("SPRING_DATASOURCE_USERNAME", "legacy-user");
        environment.put("SPRING_DATASOURCE_PASSWORD", LEGACY_DATASOURCE_SECRET);
        environment.put("SPRING_DATASOURCE_DRIVER_CLASS_NAME", "hostile.LegacyDriver");
        environment.put("SPRING_APPLICATION_JSON",
                "{\"spring\":{\"datasource\":{\"password\":\""
                        + LEGACY_DATASOURCE_SECRET + "\"}}}");
        environment.put("SPRING_CONFIG_ADDITIONAL_LOCATION", "classpath:/");
        environment.put("SPRING_CONFIG_IMPORT",
                "file:/private/ordenfix-hostile-import.properties");
        environment.put("SPRING_CONFIG_LOCATION", "classpath:/");
        environment.put("SPRING_FLYWAY_ENABLED", "true");
        environment.put("SPRING_MAIN_BANNER_MODE", "console");
        environment.put("SPRING_MAIN_KEEP_ALIVE", "true");
        environment.put("SPRING_MAIN_LOG_STARTUP_INFO", "true");
        environment.put("SPRING_MAIN_SOURCES", NORMAL_START_CLASS);
        environment.put("SPRING_MAIN_WEB_APPLICATION_TYPE", "servlet");
        environment.put("LOGGING_CONFIG", "file:/private/ordenfix-hostile-logback.xml");
        environment.put("LOGGING_LEVEL_ROOT", "TRACE");
        return environment;
    }

    private JsonNode assertImportReport(
            ProcessResult process,
            ValidatedRelease release,
            int expectedExitCode,
            String expectedStatus,
            Boolean expectedPersisted,
            String expectedOutcome,
            String expectedIssueCode,
            String... extraCanaries) throws IOException {
        assertThat(process.exitCode()).isEqualTo(expectedExitCode);
        assertThat(process.stdoutLimitExceeded()).isFalse();
        assertThat(process.stderrLimitExceeded()).isFalse();
        assertThat(process.stderr()).isEmpty();
        assertThat(process.stdout())
                .endsWith("\n")
                .doesNotEndWith("\n\n")
                .doesNotContain("\r");
        String jsonDocument = process.stdout().substring(0, process.stdout().length() - 1);
        assertThat(jsonDocument).doesNotContain("\n");

        JsonNode report;
        try (JsonParser parser = JSON.getFactory().createParser(jsonDocument)) {
            assertThat(parser.nextToken()).isEqualTo(JsonToken.START_OBJECT);
            report = JSON.readTree(parser);
            assertThat(parser.nextToken()).isNull();
        }
        assertExactFields(report, REPORT_FIELDS);
        assertThat(report.path("reportVersion").intValue()).isEqualTo(2);
        assertThat(report.path("command").textValue()).isEqualTo("import");
        assertThat(report.path("status").textValue()).isEqualTo(expectedStatus);
        if (expectedPersisted == null) {
            assertThat(report.path("persisted").isNull()).isTrue();
        } else {
            assertThat(report.path("persisted").booleanValue()).isEqualTo(expectedPersisted);
        }
        assertExactFields(report.path("publication"), PUBLICATION_FIELDS);
        assertThat(report.path("publication").path("publicationId").textValue())
                .isEqualTo(release.plan().manifest().publicationId());
        assertThat(report.path("publication").path("schemaVersion").intValue())
                .isEqualTo(release.plan().manifest().schemaVersion());
        assertThat(report.path("publication").path("manifestSha256").textValue())
                .isEqualTo(release.plan().manifestSha256());
        assertExactFields(report.path("counts"), COUNT_FIELDS);
        assertThat(report.path("counts").path("documents").intValue())
                .isEqualTo(release.documentCount());
        assertThat(report.path("counts").path("requirements").intValue())
                .isEqualTo(release.requirementCount());
        assertThat(report.path("counts").path("scopes").intValue())
                .isEqualTo(release.scopeCount());
        assertThat(report.path("dryRun").isNull()).isTrue();
        if (expectedOutcome == null) {
            assertThat(report.path("import").isNull()).isTrue();
        } else {
            JsonNode details = report.path("import");
            assertExactFields(details, IMPORT_FIELDS);
            assertThat(details.path("outcome").textValue()).isEqualTo(expectedOutcome);
            assertThat(details.path("publicationUuid").textValue())
                    .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
            assertPostgresInstant(details.path("importedAt").textValue());
            assertPostgresInstant(details.path("sealedAt").textValue());
            assertThat(details.path("sealed").booleanValue()).isTrue();
            assertThat(details.path("promotionChanged").booleanValue()).isFalse();
        }
        assertThat(report.path("issues").isArray()).isTrue();
        if (expectedIssueCode == null) {
            assertThat(report.path("issues")).isEmpty();
        } else {
            assertThat(report.path("issues")).hasSize(1);
            JsonNode issue = report.path("issues").get(0);
            assertExactFields(issue, ISSUE_FIELDS);
            assertThat(issue.path("code").textValue()).isEqualTo(expectedIssueCode);
            assertThat(issue.path("severity").textValue()).isEqualTo(expectedStatus);
            String location = issue.path("location").textValue();
            assertThat(location)
                    .isNotBlank()
                    .matches("[A-Za-z0-9_./,:#~-]+")
                    .doesNotStartWith("/")
                    .doesNotStartWith("\\")
                    .doesNotContain("\\", "://", "..");
        }
        assertThat(report.path("omittedIssueCount").intValue()).isZero();

        List<String> forbidden = new ArrayList<>(List.of(
                copiedManifest.toAbsolutePath().toString(),
                temporaryDirectory.toAbsolutePath().toString(),
                PROCESS_SECRET,
                credentials.password(),
                credentials.jdbcUrl(),
                LEGACY_DATASOURCE_SECRET,
                "application-secret.properties",
                "OrdenFix Argentina SAS",
                "30-00000000-0",
                "Calle Pública 100",
                "legal@ordenfix.com",
                "privacidad@ordenfix.com",
                "soporte@ordenfix.com",
                "# Términos",
                "SELECT ",
                "Exception",
                "stacktrace",
                "Spring Boot",
                "Started "));
        forbidden.addAll(List.of(extraCanaries));
        assertThat(process.stdout()).doesNotContain(forbidden.toArray(String[]::new));
        assertThat(process.stderr()).doesNotContain(forbidden.toArray(String[]::new));
        return report;
    }

    private static void assertSameReceipt(JsonNode imported, JsonNode replayed) {
        assertThat(replayed.path("publicationUuid").textValue())
                .isEqualTo(imported.path("publicationUuid").textValue());
        assertThat(replayed.path("importedAt").textValue())
                .isEqualTo(imported.path("importedAt").textValue());
        assertThat(replayed.path("sealedAt").textValue())
                .isEqualTo(imported.path("sealedAt").textValue());
        assertThat(replayed.path("sealed").booleanValue()).isTrue();
        assertThat(replayed.path("promotionChanged").booleanValue()).isFalse();
    }

    private static void assertPostgresInstant(String value) {
        Instant parsed = Instant.parse(value);
        assertThat(parsed.getNano() % 1_000).isZero();
    }

    private static void assertExactFields(JsonNode object, List<String> expectedFields) {
        assertThat(object.isObject()).isTrue();
        List<String> fields = new ArrayList<>();
        object.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyElementsOf(expectedFields);
    }

    private static ValidatedRelease validate(Path manifest) {
        LegalManifestValidation<ValidatedRelease> validation =
                new LegalManifestValidator().validate(manifest);
        assertThat(validation.status())
                .as("issues=%s", validation.issues())
                .isEqualTo(LegalManifestStatus.PASS);
        return validation.value().orElseThrow();
    }

    private static long legalPublicationCount() {
        return Objects.requireNonNull(owner.queryForObject(
                "SELECT count(*) FROM legal_publicaciones",
                Long.class));
    }

    private static Map<String, Long> legalTableCounts() throws Exception {
        Map<String, Long> counts = new LinkedHashMap<>();
        List<String> tables = new ArrayList<>();
        try (Statement statement = observerConnection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT tablename
                       FROM pg_catalog.pg_tables
                      WHERE schemaname = 'public'
                        AND tablename LIKE 'legal_%'
                      ORDER BY tablename
                     """)) {
            while (result.next()) {
                tables.add(result.getString(1));
            }
        }
        for (String table : tables) {
            try (Statement statement = observerConnection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT count(*) FROM public." + quoted(table))) {
                assertThat(result.next()).isTrue();
                counts.put(table, result.getLong(1));
            }
        }
        return Map.copyOf(counts);
    }

    private static long databaseSessions() throws Exception {
        try (Statement statement = observerConnection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT sessions
                       FROM pg_catalog.pg_stat_database
                      WHERE datname = pg_catalog.current_database()
                     """)) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }

    private static String quoted(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }
}
