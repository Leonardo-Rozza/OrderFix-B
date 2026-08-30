package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoVersionLegal;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** SELECT-only authority that accredits an execution plan's complete editorial post-state. */
final class LegalEditorialPostStateVerifier {

    private static final String POSTCONDITION_LOCATION = "database/postcondition";
    private static final Comparator<UUID> UUID_ORDER = Comparator.comparing(UUID::toString);
    private static final Comparator<DocumentStateProjection> DOCUMENT_STATE_ORDER = Comparator
            .comparing(DocumentStateProjection::versionId, UUID_ORDER);
    private static final Comparator<RequirementStateProjection> REQUIREMENT_STATE_ORDER = Comparator
            .comparing(RequirementStateProjection::versionId, UUID_ORDER);
    private static final Comparator<DocumentTransitionProjection> DOCUMENT_TRANSITION_ORDER =
            Comparator.comparing(DocumentTransitionProjection::versionId, UUID_ORDER)
                    .thenComparingInt(transition -> transition.previousState().ordinal())
                    .thenComparingInt(transition -> transition.newState().ordinal());
    private static final Comparator<RequirementTransitionProjection> REQUIREMENT_TRANSITION_ORDER =
            Comparator.comparing(RequirementTransitionProjection::versionId, UUID_ORDER)
                    .thenComparingInt(transition -> transition.previousState().ordinal())
                    .thenComparingInt(transition -> transition.newState().ordinal());
    private static final Comparator<SlotProjection> SLOT_ORDER = Comparator
            .comparing((SlotProjection slot) -> slot.key().type().name())
            .thenComparing(slot -> slot.key().locale().getCodigo())
            .thenComparing(slot -> slot.key().context().name());
    private static final Comparator<PointerProjection> POINTER_ORDER = Comparator
            .comparing((PointerProjection pointer) -> pointer.key().locale().getCodigo())
            .thenComparing(pointer -> pointer.key().context().name())
            .thenComparing(pointer -> pointer.key().audience().name());
    private static final Comparator<BatchProjection> BATCH_ORDER = Comparator
            .comparing(BatchProjection::batchId, UUID_ORDER);

    private final JdbcTemplate jdbc;
    private final LegalEditorialPlannerCore.PlannerStateReader stateReader;

    LegalEditorialPostStateVerifier(JdbcTemplate jdbc) {
        this(jdbc, new LegalEditorialPlannerCore.JdbcPlannerStateReader(jdbc));
    }

