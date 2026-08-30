package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialReadiness;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoVersionLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LegalEditorialPostStateVerifierTest {

    private static final Instant APPLIED_AT =
            Instant.parse("2026-08-28T18:00:00.123456Z");
    private static final Instant HISTORICAL_APPLIED_AT = APPLIED_AT.minusSeconds(1);
    private static final Instant REPLAY_OBSERVED_AT =
            Instant.parse("2026-08-29T18:00:00.654321Z");
    private static final UUID PUBLICATION = uuid(1);
    private static final UUID SOURCE_PUBLICATION = uuid(2);
    private static final UUID DOCUMENT = uuid(10);
    private static final UUID SOURCE_DOCUMENT = uuid(11);
    private static final UUID HISTORICAL_PREDECESSOR = uuid(12);
    private static final UUID DOCUMENT_LINE = uuid(20);
    private static final UUID SOURCE_DOCUMENT_LINE = uuid(21);
    private static final UUID REQUIREMENT = uuid(30);
    private static final UUID REQUIREMENT_LINE = uuid(31);
    private static final UUID REQUIRED_SET = uuid(40);
    private static final UUID UNEXPECTED_BATCH = uuid(50);
    private static final UUID CURRENT_BATCH = uuid(51);
    private static final UUID HISTORICAL_BATCH = uuid(52);

    @Test
    void exactFreshPromoteBuildsTheReceiptFromTheAccreditedTargetProjection() {
        Harness harness = new Harness(promotedSnapshot());
        LegalEditorialExecutionPlan plan = promotePlan(true, APPLIED_AT);

        LegalEditorialApplyReceipt receipt = harness.verifier.verify(plan);

        assertThat(receipt).isEqualTo(new LegalEditorialApplyReceipt(
                LegalEditorialApplyReceipt.OperationType.PROMOTE,
                PUBLICATION,
                APPLIED_AT,
                LegalEditorialReadiness.READY,
                1,
                1,
                2,
                2,
                1,
                1,
                0));
        verify(harness.reader).snapshot(
                PUBLICATION,
                PUBLICATION,
                Set.of(DOCUMENT),
                Set.of(REQUIREMENT),
                Set.of());
        verifyNoInteractions(harness.jdbc);
    }

    @Test
    void replayKeepsExpectedAppliedAtInsteadOfTheLaterObservationTimestamp() {
        Harness harness = new Harness(promotedSnapshot());
        LegalEditorialExecutionPlan replay = promotePlan(false, REPLAY_OBSERVED_AT);

        LegalEditorialApplyReceipt receipt = harness.verifier.verify(replay);

        assertThat(receipt.appliedAt()).isEqualTo(APPLIED_AT);
        assertThat(receipt.appliedAt()).isNotEqualTo(replay.observedAt());
    }

    @Test
    void sourceStatesAreVerifiedButExcludedFromTargetReceiptCounts() {
        LegalEditorialExecutionPlan plan = replacementPlan();
        Harness harness = new Harness(replacementSnapshot());

        LegalEditorialApplyReceipt receipt = harness.verifier.verify(plan);

        assertThat(receipt.operationType())
                .isEqualTo(LegalEditorialApplyReceipt.OperationType.REPLACE);
        assertThat(receipt.documentVersions()).isEqualTo(1);
        assertThat(receipt.documentTransitions()).isEqualTo(2);
        assertThat(receipt.documentSlots()).isEqualTo(1);
        assertThat(receipt.replacementBatches()).isEqualTo(1);
        verify(harness.reader).snapshot(
                PUBLICATION,
                SOURCE_PUBLICATION,
                Set.of(DOCUMENT, SOURCE_DOCUMENT),
                Set.of(),
                Set.of(HISTORICAL_BATCH, CURRENT_BATCH));
    }

    @Test
    void exactRetirementPreservesUnaffectedProjectionAndBuildsNotReadyReceipt() {
        LegalEditorialExecutionPlan plan = retirementPlan();
        Harness harness = new Harness(retirementSnapshot());

        LegalEditorialApplyReceipt receipt = harness.verifier.verify(plan);

        assertThat(receipt).isEqualTo(new LegalEditorialApplyReceipt(
                LegalEditorialApplyReceipt.OperationType.RETIRE,
                PUBLICATION,
                APPLIED_AT,
                LegalEditorialReadiness.NOT_READY,
                2,
                1,
                5,
                2,
                1,
                1,
                1));
        verify(harness.reader).snapshot(
                PUBLICATION,
                PUBLICATION,
                Set.of(DOCUMENT, SOURCE_DOCUMENT),
                Set.of(REQUIREMENT),
                Set.of(HISTORICAL_BATCH));
    }

    @Test
    void failsClosedWhenARetirementPointerDiffersFromItsFixedExpectedUpdatedAt() {
        LegalEditorialPlannerCore.PlannerSnapshot exact = retirementSnapshot();
        LegalEditorialPlannerCore.PointerEvidence pointer = exact.activePointers().getFirst();
        LegalEditorialPlannerCore.PointerEvidence drifted =
                new LegalEditorialPlannerCore.PointerEvidence(
                        pointer.key(),
                        pointer.requiredSetId(),
                        pointer.publicationId(),
                        pointer.revision(),
                        pointer.updatedAt().plusMillis(1),
                        pointer.memberVersionIds(),
                        pointer.referencedDocumentVersionIds());
        Harness harness = new Harness(copy(
                exact,
                exact.documents(),
                exact.documentTransitions(),
                exact.activeSlots(),
                List.of(drifted),
                exact.batches()));

        assertPostconditionMismatch(harness, retirementPlan());
    }

    @Test
    void failsClosedWhenARetirementPointerDependenciesDifferFromTheFixedExpectedGraph() {
        LegalEditorialPlannerCore.PlannerSnapshot exact = retirementSnapshot();
        LegalEditorialPlannerCore.PointerEvidence pointer = exact.activePointers().getFirst();
        LegalEditorialPlannerCore.PointerEvidence drifted =
                new LegalEditorialPlannerCore.PointerEvidence(
                        pointer.key(),
                        pointer.requiredSetId(),
                        pointer.publicationId(),
                        pointer.revision(),
                        pointer.updatedAt(),
                        List.of(uuid(99)),
                        pointer.referencedDocumentVersionIds());
        Harness harness = new Harness(copy(
                exact,
                exact.documents(),
                exact.documentTransitions(),
                exact.activeSlots(),
                List.of(drifted),
                exact.batches()));

        assertPostconditionMismatch(harness, retirementPlan());
    }

    @Test
    void failsClosedWhenARetirementPointerDocumentReferencesDifferFromExpectation() {
        LegalEditorialPlannerCore.PlannerSnapshot exact = retirementSnapshot();
        LegalEditorialPlannerCore.PointerEvidence pointer = exact.activePointers().getFirst();
        LegalEditorialPlannerCore.PointerEvidence drifted =
                new LegalEditorialPlannerCore.PointerEvidence(
                        pointer.key(),
                        pointer.requiredSetId(),
                        pointer.publicationId(),
                        pointer.revision(),
                        pointer.updatedAt(),
                        pointer.memberVersionIds(),
                        List.of(uuid(98)));
        Harness harness = new Harness(copy(
                exact,
                exact.documents(),
                exact.documentTransitions(),
                exact.activeSlots(),
                List.of(drifted),
                exact.batches()));

        assertPostconditionMismatch(harness, retirementPlan());
    }

    @Test
    void failsClosedWhenARetirementPreservedRequirementTimestampDrifts() {
        LegalEditorialPlannerCore.PlannerSnapshot exact = retirementSnapshot();
        LegalEditorialPlannerCore.RequirementEvidence requirement =
                exact.requirements().get(REQUIREMENT);
        LegalEditorialPlannerCore.RequirementEvidence drifted =
                new LegalEditorialPlannerCore.RequirementEvidence(
                        requirement.id(),
                        requirement.lineId(),
                        requirement.introductionPublicationId(),
                        requirement.statementSha256(),
                        requirement.state(),
                        requirement.stateChangedAt().plusMillis(1),
                        requirement.lastReason(),
                        requirement.locale(),
                        requirement.context(),
                        requirement.audiences());
        Harness harness = new Harness(copyRequirements(
                exact,
                Map.of(REQUIREMENT, drifted),
                exact.requirementTransitions()));

        assertPostconditionMismatch(harness, retirementPlan());
    }

    @Test
    void failsClosedWhenReplacementPreexistingHistoryIsMissingARow() {
        LegalEditorialPlannerCore.PlannerSnapshot exact = replacementSnapshot();
        List<LegalEditorialPlannerCore.DocumentTransitionEvidence> incompleteHistory =
                exact.documentTransitions().stream()
                        .filter(transition -> !(transition.versionId().equals(SOURCE_DOCUMENT)
                                && transition.previousState() == EstadoVersionLegal.BORRADOR))
                        .toList();
        Harness harness = new Harness(copy(
                exact,
                exact.documents(),
                incompleteHistory,
                exact.activeSlots(),
                exact.activePointers(),
                exact.batches()));

        assertPostconditionMismatch(harness, replacementPlan());
    }

    @Test
    void failsClosedWhenAHistoricalBatchDiffersFromTheAccreditedChain() {
        LegalEditorialPlannerCore.PlannerSnapshot exact = replacementSnapshot();
        Map<UUID, LegalEditorialPlannerCore.BatchEvidence> batches =
                new LinkedHashMap<>(exact.batches());
        batches.put(HISTORICAL_BATCH, new LegalEditorialPlannerCore.BatchEvidence(
                HISTORICAL_BATCH,
                HISTORICAL_APPLIED_AT,
                HISTORICAL_APPLIED_AT,
                List.of(uuid(13)),
                List.of(new LegalEditorialExecutionPlan.ReplacementSuccessor(
                        SOURCE_DOCUMENT,
                        SOURCE_PUBLICATION))));
        Harness harness = new Harness(copy(
                exact,
                exact.documents(),
                exact.documentTransitions(),
                exact.activeSlots(),
                exact.activePointers(),
                batches));

        assertPostconditionMismatch(harness, replacementPlan());
    }

    @Test
    void failsClosedWhenAClassifiedVersionStateDiffers() {
        LegalEditorialPlannerCore.PlannerSnapshot exact = promotedSnapshot();
        LegalEditorialPlannerCore.DocumentEvidence drifted = new LegalEditorialPlannerCore
                .DocumentEvidence(
                DOCUMENT,
                DOCUMENT_LINE,
                PUBLICATION,
                "document-sha",
                APPLIED_AT.minusSeconds(1),
                EstadoVersionLegal.PUBLICADA,
                APPLIED_AT,
                null,
                null,
                TipoDocumentoLegal.TERMINOS_SERVICIO,
                LocaleLegal.ES_AR,
                List.of(ContextoLegal.REGISTRO));
        Harness harness = new Harness(copy(exact, Map.of(DOCUMENT, drifted),
                exact.documentTransitions(), exact.activeSlots(), exact.activePointers(),
                exact.batches()));

        assertPostconditionMismatch(harness, promotePlan(true, APPLIED_AT));
    }

    @Test
    void failsClosedWhenTargetMembershipIsMissingAnExpectedVersion() {
        LegalEditorialPlannerCore.PlannerSnapshot exact = promotedSnapshot();
        Harness harness = new Harness(new LegalEditorialPlannerCore.PlannerSnapshot(
                List.of(),
                exact.targetRequirementIds(),
                exact.sourceDocumentIds(),
                exact.sourceRequirementIds(),
                exact.documents(),
                exact.requirements(),
                exact.targetScopes(),
                exact.activeSlots(),
                exact.activePointers(),
                exact.documentTransitions(),
                exact.requirementTransitions(),
                exact.batches()));

        assertPostconditionMismatch(harness, promotePlan(true, APPLIED_AT));
    }

    @Test
    void failsClosedWhenCompleteHistoryIsMissingARow() {
        LegalEditorialPlannerCore.PlannerSnapshot exact = promotedSnapshot();
        Harness harness = new Harness(copy(exact, exact.documents(),
                List.of(exact.documentTransitions().getFirst()), exact.activeSlots(),
                exact.activePointers(), exact.batches()));

        assertPostconditionMismatch(harness, promotePlan(true, APPLIED_AT));
    }

    @Test
    void failsClosedWhenCompleteRequirementHistoryIsMissingARow() {
        LegalEditorialPlannerCore.PlannerSnapshot exact = promotedSnapshot();
        Harness harness = new Harness(new LegalEditorialPlannerCore.PlannerSnapshot(
                exact.targetDocumentIds(),
                exact.targetRequirementIds(),
                exact.sourceDocumentIds(),
                exact.sourceRequirementIds(),
                exact.documents(),
                exact.requirements(),
                exact.targetScopes(),
                exact.activeSlots(),
                exact.activePointers(),
                exact.documentTransitions(),
                List.of(exact.requirementTransitions().getFirst()),
                exact.batches()));

        assertPostconditionMismatch(harness, promotePlan(true, APPLIED_AT));
    }

    @Test
    void failsClosedWhenCompleteHistoryHasAnExtraRow() {
        LegalEditorialPlannerCore.PlannerSnapshot exact = promotedSnapshot();
        List<LegalEditorialPlannerCore.DocumentTransitionEvidence> transitions =
                new ArrayList<>(exact.documentTransitions());
        transitions.add(new LegalEditorialPlannerCore.DocumentTransitionEvidence(
                3,
                DOCUMENT,
                EstadoVersionLegal.BORRADOR,
                EstadoVersionLegal.PUBLICADA,
                null,
                null,
                APPLIED_AT));
        Harness harness = new Harness(copy(exact, exact.documents(), transitions,
                exact.activeSlots(), exact.activePointers(), exact.batches()));

        assertPostconditionMismatch(harness, promotePlan(true, APPLIED_AT));
    }

    @Test
    void failsClosedWhenTransitionIdsRevealAReorderedChain() {
        LegalEditorialPlannerCore.PlannerSnapshot exact = promotedSnapshot();
        List<LegalEditorialPlannerCore.DocumentTransitionEvidence> reordered = List.of(
                documentTransitionEvidence(2, DOCUMENT,
                        EstadoVersionLegal.BORRADOR, EstadoVersionLegal.PUBLICADA, null),
                documentTransitionEvidence(1, DOCUMENT,
                        EstadoVersionLegal.PUBLICADA, EstadoVersionLegal.VIGENTE, null));
        Harness harness = new Harness(copy(exact, exact.documents(), reordered,
                exact.activeSlots(), exact.activePointers(), exact.batches()));

        assertPostconditionMismatch(harness, promotePlan(true, APPLIED_AT));
    }

    @Test
    void failsClosedWhenTheGlobalSlotProjectionHasAnExtraRow() {
        LegalEditorialPlannerCore.PlannerSnapshot exact = promotedSnapshot();
        List<LegalEditorialPlannerCore.SlotEvidence> slots = new ArrayList<>(exact.activeSlots());
        slots.add(new LegalEditorialPlannerCore.SlotEvidence(
                new LegalEditorialExecutionPlan.DocumentSlotKey(
                        TipoDocumentoLegal.POLITICA_PRIVACIDAD,
                        LocaleLegal.ES_AR,
                        ContextoLegal.USO_CONTINUADO),
                DOCUMENT,
                DOCUMENT_LINE,
                PUBLICATION));
        Harness harness = new Harness(copy(exact, exact.documents(),
                exact.documentTransitions(), slots, exact.activePointers(), exact.batches()));

        assertPostconditionMismatch(harness, promotePlan(true, APPLIED_AT));
    }

    @Test
    void failsClosedWhenAPointerRevisionDiffers() {
        LegalEditorialPlannerCore.PlannerSnapshot exact = promotedSnapshot();
        LegalEditorialPlannerCore.PointerEvidence pointer = exact.activePointers().getFirst();
        LegalEditorialPlannerCore.PointerEvidence drifted = new LegalEditorialPlannerCore
                .PointerEvidence(
                pointer.key(),
                pointer.requiredSetId(),
                pointer.publicationId(),
                "sha256:" + "c".repeat(64),
                pointer.updatedAt(),
                pointer.memberVersionIds(),
                pointer.referencedDocumentVersionIds());
        Harness harness = new Harness(copy(exact, exact.documents(),
                exact.documentTransitions(), exact.activeSlots(), List.of(drifted),
                exact.batches()));

        assertPostconditionMismatch(harness, promotePlan(true, APPLIED_AT));
    }

    @Test
    void failsClosedWhenAnUnexpectedRelatedBatchExists() {
        LegalEditorialPlannerCore.PlannerSnapshot exact = promotedSnapshot();
        Map<UUID, LegalEditorialPlannerCore.BatchEvidence> batches = Map.of(
                UNEXPECTED_BATCH,
                new LegalEditorialPlannerCore.BatchEvidence(
                        UNEXPECTED_BATCH,
                        APPLIED_AT,
                        APPLIED_AT,
                        List.of(DOCUMENT),
                        List.of(new LegalEditorialExecutionPlan.ReplacementSuccessor(
                                uuid(12), PUBLICATION))));
        Harness harness = new Harness(copy(exact, exact.documents(),
                exact.documentTransitions(), exact.activeSlots(), exact.activePointers(), batches));

        assertPostconditionMismatch(harness, promotePlan(true, APPLIED_AT));
    }

    @Test
    void productionReaderIsSelectOnlyAndNeverInfersAppliedAtFromHistory() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialPostStateVerifier.java"));

        assertThat(source)
                .doesNotContain("max(", "batchUpdate(", ".update(", ".execute(",
                        "FOR UPDATE", "transaction_timestamp()")
                .contains("expectedAppliedAt()");
    }

    private static void assertPostconditionMismatch(
            Harness harness,
            LegalEditorialExecutionPlan plan) {
        assertThatThrownBy(() -> harness.verifier.verify(plan))
                .isInstanceOfSatisfying(
                        LegalEditorialOperationalException.class,
                        failure -> assertThat(failure.issue().code())
                                .isEqualTo(LegalManifestIssueCode.POSTCONDITION_NOT_READY));
    }

    private static LegalEditorialExecutionPlan promotePlan(
            boolean changeRequired,
            Instant observedAt) {
        LegalEditorialExecutionPlan.ExpectedPostState expected = promotedExpectedPostState();
        return new LegalEditorialExecutionPlan(
                LegalEditorialExecutionPlan.OperationType.PROMOTE,
                Optional.empty(),
                publication(PUBLICATION, "target"),
                Optional.empty(),
                Optional.empty(),
                observedAt,
                APPLIED_AT,
                LegalEditorialReadiness.READY,
                false,
                changeRequired,
                expected,
                changeRequired
                        ? new LegalEditorialExecutionPlan.MutationCommands(
                                expected.documentTransitions(),
                                expected.requirementTransitions(),
                                List.of(),
                                expected.documentSlots(),
                                List.of(),
                                expected.requiredSetPointers(),
                                List.of())
                        : LegalEditorialExecutionPlan.MutationCommands.empty());
    }

    private static LegalEditorialExecutionPlan replacementPlan() {
        LegalEditorialExecutionPlan plan = mock(LegalEditorialExecutionPlan.class);
        when(plan.operationType()).thenReturn(LegalEditorialExecutionPlan.OperationType.REPLACE);
        when(plan.target()).thenReturn(publication(PUBLICATION, "target"));
        when(plan.source()).thenReturn(Optional.of(new LegalEditorialExecutionPlan.SourceIdentity(
                publication(SOURCE_PUBLICATION, "source"),
                "sha256:" + "b".repeat(64))));
        when(plan.expectedAppliedAt()).thenReturn(APPLIED_AT);
        when(plan.expectedReadinessAfter()).thenReturn(LegalEditorialReadiness.READY);
        when(plan.expectedPostState()).thenReturn(replacementExpectedPostState());
        return plan;
    }

    private static LegalEditorialExecutionPlan retirementPlan() {
        return new LegalEditorialExecutionPlan(
                LegalEditorialExecutionPlan.OperationType.RETIRE,
                Optional.of(new LegalEditorialExecutionPlan.SourceIdentity(
                        publication(PUBLICATION, "target"),
                        "sha256:" + "b".repeat(64))),
                publication(PUBLICATION, "target"),
                Optional.of(uuid(60)),
                Optional.of("c".repeat(64)),
                REPLAY_OBSERVED_AT,
                APPLIED_AT,
                LegalEditorialReadiness.NOT_READY,
                true,
                false,
                retirementExpectedPostState(),
                LegalEditorialExecutionPlan.MutationCommands.empty());
    }

    private static LegalEditorialExecutionPlan.ExpectedPostState promotedExpectedPostState() {
        return new LegalEditorialExecutionPlan.ExpectedPostState(
                List.of(documentState(DOCUMENT, EstadoVersionLegal.VIGENTE, null)),
                List.of(requirementState()),
                documentPromotionHistory(DOCUMENT),
                requirementPromotionHistory(),
                List.of(),
                List.of(),
                List.of(slot(DOCUMENT, DOCUMENT_LINE)),
                List.of(pointer()),
                List.of(),
                LegalEditorialExecutionPlan.V27TriggerEffects.empty());
    }

    private static LegalEditorialExecutionPlan.ExpectedPostState replacementExpectedPostState() {
        LegalEditorialExecutionPlan.DocumentTransition replaceSource =
                new LegalEditorialExecutionPlan.DocumentTransition(
                SOURCE_DOCUMENT,
                EstadoVersionLegal.VIGENTE,
                EstadoVersionLegal.REEMPLAZADA,
                null,
                CURRENT_BATCH,
                APPLIED_AT);
        List<LegalEditorialExecutionPlan.DocumentTransition> transitions = new ArrayList<>(
                documentPromotionHistory(DOCUMENT, APPLIED_AT, CURRENT_BATCH));
        transitions.add(replaceSource);
        LegalEditorialExecutionPlan.ReplacementBatch historicalBatch =
                new LegalEditorialExecutionPlan.ReplacementBatch(
                        HISTORICAL_BATCH,
                        HISTORICAL_APPLIED_AT,
                        HISTORICAL_APPLIED_AT,
                        List.of(HISTORICAL_PREDECESSOR),
                        List.of(new LegalEditorialExecutionPlan.ReplacementSuccessor(
                                SOURCE_DOCUMENT,
                                SOURCE_PUBLICATION)));
        LegalEditorialExecutionPlan.ReplacementBatch currentBatch =
                new LegalEditorialExecutionPlan.ReplacementBatch(
                        CURRENT_BATCH,
                        APPLIED_AT,
                        APPLIED_AT,
                        List.of(SOURCE_DOCUMENT),
                        List.of(new LegalEditorialExecutionPlan.ReplacementSuccessor(
                                DOCUMENT,
                                PUBLICATION)));
        return new LegalEditorialExecutionPlan.ExpectedPostState(
                List.of(
                        documentState(
                                DOCUMENT,
                                EstadoVersionLegal.VIGENTE,
                                null,
                                CURRENT_BATCH),
                        documentState(
                                SOURCE_DOCUMENT,
                                EstadoVersionLegal.REEMPLAZADA,
                                null,
                                CURRENT_BATCH)),
                List.of(),
                transitions,
                List.of(),
                documentPromotionHistory(
                        SOURCE_DOCUMENT,
                        HISTORICAL_APPLIED_AT,
                        HISTORICAL_BATCH),
                List.of(),
                List.of(slot(DOCUMENT, DOCUMENT_LINE)),
                List.of(),
                List.of(historicalBatch),
                List.of(currentBatch),
                new LegalEditorialExecutionPlan.V27TriggerEffects(
                        List.of(
                                transitions.stream()
                                        .filter(transition -> transition.documentVersionId()
                                                .equals(DOCUMENT)
                                                && transition.newState()
                                                == EstadoVersionLegal.VIGENTE)
                                        .findFirst()
                                        .orElseThrow(),
                                replaceSource),
                        List.of(new LegalEditorialExecutionPlan.DocumentSlotDelete(
                                slot(DOCUMENT, DOCUMENT_LINE).key(),
                                SOURCE_DOCUMENT)),
                        List.of(slot(DOCUMENT, DOCUMENT_LINE))));
    }

    private static LegalEditorialExecutionPlan.ExpectedPostState retirementExpectedPostState() {
        LegalEditorialExecutionPlan.DocumentTransition retirement =
                new LegalEditorialExecutionPlan.DocumentTransition(
                        DOCUMENT,
                        EstadoVersionLegal.VIGENTE,
                        EstadoVersionLegal.RETIRADA,
                        "Retiro operativo explícito",
                        null,
                        APPLIED_AT);
        List<LegalEditorialExecutionPlan.DocumentTransition> preexisting = new ArrayList<>();
        preexisting.addAll(documentPromotionHistory(DOCUMENT, HISTORICAL_APPLIED_AT));
        preexisting.addAll(documentPromotionHistory(
                SOURCE_DOCUMENT,
                HISTORICAL_APPLIED_AT,
                HISTORICAL_BATCH));
        LegalEditorialExecutionPlan.ReplacementBatch historicalBatch =
                new LegalEditorialExecutionPlan.ReplacementBatch(
                        HISTORICAL_BATCH,
                        HISTORICAL_APPLIED_AT,
                        HISTORICAL_APPLIED_AT,
                        List.of(HISTORICAL_PREDECESSOR),
                        List.of(new LegalEditorialExecutionPlan.ReplacementSuccessor(
                                SOURCE_DOCUMENT,
                                PUBLICATION)));
        return new LegalEditorialExecutionPlan.ExpectedPostState(
                List.of(
                        new LegalEditorialExecutionPlan.ExpectedDocumentState(
                                DOCUMENT,
                                EstadoVersionLegal.RETIRADA,
                                APPLIED_AT,
                                "Retiro operativo explícito",
                                null),
                        new LegalEditorialExecutionPlan.ExpectedDocumentState(
                                SOURCE_DOCUMENT,
                                EstadoVersionLegal.VIGENTE,
                                HISTORICAL_APPLIED_AT,
                                null,
                                HISTORICAL_BATCH)),
                List.of(requirementState(HISTORICAL_APPLIED_AT)),
                List.of(retirement),
                List.of(),
                preexisting,
                requirementPromotionHistory(HISTORICAL_APPLIED_AT),
                List.of(slot(SOURCE_DOCUMENT, SOURCE_DOCUMENT_LINE)),
                List.of(retirementPointer(HISTORICAL_APPLIED_AT)),
                List.of(historicalBatch),
                List.of(),
                LegalEditorialExecutionPlan.V27TriggerEffects.empty());
    }

    private static LegalEditorialPlannerCore.PlannerSnapshot promotedSnapshot() {
        return new LegalEditorialPlannerCore.PlannerSnapshot(
                List.of(DOCUMENT),
                List.of(REQUIREMENT),
                List.of(DOCUMENT),
                List.of(REQUIREMENT),
                Map.of(DOCUMENT, documentEvidence(
                        DOCUMENT, DOCUMENT_LINE, PUBLICATION, EstadoVersionLegal.VIGENTE, null)),
                Map.of(REQUIREMENT, requirementEvidence()),
                List.of(),
                List.of(new LegalEditorialPlannerCore.SlotEvidence(
                        slot(DOCUMENT, DOCUMENT_LINE).key(),
                        DOCUMENT,
                        DOCUMENT_LINE,
                        PUBLICATION)),
                List.of(new LegalEditorialPlannerCore.PointerEvidence(
                        pointer().key(),
                        REQUIRED_SET,
                        PUBLICATION,
                        pointer().requiredSetRevision(),
                        APPLIED_AT,
                        List.of(REQUIREMENT),
                        List.of(DOCUMENT))),
                List.of(
                        documentTransitionEvidence(1, DOCUMENT,
                                EstadoVersionLegal.BORRADOR, EstadoVersionLegal.PUBLICADA, null),
                        documentTransitionEvidence(2, DOCUMENT,
                                EstadoVersionLegal.PUBLICADA, EstadoVersionLegal.VIGENTE, null)),
                List.of(
                        requirementTransitionEvidence(1,
                                EstadoVersionLegal.BORRADOR, EstadoVersionLegal.PUBLICADA),
                        requirementTransitionEvidence(2,
                                EstadoVersionLegal.PUBLICADA, EstadoVersionLegal.VIGENTE)),
                Map.of());
    }

    private static LegalEditorialPlannerCore.PlannerSnapshot replacementSnapshot() {
        Map<UUID, LegalEditorialPlannerCore.DocumentEvidence> documents = new LinkedHashMap<>();
        documents.put(DOCUMENT, documentEvidence(
                DOCUMENT,
                DOCUMENT_LINE,
                PUBLICATION,
                EstadoVersionLegal.VIGENTE,
                null,
                CURRENT_BATCH));
        documents.put(SOURCE_DOCUMENT, documentEvidence(
                SOURCE_DOCUMENT,
                SOURCE_DOCUMENT_LINE,
                SOURCE_PUBLICATION,
                EstadoVersionLegal.REEMPLAZADA,
                null,
                CURRENT_BATCH));
        return new LegalEditorialPlannerCore.PlannerSnapshot(
                List.of(DOCUMENT),
                List.of(),
                List.of(SOURCE_DOCUMENT),
                List.of(),
                documents,
                Map.of(),
                List.of(),
                List.of(new LegalEditorialPlannerCore.SlotEvidence(
                        slot(DOCUMENT, DOCUMENT_LINE).key(),
                        DOCUMENT,
                        DOCUMENT_LINE,
                        PUBLICATION)),
                List.of(),
                List.of(
                        documentTransitionEvidence(1, DOCUMENT,
                                EstadoVersionLegal.BORRADOR, EstadoVersionLegal.PUBLICADA, null),
                        documentTransitionEvidence(2, DOCUMENT,
                                EstadoVersionLegal.PUBLICADA, EstadoVersionLegal.VIGENTE, null,
                                CURRENT_BATCH, APPLIED_AT),
                        documentTransitionEvidence(3, SOURCE_DOCUMENT,
                                EstadoVersionLegal.BORRADOR, EstadoVersionLegal.PUBLICADA, null,
                                HISTORICAL_APPLIED_AT),
                        documentTransitionEvidence(4, SOURCE_DOCUMENT,
                                EstadoVersionLegal.PUBLICADA, EstadoVersionLegal.VIGENTE, null,
                                HISTORICAL_BATCH, HISTORICAL_APPLIED_AT),
                        documentTransitionEvidence(5, SOURCE_DOCUMENT,
                                EstadoVersionLegal.VIGENTE, EstadoVersionLegal.REEMPLAZADA,
                                null, CURRENT_BATCH, APPLIED_AT)),
                List.of(),
                Map.of(
                        HISTORICAL_BATCH,
                        new LegalEditorialPlannerCore.BatchEvidence(
                                HISTORICAL_BATCH,
                                HISTORICAL_APPLIED_AT,
                                HISTORICAL_APPLIED_AT,
                                List.of(HISTORICAL_PREDECESSOR),
                                List.of(new LegalEditorialExecutionPlan.ReplacementSuccessor(
                                        SOURCE_DOCUMENT,
                                        SOURCE_PUBLICATION))),
                        CURRENT_BATCH,
                        new LegalEditorialPlannerCore.BatchEvidence(
                                CURRENT_BATCH,
                                APPLIED_AT,
                                APPLIED_AT,
                                List.of(SOURCE_DOCUMENT),
                                List.of(new LegalEditorialExecutionPlan.ReplacementSuccessor(
                                        DOCUMENT,
                                        PUBLICATION)))));
    }

    private static LegalEditorialPlannerCore.PlannerSnapshot retirementSnapshot() {
        Map<UUID, LegalEditorialPlannerCore.DocumentEvidence> documents = new LinkedHashMap<>();
        documents.put(DOCUMENT, new LegalEditorialPlannerCore.DocumentEvidence(
                DOCUMENT,
                DOCUMENT_LINE,
                PUBLICATION,
                "document-sha",
                HISTORICAL_APPLIED_AT.minusSeconds(1),
                EstadoVersionLegal.RETIRADA,
                APPLIED_AT,
                "Retiro operativo explícito",
                null,
                TipoDocumentoLegal.POLITICA_PRIVACIDAD,
                LocaleLegal.ES_AR,
                List.of(ContextoLegal.REGISTRO)));
        documents.put(SOURCE_DOCUMENT, new LegalEditorialPlannerCore.DocumentEvidence(
                SOURCE_DOCUMENT,
                SOURCE_DOCUMENT_LINE,
                PUBLICATION,
                "survivor-document-sha",
                HISTORICAL_APPLIED_AT.minusSeconds(1),
                EstadoVersionLegal.VIGENTE,
                HISTORICAL_APPLIED_AT,
                null,
                HISTORICAL_BATCH,
                TipoDocumentoLegal.TERMINOS_SERVICIO,
                LocaleLegal.ES_AR,
                List.of(ContextoLegal.REGISTRO)));
        return new LegalEditorialPlannerCore.PlannerSnapshot(
                List.of(DOCUMENT, SOURCE_DOCUMENT),
                List.of(REQUIREMENT),
                List.of(DOCUMENT, SOURCE_DOCUMENT),
                List.of(REQUIREMENT),
                documents,
                Map.of(REQUIREMENT, requirementEvidence(HISTORICAL_APPLIED_AT)),
                List.of(),
                List.of(new LegalEditorialPlannerCore.SlotEvidence(
                        slot(SOURCE_DOCUMENT, SOURCE_DOCUMENT_LINE).key(),
                        SOURCE_DOCUMENT,
                        SOURCE_DOCUMENT_LINE,
                        PUBLICATION)),
                List.of(new LegalEditorialPlannerCore.PointerEvidence(
                        pointer(HISTORICAL_APPLIED_AT).key(),
                        REQUIRED_SET,
                        PUBLICATION,
                        pointer(HISTORICAL_APPLIED_AT).requiredSetRevision(),
                        HISTORICAL_APPLIED_AT,
                        List.of(REQUIREMENT),
                        List.of(SOURCE_DOCUMENT))),
                List.of(
                        documentTransitionEvidence(
                                1,
                                DOCUMENT,
                                EstadoVersionLegal.BORRADOR,
                                EstadoVersionLegal.PUBLICADA,
                                null,
                                HISTORICAL_APPLIED_AT),
                        documentTransitionEvidence(
                                2,
                                DOCUMENT,
                                EstadoVersionLegal.PUBLICADA,
                                EstadoVersionLegal.VIGENTE,
                                null,
                                HISTORICAL_APPLIED_AT),
                        documentTransitionEvidence(
                                3,
                                DOCUMENT,
                                EstadoVersionLegal.VIGENTE,
                                EstadoVersionLegal.RETIRADA,
                                "Retiro operativo explícito",
                                APPLIED_AT),
                        documentTransitionEvidence(
                                4,
                                SOURCE_DOCUMENT,
                                EstadoVersionLegal.BORRADOR,
                                EstadoVersionLegal.PUBLICADA,
                                null,
                                HISTORICAL_APPLIED_AT),
                        documentTransitionEvidence(
                                5,
                                SOURCE_DOCUMENT,
                                EstadoVersionLegal.PUBLICADA,
                                EstadoVersionLegal.VIGENTE,
                                null,
                                HISTORICAL_BATCH,
                                HISTORICAL_APPLIED_AT)),
                List.of(
                        requirementTransitionEvidence(
                                1,
                                EstadoVersionLegal.BORRADOR,
                                EstadoVersionLegal.PUBLICADA,
                                HISTORICAL_APPLIED_AT),
                        requirementTransitionEvidence(
                                2,
                                EstadoVersionLegal.PUBLICADA,
                                EstadoVersionLegal.VIGENTE,
                                HISTORICAL_APPLIED_AT)),
                Map.of(HISTORICAL_BATCH, new LegalEditorialPlannerCore.BatchEvidence(
                        HISTORICAL_BATCH,
                        HISTORICAL_APPLIED_AT,
                        HISTORICAL_APPLIED_AT,
                        List.of(HISTORICAL_PREDECESSOR),
                        List.of(new LegalEditorialExecutionPlan.ReplacementSuccessor(
                                SOURCE_DOCUMENT,
                                PUBLICATION)))));
    }

    private static LegalEditorialPlannerCore.PlannerSnapshot copy(
            LegalEditorialPlannerCore.PlannerSnapshot source,
            Map<UUID, LegalEditorialPlannerCore.DocumentEvidence> documents,
            List<LegalEditorialPlannerCore.DocumentTransitionEvidence> documentTransitions,
            List<LegalEditorialPlannerCore.SlotEvidence> slots,
            List<LegalEditorialPlannerCore.PointerEvidence> pointers,
            Map<UUID, LegalEditorialPlannerCore.BatchEvidence> batches) {
        return new LegalEditorialPlannerCore.PlannerSnapshot(
                source.targetDocumentIds(),
                source.targetRequirementIds(),
                source.sourceDocumentIds(),
                source.sourceRequirementIds(),
                documents,
                source.requirements(),
                source.targetScopes(),
                slots,
                pointers,
                documentTransitions,
                source.requirementTransitions(),
                batches);
    }

    private static LegalEditorialPlannerCore.PlannerSnapshot copyRequirements(
            LegalEditorialPlannerCore.PlannerSnapshot source,
            Map<UUID, LegalEditorialPlannerCore.RequirementEvidence> requirements,
            List<LegalEditorialPlannerCore.RequirementTransitionEvidence> transitions) {
        return new LegalEditorialPlannerCore.PlannerSnapshot(
                source.targetDocumentIds(),
                source.targetRequirementIds(),
                source.sourceDocumentIds(),
                source.sourceRequirementIds(),
                source.documents(),
                requirements,
                source.targetScopes(),
                source.activeSlots(),
                source.activePointers(),
                source.documentTransitions(),
                transitions,
                source.batches());
    }

    private static LegalEditorialExecutionPlan.PublicationIdentity publication(
            UUID id,
            String externalId) {
        return new LegalEditorialExecutionPlan.PublicationIdentity(
                externalId,
                id,
                "a".repeat(64));
    }

    private static LegalEditorialExecutionPlan.ExpectedDocumentState documentState(
            UUID id,
            EstadoVersionLegal state,
            String reason) {
        return documentState(id, state, reason, null);
    }

    private static LegalEditorialExecutionPlan.ExpectedDocumentState documentState(
            UUID id,
            EstadoVersionLegal state,
            String reason,
            UUID replacementBatchId) {
        return new LegalEditorialExecutionPlan.ExpectedDocumentState(
                id,
                state,
                APPLIED_AT,
                reason,
                replacementBatchId);
    }

    private static LegalEditorialExecutionPlan.ExpectedRequirementState requirementState() {
        return requirementState(APPLIED_AT);
    }

    private static LegalEditorialExecutionPlan.ExpectedRequirementState requirementState(
            Instant stateChangedAt) {
        return new LegalEditorialExecutionPlan.ExpectedRequirementState(
                REQUIREMENT,
                EstadoVersionLegal.VIGENTE,
                stateChangedAt,
                null);
    }

    private static LegalEditorialExecutionPlan.ExpectedDocumentSlot slot(
            UUID document,
            UUID line) {
        return new LegalEditorialExecutionPlan.ExpectedDocumentSlot(
                new LegalEditorialExecutionPlan.DocumentSlotKey(
                        TipoDocumentoLegal.TERMINOS_SERVICIO,
                        LocaleLegal.ES_AR,
                        ContextoLegal.REGISTRO),
                document,
                line,
                PUBLICATION);
    }

    private static LegalEditorialExecutionPlan.ExpectedRequiredSetPointer pointer() {
        return pointer(APPLIED_AT);
    }

    private static LegalEditorialExecutionPlan.ExpectedRequiredSetPointer pointer(
            Instant updatedAt) {
        return new LegalEditorialExecutionPlan.ExpectedRequiredSetPointer(
                new LegalEditorialExecutionPlan.RequiredSetPointerKey(
                        LocaleLegal.ES_AR,
                        ContextoLegal.REGISTRO,
                        AudienciaLegal.ADMIN_TITULAR),
                REQUIRED_SET,
                PUBLICATION,
                "sha256:" + "b".repeat(64),
                updatedAt);
    }

    private static LegalEditorialExecutionPlan.ExpectedRequiredSetPointer retirementPointer(
            Instant updatedAt) {
        LegalEditorialExecutionPlan.ExpectedRequiredSetPointer pointer = pointer(updatedAt);
        return new LegalEditorialExecutionPlan.ExpectedRequiredSetPointer(
                pointer.key(),
                pointer.requiredSetId(),
                pointer.publicationId(),
                pointer.requiredSetRevision(),
                pointer.updatedAt(),
                Optional.of(new LegalEditorialExecutionPlan.RequiredSetDependencies(
                        List.of(REQUIREMENT),
                        List.of(SOURCE_DOCUMENT))));
    }

    private static List<LegalEditorialExecutionPlan.DocumentTransition> documentPromotionHistory(
            UUID documentId) {
        return documentPromotionHistory(documentId, APPLIED_AT);
    }

    private static List<LegalEditorialExecutionPlan.DocumentTransition> documentPromotionHistory(
            UUID documentId,
            Instant occurredAt) {
        return documentPromotionHistory(documentId, occurredAt, null);
    }

    private static List<LegalEditorialExecutionPlan.DocumentTransition> documentPromotionHistory(
            UUID documentId,
            Instant occurredAt,
            UUID activationBatchId) {
        return List.of(
                new LegalEditorialExecutionPlan.DocumentTransition(
                        documentId,
                        EstadoVersionLegal.BORRADOR,
                        EstadoVersionLegal.PUBLICADA,
                        null,
                        null,
                        occurredAt),
                new LegalEditorialExecutionPlan.DocumentTransition(
                        documentId,
                        EstadoVersionLegal.PUBLICADA,
                        EstadoVersionLegal.VIGENTE,
                        null,
                        activationBatchId,
                        occurredAt));
    }

    private static List<LegalEditorialExecutionPlan.RequirementTransition>
            requirementPromotionHistory() {
        return requirementPromotionHistory(APPLIED_AT);
    }

    private static List<LegalEditorialExecutionPlan.RequirementTransition>
            requirementPromotionHistory(Instant occurredAt) {
        return List.of(
                new LegalEditorialExecutionPlan.RequirementTransition(
                        REQUIREMENT,
                        EstadoVersionLegal.BORRADOR,
                        EstadoVersionLegal.PUBLICADA,
                        null,
                        occurredAt),
                new LegalEditorialExecutionPlan.RequirementTransition(
                        REQUIREMENT,
                        EstadoVersionLegal.PUBLICADA,
                        EstadoVersionLegal.VIGENTE,
                        null,
                        occurredAt));
    }

    private static LegalEditorialPlannerCore.DocumentEvidence documentEvidence(
            UUID id,
            UUID lineId,
            UUID introductionPublication,
            EstadoVersionLegal state,
            String reason) {
        return documentEvidence(id, lineId, introductionPublication, state, reason, null);
    }

    private static LegalEditorialPlannerCore.DocumentEvidence documentEvidence(
            UUID id,
            UUID lineId,
            UUID introductionPublication,
            EstadoVersionLegal state,
            String reason,
            UUID replacementBatchId) {
        return new LegalEditorialPlannerCore.DocumentEvidence(
                id,
                lineId,
                introductionPublication,
                "document-sha",
                APPLIED_AT.minusSeconds(1),
                state,
                APPLIED_AT,
                reason,
                replacementBatchId,
                TipoDocumentoLegal.TERMINOS_SERVICIO,
                LocaleLegal.ES_AR,
                List.of(ContextoLegal.REGISTRO));
    }

    private static LegalEditorialPlannerCore.RequirementEvidence requirementEvidence() {
        return requirementEvidence(APPLIED_AT);
    }

    private static LegalEditorialPlannerCore.RequirementEvidence requirementEvidence(
            Instant stateChangedAt) {
        return new LegalEditorialPlannerCore.RequirementEvidence(
                REQUIREMENT,
                REQUIREMENT_LINE,
                PUBLICATION,
                "requirement-sha",
                EstadoVersionLegal.VIGENTE,
                stateChangedAt,
                null,
                LocaleLegal.ES_AR,
                ContextoLegal.REGISTRO,
                List.of(AudienciaLegal.ADMIN_TITULAR));
    }

    private static LegalEditorialPlannerCore.DocumentTransitionEvidence
            documentTransitionEvidence(
                    long id,
                    UUID documentId,
                    EstadoVersionLegal previous,
                    EstadoVersionLegal next,
                    String reason) {
        return documentTransitionEvidence(id, documentId, previous, next, reason, APPLIED_AT);
    }

    private static LegalEditorialPlannerCore.DocumentTransitionEvidence
            documentTransitionEvidence(
                    long id,
                    UUID documentId,
                    EstadoVersionLegal previous,
                    EstadoVersionLegal next,
                    String reason,
                    Instant occurredAt) {
        return documentTransitionEvidence(
                id,
                documentId,
                previous,
                next,
                reason,
                null,
                occurredAt);
    }

    private static LegalEditorialPlannerCore.DocumentTransitionEvidence
            documentTransitionEvidence(
                    long id,
                    UUID documentId,
                    EstadoVersionLegal previous,
                    EstadoVersionLegal next,
                    String reason,
                    UUID replacementBatchId,
                    Instant occurredAt) {
        return new LegalEditorialPlannerCore.DocumentTransitionEvidence(
                id,
                documentId,
                previous,
                next,
                reason,
                replacementBatchId,
                occurredAt);
    }

    private static LegalEditorialPlannerCore.RequirementTransitionEvidence
            requirementTransitionEvidence(
                    long id,
                    EstadoVersionLegal previous,
                    EstadoVersionLegal next) {
        return requirementTransitionEvidence(id, previous, next, APPLIED_AT);
    }

    private static LegalEditorialPlannerCore.RequirementTransitionEvidence
            requirementTransitionEvidence(
                    long id,
                    EstadoVersionLegal previous,
                    EstadoVersionLegal next,
                    Instant occurredAt) {
        return new LegalEditorialPlannerCore.RequirementTransitionEvidence(
                id,
                REQUIREMENT,
                previous,
                next,
                null,
                null,
                occurredAt);
    }

    private static UUID uuid(long value) {
        return UUID.fromString("00000000-0000-0000-0000-" + String.format("%012d", value));
    }

    private static final class Harness {
        private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        private final LegalEditorialPlannerCore.PlannerStateReader reader =
                mock(LegalEditorialPlannerCore.PlannerStateReader.class);
        private final LegalEditorialPostStateVerifier verifier;

        private Harness(LegalEditorialPlannerCore.PlannerSnapshot snapshot) {
            when(reader.usesJdbc(jdbc)).thenReturn(true);
            when(reader.snapshot(any(), any(), any(), any(), any())).thenReturn(snapshot);
            verifier = new LegalEditorialPostStateVerifier(jdbc, reader);
        }
    }
}
