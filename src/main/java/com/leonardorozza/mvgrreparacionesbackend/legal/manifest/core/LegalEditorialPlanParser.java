package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.LegalEditorialPlanSchema;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalEditorialPlanV1;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalEditorialPlanV1.DocumentRef;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalEditorialPlanV1.DocumentReplacementBatch;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalEditorialPlanV1.DocumentRetirement;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalEditorialPlanV1.DocumentScopedRef;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalEditorialPlanV1.OperationType;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalEditorialPlanV1.RequirementRef;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalEditorialPlanV1.RequirementReplacement;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalEditorialPlanV1.RequirementRetirement;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalEditorialPlanV1.RequirementScopedRef;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/** Strict JSON, local schema, raw RFC 8785 and immutable mapping boundary for editorial plans. */
public final class LegalEditorialPlanParser {

    private final StrictJsonReader jsonReader;
    private final Rfc8785Canonicalizer canonicalizer;

    public LegalEditorialPlanParser() {
        this(new StrictJsonReader(), new Rfc8785Canonicalizer());
    }

    LegalEditorialPlanParser(
            StrictJsonReader jsonReader,
            Rfc8785Canonicalizer canonicalizer) {
        this.jsonReader = Objects.requireNonNull(jsonReader, "jsonReader");
        this.canonicalizer = Objects.requireNonNull(canonicalizer, "canonicalizer");
    }

    public LegalManifestValidation<ParsedEditorialPlan> parse(byte[] rawBytes) {
        LegalManifestValidation<StrictJsonReader.StrictJsonDocument> strict =
                jsonReader.readEditorialPlan(rawBytes);
        if (!strict.passed()) {
            return strict.asFailure();
        }

        StrictJsonReader.StrictJsonDocument document = strict.value().orElseThrow();
        JsonNode root = document.root();
        try {
            if (!LegalEditorialPlanSchema.validate(root).isEmpty()) {
                return failure(LegalManifestIssueCode.EDITORIAL_PLAN_SCHEMA_INVALID);
            }
        } catch (RuntimeException | LinkageError exception) {
            return failure(LegalManifestIssueCode.EDITORIAL_PLAN_SCHEMA_UNAVAILABLE);
        }

        LegalManifestValidation<Rfc8785Canonicalizer.CanonicalJson> canonical =
                canonicalizer.canonicalizeEditorialPlan(document);
        if (!canonical.passed()) {
            return canonical.asFailure();
        }

        final LegalEditorialPlanV1 plan;
        try {
            plan = mapPlan(root);
        } catch (RuntimeException exception) {
            return failure(LegalManifestIssueCode.EDITORIAL_PLAN_MODEL_MAPPING_ERROR);
        }

        Rfc8785Canonicalizer.CanonicalJson canonicalJson = canonical.value().orElseThrow();
        return LegalManifestValidation.pass(new ParsedEditorialPlan(
                plan,
                new String(canonicalJson.utf8(), StandardCharsets.UTF_8),
                canonicalJson.sha256()));
    }

    private static LegalEditorialPlanV1 mapPlan(JsonNode root) {
        return new LegalEditorialPlanV1(
                required(root, "schemaVersion").intValue(),
                uuid(root, "operationId"),
                OperationType.valueOf(text(root, "operationType")),
                text(root, "expectedCurrentPublicationId"),
                text(root, "expectedCurrentManifestSha256"),
                text(root, "expectedEditorialStateFingerprint"),
                text(root, "targetPublicationId"),
                text(root, "targetManifestSha256"),
                mapArray(required(root, "documentAdditions"),
                        LegalEditorialPlanParser::mapDocumentScopedRef),
                mapArray(required(root, "documentReuses"),
                        LegalEditorialPlanParser::mapDocumentScopedRef),
                mapArray(required(root, "documentReplacementBatches"),
                        LegalEditorialPlanParser::mapDocumentReplacementBatch),
                mapArray(required(root, "documentRetirements"),
                        LegalEditorialPlanParser::mapDocumentRetirement),
                mapArray(required(root, "requirementAdditions"),
                        LegalEditorialPlanParser::mapRequirementScopedRef),
                mapArray(required(root, "requirementReuses"),
                        LegalEditorialPlanParser::mapRequirementScopedRef),
                mapArray(required(root, "requirementReplacements"),
                        LegalEditorialPlanParser::mapRequirementReplacement),
                mapArray(required(root, "requirementRetirements"),
                        LegalEditorialPlanParser::mapRequirementRetirement),
                LegalEditorialReadiness.valueOf(text(root, "expectedReadinessAfter")),
                required(root, "acknowledgeFailClosedGap").booleanValue());
    }

