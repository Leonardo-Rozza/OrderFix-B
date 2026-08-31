package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialReadiness;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalEditorialITFixture.EditorialSnapshot;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalEditorialITFixture.ImportedRelease;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalEditorialITFixture.ReplaceFixture;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.ApplyHarness;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.CommitAcknowledgementLostDataSource;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.EditorialLockHolder;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.Harness;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.PublicationRowLockHolder;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.applyHarness;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.assertEditorialConfirmed;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.assertEditorialKnownFailure;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.assertEditorialUnknown;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.awaitBackendGone;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.awaitCondition;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.awaitLatch;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.awaitValue;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.blockedEditorialGatePid;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.blockedPublicationForUpdatePid;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.cleanLegalState;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.directDataSource;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.hasBidirectionalWaitCycle;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.holdEditorialLock;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.holdPublicationRow;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.migrate;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.pooledDataSource;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.restrictedApplyHarness;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.restrictedHarness;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.terminateBackend;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** PostgreSQL accreditation for recoverable and indeterminate editorial failures. */
@Testcontainers
class LegalEditorialFailureIT {

    private static final String IMPORT_ROLE = "ordenfix_legal_import_failure_10b_it";
    private static final String IMPORT_PASSWORD = "import-failure-10b-must-not-leak";
    private static final String EDITORIAL_ROLE = "ordenfix_legal_editorial_failure_10b_it";
    private static final String EDITORIAL_PASSWORD = "editorial-failure-10b-must-not-leak";
    private static final String IMPORT_APPLICATION_NAME =
            "ordenfix-legal-failure-10b-import";
    private static final String EDITORIAL_APPLICATION_NAME =
            "ordenfix-legal-failure-10b-editorial";
    private static final String OWNER_APPLICATION_NAME =
            "ordenfix-legal-failure-10b-owner";
    private static final String EXTERNAL_APPLICATION_NAME =
            "ordenfix-legal-failure-10b-external";
    private static final String RECONCILIATION_CANARY =
            "CANARY_10B_RECONCILIATION_MUST_NOT_LEAK";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_editorial_failure_10b")
            .withUsername("ordenfix")
            .withPassword("ordenfix");

    private static HikariDataSource importPool;
    private static HikariDataSource editorialPool;
    private static DataSource externalDataSource;
    private static JdbcTemplate owner;
    private static Harness importer;
    private static ApplyHarness production;
    private static LegalRestrictedImportRoleFixture importRoleFixture;
    private static LegalRestrictedEditorialRoleFixture editorialRoleFixture;

    @TempDir
    private Path temporaryDirectory;

    private LegalEditorialITFixture fixture;

    @BeforeAll
    static void migrateProvisionAndAssemble() {
        migrate(POSTGRES);
        DataSource ownerDataSource = directDataSource(POSTGRES, OWNER_APPLICATION_NAME);
        externalDataSource = directDataSource(POSTGRES, EXTERNAL_APPLICATION_NAME);
        owner = new JdbcTemplate(ownerDataSource);

        importRoleFixture = new LegalRestrictedImportRoleFixture(
                owner,
                POSTGRES.getJdbcUrl(),
                IMPORT_ROLE,
                IMPORT_PASSWORD,
                POSTGRES.getDriverClassName());
        LegalRestrictedImportRoleFixture.Credentials importCredentials =
                importRoleFixture.provisionAndVerify();
        editorialRoleFixture = new LegalRestrictedEditorialRoleFixture(
                owner,
                POSTGRES.getJdbcUrl(),
                EDITORIAL_ROLE,
                EDITORIAL_PASSWORD,
                POSTGRES.getDriverClassName());
        LegalRestrictedEditorialRoleFixture.Credentials editorialCredentials =
                editorialRoleFixture.provisionAndVerify();

        importPool = pooledDataSource(
                POSTGRES,
                IMPORT_APPLICATION_NAME,
                importCredentials.username(),
                importCredentials.password());
        editorialPool = pooledDataSource(
                POSTGRES,
                EDITORIAL_APPLICATION_NAME,
                editorialCredentials.username(),
                editorialCredentials.password());
        importer = restrictedHarness(
                importPool,
                LegalDatabaseBudgets.production(),
                IMPORT_ROLE);
        production = restrictedApplyHarness(
                editorialPool,
                LegalDatabaseBudgets.production(),
                EDITORIAL_ROLE);
    }

    @AfterAll
    static void closePools() {
        if (editorialPool != null) {
            editorialPool.close();
        }
        if (importPool != null) {
            importPool.close();
        }
    }

    @BeforeEach
    void cleanRecheckAndBuildFixture() {
        cleanLegalState(owner);
        importRoleFixture.verify();
        editorialRoleFixture.verify();
        fixture = new LegalEditorialITFixture(
                temporaryDirectory,
                LegalEditorialFailureIT.class,
                owner,
                importer,
                production);
    }

