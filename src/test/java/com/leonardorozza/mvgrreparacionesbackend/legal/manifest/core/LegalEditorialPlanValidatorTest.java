package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialPlanValidator.ValidatedEditorialPlan;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LegalEditorialPlanValidatorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    private final LegalEditorialPlanValidator validator = new LegalEditorialPlanValidator();

    @Test
    void acceptsReplaceAndExposesOnlyAnOpaqueDeeplySortedSemanticCopy() throws IOException {
        ObjectNode raw = fixture("replace-valid-v1/editorial-plan.json");

        LegalManifestValidation<ValidatedEditorialPlan> result = validator.validate(write(raw));

        assertThat(result.status()).isEqualTo(LegalManifestStatus.PASS);
        ValidatedEditorialPlan validated = result.value().orElseThrow();
        assertThat(validated.plan().documentReuses().getFirst().contexts())
                .containsExactly(ContextoLegal.REGISTRO, ContextoLegal.USO_CONTINUADO);
        assertThat(validated.plan().documentReplacementBatches().getFirst().predecessors())
                .extracting(item -> item.documentVersionId().toString())
                .containsExactly(
                        "00000000-0000-0000-0000-000000000110",
                        "00000000-0000-0000-0000-000000000111");
        assertThat(validated.plan().requirementReuses().getFirst().audiences())
                .containsExactly(AudienciaLegal.ADMIN_TITULAR, AudienciaLegal.USER);
        assertThat(validated.canonicalJson())
                .contains("[\"USO_CONTINUADO\",\"REGISTRO\"]");
        assertThat(validated.editorialPlanSha256()).matches("[0-9a-f]{64}");

        assertThat(ValidatedEditorialPlan.class.getDeclaredConstructors())
                .allSatisfy(constructor -> assertThat(Modifier.isPrivate(
                        constructor.getModifiers())).isTrue());
    }

    @Test
    void enforcesTheClosedReplaceOperationMatrix() throws IOException {
        ObjectNode sameTarget = fixture("replace-valid-v1/editorial-plan.json");
        sameTarget.put("targetPublicationId", sameTarget.path(
                "expectedCurrentPublicationId").textValue());
        assertBlocked(write(sameTarget), LegalManifestIssueCode.REPLACEMENT_MAPPING_INVALID);

        ObjectNode notReady = fixture("replace-valid-v1/editorial-plan.json");
        notReady.put("expectedReadinessAfter", "NOT_READY");
        assertBlocked(write(notReady), LegalManifestIssueCode.EXPECTED_READINESS_MISMATCH);

        ObjectNode acknowledged = fixture("replace-valid-v1/editorial-plan.json");
        acknowledged.put("acknowledgeFailClosedGap", true);
        assertBlocked(write(acknowledged),
                LegalManifestIssueCode.FAIL_CLOSED_GAP_NOT_ACKNOWLEDGED);

        ObjectNode empty = fixture("replace-valid-v1/editorial-plan.json");
        clearAllCollections(empty);
        assertBlocked(write(empty), LegalManifestIssueCode.REPLACEMENT_MAPPING_INVALID);
    }

    @Test
    void enforcesTheClosedRetireOperationMatrixAndAtLeastOneExplicitExit()
            throws IOException {
        assertThat(validator.validate(write(fixture("retire-valid-v1/editorial-plan.json")))
                .status()).isEqualTo(LegalManifestStatus.PASS);

        ObjectNode differentSha = fixture("retire-valid-v1/editorial-plan.json");
        differentSha.put("targetManifestSha256", "f".repeat(64));
        assertBlocked(write(differentSha), LegalManifestIssueCode.REPLACEMENT_MAPPING_INVALID);

        ObjectNode ready = fixture("retire-valid-v1/editorial-plan.json");
        ready.put("expectedReadinessAfter", "READY");
        assertBlocked(write(ready), LegalManifestIssueCode.EXPECTED_READINESS_MISMATCH);

        ObjectNode noAck = fixture("retire-valid-v1/editorial-plan.json");
        noAck.put("acknowledgeFailClosedGap", false);
        assertBlocked(write(noAck),
                LegalManifestIssueCode.FAIL_CLOSED_GAP_NOT_ACKNOWLEDGED);

        ObjectNode forbiddenAddition = fixture("retire-valid-v1/editorial-plan.json");
        forbiddenAddition.withArray("documentAdditions").addObject()
                .put("documentVersionId", "00000000-0000-0000-0000-000000000999")
                .put("sha256", "f".repeat(64))
                .putArray("contexts").add("REGISTRO");
        assertBlocked(write(forbiddenAddition),
                LegalManifestIssueCode.REPLACEMENT_MAPPING_INVALID);

        ObjectNode noExit = fixture("retire-valid-v1/editorial-plan.json");
        noExit.withArray("documentRetirements").removeAll();
        noExit.withArray("requirementRetirements").removeAll();
        assertBlocked(write(noExit), LegalManifestIssueCode.REPLACEMENT_MAPPING_INVALID);
    }

    @Test
    void rejectsDuplicateOrCrossCategoryVersionIdsAndRequirementSelfCycles()
            throws IOException {
        ObjectNode duplicateTarget = fixture("replace-valid-v1/editorial-plan.json");
        ((ObjectNode) duplicateTarget.path("documentAdditions").get(0)).put(
                "documentVersionId",
                duplicateTarget.path("documentReplacementBatches").get(0)
                        .path("successors").get(0).path("documentVersionId").textValue());
        assertBlocked(write(duplicateTarget),
                LegalManifestIssueCode.REPLACEMENT_MAPPING_INVALID);

        ObjectNode crossSideWithoutReuse = fixture("replace-valid-v1/editorial-plan.json");
        ((ObjectNode) crossSideWithoutReuse.path("documentRetirements").get(0)).put(
                "documentVersionId",
                crossSideWithoutReuse.path("documentAdditions").get(0)
                        .path("documentVersionId").textValue());
        assertBlocked(write(crossSideWithoutReuse),
                LegalManifestIssueCode.REPLACEMENT_MAPPING_INVALID);

        ObjectNode requirementCycle = fixture("replace-valid-v1/editorial-plan.json");
        ((ObjectNode) requirementCycle.path("requirementReplacements").get(0)
                .path("successor")).put(
                "requirementVersionId",
                requirementCycle.path("requirementReplacements").get(0)
                        .path("predecessor").path("requirementVersionId").textValue());
        assertBlocked(write(requirementCycle),
                LegalManifestIssueCode.REPLACEMENT_MAPPING_INVALID);
    }

    @Test
    void appliesDocumentCapacityPerSourceAndTargetAcrossAllCollections()
            throws IOException {
        ObjectNode plan = fixture("replace-valid-v1/editorial-plan.json");
        clearAllCollections(plan);
        ArrayNode additions = plan.withArray("documentAdditions");
        for (int index = 0; index < LegalEditorialPlanLimits.MAX_DOCUMENTS; index++) {
            additions.addObject()
                    .put("documentVersionId", uuid(10_000 + index))
                    .put("sha256", "a".repeat(64))
                    .putArray("contexts").add("REGISTRO");
        }
        ObjectNode batch = plan.withArray("documentReplacementBatches").addObject();
        batch.put("replacementBatchId", uuid(20_000));
        batch.putArray("contexts").add("USO_CONTINUADO");
        batch.putArray("predecessors").addObject()
                .put("documentVersionId", uuid(20_001))
                .put("sha256", "b".repeat(64));
        batch.putArray("successors").addObject()
                .put("documentVersionId", uuid(20_002))
                .put("sha256", "c".repeat(64));

        assertBlocked(write(plan), LegalManifestIssueCode.REPLACEMENT_MAPPING_INVALID);
    }

    @Test
    void rejectsAUnicodeBoundarySpaceWithoutSilentlyTrimmingTheReason()
            throws IOException {
        ObjectNode plan = fixture("retire-valid-v1/editorial-plan.json");
        ((ObjectNode) plan.path("documentRetirements").get(0))
                .put("reason", "\u00A0Retiro explícito");

        LegalManifestValidation<ValidatedEditorialPlan> result = validator.validate(write(plan));

        assertThat(result.status()).isEqualTo(LegalManifestStatus.BLOCKED);
        assertThat(result.issues())
                .extracting(LegalManifestIssue::code)
                .containsAnyOf(
                        LegalManifestIssueCode.RETIREMENT_REASON_REQUIRED,
                        LegalManifestIssueCode.EDITORIAL_PLAN_SCHEMA_INVALID);
    }

    private Path write(ObjectNode plan) throws IOException {
        Path path = temporaryDirectory.resolve(ConfinedEditorialPlanReader.PLAN_FILENAME);
        Files.write(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(plan));
        return path;
    }

    private void assertBlocked(Path path, LegalManifestIssueCode expectedCode) {
        LegalManifestValidation<ValidatedEditorialPlan> result = validator.validate(path);
        assertThat(result.status()).isEqualTo(LegalManifestStatus.BLOCKED);
        assertThat(result.value()).isEmpty();
        assertThat(result.issues())
                .extracting(LegalManifestIssue::code)
                .contains(expectedCode);
    }

    private static void clearAllCollections(ObjectNode plan) {
        for (String field : new String[]{
                "documentAdditions",
                "documentReuses",
                "documentReplacementBatches",
                "documentRetirements",
                "requirementAdditions",
                "requirementReuses",
                "requirementReplacements",
                "requirementRetirements"}) {
            plan.withArray(field).removeAll();
        }
    }

    private static String uuid(int value) {
        return new UUID(0L, value).toString();
    }

    private static ObjectNode fixture(String relativePath) throws IOException {
        try (InputStream input = LegalEditorialPlanValidatorTest.class.getResourceAsStream(
                "/legal/editorial/" + relativePath)) {
            assertThat(input).isNotNull();
            return (ObjectNode) JSON.readTree(input);
        }
    }
}
