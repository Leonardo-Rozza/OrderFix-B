package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli.LegalCliProcessSupport.Artifacts;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli.LegalCliProcessSupport.ProcessResult;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli.LegalEditorialProcessFixture.OwnerSnapshot;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalRestrictedEditorialRoleFixture;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalRestrictedEditorialRoleFixture.Credentials;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalRestrictedImportRoleFixture;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class LegalEditorialProcessIT {

    private static final String IMPORT_ROLE = "ordenfix_legal_import_10e";
    private static final String IMPORT_PASSWORD =
            "import-10e-password-must-never-leak";
    private static final String EDITORIAL_ROLE = "ordenfix_legal_editorial_10e";
    private static final String EDITORIAL_PASSWORD =
            "editorial-10e-password-must-never-leak";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_editorial_process_10e")
            .withUsername("ordenfix")
            .withPassword("owner-10e-password-must-never-leak");

    private static Artifacts artifacts;
    private static JdbcTemplate owner;
    private static LegalRestrictedImportRoleFixture importRoleFixture;
    private static LegalRestrictedEditorialRoleFixture editorialRoleFixture;
    private static LegalRestrictedImportRoleFixture.Credentials importCredentials;
    private static Credentials editorialCredentials;

    @TempDir
    private Path temporaryDirectory;

    private LegalEditorialProcessFixture fixture;

    @BeforeAll
    static void migrateAndProvisionRestrictedRoles() {
        artifacts = LegalCliProcessSupport.locateArtifacts();
        Flyway.configure()
                .dataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        owner = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        importRoleFixture = new LegalRestrictedImportRoleFixture(
                owner,
                POSTGRES.getJdbcUrl(),
                IMPORT_ROLE,
                IMPORT_PASSWORD,
                POSTGRES.getDriverClassName());
        importCredentials = importRoleFixture.provisionAndVerify();
        editorialRoleFixture = new LegalRestrictedEditorialRoleFixture(
                owner,
                POSTGRES.getJdbcUrl(),
                EDITORIAL_ROLE,
                EDITORIAL_PASSWORD,
                POSTGRES.getDriverClassName());
        editorialCredentials = editorialRoleFixture.provisionAndVerify();
    }

    @BeforeEach
    void resetDatabaseAndCreateFixture() {
        owner.execute("""
                TRUNCATE TABLE
                    legal_idempotencia_resultados,
                    legal_aceptacion_metadatos_cifrados,
                    legal_aceptacion_metadatos,
                    legal_aceptacion_documentos,
                    legal_aceptaciones,
                    legal_aceptacion_lotes,
                    legal_publicaciones,
                    legal_documento_reemplazo_lotes
                RESTART IDENTITY CASCADE
                """);
        importRoleFixture.verify();
        editorialRoleFixture.verify();
        fixture = new LegalEditorialProcessFixture(
                temporaryDirectory,
                getClass(),
                artifacts,
                owner,
                importCredentials,
                editorialCredentials);
    }

    @Test
    void restrictedPackagedJarsSeedAndObserveNotReadyRelease() throws Exception {
        LegalEditorialProcessFixture.ReleaseArtifact source =
                fixture.copyRelease("source-process-v1");

        JsonNode imported = assertSingleJson(
                fixture.executeImport(source),
                0,
                2,
                "import",
                "PASS");
        assertThat(imported.path("persisted").booleanValue()).isTrue();
        assertThat(imported.path("import").path("outcome").textValue())
                .isEqualTo("IMPORTED");
        String publicationUuid = imported.path("import")
                .path("publicationUuid")
                .textValue();
        assertThat(UUID.fromString(publicationUuid).toString())
                .isEqualTo(publicationUuid);
        assertThat(owner.queryForObject("""
                SELECT id::text
                  FROM legal_publicaciones
                 WHERE publication_external_id = ?
                """, String.class, "source-process-v1"))
                .isEqualTo(publicationUuid);
        OwnerSnapshot beforeReadiness = fixture.snapshotOwner();

        JsonNode readiness = assertSingleJson(
                fixture.executeEditorial("readiness", source),
                2,
                3,
                "readiness",
                "BLOCKED");
        assertThat(readiness.path("persisted").isBoolean()).isTrue();
        assertThat(readiness.path("persisted").booleanValue()).isFalse();
        assertThat(readiness.path("publication").path("publicationUuid").textValue())
                .isEqualTo(publicationUuid);
        assertThat(readiness.path("operation").isNull()).isTrue();
        assertThat(readiness.path("plan").isNull()).isTrue();
        assertThat(readiness.path("readiness").path("value").textValue())
                .isEqualTo("NOT_READY");
        assertThat(readiness.path("readiness").path("editorialStateFingerprint")
                .textValue()).matches("sha256:[0-9a-f]{64}");
        assertThat(readiness.path("counts").path("state").isObject()).isTrue();
        assertThat(fixture.snapshotOwner()).isEqualTo(beforeReadiness);
        assertThat(fixture.digestTree(source))
                .isEqualTo(source.sha256ByRelativePath());
        assertThat(owner.queryForObject("""
                SELECT count(*)
                  FROM legal_publicaciones
                 WHERE publication_external_id = 'source-process-v1'
                   AND estado_construccion = 'SELLADO'
                """, Long.class)).isEqualTo(1L);
    }

    private JsonNode assertSingleJson(
            ProcessResult process,
            int expectedExit,
            int expectedReportVersion,
            String expectedCommand,
            String expectedStatus) throws Exception {
        assertThat(process.timedOut()).isFalse();
        assertThat(process.wallDuration()).isLessThan(Duration.ofSeconds(70));
        assertThat(process.exitCode()).isEqualTo(expectedExit);
        assertThat(process.stdoutLimitExceeded()).isFalse();
        assertThat(process.stderrLimitExceeded()).isFalse();
        assertThat(process.stderr()).isEmpty();
        assertThat(process.stdout()).endsWith("\n").doesNotEndWith("\n\n");
        String document = process.stdout().substring(0, process.stdout().length() - 1);
        assertThat(document).doesNotContain("\n", "\r");
        try (JsonParser parser = JSON.createParser(document)) {
            JsonNode report = JSON.readTree(parser);
            assertThat(parser.nextToken()).isNull();
            assertThat(report.isObject()).isTrue();
            assertThat(report.path("reportVersion").isIntegralNumber()).isTrue();
            assertThat(report.path("reportVersion").intValue())
                    .isEqualTo(expectedReportVersion);
            assertExactTopLevelFields(report, expectedReportVersion);
            assertThat(report.path("command").isTextual()).isTrue();
            assertThat(report.path("status").isTextual()).isTrue();
            assertThat(report.path("persisted").isBoolean()).isTrue();
            assertThat(report.path("issues").isArray()).isTrue();
            assertThat(report.path("omittedIssueCount").isIntegralNumber()).isTrue();
            assertThat(report.path("command").textValue()).isEqualTo(expectedCommand);
            assertThat(report.path("status").textValue()).isEqualTo(expectedStatus);
            assertThat(process.stdout())
                    .doesNotContain(
                            temporaryDirectory.toAbsolutePath().toString(),
                            POSTGRES.getJdbcUrl(),
                            POSTGRES.getPassword(),
                            POSTGRES.getUsername(),
                            IMPORT_ROLE,
                            IMPORT_PASSWORD,
                            EDITORIAL_ROLE,
                            EDITORIAL_PASSWORD,
                            "jdbc:postgresql:",
                            "org.postgresql.Driver",
                            "SELECT ",
                            "INSERT ",
                            "UPDATE ",
                            "DELETE ",
                            "TRUNCATE ",
                            "Exception",
                            "stacktrace",
                            "Spring Boot",
                            "Started ");
            assertThat(process.stdout())
                    .doesNotContain(fixture.outputCanaries().toArray(String[]::new));
            return report;
        }
    }

    private static void assertExactTopLevelFields(
            JsonNode report,
            int reportVersion) {
        List<String> fields = new ArrayList<>();
        report.fieldNames().forEachRemaining(fields::add);
        if (reportVersion == 2) {
            assertThat(fields).containsExactly(
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
            return;
        }
        assertThat(fields).containsExactly(
                "reportVersion",
                "command",
                "status",
                "persisted",
                "publication",
                "operation",
                "plan",
                "readiness",
                "counts",
                "issues",
                "omittedIssueCount");
    }
}