    @Test
    void advisoryTimeoutIs55P03AndRetryConvergesWithoutDuplicateWrites()
            throws Exception {
        ImportedRelease target = fixture.importedDraft("failure-10b-timeout-v1");
        ObservedHarness attempt = observedHarness(
                editorialPool,
                new LegalDatabaseBudgets(5, 2, 1, 1));
        LegalInitialPromotionCore writer = spy(attempt.harness().promotionCore());
        FailureProbe failure = failureProbe();
        LegalEditorialApplyService service = service(
                attempt.harness(),
                writer,
                attempt.harness().replacementWriter(),
                attempt.harness().commitReconciler(),
                failure.mapper());
        EditorialSnapshot before = fixture.snapshot();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<LegalEditorialApplyResult> pending = null;

        try (EditorialLockHolder holder = holdEditorialLock(externalDataSource)) {
            pending = executor.submit(() -> service.applyPromote(target.release()));
            int victimPid = awaitValue(
                    "apply editorial esperando el advisory lock retenido",
                    () -> blockedEditorialGatePid(
                            owner,
                            EDITORIAL_APPLICATION_NAME,
                            holder.backendPid()));
            assertThat(victimPid).isPositive().isNotEqualTo(holder.backendPid());

            LegalEditorialApplyResult timedOut = pending.get(15, TimeUnit.SECONDS);

            assertEditorialKnownFailure(
                    timedOut,
                    LegalManifestStatus.ERROR,
                    LegalManifestIssueCode.CONCURRENT_OPERATION);
            assertThat(timedOut.issues().getFirst().location())
                    .isEqualTo("database/concurrency");
            assertThat(failure.sqlState()).isEqualTo("55P03");
            assertThat(attempt.jdbc().lockAttempts()).singleElement().satisfies(lock -> {
                assertThat(lock.backendPid()).isEqualTo(victimPid);
                assertThat(lock.readOnly()).isFalse();
                assertThat(lock.success()).isFalse();
                assertThat(lock.failureSqlState()).isEqualTo("55P03");
            });
            verify(writer, never()).write(any());
            assertThat(attempt.jdbc().dmlExecutions()).isZero();
            assertThat(fixture.snapshot()).isEqualTo(before);
            holder.release();
        } finally {
            cancel(pending);
            shutdown(executor);
        }

        LegalEditorialApplyResult retry = service.applyPromote(target.release());
        assertEditorialConfirmed(retry, LegalEditorialApplyResult.Outcome.APPLIED);
        assertExactReceipt(
                retry,
                target,
                LegalEditorialApplyReceipt.OperationType.PROMOTE,
                attempt.harness());
        verify(writer, times(1)).write(any());
        int dmlAfterRetry = attempt.jdbc().dmlExecutions();
        assertThat(dmlAfterRetry).isPositive();
        EditorialSnapshot afterRetry = fixture.snapshot();

        LegalEditorialApplyResult replay = service.applyPromote(target.release());

        assertEditorialConfirmed(replay, LegalEditorialApplyResult.Outcome.ALREADY_APPLIED);
        assertThat(replay.receipt()).isEqualTo(retry.receipt());
        verify(writer, times(1)).write(any());
        assertThat(attempt.jdbc().dmlExecutions()).isEqualTo(dmlAfterRetry);
        assertThat(fixture.snapshot()).isEqualTo(afterRetry);
    }

