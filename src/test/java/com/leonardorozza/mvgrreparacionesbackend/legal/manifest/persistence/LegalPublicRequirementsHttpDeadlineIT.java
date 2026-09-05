package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalPublicRegistrationRequirements;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalPublicRequirementsValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicRequirementsHttpITSupport.HttpHarness;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real HTTP and restricted PostgreSQL. Lock/pool/FETCH cases measure actual waiting; phase cases
 * use the existing monotonic-clock seam and run the real calculation/commit/close before injection.
 * The test-only three-second FETCH budget does not change the production fifteen-second policy.
 */
class LegalPublicRequirementsHttpDeadlineIT {

    private static final String ROOT = "/api/public/requisitos-legales";
    private static final String ROLE = "ordenfix_legal_public_requirements_deadline_it";
    private static final String PASSWORD = "requirements-http-deadline-test-only";
    private static final String FETCH_MARKER = "fixture_deadline_fetch";
    private static final Duration BUDGET = Duration.ofSeconds(15);
    private static final Duration FETCH_BUDGET = Duration.ofSeconds(3);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_public_requirements_http_deadline")
            .withUsername("ordenfix").withPassword("ordenfix");
    private static JdbcTemplate owner;

    @TempDir
    private Path directory;
    private final AtomicLong clock = new AtomicLong();
    private Attempt attempt = new Attempt(Fault.NONE);
    private LegalRequiredSetProjection projection;
    private LegalPublicRegistrationRequirements expected;
    private DatabaseHarness database;
    private HttpHarness http;

    @BeforeAll
    static void migrateAndProvisionOnlyTheEphemeralConsumer() {
        POSTGRES.start();
        LegalManifestPersistenceITSupport.migrateLatest(POSTGRES);
        owner = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        new LegalRestrictedPublicRequirementsRoleFixture(owner, POSTGRES.getJdbcUrl(), ROLE, PASSWORD,
                POSTGRES.getDriverClassName()).provisionAndVerify();
    }

    @BeforeEach
    void seedARealPublicationWithAnOptionalMember() throws Exception {
        LegalPublicRequirementsReadITSupport.clearCatalog(owner);
        projection = LegalPublicRequirementsReadITSupport.seedRegistrationWithOptional(
                owner, directory, "requirements-http-deadline-" + UUID.randomUUID()).projection();
        expected = new LegalPublicRequirementsValidator().validate(projection);
    }

    @AfterEach
    void releaseTheHttpOwnerAndTheCallerOwnedDatabaseGraph() {
        try {
            if (http != null) {
                http.close();
                assertThat(http.context().isActive()).isFalse();
            }
        } finally {
            if (database != null) {
                database.close();
                assertThat(database.pool().isClosed()).isTrue();
            }
        }
    }

    @AfterAll
    static void stopPostgres() {
        POSTGRES.stop();
    }

