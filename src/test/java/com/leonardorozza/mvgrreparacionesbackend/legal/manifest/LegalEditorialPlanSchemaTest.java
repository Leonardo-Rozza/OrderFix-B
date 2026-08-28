package com.leonardorozza.mvgrreparacionesbackend.legal.manifest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialPlanLimits;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.networknt.schema.Error;
import com.networknt.schema.SpecificationVersion;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegalEditorialPlanSchemaTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> REQUIRED_ARRAYS = List.of(
            "documentAdditions",
            "documentReuses",
            "documentReplacementBatches",
            "documentRetirements",
            "requirementAdditions",
            "requirementReuses",
            "requirementReplacements",
            "requirementRetirements");

    @Test
    void freezesTheClasspathResourceBySizeAndSha256() throws IOException {
        byte[] bytes;
        try (InputStream input = LegalEditorialPlanSchema.class
                .getResourceAsStream(LegalEditorialPlanSchema.RESOURCE_PATH)) {
            assertThat(input).isNotNull();
            bytes = input.readAllBytes();
        }

        assertThat(bytes).hasSize(LegalEditorialPlanSchema.EXPECTED_SIZE_BYTES);
        assertThat(sha256(bytes)).isEqualTo(LegalEditorialPlanSchema.EXPECTED_SHA256);
    }

    @Test
    void initializesTheOfflineDraft202012SchemaAndAcceptsTheFrozenFlatShape()
            throws JsonProcessingException {
        var schema = LegalEditorialPlanSchema.schema();
        ObjectNode plan = validPlan();

        assertThat(schema.getSchemaContext().getDialect().getSpecificationVersion())
                .isEqualTo(SpecificationVersion.DRAFT_2020_12);
        assertThat(schema.getSchemaContext().getSchemaRegistryConfig().getFormatAssertionsEnabled())
                .isTrue();
        assertThat(schema.getValidators()).isNotEmpty();
        assertThat(plan).hasSize(18);
        assertThat(plan.has("$schema")).isFalse();
        assertThat(LegalEditorialPlanSchema.validate(plan)).isEmpty();
    }

    @Test
    void requiresAllEightExplicitArraysAndRejectsEveryUnknownField()
            throws JsonProcessingException {
        for (String requiredArray : REQUIRED_ARRAYS) {
            ObjectNode plan = validPlan();
            plan.remove(requiredArray);

            assertThat(LegalEditorialPlanSchema.validate(plan))
                    .extracting(Error::getKeyword)
                    .contains("required");
        }

        ObjectNode rootUnknown = validPlan();
        rootUnknown.put("$schema", "https://example.invalid/editorial-plan.schema.json");
        assertThat(LegalEditorialPlanSchema.validate(rootUnknown))
                .extracting(Error::getKeyword)
                .contains("additionalProperties");

        for (String arrayName : REQUIRED_ARRAYS) {
            ObjectNode plan = validPlan();
            ObjectNode item = (ObjectNode) plan.path(arrayName).get(0);
            item.put("unexpected", true);

            assertThat(LegalEditorialPlanSchema.validate(plan))
                    .as(arrayName)
                    .extracting(Error::getKeyword)
                    .contains("additionalProperties");
        }

        ObjectNode nestedRefUnknown = validPlan();
        ((ObjectNode) nestedRefUnknown.path("documentReplacementBatches")
                .get(0)
                .path("predecessors")
                .get(0)).put("unexpected", true);
        assertThat(LegalEditorialPlanSchema.validate(nestedRefUnknown))
                .extracting(Error::getKeyword)
                .contains("additionalProperties");
    }

    @Test
    void rejectsFutureVersionsUnsupportedEnumsAndNonNormalizedIdentifiers()
            throws JsonProcessingException {
        assertInvalidMutation("const", plan -> plan.put("schemaVersion", 2));
        assertInvalidMutation("enum", plan -> plan.put("operationType", "PROMOTE"));
        assertInvalidMutation("enum", plan -> plan.put("expectedReadinessAfter", "ERROR"));
        assertInvalidMutation("pattern", plan -> plan.put(
                "operationId",
                "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA"));
        assertInvalidMutation("pattern", plan -> plan.put(
                "expectedCurrentManifestSha256",
                "A".repeat(64)));
        assertInvalidMutation("pattern", plan -> plan.put(
                "expectedEditorialStateFingerprint",
                "c".repeat(64)));
        assertInvalidMutation("pattern", plan -> plan.put(
                "targetPublicationId",
                "Release-2026-08-25"));

        assertInvalidMutation("pattern", plan -> ((ObjectNode) plan.path("requirementAdditions")
                .get(0)).put("statementSha256", "F".repeat(64)));
    }

    @Test
    void enforcesEveryTopLevelCollectionLimitAtItsExactBoundary()
            throws JsonProcessingException {
        for (String arrayName : REQUIRED_ARRAYS) {
            int maximum = arrayName.startsWith("document")
                    ? LegalEditorialPlanLimits.MAX_DOCUMENTS
                    : LegalEditorialPlanLimits.MAX_REQUIREMENTS;
            if ("documentReplacementBatches".equals(arrayName)) {
                maximum = LegalEditorialPlanLimits.MAX_REPLACEMENT_BATCHES;
            }

            ObjectNode plan = validPlan();
            ArrayNode items = (ArrayNode) plan.path(arrayName);
            ObjectNode template = ((ObjectNode) items.get(0)).deepCopy();
            items.removeAll();
            for (int index = 0; index < maximum; index++) {
                items.add(uniqueItem(template, arrayName, index));
            }

            assertThat(LegalEditorialPlanSchema.validate(plan)).as(arrayName).isEmpty();

            items.add(uniqueItem(template, arrayName, maximum));
            assertThat(LegalEditorialPlanSchema.validate(plan))
                    .as(arrayName)
                    .extracting(Error::getKeyword)
                    .contains("maxItems");
        }
    }

    @Test
    void boundsNestedContextsAudiencesAndReplacementMembers()
            throws JsonProcessingException {
        ObjectNode plan = validPlan();
        ArrayNode contexts = (ArrayNode) plan.path("documentAdditions").get(0).path("contexts");
        contexts.removeAll();
        Arrays.stream(ContextoLegal.values()).map(Enum::name).forEach(contexts::add);
        assertThat(LegalEditorialPlanSchema.validate(plan)).isEmpty();

        contexts.add(ContextoLegal.REGISTRO.name());
        assertThat(LegalEditorialPlanSchema.validate(plan))
                .extracting(Error::getKeyword)
                .contains("maxItems", "uniqueItems");

        plan = validPlan();
        ArrayNode audiences = (ArrayNode) plan.path("requirementAdditions")
                .get(0)
                .path("audiences");
        audiences.removeAll();
        Arrays.stream(AudienciaLegal.values()).map(Enum::name).forEach(audiences::add);
        assertThat(LegalEditorialPlanSchema.validate(plan)).isEmpty();

        audiences.add(AudienciaLegal.USER.name());
        assertThat(LegalEditorialPlanSchema.validate(plan))
                .extracting(Error::getKeyword)
                .contains("maxItems", "uniqueItems");

        plan = validPlan();
        ArrayNode predecessors = (ArrayNode) plan.path("documentReplacementBatches")
                .get(0)
                .path("predecessors");
        ObjectNode predecessorTemplate = ((ObjectNode) predecessors.get(0)).deepCopy();
        predecessors.removeAll();
        for (int index = 0; index < LegalEditorialPlanLimits.MAX_DOCUMENTS; index++) {
            ObjectNode predecessor = predecessorTemplate.deepCopy();
            predecessor.put("documentVersionId", uuid(index));
            predecessors.add(predecessor);
        }
        assertThat(LegalEditorialPlanSchema.validate(plan)).isEmpty();

        ObjectNode excessPredecessor = predecessorTemplate.deepCopy();
        excessPredecessor.put(
                "documentVersionId",
                uuid(LegalEditorialPlanLimits.MAX_DOCUMENTS));
        predecessors.add(excessPredecessor);
        assertThat(LegalEditorialPlanSchema.validate(plan))
                .extracting(Error::getKeyword)
                .contains("maxItems");
    }

    @Test
    void enforcesTrimmedReasonAndUnicodeCodePointLimit()
            throws JsonProcessingException {
        ObjectNode plan = validPlan();
        ObjectNode retirement = (ObjectNode) plan.path("documentRetirements").get(0);
        retirement.put("reason", "😀".repeat(LegalEditorialPlanLimits.MAX_REASON_CODE_POINTS));
        assertThat(LegalEditorialPlanSchema.validate(plan)).isEmpty();

        retirement.put("reason", "😀".repeat(
                LegalEditorialPlanLimits.MAX_REASON_CODE_POINTS + 1));
        assertThat(LegalEditorialPlanSchema.validate(plan))
                .extracting(Error::getKeyword)
                .contains("maxLength");

        retirement.put("reason", " Retiro explícito");
        assertThat(LegalEditorialPlanSchema.validate(plan))
                .extracting(Error::getKeyword)
                .contains("pattern");

        retirement.put("reason", "Retiro explícito ");
        assertThat(LegalEditorialPlanSchema.validate(plan))
                .extracting(Error::getKeyword)
                .contains("pattern");

        retirement.put("reason", "Retiro" + Character.toString(0) + "inválido");
        assertThat(LegalEditorialPlanSchema.validate(plan))
                .extracting(Error::getKeyword)
                .contains("pattern");
    }

    @Test
    void keepsSchemaEnumsInParityWithThePersistenceEnums() throws IOException {
        JsonNode schema;
        try (InputStream input = LegalEditorialPlanSchema.class
                .getResourceAsStream(LegalEditorialPlanSchema.RESOURCE_PATH)) {
            assertThat(input).isNotNull();
            schema = JSON.readTree(input);
        }

        assertThat(enumValues(schema, "context"))
                .containsExactlyInAnyOrderElementsOf(enumNames(ContextoLegal.values()));
        assertThat(enumValues(schema, "audience"))
                .containsExactlyInAnyOrderElementsOf(enumNames(AudienciaLegal.values()));
    }

    @Test
    void blocksExternalSchemaReferencesWithoutNetworkResolution() {
        byte[] externalReference = """
                {
                  "$schema": "https://json-schema.org/draft/2020-12/schema",
                  "$ref": "https://example.invalid/never-fetch.json"
                }
                """.getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> LegalEditorialPlanSchema.compileSchema(externalReference))
                .isInstanceOf(RuntimeException.class);
    }

    private static ObjectNode uniqueItem(ObjectNode template, String arrayName, int index) {
        ObjectNode item = template.deepCopy();
        switch (arrayName) {
            case "documentAdditions", "documentReuses", "documentRetirements" ->
                    item.put("documentVersionId", uuid(index));
            case "documentReplacementBatches" -> item.put("replacementBatchId", uuid(index));
            case "requirementAdditions", "requirementReuses", "requirementRetirements" ->
                    item.put("requirementVersionId", uuid(index));
            case "requirementReplacements" -> {
                ((ObjectNode) item.path("predecessor"))
                        .put("requirementVersionId", uuid(index));
                ((ObjectNode) item.path("successor"))
                        .put("requirementVersionId", uuid(index + 10_000));
            }
            default -> throw new IllegalArgumentException("Unexpected array: " + arrayName);
        }
        return item;
    }

    private static void assertInvalidMutation(
            String expectedKeyword,
            PlanMutation mutation
    ) throws JsonProcessingException {
        ObjectNode plan = validPlan();
        mutation.apply(plan);

        assertThat(LegalEditorialPlanSchema.validate(plan))
                .extracting(Error::getKeyword)
                .contains(expectedKeyword);
    }

    private static Set<String> enumValues(JsonNode schema, String definition) {
        return JSON.convertValue(
                schema.path("$defs").path(definition).path("enum"),
                JSON.getTypeFactory().constructCollectionType(Set.class, String.class));
    }

    private static List<String> enumNames(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).collect(Collectors.toList());
    }

    private static String uuid(int index) {
        return new UUID(0L, index + 1L).toString();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private static ObjectNode validPlan() throws JsonProcessingException {
        return (ObjectNode) JSON.readTree("""
                {
                  "schemaVersion": 1,
                  "operationId": "00000000-0000-0000-0000-000000000001",
                  "operationType": "REPLACE",
                  "expectedCurrentPublicationId": "release-2026-08-24",
                  "expectedCurrentManifestSha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "expectedEditorialStateFingerprint": "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                  "targetPublicationId": "release-2026-08-25",
                  "targetManifestSha256": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                  "documentAdditions": [
                    {
                      "documentVersionId": "10000000-0000-0000-0000-000000000001",
                      "sha256": "1111111111111111111111111111111111111111111111111111111111111111",
                      "contexts": ["REGISTRO"]
                    }
                  ],
                  "documentReuses": [
                    {
                      "documentVersionId": "10000000-0000-0000-0000-000000000002",
                      "sha256": "2222222222222222222222222222222222222222222222222222222222222222",
                      "contexts": ["USO_CONTINUADO"]
                    }
                  ],
                  "documentReplacementBatches": [
                    {
                      "replacementBatchId": "20000000-0000-0000-0000-000000000001",
                      "contexts": ["CONTRATACION_PRO"],
                      "predecessors": [
                        {
                          "documentVersionId": "10000000-0000-0000-0000-000000000003",
                          "sha256": "3333333333333333333333333333333333333333333333333333333333333333"
                        }
                      ],
                      "successors": [
                        {
                          "documentVersionId": "10000000-0000-0000-0000-000000000004",
                          "sha256": "4444444444444444444444444444444444444444444444444444444444444444"
                        }
                      ]
                    }
                  ],
                  "documentRetirements": [
                    {
                      "documentVersionId": "10000000-0000-0000-0000-000000000005",
                      "sha256": "5555555555555555555555555555555555555555555555555555555555555555",
                      "contexts": ["CIERRE_CUENTA"],
                      "reason": "Retiro documental explícito"
                    }
                  ],
                  "requirementAdditions": [
                    {
                      "requirementVersionId": "30000000-0000-0000-0000-000000000001",
                      "statementSha256": "6666666666666666666666666666666666666666666666666666666666666666",
                      "context": "REGISTRO",
                      "audiences": ["ADMIN_TITULAR"]
                    }
                  ],
                  "requirementReuses": [
                    {
                      "requirementVersionId": "30000000-0000-0000-0000-000000000002",
                      "statementSha256": "7777777777777777777777777777777777777777777777777777777777777777",
                      "context": "USO_CONTINUADO",
                      "audiences": ["USER"]
                    }
                  ],
                  "requirementReplacements": [
                    {
                      "predecessor": {
                        "requirementVersionId": "30000000-0000-0000-0000-000000000003",
                        "statementSha256": "8888888888888888888888888888888888888888888888888888888888888888"
                      },
                      "successor": {
                        "requirementVersionId": "30000000-0000-0000-0000-000000000004",
                        "statementSha256": "9999999999999999999999999999999999999999999999999999999999999999"
                      },
                      "context": "PRIMER_INGRESO_EMPLEADO",
                      "audiences": ["ADMIN_TITULAR", "USER"]
                    }
                  ],
                  "requirementRetirements": [
                    {
                      "requirementVersionId": "30000000-0000-0000-0000-000000000005",
                      "statementSha256": "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
                      "context": "ARREPENTIMIENTO",
                      "audiences": ["USER"],
                      "reason": "Retiro de requisito explícito"
                    }
                  ],
                  "expectedReadinessAfter": "READY",
                  "acknowledgeFailClosedGap": false
                }
                """);
    }

    @FunctionalInterface
    private interface PlanMutation {
        void apply(ObjectNode plan);
    }
}
