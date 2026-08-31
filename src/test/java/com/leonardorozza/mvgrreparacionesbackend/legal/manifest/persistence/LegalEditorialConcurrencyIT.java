package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialReadiness;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidation;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalEditorialITFixture.EditorialSnapshot;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalEditorialITFixture.ImportedRelease;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalEditorialITFixture.ReplaceFixture;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.ApplyHarness;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.EditorialLockHolder;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.Harness;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.PlannerHarness;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.SequenceState;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestDryRunService.DryRunResult;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.assertConfirmed;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.assertDryRunPass;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.assertEditorialConfirmed;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.assertEditorialKnownFailure;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.awaitCondition;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.awaitLatch;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.blockedEditorialGateWaits;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.cleanLegalState;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.directDataSource;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.editorialSequenceStates;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.holdEditorialLock;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.migrate;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.pooledDataSource;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.restrictedApplyHarness;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.restrictedHarness;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.restrictedPlannerHarness;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

/** PostgreSQL accreditation for cooperative editorial races and the shared database gate. */
@Testcontainers
class LegalEditorialConcurrencyIT {

    private static final String IMPORT_ROLE = "ordenfix_legal_import_concurrency_it";
    private static final String IMPORT_PASSWORD = "import-concurrency-password-must-not-leak";
    private static final String EDITORIAL_ROLE = "ordenfix_legal_editorial_concurrency_it";
    private static final String EDITORIAL_PASSWORD =
            "editorial-concurrency-password-must-not-leak";
    private static final String IMPORT_APPLICATION_NAME =
            "ordenfix-legal-concurrency-import";
    private static final String EDITORIAL_APPLICATION_NAME =
            "ordenfix-legal-concurrency-editorial";
    private static final String OWNER_APPLICATION_NAME =
            "ordenfix-legal-concurrency-owner";
    private static final String LOCK_APPLICATION_NAME =
            "ordenfix-legal-concurrency-lock-holder";
    private static final String DOCUMENT_TRANSITION_SEQUENCE =
            "legal_documento_transiciones_id_seq";
    private static final String REQUIREMENT_TRANSITION_SEQUENCE =
            "legal_requisito_transiciones_id_seq";
    private static final String REPLACEMENT_PREDECESSOR_SEQUENCE =
            "legal_documento_reemplazo_anteriores_id_seq";
    private static final String REPLACEMENT_SUCCESSOR_SEQUENCE =
            "legal_documento_reemplazo_sucesoras_id_seq";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_editorial_concurrency")
            .withUsername("ordenfix")
            .withPassword("ordenfix");

    private static HikariDataSource importPool;
    private static HikariDataSource editorialPool;
    private static DataSource lockDataSource;
    private static JdbcTemplate observer;
    private static Harness importer;
    private static PlannerHarness planner;
    private static ApplyHarness apply;
    private static LegalRestrictedImportRoleFixture importRoleFixture;
    private static LegalRestrictedEditorialRoleFixture editorialRoleFixture;

    @TempDir
    private Path temporaryDirectory;

    private LegalEditorialITFixture fixture;