    @Test
    void realReplaceDeadlockIs40P01AndRetryConvergesWithoutDuplicateWrites()
            throws Exception {
        ImportedRelease source = fixture.readyRelease("failure-10b-deadlock-source-v1");
        ImportedRelease target = fixture.importedDocumentRevision(
                "failure-10b-deadlock-target-v1",
                "cierre-cuenta",
                "failure-10b-deadlock-v2");
        ReplaceFixture replacement = fixture.replacementPlan(
                source,
                target,
                "failure-10b-deadlock");
        ObservedHarness attempt = observedHarness(
                editorialPool,
                LegalDatabaseBudgets.production());
        LegalDocumentReplacementWriter writer = spy(
                attempt.harness().replacementWriter());
        FailureProbe failure = failureProbe();
        LegalEditorialApplyService service = service(
                attempt.harness(),
                attempt.harness().promotionCore(),
                writer,
                attempt.harness().commitReconciler(),
                failure.mapper());
        EditorialSnapshot before = fixture.snapshot();
        PublicationLockCheckpoint checkpoint = attempt.jdbc()
                .pauseNextPublicationLock();
        PublicationRowLockHolder holder = holdPublicationRow(
                externalDataSource,
                target.publicationId());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<LegalEditorialApplyResult> applyFuture = null;
        Future<?> lockFuture = null;

        try {
            applyFuture = executor.submit(() -> service.applyReplace(
                    target.release(),
                    replacement.plan()));
            awaitLatch(
                    checkpoint.reached(),
                    "REPLACE antes del FOR UPDATE de publicaciones");
            int victimPid = checkpoint.backendPid().get();
            assertThat(victimPid).isPositive().isNotEqualTo(holder.backendPid());

            lockFuture = executor.submit(() -> {
                holder.acquireEditorialLock();
                return null;
            });
            int waitingHolderPid = awaitValue(
                    "holder externo esperando el advisory del REPLACE",
                    () -> blockedEditorialGatePid(
                            owner,
                            EXTERNAL_APPLICATION_NAME,
                            victimPid));
            assertThat(waitingHolderPid).isEqualTo(holder.backendPid());
            checkpoint.release().countDown();
            awaitCondition(
                    "ciclo bidireccional real entre row lock y advisory lock",
                    () -> hasBidirectionalWaitCycle(
                            owner,
                            victimPid,
                            holder.backendPid()));

            lockFuture.get(15, TimeUnit.SECONDS);
            holder.release();
            LegalEditorialApplyResult deadlocked = applyFuture.get(15, TimeUnit.SECONDS);

            assertEditorialKnownFailure(
                    deadlocked,
                    LegalManifestStatus.ERROR,
                    LegalManifestIssueCode.CONCURRENT_OPERATION);
            assertThat(deadlocked.issues().getFirst().location())
                    .isEqualTo("database/concurrency");
            assertThat(failure.sqlState()).isEqualTo("40P01");
            assertThat(fixture.snapshot()).isEqualTo(before);
            verify(writer, times(1)).write(any());
            assertThat(attempt.jdbc().dmlExecutions()).isZero();
        } finally {
            checkpoint.release().countDown();
            terminatePendingBackend(
                    applyFuture,
                    checkpoint.backendPid().get() > 0
                            ? checkpoint.backendPid().get()
                            : attempt.jdbc().mutableBackendPid());
            joinBeforeConnectionRelease(lockFuture);
            holder.release();
            cancel(applyFuture);
            cancel(lockFuture);
            shutdown(executor);
        }

        LegalEditorialApplyResult retry = service.applyReplace(
                target.release(),
                replacement.plan());
        assertEditorialConfirmed(retry, LegalEditorialApplyResult.Outcome.APPLIED);
        assertExactReceipt(
                retry,
                target,
                LegalEditorialApplyReceipt.OperationType.REPLACE,
                attempt.harness());
        assertThat(retry.receipt().orElseThrow().documentVersions())
                .isEqualTo(target.release().documentCount());
        assertThat(retry.receipt().orElseThrow().requirementVersions())
                .isEqualTo(target.release().requirementCount());
        assertThat(retry.receipt().orElseThrow().replacementBatches())
                .isEqualTo(replacement.replacementBatchIds().size());
        verify(writer, times(2)).write(any());
        int dmlAfterRetry = attempt.jdbc().dmlExecutions();
        assertThat(dmlAfterRetry).isPositive();
        EditorialSnapshot afterRetry = fixture.snapshot();

        LegalEditorialApplyResult replay = service.applyReplace(
                target.release(),
                replacement.plan());

        assertEditorialConfirmed(replay, LegalEditorialApplyResult.Outcome.ALREADY_APPLIED);
        assertThat(replay.receipt()).isEqualTo(retry.receipt());
        verify(writer, times(2)).write(any());
        assertThat(attempt.jdbc().dmlExecutions()).isEqualTo(dmlAfterRetry);
        assertThat(fixture.snapshot()).isEqualTo(afterRetry);
    }

