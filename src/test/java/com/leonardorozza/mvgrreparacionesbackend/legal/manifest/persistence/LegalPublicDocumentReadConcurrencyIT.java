package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

/** PostgreSQL locks, real REPLACE, pool saturation and cancellation with the isolated reader. */
class LegalPublicDocumentReadConcurrencyIT {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_public_document_concurrency")
            .withUsername("ordenfix").withPassword("ordenfix");
    private static final String ROLE = "ordenfix_public_document_concurrency_it";
    private static final String PASSWORD = "public-document-concurrency-test-only";
    private static JdbcTemplate owner;
    @TempDir
    Path directory;
    private LegalPublicDocumentReadITSupport.Seed seed;
    private Harness harness;

    @BeforeAll
    static void provision() {
        POSTGRES.start();
        LegalManifestPersistenceITSupport.migrateLatest(POSTGRES);
        owner = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        new LegalRestrictedPublicDocumentRoleFixture(owner, POSTGRES.getJdbcUrl(), ROLE, PASSWORD,
                POSTGRES.getDriverClassName()).provisionAndVerify();
    }

    @BeforeEach
    void seedAndCompose() throws Exception {
        LegalPublicDocumentReadITSupport.clearCatalog(owner);
        seed = LegalPublicDocumentReadITSupport.seedCatalog(owner, directory, "concurrency-initial");
        harness = harness(Duration.ofSeconds(15));
    }

    @AfterEach
    void closeHarness() {
        if (harness != null) {
            harness.close();
        }
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @Test
    void twoSharedCatalogsCoexistAndARealReplacementWaitsUntilTheyFinish() throws Exception {
        Supplier<LegalPublicDocumentReadITSupport.Seed> replacement =
                LegalPublicDocumentReadITSupport.prepareReplacement(
                        owner, directory, "concurrency-successor", seed);
        LegalPublicDocumentCatalog baseline = harness.service.catalog(null, LocaleLegal.ES_AR, 0, 100);
        CountDownLatch bothReading = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            bothReading.countDown();
            await(release);
            return invocation.callRealMethod();
        }).when(harness.reader).readCatalog(any(), any(), anyInt(), anyInt(), any());