    @BeforeAll
    static void migrateProvisionAndAssemble() {
        migrate(POSTGRES);
        DataSource ownerDataSource = directDataSource(POSTGRES, OWNER_APPLICATION_NAME);
        lockDataSource = directDataSource(POSTGRES, LOCK_APPLICATION_NAME);
        observer = new JdbcTemplate(ownerDataSource);

        importRoleFixture = new LegalRestrictedImportRoleFixture(
                observer,
                POSTGRES.getJdbcUrl(),
                IMPORT_ROLE,
                IMPORT_PASSWORD,
                POSTGRES.getDriverClassName());
        LegalRestrictedImportRoleFixture.Credentials importCredentials =
                importRoleFixture.provisionAndVerify();
        editorialRoleFixture = new LegalRestrictedEditorialRoleFixture(
                observer,
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
        planner = restrictedPlannerHarness(
                editorialPool,
                LegalDatabaseBudgets.production(),
                EDITORIAL_ROLE);
        apply = restrictedApplyHarness(
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
        cleanLegalState(observer);
        importRoleFixture.verify();
        editorialRoleFixture.verify();
        fixture = new LegalEditorialITFixture(
                temporaryDirectory,
                LegalEditorialConcurrencyIT.class,
                observer,
                importer,
                apply);
    }

    @Test
    void identicalPromotionsSerializeToAppliedAndAlreadyApplied() throws Exception {
        ImportedRelease target = fixture.importedDraft(
                "concurrency-promote-identical-v1");
        Map<String, SequenceState> sequencesBefore = editorialSequenceStates(observer);
        JdbcTemplate controlledJdbc = new JdbcTemplate(editorialPool);
        LegalEditorialSchemaVerifier schemaVerifier = spy(new LegalEditorialSchemaVerifier(
                controlledJdbc,
                LegalV27EditorialInventory.DEFAULT_SCHEMA));
        LegalEditorialPrivilegeVerifier privilegeVerifier =
                new LegalEditorialPrivilegeVerifier(
                        controlledJdbc,
                        EDITORIAL_ROLE,
                        LegalV27EditorialInventory.DEFAULT_SCHEMA);
        ApplyHarness controlled = LegalManifestPersistenceITSupport.applyHarness(
                controlledJdbc,
                LegalDatabaseBudgets.production(),
                schemaVerifier,
                privilegeVerifier);
        CountDownLatch transactionAPaused = new CountDownLatch(1);
        CountDownLatch transactionBPaused = new CountDownLatch(1);
        CountDownLatch releaseTransactionA = new CountDownLatch(1);
        CountDownLatch releaseTransactionB = new CountDownLatch(1);
        AtomicInteger verifierInvocations = new AtomicInteger();
        AtomicInteger transactionAPid = new AtomicInteger();
        AtomicInteger transactionBPid = new AtomicInteger();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            int invocationNumber = verifierInvocations.incrementAndGet();
            if (invocationNumber == 1) {
                transactionAPid.set(currentBackendPid(controlledJdbc));
                transactionAPaused.countDown();
                awaitLatch(releaseTransactionA, "liberación de la transacción editorial A");
            } else if (invocationNumber == 2) {
                transactionBPid.set(currentBackendPid(controlledJdbc));
                transactionBPaused.countDown();
                awaitLatch(releaseTransactionB, "liberación de la transacción editorial B");
            }
            return null;
        }).when(schemaVerifier).verify();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<LegalEditorialApplyResult> transactionA = null;
        Future<LegalEditorialApplyResult> transactionB = null;
        try {
            transactionA = executor.submit(() ->
                    controlled.service().applyPromote(target.release()));
            awaitLatch(transactionAPaused, "transacción editorial A antes del advisory lock");

            transactionB = executor.submit(() ->
                    controlled.service().applyPromote(target.release()));
            awaitLatch(transactionBPaused, "transacción editorial B antes del advisory lock");

            EditorialTransaction observedA = editorialTransaction(transactionAPid.get());
            EditorialTransaction observedB = editorialTransaction(transactionBPid.get());
            assertThat(observedA.pid()).isPositive().isNotEqualTo(observedB.pid());
            assertThat(observedB.pid()).isPositive();
            assertThat(observedA.applicationName()).isEqualTo(EDITORIAL_APPLICATION_NAME);
            assertThat(observedB.applicationName()).isEqualTo(EDITORIAL_APPLICATION_NAME);
            assertThat(observedA.state()).isEqualTo("idle in transaction");
            assertThat(observedB.state()).isEqualTo("idle in transaction");
            assertThat(observedA.xactStart()).isBefore(observedB.xactStart());

            releaseTransactionB.countDown();
            LegalEditorialApplyResult applied = transactionB.get(30, TimeUnit.SECONDS);
            assertEditorialConfirmed(applied, LegalEditorialApplyResult.Outcome.APPLIED);
            assertThat(applied.receipt().orElseThrow().appliedAt())
                    .isEqualTo(observedB.xactStart());
            assertThat(applied.targetPublicationUuid()).contains(target.publicationId());

            releaseTransactionA.countDown();
            LegalEditorialApplyResult replay = transactionA.get(30, TimeUnit.SECONDS);
            assertThat(replay.outcome())
                    .as("A nació en %s, B nació en %s y B confirmó primero; resultado A=%s",
                            observedA.xactStart(),
                            observedB.xactStart(),
                            replay.issues())
                    .isEqualTo(LegalEditorialApplyResult.Outcome.ALREADY_APPLIED);
            assertEditorialConfirmed(
                    replay,
                    LegalEditorialApplyResult.Outcome.ALREADY_APPLIED);
            assertThat(replay.receipt()).isEqualTo(applied.receipt());
            assertThat(applied.receipt().orElseThrow().readinessAfter())
                    .isEqualTo(LegalEditorialReadiness.READY);
            assertCurrentProjections(target.publicationId());
            assertThat(observer.queryForObject(
                    "SELECT count(*) FROM legal_documento_reemplazo_lotes",
                    Long.class)).isZero();
            assertSinglePromotionSequenceDelta(
                    sequencesBefore,
                    editorialSequenceStates(observer),
                    target.release());

            EditorialSnapshot afterRace = fixture.snapshot();
            LegalEditorialApplyResult third =
                    controlled.service().applyPromote(target.release());
            assertEditorialConfirmed(
                    third,
                    LegalEditorialApplyResult.Outcome.ALREADY_APPLIED);
            assertThat(third.receipt()).isEqualTo(applied.receipt());
            assertThat(fixture.snapshot()).isEqualTo(afterRace);
        } finally {
            releaseTransactionA.countDown();
            releaseTransactionB.countDown();
            cancel(transactionA);
            cancel(transactionB);
            shutdown(executor);
        }
    }