    @Test
    void killedPreCommitSessionReconcilesSourceExactAndRetryAppliesOnce()
            throws Exception {
        ImportedRelease target = fixture.importedDraft("failure-10b-session-kill-v1");
        ObservedHarness attempt = observedHarness(
                editorialPool,
                LegalDatabaseBudgets.production());
        LegalInitialPromotionCore writer = spy(attempt.harness().promotionCore());
        ReconciliationProbe reconciliation = observePromoteReconciliation(
                attempt.harness(),
                target.release());
        FailureProbe failure = failureProbe();
        LegalEditorialApplyService service = service(
                attempt.harness(),
                writer,
                attempt.harness().replacementWriter(),
                reconciliation.reconciler(),
                failure.mapper());
        EditorialSnapshot before = fixture.snapshot();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<LegalEditorialApplyResult> pending = null;

        try (PublicationRowLockHolder holder = holdPublicationRow(
                externalDataSource,
                target.publicationId())) {
            pending = executor.submit(() -> service.applyPromote(target.release()));
            int terminatedPid = awaitValue(
                    "PROMOTE real bloqueado en la publicación antes del commit",
                    () -> blockedPublicationForUpdatePid(
                            owner,
                            EDITORIAL_APPLICATION_NAME,
                            holder.backendPid()));
            assertThat(terminatedPid).isPositive().isNotEqualTo(holder.backendPid());
            assertThat(terminateBackend(owner, terminatedPid)).isTrue();
            awaitBackendGone(owner, terminatedPid);
            holder.release();

            LegalEditorialApplyResult killed = pending.get(15, TimeUnit.SECONDS);

            assertEditorialKnownFailure(
                    killed,
                    LegalManifestStatus.ERROR,
                    LegalManifestIssueCode.EDITORIAL_OBSERVATION_FAILED);
            assertNoTentativeMetadata(killed);
            reconciliation.assertOutcome(
                    target.release(),
                    LegalEditorialCommitReconciler.Outcome.SOURCE_EXACT);
            assertThat(failure.sqlState()).isNotNull();
            assertThat(fixture.snapshot()).isEqualTo(before);
            verify(writer, times(1)).write(any());
            assertThat(attempt.jdbc().dmlExecutions()).isZero();
            assertThat(attempt.jdbc().lockAttempts()).hasSize(2);
            BoundaryLockAttempt mutable = attempt.jdbc().lockAttempts().get(0);
            BoundaryLockAttempt readOnly = attempt.jdbc().lockAttempts().get(1);
            assertThat(mutable.backendPid()).isEqualTo(terminatedPid);
            assertThat(mutable.readOnly()).isFalse();
            assertThat(readOnly.backendPid()).isPositive().isNotEqualTo(terminatedPid);
            assertThat(readOnly.readOnly()).isTrue();
        } finally {
            cancel(pending);
            shutdown(executor);
        }

        LegalEditorialApplyResult retry = service
                .applyPromote(target.release());
        assertEditorialConfirmed(retry, LegalEditorialApplyResult.Outcome.APPLIED);
        assertExactReceipt(
                retry,
                target,
                LegalEditorialApplyReceipt.OperationType.PROMOTE,
                attempt.harness());
        verify(writer, times(2)).write(any());
        int dmlAfterRetry = attempt.jdbc().dmlExecutions();
        assertThat(dmlAfterRetry).isPositive();
        EditorialSnapshot afterRetry = fixture.snapshot();

        LegalEditorialApplyResult replay = service
                .applyPromote(target.release());

        assertEditorialConfirmed(replay, LegalEditorialApplyResult.Outcome.ALREADY_APPLIED);
        assertThat(replay.receipt()).isEqualTo(retry.receipt());
        assertThat(fixture.snapshot()).isEqualTo(afterRetry);
        verify(writer, times(2)).write(any());
        assertThat(attempt.jdbc().dmlExecutions()).isEqualTo(dmlAfterRetry);
    }

    @Test
    void lostCommitAcknowledgementReconcilesExactReceiptAndReplayIsSelectOnly()
            throws Exception {
        ImportedRelease target = fixture.importedDraft("failure-10b-ack-exact-v1");
        CommitAcknowledgementLostDataSource ambiguousDataSource =
                new CommitAcknowledgementLostDataSource(editorialPool);
        ObservedHarness attempt = observedHarness(
                ambiguousDataSource,
                LegalDatabaseBudgets.production());
        LegalInitialPromotionCore writer = spy(attempt.harness().promotionCore());
        ReconciliationProbe reconciliation = observePromoteReconciliation(
                attempt.harness(),
                target.release());
        LegalEditorialApplyService service = service(
                attempt.harness(),
                writer,
                attempt.harness().replacementWriter(),
                reconciliation.reconciler(),
                new LegalEditorialFailureMapper());
        EditorialSnapshot before = fixture.snapshot();

        LegalEditorialApplyResult first = service.applyPromote(target.release());

        assertEditorialConfirmed(first, LegalEditorialApplyResult.Outcome.ALREADY_APPLIED);
        assertThat(ambiguousDataSource.armed()).isFalse();
        reconciliation.assertOutcome(
                target.release(),
                LegalEditorialCommitReconciler.Outcome.POST_EXACT);
        assertThat(reconciliation.receipt()).contains(first.receipt().orElseThrow());
        assertExactReceipt(
                first,
                target,
                LegalEditorialApplyReceipt.OperationType.PROMOTE,
                attempt.harness());
        assertThat(attempt.jdbc().lockAttempts()).hasSize(2);
        assertThat(attempt.jdbc().lockAttempts())
                .extracting(BoundaryLockAttempt::readOnly)
                .containsExactly(false, true);
        assertThat(attempt.jdbc().lockAttempts())
                .extracting(BoundaryLockAttempt::backendPid)
                .allSatisfy(pid -> assertThat(pid).isPositive());
        verify(writer, times(1)).write(any());
        assertThat(fixture.snapshot()).isNotEqualTo(before);
        int dmlAfterCommit = attempt.jdbc().dmlExecutions();
        assertThat(dmlAfterCommit).isPositive();
        EditorialSnapshot afterCommit = fixture.snapshot();

        LegalEditorialApplyResult replay = service.applyPromote(target.release());

        assertEditorialConfirmed(replay, LegalEditorialApplyResult.Outcome.ALREADY_APPLIED);
        assertThat(replay.receipt()).isEqualTo(first.receipt());
        verify(writer, times(1)).write(any());
        assertThat(attempt.jdbc().dmlExecutions()).isEqualTo(dmlAfterCommit);
        assertThat(fixture.snapshot()).isEqualTo(afterCommit);
        assertThat(attempt.jdbc().lockAttempts()).hasSize(3);
        assertThat(attempt.jdbc().lockAttempts())
                .extracting(BoundaryLockAttempt::readOnly)
                .containsExactly(false, true, false);
    }