    LegalEditorialPostStateVerifier(
            JdbcTemplate jdbc,
            LegalEditorialPlannerCore.PlannerStateReader stateReader) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.stateReader = Objects.requireNonNull(stateReader, "stateReader");
        if (!this.stateReader.usesJdbc(this.jdbc)) {
            throw new IllegalArgumentException(
                    "El verificador editorial requiere una única sesión JDBC compartida");
        }
    }

    LegalEditorialApplyReceipt verify(LegalEditorialExecutionPlan plan) {
        LegalEditorialExecutionPlan required = Objects.requireNonNull(plan, "plan");
        LegalEditorialExecutionPlan.ExpectedPostState expected = required.expectedPostState();
        Set<UUID> declaredDocumentIds = documentStateIds(expected);
        Set<UUID> declaredRequirementIds = requirementStateIds(expected);
        Set<UUID> declaredBatchIds = expected.replacementBatches().stream()
                .map(LegalEditorialExecutionPlan.ReplacementBatch::batchId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        UUID sourcePublicationId = required.source()
                .map(LegalEditorialExecutionPlan.SourceIdentity::publication)
                .map(LegalEditorialExecutionPlan.PublicationIdentity::publicationUuid)
                .orElse(required.target().publicationUuid());
        LegalEditorialPlannerCore.PlannerSnapshot actual = stateReader.snapshot(
                required.target().publicationUuid(),
                sourcePublicationId,
                declaredDocumentIds,
                declaredRequirementIds,
                declaredBatchIds);

        Set<UUID> expectedTargetDocuments = expectedTargetDocumentIds(required, expected);
        Set<UUID> expectedTargetRequirements = expectedTargetRequirementIds(required, expected);
        requireExact(Set.copyOf(actual.targetDocumentIds()).equals(expectedTargetDocuments));
        requireExact(Set.copyOf(actual.targetRequirementIds()).equals(expectedTargetRequirements));
        requireExact(expectedDocumentStates(expected).equals(actualDocumentStates(actual)));
        requireExact(expectedRequirementStates(expected).equals(actualRequirementStates(actual)));
        requireExact(expectedDocumentHistory(expected).equals(actualDocumentHistory(actual)));
        requireExact(expectedRequirementHistory(expected).equals(actualRequirementHistory(actual)));
        requireExact(expectedSlots(expected).equals(actualSlots(actual)));
        requireExact(expectedPointers(expected).equals(actualPointers(actual)));
        requireExact(expectedBatches(expected).equals(actualBatches(actual)));

        UUID targetPublicationId = required.target().publicationUuid();
        int documentTransitions = Math.toIntExact(actual.documentTransitions().stream()
                .filter(transition -> expectedTargetDocuments.contains(transition.versionId()))
                .count());
        int requirementTransitions = Math.toIntExact(actual.requirementTransitions().stream()
                .filter(transition -> expectedTargetRequirements.contains(transition.versionId()))
                .count());
        int documentSlots = Math.toIntExact(actual.activeSlots().stream()
                .filter(slot -> targetPublicationId.equals(slot.publicationId()))
                .count());
        int requiredSetPointers = Math.toIntExact(actual.activePointers().stream()
                .filter(pointer -> targetPublicationId.equals(pointer.publicationId()))
                .count());
        int replacementBatches = Math.toIntExact(actual.documentTransitions().stream()
                .filter(transition -> expectedTargetDocuments.contains(transition.versionId()))
                .map(LegalEditorialPlannerCore.DocumentTransitionEvidence::replacementBatchId)
                .filter(Objects::nonNull)
                .distinct()
                .count());
        return new LegalEditorialApplyReceipt(
                receiptOperation(required.operationType()),
                targetPublicationId,
                required.expectedAppliedAt(),
                required.expectedReadinessAfter(),
                actual.targetDocumentIds().size(),
                actual.targetRequirementIds().size(),
                documentTransitions,
                requirementTransitions,
                documentSlots,
                requiredSetPointers,
                replacementBatches);
    }

    boolean usesJdbc(JdbcTemplate candidate) {
        return jdbc == candidate && stateReader.usesJdbc(candidate);
    }

    private static Set<UUID> expectedTargetDocumentIds(
            LegalEditorialExecutionPlan plan,
            LegalEditorialExecutionPlan.ExpectedPostState expected) {
        return expected.documentStates().stream()
                .filter(state -> plan.operationType()
                        == LegalEditorialExecutionPlan.OperationType.RETIRE
                        || state.state() == EstadoVersionLegal.VIGENTE)
                .map(LegalEditorialExecutionPlan.ExpectedDocumentState::documentVersionId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static Set<UUID> expectedTargetRequirementIds(
            LegalEditorialExecutionPlan plan,
            LegalEditorialExecutionPlan.ExpectedPostState expected) {
        return expected.requirementStates().stream()
                .filter(state -> plan.operationType()
                        == LegalEditorialExecutionPlan.OperationType.RETIRE
                        || state.state() == EstadoVersionLegal.VIGENTE)
                .map(LegalEditorialExecutionPlan.ExpectedRequirementState::requirementVersionId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static Set<UUID> documentStateIds(
            LegalEditorialExecutionPlan.ExpectedPostState expected) {
        return expected.documentStates().stream()
                .map(LegalEditorialExecutionPlan.ExpectedDocumentState::documentVersionId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private static Set<UUID> requirementStateIds(
            LegalEditorialExecutionPlan.ExpectedPostState expected) {
        return expected.requirementStates().stream()
                .map(LegalEditorialExecutionPlan.ExpectedRequirementState::requirementVersionId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private static List<DocumentStateProjection> expectedDocumentStates(
            LegalEditorialExecutionPlan.ExpectedPostState expected) {
        return expected.documentStates().stream()
                .map(state -> new DocumentStateProjection(
                        state.documentVersionId(),
                        state.state(),
                        state.stateChangedAt(),
                        state.lastReason(),
                        state.replacementBatchId()))
                .sorted(DOCUMENT_STATE_ORDER)
                .toList();
    }

    private static List<DocumentStateProjection> actualDocumentStates(
            LegalEditorialPlannerCore.PlannerSnapshot actual) {
        return actual.documents().values().stream()
                .map(state -> new DocumentStateProjection(
                        state.id(),
                        state.state(),
                        state.stateChangedAt(),
                        state.lastReason(),
                        state.replacementBatchId()))
                .sorted(DOCUMENT_STATE_ORDER)
                .toList();
    }

    private static List<RequirementStateProjection> expectedRequirementStates(
            LegalEditorialExecutionPlan.ExpectedPostState expected) {
        return expected.requirementStates().stream()
                .map(state -> new RequirementStateProjection(
                        state.requirementVersionId(),
                        state.state(),
                        state.stateChangedAt(),
                        state.lastReason()))
                .sorted(REQUIREMENT_STATE_ORDER)
                .toList();
    }

    private static List<RequirementStateProjection> actualRequirementStates(
            LegalEditorialPlannerCore.PlannerSnapshot actual) {
        return actual.requirements().values().stream()
                .map(state -> new RequirementStateProjection(
                        state.id(),
                        state.state(),
                        state.stateChangedAt(),
                        state.lastReason()))
                .sorted(REQUIREMENT_STATE_ORDER)
                .toList();
    }

    private static List<DocumentTransitionProjection> expectedDocumentHistory(
            LegalEditorialExecutionPlan.ExpectedPostState expected) {
        List<LegalEditorialExecutionPlan.DocumentTransition> history = new ArrayList<>(
                expected.preexistingDocumentTransitions());
        history.addAll(expected.documentTransitions());
        return history.stream()
                .map(transition -> new DocumentTransitionProjection(
                        transition.documentVersionId(),
                        transition.previousState(),
                        transition.newState(),
                        transition.reason(),
                        transition.replacementBatchId(),
                        transition.occurredAt()))
                .sorted(DOCUMENT_TRANSITION_ORDER)
                .toList();
    }

    private static List<DocumentTransitionProjection> actualDocumentHistory(
            LegalEditorialPlannerCore.PlannerSnapshot actual) {
        return actual.documentTransitions().stream()
                .sorted(Comparator
                        .comparing(
                                LegalEditorialPlannerCore.DocumentTransitionEvidence::versionId,
                                UUID_ORDER)
                        .thenComparingLong(
                                LegalEditorialPlannerCore.DocumentTransitionEvidence::id))
                .map(transition -> new DocumentTransitionProjection(
                        transition.versionId(),
                        transition.previousState(),
                        transition.newState(),
                        transition.reason(),
                        transition.replacementBatchId(),
                        transition.occurredAt()))
                .toList();
    }

    private static List<RequirementTransitionProjection> expectedRequirementHistory(
            LegalEditorialExecutionPlan.ExpectedPostState expected) {
        List<LegalEditorialExecutionPlan.RequirementTransition> history = new ArrayList<>(
                expected.preexistingRequirementTransitions());
        history.addAll(expected.requirementTransitions());
        return history.stream()
                .map(transition -> new RequirementTransitionProjection(
                        transition.requirementVersionId(),
                        transition.previousState(),
                        transition.newState(),
                        transition.reason(),
                        transition.occurredAt()))
                .sorted(REQUIREMENT_TRANSITION_ORDER)
                .toList();
    }

    private static List<RequirementTransitionProjection> actualRequirementHistory(
            LegalEditorialPlannerCore.PlannerSnapshot actual) {
        return actual.requirementTransitions().stream()
                .sorted(Comparator
                        .comparing(
                                LegalEditorialPlannerCore.RequirementTransitionEvidence::versionId,
                                UUID_ORDER)
                        .thenComparingLong(
                                LegalEditorialPlannerCore.RequirementTransitionEvidence::id))
                .map(transition -> new RequirementTransitionProjection(
                        transition.versionId(),
                        transition.previousState(),
                        transition.newState(),
                        transition.reason(),
                        transition.occurredAt()))
                .toList();
    }

    private static List<SlotProjection> expectedSlots(
            LegalEditorialExecutionPlan.ExpectedPostState expected) {
        return expected.documentSlots().stream()
                .map(slot -> new SlotProjection(
                        slot.key(),
                        slot.documentVersionId(),
                        slot.documentLineId(),
                        slot.publicationId()))
                .sorted(SLOT_ORDER)
                .toList();
    }

    private static List<SlotProjection> actualSlots(
            LegalEditorialPlannerCore.PlannerSnapshot actual) {
        return actual.activeSlots().stream()
                .map(slot -> new SlotProjection(
                        slot.key(),
                        slot.documentVersionId(),
                        slot.documentLineId(),
                        slot.publicationId()))
                .sorted(SLOT_ORDER)
                .toList();
    }

    private static List<PointerProjection> expectedPointers(
            LegalEditorialExecutionPlan.ExpectedPostState expected) {
        return expected.requiredSetPointers().stream()
                .map(pointer -> new PointerProjection(
                        pointer.key(),
                        pointer.requiredSetId(),
                        pointer.publicationId(),
                        pointer.requiredSetRevision(),
                        pointer.updatedAt()))
                .sorted(POINTER_ORDER)
                .toList();
    }

    private static List<PointerProjection> actualPointers(
            LegalEditorialPlannerCore.PlannerSnapshot actual) {
        return actual.activePointers().stream()
                .map(pointer -> new PointerProjection(
                        pointer.key(),
                        pointer.requiredSetId(),
                        pointer.publicationId(),
                        pointer.revision(),
                        pointer.updatedAt()))
                .sorted(POINTER_ORDER)
                .toList();
    }

    private static List<BatchProjection> expectedBatches(
            LegalEditorialExecutionPlan.ExpectedPostState expected) {
        return expected.replacementBatches().stream()
                .map(batch -> new BatchProjection(
                        batch.batchId(),
                        batch.createdAt(),
                        batch.sealedAt(),
                        sortedUuids(batch.predecessorDocumentVersionIds()),
                        sortedSuccessors(batch.successors())))
                .sorted(BATCH_ORDER)
                .toList();
    }

    private static List<BatchProjection> actualBatches(
            LegalEditorialPlannerCore.PlannerSnapshot actual) {
        return actual.batches().values().stream()
                .map(batch -> new BatchProjection(
                        batch.id(),
                        batch.createdAt(),
                        batch.sealedAt(),
                        sortedUuids(batch.predecessorIds()),
                        sortedSuccessors(batch.successors())))
                .sorted(BATCH_ORDER)
                .toList();
    }

    private static List<UUID> sortedUuids(List<UUID> values) {
        return values.stream().sorted(UUID_ORDER).toList();
    }

    private static List<LegalEditorialExecutionPlan.ReplacementSuccessor> sortedSuccessors(
            List<LegalEditorialExecutionPlan.ReplacementSuccessor> values) {
        return values.stream().sorted(Comparator
                .comparing(
                        LegalEditorialExecutionPlan.ReplacementSuccessor::documentVersionId,
                        UUID_ORDER)
                .thenComparing(
                        LegalEditorialExecutionPlan.ReplacementSuccessor::publicationId,
                        UUID_ORDER)).toList();
    }

    private static LegalEditorialApplyReceipt.OperationType receiptOperation(
            LegalEditorialExecutionPlan.OperationType operationType) {
        return switch (operationType) {
            case PROMOTE -> LegalEditorialApplyReceipt.OperationType.PROMOTE;
            case REPLACE -> LegalEditorialApplyReceipt.OperationType.REPLACE;
            case RETIRE -> LegalEditorialApplyReceipt.OperationType.RETIRE;
        };
    }

    private static void requireExact(boolean exact) {
        if (!exact) {
            throw new LegalEditorialOperationalException(
                    LegalManifestIssueCode.POSTCONDITION_NOT_READY,
                    POSTCONDITION_LOCATION);
        }
    }

    private record DocumentStateProjection(
            UUID versionId,
            EstadoVersionLegal state,
            Instant stateChangedAt,
            String lastReason,
            UUID replacementBatchId) { }

    private record RequirementStateProjection(
            UUID versionId,
            EstadoVersionLegal state,
            Instant stateChangedAt,
            String lastReason) { }

    private record DocumentTransitionProjection(
            UUID versionId,
            EstadoVersionLegal previousState,
            EstadoVersionLegal newState,
            String reason,
            UUID replacementBatchId,
            Instant occurredAt) { }

    private record RequirementTransitionProjection(
            UUID versionId,
            EstadoVersionLegal previousState,
            EstadoVersionLegal newState,
            String reason,
            Instant occurredAt) { }

    private record SlotProjection(
            LegalEditorialExecutionPlan.DocumentSlotKey key,
            UUID documentVersionId,
            UUID documentLineId,
            UUID publicationId) { }

    private record PointerProjection(
            LegalEditorialExecutionPlan.RequiredSetPointerKey key,
            UUID requiredSetId,
            UUID publicationId,
            String requiredSetRevision,
            Instant updatedAt) { }

    private record BatchProjection(
            UUID batchId,
            Instant createdAt,
            Instant sealedAt,
            List<UUID> predecessorDocumentVersionIds,
            List<LegalEditorialExecutionPlan.ReplacementSuccessor> successors) { }
}
