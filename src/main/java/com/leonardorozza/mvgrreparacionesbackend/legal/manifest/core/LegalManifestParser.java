package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.LegalManifestSchema;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalManifestV1;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalManifestV1.Contacts;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalManifestV1.DocumentEntry;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalManifestV1.PublisherSnapshot;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalManifestV1.RequirementEntry;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalManifestV1.Review;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalManifestV1.ReviewRecord;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalManifestV1.ReviewStatus;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoActoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Frontera única del manifiesto v1: JSON estricto, schema local, RFC 8785 y modelo inmutable.
 */
public final class LegalManifestParser {

    private final StrictJsonReader jsonReader;
    private final Rfc8785Canonicalizer canonicalizer;

    public LegalManifestParser() {
        this(new StrictJsonReader(), new Rfc8785Canonicalizer());
    }

    LegalManifestParser(
            StrictJsonReader jsonReader,
            Rfc8785Canonicalizer canonicalizer) {
        this.jsonReader = Objects.requireNonNull(jsonReader, "jsonReader");
        this.canonicalizer = Objects.requireNonNull(canonicalizer, "canonicalizer");
    }

    public LegalManifestValidation<ParsedManifest> parse(byte[] rawBytes) {
        LegalManifestValidation<StrictJsonReader.StrictJsonDocument> strict =
                jsonReader.read(rawBytes);
        if (!strict.passed()) {
            return strict.asFailure();
        }

        StrictJsonReader.StrictJsonDocument document = strict.value().orElseThrow();
        JsonNode root = document.root();
        try {
            if (!LegalManifestSchema.validate(root).isEmpty()) {
                return failure(LegalManifestIssueCode.MANIFEST_SCHEMA_INVALID);
            }
        } catch (RuntimeException | LinkageError exception) {
            return failure(LegalManifestIssueCode.MANIFEST_SCHEMA_UNAVAILABLE);
        }

        LegalManifestValidation<Rfc8785Canonicalizer.CanonicalJson> canonical =
                canonicalizer.canonicalize(document);
        if (!canonical.passed()) {
            return canonical.asFailure();
        }

        final LegalManifestV1 manifest;
        try {
            if (!hasSupportedLocales(root)) {
                return failure(LegalManifestIssueCode.MANIFEST_LOCALE_UNSUPPORTED);
            }
            manifest = mapManifest(root);
        } catch (RuntimeException exception) {
            return failure(LegalManifestIssueCode.MANIFEST_MODEL_MAPPING_ERROR);
        }

        Rfc8785Canonicalizer.CanonicalJson canonicalJson = canonical.value().orElseThrow();
        return LegalManifestValidation.pass(new ParsedManifest(
                manifest,
                new String(canonicalJson.utf8(), StandardCharsets.UTF_8),
                canonicalJson.sha256()));
    }

    private static LegalManifestV1 mapManifest(JsonNode root) {
        return new LegalManifestV1(
                optionalText(root, "$schema"),
                required(root, "schemaVersion").intValue(),
                requiredText(root, "publicationId"),
                LocaleLegal.fromCodigo(requiredText(root, "locale")),
                mapPublisher(required(root, "publisherSnapshot")),
                mapReview(required(root, "review")),
                mapArray(required(root, "documents"), LegalManifestParser::mapDocument),
                mapArray(required(root, "requirements"), LegalManifestParser::mapRequirement));
    }

    private static boolean hasSupportedLocales(JsonNode root) {
        String supportedLocale = LocaleLegal.ES_AR.getCodigo();
        if (!supportedLocale.equals(requiredText(root, "locale"))) {
            return false;
        }
        for (JsonNode document : required(root, "documents")) {
            if (!supportedLocale.equals(requiredText(document, "locale"))) {
                return false;
            }
        }
        return true;
    }

    private static PublisherSnapshot mapPublisher(JsonNode node) {
        JsonNode contacts = required(node, "contacts");
        return new PublisherSnapshot(
                requiredText(node, "legalName"),
                requiredText(node, "taxId"),
                requiredText(node, "legalAddress"),
                requiredText(node, "jurisdiction"),
                requiredText(node, "businessHours"),
                new Contacts(
                        requiredText(contacts, "legalEmail"),
                        requiredText(contacts, "privacyEmail"),
                        requiredText(contacts, "supportEmail")));
    }

    private static Review mapReview(JsonNode node) {
        return new Review(
                mapReviewRecord(required(node, "legal")),
                mapReviewRecord(required(node, "accounting")));
    }

    private static ReviewRecord mapReviewRecord(JsonNode node) {
        String reviewedAt = optionalText(node, "reviewedAt");
        return new ReviewRecord(
                ReviewStatus.valueOf(requiredText(node, "status")),
                optionalText(node, "reference"),
                reviewedAt == null ? null : OffsetDateTime.parse(reviewedAt));
    }

    private static DocumentEntry mapDocument(JsonNode node) {
        return new DocumentEntry(
                requiredText(node, "key"),
                TipoDocumentoLegal.valueOf(requiredText(node, "type")),
                requiredText(node, "version"),
                LocaleLegal.fromCodigo(requiredText(node, "locale")),
                requiredText(node, "source"),
                requiredText(node, "sha256"),
                OffsetDateTime.parse(requiredText(node, "effectiveAt")),
                mapArray(required(node, "contexts"),
                        context -> ContextoLegal.valueOf(context.textValue())),
                required(node, "requiresReacceptance").booleanValue());
    }

    private static RequirementEntry mapRequirement(JsonNode node) {
        return new RequirementEntry(
                requiredText(node, "key"),
                requiredText(node, "version"),
                ContextoLegal.valueOf(requiredText(node, "context")),
                mapArray(required(node, "roles"),
                        role -> AudienciaLegal.valueOf(role.textValue())),
                TipoActoLegal.valueOf(requiredText(node, "actType")),
                requiredText(node, "statement"),
                requiredText(node, "statementSha256"),
                mapArray(required(node, "documents"), JsonNode::textValue),
                required(node, "required").booleanValue(),
                required(node, "requiresReacceptance").booleanValue());
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

    private static String requiredText(JsonNode object, String fieldName) {
        return required(object, fieldName).textValue();
    }

    private static String optionalText(JsonNode object, String fieldName) {
        JsonNode value = object.get(fieldName);
        return value == null || value.isNull() ? null : value.textValue();
    }

    private static <T> LegalManifestValidation<T> failure(LegalManifestIssueCode code) {
        return LegalManifestValidation.failure(LegalManifestIssue.at(
                code,
                StrictJsonReader.DEFAULT_LOCATION));
    }

    /**
     * Resultado autocontenido y sin referencias mutables del parseo acreditado.
     */
    public record ParsedManifest(
            LegalManifestV1 manifest,
            String canonicalJson,
            String manifestSha256) {

        public ParsedManifest {
            manifest = Objects.requireNonNull(manifest, "manifest");
            canonicalJson = Objects.requireNonNull(canonicalJson, "canonicalJson");
            manifestSha256 = Objects.requireNonNull(manifestSha256, "manifestSha256");
        }
    }
}