    @Test
    void lostCommitAcknowledgementWithUnavailableReadOnlyReconciliationIsUnknownAndRedacted()
            throws Exception {
        ImportedRelease target = fixture.importedDraft("failure-10b-ack-unknown-v1");
        CommitAcknowledgementLostDataSource ambiguousDataSource =
                new CommitAcknowledgementLostDataSource(editorialPool);
        FailureObservingJdbcTemplate jdbc = new FailureObservingJdbcTemplate(
                ambiguousDataSource);
        jdbc.failNextReadOnlyTimeBoundary();
        ObservedHarness attempt = observedHarness(
                jdbc,
                LegalDatabaseBudgets.production());
        LegalInitialPromotionCore writer = spy(attempt.harness().promotionCore());
        ReconciliationProbe reconciliation = observePromoteReconciliation(
                attempt.harness(),
                target.release());
        LegalEditorialApplyService service = service(
                attempt.harness(),
                writer,
                attempt.harness().replacementWriter(),
                reconciliation.reconciler(),
                new LegalEditorialFailureMapper());
        EditorialSnapshot before = fixture.snapshot();

        LegalEditorialApplyResult unknown = service.applyPromote(target.release());

        assertEditorialUnknown(unknown);
        assertNoTentativeMetadata(unknown);
        assertThat(unknown.omittedIssueCount()).isZero();
        assertThat(unknown.issues().getFirst().location()).isEqualTo("database/commit");
        assertThat(unknown.issues().toString()).doesNotContain(RECONCILIATION_CANARY);
        assertThat(ambiguousDataSource.armed()).isFalse();
        assertThat(jdbc.readOnlyTimeBoundaryFailureWasInjected()).isTrue();
        reconciliation.assertOutcome(
                target.release(),
                LegalEditorialCommitReconciler.Outcome.UNKNOWN);
        assertThat(reconciliation.receipt()).isEmpty();
        assertThat(jdbc.lockAttempts()).hasSize(2);
        assertThat(jdbc.lockAttempts())
                .extracting(BoundaryLockAttempt::readOnly)
                .containsExactly(false, true);
        assertThat(jdbc.lockAttempts())
                .extracting(BoundaryLockAttempt::backendPid)
                .allSatisfy(pid -> assertThat(pid).isPositive());
        verify(writer, times(1)).write(any());
        EditorialSnapshot committed = fixture.snapshot();
        assertThat(committed).isNotEqualTo(before);
        int dmlAfterCommit = attempt.jdbc().dmlExecutions();
        assertThat(dmlAfterCommit).isPositive();

        LegalEditorialApplyResult retry = service.applyPromote(target.release());

        assertEditorialConfirmed(retry, LegalEditorialApplyResult.Outcome.ALREADY_APPLIED);
        assertExactReceipt(
                retry,
                target,
                LegalEditorialApplyReceipt.OperationType.PROMOTE,
                attempt.harness());
        verify(writer, times(1)).write(any());
        assertThat(attempt.jdbc().dmlExecutions()).isEqualTo(dmlAfterCommit);
        assertThat(fixture.snapshot()).isEqualTo(committed);

        LegalEditorialApplyResult replay = service.applyPromote(target.release());

        assertEditorialConfirmed(replay, LegalEditorialApplyResult.Outcome.ALREADY_APPLIED);
        assertThat(replay.receipt()).isEqualTo(retry.receipt());
        verify(writer, times(1)).write(any());
        assertThat(attempt.jdbc().dmlExecutions()).isEqualTo(dmlAfterCommit);
        assertThat(fixture.snapshot()).isEqualTo(committed);
    }

    private static ObservedHarness observedHarness(
            DataSource dataSource,
            LegalDatabaseBudgets budgets) {
        return observedHarness(new FailureObservingJdbcTemplate(dataSource), budgets);
    }

