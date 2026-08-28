package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialReadiness;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable representation of the external version 1 editorial-plan contract.
 * Contract and cross-collection validation belong to the plan validator, not these value objects.
 */
public record LegalEditorialPlanV1(
        int schemaVersion,
        UUID operationId,
        OperationType operationType,
        String expectedCurrentPublicationId,
        String expectedCurrentManifestSha256,
        String expectedEditorialStateFingerprint,
        String targetPublicationId,
        String targetManifestSha256,
        List<DocumentScopedRef> documentAdditions,
        List<DocumentScopedRef> documentReuses,
        List<DocumentReplacementBatch> documentReplacementBatches,
        List<DocumentRetirement> documentRetirements,
        List<RequirementScopedRef> requirementAdditions,
        List<RequirementScopedRef> requirementReuses,
        List<RequirementReplacement> requirementReplacements,
        List<RequirementRetirement> requirementRetirements,
        LegalEditorialReadiness expectedReadinessAfter,
        boolean acknowledgeFailClosedGap
) {

    public LegalEditorialPlanV1 {
        operationId = Objects.requireNonNull(operationId, "operationId");
        operationType = Objects.requireNonNull(operationType, "operationType");
        expectedCurrentPublicationId = Objects.requireNonNull(
                expectedCurrentPublicationId,
                "expectedCurrentPublicationId");
        expectedCurrentManifestSha256 = Objects.requireNonNull(
                expectedCurrentManifestSha256,
                "expectedCurrentManifestSha256");
        expectedEditorialStateFingerprint = Objects.requireNonNull(
                expectedEditorialStateFingerprint,
                "expectedEditorialStateFingerprint");
        targetPublicationId = Objects.requireNonNull(targetPublicationId, "targetPublicationId");
        targetManifestSha256 = Objects.requireNonNull(
                targetManifestSha256,
                "targetManifestSha256");
        documentAdditions = immutableCopy(documentAdditions, "documentAdditions");
        documentReuses = immutableCopy(documentReuses, "documentReuses");
        documentReplacementBatches = immutableCopy(
                documentReplacementBatches,
                "documentReplacementBatches");
        documentRetirements = immutableCopy(documentRetirements, "documentRetirements");
        requirementAdditions = immutableCopy(requirementAdditions, "requirementAdditions");
        requirementReuses = immutableCopy(requirementReuses, "requirementReuses");
        requirementReplacements = immutableCopy(
                requirementReplacements,
                "requirementReplacements");
        requirementRetirements = immutableCopy(
                requirementRetirements,
                "requirementRetirements");
        expectedReadinessAfter = Objects.requireNonNull(
                expectedReadinessAfter,
                "expectedReadinessAfter");
    }

    private static <T> List<T> immutableCopy(List<T> values, String name) {
        return List.copyOf(Objects.requireNonNull(values, name));
    }

    public enum OperationType {
        REPLACE,
        RETIRE
    }

    public record DocumentRef(UUID documentVersionId, String sha256) {
        public DocumentRef {
            documentVersionId = Objects.requireNonNull(documentVersionId, "documentVersionId");
            sha256 = Objects.requireNonNull(sha256, "sha256");
        }
    }

    public record DocumentScopedRef(
            UUID documentVersionId,
            String sha256,
            List<ContextoLegal> contexts
    ) {
        public DocumentScopedRef {
            documentVersionId = Objects.requireNonNull(documentVersionId, "documentVersionId");
            sha256 = Objects.requireNonNull(sha256, "sha256");
            contexts = immutableCopy(contexts, "contexts");
        }
    }

    public record DocumentReplacementBatch(
            UUID replacementBatchId,
            List<ContextoLegal> contexts,
            List<DocumentRef> predecessors,
            List<DocumentRef> successors
    ) {
        public DocumentReplacementBatch {
            replacementBatchId = Objects.requireNonNull(
                    replacementBatchId,
                    "replacementBatchId");
            contexts = immutableCopy(contexts, "contexts");
            predecessors = immutableCopy(predecessors, "predecessors");
            successors = immutableCopy(successors, "successors");
        }
    }

    public record DocumentRetirement(
            UUID documentVersionId,
            String sha256,
            List<ContextoLegal> contexts,
            String reason
    ) {
        public DocumentRetirement {
            documentVersionId = Objects.requireNonNull(documentVersionId, "documentVersionId");
            sha256 = Objects.requireNonNull(sha256, "sha256");
            contexts = immutableCopy(contexts, "contexts");
            reason = Objects.requireNonNull(reason, "reason");
        }
    }

    public record RequirementRef(UUID requirementVersionId, String statementSha256) {
        public RequirementRef {
            requirementVersionId = Objects.requireNonNull(
                    requirementVersionId,
                    "requirementVersionId");
            statementSha256 = Objects.requireNonNull(statementSha256, "statementSha256");
        }
    }

    public record RequirementScopedRef(
            UUID requirementVersionId,
            String statementSha256,
            ContextoLegal context,
            List<AudienciaLegal> audiences
    ) {
        public RequirementScopedRef {
            requirementVersionId = Objects.requireNonNull(
                    requirementVersionId,
                    "requirementVersionId");
            statementSha256 = Objects.requireNonNull(statementSha256, "statementSha256");
            context = Objects.requireNonNull(context, "context");
            audiences = immutableCopy(audiences, "audiences");
        }
    }

    public record RequirementReplacement(
            RequirementRef predecessor,
            RequirementRef successor,
            ContextoLegal context,
            List<AudienciaLegal> audiences
    ) {
        public RequirementReplacement {
            predecessor = Objects.requireNonNull(predecessor, "predecessor");
            successor = Objects.requireNonNull(successor, "successor");
            context = Objects.requireNonNull(context, "context");
            audiences = immutableCopy(audiences, "audiences");
        }
    }

    public record RequirementRetirement(
            UUID requirementVersionId,
            String statementSha256,
            ContextoLegal context,
            List<AudienciaLegal> audiences,
            String reason
    ) {
        public RequirementRetirement {
            requirementVersionId = Objects.requireNonNull(
                    requirementVersionId,
                    "requirementVersionId");
            statementSha256 = Objects.requireNonNull(statementSha256, "statementSha256");
            context = Objects.requireNonNull(context, "context");
            audiences = immutableCopy(audiences, "audiences");
            reason = Objects.requireNonNull(reason, "reason");
        }
    }
}
