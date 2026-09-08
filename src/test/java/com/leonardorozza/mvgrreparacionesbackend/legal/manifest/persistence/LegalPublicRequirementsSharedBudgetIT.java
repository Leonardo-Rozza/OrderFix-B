package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalPublicRegistrationRequirements;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalPublicRequirementsValidator;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionSystemException;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

/**
 * Production public context and PostgreSQL 16, with separate deterministic owner/local clocks.
 * Faults follow real SQL, COMMIT or close. This accredits rejection and persistence, not a timing SLA.
 */
class LegalPublicRequirementsSharedBudgetIT {
    private static final String ROLE = "ordenfix_legal_public_requirements_shared_budget_it";
    private static final String PASSWORD = "public-shared-budget-test-only";
    private static final Duration LOCAL_BUDGET = Duration.ofSeconds(15);
    private static final String METADATA_SQL = "AS markdown_octets";
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_public_requirements_shared_budget")
            .withUsername("ordenfix").withPassword("ordenfix");
    private static JdbcTemplate owner;

    @TempDir private Path temporaryDirectory;
    private final AtomicLong localClock = new AtomicLong();
    private final AtomicLong externalClock = new AtomicLong();
    private Attempt attempt;
    private AnnotationConfigApplicationContext context;
    private HikariDataSource pool;
    private LegalJdbcMetricsSupport metrics;
    private LegalPublicRequirementsReadService service;
    private LegalPublicRegistrationRequirements expected;

    @BeforeAll
    static void migrateAndProvisionOnlyTheDedicatedPublicConsumer() {
        POSTGRES.start();
        LegalManifestPersistenceITSupport.migrateLatest(POSTGRES);
        owner = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        new LegalRestrictedPublicRequirementsRoleFixture(owner, POSTGRES.getJdbcUrl(), ROLE, PASSWORD,
                POSTGRES.getDriverClassName()).provisionAndVerify();
    }