    private static ObservedHarness observedHarness(
            FailureObservingJdbcTemplate jdbc,
            LegalDatabaseBudgets budgets) {
        LegalEditorialSchemaVerifier schemaVerifier = new LegalEditorialSchemaVerifier(
                jdbc,
                LegalV27EditorialInventory.DEFAULT_SCHEMA);
        LegalEditorialPrivilegeVerifier privilegeVerifier =
                new LegalEditorialPrivilegeVerifier(
                        jdbc,
                        EDITORIAL_ROLE,
                        LegalV27EditorialInventory.DEFAULT_SCHEMA);
        ApplyHarness harness = applyHarness(
                jdbc,
                budgets,
                schemaVerifier,
                privilegeVerifier);
        return new ObservedHarness(jdbc, harness);
    }

    private static LegalEditorialApplyService service(
            ApplyHarness harness,
            LegalEditorialMutationWriter promotionWriter,
            LegalEditorialMutationWriter replacementWriter,
            LegalEditorialCommitReconciler reconciler,
            LegalEditorialFailureMapper mapper) {
        return new LegalEditorialApplyService(
                harness.gate(),
                harness.jdbc(),
                harness.plannerCore(),
                promotionWriter,
                replacementWriter,
                harness.retirementWriter(),
                harness.postStateVerifier(),
                harness.readinessCore(),
                harness.replaceScopeGuard(),
                reconciler,
                mapper,
                harness.schemaVerifier(),
                harness.privilegeVerifier());
    }

    private static FailureProbe failureProbe() {
        LegalEditorialFailureMapper mapper = spy(new LegalEditorialFailureMapper());
        AtomicReference<String> sqlState = new AtomicReference<>();
        doAnswer(invocation -> {
            sqlState.set(findSqlState(invocation.getArgument(0)));
            return invocation.callRealMethod();
        }).when(mapper).map(any());
        return new FailureProbe(mapper, sqlState);
    }

    private static ReconciliationProbe observePromoteReconciliation(
            ApplyHarness harness,
            ValidatedRelease target) {
        LegalEditorialCommitReconciler reconciler = spy(harness.commitReconciler());
        AtomicInteger invocations = new AtomicInteger();
        AtomicReference<LegalEditorialCommitReconciler.Result> observed =
                new AtomicReference<>();
        doAnswer(invocation -> {
            invocations.incrementAndGet();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            assertThat(TransactionSynchronizationManager.isSynchronizationActive())
                    .isFalse();
            assertThat(TransactionSynchronizationManager.hasResource(harness.dataSource()))
                    .isFalse();
            LegalEditorialCommitReconciler.Result result =
                    (LegalEditorialCommitReconciler.Result) invocation.callRealMethod();
            observed.set(result);
            return result;
        }).when(reconciler).reconcilePromote(eq(target), eq(true));
        return new ReconciliationProbe(reconciler, invocations, observed);
    }

    private static void assertExactReceipt(
            LegalEditorialApplyResult result,
            ImportedRelease target,
            LegalEditorialApplyReceipt.OperationType operation,
            ApplyHarness harness) {
        LegalEditorialApplyReceipt receipt = result.receipt().orElseThrow();
        assertThat(receipt.operationType()).isEqualTo(operation);
        assertThat(receipt.targetPublicationUuid()).isEqualTo(target.publicationId());
        assertThat(receipt.readinessAfter()).isEqualTo(LegalEditorialReadiness.READY);
        OffsetDateTime persistedAppliedAt = owner.queryForObject("""
                SELECT max(dt.ocurrido_en)
                  FROM legal_documento_transiciones dt
                  JOIN legal_publicacion_documentos pd
                    ON pd.documento_version_id = dt.documento_version_id
                 WHERE pd.publicacion_id = ?
                """, OffsetDateTime.class, target.publicationId());
        assertThat(persistedAppliedAt).isNotNull();
        assertThat(receipt.appliedAt())
                .isEqualTo(Objects.requireNonNull(persistedAppliedAt).toInstant());

        LegalEditorialReadinessResult readiness = harness.readinessCore().evaluate(
                target.release(),
                receipt.appliedAt());
        assertThat(readiness.readiness()).isEqualTo(LegalEditorialReadiness.READY);
        LegalEditorialReadinessObservation observed = readiness.observation().orElseThrow();
        assertThat(observed.publicationUuid()).contains(target.publicationId());
        if (operation == LegalEditorialApplyReceipt.OperationType.PROMOTE) {
            assertThat(receipt.documentVersions()).isEqualTo(observed.documentVersions());
            assertThat(receipt.requirementVersions()).isEqualTo(observed.requirementVersions());
            assertThat(receipt.documentTransitions()).isEqualTo(observed.documentTransitions());
            assertThat(receipt.requirementTransitions())
                    .isEqualTo(observed.requirementTransitions());
            assertThat(receipt.documentSlots()).isEqualTo(observed.documentSlots());
            assertThat(receipt.requiredSetPointers())
                    .isEqualTo(observed.currentRequirementSets());
            assertThat(receipt.replacementBatches()).isEqualTo(observed.replacementLots());
        }
    }

