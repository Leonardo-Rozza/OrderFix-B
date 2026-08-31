package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialPlanValidator.ValidatedEditorialPlan;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialReadiness;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidation;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalEditorialPlanV1;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalEditorialPlanV1.DocumentRef;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalEditorialPlanV1.DocumentReplacementBatch;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalEditorialPlanV1.OperationType;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LegalEditorialApplyServiceReplaceTest {

    private static final Instant OBSERVED_AT =
            Instant.parse("2026-08-30T10:00:00.123456Z");
    private static final Instant TRANSACTION_AT =
            Instant.parse("2026-08-30T09:59:59.123456Z");
    private static final LegalEditorialTimeBoundary BOUNDARY =
            new LegalEditorialTimeBoundary(TRANSACTION_AT, OBSERVED_AT);
    private static final UUID PUBLICATION_ID =
            UUID.fromString("776dccf8-b28c-4a37-a5e6-ad75069c899a");

    @AfterEach
    void clearSynchronization() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void manyToManyReplaceIsBlockedByTheRealGuardBeforeGateTimestampOrJdbcGraph() {
        Harness harness = new Harness();
        ValidatedRelease release = mock(ValidatedRelease.class);
        ValidatedEditorialPlan editorialPlan = manyToManyPlan();

        LegalEditorialApplyResult result =
                harness.service(new LegalEditorialReplaceScopeGuard())
                        .applyReplace(release, editorialPlan);

        assertThat(result.status()).isEqualTo(LegalManifestStatus.BLOCKED);
        assertThat(result.persisted()).isFalse();
        assertThat(result.outcome())
                .isEqualTo(LegalEditorialApplyResult.Outcome.BLOCKED);
        assertThat(result.issues())
                .extracting(LegalManifestIssue::code)
                .containsExactly(LegalManifestIssueCode.REPLACEMENT_MAPPING_INVALID);
        verify(harness.gate, never()).usesJdbc(any());
        verify(harness.gate, never()).executeMutable(any());
        verify(harness.jdbc, never()).queryForObject(anyString(), any(Class.class));
        verify(harness.planner, never()).usesJdbc(any());
        verify(harness.promotionWriter, never()).usesJdbc(any());
        verify(harness.replacementWriter, never()).usesJdbc(any());
        verify(harness.retirementWriter, never()).usesJdbc(any());
        verify(harness.postStateVerifier, never()).usesJdbc(any());
        verify(harness.readinessCore, never()).usesJdbc(any());
        verify(harness.commitReconciler, never()).usesJdbc(any());
        verify(harness.planner, never()).planReplace(any(), any(), any());
        verify(harness.promotionWriter, never()).write(any());
        verify(harness.replacementWriter, never()).write(any());
        verify(harness.retirementWriter, never()).write(any());
        verify(harness.postStateVerifier, never()).verify(any());
        verify(harness.jdbc, never()).execute(anyString());
        verify(harness.readinessCore, never()).evaluate(any(), any());
    }

    @Test
    void freshReplaceUsesOnlyItsWriterAndAllowsDifferentReadinessCounts() {
        Harness harness = new Harness();
        when(harness.retirementWriter.usesJdbc(harness.jdbc)).thenReturn(false);
        executeAndComplete(harness.gate, TransactionSynchronization.STATUS_COMMITTED);
        ValidatedRelease release = mock(ValidatedRelease.class);
        ValidatedEditorialPlan editorialPlan = supportedPlan(harness);
        LegalEditorialExecutionPlan plan = plan(true);
        LegalEditorialApplyReceipt receipt = receipt();
        LegalEditorialReadinessResult readiness = ready();
        assertThat(readiness.observation().orElseThrow().documentVersions())
                .isNotEqualTo(receipt.documentVersions());
        assertThat(readiness.observation().orElseThrow().replacementLots())
                .isNotEqualTo(receipt.replacementBatches());
        when(harness.planner.planReplace(release, editorialPlan, BOUNDARY))
                .thenReturn(LegalEditorialPlanResult.applicable(plan));
        when(harness.postStateVerifier.verify(plan)).thenReturn(receipt);
        when(harness.readinessCore.evaluate(release, OBSERVED_AT))
                .thenReturn(readiness);

        LegalEditorialApplyResult result =
                harness.service().applyReplace(release, editorialPlan);

        assertSuccess(result, LegalEditorialApplyResult.Outcome.APPLIED, receipt);
        verify(harness.jdbc, never()).queryForObject(anyString(), any(Class.class));
        InOrder order = inOrder(
                harness.planner,
                harness.replacementWriter,
                harness.postStateVerifier,
                harness.jdbc,
                harness.readinessCore);
        order.verify(harness.planner).planReplace(release, editorialPlan, BOUNDARY);
        order.verify(harness.replacementWriter).write(plan);
        order.verify(harness.postStateVerifier).verify(plan);
        order.verify(harness.jdbc).execute("SET CONSTRAINTS ALL IMMEDIATE");
        order.verify(harness.readinessCore).evaluate(release, OBSERVED_AT);
        verify(harness.promotionWriter, never()).write(any());
        verify(harness.retirementWriter, never()).usesJdbc(harness.jdbc);
        verify(harness.retirementWriter, never()).write(any());
    }

    @Test
    void verifierFailureAfterReplaceDmlRollsBackBeforeConstraintsOrReadiness() {
        Harness harness = new Harness();
        executeAndRollbackOnFailure(harness.gate);
        ValidatedRelease release = mock(ValidatedRelease.class);
        ValidatedEditorialPlan editorialPlan = supportedPlan(harness);
        LegalEditorialExecutionPlan plan = plan(true);
        when(harness.planner.planReplace(release, editorialPlan, BOUNDARY))
                .thenReturn(LegalEditorialPlanResult.applicable(plan));
        when(harness.postStateVerifier.verify(plan)).thenThrow(
                new LegalEditorialOperationalException(
                        LegalManifestIssueCode.POSTCONDITION_NOT_READY,
                        "database/postcondition"));

        LegalEditorialApplyResult result =
                harness.service().applyReplace(release, editorialPlan);

        assertKnownFailure(result, LegalManifestIssueCode.POSTCONDITION_NOT_READY);
        verify(harness.replacementWriter).write(plan);
        verify(harness.jdbc, never()).execute(anyString());
        verify(harness.readinessCore, never()).evaluate(any(), any());
    }

    @Test
    void deferredConstraintFailureRollsBackReplaceBeforeReadiness() {
        Harness harness = new Harness();
        executeAndRollbackOnFailure(harness.gate);
        ValidatedRelease release = mock(ValidatedRelease.class);
        ValidatedEditorialPlan editorialPlan = supportedPlan(harness);
        LegalEditorialExecutionPlan plan = plan(true);
        when(harness.planner.planReplace(release, editorialPlan, BOUNDARY))
                .thenReturn(LegalEditorialPlanResult.applicable(plan));
        when(harness.postStateVerifier.verify(plan)).thenReturn(receipt());
        doThrow(new IllegalStateException("constraint failure"))
                .when(harness.jdbc).execute("SET CONSTRAINTS ALL IMMEDIATE");

        LegalEditorialApplyResult result =
                harness.service().applyReplace(release, editorialPlan);

        assertKnownFailure(result, LegalManifestIssueCode.EDITORIAL_OBSERVATION_FAILED);
        InOrder order = inOrder(
                harness.replacementWriter,
                harness.postStateVerifier,
                harness.jdbc);
        order.verify(harness.replacementWriter).write(plan);
        order.verify(harness.postStateVerifier).verify(plan);
        order.verify(harness.jdbc).execute("SET CONSTRAINTS ALL IMMEDIATE");
        verify(harness.readinessCore, never()).evaluate(any(), any());
    }

    @Test
    void notReadyAfterReplaceConstraintsRollsBackTheCompleteDelta() {
        Harness harness = new Harness();
        executeAndRollbackOnFailure(harness.gate);
        ValidatedRelease release = mock(ValidatedRelease.class);
        ValidatedEditorialPlan editorialPlan = supportedPlan(harness);
        LegalEditorialExecutionPlan plan = plan(true);
        when(harness.planner.planReplace(release, editorialPlan, BOUNDARY))
                .thenReturn(LegalEditorialPlanResult.applicable(plan));
        when(harness.postStateVerifier.verify(plan)).thenReturn(receipt());
        when(harness.readinessCore.evaluate(release, OBSERVED_AT)).thenReturn(
                LegalEditorialReadinessResult.notReady(
                        ready().observation().orElseThrow(),
                        List.of(LegalManifestIssue.at(
                                LegalManifestIssueCode.CURRENT_STATE_MISMATCH,
                                "database/state"))));

        LegalEditorialApplyResult result =
                harness.service().applyReplace(release, editorialPlan);

        assertKnownFailure(result, LegalManifestIssueCode.POSTCONDITION_NOT_READY);
        verify(harness.replacementWriter).write(plan);
        verify(harness.postStateVerifier).verify(plan);
        verify(harness.jdbc).execute("SET CONSTRAINTS ALL IMMEDIATE");
        verify(harness.readinessCore).evaluate(release, OBSERVED_AT);
    }

    @Test
    void foreignReplaceReceiptIdentityRollsBackFailClosed() {
        Harness harness = new Harness();
        executeAndRollbackOnFailure(harness.gate);
        ValidatedRelease release = mock(ValidatedRelease.class);
        ValidatedEditorialPlan editorialPlan = supportedPlan(harness);
        LegalEditorialExecutionPlan plan = plan(true);
        LegalEditorialApplyReceipt foreignReceipt = new LegalEditorialApplyReceipt(
                LegalEditorialApplyReceipt.OperationType.REPLACE,
                UUID.fromString("87ca3b08-23f4-4ac8-8611-2ac95a850bb4"),
                TRANSACTION_AT,
                LegalEditorialReadiness.READY,
                2,
                1,
                5,
                2,
                1,
                1,
                1);
        when(harness.planner.planReplace(release, editorialPlan, BOUNDARY))
                .thenReturn(LegalEditorialPlanResult.applicable(plan));
        when(harness.postStateVerifier.verify(plan)).thenReturn(foreignReceipt);
        when(harness.readinessCore.evaluate(release, OBSERVED_AT)).thenReturn(ready());

        LegalEditorialApplyResult result =
                harness.service().applyReplace(release, editorialPlan);

        assertKnownFailure(result, LegalManifestIssueCode.POSTCONDITION_NOT_READY);
    }

    @Test
    void exactReplaceReplayIsSelectOnlyAndInvokesNoWriter() {
        Harness harness = new Harness();
        executeAndComplete(harness.gate, TransactionSynchronization.STATUS_COMMITTED);
        ValidatedRelease release = mock(ValidatedRelease.class);
        ValidatedEditorialPlan editorialPlan = supportedPlan(harness);
        LegalEditorialExecutionPlan plan = plan(false);
        LegalEditorialApplyReceipt receipt = receipt();
        when(harness.planner.planReplace(release, editorialPlan, BOUNDARY))
                .thenReturn(LegalEditorialPlanResult.applicable(plan));
        when(harness.postStateVerifier.verify(plan)).thenReturn(receipt);

        LegalEditorialApplyResult result =
                harness.service().applyReplace(release, editorialPlan);

        assertSuccess(
                result,
                LegalEditorialApplyResult.Outcome.ALREADY_APPLIED,
                receipt);
        verify(harness.promotionWriter, never()).write(any());
        verify(harness.replacementWriter, never()).write(any());
        verify(harness.retirementWriter, never()).write(any());
        verify(harness.postStateVerifier).verify(plan);
        verify(harness.jdbc, never()).execute(anyString());
        verify(harness.readinessCore, never()).evaluate(any(), any());
    }

    @Test
    void unknownReplaceCompletionNeverLeaksTheTentativeReceipt() {
        Harness harness = new Harness();
        executeAndComplete(
                harness.gate,
                TransactionSynchronization.STATUS_UNKNOWN,
                new IllegalStateException("connection outcome unknown"));
        ValidatedRelease release = mock(ValidatedRelease.class);
        ValidatedEditorialPlan editorialPlan = supportedPlan(harness);
        LegalEditorialExecutionPlan plan = plan(true);
        when(harness.planner.planReplace(release, editorialPlan, BOUNDARY))
                .thenReturn(LegalEditorialPlanResult.applicable(plan));
        when(harness.postStateVerifier.verify(plan)).thenReturn(receipt());
        when(harness.readinessCore.evaluate(release, OBSERVED_AT)).thenReturn(ready());
        when(harness.commitReconciler.reconcileReplace(
                release,
                editorialPlan,
                true)).thenReturn(new LegalEditorialCommitReconciler.Result(
                        LegalEditorialCommitReconciler.Outcome.UNKNOWN,
                        Optional.empty()));

        LegalEditorialApplyResult result =
                harness.service().applyReplace(release, editorialPlan);

        assertThat(result.status()).isEqualTo(LegalManifestStatus.ERROR);
        assertThat(result.persisted()).isNull();
        assertThat(result.outcome()).isEqualTo(LegalEditorialApplyResult.Outcome.UNKNOWN);
        assertThat(result.receipt()).isEmpty();
        assertThat(result.targetPublicationUuid()).isEmpty();
        assertThat(result.appliedAt()).isEmpty();
        assertThat(result.issues())
                .extracting(LegalManifestIssue::code)
                .containsExactly(LegalManifestIssueCode.COMMIT_OUTCOME_UNKNOWN);
        verify(harness.commitReconciler).reconcileReplace(
                release,
                editorialPlan,
                true);
    }

    @Test
    void failureAfterCommittedReplaceCompletionKeepsTheConfirmedReceipt() {
        Harness harness = new Harness();
        executeAndComplete(
                harness.gate,
                TransactionSynchronization.STATUS_COMMITTED,
                new LinkageError("driver cleanup failed"));
        ValidatedRelease release = mock(ValidatedRelease.class);
        ValidatedEditorialPlan editorialPlan = supportedPlan(harness);
        LegalEditorialExecutionPlan plan = plan(true);
        LegalEditorialApplyReceipt receipt = receipt();
        when(harness.planner.planReplace(release, editorialPlan, BOUNDARY))
                .thenReturn(LegalEditorialPlanResult.applicable(plan));
        when(harness.postStateVerifier.verify(plan)).thenReturn(receipt);
        when(harness.readinessCore.evaluate(release, OBSERVED_AT)).thenReturn(ready());

        LegalEditorialApplyResult result =
                harness.service().applyReplace(release, editorialPlan);

        assertSuccess(result, LegalEditorialApplyResult.Outcome.APPLIED, receipt);
    }

    @Test
    void aPlannerResultOfAnotherOperationRollsBackBeforeWriterOrVerifier() {
        Harness harness = new Harness();
        executeAndRollbackOnFailure(harness.gate);
        ValidatedRelease release = mock(ValidatedRelease.class);
        ValidatedEditorialPlan editorialPlan = supportedPlan(harness);
        LegalEditorialExecutionPlan plan = plan(false);
        when(plan.operationType())
                .thenReturn(LegalEditorialExecutionPlan.OperationType.PROMOTE);
        when(harness.planner.planReplace(release, editorialPlan, BOUNDARY))
                .thenReturn(LegalEditorialPlanResult.applicable(plan));

        LegalEditorialApplyResult result =
                harness.service().applyReplace(release, editorialPlan);

        assertThat(result.outcome()).isEqualTo(LegalEditorialApplyResult.Outcome.ERROR);
        assertThat(result.persisted()).isFalse();
        assertThat(result.issues())
                .extracting(LegalManifestIssue::code)
                .containsExactly(LegalManifestIssueCode.EDITORIAL_OBSERVATION_FAILED);
        verify(harness.promotionWriter, never()).write(any());
        verify(harness.replacementWriter, never()).write(any());
        verify(harness.retirementWriter, never()).write(any());
        verify(harness.postStateVerifier, never()).verify(any());
    }

    @Test
    void foreignReplacementWriterFailsBeforeOpeningTheTransaction() {
        Harness harness = new Harness();
        when(harness.replacementWriter.usesJdbc(harness.jdbc)).thenReturn(false);
        ValidatedEditorialPlan editorialPlan = supportedPlan(harness);

        LegalEditorialApplyResult result = harness.service().applyReplace(
                mock(ValidatedRelease.class),
                editorialPlan);

        assertThat(result.outcome()).isEqualTo(LegalEditorialApplyResult.Outcome.ERROR);
        assertThat(result.persisted()).isFalse();
        assertThat(result.issues())
                .extracting(LegalManifestIssue::code)
                .containsExactly(LegalManifestIssueCode.EDITORIAL_OBSERVATION_FAILED);
        verify(harness.gate, never()).executeMutable(any());
        verify(harness.jdbc, never()).queryForObject(anyString(), any(Class.class));
    }

    private static ValidatedEditorialPlan supportedPlan(Harness harness) {
        ValidatedEditorialPlan editorialPlan = mock(ValidatedEditorialPlan.class);
        when(harness.scopeGuard.validate(editorialPlan))
                .thenReturn(LegalManifestValidation.pass(editorialPlan));
        return editorialPlan;
    }

    private static ValidatedEditorialPlan manyToManyPlan() {
        DocumentReplacementBatch batch = new DocumentReplacementBatch(
                UUID.fromString("00000000-0000-0000-0000-000000000201"),
                List.of(ContextoLegal.USO_CONTINUADO),
                List.of(
                        documentRef("00000000-0000-0000-0000-000000000202", 'a'),
                        documentRef("00000000-0000-0000-0000-000000000203", 'b')),
                List.of(
                        documentRef("00000000-0000-0000-0000-000000000204", 'c'),
                        documentRef("00000000-0000-0000-0000-000000000205", 'd')));
        LegalEditorialPlanV1 model = mock(LegalEditorialPlanV1.class);
        when(model.documentReplacementBatches()).thenReturn(List.of(batch));
        ValidatedEditorialPlan token = mock(ValidatedEditorialPlan.class);
        when(token.plan()).thenReturn(model);
        when(token.operationType()).thenReturn(OperationType.REPLACE);
        return token;
    }

    private static DocumentRef documentRef(String id, char digestCharacter) {
        return new DocumentRef(
                UUID.fromString(id),
                String.valueOf(digestCharacter).repeat(64));
    }

    private static LegalEditorialExecutionPlan plan(boolean changeRequired) {
        LegalEditorialExecutionPlan plan = mock(LegalEditorialExecutionPlan.class);
        when(plan.changeRequired()).thenReturn(changeRequired);
        when(plan.operationType()).thenReturn(
                LegalEditorialExecutionPlan.OperationType.REPLACE);
        when(plan.target()).thenReturn(new LegalEditorialExecutionPlan.PublicationIdentity(
                "publication-replace",
                PUBLICATION_ID,
                "a".repeat(64)));
        when(plan.transactionAt()).thenReturn(TRANSACTION_AT);
        when(plan.observedAt()).thenReturn(OBSERVED_AT);
        when(plan.expectedAppliedAt()).thenReturn(TRANSACTION_AT);
        when(plan.expectedReadinessAfter()).thenReturn(LegalEditorialReadiness.READY);
        return plan;
    }

    private static LegalEditorialApplyReceipt receipt() {
        return new LegalEditorialApplyReceipt(
                LegalEditorialApplyReceipt.OperationType.REPLACE,
                PUBLICATION_ID,
                TRANSACTION_AT,
                LegalEditorialReadiness.READY,
                2,
                1,
                5,
                2,
                1,
                1,
                1);
    }

    private static LegalEditorialReadinessResult ready() {
        return LegalEditorialReadinessResult.ready(
                new LegalEditorialReadinessObservation(
                        Optional.of(PUBLICATION_ID),
                        OBSERVED_AT,
                        "sha256:" + "0".repeat(64),
                        99,
                        98,
                        97,
                        96,
                        95,
                        94,
                        93));
    }

    private static void assertSuccess(
            LegalEditorialApplyResult result,
            LegalEditorialApplyResult.Outcome outcome,
            LegalEditorialApplyReceipt receipt) {
        assertThat(result.status()).isEqualTo(LegalManifestStatus.PASS);
        assertThat(result.persisted()).isTrue();
        assertThat(result.outcome()).isEqualTo(outcome);
        assertThat(result.receipt()).contains(receipt);
        assertThat(result.issues()).isEmpty();
    }

    private static void assertKnownFailure(
            LegalEditorialApplyResult result,
            LegalManifestIssueCode issueCode) {
        assertThat(result.status()).isEqualTo(LegalManifestStatus.ERROR);
        assertThat(result.persisted()).isFalse();
        assertThat(result.outcome()).isEqualTo(LegalEditorialApplyResult.Outcome.ERROR);
        assertThat(result.receipt()).isEmpty();
        assertThat(result.issues())
                .extracting(LegalManifestIssue::code)
                .containsExactly(issueCode);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void executeAndComplete(
            LegalManifestDatabaseGate gate,
            int completion) {
        executeAndComplete(gate, completion, null);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void executeAndComplete(
            LegalManifestDatabaseGate gate,
            int completion,
            Throwable terminalFailure) {
        when(gate.executeMutable(any())).thenAnswer(invocation -> {
            LegalManifestDatabaseGate.EditorialTransactionCallback callback =
                    invocation.getArgument(0);
            beginSynchronizedTransaction();
            try {
                Object value = callback.doInTransaction(
                        new SimpleTransactionStatus(),
                        BOUNDARY);
                List<TransactionSynchronization> synchronizations =
                        TransactionSynchronizationManager.getSynchronizations();
                synchronizations.forEach(sync -> sync.beforeCommit(false));
                synchronizations.forEach(sync -> sync.afterCompletion(completion));
                if (terminalFailure instanceof RuntimeException runtimeFailure) {
                    throw runtimeFailure;
                }
                if (terminalFailure instanceof LinkageError linkageFailure) {
                    throw linkageFailure;
                }
                return value;
            } finally {
                TransactionSynchronizationManager.clear();
            }
        });
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void executeAndRollbackOnFailure(LegalManifestDatabaseGate gate) {
        when(gate.executeMutable(any())).thenAnswer(invocation -> {
            LegalManifestDatabaseGate.EditorialTransactionCallback callback =
                    invocation.getArgument(0);
            beginSynchronizedTransaction();
            try {
                return callback.doInTransaction(
                        new SimpleTransactionStatus(),
                        BOUNDARY);
            } catch (RuntimeException | LinkageError failure) {
                TransactionSynchronizationManager.getSynchronizations()
                        .forEach(sync -> sync.afterCompletion(
                                TransactionSynchronization.STATUS_ROLLED_BACK));
                throw failure;
            } finally {
                TransactionSynchronizationManager.clear();
            }
        });
    }

    private static void beginSynchronizedTransaction() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    private static final class Harness {
        private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        private final LegalManifestDatabaseGate gate = mock(LegalManifestDatabaseGate.class);
        private final LegalEditorialPlannerCore planner = mock(LegalEditorialPlannerCore.class);
        private final LegalEditorialMutationWriter promotionWriter =
                mock(LegalEditorialMutationWriter.class);
        private final LegalEditorialMutationWriter replacementWriter =
                mock(LegalEditorialMutationWriter.class);
        private final LegalEditorialMutationWriter retirementWriter =
                mock(LegalEditorialMutationWriter.class);
        private final LegalEditorialPostStateVerifier postStateVerifier =
                mock(LegalEditorialPostStateVerifier.class);
        private final LegalEditorialReadinessCore readinessCore =
                mock(LegalEditorialReadinessCore.class);
        private final LegalEditorialReplaceScopeGuard scopeGuard =
                mock(LegalEditorialReplaceScopeGuard.class);
        private final LegalEditorialCommitReconciler commitReconciler =
                mock(LegalEditorialCommitReconciler.class);
        private final LegalEditorialFailureMapper failureMapper =
                new LegalEditorialFailureMapper();
        private final LegalEditorialSchemaVerifier schemaVerifier =
                mock(LegalEditorialSchemaVerifier.class);
        private final LegalEditorialPrivilegeVerifier privilegeVerifier =
                mock(LegalEditorialPrivilegeVerifier.class);

        private Harness() {
            when(gate.usesJdbc(jdbc)).thenReturn(true);
            when(planner.usesJdbc(jdbc)).thenReturn(true);
            when(promotionWriter.usesJdbc(jdbc)).thenReturn(true);
            when(replacementWriter.usesJdbc(jdbc)).thenReturn(true);
            when(retirementWriter.usesJdbc(jdbc)).thenReturn(true);
            when(postStateVerifier.usesJdbc(jdbc)).thenReturn(true);
            when(readinessCore.usesJdbc(jdbc)).thenReturn(true);
            when(commitReconciler.usesJdbc(jdbc)).thenReturn(true);
        }

        private LegalEditorialApplyService service() {
            return service(scopeGuard);
        }

        private LegalEditorialApplyService service(
                LegalEditorialReplaceScopeGuard replaceScopeGuard) {
            return new LegalEditorialApplyService(
                    gate,
                    jdbc,
                    planner,
                    promotionWriter,
                    replacementWriter,
                    retirementWriter,
                    postStateVerifier,
                    readinessCore,
                    replaceScopeGuard,
                    commitReconciler,
                    failureMapper,
                    schemaVerifier,
                    privilegeVerifier);
        }
    }
}