    @BeforeEach
    void seedAndOpenTheActualPublicGraphWithPhaseObservation() throws Exception {
        LegalPublicRequirementsReadITSupport.clearCatalog(owner);
        var seed = LegalPublicRequirementsReadITSupport.seedRegistration(
                owner, temporaryDirectory, "public-shared-budget-" + UUID.randomUUID());
        expected = new LegalPublicRequirementsValidator().validate(seed.projection());
        context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("public-shared-budget-it", Map.of(
                LegalPublicRequirementsDatabaseConfiguration.ENABLED_PROPERTY, "true",
                LegalPublicRequirementsDatabaseConfiguration.PROPERTY_PREFIX + "jdbc-url", POSTGRES.getJdbcUrl(),
                LegalPublicRequirementsDatabaseConfiguration.PROPERTY_PREFIX + "username", ROLE,
                LegalPublicRequirementsDatabaseConfiguration.PROPERTY_PREFIX + "password", PASSWORD)));
        context.getBeanFactory().addBeanPostProcessor(new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (name.equals("legalPublicRequirementsPool")) {
                    pool = (HikariDataSource) bean;
                    metrics = LegalJdbcMetricsSupport.instrument(pool, Duration.ZERO);
                    HikariDataSource monitored = spy(pool);
                    try {
                        doAnswer(invocation -> instrumentConnection(metrics.dataSource().getConnection(), attempt))
                                .when(monitored).getConnection();
                    } catch (SQLException impossibleDuringStubbing) {
                        throw new IllegalStateException(impossibleDuringStubbing);
                    }
                    return monitored;
                }
                if (bean instanceof LegalRequiredSetAggregateStore original) {
                    var monitored = spy(original);
                    doAnswer(invocation -> {
                        var receipt = (LegalRequiredSetAggregateReceipt) invocation.callRealMethod();
                        attempt.receipts.add(receipt);
                        return receipt;
                    }).when(monitored).materialize(any(), any());
                    return monitored;
                }
                if (bean instanceof LegalPublicRequirementsReader original) {
                    var monitored = spy(original);
                    doAnswer(invocation -> {
                        LegalPublicRequirementsDeadline deadline = invocation.getArgument(2);
                        attempt.remainingAtReader = deadline.remainingMillis();
                        attempt.transaction = context.getBean(JdbcTemplate.class).queryForMap("""
                                SELECT current_user AS current_role, session_user AS session_role,
                                       current_setting('transaction_isolation') AS isolation,
                                       current_setting('transaction_read_only') AS read_only,
                                       current_setting('statement_timeout') AS statement_timeout
                                """);
                        return invocation.callRealMethod();
                    }).when(monitored).read(any(), any(), any());
                    return monitored;
                }
                return bean;
            }
        });
        // Same nominal wrapper and actual graph; only its existing local-clock seam is replaced.
        context.addBeanFactoryPostProcessor(factory -> {
            var definition = (AbstractBeanDefinition) factory.getBeanDefinition("legalPublicRequirementsDataSource");
            definition.setInstanceSupplier(() -> new LegalPublicRequirementsDataSource(
                    factory.getBean("legalPublicRequirementsPool", HikariDataSource.class), LOCAL_BUDGET, localClock::get));
        });
        context.register(LegalPublicRequirementsDatabaseConfiguration.class);
        context.refresh();
        service = context.getBean(LegalPublicRequirementsReadService.class);
        var bounded = context.getBean(LegalPublicRequirementsDataSource.class);
        assertThat(context.getBean(JdbcTemplate.class).getDataSource()).isSameAs(bounded);
        var manager = context.getBean(DataSourceTransactionManager.class);
        assertThat(manager.getClass()).isEqualTo(DataSourceTransactionManager.class);
        assertThat(manager.getDataSource()).isSameAs(bounded);
        assertThat(manager.isRollbackOnCommitFailure()).isFalse();
        assertThat(context.getBeansOfType(LegalDatabaseBoundaryMarker.class).values())
                .extracting(LegalDatabaseBoundaryMarker::kind)
                .containsExactly(LegalDatabaseBoundaryMarker.Kind.PUBLIC_REQUIREMENTS);
        start(Fault.NONE, 0);
    }

    @AfterEach
    void closeTheContextAndItsActualPool() {
        try {
            if (context != null) context.close();
        } finally {
            if (pool != null) pool.close();
        }
    }

    @AfterAll
    static void stopPostgres() {
        POSTGRES.stop();
    }

    @ParameterizedTest @CsvSource({"0,15000,5s", "28,2000,2s"})
    void appliesTheMinimumToRealPublicSqlWithoutChangingRoleOrTransaction(long consumedSeconds,
            int expectedRemaining, String expectedSqlTimeout) {
        start(Fault.NONE, consumedSeconds);

        LegalPublicRegistrationRequirements result = service.readRegistration(attempt.budget);

        assertResult(result);
        assertThat(attempt.remainingAtReader).isEqualTo(expectedRemaining);
        assertThat(attempt.transaction).containsEntry("statement_timeout", expectedSqlTimeout);
        assertPublicTransaction();
        assertThat(attempt.queryTimeouts).contains(Math.min(5, expectedRemaining / 1_000));
        assertThat(attempt.budget.remainingMillis()).isEqualTo(30_000 - Math.toIntExact(consumedSeconds * 1_000));
        assertThat(attempt.receipts).singleElement().satisfies(receipt ->
                assertThat(receipt.outcome()).isEqualTo(LegalRequiredSetAggregateReceipt.Outcome.CREATED));
        assertCompleted(TransactionSynchronization.STATUS_COMMITTED, 1, 0);
        assertCounts(1);
    }

    @ParameterizedTest
    @EnumSource(value = Fault.class, names = {"LOCAL_READER_EXPIRY", "OWNER_READER_EXPIRY"})
    void expiryAfterRealReaderMetadataRollsBackBeforeTextHydration(Fault phase) {
        start(phase, phase == Fault.OWNER_READER_EXPIRY ? 22 : 0);

        Throwable failure = failedRead();

        assertThat(failure).hasNoCause();
        assertThat(attempt.metadataQueries).isEqualTo(1);
        assertThat(attempt.phaseInjected).isTrue();
        assertThat(metrics.snapshot().executionsContaining("ELSE NULL END AS text_utf8")).isZero();
        assertCompleted(TransactionSynchronization.STATUS_ROLLED_BACK, 0, 1);
        assertCounts(0);
        if (phase == Fault.LOCAL_READER_EXPIRY) {
            // A local cap failure does not invent cleanup or invalidate the distinct thirty-second owner.
            assertThat(attempt.budget.remainingMillis()).isEqualTo(15_000);
        } else {
            assertThatThrownBy(attempt.budget::check)
                    .isExactlyInstanceOf(LegalRegistrationBudget.UnavailableException.class).hasNoCause();
        }
        recoverHistorically(null, LegalRequiredSetAggregateReceipt.Outcome.CREATED);
    }

    @ParameterizedTest @ValueSource(strings = {"null", "expired", "cleanup", "insufficient-borrow"})
    void unusableExternalBudgetNeverBorrowsAndDoesNotReplaceTheHistoricalApi(String defect) {
        start(Fault.NONE, 0);
        if (defect.equals("expired")) externalClock.set(30_000_000_000L);
        if (defect.equals("cleanup")) attempt.budget.recordCleanupFailure(attempt.sqlCloseFailure);
        if (defect.equals("insufficient-borrow")) externalClock.set(29_500_000_000L);
        var supplied = defect.equals("null") ? null : attempt.budget;

        Throwable failure = catchThrowable(() -> service.readRegistration(supplied));

        assertThat(failure).isExactlyInstanceOf(LegalPublicRequirementsReadException.class)
                .hasMessage(new LegalPublicRequirementsReadException().getMessage());
        if (defect.equals("cleanup")) assertThat(failure).hasCause(attempt.sqlCloseFailure);
        assertThat(attempt.borrows).isZero();
        assertThat(attempt.receipts).isEmpty();
        assertThat(attempt.completions).isEmpty();
        assertThat(metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isZero();
        assertThat(metrics.snapshot().commits()).isZero();
        assertThat(metrics.snapshot().rollbacks()).isZero();
        assertCounts(0);
        assertReleased();
        recoverHistorically(null, LegalRequiredSetAggregateReceipt.Outcome.CREATED);
    }

    @ParameterizedTest
    @EnumSource(value = Fault.class, names = {"BEFORE_COMMIT_EXPIRY", "AFTER_COMMIT_EXPIRY", "CLOSE_EXPIRY"})
    void lateOwnerExpiryPreservesTheActualCommitOutcome(Fault phase) {
        start(phase, 22);

        failedRead();

        assertThat(attempt.phaseInjected).isTrue();
        boolean committed = phase != Fault.BEFORE_COMMIT_EXPIRY;
        assertCompleted(committed ? TransactionSynchronization.STATUS_COMMITTED : TransactionSynchronization.STATUS_ROLLED_BACK,
                committed ? 1 : 0, committed ? 0 : 1);
        assertCounts(committed ? 1 : 0);
        assertThatThrownBy(attempt.budget::check).isExactlyInstanceOf(LegalRegistrationBudget.UnavailableException.class).hasNoCause();
        UUID confirmedId = committed ? attempt.receipts.getFirst().aggregateId() : null;
        recoverHistorically(confirmedId, committed ? LegalRequiredSetAggregateReceipt.Outcome.REUSED
                : LegalRequiredSetAggregateReceipt.Outcome.CREATED);
    }

    @ParameterizedTest
    @EnumSource(value = Fault.class, names = {"CLOSE_SQL", "CLOSE_RUNTIME"})
    void absorbedCloseFailurePoisonsTheExternalOwnerAndKeepsTheAlreadyCommittedAggregate(Fault phase) {
        start(phase, 0);
        Throwable cleanup = phase == Fault.CLOSE_SQL ? attempt.sqlCloseFailure : attempt.runtimeCloseFailure;

        Throwable failure = failedRead();

        assertThat(failure).hasCause(cleanup);
        assertThatThrownBy(attempt.budget::check).isExactlyInstanceOf(LegalRegistrationBudget.UnavailableException.class)
                .hasCause(cleanup);
        assertCompleted(TransactionSynchronization.STATUS_COMMITTED, 1, 0);
        assertCounts(1);
        UUID aggregateId = attempt.receipts.getFirst().aggregateId();
        var headers = owner.queryForList("SELECT id, xmin::text, required_set_revision FROM legal_requisito_agregados ORDER BY id");
        metrics.reset();

        assertThatThrownBy(() -> service.readRegistration(attempt.budget))
                .isExactlyInstanceOf(LegalPublicRequirementsReadException.class).hasCause(cleanup);

        assertThat(attempt.borrows).isEqualTo(1); // No second borrow after the first request's connection return.
        assertThat(metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isZero();
        assertThat(metrics.snapshot().commits()).isZero();
        assertThat(owner.queryForList("SELECT id, xmin::text, required_set_revision FROM legal_requisito_agregados ORDER BY id"))
                .isEqualTo(headers);
        assertReleased();
        recoverHistorically(aggregateId, LegalRequiredSetAggregateReceipt.Outcome.REUSED);
    }

    @Test
    void lostAcknowledgementIsUnknownButTheActualCommitCanBeReusedWithoutDml() {
        start(Fault.COMMIT_ACK, 22);

        Throwable failure = failedRead();

        assertThat(failure.getCause()).isExactlyInstanceOf(TransactionSystemException.class).hasCause(attempt.sqlAckFailure);
        assertCompleted(TransactionSynchronization.STATUS_UNKNOWN, 1, 0);
        assertCounts(1);
        assertThat(attempt.budget.remainingMillis()).isEqualTo(8_000);
        recoverHistorically(attempt.receipts.getFirst().aggregateId(), LegalRequiredSetAggregateReceipt.Outcome.REUSED);
    }

    @Test
    void aRealSqlErrorRollsBackTheReadWithoutMisclassifyingItAsOwnerCleanup() {
        start(Fault.READER_SQL, 0);

        Throwable failure = failedRead();

        assertThat((Throwable) attempt.injectedSqlFailure).isNotNull();
        assertThat(attempt.injectedSqlFailure.getSQLState()).isEqualTo("22012");
        assertThat(causeContains(failure, attempt.injectedSqlFailure)).isTrue();
        assertThat(attempt.metadataQueries).isEqualTo(1);
        assertCompleted(TransactionSynchronization.STATUS_ROLLED_BACK, 0, 1);
        assertCounts(0);
        assertThat(attempt.budget.remainingMillis()).isEqualTo(30_000);
        recoverHistorically(null, LegalRequiredSetAggregateReceipt.Outcome.CREATED);
    }

    @Test
    void reuseOfTheSameOwnerKeepsTimeAlreadySpentAndDoesNotRewriteTheAggregate() {
        start(Fault.NONE, 0);
        LegalRegistrationBudget sameOwner = attempt.budget;
        assertResult(service.readRegistration(sameOwner));
        UUID aggregateId = attempt.receipts.getFirst().aggregateId();
        assertCompleted(TransactionSynchronization.STATUS_COMMITTED, 1, 0);
        var headers = owner.queryForList("SELECT id, xmin::text, required_set_revision FROM legal_requisito_agregados ORDER BY id");
        externalClock.set(28_000_000_000L);
        localClock.set(9_000_000_000L);
        attempt = new Attempt(Fault.NONE, sameOwner);
        metrics.reset();

        assertResult(service.readRegistration(sameOwner));

        assertThat(attempt.remainingAtReader).isEqualTo(2_000);
        assertThat(attempt.transaction).containsEntry("statement_timeout", "2s");
        assertThat(sameOwner.remainingMillis()).isEqualTo(2_000);
        assertThat(attempt.receipts).singleElement().satisfies(receipt -> {
            assertThat(receipt.outcome()).isEqualTo(LegalRequiredSetAggregateReceipt.Outcome.REUSED);
            assertThat(receipt.aggregateId()).isEqualTo(aggregateId);
        });
        assertThat(metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isZero();
        assertThat(owner.queryForList("SELECT id, xmin::text, required_set_revision FROM legal_requisito_agregados ORDER BY id"))
                .isEqualTo(headers);
        assertCompleted(TransactionSynchronization.STATUS_COMMITTED, 1, 0);
    }

    private void start(Fault fault, long consumedSeconds) {
        localClock.set(0);
        externalClock.set(0);
        var budget = LegalRegistrationBudget.start(externalClock::get);
        externalClock.set(Duration.ofSeconds(consumedSeconds).toNanos());
        attempt = new Attempt(fault, budget);
        metrics.reset();
    }

    private Throwable failedRead() {
        Throwable failure = catchThrowable(() -> service.readRegistration(attempt.budget));
        assertThat(failure).isExactlyInstanceOf(LegalPublicRequirementsReadException.class)
                .hasMessage(new LegalPublicRequirementsReadException().getMessage());
        assertThat(attempt.borrows).isEqualTo(1);
        assertThat(attempt.receipts).singleElement().satisfies(receipt ->
                assertThat(receipt.outcome()).isEqualTo(LegalRequiredSetAggregateReceipt.Outcome.CREATED));
        assertThat(metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isEqualTo(2);
        assertPublicTransaction();
        return failure;
    }

    private void assertPublicTransaction() {
        assertThat(attempt.transaction).containsEntry("current_role", ROLE).containsEntry("session_role", ROLE)
                .containsEntry("isolation", "read committed").containsEntry("read_only", "off");
    }

    private void assertResult(LegalPublicRegistrationRequirements result) {
        assertThat(result.projection()).isEqualTo(expected.projection());
        assertThat(result.scopeRevision()).isEqualTo(expected.scopeRevision());
        assertThat(result.requiredSetRevision()).isEqualTo(expected.requiredSetRevision());
    }

    private void assertCompleted(int completion, int commits, int rollbacks) {
        assertThat(attempt.completions).containsExactly(completion);
        assertThat(attempt.afterCommits).isEqualTo(completion == TransactionSynchronization.STATUS_COMMITTED ? 1 : 0);
        assertThat(attempt.commitCalls).isEqualTo(commits);
        assertThat(attempt.rollbackCalls).isEqualTo(rollbacks);
        assertThat(metrics.snapshot().commits()).isEqualTo(commits);
        assertThat(metrics.snapshot().rollbacks()).isEqualTo(rollbacks);
        assertThat(attempt.closes).isEqualTo(1);
        assertReleased();
    }

    private void assertReleased() {
        assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
        assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
        assertThatThrownBy(context.getBean(LegalPublicRequirementsDataSource.class)::getConnection)
                .isExactlyInstanceOf(SQLException.class);
    }

    private static void assertCounts(long count) {
        assertThat(LegalPublicRequirementsReadITSupport.aggregateCounts(owner))
                .containsExactlyInAnyOrderEntriesOf(Map.of("legal_requisito_agregados", count,
                        "legal_requisito_agregado_scopes", count));
    }

    private void recoverHistorically(UUID confirmedId, LegalRequiredSetAggregateReceipt.Outcome outcome) {
        start(Fault.NONE, 0);
        assertResult(service.readRegistration());
        assertThat(attempt.remainingAtReader).isEqualTo(15_000);
        assertThat(attempt.receipts).singleElement().satisfies(receipt -> {
            assertThat(receipt.outcome()).isEqualTo(outcome);
            if (confirmedId != null) assertThat(receipt.aggregateId()).isEqualTo(confirmedId);
        });
        assertThat(metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.DML))
                .isEqualTo(outcome == LegalRequiredSetAggregateReceipt.Outcome.REUSED ? 0 : 2);
        assertCounts(1);
        assertCompleted(TransactionSynchronization.STATUS_COMMITTED, 1, 0);
    }

    private Connection instrumentConnection(Connection delegate, Attempt observed) {
        observed.borrows++;
        AtomicBoolean registered = new AtomicBoolean();
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, arguments);
                    if (TransactionSynchronizationManager.isSynchronizationActive() && registered.compareAndSet(false, true)) {
                        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                            @Override public void beforeCompletion() {
                                if (observed.fault == Fault.BEFORE_COMMIT_EXPIRY) expireOwner(observed);
                            }
                            @Override public void afterCommit() { observed.afterCommits++; }
                            @Override public void afterCompletion(int status) { observed.completions.add(status); }
                        });
                    }
                    String name = method.getName();
                    if (name.equals("commit")) {
                        observed.commitCalls++;
                        Object result = invoke(delegate, method, arguments);
                        if (observed.fault == Fault.COMMIT_ACK) throw observed.sqlAckFailure;
                        if (observed.fault == Fault.AFTER_COMMIT_EXPIRY) expireOwner(observed);
                        return result;
                    }
                    if (name.equals("rollback") && method.getParameterCount() == 0) observed.rollbackCalls++;
                    Object result = invoke(delegate, method, arguments);
                    if (name.equals("close")) {
                        observed.closes++;
                        if (observed.fault == Fault.CLOSE_SQL) throw observed.sqlCloseFailure;
                        if (observed.fault == Fault.CLOSE_RUNTIME) throw observed.runtimeCloseFailure;
                        if (observed.fault == Fault.CLOSE_EXPIRY) expireOwner(observed);
                    }
                    if (result instanceof Statement statement && (name.equals("prepareStatement") || name.equals("createStatement"))) {
                        String sql = arguments != null && arguments.length > 0 && arguments[0] instanceof String text ? text : null;
                        return instrumentStatement(statement, sql, delegate, observed);
                    }
                    return result;
                });
    }

    private Statement instrumentStatement(Statement delegate, String preparedSql, Connection connection, Attempt observed) {
        Class<?> type = delegate instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
        return (Statement) Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[]{type},
                (proxy, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, arguments);
                    if (method.getName().equals("setQueryTimeout")) observed.queryTimeouts.add((Integer) arguments[0]);
                    Object result = invoke(delegate, method, arguments);
                    String sql = preparedSql != null ? preparedSql
                            : arguments != null && arguments.length > 0 && arguments[0] instanceof String text ? text : null;
                    if (method.getName().equals("executeQuery") && sql != null && sql.contains(METADATA_SQL)) {
                        observed.metadataQueries++;
                        if (observed.fault == Fault.LOCAL_READER_EXPIRY) {
                            localClock.set(15_000_000_000L);
                            externalClock.set(15_000_000_000L);
                            observed.phaseInjected = true;
                        }
                        if (observed.fault == Fault.OWNER_READER_EXPIRY) expireOwner(observed);
                        if (observed.fault == Fault.READER_SQL) {
                            // The reader's real metadata query completed; the injected failure is also real PostgreSQL.
                            ((ResultSet) result).close();
                            try (Statement failing = connection.createStatement()) {
                                failing.execute("SELECT 1 / 0");
                            } catch (SQLException sqlFailure) {
                                observed.injectedSqlFailure = sqlFailure;
                                throw sqlFailure;
                            }
                            throw new AssertionError("PostgreSQL did not reject division by zero");
                        }
                    }
                    return result;
                });
    }

    private void expireOwner(Attempt observed) {
        externalClock.set(30_000_000_000L);
        localClock.set(8_000_000_000L);
        observed.phaseInjected = true;
    }

    private static Object objectMethod(Object proxy, Method method, Object[] arguments) {
        return switch (method.getName()) {
            case "equals" -> proxy == arguments[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "PublicSharedBudgetObservation";
            default -> throw new IllegalStateException("Unsupported Object method");
        };
    }

    private static Object invoke(Object target, Method method, Object[] arguments) throws Throwable {
        try { return method.invoke(target, arguments); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    private static boolean causeContains(Throwable failure, Throwable expectedCause) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current == expectedCause) return true;
        }
        return false;
    }

    private enum Fault {
        NONE, LOCAL_READER_EXPIRY, OWNER_READER_EXPIRY, BEFORE_COMMIT_EXPIRY,
        AFTER_COMMIT_EXPIRY, CLOSE_EXPIRY, CLOSE_SQL, CLOSE_RUNTIME, COMMIT_ACK, READER_SQL
    }

    private static final class Attempt {
        final Fault fault;
        final LegalRegistrationBudget budget;
        final SQLException sqlCloseFailure = new SQLException("synthetic failure after connection return", "08006");
        final RuntimeException runtimeCloseFailure = new IllegalStateException("synthetic runtime failure after connection return");
        final SQLException sqlAckFailure = new SQLException("synthetic acknowledgement loss after physical commit", "08006");
        final List<LegalRequiredSetAggregateReceipt> receipts = new ArrayList<>();
        final List<Integer> completions = new ArrayList<>();
        final List<Integer> queryTimeouts = new ArrayList<>();
        Map<String, Object> transaction;
        SQLException injectedSqlFailure;
        int remainingAtReader;
        int borrows;
        int commitCalls;
        int rollbackCalls;
        int closes;
        int afterCommits;
        int metadataQueries;
        boolean phaseInjected;

        Attempt(Fault fault, LegalRegistrationBudget budget) {
            this.fault = fault;
            this.budget = budget;
        }
    }
}
