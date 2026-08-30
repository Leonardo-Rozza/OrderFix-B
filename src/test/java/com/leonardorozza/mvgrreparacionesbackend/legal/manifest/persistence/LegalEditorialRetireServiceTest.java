package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialPlanValidator.ValidatedEditorialPlan;
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
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LegalEditorialRetireServiceTest {

    private static final Instant OBSERVED_AT =
            Instant.parse("2026-08-30T12:00:00.123456Z");
    private static final Instant HISTORICAL_APPLIED_AT =
            Instant.parse("2026-08-29T12:00:00.123456Z");
    private static final UUID PUBLICATION_ID =
            UUID.fromString("0f54d580-8f6b-44ff-a354-ec042f60e5f2");

    @AfterEach
    void clearSynchronization() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void freshRetireCommitsOnlyAfterTheExactNotReadyPostcondition() {
        Harness harness = new Harness();
        when(harness.promotionWriter.usesJdbc(harness.jdbc)).thenReturn(false);
        when(harness.replacementWriter.usesJdbc(harness.jdbc)).thenReturn(false);
        executeAndComplete(harness.gate, TransactionSynchronization.STATUS_COMMITTED);
        ValidatedRelease release = mock(ValidatedRelease.class);
        ValidatedEditorialPlan editorialPlan = mock(ValidatedEditorialPlan.class);
        LegalEditorialExecutionPlan plan = plan(true, OBSERVED_AT);
        LegalEditorialApplyReceipt receipt = receipt(OBSERVED_AT);
        when(harness.planner.planRetire(release, editorialPlan, OBSERVED_AT))
                .thenReturn(LegalEditorialPlanResult.applicable(plan));
        when(harness.postStateVerifier.verify(plan)).thenReturn(receipt);
        when(harness.readinessCore.evaluate(release, OBSERVED_AT))
                .thenReturn(notReady());

        LegalEditorialApplyResult result = harness.service()
                .applyRetire(release, editorialPlan);

        assertSuccess(result, LegalEditorialApplyResult.Outcome.APPLIED, receipt);
        verify(harness.gate, times(1)).executeMutable(any());
        verify(harness.jdbc, times(1)).queryForObject(
                "SELECT transaction_timestamp()",
                OffsetDateTime.class);
        InOrder order = inOrder(
                harness.planner,
                harness.retirementWriter,
                harness.postStateVerifier,
                harness.jdbc,
                harness.readinessCore);
        order.verify(harness.planner).planRetire(release, editorialPlan, OBSERVED_AT);
        order.verify(harness.retirementWriter).write(plan);
        order.verify(harness.postStateVerifier).verify(plan);
        order.verify(harness.jdbc).execute("SET CONSTRAINTS ALL IMMEDIATE");
        order.verify(harness.readinessCore).evaluate(release, OBSERVED_AT);
        verify(harness.promotionWriter, never()).usesJdbc(harness.jdbc);
        verify(harness.promotionWriter, never()).write(any());
        verify(harness.replacementWriter, never()).usesJdbc(harness.jdbc);
        verify(harness.replacementWriter, never()).write(any());
    }

    @Test
    void exactRetireReplayIsSelectOnlyAndPreservesHistoricalAppliedAt() {
        Harness harness = new Harness();
        executeAndComplete(harness.gate, TransactionSynchronization.STATUS_COMMITTED);
        ValidatedRelease release = mock(ValidatedRelease.class);
        ValidatedEditorialPlan editorialPlan = mock(ValidatedEditorialPlan.class);
        LegalEditorialExecutionPlan plan = plan(false, HISTORICAL_APPLIED_AT);
        LegalEditorialApplyReceipt receipt = receipt(HISTORICAL_APPLIED_AT);
        when(harness.planner.planRetire(release, editorialPlan, OBSERVED_AT))
                .thenReturn(LegalEditorialPlanResult.applicable(plan));
        when(harness.postStateVerifier.verify(plan)).thenReturn(receipt);

        LegalEditorialApplyResult result = harness.service()
                .applyRetire(release, editorialPlan);

        assertSuccess(
                result,
                LegalEditorialApplyResult.Outcome.ALREADY_APPLIED,
                receipt);
        assertThat(result.appliedAt()).contains(HISTORICAL_APPLIED_AT);
        verify(harness.promotionWriter, never()).write(any());
        verify(harness.replacementWriter, never()).write(any());
        verify(harness.retirementWriter, never()).write(any());
        verify(harness.postStateVerifier).verify(plan);
        verify(harness.jdbc, never()).execute(anyString());
        verify(harness.readinessCore, never()).evaluate(any(), any());
    }

    @Test
    void expectedReadinessMismatchRemainsATypedBlockerBeforeAnyDml() {
        Harness harness = new Harness();
        executeAndRollbackOnFailure(harness.gate);
        ValidatedRelease release = mock(ValidatedRelease.class);
        ValidatedEditorialPlan editorialPlan = mock(ValidatedEditorialPlan.class);
        LegalEditorialExecutionPlan plan = plan(false, HISTORICAL_APPLIED_AT);
        when(plan.expectedReadinessAfter()).thenReturn(LegalEditorialReadiness.READY);
        when(harness.planner.planRetire(release, editorialPlan, OBSERVED_AT))
                .thenReturn(LegalEditorialPlanResult.applicable(plan));

        LegalEditorialApplyResult result = harness.service()
                .applyRetire(release, editorialPlan);

        assertBlocked(result, LegalManifestIssueCode.EXPECTED_READINESS_MISMATCH);
        assertNoPostPlanningWork(harness);
    }

    @Test
    void missingFailClosedAcknowledgementRemainsATypedBlockerBeforeAnyDml() {
        Harness harness = new Harness();
        executeAndRollbackOnFailure(harness.gate);
        ValidatedRelease release = mock(ValidatedRelease.class);
        ValidatedEditorialPlan editorialPlan = mock(ValidatedEditorialPlan.class);
        LegalEditorialExecutionPlan plan = plan(false, HISTORICAL_APPLIED_AT);
        when(plan.acknowledgeFailClosedGap()).thenReturn(false);
        when(harness.planner.planRetire(release, editorialPlan, OBSERVED_AT))
                .thenReturn(LegalEditorialPlanResult.applicable(plan));

        LegalEditorialApplyResult result = harness.service()
                .applyRetire(release, editorialPlan);

        assertBlocked(
                result,
                LegalManifestIssueCode.FAIL_CLOSED_GAP_NOT_ACKNOWLEDGED);
        assertNoPostPlanningWork(harness);
    }

    @Test
    void aPlannerResultOfAnotherOperationFailsClosedBeforeWriterOrVerifier() {
        Harness harness = new Harness();
        executeAndRollbackOnFailure(harness.gate);
        ValidatedRelease release = mock(ValidatedRelease.class);
        ValidatedEditorialPlan editorialPlan = mock(ValidatedEditorialPlan.class);
        LegalEditorialExecutionPlan plan = plan(false, HISTORICAL_APPLIED_AT);
        when(plan.operationType())
                .thenReturn(LegalEditorialExecutionPlan.OperationType.REPLACE);
        when(harness.planner.planRetire(release, editorialPlan, OBSERVED_AT))
                .thenReturn(LegalEditorialPlanResult.applicable(plan));

        LegalEditorialApplyResult result = harness.service()
                .applyRetire(release, editorialPlan);

        assertError(result, LegalManifestIssueCode.EDITORIAL_OBSERVATION_FAILED);
        assertNoPostPlanningWork(harness);
    }

    @Test
    void readyAfterRetireConstraintsRollsBackTheCompleteDelta() {
        Harness harness = new Harness();
        executeAndRollbackOnFailure(harness.gate);
        ValidatedRelease release = mock(ValidatedRelease.class);
        ValidatedEditorialPlan editorialPlan = mock(ValidatedEditorialPlan.class);
        LegalEditorialExecutionPlan plan = plan(true, OBSERVED_AT);
        when(harness.planner.planRetire(release, editorialPlan, OBSERVED_AT))
                .thenReturn(LegalEditorialPlanResult.applicable(plan));
        when(harness.postStateVerifier.verify(plan)).thenReturn(receipt(OBSERVED_AT));
        when(harness.readinessCore.evaluate(release, OBSERVED_AT))
                .thenReturn(LegalEditorialReadinessResult.ready(observation()));

        LegalEditorialApplyResult result = harness.service()
                .applyRetire(release, editorialPlan);

        assertError(result, LegalManifestIssueCode.POSTCONDITION_NOT_READY);
        verify(harness.retirementWriter).write(plan);
        verify(harness.postStateVerifier).verify(plan);
        verify(harness.jdbc).execute("SET CONSTRAINTS ALL IMMEDIATE");
        verify(harness.readinessCore).evaluate(release, OBSERVED_AT);
    }

    @Test
    void readinessOperationalErrorBecomesTheStableRetirePostconditionAndRollsBack() {
        Harness harness = new Harness();
        executeAndRollbackOnFailure(harness.gate);
        ValidatedRelease release = mock(ValidatedRelease.class);
        ValidatedEditorialPlan editorialPlan = mock(ValidatedEditorialPlan.class);
        LegalEditorialExecutionPlan plan = plan(true, OBSERVED_AT);
        when(harness.planner.planRetire(release, editorialPlan, OBSERVED_AT))
                .thenReturn(LegalEditorialPlanResult.applicable(plan));
        when(harness.postStateVerifier.verify(plan)).thenReturn(receipt(OBSERVED_AT));
        when(harness.readinessCore.evaluate(release, OBSERVED_AT)).thenReturn(
                LegalEditorialReadinessResult.error(List.of(LegalManifestIssue.at(
                        LegalManifestIssueCode.CONCURRENT_OPERATION,
                        "database/concurrency"))));

        LegalEditorialApplyResult result = harness.service()
                .applyRetire(release, editorialPlan);

        assertError(result, LegalManifestIssueCode.POSTCONDITION_NOT_READY);
        verify(harness.retirementWriter).write(plan);
        verify(harness.jdbc).execute("SET CONSTRAINTS ALL IMMEDIATE");
    }

    @Test
    void unknownRetireCompletionNeverLeaksTheTentativeReceipt() {
        Harness harness = new Harness();
        executeAndComplete(
                harness.gate,
                TransactionSynchronization.STATUS_UNKNOWN,
                new IllegalStateException("connection outcome unknown"));
        ValidatedRelease release = mock(ValidatedRelease.class);
        ValidatedEditorialPlan editorialPlan = mock(ValidatedEditorialPlan.class);
        LegalEditorialExecutionPlan plan = plan(true, OBSERVED_AT);
        when(harness.planner.planRetire(release, editorialPlan, OBSERVED_AT))
                .thenReturn(LegalEditorialPlanResult.applicable(plan));
        when(harness.postStateVerifier.verify(plan)).thenReturn(receipt(OBSERVED_AT));
        when(harness.readinessCore.evaluate(release, OBSERVED_AT)).thenReturn(notReady());

        LegalEditorialApplyResult result = harness.service()
                .applyRetire(release, editorialPlan);

        assertThat(result.status()).isEqualTo(LegalManifestStatus.ERROR);
        assertThat(result.persisted()).isNull();
        assertThat(result.outcome()).isEqualTo(LegalEditorialApplyResult.Outcome.UNKNOWN);
        assertThat(result.receipt()).isEmpty();
        assertThat(result.targetPublicationUuid()).isEmpty();
        assertThat(result.appliedAt()).isEmpty();
        assertThat(result.issues())
                .extracting(LegalManifestIssue::code)
                .containsExactly(LegalManifestIssueCode.COMMIT_OUTCOME_UNKNOWN);
    }

    @Test
    void foreignRetirementWriterFailsBeforeOpeningTheTransaction() {
        Harness harness = new Harness();
        when(harness.retirementWriter.usesJdbc(harness.jdbc)).thenReturn(false);

        LegalEditorialApplyResult result = harness.service().applyRetire(
                mock(ValidatedRelease.class),
                mock(ValidatedEditorialPlan.class));

        assertError(result, LegalManifestIssueCode.EDITORIAL_OBSERVATION_FAILED);
        verify(harness.gate, never()).executeMutable(any());
        verify(harness.jdbc, never()).queryForObject(anyString(), any(Class.class));
        verify(harness.promotionWriter, never()).usesJdbc(any());
        verify(harness.replacementWriter, never()).usesJdbc(any());
    }

    private static LegalEditorialExecutionPlan plan(
            boolean changeRequired,
            Instant expectedAppliedAt) {
        LegalEditorialExecutionPlan plan = mock(LegalEditorialExecutionPlan.class);
        when(plan.changeRequired()).thenReturn(changeRequired);
        when(plan.operationType()).thenReturn(
                LegalEditorialExecutionPlan.OperationType.RETIRE);
        when(plan.target()).thenReturn(new LegalEditorialExecutionPlan.PublicationIdentity(
                "publication-retire",
                PUBLICATION_ID,
                "a".repeat(64)));
        when(plan.observedAt()).thenReturn(OBSERVED_AT);
        when(plan.expectedAppliedAt()).thenReturn(expectedAppliedAt);
        when(plan.expectedReadinessAfter())
                .thenReturn(LegalEditorialReadiness.NOT_READY);
        when(plan.acknowledgeFailClosedGap()).thenReturn(true);
        return plan;
    }

    private static LegalEditorialReadinessResult notReady() {
        return LegalEditorialReadinessResult.notReady(
                observation(),
                List.of(LegalManifestIssue.at(
                        LegalManifestIssueCode.CURRENT_STATE_MISMATCH,
                        "database/state")));
    }

    private static LegalEditorialReadinessObservation observation() {
        return new LegalEditorialReadinessObservation(
                Optional.of(PUBLICATION_ID),
                OBSERVED_AT,
                "sha256:" + "0".repeat(64),
                4,
                3,
                9,
                7,
                2,
                1,
                1);
    }

    private static LegalEditorialApplyReceipt receipt(Instant appliedAt) {
        return new LegalEditorialApplyReceipt(
                LegalEditorialApplyReceipt.OperationType.RETIRE,
                PUBLICATION_ID,
                appliedAt,
                LegalEditorialReadiness.NOT_READY,
                4,
                3,
                9,
                7,
                2,
                1,
                1);
    }

    private static void assertSuccess(
            LegalEditorialApplyResult result,
            LegalEditorialApplyResult.Outcome outcome,
            LegalEditorialApplyReceipt receipt) {
        assertThat(result.status()).isEqualTo(LegalManifestStatus.PASS);
        assertThat(result.persisted()).isTrue();
        assertThat(result.outcome()).isEqualTo(outcome);
        assertThat(result.receipt()).contains(receipt);
        assertThat(result.readinessAfter()).contains(LegalEditorialReadiness.NOT_READY);
        assertThat(result.issues()).isEmpty();
    }

    private static void assertBlocked(
            LegalEditorialApplyResult result,
            LegalManifestIssueCode issueCode) {
        assertThat(result.status()).isEqualTo(LegalManifestStatus.BLOCKED);
        assertThat(result.persisted()).isFalse();
        assertThat(result.outcome()).isEqualTo(LegalEditorialApplyResult.Outcome.BLOCKED);
        assertThat(result.receipt()).isEmpty();
        assertThat(result.issues())
                .extracting(LegalManifestIssue::code)
                .containsExactly(issueCode);
    }

    private static void assertError(
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

    private static void assertNoPostPlanningWork(Harness harness) {
        verify(harness.promotionWriter, never()).write(any());
        verify(harness.replacementWriter, never()).write(any());
        verify(harness.retirementWriter, never()).write(any());
        verify(harness.postStateVerifier, never()).verify(any());
        verify(harness.jdbc, never()).execute(anyString());
        verify(harness.readinessCore, never()).evaluate(any(), any());
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
            TransactionCallback callback = invocation.getArgument(0);
            beginSynchronizedTransaction();
            try {
                Object value = callback.doInTransaction(new SimpleTransactionStatus());
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
            TransactionCallback callback = invocation.getArgument(0);
            beginSynchronizedTransaction();
            try {
                return callback.doInTransaction(new SimpleTransactionStatus());
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
        private final LegalEditorialReplaceScopeGuard replaceScopeGuard =
                mock(LegalEditorialReplaceScopeGuard.class);
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
            when(jdbc.queryForObject(
                    "SELECT transaction_timestamp()",
                    OffsetDateTime.class)).thenReturn(OffsetDateTime.ofInstant(
                            OBSERVED_AT,
                            ZoneOffset.UTC));
        }

        private LegalEditorialApplyService service() {
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
                    failureMapper,
                    schemaVerifier,
                    privilegeVerifier);
        }
    }
}
