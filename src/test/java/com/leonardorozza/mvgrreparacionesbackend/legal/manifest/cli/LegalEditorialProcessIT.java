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
import java.util.Set;
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
    private static final Set<String> PROTECTED_HTTP_TABLES = Set.of(
            "legal_aceptacion_lotes",
            "legal_aceptaciones",
            "legal_aceptacion_documentos",
            "legal_aceptacion_metadatos",
            "legal_aceptacion_metadatos_cifrados",
            "legal_idempotencia_resultados");
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
    void restrictedPackagedJarsCompleteEditorialLifecycle() throws Exception {
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

        JsonNode promotePlan = assertSingleJson(
                fixture.executeEditorial("plan-promote", source),
                0,
                3,
                "plan-promote",
                "PASS");
        assertThat(promotePlan.path("persisted").booleanValue()).isFalse();
        assertOperation(promotePlan, "PROMOTE", "APPLICABLE");
        assertThat(promotePlan.path("plan").path("changeRequired").booleanValue())
                .isTrue();
        assertThat(promotePlan.path("readiness").path("value").textValue())
                .isEqualTo("READY");
        assertThat(promotePlan.path("counts").path("delta").isObject()).isTrue();
        assertNoIssues(promotePlan);
        assertThat(fixture.snapshotOwner()).isEqualTo(beforeReadiness);

        JsonNode promoted = assertSingleJson(
                fixture.executeEditorial("apply-promote", source),
                0,
                3,
                "apply-promote",
                "PASS");
        assertThat(promoted.path("persisted").booleanValue()).isTrue();
        assertOperation(promoted, "PROMOTE", "APPLIED");
        assertThat(promoted.path("publication").path("publicationUuid").textValue())
                .isEqualTo(publicationUuid);
        assertThat(promoted.path("plan").isNull()).isTrue();
        assertThat(promoted.path("readiness").path("value").textValue())
                .isEqualTo("READY");
        assertThat(promoted.path("counts").path("delta").isNull()).isTrue();
        assertNoIssues(promoted);
        assertStateMatchesDatabase(promoted, "source-process-v1", 11, 6, 21, 8, 0);
        OwnerSnapshot afterPromote = fixture.snapshotOwner();
        assertThat(afterPromote).isNotEqualTo(beforeReadiness);
        assertProtectedRowsUnchanged(beforeReadiness, afterPromote);

        JsonNode promoteReplay = assertSingleJson(
                fixture.executeEditorial("apply-promote", source),
                0,
                3,
                "apply-promote",
                "PASS");
        assertThat(promoteReplay.path("persisted").booleanValue()).isTrue();
        assertOperation(promoteReplay, "PROMOTE", "ALREADY_APPLIED");
        assertThat(promoteReplay.path("operation").path("appliedAt"))
                .isEqualTo(promoted.path("operation").path("appliedAt"));
        assertThat(promoteReplay.path("publication"))
                .isEqualTo(promoted.path("publication"));
        assertThat(promoteReplay.path("counts").path("state"))
                .isEqualTo(promoted.path("counts").path("state"));
        assertNoIssues(promoteReplay);
        assertThat(fixture.snapshotOwner()).isEqualTo(afterPromote);
        assertThat(fixture.digestTree(source))
                .isEqualTo(source.sha256ByRelativePath());
    }

    private static void assertOperation(
            JsonNode report,
            String expectedType,
            String expectedOutcome) {
        assertThat(report.path("operation").isObject()).isTrue();
        assertThat(report.path("operation").path("operationType").textValue())
                .isEqualTo(expectedType);
        assertThat(report.path("operation").path("outcome").textValue())
                .isEqualTo(expectedOutcome);
    }

    private static void assertNoIssues(JsonNode report) {
        assertThat(report.path("issues")).isEmpty();
        assertThat(report.path("omittedIssueCount").intValue()).isZero();
    }

    private static void assertProtectedRowsUnchanged(
            OwnerSnapshot before,
            OwnerSnapshot after) {
        for (String table : PROTECTED_HTTP_TABLES) {
            assertThat(after.rowsByTable().get(table))
                    .as("tabla HTTP protegida %s", table)
                    .isEqualTo(before.rowsByTable().get(table));
        }
    }

    private static void assertStateMatchesDatabase(
            JsonNode report,
            String publicationId,
            int expectedDocumentVersions,
            int expectedRequirementVersions,
            int expectedDocumentSlots,
            int expectedRequiredSetPointers,
            int expectedReplacementBatches) {
        UUID publicationUuid = owner.queryForObject("""
                SELECT id
                  FROM legal_publicaciones
                 WHERE publication_external_id = ?
                """, UUID.class, publicationId);
        JsonNode state = report.path("counts").path("state");
        assertThat(state.path("documentVersions").intValue())
                .isEqualTo(expectedDocumentVersions)
                .isEqualTo(databaseCount("""
                        SELECT count(*)
                          FROM legal_publicacion_documentos
                         WHERE publicacion_id = ?
                        """, publicationUuid));
        assertThat(state.path("requirementVersions").intValue())
                .isEqualTo(expectedRequirementVersions)
                .isEqualTo(databaseCount("""
                        SELECT count(*)
                          FROM legal_publicacion_requisitos
                         WHERE publicacion_id = ?
                        """, publicationUuid));
        assertThat(state.path("documentTransitions").intValue())
                .isEqualTo(databaseCount("""
                        SELECT count(*)
                          FROM legal_documento_transiciones transition
                         WHERE EXISTS (
                               SELECT 1
                                 FROM legal_publicacion_documentos member
                                WHERE member.publicacion_id = ?
                                  AND member.documento_version_id =
                                      transition.documento_version_id)
                        """, publicationUuid));
        assertThat(state.path("requirementTransitions").intValue())
                .isEqualTo(databaseCount("""
                        SELECT count(*)
                          FROM legal_requisito_transiciones transition
                         WHERE EXISTS (
                               SELECT 1
                                 FROM legal_publicacion_requisitos member
                                WHERE member.publicacion_id = ?
                                  AND member.requisito_version_id =
                                      transition.requisito_version_id)
                        """, publicationUuid));
        assertThat(state.path("documentSlots").intValue())
                .isEqualTo(expectedDocumentSlots)
                .isEqualTo(databaseCount("""
                        SELECT count(*)
                          FROM legal_documento_vigentes
                         WHERE publicacion_id = ?
                        """, publicationUuid));
        assertThat(state.path("requiredSetPointers").intValue())
                .isEqualTo(expectedRequiredSetPointers)
                .isEqualTo(databaseCount("""
                        SELECT count(*)
                          FROM legal_requisito_conjuntos_actuales
                         WHERE publicacion_id = ?
                        """, publicationUuid));
        assertThat(state.path("replacementBatches").intValue())
                .isEqualTo(expectedReplacementBatches)
                .isEqualTo(databaseCount("""
                        SELECT count(DISTINCT transition.reemplazo_lote_id)
                          FROM legal_documento_transiciones transition
                         WHERE transition.reemplazo_lote_id IS NOT NULL
                           AND EXISTS (
                               SELECT 1
                                 FROM legal_publicacion_documentos member
                                WHERE member.publicacion_id = ?
                                  AND member.documento_version_id =
                                      transition.documento_version_id)
                        """, publicationUuid));
    }

    private static int databaseCount(String sql, UUID publicationUuid) {
        return Math.toIntExact(owner.queryForObject(sql, Long.class, publicationUuid));
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
