package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalApplicableScopeResolver;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalApplicableScopeSet;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PerfilAgregadoLegal;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.awaitCondition;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.awaitLatch;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.awaitValue;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.blockedEditorialGateWaits;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.terminateBackend;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.awaitBackendGone;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class LegalRequiredSetAggregateConcurrencyIT {

    private static final String ROLE = "ordenfix_legal_aggregate_concurrency_it";
    private static final String PASSWORD = "legal-aggregate-concurrency-test-only";
    private static final String MATERIALIZER_APPLICATION =
            "ordenfix-legal-aggregate-concurrency-materializer";
    private static final String EXCLUSIVE_APPLICATION =
            "ordenfix-legal-aggregate-concurrency-exclusive";
    private static final String OBSERVER_APPLICATION =
            "ordenfix-legal-aggregate-concurrency-observer";
    private static final int FUTURE_TIMEOUT_SECONDS = 20;
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("ordenfix_legal_aggregate_concurrency")
                    .withUsername("ordenfix")
                    .withPassword("ordenfix");

    @TempDir
    private static Path temporaryDirectory;

    private static HikariDataSource materializerPool;
    private static DataSource exclusiveDataSource;
    private static JdbcTemplate observer;
    private static LegalV28AggregateITSupport.AggregateHarness aggregate;
    private static LegalApplicableScopeSet userScopes;
    private static LegalApplicableScopeSet adminScopes;
    private UUID currentPublicationId;

    @BeforeAll
    static void migrateSeedAndAssembleConcurrentRestrictedRuntime() throws Exception {
        POSTGRES.start();
        Flyway.configure()
                .dataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        DataSource ownerDataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
        JdbcTemplate owner = new JdbcTemplate(ownerDataSource);
        LegalRestrictedAggregateRoleFixture.Credentials credentials =
                new LegalRestrictedAggregateRoleFixture(
                        owner,
                        POSTGRES.getJdbcUrl(),
                        ROLE,
                        PASSWORD,
                        POSTGRES.getDriverClassName())
                        .provisionAndVerify();
        materializerPool = LegalManifestPersistenceITSupport.pooledDataSource(
                POSTGRES,
                MATERIALIZER_APPLICATION,
                credentials.username(),
                credentials.password());
        exclusiveDataSource = LegalManifestPersistenceITSupport.directDataSource(
                POSTGRES,
                EXCLUSIVE_APPLICATION);
        observer = new JdbcTemplate(LegalManifestPersistenceITSupport.directDataSource(
                POSTGRES,
                OBSERVER_APPLICATION));
        aggregate = LegalV28AggregateITSupport.aggregateHarness(materializerPool, ROLE);
        LegalApplicableScopeResolver resolver = new LegalApplicableScopeResolver();
        userScopes = resolver.resolve(
                PerfilAgregadoLegal.AUTHENTICATED_PENDING,
                LocaleLegal.ES_AR,
                AudienciaLegal.USER);
        adminScopes = resolver.resolve(
                PerfilAgregadoLegal.AUTHENTICATED_PENDING,
                LocaleLegal.ES_AR,
                AudienciaLegal.ADMIN_TITULAR);
    }

    @BeforeEach
    void seedAnIndependentCurrentGraph() throws Exception {
        observer.execute("""
                TRUNCATE TABLE legal_requisito_agregados, legal_publicaciones,
                               legal_documento_reemplazo_lotes
                RESTART IDENTITY CASCADE
                """);
        currentPublicationId = importEquivalentPublication();
        LegalManifestPersistenceITSupport.promoteToReady(observer, currentPublicationId);
    }

    private static UUID importEquivalentPublication() throws Exception {
        return LegalV28AggregateITSupport.importRelease(exclusiveDataSource,
                LegalV28AggregateITSupport.releaseWithContinuedUse(
                        temporaryDirectory, LegalRequiredSetAggregateConcurrencyIT.class,
                        "aggregate-concurrency-" + UUID.randomUUID()));
    }

    @AfterAll
    static void closeResources() {
        if (materializerPool != null) {
            materializerPool.close();
        }
        POSTGRES.stop();
    }

    @Test
    void identicalPhysicalMaterializationsRaceToOneCreatedOneReusedAndOneHeader()
            throws Exception {
        CountDownLatch bothHoldSharedLock = new CountDownLatch(2);
        CountDownLatch releaseRace = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<LegalRequiredSetAggregateReceipt> first = null;
        Future<LegalRequiredSetAggregateReceipt> second = null;
        try (Connection insertBarrier = exclusiveDataSource.getConnection()) {
            insertBarrier.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            insertBarrier.setAutoCommit(false);
            try (PreparedStatement statement = insertBarrier.prepareStatement(
                    "LOCK TABLE legal_requisito_agregados IN SHARE MODE")) {
                statement.execute();
            }
            first = executor.submit(() -> materializeAfterCheckpoint(
                    userScopes,
                    bothHoldSharedLock,
                    releaseRace));
            second = executor.submit(() -> materializeAfterCheckpoint(
                    userScopes,
                    bothHoldSharedLock,
                    releaseRace));
            awaitLatch(bothHoldSharedLock, "ambos materializadores con lock compartido");
            assertThat(heldEditorialLocks(MATERIALIZER_APPLICATION, "ShareLock"))
                    .isEqualTo(2);
            releaseRace.countDown();
            awaitCondition(
                    "ambos INSERT agregados detenidos en la barrera de carrera",
                    () -> blockedHeaderInserts() == 2);
            insertBarrier.commit();

            List<LegalRequiredSetAggregateReceipt> receipts = List.of(
                    first.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    second.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertThat(receipts)
                    .extracting(LegalRequiredSetAggregateReceipt::outcome)
                    .containsExactlyInAnyOrder(
                            LegalRequiredSetAggregateReceipt.Outcome.CREATED,
                            LegalRequiredSetAggregateReceipt.Outcome.REUSED);
            assertThat(receipts)
                    .extracting(LegalRequiredSetAggregateReceipt::aggregateId)
                    .containsOnly(receipts.getFirst().aggregateId());
            assertThat(receipts)
                    .extracting(LegalRequiredSetAggregateReceipt::requiredSetRevision)
                    .containsOnly(receipts.getFirst().requiredSetRevision());
            assertThat(receipts)
                    .extracting(LegalRequiredSetAggregateReceipt::provenanceFingerprint)
                    .containsOnly(receipts.getFirst().provenanceFingerprint());
            assertThat(observer.queryForObject("""
                    SELECT count(*)
                      FROM legal_requisito_agregados
                     WHERE perfil = 'AUTHENTICATED_PENDING'
                       AND locale = 'es-AR'
                       AND audiencia = 'USER'
                       AND required_set_revision = ?
                       AND provenance_fingerprint = ?
                    """, Long.class,
                    receipts.getFirst().requiredSetRevision(),
                    receipts.getFirst().provenanceFingerprint())).isEqualTo(1L);
            assertThat(observer.queryForObject("""
                    SELECT count(*)
                      FROM legal_requisito_agregado_scopes
                     WHERE agregado_id = ?
                    """, Long.class,
                    receipts.getFirst().aggregateId())).isEqualTo(1L);
        } finally {
            releaseRace.countDown();
            cancel(first);
            cancel(second);
            shutdown(executor);
        }
    }

    @Test
    void twoSharedTransactionsProgressTogetherWhileExclusiveWaitsForBoth()
            throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        CountDownLatch releaseShared = new CountDownLatch(1);
        CountDownLatch exclusiveStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(3);
        Future<Void> first = null;
        Future<Void> second = null;
        Future<Integer> exclusive = null;
        try {
            first = executor.submit(() -> holdShared(firstEntered, releaseShared));
            awaitLatch(firstEntered, "primer lock compartido");
            second = executor.submit(() -> holdShared(secondEntered, releaseShared));
            awaitLatch(secondEntered, "segundo lock compartido compatible");
            assertThat(heldEditorialLocks(MATERIALIZER_APPLICATION, "ShareLock"))
                    .isEqualTo(2);

            exclusive = executor.submit(() -> acquireExclusiveAndCommit(exclusiveStarted));
            awaitLatch(exclusiveStarted, "writer exclusivo iniciado");
            awaitCondition(
                    "writer exclusivo bloqueado por ambos shared",
                    () -> blockedEditorialGateWaits(observer, EXCLUSIVE_APPLICATION) == 1);
            assertThat(exclusive.isDone()).isFalse();

            releaseShared.countDown();
            first.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            second.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(exclusive.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isPositive();
        } finally {
            releaseShared.countDown();
            cancel(first);
            cancel(second);
            cancel(exclusive);
            shutdown(executor);
        }
    }

    @Test
    void transactionStartedBeforeExclusiveWaitUsesPostLockStatementTimestamp()
            throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<LegalEditorialTimeBoundary> boundary = new AtomicReference<>();
        Future<LegalRequiredSetAggregateReceipt> future = null;
        try (Connection exclusive = exclusiveDataSource.getConnection()) {
            exclusive.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            exclusive.setAutoCommit(false);
            int exclusivePid = currentBackendPid(exclusive);
            acquireEditorialLock(exclusive, false);

            future = executor.submit(() -> aggregate.gate().executeMutableShared(
                    (status, observed) -> {
                        boundary.set(observed);
                        return aggregate.store().materialize(adminScopes, observed);
                    }));
            BlockedTransaction blocked = awaitValue(
                    "materializador shared esperando al writer exclusivo",
                    () -> blockedMaterializer(exclusivePid));
            assertThat(boundary.get()).isNull();
            assertThat(future.isDone()).isFalse();
            Instant releaseMarkedAt = statementTimestamp(exclusive);
            assertThat(blocked.startedAt()).isBeforeOrEqualTo(releaseMarkedAt);

            exclusive.commit();
            LegalRequiredSetAggregateReceipt receipt = future.get(
                    FUTURE_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS);
            assertThat(receipt.outcome())
                    .isEqualTo(LegalRequiredSetAggregateReceipt.Outcome.CREATED);
            assertThat(boundary.get().transactionAt()).isEqualTo(blocked.startedAt());
            assertThat(boundary.get().observedAt()).isAfterOrEqualTo(releaseMarkedAt);
            assertThat(receipt.createdAt()).isAfterOrEqualTo(boundary.get().observedAt());
            assertThat(receipt.createdAt()).isAfter(blocked.startedAt());
        } finally {
            cancel(future);
            shutdown(executor);
        }
    }

    @Test
    void deferredCardinalityFailureRollsBackHeaderAndEveryInsertedScope() {
        LegalV28AggregateITSupport.Pointer pointer =
                LegalV28AggregateITSupport.currentPointer(
                        aggregate.jdbc(),
                        ContextoLegal.USO_CONTINUADO,
                        AudienciaLegal.USER);
        UUID aggregateId = UUID.randomUUID();
        AtomicBoolean callbackCompleted = new AtomicBoolean();

        Throwable failure = catchThrowable(() -> aggregate.gate().executeMutableShared(
                (status, boundary) -> {
                    LegalV28AggregateITSupport.insertHeader(
                            aggregate.jdbc(),
                            aggregateId,
                            PerfilAgregadoLegal.AUTHENTICATED_PENDING,
                            AudienciaLegal.USER,
                            LegalV28AggregateITSupport.digest('c'),
                            LegalV28AggregateITSupport.digest('d'),
                            2);
                    LegalV28AggregateITSupport.insertScope(
                            aggregate.jdbc(),
                            aggregateId,
                            1,
                            pointer);
                    callbackCompleted.set(true);
                    return null;
                }));

        assertThat(callbackCompleted).isTrue();
        LegalV28AggregateITSupport.assertSqlState(failure, "23514");
        assertThat(aggregate.jdbc().queryForObject(
                "SELECT count(*) FROM legal_requisito_agregados WHERE id = ?",
                Long.class,
                aggregateId)).isZero();
        assertThat(aggregate.jdbc().queryForObject(
                "SELECT count(*) FROM legal_requisito_agregado_scopes WHERE agregado_id = ?",
                Long.class,
                aggregateId)).isZero();
    }

    @Test
    void concurrentEditorialPromotionExposesOnlyWholeOldOrNewVectors() throws Exception {
        UUID nextPublicationId = importEquivalentPublication();
        LegalApplicableScopeSet scopes = new LegalApplicableScopeResolver(
                (profile, audience) -> List.of(ContextoLegal.REGISTRO, ContextoLegal.USO_CONTINUADO))
                .resolve(PerfilAgregadoLegal.AUTHENTICATED_PENDING,
                        LocaleLegal.ES_AR, AudienciaLegal.ADMIN_TITULAR);
        CountDownLatch oldRead = new CountDownLatch(1);
        CountDownLatch releaseOld = new CountDownLatch(1);
        CountDownLatch partialPromotion = new CountDownLatch(1);
        CountDownLatch finishPromotion = new CountDownLatch(1);
        AtomicBoolean nextEntered = new AtomicBoolean();
        ExecutorService executor = Executors.newFixedThreadPool(3);
        Future<LegalRequiredSetAggregateReceipt> old = null;
        Future<?> writer = null;
        Future<LegalRequiredSetAggregateReceipt> next = null;
        try {
            old = executor.submit(() -> aggregate.gate().executeMutableShared((status, boundary) -> {
                LegalRequiredSetAggregateReceipt receipt = aggregate.store().materialize(scopes, boundary);
                oldRead.countDown();
                awaitLatch(releaseOld, "lector anterior confirma antes de promover");
                return receipt;
            }));
            awaitLatch(oldRead, "vector anterior materializado bajo shared");
            writer = executor.submit(() -> {
                promoteEquivalentPublication(nextPublicationId, partialPromotion, finishPromotion);
                return null;
            });
            awaitCondition("promoción espera al lector anterior", () ->
                    blockedEditorialGateWaits(observer, EXCLUSIVE_APPLICATION) == 1);
            releaseOld.countDown();
            LegalRequiredSetAggregateReceipt before = old.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            awaitLatch(partialPromotion, "writer con slots nuevos y primer scope reemplazado");
            next = executor.submit(() -> aggregate.gate().executeMutableShared((status, boundary) -> {
                nextEntered.set(true);
                return aggregate.store().materialize(scopes, boundary);
            }));
            awaitCondition("lector nuevo bloqueado mientras el writer tiene vector parcial", () ->
                    blockedEditorialGateWaits(observer, MATERIALIZER_APPLICATION) == 1);
            assertThat(nextEntered).isFalse();
            assertThat(next.isDone()).isFalse();
            finishPromotion.countDown();
            writer.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            LegalRequiredSetAggregateReceipt after = next.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertThat(before.provenance().scopes()).hasSize(2).allSatisfy(scope ->
                    assertThat(scope.publicationId()).isEqualTo(currentPublicationId));
            assertThat(after.provenance().scopes()).hasSize(2).allSatisfy(scope ->
                    assertThat(scope.publicationId()).isEqualTo(nextPublicationId));
            assertThat(before.outcome()).isEqualTo(LegalRequiredSetAggregateReceipt.Outcome.CREATED);
            assertThat(after.outcome()).isEqualTo(LegalRequiredSetAggregateReceipt.Outcome.CREATED);
            assertThat(after.requiredSetRevision()).isEqualTo(before.requiredSetRevision());
            assertThat(after.aggregateId()).isNotEqualTo(before.aggregateId());
            assertThat(after.provenanceFingerprint()).isNotEqualTo(before.provenanceFingerprint());
            assertThat(observer.queryForObject("SELECT count(*) FROM legal_requisito_agregados", Long.class))
                    .isEqualTo(2);
            assertThat(observer.queryForObject("SELECT count(*) FROM legal_requisito_agregado_scopes", Long.class))
                    .isEqualTo(4);
        } finally {
            releaseOld.countDown();
            finishPromotion.countDown();
            cancel(old);
            cancel(writer);
            cancel(next);
            shutdown(executor);
        }
    }

    @Test
    void lockTimeoutAfterMaterializationRollsBackAndOnlyExplicitRetryConverges() throws Exception {
        long auxiliaryKey = 120_601L;
        AtomicReference<LegalRequiredSetAggregateReceipt> tentative = new AtomicReference<>();
        AtomicInteger callbacks = new AtomicInteger();
        AtomicInteger pid = new AtomicInteger();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Throwable> attempt = null;
        try (Connection blocker = exclusiveDataSource.getConnection()) {
            blocker.setAutoCommit(false);
            auxiliaryLock(blocker, auxiliaryKey);
            attempt = executor.submit(() -> catchThrowable(() -> aggregate.gate().executeMutableShared(
                    (status, boundary) -> {
                        callbacks.incrementAndGet();
                        pid.set(aggregate.jdbc().queryForObject("SELECT pg_backend_pid()", Integer.class));
                        tentative.set(aggregate.store().materialize(userScopes, boundary));
                        // Fault checkpoint after real DML. Shrink, never relax, the production budget.
                        aggregate.jdbc().execute("SET LOCAL lock_timeout TO '1s'");
                        auxiliaryLock(aggregate.jdbc(), auxiliaryKey);
                        return tentative.get();
                    })));
            // The blocker stays held until PostgreSQL reports the timeout. Do not race
            // the one-second deadline merely to observe its transient pg_locks row.
            Throwable failure = attempt.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            LegalV28AggregateITSupport.assertSqlState(failure, "55P03");
            assertThat(callbacks).hasValue(1);
            assertAbsent(tentative.get().aggregateId());
            blocker.rollback();
        } finally {
            cancel(attempt);
            shutdown(executor);
        }
        assertExplicitRetry(userScopes, tentative.get());
    }

    @Test
    void realDeadlockAfterDmlRollsBackOneVictimAndExplicitRetryConverges() throws Exception {
        CountDownLatch bothInserted = new CountDownLatch(2);
        CountDownLatch formCycle = new CountDownLatch(1);
        AtomicInteger firstPid = new AtomicInteger();
        AtomicInteger secondPid = new AtomicInteger();
        AtomicInteger callbacks = new AtomicInteger();
        AtomicReference<LegalRequiredSetAggregateReceipt> firstTentative = new AtomicReference<>();
        AtomicReference<LegalRequiredSetAggregateReceipt> secondTentative = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<Throwable> first = null;
        Future<Throwable> second = null;
        try {
            first = executor.submit(() -> deadlockAttempt(userScopes, 120_611L, 120_612L,
                    firstPid, firstTentative, callbacks, bothInserted, formCycle));
            second = executor.submit(() -> deadlockAttempt(adminScopes, 120_612L, 120_611L,
                    secondPid, secondTentative, callbacks, bothInserted, formCycle));
            awaitLatch(bothInserted, "ambas transacciones con agregado y lock auxiliar propios");
            assertThat(firstTentative.get()).isNotNull();
            assertThat(secondTentative.get()).isNotNull();
            formCycle.countDown();
            Throwable firstFailure = first.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            Throwable secondFailure = second.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat((firstFailure == null) != (secondFailure == null)).isTrue();
            Throwable victim = firstFailure == null ? secondFailure : firstFailure;
            LegalV28AggregateITSupport.assertSqlState(victim, "40P01");
            assertDeadlockParticipants(victim, firstPid.get(), secondPid.get());
            assertThat(callbacks).hasValue(2);
            LegalRequiredSetAggregateReceipt aborted = firstFailure == null
                    ? secondTentative.get() : firstTentative.get();
            assertAbsent(aborted.aggregateId());
            assertThat(observer.queryForObject("SELECT count(*) FROM legal_requisito_agregados", Long.class))
                    .isEqualTo(1);
            assertThat(observer.queryForObject("SELECT count(*) FROM legal_requisito_agregado_scopes", Long.class))
                    .isEqualTo(1);
            assertExplicitRetry(firstFailure == null ? adminScopes : userScopes, aborted);
            assertThat(observer.queryForObject("SELECT count(*) FROM legal_requisito_agregados", Long.class))
                    .isEqualTo(2);
        } finally {
            formCycle.countDown();
            cancel(first);
            cancel(second);
            shutdown(executor);
        }
    }

    @Test
    void terminatedSessionAfterDmlLeavesNoPartialAggregateAndRetryUsesANewSession() throws Exception {
        long auxiliaryKey = 120_621L;
        AtomicReference<LegalRequiredSetAggregateReceipt> tentative = new AtomicReference<>();
        AtomicInteger pid = new AtomicInteger();
        AtomicInteger callbacks = new AtomicInteger();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Throwable> attempt = null;
        try (Connection blocker = exclusiveDataSource.getConnection()) {
            blocker.setAutoCommit(false);
            auxiliaryLock(blocker, auxiliaryKey);
            int blockerPid = currentBackendPid(blocker);
            attempt = executor.submit(() -> catchThrowable(() -> aggregate.gate().executeMutableShared(
                    (status, boundary) -> {
                        callbacks.incrementAndGet();
                        pid.set(aggregate.jdbc().queryForObject("SELECT pg_backend_pid()", Integer.class));
                        tentative.set(aggregate.store().materialize(userScopes, boundary));
                        auxiliaryLock(aggregate.jdbc(), auxiliaryKey);
                        return tentative.get();
                    })));
            awaitCondition("sesión bloqueada después de cabecera y miembros", () ->
                    pid.get() > 0 && isBlockedBy(pid.get(), blockerPid));
            assertThat(terminateBackend(observer, pid.get())).isTrue();
            awaitBackendGone(observer, pid.get());
            Throwable failure = attempt.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(failure).isInstanceOf(RuntimeException.class);
            assertThat(callbacks).hasValue(1);
            assertAbsent(tentative.get().aggregateId());
            blocker.rollback();
        } finally {
            cancel(attempt);
            shutdown(executor);
        }
        assertExplicitRetry(userScopes, tentative.get());
        Integer retryPid = aggregate.gate().executeMutableShared((status, boundary) ->
                aggregate.jdbc().queryForObject("SELECT pg_backend_pid()", Integer.class));
        assertThat(retryPid).isNotEqualTo(pid.get());
    }

    private static Throwable deadlockAttempt(
            LegalApplicableScopeSet scopes, long ownKey, long otherKey,
            AtomicInteger pid, AtomicReference<LegalRequiredSetAggregateReceipt> tentative,
            AtomicInteger callbacks, CountDownLatch bothInserted, CountDownLatch formCycle) {
        return catchThrowable(() -> aggregate.gate().executeMutableShared((status, boundary) -> {
            callbacks.incrementAndGet();
            pid.set(aggregate.jdbc().queryForObject("SELECT pg_backend_pid()", Integer.class));
            tentative.set(aggregate.store().materialize(scopes, boundary));
            auxiliaryLock(aggregate.jdbc(), ownKey);
            bothInserted.countDown();
            awaitLatch(formCycle, "formar deadlock después de INSERT reales");
            auxiliaryLock(aggregate.jdbc(), otherKey);
            return tentative.get();
        }));
    }

    private static void assertAbsent(UUID aggregateId) {
        assertThat(aggregateId).isNotNull();
        assertThat(observer.queryForObject(
                "SELECT count(*) FROM legal_requisito_agregados WHERE id = ?", Long.class, aggregateId))
                .isZero();
        assertThat(observer.queryForObject(
                "SELECT count(*) FROM legal_requisito_agregado_scopes WHERE agregado_id = ?", Long.class, aggregateId))
                .isZero();
        assertThat(observer.queryForObject(
                "SELECT count(*) FROM legal_aceptacion_lotes WHERE agregado_id = ?", Long.class, aggregateId))
                .isZero();
    }

    private static void assertDeadlockParticipants(Throwable failure, int firstPid, int secondPid) {
        Throwable cause = failure;
        while (cause != null && !(cause instanceof SQLException)) {
            cause = cause.getCause();
        }
        assertThat(cause).isInstanceOf(SQLException.class);
        // PostgreSQL's durable deadlock detail identifies both actual sessions, even
        // if the observer is scheduled only after the detector has broken the cycle.
        assertThat(cause.getMessage()).contains("Process " + firstPid, "Process " + secondPid);
    }

    private static void assertExplicitRetry(
            LegalApplicableScopeSet scopes, LegalRequiredSetAggregateReceipt aborted) {
        LegalRequiredSetAggregateReceipt created = aggregate.gate().executeMutableShared(
                (status, boundary) -> aggregate.store().materialize(scopes, boundary));
        LegalRequiredSetAggregateReceipt reused = aggregate.gate().executeMutableShared(
                (status, boundary) -> aggregate.store().materialize(scopes, boundary));
        assertThat(created.outcome()).isEqualTo(LegalRequiredSetAggregateReceipt.Outcome.CREATED);
        assertThat(created.aggregateId()).isNotEqualTo(aborted.aggregateId());
        assertThat(created.requiredSetRevision()).isEqualTo(aborted.requiredSetRevision());
        assertThat(created.provenance()).isEqualTo(aborted.provenance());
        assertThat(reused.outcome()).isEqualTo(LegalRequiredSetAggregateReceipt.Outcome.REUSED);
        assertThat(reused.aggregateId()).isEqualTo(created.aggregateId());
        assertThat(reused.requiredSetRevision()).isEqualTo(created.requiredSetRevision());
        assertThat(reused.provenance()).isEqualTo(created.provenance());
        assertThat(observer.queryForObject(
                "SELECT count(*) FROM legal_requisito_agregado_scopes WHERE agregado_id = ?",
                Long.class, created.aggregateId())).isEqualTo((long) scopes.contexts().size());
    }

    private static void auxiliaryLock(JdbcTemplate jdbc, long key) {
        jdbc.queryForList("SELECT pg_catalog.pg_advisory_xact_lock(?::bigint)", key);
    }

    private static void auxiliaryLock(Connection connection, long key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT pg_catalog.pg_advisory_xact_lock(?::bigint)")) {
            statement.setLong(1, key);
            statement.execute();
        }
    }

    private static boolean isBlockedBy(int blocked, int blocker) {
        return Boolean.TRUE.equals(observer.queryForObject(
                "SELECT ? = ANY(pg_catalog.pg_blocking_pids(?))", Boolean.class, blocker, blocked));
    }

    /** Owner-only editorial fixture; no materializer grants or migration objects are changed. */
    private static void promoteEquivalentPublication(
            UUID publicationId, CountDownLatch partial, CountDownLatch complete) {
        JdbcTemplate writer = new JdbcTemplate(exclusiveDataSource);
        new TransactionTemplate(new DataSourceTransactionManager(exclusiveDataSource))
                .executeWithoutResult(status -> {
                    writer.queryForList("""
                            SELECT pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(?, 0))
                            """, LegalManifestDatabaseGate.EDITORIAL_LOCK_NAME);
                    writer.update("DELETE FROM legal_documento_vigentes");
                    writer.update("""
                            INSERT INTO legal_documento_vigentes
                                (tipo, locale, contexto, documento_version_id,
                                 documento_linea_id, publicacion_id, estado_documento)
                            SELECT linea.tipo, linea.locale, contexto.contexto, version.id,
                                   version.documento_linea_id, publicacion.publicacion_id, 'VIGENTE'
                              FROM legal_publicacion_documentos publicacion
                              JOIN legal_documento_versiones version ON version.id = publicacion.documento_version_id
                              JOIN legal_documento_lineas linea ON linea.id = version.documento_linea_id
                              JOIN legal_documento_contextos contexto ON contexto.documento_version_id = version.id
                             WHERE publicacion.publicacion_id = ?
                             ORDER BY linea.tipo, linea.locale, contexto.contexto
                            """, publicationId);
                    replacePointers(writer, publicationId, true);
                    partial.countDown();
                    awaitLatch(complete, "completar todos los scopes editoriales");
                    replacePointers(writer, publicationId, false);
                    writer.execute("SET CONSTRAINTS ALL IMMEDIATE");
                });
    }

    private static void replacePointers(JdbcTemplate writer, UUID publicationId, boolean registration) {
        String predicate = registration ? "contexto = 'REGISTRO'" : "contexto <> 'REGISTRO'";
        writer.update("DELETE FROM legal_requisito_conjuntos_actuales WHERE " + predicate);
        writer.update("""
                INSERT INTO legal_requisito_conjuntos_actuales
                    (locale, contexto, audiencia, conjunto_id, publicacion_id, actualizado_en)
                SELECT locale, contexto, audiencia, id, publicacion_id, statement_timestamp()
                  FROM legal_requisito_conjuntos
                 WHERE publicacion_id = ? AND %s
                 ORDER BY locale, contexto, audiencia
                """.formatted(predicate), publicationId);
    }

    private static final String ACCEPTANCE_APPLICATION =
            "ordenfix-legal-aggregate-concurrency-acceptance-fixture";

    @Test
    void acceptanceStartedBeforeExclusiveWaitUsesPostLockTimestampInCompleteLot()
            throws Exception {
        LegalRequiredSetAggregateReceipt receipt = aggregate.gate().executeMutableShared(
                (status, boundary) -> aggregate.store().materialize(userScopes, boundary));
        AcceptanceActor actor = seedAcceptanceActor();
        UUID lotId = UUID.randomUUID();
        UUID acceptanceId = UUID.randomUUID();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<AcceptanceCommit> future = null;
        AtomicReference<AcceptanceWait> blocked = new AtomicReference<>();

        try (Connection exclusive = exclusiveDataSource.getConnection()) {
            exclusive.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            exclusive.setAutoCommit(false);
            int exclusivePid = currentBackendPid(exclusive);
            acquireEditorialLock(exclusive, false);

            future = executor.submit(() -> commitAcceptanceFixture(
                    receipt, actor, lotId, acceptanceId));
            awaitCondition("consumidor de aceptacion esperando el writer exclusivo", () -> {
                List<AcceptanceWait> waits = blockedAcceptanceFixture(exclusivePid);
                if (waits.size() != 1) {
                    return false;
                }
                blocked.set(waits.getFirst());
                return true;
            });
            assertThat(future.isDone()).isFalse();
            Instant releaseMarkedAt = statementTimestamp(exclusive);
            assertThat(blocked.get().transactionAt()).isBeforeOrEqualTo(releaseMarkedAt);
            exclusive.commit();

            AcceptanceCommit committed = future.get(
                    FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(committed.backendPid()).isEqualTo(blocked.get().backendPid());
            assertThat(committed.transactionAt()).isEqualTo(blocked.get().transactionAt());
            assertThat(committed.observedAt()).isAfterOrEqualTo(releaseMarkedAt);
            assertThat(committed.acceptedAt()).isAfterOrEqualTo(committed.observedAt());
            assertThat(committed.acceptedAt()).isAfter(committed.transactionAt());
            assertThat(committed.acceptedAt()).isAfterOrEqualTo(receipt.createdAt());
            assertThat(committed.acceptedAt()).isNotEqualTo(Instant.EPOCH);
            assertAcceptanceFixtureRows(lotId, acceptanceId, committed.documentCount(), true);
            assertThat(observer.queryForObject("""
                    SELECT aceptado_en FROM legal_aceptacion_lotes WHERE id = ?
                    """, OffsetDateTime.class, lotId).toInstant())
                    .isEqualTo(committed.acceptedAt());
            assertThat(observer.queryForObject("""
                    SELECT capturado_en FROM legal_aceptacion_metadatos WHERE lote_id = ?
                    """, OffsetDateTime.class, lotId).toInstant())
                    .isEqualTo(committed.acceptedAt());
        } finally {
            cancel(future);
            shutdown(executor);
        }
    }

    @Test
    void deferredMissingIpFailureRollsBackLotActDocumentsAndMetadata() throws Exception {
        LegalRequiredSetAggregateReceipt receipt = aggregate.gate().executeMutableShared(
                (status, boundary) -> aggregate.store().materialize(userScopes, boundary));
        AcceptanceActor actor = seedAcceptanceActor();
        UUID lotId = UUID.randomUUID();
        UUID acceptanceId = UUID.randomUUID();

        try (Connection connection = acceptanceFixtureDataSource().getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            acquireEditorialLock(connection, true);
            AcceptanceWrite written = writeAcceptanceFixture(
                    connection, receipt, actor, lotId, acceptanceId, false);
            assertThat(written.documentCount()).isPositive();
            // All INSERTs succeeded. The incomplete IP evidence fails only at commit.
            Throwable failure = catchThrowable(connection::commit);
            assertThat(failure).isInstanceOf(SQLException.class);
            assertThat(((SQLException) failure).getSQLState()).isEqualTo("23514");
            assertThat(failure.getMessage()).contains("metadata activa incompleta");
            connection.rollback();
        }

        assertAcceptanceFixtureRows(lotId, acceptanceId, 0, false);
        assertThat(observer.queryForObject("""
                SELECT count(*) FROM legal_requisito_agregados WHERE id = ?
                """, Long.class, receipt.aggregateId())).isEqualTo(1L);
    }

    private static DataSource acceptanceFixtureDataSource() {
        return LegalManifestPersistenceITSupport.directDataSource(
                POSTGRES, ACCEPTANCE_APPLICATION);
    }

    private static AcceptanceActor seedAcceptanceActor() {
        String identity = UUID.randomUUID().toString();
        Long workshopId = observer.queryForObject("""
                INSERT INTO talleres (nombre) VALUES (?) RETURNING id
                """, Long.class, "Taller fixture aceptacion " + identity);
        Long userId = observer.queryForObject("""
                INSERT INTO users (username, password, email, role, taller_id)
                VALUES (?, 'hash-acceptance-fixture', ?, 'USER', ?)
                RETURNING id
                """, Long.class,
                "acceptance-" + identity,
                identity + "@ordenfix.test",
                workshopId);
        return new AcceptanceActor(userId, workshopId);
    }

    private static AcceptanceCommit commitAcceptanceFixture(
            LegalRequiredSetAggregateReceipt receipt,
            AcceptanceActor actor,
            UUID lotId,
            UUID acceptanceId) throws SQLException {
        try (Connection connection = acceptanceFixtureDataSource().getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            try {
                JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(
                        connection, true));
                jdbc.execute("SET LOCAL statement_timeout TO '10s'");
                jdbc.execute("SET LOCAL lock_timeout TO '5s'");
                int backendPid = currentBackendPid(connection);
                Instant transactionAt = jdbc.queryForObject(
                        "SELECT transaction_timestamp()", OffsetDateTime.class).toInstant();
                assertThat(jdbc.queryForObject(
                        "SELECT current_setting('transaction_isolation')", String.class))
                        .isEqualTo("read committed");
                acquireEditorialLock(connection, true);
                // A separate statement after the lock supplies the causal observation.
                Instant observedAt = statementTimestamp(connection);
                AcceptanceWrite written = writeAcceptanceFixture(
                        connection, receipt, actor, lotId, acceptanceId, true);
                connection.commit();
                return new AcceptanceCommit(
                        backendPid, transactionAt, observedAt,
                        written.acceptedAt(), written.documentCount());
            } catch (RuntimeException | SQLException failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            }
        }
    }

    private static AcceptanceWrite writeAcceptanceFixture(
            Connection connection,
            LegalRequiredSetAggregateReceipt receipt,
            AcceptanceActor actor,
            UUID lotId,
            UUID acceptanceId,
            boolean includeIp) {
        JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        List<UUID> requirementIds = jdbc.queryForList("""
                SELECT member.requisito_version_id
                  FROM legal_requisito_agregado_scopes scope
                  JOIN legal_requisito_conjunto_miembros member
                    ON member.conjunto_id = scope.conjunto_id
                 WHERE scope.agregado_id = ?
                   AND scope.contexto = 'USO_CONTINUADO'
                   AND scope.audiencia = 'USER'
                 ORDER BY member.requisito_version_id
                """, UUID.class, receipt.aggregateId());
        assertThat(requirementIds).hasSize(1);
        UUID requirementId = requirementIds.getFirst();
        OffsetDateTime acceptedAt = jdbc.queryForObject("""
                INSERT INTO legal_aceptacion_lotes
                    (id, user_id, taller_id, rol_wire, audiencia,
                     required_set_revision, aceptado_en, revision_scheme,
                     perfil, agregado_id)
                VALUES (?, ?, ?, 'USER', 'USER', ?, ?, 'AGGREGATE_V1',
                        'AUTHENTICATED_PENDING', ?)
                RETURNING aceptado_en
                """, OffsetDateTime.class,
                lotId, actor.userId(), actor.workshopId(), receipt.requiredSetRevision(),
                OffsetDateTime.ofInstant(Instant.EPOCH, java.time.ZoneOffset.UTC),
                receipt.aggregateId());
        assertThat(jdbc.update("""
                INSERT INTO legal_aceptaciones
                    (id, lote_id, user_id, taller_id, requisito_version_id,
                     requisito_clave, requisito_version, contexto, tipo_acto,
                     afirmacion, afirmacion_sha256, requerido)
                SELECT ?, ?, ?, ?, version.id, line.clave, version.version,
                       line.contexto, line.tipo_acto, version.afirmacion,
                       version.afirmacion_sha256, version.requerido
                  FROM legal_requisito_versiones version
                  JOIN legal_requisito_lineas line ON line.id = version.requisito_linea_id
                 WHERE version.id = ?
                """, acceptanceId, lotId, actor.userId(), actor.workshopId(), requirementId))
                .isEqualTo(1);
        int documentCount = jdbc.update("""
                INSERT INTO legal_aceptacion_documentos
                    (aceptacion_id, documento_ordinal, documento_version_id,
                     documento_clave, tipo, version, titulo, sha256)
                SELECT ?, document.documento_ordinal, version.id, line.clave, line.tipo,
                       version.version, version.titulo, version.sha256
                  FROM legal_requisito_documentos document
                  JOIN legal_documento_versiones version
                    ON version.id = document.documento_version_id
                  JOIN legal_documento_lineas line ON line.id = version.documento_linea_id
                 WHERE document.requisito_version_id = ?
                """, acceptanceId, requirementId);
        assertThat(documentCount).isPositive();
        assertThat(jdbc.update("""
                INSERT INTO legal_aceptacion_metadatos (lote_id, capturado_en, retener_hasta)
                VALUES (?, statement_timestamp(), statement_timestamp() + INTERVAL '30 days')
                """, lotId)).isEqualTo(1);
        if (includeIp) {
            // Opaque test bytes satisfy database shape only; this is not an encryption service.
            byte[] nonce = new byte[12];
            new java.security.SecureRandom().nextBytes(nonce);
            assertThat(jdbc.update("""
                    INSERT INTO legal_aceptacion_metadatos_cifrados
                        (lote_id, tipo, key_version, nonce, ciphertext, tag, longitud_original)
                    VALUES (?, 'IP', 1, ?, ?, ?, 9)
                    """, lotId, nonce, new byte[9], new byte[16])).isEqualTo(1);
        }
        return new AcceptanceWrite(acceptedAt.toInstant(), documentCount);
    }

    private static List<AcceptanceWait> blockedAcceptanceFixture(int blockerPid) {
        return observer.query("""
                SELECT activity.pid, activity.xact_start
                  FROM pg_catalog.pg_stat_activity activity
                 WHERE activity.datname = pg_catalog.current_database()
                   AND activity.application_name = ?
                   AND activity.state = 'active'
                   AND activity.wait_event_type = 'Lock'
                   AND activity.query LIKE '%pg_advisory_xact_lock_shared%'
                   AND ? = ANY(pg_catalog.pg_blocking_pids(activity.pid))
                """, (resultSet, rowNumber) -> new AcceptanceWait(
                        resultSet.getInt("pid"),
                        resultSet.getObject("xact_start", OffsetDateTime.class).toInstant()),
                ACCEPTANCE_APPLICATION, blockerPid);
    }

    private static void assertAcceptanceFixtureRows(
            UUID lotId, UUID acceptanceId, int expectedDocuments, boolean committed) {
        long expected = committed ? 1L : 0L;
        assertThat(observer.queryForObject("""
                SELECT count(*) FROM legal_aceptacion_lotes WHERE id = ?
                """, Long.class, lotId)).isEqualTo(expected);
        assertThat(observer.queryForObject("""
                SELECT count(*) FROM legal_aceptaciones WHERE lote_id = ?
                """, Long.class, lotId)).isEqualTo(expected);
        assertThat(observer.queryForObject("""
                SELECT count(*) FROM legal_aceptacion_documentos WHERE aceptacion_id = ?
                """, Long.class, acceptanceId)).isEqualTo((long) expectedDocuments);
        assertThat(observer.queryForObject("""
                SELECT count(*) FROM legal_aceptacion_metadatos WHERE lote_id = ?
                """, Long.class, lotId)).isEqualTo(expected);
        assertThat(observer.queryForObject("""
                SELECT count(*) FROM legal_aceptacion_metadatos_cifrados WHERE lote_id = ?
                """, Long.class, lotId)).isEqualTo(expected);
    }

    private record AcceptanceActor(long userId, long workshopId) { }

    private record AcceptanceWait(int backendPid, Instant transactionAt) { }

    private record AcceptanceWrite(Instant acceptedAt, int documentCount) { }

    private record AcceptanceCommit(
            int backendPid,
            Instant transactionAt,
            Instant observedAt,
            Instant acceptedAt,
            int documentCount) { }

    private static LegalRequiredSetAggregateReceipt materializeAfterCheckpoint(
            LegalApplicableScopeSet scopes,
            CountDownLatch entered,
            CountDownLatch release) {
        return aggregate.gate().executeMutableShared((status, boundary) -> {
            entered.countDown();
            awaitLatch(release, "liberación de carrera agregada");
            return aggregate.store().materialize(scopes, boundary);
        });
    }

    private static Void holdShared(CountDownLatch entered, CountDownLatch release) {
        return aggregate.gate().executeMutableShared((status, boundary) -> {
            entered.countDown();
            awaitLatch(release, "liberación de lock compartido");
            return null;
        });
    }

    private static int acquireExclusiveAndCommit(CountDownLatch started) throws SQLException {
        try (Connection connection = exclusiveDataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            int backendPid = currentBackendPid(connection);
            started.countDown();
            try {
                acquireEditorialLock(connection, false);
                connection.commit();
                return backendPid;
            } catch (RuntimeException | SQLException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private static void acquireEditorialLock(Connection connection, boolean shared)
            throws SQLException {
        String function = shared
                ? "pg_catalog.pg_advisory_xact_lock_shared"
                : "pg_catalog.pg_advisory_xact_lock";
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + function + "(pg_catalog.hashtextextended(?, 0))")) {
            statement.setString(1, LegalManifestDatabaseGate.EDITORIAL_LOCK_NAME);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.next()).isFalse();
            }
        }
    }

    private static int heldEditorialLocks(String applicationName, String mode) {
        Integer count = observer.queryForObject("""
                WITH expected AS (
                    SELECT pg_catalog.hashtextextended(?, 0) AS key
                )
                SELECT count(*)::integer
                  FROM pg_catalog.pg_locks held
                  JOIN pg_catalog.pg_stat_activity activity
                    ON activity.pid = held.pid
                  CROSS JOIN expected
                 WHERE activity.datname = pg_catalog.current_database()
                   AND activity.application_name = ?
                   AND held.locktype = 'advisory'
                   AND held.database = (
                       SELECT database.oid
                         FROM pg_catalog.pg_database database
                        WHERE database.datname = pg_catalog.current_database()
                   )
                   AND held.granted
                   AND held.objsubid = 1
                   AND held.mode = ?
                   AND held.classid::bigint = (
                       (expected.key >> 32) & 4294967295::bigint
                   )
                   AND held.objid::bigint = (
                       expected.key & 4294967295::bigint
                   )
                """, Integer.class,
                LegalManifestDatabaseGate.EDITORIAL_LOCK_NAME,
                applicationName,
                mode);
        return count == null ? -1 : count;
    }

    private static Optional<BlockedTransaction> blockedMaterializer(int blockerPid) {
        return observer.query("""
                SELECT activity.pid, activity.xact_start
                  FROM pg_catalog.pg_stat_activity activity
                 WHERE activity.datname = pg_catalog.current_database()
                   AND activity.application_name = ?
                   AND activity.state = 'active'
                   AND activity.wait_event_type = 'Lock'
                   AND activity.query LIKE '%pg_advisory_xact_lock_shared%'
                   AND ? = ANY(pg_catalog.pg_blocking_pids(activity.pid))
                 ORDER BY activity.pid
                 LIMIT 1
                """, (resultSet, rowNumber) -> new BlockedTransaction(
                resultSet.getInt("pid"),
                resultSet.getObject("xact_start", OffsetDateTime.class).toInstant()),
                MATERIALIZER_APPLICATION,
                blockerPid).stream().findFirst();
    }

    private static int blockedHeaderInserts() {
        Integer count = observer.queryForObject("""
                SELECT count(*)::integer
                  FROM pg_catalog.pg_stat_activity activity
                 WHERE activity.datname = pg_catalog.current_database()
                   AND activity.application_name = ?
                   AND activity.state = 'active'
                   AND activity.wait_event_type = 'Lock'
                   AND activity.query LIKE '%INSERT INTO legal_requisito_agregados%'
                """, Integer.class, MATERIALIZER_APPLICATION);
        return count == null ? -1 : count;
    }

    private static int currentBackendPid(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT pg_catalog.pg_backend_pid()")) {
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                int pid = result.getInt(1);
                assertThat(result.next()).isFalse();
                return pid;
            }
        }
    }

    private static Instant statementTimestamp(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT statement_timestamp()")) {
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                Instant timestamp = result.getObject(1, OffsetDateTime.class).toInstant();
                assertThat(result.next()).isFalse();
                return timestamp;
            }
        }
    }

    private static void cancel(Future<?> future) {
        if (future != null && !future.isDone()) {
            future.cancel(true);
        }
    }

    private static void shutdown(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    private record BlockedTransaction(int pid, Instant startedAt) { }
}