    @Test
    void incompatiblePromotionTargetsConfirmAtMostOne() throws Exception {
        ImportedRelease first = fixture.importedDraft("concurrency-promote-a-v1");
        ImportedRelease second = fixture.importedDraft("concurrency-promote-b-v1");
        Map<String, SequenceState> sequencesBefore = editorialSequenceStates(observer);

        List<LegalEditorialApplyResult> results = runTwoBehindHeldGate(
                () -> apply.service().applyPromote(first.release()),
                () -> apply.service().applyPromote(second.release()));

        assertThat(results)
                .extracting(LegalEditorialApplyResult::outcome)
                .containsExactlyInAnyOrder(
                        LegalEditorialApplyResult.Outcome.APPLIED,
                        LegalEditorialApplyResult.Outcome.BLOCKED);
        LegalEditorialApplyResult firstResult = results.get(0);
        LegalEditorialApplyResult secondResult = results.get(1);
        ImportedRelease winner = firstResult.outcome()
                == LegalEditorialApplyResult.Outcome.APPLIED ? first : second;
        ImportedRelease loser = winner == first ? second : first;
        LegalEditorialApplyResult applied = winner == first ? firstResult : secondResult;
        LegalEditorialApplyResult blocked = winner == first ? secondResult : firstResult;
        assertEditorialConfirmed(applied, LegalEditorialApplyResult.Outcome.APPLIED);
        assertEditorialKnownFailure(
                blocked,
                LegalManifestStatus.BLOCKED,
                LegalManifestIssueCode.INITIAL_PROJECTION_ALREADY_EXISTS);
        assertCurrentProjections(winner.publicationId());
        assertSinglePromotionSequenceDelta(
                sequencesBefore,
                editorialSequenceStates(observer),
                winner.release());

        EditorialSnapshot afterRace = fixture.snapshot();
        LegalEditorialApplyResult winnerRetry = apply.service().applyPromote(winner.release());
        LegalEditorialApplyResult loserRetry = apply.service().applyPromote(loser.release());
        assertEditorialConfirmed(
                winnerRetry,
                LegalEditorialApplyResult.Outcome.ALREADY_APPLIED);
        assertEditorialKnownFailure(
                loserRetry,
                LegalManifestStatus.BLOCKED,
                LegalManifestIssueCode.INITIAL_PROJECTION_ALREADY_EXISTS);
        assertThat(fixture.snapshot()).isEqualTo(afterRace);
    }

