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
    private static final UUID DOCUMENT_ONE = uuid(10);
    private static final UUID DOCUMENT_TWO = uuid(11);
    private static final UUID DOCUMENT_PREDECESSOR = uuid(12);
    private static final UUID DOCUMENT_SUCCESSOR = uuid(13);
    private static final UUID DOCUMENT_LINE_ONE = uuid(20);
    private static final UUID DOCUMENT_LINE_TWO = uuid(21);
    private static final UUID REQUIREMENT_ONE = uuid(30);
    private static final UUID REQUIRED_SET = uuid(40);

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
                        "observedAt",
                        "expectedAppliedAt",
                        "expectedReadinessAfter",
                        "acknowledgeFailClosedGap",
                        "changeRequired",
                        "expectedPostState",
                        "mutationCommands");
    }

    @Test
    void promoteCarriesOnlyDirectCommandsAndUsesOnePostgresTimestamp() {
        LegalEditorialExecutionPlan plan = promote(true);

        assertThat(plan.operationType())
                .isEqualTo(LegalEditorialExecutionPlan.OperationType.PROMOTE);
        assertThat(plan.source()).isEmpty();
        assertThat(plan.operationId()).isEmpty();
        assertThat(plan.planSha256()).isEmpty();
        assertThat(plan.expectedReadinessAfter()).isEqualTo(LegalEditorialReadiness.READY);
        assertThat(plan.acknowledgeFailClosedGap()).isFalse();
        assertThat(plan.changeRequired()).isTrue();
        assertThat(plan.expectedAppliedAt()).isEqualTo(plan.observedAt());
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
    void retirementIsFailClosedAndOnlyContainsExplicitTerminalEffects() {
        LegalEditorialExecutionPlan plan = retirement(true);

        assertThat(plan.source()).contains(source());
        assertThat(plan.target()).isEqualTo(source().publication());
        assertThat(plan.expectedReadinessAfter()).isEqualTo(LegalEditorialReadiness.NOT_READY);
        assertThat(plan.acknowledgeFailClosedGap()).isTrue();
        assertThat(plan.expectedPostState().documentSlots()).isEmpty();
        assertThat(plan.expectedPostState().requiredSetPointers()).isEmpty();
        assertThat(plan.mutationCommands().documentTransitions())
                .singleElement()
                .satisfies(transition -> {
                    assertThat(transition.newState()).isEqualTo(EstadoVersionLegal.RETIRADA);
                    assertThat(transition.reason()).isEqualTo("Retiro operativo explícito");
                });
        assertThat(plan.deltaCounts()).isEqualTo(new LegalEditorialExecutionPlan.DeltaCounts(
                1, 0, 0, 1, 0, 0, 0, 0, 0, 0));
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
    void applicationTimeUsesPostgresPrecisionCannotBeFutureAndMatchesFreshObservation() {
        LegalEditorialExecutionPlan replay = promote(false);
        LegalEditorialExecutionPlan fresh = promote(true);

        assertThatThrownBy(() -> copyWithTimes(replay, OBSERVED_AT, OTHER_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("posterior");
        assertThatThrownBy(() -> copyWithTimes(
                replay,
                OTHER_AT,
                Instant.parse("2026-08-28T18:00:00.123456789Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("microsegundos");
        assertThatThrownBy(() -> copyWithTimes(replay, OTHER_AT, PREEXISTING_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expectedAppliedAt");
        assertThatThrownBy(() -> copyWithTimes(fresh, OTHER_AT, OBSERVED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mutación fresca");
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
                                        null,
                                        PREEXISTING_AT)),
                        List.of(),
                        List.of(successorSlot),
                        List.of(),
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
        LegalEditorialExecutionPlan.ExpectedPostState postState =
                new LegalEditorialExecutionPlan.ExpectedPostState(
                        List.of(new LegalEditorialExecutionPlan.ExpectedDocumentState(
                                DOCUMENT_PREDECESSOR,
                                EstadoVersionLegal.RETIRADA,
                                OBSERVED_AT,
                                "Retiro operativo explícito",
                                null)),
                        List.of(),
                        List.of(transition),
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
                                        null,
                                        PREEXISTING_AT)),
                        List.of(),
                        List.of(),
                        List.of(),
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
                        List.of(),
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
                OBSERVED_AT,
                LegalEditorialReadiness.NOT_READY,
                true,
                changeRequired,
                postState,
                commands);
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
            Instant observedAt,
            Instant expectedAppliedAt) {
        return new LegalEditorialExecutionPlan(
                original.operationType(),
                original.source(),
                original.target(),
                original.operationId(),
                original.planSha256(),
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
}