    private static void assertNoTentativeMetadata(LegalEditorialApplyResult result) {
        assertThat(result.receipt()).isEmpty();
        assertThat(result.operationType()).isEmpty();
        assertThat(result.targetPublicationUuid()).isEmpty();
        assertThat(result.appliedAt()).isEmpty();
        assertThat(result.readinessAfter()).isEmpty();
    }

    private static String findSqlState(Throwable root) {
        Deque<Throwable> pending = new ArrayDeque<>();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        pending.add(Objects.requireNonNull(root, "root"));
        while (!pending.isEmpty()) {
            Throwable current = pending.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            if (current instanceof SQLException sqlException
                    && sqlException.getSQLState() != null) {
                return sqlException.getSQLState();
            }
            if (current instanceof TransactionSystemException transactionFailure
                    && transactionFailure.getApplicationException() != null) {
                pending.addLast(transactionFailure.getApplicationException());
            }
            if (current.getCause() != null) {
                pending.addLast(current.getCause());
            }
            Collections.addAll(pending, current.getSuppressed());
            if (current instanceof SQLException sqlException
                    && sqlException.getNextException() != null) {
                pending.addLast(sqlException.getNextException());
            }
        }
        return null;
    }

    private static void cancel(Future<?> future) {
        if (future != null) {
            future.cancel(true);
        }
    }

    private static void terminatePendingBackend(
            Future<?> future,
            int backendPid) {
        if (future == null || future.isDone()) {
            return;
        }
        List<Integer> candidates = backendPid > 0
                ? List.of(backendPid)
                : owner.queryForList("""
                        SELECT pid
                          FROM pg_catalog.pg_stat_activity
                         WHERE datname = pg_catalog.current_database()
                           AND application_name = ?
                           AND pid <> pg_catalog.pg_backend_pid()
                         ORDER BY pid
                        """, Integer.class, EDITORIAL_APPLICATION_NAME);
        for (Integer candidate : candidates) {
            int pid = Objects.requireNonNull(candidate, "backend PID de cleanup");
            if (terminateBackend(owner, pid)) {
                awaitBackendGone(owner, pid);
            }
        }
    }