    @Test
    void replacementsWithAnOverlappingPredecessorConfirmAtMostOne()
            throws Exception {
        ImportedRelease source = fixture.readyRelease("concurrency-replace-source-v1");
        ImportedRelease firstTarget = fixture.importedDocumentRevision(
                "concurrency-replace-a-v1",
                "cierre-cuenta",
                "concurrency-a-v2");
        ImportedRelease secondTarget = fixture.importedDocumentRevision(
                "concurrency-replace-b-v1",
                "cierre-cuenta",
                "concurrency-b-v2");
        ReplaceFixture firstPlan = fixture.replacementPlan(
                source,
                firstTarget,
                "replace-a");
        ReplaceFixture secondPlan = fixture.replacementPlan(
                source,
                secondTarget,
                "replace-b");
        assertOverlappingOneToOnePlans(firstPlan, secondPlan);
        Map<String, SequenceState> sequencesBefore = editorialSequenceStates(observer);

        List<LegalEditorialApplyResult> results = runTwoBehindHeldGate(
                () -> apply.service().applyReplace(firstTarget.release(), firstPlan.plan()),
                () -> apply.service().applyReplace(secondTarget.release(), secondPlan.plan()));

        assertThat(results)
                .extracting(LegalEditorialApplyResult::outcome)
                .containsExactlyInAnyOrder(
                        LegalEditorialApplyResult.Outcome.APPLIED,
                        LegalEditorialApplyResult.Outcome.BLOCKED);
        LegalEditorialApplyResult firstResult = results.get(0);
        LegalEditorialApplyResult secondResult = results.get(1);
        boolean firstWon = firstResult.outcome() == LegalEditorialApplyResult.Outcome.APPLIED;
        ReplaceFixture winnerPlan = firstWon ? firstPlan : secondPlan;
        ReplaceFixture loserPlan = firstWon ? secondPlan : firstPlan;
        LegalEditorialApplyResult applied = firstWon ? firstResult : secondResult;
        LegalEditorialApplyResult blocked = firstWon ? secondResult : firstResult;
        assertEditorialConfirmed(applied, LegalEditorialApplyResult.Outcome.APPLIED);
        assertEditorialKnownFailure(
                blocked,
                LegalManifestStatus.BLOCKED,
                LegalManifestIssueCode.SOURCE_FINGERPRINT_MISMATCH);
        assertThat(blocked.issues())
                .extracting(LegalManifestIssue::location)
                .containsExactly("database/source");
        assertThat(applied.receipt().orElseThrow().replacementBatches()).isOne();
        assertWinningReplaceState(winnerPlan, loserPlan);
        assertSequenceDelta(
                sequencesBefore,
                editorialSequenceStates(observer),
                Map.of(
                        DOCUMENT_TRANSITION_SEQUENCE, 3L,
                        REQUIREMENT_TRANSITION_SEQUENCE, 3L,
                        REPLACEMENT_PREDECESSOR_SEQUENCE, 1L,
                        REPLACEMENT_SUCCESSOR_SEQUENCE, 1L));

        EditorialSnapshot afterRace = fixture.snapshot();
        LegalEditorialApplyResult winnerRetry = apply.service().applyReplace(
                winnerPlan.target().release(),
                winnerPlan.plan());
        LegalEditorialApplyResult loserRetry = apply.service().applyReplace(
                loserPlan.target().release(),
                loserPlan.plan());
        assertEditorialConfirmed(
                winnerRetry,
                LegalEditorialApplyResult.Outcome.ALREADY_APPLIED);
        assertEditorialKnownFailure(
                loserRetry,
                LegalManifestStatus.BLOCKED,
                LegalManifestIssueCode.SOURCE_FINGERPRINT_MISMATCH);
        assertThat(fixture.snapshot()).isEqualTo(afterRace);
    }

