package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.ApplyHarness;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.Harness;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.applyHarness;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.assertEditorialConfirmed;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.assertEditorialKnownFailure;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.awaitValue;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.cleanLegalState;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.copyRelease;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.editorialSequenceStates;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.harness;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.holdEditorialLock;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.migrate;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.pooledDataSource;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.withReplicaRole;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Testcontainers
class LegalEditorialApplyFailureIT {

    private static final String APPLY_APPLICATION_NAME = "ordenfix-legal-apply-failure-it";
    private static final String CANARY = "CANARY_9D2_DO_NOT_EXPOSE";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_apply_failure")
            .withUsername("ordenfix")
            .withPassword("ordenfix");

    private static HikariDataSource pool;
    private static DataSource externalDataSource;
    private static JdbcTemplate observer;
    private static Harness importer;
    private static ApplyHarness production;

    @TempDir
    private Path temporaryDirectory;

    @BeforeAll
    static void migrateAndAssemble() {
        migrate(POSTGRES);
        pool = pooledDataSource(POSTGRES, APPLY_APPLICATION_NAME);
        externalDataSource = LegalManifestPersistenceITSupport.directDataSource(
                POSTGRES,
                "ordenfix-legal-apply-failure-external");
        observer = new JdbcTemplate(LegalManifestPersistenceITSupport.directDataSource(
                POSTGRES,
                "ordenfix-legal-apply-failure-observer"));
        importer = harness(pool, LegalDatabaseBudgets.production());
        production = applyHarness(pool, LegalDatabaseBudgets.production());
    }

    @AfterAll
    static void closePool() {
        if (pool != null) {
            pool.close();
        }
    }

    @BeforeEach
    void cleanDatabase() {
        cleanLegalState(observer);
    }

    @Test
    void missingPlanMarkerPreservesUnknownWithoutOpeningReconciliation() throws Exception {
        ImportedRelease target = importedDraft("apply-failure-missing-marker-v1");
        ObservedAttempt attempt = observedAttempt(
                AcknowledgementLoss.ROLLBACK,
                LegalDatabaseBudgets.production());
        LegalEditorialPlannerCore planner = spy(attempt.harness().plannerCore());
        doThrow(new IllegalStateException(CANARY + "_PLAN"))
                .when(planner).planPromote(eq(target.release()), any());
        LegalEditorialMutationWriter writer = inertWriter(attempt.harness());
        LegalEditorialCommitReconciler reconciler = spy(newReconciler(
                attempt.harness(),
                planner,
                attempt.harness().postStateVerifier()));
        LegalEditorialApplyService service = service(
                attempt.harness(),
                planner,
                writer,
                attempt.harness().postStateVerifier(),
                reconciler);
        Map<String, List<String>> rowsBefore = editorialTableRows(observer);
        Map<String, LegalManifestPersistenceITSupport.SequenceState> sequencesBefore =
                editorialSequenceStates(observer);

        LegalEditorialApplyResult result = service.applyPromote(target.release());

        assertUnknownRedacted(result, CANARY);
        verify(reconciler, never()).reconcilePromote(any(), anyBoolean());
        verify(reconciler, never()).reconcileReplace(any(), any(), anyBoolean());
        verify(reconciler, never()).reconcileRetire(any(), any(), anyBoolean());
        verify(writer, never()).write(any());
        assertThat(attempt.dataSource().acknowledgementWasLost()).isTrue();
        assertThat(attempt.dataSource().connectionRequests()).isEqualTo(1);
        assertThat(attempt.dataSource().acquisitions())
                .extracting(LeaseAcquisition::leaseId)
                .containsExactly(1);
        assertThat(attempt.dataSource().closedLeases()).containsExactly(1);
        assertThat(attempt.jdbc().lockAttempts()).hasSize(1);
        assertMutableLock(attempt.jdbc().lockAttempts().getFirst());
        assertThat(editorialTableRows(observer)).isEqualTo(rowsBefore);
        assertThat(editorialSequenceStates(observer)).isEqualTo(sequencesBefore);
    }

