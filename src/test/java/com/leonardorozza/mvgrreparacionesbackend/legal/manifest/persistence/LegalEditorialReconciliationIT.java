package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialReadiness;
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
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.transaction.TransactionSystemException;
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
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.applyHarness;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.assertEditorialConfirmed;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.assertEditorialKnownFailure;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.awaitLatch;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.awaitValue;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.cleanLegalState;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.copyRelease;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.editorialSequenceStates;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.harness;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.migrate;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.pooledDataSource;
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
class LegalEditorialReconciliationIT {

    private static final String APPLY_APPLICATION_NAME =
            "ordenfix-legal-reconciliation-it";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_reconciliation")
            .withUsername("ordenfix")
            .withPassword("ordenfix");

    private static HikariDataSource pool;
    private static JdbcTemplate observer;
    private static Harness importer;
    private static ApplyHarness production;

    @TempDir
    private Path temporaryDirectory;

    @BeforeAll
    static void migrateAndAssemble() {
        migrate(POSTGRES);
        pool = pooledDataSource(POSTGRES, APPLY_APPLICATION_NAME);
        observer = new JdbcTemplate(LegalManifestPersistenceITSupport.directDataSource(
                POSTGRES,
                "ordenfix-legal-reconciliation-observer"));
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
    void lostCommitAcknowledgementRebuildsTheCompleteReceiptFromPostgres()
            throws Exception {
        ImportedRelease target = importedDraft("reconciliation-commit-receipt-v1");
        var ambiguousDataSource =
                new LegalManifestPersistenceITSupport.CommitAcknowledgementLostDataSource(pool);
        ApplyHarness ambiguous = applyHarness(
                ambiguousDataSource,
                LegalDatabaseBudgets.production());
        Map<String, List<String>> rowsBeforeAttempt = editorialTableRows(observer);
        Map<String, LegalManifestPersistenceITSupport.SequenceState> sequencesBeforeAttempt =
                editorialSequenceStates(observer);

        LegalEditorialApplyResult result = ambiguous.service()
                .applyPromote(target.release());

        assertEditorialConfirmed(result, LegalEditorialApplyResult.Outcome.ALREADY_APPLIED);
        assertThat(ambiguousDataSource.armed()).isFalse();
        LegalEditorialApplyReceipt receipt = result.receipt().orElseThrow();
        assertThat(receipt.operationType())
                .isEqualTo(LegalEditorialApplyReceipt.OperationType.PROMOTE);
        assertThat(receipt.targetPublicationUuid()).isEqualTo(target.publicationId());
        assertThat(receipt.readinessAfter()).isEqualTo(LegalEditorialReadiness.READY);
        OffsetDateTime persistedAppliedAt = observer.queryForObject("""
                SELECT max(dt.ocurrido_en)
                  FROM legal_documento_transiciones dt
                  JOIN legal_publicacion_documentos pd
                    ON pd.documento_version_id = dt.documento_version_id
                 WHERE pd.publicacion_id = ?
                """, OffsetDateTime.class, target.publicationId());
        assertThat(persistedAppliedAt).isNotNull();
        assertThat(receipt.appliedAt())
                .isEqualTo(Objects.requireNonNull(persistedAppliedAt).toInstant());
        LegalEditorialReadinessResult readiness = production.readinessCore().evaluate(
                target.release(),
                receipt.appliedAt());
        assertThat(readiness.readiness()).isEqualTo(LegalEditorialReadiness.READY);
        LegalEditorialReadinessObservation observed = readiness.observation().orElseThrow();
        assertThat(observed.publicationUuid()).contains(target.publicationId());
        assertThat(receipt.documentVersions()).isEqualTo(observed.documentVersions());
        assertThat(receipt.requirementVersions()).isEqualTo(observed.requirementVersions());
        assertThat(receipt.documentTransitions()).isEqualTo(observed.documentTransitions());
        assertThat(receipt.requirementTransitions())
                .isEqualTo(observed.requirementTransitions());
        assertThat(receipt.documentSlots()).isEqualTo(observed.documentSlots());
        assertThat(receipt.requiredSetPointers())
                .isEqualTo(observed.currentRequirementSets());
        assertThat(receipt.replacementBatches()).isEqualTo(observed.replacementLots());
        assertThat(editorialTableRows(observer)).isNotEqualTo(rowsBeforeAttempt);
        assertThat(editorialSequenceStates(observer)).isNotEqualTo(sequencesBeforeAttempt);
        Map<String, List<String>> rowsBeforeReplay = editorialTableRows(observer);
        Map<String, LegalManifestPersistenceITSupport.SequenceState> sequencesBeforeReplay =
                editorialSequenceStates(observer);

        LegalEditorialApplyResult replay = ambiguous.service()
                .applyPromote(target.release());

        assertEditorialConfirmed(replay, LegalEditorialApplyResult.Outcome.ALREADY_APPLIED);
        assertThat(replay.receipt()).contains(receipt);
        assertThat(editorialTableRows(observer)).isEqualTo(rowsBeforeReplay);
        assertThat(editorialSequenceStates(observer)).isEqualTo(sequencesBeforeReplay);
    }

    @Test
    void rollbackWithoutAcknowledgementReconcilesSourceExactAndPreservesTheOriginalIssue()
            throws Exception {
        ImportedRelease target = importedDraft("reconciliation-rollback-operational-v1");
        ObservedAttempt attempt = observedAttempt("08006");
        LegalEditorialOperationalException originalFailure =
                new LegalEditorialOperationalException(
                        LegalManifestIssueCode.SCHEMA_DRIFT,
                        "database/schema");
        LegalEditorialMutationWriter writer = failingWriter(
                attempt.harness(),
                originalFailure);
        ReconciliationProbe reconciliation = observeReconciliation(
                attempt.harness(),
                target.release());
        LegalEditorialApplyService service = applyService(
                attempt.harness(),
                writer,
                reconciliation.reconciler(),
                new LegalEditorialFailureMapper());
        Map<String, List<String>> rowsBeforeAttempt = editorialTableRows(observer);
        Map<String, LegalManifestPersistenceITSupport.SequenceState> sequencesBeforeAttempt =
                editorialSequenceStates(observer);

        LegalEditorialApplyResult result = service.applyPromote(target.release());

        assertEditorialKnownFailure(
                result,
                LegalManifestStatus.ERROR,
                LegalManifestIssueCode.SCHEMA_DRIFT);
        assertNoTentativeMetadata(result);
        assertThat(attempt.dataSource().rollbackAcknowledgementWasLost()).isTrue();
        assertThat(editorialTableRows(observer)).isEqualTo(rowsBeforeAttempt);
        assertThat(editorialSequenceStates(observer)).isEqualTo(sequencesBeforeAttempt);
        reconciliation.assertSourceExactOnce(target.release());
        assertConclusiveBoundaries(attempt, null);
        verify(writer, times(1)).write(any());

        LegalEditorialApplyResult retry = production.service().applyPromote(target.release());
        assertEditorialConfirmed(retry, LegalEditorialApplyResult.Outcome.APPLIED);
        assertThat(editorialTableRows(observer)).isNotEqualTo(rowsBeforeAttempt);
        assertThat(editorialSequenceStates(observer)).isNotEqualTo(sequencesBeforeAttempt);
        Map<String, List<String>> rowsAfterRetry = editorialTableRows(observer);
        Map<String, LegalManifestPersistenceITSupport.SequenceState> sequencesAfterRetry =
                editorialSequenceStates(observer);

        LegalEditorialApplyResult replay = production.service().applyPromote(target.release());

        assertEditorialConfirmed(replay, LegalEditorialApplyResult.Outcome.ALREADY_APPLIED);
        assertThat(replay.receipt()).isEqualTo(retry.receipt());
        assertThat(editorialTableRows(observer)).isEqualTo(rowsAfterRetry);
        assertThat(editorialSequenceStates(observer)).isEqualTo(sequencesAfterRetry);
        verify(writer, times(1)).write(any());
    }

    @Test
    void sourceExactNormalizesAnOriginalBlockerToObservationFailure() throws Exception {
        ImportedRelease target = importedDraft("reconciliation-rollback-blocked-v1");
        ObservedAttempt attempt = observedAttempt("ZZ901");
        LegalEditorialBlockedException originalFailure = new LegalEditorialBlockedException(
                LegalManifestIssueCode.CURRENT_STATE_MISMATCH,
                "database/state");
        LegalEditorialMutationWriter writer = failingWriter(
                attempt.harness(),
                originalFailure);
        ReconciliationProbe reconciliation = observeReconciliation(
                attempt.harness(),
                target.release());
        LegalEditorialApplyService service = applyService(
                attempt.harness(),
                writer,
                reconciliation.reconciler(),
                new LegalEditorialFailureMapper());
        Map<String, List<String>> rowsBeforeAttempt = editorialTableRows(observer);
        Map<String, LegalManifestPersistenceITSupport.SequenceState> sequencesBeforeAttempt =
                editorialSequenceStates(observer);

        LegalEditorialApplyResult result = service.applyPromote(target.release());

        assertEditorialKnownFailure(
                result,
                LegalManifestStatus.ERROR,
                LegalManifestIssueCode.EDITORIAL_OBSERVATION_FAILED);
        assertNoTentativeMetadata(result);
        assertThat(attempt.dataSource().rollbackAcknowledgementWasLost()).isTrue();
        assertThat(editorialTableRows(observer)).isEqualTo(rowsBeforeAttempt);
        assertThat(editorialSequenceStates(observer)).isEqualTo(sequencesBeforeAttempt);
        reconciliation.assertSourceExactOnce(target.release());
        assertConclusiveBoundaries(attempt, null);
        verify(writer, times(1)).write(any());
    }

    @Test
    void sourceExactNormalizesAnIssueOutsideThePublicApplyAllowlist() throws Exception {
        ImportedRelease target = importedDraft("reconciliation-rollback-foreign-v1");
        ObservedAttempt attempt = observedAttempt("ZZ902");
        IllegalStateException originalFailure =
                new IllegalStateException("fallo editorial ajeno al contrato público");
        LegalEditorialMutationWriter writer = failingWriter(
                attempt.harness(),
                originalFailure);
        ReconciliationProbe reconciliation = observeReconciliation(
                attempt.harness(),
                target.release());
        LegalEditorialFailureMapper mapper = mock(LegalEditorialFailureMapper.class);
        when(mapper.map(any())).thenReturn(LegalManifestIssue.at(
                LegalManifestIssueCode.MANIFEST_VALIDATION_ERROR,
                "manifest"));
        LegalEditorialApplyService service = applyService(
                attempt.harness(),
                writer,
                reconciliation.reconciler(),
                mapper);
        Map<String, List<String>> rowsBeforeAttempt = editorialTableRows(observer);
        Map<String, LegalManifestPersistenceITSupport.SequenceState> sequencesBeforeAttempt =
                editorialSequenceStates(observer);

        LegalEditorialApplyResult result = service.applyPromote(target.release());

        assertEditorialKnownFailure(
                result,
                LegalManifestStatus.ERROR,
                LegalManifestIssueCode.EDITORIAL_OBSERVATION_FAILED);
        assertNoTentativeMetadata(result);
        assertThat(attempt.dataSource().rollbackAcknowledgementWasLost()).isTrue();
        assertThat(editorialTableRows(observer)).isEqualTo(rowsBeforeAttempt);
        assertThat(editorialSequenceStates(observer)).isEqualTo(sequencesBeforeAttempt);
        reconciliation.assertSourceExactOnce(target.release());
        assertConclusiveBoundaries(attempt, null);
        ArgumentCaptor<Throwable> mappedFailure = ArgumentCaptor.forClass(Throwable.class);
        verify(mapper, times(1)).map(mappedFailure.capture());
        assertThat(throwableGraphContains(mappedFailure.getValue(), originalFailure)).isTrue();
        verify(writer, times(1)).write(any());
    }

    @Test
    void killedSessionReconcilesSourceExactFromANewPostgresSessionAndManualRetryAppliesOnce()
            throws Exception {
        ImportedRelease target = importedDraft("reconciliation-session-kill-v1");
        ObservedAttempt attempt = observedAttempt(null);
        CountDownLatch writerEntered = new CountDownLatch(1);
        CountDownLatch continueAfterKill = new CountDownLatch(1);
        AtomicInteger victimPid = new AtomicInteger();
        LegalEditorialMutationWriter writer = mock(LegalEditorialMutationWriter.class);
        when(writer.usesJdbc(attempt.harness().jdbc())).thenReturn(true);
        doAnswer(invocation -> {
            victimPid.set(Objects.requireNonNull(attempt.harness().jdbc().queryForObject(
                    "SELECT pg_backend_pid()",
                    Integer.class)));
            writerEntered.countDown();
            awaitLatchWithTimeout(
                    continueAfterKill,
                    "continuación del writer después del session kill",
                    30);
            attempt.harness().jdbc().queryForObject("SELECT 1", Integer.class);
            throw new AssertionError("La sesión terminada aceptó una nueva observación");
        }).when(writer).write(any());
        ReconciliationProbe reconciliation = observeReconciliation(
                attempt.harness(),
                target.release());
        LegalEditorialApplyService service = applyService(
                attempt.harness(),
                writer,
                reconciliation.reconciler(),
                new LegalEditorialFailureMapper());
        Map<String, List<String>> rowsBeforeAttempt = editorialTableRows(observer);
        Map<String, LegalManifestPersistenceITSupport.SequenceState> sequencesBeforeAttempt =
                editorialSequenceStates(observer);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<LegalEditorialApplyResult> pending = null;
        try {
            pending = executor.submit(() -> service.applyPromote(target.release()));
            awaitLatch(writerEntered, "writer editorial antes de terminar la sesión");
            int terminatedPid = victimPid.get();
            assertThat(terminatedPid).isPositive();

            Boolean terminated = observer.queryForObject(
                    "SELECT pg_catalog.pg_terminate_backend(?)",
                    Boolean.class,
                    terminatedPid);
            assertThat(terminated).isTrue();
            awaitValue(
                    "la sesión editorial terminada desapareciendo de pg_stat_activity",
                    () -> {
                        Integer sessions = observer.queryForObject("""
                                SELECT count(*)::integer
                                  FROM pg_catalog.pg_stat_activity
                                 WHERE pid = ?
                                """, Integer.class, terminatedPid);
                        return Objects.requireNonNull(sessions) == 0
                                ? Optional.of(Boolean.TRUE)
                                : Optional.empty();
                    });
            continueAfterKill.countDown();

            LegalEditorialApplyResult result = pending.get(15, TimeUnit.SECONDS);

            assertEditorialKnownFailure(
                    result,
                    LegalManifestStatus.ERROR,
                    LegalManifestIssueCode.EDITORIAL_OBSERVATION_FAILED);
            assertNoTentativeMetadata(result);
            assertThat(editorialTableRows(observer)).isEqualTo(rowsBeforeAttempt);
            assertThat(editorialSequenceStates(observer)).isEqualTo(sequencesBeforeAttempt);
            reconciliation.assertSourceExactOnce(target.release());
            assertConclusiveBoundaries(attempt, terminatedPid);
            verify(writer, times(1)).write(any());

            LegalEditorialApplyResult retry = production.service()
                    .applyPromote(target.release());
            assertEditorialConfirmed(retry, LegalEditorialApplyResult.Outcome.APPLIED);
            Map<String, List<String>> rowsAfterRetry = editorialTableRows(observer);
            Map<String, LegalManifestPersistenceITSupport.SequenceState> sequencesAfterRetry =
                    editorialSequenceStates(observer);
            assertThat(rowsAfterRetry).isNotEqualTo(rowsBeforeAttempt);
            assertThat(sequencesAfterRetry).isNotEqualTo(sequencesBeforeAttempt);

            LegalEditorialApplyResult replay = production.service()
                    .applyPromote(target.release());

            assertEditorialConfirmed(replay, LegalEditorialApplyResult.Outcome.ALREADY_APPLIED);
            assertThat(replay.receipt()).isEqualTo(retry.receipt());
            assertThat(editorialTableRows(observer)).isEqualTo(rowsAfterRetry);
            assertThat(editorialSequenceStates(observer)).isEqualTo(sequencesAfterRetry);
            verify(writer, times(1)).write(any());
        } finally {
            continueAfterKill.countDown();
            if (pending != null) {
                pending.cancel(true);
            }
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private ImportedRelease importedDraft(String externalId) throws Exception {
        ValidatedRelease release = copyRelease(
                temporaryDirectory,
                LegalEditorialReconciliationIT.class,
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

    private static void awaitLatchWithTimeout(
            CountDownLatch latch,
            String description,
            long timeoutSeconds) {
        try {
            if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
                throw new AssertionError("No se abrió a tiempo: " + description);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Espera interrumpida: " + description, interrupted);
        }
    }

    private static ObservedAttempt observedAttempt(String rollbackSqlState) {
        BoundaryTrackingDataSource dataSource = new BoundaryTrackingDataSource(
                pool,
                rollbackSqlState);
        BoundaryObservingJdbcTemplate jdbc = new BoundaryObservingJdbcTemplate(dataSource);
        LegalEditorialSchemaVerifier schemaVerifier = new LegalEditorialSchemaVerifier(
                jdbc,
                LegalV27EditorialInventory.DEFAULT_SCHEMA);
        LegalEditorialPrivilegeVerifier privilegeVerifier = mock(
                LegalEditorialPrivilegeVerifier.class);
        when(privilegeVerifier.usesJdbc(jdbc)).thenReturn(true);
        ApplyHarness harness = applyHarness(
                jdbc,
                LegalDatabaseBudgets.production(),
                schemaVerifier,
                privilegeVerifier);
        return new ObservedAttempt(dataSource, jdbc, harness);
    }

    private static LegalEditorialMutationWriter failingWriter(
            ApplyHarness harness,
            RuntimeException failure) {
        LegalEditorialMutationWriter writer = mock(LegalEditorialMutationWriter.class);
        when(writer.usesJdbc(harness.jdbc())).thenReturn(true);
        doThrow(failure).when(writer).write(any());
        return writer;
    }

    private static ReconciliationProbe observeReconciliation(
            ApplyHarness harness,
            ValidatedRelease target) {
        LegalEditorialCommitReconciler reconciler = spy(harness.commitReconciler());
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
            LegalEditorialCommitReconciler.Result result =
                    (LegalEditorialCommitReconciler.Result) invocation.callRealMethod();
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

    private static LegalEditorialApplyService applyService(
            ApplyHarness harness,
            LegalEditorialMutationWriter promotionWriter,
            LegalEditorialCommitReconciler reconciler,
            LegalEditorialFailureMapper failureMapper) {
        return new LegalEditorialApplyService(
                harness.gate(),
                harness.jdbc(),
                harness.plannerCore(),
                promotionWriter,
                harness.replacementWriter(),
                harness.retirementWriter(),
                harness.postStateVerifier(),
                harness.readinessCore(),
                harness.replaceScopeGuard(),
                reconciler,
                failureMapper,
                harness.schemaVerifier(),
                harness.privilegeVerifier());
    }

    private static void assertNoTentativeMetadata(LegalEditorialApplyResult result) {
        assertThat(result.receipt()).isEmpty();
        assertThat(result.operationType()).isEmpty();
        assertThat(result.targetPublicationUuid()).isEmpty();
        assertThat(result.appliedAt()).isEmpty();
        assertThat(result.readinessAfter()).isEmpty();
    }

    private static void assertConclusiveBoundaries(
            ObservedAttempt attempt,
            Integer terminatedPid) {
        assertThat(attempt.dataSource().acquisitions())
                .extracting(LeaseAcquisition::leaseId)
                .containsExactly(1, 2);
        assertThat(attempt.dataSource().acquisitions().get(1).closedBeforeAcquisition())
                .containsExactly(1);
        assertThat(attempt.dataSource().closedLeases()).containsExactly(1, 2);
        assertThat(attempt.jdbc().lockObservations()).hasSize(2);
        LockObservation mutable = attempt.jdbc().lockObservations().get(0);
        LockObservation reconciliation = attempt.jdbc().lockObservations().get(1);
        assertThat(mutable.lockName())
                .isEqualTo(LegalManifestDatabaseGate.EDITORIAL_LOCK_NAME);
        assertThat(reconciliation.lockName())
                .isEqualTo(LegalManifestDatabaseGate.EDITORIAL_LOCK_NAME);
        assertThat(mutable.leaseId()).isEqualTo(1);
        assertThat(reconciliation.leaseId()).isEqualTo(2);
        assertThat(mutable.transactionName()).isEqualTo("legal-editorial-apply-it");
        assertThat(reconciliation.transactionName())
                .isEqualTo("legal-editorial-reconciliation-it");
        assertThat(mutable.transactionActive()).isTrue();
        assertThat(mutable.synchronizationActive()).isTrue();
        assertThat(mutable.resourceBound()).isTrue();
        assertThat(mutable.springReadOnly()).isFalse();
        assertThat(mutable.postgresReadOnly()).isEqualTo("off");
        assertThat(reconciliation.transactionActive()).isTrue();
        assertThat(reconciliation.synchronizationActive()).isTrue();
        assertThat(reconciliation.resourceBound()).isTrue();
        assertThat(reconciliation.springReadOnly()).isTrue();
        assertThat(reconciliation.postgresReadOnly()).isEqualTo("on");
        if (terminatedPid != null) {
            assertThat(mutable.backendPid()).isEqualTo(terminatedPid);
            assertThat(reconciliation.backendPid()).isNotEqualTo(terminatedPid);
        }
    }

    private static boolean throwableGraphContains(Throwable root, Throwable expected) {
        Deque<Throwable> pending = new ArrayDeque<>();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        pending.add(root);
        while (!pending.isEmpty()) {
            Throwable current = pending.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            if (current == expected) {
                return true;
            }
            if (current instanceof TransactionSystemException transactionFailure
                    && transactionFailure.getApplicationException() != null) {
                pending.addLast(transactionFailure.getApplicationException());
            }
            if (current.getCause() != null) {
                pending.addLast(current.getCause());
            }
            Collections.addAll(pending, current.getSuppressed());
        }
        return false;
    }

    private record ImportedRelease(ValidatedRelease release, UUID publicationId) { }

    private record ObservedAttempt(
            BoundaryTrackingDataSource dataSource,
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

        void assertSourceExactOnce(ValidatedRelease target) {
            verify(reconciler, times(1)).reconcilePromote(target, true);
            verify(reconciler, never()).reconcileReplace(any(), any(), anyBoolean());
            verify(reconciler, never()).reconcileRetire(any(), any(), anyBoolean());
            assertThat(invocations.get()).isOne();
            assertThat(entry.get()).isEqualTo(new ReconciliationEntry(false, false, false));
            assertThat(observed.get()).isNotNull();
            assertThat(observed.get().outcome())
                    .isEqualTo(LegalEditorialCommitReconciler.Outcome.SOURCE_EXACT);
            assertThat(observed.get().receipt()).isEmpty();
            assertThat(TransactionSynchronizationManager.hasResource(harness.dataSource()))
                    .isFalse();
        }
    }

    private record LeaseAcquisition(int leaseId, List<Integer> closedBeforeAcquisition) { }

    private record LockObservation(
            int leaseId,
            int backendPid,
            String lockName,
            String transactionName,
            boolean transactionActive,
            boolean synchronizationActive,
            boolean springReadOnly,
            boolean resourceBound,
            String postgresReadOnly
    ) { }

    /** Test-only observer for both the mutable and reconciliation lock boundaries. */
    private static final class BoundaryObservingJdbcTemplate extends JdbcTemplate {

        private final BoundaryTrackingDataSource dataSource;
        private final List<LockObservation> lockObservations = new CopyOnWriteArrayList<>();

        private BoundaryObservingJdbcTemplate(BoundaryTrackingDataSource dataSource) {
            super(dataSource);
            this.dataSource = dataSource;
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
                LockObservation successfulLock = new LockObservation(
                        dataSource.leaseId(connection),
                        Objects.requireNonNull(backendPid),
                        Objects.toString(args[0]),
                        TransactionSynchronizationManager.getCurrentTransactionName(),
                        TransactionSynchronizationManager.isActualTransactionActive(),
                        TransactionSynchronizationManager.isSynchronizationActive(),
                        TransactionSynchronizationManager.isCurrentTransactionReadOnly(),
                        TransactionSynchronizationManager.hasResource(dataSource),
                        Objects.requireNonNull(postgresReadOnly));
                List<Map<String, Object>> result = super.queryForList(sql, args);
                lockObservations.add(successfulLock);
                return result;
            } finally {
                DataSourceUtils.releaseConnection(connection, dataSource);
            }
        }

        List<LockObservation> lockObservations() {
            return List.copyOf(lockObservations);
        }
    }

    /**
     * Test-only DataSource that exposes logical leases and can lose one successful rollback ACK.
     */
    private static final class BoundaryTrackingDataSource extends DelegatingDataSource {

        private final String rollbackSqlState;
        private final AtomicBoolean rollbackArmed;
        private final AtomicInteger nextLease = new AtomicInteger();
        private final Map<Connection, Integer> leaseIds = Collections.synchronizedMap(
                new IdentityHashMap<>());
        private final List<Integer> closedLeases = new CopyOnWriteArrayList<>();
        private final List<LeaseAcquisition> acquisitions = new CopyOnWriteArrayList<>();

        private BoundaryTrackingDataSource(
                DataSource targetDataSource,
                String rollbackSqlState) {
            super(Objects.requireNonNull(targetDataSource, "targetDataSource"));
            this.rollbackSqlState = rollbackSqlState;
            this.rollbackArmed = new AtomicBoolean(rollbackSqlState != null);
        }

        @Override
        public Connection getConnection() throws SQLException {
            return wrap(super.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return wrap(super.getConnection(username, password));
        }

        boolean rollbackAcknowledgementWasLost() {
            return rollbackSqlState != null && !rollbackArmed.get();
        }

        int leaseId(Connection connection) {
            Integer leaseId = leaseIds.get(connection);
            if (leaseId == null) {
                throw new IllegalStateException("La conexión no pertenece al intento observado");
            }
            return leaseId;
        }

        List<Integer> closedLeases() {
            return List.copyOf(closedLeases);
        }

        List<LeaseAcquisition> acquisitions() {
            return List.copyOf(acquisitions);
        }

        private Connection wrap(Connection target) {
            int leaseId = nextLease.incrementAndGet();
            acquisitions.add(new LeaseAcquisition(leaseId, List.copyOf(closedLeases)));
            Connection proxy = (Connection) Proxy.newProxyInstance(
                    LegalEditorialReconciliationIT.class.getClassLoader(),
                    new Class<?>[]{Connection.class},
                    (candidate, method, arguments) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return switch (method.getName()) {
                                case "equals" -> candidate == arguments[0];
                                case "hashCode" -> System.identityHashCode(candidate);
                                case "toString" -> "legal-reconciliation-lease-" + leaseId;
                                default -> throw new IllegalStateException(
                                        "Método Object inesperado en el proxy JDBC");
                            };
                        }
                        if ("rollback".equals(method.getName())
                                && method.getParameterCount() == 0
                                && rollbackArmed.get()) {
                            Object result = invoke(target, method, arguments);
                            if (rollbackArmed.compareAndSet(true, false)) {
                                throw new SQLException(
                                        "confirmación de rollback perdida por el test",
                                        rollbackSqlState);
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