    @Test
    void importDryRunReadinessPlanAndApplyWaitForTheSameAdvisoryLock()
            throws Exception {
        ImportedRelease target = fixture.importedDraft("concurrency-shared-gate-target-v1");
        ValidatedRelease dryRunRelease = fixture.draftRelease(
                "concurrency-shared-gate-dry-run-v1");
        EditorialSnapshot before = fixture.snapshot();
        ExecutorService executor = Executors.newFixedThreadPool(5);
        Future<LegalManifestImportResult> importReplay = null;
        Future<LegalManifestValidation<DryRunResult>> dryRun = null;
        Future<LegalEditorialReadinessResult> readiness = null;
        Future<LegalEditorialPlanResult> plan = null;
        Future<LegalEditorialApplyResult> applyFuture = null;

        try (EditorialLockHolder holder = holdEditorialLock(lockDataSource)) {
            importReplay = executor.submit(() ->
                    importer.importService().importManifest(target.release()));
            dryRun = executor.submit(() ->
                    importer.dryRunService().dryRun(dryRunRelease));
            readiness = executor.submit(() ->
                    planner.readinessService().evaluate(target.release()));
            plan = executor.submit(() ->
                    planner.plannerService().planPromote(target.release()));
            applyFuture = executor.submit(() ->
                    apply.service().applyPromote(target.release()));

            awaitCondition(
                    "import, dry-run, readiness, plan y apply esperando el mismo gate",
                    () -> blockedEditorialGateWaits(observer, IMPORT_APPLICATION_NAME) == 2
                            && blockedEditorialGateWaits(
                                    observer,
                                    EDITORIAL_APPLICATION_NAME) == 3);
            assertThat(fixture.snapshot()).isEqualTo(before);
            holder.release();

            LegalManifestImportResult imported = importReplay.get(30, TimeUnit.SECONDS);
            LegalManifestValidation<DryRunResult> simulated =
                    dryRun.get(30, TimeUnit.SECONDS);
            LegalEditorialReadinessResult observed =
                    readiness.get(30, TimeUnit.SECONDS);
            LegalEditorialPlanResult planned = plan.get(30, TimeUnit.SECONDS);
            LegalEditorialApplyResult applied = applyFuture.get(30, TimeUnit.SECONDS);

            assertConfirmed(imported, LegalManifestImportResult.Outcome.ALREADY_IMPORTED);
            assertThat(imported.receipt().orElseThrow().publicationUuid())
                    .isEqualTo(target.publicationId());
            assertDryRunPass(simulated);
            assertThat(observed.status()).isNotEqualTo(LegalManifestStatus.ERROR);
            assertThat(observed.readiness())
                    .isIn(LegalEditorialReadiness.NOT_READY, LegalEditorialReadiness.READY);
            assertThat(planned.status()).isEqualTo(LegalManifestStatus.PASS);
            assertThat(planned.outcome()).isEqualTo(LegalEditorialPlanResult.Outcome.APPLICABLE);
            assertEditorialConfirmed(applied, LegalEditorialApplyResult.Outcome.APPLIED);
            LegalEditorialReadinessResult finalReadiness =
                    planner.readinessService().evaluate(target.release());
            assertThat(finalReadiness.status()).isEqualTo(LegalManifestStatus.PASS);
            assertThat(finalReadiness.readiness()).isEqualTo(LegalEditorialReadiness.READY);
            assertCurrentProjections(target.publicationId());
        } finally {
            cancel(importReplay);
            cancel(dryRun);
            cancel(readiness);
            cancel(plan);
            cancel(applyFuture);
            shutdown(executor);
        }
    }

