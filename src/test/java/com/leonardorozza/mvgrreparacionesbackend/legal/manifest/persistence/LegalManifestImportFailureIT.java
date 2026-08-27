package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestImportResult.Outcome;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.CommitAcknowledgementLostDataSource;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.Harness;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.UncooperativeLineWriter;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.assertConfirmed;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.assertKnownFailure;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.assertUnknown;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.awaitValue;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.blockedDocumentInsertPid;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.cleanLegalState;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.goldenRelease;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.harness;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.holdUncommittedDocumentLine;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.migrate;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.openPublicationCount;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.pooledDataSource;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.publicationCount;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.requiredTableCounts;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.sequenceStates;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class LegalManifestImportFailureIT {

    private static final String IMPORT_APPLICATION_NAME =
            "ordenfix-legal-import-failure-it";
    private static final String OBSERVER_APPLICATION_NAME =
            "ordenfix-legal-import-failure-observer";
    private static final String EXTERNAL_WRITER_APPLICATION_NAME =
            "ordenfix-legal-import-failure-writer";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_import_failure")
            .withUsername("ordenfix")
            .withPassword("ordenfix");

    private static HikariDataSource importPool;
    private static DataSource externalWriterDataSource;
    private static JdbcTemplate observer;
    private static Harness production;
    private static ValidatedRelease golden;

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
        golden = goldenRelease(LegalManifestImportFailureIT.class);
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
    void nonCooperativeWriterLockTimesOutAndRollsBackCompleteAttempt()
            throws Exception {
        Harness reduced = harness(
                importPool,
                new LegalDatabaseBudgets(5, 2, 1, 1));
        try (UncooperativeLineWriter externalWriter = holdUncommittedDocumentLine(
                externalWriterDataSource,
                "non-cooperative-timeout-holder",
                "atestacion-datos-cliente")) {
            LegalManifestImportResult timedOut =
                    reduced.importService().importManifest(golden);

            assertKnownFailure(
                    timedOut,
                    LegalManifestStatus.ERROR,
                    LegalManifestIssueCode.IMPORT_DB_LOCK_TIMEOUT);
            assertThat(requiredTableCounts(observer).values()).containsOnly(0L);
            assertThat(openPublicationCount(observer)).isZero();

            externalWriter.release();
            LegalManifestImportResult retry =
                    production.importService().importManifest(golden);

            assertConfirmed(retry, Outcome.IMPORTED);
            assertThat(publicationCount(observer)).isEqualTo(1L);
            assertThat(openPublicationCount(observer)).isZero();
        }
    }

    @Test
    void realPostgresDeadlockMapsConcurrencyAndRollsBackCompleteAttempt()
            throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<LegalManifestImportResult> pending = null;
        try (UncooperativeLineWriter externalWriter = holdUncommittedDocumentLine(
                externalWriterDataSource,
                "non-cooperative-deadlock-holder",
                "terminos")) {
            pending = executor.submit(() ->
                    production.importService().importManifest(golden));
            awaitValue(
                    "import esperando la identidad retenida antes del deadlock",
                    () -> blockedDocumentInsertPid(observer, IMPORT_APPLICATION_NAME));

            // El import retiene el advisory lock y espera la identidad; el writer externo
            // retiene la identidad y pide el mismo advisory lock. PostgreSQL cancela el import
            // al detectar el ciclo antes del lock_timeout de 5 s.
            externalWriter.acquireEditorialLock();
            LegalManifestImportResult deadlocked = pending.get(15, TimeUnit.SECONDS);

            assertKnownFailure(
                    deadlocked,
                    LegalManifestStatus.ERROR,
                    LegalManifestIssueCode.IMPORT_DB_CONCURRENCY);
            externalWriter.release();
            assertThat(requiredTableCounts(observer).values()).containsOnly(0L);
            assertThat(openPublicationCount(observer)).isZero();

            LegalManifestImportResult retry =
                    production.importService().importManifest(golden);
            assertConfirmed(retry, Outcome.IMPORTED);
            assertThat(publicationCount(observer)).isEqualTo(1L);
        } finally {
            if (pending != null) {
                pending.cancel(true);
            }
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void terminatedImportSessionLeavesNoRowsAndPoolRecovers() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<LegalManifestImportResult> pending = null;
        try (UncooperativeLineWriter externalWriter = holdUncommittedDocumentLine(
                externalWriterDataSource,
                "non-cooperative-disconnect-holder",
                "atestacion-datos-cliente")) {
            pending = executor.submit(() ->
                    production.importService().importManifest(golden));
            int backendPid = awaitValue(
                    "sesión importadora esperando una identidad externa",
                    () -> blockedDocumentInsertPid(observer, IMPORT_APPLICATION_NAME));

            Boolean terminated = observer.queryForObject(
                    "SELECT pg_catalog.pg_terminate_backend(?)",
                    Boolean.class,
                    backendPid);
            assertThat(terminated).isTrue();

            LegalManifestImportResult disconnected =
                    pending.get(15, TimeUnit.SECONDS);
            externalWriter.release();

            // The server rolled the session back, but the transaction manager could not
            // acknowledge its own rollback over the terminated connection.
            assertUnknown(disconnected);
            assertThat(requiredTableCounts(observer).values()).containsOnly(0L);
            assertThat(openPublicationCount(observer)).isZero();

            LegalManifestImportResult retry =
                    production.importService().importManifest(golden);
            assertConfirmed(retry, Outcome.IMPORTED);
            assertThat(publicationCount(observer)).isEqualTo(1L);
        } finally {
            if (pending != null) {
                pending.cancel(true);
            }
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void commitAcknowledgementLossReturnsUnknownAndExactRetryReconciles() {
        CommitAcknowledgementLostDataSource ambiguousDataSource =
                new CommitAcknowledgementLostDataSource(importPool);
        Harness ambiguous = harness(
                ambiguousDataSource,
                LegalDatabaseBudgets.production());

        LegalManifestImportResult firstAttempt =
                ambiguous.importService().importManifest(golden);

        assertUnknown(firstAttempt);
        assertThat(ambiguousDataSource.armed()).isFalse();
        assertThat(publicationCount(observer)).isEqualTo(1L);
        assertThat(openPublicationCount(observer)).isZero();
        UUID persistedPublication = observer.queryForObject("""
                SELECT id
                  FROM legal_publicaciones
                 WHERE publication_external_id = ?
                   AND estado_construccion = 'SELLADO'
                """, UUID.class, golden.plan().manifest().publicationId());
        assertThat(persistedPublication).isNotNull();
        Map<String, Long> rowsBeforeReplay = requiredTableCounts(observer);
        Map<String, LegalManifestPersistenceITSupport.SequenceState> sequencesBeforeReplay =
                sequenceStates(observer);

        LegalManifestImportResult replay =
                ambiguous.importService().importManifest(golden);

        assertConfirmed(replay, Outcome.ALREADY_IMPORTED);
        assertThat(replay.receipt().orElseThrow().publicationUuid())
                .isEqualTo(persistedPublication);
        assertThat(requiredTableCounts(observer)).isEqualTo(rowsBeforeReplay);
        assertThat(sequenceStates(observer)).isEqualTo(sequencesBeforeReplay);
        assertThat(publicationCount(observer)).isEqualTo(1L);
    }
}
