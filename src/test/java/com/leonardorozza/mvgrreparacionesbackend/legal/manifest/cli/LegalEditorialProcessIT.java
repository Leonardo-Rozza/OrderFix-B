package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli.LegalCliProcessSupport.Artifacts;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli.LegalCliProcessSupport.ProcessResult;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli.LegalEditorialProcessFixture.OwnerSnapshot;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli.LegalEditorialProcessFixture.PlanArtifact;
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
    private static final Set<String> EXPECTED_LEGAL_TABLES = Set.of(
            "legal_publicaciones",
            "legal_documento_reemplazo_lotes",
            "legal_documento_lineas",
            "legal_documento_versiones",
            "legal_documento_contextos",
            "legal_publicacion_documentos",
            "legal_documento_reemplazo_anteriores",
            "legal_documento_reemplazo_sucesoras",
            "legal_documento_transiciones",
            "legal_documento_vigentes",
            "legal_requisito_lineas",
            "legal_requisito_audiencias",
            "legal_requisito_versiones",
            "legal_requisito_documentos",
            "legal_publicacion_requisitos",
            "legal_requisito_transiciones",
            "legal_requisito_conjuntos",
            "legal_requisito_conjunto_miembros",
            "legal_requisito_conjuntos_actuales",
            "legal_aceptacion_lotes",
            "legal_aceptaciones",
            "legal_aceptacion_documentos",
            "legal_aceptacion_metadatos",
            "legal_aceptacion_metadatos_cifrados",
            "legal_idempotencia_resultados");
    private static final Set<String> EXPECTED_LEGAL_SEQUENCES = Set.of(
            "legal_documento_contextos_id_seq",
            "legal_publicacion_documentos_id_seq",
            "legal_documento_reemplazo_anteriores_id_seq",
            "legal_documento_reemplazo_sucesoras_id_seq",
            "legal_documento_transiciones_id_seq",
            "legal_requisito_audiencias_id_seq",
            "legal_requisito_documentos_id_seq",
            "legal_publicacion_requisitos_id_seq",
            "legal_requisito_transiciones_id_seq",
            "legal_requisito_conjunto_miembros_id_seq",
            "legal_aceptacion_documentos_id_seq",
            "legal_aceptacion_metadatos_cifrados_id_seq",
            "legal_idempotencia_resultados_id_seq");
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
        assertThat(beforeReadiness.rowsByTable().keySet())
                .containsExactlyInAnyOrderElementsOf(EXPECTED_LEGAL_TABLES);
        assertThat(beforeReadiness.sequences().keySet())
                .containsExactlyInAnyOrderElementsOf(EXPECTED_LEGAL_SEQUENCES);

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
        assertStateMatchesDatabase(
                readiness,
                "source-process-v1",
                11,
                6,
                0,
                0,
                0,
                0,
                0);
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
        assertDelta(promotePlan, 22, 0, 12, 0, 21, 0, 0, 0, 8, 0);
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
        assertStateMatchesDatabase(
                promoted,
                "source-process-v1",
                11,
                6,
                22,
                12,
                21,
                8,
                0);
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
        assertSameReceipt(promoted, promoteReplay);
        assertNoIssues(promoteReplay);
        assertThat(fixture.snapshotOwner()).isEqualTo(afterPromote);
        assertThat(fixture.digestTree(source))
                .isEqualTo(source.sha256ByRelativePath());

        LegalEditorialProcessFixture.ReleaseArtifact target =
                fixture.successor(source, "target-process-v2");
        JsonNode targetImported = assertSingleJson(
                fixture.executeImport(target),
                0,
                2,
                "import",
                "PASS");
        assertThat(targetImported.path("persisted").booleanValue()).isTrue();
        assertThat(targetImported.path("import").path("outcome").textValue())
                .isEqualTo("IMPORTED");
        String targetPublicationUuid = targetImported.path("import")
                .path("publicationUuid")
                .textValue();
        assertThat(UUID.fromString(targetPublicationUuid).toString())
                .isEqualTo(targetPublicationUuid);
        assertNoIssues(targetImported);
        OwnerSnapshot afterTargetImport = fixture.snapshotOwner();
        assertProtectedRowsUnchanged(afterPromote, afterTargetImport);

        JsonNode incompatiblePromote = assertSingleJson(
                fixture.executeEditorial("plan-promote", target),
                2,
                3,
                "plan-promote",
                "BLOCKED");
        assertThat(incompatiblePromote.path("persisted").booleanValue()).isFalse();
        assertOperation(incompatiblePromote, "PROMOTE", "BLOCKED");
        assertThat(incompatiblePromote.path("publication")
                .path("publicationUuid").isNull()).isTrue();
        assertThat(incompatiblePromote.path("plan").isNull()).isTrue();
        assertThat(incompatiblePromote.path("readiness").isNull()).isTrue();
        assertThat(incompatiblePromote.path("counts").path("state").isNull()).isTrue();
        assertThat(incompatiblePromote.path("counts").path("delta").isNull()).isTrue();
        assertThat(issueCodes(incompatiblePromote))
                .containsExactly("INITIAL_PROJECTION_ALREADY_EXISTS");
        assertThat(fixture.snapshotOwner()).isEqualTo(afterTargetImport);

        JsonNode sourceReady = assertSingleJson(
                fixture.executeEditorial("readiness", source),
                0,
                3,
                "readiness",
                "PASS");
        assertThat(sourceReady.path("readiness").path("value").textValue())
                .isEqualTo("READY");
        String replacementFingerprint = sourceReady.path("readiness")
                .path("editorialStateFingerprint")
                .textValue();
        assertThat(replacementFingerprint).matches("sha256:[0-9a-f]{64}");
        assertStateMatchesDatabase(
                sourceReady,
                "source-process-v1",
                11,
                6,
                22,
                12,
                21,
                8,
                0);
        assertNoIssues(sourceReady);
        assertThat(fixture.snapshotOwner()).isEqualTo(afterTargetImport);

        PlanArtifact replacement = fixture.replacementPlan(
                source,
                target,
                replacementFingerprint);
        assertThat(fixture.snapshotOwner()).isEqualTo(afterTargetImport);
        assertThat(fixture.digestPlan(replacement)).isEqualTo(replacement.fileSha256());
        assertThat(replacement.plan().editorialPlanSha256())
                .matches("[0-9a-f]{64}");

        JsonNode replacePlan = assertSingleJson(
                fixture.executeEditorial("plan-replace", target, replacement),
                0,
                3,
                "plan-replace",
                "PASS");
        assertThat(replacePlan.path("persisted").booleanValue()).isFalse();
        assertOperation(replacePlan, "REPLACE", "APPLICABLE");
        assertPlanIdentity(replacePlan, replacement, true, "READY");
        assertDelta(replacePlan, 1, 2, 6, 18, 18, 3, 3, 8, 8, 1);
        assertNoIssues(replacePlan);
        assertThat(fixture.snapshotOwner()).isEqualTo(afterTargetImport);

        JsonNode replaced = assertSingleJson(
                fixture.executeEditorial("apply-replace", target, replacement),
                0,
                3,
                "apply-replace",
                "PASS");
        assertThat(replaced.path("persisted").booleanValue()).isTrue();
        assertOperation(replaced, "REPLACE", "APPLIED");
        assertPlanIdentity(replaced, replacement, false, "READY");
        assertThat(replaced.path("publication").path("publicationUuid").textValue())
                .isEqualTo(targetPublicationUuid);
        assertThat(replaced.path("readiness").path("value").textValue())
                .isEqualTo("READY");
        assertStateMatchesDatabase(
                replaced,
                "target-process-v2",
                11,
                6,
                22,
                12,
                21,
                8,
                1);
        assertNoIssues(replaced);
        assertCurrentVersionStates(11, 1, 6, 2);
        OwnerSnapshot afterReplace = fixture.snapshotOwner();
        assertThat(afterReplace).isNotEqualTo(afterTargetImport);
        assertProtectedRowsUnchanged(afterTargetImport, afterReplace);

        JsonNode replaceReplay = assertSingleJson(
                fixture.executeEditorial("apply-replace", target, replacement),
                0,
                3,
                "apply-replace",
                "PASS");
        assertThat(replaceReplay.path("persisted").booleanValue()).isTrue();
        assertOperation(replaceReplay, "REPLACE", "ALREADY_APPLIED");
        assertPlanIdentity(replaceReplay, replacement, false, "READY");
        assertSameReceipt(replaced, replaceReplay);
        assertNoIssues(replaceReplay);
        assertThat(fixture.snapshotOwner()).isEqualTo(afterReplace);

        JsonNode targetReady = assertSingleJson(
                fixture.executeEditorial("readiness", target),
                0,
                3,
                "readiness",
                "PASS");
        assertThat(targetReady.path("readiness").path("value").textValue())
                .isEqualTo("READY");
        String retirementFingerprint = targetReady.path("readiness")
                .path("editorialStateFingerprint")
                .textValue();
        assertThat(retirementFingerprint).matches("sha256:[0-9a-f]{64}");
        assertStateCounts(
                targetReady,
                12,
                8,
                25,
                18,
                21,
                8,
                1);
        assertNoIssues(targetReady);
        assertThat(fixture.snapshotOwner()).isEqualTo(afterReplace);

        PlanArtifact retirement = fixture.retirementPlan(
                target,
                retirementFingerprint);
        assertThat(fixture.snapshotOwner()).isEqualTo(afterReplace);
        assertThat(fixture.digestPlan(retirement)).isEqualTo(retirement.fileSha256());

        JsonNode retirePlan = assertSingleJson(
                fixture.executeEditorial("plan-retire", target, retirement),
                0,
                3,
                "plan-retire",
                "PASS");
        assertThat(retirePlan.path("persisted").booleanValue()).isFalse();
        assertOperation(retirePlan, "RETIRE", "APPLICABLE");
        assertPlanIdentity(retirePlan, retirement, true, "NOT_READY");
        assertDelta(retirePlan, 1, 0, 1, 2, 0, 0, 0, 4, 0, 0);
        assertNoIssues(retirePlan);
        assertThat(fixture.snapshotOwner()).isEqualTo(afterReplace);

        JsonNode retired = assertSingleJson(
                fixture.executeEditorial("apply-retire", target, retirement),
                0,
                3,
                "apply-retire",
                "PASS");
        assertThat(retired.path("persisted").booleanValue()).isTrue();
        assertOperation(retired, "RETIRE", "APPLIED");
        assertPlanIdentity(retired, retirement, false, "NOT_READY");
        assertThat(retired.path("readiness").path("value").textValue())
                .isEqualTo("NOT_READY");
        assertStateMatchesDatabase(
                retired,
                "target-process-v2",
                11,
                6,
                23,
                13,
                19,
                4,
                1);
        assertNoIssues(retired);
        assertCurrentVersionStates(10, 1, 5, 2);
        assertThat(owner.queryForObject("""
                SELECT count(*)
                  FROM legal_documento_versiones
                 WHERE estado = 'RETIRADA'
                """, Long.class)).isEqualTo(1L);
        assertThat(owner.queryForObject("""
                SELECT count(*)
                  FROM legal_requisito_versiones
                 WHERE estado = 'RETIRADA'
                """, Long.class)).isEqualTo(1L);
        OwnerSnapshot afterRetire = fixture.snapshotOwner();
        assertThat(afterRetire).isNotEqualTo(afterReplace);
        assertProtectedRowsUnchanged(afterReplace, afterRetire);

        JsonNode retireReplay = assertSingleJson(
                fixture.executeEditorial("apply-retire", target, retirement),
                0,
                3,
                "apply-retire",
                "PASS");
        assertThat(retireReplay.path("persisted").booleanValue()).isTrue();
        assertOperation(retireReplay, "RETIRE", "ALREADY_APPLIED");
        assertPlanIdentity(retireReplay, retirement, false, "NOT_READY");
        assertSameReceipt(retired, retireReplay);
        assertNoIssues(retireReplay);
        assertThat(fixture.snapshotOwner()).isEqualTo(afterRetire);
        assertThat(fixture.digestTree(source))
                .isEqualTo(source.sha256ByRelativePath());
        assertThat(fixture.digestTree(target))
                .isEqualTo(target.sha256ByRelativePath());
        assertThat(fixture.digestPlan(replacement)).isEqualTo(replacement.fileSha256());
        assertThat(fixture.digestPlan(retirement)).isEqualTo(retirement.fileSha256());
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

    private static List<String> issueCodes(JsonNode report) {
        List<String> codes = new ArrayList<>();
        report.path("issues").forEach(issue -> codes.add(issue.path("code").textValue()));
        return List.copyOf(codes);
    }

    private static void assertPlanIdentity(
            JsonNode report,
            PlanArtifact plan,
            boolean planned,
            String expectedReadiness) {
        JsonNode reportPlan = report.path("plan");
        assertThat(reportPlan.path("operationId").textValue())
                .isEqualTo(plan.plan().operationId().toString());
        assertThat(reportPlan.path("editorialPlanSha256").textValue())
                .isEqualTo(plan.plan().editorialPlanSha256());
        assertThat(reportPlan.path("expectedReadinessAfter").textValue())
                .isEqualTo(expectedReadiness);
        if (planned) {
            assertThat(reportPlan.path("changeRequired").booleanValue()).isTrue();
            assertThat(reportPlan.path("observedAt").isTextual()).isTrue();
        } else {
            assertThat(reportPlan.path("changeRequired").isNull()).isTrue();
            assertThat(reportPlan.path("observedAt").isNull()).isTrue();
        }
    }

    private static void assertSameReceipt(JsonNode applied, JsonNode replay) {
        assertThat(replay.path("publication")).isEqualTo(applied.path("publication"));
        assertThat(replay.path("operation").path("appliedAt"))
                .isEqualTo(applied.path("operation").path("appliedAt"));
        assertThat(replay.path("plan")).isEqualTo(applied.path("plan"));
        assertThat(replay.path("readiness")).isEqualTo(applied.path("readiness"));
        assertThat(replay.path("counts")).isEqualTo(applied.path("counts"));
    }

    private static void assertDelta(
            JsonNode report,
            int directDocumentTransitions,
            int triggerDerivedDocumentTransitions,
            int directRequirementTransitions,
            int directDocumentSlotDeletes,
            int directDocumentSlotInserts,
            int triggerDerivedDocumentSlotDeletes,
            int triggerDerivedDocumentSlotInserts,
            int requiredSetPointerDeletes,
            int requiredSetPointerInserts,
            int replacementBatches) {
        JsonNode delta = report.path("counts").path("delta");
        assertExactIntegralFields(delta, List.of(
                "directDocumentTransitions",
                "triggerDerivedDocumentTransitions",
                "directRequirementTransitions",
                "directDocumentSlotDeletes",
                "directDocumentSlotInserts",
                "triggerDerivedDocumentSlotDeletes",
                "triggerDerivedDocumentSlotInserts",
                "requiredSetPointerDeletes",
                "requiredSetPointerInserts",
                "replacementBatches"));
        assertThat(delta.path("directDocumentTransitions").intValue())
                .isEqualTo(directDocumentTransitions);
        assertThat(delta.path("triggerDerivedDocumentTransitions").intValue())
                .isEqualTo(triggerDerivedDocumentTransitions);
        assertThat(delta.path("directRequirementTransitions").intValue())
                .isEqualTo(directRequirementTransitions);
        assertThat(delta.path("directDocumentSlotDeletes").intValue())
                .isEqualTo(directDocumentSlotDeletes);
        assertThat(delta.path("directDocumentSlotInserts").intValue())
                .isEqualTo(directDocumentSlotInserts);
        assertThat(delta.path("triggerDerivedDocumentSlotDeletes").intValue())
                .isEqualTo(triggerDerivedDocumentSlotDeletes);
        assertThat(delta.path("triggerDerivedDocumentSlotInserts").intValue())
                .isEqualTo(triggerDerivedDocumentSlotInserts);
        assertThat(delta.path("requiredSetPointerDeletes").intValue())
                .isEqualTo(requiredSetPointerDeletes);
        assertThat(delta.path("requiredSetPointerInserts").intValue())
                .isEqualTo(requiredSetPointerInserts);
        assertThat(delta.path("replacementBatches").intValue())
                .isEqualTo(replacementBatches);
    }

    private static void assertStateCounts(
            JsonNode report,
            int documentVersions,
            int requirementVersions,
            int documentTransitions,
            int requirementTransitions,
            int documentSlots,
            int requiredSetPointers,
            int replacementBatches) {
        JsonNode state = report.path("counts").path("state");
        assertExactIntegralFields(state, List.of(
                "documentVersions",
                "requirementVersions",
                "documentTransitions",
                "requirementTransitions",
                "documentSlots",
                "requiredSetPointers",
                "replacementBatches"));
        assertThat(state.path("documentVersions").intValue())
                .isEqualTo(documentVersions);
        assertThat(state.path("requirementVersions").intValue())
                .isEqualTo(requirementVersions);
        assertThat(state.path("documentTransitions").intValue())
                .isEqualTo(documentTransitions);
        assertThat(state.path("requirementTransitions").intValue())
                .isEqualTo(requirementTransitions);
        assertThat(state.path("documentSlots").intValue())
                .isEqualTo(documentSlots);
        assertThat(state.path("requiredSetPointers").intValue())
                .isEqualTo(requiredSetPointers);
        assertThat(state.path("replacementBatches").intValue())
                .isEqualTo(replacementBatches);
    }

    private static void assertExactIntegralFields(
            JsonNode object,
            List<String> expectedFields) {
        assertThat(object.isObject()).isTrue();
        List<String> actualFields = new ArrayList<>();
        object.fieldNames().forEachRemaining(actualFields::add);
        assertThat(actualFields).containsExactlyElementsOf(expectedFields);
        for (String field : expectedFields) {
            assertThat(object.path(field).isIntegralNumber())
                    .as("campo integral %s", field)
                    .isTrue();
        }
    }

    private static void assertCurrentVersionStates(
            long currentDocuments,
            long replacedDocuments,
            long currentRequirements,
            long replacedRequirements) {
        assertThat(owner.queryForObject("""
                SELECT count(*)
                  FROM legal_documento_versiones
                 WHERE estado = 'VIGENTE'
                """, Long.class)).isEqualTo(currentDocuments);
        assertThat(owner.queryForObject("""
                SELECT count(*)
                  FROM legal_documento_versiones
                 WHERE estado = 'REEMPLAZADA'
                """, Long.class)).isEqualTo(replacedDocuments);
        assertThat(owner.queryForObject("""
                SELECT count(*)
                  FROM legal_requisito_versiones
                 WHERE estado = 'VIGENTE'
                """, Long.class)).isEqualTo(currentRequirements);
        assertThat(owner.queryForObject("""
                SELECT count(*)
                  FROM legal_requisito_versiones
                 WHERE estado = 'REEMPLAZADA'
                """, Long.class)).isEqualTo(replacedRequirements);
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
            int expectedDocumentTransitions,
            int expectedRequirementTransitions,
            int expectedDocumentSlots,
            int expectedRequiredSetPointers,
            int expectedReplacementBatches) {
        UUID publicationUuid = owner.queryForObject("""
                SELECT id
                  FROM legal_publicaciones
                 WHERE publication_external_id = ?
                """, UUID.class, publicationId);
        JsonNode state = report.path("counts").path("state");
        assertStateCounts(
                report,
                expectedDocumentVersions,
                expectedRequirementVersions,
                expectedDocumentTransitions,
                expectedRequirementTransitions,
                expectedDocumentSlots,
                expectedRequiredSetPointers,
                expectedReplacementBatches);
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
                .isEqualTo(expectedDocumentTransitions)
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
                .isEqualTo(expectedRequirementTransitions)
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