    private <T> List<T> runTwoBehindHeldGate(
            Callable<T> firstOperation,
            Callable<T> secondOperation) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<T> first = null;
        Future<T> second = null;
        EditorialSnapshot before = fixture.snapshot();
        try (EditorialLockHolder holder = holdEditorialLock(lockDataSource)) {
            first = executor.submit(firstOperation);
            second = executor.submit(secondOperation);
            awaitCondition(
                    "dos operaciones editoriales esperando el advisory lock",
                    () -> blockedEditorialGateWaits(
                            observer,
                            EDITORIAL_APPLICATION_NAME) == 2);
            assertThat(fixture.snapshot()).isEqualTo(before);
            holder.release();
            return List.of(
                    first.get(30, TimeUnit.SECONDS),
                    second.get(30, TimeUnit.SECONDS));
        } finally {
            cancel(first);
            cancel(second);
            shutdown(executor);
        }
    }

    private static void assertOverlappingOneToOnePlans(
            ReplaceFixture first,
            ReplaceFixture second) {
        Set<UUID> sharedPredecessors = new HashSet<>(first.predecessorDocumentIds());
        sharedPredecessors.retainAll(second.predecessorDocumentIds());
        Set<UUID> sharedSuccessors = new HashSet<>(first.successorDocumentIds());
        sharedSuccessors.retainAll(second.successorDocumentIds());
        Set<UUID> sharedBatches = new HashSet<>(first.replacementBatchIds());
        sharedBatches.retainAll(second.replacementBatchIds());

        assertThat(first.predecessorDocumentIds()).hasSize(1);
        assertThat(second.predecessorDocumentIds()).hasSize(1);
        assertThat(sharedPredecessors).hasSize(1);
        assertThat(first.successorDocumentIds()).hasSize(1);
        assertThat(second.successorDocumentIds()).hasSize(1);
        assertThat(sharedSuccessors).isEmpty();
        assertThat(first.replacementBatchIds()).hasSize(1);
        assertThat(second.replacementBatchIds()).hasSize(1);
        assertThat(sharedBatches).isEmpty();
        assertThat(first.predecessorRequirementIds()).hasSize(1);
        assertThat(second.predecessorRequirementIds()).hasSize(1);
        assertThat(first.plan().plan().expectedEditorialStateFingerprint())
                .isEqualTo(second.plan().plan().expectedEditorialStateFingerprint());
    }

    private static void assertWinningReplaceState(
            ReplaceFixture winner,
            ReplaceFixture loser) {
        UUID winnerBatch = only(winner.replacementBatchIds());
        UUID predecessor = only(winner.predecessorDocumentIds());
        UUID winnerSuccessor = only(winner.successorDocumentIds());
        UUID loserSuccessor = only(loser.successorDocumentIds());
        UUID winnerRequirement = only(winner.successorRequirementIds());
        UUID loserRequirement = only(loser.successorRequirementIds());

        assertThat(observer.queryForList(
                "SELECT id FROM legal_documento_reemplazo_lotes ORDER BY id::text",
                UUID.class)).containsExactly(winnerBatch);
        assertThat(observer.queryForObject(
                "SELECT estado_construccion FROM legal_documento_reemplazo_lotes WHERE id = ?",
                String.class,
                winnerBatch)).isEqualTo("SELLADO");
        assertState("legal_documento_versiones", predecessor, "REEMPLAZADA");
        assertState("legal_documento_versiones", winnerSuccessor, "VIGENTE");
        assertState("legal_documento_versiones", loserSuccessor, "BORRADOR");
        assertState("legal_requisito_versiones", winnerRequirement, "VIGENTE");
        assertState("legal_requisito_versiones", loserRequirement, "BORRADOR");
        assertThat(historyCount("legal_documento_transiciones", "documento_version_id",
                loserSuccessor)).isZero();
        assertThat(historyCount("legal_requisito_transiciones", "requisito_version_id",
                loserRequirement)).isZero();
        assertThat(observer.queryForObject(
                "SELECT count(*) FROM legal_documento_vigentes WHERE documento_version_id = ?",
                Long.class,
                loserSuccessor)).isZero();
        assertCurrentProjections(winner.target().publicationId());
    }

    private static void assertCurrentProjections(UUID publicationId) {
        assertThat(observer.queryForList("""
                SELECT DISTINCT publicacion_id
                  FROM legal_documento_vigentes
                 ORDER BY publicacion_id
                """, UUID.class)).containsExactly(publicationId);
        assertThat(observer.queryForList("""
                SELECT DISTINCT publicacion_id
                  FROM legal_requisito_conjuntos_actuales
                 ORDER BY publicacion_id
                """, UUID.class)).containsExactly(publicationId);
    }

    private static void assertState(String table, UUID id, String expected) {
        assertThat(observer.queryForObject(
                "SELECT estado FROM " + table + " WHERE id = ?",
                String.class,
                id)).isEqualTo(expected);
    }

    private static long historyCount(String table, String idColumn, UUID id) {
        return Objects.requireNonNull(observer.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE " + idColumn + " = ?",
                Long.class,
                id));
    }

    private static void assertSinglePromotionSequenceDelta(
            Map<String, SequenceState> before,
            Map<String, SequenceState> after,
            ValidatedRelease release) {
        assertSequenceDelta(
                before,
                after,
                Map.of(
                        DOCUMENT_TRANSITION_SEQUENCE,
                        (long) release.documentCount() * 2,
                        REQUIREMENT_TRANSITION_SEQUENCE,
                        (long) release.requirementCount() * 2));
    }

    private static void assertSequenceDelta(
            Map<String, SequenceState> before,
            Map<String, SequenceState> after,
            Map<String, Long> expectedAdvances) {
        assertThat(after.keySet()).containsExactlyInAnyOrderElementsOf(before.keySet());
        for (String sequence : before.keySet()) {
            long actual = sequencePosition(after.get(sequence))
                    - sequencePosition(before.get(sequence));
            assertThat(actual)
                    .as("avance de %s", sequence)
                    .isEqualTo(expectedAdvances.getOrDefault(sequence, 0L));
        }
    }

    private static long sequencePosition(SequenceState state) {
        return state.called() ? state.lastValue() : 0L;
    }

    private static int currentBackendPid(JdbcTemplate jdbc) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT pg_catalog.pg_backend_pid()",
                Integer.class));
    }

    private static EditorialTransaction editorialTransaction(int pid) {
        List<EditorialTransaction> observations = observer.query("""
                        SELECT pid, application_name, state, xact_start
                          FROM pg_catalog.pg_stat_activity
                         WHERE pid = ?
                        """,
                (resultSet, rowNumber) -> new EditorialTransaction(
                        resultSet.getInt("pid"),
                        resultSet.getString("application_name"),
                        resultSet.getString("state"),
                        Objects.requireNonNull(resultSet.getObject(
                                "xact_start",
                                OffsetDateTime.class)).toInstant()),
                pid);
        assertThat(observations)
                .as("sesión editorial activa para pid=%s", pid)
                .hasSize(1);
        return observations.getFirst();
    }

    private static UUID only(Set<UUID> values) {
        assertThat(values).hasSize(1);
        return values.iterator().next();
    }

    private static void cancel(Future<?> future) {
        if (future != null && !future.isDone()) {
            future.cancel(true);
        }
    }

    private static void shutdown(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    private record EditorialTransaction(
            int pid,
            String applicationName,
            String state,
            Instant xactStart
    ) { }
}