    @Test
    void partialStateRemainsUnknownAndManualRetryDoesNotHealIt() throws Exception {
        ImportedRelease target = importedDraft("apply-failure-partial-state-v1");
        ObservedAttempt attempt = observedAttempt(
                AcknowledgementLoss.ROLLBACK,
                LegalDatabaseBudgets.production());
        AtomicReference<LegalEditorialExecutionPlan> capturedPlan = new AtomicReference<>();
        LegalEditorialMutationWriter writer = failingWriter(
                attempt.harness(),
                capturedPlan,
                new IllegalStateException(CANARY + "_WRITER"));
        LegalEditorialPlannerCore planner = spy(attempt.harness().plannerCore());
        List<LegalEditorialPlanResult.Outcome> plannerOutcomes = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            LegalEditorialPlanResult result =
                    (LegalEditorialPlanResult) invocation.callRealMethod();
            plannerOutcomes.add(result.outcome());
            return result;
        }).when(planner).planPromote(eq(target.release()), any());
        AtomicReference<Map<String, List<String>>> partialRows = new AtomicReference<>();
        AtomicReference<Map<String, LegalManifestPersistenceITSupport.SequenceState>>
                partialSequences = new AtomicReference<>();
        LegalEditorialCommitReconciler real = newReconciler(
                attempt.harness(),
                planner,
                attempt.harness().postStateVerifier());
        ReconciliationProbe probe = observeReconciliation(
                attempt.harness(),
                target.release(),
                real,
                invocation -> {
                    insertOneUnprojectedTransition(capturedPlan.get());
                    partialRows.set(editorialTableRows(observer));
                    partialSequences.set(editorialSequenceStates(observer));
                    return callReal(invocation);
                });
        LegalEditorialApplyService service = service(
                attempt.harness(),
                planner,
                writer,
                attempt.harness().postStateVerifier(),
                probe.reconciler());

        LegalEditorialApplyResult result = service.applyPromote(target.release());

        assertUnknownRedacted(result, CANARY);
        probe.assertUnknownOnce(target.release());
        assertThat(plannerOutcomes).containsExactly(
                LegalEditorialPlanResult.Outcome.APPLICABLE,
                LegalEditorialPlanResult.Outcome.BLOCKED);
        assertThat(partialRows.get()).isNotNull();
        assertThat(partialSequences.get()).isNotNull();
        assertThat(editorialTableRows(observer)).isEqualTo(partialRows.get());
        assertThat(editorialSequenceStates(observer)).isEqualTo(partialSequences.get());
        assertTwoSuccessfulBoundaries(attempt);
        verify(writer, times(1)).write(any());

        LegalInitialPromotionCore retryWriter = spy(production.promotionCore());
        LegalEditorialApplyService retryService = service(
                production,
                production.plannerCore(),
                retryWriter,
                production.postStateVerifier(),
                production.commitReconciler());

        LegalEditorialApplyResult retry = retryService.applyPromote(target.release());

        assertEditorialKnownFailure(
                retry,
                LegalManifestStatus.BLOCKED,
                LegalManifestIssueCode.INITIAL_PROJECTION_ALREADY_EXISTS);
        verify(retryWriter, never()).write(any());
        assertThat(editorialTableRows(observer)).isEqualTo(partialRows.get());
        assertThat(editorialSequenceStates(observer)).isEqualTo(partialSequences.get());
    }

    @Test
    void killedOriginalAndReadOnlySessionsPreserveUnknown() throws Exception {
        ImportedRelease target = importedDraft("apply-failure-double-session-kill-v1");
        ObservedAttempt attempt = observedAttempt(
                AcknowledgementLoss.COMMIT_SESSION_KILL,
                LegalDatabaseBudgets.production());
        attempt.jdbc().killNextReconciliationObservation();
        LegalInitialPromotionCore writer = spy(attempt.harness().promotionCore());
        AtomicReference<Map<String, List<String>>> postKillRows = new AtomicReference<>();
        AtomicReference<Map<String, LegalManifestPersistenceITSupport.SequenceState>>
                postKillSequences = new AtomicReference<>();
        ReconciliationProbe probe = observeReconciliation(
                attempt.harness(),
                target.release(),
                attempt.harness().commitReconciler(),
                invocation -> {
                    postKillRows.set(editorialTableRows(observer));
                    postKillSequences.set(editorialSequenceStates(observer));
                    return callReal(invocation);
                });
        LegalEditorialApplyService service = service(
                attempt.harness(),
                attempt.harness().plannerCore(),
                writer,
                attempt.harness().postStateVerifier(),
                probe.reconciler());
        Map<String, List<String>> rowsBefore = editorialTableRows(observer);

        LegalEditorialApplyResult result = service.applyPromote(target.release());

        assertUnknownRedacted(result, CANARY);
        probe.assertUnknownOnce(target.release());
        int firstPid = attempt.dataSource().killedCommitPid();
        int reconciliationPid = attempt.jdbc().killedReconciliationPid();
        CommitSessionFailure commitFailure = attempt.dataSource().commitSessionFailure();
        assertThat(firstPid).isPositive();
        assertThat(reconciliationPid).isPositive().isNotEqualTo(firstPid);
        assertThat(commitFailure).isNotNull();
        assertThat(commitFailure.exposed().getMessage())
                .isEqualTo(CANARY + "_COMMIT_SESSION_KILLED");
        assertThat(commitFailure.exposed().getSQLState()).isEqualTo("08006");
        assertThat(commitFailure.exposed().getCause()).isSameAs(commitFailure.cause());
        assertThat(attempt.dataSource().connectionRequests()).isEqualTo(2);
        assertThat(attempt.dataSource().acquisitions())
                .extracting(LeaseAcquisition::leaseId)
                .containsExactly(1, 2);
        assertThat(attempt.dataSource().closedLeases()).containsExactly(1, 2);
        assertThat(attempt.jdbc().lockAttempts()).hasSize(2);
        assertMutableLock(attempt.jdbc().lockAttempts().get(0));
        assertReadOnlyLock(attempt.jdbc().lockAttempts().get(1), true);
        assertThat(attempt.jdbc().lockAttempts().get(0).backendPid()).isEqualTo(firstPid);
        assertThat(attempt.jdbc().lockAttempts().get(1).backendPid())
                .isEqualTo(reconciliationPid);
        assertThat(postKillRows.get()).isEqualTo(rowsBefore);
        assertThat(editorialTableRows(observer)).isEqualTo(postKillRows.get());
        assertThat(editorialSequenceStates(observer)).isEqualTo(postKillSequences.get());
        verify(writer, times(1)).write(any());
    }

    @Test
    void connectionFailureArmedAfterUnknownPreservesUnknown() throws Exception {
        ImportedRelease target = importedDraft("apply-failure-reconciliation-db-v1");
        ObservedAttempt attempt = observedAttempt(
                AcknowledgementLoss.ROLLBACK,
                LegalDatabaseBudgets.production());
        LegalEditorialMutationWriter writer = failingWriter(
                attempt.harness(),
                new AtomicReference<>(),
                new IllegalStateException(CANARY + "_DB"));
        Map<String, List<String>> rowsBefore = editorialTableRows(observer);
        Map<String, LegalManifestPersistenceITSupport.SequenceState> sequencesBefore =
                editorialSequenceStates(observer);
        ReconciliationProbe probe = observeReconciliation(
                attempt.harness(),
                target.release(),
                attempt.harness().commitReconciler(),
                invocation -> {
                    attempt.dataSource().failNextConnection();
                    return callReal(invocation);
                });
        LegalEditorialApplyService service = service(
                attempt.harness(),
                attempt.harness().plannerCore(),
                writer,
                attempt.harness().postStateVerifier(),
                probe.reconciler());

        LegalEditorialApplyResult result = service.applyPromote(target.release());

        assertUnknownRedacted(result, CANARY);
        probe.assertUnknownOnce(target.release());
        assertThat(attempt.dataSource().acknowledgementWasLost()).isTrue();
        assertThat(attempt.dataSource().connectionRequests()).isEqualTo(2);
        assertThat(attempt.dataSource().failedConnectionRequests()).containsExactly(2);
        assertThat(attempt.dataSource().acquisitions())
                .extracting(LeaseAcquisition::leaseId)
                .containsExactly(1);
        assertThat(attempt.dataSource().closedLeases()).containsExactly(1);
        assertThat(attempt.jdbc().lockAttempts()).hasSize(1);
        assertMutableLock(attempt.jdbc().lockAttempts().getFirst());
        assertThat(editorialTableRows(observer)).isEqualTo(rowsBefore);
        assertThat(editorialSequenceStates(observer)).isEqualTo(sequencesBefore);
        verify(writer, times(1)).write(any());
    }

    @Test
    void advisoryLockHeldOnlyForReconciliationPreservesUnknown() throws Exception {
        ImportedRelease target = importedDraft("apply-failure-reconciliation-lock-v1");
        ObservedAttempt attempt = observedAttempt(
                AcknowledgementLoss.ROLLBACK,
                new LegalDatabaseBudgets(5, 2, 1, 1));
        LegalEditorialMutationWriter writer = failingWriter(
                attempt.harness(),
                new AtomicReference<>(),
                new IllegalStateException(CANARY + "_LOCK"));
        LegalEditorialPlannerCore planner = spy(attempt.harness().plannerCore());
        LegalEditorialPostStateVerifier verifier = spy(
                attempt.harness().postStateVerifier());
        LegalEditorialCommitReconciler real = newReconciler(
                attempt.harness(),
                planner,
                verifier);
        Map<String, List<String>> rowsBefore = editorialTableRows(observer);
        Map<String, LegalManifestPersistenceITSupport.SequenceState> sequencesBefore =
                editorialSequenceStates(observer);
        ReconciliationProbe probe = observeReconciliation(
                attempt.harness(),
                target.release(),
                real,
                invocation -> {
                    try (var ignored = holdEditorialLock(externalDataSource)) {
                        return callReal(invocation);
                    }
                });
        LegalEditorialApplyService service = service(
                attempt.harness(),
                planner,
                writer,
                verifier,
                probe.reconciler());

        LegalEditorialApplyResult result = service.applyPromote(target.release());

        assertUnknownRedacted(result, CANARY);
        probe.assertUnknownOnce(target.release());
        assertThat(attempt.dataSource().connectionRequests()).isEqualTo(2);
        assertThat(attempt.dataSource().acquisitions())
                .extracting(LeaseAcquisition::leaseId)
                .containsExactly(1, 2);
        assertThat(attempt.dataSource().closedLeases()).containsExactly(1, 2);
        assertThat(attempt.jdbc().lockAttempts()).hasSize(2);
        assertMutableLock(attempt.jdbc().lockAttempts().get(0));
        assertReadOnlyLock(attempt.jdbc().lockAttempts().get(1), false);
        verify(planner, times(1)).planPromote(eq(target.release()), any());
        verify(verifier, never()).verify(any());
        verify(writer, times(1)).write(any());
        assertThat(editorialTableRows(observer)).isEqualTo(rowsBefore);
        assertThat(editorialSequenceStates(observer)).isEqualTo(sequencesBefore);
    }

    @Test
    void committedReplayWithFailedVerifierPreservesUnknownAndHidesTentativeReceipt()
            throws Exception {
        ImportedRelease target = importedDraft("apply-failure-verifier-v1");
        ObservedAttempt attempt = observedAttempt(
                AcknowledgementLoss.COMMIT,
                LegalDatabaseBudgets.production());
        LegalEditorialPostStateVerifier verifier = spy(
                attempt.harness().postStateVerifier());
        AtomicInteger verifierCalls = new AtomicInteger();
        AtomicReference<LegalEditorialApplyReceipt> tentativeReceipt = new AtomicReference<>();
        doAnswer(invocation -> {
            if (verifierCalls.incrementAndGet() == 1) {
                LegalEditorialApplyReceipt receipt =
                        (LegalEditorialApplyReceipt) invocation.callRealMethod();
                tentativeReceipt.set(receipt);
                return receipt;
            }
            throw new IllegalStateException(CANARY + "_VERIFIER");
        }).when(verifier).verify(any());
        LegalEditorialCommitReconciler real = newReconciler(
                attempt.harness(),
                attempt.harness().plannerCore(),
                verifier);
        ReconciliationProbe probe = observeReconciliation(
                attempt.harness(),
                target.release(),
                real,
                LegalEditorialApplyFailureIT::callReal);
        LegalEditorialApplyService service = service(
                attempt.harness(),
                attempt.harness().plannerCore(),
                attempt.harness().promotionCore(),
                verifier,
                probe.reconciler());
        Map<String, List<String>> rowsBefore = editorialTableRows(observer);
        Map<String, LegalManifestPersistenceITSupport.SequenceState> sequencesBefore =
                editorialSequenceStates(observer);

        LegalEditorialApplyResult result = service.applyPromote(target.release());

        assertUnknownRedacted(result, CANARY);
        probe.assertUnknownOnce(target.release());
        assertThat(tentativeReceipt.get()).isNotNull();
        assertThat(verifierCalls.get()).isEqualTo(2);
        assertThat(attempt.dataSource().acknowledgementWasLost()).isTrue();
        assertTwoSuccessfulBoundaries(attempt);
        Map<String, List<String>> committedRows = editorialTableRows(observer);
        Map<String, LegalManifestPersistenceITSupport.SequenceState> committedSequences =
                editorialSequenceStates(observer);
        assertThat(committedRows).isNotEqualTo(rowsBefore);
        assertThat(committedSequences).isNotEqualTo(sequencesBefore);

        LegalEditorialApplyResult retry = production.service().applyPromote(target.release());

        assertEditorialConfirmed(retry, LegalEditorialApplyResult.Outcome.ALREADY_APPLIED);
        assertThat(retry.receipt()).contains(tentativeReceipt.get());
        assertThat(editorialTableRows(observer)).isEqualTo(committedRows);
        assertThat(editorialSequenceStates(observer)).isEqualTo(committedSequences);
    }

    @Test
    void plannerErrorInFreshReadOnlyBoundaryPreservesUnknown() throws Exception {
        ImportedRelease target = importedDraft("apply-failure-planner-error-v1");
        ObservedAttempt attempt = observedAttempt(
                AcknowledgementLoss.ROLLBACK,
                LegalDatabaseBudgets.production());
        LegalEditorialPlannerCore planner = spy(attempt.harness().plannerCore());
        AtomicInteger plannerCalls = new AtomicInteger();
        doAnswer(invocation -> {
            if (plannerCalls.incrementAndGet() == 1) {
                return invocation.callRealMethod();
            }
            return LegalEditorialPlanResult.error(List.of(LegalManifestIssue.at(
                    LegalManifestIssueCode.SCHEMA_DRIFT,
                    CANARY + "_PLANNER_ERROR")));
        }).when(planner).planPromote(eq(target.release()), any());
        LegalEditorialMutationWriter writer = failingWriter(
                attempt.harness(),
                new AtomicReference<>(),
                new IllegalStateException(CANARY + "_ORIGINAL"));
        LegalEditorialPostStateVerifier verifier = spy(
                attempt.harness().postStateVerifier());
        LegalEditorialCommitReconciler real = newReconciler(
                attempt.harness(),
                planner,
                verifier);
        ReconciliationProbe probe = observeReconciliation(
                attempt.harness(),
                target.release(),
                real,
                LegalEditorialApplyFailureIT::callReal);
        LegalEditorialApplyService service = service(
                attempt.harness(),
                planner,
                writer,
                verifier,
                probe.reconciler());
        Map<String, List<String>> rowsBefore = editorialTableRows(observer);
        Map<String, LegalManifestPersistenceITSupport.SequenceState> sequencesBefore =
                editorialSequenceStates(observer);

        LegalEditorialApplyResult result = service.applyPromote(target.release());

        assertUnknownRedacted(result, CANARY);
        probe.assertUnknownOnce(target.release());
        assertThat(plannerCalls.get()).isEqualTo(2);
        assertTwoSuccessfulBoundaries(attempt);
        verify(verifier, never()).verify(any());
        verify(writer, times(1)).write(any());
        assertThat(editorialTableRows(observer)).isEqualTo(rowsBefore);
        assertThat(editorialSequenceStates(observer)).isEqualTo(sequencesBefore);
    }

    private ImportedRelease importedDraft(String externalId) throws Exception {
        ValidatedRelease release = copyRelease(
                temporaryDirectory,
                LegalEditorialApplyFailureIT.class,
                externalId,
                (manifestPath, manifest) -> manifest.withArray("documents")
                        .forEach(document -> ((ObjectNode) document).put(
                                "effectiveAt",
                                "2026-01-01T00:00:00-03:00")));
        UUID publicationId = importer.importService()
                .importManifest(release)
                .receipt()
                .orElseThrow()
                .publicationUuid();
        return new ImportedRelease(release, publicationId);
    }

    private static ObservedAttempt observedAttempt(
            AcknowledgementLoss acknowledgementLoss,
            LegalDatabaseBudgets budgets) {
        PhaseTrackingDataSource dataSource = new PhaseTrackingDataSource(
                pool,
                acknowledgementLoss,
                observer);
        BoundaryObservingJdbcTemplate jdbc = new BoundaryObservingJdbcTemplate(
                dataSource,
                observer);
        LegalEditorialSchemaVerifier schemaVerifier = new LegalEditorialSchemaVerifier(
                jdbc,
                LegalV27EditorialInventory.DEFAULT_SCHEMA);
        LegalEditorialPrivilegeVerifier privilegeVerifier = mock(
                LegalEditorialPrivilegeVerifier.class);
        when(privilegeVerifier.usesJdbc(jdbc)).thenReturn(true);
        ApplyHarness harness = applyHarness(
                jdbc,
                budgets,
                schemaVerifier,
                privilegeVerifier);
        return new ObservedAttempt(dataSource, jdbc, harness);
    }

    private static LegalEditorialMutationWriter inertWriter(ApplyHarness harness) {
        LegalEditorialMutationWriter writer = mock(LegalEditorialMutationWriter.class);
        when(writer.usesJdbc(harness.jdbc())).thenReturn(true);
        return writer;
    }

    private static LegalEditorialMutationWriter failingWriter(
            ApplyHarness harness,
            AtomicReference<LegalEditorialExecutionPlan> capturedPlan,
            RuntimeException failure) {
        LegalEditorialMutationWriter writer = inertWriter(harness);
        doAnswer(invocation -> {
            capturedPlan.set(invocation.getArgument(0));
            throw failure;
        }).when(writer).write(any());
        return writer;
    }

    private static LegalEditorialCommitReconciler newReconciler(
            ApplyHarness harness,
            LegalEditorialPlannerCore planner,
            LegalEditorialPostStateVerifier verifier) {
        return new LegalEditorialCommitReconciler(
                harness.reconciliationGate(),
                harness.jdbc(),
                planner,
                verifier,
                harness.schemaVerifier(),
                harness.privilegeVerifier());
    }

    private static LegalEditorialApplyService service(
            ApplyHarness harness,
            LegalEditorialPlannerCore planner,
            LegalEditorialMutationWriter promotionWriter,
            LegalEditorialPostStateVerifier verifier,
            LegalEditorialCommitReconciler reconciler) {
        return new LegalEditorialApplyService(
                harness.gate(),
                harness.jdbc(),
                planner,
                promotionWriter,
                harness.replacementWriter(),
                harness.retirementWriter(),
                verifier,
                harness.readinessCore(),
                harness.replaceScopeGuard(),
                reconciler,
                new LegalEditorialFailureMapper(),
                harness.schemaVerifier(),
                harness.privilegeVerifier());
    }

    private static ReconciliationProbe observeReconciliation(
            ApplyHarness harness,
            ValidatedRelease target,
            LegalEditorialCommitReconciler real,
            ReconciliationBehavior behavior) {
        LegalEditorialCommitReconciler reconciler = spy(real);
        AtomicInteger invocations = new AtomicInteger();
        AtomicReference<ReconciliationEntry> entry = new AtomicReference<>();
        AtomicReference<LegalEditorialCommitReconciler.Result> observed =
                new AtomicReference<>();
        doAnswer(invocation -> {
            invocations.incrementAndGet();
            entry.set(new ReconciliationEntry(
                    TransactionSynchronizationManager.isActualTransactionActive(),
                    TransactionSynchronizationManager.isSynchronizationActive(),
                    TransactionSynchronizationManager.hasResource(harness.dataSource())));
            LegalEditorialCommitReconciler.Result result = behavior.invoke(invocation);
            observed.set(result);
            return result;
        }).when(reconciler).reconcilePromote(eq(target), eq(true));
        return new ReconciliationProbe(
                harness,
                reconciler,
                invocations,
                entry,
                observed);
    }

    private static LegalEditorialCommitReconciler.Result callReal(
            InvocationOnMock invocation) throws Throwable {
        return (LegalEditorialCommitReconciler.Result) invocation.callRealMethod();
    }

    private static void insertOneUnprojectedTransition(
            LegalEditorialExecutionPlan plan) {
        LegalEditorialExecutionPlan.DocumentTransition transition = Objects.requireNonNull(
                plan,
                "captured plan").mutationCommands().documentTransitions().getFirst();
        withReplicaRole(observer, () -> observer.update("""
                INSERT INTO legal_documento_transiciones
                    (documento_version_id, estado_anterior, estado_nuevo,
                     motivo, reemplazo_lote_id, ocurrido_en)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                transition.documentVersionId(),
                transition.previousState().name(),
                transition.newState().name(),
                transition.reason(),
                transition.replacementBatchId(),
                Timestamp.from(transition.occurredAt())));
    }

    private static void assertUnknownRedacted(
            LegalEditorialApplyResult result,
            String... canaries) {
        assertThat(result.status()).isEqualTo(LegalManifestStatus.ERROR);
        assertThat(result.persisted()).isNull();
        assertThat(result.outcome()).isEqualTo(LegalEditorialApplyResult.Outcome.UNKNOWN);
        assertThat(result.receipt()).isEmpty();
        assertThat(result.operationType()).isEmpty();
        assertThat(result.targetPublicationUuid()).isEmpty();
        assertThat(result.appliedAt()).isEmpty();
        assertThat(result.readinessAfter()).isEmpty();
        assertThat(result.omittedIssueCount()).isZero();
        assertThat(result.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.code()).isEqualTo(LegalManifestIssueCode.COMMIT_OUTCOME_UNKNOWN);
            assertThat(issue.location()).isEqualTo("database/commit");
        });
        String exposed = result.issues().toString();
        for (String canary : canaries) {
            assertThat(exposed).doesNotContain(canary);
        }
    }

    private static void assertTwoSuccessfulBoundaries(ObservedAttempt attempt) {
        assertThat(attempt.dataSource().connectionRequests()).isEqualTo(2);
        assertThat(attempt.dataSource().acquisitions())
                .extracting(LeaseAcquisition::leaseId)
                .containsExactly(1, 2);
        assertThat(attempt.dataSource().acquisitions().get(1).closedBeforeAcquisition())
                .containsExactly(1);
        assertThat(attempt.dataSource().closedLeases()).containsExactly(1, 2);
        assertThat(attempt.jdbc().lockAttempts()).hasSize(2);
        assertMutableLock(attempt.jdbc().lockAttempts().get(0));
        assertReadOnlyLock(attempt.jdbc().lockAttempts().get(1), true);
    }

    private static void assertMutableLock(LockAttempt lock) {
        assertThat(lock.leaseId()).isEqualTo(1);
        assertThat(lock.lockName()).isEqualTo(LegalManifestDatabaseGate.EDITORIAL_LOCK_NAME);
        assertThat(lock.transactionName()).isEqualTo("legal-editorial-apply-it");
        assertThat(lock.springReadOnly()).isFalse();
        assertThat(lock.postgresReadOnly()).isEqualTo("off");
        assertThat(lock.success()).isTrue();
        assertThat(lock.failureSqlState()).isNull();
    }

    private static void assertReadOnlyLock(LockAttempt lock, boolean success) {
        assertThat(lock.leaseId()).isEqualTo(2);
        assertThat(lock.lockName()).isEqualTo(LegalManifestDatabaseGate.EDITORIAL_LOCK_NAME);
        assertThat(lock.transactionName()).isEqualTo("legal-editorial-reconciliation-it");
        assertThat(lock.springReadOnly()).isTrue();
        assertThat(lock.postgresReadOnly()).isEqualTo("on");
        assertThat(lock.success()).isEqualTo(success);
        assertThat(lock.failureSqlState()).isEqualTo(success ? null : "55P03");
    }

    private static Map<String, List<String>> editorialTableRows(JdbcTemplate jdbc) {
        Map<String, List<String>> rows = new LinkedHashMap<>();
        for (String table : new TreeSet<>(LegalV27EditorialInventory.EDITORIAL_TABLES)) {
            List<String> tableRows = jdbc.queryForList(
                    "SELECT pg_catalog.to_jsonb(row_data)::text"
                            + " FROM " + quoteIdentifier(table) + " row_data ORDER BY 1",
                    String.class);
            rows.put(table, List.copyOf(tableRows));
        }
        return Map.copyOf(rows);
    }

    private static String quoteIdentifier(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    private record ImportedRelease(ValidatedRelease release, UUID publicationId) { }

    private record ObservedAttempt(
            PhaseTrackingDataSource dataSource,
            BoundaryObservingJdbcTemplate jdbc,
            ApplyHarness harness
    ) { }

    private record ReconciliationEntry(
            boolean transactionActive,
            boolean synchronizationActive,
            boolean resourceBound
    ) { }

    private record ReconciliationProbe(
            ApplyHarness harness,
            LegalEditorialCommitReconciler reconciler,
            AtomicInteger invocations,
            AtomicReference<ReconciliationEntry> entry,
            AtomicReference<LegalEditorialCommitReconciler.Result> observed
    ) {

        void assertUnknownOnce(ValidatedRelease target) {
            verify(reconciler, times(1)).reconcilePromote(target, true);
            verify(reconciler, never()).reconcileReplace(any(), any(), anyBoolean());
            verify(reconciler, never()).reconcileRetire(any(), any(), anyBoolean());
            assertThat(invocations.get()).isOne();
            assertThat(entry.get()).isEqualTo(new ReconciliationEntry(false, false, false));
            assertThat(observed.get()).isNotNull();
            assertThat(observed.get().outcome())
                    .isEqualTo(LegalEditorialCommitReconciler.Outcome.UNKNOWN);
            assertThat(observed.get().receipt()).isEmpty();
            assertThat(TransactionSynchronizationManager.hasResource(harness.dataSource()))
                    .isFalse();
        }
    }

    @FunctionalInterface
    private interface ReconciliationBehavior {
        LegalEditorialCommitReconciler.Result invoke(InvocationOnMock invocation) throws Throwable;
    }

    private enum AcknowledgementLoss {
        NONE,
        COMMIT,
        ROLLBACK,
        COMMIT_SESSION_KILL
    }

    private record LeaseAcquisition(int leaseId, List<Integer> closedBeforeAcquisition) { }

    private record CommitSessionFailure(SQLException cause, SQLException exposed) { }

    private record LockAttempt(
            int leaseId,
            int backendPid,
            String lockName,
            String transactionName,
            boolean springReadOnly,
            String postgresReadOnly,
            boolean success,
            String failureSqlState
    ) { }

    /** Test-only JDBC observer for connection, lock and second-session boundaries. */
    private static final class BoundaryObservingJdbcTemplate extends JdbcTemplate {

        private final PhaseTrackingDataSource dataSource;
        private final JdbcTemplate observer;
        private final List<LockAttempt> lockAttempts = new CopyOnWriteArrayList<>();
        private final AtomicBoolean killReconciliation = new AtomicBoolean();
        private final AtomicInteger killedReconciliationPid = new AtomicInteger();

        private BoundaryObservingJdbcTemplate(
                PhaseTrackingDataSource dataSource,
                JdbcTemplate observer) {
            super(dataSource);
            this.dataSource = dataSource;
            this.observer = observer;
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            if (!sql.contains("pg_advisory_xact_lock")) {
                return super.queryForList(sql, args);
            }
            Connection connection = DataSourceUtils.getConnection(dataSource);
            try {
                Integer backendPid = super.queryForObject(
                        "SELECT pg_backend_pid()",
                        Integer.class);
                String postgresReadOnly = super.queryForObject(
                        "SELECT pg_catalog.current_setting('transaction_read_only')",
                        String.class);
                LockAttempt attempted = new LockAttempt(
                        dataSource.leaseId(connection),
                        Objects.requireNonNull(backendPid),
                        Objects.toString(args[0]),
                        TransactionSynchronizationManager.getCurrentTransactionName(),
                        TransactionSynchronizationManager.isCurrentTransactionReadOnly(),
                        Objects.requireNonNull(postgresReadOnly),
                        false,
                        null);
                try {
                    List<Map<String, Object>> result = super.queryForList(sql, args);
                    lockAttempts.add(withSuccess(attempted));
                    return result;
                } catch (RuntimeException | LinkageError failure) {
                    lockAttempts.add(withFailure(attempted, failure));
                    throw failure;
                }
            } finally {
                DataSourceUtils.releaseConnection(connection, dataSource);
            }
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType) {
            return queryForObject(sql, requiredType, new Object[0]);
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            if ("SELECT transaction_timestamp()".equals(sql)
                    && TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                    && killReconciliation.compareAndSet(true, false)) {
                Connection connection = DataSourceUtils.getConnection(dataSource);
                try {
                    Integer pid = super.queryForObject("SELECT pg_backend_pid()", Integer.class);
                    int backendPid = Objects.requireNonNull(pid);
                    killedReconciliationPid.set(backendPid);
                    Boolean terminated = observer.queryForObject(
                            "SELECT pg_catalog.pg_terminate_backend(?)",
                            Boolean.class,
                            backendPid);
                    assertThat(terminated).isTrue();
                    awaitValue("segunda sesión editorial terminada", () -> {
                        Integer sessions = observer.queryForObject("""
                                SELECT count(*)::integer
                                  FROM pg_catalog.pg_stat_activity
                                 WHERE pid = ?
                                """, Integer.class, backendPid);
                        return Objects.requireNonNull(sessions) == 0
                                ? Optional.of(Boolean.TRUE)
                                : Optional.empty();
                    });
                    return super.queryForObject(sql, requiredType, args);
                } finally {
                    DataSourceUtils.releaseConnection(connection, dataSource);
                }
            }
            return super.queryForObject(sql, requiredType, args);
        }

        void killNextReconciliationObservation() {
            killReconciliation.set(true);
        }

        int killedReconciliationPid() {
            return killedReconciliationPid.get();
        }

        List<LockAttempt> lockAttempts() {
            return List.copyOf(lockAttempts);
        }

        private static LockAttempt withSuccess(LockAttempt attempt) {
            return new LockAttempt(
                    attempt.leaseId(),
                    attempt.backendPid(),
                    attempt.lockName(),
                    attempt.transactionName(),
                    attempt.springReadOnly(),
                    attempt.postgresReadOnly(),
                    true,
                    null);
        }

        private static LockAttempt withFailure(LockAttempt attempt, Throwable failure) {
            return new LockAttempt(
                    attempt.leaseId(),
                    attempt.backendPid(),
                    attempt.lockName(),
                    attempt.transactionName(),
                    attempt.springReadOnly(),
                    attempt.postgresReadOnly(),
                    false,
                    findSqlState(failure));
        }

        private static String findSqlState(Throwable failure) {
            Throwable current = failure;
            while (current != null) {
                if (current instanceof SQLException sqlException
                        && sqlException.getSQLState() != null) {
                    return sqlException.getSQLState();
                }
                current = current.getCause();
            }
            return null;
        }
    }

    /** Test-only datasource that can lose one ACK and fail one later connection request. */
    private static final class PhaseTrackingDataSource extends DelegatingDataSource {

        private final AcknowledgementLoss acknowledgementLoss;
        private final JdbcTemplate observer;
        private final AtomicBoolean acknowledgementArmed;
        private final AtomicBoolean failNextConnection = new AtomicBoolean();
        private final AtomicInteger connectionRequests = new AtomicInteger();
        private final AtomicInteger nextLease = new AtomicInteger();
        private final AtomicInteger killedCommitPid = new AtomicInteger();
        private final AtomicReference<CommitSessionFailure> commitSessionFailure =
                new AtomicReference<>();
        private final Map<Connection, Integer> leaseIds = Collections.synchronizedMap(
                new IdentityHashMap<>());
        private final List<Integer> closedLeases = new CopyOnWriteArrayList<>();
        private final List<Integer> failedConnectionRequests = new CopyOnWriteArrayList<>();
        private final List<LeaseAcquisition> acquisitions = new CopyOnWriteArrayList<>();

        private PhaseTrackingDataSource(
                DataSource targetDataSource,
                AcknowledgementLoss acknowledgementLoss,
                JdbcTemplate observer) {
            super(Objects.requireNonNull(targetDataSource, "targetDataSource"));
            this.acknowledgementLoss = Objects.requireNonNull(
                    acknowledgementLoss,
                    "acknowledgementLoss");
            this.observer = Objects.requireNonNull(observer, "observer");
            this.acknowledgementArmed = new AtomicBoolean(
                    acknowledgementLoss != AcknowledgementLoss.NONE);
        }

        @Override
        public Connection getConnection() throws SQLException {
            int request = connectionRequests.incrementAndGet();
            if (failNextConnection.compareAndSet(true, false)) {
                failedConnectionRequests.add(request);
                throw new SQLException(CANARY + "_CONNECTION", "08006");
            }
            return wrap(super.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            int request = connectionRequests.incrementAndGet();
            if (failNextConnection.compareAndSet(true, false)) {
                failedConnectionRequests.add(request);
                throw new SQLException(CANARY + "_CONNECTION", "08006");
            }
            return wrap(super.getConnection(username, password));
        }

        void failNextConnection() {
            if (!failNextConnection.compareAndSet(false, true)) {
                throw new IllegalStateException("Ya existe un fallo de conexión armado");
            }
        }

        boolean acknowledgementWasLost() {
            return acknowledgementLoss != AcknowledgementLoss.NONE
                    && !acknowledgementArmed.get();
        }

        int connectionRequests() {
            return connectionRequests.get();
        }

        int killedCommitPid() {
            return killedCommitPid.get();
        }

        CommitSessionFailure commitSessionFailure() {
            return commitSessionFailure.get();
        }

        int leaseId(Connection connection) {
            Integer leaseId = leaseIds.get(connection);
            if (leaseId == null) {
                throw new IllegalStateException("La conexión no pertenece al intento 9D2");
            }
            return leaseId;
        }

        List<Integer> closedLeases() {
            return List.copyOf(closedLeases);
        }

        List<Integer> failedConnectionRequests() {
            return List.copyOf(failedConnectionRequests);
        }

        List<LeaseAcquisition> acquisitions() {
            return List.copyOf(acquisitions);
        }

        private Connection wrap(Connection target) {
            int leaseId = nextLease.incrementAndGet();
            acquisitions.add(new LeaseAcquisition(leaseId, List.copyOf(closedLeases)));
            Connection proxy = (Connection) Proxy.newProxyInstance(
                    LegalEditorialApplyFailureIT.class.getClassLoader(),
                    new Class<?>[]{Connection.class},
                    (candidate, method, arguments) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return switch (method.getName()) {
                                case "equals" -> candidate == arguments[0];
                                case "hashCode" -> System.identityHashCode(candidate);
                                case "toString" -> "legal-apply-failure-lease-" + leaseId;
                                default -> throw new IllegalStateException(
                                        "Método Object inesperado en el proxy JDBC");
                            };
                        }
                        boolean killCommitSession = acknowledgementArmed.get()
                                && acknowledgementLoss
                                        == AcknowledgementLoss.COMMIT_SESSION_KILL
                                && method.getParameterCount() == 0
                                && "commit".equals(method.getName());
                        if (killCommitSession
                                && acknowledgementArmed.compareAndSet(true, false)) {
                            int backendPid = readBackendPid(target);
                            killedCommitPid.set(backendPid);
                            Boolean terminated = observer.queryForObject(
                                    "SELECT pg_catalog.pg_terminate_backend(?)",
                                    Boolean.class,
                                    backendPid);
                            assertThat(terminated).isTrue();
                            awaitValue("sesión mutante terminada en commit", () -> {
                                Integer sessions = observer.queryForObject("""
                                        SELECT count(*)::integer
                                          FROM pg_catalog.pg_stat_activity
                                         WHERE pid = ?
                                        """, Integer.class, backendPid);
                                return Objects.requireNonNull(sessions) == 0
                                        ? Optional.of(Boolean.TRUE)
                                        : Optional.empty();
                            });
                            try {
                                invoke(target, method, arguments);
                            } catch (SQLException failure) {
                                SQLException exposed = new SQLException(
                                        CANARY + "_COMMIT_SESSION_KILLED",
                                        "08006",
                                        failure);
                                commitSessionFailure.set(new CommitSessionFailure(
                                        failure,
                                        exposed));
                                throw exposed;
                            }
                            throw new SQLException(
                                    CANARY + "_COMMIT_SESSION_REMAINED_AVAILABLE",
                                    "08006");
                        }
                        boolean losesAcknowledgement = acknowledgementArmed.get()
                                && method.getParameterCount() == 0
                                && ((acknowledgementLoss == AcknowledgementLoss.COMMIT
                                        && "commit".equals(method.getName()))
                                    || (acknowledgementLoss == AcknowledgementLoss.ROLLBACK
                                        && "rollback".equals(method.getName())));
                        if (losesAcknowledgement) {
                            Object result = invoke(target, method, arguments);
                            if (acknowledgementArmed.compareAndSet(true, false)) {
                                throw new SQLException(
                                        CANARY + "_ACK_" + acknowledgementLoss,
                                        "08006");
                            }
                            return result;
                        }
                        if ("close".equals(method.getName())
                                && method.getParameterCount() == 0) {
                            Object result = invoke(target, method, arguments);
                            if (!closedLeases.contains(leaseId)) {
                                closedLeases.add(leaseId);
                            }
                            return result;
                        }
                        return invoke(target, method, arguments);
                    });
            leaseIds.put(proxy, leaseId);
            return proxy;
        }

        private static int readBackendPid(Connection connection) throws SQLException {
            try (var statement = connection.createStatement();
                    var result = statement.executeQuery("SELECT pg_backend_pid()")) {
                if (!result.next()) {
                    throw new SQLException("PostgreSQL no devolvió el PID del commit");
                }
                int pid = result.getInt(1);
                if (result.next()) {
                    throw new SQLException("PostgreSQL devolvió más de un PID del commit");
                }
                return pid;
            }
        }

        private static Object invoke(
                Connection target,
                java.lang.reflect.Method method,
                Object[] arguments) throws Throwable {
            try {
                return method.invoke(target, arguments);
            } catch (InvocationTargetException invocationFailure) {
                throw invocationFailure.getCause();
            }
        }
    }
}