    private static void joinBeforeConnectionRelease(Future<?> future) {
        if (future == null) {
            return;
        }
        try {
            future.get(10, TimeUnit.SECONDS);
        } catch (CancellationException | ExecutionException completed) {
            // The worker no longer owns the holder connection.
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Cleanup del holder editorial interrumpido",
                    interrupted);
        } catch (TimeoutException timedOut) {
            throw new AssertionError(
                    "El worker conservó la conexión del holder durante el cleanup",
                    timedOut);
        }
    }

    private static void shutdown(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    private record ObservedHarness(
            FailureObservingJdbcTemplate jdbc,
            ApplyHarness harness
    ) { }

    private record FailureProbe(
            LegalEditorialFailureMapper mapper,
            AtomicReference<String> observedSqlState
    ) {

        String sqlState() {
            return observedSqlState.get();
        }
    }

    private record ReconciliationProbe(
            LegalEditorialCommitReconciler reconciler,
            AtomicInteger invocations,
            AtomicReference<LegalEditorialCommitReconciler.Result> observed
    ) {

        void assertOutcome(
                ValidatedRelease target,
                LegalEditorialCommitReconciler.Outcome outcome) {
            verify(reconciler, times(1)).reconcilePromote(target, true);
            verify(reconciler, never()).reconcileReplace(any(), any(), anyBoolean());
            verify(reconciler, never()).reconcileRetire(any(), any(), anyBoolean());
            assertThat(invocations.get()).isOne();
            assertThat(observed.get()).isNotNull();
            assertThat(observed.get().outcome()).isEqualTo(outcome);
        }

        Optional<LegalEditorialApplyReceipt> receipt() {
            return observed.get() == null ? Optional.empty() : observed.get().receipt();
        }
    }

    private record BoundaryLockAttempt(
            int backendPid,
            boolean readOnly,
            boolean success,
            String failureSqlState
    ) { }

    private record PublicationLockCheckpoint(
            CountDownLatch reached,
            CountDownLatch release,
            AtomicInteger backendPid
    ) { }

    /** Observes transaction PIDs and can fail only the fresh read-only time boundary. */
    private static final class FailureObservingJdbcTemplate extends JdbcTemplate {

        private final DataSource dataSource;
        private final AtomicBoolean failReadOnlyTimeBoundary = new AtomicBoolean();
        private final AtomicBoolean readOnlyTimeBoundaryFailureInjected =
                new AtomicBoolean();
        private final AtomicInteger dmlExecutions = new AtomicInteger();
        private final AtomicInteger mutableBackendPid = new AtomicInteger();
        private final AtomicReference<PublicationLockCheckpoint>
                publicationLockCheckpoint = new AtomicReference<>();
        private final List<BoundaryLockAttempt> lockAttempts =
                new CopyOnWriteArrayList<>();

        private FailureObservingJdbcTemplate(DataSource dataSource) {
            super(Objects.requireNonNull(dataSource, "dataSource"));
            this.dataSource = dataSource;
        }

        @Override
        public void execute(String sql) {
            recordDml(sql);
            super.execute(sql);
        }

        @Override
        public int update(String sql) {
            recordDml(sql);
            return super.update(sql);
        }

        @Override
        public int update(String sql, Object... args) {
            recordDml(sql);
            return super.update(sql, args);
        }

        @Override
        public int[] batchUpdate(String... sql) {
            for (String statement : sql) {
                recordDml(statement);
            }
            return super.batchUpdate(sql);
        }

        @Override
        public int[] batchUpdate(String sql, List<Object[]> batchArgs) {
            recordDml(sql);
            return super.batchUpdate(sql, batchArgs);
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            pauseAtPublicationLock(sql);
            if (!sql.contains("pg_advisory_xact_lock")) {
                return super.queryForList(sql, args);
            }
            Connection connection = DataSourceUtils.getConnection(dataSource);
            try {
                int backendPid = Objects.requireNonNull(super.queryForObject(
                        "SELECT pg_backend_pid()",
                        Integer.class));
                boolean readOnly = TransactionSynchronizationManager
                        .isCurrentTransactionReadOnly();
                if (!readOnly) {
                    mutableBackendPid.compareAndSet(0, backendPid);
                }
                try {
                    List<Map<String, Object>> result = super.queryForList(sql, args);
                    lockAttempts.add(new BoundaryLockAttempt(
                            backendPid,
                            readOnly,
                            true,
                            null));
                    return result;
                } catch (RuntimeException | LinkageError failure) {
                    lockAttempts.add(new BoundaryLockAttempt(
                            backendPid,
                            readOnly,
                            false,
                            findSqlState(failure)));
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
                    && failReadOnlyTimeBoundary.compareAndSet(true, false)) {
                readOnlyTimeBoundaryFailureInjected.set(true);
                throw new CannotGetJdbcConnectionException(
                        RECONCILIATION_CANARY,
                        new SQLException(RECONCILIATION_CANARY, "08006"));
            }
            return super.queryForObject(sql, requiredType, args);
        }

        void failNextReadOnlyTimeBoundary() {
            if (!failReadOnlyTimeBoundary.compareAndSet(false, true)) {
                throw new IllegalStateException(
                        "Ya existe un fallo read-only pendiente de inyección");
            }
        }

        boolean readOnlyTimeBoundaryFailureWasInjected() {
            return readOnlyTimeBoundaryFailureInjected.get();
        }

        PublicationLockCheckpoint pauseNextPublicationLock() {
            PublicationLockCheckpoint checkpoint = new PublicationLockCheckpoint(
                    new CountDownLatch(1),
                    new CountDownLatch(1),
                    new AtomicInteger());
            if (!publicationLockCheckpoint.compareAndSet(null, checkpoint)) {
                throw new IllegalStateException(
                        "Ya existe un checkpoint de publicación pendiente");
            }
            return checkpoint;
        }

        int dmlExecutions() {
            return dmlExecutions.get();
        }

        int mutableBackendPid() {
            return mutableBackendPid.get();
        }

        List<BoundaryLockAttempt> lockAttempts() {
            return List.copyOf(lockAttempts);
        }

        private void recordDml(String sql) {
            String normalized = Objects.requireNonNull(sql, "sql")
                    .stripLeading()
                    .toUpperCase(java.util.Locale.ROOT);
            if (normalized.startsWith("INSERT ")
                    || normalized.startsWith("UPDATE ")
                    || normalized.startsWith("DELETE ")
                    || normalized.startsWith("MERGE ")) {
                dmlExecutions.incrementAndGet();
            }
        }

        private void pauseAtPublicationLock(String sql) {
            if (!sql.contains("FROM legal_publicaciones")
                    || !sql.contains("FOR UPDATE")) {
                return;
            }
            PublicationLockCheckpoint checkpoint = publicationLockCheckpoint.get();
            if (checkpoint == null
                    || !publicationLockCheckpoint.compareAndSet(checkpoint, null)) {
                return;
            }
            Connection connection = DataSourceUtils.getConnection(dataSource);
            try {
                int backendPid = Objects.requireNonNull(super.queryForObject(
                        "SELECT pg_backend_pid()",
                        Integer.class));
                checkpoint.backendPid().set(backendPid);
                checkpoint.reached().countDown();
                awaitLatch(
                        checkpoint.release(),
                        "REPLACE habilitado para solicitar el row lock");
            } finally {
                DataSourceUtils.releaseConnection(connection, dataSource);
            }
        }
    }
}
