package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalApplicableScopeResolver;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalApplicableScopeSet;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PerfilAgregadoLegal;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
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
import java.util.concurrent.atomic.AtomicReference;

import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.awaitCondition;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.awaitLatch;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.awaitValue;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.blockedEditorialGateWaits;
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
        ValidatedRelease release = LegalV28AggregateITSupport.releaseWithContinuedUse(
                temporaryDirectory,
                LegalRequiredSetAggregateConcurrencyIT.class,
                "aggregate-concurrency-current");
        UUID publicationId = LegalV28AggregateITSupport.importRelease(
                ownerDataSource,
                release);
        LegalManifestPersistenceITSupport.promoteToReady(owner, publicationId);

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
