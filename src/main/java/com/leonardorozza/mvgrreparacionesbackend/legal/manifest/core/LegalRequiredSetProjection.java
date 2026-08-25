package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoActoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable, already-resolved input for one contractual required-set response.
 *
 * <p>This value is not a validation token. Persistence must build it only from the opaque
 * {@code ValidatedRelease} and the definitive version UUIDs resolved inside its transaction.</p>
 */
public record LegalRequiredSetProjection(
        ContextoLegal context,
        LocaleLegal locale,
        List<RequirementProjection> requirements
) {

    private static final int MAX_STATEMENT_CODE_POINTS = 1_000;
    private static final int MAX_TITLE_CODE_POINTS = 300;
    private static final int MAX_VERSION_CODE_POINTS = 40;
    private static final int SHA256_LENGTH = 64;

    public LegalRequiredSetProjection {
        context = Objects.requireNonNull(context, "context");
        locale = Objects.requireNonNull(locale, "locale");
        List<RequirementProjection> requiredRequirements = Objects.requireNonNull(
                requirements,
                "requirements");
        if (requiredRequirements.size() > LegalManifestLimits.MAX_REQUIREMENTS) {
            throw new IllegalArgumentException(
                    "requirements supera el límite de la proyección legal");
        }
        requirements = List.copyOf(requiredRequirements);
    }

    /** One requirement version in manifest order. */
    public record RequirementProjection(
            UUID versionId,
            ContextoLegal context,
            TipoActoLegal actType,
            String statement,
            String statementSha256,
            List<DocumentProjection> documents,
            boolean required
    ) {

        public RequirementProjection {
            versionId = Objects.requireNonNull(versionId, "versionId");
            context = Objects.requireNonNull(context, "context");
            actType = Objects.requireNonNull(actType, "actType");
            statement = requireCodePointLimit(
                    statement,
                    MAX_STATEMENT_CODE_POINTS,
                    "statement");
            statementSha256 = requireLength(statementSha256, SHA256_LENGTH, "statementSha256");
            List<DocumentProjection> requiredDocuments = Objects.requireNonNull(
                    documents,
                    "documents");
            if (requiredDocuments.size()
                    > LegalManifestLimits.MAX_DOCUMENTS_PER_REQUIREMENT) {
                throw new IllegalArgumentException(
                        "documents supera el límite de la proyección legal");
            }
            documents = List.copyOf(requiredDocuments);
        }
    }

    /** One immutable document version in requirement-document order. */
    public record DocumentProjection(
            UUID versionId,
            TipoDocumentoLegal type,
            String version,
            String title,
            String markdown,
            String sha256,
            OffsetDateTime effectiveAt,
            LocaleLegal locale
    ) {

        public DocumentProjection {
            versionId = Objects.requireNonNull(versionId, "versionId");
            type = Objects.requireNonNull(type, "type");
            version = requireCodePointLimit(version, MAX_VERSION_CODE_POINTS, "version");
            title = requireCodePointLimit(title, MAX_TITLE_CODE_POINTS, "title");
            markdown = Objects.requireNonNull(markdown, "markdown");
            sha256 = requireLength(sha256, SHA256_LENGTH, "sha256");
            effectiveAt = Objects.requireNonNull(effectiveAt, "effectiveAt");
            locale = Objects.requireNonNull(locale, "locale");
        }
    }

    private static String requireCodePointLimit(String value, int maximum, String field) {
        String required = Objects.requireNonNull(value, field);
        if (required.codePointCount(0, required.length()) > maximum) {
            throw new IllegalArgumentException(field + " supera el límite de la proyección legal");
        }
        return required;
    }

    private static String requireLength(String value, int exactLength, String field) {
        String required = Objects.requireNonNull(value, field);
        if (required.length() != exactLength) {
            throw new IllegalArgumentException(field + " no respeta el contrato de longitud");
        }
        return required;
    }
}
