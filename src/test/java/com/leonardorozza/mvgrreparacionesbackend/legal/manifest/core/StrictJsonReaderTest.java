package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StrictJsonReaderTest {

    private final StrictJsonReader reader = new StrictJsonReader();

    @Test
    void acceptsOnlyACompleteStrictDocumentAndPreservesItsText() {
        String json = """
                {
                  "a": 1,
                  "emoji": "😀"
                }
                """;

        LegalManifestValidation<StrictJsonReader.StrictJsonDocument> result = read(json);

        assertThat(result.status()).isEqualTo(LegalManifestStatus.PASS);
        assertThat(result.issues()).isEmpty();
        StrictJsonReader.StrictJsonDocument document = result.value().orElseThrow();
        assertThat(document.text()).isEqualTo(json);
        assertThat(document.root().path("a").asInt()).isEqualTo(1);
        assertThat(document.root().path("emoji").asText()).isEqualTo("😀");
        assertThat(document.profile()).isEqualTo(StrictJsonReader.ExternalJsonProfile.MANIFEST);
        assertThat(document.safeLocation()).isEqualTo(StrictJsonReader.DEFAULT_LOCATION);

        ((ObjectNode) document.root()).put("mutated", true);
        assertThat(document.root().has("mutated")).isFalse();
    }

    @Test
    void freezesParserLimitsDuplicateDetectionAndAllJsonExtensionsOff() {
        var constraints = reader.readConstraints();
        var editorialConstraints = reader.editorialPlanReadConstraints();

        assertThat(constraints.getMaxDocumentLength())
                .isEqualTo(LegalManifestLimits.MAX_MANIFEST_BYTES);
        assertThat(constraints.getMaxTokenCount())
                .isEqualTo(LegalManifestLimits.MAX_JSON_TOKENS);
        assertThat(constraints.getMaxNestingDepth())
                .isEqualTo(LegalManifestLimits.MAX_JSON_DEPTH);
        assertThat(constraints.getMaxStringLength())
                .isEqualTo(LegalManifestLimits.MAX_JSON_STRING_LENGTH);
        assertThat(constraints.getMaxNameLength())
                .isEqualTo(LegalManifestLimits.MAX_JSON_NAME_LENGTH);
        assertThat(constraints.getMaxNumberLength())
                .isEqualTo(LegalManifestLimits.MAX_JSON_NUMBER_LENGTH);
        assertThat(editorialConstraints.getMaxDocumentLength())
                .isEqualTo(LegalEditorialPlanLimits.MAX_PLAN_BYTES);
        assertThat(editorialConstraints.getMaxTokenCount())
                .isEqualTo(LegalEditorialPlanLimits.MAX_JSON_TOKENS);
        assertThat(editorialConstraints.getMaxNestingDepth())
                .isEqualTo(LegalEditorialPlanLimits.MAX_JSON_DEPTH);
        assertThat(editorialConstraints.getMaxStringLength())
                .isEqualTo(LegalEditorialPlanLimits.MAX_JSON_STRING_LENGTH);
        assertThat(editorialConstraints.getMaxNameLength())
                .isEqualTo(LegalEditorialPlanLimits.MAX_JSON_NAME_LENGTH);
        assertThat(editorialConstraints.getMaxNumberLength())
                .isEqualTo(LegalEditorialPlanLimits.MAX_JSON_NUMBER_LENGTH);
        assertThat(reader.isEnabled(StreamReadFeature.STRICT_DUPLICATE_DETECTION)).isTrue();
        assertThat(reader.isEnabled(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)).isTrue();
        assertThat(reader.isEnabled(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)).isTrue();
        assertThat(JsonReadFeature.values())
                .allSatisfy(feature -> assertThat(reader.isEnabled(feature)).isFalse());
    }

    @Test
    void keepsOnlyTheDefaultLocationEntryPointPublic() throws NoSuchMethodException {
        int defaultModifiers = StrictJsonReader.class
                .getDeclaredMethod("read", byte[].class)
                .getModifiers();
        int locationAwareModifiers = StrictJsonReader.class
                .getDeclaredMethod("read", byte[].class, String.class)
                .getModifiers();

        assertThat(Modifier.isPublic(defaultModifiers)).isTrue();
        assertThat(locationAwareModifiers
                & (Modifier.PUBLIC | Modifier.PROTECTED | Modifier.PRIVATE)).isZero();
        int editorialPlanModifiers = StrictJsonReader.class
                .getDeclaredMethod("readEditorialPlan", byte[].class)
                .getModifiers();
        assertThat(editorialPlanModifiers
                & (Modifier.PUBLIC | Modifier.PROTECTED | Modifier.PRIVATE)).isZero();
    }

    @Test
    void appliesTheClosedEditorialPlanIssueProfileWithoutChangingManifestCodes() {
        LegalManifestValidation<?> manifest = reader.read(null);
        LegalManifestValidation<?> plan = reader.readEditorialPlan(null);

        assertThat(manifest.issues())
                .extracting(LegalManifestIssue::code)
                .containsExactly(LegalManifestIssueCode.MANIFEST_REQUIRED);
        assertThat(plan.issues())
                .extracting(LegalManifestIssue::code)
                .containsExactly(LegalManifestIssueCode.EDITORIAL_PLAN_REQUIRED);
        assertThat(plan.issues())
                .extracting(LegalManifestIssue::location)
                .containsOnly(ConfinedEditorialPlanReader.PLAN_FILENAME);

        LegalManifestValidation<?> malformed = reader.readEditorialPlan(
                "{\"a\":1,\"a\":2}".getBytes(StandardCharsets.UTF_8));
        assertThat(malformed.issues())
                .extracting(LegalManifestIssue::code)
                .containsExactly(LegalManifestIssueCode.EDITORIAL_PLAN_JSON_INVALID);

        StrictJsonReader.StrictJsonDocument document = reader.readEditorialPlan(
                "{}".getBytes(StandardCharsets.UTF_8)).value().orElseThrow();
        assertThat(document.profile())
                .isEqualTo(StrictJsonReader.ExternalJsonProfile.EDITORIAL_PLAN);
        assertThat(document.safeLocation()).isEqualTo(StrictJsonReader.EDITORIAL_PLAN_LOCATION);
    }

    @Test
    void rejectsMissingOversizedMalformedUtf8AndBomBeforeParsing() {
        assertBlocked(reader.read(null), LegalManifestIssueCode.MANIFEST_REQUIRED);
        assertBlocked(reader.read(new byte[0]), LegalManifestIssueCode.MANIFEST_REQUIRED);
        assertBlocked(
                reader.read(new byte[LegalManifestLimits.MAX_MANIFEST_BYTES + 1]),
                LegalManifestIssueCode.MANIFEST_SIZE_LIMIT_EXCEEDED);
        assertBlocked(
                reader.read(new byte[]{(byte) 0xC3, 0x28}),
                LegalManifestIssueCode.MANIFEST_UTF8_INVALID);
        assertBlocked(
                reader.read(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, '{', '}'}),
                LegalManifestIssueCode.MANIFEST_BOM_FORBIDDEN);
    }

    @Test
    void rejectsRawCrAndNonNfcTextWithDeterministicIssues() {
        LegalManifestValidation<?> result = read("{\r\n\"value\":\"e\u0301\"}");

        assertThat(result.status()).isEqualTo(LegalManifestStatus.BLOCKED);
        assertThat(result.issues())
                .extracting(LegalManifestIssue::code)
                .containsExactly(
                        LegalManifestIssueCode.MANIFEST_CR_FORBIDDEN,
                        LegalManifestIssueCode.MANIFEST_NFC_REQUIRED);
        assertThat(result.issues())
                .extracting(LegalManifestIssue::location)
                .containsOnly(StrictJsonReader.DEFAULT_LOCATION);
    }

    @Test
    void rejectsEscapedNonNfcInFieldNamesAndStringValues() {
        String escapedNfd = unicodeEscape("0065") + unicodeEscape("0301");

        assertBlocked(
                read("{\"" + escapedNfd + "\":1}"),
                LegalManifestIssueCode.MANIFEST_NFC_REQUIRED);
        assertBlocked(
                read("{\"value\":\"" + escapedNfd + "\"}"),
                LegalManifestIssueCode.MANIFEST_NFC_REQUIRED);
    }

    @Test
    void rejectsEscapedUnpairedSurrogatesInFieldNamesAndStringValues() {
        String highSurrogate = unicodeEscape("D800");
        String lowSurrogate = unicodeEscape("DC00");

        assertBlocked(
                read("{\"" + highSurrogate + "\":1}"),
                LegalManifestIssueCode.MANIFEST_SURROGATE_INVALID);
        assertBlocked(
                read("{\"value\":\"" + lowSurrogate + "\"}"),
                LegalManifestIssueCode.MANIFEST_SURROGATE_INVALID);
        assertThat(read("{\"value\":\""
                + unicodeEscape("D83D") + unicodeEscape("DE00") + "\"}").passed()).isTrue();
    }

    @Test
    void rejectsUnicodeNoncharactersRawAndEscapedInFieldNamesAndStringValues() {
        String rawAstralNoncharacter = new String(Character.toChars(0x1FFFE));
        String rawLastNoncharacter = new String(Character.toChars(0x10FFFF));
        List<String> documents = List.of(
                "{\"\uFDD0\":1}",
                "{\"value\":\"\uFFFF\"}",
                "{\"" + rawAstralNoncharacter + "\":1}",
                "{\"value\":\"" + rawLastNoncharacter + "\"}",
                "{\"" + unicodeEscape("FDD0") + "\":1}",
                "{\"value\":\"" + unicodeEscape("FFFF") + "\"}",
                "{\"" + unicodeEscape("D83F") + unicodeEscape("DFFE") + "\":1}",
                "{\"value\":\"" + unicodeEscape("D83F")
                        + unicodeEscape("DFFF") + "\"}");

        for (String document : documents) {
            assertBlocked(
                    read(document),
                    LegalManifestIssueCode.MANIFEST_UNICODE_NONCHARACTER_FORBIDDEN);
        }
    }

    @Test
    void rejectsCrDecodedFromAJsonEscape() {
        assertBlocked(
                read("{\"value\":\"" + "\\" + "r\"}"),
                LegalManifestIssueCode.MANIFEST_CR_FORBIDDEN);
    }

    @Test
    void rejectsDuplicateNamesIncludingEscapedAndPreviousNull() {
        assertBlocked(
                read("{\"a\":null,\"a\":1}"),
                LegalManifestIssueCode.MANIFEST_JSON_INVALID);
        assertBlocked(
                read("{\"a\":null,\"" + unicodeEscape("0061") + "\":1}"),
                LegalManifestIssueCode.MANIFEST_JSON_INVALID);
    }

    @Test
    void rejectsNumbersOutsideTheFiniteIJsonRangeBeforeCanonicalization() {
        assertBlocked(
                read("{\"number\":1e400}"),
                LegalManifestIssueCode.MANIFEST_IJSON_NUMBER_INVALID);
        assertBlocked(
                read("{\"number\":-1e400}"),
                LegalManifestIssueCode.MANIFEST_IJSON_NUMBER_INVALID);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{} true",
            "/*comment*/ {}",
            "# comment\n{}",
            "{'a':1}",
            "{unquoted:1}",
            "{\"a\":1,}",
            "{\"a\":NaN}",
            "{\"a\":Infinity}",
            "{\"a\":+1}",
            "{\"a\":.5}",
            "{\"a\":1.}",
            "{\"a\":01}",
            "[1,,2]"
    })
    void rejectsTrailingContentAndNonStandardJsonExtensions(String json) {
        assertBlocked(read(json), LegalManifestIssueCode.MANIFEST_JSON_INVALID);
    }

    @Test
    void acceptsExactDepthNameNumberAndTokenLimitsAndRejectsTheNextValue() {
        String exactDepth = "[".repeat(LegalManifestLimits.MAX_JSON_DEPTH)
                + "0"
                + "]".repeat(LegalManifestLimits.MAX_JSON_DEPTH);
        String tooDeep = "[".repeat(LegalManifestLimits.MAX_JSON_DEPTH + 1)
                + "0"
                + "]".repeat(LegalManifestLimits.MAX_JSON_DEPTH + 1);
        String exactName = "a".repeat(LegalManifestLimits.MAX_JSON_NAME_LENGTH);
        String longName = "a".repeat(LegalManifestLimits.MAX_JSON_NAME_LENGTH + 1);
        String exactNumber = "1".repeat(LegalManifestLimits.MAX_JSON_NUMBER_LENGTH);
        String longNumber = "1".repeat(LegalManifestLimits.MAX_JSON_NUMBER_LENGTH + 1);
        String exactTokenBudget = numericArray((int) LegalManifestLimits.MAX_JSON_TOKENS - 2);
        String tooManyTokens = numericArray((int) LegalManifestLimits.MAX_JSON_TOKENS - 1);

        assertThat(read(exactDepth).passed()).isTrue();
        assertThat(read("{\"" + exactName + "\":1}").passed()).isTrue();
        assertThat(read("{\"number\":" + exactNumber + "}").passed()).isTrue();
        assertThat(read(exactTokenBudget).passed()).isTrue();

        assertBlocked(read(tooDeep), LegalManifestIssueCode.MANIFEST_JSON_LIMIT_EXCEEDED);
        assertBlocked(
                read("{\"" + longName + "\":1}"),
                LegalManifestIssueCode.MANIFEST_JSON_LIMIT_EXCEEDED);
        assertBlocked(
                read("{\"number\":" + longNumber + "}"),
                LegalManifestIssueCode.MANIFEST_JSON_LIMIT_EXCEEDED);
        assertBlocked(
                read(tooManyTokens),
                LegalManifestIssueCode.MANIFEST_JSON_LIMIT_EXCEEDED);
    }

    @Test
    void acceptsTheExactManifestByteLimitAndRejectsTheNextByte() {
        String exactLimit = "\""
                + "a".repeat(LegalManifestLimits.MAX_MANIFEST_BYTES - 2)
                + "\"";

        assertThat(LegalManifestLimits.MAX_JSON_STRING_LENGTH)
                .isEqualTo(LegalManifestLimits.MAX_MANIFEST_BYTES);
        assertThat(exactLimit.getBytes(StandardCharsets.UTF_8))
                .hasSize(LegalManifestLimits.MAX_MANIFEST_BYTES);
        assertThat(read(exactLimit).status()).isEqualTo(LegalManifestStatus.PASS);
        assertBlocked(
                read(exactLimit + " "),
                LegalManifestIssueCode.MANIFEST_SIZE_LIMIT_EXCEEDED);
    }

    @Test
    void explicitResultNeverUsesNullToRepresentPassAndBoundsIssues() {
        assertThatThrownBy(() -> LegalManifestValidation.pass(null))
                .isInstanceOf(NullPointerException.class);

        List<LegalManifestIssue> candidates = new ArrayList<>();
        for (int index = 0; index < LegalManifestLimits.MAX_EXPOSED_ISSUES + 5; index++) {
            candidates.add(LegalManifestIssue.at(
                    LegalManifestIssueCode.MANIFEST_JSON_INVALID,
                    "publication-manifest.json#issue-" + String.format("%03d", index)));
        }
        candidates.add(candidates.get(0));
        candidates.add(LegalManifestIssue.at(
                LegalManifestIssueCode.MANIFEST_JSON_READER_ERROR,
                StrictJsonReader.DEFAULT_LOCATION));

        LegalManifestValidation<Object> result = LegalManifestValidation.failure(candidates);

        assertThat(result.status()).isEqualTo(LegalManifestStatus.ERROR);
        assertThat(result.value()).isEmpty();
        assertThat(result.issues()).hasSize(LegalManifestLimits.MAX_EXPOSED_ISSUES);
        assertThat(result.omittedIssueCount()).isEqualTo(6);
        assertThat(result.issues().getFirst().code())
                .isEqualTo(LegalManifestIssueCode.MANIFEST_JSON_READER_ERROR);
        assertThatThrownBy(() -> result.issues().add(candidates.get(0)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void issueTaxonomyKeepsUnsupportedInputSeparateFromInternalModelDrift() {
        assertThat(LegalManifestIssueCode.MANIFEST_LOCALE_UNSUPPORTED.severity())
                .isEqualTo(LegalManifestStatus.BLOCKED);
        assertThat(LegalManifestIssueCode.MANIFEST_MODEL_MAPPING_ERROR.severity())
                .isEqualTo(LegalManifestStatus.ERROR);
    }

    @Test
    void issueLocationsCannotExposeAbsoluteOrEscapingPaths() {
        assertThatThrownBy(() -> LegalManifestIssue.at(
                LegalManifestIssueCode.MANIFEST_JSON_INVALID,
                "/private/release/publication-manifest.json"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LegalManifestIssue.at(
                LegalManifestIssueCode.MANIFEST_JSON_INVALID,
                "release/../secret"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private LegalManifestValidation<StrictJsonReader.StrictJsonDocument> read(String json) {
        return reader.read(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String unicodeEscape(String hexadecimalCodeUnit) {
        return "\\" + "u" + hexadecimalCodeUnit;
    }

    private static String numericArray(int elementCount) {
        StringBuilder json = new StringBuilder(elementCount * 2 + 1).append('[');
        for (int index = 0; index < elementCount; index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append('0');
        }
        return json.append(']').toString();
    }

    private static void assertBlocked(
            LegalManifestValidation<?> result,
            LegalManifestIssueCode expectedCode) {
        assertThat(result.status()).isEqualTo(LegalManifestStatus.BLOCKED);
        assertThat(result.value()).isEmpty();
        assertThat(result.issues())
                .extracting(LegalManifestIssue::code)
                .contains(expectedCode);
    }
}
