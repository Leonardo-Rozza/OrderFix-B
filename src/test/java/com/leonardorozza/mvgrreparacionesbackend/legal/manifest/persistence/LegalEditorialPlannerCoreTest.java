package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialPlanValidator.ValidatedEditorialPlan;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialReadiness;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalEditorialPlanV1;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalEditorialPlanV1.DocumentRef;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalEditorialPlanV1.DocumentReplacementBatch;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalEditorialPlanV1.DocumentRetirement;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalEditorialPlanV1.DocumentScopedRef;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalManifestV1;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalManifestV1.ReviewStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalPublicationPlan;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoVersionLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LegalEditorialPlannerCoreTest {

    private static final Instant OBSERVED_AT =
            Instant.parse("2026-08-28T18:00:00.123456Z");
    private static final Instant APPLIED_AT =
            Instant.parse("2026-08-27T18:00:00.123456Z");
    private static final String TARGET_EXTERNAL_ID = "legal-ar-2026-08";
    private static final String TARGET_SHA = "a".repeat(64);
    private static final String SOURCE_EXTERNAL_ID = "legal-ar-2026-07";
    private static final String SOURCE_SHA = "9".repeat(64);
    private static final String FINGERPRINT = "sha256:" + "b".repeat(64);
    private static final UUID TARGET_PUBLICATION_ID = uuid(1);
    private static final UUID DOCUMENT_ID = uuid(2);
    private static final UUID DOCUMENT_LINE_ID = uuid(3);
    private static final UUID REQUIRED_SET_ID = uuid(4);
    private static final UUID OPERATION_ID = uuid(5);
    private static final UUID SOURCE_PUBLICATION_ID = uuid(6);
    private static final UUID ADDED_DOCUMENT_ID = uuid(7);
    private static final UUID ADDED_DOCUMENT_LINE_ID = uuid(8);
    private static final UUID HISTORICAL_BATCH_ID = uuid(9);
    private static final UUID CURRENT_BATCH_ID = uuid(10);
    private static final UUID HISTORICAL_PREDECESSOR_ID = uuid(11);
    private static final UUID EXTRA_BATCH_ID = uuid(12);
    private static final UUID EXTRA_PREDECESSOR_ID = uuid(13);
    private static final Instant HISTORICAL_ACTIVATION_AT =
            APPLIED_AT.minusSeconds(86_400);
    private static final Instant HISTORICAL_BATCH_CREATED_AT =
            HISTORICAL_ACTIVATION_AT.minusSeconds(60);

    @Test
    void coreAndOriginVerifierAreSelectOnlyAndOwnNeitherGateNorClock() throws Exception {
        String plannerSource = Files.readString(Path.of(
                "src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/"
                        + "persistence/LegalEditorialPlannerCore.java"));
        String originSource = Files.readString(Path.of(
                "src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/"
                        + "persistence/LegalManifestOriginGraphVerifier.java"));
        String normalized = (plannerSource + originSource)
                .toLowerCase(java.util.Locale.ROOT);

        assertThat(normalized)
                .doesNotContain("for update")
                .doesNotContain("for share")
                .doesNotContain("transaction_timestamp")
                .doesNotContain("current_timestamp")
                .doesNotContain("new legalmanifestdatabasegate")
                .doesNotContain("insert into ")
                .doesNotContain("update legal_")
                .doesNotContain("delete from ");
        assertThat(normalized).contains("select ");
    }

    @Test
    void constructorRejectsAnySplitJdbcGraph() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        LegalEditorialReadinessCore readiness = mock(LegalEditorialReadinessCore.class);
        LegalManifestOriginGraphVerifier origin = mock(LegalManifestOriginGraphVerifier.class);
        LegalEditorialPlannerCore.PlannerStateReader reader =
                mock(LegalEditorialPlannerCore.PlannerStateReader.class);
        when(readiness.usesJdbc(jdbc)).thenReturn(true);
        when(origin.usesJdbc(jdbc)).thenReturn(true);
        when(reader.usesJdbc(jdbc)).thenReturn(false);

        assertThatThrownBy(() -> new LegalEditorialPlannerCore(
                jdbc,
                readiness,
                origin,
                reader))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("única sesión JDBC");
    }

    @Test
    void freshPromotionDerivesACompleteApplicablePlanAtTheCallerTimestamp() {
        Harness harness = new Harness(ReviewStatus.APPROVED, ReviewStatus.APPROVED);
        LegalEditorialPlannerCore.PlannerSnapshot snapshot = freshSnapshot();
        LegalEditorialReadinessObservation observation = observation(1, 0, 0, 0, 0);
        when(harness.reader.snapshot(
                any(), any(), anySet(), anySet(), anySet())).thenReturn(snapshot);
        when(harness.readiness.evaluate(harness.release, OBSERVED_AT)).thenReturn(
                LegalEditorialReadinessResult.notReady(
                        observation,
                        List.of(LegalManifestIssue.at(
                                LegalManifestIssueCode.CURRENT_STATE_MISMATCH,
                                "database/state"))));

        LegalEditorialPlanResult result = harness.core().planPromote(
                harness.release,
                OBSERVED_AT);

        assertThat(result.status()).isEqualTo(LegalManifestStatus.PASS);
        assertThat(result.changeRequired()).contains(true);
        assertThat(result.observedAt()).contains(OBSERVED_AT);
        assertThat(result.expectedReadinessAfter()).contains(LegalEditorialReadiness.READY);
        assertThat(result.executionPlan()).get()
                .satisfies(plan -> {
                    assertThat(plan.operationType())
                            .isEqualTo(LegalEditorialExecutionPlan.OperationType.PROMOTE);
                    assertThat(plan.expectedAppliedAt()).isEqualTo(OBSERVED_AT);
                    assertThat(plan.mutationCommands().documentTransitions()).hasSize(2);
                    assertThat(plan.mutationCommands().documentSlotInserts()).hasSize(1);
                });
        verify(harness.origin).verify(harness.release, TARGET_PUBLICATION_ID);
    }

    @Test
    void exactPromotionPostStateIsRecognizedBeforeAnySourceClassification() {
        Harness harness = new Harness(ReviewStatus.APPROVED, ReviewStatus.APPROVED);
        LegalEditorialPlannerCore.PlannerSnapshot snapshot = promotedSnapshot();
        LegalEditorialReadinessObservation observation = observation(1, 2, 0, 1, 0);
        when(harness.reader.snapshot(
                any(), any(), anySet(), anySet(), anySet())).thenReturn(snapshot);
        when(harness.readiness.evaluate(harness.release, OBSERVED_AT)).thenReturn(
                LegalEditorialReadinessResult.ready(observation));

        LegalEditorialPlanResult result = harness.core().planPromote(
                harness.release,
                OBSERVED_AT);

        assertThat(result.status()).isEqualTo(LegalManifestStatus.PASS);
        assertThat(result.changeRequired()).contains(false);
        assertThat(result.executionPlan()).get().satisfies(plan -> {
            assertThat(plan.observedAt()).isEqualTo(OBSERVED_AT);
            assertThat(plan.expectedAppliedAt()).isEqualTo(APPLIED_AT);
            assertThat(plan.mutationCommands().isEmpty()).isTrue();
            assertThat(plan.expectedPostState().documentTransitions())
                    .extracting(LegalEditorialExecutionPlan.DocumentTransition::occurredAt)
                    .containsOnly(APPLIED_AT);
        });
        verify(harness.readiness, never()).observeState(any(), any());
    }

    @Test
    void partialPromotionNeverProducesACompletionPlan() {
        Harness harness = new Harness(ReviewStatus.APPROVED, ReviewStatus.APPROVED);
        LegalEditorialPlannerCore.DocumentEvidence document = document(
                EstadoVersionLegal.PUBLICADA,
                APPLIED_AT,
                null);
        LegalEditorialPlannerCore.PlannerSnapshot snapshot = new LegalEditorialPlannerCore
                .PlannerSnapshot(
                List.of(DOCUMENT_ID),
                List.of(),
                List.of(DOCUMENT_ID),
                List.of(),
                Map.of(DOCUMENT_ID, document),
                Map.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(new LegalEditorialPlannerCore.DocumentTransitionEvidence(
                        1,
                        DOCUMENT_ID,
                        EstadoVersionLegal.BORRADOR,
                        EstadoVersionLegal.PUBLICADA,
                        null,
                        null,
                        APPLIED_AT)),
                List.of(),
                Map.of());
        when(harness.reader.snapshot(
                any(), any(), anySet(), anySet(), anySet())).thenReturn(snapshot);
        when(harness.readiness.evaluate(harness.release, OBSERVED_AT)).thenReturn(
                LegalEditorialReadinessResult.notReady(
                        observation(1, 1, 0, 0, 0),
                        List.of(LegalManifestIssue.at(
                                LegalManifestIssueCode.CURRENT_STATE_MISMATCH,
                                "database/state"))));

        LegalEditorialPlanResult result = harness.core().planPromote(
                harness.release,
                OBSERVED_AT);

        assertThat(result.status()).isEqualTo(LegalManifestStatus.BLOCKED);
        assertThat(result.executionPlan()).isEmpty();
        assertThat(result.issues())
                .extracting(LegalManifestIssue::code)
                .containsExactly(LegalManifestIssueCode.INITIAL_PROJECTION_ALREADY_EXISTS);
    }

    @Test
    void promoteRejectsPendingLegalOrAccountingReviewBeforeReadingDatabaseState() {
        Harness harness = new Harness(ReviewStatus.PENDING, ReviewStatus.APPROVED);

        assertThatThrownBy(() -> harness.core().planPromote(
                harness.release,
                OBSERVED_AT))
                .isInstanceOfSatisfying(
                        LegalEditorialBlockedException.class,
                        failure -> assertThat(failure.issue().code())
                                .isEqualTo(LegalManifestIssueCode.REVISION_MISMATCH));
        verify(harness.reader, never()).publication(any());
        verify(harness.origin, never()).verify(any(), any());
    }

    @Test
    void replaceScopeGuardBlocksMultipleAndSplitMergeBatchesBeforeReadingDatabaseState() {
        List<List<DocumentReplacementBatch>> unsupportedMappings = List.of(
                List.of(batchWithCardinality(1, 1), batchWithCardinality(1, 1)),
                List.of(batchWithCardinality(1, 2)),
                List.of(batchWithCardinality(2, 1)));

        for (List<DocumentReplacementBatch> batches : unsupportedMappings) {
            Harness harness = new Harness(ReviewStatus.APPROVED, ReviewStatus.APPROVED);
            ValidatedEditorialPlan token = replacementToken(List.of(), List.of());
            when(token.plan().documentReplacementBatches()).thenReturn(batches);

            LegalEditorialPlanResult result = harness.core().planReplace(
                    harness.release,
                    token,
                    OBSERVED_AT);

            assertThat(result.status()).isEqualTo(LegalManifestStatus.BLOCKED);
            assertThat(result.executionPlan()).isEmpty();
            assertThat(result.issues())
                    .extracting(LegalManifestIssue::code)
                    .containsExactly(LegalManifestIssueCode.REPLACEMENT_MAPPING_INVALID);
            verify(harness.reader, never()).publication(any());
            verify(harness.reader, never()).snapshot(
                    any(), any(), anySet(), anySet(), anySet());
            verify(harness.readiness, never()).evaluate(any(), any());
            verify(harness.readiness, never()).observeState(any(), any());
            verify(harness.origin, never()).verify(any(), any());
        }
    }

    @Test
    void replaceRejectsAPendingTargetReviewBeforeReadingDatabaseState() {
        Harness harness = new Harness(ReviewStatus.APPROVED, ReviewStatus.PENDING);
        ValidatedEditorialPlan token = mock(ValidatedEditorialPlan.class);
        LegalEditorialPlanV1 plan = mock(LegalEditorialPlanV1.class);
        when(token.plan()).thenReturn(plan);
        when(token.operationType()).thenReturn(
                LegalEditorialPlanV1.OperationType.REPLACE);
        when(plan.operationType()).thenReturn(LegalEditorialPlanV1.OperationType.REPLACE);
        when(plan.documentReplacementBatches()).thenReturn(List.of());
        when(plan.targetPublicationId()).thenReturn(TARGET_EXTERNAL_ID);
        when(plan.targetManifestSha256()).thenReturn(TARGET_SHA);
        when(plan.expectedReadinessAfter()).thenReturn(LegalEditorialReadiness.READY);
        when(plan.acknowledgeFailClosedGap()).thenReturn(false);

        assertThatThrownBy(() -> harness.core().planReplace(
                harness.release,
                token,
                OBSERVED_AT))
                .isInstanceOfSatisfying(
                        LegalEditorialBlockedException.class,
                        failure -> assertThat(failure.issue().code())
                                .isEqualTo(LegalManifestIssueCode.REVISION_MISMATCH));
        verify(harness.reader, never()).publication(any());
        verify(harness.origin, never()).verify(any(), any());
    }

    @Test
    void documentOnlyRetirementExplicitlyDeletesEveryPointerReferencingTheDocument() {
        Harness harness = new Harness(ReviewStatus.APPROVED, ReviewStatus.APPROVED);
        ValidatedEditorialPlan token = mock(ValidatedEditorialPlan.class);
        LegalEditorialPlanV1 plan = mock(LegalEditorialPlanV1.class);
        DocumentRetirement retirement = new DocumentRetirement(
                DOCUMENT_ID,
                "c".repeat(64),
                List.of(ContextoLegal.REGISTRO),
                "Retiro operativo explícito");
        when(token.plan()).thenReturn(plan);
        when(token.operationId()).thenReturn(OPERATION_ID);
        when(token.editorialPlanSha256()).thenReturn("d".repeat(64));
        when(plan.operationType()).thenReturn(LegalEditorialPlanV1.OperationType.RETIRE);
        when(plan.targetPublicationId()).thenReturn(TARGET_EXTERNAL_ID);
        when(plan.targetManifestSha256()).thenReturn(TARGET_SHA);
        when(plan.expectedCurrentPublicationId()).thenReturn(TARGET_EXTERNAL_ID);
        when(plan.expectedCurrentManifestSha256()).thenReturn(TARGET_SHA);
        when(plan.expectedEditorialStateFingerprint()).thenReturn(FINGERPRINT);
        when(plan.expectedReadinessAfter()).thenReturn(LegalEditorialReadiness.NOT_READY);
        when(plan.acknowledgeFailClosedGap()).thenReturn(true);
        when(plan.documentAdditions()).thenReturn(List.of());
        when(plan.documentReuses()).thenReturn(List.of());
        when(plan.documentReplacementBatches()).thenReturn(List.of());
        when(plan.documentRetirements()).thenReturn(List.of(retirement));
        when(plan.requirementAdditions()).thenReturn(List.of());
        when(plan.requirementReuses()).thenReturn(List.of());
        when(plan.requirementReplacements()).thenReturn(List.of());
        when(plan.requirementRetirements()).thenReturn(List.of());
        LegalEditorialPlannerCore.PlannerSnapshot snapshot = retirementSourceSnapshot();
        when(harness.reader.snapshot(
                any(), any(), anySet(), anySet(), anySet())).thenReturn(snapshot);
        when(harness.readiness.observeState(TARGET_EXTERNAL_ID, OBSERVED_AT)).thenReturn(
                observation(1, 2, 0, 1, 0));

        LegalEditorialPlanResult result = harness.core().planRetire(
                harness.release,
                token,
                OBSERVED_AT);

        assertThat(result.status()).isEqualTo(LegalManifestStatus.PASS);
        assertThat(result.changeRequired()).contains(true);
        assertThat(result.executionPlan()).get().satisfies(execution -> {
            assertThat(execution.observedAt()).isEqualTo(OBSERVED_AT);
            assertThat(execution.expectedAppliedAt()).isEqualTo(OBSERVED_AT);
            assertThat(execution.mutationCommands().documentSlotDeletes()).hasSize(1);
            assertThat(execution.mutationCommands().requiredSetPointerDeletes())
                    .singleElement()
                    .satisfies(pointer -> assertThat(pointer.expectedRequiredSetId())
                            .isEqualTo(REQUIRED_SET_ID));
            assertThat(execution.expectedPostState().documentTransitions())
                    .singleElement()
                    .satisfies(transition -> {
                        assertThat(transition.previousState())
                                .isEqualTo(EstadoVersionLegal.VIGENTE);
                        assertThat(transition.newState())
                                .isEqualTo(EstadoVersionLegal.RETIRADA);
                        assertThat(transition.occurredAt()).isEqualTo(OBSERVED_AT);
                    });
            assertThat(execution.expectedPostState().preexistingDocumentTransitions())
                    .hasSize(2)
                    .extracting(LegalEditorialExecutionPlan.DocumentTransition::occurredAt)
                    .containsOnly(APPLIED_AT);
            assertThat(execution.expectedPostState().preexistingRequirementTransitions())
                    .isEmpty();
        });
    }

    @Test
    void replacementReplayWithoutBatchPreservesReuseHistoryAndHistoricalApplyTime() {
        Harness harness = new Harness(ReviewStatus.APPROVED, ReviewStatus.APPROVED);
        DocumentScopedRef reuse = new DocumentScopedRef(
                DOCUMENT_ID,
                "c".repeat(64),
                List.of(ContextoLegal.REGISTRO));
        DocumentScopedRef addition = new DocumentScopedRef(
                ADDED_DOCUMENT_ID,
                "d".repeat(64),
                List.of(ContextoLegal.CIERRE_CUENTA));
        ValidatedEditorialPlan token = replacementToken(List.of(addition), List.of(reuse));
        Instant reuseActivation = APPLIED_AT.minusSeconds(86_400);
        LegalEditorialPlannerCore.DocumentEvidence reusedDocument = evidence(
                DOCUMENT_ID,
                DOCUMENT_LINE_ID,
                SOURCE_PUBLICATION_ID,
                "c".repeat(64),
                EstadoVersionLegal.VIGENTE,
                reuseActivation,
                List.of(ContextoLegal.REGISTRO));
        LegalEditorialPlannerCore.DocumentEvidence addedDocument = evidence(
                ADDED_DOCUMENT_ID,
                ADDED_DOCUMENT_LINE_ID,
                TARGET_PUBLICATION_ID,
                "d".repeat(64),
                EstadoVersionLegal.VIGENTE,
                APPLIED_AT,
                List.of(ContextoLegal.CIERRE_CUENTA));
        LegalEditorialPlannerCore.PlannerSnapshot snapshot = new LegalEditorialPlannerCore
                .PlannerSnapshot(
                List.of(DOCUMENT_ID, ADDED_DOCUMENT_ID),
                List.of(),
                List.of(DOCUMENT_ID),
                List.of(),
                Map.of(
                        DOCUMENT_ID, reusedDocument,
                        ADDED_DOCUMENT_ID, addedDocument),
                Map.of(),
                List.of(),
                List.of(
                        slot(reusedDocument, ContextoLegal.REGISTRO, TARGET_PUBLICATION_ID),
                        slot(addedDocument, ContextoLegal.CIERRE_CUENTA, TARGET_PUBLICATION_ID)),
                List.of(),
                List.of(
                        transition(1, DOCUMENT_ID, EstadoVersionLegal.BORRADOR,
                                EstadoVersionLegal.PUBLICADA, reuseActivation),
                        transition(2, DOCUMENT_ID, EstadoVersionLegal.PUBLICADA,
                                EstadoVersionLegal.VIGENTE, reuseActivation),
                        transition(3, ADDED_DOCUMENT_ID, EstadoVersionLegal.BORRADOR,
                                EstadoVersionLegal.PUBLICADA, APPLIED_AT),
                        transition(4, ADDED_DOCUMENT_ID, EstadoVersionLegal.PUBLICADA,
                                EstadoVersionLegal.VIGENTE, APPLIED_AT)),
                List.of(),
                Map.of());
        stubReplacementPublications(harness);
        when(harness.reader.snapshot(
                any(), any(), anySet(), anySet(), anySet())).thenReturn(snapshot);
        when(harness.readiness.evaluate(harness.release, OBSERVED_AT)).thenReturn(
                LegalEditorialReadinessResult.ready(observation(2, 4, 0, 2, 0)));

        LegalEditorialPlanResult result = harness.core().planReplace(
                harness.release,
                token,
                OBSERVED_AT);

        assertThat(result.status()).isEqualTo(LegalManifestStatus.PASS);
        assertThat(result.changeRequired()).contains(false);
        assertThat(result.executionPlan()).get().satisfies(execution -> {
            assertThat(execution.observedAt()).isEqualTo(OBSERVED_AT);
            assertThat(execution.expectedAppliedAt()).isEqualTo(APPLIED_AT);
            assertThat(execution.mutationCommands().isEmpty()).isTrue();
            assertThat(execution.expectedPostState().documentTransitions())
                    .hasSize(2)
                    .extracting(LegalEditorialExecutionPlan.DocumentTransition::documentVersionId)
                    .containsOnly(ADDED_DOCUMENT_ID);
            assertThat(execution.expectedPostState().preexistingDocumentTransitions())
                    .hasSize(2)
                    .allSatisfy(transition -> {
                        assertThat(transition.documentVersionId()).isEqualTo(DOCUMENT_ID);
                        assertThat(transition.occurredAt()).isEqualTo(reuseActivation);
                    });
            assertThat(execution.expectedPostState().replacementBatches()).isEmpty();
        });
        verify(harness.readiness, never()).observeState(any(), any());
    }

    @Test
    void freshReplacementSeparatesHistoricalAndCurrentBatchesInAChain() {
        Harness harness = new Harness(ReviewStatus.APPROVED, ReviewStatus.APPROVED);
        ValidatedEditorialPlan token = replacementToken(
                List.of(),
                List.of(),
                List.of(currentReplacementBatch()));
        stubReplacementPublications(harness);
        when(harness.reader.snapshot(
                any(), any(), anySet(), anySet(), anySet())).thenReturn(
                historicalChainSourceSnapshot(Map.of(
                        HISTORICAL_BATCH_ID,
                        historicalBatch(HISTORICAL_ACTIVATION_AT))));
        when(harness.readiness.evaluate(harness.release, OBSERVED_AT)).thenReturn(
                LegalEditorialReadinessResult.notReady(
                        observation(2, 2, 0, 1, 1),
                        List.of(LegalManifestIssue.at(
                                LegalManifestIssueCode.CURRENT_STATE_MISMATCH,
                                "database/state"))));
        when(harness.readiness.observeState(SOURCE_EXTERNAL_ID, OBSERVED_AT)).thenReturn(
                sourceObservation(FINGERPRINT));

        LegalEditorialPlanResult result = harness.core().planReplace(
                harness.release,
                token,
                OBSERVED_AT);

        assertThat(result.status()).isEqualTo(LegalManifestStatus.PASS);
        assertThat(result.changeRequired()).contains(true);
        assertThat(result.executionPlan()).get().satisfies(execution -> {
            assertThat(execution.expectedAppliedAt()).isEqualTo(OBSERVED_AT);
            assertThat(execution.expectedPostState().preexistingReplacementBatches())
                    .singleElement()
                    .satisfies(batch -> {
                        assertThat(batch.batchId()).isEqualTo(HISTORICAL_BATCH_ID);
                        assertThat(batch.createdAt()).isEqualTo(HISTORICAL_BATCH_CREATED_AT);
                        assertThat(batch.sealedAt()).isEqualTo(HISTORICAL_ACTIVATION_AT);
                    });
            assertThat(execution.expectedPostState().replacementBatches())
                    .singleElement()
                    .satisfies(batch -> {
                        assertThat(batch.batchId()).isEqualTo(CURRENT_BATCH_ID);
                        assertThat(batch.createdAt()).isEqualTo(OBSERVED_AT);
                        assertThat(batch.sealedAt()).isEqualTo(OBSERVED_AT);
                    });
            assertThat(execution.mutationCommands().replacementBatchesToCreateAndSeal())
                    .extracting(LegalEditorialExecutionPlan.ReplacementBatch::batchId)
                    .containsExactly(CURRENT_BATCH_ID);
            assertThat(execution.expectedPostState().preexistingDocumentTransitions())
                    .filteredOn(transition -> HISTORICAL_BATCH_ID.equals(
                            transition.replacementBatchId()))
                    .singleElement()
                    .satisfies(transition -> {
                        assertThat(transition.documentVersionId()).isEqualTo(DOCUMENT_ID);
                        assertThat(transition.occurredAt())
                                .isEqualTo(HISTORICAL_ACTIVATION_AT);
                    });
        });
    }

    @Test
    void replacementReplayPreservesHistoricalBatchAndKeepsCurrentBatchAsCutoverDelta() {
        Harness harness = new Harness(ReviewStatus.APPROVED, ReviewStatus.APPROVED);
        ValidatedEditorialPlan token = replacementToken(
                List.of(),
                List.of(),
                List.of(currentReplacementBatch()));
        stubReplacementPublications(harness);
        when(harness.reader.snapshot(
                any(), any(), anySet(), anySet(), anySet())).thenReturn(
                historicalChainPostSnapshot());
        when(harness.readiness.evaluate(harness.release, OBSERVED_AT)).thenReturn(
                LegalEditorialReadinessResult.ready(observation(2, 5, 0, 1, 2)));

        LegalEditorialPlanResult result = harness.core().planReplace(
                harness.release,
                token,
                OBSERVED_AT);

        assertThat(result.status()).isEqualTo(LegalManifestStatus.PASS);
        assertThat(result.changeRequired()).contains(false);
        assertThat(result.executionPlan()).get().satisfies(execution -> {
            assertThat(execution.expectedAppliedAt()).isEqualTo(APPLIED_AT);
            assertThat(execution.mutationCommands().isEmpty()).isTrue();
            assertThat(execution.expectedPostState().preexistingReplacementBatches())
                    .singleElement()
                    .satisfies(batch -> {
                        assertThat(batch.batchId()).isEqualTo(HISTORICAL_BATCH_ID);
                        assertThat(batch.createdAt()).isEqualTo(HISTORICAL_BATCH_CREATED_AT);
                        assertThat(batch.sealedAt()).isEqualTo(HISTORICAL_ACTIVATION_AT);
                    });
            assertThat(execution.expectedPostState().replacementBatches())
                    .singleElement()
                    .satisfies(batch -> {
                        assertThat(batch.batchId()).isEqualTo(CURRENT_BATCH_ID);
                        assertThat(batch.createdAt()).isEqualTo(APPLIED_AT);
                        assertThat(batch.sealedAt()).isEqualTo(APPLIED_AT);
                    });
            assertThat(execution.expectedPostState().preexistingDocumentTransitions())
                    .extracting(LegalEditorialExecutionPlan.DocumentTransition::occurredAt)
                    .containsOnly(HISTORICAL_ACTIVATION_AT);
        });
        verify(harness.readiness, never()).observeState(any(), any());
    }

    @Test
    void replacementRejectsAnUnexpectedRelatedBatchFromTheSnapshot() {
        Harness harness = new Harness(ReviewStatus.APPROVED, ReviewStatus.APPROVED);
        ValidatedEditorialPlan token = replacementToken(
                List.of(),
                List.of(),
                List.of(currentReplacementBatch()));
        stubReplacementPublications(harness);
        when(harness.reader.snapshot(
                any(), any(), anySet(), anySet(), anySet())).thenReturn(
                historicalChainSourceSnapshot(Map.of(
                        HISTORICAL_BATCH_ID,
                        historicalBatch(HISTORICAL_ACTIVATION_AT),
                        EXTRA_BATCH_ID,
                        new LegalEditorialPlannerCore.BatchEvidence(
                                EXTRA_BATCH_ID,
                                HISTORICAL_BATCH_CREATED_AT,
                                HISTORICAL_ACTIVATION_AT,
                                List.of(EXTRA_PREDECESSOR_ID),
                                List.of(new LegalEditorialExecutionPlan.ReplacementSuccessor(
                                        DOCUMENT_ID,
                                        SOURCE_PUBLICATION_ID))))));
        when(harness.readiness.evaluate(harness.release, OBSERVED_AT)).thenReturn(
                LegalEditorialReadinessResult.notReady(
                        observation(2, 2, 0, 1, 2),
                        List.of(LegalManifestIssue.at(
                                LegalManifestIssueCode.CURRENT_STATE_MISMATCH,
                                "database/state"))));
        when(harness.readiness.observeState(SOURCE_EXTERNAL_ID, OBSERVED_AT)).thenReturn(
                sourceObservation(FINGERPRINT));

        LegalEditorialPlanResult result = harness.core().planReplace(
                harness.release,
                token,
                OBSERVED_AT);

        assertThat(result.status()).isEqualTo(LegalManifestStatus.BLOCKED);
        assertThat(result.executionPlan()).isEmpty();
        assertThat(result.issues())
                .extracting(LegalManifestIssue::code)
                .containsExactly(LegalManifestIssueCode.CURRENT_STATE_MISMATCH);
    }

    @Test
    void replacementRejectsAHistoricalBatchWhoseSealDoesNotMatchItsActivation() {
        Harness harness = new Harness(ReviewStatus.APPROVED, ReviewStatus.APPROVED);
        ValidatedEditorialPlan token = replacementToken(
                List.of(),
                List.of(),
                List.of(currentReplacementBatch()));
        stubReplacementPublications(harness);
        when(harness.reader.snapshot(
                any(), any(), anySet(), anySet(), anySet())).thenReturn(
                historicalChainSourceSnapshot(Map.of(
                        HISTORICAL_BATCH_ID,
                        historicalBatch(HISTORICAL_ACTIVATION_AT.plusSeconds(1)))));
        when(harness.readiness.evaluate(harness.release, OBSERVED_AT)).thenReturn(
                LegalEditorialReadinessResult.notReady(
                        observation(2, 2, 0, 1, 1),
                        List.of(LegalManifestIssue.at(
                                LegalManifestIssueCode.CURRENT_STATE_MISMATCH,
                                "database/state"))));
        when(harness.readiness.observeState(SOURCE_EXTERNAL_ID, OBSERVED_AT)).thenReturn(
                sourceObservation(FINGERPRINT));

        LegalEditorialPlanResult result = harness.core().planReplace(
                harness.release,
                token,
                OBSERVED_AT);

        assertThat(result.status()).isEqualTo(LegalManifestStatus.BLOCKED);
        assertThat(result.executionPlan()).isEmpty();
        assertThat(result.issues())
                .extracting(LegalManifestIssue::code)
                .containsExactly(LegalManifestIssueCode.CURRENT_STATE_MISMATCH);
    }

    @Test
    void replacementReplayRejectsAnAdditionPublishedBeforeTheAtomicActivation() {
        Harness harness = new Harness(ReviewStatus.APPROVED, ReviewStatus.APPROVED);
        DocumentScopedRef reuse = new DocumentScopedRef(
                DOCUMENT_ID,
                "c".repeat(64),
                List.of(ContextoLegal.REGISTRO));
        DocumentScopedRef addition = new DocumentScopedRef(
                ADDED_DOCUMENT_ID,
                "d".repeat(64),
                List.of(ContextoLegal.CIERRE_CUENTA));
        ValidatedEditorialPlan token = replacementToken(List.of(addition), List.of(reuse));
        Instant historicalActivation = APPLIED_AT.minusSeconds(86_400);
        LegalEditorialPlannerCore.DocumentEvidence reusedDocument = evidence(
                DOCUMENT_ID,
                DOCUMENT_LINE_ID,
                SOURCE_PUBLICATION_ID,
                "c".repeat(64),
                EstadoVersionLegal.VIGENTE,
                historicalActivation,
                List.of(ContextoLegal.REGISTRO));
        LegalEditorialPlannerCore.DocumentEvidence addedDocument = evidence(
                ADDED_DOCUMENT_ID,
                ADDED_DOCUMENT_LINE_ID,
                TARGET_PUBLICATION_ID,
                "d".repeat(64),
                EstadoVersionLegal.VIGENTE,
                APPLIED_AT,
                List.of(ContextoLegal.CIERRE_CUENTA));
        LegalEditorialPlannerCore.PlannerSnapshot snapshot = new LegalEditorialPlannerCore
                .PlannerSnapshot(
                List.of(DOCUMENT_ID, ADDED_DOCUMENT_ID),
                List.of(),
                List.of(DOCUMENT_ID),
                List.of(),
                Map.of(
                        DOCUMENT_ID, reusedDocument,
                        ADDED_DOCUMENT_ID, addedDocument),
                Map.of(),
                List.of(),
                List.of(
                        slot(reusedDocument, ContextoLegal.REGISTRO, TARGET_PUBLICATION_ID),
                        slot(addedDocument, ContextoLegal.CIERRE_CUENTA, TARGET_PUBLICATION_ID)),
                List.of(),
                List.of(
                        transition(1, DOCUMENT_ID, EstadoVersionLegal.BORRADOR,
                                EstadoVersionLegal.PUBLICADA, historicalActivation),
                        transition(2, DOCUMENT_ID, EstadoVersionLegal.PUBLICADA,
                                EstadoVersionLegal.VIGENTE, historicalActivation),
                        transition(3, ADDED_DOCUMENT_ID, EstadoVersionLegal.BORRADOR,
                                EstadoVersionLegal.PUBLICADA, APPLIED_AT.minusSeconds(1)),
                        transition(4, ADDED_DOCUMENT_ID, EstadoVersionLegal.PUBLICADA,
                                EstadoVersionLegal.VIGENTE, APPLIED_AT)),
                List.of(),
                Map.of());
        stubReplacementPublications(harness);
        when(harness.reader.snapshot(
                any(), any(), anySet(), anySet(), anySet())).thenReturn(snapshot);
        when(harness.readiness.evaluate(harness.release, OBSERVED_AT)).thenReturn(
                LegalEditorialReadinessResult.ready(observation(2, 4, 0, 2, 0)));
        when(harness.readiness.observeState(SOURCE_EXTERNAL_ID, OBSERVED_AT)).thenReturn(
                sourceObservation("sha256:" + "f".repeat(64)));

        LegalEditorialPlanResult result = harness.core().planReplace(
                harness.release,
                token,
                OBSERVED_AT);

        assertThat(result.status()).isEqualTo(LegalManifestStatus.BLOCKED);
        assertThat(result.executionPlan()).isEmpty();
        assertThat(result.issues())
                .extracting(LegalManifestIssue::code)
                .containsExactly(LegalManifestIssueCode.SOURCE_FINGERPRINT_MISMATCH);
        verify(harness.readiness).observeState(SOURCE_EXTERNAL_ID, OBSERVED_AT);
    }

    @Test
    void replacementCannotTreatAnUnpromotedSealedDraftAsAnEmptySource() {
        Harness harness = new Harness(ReviewStatus.APPROVED, ReviewStatus.APPROVED);
        DocumentScopedRef addition = new DocumentScopedRef(
                ADDED_DOCUMENT_ID,
                "d".repeat(64),
                List.of(ContextoLegal.CIERRE_CUENTA));
        ValidatedEditorialPlan token = replacementToken(List.of(addition), List.of());
        LegalEditorialPlannerCore.DocumentEvidence sourceDraft = evidence(
                DOCUMENT_ID,
                DOCUMENT_LINE_ID,
                SOURCE_PUBLICATION_ID,
                "c".repeat(64),
                EstadoVersionLegal.BORRADOR,
                null,
                List.of(ContextoLegal.REGISTRO));
        LegalEditorialPlannerCore.DocumentEvidence targetDraft = evidence(
                ADDED_DOCUMENT_ID,
                ADDED_DOCUMENT_LINE_ID,
                TARGET_PUBLICATION_ID,
                "d".repeat(64),
                EstadoVersionLegal.BORRADOR,
                null,
                List.of(ContextoLegal.CIERRE_CUENTA));
        LegalEditorialPlannerCore.PlannerSnapshot snapshot = new LegalEditorialPlannerCore
                .PlannerSnapshot(
                List.of(ADDED_DOCUMENT_ID),
                List.of(),
                List.of(DOCUMENT_ID),
                List.of(),
                Map.of(
                        DOCUMENT_ID, sourceDraft,
                        ADDED_DOCUMENT_ID, targetDraft),
                Map.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Map.of());
        stubReplacementPublications(harness);
        when(harness.reader.snapshot(
                any(), any(), anySet(), anySet(), anySet())).thenReturn(snapshot);
        when(harness.readiness.evaluate(harness.release, OBSERVED_AT)).thenReturn(
                LegalEditorialReadinessResult.notReady(
                        observation(2, 0, 0, 0, 0),
                        List.of(LegalManifestIssue.at(
                                LegalManifestIssueCode.CURRENT_STATE_MISMATCH,
                                "database/state"))));
        when(harness.readiness.observeState(SOURCE_EXTERNAL_ID, OBSERVED_AT)).thenReturn(
                sourceObservation(FINGERPRINT));

        LegalEditorialPlanResult result = harness.core().planReplace(
                harness.release,
                token,
                OBSERVED_AT);

        assertThat(result.status()).isEqualTo(LegalManifestStatus.BLOCKED);
        assertThat(result.executionPlan()).isEmpty();
        assertThat(result.issues())
                .extracting(LegalManifestIssue::code)
                .containsExactly(LegalManifestIssueCode.CURRENT_STATE_MISMATCH);
    }

    @Test
    void freezesFiniteBudgetsIncludingPointerDocumentReferences() {
        assertThat(LegalEditorialPlannerCore.MAX_ACTIVE_SLOTS).isEqualTo(1_024);
        assertThat(LegalEditorialPlannerCore.MAX_ACTIVE_POINTERS).isEqualTo(64);
        assertThat(LegalEditorialPlannerCore.MAX_ACTIVE_POINTER_MEMBERS).isEqualTo(16_384);
        assertThat(LegalEditorialPlannerCore.MAX_ACTIVE_POINTER_DOCUMENT_REFERENCES)
                .isEqualTo(32_768);
        assertThat(LegalEditorialPlannerCore.MAX_RELEVANT_TRANSITIONS).isEqualTo(8_192);
    }

    private static LegalEditorialPlannerCore.PlannerSnapshot freshSnapshot() {
        return new LegalEditorialPlannerCore.PlannerSnapshot(
                List.of(DOCUMENT_ID),
                List.of(),
                List.of(DOCUMENT_ID),
                List.of(),
                Map.of(DOCUMENT_ID, document(EstadoVersionLegal.BORRADOR, null, null)),
                Map.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Map.of());
    }

    private static LegalEditorialPlannerCore.PlannerSnapshot promotedSnapshot() {
        LegalEditorialPlannerCore.DocumentEvidence document = document(
                EstadoVersionLegal.VIGENTE,
                APPLIED_AT,
                null);
        LegalEditorialExecutionPlan.DocumentSlotKey slotKey =
                new LegalEditorialExecutionPlan.DocumentSlotKey(
                        TipoDocumentoLegal.TERMINOS_SERVICIO,
                        LocaleLegal.ES_AR,
                        ContextoLegal.REGISTRO);
        return new LegalEditorialPlannerCore.PlannerSnapshot(
                List.of(DOCUMENT_ID),
                List.of(),
                List.of(DOCUMENT_ID),
                List.of(),
                Map.of(DOCUMENT_ID, document),
                Map.of(),
                List.of(),
                List.of(new LegalEditorialPlannerCore.SlotEvidence(
                        slotKey,
                        DOCUMENT_ID,
                        DOCUMENT_LINE_ID,
                        TARGET_PUBLICATION_ID)),
                List.of(),
                List.of(
                        new LegalEditorialPlannerCore.DocumentTransitionEvidence(
                                1,
                                DOCUMENT_ID,
                                EstadoVersionLegal.BORRADOR,
                                EstadoVersionLegal.PUBLICADA,
                                null,
                                null,
                                APPLIED_AT),
                        new LegalEditorialPlannerCore.DocumentTransitionEvidence(
                                2,
                                DOCUMENT_ID,
                                EstadoVersionLegal.PUBLICADA,
                                EstadoVersionLegal.VIGENTE,
                                null,
                                null,
                                APPLIED_AT)),
                List.of(),
                Map.of());
    }

    private static LegalEditorialPlannerCore.PlannerSnapshot retirementSourceSnapshot() {
        LegalEditorialPlannerCore.DocumentEvidence document = document(
                EstadoVersionLegal.VIGENTE,
                APPLIED_AT,
                null);
        LegalEditorialExecutionPlan.DocumentSlotKey slotKey =
                new LegalEditorialExecutionPlan.DocumentSlotKey(
                        TipoDocumentoLegal.TERMINOS_SERVICIO,
                        LocaleLegal.ES_AR,
                        ContextoLegal.REGISTRO);
        LegalEditorialExecutionPlan.RequiredSetPointerKey pointerKey =
                new LegalEditorialExecutionPlan.RequiredSetPointerKey(
                        LocaleLegal.ES_AR,
                        ContextoLegal.REGISTRO,
                        AudienciaLegal.USER);
        return new LegalEditorialPlannerCore.PlannerSnapshot(
                List.of(DOCUMENT_ID),
                List.of(),
                List.of(DOCUMENT_ID),
                List.of(),
                Map.of(DOCUMENT_ID, document),
                Map.of(),
                List.of(),
                List.of(new LegalEditorialPlannerCore.SlotEvidence(
                        slotKey,
                        DOCUMENT_ID,
                        DOCUMENT_LINE_ID,
                        TARGET_PUBLICATION_ID)),
                List.of(new LegalEditorialPlannerCore.PointerEvidence(
                        pointerKey,
                        REQUIRED_SET_ID,
                        TARGET_PUBLICATION_ID,
                        "sha256:" + "e".repeat(64),
                        APPLIED_AT,
                        List.of(),
                        List.of(DOCUMENT_ID))),
                List.of(
                        new LegalEditorialPlannerCore.DocumentTransitionEvidence(
                                1,
                                DOCUMENT_ID,
                                EstadoVersionLegal.BORRADOR,
                                EstadoVersionLegal.PUBLICADA,
                                null,
                                null,
                                APPLIED_AT),
                        new LegalEditorialPlannerCore.DocumentTransitionEvidence(
                                2,
                                DOCUMENT_ID,
                                EstadoVersionLegal.PUBLICADA,
                                EstadoVersionLegal.VIGENTE,
                                null,
                                null,
                                APPLIED_AT)),
                List.of(),
                Map.of());
    }

    private static ValidatedEditorialPlan replacementToken(
            List<DocumentScopedRef> additions,
            List<DocumentScopedRef> reuses) {
        return replacementToken(additions, reuses, List.of());
    }

    private static ValidatedEditorialPlan replacementToken(
            List<DocumentScopedRef> additions,
            List<DocumentScopedRef> reuses,
            List<DocumentReplacementBatch> batches) {
        ValidatedEditorialPlan token = mock(ValidatedEditorialPlan.class);
        LegalEditorialPlanV1 plan = mock(LegalEditorialPlanV1.class);
        when(token.plan()).thenReturn(plan);
        when(token.operationType()).thenReturn(
                LegalEditorialPlanV1.OperationType.REPLACE);
        when(token.operationId()).thenReturn(OPERATION_ID);
        when(token.editorialPlanSha256()).thenReturn("e".repeat(64));
        when(plan.operationType()).thenReturn(LegalEditorialPlanV1.OperationType.REPLACE);
        when(plan.expectedCurrentPublicationId()).thenReturn(SOURCE_EXTERNAL_ID);
        when(plan.expectedCurrentManifestSha256()).thenReturn(SOURCE_SHA);
        when(plan.expectedEditorialStateFingerprint()).thenReturn(FINGERPRINT);
        when(plan.targetPublicationId()).thenReturn(TARGET_EXTERNAL_ID);
        when(plan.targetManifestSha256()).thenReturn(TARGET_SHA);
        when(plan.expectedReadinessAfter()).thenReturn(LegalEditorialReadiness.READY);
        when(plan.acknowledgeFailClosedGap()).thenReturn(false);
        when(plan.documentAdditions()).thenReturn(additions);
        when(plan.documentReuses()).thenReturn(reuses);
        when(plan.documentReplacementBatches()).thenReturn(batches);
        when(plan.documentRetirements()).thenReturn(List.of());
        when(plan.requirementAdditions()).thenReturn(List.of());
        when(plan.requirementReuses()).thenReturn(List.of());
        when(plan.requirementReplacements()).thenReturn(List.of());
        when(plan.requirementRetirements()).thenReturn(List.of());
        return token;
    }

    private static DocumentReplacementBatch currentReplacementBatch() {
        return new DocumentReplacementBatch(
                CURRENT_BATCH_ID,
                List.of(ContextoLegal.REGISTRO),
                List.of(new DocumentRef(DOCUMENT_ID, "c".repeat(64))),
                List.of(new DocumentRef(ADDED_DOCUMENT_ID, "d".repeat(64))));
    }

    private static LegalEditorialPlannerCore.PlannerSnapshot historicalChainSourceSnapshot(
            Map<UUID, LegalEditorialPlannerCore.BatchEvidence> batches) {
        LegalEditorialPlannerCore.DocumentEvidence predecessor = evidence(
                DOCUMENT_ID,
                DOCUMENT_LINE_ID,
                SOURCE_PUBLICATION_ID,
                "c".repeat(64),
                EstadoVersionLegal.VIGENTE,
                HISTORICAL_ACTIVATION_AT,
                HISTORICAL_BATCH_ID,
                List.of(ContextoLegal.REGISTRO));
        LegalEditorialPlannerCore.DocumentEvidence successor = evidence(
                ADDED_DOCUMENT_ID,
                ADDED_DOCUMENT_LINE_ID,
                TARGET_PUBLICATION_ID,
                "d".repeat(64),
                EstadoVersionLegal.BORRADOR,
                null,
                null,
                List.of(ContextoLegal.REGISTRO));
        return new LegalEditorialPlannerCore.PlannerSnapshot(
                List.of(ADDED_DOCUMENT_ID),
                List.of(),
                List.of(DOCUMENT_ID),
                List.of(),
                Map.of(
                        DOCUMENT_ID, predecessor,
                        ADDED_DOCUMENT_ID, successor),
                Map.of(),
                List.of(),
                List.of(slot(predecessor, ContextoLegal.REGISTRO, SOURCE_PUBLICATION_ID)),
                List.of(),
                List.of(
                        transition(
                                1,
                                DOCUMENT_ID,
                                EstadoVersionLegal.BORRADOR,
                                EstadoVersionLegal.PUBLICADA,
                                null,
                                HISTORICAL_ACTIVATION_AT),
                        transition(
                                2,
                                DOCUMENT_ID,
                                EstadoVersionLegal.PUBLICADA,
                                EstadoVersionLegal.VIGENTE,
                                HISTORICAL_BATCH_ID,
                                HISTORICAL_ACTIVATION_AT)),
                List.of(),
                batches);
    }

    private static LegalEditorialPlannerCore.PlannerSnapshot historicalChainPostSnapshot() {
        LegalEditorialPlannerCore.DocumentEvidence predecessor = evidence(
                DOCUMENT_ID,
                DOCUMENT_LINE_ID,
                SOURCE_PUBLICATION_ID,
                "c".repeat(64),
                EstadoVersionLegal.REEMPLAZADA,
                APPLIED_AT,
                CURRENT_BATCH_ID,
                List.of(ContextoLegal.REGISTRO));
        LegalEditorialPlannerCore.DocumentEvidence successor = evidence(
                ADDED_DOCUMENT_ID,
                ADDED_DOCUMENT_LINE_ID,
                TARGET_PUBLICATION_ID,
                "d".repeat(64),
                EstadoVersionLegal.VIGENTE,
                APPLIED_AT,
                CURRENT_BATCH_ID,
                List.of(ContextoLegal.REGISTRO));
        return new LegalEditorialPlannerCore.PlannerSnapshot(
                List.of(ADDED_DOCUMENT_ID),
                List.of(),
                List.of(DOCUMENT_ID),
                List.of(),
                Map.of(
                        DOCUMENT_ID, predecessor,
                        ADDED_DOCUMENT_ID, successor),
                Map.of(),
                List.of(),
                List.of(slot(successor, ContextoLegal.REGISTRO, TARGET_PUBLICATION_ID)),
                List.of(),
                List.of(
                        transition(
                                1,
                                DOCUMENT_ID,
                                EstadoVersionLegal.BORRADOR,
                                EstadoVersionLegal.PUBLICADA,
                                null,
                                HISTORICAL_ACTIVATION_AT),
                        transition(
                                2,
                                DOCUMENT_ID,
                                EstadoVersionLegal.PUBLICADA,
                                EstadoVersionLegal.VIGENTE,
                                HISTORICAL_BATCH_ID,
                                HISTORICAL_ACTIVATION_AT),
                        transition(
                                3,
                                DOCUMENT_ID,
                                EstadoVersionLegal.VIGENTE,
                                EstadoVersionLegal.REEMPLAZADA,
                                CURRENT_BATCH_ID,
                                APPLIED_AT),
                        transition(
                                4,
                                ADDED_DOCUMENT_ID,
                                EstadoVersionLegal.BORRADOR,
                                EstadoVersionLegal.PUBLICADA,
                                null,
                                APPLIED_AT),
                        transition(
                                5,
                                ADDED_DOCUMENT_ID,
                                EstadoVersionLegal.PUBLICADA,
                                EstadoVersionLegal.VIGENTE,
                                CURRENT_BATCH_ID,
                                APPLIED_AT)),
                List.of(),
                Map.of(
                        HISTORICAL_BATCH_ID,
                        historicalBatch(HISTORICAL_ACTIVATION_AT),
                        CURRENT_BATCH_ID,
                        new LegalEditorialPlannerCore.BatchEvidence(
                                CURRENT_BATCH_ID,
                                APPLIED_AT,
                                APPLIED_AT,
                                List.of(DOCUMENT_ID),
                                List.of(new LegalEditorialExecutionPlan.ReplacementSuccessor(
                                        ADDED_DOCUMENT_ID,
                                        TARGET_PUBLICATION_ID)))));
    }

    private static LegalEditorialPlannerCore.BatchEvidence historicalBatch(Instant sealedAt) {
        return new LegalEditorialPlannerCore.BatchEvidence(
                HISTORICAL_BATCH_ID,
                HISTORICAL_BATCH_CREATED_AT,
                sealedAt,
                List.of(HISTORICAL_PREDECESSOR_ID),
                List.of(new LegalEditorialExecutionPlan.ReplacementSuccessor(
                        DOCUMENT_ID,
                        SOURCE_PUBLICATION_ID)));
    }

    private static DocumentReplacementBatch batchWithCardinality(
            int predecessorCount,
            int successorCount) {
        DocumentReplacementBatch batch = mock(DocumentReplacementBatch.class);
        when(batch.predecessors()).thenReturn(Collections.nCopies(
                predecessorCount,
                mock(DocumentRef.class)));
        when(batch.successors()).thenReturn(Collections.nCopies(
                successorCount,
                mock(DocumentRef.class)));
        return batch;
    }

    private static void stubReplacementPublications(Harness harness) {
        when(harness.reader.publication(SOURCE_EXTERNAL_ID)).thenReturn(Optional.of(
                new LegalEditorialPlannerCore.PublicationEvidence(
                        SOURCE_PUBLICATION_ID,
                        SOURCE_EXTERNAL_ID,
                        SOURCE_SHA,
                        "SELLADO",
                        APPLIED_AT)));
    }

    private static LegalEditorialReadinessObservation sourceObservation(String fingerprint) {
        return new LegalEditorialReadinessObservation(
                Optional.of(SOURCE_PUBLICATION_ID),
                OBSERVED_AT,
                fingerprint,
                1,
                0,
                0,
                0,
                0,
                0,
                0);
    }

    private static LegalEditorialPlannerCore.DocumentEvidence evidence(
            UUID id,
            UUID lineId,
            UUID introductionPublicationId,
            String sha256,
            EstadoVersionLegal state,
            Instant stateChangedAt,
            List<ContextoLegal> contexts) {
        return evidence(
                id,
                lineId,
                introductionPublicationId,
                sha256,
                state,
                stateChangedAt,
                null,
                contexts);
    }

    private static LegalEditorialPlannerCore.DocumentEvidence evidence(
            UUID id,
            UUID lineId,
            UUID introductionPublicationId,
            String sha256,
            EstadoVersionLegal state,
            Instant stateChangedAt,
            UUID replacementBatchId,
            List<ContextoLegal> contexts) {
        return new LegalEditorialPlannerCore.DocumentEvidence(
                id,
                lineId,
                introductionPublicationId,
                sha256,
                OBSERVED_AT.minusSeconds(60),
                state,
                stateChangedAt,
                null,
                replacementBatchId,
                TipoDocumentoLegal.TERMINOS_SERVICIO,
                LocaleLegal.ES_AR,
                contexts);
    }

    private static LegalEditorialPlannerCore.SlotEvidence slot(
            LegalEditorialPlannerCore.DocumentEvidence document,
            ContextoLegal context,
            UUID publicationId) {
        return new LegalEditorialPlannerCore.SlotEvidence(
                new LegalEditorialExecutionPlan.DocumentSlotKey(
                        document.type(),
                        document.locale(),
                        context),
                document.id(),
                document.lineId(),
                publicationId);
    }

    private static LegalEditorialPlannerCore.DocumentTransitionEvidence transition(
            long id,
            UUID documentVersionId,
            EstadoVersionLegal previous,
            EstadoVersionLegal next,
            Instant occurredAt) {
        return transition(id, documentVersionId, previous, next, null, occurredAt);
    }

    private static LegalEditorialPlannerCore.DocumentTransitionEvidence transition(
            long id,
            UUID documentVersionId,
            EstadoVersionLegal previous,
            EstadoVersionLegal next,
            UUID replacementBatchId,
            Instant occurredAt) {
        return new LegalEditorialPlannerCore.DocumentTransitionEvidence(
                id,
                documentVersionId,
                previous,
                next,
                null,
                replacementBatchId,
                occurredAt);
    }

    private static LegalEditorialPlannerCore.DocumentEvidence document(
            EstadoVersionLegal state,
            Instant stateChangedAt,
            UUID replacementBatchId) {
        return new LegalEditorialPlannerCore.DocumentEvidence(
                DOCUMENT_ID,
                DOCUMENT_LINE_ID,
                TARGET_PUBLICATION_ID,
                "c".repeat(64),
                OBSERVED_AT.minusSeconds(60),
                state,
                stateChangedAt,
                null,
                replacementBatchId,
                TipoDocumentoLegal.TERMINOS_SERVICIO,
                LocaleLegal.ES_AR,
                List.of(ContextoLegal.REGISTRO));
    }

    private static LegalEditorialReadinessObservation observation(
            int documentVersions,
            int documentTransitions,
            int requirementTransitions,
            int documentSlots,
            int replacementLots) {
        return new LegalEditorialReadinessObservation(
                Optional.of(TARGET_PUBLICATION_ID),
                OBSERVED_AT,
                FINGERPRINT,
                documentVersions,
                0,
                documentTransitions,
                requirementTransitions,
                documentSlots,
                0,
                replacementLots);
    }

    private static UUID uuid(long suffix) {
        return new UUID(0, suffix);
    }

    private static final class Harness {
        private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        private final LegalEditorialReadinessCore readiness =
                mock(LegalEditorialReadinessCore.class);
        private final LegalManifestOriginGraphVerifier origin =
                mock(LegalManifestOriginGraphVerifier.class);
        private final LegalEditorialPlannerCore.PlannerStateReader reader =
                mock(LegalEditorialPlannerCore.PlannerStateReader.class);
        private final ValidatedRelease release = mock(ValidatedRelease.class);

        private Harness(ReviewStatus legalStatus, ReviewStatus accountingStatus) {
            LegalPublicationPlan publicationPlan = mock(LegalPublicationPlan.class);
            LegalManifestV1 manifest = mock(LegalManifestV1.class);
            LegalManifestV1.Review review = mock(LegalManifestV1.Review.class);
            LegalManifestV1.ReviewRecord legal = mock(LegalManifestV1.ReviewRecord.class);
            LegalManifestV1.ReviewRecord accounting = mock(
                    LegalManifestV1.ReviewRecord.class);
            when(release.plan()).thenReturn(publicationPlan);
            when(publicationPlan.manifest()).thenReturn(manifest);
            when(publicationPlan.manifestSha256()).thenReturn(TARGET_SHA);
            when(manifest.publicationId()).thenReturn(TARGET_EXTERNAL_ID);
            when(manifest.review()).thenReturn(review);
            when(review.legal()).thenReturn(legal);
            when(review.accounting()).thenReturn(accounting);
            when(legal.status()).thenReturn(legalStatus);
            when(accounting.status()).thenReturn(accountingStatus);
            when(readiness.usesJdbc(jdbc)).thenReturn(true);
            when(origin.usesJdbc(jdbc)).thenReturn(true);
            when(reader.usesJdbc(jdbc)).thenReturn(true);
            when(reader.publication(TARGET_EXTERNAL_ID)).thenReturn(Optional.of(
                    new LegalEditorialPlannerCore.PublicationEvidence(
                            TARGET_PUBLICATION_ID,
                            TARGET_EXTERNAL_ID,
                            TARGET_SHA,
                            "SELLADO",
                            APPLIED_AT)));
        }

        private LegalEditorialPlannerCore core() {
            return new LegalEditorialPlannerCore(jdbc, readiness, origin, reader);
        }
    }
}
