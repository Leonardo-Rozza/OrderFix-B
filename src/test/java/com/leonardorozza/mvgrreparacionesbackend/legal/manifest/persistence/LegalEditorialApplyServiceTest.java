package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialReadiness;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LegalEditorialApplyServiceTest {

    private static final Instant OBSERVED_AT =
            Instant.parse("2026-08-28T18:00:00.123456Z");
    private static final Instant TRANSACTION_AT =
            Instant.parse("2026-08-28T17:59:59.123456Z");
    private static final LegalEditorialTimeBoundary BOUNDARY =
            new LegalEditorialTimeBoundary(TRANSACTION_AT, OBSERVED_AT);
    private static final UUID PUBLICATION_ID =
            UUID.fromString("2baa77da-06fc-45f2-88c9-7d662d84dcb1");

    @AfterEach
    void clearSynchronization() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void constructorIsTheOnlyAssemblyPathAndRequiresTheExactEditorialPreflights() {
        Harness harness = new Harness();

        harness.service();

        assertThat(LegalEditorialApplyService.class.getDeclaredConstructors())
                .singleElement()
                .satisfies(constructor -> {
                    assertThat(constructor.getParameterCount()).isEqualTo(13);
                    assertThat(constructor.getParameterTypes())
                            .containsOnlyOnce(LegalEditorialCommitReconciler.class);
                    assertThat(constructor.getModifiers() & Modifier.PUBLIC).isZero();
                });
        verify(harness.gate).requireExactEditorialPreflights(
                harness.jdbc,
                harness.schemaVerifier,
                harness.privilegeVerifier);
    }

    @Test
    void freshPromotionCommitsOnlyAfterTheGateBoundaryAndAReadyExactPostcondition() {
        Harness harness = new Harness();
        executeAndComplete(
                harness.gate,
                TransactionSynchronization.STATUS_COMMITTED,
                null);
        ValidatedRelease release = mock(ValidatedRelease.class);
        LegalEditorialExecutionPlan plan = plan(true);
        LegalEditorialApplyReceipt receipt = receipt();
        when(harness.planner.planPromote(release, BOUNDARY))
                .thenReturn(LegalEditorialPlanResult.applicable(plan));
        when(harness.postStateVerifier.verify(plan)).thenReturn(receipt);
        when(harness.readinessCore.evaluate(release, OBSERVED_AT))
                .thenReturn(ready());

        LegalEditorialApplyResult result = harness.service().applyPromote(release);

        assertSuccess(result, LegalEditorialApplyResult.Outcome.APPLIED, receipt);
        verify(harness.gate, times(1)).executeMutable(any());
        verify(harness.jdbc, never()).queryForObject(anyString(), any(Class.class));
        InOrder order = inOrder(
                harness.planner,
                harness.writer,
                harness.postStateVerifier,
                harness.jdbc,
                harness.readinessCore);
        order.verify(harness.planner).planPromote(release, BOUNDARY);
        order.verify(harness.writer).write(plan);
        order.verify(harness.postStateVerifier).verify(plan);
        order.verify(harness.jdbc).execute("SET CONSTRAINTS ALL IMMEDIATE");
        order.verify(harness.readinessCore).evaluate(release, OBSERVED_AT);
        verify(harness.commitReconciler, never())
                .reconcilePromote(eq(release), anyBoolean());
    }

    @Test
    void foreignReplaceAndRetireWritersDoNotParticipateInPromote() {
        Harness harness = new Harness();
        when(harness.replacementWriter.usesJdbc(harness.jdbc)).thenReturn(false);
        when(harness.retirementWriter.usesJdbc(harness.jdbc)).thenReturn(false);
        executeAndComplete(
                harness.gate,
                TransactionSynchronization.STATUS_COMMITTED,
                null);
        ValidatedRelease release = mock(ValidatedRelease.class);
        LegalEditorialExecutionPlan plan = plan(true);
        LegalEditorialApplyReceipt receipt = receipt();
        when(harness.planner.planPromote(release, BOUNDARY))
                .thenReturn(LegalEditorialPlanResult.applicable(plan));
        when(harness.postStateVerifier.verify(plan)).thenReturn(receipt);
        when(harness.readinessCore.evaluate(release, OBSERVED_AT))
                .thenReturn(ready());

        LegalEditorialApplyResult result = harness.service().applyPromote(release);

        assertSuccess(result, LegalEditorialApplyResult.Outcome.APPLIED, receipt);
        verify(harness.writer).usesJdbc(harness.jdbc);
        verify(harness.writer).write(plan);
        verify(harness.replacementWriter, never()).usesJdbc(harness.jdbc);
        verify(harness.replacementWriter, never()).write(any());
        verify(harness.retirementWriter, never()).usesJdbc(harness.jdbc);
        verify(harness.retirementWriter, never()).write(any());
    }

    @Test
    void exactReplayIsConfirmedWithSelectOnlyAndDoesNotRunThePostMutationReadiness() {
        Harness harness = new Harness();
        executeAndComplete(
                harness.gate,
                TransactionSynchronization.STATUS_COMMITTED,
                null);
        ValidatedRelease release = mock(ValidatedRelease.class);
        LegalEditorialExecutionPlan plan = plan(false);
        LegalEditorialApplyReceipt receipt = receipt();
        when(harness.planner.planPromote(release, BOUNDARY))
                .thenReturn(LegalEditorialPlanResult.applicable(plan));
        when(harness.postStateVerifier.verify(plan)).thenReturn(receipt);

        LegalEditorialApplyResult result = harness.service().applyPromote(release);

        assertSuccess(
                result,
                LegalEditorialApplyResult.Outcome.ALREADY_APPLIED,
                receipt);
        verify(harness.writer, never()).write(any());
        verify(harness.replacementWriter, never()).write(any());
        verify(harness.retirementWriter, never()).write(any());
        verify(harness.postStateVerifier).verify(plan);
        verify(harness.jdbc, never()).execute(anyString());
        verify(harness.readinessCore, never()).evaluate(any(), any());
        verify(harness.jdbc, never()).queryForObject(anyString(), any(Class.class));
    }

    @Test
    void replayWithANonPromotePlanFailsClosedBeforeVerification() {
        Harness harness = new Harness();
        executeAndRollbackOnFailure(harness.gate);
        ValidatedRelease release = mock(ValidatedRelease.class);
        LegalEditorialExecutionPlan plan = plan(false);
        when(plan.operationType()).thenReturn(LegalEditorialExecutionPlan.OperationType.REPLACE);
        when(harness.planner.planPromote(release, BOUNDARY))
                .thenReturn(LegalEditorialPlanResult.applicable(plan));

        LegalEditorialApplyResult result = harness.service().applyPromote(release);

        assertKnownFailure(
                result,
                LegalManifestStatus.ERROR,
                LegalEditorialApplyResult.Outcome.ERROR,
                LegalManifestIssueCode.EDITORIAL_OBSERVATION_FAILED);
        verify(harness.writer, never()).write(any());
        verify(harness.replacementWriter, never()).write(any());
        verify(harness.retirementWriter, never()).write(any());
        verify(harness.postStateVerifier, never()).verify(any());
        verify(harness.jdbc, never()).execute(anyString());
        verify(harness.readinessCore, never()).evaluate(any(), any());
    }

    @Test
    void plannerBlockerRollsBackAndNeverMutates() {
        Harness harness = new Harness();
        executeAndRollbackOnFailure(harness.gate);
        ValidatedRelease release = mock(ValidatedRelease.class);
        when(harness.planner.planPromote(release, BOUNDARY))
                .thenReturn(LegalEditorialPlanResult.blocked(List.of(
                        LegalManifestIssue.at(
                                LegalManifestIssueCode.CURRENT_STATE_MISMATCH,
                                "database/state"))));

        LegalEditorialApplyResult result = harness.service().applyPromote(release);

        assertKnownFailure(
                result,
                LegalManifestStatus.BLOCKED,
                LegalEditorialApplyResult.Outcome.BLOCKED,
                LegalManifestIssueCode.CURRENT_STATE_MISMATCH);
        verify(harness.writer, never()).write(any());
        verify(harness.postStateVerifier, never()).verify(any());
        verify(harness.readinessCore, never()).evaluate(any(), any());
    }

    @Test
    void notReadyPostconditionRollsBackTheCompleteDelta() {
        Harness harness = new Harness();
        executeAndRollbackOnFailure(harness.gate);
        ValidatedRelease release = mock(ValidatedRelease.class);
        LegalEditorialExecutionPlan plan = plan(true);
        when(harness.planner.planPromote(release, BOUNDARY))
                .thenReturn(LegalEditorialPlanResult.applicable(plan));
        when(harness.postStateVerifier.verify(plan)).thenReturn(receipt());
        when(harness.readinessCore.evaluate(release, OBSERVED_AT))
                .thenReturn(LegalEditorialReadinessResult.notReady(
                        observation(PUBLICATION_ID),
                        List.of(LegalManifestIssue.at(
                                LegalManifestIssueCode.CURRENT_STATE_MISMATCH,
                                "database/state"))));

        LegalEditorialApplyResult result = harness.service().applyPromote(release);

        assertKnownFailure(
                result,
                LegalManifestStatus.ERROR,
                LegalEditorialApplyResult.Outcome.ERROR,
                LegalManifestIssueCode.POSTCONDITION_NOT_READY);
        verify(harness.writer).write(plan);
        verify(harness.postStateVerifier).verify(plan);
    }

    @Test
    void verifierMismatchAfterDmlNeverForcesConstraintsOrReadiness() {
        Harness harness = new Harness();
        executeAndRollbackOnFailure(harness.gate);
        ValidatedRelease release = mock(ValidatedRelease.class);
        LegalEditorialExecutionPlan plan = plan(true);
        when(harness.planner.planPromote(release, BOUNDARY))
                .thenReturn(LegalEditorialPlanResult.applicable(plan));
        when(harness.postStateVerifier.verify(plan)).thenThrow(
                new LegalEditorialOperationalException(
                        LegalManifestIssueCode.POSTCONDITION_NOT_READY,
                        "database/postcondition"));

        LegalEditorialApplyResult result = harness.service().applyPromote(release);

        assertKnownFailure(
                result,
                LegalManifestStatus.ERROR,
                LegalEditorialApplyResult.Outcome.ERROR,
                LegalManifestIssueCode.POSTCONDITION_NOT_READY);
        verify(harness.writer).write(plan);
        verify(harness.jdbc, never()).execute(anyString());
        verify(harness.readinessCore, never()).evaluate(any(), any());
    }

    @Test
    void deferredConstraintFailureOccursAfterVerificationAndBeforeReadiness() {
        Harness harness = new Harness();
        executeAndRollbackOnFailure(harness.gate);
        ValidatedRelease release = mock(ValidatedRelease.class);
        LegalEditorialExecutionPlan plan = plan(true);
        when(harness.planner.planPromote(release, BOUNDARY))
                .thenReturn(LegalEditorialPlanResult.applicable(plan));
        when(harness.postStateVerifier.verify(plan)).thenReturn(receipt());
        doThrow(new IllegalStateException("constraint failure"))
                .when(harness.jdbc).execute("SET CONSTRAINTS ALL IMMEDIATE");

        LegalEditorialApplyResult result = harness.service().applyPromote(release);

        assertKnownFailure(
                result,
                LegalManifestStatus.ERROR,
                LegalEditorialApplyResult.Outcome.ERROR,
                LegalManifestIssueCode.EDITORIAL_OBSERVATION_FAILED);
        InOrder order = inOrder(harness.writer, harness.postStateVerifier, harness.jdbc);
        order.verify(harness.writer).write(plan);
        order.verify(harness.postStateVerifier).verify(plan);
        order.verify(harness.jdbc).execute("SET CONSTRAINTS ALL IMMEDIATE");
        verify(harness.readinessCore, never()).evaluate(any(), any());
        verify(harness.commitReconciler, never())
                .reconcilePromote(eq(release), anyBoolean());
    }

    @Test
    void aReceiptWithForeignTargetRollsBackFailClosed() {
        Harness harness = new Harness();
        executeAndRollbackOnFailure(harness.gate);
        ValidatedRelease release = mock(ValidatedRelease.class);
        LegalEditorialExecutionPlan plan = plan(true);
        LegalEditorialApplyReceipt inconsistent = new LegalEditorialApplyReceipt(
                LegalEditorialApplyReceipt.OperationType.PROMOTE,
                UUID.fromString("87ca3b08-23f4-4ac8-8611-2ac95a850bb4"),
                TRANSACTION_AT,
                LegalEditorialReadiness.READY,
                2,
                2,
                4,
                4,
                3,
                1,
                0);
        when(harness.planner.planPromote(release, BOUNDARY))
                .thenReturn(LegalEditorialPlanResult.applicable(plan));
        when(harness.postStateVerifier.verify(plan)).thenReturn(inconsistent);
        when(harness.readinessCore.evaluate(release, OBSERVED_AT))
                .thenReturn(ready());

        LegalEditorialApplyResult result = harness.service().applyPromote(release);

        assertKnownFailure(
                result,
                LegalManifestStatus.ERROR,
                LegalEditorialApplyResult.Outcome.ERROR,
                LegalManifestIssueCode.POSTCONDITION_NOT_READY);
    }

    @Test
    void promoteReadinessCountDriftRollsBackFailClosed() {
        Harness harness = new Harness();
        executeAndRollbackOnFailure(harness.gate);
        ValidatedRelease release = mock(ValidatedRelease.class);
        LegalEditorialExecutionPlan plan = plan(true);
        LegalEditorialApplyReceipt receipt = receipt();
        when(harness.planner.planPromote(release, BOUNDARY))
                .thenReturn(LegalEditorialPlanResult.applicable(plan));
        when(harness.postStateVerifier.verify(plan)).thenReturn(receipt);
        when(harness.readinessCore.evaluate(release, OBSERVED_AT))
                .thenReturn(LegalEditorialReadinessResult.ready(
                        new LegalEditorialReadinessObservation(
                                Optional.of(PUBLICATION_ID),
                                OBSERVED_AT,
                                "sha256:" + "1".repeat(64),
                                9,
                                8,
                                17,
                                15,
                                7,
                                6,
                                5)));

        LegalEditorialApplyResult result = harness.service().applyPromote(release);

        assertKnownFailure(
                result,
                LegalManifestStatus.ERROR,
                LegalEditorialApplyResult.Outcome.ERROR,
                LegalManifestIssueCode.POSTCONDITION_NOT_READY);
    }

    @Test
    void unknownCompletionNeverLeaksTheTentativeReceipt() {
        Harness harness = new Harness();
        executeAndComplete(
                harness.gate,
                TransactionSynchronization.STATUS_UNKNOWN,
                new IllegalStateException("connection outcome unknown"));
        ValidatedRelease release = mock(ValidatedRelease.class);
        LegalEditorialExecutionPlan plan = plan(true);
        when(harness.planner.planPromote(release, BOUNDARY))
                .thenReturn(LegalEditorialPlanResult.applicable(plan));
        when(harness.postStateVerifier.verify(plan)).thenReturn(receipt());
        when(harness.readinessCore.evaluate(release, OBSERVED_AT))
                .thenReturn(ready());
        when(harness.commitReconciler.reconcilePromote(release, true))
                .thenReturn(new LegalEditorialCommitReconciler.Result(
                        LegalEditorialCommitReconciler.Outcome.UNKNOWN,
                        Optional.empty()));

        LegalEditorialApplyResult result = harness.service().applyPromote(release);

        assertThat(result.status()).isEqualTo(LegalManifestStatus.ERROR);
        assertThat(result.persisted()).isNull();
        assertThat(result.outcome()).isEqualTo(LegalEditorialApplyResult.Outcome.UNKNOWN);
        assertThat(result.receipt()).isEmpty();
        assertThat(result.targetPublicationUuid()).isEmpty();
        assertThat(result.appliedAt()).isEmpty();
        assertThat(result.issues())
                .extracting(LegalManifestIssue::code)
                .containsExactly(LegalManifestIssueCode.COMMIT_OUTCOME_UNKNOWN);
        verify(harness.commitReconciler).reconcilePromote(release, true);
    }

    @Test
    void aFailureAfterCommittedCompletionStillReturnsTheConfirmedReceipt() {
        Harness harness = new Harness();
        executeAndComplete(
                harness.gate,
                TransactionSynchronization.STATUS_COMMITTED,
                new LinkageError("driver cleanup failed"));
        ValidatedRelease release = mock(ValidatedRelease.class);
        LegalEditorialExecutionPlan plan = plan(true);
        LegalEditorialApplyReceipt receipt = receipt();
        when(harness.planner.planPromote(release, BOUNDARY))
                .thenReturn(LegalEditorialPlanResult.applicable(plan));
        when(harness.postStateVerifier.verify(plan)).thenReturn(receipt);
        when(harness.readinessCore.evaluate(release, OBSERVED_AT))
                .thenReturn(ready());

        LegalEditorialApplyResult result = harness.service().applyPromote(release);

        assertSuccess(result, LegalEditorialApplyResult.Outcome.APPLIED, receipt);
        verify(harness.commitReconciler, never())
                .reconcilePromote(eq(release), anyBoolean());
    }

    @Test
    void foreignJdbcParticipantsFailBeforeOpeningTheTransaction() {
        Harness foreignVerifier = new Harness();
        when(foreignVerifier.postStateVerifier.usesJdbc(foreignVerifier.jdbc))
                .thenReturn(false);

        LegalEditorialApplyResult verifierResult = foreignVerifier.service()
                .applyPromote(mock(ValidatedRelease.class));

        assertKnownFailure(
                verifierResult,
                LegalManifestStatus.ERROR,
                LegalEditorialApplyResult.Outcome.ERROR,
                LegalManifestIssueCode.EDITORIAL_OBSERVATION_FAILED);
        verify(foreignVerifier.gate, never()).executeMutable(any());
        verify(foreignVerifier.jdbc, never()).queryForObject(anyString(), any(Class.class));

        Harness foreignReconciler = new Harness();
        when(foreignReconciler.commitReconciler.usesJdbc(foreignReconciler.jdbc))
                .thenReturn(false);

        LegalEditorialApplyResult reconcilerResult = foreignReconciler.service()
                .applyPromote(mock(ValidatedRelease.class));

        assertKnownFailure(
                reconcilerResult,
                LegalManifestStatus.ERROR,
                LegalEditorialApplyResult.Outcome.ERROR,
                LegalManifestIssueCode.EDITORIAL_OBSERVATION_FAILED);
        verify(foreignReconciler.gate, never()).executeMutable(any());
        verify(foreignReconciler.jdbc, never())
                .queryForObject(anyString(), any(Class.class));
    }

    @Test
    void aPlanFromAnotherTimeBoundaryFailsBeforePlanConstructionOrDml() {
        Harness harness = new Harness();
        executeAndRollbackOnFailure(harness.gate);
        ValidatedRelease release = mock(ValidatedRelease.class);
        LegalEditorialExecutionPlan plan = plan(true);
        when(plan.transactionAt()).thenReturn(TRANSACTION_AT.minusSeconds(1));
        when(harness.planner.planPromote(release, BOUNDARY))
                .thenReturn(LegalEditorialPlanResult.applicable(plan));

        LegalEditorialApplyResult result = harness.service().applyPromote(release);

        assertKnownFailure(
                result,
                LegalManifestStatus.ERROR,
                LegalEditorialApplyResult.Outcome.ERROR,
                LegalManifestIssueCode.EDITORIAL_OBSERVATION_FAILED);
        verify(harness.writer, never()).write(any());
        verify(harness.postStateVerifier, never()).verify(any());
        verify(harness.readinessCore, never()).evaluate(any(), any());
        verify(harness.commitReconciler, never())
                .reconcilePromote(eq(release), anyBoolean());
    }

    private static LegalEditorialExecutionPlan plan(boolean changeRequired) {
        LegalEditorialExecutionPlan plan = mock(LegalEditorialExecutionPlan.class);
        when(plan.changeRequired()).thenReturn(changeRequired);
        when(plan.operationType()).thenReturn(LegalEditorialExecutionPlan.OperationType.PROMOTE);
        when(plan.target()).thenReturn(new LegalEditorialExecutionPlan.PublicationIdentity(
                "publication-1",
                PUBLICATION_ID,
                "a".repeat(64)));
        when(plan.transactionAt()).thenReturn(TRANSACTION_AT);
        when(plan.observedAt()).thenReturn(OBSERVED_AT);
        when(plan.expectedAppliedAt()).thenReturn(TRANSACTION_AT);
        when(plan.expectedReadinessAfter()).thenReturn(LegalEditorialReadiness.READY);
        return plan;
    }

    private static LegalEditorialReadinessResult ready() {
        return LegalEditorialReadinessResult.ready(observation(PUBLICATION_ID));
    }

    private static LegalEditorialReadinessObservation observation(UUID publicationId) {
        return new LegalEditorialReadinessObservation(
                Optional.of(publicationId),
                OBSERVED_AT,
                "sha256:" + "0".repeat(64),
                2,
                2,
                4,
                4,
                3,
                1,
                0);
    }

    private static LegalEditorialApplyReceipt receipt() {
        return new LegalEditorialApplyReceipt(
                LegalEditorialApplyReceipt.OperationType.PROMOTE,
                PUBLICATION_ID,
                TRANSACTION_AT,
                LegalEditorialReadiness.READY,
                2,
                2,
                4,
                4,
                3,
                1,
                0);
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
            LegalManifestStatus status,
            LegalEditorialApplyResult.Outcome outcome,
            LegalManifestIssueCode issueCode) {
        assertThat(result.status()).isEqualTo(status);
        assertThat(result.persisted()).isFalse();
        assertThat(result.outcome()).isEqualTo(outcome);
        assertThat(result.receipt()).isEmpty();
        assertThat(result.issues())
                .extracting(LegalManifestIssue::code)
                .containsExactly(issueCode);
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
        private final LegalEditorialMutationWriter writer =
                mock(LegalEditorialMutationWriter.class);
        private final LegalEditorialMutationWriter replacementWriter =
                mock(LegalEditorialMutationWriter.class);
        private final LegalEditorialMutationWriter retirementWriter =
                mock(LegalEditorialMutationWriter.class);
        private final LegalEditorialPostStateVerifier postStateVerifier =
                mock(LegalEditorialPostStateVerifier.class);
        private final LegalEditorialReadinessCore readinessCore =
                mock(LegalEditorialReadinessCore.class);
        private final LegalEditorialReplaceScopeGuard replaceScopeGuard =
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
            when(writer.usesJdbc(jdbc)).thenReturn(true);
            when(replacementWriter.usesJdbc(jdbc)).thenReturn(true);
            when(retirementWriter.usesJdbc(jdbc)).thenReturn(true);
            when(postStateVerifier.usesJdbc(jdbc)).thenReturn(true);
            when(readinessCore.usesJdbc(jdbc)).thenReturn(true);
            when(commitReconciler.usesJdbc(jdbc)).thenReturn(true);
        }

        private LegalEditorialApplyService service() {
            return new LegalEditorialApplyService(
                    gate,
                    jdbc,
                    planner,
                    writer,
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