        try (var workers = Executors.newFixedThreadPool(3)) {
            Future<LegalPublicDocumentCatalog> first = workers.submit(
                    () -> harness.service.catalog(null, LocaleLegal.ES_AR, 0, 100));
            Future<LegalPublicDocumentCatalog> second = workers.submit(
                    () -> harness.service.catalog(null, LocaleLegal.ES_AR, 0, 100));
            Future<LegalPublicDocumentReadITSupport.Seed> writer = null;
            try {
                await(bothReading);
                assertThat(lockCount("ShareLock", true)).isEqualTo(2);
                writer = workers.submit(replacement::get);
                awaitCondition(() -> lockCount("ExclusiveLock", false) == 1);
                assertThat(writer.isDone()).isFalse();
                release.countDown();
                assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(baseline);
                assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(baseline);
                LegalPublicDocumentReadITSupport.Seed replaced = writer.get(10, TimeUnit.SECONDS);
                LegalPublicDocumentCatalog after = harness.service.catalog(null, LocaleLegal.ES_AR, 0, 100);
                assertThat(after.documentSetRevision()).isNotEqualTo(baseline.documentSetRevision());
                assertThat(after.totalElements()).isEqualTo(replaced.documents().size());
                assertThat(after.documents()).hasSize((int) after.totalElements());
                assertThat(after.documents()).extracting(document -> document.versionId())
                        .containsExactlyElementsOf(replaced.documents().stream().map(document -> document.id()).toList());
                assertThat(lockCount("ShareLock", true)).isZero();
            } finally {
                release.countDown();
                first.cancel(true);
                second.cancel(true);
                if (writer != null) {
                    writer.cancel(true);
                }
            }
        }
    }

    @Test
    void anExclusiveWriterMakesTheReaderFailAtItsOneSecondLockBudget() throws Exception {
        try (Connection writer = owner.getDataSource().getConnection()) {
            writer.setAutoCommit(false);
            try (PreparedStatement lock = writer.prepareStatement("""
                    SELECT pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(?, 0))
                    """)) {
                lock.setString(1, LegalManifestDatabaseGate.EDITORIAL_LOCK_NAME);
                lock.execute();
            }
            long started = System.nanoTime();
            assertThatThrownBy(() -> harness.service.catalog(null, LocaleLegal.ES_AR, 0, 20))
                    .isInstanceOf(LegalPublicDocumentReadException.class)
                    .satisfies(failure -> assertThat(sqlState(failure)).isEqualTo("55P03"));
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertThat(elapsed).isBetween(900L, 4_000L);
            assertThat(lockCount("ShareLock", true)).isZero();
            writer.rollback();
        }
        assertThat(harness.service.catalog(null, LocaleLegal.ES_AR, 0, 20).totalElements())
                .isEqualTo(seed.documents().size());
    }

    @Test
    void saturatedPoolFailsWithinTheDedicatedAcquisitionBudget() throws Exception {
        try (Connection first = harness.pool.getConnection(); Connection second = harness.pool.getConnection()) {
            long started = System.nanoTime();
            assertThatThrownBy(() -> harness.service.catalog(null, LocaleLegal.ES_AR, 0, 20))
                    .isInstanceOf(LegalPublicDocumentReadException.class);
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertThat(elapsed).isBetween(900L, 3_000L);
            assertThat(lockCount("ShareLock", true)).isZero();
        }
        assertThat(harness.service.catalog(null, LocaleLegal.ES_AR, 0, 20).totalElements()).isPositive();
    }

    @Test
    void deadlineCancelsABlockedStatementAfterRealPreflightsAndReleasesItsLock() {
        try (Harness shortBudget = harness(Duration.ofSeconds(2))) {
            shortBudget.service.catalog(null, LocaleLegal.ES_AR, 0, 20);
            java.util.concurrent.atomic.AtomicBoolean entered = new java.util.concurrent.atomic.AtomicBoolean();
            long started = System.nanoTime();
            assertThatThrownBy(() -> shortBudget.dataSource.withinDeadline(deadline ->
                    shortBudget.gate.executeReadOnlyShared((status, boundary) -> {
                        entered.set(true);
                        shortBudget.jdbc.execute("SELECT pg_catalog.pg_sleep(10)");
                        return "must not escape";
                    }))).isInstanceOf(RuntimeException.class);
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertThat(entered.get()).isTrue();
            assertThat(elapsed).isBetween(1_500L, 6_000L);
            awaitCondition(() -> lockCount("ShareLock", true) == 0);
            assertThat(shortBudget.pool.getHikariPoolMXBean().getActiveConnections()).isZero();
            System.out.println("public-document blocked-statement elapsedMs=" + elapsed);
        }
        assertThat(harness.service.catalog(null, LocaleLegal.ES_AR, 0, 20).totalElements()).isPositive();
    }

    @Test
    void deadlineAlsoBoundsCursorFetchAfterTheFirstBatchAndClosesAllResources() {
        try (Harness shortBudget = harness(Duration.ofSeconds(2))) {
            shortBudget.service.catalog(null, LocaleLegal.ES_AR, 0, 20);
            java.util.concurrent.atomic.AtomicInteger visited = new java.util.concurrent.atomic.AtomicInteger();
            long started = System.nanoTime();
            assertThatThrownBy(() -> shortBudget.dataSource.withinDeadline(deadline ->
                    shortBudget.gate.executeReadOnlyShared((status, boundary) ->
                            shortBudget.jdbc.execute((ConnectionCallback<Integer>) connection -> {
                                try (PreparedStatement statement = connection.prepareStatement("""
                                        SELECT n, pg_catalog.pg_sleep(0.01)
                                          FROM pg_catalog.generate_series(1, 1000) AS n
                                        """, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                                    statement.setFetchSize(32);
                                    try (ResultSet rows = statement.executeQuery()) {
                                        while (rows.next()) {
                                            visited.incrementAndGet();
                                        }
                                        return visited.get();
                                    } finally {
                                        deadline.cancel(statement);
                                    }
                                }
                            })))).isInstanceOf(RuntimeException.class);
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertThat(visited.get()).isBetween(64, 999);
            assertThat(elapsed).isBetween(1_500L, 6_000L);
            awaitCondition(() -> lockCount("ShareLock", true) == 0);
            assertThat(shortBudget.pool.getHikariPoolMXBean().getActiveConnections()).isZero();
            System.out.println("public-document cursor-fetch elapsedMs=" + elapsed + " visited=" + visited.get());
        }
        assertThat(harness.service.catalog(null, LocaleLegal.ES_AR, 0, 20).totalElements()).isPositive();
    }

    private static Harness harness(Duration budget) {
        LegalPublicDocumentReadDatabaseConfiguration config = new LegalPublicDocumentReadDatabaseConfiguration();
        String prefix = LegalPublicDocumentReadDatabaseConfiguration.PROPERTY_PREFIX;
        MockEnvironment environment = new MockEnvironment()
                .withProperty(prefix + "jdbc-url", POSTGRES.getJdbcUrl())
                .withProperty(prefix + "username", ROLE)
                .withProperty(prefix + "password", PASSWORD);
        HikariDataSource pool = config.legalPublicDocumentPool(environment);
        LegalPublicDocumentDataSource dataSource = new LegalPublicDocumentDataSource(pool, budget);
        JdbcTemplate jdbc = config.legalPublicDocumentJdbc(dataSource);
        var budgets = config.legalPublicDocumentBudgets();
        var manager = config.legalPublicDocumentTransactionManager(dataSource);
        var transaction = config.legalPublicDocumentTransactionTemplate(manager, budgets);
        var schema = config.legalPublicDocumentSchemaVerifier(jdbc);
        var privileges = config.legalPublicDocumentPrivilegeVerifier(jdbc, environment);
        var gate = config.legalPublicDocumentGate(transaction, jdbc, budgets, schema, privileges);
        var reader = spy(config.legalPublicDocumentReader(jdbc));
        var service = new LegalPublicDocumentReadService(jdbc, dataSource, gate, reader, schema, privileges);
        return new Harness(pool, dataSource, jdbc, gate, reader, service);
    }

    private static String sqlState(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    private static long lockCount(String mode, boolean granted) {
        return owner.queryForObject("""
                SELECT count(*) FROM pg_catalog.pg_locks
                 WHERE locktype = 'advisory' AND mode = ? AND granted = ?
                """, Long.class, mode, granted);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private static void awaitCondition(BooleanSupplier condition) {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= until) {
                throw new AssertionError("PostgreSQL no confirmó la condición esperada");
            }
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
    }

    private record Harness(HikariDataSource pool, LegalPublicDocumentDataSource dataSource,
                           JdbcTemplate jdbc, LegalManifestDatabaseGate gate,
                           LegalPublicDocumentReader reader, LegalPublicDocumentReadService service)
            implements AutoCloseable {
        @Override
        public void close() {
            dataSource.close();
            pool.close();
        }
    }
}
