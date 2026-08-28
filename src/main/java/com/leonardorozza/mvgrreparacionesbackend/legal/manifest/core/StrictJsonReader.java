package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Lector JSON secundario, aislado del ObjectMapper HTTP y configurado para entrada legal hostil.
 */
public final class StrictJsonReader {

    public static final String DEFAULT_LOCATION = "publication-manifest.json";
    static final String EDITORIAL_PLAN_LOCATION = "editorial-plan.json";

    private final ObjectMapper objectMapper;

    public StrictJsonReader() {
        this.objectMapper = createObjectMapper();
    }

    public LegalManifestValidation<StrictJsonDocument> read(byte[] rawBytes) {
        return read(
                rawBytes,
                DEFAULT_LOCATION,
                LegalManifestLimits.MAX_MANIFEST_BYTES,
                IssueProfile.MANIFEST);
    }

    LegalManifestValidation<StrictJsonDocument> read(
            byte[] rawBytes,
            String safeLocation) {
        return read(
                rawBytes,
                safeLocation,
                LegalManifestLimits.MAX_MANIFEST_BYTES,
                IssueProfile.MANIFEST);
    }

    LegalManifestValidation<StrictJsonDocument> readEditorialPlan(byte[] rawBytes) {
        return read(
                rawBytes,
                EDITORIAL_PLAN_LOCATION,
                LegalEditorialPlanLimits.MAX_PLAN_BYTES,
                IssueProfile.EDITORIAL_PLAN);
    }

    private LegalManifestValidation<StrictJsonDocument> read(
            byte[] rawBytes,
            String safeLocation,
            int maxBytes,
            IssueProfile issueProfile) {
        Objects.requireNonNull(safeLocation, "safeLocation");
        Objects.requireNonNull(issueProfile, "issueProfile");
        if (rawBytes == null || rawBytes.length == 0) {
            return failure(issueProfile.required, safeLocation);
        }
        if (rawBytes.length > maxBytes) {
            return failure(issueProfile.sizeLimitExceeded, safeLocation);
        }
        if (hasUtf8Bom(rawBytes)) {
            return failure(issueProfile.bomForbidden, safeLocation);
        }

        final String text;
        try {
            text = decodeUtf8(rawBytes);
        } catch (CharacterCodingException exception) {
            return failure(issueProfile.utf8Invalid, safeLocation);
        }

        EnumSet<LegalManifestIssueCode> textualIssues = EnumSet.noneOf(
                LegalManifestIssueCode.class);
        inspectUnicodeString(text, textualIssues, issueProfile);
        if (text.startsWith("\uFEFF")) {
            textualIssues.add(issueProfile.bomForbidden);
        }
        if (!textualIssues.isEmpty()) {
            return failures(textualIssues, safeLocation);
        }

        final JsonNode root;
        try {
            root = objectMapper.readTree(text);
        } catch (StreamConstraintsException exception) {
            return failure(issueProfile.jsonLimitExceeded, safeLocation);
        } catch (JsonProcessingException exception) {
            return failure(issueProfile.jsonInvalid, safeLocation);
        } catch (RuntimeException exception) {
            return failure(issueProfile.jsonReaderError, safeLocation);
        }

        if (root == null || root.isMissingNode()) {
            return failure(issueProfile.jsonInvalid, safeLocation);
        }

        EnumSet<LegalManifestIssueCode> semanticIssues = EnumSet.noneOf(
                LegalManifestIssueCode.class);
        inspectDecodedTree(root, semanticIssues, issueProfile);
        if (!semanticIssues.isEmpty()) {
            return failures(semanticIssues, safeLocation);
        }

        return LegalManifestValidation.pass(new StrictJsonDocument(text, root));
    }

    StreamReadConstraints readConstraints() {
        return objectMapper.getFactory().streamReadConstraints();
    }

    boolean isEnabled(JsonReadFeature feature) {
        return objectMapper.getFactory().isEnabled(feature.mappedFeature());
    }

    boolean isEnabled(StreamReadFeature feature) {
        return objectMapper.getFactory().isEnabled(feature.mappedFeature());
    }

    boolean isEnabled(DeserializationFeature feature) {
        return objectMapper.isEnabled(feature);
    }

