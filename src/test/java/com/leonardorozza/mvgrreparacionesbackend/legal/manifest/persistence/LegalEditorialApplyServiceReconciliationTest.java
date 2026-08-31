package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialPlanValidator.ValidatedEditorialPlan;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialReadiness;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidation;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LegalEditorialApplyServiceReconciliationTest {

    private static final Instant OBSERVED_AT =
            Instant.parse("2026-08-31T12:00:00.123456Z");
    private static final Instant TRANSACTION_AT =
            Instant.parse("2026-08-31T11:59:59.123456Z");
    private static final LegalEditorialTimeBoundary BOUNDARY =
            new LegalEditorialTimeBoundary(TRANSACTION_AT, OBSERVED_AT);
    private static final UUID PUBLICATION_ID =
            UUID.fromString("00000000-0000-0000-0000-0000000009c0");

    @AfterEach
    void clearTransactionState() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void unknownPostExactReconcilesOnceAfterUnwindAndReturnsOnlyDatabaseReceipt() {
        LegalEditorialFailureMapper failureMapper = mock(LegalEditorialFailureMapper.class);
        Harness harness = new Harness(failureMapper);
        ValidatedRelease target = mock(ValidatedRelease.class);
        LegalEditorialExecutionPlan plan = plan(
                LegalEditorialExecutionPlan.OperationType.PROMOTE,
                true);
        LegalEditorialApplyReceipt tentative = receipt(
                LegalEditorialApplyReceipt.OperationType.PROMOTE);
        LegalEditorialApplyReceipt confirmed = mock(LegalEditorialApplyReceipt.class);
        IllegalStateException originalFailure =
                new IllegalStateException("commit acknowledgement unavailable");
        executeAndComplete(
                harness.mutableGate,
                TransactionSynchronization.STATUS_UNKNOWN,
                originalFailure);
        stubFreshPromote(harness, target, plan, tentative);
        when(harness.commitReconciler.reconcilePromote(target, true))
                .thenAnswer(invocation -> {
                    assertPreviousTransactionUnbound(harness.dataSource);
                    return reconciledPost(confirmed);
                });

        LegalEditorialApplyResult result = harness.service().applyPromote(target);

        assertThat(result.status()).isEqualTo(LegalManifestStatus.PASS);
        assertThat(result.persisted()).isTrue();
        assertThat(result.outcome())
                .isEqualTo(LegalEditorialApplyResult.Outcome.ALREADY_APPLIED);
        assertThat(result.receipt()).containsSame(confirmed);
        assertThat(result.receipt().orElseThrow()).isNotSameAs(tentative);
        assertThat(result.issues()).isEmpty();
        verify(harness.mutableGate, times(1)).executeMutable(any());
        verify(harness.promotionWriter, times(1)).write(plan);
        verify(harness.commitReconciler, times(1)).reconcilePromote(target, true);
        verify(harness.commitReconciler, never()).reconcileReplace(any(), any(), anyBoolean());
        verify(harness.commitReconciler, never()).reconcileRetire(any(), any(), anyBoolean());
        verify(failureMapper, never()).map(any());
    }

    @Test
    void unknownAfterExactPlanAndWriterFailureUsesSourceExactAndOriginalThrowable() {
        LegalEditorialFailureMapper failureMapper = mock(LegalEditorialFailureMapper.class);
        Harness harness = new Harness(failureMapper);
        ValidatedRelease target = mock(ValidatedRelease.class);
        LegalEditorialExecutionPlan plan = plan(
                LegalEditorialExecutionPlan.OperationType.PROMOTE,
                true);
        LegalEditorialOperationalException originalFailure =
                new LegalEditorialOperationalException(
                        LegalManifestIssueCode.SCHEMA_DRIFT,
                        "database/schema");
        executeFailureAndComplete(
                harness.mutableGate,
                TransactionSynchronization.STATUS_UNKNOWN);
        when(harness.planner.planPromote(target, BOUNDARY))
                .thenReturn(LegalEditorialPlanResult.applicable(plan));
        doThrow(originalFailure).when(harness.promotionWriter).write(plan);
        when(harness.commitReconciler.reconcilePromote(target, true))
                .thenReturn(reconciledSource());
        when(failureMapper.map(originalFailure)).thenReturn(originalFailure.issue());

        LegalEditorialApplyResult result = harness.service().applyPromote(target);

        assertError(result, LegalManifestIssueCode.SCHEMA_DRIFT);
        verify(harness.promotionWriter, times(1)).write(plan);
        verify(harness.postStateVerifier, never()).verify(any());
        verify(harness.commitReconciler, times(1)).reconcilePromote(target, true);
        verify(failureMapper, times(1)).map(originalFailure);
    }

    @Test
    void sourceExactFallsBackForBlockedOrForeignMappedIssues() {
        LegalEditorialFailureMapper blockedMapper = mock(LegalEditorialFailureMapper.class);
        Harness blocked = new Harness(blockedMapper);
        ValidatedRelease blockedTarget = mock(ValidatedRelease.class);
        LegalEditorialExecutionPlan blockedPlan = plan(
                LegalEditorialExecutionPlan.OperationType.PROMOTE,
                true);
        LegalEditorialBlockedException blockedFailure = new LegalEditorialBlockedException(
                LegalManifestIssueCode.CURRENT_STATE_MISMATCH,
                "database/state");
        executeAndComplete(
                blocked.mutableGate,
                TransactionSynchronization.STATUS_UNKNOWN,
                blockedFailure);
        stubFreshPromote(blocked, blockedTarget, blockedPlan,
                receipt(LegalEditorialApplyReceipt.OperationType.PROMOTE));
        when(blocked.commitReconciler.reconcilePromote(blockedTarget, true))
                .thenReturn(reconciledSource());
        when(blockedMapper.map(blockedFailure)).thenReturn(blockedFailure.issue());

        assertError(
                blocked.service().applyPromote(blockedTarget),
                LegalManifestIssueCode.EDITORIAL_OBSERVATION_FAILED);

        LegalEditorialFailureMapper foreignMapper = mock(LegalEditorialFailureMapper.class);
        Harness foreign = new Harness(foreignMapper);
        ValidatedRelease foreignTarget = mock(ValidatedRelease.class);
        LegalEditorialExecutionPlan foreignPlan = plan(
                LegalEditorialExecutionPlan.OperationType.PROMOTE,
                true);
        IllegalStateException foreignFailure = new IllegalStateException("foreign failure");
        executeAndComplete(
                foreign.mutableGate,
                TransactionSynchronization.STATUS_UNKNOWN,
                foreignFailure);
        stubFreshPromote(foreign, foreignTarget, foreignPlan,
                receipt(LegalEditorialApplyReceipt.OperationType.PROMOTE));
        when(foreign.commitReconciler.reconcilePromote(foreignTarget, true))
                .thenReturn(reconciledSource());
        when(foreignMapper.map(foreignFailure)).thenReturn(LegalManifestIssue.at(
                LegalManifestIssueCode.MANIFEST_VALIDATION_ERROR,
                "manifest"));

        assertError(
                foreign.service().applyPromote(foreignTarget),
                LegalManifestIssueCode.EDITORIAL_OBSERVATION_FAILED);
    }

    @Test
    void missingExactPlanNeverInvokesTypedReconciliation() {
        Harness plannerFailure = new Harness();
        ValidatedRelease failedTarget = mock(ValidatedRelease.class);
        IllegalStateException failure = new IllegalStateException("plan unavailable");
        executeFailureAndComplete(
                plannerFailure.mutableGate,
                TransactionSynchronization.STATUS_UNKNOWN);
        when(plannerFailure.planner.planPromote(failedTarget, BOUNDARY))
                .thenThrow(failure);

        assertUnknown(plannerFailure.service().applyPromote(failedTarget));
        verifyNoReconciliation(plannerFailure);

        Harness crossOperation = new Harness();
        ValidatedRelease crossedTarget = mock(ValidatedRelease.class);
        LegalEditorialExecutionPlan replacePlan = plan(
                LegalEditorialExecutionPlan.OperationType.REPLACE,
                false);
        executeFailureAndComplete(
                crossOperation.mutableGate,
                TransactionSynchronization.STATUS_UNKNOWN);
        when(crossOperation.planner.planPromote(crossedTarget, BOUNDARY))
                .thenReturn(LegalEditorialPlanResult.applicable(replacePlan));

        assertUnknown(crossOperation.service().applyPromote(crossedTarget));
        verifyNoReconciliation(crossOperation);
    }

    @Test
    void inconclusiveNullOrBrokenReconciliationPreservesUnknownAndRedaction() {
        Harness inconclusive = unknownPromoteHarness();
        ValidatedRelease inconclusiveTarget = inconclusive.target;
        when(inconclusive.commitReconciler.reconcilePromote(inconclusiveTarget, true))
                .thenReturn(reconciledUnknown());
        assertUnknown(inconclusive.service().applyPromote(inconclusiveTarget));

        Harness nullResult = unknownPromoteHarness();
        ValidatedRelease nullTarget = nullResult.target;
        doReturn(null).when(nullResult.commitReconciler)
                .reconcilePromote(nullTarget, true);
        assertUnknown(nullResult.service().applyPromote(nullTarget));

        Harness runtimeFailure = unknownPromoteHarness();
        ValidatedRelease runtimeTarget = runtimeFailure.target;
        when(runtimeFailure.commitReconciler.reconcilePromote(runtimeTarget, true))
                .thenThrow(new IllegalStateException("reconciliation failed"));
        assertUnknown(runtimeFailure.service().applyPromote(runtimeTarget));

        Harness linkageFailure = unknownPromoteHarness();
        ValidatedRelease linkageTarget = linkageFailure.target;
        when(linkageFailure.commitReconciler.reconcilePromote(linkageTarget, true))
                .thenThrow(new NoClassDefFoundError("driver disappeared"));
        assertUnknown(linkageFailure.service().applyPromote(linkageTarget));

        for (Harness harness : List.of(
                inconclusive,
                nullResult,
                runtimeFailure,
                linkageFailure)) {
            verify(harness.commitReconciler, times(1))
                    .reconcilePromote(harness.target, true);
            verify(harness.commitReconciler, never())
                    .reconcileReplace(any(), any(), anyBoolean());
            verify(harness.commitReconciler, never())
                    .reconcileRetire(any(), any(), anyBoolean());
            verify(harness.failureMapper, never()).map(any());
            verify(harness.promotionWriter, times(1)).write(any());
            verify(harness.mutableGate, times(1)).executeMutable(any());
        }
    }

    @Test
    void replaceAndRetireUseTheirProtectedTypedInputs() {
        Harness replace = new Harness();
        ValidatedRelease replaceTarget = mock(ValidatedRelease.class);
        ValidatedEditorialPlan rawPlan = mock(ValidatedEditorialPlan.class);
        ValidatedEditorialPlan supportedPlan = mock(ValidatedEditorialPlan.class);
        LegalEditorialExecutionPlan replaceExecution = plan(
                LegalEditorialExecutionPlan.OperationType.REPLACE,
                true);
        LegalEditorialApplyReceipt replaceConfirmed = mock(LegalEditorialApplyReceipt.class);
        when(replace.replaceScopeGuard.validate(rawPlan))
                .thenReturn(LegalManifestValidation.pass(supportedPlan));
        when(replace.planner.planReplace(replaceTarget, supportedPlan, BOUNDARY))
                .thenReturn(LegalEditorialPlanResult.applicable(replaceExecution));
        when(replace.postStateVerifier.verify(replaceExecution)).thenReturn(
                receipt(LegalEditorialApplyReceipt.OperationType.REPLACE));
        when(replace.readinessCore.evaluate(replaceTarget, OBSERVED_AT)).thenReturn(ready());
        executeAndComplete(
                replace.mutableGate,
                TransactionSynchronization.STATUS_UNKNOWN,
                new IllegalStateException("replace commit unknown"));
        when(replace.commitReconciler.reconcileReplace(
                replaceTarget,
                supportedPlan,
                true)).thenReturn(reconciledPost(replaceConfirmed));

        LegalEditorialApplyResult replaceResult = replace.service()
                .applyReplace(replaceTarget, rawPlan);

        assertThat(replaceResult.outcome())
                .isEqualTo(LegalEditorialApplyResult.Outcome.ALREADY_APPLIED);
        assertThat(replaceResult.receipt()).containsSame(replaceConfirmed);
        verify(replace.commitReconciler).reconcileReplace(
                replaceTarget,
                supportedPlan,
                true);
        verify(replace.commitReconciler, never()).reconcileReplace(
                replaceTarget,
                rawPlan,
                true);
        verify(replace.replacementWriter, times(1)).write(replaceExecution);

        Harness retire = new Harness();
        ValidatedRelease current = mock(ValidatedRelease.class);
        ValidatedEditorialPlan retirementPlan = mock(ValidatedEditorialPlan.class);
        LegalEditorialExecutionPlan retireExecution = plan(
                LegalEditorialExecutionPlan.OperationType.RETIRE,
                true);
        LegalEditorialApplyReceipt retireConfirmed = mock(LegalEditorialApplyReceipt.class);
        when(retire.planner.planRetire(current, retirementPlan, BOUNDARY))
                .thenReturn(LegalEditorialPlanResult.applicable(retireExecution));
        when(retire.postStateVerifier.verify(retireExecution)).thenReturn(
                receipt(LegalEditorialApplyReceipt.OperationType.RETIRE));
        when(retire.readinessCore.evaluate(current, OBSERVED_AT)).thenReturn(notReady());
        executeAndComplete(
                retire.mutableGate,
                TransactionSynchronization.STATUS_UNKNOWN,
                new IllegalStateException("retire commit unknown"));
        when(retire.commitReconciler.reconcileRetire(
                current,
                retirementPlan,
                true)).thenReturn(reconciledPost(retireConfirmed));

        LegalEditorialApplyResult retireResult = retire.service()
                .applyRetire(current, retirementPlan);

        assertThat(retireResult.outcome())
                .isEqualTo(LegalEditorialApplyResult.Outcome.ALREADY_APPLIED);
        assertThat(retireResult.receipt()).containsSame(retireConfirmed);
        verify(retire.commitReconciler).reconcileRetire(current, retirementPlan, true);
        verify(retire.retirementWriter, times(1)).write(retireExecution);
    }

    private static Harness unknownPromoteHarness() {
        LegalEditorialFailureMapper failureMapper = mock(LegalEditorialFailureMapper.class);
        Harness harness = new Harness(failureMapper);
        LegalEditorialExecutionPlan plan = plan(
                LegalEditorialExecutionPlan.OperationType.PROMOTE,
                true);
        executeAndComplete(
                harness.mutableGate,
                TransactionSynchronization.STATUS_UNKNOWN,
                new IllegalStateException("commit unknown"));
        stubFreshPromote(
                harness,
                harness.target,
                plan,
                receipt(LegalEditorialApplyReceipt.OperationType.PROMOTE));
        return harness;
    }

    private static void stubFreshPromote(
            Harness harness,
            ValidatedRelease target,
            LegalEditorialExecutionPlan plan,
            LegalEditorialApplyReceipt tentative) {
        when(harness.planner.planPromote(target, BOUNDARY))
                .thenReturn(LegalEditorialPlanResult.applicable(plan));
        when(harness.postStateVerifier.verify(plan)).thenReturn(tentative);
        when(harness.readinessCore.evaluate(target, OBSERVED_AT)).thenReturn(ready());
    }

    private static LegalEditorialExecutionPlan plan(
            LegalEditorialExecutionPlan.OperationType operation,
            boolean changeRequired) {
        LegalEditorialExecutionPlan plan = mock(LegalEditorialExecutionPlan.class);
        when(plan.operationType()).thenReturn(operation);
        when(plan.changeRequired()).thenReturn(changeRequired);
        when(plan.target()).thenReturn(new LegalEditorialExecutionPlan.PublicationIdentity(
                "publication-9c",
                PUBLICATION_ID,
                "a".repeat(64)));
        when(plan.transactionAt()).thenReturn(TRANSACTION_AT);
        when(plan.observedAt()).thenReturn(OBSERVED_AT);
        when(plan.expectedAppliedAt()).thenReturn(TRANSACTION_AT);
        when(plan.expectedReadinessAfter()).thenReturn(
                operation == LegalEditorialExecutionPlan.OperationType.RETIRE
                        ? LegalEditorialReadiness.NOT_READY
                        : LegalEditorialReadiness.READY);
        when(plan.acknowledgeFailClosedGap()).thenReturn(
                operation == LegalEditorialExecutionPlan.OperationType.RETIRE);
        return plan;
    }

    private static LegalEditorialApplyReceipt receipt(
            LegalEditorialApplyReceipt.OperationType operation) {
        return new LegalEditorialApplyReceipt(
                operation,
                PUBLICATION_ID,
                TRANSACTION_AT,
                operation == LegalEditorialApplyReceipt.OperationType.RETIRE
                        ? LegalEditorialReadiness.NOT_READY
                        : LegalEditorialReadiness.READY,
                2,
                2,
                4,
                4,
                3,
                1,
                operation == LegalEditorialApplyReceipt.OperationType.REPLACE ? 1 : 0);
    }

    private static LegalEditorialReadinessObservation observation() {
        return new LegalEditorialReadinessObservation(
                Optional.of(PUBLICATION_ID),
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

    private static LegalEditorialReadinessResult ready() {
        return LegalEditorialReadinessResult.ready(observation());
    }

    private static LegalEditorialReadinessResult notReady() {
        return LegalEditorialReadinessResult.notReady(
                observation(),
                List.of(LegalManifestIssue.at(
                        LegalManifestIssueCode.CURRENT_STATE_MISMATCH,
                        "database/state")));
    }

    private static LegalEditorialCommitReconciler.Result reconciledPost(
            LegalEditorialApplyReceipt receipt) {
        return new LegalEditorialCommitReconciler.Result(
                LegalEditorialCommitReconciler.Outcome.POST_EXACT,
                Optional.of(receipt));
    }

    private static LegalEditorialCommitReconciler.Result reconciledSource() {
        return new LegalEditorialCommitReconciler.Result(
                LegalEditorialCommitReconciler.Outcome.SOURCE_EXACT,
                Optional.empty());
    }

    private static LegalEditorialCommitReconciler.Result reconciledUnknown() {
        return new LegalEditorialCommitReconciler.Result(
                LegalEditorialCommitReconciler.Outcome.UNKNOWN,
                Optional.empty());
    }

    private static void assertUnknown(LegalEditorialApplyResult result) {
        assertThat(result.status()).isEqualTo(LegalManifestStatus.ERROR);
        assertThat(result.persisted()).isNull();
        assertThat(result.outcome()).isEqualTo(LegalEditorialApplyResult.Outcome.UNKNOWN);
        assertThat(result.receipt()).isEmpty();
        assertThat(result.operationType()).isEmpty();
        assertThat(result.targetPublicationUuid()).isEmpty();
        assertThat(result.appliedAt()).isEmpty();
        assertThat(result.readinessAfter()).isEmpty();
        assertThat(result.issues())
                .extracting(LegalManifestIssue::code)
                .containsExactly(LegalManifestIssueCode.COMMIT_OUTCOME_UNKNOWN);
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

    private static void verifyNoReconciliation(Harness harness) {
        verify(harness.commitReconciler, never()).reconcilePromote(any(), anyBoolean());
        verify(harness.commitReconciler, never()).reconcileReplace(any(), any(), anyBoolean());
        verify(harness.commitReconciler, never()).reconcileRetire(any(), any(), anyBoolean());
    }

    private static void assertPreviousTransactionUnbound(DataSource dataSource) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
        assertThat(TransactionSynchronizationManager.hasResource(dataSource)).isFalse();
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
                throwTerminal(terminalFailure);
                return value;
            } finally {
                TransactionSynchronizationManager.clear();
            }
        });
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void executeFailureAndComplete(
            LegalManifestDatabaseGate gate,
            int completion) {
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
                        .forEach(sync -> sync.afterCompletion(completion));
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

    private static void throwTerminal(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof LinkageError linkageFailure) {
            throw linkageFailure;
        }
        throw new AssertionError("El test requiere un fallo terminal no comprobado", failure);
    }

    private static final class Harness {
        private final DataSource dataSource = mock(DataSource.class);
        private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        private final LegalManifestDatabaseGate mutableGate =
                mock(LegalManifestDatabaseGate.class);
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
        private final LegalEditorialCommitReconciler commitReconciler =
                mock(LegalEditorialCommitReconciler.class);
        private final LegalEditorialFailureMapper failureMapper;
        private final LegalEditorialSchemaVerifier schemaVerifier =
                mock(LegalEditorialSchemaVerifier.class);
        private final LegalEditorialPrivilegeVerifier privilegeVerifier =
                mock(LegalEditorialPrivilegeVerifier.class);
        private final ValidatedRelease target = mock(ValidatedRelease.class);

        private Harness() {
            this(mock(LegalEditorialFailureMapper.class));
        }

        private Harness(LegalEditorialFailureMapper failureMapper) {
            this.failureMapper = failureMapper;
            when(jdbc.getDataSource()).thenReturn(dataSource);
            when(mutableGate.usesJdbc(jdbc)).thenReturn(true);
            when(planner.usesJdbc(jdbc)).thenReturn(true);
            when(promotionWriter.usesJdbc(jdbc)).thenReturn(true);
            when(replacementWriter.usesJdbc(jdbc)).thenReturn(true);
            when(retirementWriter.usesJdbc(jdbc)).thenReturn(true);
            when(postStateVerifier.usesJdbc(jdbc)).thenReturn(true);
            when(readinessCore.usesJdbc(jdbc)).thenReturn(true);
            when(commitReconciler.usesJdbc(jdbc)).thenReturn(true);
        }

        private LegalEditorialApplyService service() {
            return new LegalEditorialApplyService(
                    mutableGate,
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
