package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialPlanParser.ParsedEditorialPlan;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalEditorialPlanV1.OperationType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class LegalEditorialPlanParserTest {

    private final LegalEditorialPlanParser parser = new LegalEditorialPlanParser();

    @Test
    void mapsTheExactV1ShapeAndPreservesRawCanonicalIdentity() throws IOException {
        LegalManifestValidation<ParsedEditorialPlan> result = parser.parse(
                fixture("replace-valid-v1/editorial-plan.json"));

        assertThat(result.status()).isEqualTo(LegalManifestStatus.PASS);
        ParsedEditorialPlan parsed = result.value().orElseThrow();
        assertThat(parsed.plan().operationType()).isEqualTo(OperationType.REPLACE);
        assertThat(parsed.plan().documentReplacementBatches()).hasSize(1);
        assertThat(parsed.plan().documentReplacementBatches().getFirst().predecessors())
                .hasSize(2);
        assertThat(parsed.plan().requirementReplacements()).hasSize(1);
        assertThat(parsed.canonicalJson()).doesNotContain("\n", "  ");
        assertThat(parsed.editorialPlanSha256()).matches("[0-9a-f]{64}");
    }

    @Test
    void returnsPlanSpecificCodesForEveryStrictJsonBoundary() {
        assertBlocked(parser.parse(null), LegalManifestIssueCode.EDITORIAL_PLAN_REQUIRED);
        assertBlocked(
                parse("{\"a\":1,\"a\":2}"),
                LegalManifestIssueCode.EDITORIAL_PLAN_JSON_INVALID);
        assertBlocked(
                parser.parse(new byte[]{(byte) 0xC3, 0x28}),
                LegalManifestIssueCode.EDITORIAL_PLAN_UTF8_INVALID);
        assertBlocked(
                parse("{\r\n\"schemaVersion\":1}"),
                LegalManifestIssueCode.EDITORIAL_PLAN_CR_FORBIDDEN);
        assertBlocked(
                parse("{\"value\":\"é\"}"),
                LegalManifestIssueCode.EDITORIAL_PLAN_NFC_REQUIRED);
    }

    @Test
    void rejectsUnknownMissingAndNonCanonicalFieldsAtTheLocalSchemaBoundary()
            throws IOException {
        String valid = new String(
                fixture("retire-valid-v1/editorial-plan.json"),
                StandardCharsets.UTF_8);

        assertBlocked(
                parse(valid.replaceFirst(
                        "\\{",
                        "{\\\"unknown\\\":true,")),
                LegalManifestIssueCode.EDITORIAL_PLAN_SCHEMA_INVALID);
        assertBlocked(
                parse(valid.replaceFirst(
                        "\\s*\\\"documentAdditions\\\"\\s*:\\s*\\[\\],",
                        "")),
                LegalManifestIssueCode.EDITORIAL_PLAN_SCHEMA_INVALID);
        assertBlocked(
                parse(valid.replace(
                        "00000000-0000-0000-0000-000000000011",
                        "00000000-0000-0000-0000-00000000001A")),
                LegalManifestIssueCode.EDITORIAL_PLAN_SCHEMA_INVALID);
    }

    @Test
    void propertyOrderAndWhitespaceDoNotChangeTheRawRfc8785Identity() {
        String first = completeMinimalReplace(
                "\"schemaVersion\":1,\"operationId\":\"00000000-0000-0000-0000-000000000001\"");
        String second = completeMinimalReplace(
                "\"operationId\": \"00000000-0000-0000-0000-000000000001\",\n"
                        + "\"schemaVersion\": 1");

        ParsedEditorialPlan parsedFirst = parser.parse(first.getBytes(StandardCharsets.UTF_8))
                .value().orElseThrow();
        ParsedEditorialPlan parsedSecond = parser.parse(second.getBytes(StandardCharsets.UTF_8))
                .value().orElseThrow();

        assertThat(parsedFirst.canonicalJson()).isEqualTo(parsedSecond.canonicalJson());
        assertThat(parsedFirst.editorialPlanSha256())
                .isEqualTo(parsedSecond.editorialPlanSha256());
    }

    private LegalManifestValidation<ParsedEditorialPlan> parse(String json) {
        return parser.parse(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String completeMinimalReplace(String prefix) {
        return "{" + prefix + ","
                + "\"operationType\":\"REPLACE\","
                + "\"expectedCurrentPublicationId\":\"source-v1\","
                + "\"expectedCurrentManifestSha256\":\"" + "a".repeat(64) + "\","
                + "\"expectedEditorialStateFingerprint\":\"sha256:"
                + "b".repeat(64) + "\","
                + "\"targetPublicationId\":\"target-v1\","
                + "\"targetManifestSha256\":\"" + "c".repeat(64) + "\","
                + "\"documentAdditions\":[{"
                + "\"documentVersionId\":\"00000000-0000-0000-0000-000000000002\","
                + "\"sha256\":\"" + "d".repeat(64) + "\","
                + "\"contexts\":[\"REGISTRO\"]}],"
                + "\"documentReuses\":[],"
                + "\"documentReplacementBatches\":[],"
                + "\"documentRetirements\":[],"
                + "\"requirementAdditions\":[],"
                + "\"requirementReuses\":[],"
                + "\"requirementReplacements\":[],"
                + "\"requirementRetirements\":[],"
                + "\"expectedReadinessAfter\":\"READY\","
                + "\"acknowledgeFailClosedGap\":false}";
    }

    private static byte[] fixture(String relativePath) throws IOException {
        try (InputStream input = LegalEditorialPlanParserTest.class.getResourceAsStream(
                "/legal/editorial/" + relativePath)) {
            assertThat(input).isNotNull();
            return input.readAllBytes();
        }
    }

    private static void assertBlocked(
            LegalManifestValidation<?> result,
            LegalManifestIssueCode code) {
        assertThat(result.status()).isEqualTo(LegalManifestStatus.BLOCKED);
        assertThat(result.value()).isEmpty();
        assertThat(result.issues())
                .extracting(LegalManifestIssue::code)
                .contains(code);
        assertThat(result.issues())
                .extracting(LegalManifestIssue::location)
                .containsOnly(ConfinedEditorialPlanReader.PLAN_FILENAME);
    }
}