    private static ObjectMapper createObjectMapper() {
        StreamReadConstraints constraints = StreamReadConstraints.builder()
                .maxDocumentLength(LegalManifestLimits.MAX_MANIFEST_BYTES)
                .maxTokenCount(LegalManifestLimits.MAX_JSON_TOKENS)
                .maxNestingDepth(LegalManifestLimits.MAX_JSON_DEPTH)
                .maxStringLength(LegalManifestLimits.MAX_JSON_STRING_LENGTH)
                .maxNameLength(LegalManifestLimits.MAX_JSON_NAME_LENGTH)
                .maxNumberLength(LegalManifestLimits.MAX_JSON_NUMBER_LENGTH)
                .build();

        var factoryBuilder = JsonFactory.builder()
                .streamReadConstraints(constraints)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
        for (JsonReadFeature feature : JsonReadFeature.values()) {
            factoryBuilder.disable(feature);
        }

        return JsonMapper.builder(factoryBuilder.build())
                .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .build();
    }

    private static String decodeUtf8(byte[] rawBytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(rawBytes))
                .toString();
    }

    private static boolean hasUtf8Bom(byte[] rawBytes) {
        return rawBytes.length >= 3
                && (rawBytes[0] & 0xFF) == 0xEF
                && (rawBytes[1] & 0xFF) == 0xBB
                && (rawBytes[2] & 0xFF) == 0xBF;
    }

    private static void inspectDecodedTree(
            JsonNode root,
            EnumSet<LegalManifestIssueCode> issues,
            IssueProfile issueProfile) {
        Deque<JsonNode> pending = new ArrayDeque<>();
        pending.add(root);

        while (!pending.isEmpty()) {
            JsonNode node = pending.removeFirst();
            if (node.isTextual()) {
                inspectUnicodeString(node.textValue(), issues, issueProfile);
            }
            if (node.isNumber() && !Double.isFinite(node.doubleValue())) {
                issues.add(issueProfile.ijsonNumberInvalid);
            }
            if (node.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    inspectUnicodeString(field.getKey(), issues, issueProfile);
                    pending.addLast(field.getValue());
                }
            } else if (node.isArray()) {
                node.forEach(pending::addLast);
            }
        }
    }

    private static void inspectUnicodeString(
            String value,
            EnumSet<LegalManifestIssueCode> issues,
            IssueProfile issueProfile) {
        if (value.indexOf('\r') >= 0) {
            issues.add(issueProfile.crForbidden);
        }
        if (!Normalizer.isNormalized(value, Normalizer.Form.NFC)) {
            issues.add(issueProfile.nfcRequired);
        }
        if (hasUnpairedSurrogate(value)) {
            issues.add(issueProfile.surrogateInvalid);
        }
        if (hasUnicodeNoncharacter(value)) {
            issues.add(issueProfile.unicodeNoncharacterForbidden);
        }
    }

    private static boolean hasUnpairedSurrogate(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    return true;
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasUnicodeNoncharacter(String value) {
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if ((codePoint >= 0xFDD0 && codePoint <= 0xFDEF)
                    || (codePoint & 0xFFFE) == 0xFFFE) {
                return true;
            }
            offset += Character.charCount(codePoint);
        }
        return false;
    }

    private static <T> LegalManifestValidation<T> failure(
            LegalManifestIssueCode code,
            String location) {
        return LegalManifestValidation.failure(LegalManifestIssue.at(code, location));
    }

    private static <T> LegalManifestValidation<T> failures(
            EnumSet<LegalManifestIssueCode> codes,
            String location) {
        List<LegalManifestIssue> issues = new ArrayList<>(codes.size());
        for (LegalManifestIssueCode code : codes) {
            issues.add(LegalManifestIssue.at(code, location));
        }
        return LegalManifestValidation.failure(issues);
    }

    private enum IssueProfile {
        MANIFEST(
                LegalManifestIssueCode.MANIFEST_REQUIRED,
                LegalManifestIssueCode.MANIFEST_SIZE_LIMIT_EXCEEDED,
                LegalManifestIssueCode.MANIFEST_UTF8_INVALID,
                LegalManifestIssueCode.MANIFEST_BOM_FORBIDDEN,
                LegalManifestIssueCode.MANIFEST_CR_FORBIDDEN,
                LegalManifestIssueCode.MANIFEST_NFC_REQUIRED,
                LegalManifestIssueCode.MANIFEST_SURROGATE_INVALID,
                LegalManifestIssueCode.MANIFEST_UNICODE_NONCHARACTER_FORBIDDEN,
                LegalManifestIssueCode.MANIFEST_JSON_LIMIT_EXCEEDED,
                LegalManifestIssueCode.MANIFEST_JSON_INVALID,
                LegalManifestIssueCode.MANIFEST_IJSON_NUMBER_INVALID,
                LegalManifestIssueCode.MANIFEST_JSON_READER_ERROR),
        EDITORIAL_PLAN(
                LegalManifestIssueCode.EDITORIAL_PLAN_REQUIRED,
                LegalManifestIssueCode.EDITORIAL_PLAN_SIZE_LIMIT_EXCEEDED,
                LegalManifestIssueCode.EDITORIAL_PLAN_UTF8_INVALID,
                LegalManifestIssueCode.EDITORIAL_PLAN_BOM_FORBIDDEN,
                LegalManifestIssueCode.EDITORIAL_PLAN_CR_FORBIDDEN,
                LegalManifestIssueCode.EDITORIAL_PLAN_NFC_REQUIRED,
                LegalManifestIssueCode.EDITORIAL_PLAN_SURROGATE_INVALID,
                LegalManifestIssueCode.EDITORIAL_PLAN_UNICODE_NONCHARACTER_FORBIDDEN,
                LegalManifestIssueCode.EDITORIAL_PLAN_JSON_LIMIT_EXCEEDED,
                LegalManifestIssueCode.EDITORIAL_PLAN_JSON_INVALID,
                LegalManifestIssueCode.EDITORIAL_PLAN_IJSON_NUMBER_INVALID,
                LegalManifestIssueCode.EDITORIAL_PLAN_JSON_READER_ERROR);

        private final LegalManifestIssueCode required;
        private final LegalManifestIssueCode sizeLimitExceeded;
        private final LegalManifestIssueCode utf8Invalid;
        private final LegalManifestIssueCode bomForbidden;
        private final LegalManifestIssueCode crForbidden;
        private final LegalManifestIssueCode nfcRequired;
        private final LegalManifestIssueCode surrogateInvalid;
        private final LegalManifestIssueCode unicodeNoncharacterForbidden;
        private final LegalManifestIssueCode jsonLimitExceeded;
        private final LegalManifestIssueCode jsonInvalid;
        private final LegalManifestIssueCode ijsonNumberInvalid;
        private final LegalManifestIssueCode jsonReaderError;

        IssueProfile(
                LegalManifestIssueCode required,
                LegalManifestIssueCode sizeLimitExceeded,
                LegalManifestIssueCode utf8Invalid,
                LegalManifestIssueCode bomForbidden,
                LegalManifestIssueCode crForbidden,
                LegalManifestIssueCode nfcRequired,
                LegalManifestIssueCode surrogateInvalid,
                LegalManifestIssueCode unicodeNoncharacterForbidden,
                LegalManifestIssueCode jsonLimitExceeded,
                LegalManifestIssueCode jsonInvalid,
                LegalManifestIssueCode ijsonNumberInvalid,
                LegalManifestIssueCode jsonReaderError) {
            this.required = required;
            this.sizeLimitExceeded = sizeLimitExceeded;
            this.utf8Invalid = utf8Invalid;
            this.bomForbidden = bomForbidden;
            this.crForbidden = crForbidden;
            this.nfcRequired = nfcRequired;
            this.surrogateInvalid = surrogateInvalid;
            this.unicodeNoncharacterForbidden = unicodeNoncharacterForbidden;
            this.jsonLimitExceeded = jsonLimitExceeded;
            this.jsonInvalid = jsonInvalid;
            this.ijsonNumberInvalid = ijsonNumberInvalid;
            this.jsonReaderError = jsonReaderError;
        }
    }

    /**
     * Documento cuya codificación, sintaxis JSON e invariantes Unicode ya fueron acreditadas.
     */
    public static final class StrictJsonDocument {

        private final String text;
        private final JsonNode root;

        private StrictJsonDocument(String text, JsonNode root) {
            this.text = text;
            this.root = root.deepCopy();
        }

        public String text() {
            return text;
        }

        public JsonNode root() {
            return root.deepCopy();
        }
    }
}
