package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection.DocumentProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection.RequirementProjection;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoVersionLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Pure, fail-closed validation of a complete public registration requirement projection. */
public final class LegalPublicRequirementsValidator {

    private final CanonicalTextValidator textValidator = new CanonicalTextValidator();

    /**
     * Validates all members before computing either revision, without sorting or repairing input.
     *
     * <p>The typed projection already bounds counts and metadata code points. Persistence remains
     * responsible for SQL ordinals, publication membership, states and the post-lock time boundary.
     * Future instants are therefore representable here, but are not proof of current availability.</p>
     *
     * @throws IllegalArgumentException if the complete projection cannot be accredited
     */
    public LegalPublicRegistrationRequirements validate(LegalRequiredSetProjection projection) {
        Objects.requireNonNull(projection, "projection");
        if (projection.context() != ContextoLegal.REGISTRO || projection.locale() != LocaleLegal.ES_AR) {
            throw new IllegalArgumentException("Los requisitos públicos requieren REGISTRO/es-AR");
        }

        // Bound every reference, including repeated Markdown, before any UTF-8 allocation/hash.
        LegalRequiredSetRevisionCalculator.requireCanonicalizationCapacity(projection);

        Set<UUID> requirementIds = new HashSet<>();
        Map<UUID, DocumentProjection> documentsById = new HashMap<>();
        boolean hasRequired = false;
        for (RequirementProjection requirement : projection.requirements()) {
            if (requirement.context() != projection.context()
                    || !requirementIds.add(requirement.versionId())) {
                throw new IllegalArgumentException("El requisito tiene contexto o identidad inválidos");
            }
            if (!LegalVisibleText.isPublishable(requirement.statement())
                    || !textValidator.validate(requirement.statement(), requirement.statementSha256(),
                            "public-requirements/statement").passed()) {
                throw new IllegalArgumentException("La afirmación legal no es publicable o su digest no coincide");
            }
            if (requirement.documents().isEmpty()) {
                throw new IllegalArgumentException("Cada requisito debe contener documentos");
            }
            Set<UUID> referencedIds = new HashSet<>();
            for (DocumentProjection document : requirement.documents()) {
                if (!referencedIds.add(document.versionId())) {
                    throw new IllegalArgumentException("El requisito repite un documento");
                }
                DocumentProjection previous = documentsById.get(document.versionId());
                if (previous == null) {
                    if (documentsById.size() == LegalManifestLimits.MAX_DOCUMENTS) {
                        throw new IllegalArgumentException("El conjunto supera el límite de documentos distintos");
                    }
                    validateDocument(document, projection.locale());
                    documentsById.put(document.versionId(), document);
                } else if (!sameCanonicalDocument(previous, document)) {
                    throw new IllegalArgumentException("Las referencias de un documento no coinciden");
                }
            }
            hasRequired |= requirement.required();
        }
        if (!hasRequired) {
            throw new IllegalArgumentException("REGISTRO requiere al menos un requisito obligatorio completo");
        }
        return new LegalPublicRegistrationRequirements(projection);
    }

    private void validateDocument(DocumentProjection document, LocaleLegal scopeLocale) {
        if (document.locale() != scopeLocale) {
            throw new IllegalArgumentException("El documento no pertenece al locale del conjunto");
        }
        // Reuse public metadata/RFC 3339 checks. VIGENTE is a canonical literal, not a DB claim.
        new LegalDocumentSummary(document.versionId(), document.type(), document.version(),
                document.title(), document.sha256(), document.effectiveAt().toInstant(),
                EstadoVersionLegal.VIGENTE, document.locale());
        if (document.markdown().isEmpty()
                || !textValidator.validate(document.markdown(), document.sha256(),
                        "public-requirements/document").passed()) {
            throw new IllegalArgumentException("El contenido legal está vacío, es inválido o su digest no coincide");
        }
    }

    private static boolean sameCanonicalDocument(DocumentProjection first, DocumentProjection next) {
        return first == next || (first.type() == next.type()
                && first.version().equals(next.version())
                && first.title().equals(next.title())
                && first.markdown().equals(next.markdown())
                && first.sha256().equals(next.sha256())
                && first.effectiveAt().toInstant().equals(next.effectiveAt().toInstant())
                && first.locale() == next.locale());
    }
}
