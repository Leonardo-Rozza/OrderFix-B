package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialPlanValidator.ValidatedEditorialPlan;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialReadiness;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LegalEditorialCommitReconcilerTest {

    private static final Instant OBSERVED_AT =
            Instant.parse("2026-08-31T12:00:00.123456Z");

    @AfterEach
    void clearThreadTransactionState() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void constructorIsTheOnlyAssemblyPathAndAccreditsTheCompleteReadOnlyGraph() {
        Harness harness = new Harness();

        LegalEditorialCommitReconciler reconciler = harness.reconciler();

        assertThat(LegalEditorialCommitReconciler.class.getDeclaredConstructors())
                .singleElement()
                .satisfies(constructor -> {
                    assertThat(constructor.getParameterCount()).isEqualTo(6);
                    assertThat(constructor.getModifiers() & Modifier.PUBLIC).isZero();
                });
        verify(harness.gate).requireExactEditorialReconciliationBoundary(
                harness.jdbc,
                harness.schemaVerifier,
                harness.privilegeVerifier);
        assertThat(reconciler.usesJdbc(harness.jdbc)).isTrue();
        verify(harness.gate, times(2)).usesJdbc(harness.jdbc);
        verify(harness.planner, times(2)).usesJdbc(harness.jdbc);
        verify(harness.postStateVerifier, times(2)).usesJdbc(harness.jdbc);

        List<Class<?>> dependencyTypes = Stream.concat(
                Stream.concat(
                        Arrays.stream(LegalEditorialCommitReconciler.class.getDeclaredFields())
                                .map(field -> field.getType()),
                        Arrays.stream(LegalEditorialCommitReconciler.class
                                        .getDeclaredConstructors())
                                .flatMap(constructor -> Arrays.stream(
                                        constructor.getParameterTypes()))),
                Arrays.stream(LegalEditorialCommitReconciler.class.getDeclaredMethods())
                        .filter(method -> !method.isSynthetic())
                        .flatMap(method -> Arrays.stream(method.getParameterTypes())))
                .toList();
        assertThat(dependencyTypes)
                .noneMatch(LegalEditorialMutationWriter.class::isAssignableFrom);
        assertThat(dependencyTypes)
                .noneMatch(LegalEditorialFailureMapper.class::isAssignableFrom);
        assertThat(dependencyTypes)
                .noneMatch(LegalEditorialApplyReceipt.class::isAssignableFrom);
        assertThat(dependencyTypes)
                .noneMatch(LegalEditorialExecutionPlan.class::isAssignableFrom);
        assertThat(dependencyTypes).doesNotContain(Instant.class, OffsetDateTime.class);
    }

    @Test
    void reconcilerSourceAndCompleteSignatureRemainSelectOnly() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialCommitReconciler.java"));

        assertThat(source)
                .contains("SELECT transaction_timestamp()")
                .doesNotContain(
                        "LegalEditorialMutationWriter",
                        "LegalInitialPromotionCore",
                        "LegalDocumentReplacementWriter",
                        "LegalEditorialRetirementWriter",
                        "LegalEditorialApplyService",
                        "LegalEditorialFailureMapper",
                        "Instant.now(",
                        ".operationId()",
                        ".planSha256()",
                        ".manifestSha256()",
                        ".expectedAppliedAt()",
                        ".apply(",
                        ".update(",
                        "batchUpdate(",
                        ".execute(",
                        "FOR UPDATE",
                        "SET CONSTRAINTS");
        assertThat(source.toUpperCase(Locale.ROOT))
                .doesNotContain("\"INSERT ", "\"UPDATE ", "\"DELETE ", "\"MERGE ");
    }

    @Test
    void constructorFailsClosedForAnUnaccreditedBoundaryOrForeignJdbcParticipant() {
        Harness unsafeBoundary = new Harness();
        IllegalArgumentException boundaryFailure =
                new IllegalArgumentException("mutable gate");
        doThrow(boundaryFailure)
                .when(unsafeBoundary.gate)
                .requireExactEditorialReconciliationBoundary(
                        unsafeBoundary.jdbc,
                        unsafeBoundary.schemaVerifier,
                        unsafeBoundary.privilegeVerifier);
        assertThatThrownBy(unsafeBoundary::reconciler).isSameAs(boundaryFailure);

        Harness foreignGate = new Harness();
        when(foreignGate.gate.usesJdbc(foreignGate.jdbc)).thenReturn(false);
        assertThatThrownBy(foreignGate::reconciler)
                .isInstanceOf(IllegalArgumentException.class);

        Harness foreignPlanner = new Harness();
        when(foreignPlanner.planner.usesJdbc(foreignPlanner.jdbc)).thenReturn(false);
        assertThatThrownBy(foreignPlanner::reconciler)
                .isInstanceOf(IllegalArgumentException.class);

        Harness foreignVerifier = new Harness();
        when(foreignVerifier.postStateVerifier.usesJdbc(foreignVerifier.jdbc))
                .thenReturn(false);
        assertThatThrownBy(foreignVerifier::reconciler)
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void freshPromotePlanAccreditsSourceExactWithoutInvokingTheVerifier() {
        Harness harness = new Harness();
        ValidatedRelease release = mock(ValidatedRelease.class);
        LegalEditorialExecutionPlan plan = executionPlan(
                LegalEditorialExecutionPlan.OperationType.PROMOTE,
                true);
        when(harness.planner.planPromote(release, OBSERVED_AT))
                .thenAnswer(invocation -> {
                    assertThat(harness.boundaryActive).isTrue();
                    return LegalEditorialPlanResult.applicable(plan);
                });

        LegalEditorialCommitReconciler.Result result =
                harness.reconciler().reconcilePromote(release, true);

        assertThat(result.outcome())
                .isEqualTo(LegalEditorialCommitReconciler.Outcome.SOURCE_EXACT);
        assertThat(result.receipt()).isEmpty();
        verify(harness.gate, times(1)).executeEditorialReconciliation(any());
        verify(harness.jdbc, times(1)).queryForObject(
                "SELECT transaction_timestamp()",
                OffsetDateTime.class);
        verify(harness.planner, times(1)).planPromote(release, OBSERVED_AT);
        verify(harness.postStateVerifier, never()).verify(any());
    }

    @Test
    void replaceAndRetireUseTheirTypedInputsAndOneFreshTimestampEach() {
        Harness replaceHarness = new Harness();
        Harness retireHarness = new Harness();
        ValidatedRelease release = mock(ValidatedRelease.class);
        ValidatedEditorialPlan editorialPlan = mock(ValidatedEditorialPlan.class);
        LegalEditorialExecutionPlan freshReplace = executionPlan(
                LegalEditorialExecutionPlan.OperationType.REPLACE,
                true);
        LegalEditorialExecutionPlan freshRetire = executionPlan(
                LegalEditorialExecutionPlan.OperationType.RETIRE,
                true);
        when(replaceHarness.planner.planReplace(release, editorialPlan, OBSERVED_AT))
                .thenReturn(LegalEditorialPlanResult.applicable(freshReplace));
        when(retireHarness.planner.planRetire(release, editorialPlan, OBSERVED_AT))
                .thenReturn(LegalEditorialPlanResult.applicable(freshRetire));

        LegalEditorialCommitReconciler.Result replace = replaceHarness.reconciler()
                .reconcileReplace(release, editorialPlan, true);
        LegalEditorialCommitReconciler.Result retire = retireHarness.reconciler()
                .reconcileRetire(release, editorialPlan, true);

        assertThat(replace.outcome())
                .isEqualTo(LegalEditorialCommitReconciler.Outcome.SOURCE_EXACT);
        assertThat(retire.outcome())
                .isEqualTo(LegalEditorialCommitReconciler.Outcome.SOURCE_EXACT);
        verify(replaceHarness.planner).planReplace(release, editorialPlan, OBSERVED_AT);
        verify(retireHarness.planner).planRetire(release, editorialPlan, OBSERVED_AT);
        verify(replaceHarness.jdbc).queryForObject(
                "SELECT transaction_timestamp()",
                OffsetDateTime.class);
        verify(retireHarness.jdbc).queryForObject(
                "SELECT transaction_timestamp()",
                OffsetDateTime.class);
    }

    @Test
    void verifiedReplayAccreditsPostExactWithOnlyTheDatabaseReceipt() {
        Harness harness = new Harness();
        ValidatedRelease release = mock(ValidatedRelease.class);
        LegalEditorialExecutionPlan replay = executionPlan(
                LegalEditorialExecutionPlan.OperationType.PROMOTE,
                false);
        LegalEditorialApplyReceipt receipt = receipt();
        when(harness.planner.planPromote(release, OBSERVED_AT))
                .thenAnswer(invocation -> {
                    assertThat(harness.boundaryActive).isTrue();
                    return LegalEditorialPlanResult.applicable(replay);
                });
        when(harness.postStateVerifier.verify(replay)).thenAnswer(invocation -> {
            assertThat(harness.boundaryActive).isTrue();
            return receipt;
        });

        LegalEditorialCommitReconciler.Result result =
                harness.reconciler().reconcilePromote(release, true);

        assertThat(result.outcome())
                .isEqualTo(LegalEditorialCommitReconciler.Outcome.POST_EXACT);
        assertThat(result.receipt()).containsSame(receipt);
        verify(harness.postStateVerifier, times(1)).verify(replay);
    }

    @Test
    void replaceAndRetireReplaysUseTheSameFullVerifierPath() {
        Harness replaceHarness = new Harness();
        Harness retireHarness = new Harness();
        ValidatedRelease release = mock(ValidatedRelease.class);
        ValidatedEditorialPlan editorialPlan = mock(ValidatedEditorialPlan.class);
        LegalEditorialExecutionPlan replaceReplay = executionPlan(
                LegalEditorialExecutionPlan.OperationType.REPLACE,
                false);
        LegalEditorialExecutionPlan retireReplay = executionPlan(
                LegalEditorialExecutionPlan.OperationType.RETIRE,
                false);
        LegalEditorialApplyReceipt replaceReceipt = receipt(
                LegalEditorialApplyReceipt.OperationType.REPLACE);
        LegalEditorialApplyReceipt retireReceipt = receipt(
                LegalEditorialApplyReceipt.OperationType.RETIRE);
        when(replaceHarness.planner.planReplace(release, editorialPlan, OBSERVED_AT))
                .thenReturn(LegalEditorialPlanResult.applicable(replaceReplay));
        when(retireHarness.planner.planRetire(release, editorialPlan, OBSERVED_AT))
                .thenReturn(LegalEditorialPlanResult.applicable(retireReplay));
        when(replaceHarness.postStateVerifier.verify(replaceReplay))
                .thenReturn(replaceReceipt);
        when(retireHarness.postStateVerifier.verify(retireReplay))
                .thenReturn(retireReceipt);

        LegalEditorialCommitReconciler.Result replace = replaceHarness.reconciler()
                .reconcileReplace(release, editorialPlan, true);
        LegalEditorialCommitReconciler.Result retire = retireHarness.reconciler()
                .reconcileRetire(release, editorialPlan, true);

        assertThat(replace.outcome())
                .isEqualTo(LegalEditorialCommitReconciler.Outcome.POST_EXACT);
        assertThat(replace.receipt()).containsSame(replaceReceipt);
        assertThat(retire.outcome())
                .isEqualTo(LegalEditorialCommitReconciler.Outcome.POST_EXACT);
        assertThat(retire.receipt()).containsSame(retireReceipt);
    }

    @Test
    void blockedAndErrorReplansRemainUnknownWithoutVerification() {
        Harness blockedHarness = new Harness();
        Harness errorHarness = new Harness();
        ValidatedRelease release = mock(ValidatedRelease.class);
        when(blockedHarness.planner.planPromote(release, OBSERVED_AT))
                .thenReturn(blockedResult());
        when(errorHarness.planner.planPromote(release, OBSERVED_AT))
                .thenReturn(errorResult());

        LegalEditorialCommitReconciler.Result blocked =
                blockedHarness.reconciler().reconcilePromote(release, true);
        LegalEditorialCommitReconciler.Result error =
                errorHarness.reconciler().reconcilePromote(release, true);

        assertUnknown(blocked);
        assertUnknown(error);
        verify(blockedHarness.postStateVerifier, never()).verify(any());
        verify(errorHarness.postStateVerifier, never()).verify(any());
    }

    @Test
    void nullReplansAndLinkageFailuresRemainUnknownWithoutVerification() {
        ValidatedRelease release = mock(ValidatedRelease.class);
        Harness nullReplan = new Harness();
        when(nullReplan.planner.planPromote(release, OBSERVED_AT)).thenReturn(null);

        assertUnknown(nullReplan.reconciler().reconcilePromote(release, true));
        verify(nullReplan.postStateVerifier, never()).verify(any());

        Harness linkageFailure = new Harness();
        when(linkageFailure.planner.planPromote(release, OBSERVED_AT))
                .thenThrow(new NoClassDefFoundError("missing reconciliation dependency"));

        assertUnknown(linkageFailure.reconciler().reconcilePromote(release, true));
        verify(linkageFailure.postStateVerifier, never()).verify(any());
    }

    @Test
    void failedPostStateVerificationRemainsUnknownAndNeverLeaksAReceipt() {
        Harness harness = new Harness();
        ValidatedRelease release = mock(ValidatedRelease.class);
        LegalEditorialExecutionPlan replay = executionPlan(
                LegalEditorialExecutionPlan.OperationType.PROMOTE,
                false);
        when(harness.planner.planPromote(release, OBSERVED_AT))
                .thenReturn(LegalEditorialPlanResult.applicable(replay));
        doThrow(new LegalEditorialOperationalException(
                LegalManifestIssueCode.POSTCONDITION_NOT_READY,
                "database/postcondition"))
                .when(harness.postStateVerifier).verify(replay);

        LegalEditorialCommitReconciler.Result result =
                harness.reconciler().reconcilePromote(release, true);

        assertUnknown(result);
    }

    @Test
    void gatePlannerAndTimestampFailuresAllRemainUnknown() {
        ValidatedRelease release = mock(ValidatedRelease.class);

        Harness gateFailure = new Harness();
        doThrow(new CannotGetJdbcConnectionException("offline"))
                .when(gateFailure.gate).executeEditorialReconciliation(any());
        assertUnknown(gateFailure.reconciler().reconcilePromote(release, true));
        verify(gateFailure.jdbc, never()).queryForObject(any(String.class), any(Class.class));
        verify(gateFailure.planner, never()).planPromote(any(), any());

        Harness lockFailure = new Harness();
        doThrow(new CannotAcquireLockException("advisory lock unavailable"))
                .when(lockFailure.gate).executeEditorialReconciliation(any());
        assertUnknown(lockFailure.reconciler().reconcilePromote(release, true));
        verify(lockFailure.jdbc, never()).queryForObject(any(String.class), any(Class.class));
        verify(lockFailure.planner, never()).planPromote(any(), any());

        Harness plannerFailure = new Harness();
        when(plannerFailure.planner.planPromote(release, OBSERVED_AT))
                .thenThrow(new IllegalStateException("partial observation"));
        assertUnknown(plannerFailure.reconciler().reconcilePromote(release, true));
        verify(plannerFailure.postStateVerifier, never()).verify(any());

        Harness missingTimestamp = new Harness();
        org.mockito.Mockito.doReturn(null).when(missingTimestamp.jdbc).queryForObject(
                "SELECT transaction_timestamp()",
                OffsetDateTime.class);
        assertUnknown(missingTimestamp.reconciler().reconcilePromote(release, true));
        verify(missingTimestamp.planner, never()).planPromote(any(), any());
    }

    @Test
    void missingPlanEvidenceOrAnActivePreviousTransactionSkipsTheDatabase() {
        ValidatedRelease release = mock(ValidatedRelease.class);
        Harness noPlan = new Harness();
        assertUnknown(noPlan.reconciler().reconcilePromote(release, false));
        verify(noPlan.gate, never()).executeEditorialReconciliation(any());

        Harness synchronizationActive = new Harness();
        LegalEditorialCommitReconciler synchronizedReconciler =
                synchronizationActive.reconciler();
        TransactionSynchronizationManager.initSynchronization();
        assertUnknown(synchronizedReconciler.reconcilePromote(release, true));
        verify(synchronizationActive.gate, never()).executeEditorialReconciliation(any());
        TransactionSynchronizationManager.clear();

        Harness active = new Harness();
        LegalEditorialCommitReconciler activeReconciler = active.reconciler();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertUnknown(activeReconciler.reconcilePromote(release, true));
        verify(active.gate, never()).executeEditorialReconciliation(any());
        TransactionSynchronizationManager.setActualTransactionActive(false);

        Harness bound = new Harness();
        LegalEditorialCommitReconciler boundReconciler = bound.reconciler();
        TransactionSynchronizationManager.bindResource(bound.dataSource, new Object());
        try {
            assertUnknown(boundReconciler.reconcilePromote(release, true));
            verify(bound.gate, never()).executeEditorialReconciliation(any());
        } finally {
            TransactionSynchronizationManager.unbindResource(bound.dataSource);
        }
    }

    @Test
    void aCrossOperationReplayOrNullBoundaryResultRemainsUnknown() {
        ValidatedRelease release = mock(ValidatedRelease.class);
        Harness crossed = new Harness();
        LegalEditorialExecutionPlan replaceReplay = executionPlan(
                LegalEditorialExecutionPlan.OperationType.REPLACE,
                false);
        when(crossed.planner.planPromote(release, OBSERVED_AT))
                .thenReturn(LegalEditorialPlanResult.applicable(replaceReplay));
        assertUnknown(crossed.reconciler().reconcilePromote(release, true));
        verify(crossed.postStateVerifier, never()).verify(any());

        Harness nullBoundary = new Harness();
        org.mockito.Mockito.doReturn(null)
                .when(nullBoundary.gate).executeEditorialReconciliation(any());
        assertUnknown(nullBoundary.reconciler().reconcilePromote(release, true));
    }

    @Test
    void aNullVerifierReceiptRemainsUnknown() {
        Harness harness = new Harness();
        ValidatedRelease release = mock(ValidatedRelease.class);
        LegalEditorialExecutionPlan replay = executionPlan(
                LegalEditorialExecutionPlan.OperationType.PROMOTE,
                false);
        when(harness.planner.planPromote(release, OBSERVED_AT))
                .thenReturn(LegalEditorialPlanResult.applicable(replay));
        when(harness.postStateVerifier.verify(replay)).thenReturn(null);

        assertUnknown(harness.reconciler().reconcilePromote(release, true));
    }

    @Test
    void aParticipantThatDriftsAfterAssemblyRemainsUnknownBeforeOpeningTheGate() {
        Harness harness = new Harness();
        LegalEditorialCommitReconciler reconciler = harness.reconciler();
        when(harness.postStateVerifier.usesJdbc(harness.jdbc)).thenReturn(false);

        LegalEditorialCommitReconciler.Result result =
                reconciler.reconcilePromote(mock(ValidatedRelease.class), true);

        assertUnknown(result);
        verify(harness.gate, never()).executeEditorialReconciliation(any());
    }

    @Test
    void theClosedResultMatrixRejectsReceiptContradictions() {
        assertThatThrownBy(() -> new LegalEditorialCommitReconciler.Result(
                LegalEditorialCommitReconciler.Outcome.POST_EXACT,
                Optional.empty())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LegalEditorialCommitReconciler.Result(
                LegalEditorialCommitReconciler.Outcome.SOURCE_EXACT,
                Optional.of(receipt()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LegalEditorialCommitReconciler.Result(
                LegalEditorialCommitReconciler.Outcome.UNKNOWN,
                Optional.of(receipt()))).isInstanceOf(IllegalArgumentException.class);
    }

    private static LegalEditorialExecutionPlan executionPlan(
            LegalEditorialExecutionPlan.OperationType operationType,
            boolean changeRequired) {
        LegalEditorialExecutionPlan plan = mock(LegalEditorialExecutionPlan.class);
        when(plan.operationType()).thenReturn(operationType);
        when(plan.changeRequired()).thenReturn(changeRequired);
        return plan;
    }

    private static LegalEditorialPlanResult blockedResult() {
        return LegalEditorialPlanResult.blocked(List.of(LegalManifestIssue.at(
                LegalManifestIssueCode.CURRENT_STATE_MISMATCH,
                "database/state")));
    }

    private static LegalEditorialPlanResult errorResult() {
        return LegalEditorialPlanResult.error(List.of(LegalManifestIssue.at(
                LegalManifestIssueCode.EDITORIAL_OBSERVATION_FAILED,
                "database/observation")));
    }

    private static LegalEditorialApplyReceipt receipt() {
        return receipt(LegalEditorialApplyReceipt.OperationType.PROMOTE);
    }

    private static LegalEditorialApplyReceipt receipt(
            LegalEditorialApplyReceipt.OperationType operationType) {
        return new LegalEditorialApplyReceipt(
                operationType,
                UUID.fromString("00000000-0000-0000-0000-000000000901"),
                OBSERVED_AT,
                operationType == LegalEditorialApplyReceipt.OperationType.RETIRE
                        ? LegalEditorialReadiness.NOT_READY
                        : LegalEditorialReadiness.READY,
                1,
                1,
                2,
                2,
                1,
                1,
                0);
    }

    private static void assertUnknown(LegalEditorialCommitReconciler.Result result) {
        assertThat(result.outcome())
                .isEqualTo(LegalEditorialCommitReconciler.Outcome.UNKNOWN);
        assertThat(result.receipt()).isEmpty();
    }

    private static final class Harness {
        private final DataSource dataSource = mock(DataSource.class);
        private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        private final LegalManifestDatabaseGate gate = mock(LegalManifestDatabaseGate.class);
        private final LegalEditorialPlannerCore planner = mock(LegalEditorialPlannerCore.class);
        private final LegalEditorialPostStateVerifier postStateVerifier =
                mock(LegalEditorialPostStateVerifier.class);
        private final LegalEditorialSchemaVerifier schemaVerifier =
                mock(LegalEditorialSchemaVerifier.class);
        private final LegalEditorialPrivilegeVerifier privilegeVerifier =
                mock(LegalEditorialPrivilegeVerifier.class);
        private final AtomicBoolean boundaryActive = new AtomicBoolean();

        private Harness() {
            when(jdbc.getDataSource()).thenReturn(dataSource);
            when(gate.usesJdbc(jdbc)).thenReturn(true);
            when(planner.usesJdbc(jdbc)).thenReturn(true);
            when(postStateVerifier.usesJdbc(jdbc)).thenReturn(true);
            when(jdbc.queryForObject(
                    "SELECT transaction_timestamp()",
                    OffsetDateTime.class)).thenAnswer(invocation -> {
                        assertThat(boundaryActive).isTrue();
                        return OffsetDateTime.ofInstant(OBSERVED_AT, ZoneOffset.UTC);
                    });
            when(gate.executeEditorialReconciliation(any())).thenAnswer(invocation -> {
                org.springframework.transaction.support.TransactionCallback<?> callback =
                        invocation.getArgument(0);
                boundaryActive.set(true);
                try {
                    return callback.doInTransaction(new SimpleTransactionStatus());
                } finally {
                    boundaryActive.set(false);
                }
            });
        }

        private LegalEditorialCommitReconciler reconciler() {
            return new LegalEditorialCommitReconciler(
                    gate,
                    jdbc,
                    planner,
                    postStateVerifier,
                    schemaVerifier,
                    privilegeVerifier);
        }
    }
}
