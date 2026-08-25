package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection.DocumentProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection.RequirementProjection;

import java.time.Instant;
import java.util.Objects;

/** Calculates the final RFC 8785 revision of one complete legal requirement scope. */
public final class LegalRequiredSetRevisionCalculator {

    private static final String REVISION_PREFIX = "sha256:";
    private static final String PUBLISHED_STATE = "VIGENTE";
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final Rfc8785Canonicalizer canonicalizer;

    public LegalRequiredSetRevisionCalculator() {
        this(new Rfc8785Canonicalizer());
    }

    LegalRequiredSetRevisionCalculator(Rfc8785Canonicalizer canonicalizer) {
        this.canonicalizer = Objects.requireNonNull(canonicalizer, "canonicalizer");
    }

    /** Returns {@code sha256:<64-hex>} over the exact prospective consumer response. */
    public String calculate(LegalRequiredSetProjection projection) {
        return REVISION_PREFIX + canonicalizer.canonicalize(projection);
    }

    byte[] canonicalUtf8(LegalRequiredSetProjection projection) {
        return canonicalizer.canonicalUtf8(projection);
    }

    /** Readable pre-JCS projection used only by golden/equivalence tests. */
    String projectionJson(LegalRequiredSetProjection projection) {
        return projectionJsonOf(projection);
    }

    private static String projectionJsonOf(LegalRequiredSetProjection projection) {
        LegalRequiredSetProjection required = Objects.requireNonNull(projection, "projection");
        requireCanonicalizationCapacity(required);
        return projectAccredited(required).toString();
    }

    private static ObjectNode projectAccredited(LegalRequiredSetProjection required) {
        ObjectNode root = JSON.objectNode();
        root.put("contexto", required.context().name());
        root.put("locale", required.locale().getCodigo());
        ArrayNode requirements = root.putArray("requisitos");
        for (RequirementProjection requirement : required.requirements()) {
            ObjectNode requirementJson = requirements.addObject();
            requirementJson.put("id", requirement.versionId().toString());
            requirementJson.put("contexto", requirement.context().name());
            requirementJson.put("tipoActo", requirement.actType().name());
            requirementJson.put("afirmacion", requirement.statement());
            requirementJson.put("afirmacionSha256", requirement.statementSha256());
            ArrayNode documents = requirementJson.putArray("documentos");
            for (DocumentProjection document : requirement.documents()) {
                ObjectNode documentJson = documents.addObject();
                documentJson.put("id", document.versionId().toString());
                documentJson.put("tipo", document.type().name());
                documentJson.put("version", document.version());
                documentJson.put("titulo", document.title());
                documentJson.put("contenidoMarkdown", document.markdown());
                documentJson.put("sha256", document.sha256());
                documentJson.put("vigenteDesde", utcInstant(document.effectiveAt().toInstant()));
                documentJson.put("estado", PUBLISHED_STATE);
                documentJson.put("locale", document.locale().getCodigo());
            }
            requirementJson.put("requerido", requirement.required());
        }
        return root;
    }

    static void requireCanonicalizationCapacity(
            LegalRequiredSetProjection projection) {
        if (projection.requirements().size() > LegalManifestLimits.MAX_REQUIREMENTS) {
            throw new IllegalArgumentException(
                    "La proyección legal supera la cantidad máxima de requisitos");
        }

        long expandedMarkdownBytes = 0L;
        for (RequirementProjection requirement : projection.requirements()) {
            if (requirement.documents().size()
                    > LegalManifestLimits.MAX_DOCUMENTS_PER_REQUIREMENT) {
                throw new IllegalArgumentException(
                        "La proyección legal supera la cantidad máxima de documentos");
            }
            for (DocumentProjection document : requirement.documents()) {
                long remainingScopeBytes =
                        LegalManifestLimits.MAX_EXPANDED_SCOPE_MARKDOWN_BYTES
                                - expandedMarkdownBytes;
                long documentBytes = utf8LengthUpTo(
                        document.markdown(),
                        Math.min(
                                LegalManifestLimits.MAX_MARKDOWN_BYTES,
                                remainingScopeBytes));
                if (documentBytes > LegalManifestLimits.MAX_MARKDOWN_BYTES
                        || documentBytes > remainingScopeBytes) {
                    throw new IllegalArgumentException(
                            "La proyección legal supera el límite Markdown del scope");
                }
                expandedMarkdownBytes += documentBytes;
            }
        }
    }

    /** Counts UTF-8 bytes without allocating an encoded copy and stops just beyond the limit. */
    private static long utf8LengthUpTo(String value, long limit) {
        Objects.requireNonNull(value, "value");
        if (limit < 0L) {
            return 1L;
        }
        long bytes = 0L;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            int encodedBytes;
            if (current <= 0x7f) {
                encodedBytes = 1;
            } else if (current <= 0x7ff) {
                encodedBytes = 2;
            } else if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException(
                            "La proyección legal contiene Unicode inválido");
                }
                index++;
                encodedBytes = 4;
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException(
                        "La proyección legal contiene Unicode inválido");
            } else {
                encodedBytes = 3;
            }
            if (bytes > limit - encodedBytes) {
                return limit + 1L;
            }
            bytes += encodedBytes;
        }
        return bytes;
    }

    static String utcInstant(Instant instant) {
        Instant required = Objects.requireNonNull(instant, "instant");
        if (required.getNano() % 1_000 != 0) {
            throw new IllegalArgumentException(
                    "La revisión legal sólo admite precisión de microsegundos");
        }
        return required.toString();
    }
}