    @Test
    void anExclusiveEditorialLockReturns503DespiteAKnownTagAndRecoversWithoutDml() throws Exception {
        open(BUDGET, System::nanoTime);
        String previous = successfulRead().getHeader("ETag");
        UUID aggregate = attempt.receipts.getFirst().aggregateId();
        reset(Fault.NONE);
        try (Connection writer = owner.getDataSource().getConnection()) {
            writer.setAutoCommit(false);
            try {
                try (PreparedStatement statement = writer.prepareStatement("""
                        SELECT pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(?, 0))
                        """)) {
                    statement.setString(1, LegalManifestDatabaseGate.EDITORIAL_LOCK_NAME);
                    statement.execute();
                }
                long started = System.nanoTime();
                MvcResult failed = unavailable(previous);
                long elapsed = elapsedMillis(started);
                assertThat(sqlState(failed.getResolvedException())).isEqualTo("55P03");
                assertThat(elapsed).isBetween(900L, 5_000L);
                assertThat(attempt.receipts).isEmpty();
                assertThat(database.metrics().snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isZero();
                assertCompletion(TransactionSynchronization.STATUS_ROLLED_BACK, 0, 1);
                assertCounts(1);
                System.out.println("public-requirements HTTP editorial-lock elapsedMs=" + elapsed);
            } finally {
                writer.rollback();
            }
        }
        recover(aggregate, LegalRequiredSetAggregateReceipt.Outcome.REUSED, previous);
    }

    @Test
    void aSaturatedPoolReturns503WithoutBeginningATransactionAndRecoversAfterRelease() throws Exception {
        open(BUDGET, System::nanoTime);
        String previous = successfulRead().getHeader("ETag");
        UUID aggregate = attempt.receipts.getFirst().aggregateId();
        reset(Fault.NONE);
        try (Connection first = database.pool().getConnection(); Connection second = database.pool().getConnection()) {
            assertThat(database.pool().getHikariPoolMXBean().getActiveConnections()).isEqualTo(2);
            long started = System.nanoTime();
            unavailable(previous);
            long elapsed = elapsedMillis(started);
            assertThat(elapsed).isBetween(900L, 4_000L);
            assertThat(attempt.borrowAttempts).isEqualTo(1);
            assertThat(attempt.leases).isZero();
            assertThat(attempt.completions).isEmpty();
            assertThat(attempt.receipts).isEmpty();
            assertThat(database.metrics().snapshot().commits()).isZero();
            assertThat(database.metrics().snapshot().rollbacks()).isZero();
            assertThat(database.metrics().snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isZero();
            assertThat(database.metrics().snapshot().executions(LegalJdbcMetricsSupport.Category.ADVISORY_LOCK)).isZero();
            assertThat(database.pool().getHikariPoolMXBean().getActiveConnections()).isEqualTo(2);
            assertCounts(1);
            assertThreadReleased();
            System.out.println("public-requirements HTTP pool-borrow elapsedMs=" + elapsed);
        }
        assertPoolIdle();
        recover(aggregate, LegalRequiredSetAggregateReceipt.Outcome.REUSED, previous);
    }

    @Test
    void realMetadataFetchExpiresAfterTwoBatchesAndNeverReturnsOrConfirmsAPartialContract() throws Exception {
        LegalPublicRequirementsReadITSupport.clearCatalog(owner);
        projection = LegalPublicRequirementsCapacityITSupport.seedRegistration(owner, directory,
                "requirements-http-fetch", 65).projection();
        expected = new LegalPublicRequirementsValidator().validate(projection);
        open(FETCH_BUDGET, System::nanoTime);
        reset(Fault.LAST_METADATA_FETCH);

        long started = System.nanoTime();
        MvcResult failed = unavailable(expectedEtag());
        long elapsed = elapsedMillis(started);

        assertThat(attempt.phaseTriggered).isTrue();
        assertThat(attempt.receipts).singleElement().satisfies(receipt ->
                assertThat(receipt.outcome()).isEqualTo(LegalRequiredSetAggregateReceipt.Outcome.CREATED));
        assertThat(database.metrics().snapshot().executionsContaining(FETCH_MARKER)).isEqualTo(1);
        assertThat(database.metrics().snapshot().rowsReadContaining(FETCH_MARKER)).isEqualTo(64);
        assertThat(attempt.slowStatementClosed).isTrue();
        assertThat(attempt.slowCursorClosed).isTrue();
        assertThat(attempt.cancellations.get()).isPositive();
        assertThat(attempt.fetchNetworkTimeouts).isNotEmpty().allSatisfy(timeout ->
                assertThat(timeout).isBetween(1, Math.toIntExact(FETCH_BUDGET.toMillis())));
        assertThat(Collections.min(attempt.fetchNetworkTimeouts))
                .isLessThan(Collections.max(attempt.fetchNetworkTimeouts));
        assertThat(attempt.borrowAttempts).isEqualTo(1);
        assertThat(attempt.leases).isEqualTo(1);
        assertThat(attempt.closes).isEqualTo(1);
        assertThat(attempt.completions).singleElement().isIn(
                TransactionSynchronization.STATUS_ROLLED_BACK, TransactionSynchronization.STATUS_UNKNOWN);
        // A socket abort can make JDBC rollback indeterminate; the independent observer proves no durable rows.
        assertThat(attempt.commitCalls).isZero();
        assertThat(attempt.rollbackCalls).isEqualTo(1);
        assertThat(database.metrics().snapshot().commits()).isZero();
        assertThat(database.metrics().snapshot().rollbacks()).isIn(0L, 1L);
        assertThat(database.metrics().snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isEqualTo(2);
        assertPoolIdle();
        assertCounts(0);
        assertThreadReleased();
        String state = sqlState(failed.getResolvedException());
        if (state != null) {
            assertThat(state).isIn("57014", "08006", "08003", "25P02");
        }
        assertThat(elapsed).isBetween(2_000L, 8_000L);
        System.out.println("public-requirements HTTP metadata-fetch elapsedMs=" + elapsed
                + " rows=64 completion=" + attempt.completions + " sqlState=" + state);
        recover(null, LegalRequiredSetAggregateReceipt.Outcome.CREATED, null);
    }

    @ParameterizedTest
    @EnumSource(value = Fault.class, names = {"AFTER_CALCULATION", "BEFORE_COMMIT", "AFTER_COMMIT",
            "DURING_CLOSE", "CLOSE_FAILURE", "COMMIT_ACK_LOST"})
    void phaseFailuresNeverPublishSuccessAndRecoveryRespectsTheActualCommitOutcome(Fault phase) throws Exception {
        open(BUDGET, clock::get);
        reset(phase);
        MvcResult failed = unavailable(expectedEtag());

        assertThat(attempt.phaseTriggered).isTrue();
        assertThat(attempt.calculated).isNotNull();
        assertThat(attempt.calculated.requiredSetRevision()).isEqualTo(expected.requiredSetRevision());
        assertThat(attempt.receipts).singleElement().satisfies(receipt ->
                assertThat(receipt.outcome()).isEqualTo(LegalRequiredSetAggregateReceipt.Outcome.CREATED));
        assertThat(database.metrics().snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isEqualTo(2);
        boolean beforeCommit = phase == Fault.AFTER_CALCULATION || phase == Fault.BEFORE_COMMIT;
        int completion = beforeCommit ? TransactionSynchronization.STATUS_ROLLED_BACK
                : phase == Fault.COMMIT_ACK_LOST ? TransactionSynchronization.STATUS_UNKNOWN
                : TransactionSynchronization.STATUS_COMMITTED;
        assertCompletion(completion, beforeCommit ? 0 : 1, beforeCommit ? 1 : 0);
        assertCounts(beforeCommit ? 0 : 1);
        if (phase == Fault.CLOSE_FAILURE || phase == Fault.COMMIT_ACK_LOST) {
            assertThat(hasCause(failed.getResolvedException(), attempt.failure)).isTrue();
        } else {
            assertThat(clock.get()).isEqualTo(BUDGET.toNanos());
        }
        UUID aggregate = beforeCommit ? null : attempt.receipts.getFirst().aggregateId();
        recover(aggregate, beforeCommit ? LegalRequiredSetAggregateReceipt.Outcome.CREATED
                : LegalRequiredSetAggregateReceipt.Outcome.REUSED, null);
    }

    private void open(Duration budget, LongSupplier monotonicClock) {
        var config = new LegalPublicRequirementsDatabaseConfiguration();
        String prefix = LegalPublicRequirementsDatabaseConfiguration.PROPERTY_PREFIX;
        MockEnvironment environment = new MockEnvironment().withProperty(prefix + "jdbc-url", POSTGRES.getJdbcUrl())
                .withProperty(prefix + "username", ROLE).withProperty(prefix + "password", PASSWORD);
        HikariDataSource pool = config.legalPublicRequirementsPool(environment);
        LegalPublicRequirementsDataSource bounded = null;
        try {
            LegalJdbcMetricsSupport metrics = LegalJdbcMetricsSupport.instrument(pool, Duration.ZERO);
            var observedPool = new AbstractDataSource() {
                @Override
                public Connection getConnection() throws SQLException {
                    Attempt observed = attempt;
                    observed.borrowAttempts++;
                    return instrumentConnection(metrics.dataSource().getConnection(), observed);
                }
                @Override
                public Connection getConnection(String username, String password) throws SQLException {
                    throw new SQLException("Test consumer credentials are fixed");
                }
            };
            bounded = new LegalPublicRequirementsDataSource(observedPool, budget, monotonicClock);
            JdbcTemplate jdbc = config.legalPublicRequirementsJdbc(bounded);
            var budgets = config.legalPublicRequirementsBudgets();
            var manager = config.legalPublicRequirementsTransactionManager(bounded);
            var template = config.legalPublicRequirementsTransactionTemplate(manager, budgets);
            var schema = config.legalPublicRequirementsSchemaVerifier(jdbc);
            var privileges = config.legalPublicRequirementsPrivilegeVerifier(jdbc, environment);
            var guard = config.legalPublicRequirementsBoundaryGuard(List.of(config.legalPublicRequirementsBoundaryMarker()));
            var gate = config.legalPublicRequirementsGate(template, jdbc, budgets, schema, privileges, guard);
            var revisions = config.legalPublicRequirementsRevisionCalculator();
            var provenance = config.legalPublicRequirementsProvenanceCalculator();
            var replay = config.legalPublicRequirementsReplayVerifier(jdbc, revisions, provenance);
            var store = spy(config.legalPublicRequirementsStore(jdbc, revisions, provenance, replay));
            doAnswer(invocation -> {
                LegalRequiredSetAggregateReceipt receipt = (LegalRequiredSetAggregateReceipt) invocation.callRealMethod();
                attempt.receipts.add(receipt);
                return receipt;
            }).when(store).materialize(any(), any());
            var reader = config.legalPublicRequirementsReader(jdbc);
            var validator = spy(new LegalPublicRequirementsValidator());
            doAnswer(invocation -> {
                LegalPublicRegistrationRequirements calculated =
                        (LegalPublicRegistrationRequirements) invocation.callRealMethod();
                attempt.calculated = calculated;
                if (attempt.fault == Fault.AFTER_CALCULATION) {
                    attempt.phaseTriggered = true;
                    clock.set(BUDGET.toNanos());
                }
                return calculated;
            }).when(validator).validate(any());
            ReflectionTestUtils.setField(reader, "validator", validator);
            var service = config.legalPublicRequirementsReadService(jdbc, bounded, gate,
                    config.legalPublicRequirementsScopeResolver(), store, reader, schema, privileges, guard);
            database = new DatabaseHarness(pool, bounded, metrics, service);
            http = LegalPublicRequirementsHttpITSupport.openHttp(service);
            reset(Fault.NONE);
        } catch (RuntimeException | Error failure) {
            if (bounded != null) {
                bounded.close();
            }
            pool.close();
            throw failure;
        }
    }

    private void reset(Fault fault) {
        clock.set(0);
        attempt = new Attempt(fault);
        database.metrics().reset();
    }

    private MvcResult unavailable(String etag) throws Exception {
        MvcResult result = http.mvc().perform(get(ROOT).param("contexto", "REGISTRO").param("locale", "es-AR")
                        .header("If-None-Match", etag)).andExpect(status().isServiceUnavailable()).andReturn();
        MockHttpServletResponse response = result.getResponse();
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("ETag")).isNull();
        assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("60");
        assertThat(response.getHeader("X-RateLimit-Remaining")).isNotNull();
        JsonNode body = JSON.readTree(response.getContentAsByteArray());
        assertThat(body.path("code").asText()).isEqualTo("CONTRATO_LEGAL_NO_DISPONIBLE");
        assertThat(body.path("status").asInt()).isEqualTo(503);
        assertThat(body.path("details").path("contexto").asText()).isEqualTo("REGISTRO");
        assertThat(body.path("details").path("locale").asText()).isEqualTo("es-AR");
        assertThat(body.has("requiredSetRevision")).isFalse();
        assertThat(body.has("requisitos")).isFalse();
        assertThat(response.getContentAsString()).doesNotContain(ROLE, PASSWORD, "pg_sleep", "SELECT", "Hikari",
                "contenidoMarkdown", "simulated");
        return result;
    }

    private MockHttpServletResponse successfulRead() throws Exception {
        MockHttpServletResponse response = http.mvc().perform(get(ROOT)
                        .param("contexto", "REGISTRO").param("locale", "es-AR"))
                .andExpect(status().isOk()).andReturn().getResponse();
        JsonNode body = JSON.readTree(response.getContentAsByteArray());
        assertThat(body.path("requiredSetRevision").asText()).isEqualTo(expected.requiredSetRevision());
        assertThat(body.path("requisitos").size()).isEqualTo(projection.requirements().size());
        assertThat(response.getHeader("ETag")).isEqualTo(expectedEtag());
        assertThat(response.getHeader("Cache-Control")).isEqualTo("public, max-age=0, must-revalidate");
        return response;
    }

    private void recover(UUID aggregate, LegalRequiredSetAggregateReceipt.Outcome expectedOutcome, String previous)
            throws Exception {
        reset(Fault.NONE);
        if (previous == null) {
            successfulRead();
        } else {
            MockHttpServletResponse response = http.mvc().perform(get(ROOT)
                            .param("contexto", "REGISTRO").param("locale", "es-AR").header("If-None-Match", previous))
                    .andExpect(status().isNotModified()).andReturn().getResponse();
            assertThat(response.getHeader("ETag")).isEqualTo(previous);
            assertThat(response.getContentAsByteArray()).isEmpty();
        }
        assertThat(attempt.receipts).singleElement().satisfies(receipt -> {
            assertThat(receipt.outcome()).isEqualTo(expectedOutcome);
            if (aggregate != null) {
                assertThat(receipt.aggregateId()).isEqualTo(aggregate);
            }
        });
        assertThat(database.metrics().snapshot().executions(LegalJdbcMetricsSupport.Category.DML))
                .isEqualTo(expectedOutcome == LegalRequiredSetAggregateReceipt.Outcome.REUSED ? 0 : 2);
        assertCompletion(TransactionSynchronization.STATUS_COMMITTED, 1, 0);
        assertCounts(1);
    }

    private String expectedEtag() { return "W/\"" + expected.requiredSetRevision() + "\""; }

    private void assertCompletion(int completion, int commits, int rollbacks) {
        assertThat(attempt.completions).containsExactly(completion);
        assertThat(attempt.borrowAttempts).isEqualTo(1);
        assertThat(attempt.leases).isEqualTo(1);
        assertThat(attempt.commitCalls).isEqualTo(commits);
        assertThat(attempt.rollbackCalls).isEqualTo(rollbacks);
        assertThat(database.metrics().snapshot().commits()).isEqualTo(commits);
        assertThat(database.metrics().snapshot().rollbacks()).isEqualTo(rollbacks);
        assertThat(attempt.closes).isEqualTo(1);
        assertPoolIdle();
        assertThreadReleased();
    }

    private void assertPoolIdle() {
        assertThat(database.pool().getHikariPoolMXBean().getActiveConnections()).isZero();
        // An aborted lease can be returned before server cleanup finishes. Observe eventual release
        // independently, while allowing the pool's ordinary idle sessions; UNKNOWN remains UNKNOWN.
        long until = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        long remaining;
        do {
            remaining = owner.queryForObject("""
                    SELECT (SELECT count(*) FROM pg_catalog.pg_stat_activity activity
                             WHERE activity.usename = ? AND activity.datname = current_database()
                               AND (activity.xact_start IS NOT NULL OR activity.state <> 'idle'))
                         + (SELECT count(*) FROM pg_catalog.pg_locks locks
                              JOIN pg_catalog.pg_stat_activity activity ON activity.pid = locks.pid
                             WHERE activity.usename = ? AND activity.datname = current_database()
                               AND locks.locktype = 'advisory')
                    """, Long.class, ROLE, ROLE);
            if (remaining == 0 || Thread.currentThread().isInterrupted()) {
                break;
            }
            java.util.concurrent.locks.LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
        } while (System.nanoTime() < until);
        assertThat(remaining).as("Server transactions/advisory locks released within the test observation window")
                .isZero();
    }

    private static void assertThreadReleased() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
        assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    private static void assertCounts(long count) {
        assertThat(LegalPublicRequirementsReadITSupport.aggregateCounts(owner)).containsExactlyInAnyOrderEntriesOf(
                Map.of("legal_requisito_agregados", count, "legal_requisito_agregado_scopes", count));
    }

    private Connection instrumentConnection(Connection delegate, Attempt observed) {
        observed.leases++;
        AtomicBoolean registered = new AtomicBoolean();
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return objectMethod(proxy, method, arguments);
                    }
                    if (TransactionSynchronizationManager.isSynchronizationActive() && registered.compareAndSet(false, true)) {
                        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                            @Override
                            public void beforeCompletion() {
                                if (observed.fault == Fault.BEFORE_COMMIT) {
                                    observed.phaseTriggered = true;
                                    clock.set(BUDGET.toNanos());
                                }
                            }
                            @Override public void afterCompletion(int state) { observed.completions.add(state); }
                        });
                    }
                    String name = method.getName();
                    if (name.equals("commit")) {
                        observed.commitCalls++;
                        Object committed = invoke(delegate, method, arguments);
                        if (observed.fault == Fault.AFTER_COMMIT || observed.fault == Fault.COMMIT_ACK_LOST) {
                            observed.phaseTriggered = true;
                            if (observed.fault == Fault.COMMIT_ACK_LOST) {
                                throw observed.failure;
                            }
                            clock.set(BUDGET.toNanos());
                        }
                        return committed;
                    }
                    if (name.equals("rollback") && method.getParameterCount() == 0) {
                        observed.rollbackCalls++;
                    }
                    if (name.equals("setNetworkTimeout") && observed.phaseTriggered
                            && observed.fault == Fault.LAST_METADATA_FETCH) {
                        observed.fetchNetworkTimeouts.add((Integer) arguments[1]);
                    }
                    if (name.equals("prepareStatement") && arguments[0] instanceof String sql
                            && observed.fault == Fault.LAST_METADATA_FETCH && sql.contains("AS statement_octets")
                            && sql.contains("FROM public.legal_requisito_conjunto_miembros m")) {
                        observed.phaseTriggered = true;
                        UUID last = projection.requirements().getLast().versionId();
                        arguments = arguments.clone();
                        arguments[0] = sql.replaceFirst("SELECT ", "SELECT pg_catalog.pg_sleep(CASE WHEN "
                                + "m.requisito_version_id = '" + last + "'::uuid THEN 10 ELSE 0.002 END) AS "
                                + FETCH_MARKER + ", ");
                    }
                    Object result = invoke(delegate, method, arguments);
                    if (result instanceof Statement statement) {
                        boolean slow = arguments != null && arguments.length > 0 && arguments[0] instanceof String sql
                                && sql.contains(FETCH_MARKER);
                        return instrumentStatement(statement, observed, slow);
                    }
                    if (name.equals("close")) {
                        observed.closes++;
                        if (observed.fault == Fault.DURING_CLOSE || observed.fault == Fault.CLOSE_FAILURE) {
                            observed.phaseTriggered = true;
                            if (observed.fault == Fault.CLOSE_FAILURE) {
                                throw observed.failure;
                            }
                            clock.set(BUDGET.toNanos());
                        }
                    }
                    return result;
                });
    }

    private static Statement instrumentStatement(Statement delegate, Attempt observed, boolean slow) {
        Class<?> type = delegate instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
        return (Statement) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, arguments) -> {
            if (method.getDeclaringClass() == Object.class) { return objectMethod(proxy, method, arguments); }
            if (method.getName().equals("cancel")) { observed.cancellations.incrementAndGet(); }
            Object result = invoke(delegate, method, arguments);
            if (slow && method.getName().equals("close")) { observed.slowStatementClosed = true; }
            if (slow && result instanceof ResultSet rows) {
                return Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[]{ResultSet.class},
                        (cursor, operation, values) -> {
                            if (operation.getDeclaringClass() == Object.class) { return objectMethod(cursor, operation, values); }
                            Object value = invoke(rows, operation, values);
                            if (operation.getName().equals("close")) { observed.slowCursorClosed = true; }
                            return value;
                        });
            }
            return result;
        });
    }

    private static Object objectMethod(Object proxy, Method method, Object[] arguments) {
        return switch (method.getName()) {
            case "equals" -> proxy == arguments[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "RequirementsDeadlineFixture";
            default -> throw new IllegalStateException("Unsupported Object method");
        };
    }

    private static Object invoke(Object target, Method method, Object[] arguments) throws Throwable {
        try { return method.invoke(target, arguments); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    private static long elapsedMillis(long started) { return (System.nanoTime() - started) / 1_000_000L; }

    private static String sqlState(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        while (failure != null && seen.add(failure)) {
            if (failure instanceof SQLException sql && sql.getSQLState() != null) { return sql.getSQLState(); }
            failure = failure.getCause();
        }
        return null;
    }

    private static boolean hasCause(Throwable actual, Throwable expected) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        while (actual != null && seen.add(actual)) {
            if (actual == expected) { return true; }
            actual = actual.getCause();
        }
        return false;
    }

    private enum Fault {
        NONE, LAST_METADATA_FETCH, AFTER_CALCULATION, BEFORE_COMMIT, AFTER_COMMIT,
        DURING_CLOSE, CLOSE_FAILURE, COMMIT_ACK_LOST
    }

    private static final class Attempt {
        private final Fault fault;
        private final SQLException failure = new SQLException("simulated failure after real commit or close", "08006");
        private final List<LegalRequiredSetAggregateReceipt> receipts = new ArrayList<>();
        private final List<Integer> completions = new ArrayList<>();
        private final List<Integer> fetchNetworkTimeouts = new ArrayList<>();
        private final AtomicInteger cancellations = new AtomicInteger();
        private LegalPublicRegistrationRequirements calculated;
        private boolean phaseTriggered;
        private boolean slowStatementClosed;
        private boolean slowCursorClosed;
        private int borrowAttempts;
        private int leases;
        private int commitCalls;
        private int rollbackCalls;
        private int closes;
        private Attempt(Fault fault) { this.fault = fault; }
    }

    private record DatabaseHarness(HikariDataSource pool, LegalPublicRequirementsDataSource bounded,
                                   LegalJdbcMetricsSupport metrics, LegalPublicRequirementsReadService service)
            implements AutoCloseable {
        @Override public void close() {
            try { bounded.close(); }
            finally { pool.close(); }
        }
    }
}