    private static DocumentScopedRef mapDocumentScopedRef(JsonNode node) {
        return new DocumentScopedRef(
                uuid(node, "documentVersionId"),
                text(node, "sha256"),
                mapContexts(required(node, "contexts")));
    }

    private static DocumentRef mapDocumentRef(JsonNode node) {
        return new DocumentRef(
                uuid(node, "documentVersionId"),
                text(node, "sha256"));
    }

    private static DocumentReplacementBatch mapDocumentReplacementBatch(JsonNode node) {
        return new DocumentReplacementBatch(
                uuid(node, "replacementBatchId"),
                mapContexts(required(node, "contexts")),
                mapArray(required(node, "predecessors"),
                        LegalEditorialPlanParser::mapDocumentRef),
                mapArray(required(node, "successors"),
                        LegalEditorialPlanParser::mapDocumentRef));
    }

    private static DocumentRetirement mapDocumentRetirement(JsonNode node) {
        return new DocumentRetirement(
                uuid(node, "documentVersionId"),
                text(node, "sha256"),
                mapContexts(required(node, "contexts")),
                text(node, "reason"));
    }

    private static RequirementScopedRef mapRequirementScopedRef(JsonNode node) {
        return new RequirementScopedRef(
                uuid(node, "requirementVersionId"),
                text(node, "statementSha256"),
                ContextoLegal.valueOf(text(node, "context")),
                mapAudiences(required(node, "audiences")));
    }

    private static RequirementRef mapRequirementRef(JsonNode node) {
        return new RequirementRef(
                uuid(node, "requirementVersionId"),
                text(node, "statementSha256"));
    }

    private static RequirementReplacement mapRequirementReplacement(JsonNode node) {
        return new RequirementReplacement(
                mapRequirementRef(required(node, "predecessor")),
                mapRequirementRef(required(node, "successor")),
                ContextoLegal.valueOf(text(node, "context")),
                mapAudiences(required(node, "audiences")));
    }

    private static RequirementRetirement mapRequirementRetirement(JsonNode node) {
        return new RequirementRetirement(
                uuid(node, "requirementVersionId"),
                text(node, "statementSha256"),
                ContextoLegal.valueOf(text(node, "context")),
                mapAudiences(required(node, "audiences")),
                text(node, "reason"));
    }

    private static List<ContextoLegal> mapContexts(JsonNode node) {
        return mapArray(node, item -> ContextoLegal.valueOf(item.textValue()));
    }

    private static List<AudienciaLegal> mapAudiences(JsonNode node) {
        return mapArray(node, item -> AudienciaLegal.valueOf(item.textValue()));
    }

    private static <T> List<T> mapArray(JsonNode array, Function<JsonNode, T> mapper) {
        List<T> values = new ArrayList<>(array.size());
        for (JsonNode element : array) {
            values.add(mapper.apply(element));
        }
        return values;
    }

    private static JsonNode required(JsonNode object, String fieldName) {
        return object.required(fieldName);
    }

    private static String text(JsonNode object, String fieldName) {
        return required(object, fieldName).textValue();
    }

    private static UUID uuid(JsonNode object, String fieldName) {
        String raw = text(object, fieldName);
        UUID parsed = UUID.fromString(raw);
        if (!parsed.toString().equals(raw)) {
            throw new IllegalArgumentException("UUID no canónico");
        }
        return parsed;
    }

    private static <T> LegalManifestValidation<T> failure(LegalManifestIssueCode code) {
        return LegalManifestValidation.failure(LegalManifestIssue.at(
                code,
                ConfinedEditorialPlanReader.PLAN_FILENAME));
    }

    /** Exact raw canonical artifact plus its immutable mapped representation. */
    public record ParsedEditorialPlan(
            LegalEditorialPlanV1 plan,
            String canonicalJson,
            String editorialPlanSha256) {

        public ParsedEditorialPlan {
            plan = Objects.requireNonNull(plan, "plan");
            canonicalJson = Objects.requireNonNull(canonicalJson, "canonicalJson");
            editorialPlanSha256 = Objects.requireNonNull(
                    editorialPlanSha256,
                    "editorialPlanSha256");
        }
    }
}
