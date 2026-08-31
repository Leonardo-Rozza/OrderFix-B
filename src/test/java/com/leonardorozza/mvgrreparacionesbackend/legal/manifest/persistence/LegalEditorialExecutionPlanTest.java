package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialReadiness;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoVersionLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegalEditorialExecutionPlanTest {

    private static final Instant OBSERVED_AT =
            Instant.parse("2026-08-28T18:00:00.123456Z");
    private static final Instant OTHER_AT =
            Instant.parse("2026-08-28T18:00:01.123456Z");
    private static final Instant PREEXISTING_AT =
            Instant.parse("2026-08-01T12:00:00.123456Z");

    private static final String SHA_A = "a".repeat(64);
    private static final String SHA_B = "b".repeat(64);
    private static final String PLAN_SHA = "c".repeat(64);
    private static final String FINGERPRINT = "sha256:" + "d".repeat(64);
    private static final String REVISION = "sha256:" + "e".repeat(64);

    private static final UUID SOURCE_PUBLICATION = uuid(1);
    private static final UUID TARGET_PUBLICATION = uuid(2);
    private static final UUID OPERATION_ID = uuid(3);
    private static final UUID BATCH_ID = uuid(4);
    private static final UUID HISTORICAL_BATCH_ID = uuid(5);
    private static final UUID SECOND_HISTORICAL_BATCH_ID = uuid(6);
    private static final UUID DOCUMENT_ONE = uuid(10);
    private static final UUID DOCUMENT_TWO = uuid(11);
    private static final UUID DOCUMENT_PREDECESSOR = uuid(12);
    private static final UUID DOCUMENT_SUCCESSOR = uuid(13);
    private static final UUID HISTORICAL_PREDECESSOR = uuid(14);
    private static final UUID DOCUMENT_LINE_ONE = uuid(20);
    private static final UUID DOCUMENT_LINE_TWO = uuid(21);
    private static final UUID REQUIREMENT_ONE = uuid(30);
    private static final UUID REQUIRED_SET = uuid(40);
    private static final UUID AFFECTED_REQUIRED_SET = uuid(41);

    @Test
    void freezesTheOperationAndTopLevelPlanShape() {
        assertThat(LegalEditorialExecutionPlan.OperationType.values())
                .containsExactly(
                        LegalEditorialExecutionPlan.OperationType.PROMOTE,
                        LegalEditorialExecutionPlan.OperationType.REPLACE,
                        LegalEditorialExecutionPlan.OperationType.RETIRE);
        assertThat(LegalEditorialExecutionPlan.class.isRecord()).isTrue();
        assertThat(Arrays.stream(LegalEditorialExecutionPlan.class.getRecordComponents())
                .map(component -> component.getName()))
                .containsExactly(
                        "operationType",
                        "source",
                        "target",
                        "operationId",
                        "planSha256",
                        "transactionAt",
                        "observedAt",
                        "expectedAppliedAt",
                        "expectedReadinessAfter",
                        "acknowledgeFailClosedGap",
                        "changeRequired",
                        "expectedPostState",
                        "mutationCommands");
    }

    @Test
    void promoteCarriesOnlyDirectCommandsAndUsesTheTransactionTimestamp() {
        LegalEditorialExecutionPlan plan = promote(true);

        assertThat(plan.operationType())
                .isEqualTo(LegalEditorialExecutionPlan.OperationType.PROMOTE);
        assertThat(plan.source()).isEmpty();
        assertThat(plan.operationId()).isEmpty();
        assertThat(plan.planSha256()).isEmpty();
        assertThat(plan.expectedReadinessAfter()).isEqualTo(LegalEditorialReadiness.READY);
        assertThat(plan.acknowledgeFailClosedGap()).isFalse();
        assertThat(plan.changeRequired()).isTrue();
        assertThat(plan.expectedAppliedAt()).isEqualTo(plan.transactionAt());
        assertThat(plan.transactionAt()).isEqualTo(plan.observedAt());
        assertThat(plan.expectedPostState().v27TriggerEffects().isEmpty()).isTrue();
        assertThat(plan.expectedPostState().replacementBatches()).isEmpty();
        assertThat(plan.deltaCounts()).isEqualTo(new LegalEditorialExecutionPlan.DeltaCounts(
                4, 0, 2, 0, 2, 0, 0, 0, 1, 0));
        assertThat(plan.mutationCommands().documentTransitions())
                .extracting(LegalEditorialExecutionPlan.DocumentTransition::occurredAt)
                .containsOnly(OBSERVED_AT);
        assertThat(plan.mutationCommands().requirementTransitions())
                .extracting(LegalEditorialExecutionPlan.RequirementTransition::occurredAt)
                .containsOnly(OBSERVED_AT);
        assertThat(plan.mutationCommands().requiredSetPointerInserts())
                .extracting(LegalEditorialExecutionPlan.ExpectedRequiredSetPointer::updatedAt)
                .containsOnly(OBSERVED_AT);
    }

    @Test
    void exactReplayKeepsPostStateButHasNoCommandsAndZeroDelta() {
        LegalEditorialExecutionPlan replay = promote(false);

        assertThat(replay.changeRequired()).isFalse();
        assertThat(replay.transactionAt()).isEqualTo(OTHER_AT);
        assertThat(replay.observedAt()).isEqualTo(OTHER_AT);
        assertThat(replay.expectedAppliedAt()).isEqualTo(OBSERVED_AT);
        assertThat(replay.expectedAppliedAt()).isBefore(replay.observedAt());
        assertThat(replay.expectedPostState().documentTransitions()).hasSize(4);
        assertThat(replay.expectedPostState().requirementTransitions()).hasSize(2);
        assertThat(replay.mutationCommands().isEmpty()).isTrue();
        assertThat(replay.deltaCounts()).isEqualTo(LegalEditorialExecutionPlan.DeltaCounts.ZERO);
        assertThat(replay.deltaCounts().isZero()).isTrue();
    }

    @Test
    void replacementSeparatesDirectCommandsFromEveryV27TriggerEffect() {
        LegalEditorialExecutionPlan plan = replacement(true);

        assertThat(plan.source()).contains(source());
        assertThat(plan.target()).isEqualTo(target());
        assertThat(plan.operationId()).contains(OPERATION_ID);
        assertThat(plan.planSha256()).contains(PLAN_SHA);
        assertThat(plan.mutationCommands().documentTransitions())
                .extracting(
                        LegalEditorialExecutionPlan.DocumentTransition::previousState,
                        LegalEditorialExecutionPlan.DocumentTransition::newState)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        EstadoVersionLegal.BORRADOR,
                        EstadoVersionLegal.PUBLICADA));
        assertThat(plan.expectedPostState().v27TriggerEffects().documentTransitions())
                .extracting(
                        LegalEditorialExecutionPlan.DocumentTransition::previousState,
                        LegalEditorialExecutionPlan.DocumentTransition::newState)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(
                                EstadoVersionLegal.PUBLICADA,
                                EstadoVersionLegal.VIGENTE),
                        org.assertj.core.groups.Tuple.tuple(
                                EstadoVersionLegal.VIGENTE,
                                EstadoVersionLegal.REEMPLAZADA));
        assertThat(plan.expectedPostState().v27TriggerEffects().documentSlotDeletes())
                .hasSize(1);
        assertThat(plan.expectedPostState().v27TriggerEffects().documentSlotInserts())
                .hasSize(1);
        assertThat(plan.mutationCommands().documentSlotDeletes()).isEmpty();
        assertThat(plan.mutationCommands().documentSlotInserts()).isEmpty();
        assertThat(plan.expectedPostState().preexistingReplacementBatches())
                .extracting(LegalEditorialExecutionPlan.ReplacementBatch::batchId)
                .containsExactly(HISTORICAL_BATCH_ID);
        assertThat(plan.mutationCommands().replacementBatchesToCreateAndSeal())
                .extracting(LegalEditorialExecutionPlan.ReplacementBatch::batchId)
                .containsExactly(BATCH_ID);
        assertThat(plan.deltaCounts()).isEqualTo(new LegalEditorialExecutionPlan.DeltaCounts(
                1, 2, 0, 0, 0, 1, 1, 0, 0, 1));
    }

    @Test
    void replacementReplayRetainsTriggerEvidenceWithoutReissuingItsCommands() {
        LegalEditorialExecutionPlan replay = replacement(false);

        assertThat(replay.expectedPostState().replacementBatches()).hasSize(1);
        assertThat(replay.observedAt()).isEqualTo(OTHER_AT);
        assertThat(replay.expectedAppliedAt()).isEqualTo(OBSERVED_AT);
        assertThat(replay.expectedPostState().preexistingDocumentTransitions())
                .extracting(
                        LegalEditorialExecutionPlan.DocumentTransition::previousState,
                        LegalEditorialExecutionPlan.DocumentTransition::newState)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                EstadoVersionLegal.BORRADOR,
                                EstadoVersionLegal.PUBLICADA),
                        org.assertj.core.groups.Tuple.tuple(
                                EstadoVersionLegal.PUBLICADA,
                                EstadoVersionLegal.VIGENTE));
        assertThat(replay.expectedPostState().v27TriggerEffects().isEmpty()).isFalse();
        assertThat(replay.mutationCommands().isEmpty()).isTrue();
        assertThat(replay.deltaCounts().isZero()).isTrue();
    }

    @Test
    void retirementPreservesUnaffectedProjectionAndHistoricalBatchesWithoutReissuingThem() {
        LegalEditorialExecutionPlan plan = retirement(true);

        assertThat(plan.source()).contains(source());
        assertThat(plan.target()).isEqualTo(source().publication());
        assertThat(plan.expectedReadinessAfter()).isEqualTo(LegalEditorialReadiness.NOT_READY);
        assertThat(plan.acknowledgeFailClosedGap()).isTrue();
        assertThat(plan.expectedPostState().documentSlots())
                .containsExactly(preservedSlot());
        assertThat(plan.expectedPostState().requiredSetPointers())
                .containsExactly(preservedPointer(PREEXISTING_AT));
        assertThat(plan.expectedPostState().requiredSetPointers().getFirst().updatedAt())
                .isNotEqualTo(plan.expectedAppliedAt());
        assertThat(plan.expectedPostState().preexistingReplacementBatches())
                .extracting(LegalEditorialExecutionPlan.ReplacementBatch::batchId)
                .containsExactly(HISTORICAL_BATCH_ID);
        assertThat(plan.expectedPostState().replacementBatches()).isEmpty();
        assertThat(plan.mutationCommands().documentTransitions())
                .singleElement()
                .satisfies(transition -> {
                    assertThat(transition.newState()).isEqualTo(EstadoVersionLegal.RETIRADA);
                    assertThat(transition.reason()).isEqualTo("Retiro operativo explícito");
                });
        assertThat(plan.mutationCommands().documentSlotInserts()).isEmpty();
        assertThat(plan.mutationCommands().requiredSetPointerInserts()).isEmpty();
        assertThat(plan.mutationCommands().replacementBatchesToCreateAndSeal()).isEmpty();
        assertThat(plan.deltaCounts()).isEqualTo(new LegalEditorialExecutionPlan.DeltaCounts(
                1, 0, 0, 1, 0, 0, 0, 1, 0, 0));
    }

    @Test
    void retirementReplayKeepsPreservedPostStateWithoutCommandsOrDelta() {
        LegalEditorialExecutionPlan replay = retirement(false);

        assertThat(replay.observedAt()).isEqualTo(OTHER_AT);
        assertThat(replay.expectedAppliedAt()).isEqualTo(OBSERVED_AT);
        assertThat(replay.expectedPostState().documentSlots())
                .containsExactly(preservedSlot());
        assertThat(replay.expectedPostState().requiredSetPointers())
                .containsExactly(preservedPointer(PREEXISTING_AT));
        assertThat(replay.expectedPostState().preexistingReplacementBatches())
                .hasSize(1);
        assertThat(replay.mutationCommands().isEmpty()).isTrue();
        assertThat(replay.deltaCounts().isZero()).isTrue();
    }

    @Test
    void retirementRejectsProjectionInsertsAndCurrentReplacementBatches() {
        LegalEditorialExecutionPlan retirement = retirement(true);
        LegalEditorialExecutionPlan.MutationCommands original =
                retirement.mutationCommands();

        LegalEditorialExecutionPlan.MutationCommands withSlotInsert =
                new LegalEditorialExecutionPlan.MutationCommands(
                        original.documentTransitions(),
                        original.requirementTransitions(),
                        original.documentSlotDeletes(),
                        List.of(preservedSlot()),
                        original.requiredSetPointerDeletes(),
                        List.of(),
                        List.of());
        assertThatThrownBy(() -> copy(
                retirement,
                retirement.operationType(),
                retirement.source(),
                retirement.target(),
                retirement.operationId(),
                retirement.planSha256(),
                retirement.expectedReadinessAfter(),
                retirement.acknowledgeFailClosedGap(),
                true,
                retirement.expectedPostState(),
                withSlotInsert))
                .isInstanceOf(IllegalArgumentException.class);

        LegalEditorialExecutionPlan.MutationCommands withPointerInsert =
                new LegalEditorialExecutionPlan.MutationCommands(
                        original.documentTransitions(),
                        original.requirementTransitions(),
                        original.documentSlotDeletes(),
                        List.of(),
                        original.requiredSetPointerDeletes(),
                        List.of(preservedPointer(OBSERVED_AT)),
                        List.of());
        assertThatThrownBy(() -> copy(
                retirement,
                retirement.operationType(),
                retirement.source(),
                retirement.target(),
                retirement.operationId(),
                retirement.planSha256(),
                retirement.expectedReadinessAfter(),
                retirement.acknowledgeFailClosedGap(),
                true,
                retirement.expectedPostState(),
                withPointerInsert))
                .isInstanceOf(IllegalArgumentException.class);

        LegalEditorialExecutionPlan.ReplacementBatch currentBatch = replacementBatch(
                BATCH_ID,
                DOCUMENT_TWO,
                DOCUMENT_SUCCESSOR);
        LegalEditorialExecutionPlan.ExpectedPostState withCurrentBatch = copyPostState(
                retirement.expectedPostState(),
                retirement.expectedPostState().documentSlots(),
                retirement.expectedPostState().requiredSetPointers(),
                retirement.expectedPostState().preexistingReplacementBatches(),
                List.of(currentBatch));
        assertThatThrownBy(() -> copy(
                retirement,
                retirement.operationType(),
                retirement.source(),
                retirement.target(),
                retirement.operationId(),
                retirement.planSha256(),
                retirement.expectedReadinessAfter(),
                retirement.acknowledgeFailClosedGap(),
                true,
                withCurrentBatch,
                original))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void retirementDeletesMustBeDisjointFromPreservedPostState() {
        LegalEditorialExecutionPlan retirement = retirement(true);
        LegalEditorialExecutionPlan.MutationCommands original =
                retirement.mutationCommands();

        LegalEditorialExecutionPlan.MutationCommands overlappingSlot =
                new LegalEditorialExecutionPlan.MutationCommands(
                        original.documentTransitions(),
                        original.requirementTransitions(),
                        List.of(new LegalEditorialExecutionPlan.DocumentSlotDelete(
                                preservedSlot().key(),
                                DOCUMENT_PREDECESSOR)),
                        List.of(),
                        original.requiredSetPointerDeletes(),
                        List.of(),
                        List.of());
        assertThatThrownBy(() -> copy(
                retirement,
                retirement.operationType(),
                retirement.source(),
                retirement.target(),
                retirement.operationId(),
                retirement.planSha256(),
                retirement.expectedReadinessAfter(),
                retirement.acknowledgeFailClosedGap(),
                true,
                retirement.expectedPostState(),
                overlappingSlot))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("slot")
                .hasMessageContaining("postestado");

        LegalEditorialExecutionPlan.MutationCommands overlappingPointer =
                new LegalEditorialExecutionPlan.MutationCommands(
                        original.documentTransitions(),
                        original.requirementTransitions(),
                        original.documentSlotDeletes(),
                        List.of(),
                        List.of(new LegalEditorialExecutionPlan.RequiredSetPointerDelete(
                                preservedPointer(PREEXISTING_AT).key(),
                                REQUIRED_SET,
                                Optional.of(dependencies(
                                        List.of(),
                                        List.of(DOCUMENT_PREDECESSOR))))),
                        List.of(),
                        List.of());
        assertThatThrownBy(() -> copy(
                retirement,
                retirement.operationType(),
                retirement.source(),
                retirement.target(),
                retirement.operationId(),
                retirement.planSha256(),
                retirement.expectedReadinessAfter(),
                retirement.acknowledgeFailClosedGap(),
                true,
                retirement.expectedPostState(),
                overlappingPointer))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("puntero")
                .hasMessageContaining("postestado");
    }

    @Test
    void retirementRejectsSlotDeleteForANonRetiredDocument() {
        LegalEditorialExecutionPlan retirement = retirement(true);
        LegalEditorialExecutionPlan.MutationCommands original =
                retirement.mutationCommands();
        LegalEditorialExecutionPlan.MutationCommands wrongDelete =
                new LegalEditorialExecutionPlan.MutationCommands(
                        original.documentTransitions(),
                        original.requirementTransitions(),
                        List.of(new LegalEditorialExecutionPlan.DocumentSlotDelete(
                                slotKey(),
                                DOCUMENT_ONE)),
                        List.of(),
                        original.requiredSetPointerDeletes(),
                        List.of(),
                        List.of());

        assertThatThrownBy(() -> copy(
                retirement,
                retirement.operationType(),
                retirement.source(),
                retirement.target(),
                retirement.operationId(),
                retirement.planSha256(),
                retirement.expectedReadinessAfter(),
                retirement.acknowledgeFailClosedGap(),
                true,
                retirement.expectedPostState(),
                wrongDelete))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("documento retirado");
    }

    @Test
    void retirementRejectsPointerDeleteWithoutAuthoritativeDependencies() {
        LegalEditorialExecutionPlan retirement = retirement(true);
        LegalEditorialExecutionPlan.MutationCommands original =
                retirement.mutationCommands();
        LegalEditorialExecutionPlan.MutationCommands deleteWithoutEvidence =
                new LegalEditorialExecutionPlan.MutationCommands(
                        original.documentTransitions(),
                        original.requirementTransitions(),
                        original.documentSlotDeletes(),
                        List.of(),
                        List.of(new LegalEditorialExecutionPlan.RequiredSetPointerDelete(
                                affectedPointerKey(),
                                AFFECTED_REQUIRED_SET)),
                        List.of(),
                        List.of());

        assertThatThrownBy(() -> copy(
                retirement,
                retirement.operationType(),
                retirement.source(),
                retirement.target(),
                retirement.operationId(),
                retirement.planSha256(),
                retirement.expectedReadinessAfter(),
                retirement.acknowledgeFailClosedGap(),
                true,
                retirement.expectedPostState(),
                deleteWithoutEvidence))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("evidencia autoritativa");
    }

    @Test
    void retirementRejectsPointerDeleteUnrelatedToEveryExplicitRetirement() {
        LegalEditorialExecutionPlan retirement = retirement(true);
        LegalEditorialExecutionPlan.MutationCommands original =
                retirement.mutationCommands();
        LegalEditorialExecutionPlan.RequiredSetDependencies unrelatedDependencies =
                dependencies(List.of(REQUIREMENT_ONE), List.of(DOCUMENT_ONE));
        LegalEditorialExecutionPlan.MutationCommands unrelatedDelete =
                new LegalEditorialExecutionPlan.MutationCommands(
                        original.documentTransitions(),
                        original.requirementTransitions(),
                        original.documentSlotDeletes(),
                        List.of(),
                        List.of(new LegalEditorialExecutionPlan.RequiredSetPointerDelete(
                                new LegalEditorialExecutionPlan.RequiredSetPointerKey(
                                        LocaleLegal.ES_AR,
                                        ContextoLegal.CIERRE_CUENTA,
                                        AudienciaLegal.USER),
                                AFFECTED_REQUIRED_SET,
                                Optional.of(unrelatedDependencies))),
                        List.of(),
                        List.of());

        assertThatThrownBy(() -> copy(
                retirement,
                retirement.operationType(),
                retirement.source(),
                retirement.target(),
                retirement.operationId(),
                retirement.planSha256(),
                retirement.expectedReadinessAfter(),
                retirement.acknowledgeFailClosedGap(),
                true,
                retirement.expectedPostState(),
                unrelatedDelete))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("puntero eliminado")
                .hasMessageContaining("retiro explícito");
    }

    @Test
    void retirementRejectsPreservedPointerThatStillReferencesARetiredDocument() {
        LegalEditorialExecutionPlan retirement = retirement(true);
        LegalEditorialExecutionPlan.ExpectedRequiredSetPointer preserved =
                preservedPointer(PREEXISTING_AT);
        LegalEditorialExecutionPlan.ExpectedRequiredSetPointer affectedSurvivor =
                new LegalEditorialExecutionPlan.ExpectedRequiredSetPointer(
                        preserved.key(),
                        preserved.requiredSetId(),
                        preserved.publicationId(),
                        preserved.requiredSetRevision(),
                        preserved.updatedAt(),
                        Optional.of(dependencies(
                                List.of(REQUIREMENT_ONE),
                                List.of(DOCUMENT_PREDECESSOR))));
        LegalEditorialExecutionPlan.ExpectedPostState invalidPostState = copyPostState(
                retirement.expectedPostState(),
                retirement.expectedPostState().documentSlots(),
                List.of(affectedSurvivor),
                retirement.expectedPostState().preexistingReplacementBatches(),
                retirement.expectedPostState().replacementBatches());

        assertThatThrownBy(() -> copy(
                retirement,
                retirement.operationType(),
                retirement.source(),
                retirement.target(),
                retirement.operationId(),
                retirement.planSha256(),
                retirement.expectedReadinessAfter(),
                retirement.acknowledgeFailClosedGap(),
                true,
                invalidPostState,
                retirement.mutationCommands()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("puntero preservado")
                .hasMessageContaining("retiro explícito");
    }

    @Test
    void retirementRejectsPreservedPointerWithoutAuthoritativeDependencies() {
        LegalEditorialExecutionPlan retirement = retirement(true);
        LegalEditorialExecutionPlan.ExpectedRequiredSetPointer preserved =
                preservedPointer(PREEXISTING_AT);
        LegalEditorialExecutionPlan.ExpectedRequiredSetPointer withoutEvidence =
                new LegalEditorialExecutionPlan.ExpectedRequiredSetPointer(
                        preserved.key(),
                        preserved.requiredSetId(),
                        preserved.publicationId(),
                        preserved.requiredSetRevision(),
                        preserved.updatedAt());
        LegalEditorialExecutionPlan.ExpectedPostState invalidPostState = copyPostState(
                retirement.expectedPostState(),
                retirement.expectedPostState().documentSlots(),
                List.of(withoutEvidence),
                retirement.expectedPostState().preexistingReplacementBatches(),
                retirement.expectedPostState().replacementBatches());

        assertThatThrownBy(() -> copy(
                retirement,
                retirement.operationType(),
                retirement.source(),
                retirement.target(),
                retirement.operationId(),
                retirement.planSha256(),
                retirement.expectedReadinessAfter(),
                retirement.acknowledgeFailClosedGap(),
                true,
                invalidPostState,
                retirement.mutationCommands()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("puntero preservado")
                .hasMessageContaining("evidencia autoritativa");
    }

    @Test
    void retirementClassifiesPointerAffectedOnlyByARetiredRequirement() {
        LegalEditorialExecutionPlan plan = retirementWithRequirementImpact(false);

        assertThat(plan.mutationCommands().requiredSetPointerDeletes())
                .singleElement()
                .satisfies(pointer -> {
                    LegalEditorialExecutionPlan.RequiredSetDependencies dependencies =
                            pointer.dependenciesEvidence().orElseThrow();
                    assertThat(dependencies.memberRequirementVersionIds())
                            .containsExactly(REQUIREMENT_ONE);
                    assertThat(dependencies.referencedDocumentVersionIds()).isEmpty();
                });
    }

    @Test
    void retirementRejectsPreservedPointerDependingOnARetiredRequirement() {
        assertThatThrownBy(() -> retirementWithRequirementImpact(true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("puntero preservado")
                .hasMessageContaining("retiro explícito");
    }

    @Test
    void requiredSetDependenciesCanonicalizeSharedDocumentReferencesAsAUnion() {
        LegalEditorialExecutionPlan.RequiredSetDependencies dependencies =
                new LegalEditorialExecutionPlan.RequiredSetDependencies(
                        List.of(REQUIREMENT_ONE, uuid(31)),
                        List.of(DOCUMENT_ONE, DOCUMENT_ONE));

        assertThat(dependencies.memberRequirementVersionIds())
                .containsExactly(REQUIREMENT_ONE, uuid(31));
        assertThat(dependencies.referencedDocumentVersionIds())
                .containsExactly(DOCUMENT_ONE);
    }

    @Test
    void promotionStillRequiresPointerInsertAtExpectedAppliedAt() {
        LegalEditorialExecutionPlan promote = promote(true);
        LegalEditorialExecutionPlan.ExpectedRequiredSetPointer originalPointer =
                promote.expectedPostState().requiredSetPointers().getFirst();
        LegalEditorialExecutionPlan.ExpectedRequiredSetPointer historicalPointer =
                new LegalEditorialExecutionPlan.ExpectedRequiredSetPointer(
                        originalPointer.key(),
                        originalPointer.requiredSetId(),
                        originalPointer.publicationId(),
                        originalPointer.requiredSetRevision(),
                        PREEXISTING_AT);
        LegalEditorialExecutionPlan.ExpectedPostState historicalPostState = copyPostState(
                promote.expectedPostState(),
                promote.expectedPostState().documentSlots(),
                List.of(historicalPointer),
                promote.expectedPostState().preexistingReplacementBatches(),
                promote.expectedPostState().replacementBatches());
        LegalEditorialExecutionPlan.MutationCommands originalCommands =
                promote.mutationCommands();
        LegalEditorialExecutionPlan.MutationCommands historicalCommands =
                new LegalEditorialExecutionPlan.MutationCommands(
                        originalCommands.documentTransitions(),
                        originalCommands.requirementTransitions(),
                        originalCommands.documentSlotDeletes(),
                        originalCommands.documentSlotInserts(),
                        originalCommands.requiredSetPointerDeletes(),
                        List.of(historicalPointer),
                        originalCommands.replacementBatchesToCreateAndSeal());

        assertThatThrownBy(() -> copy(
                promote,
                promote.operationType(),
                promote.source(),
                promote.target(),
                promote.operationId(),
                promote.planSha256(),
                promote.expectedReadinessAfter(),
                promote.acknowledgeFailClosedGap(),
                true,
                historicalPostState,
                historicalCommands))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requiredSetPointers");
    }

    @Test
    void closedOperationMatrixRejectsEveryCrossOperationCombination() {
        LegalEditorialExecutionPlan promote = promote(true);
        LegalEditorialExecutionPlan replacement = replacement(true);
        LegalEditorialExecutionPlan retirement = retirement(true);

        assertThatThrownBy(() -> copy(
                promote,
                promote.operationType(),
                Optional.of(source()),
                promote.target(),
                Optional.empty(),
                Optional.empty(),
                LegalEditorialReadiness.READY,
                false,
                true,
                promote.expectedPostState(),
                promote.mutationCommands()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> copy(
                promote,
                promote.operationType(),
                Optional.empty(),
                promote.target(),
                Optional.of(OPERATION_ID),
                Optional.of(PLAN_SHA),
                LegalEditorialReadiness.READY,
                false,
                true,
                promote.expectedPostState(),
                promote.mutationCommands()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> copy(
                replacement,
                replacement.operationType(),
                Optional.of(source()),
                source().publication(),
                Optional.of(OPERATION_ID),
                Optional.of(PLAN_SHA),
                LegalEditorialReadiness.READY,
                false,
                true,
                replacement.expectedPostState(),
                replacement.mutationCommands()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> copy(
                replacement,
                replacement.operationType(),
                replacement.source(),
                replacement.target(),
                Optional.empty(),
                Optional.of(PLAN_SHA),
                LegalEditorialReadiness.READY,
                false,
                true,
                replacement.expectedPostState(),
                replacement.mutationCommands()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> copy(
                replacement,
                replacement.operationType(),
                replacement.source(),
                replacement.target(),
                Optional.of(OPERATION_ID),
                Optional.of(PLAN_SHA),
                LegalEditorialReadiness.NOT_READY,
                false,
                true,
                replacement.expectedPostState(),
                replacement.mutationCommands()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> copy(
                retirement,
                retirement.operationType(),
                retirement.source(),
                retirement.target(),
                retirement.operationId(),
                retirement.planSha256(),
                LegalEditorialReadiness.NOT_READY,
                false,
                true,
                retirement.expectedPostState(),
                retirement.mutationCommands()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> copy(
                retirement,
                retirement.operationType(),
                retirement.source(),
                target(),
                retirement.operationId(),
                retirement.planSha256(),
                LegalEditorialReadiness.NOT_READY,
                true,
                true,
                retirement.expectedPostState(),
                retirement.mutationCommands()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> copy(
                promote,
                promote.operationType(),
                promote.source(),
                promote.target(),
                promote.operationId(),
                promote.planSha256(),
                LegalEditorialReadiness.ERROR,
                false,
                true,
                promote.expectedPostState(),
                promote.mutationCommands()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void changeRequiredAndCommandSetCannotDisagree() {
        LegalEditorialExecutionPlan promote = promote(true);

        assertThatThrownBy(() -> copy(
                promote,
                promote.operationType(),
                promote.source(),
                promote.target(),
                promote.operationId(),
                promote.planSha256(),
                promote.expectedReadinessAfter(),
                promote.acknowledgeFailClosedGap(),
                false,
                promote.expectedPostState(),
                promote.mutationCommands()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> copy(
                promote,
                promote.operationType(),
                promote.source(),
                promote.target(),
                promote.operationId(),
                promote.planSha256(),
                promote.expectedReadinessAfter(),
                promote.acknowledgeFailClosedGap(),
                true,
                promote.expectedPostState(),
                LegalEditorialExecutionPlan.MutationCommands.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void temporalBoundaryUsesPostgresPrecisionAndCannotObserveBeforeTransaction() {
        LegalEditorialExecutionPlan replay = promote(false);

        assertThatThrownBy(() -> copyWithTimes(
                replay,
                Instant.parse("2026-08-28T18:00:00.123456789Z"),
                OTHER_AT,
                OBSERVED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("transactionAt")
                .hasMessageContaining("microsegundos");
        assertThatThrownBy(() -> copyWithTimes(
                replay,
                OBSERVED_AT,
                Instant.parse("2026-08-28T18:00:01.123456789Z"),
                OBSERVED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("observedAt")
                .hasMessageContaining("microsegundos");
        assertThatThrownBy(() -> copyWithTimes(
                replay,
                PREEXISTING_AT,
                OTHER_AT,
                Instant.parse("2026-08-28T18:00:00.123456789Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expectedAppliedAt")
                .hasMessageContaining("microsegundos");
        assertThatThrownBy(() -> copyWithTimes(
                replay,
                OTHER_AT,
                OBSERVED_AT,
                OBSERVED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("transactionAt")
                .hasMessageContaining("posterior");
        assertThatThrownBy(() -> copyWithTimes(
                replay,
                OBSERVED_AT,
                OTHER_AT,
                Instant.parse("2026-08-28T18:00:02.123456Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expectedAppliedAt")
                .hasMessageContaining("posterior");
    }

    @Test
    void freshMutationUsesTransactionAtAndAllowsLaterObservation() {
        LegalEditorialExecutionPlan fresh = promote(true);

        LegalEditorialExecutionPlan observedLater = copyWithTimes(
                fresh,
                OBSERVED_AT,
                OTHER_AT,
                OBSERVED_AT);

        assertThat(observedLater.expectedAppliedAt())
                .isEqualTo(observedLater.transactionAt());
        assertThat(observedLater.observedAt()).isAfter(observedLater.transactionAt());
        assertThatThrownBy(() -> copyWithTimes(
                fresh,
                PREEXISTING_AT,
                OTHER_AT,
                OBSERVED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mutación fresca")
                .hasMessageContaining("transactionAt");
    }

    @Test
    void replayAllowsAppliedAtOnEitherSideOfTransactionWithinObservationBoundary() {
        LegalEditorialExecutionPlan replay = promote(false);

        LegalEditorialExecutionPlan transactionBeforeApplied = copyWithTimes(
                replay,
                PREEXISTING_AT,
                OTHER_AT,
                OBSERVED_AT);
        LegalEditorialExecutionPlan transactionAfterApplied = copyWithTimes(
                replay,
                OTHER_AT,
                OTHER_AT,
                OBSERVED_AT);

        assertThat(transactionBeforeApplied.transactionAt())
                .isBefore(transactionBeforeApplied.expectedAppliedAt());
        assertThat(transactionAfterApplied.transactionAt())
                .isAfter(transactionAfterApplied.expectedAppliedAt());
        assertThat(transactionBeforeApplied.expectedAppliedAt())
                .isBeforeOrEqualTo(transactionBeforeApplied.observedAt());
        assertThat(transactionAfterApplied.expectedAppliedAt())
                .isBeforeOrEqualTo(transactionAfterApplied.observedAt());
    }

    @Test
    void directCommandsMustExactlyProduceTheDeclaredPostState() {
        LegalEditorialExecutionPlan promote = promote(true);
        LegalEditorialExecutionPlan.MutationCommands missingPointer =
                new LegalEditorialExecutionPlan.MutationCommands(
                        promote.mutationCommands().documentTransitions(),
                        promote.mutationCommands().requirementTransitions(),
                        promote.mutationCommands().documentSlotDeletes(),
                        promote.mutationCommands().documentSlotInserts(),
                        promote.mutationCommands().requiredSetPointerDeletes(),
                        List.of(),
                        promote.mutationCommands().replacementBatchesToCreateAndSeal());

        assertThatThrownBy(() -> copy(
                promote,
                promote.operationType(),
                promote.source(),
                promote.target(),
                promote.operationId(),
                promote.planSha256(),
                promote.expectedReadinessAfter(),
                promote.acknowledgeFailClosedGap(),
                true,
                promote.expectedPostState(),
                missingPointer))
                .isInstanceOf(IllegalArgumentException.class);

        List<LegalEditorialExecutionPlan.DocumentTransition> wrongTime = new ArrayList<>(
                promote.mutationCommands().documentTransitions());
        LegalEditorialExecutionPlan.DocumentTransition first = wrongTime.getFirst();
        wrongTime.set(0, new LegalEditorialExecutionPlan.DocumentTransition(
                first.documentVersionId(),
                first.previousState(),
                first.newState(),
                first.reason(),
                first.replacementBatchId(),
                OTHER_AT));
        LegalEditorialExecutionPlan.MutationCommands commandsAtDifferentTime =
                new LegalEditorialExecutionPlan.MutationCommands(
                        wrongTime,
                        promote.mutationCommands().requirementTransitions(),
                        List.of(),
                        promote.mutationCommands().documentSlotInserts(),
                        List.of(),
                        promote.mutationCommands().requiredSetPointerInserts(),
                        List.of());
        assertThatThrownBy(() -> copy(
                promote,
                promote.operationType(),
                promote.source(),
                promote.target(),
                promote.operationId(),
                promote.planSha256(),
                promote.expectedReadinessAfter(),
                promote.acknowledgeFailClosedGap(),
                true,
                promote.expectedPostState(),
                commandsAtDifferentTime))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lifecycleRecordsMirrorV27EdgesMetadataAndTimestampPrecision() {
        assertThatThrownBy(() -> new LegalEditorialExecutionPlan.DocumentTransition(
                DOCUMENT_ONE,
                EstadoVersionLegal.BORRADOR,
                EstadoVersionLegal.VIGENTE,
                null,
                null,
                OBSERVED_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LegalEditorialExecutionPlan.DocumentTransition(
                DOCUMENT_ONE,
                EstadoVersionLegal.VIGENTE,
                EstadoVersionLegal.RETIRADA,
                null,
                null,
                OBSERVED_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LegalEditorialExecutionPlan.DocumentTransition(
                DOCUMENT_ONE,
                EstadoVersionLegal.VIGENTE,
                EstadoVersionLegal.REEMPLAZADA,
                null,
                null,
                OBSERVED_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LegalEditorialExecutionPlan.RequirementTransition(
                REQUIREMENT_ONE,
                EstadoVersionLegal.VIGENTE,
                EstadoVersionLegal.RETIRADA,
                " con espacios ",
                OBSERVED_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LegalEditorialExecutionPlan.ExpectedDocumentState(
                DOCUMENT_ONE,
                EstadoVersionLegal.BORRADOR,
                OBSERVED_AT,
                null,
                null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LegalEditorialExecutionPlan.ExpectedRequirementState(
                REQUIREMENT_ONE,
                EstadoVersionLegal.RETIRADA,
                OBSERVED_AT,
                null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LegalEditorialExecutionPlan.PublicationIdentity(
                "target",
                TARGET_PUBLICATION,
                SHA_A.toUpperCase()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LegalEditorialExecutionPlan.SourceIdentity(
                source().publication(),
                "d".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LegalEditorialExecutionPlan.DeltaCounts(
                -1, 0, 0, 0, 0, 0, 0, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LegalEditorialExecutionPlan(
                LegalEditorialExecutionPlan.OperationType.PROMOTE,
                Optional.empty(),
                target(),
                Optional.empty(),
                Optional.empty(),
                OBSERVED_AT,
                Instant.parse("2026-08-28T18:00:00.123456789Z"),
                OBSERVED_AT,
                LegalEditorialReadiness.READY,
                false,
                false,
                promote(false).expectedPostState(),
                LegalEditorialExecutionPlan.MutationCommands.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listsAreDefensiveSortedByUuidOrDatabasePrimaryKeyAndRejectDuplicates() {
        List<LegalEditorialExecutionPlan.ExpectedDocumentState> mutable = new ArrayList<>();
        mutable.add(documentState(DOCUMENT_TWO, null));
        mutable.add(documentState(DOCUMENT_ONE, null));
        LegalEditorialExecutionPlan.ExpectedPostState ordered = new LegalEditorialExecutionPlan.ExpectedPostState(
                mutable,
                List.of(),
                List.of(
                        transition(DOCUMENT_TWO, EstadoVersionLegal.PUBLICADA, EstadoVersionLegal.VIGENTE, null),
                        transition(DOCUMENT_TWO, EstadoVersionLegal.BORRADOR, EstadoVersionLegal.PUBLICADA, null),
                        transition(DOCUMENT_ONE, EstadoVersionLegal.PUBLICADA, EstadoVersionLegal.VIGENTE, null),
                        transition(DOCUMENT_ONE, EstadoVersionLegal.BORRADOR, EstadoVersionLegal.PUBLICADA, null)),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                LegalEditorialExecutionPlan.V27TriggerEffects.empty());
        mutable.clear();

        assertThat(ordered.documentStates())
                .extracting(LegalEditorialExecutionPlan.ExpectedDocumentState::documentVersionId)
                .containsExactly(DOCUMENT_ONE, DOCUMENT_TWO);
        assertThatThrownBy(() -> ordered.documentStates().clear())
                .isInstanceOf(UnsupportedOperationException.class);

        assertThatThrownBy(() -> new LegalEditorialExecutionPlan.ExpectedPostState(
                List.of(documentState(DOCUMENT_ONE, null), documentState(DOCUMENT_ONE, null)),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                LegalEditorialExecutionPlan.V27TriggerEffects.empty()))
                .isInstanceOf(IllegalArgumentException.class);

        LegalEditorialExecutionPlan.DocumentSlotKey key = slotKey();
        assertThatThrownBy(() -> new LegalEditorialExecutionPlan.MutationCommands(
                List.of(),
                List.of(),
                List.of(
                        new LegalEditorialExecutionPlan.DocumentSlotDelete(key, DOCUMENT_ONE),
                        new LegalEditorialExecutionPlan.DocumentSlotDelete(key, DOCUMENT_TWO)),
                List.of(),
                List.of(),
                List.of(),
                List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void prehistoryIsImmutableDisjointAndCompletesEveryLifecycleChain() {
        LegalEditorialExecutionPlan replacement = replacement(false);
        LegalEditorialExecutionPlan.ExpectedPostState postState =
                replacement.expectedPostState();
        List<LegalEditorialExecutionPlan.DocumentTransition> prehistory =
                postState.preexistingDocumentTransitions();

        assertThat(prehistory)
                .extracting(
                        LegalEditorialExecutionPlan.DocumentTransition::previousState,
                        LegalEditorialExecutionPlan.DocumentTransition::newState)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                EstadoVersionLegal.BORRADOR,
                                EstadoVersionLegal.PUBLICADA),
                        org.assertj.core.groups.Tuple.tuple(
                                EstadoVersionLegal.PUBLICADA,
                                EstadoVersionLegal.VIGENTE));
        assertThatThrownBy(prehistory::clear)
                .isInstanceOf(UnsupportedOperationException.class);

        assertThatThrownBy(() -> new LegalEditorialExecutionPlan.ExpectedPostState(
                postState.documentStates(),
                postState.requirementStates(),
                postState.documentTransitions(),
                postState.requirementTransitions(),
                List.of(prehistory.getFirst(), prehistory.getFirst()),
                postState.preexistingRequirementTransitions(),
                postState.documentSlots(),
                postState.requiredSetPointers(),
                postState.replacementBatches(),
                postState.v27TriggerEffects()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicada");

        assertThatThrownBy(() -> new LegalEditorialExecutionPlan.ExpectedPostState(
                postState.documentStates(),
                postState.requirementStates(),
                postState.documentTransitions(),
                postState.requirementTransitions(),
                List.of(postState.documentTransitions().getFirst()),
                postState.preexistingRequirementTransitions(),
                postState.documentSlots(),
                postState.requiredSetPointers(),
                postState.replacementBatches(),
                postState.v27TriggerEffects()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("preexistente");

        LegalEditorialExecutionPlan.ExpectedPostState missingPrehistory =
                new LegalEditorialExecutionPlan.ExpectedPostState(
                        postState.documentStates(),
                        postState.requirementStates(),
                        postState.documentTransitions(),
                        postState.requirementTransitions(),
                        List.of(),
                        postState.preexistingRequirementTransitions(),
                        postState.documentSlots(),
                        postState.requiredSetPointers(),
                        postState.replacementBatches(),
                        postState.v27TriggerEffects());
        assertThatThrownBy(() -> copy(
                replacement,
                replacement.operationType(),
                replacement.source(),
                replacement.target(),
                replacement.operationId(),
                replacement.planSha256(),
                replacement.expectedReadinessAfter(),
                replacement.acknowledgeFailClosedGap(),
                false,
                missingPrehistory,
                LegalEditorialExecutionPlan.MutationCommands.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BORRADOR");
    }

    @Test
    void promotionRejectsAnyPreexistingHistory() {
        LegalEditorialExecutionPlan promote = promote(false);
        LegalEditorialExecutionPlan.ExpectedPostState original = promote.expectedPostState();
        LegalEditorialExecutionPlan.DocumentTransition moved =
                original.documentTransitions().getFirst();
        List<LegalEditorialExecutionPlan.DocumentTransition> delta = original
                .documentTransitions().stream()
                .filter(transition -> !transition.equals(moved))
                .toList();
        LegalEditorialExecutionPlan.ExpectedPostState withPrehistory =
                new LegalEditorialExecutionPlan.ExpectedPostState(
                        original.documentStates(),
                        original.requirementStates(),
                        delta,
                        original.requirementTransitions(),
                        List.of(moved),
                        original.preexistingRequirementTransitions(),
                        original.documentSlots(),
                        original.requiredSetPointers(),
                        original.replacementBatches(),
                        original.v27TriggerEffects());

        assertThatThrownBy(() -> copy(
                promote,
                promote.operationType(),
                promote.source(),
                promote.target(),
                promote.operationId(),
                promote.planSha256(),
                promote.expectedReadinessAfter(),
                promote.acknowledgeFailClosedGap(),
                false,
                withPrehistory,
                LegalEditorialExecutionPlan.MutationCommands.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void replacementBatchesRequireTwoDisjointUniqueSidesAndCannotLeakIntoDirectDml() {
        LegalEditorialExecutionPlan.ReplacementSuccessor successor =
                new LegalEditorialExecutionPlan.ReplacementSuccessor(
                        DOCUMENT_SUCCESSOR,
                        TARGET_PUBLICATION);
        assertThatThrownBy(() -> new LegalEditorialExecutionPlan.ReplacementBatch(
                BATCH_ID,
                OBSERVED_AT,
                OBSERVED_AT,
                List.of(),
                List.of(successor)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LegalEditorialExecutionPlan.ReplacementBatch(
                BATCH_ID,
                OBSERVED_AT,
                OBSERVED_AT,
                List.of(DOCUMENT_SUCCESSOR),
                List.of(successor)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LegalEditorialExecutionPlan.ReplacementBatch(
                BATCH_ID,
                OTHER_AT,
                OBSERVED_AT,
                List.of(DOCUMENT_PREDECESSOR),
                List.of(successor)))
                .isInstanceOf(IllegalArgumentException.class);

        LegalEditorialExecutionPlan.DocumentTransition derived = transition(
                DOCUMENT_PREDECESSOR,
                EstadoVersionLegal.VIGENTE,
                EstadoVersionLegal.REEMPLAZADA,
                BATCH_ID);
        assertThatThrownBy(() -> new LegalEditorialExecutionPlan.MutationCommands(
                List.of(derived),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()))
                .isInstanceOf(IllegalArgumentException.class);

        LegalEditorialExecutionPlan replacement = replacement(false);
        LegalEditorialExecutionPlan.V27TriggerEffects incompleteEffects =
                new LegalEditorialExecutionPlan.V27TriggerEffects(
                        List.of(replacement.expectedPostState().v27TriggerEffects()
                                .documentTransitions().getFirst()),
                        replacement.expectedPostState().v27TriggerEffects()
                                .documentSlotDeletes(),
                        replacement.expectedPostState().v27TriggerEffects()
                                .documentSlotInserts());
        LegalEditorialExecutionPlan.ExpectedPostState incompletePostState =
                new LegalEditorialExecutionPlan.ExpectedPostState(
                        replacement.expectedPostState().documentStates(),
                        replacement.expectedPostState().requirementStates(),
                        replacement.expectedPostState().documentTransitions(),
                        replacement.expectedPostState().requirementTransitions(),
                        replacement.expectedPostState().preexistingDocumentTransitions(),
                        replacement.expectedPostState().preexistingRequirementTransitions(),
                        replacement.expectedPostState().documentSlots(),
                        replacement.expectedPostState().requiredSetPointers(),
                        replacement.expectedPostState().replacementBatches(),
                        incompleteEffects);
        assertThatThrownBy(() -> copy(
                replacement,
                replacement.operationType(),
                replacement.source(),
                replacement.target(),
                replacement.operationId(),
                replacement.planSha256(),
                replacement.expectedReadinessAfter(),
                replacement.acknowledgeFailClosedGap(),
                false,
                incompletePostState,
                LegalEditorialExecutionPlan.MutationCommands.empty()))
                .isInstanceOf(IllegalArgumentException.class);

        LegalEditorialExecutionPlan applyingReplacement = replacement(true);
        LegalEditorialExecutionPlan.MutationCommands duplicatedTriggerDelete =
                new LegalEditorialExecutionPlan.MutationCommands(
                        applyingReplacement.mutationCommands().documentTransitions(),
                        List.of(),
                        applyingReplacement.expectedPostState().v27TriggerEffects()
                                .documentSlotDeletes(),
                        List.of(),
                        List.of(),
                        List.of(),
                        applyingReplacement.mutationCommands()
                                .replacementBatchesToCreateAndSeal());
        assertThatThrownBy(() -> copy(
                applyingReplacement,
                applyingReplacement.operationType(),
                applyingReplacement.source(),
                applyingReplacement.target(),
                applyingReplacement.operationId(),
                applyingReplacement.planSha256(),
                applyingReplacement.expectedReadinessAfter(),
                applyingReplacement.acknowledgeFailClosedGap(),
                true,
                applyingReplacement.expectedPostState(),
                duplicatedTriggerDelete))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void currentBatchesAndMutationCommandsUseTheirMinimumMemberBeforeBatchId() {
        UUID firstTextualMember = uuid("7fffffff-0000-0000-0000-000000000100");
        UUID secondTextualMember = uuid("80000000-0000-0000-0000-000000000500");
        UUID firstSuccessorOutOfOrder = uuid("90000000-0000-0000-0000-000000000200");
        UUID firstPredecessor = uuid("f0000000-0000-0000-0000-000000000400");
        UUID secondPredecessorOutOfOrder = uuid("a0000000-0000-0000-0000-000000000600");
        UUID secondSuccessor = uuid("b0000000-0000-0000-0000-000000000700");
        LegalEditorialExecutionPlan.ReplacementBatch firstByMember =
                new LegalEditorialExecutionPlan.ReplacementBatch(
                        uuid(900),
                        OBSERVED_AT,
                        OBSERVED_AT,
                        List.of(firstPredecessor),
                        List.of(
                                new LegalEditorialExecutionPlan.ReplacementSuccessor(
                                        firstSuccessorOutOfOrder,
                                        TARGET_PUBLICATION),
                                new LegalEditorialExecutionPlan.ReplacementSuccessor(
                                        firstTextualMember,
                                        SOURCE_PUBLICATION)));
        LegalEditorialExecutionPlan.ReplacementBatch firstByBatchId =
                new LegalEditorialExecutionPlan.ReplacementBatch(
                        uuid(800),
                        OBSERVED_AT,
                        OBSERVED_AT,
                        List.of(secondPredecessorOutOfOrder, secondTextualMember),
                        List.of(new LegalEditorialExecutionPlan.ReplacementSuccessor(
                                secondSuccessor,
                                TARGET_PUBLICATION)));

        LegalEditorialExecutionPlan.ExpectedPostState postState = postStateWithBatches(
                List.of(),
                List.of(firstByBatchId, firstByMember));
        LegalEditorialExecutionPlan.MutationCommands commands =
                new LegalEditorialExecutionPlan.MutationCommands(
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(firstByBatchId, firstByMember));

        assertThat(firstByMember.successors())
                .extracting(LegalEditorialExecutionPlan.ReplacementSuccessor::documentVersionId)
                .containsExactly(firstTextualMember, firstSuccessorOutOfOrder);
        assertThat(firstByBatchId.predecessorDocumentVersionIds())
                .containsExactly(secondTextualMember, secondPredecessorOutOfOrder);
        assertThat(postState.replacementBatches())
                .containsExactly(firstByMember, firstByBatchId);
        assertThat(commands.replacementBatchesToCreateAndSeal())
                .containsExactly(firstByMember, firstByBatchId);
    }

    @Test
    void currentBatchesRejectMemberOverlapAtPostStateAndCommandBoundaries() {
        LegalEditorialExecutionPlan.ReplacementBatch first = replacementBatch(
                BATCH_ID,
                DOCUMENT_ONE,
                DOCUMENT_TWO);
        LegalEditorialExecutionPlan.ReplacementBatch overlapping = replacementBatch(
                SECOND_HISTORICAL_BATCH_ID,
                DOCUMENT_PREDECESSOR,
                DOCUMENT_ONE);

        assertThatThrownBy(() -> postStateWithBatches(
                List.of(),
                List.of(first, overlapping)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("más de un lote");
        assertThatThrownBy(() -> new LegalEditorialExecutionPlan.MutationCommands(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(first, overlapping)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("más de un lote");
    }

    @Test
    void historicalBatchesRemainOrderedByBatchIdAndAllowADeepSuccessorChain() {
        LegalEditorialExecutionPlan.ReplacementBatch first = replacementBatch(
                SECOND_HISTORICAL_BATCH_ID,
                DOCUMENT_ONE,
                DOCUMENT_TWO);
        LegalEditorialExecutionPlan.ReplacementBatch second = replacementBatch(
                HISTORICAL_BATCH_ID,
                DOCUMENT_TWO,
                DOCUMENT_PREDECESSOR);

        LegalEditorialExecutionPlan.ExpectedPostState postState = postStateWithBatches(
                List.of(second, first),
                List.of());

        assertThat(postState.preexistingReplacementBatches())
                .extracting(
                        LegalEditorialExecutionPlan.ReplacementBatch::batchId,
                        batch -> batch.predecessorDocumentVersionIds().getFirst(),
                        batch -> batch.successors().getFirst().documentVersionId())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                HISTORICAL_BATCH_ID,
                                DOCUMENT_TWO,
                                DOCUMENT_PREDECESSOR),
                        org.assertj.core.groups.Tuple.tuple(
                                SECOND_HISTORICAL_BATCH_ID,
                                DOCUMENT_ONE,
                                DOCUMENT_TWO));
    }

    @Test
    void historicalBatchesRejectRepeatedPredecessorOrSuccessorRoles() {
        LegalEditorialExecutionPlan.ReplacementBatch first = replacementBatch(
                HISTORICAL_BATCH_ID,
                DOCUMENT_ONE,
                DOCUMENT_PREDECESSOR);
        LegalEditorialExecutionPlan.ReplacementBatch repeatedPredecessor = replacementBatch(
                SECOND_HISTORICAL_BATCH_ID,
                DOCUMENT_ONE,
                DOCUMENT_SUCCESSOR);
        LegalEditorialExecutionPlan.ReplacementBatch repeatedSuccessor = replacementBatch(
                SECOND_HISTORICAL_BATCH_ID,
                DOCUMENT_TWO,
                DOCUMENT_PREDECESSOR);

        assertThatThrownBy(() -> postStateWithBatches(
                List.of(first, repeatedPredecessor),
                List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("predecesora histórica");
        assertThatThrownBy(() -> postStateWithBatches(
                List.of(first, repeatedSuccessor),
                List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sucesora histórica");
    }

    @Test
    void replacementBatchIdCannotOverlapHistoricalAndCutoverSets() {
        LegalEditorialExecutionPlan.ReplacementBatch historical = replacementBatch(
                HISTORICAL_BATCH_ID,
                DOCUMENT_ONE,
                DOCUMENT_PREDECESSOR);
        LegalEditorialExecutionPlan.ReplacementBatch currentWithSameId = replacementBatch(
                HISTORICAL_BATCH_ID,
                DOCUMENT_PREDECESSOR,
                DOCUMENT_SUCCESSOR);

        assertThatThrownBy(() -> postStateWithBatches(
                List.of(historical),
                List.of(currentWithSameId)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("preexistente")
                .hasMessageContaining("delta");
    }

    private static LegalEditorialExecutionPlan promote(boolean changeRequired) {
        List<LegalEditorialExecutionPlan.DocumentTransition> documentTransitions = List.of(
                transition(DOCUMENT_TWO, EstadoVersionLegal.PUBLICADA, EstadoVersionLegal.VIGENTE, null),
                transition(DOCUMENT_ONE, EstadoVersionLegal.BORRADOR, EstadoVersionLegal.PUBLICADA, null),
                transition(DOCUMENT_TWO, EstadoVersionLegal.BORRADOR, EstadoVersionLegal.PUBLICADA, null),
                transition(DOCUMENT_ONE, EstadoVersionLegal.PUBLICADA, EstadoVersionLegal.VIGENTE, null));
        List<LegalEditorialExecutionPlan.RequirementTransition> requirementTransitions = List.of(
                requirementTransition(EstadoVersionLegal.PUBLICADA, EstadoVersionLegal.VIGENTE),
                requirementTransition(EstadoVersionLegal.BORRADOR, EstadoVersionLegal.PUBLICADA));
        LegalEditorialExecutionPlan.ExpectedDocumentSlot slotOne = slot(
                new LegalEditorialExecutionPlan.DocumentSlotKey(
                        TipoDocumentoLegal.TERMINOS_SERVICIO,
                        LocaleLegal.ES_AR,
                        ContextoLegal.REGISTRO),
                DOCUMENT_ONE,
                DOCUMENT_LINE_ONE,
                TARGET_PUBLICATION);
        LegalEditorialExecutionPlan.ExpectedDocumentSlot slotTwo = slot(
                new LegalEditorialExecutionPlan.DocumentSlotKey(
                        TipoDocumentoLegal.POLITICA_PRIVACIDAD,
                        LocaleLegal.ES_AR,
                        ContextoLegal.USO_CONTINUADO),
                DOCUMENT_TWO,
                DOCUMENT_LINE_TWO,
                TARGET_PUBLICATION);
        LegalEditorialExecutionPlan.ExpectedRequiredSetPointer pointer =
                new LegalEditorialExecutionPlan.ExpectedRequiredSetPointer(
                        new LegalEditorialExecutionPlan.RequiredSetPointerKey(
                                LocaleLegal.ES_AR,
                                ContextoLegal.REGISTRO,
                                AudienciaLegal.ADMIN_TITULAR),
                        REQUIRED_SET,
                        TARGET_PUBLICATION,
                        REVISION,
                        OBSERVED_AT);
        LegalEditorialExecutionPlan.ExpectedPostState postState =
                new LegalEditorialExecutionPlan.ExpectedPostState(
                        List.of(
                                documentState(DOCUMENT_TWO, null),
                                documentState(DOCUMENT_ONE, null)),
                        List.of(new LegalEditorialExecutionPlan.ExpectedRequirementState(
                                REQUIREMENT_ONE,
                                EstadoVersionLegal.VIGENTE,
                                OBSERVED_AT,
                                null)),
                        documentTransitions,
                        requirementTransitions,
                        List.of(),
                        List.of(),
                        List.of(slotTwo, slotOne),
                        List.of(pointer),
                        List.of(),
                        LegalEditorialExecutionPlan.V27TriggerEffects.empty());
        LegalEditorialExecutionPlan.MutationCommands commands = changeRequired
                ? new LegalEditorialExecutionPlan.MutationCommands(
                        documentTransitions,
                        requirementTransitions,
                        List.of(),
                        List.of(slotTwo, slotOne),
                        List.of(),
                        List.of(pointer),
                        List.of())
                : LegalEditorialExecutionPlan.MutationCommands.empty();
        return new LegalEditorialExecutionPlan(
                LegalEditorialExecutionPlan.OperationType.PROMOTE,
                Optional.empty(),
                target(),
                Optional.empty(),
                Optional.empty(),
                changeRequired ? OBSERVED_AT : OTHER_AT,
                changeRequired ? OBSERVED_AT : OTHER_AT,
                OBSERVED_AT,
                LegalEditorialReadiness.READY,
                false,
                changeRequired,
                postState,
                commands);
    }

    private static LegalEditorialExecutionPlan replacement(boolean changeRequired) {
        LegalEditorialExecutionPlan.DocumentTransition publishSuccessor = transition(
                DOCUMENT_SUCCESSOR,
                EstadoVersionLegal.BORRADOR,
                EstadoVersionLegal.PUBLICADA,
                null);
        LegalEditorialExecutionPlan.DocumentTransition activateSuccessor = transition(
                DOCUMENT_SUCCESSOR,
                EstadoVersionLegal.PUBLICADA,
                EstadoVersionLegal.VIGENTE,
                BATCH_ID);
        LegalEditorialExecutionPlan.DocumentTransition replacePredecessor = transition(
                DOCUMENT_PREDECESSOR,
                EstadoVersionLegal.VIGENTE,
                EstadoVersionLegal.REEMPLAZADA,
                BATCH_ID);
        LegalEditorialExecutionPlan.DocumentSlotKey key = slotKey();
        LegalEditorialExecutionPlan.ExpectedDocumentSlot successorSlot = slot(
                key,
                DOCUMENT_SUCCESSOR,
                DOCUMENT_LINE_TWO,
                TARGET_PUBLICATION);
        LegalEditorialExecutionPlan.ReplacementBatch batch =
                new LegalEditorialExecutionPlan.ReplacementBatch(
                        BATCH_ID,
                        OBSERVED_AT,
                        OBSERVED_AT,
                        List.of(DOCUMENT_PREDECESSOR),
                        List.of(new LegalEditorialExecutionPlan.ReplacementSuccessor(
                                DOCUMENT_SUCCESSOR,
                                TARGET_PUBLICATION)));
        LegalEditorialExecutionPlan.ReplacementBatch historicalBatch =
                new LegalEditorialExecutionPlan.ReplacementBatch(
                        HISTORICAL_BATCH_ID,
                        PREEXISTING_AT,
                        PREEXISTING_AT,
                        List.of(DOCUMENT_ONE),
                        List.of(new LegalEditorialExecutionPlan.ReplacementSuccessor(
                                DOCUMENT_PREDECESSOR,
                                SOURCE_PUBLICATION)));
        LegalEditorialExecutionPlan.V27TriggerEffects triggerEffects =
                new LegalEditorialExecutionPlan.V27TriggerEffects(
                        List.of(activateSuccessor, replacePredecessor),
                        List.of(new LegalEditorialExecutionPlan.DocumentSlotDelete(
                                key,
                                DOCUMENT_PREDECESSOR)),
                        List.of(successorSlot));
        LegalEditorialExecutionPlan.ExpectedPostState postState =
                new LegalEditorialExecutionPlan.ExpectedPostState(
                        List.of(
                                new LegalEditorialExecutionPlan.ExpectedDocumentState(
                                        DOCUMENT_PREDECESSOR,
                                        EstadoVersionLegal.REEMPLAZADA,
                                        OBSERVED_AT,
                                        null,
                                        BATCH_ID),
                                new LegalEditorialExecutionPlan.ExpectedDocumentState(
                                        DOCUMENT_SUCCESSOR,
                                        EstadoVersionLegal.VIGENTE,
                                        OBSERVED_AT,
                                        null,
                                        BATCH_ID)),
                        List.of(),
                        List.of(publishSuccessor, activateSuccessor, replacePredecessor),
                        List.of(),
                        List.of(
                                transitionAt(
                                        DOCUMENT_PREDECESSOR,
                                        EstadoVersionLegal.BORRADOR,
                                        EstadoVersionLegal.PUBLICADA,
                                        null,
                                        PREEXISTING_AT),
                                transitionAt(
                                        DOCUMENT_PREDECESSOR,
                                        EstadoVersionLegal.PUBLICADA,
                                        EstadoVersionLegal.VIGENTE,
                                        HISTORICAL_BATCH_ID,
                                        PREEXISTING_AT)),
                        List.of(),
                        List.of(successorSlot),
                        List.of(),
                        List.of(historicalBatch),
                        List.of(batch),
                        triggerEffects);
        LegalEditorialExecutionPlan.MutationCommands commands = changeRequired
                ? new LegalEditorialExecutionPlan.MutationCommands(
                        List.of(publishSuccessor),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(batch))
                : LegalEditorialExecutionPlan.MutationCommands.empty();
        return new LegalEditorialExecutionPlan(
                LegalEditorialExecutionPlan.OperationType.REPLACE,
                Optional.of(source()),
                target(),
                Optional.of(OPERATION_ID),
                Optional.of(PLAN_SHA),
                changeRequired ? OBSERVED_AT : OTHER_AT,
                changeRequired ? OBSERVED_AT : OTHER_AT,
                OBSERVED_AT,
                LegalEditorialReadiness.READY,
                false,
                changeRequired,
                postState,
                commands);
    }

    private static LegalEditorialExecutionPlan retirement(boolean changeRequired) {
        LegalEditorialExecutionPlan.DocumentTransition transition =
                new LegalEditorialExecutionPlan.DocumentTransition(
                        DOCUMENT_PREDECESSOR,
                        EstadoVersionLegal.VIGENTE,
                        EstadoVersionLegal.RETIRADA,
                        "Retiro operativo explícito",
                        null,
                        OBSERVED_AT);
        LegalEditorialExecutionPlan.ReplacementBatch historicalBatch = replacementBatch(
                HISTORICAL_BATCH_ID,
                HISTORICAL_PREDECESSOR,
                DOCUMENT_ONE);
        LegalEditorialExecutionPlan.ExpectedPostState postState =
                new LegalEditorialExecutionPlan.ExpectedPostState(
                        List.of(
                                new LegalEditorialExecutionPlan.ExpectedDocumentState(
                                        DOCUMENT_ONE,
                                        EstadoVersionLegal.VIGENTE,
                                        PREEXISTING_AT,
                                        null,
                                        HISTORICAL_BATCH_ID),
                                new LegalEditorialExecutionPlan.ExpectedDocumentState(
                                        DOCUMENT_PREDECESSOR,
                                        EstadoVersionLegal.RETIRADA,
                                        OBSERVED_AT,
                                        "Retiro operativo explícito",
                                        null)),
                        List.of(new LegalEditorialExecutionPlan.ExpectedRequirementState(
                                REQUIREMENT_ONE,
                                EstadoVersionLegal.VIGENTE,
                                PREEXISTING_AT,
                                null)),
                        List.of(transition),
                        List.of(),
                        List.of(
                                transitionAt(
                                        DOCUMENT_ONE,
                                        EstadoVersionLegal.BORRADOR,
                                        EstadoVersionLegal.PUBLICADA,
                                        null,
                                        PREEXISTING_AT),
                                transitionAt(
                                        DOCUMENT_ONE,
                                        EstadoVersionLegal.PUBLICADA,
                                        EstadoVersionLegal.VIGENTE,
                                        HISTORICAL_BATCH_ID,
                                        PREEXISTING_AT),
                                transitionAt(
                                        DOCUMENT_PREDECESSOR,
                                        EstadoVersionLegal.BORRADOR,
                                        EstadoVersionLegal.PUBLICADA,
                                        null,
                                        PREEXISTING_AT),
                                transitionAt(
                                        DOCUMENT_PREDECESSOR,
                                        EstadoVersionLegal.PUBLICADA,
                                        EstadoVersionLegal.VIGENTE,
                                        null,
                                        PREEXISTING_AT)),
                        List.of(
                                new LegalEditorialExecutionPlan.RequirementTransition(
                                        REQUIREMENT_ONE,
                                        EstadoVersionLegal.BORRADOR,
                                        EstadoVersionLegal.PUBLICADA,
                                        null,
                                        PREEXISTING_AT),
                                new LegalEditorialExecutionPlan.RequirementTransition(
                                        REQUIREMENT_ONE,
                                        EstadoVersionLegal.PUBLICADA,
                                        EstadoVersionLegal.VIGENTE,
                                        null,
                                        PREEXISTING_AT)),
                        List.of(preservedSlot()),
                        List.of(preservedPointer(PREEXISTING_AT)),
                        List.of(historicalBatch),
                        List.of(),
                        LegalEditorialExecutionPlan.V27TriggerEffects.empty());
        LegalEditorialExecutionPlan.MutationCommands commands = changeRequired
                ? new LegalEditorialExecutionPlan.MutationCommands(
                        List.of(transition),
                        List.of(),
                        List.of(new LegalEditorialExecutionPlan.DocumentSlotDelete(
                                slotKey(),
                                DOCUMENT_PREDECESSOR)),
                        List.of(),
                        List.of(new LegalEditorialExecutionPlan.RequiredSetPointerDelete(
                                affectedPointerKey(),
                                AFFECTED_REQUIRED_SET,
                                Optional.of(dependencies(
                                        List.of(REQUIREMENT_ONE),
                                        List.of(DOCUMENT_PREDECESSOR))))),
                        List.of(),
                        List.of())
                : LegalEditorialExecutionPlan.MutationCommands.empty();
        return new LegalEditorialExecutionPlan(
                LegalEditorialExecutionPlan.OperationType.RETIRE,
                Optional.of(source()),
                source().publication(),
                Optional.of(OPERATION_ID),
                Optional.of(PLAN_SHA),
                changeRequired ? OBSERVED_AT : OTHER_AT,
                changeRequired ? OBSERVED_AT : OTHER_AT,
                OBSERVED_AT,
                LegalEditorialReadiness.NOT_READY,
                true,
                changeRequired,
                postState,
                commands);
    }

    private static LegalEditorialExecutionPlan retirementWithRequirementImpact(
            boolean preserveAffectedPointer) {
        LegalEditorialExecutionPlan original = retirement(true);
        LegalEditorialExecutionPlan.ExpectedPostState originalPostState =
                original.expectedPostState();
        LegalEditorialExecutionPlan.RequirementTransition requirementRetirement =
                new LegalEditorialExecutionPlan.RequirementTransition(
                        REQUIREMENT_ONE,
                        EstadoVersionLegal.VIGENTE,
                        EstadoVersionLegal.RETIRADA,
                        "Retiro explícito del requisito",
                        OBSERVED_AT);
        LegalEditorialExecutionPlan.ExpectedRequiredSetPointer affectedSurvivor =
                new LegalEditorialExecutionPlan.ExpectedRequiredSetPointer(
                        preservedPointer(PREEXISTING_AT).key(),
                        REQUIRED_SET,
                        SOURCE_PUBLICATION,
                        REVISION,
                        PREEXISTING_AT,
                        Optional.of(dependencies(List.of(REQUIREMENT_ONE), List.of())));
        LegalEditorialExecutionPlan.ExpectedPostState postState =
                new LegalEditorialExecutionPlan.ExpectedPostState(
                        originalPostState.documentStates(),
                        List.of(new LegalEditorialExecutionPlan.ExpectedRequirementState(
                                REQUIREMENT_ONE,
                                EstadoVersionLegal.RETIRADA,
                                OBSERVED_AT,
                                "Retiro explícito del requisito")),
                        originalPostState.documentTransitions(),
                        List.of(requirementRetirement),
                        originalPostState.preexistingDocumentTransitions(),
                        originalPostState.preexistingRequirementTransitions(),
                        originalPostState.documentSlots(),
                        preserveAffectedPointer
                                ? List.of(affectedSurvivor)
                                : List.of(),
                        originalPostState.preexistingReplacementBatches(),
                        originalPostState.replacementBatches(),
                        originalPostState.v27TriggerEffects());
        LegalEditorialExecutionPlan.MutationCommands commands =
                new LegalEditorialExecutionPlan.MutationCommands(
                        original.mutationCommands().documentTransitions(),
                        List.of(requirementRetirement),
                        original.mutationCommands().documentSlotDeletes(),
                        List.of(),
                        List.of(new LegalEditorialExecutionPlan.RequiredSetPointerDelete(
                                affectedPointerKey(),
                                AFFECTED_REQUIRED_SET,
                                Optional.of(dependencies(
                                        List.of(REQUIREMENT_ONE),
                                        List.of())))),
                        List.of(),
                        List.of());
        return copy(
                original,
                original.operationType(),
                original.source(),
                original.target(),
                original.operationId(),
                original.planSha256(),
                original.expectedReadinessAfter(),
                original.acknowledgeFailClosedGap(),
                true,
                postState,
                commands);
    }

    private static LegalEditorialExecutionPlan.ExpectedPostState copyPostState(
            LegalEditorialExecutionPlan.ExpectedPostState original,
            List<LegalEditorialExecutionPlan.ExpectedDocumentSlot> documentSlots,
            List<LegalEditorialExecutionPlan.ExpectedRequiredSetPointer> requiredSetPointers,
            List<LegalEditorialExecutionPlan.ReplacementBatch> preexistingReplacementBatches,
            List<LegalEditorialExecutionPlan.ReplacementBatch> replacementBatches) {
        return new LegalEditorialExecutionPlan.ExpectedPostState(
                original.documentStates(),
                original.requirementStates(),
                original.documentTransitions(),
                original.requirementTransitions(),
                original.preexistingDocumentTransitions(),
                original.preexistingRequirementTransitions(),
                documentSlots,
                requiredSetPointers,
                preexistingReplacementBatches,
                replacementBatches,
                original.v27TriggerEffects());
    }

    private static LegalEditorialExecutionPlan.ExpectedPostState postStateWithBatches(
            List<LegalEditorialExecutionPlan.ReplacementBatch> historical,
            List<LegalEditorialExecutionPlan.ReplacementBatch> current) {
        return new LegalEditorialExecutionPlan.ExpectedPostState(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                historical,
                current,
                LegalEditorialExecutionPlan.V27TriggerEffects.empty());
    }

    private static LegalEditorialExecutionPlan.ReplacementBatch replacementBatch(
            UUID batchId,
            UUID predecessorId,
            UUID successorId) {
        return new LegalEditorialExecutionPlan.ReplacementBatch(
                batchId,
                PREEXISTING_AT,
                PREEXISTING_AT,
                List.of(predecessorId),
                List.of(new LegalEditorialExecutionPlan.ReplacementSuccessor(
                        successorId,
                        SOURCE_PUBLICATION)));
    }

    private static LegalEditorialExecutionPlan copy(
            LegalEditorialExecutionPlan original,
            LegalEditorialExecutionPlan.OperationType operationType,
            Optional<LegalEditorialExecutionPlan.SourceIdentity> source,
            LegalEditorialExecutionPlan.PublicationIdentity target,
            Optional<UUID> operationId,
            Optional<String> planSha256,
            LegalEditorialReadiness expectedReadinessAfter,
            boolean acknowledgeFailClosedGap,
            boolean changeRequired,
            LegalEditorialExecutionPlan.ExpectedPostState expectedPostState,
            LegalEditorialExecutionPlan.MutationCommands commands) {
        return new LegalEditorialExecutionPlan(
                operationType,
                source,
                target,
                operationId,
                planSha256,
                original.transactionAt(),
                original.observedAt(),
                original.expectedAppliedAt(),
                expectedReadinessAfter,
                acknowledgeFailClosedGap,
                changeRequired,
                expectedPostState,
                commands);
    }

    private static LegalEditorialExecutionPlan copyWithTimes(
            LegalEditorialExecutionPlan original,
            Instant transactionAt,
            Instant observedAt,
            Instant expectedAppliedAt) {
        return new LegalEditorialExecutionPlan(
                original.operationType(),
                original.source(),
                original.target(),
                original.operationId(),
                original.planSha256(),
                transactionAt,
                observedAt,
                expectedAppliedAt,
                original.expectedReadinessAfter(),
                original.acknowledgeFailClosedGap(),
                original.changeRequired(),
                original.expectedPostState(),
                original.mutationCommands());
    }

    private static LegalEditorialExecutionPlan.PublicationIdentity target() {
        return new LegalEditorialExecutionPlan.PublicationIdentity(
                "release-target",
                TARGET_PUBLICATION,
                SHA_B);
    }

    private static LegalEditorialExecutionPlan.SourceIdentity source() {
        return new LegalEditorialExecutionPlan.SourceIdentity(
                new LegalEditorialExecutionPlan.PublicationIdentity(
                        "release-source",
                        SOURCE_PUBLICATION,
                        SHA_A),
                FINGERPRINT);
    }

    private static LegalEditorialExecutionPlan.ExpectedDocumentState documentState(
            UUID documentVersionId,
            UUID batchId) {
        return new LegalEditorialExecutionPlan.ExpectedDocumentState(
                documentVersionId,
                EstadoVersionLegal.VIGENTE,
                OBSERVED_AT,
                null,
                batchId);
    }

    private static LegalEditorialExecutionPlan.DocumentTransition transition(
            UUID documentVersionId,
            EstadoVersionLegal previous,
            EstadoVersionLegal next,
            UUID batchId) {
        return transitionAt(documentVersionId, previous, next, batchId, OBSERVED_AT);
    }

    private static LegalEditorialExecutionPlan.DocumentTransition transitionAt(
            UUID documentVersionId,
            EstadoVersionLegal previous,
            EstadoVersionLegal next,
            UUID batchId,
            Instant occurredAt) {
        return new LegalEditorialExecutionPlan.DocumentTransition(
                documentVersionId,
                previous,
                next,
                null,
                batchId,
                occurredAt);
    }

    private static LegalEditorialExecutionPlan.RequirementTransition requirementTransition(
            EstadoVersionLegal previous,
            EstadoVersionLegal next) {
        return new LegalEditorialExecutionPlan.RequirementTransition(
                REQUIREMENT_ONE,
                previous,
                next,
                null,
                OBSERVED_AT);
    }

    private static LegalEditorialExecutionPlan.DocumentSlotKey slotKey() {
        return new LegalEditorialExecutionPlan.DocumentSlotKey(
                TipoDocumentoLegal.TERMINOS_SERVICIO,
                LocaleLegal.ES_AR,
                ContextoLegal.REGISTRO);
    }

    private static LegalEditorialExecutionPlan.ExpectedDocumentSlot preservedSlot() {
        return slot(
                new LegalEditorialExecutionPlan.DocumentSlotKey(
                        TipoDocumentoLegal.POLITICA_PRIVACIDAD,
                        LocaleLegal.ES_AR,
                        ContextoLegal.USO_CONTINUADO),
                DOCUMENT_ONE,
                DOCUMENT_LINE_ONE,
                SOURCE_PUBLICATION);
    }

    private static LegalEditorialExecutionPlan.ExpectedRequiredSetPointer preservedPointer(
            Instant updatedAt) {
        return new LegalEditorialExecutionPlan.ExpectedRequiredSetPointer(
                new LegalEditorialExecutionPlan.RequiredSetPointerKey(
                        LocaleLegal.ES_AR,
                        ContextoLegal.USO_CONTINUADO,
                        AudienciaLegal.ADMIN_TITULAR),
                REQUIRED_SET,
                SOURCE_PUBLICATION,
                REVISION,
                updatedAt,
                Optional.of(dependencies(
                        List.of(REQUIREMENT_ONE),
                        List.of(DOCUMENT_ONE))));
    }

    private static LegalEditorialExecutionPlan.RequiredSetDependencies dependencies(
            List<UUID> requirementVersionIds,
            List<UUID> documentVersionIds) {
        return new LegalEditorialExecutionPlan.RequiredSetDependencies(
                requirementVersionIds,
                documentVersionIds);
    }

    private static LegalEditorialExecutionPlan.RequiredSetPointerKey affectedPointerKey() {
        return new LegalEditorialExecutionPlan.RequiredSetPointerKey(
                LocaleLegal.ES_AR,
                ContextoLegal.REGISTRO,
                AudienciaLegal.ADMIN_TITULAR);
    }

    private static LegalEditorialExecutionPlan.ExpectedDocumentSlot slot(
            LegalEditorialExecutionPlan.DocumentSlotKey key,
            UUID documentVersionId,
            UUID lineId,
            UUID publicationId) {
        return new LegalEditorialExecutionPlan.ExpectedDocumentSlot(
                key,
                documentVersionId,
                lineId,
                publicationId);
    }

    private static UUID uuid(long value) {
        return new UUID(0, value);
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }
}
