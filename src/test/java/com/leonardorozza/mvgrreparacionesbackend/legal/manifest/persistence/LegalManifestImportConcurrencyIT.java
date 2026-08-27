package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidation;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestDryRunService.DryRunResult;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestImportResult.Outcome;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.Harness;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.PublicationGraphCounts;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.ReleaseMutation;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.UncooperativeLineWriter;
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
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.assertConfirmed;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.assertDryRunPass;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.assertKnownFailure;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.awaitCondition;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.awaitLatch;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.blockedDocumentInsertPid;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.blockedEditorialGateWaits;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.cleanLegalState;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.copyRelease;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.goldenRelease;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.harness;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.holdUncommittedDocumentLine;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.migrate;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.openPublicationCount;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.pooledDataSource;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.publicationCount;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.publicationGraphCounts;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.requiredTableCounts;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.sequenceStates;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class LegalManifestImportConcurrencyIT {

    private static final String IMPORT_APPLICATION_NAME =
            "ordenfix-legal-import-concurrency-it";
    private static final String OBSERVER_APPLICATION_NAME =
            "ordenfix-legal-import-concurrency-observer";
    private static final String EXTERNAL_WRITER_APPLICATION_NAME =
            "ordenfix-legal-import-concurrency-writer";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_import_concurrency")
            .withUsername("ordenfix")
            .withPassword("ordenfix");

    private static HikariDataSource importPool;
    private static DataSource externalWriterDataSource;
    private static JdbcTemplate observer;
    private static Harness production;
    private static ValidatedRelease golden;

    @TempDir
    private Path temporaryDirectory;

    @BeforeAll
    static void migrateAndAssembleServices() throws Exception {
        migrate(POSTGRES);
        importPool = pooledDataSource(POSTGRES, IMPORT_APPLICATION_NAME);
        externalWriterDataSource = LegalManifestPersistenceITSupport.directDataSource(
                POSTGRES,
                EXTERNAL_WRITER_APPLICATION_NAME);
        observer = new JdbcTemplate(LegalManifestPersistenceITSupport.directDataSource(
                POSTGRES,
                OBSERVER_APPLICATION_NAME));
        production = harness(importPool, LegalDatabaseBudgets.production());
        golden = goldenRelease(LegalManifestImportConcurrencyIT.class);
    }

    @AfterAll
    static void closePool() {
        if (importPool != null) {
            importPool.close();
        }
    }

    @BeforeEach
    void cleanDatabase() {
        cleanLegalState(observer);
    }

    @Test
    void identicalImportsSerializeToImportedAndAlreadyImported() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch firstInsideCallback = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicBoolean pauseFirst = new AtomicBoolean(true);
        Future<LegalManifestImportResult> first = executor.submit(() ->
                production.importService().importManifest(golden, () -> {
                    if (pauseFirst.compareAndSet(true, false)) {
                        firstInsideCallback.countDown();
                        awaitLatch(releaseFirst, "primer import retenido dentro del callback");
                    }
                }));
        Future<LegalManifestImportResult> second = null;

        try {
            awaitLatch(firstInsideCallback, "primer import dentro del callback");
            second = executor.submit(() -> production.importService().importManifest(golden));
            awaitCondition(
                    "segundo import esperando el advisory lock editorial",
                    () -> blockedEditorialGateWaits(observer, IMPORT_APPLICATION_NAME) == 1);
            releaseFirst.countDown();

            LegalManifestImportResult imported = first.get(30, TimeUnit.SECONDS);
            LegalManifestImportResult replay = second.get(30, TimeUnit.SECONDS);

            assertConfirmed(imported, Outcome.IMPORTED);
            assertConfirmed(replay, Outcome.ALREADY_IMPORTED);
            assertThat(replay.receipt()).isEqualTo(imported.receipt());
            assertThat(publicationCount(observer)).isEqualTo(1L);
            assertThat(openPublicationCount(observer)).isZero();
        } finally {
            releaseFirst.countDown();
            first.cancel(true);
            if (second != null) {
                second.cancel(true);
            }
            shutdown(executor);
        }
    }

    @Test
    void secondImportTimesOutWithinReducedBudgetThenExactRetryReconciles()
            throws Exception {
        Harness reduced = harness(
                importPool,
                new LegalDatabaseBudgets(5, 2, 1, 1));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch firstInsideCallback = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        Future<LegalManifestImportResult> first = executor.submit(() ->
                production.importService().importManifest(golden, () -> {
                    firstInsideCallback.countDown();
                    awaitLatch(releaseFirst, "primer import retenido más allá del presupuesto");
                }));

        try {
            awaitLatch(firstInsideCallback, "primer import dentro del callback");
            Map<String, LegalManifestPersistenceITSupport.SequenceState> sequencesBefore =
                    sequenceStates(observer);

            LegalManifestImportResult timedOut =
                    reduced.importService().importManifest(golden);

            assertKnownFailure(
                    timedOut,
                    LegalManifestStatus.ERROR,
                    LegalManifestIssueCode.IMPORT_DB_LOCK_TIMEOUT);
            assertThat(sequenceStates(observer)).isEqualTo(sequencesBefore);
            assertThat(requiredTableCounts(observer).values()).containsOnly(0L);

            releaseFirst.countDown();
            LegalManifestImportResult imported = first.get(30, TimeUnit.SECONDS);
            LegalManifestImportResult replay =
                    production.importService().importManifest(golden);

            assertConfirmed(imported, Outcome.IMPORTED);
            assertConfirmed(replay, Outcome.ALREADY_IMPORTED);
            assertThat(replay.receipt()).isEqualTo(imported.receipt());
        } finally {
            releaseFirst.countDown();
            first.cancel(true);
            shutdown(executor);
        }
    }

    @Test
    void distinctCompatibleReleasesSerializeReuseAndReplayPreservesCounts()
            throws Exception {
        ValidatedRelease firstRelease = copyRelease(
                temporaryDirectory,
                LegalManifestImportConcurrencyIT.class,
                "concurrent-compatible-a-v1",
                ReleaseMutation.NONE);
        ValidatedRelease secondRelease = copyRelease(
                temporaryDirectory,
                LegalManifestImportConcurrencyIT.class,
                "concurrent-compatible-b-v1",
                ReleaseMutation.NONE);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch firstInsideCallback = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        Future<LegalManifestImportResult> first = executor.submit(() ->
                production.importService().importManifest(firstRelease, () -> {
                    firstInsideCallback.countDown();
                    awaitLatch(releaseFirst, "primera publicación compatible retenida");
                }));
        Future<LegalManifestImportResult> second = null;

        try {
            awaitLatch(firstInsideCallback, "primera publicación compatible dentro del callback");
            second = executor.submit(() ->
                    production.importService().importManifest(secondRelease));
            awaitCondition(
                    "segunda publicación compatible esperando el gate",
                    () -> blockedEditorialGateWaits(observer, IMPORT_APPLICATION_NAME) == 1);
            releaseFirst.countDown();

            LegalManifestImportResult firstImported = first.get(30, TimeUnit.SECONDS);
            LegalManifestImportResult secondImported = second.get(30, TimeUnit.SECONDS);
            assertConfirmed(firstImported, Outcome.IMPORTED);
            assertConfirmed(secondImported, Outcome.IMPORTED);

            PublicationGraphCounts firstCounts = publicationGraphCounts(
                    observer,
                    firstImported.receipt().orElseThrow().publicationUuid());
            PublicationGraphCounts secondCounts = publicationGraphCounts(
                    observer,
                    secondImported.receipt().orElseThrow().publicationUuid());
            assertThat(firstCounts).isEqualTo(new PublicationGraphCounts(
                    11, 6, 8,
                    11, 11, 0,
                    6, 6, 0));
            assertThat(secondCounts).isEqualTo(new PublicationGraphCounts(
                    11, 6, 8,
                    0, 0, 11,
                    0, 0, 6));

            Map<String, Long> rowsBeforeReplay = requiredTableCounts(observer);
            Map<String, LegalManifestPersistenceITSupport.SequenceState> sequencesBeforeReplay =
                    sequenceStates(observer);
            LegalManifestImportResult replay =
                    production.importService().importManifest(secondRelease);

            assertConfirmed(replay, Outcome.ALREADY_IMPORTED);
            assertThat(replay.receipt()).isEqualTo(secondImported.receipt());
            assertThat(publicationGraphCounts(
                    observer,
                    replay.receipt().orElseThrow().publicationUuid()))
                    .isEqualTo(secondCounts);
            assertThat(requiredTableCounts(observer)).isEqualTo(rowsBeforeReplay);
            assertThat(sequenceStates(observer)).isEqualTo(sequencesBeforeReplay);
        } finally {
            releaseFirst.countDown();
            first.cancel(true);
            if (second != null) {
                second.cancel(true);
            }
            shutdown(executor);
        }
    }

    @Test
    void distinctIncompatibleReleasesConfirmAtMostOne() throws Exception {
        ValidatedRelease compatible = copyRelease(
                temporaryDirectory,
                LegalManifestImportConcurrencyIT.class,
                "concurrent-incompatible-a-v1",
                ReleaseMutation.NONE);
        ValidatedRelease incompatible = copyRelease(
                temporaryDirectory,
                LegalManifestImportConcurrencyIT.class,
                "concurrent-incompatible-b-v1",
                LegalManifestPersistenceITSupport::replaceTermsWithConflictingValidContent);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch firstInsideCallback = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        Future<LegalManifestImportResult> first = executor.submit(() ->
                production.importService().importManifest(compatible, () -> {
                    firstInsideCallback.countDown();
                    awaitLatch(releaseFirst, "primera publicación incompatible retenida");
                }));
        Future<LegalManifestImportResult> second = null;

        try {
            awaitLatch(firstInsideCallback, "primera publicación incompatible dentro del callback");
            second = executor.submit(() ->
                    production.importService().importManifest(incompatible));
            awaitCondition(
                    "segunda publicación incompatible esperando el gate",
                    () -> blockedEditorialGateWaits(observer, IMPORT_APPLICATION_NAME) == 1);
            releaseFirst.countDown();

            LegalManifestImportResult imported = first.get(30, TimeUnit.SECONDS);
            LegalManifestImportResult blocked = second.get(30, TimeUnit.SECONDS);

            assertConfirmed(imported, Outcome.IMPORTED);
            assertKnownFailure(
                    blocked,
                    LegalManifestStatus.BLOCKED,
                    LegalManifestIssueCode.IMPORT_DB_PERSISTED_CONFLICT);
            assertThat(publicationCount(observer)).isEqualTo(1L);
            assertThat(openPublicationCount(observer)).isZero();
        } finally {
            releaseFirst.countDown();
            first.cancel(true);
            if (second != null) {
                second.cancel(true);
            }
            shutdown(executor);
        }
    }

    @Test
    void dryRunAndImportShareEditorialGateWithoutExposingPartialGraph()
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<LegalManifestValidation<DryRunResult>> dryRun = null;
        Future<LegalManifestImportResult> importAttempt = null;
        try (UncooperativeLineWriter externalWriter = holdUncommittedDocumentLine(
                externalWriterDataSource,
                "non-cooperative-dry-run-holder",
                "atestacion-datos-cliente")) {
            dryRun = executor.submit(() -> production.dryRunService().dryRun(golden));
            awaitCondition(
                    "dry-run con grafo provisional esperando el writer externo",
                    () -> blockedDocumentInsertPid(observer, IMPORT_APPLICATION_NAME).isPresent());

            importAttempt = executor.submit(() ->
                    production.importService().importManifest(golden));
            awaitCondition(
                    "import cooperativo esperando el lock retenido por el dry-run",
                    () -> blockedEditorialGateWaits(observer, IMPORT_APPLICATION_NAME) == 1);
            assertThat(requiredTableCounts(observer).values()).containsOnly(0L);

            externalWriter.release();
            LegalManifestValidation<DryRunResult> dryRunResult =
                    dryRun.get(30, TimeUnit.SECONDS);
            LegalManifestImportResult importResult =
                    importAttempt.get(30, TimeUnit.SECONDS);

            assertDryRunPass(dryRunResult);
            assertConfirmed(importResult, Outcome.IMPORTED);
            assertThat(publicationCount(observer)).isEqualTo(1L);
            assertThat(openPublicationCount(observer)).isZero();
        } finally {
            if (dryRun != null) {
                dryRun.cancel(true);
            }
            if (importAttempt != null) {
                importAttempt.cancel(true);
            }
            shutdown(executor);
        }
    }

    private static void shutdown(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
}
