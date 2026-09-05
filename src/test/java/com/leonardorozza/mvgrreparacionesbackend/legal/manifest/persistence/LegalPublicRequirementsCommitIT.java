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
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

/**
 * Real PostgreSQL/store/reader with faults confined to the commit acknowledgement boundary.
 * A lost acknowledgement is simulated after the real COMMIT: UNKNOWN is an application outcome,
 * not evidence that the database rolled back. No production role or migration is changed.
 */
class LegalPublicRequirementsCommitIT {

    private static final String ROLE = "ordenfix_legal_public_requirements_commit_it";
    private static final String PASSWORD = "legal-public-requirements-commit-test-only";
    private static final Duration BUDGET = Duration.ofSeconds(15);
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_public_requirements_commit")
            .withUsername("ordenfix").withPassword("ordenfix");

    private static JdbcTemplate owner;
    @TempDir
    private Path temporaryDirectory;
    private final AtomicLong clock = new AtomicLong();
    private Attempt attempt = new Attempt(Fault.NONE);
    private AnnotationConfigApplicationContext context;
    private HikariDataSource pool;
    private LegalJdbcMetricsSupport metrics;
    private LegalPublicRequirementsReadService service;
    private LegalPublicRegistrationRequirements expected;

    @BeforeAll
    static void migrateAndProvisionTheDedicatedConsumer() {
        POSTGRES.start();
        LegalManifestPersistenceITSupport.migrateLatest(POSTGRES);
        owner = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        new LegalRestrictedPublicRequirementsRoleFixture(owner, POSTGRES.getJdbcUrl(), ROLE, PASSWORD,
                POSTGRES.getDriverClassName()).provisionAndVerify();
    }

    @BeforeEach
    void openTheProductionCompositionWithPassiveMetricsAndPhaseFaults() throws Exception {
        LegalPublicRequirementsReadITSupport.clearCatalog(owner);
        var seed = LegalPublicRequirementsReadITSupport.seedRegistration(
                owner, temporaryDirectory, "requirements-commit-" + UUID.randomUUID());
        expected = new LegalPublicRequirementsValidator().validate(seed.projection());

        context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("commit-it", Map.of(
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
                        doAnswer(invocation -> instrument(metrics.dataSource().getConnection(), attempt))
                                .when(monitored).getConnection();
                    } catch (SQLException impossibleDuringStubbing) {
                        throw new IllegalStateException(impossibleDuringStubbing);
                    }
                    return monitored;
                }
                if (bean instanceof LegalRequiredSetAggregateStore original) {
                    LegalRequiredSetAggregateStore observed = spy(original);
                    doAnswer(invocation -> {
                        LegalRequiredSetAggregateReceipt receipt =
                                (LegalRequiredSetAggregateReceipt) invocation.callRealMethod();
                        attempt.receipts.add(receipt);
                        return receipt;
                    }).when(observed).materialize(any(), any());
                    return observed;
                }
                return bean;
            }
        });
        // Replace only construction of the same production wrapper to use its existing clock seam.
        // All JDBC, manager, template, gate, store, reader and facade beans remain the actual config.
        context.addBeanFactoryPostProcessor(factory -> {
            var definition = (AbstractBeanDefinition) factory.getBeanDefinition("legalPublicRequirementsDataSource");
            definition.setInstanceSupplier(() -> new LegalPublicRequirementsDataSource(
                    factory.getBean("legalPublicRequirementsPool", HikariDataSource.class), BUDGET, clock::get));
        });
        context.register(LegalPublicRequirementsDatabaseConfiguration.class);
        context.refresh();
        service = context.getBean(LegalPublicRequirementsReadService.class);
        assertThat(context.getBean(DataSourceTransactionManager.class).isRollbackOnCommitFailure()).isFalse();
        assertThat(context.getBeansOfType(LegalDatabaseBoundaryMarker.class).values())
                .extracting(LegalDatabaseBoundaryMarker::kind)
                .containsExactly(LegalDatabaseBoundaryMarker.Kind.PUBLIC_REQUIREMENTS);
        start(Fault.NONE);
    }

    @AfterEach
    void closeResources() {
        try {
            if (context != null) {
                context.close();
            }
        } finally {
            if (pool != null) {
                pool.close();
            }
        }
    }

    @AfterAll
    static void stopContainer() {
        POSTGRES.stop();
    }

    @Test
    void failureBeforeCommitRollsBackTheAccreditedNewAggregate() {
        start(Fault.BEFORE_COMMIT);
        Throwable failure = failedRead();
        assertThat(failure).hasCause(attempt.runtimeFailure);
        assertCompleted(TransactionSynchronization.STATUS_ROLLED_BACK, 0, 1);
        assertCounts(0);
        recover(null, LegalRequiredSetAggregateReceipt.Outcome.CREATED);
    }

    @Test
    void expiryImmediatelyBeforeCommitPreventsSendingCommitAndRollsBack() {
        start(Fault.BEFORE_COMMIT_DEADLINE);
        failedRead();
        assertCompleted(TransactionSynchronization.STATUS_ROLLED_BACK, 0, 1);
        assertCounts(0);
        recover(null, LegalRequiredSetAggregateReceipt.Outcome.CREATED);
    }

    @Test
    void lostSqlAcknowledgementReportsUnknownWithoutRollbackAndCanReuseTheRealCommit() {
        start(Fault.COMMIT_SQL_ACKNOWLEDGEMENT);
        Throwable failure = failedRead();
        assertThat(failure.getCause()).isInstanceOf(TransactionSystemException.class)
                .hasCause(attempt.sqlFailure);
        assertCompleted(TransactionSynchronization.STATUS_UNKNOWN, 1, 0);
        assertCounts(1);
        recover(attempt.receipts.getFirst().aggregateId(), LegalRequiredSetAggregateReceipt.Outcome.REUSED);
    }

    @Test
    void uncheckedDriverFailureAfterRealCommitAlsoReportsUnknownWithoutRollback() {
        start(Fault.COMMIT_RUNTIME_ACKNOWLEDGEMENT);
        Throwable failure = failedRead();
        assertThat(failure.getCause()).isInstanceOf(TransactionSystemException.class);
        assertThat(failure.getCause().getCause()).isInstanceOf(SQLException.class)
                .hasCause(attempt.runtimeFailure);
        assertCompleted(TransactionSynchronization.STATUS_UNKNOWN, 1, 0);
        assertCounts(1);
        recover(attempt.receipts.getFirst().aggregateId(), LegalRequiredSetAggregateReceipt.Outcome.REUSED);
    }

    @Test
    void failingAfterCommitCallbackPreventsDeliveryButKeepsCommittedStatusAndAggregate() {
        start(Fault.AFTER_COMMIT);
        Throwable failure = failedRead();
        assertThat(failure).hasCause(attempt.runtimeFailure);
        assertCompleted(TransactionSynchronization.STATUS_COMMITTED, 1, 0);
        assertCounts(1);
        recover(attempt.receipts.getFirst().aggregateId(), LegalRequiredSetAggregateReceipt.Outcome.REUSED);
    }

    @Test
    void expiryAfterRealCommitIsCheckedOnlyAfterSpringReportsCommitted() {
        start(Fault.AFTER_COMMIT_DEADLINE);
        failedRead();
        assertCompleted(TransactionSynchronization.STATUS_COMMITTED, 1, 0);
        assertCounts(1);
        recover(attempt.receipts.getFirst().aggregateId(), LegalRequiredSetAggregateReceipt.Outcome.REUSED);
    }

    @Test
    void expiryDuringLeaseCleanupPreventsDeliveryWithoutInventingARollback() {
        start(Fault.CLOSE_DEADLINE);
        failedRead();
        assertCompleted(TransactionSynchronization.STATUS_COMMITTED, 1, 0);
        assertCounts(1);
        recover(attempt.receipts.getFirst().aggregateId(), LegalRequiredSetAggregateReceipt.Outcome.REUSED);
    }

    @Test
    void sqlFailureAfterPhysicalClosePreventsDeliveryEvenWhenSpringAbsorbsIt() {
        start(Fault.CLOSE_SQL);
        Throwable failure = failedRead();
        assertThat(failure).hasCause(attempt.sqlCloseFailure);
        assertCompleted(TransactionSynchronization.STATUS_COMMITTED, 1, 0);
        assertCounts(1);
        recover(attempt.receipts.getFirst().aggregateId(), LegalRequiredSetAggregateReceipt.Outcome.REUSED);
    }

    @Test
    void uncheckedFailureAfterPhysicalCloseAlsoPreventsDeliveryWithoutRollback() {
        start(Fault.CLOSE_RUNTIME);
        Throwable failure = failedRead();
        assertThat(failure).hasCause(attempt.runtimeCloseFailure);
        assertCompleted(TransactionSynchronization.STATUS_COMMITTED, 1, 0);
        assertCounts(1);
        recover(attempt.receipts.getFirst().aggregateId(), LegalRequiredSetAggregateReceipt.Outcome.REUSED);
    }

    @Test
    void caughtNestedCloseFailureStillRejectsTheOwningOperationAndDoesNotPoisonANewOne() {
        start(Fault.CLOSE_SQL);
        Throwable failure = catchThrowable(() -> context.getBean(LegalPublicRequirementsDataSource.class)
                .withinDeadline(deadline -> {
                    // Catch the inner facade error deliberately: nesting must not reset or clear it.
                    assertThat(failedRead()).hasCause(attempt.sqlCloseFailure);
                    return "an outer success must not escape";
                }));
        assertThat(failure).isInstanceOf(LegalPublicRequirementsReadException.class)
                .hasCause(attempt.sqlCloseFailure);
        assertCompleted(TransactionSynchronization.STATUS_COMMITTED, 1, 0);
        assertCounts(1);
        recover(attempt.receipts.getFirst().aggregateId(), LegalRequiredSetAggregateReceipt.Outcome.REUSED);
    }

    private void start(Fault fault) {
        clock.set(0);
        attempt = new Attempt(fault);
        metrics.reset();
    }

    private Throwable failedRead() {
        Throwable failure = catchThrowable(service::readRegistration);
        assertThat(failure).isInstanceOf(LegalPublicRequirementsReadException.class)
                .hasMessage(new LegalPublicRequirementsReadException().getMessage());
        // The store completed inside the real transaction before the injected phase was reached.
        assertThat(attempt.receipts).singleElement()
                .satisfies(receipt -> assertThat(receipt.outcome())
                        .isEqualTo(LegalRequiredSetAggregateReceipt.Outcome.CREATED));
        assertThat(metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isPositive();
        assertThat(attempt.borrows).isEqualTo(1); // No transparent retry after any failure.
        return failure;
    }

    private void assertCompleted(int completion, int realCommits, int rollbacks) {
        assertThat(attempt.completions).containsExactly(completion);
        assertThat(attempt.afterCommit).isEqualTo(completion == TransactionSynchronization.STATUS_COMMITTED ? 1 : 0);
        assertThat(attempt.commitCalls).isEqualTo(realCommits);
        assertThat(attempt.rollbackCalls).isEqualTo(rollbacks);
        assertThat(metrics.snapshot().commits()).isEqualTo(realCommits);
        assertThat(metrics.snapshot().rollbacks()).isEqualTo(rollbacks);
        assertThat(attempt.closes).isEqualTo(1);
        assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
        assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
    }

    private void recover(UUID alreadyCommitted, LegalRequiredSetAggregateReceipt.Outcome outcome) {
        start(Fault.NONE);
        LegalPublicRegistrationRequirements recovered = service.readRegistration();
        assertThat(recovered.projection()).isEqualTo(expected.projection());
        assertThat(recovered.scopeRevision()).isEqualTo(expected.scopeRevision());
        assertThat(recovered.requiredSetRevision()).isEqualTo(expected.requiredSetRevision());
        assertThat(attempt.receipts).singleElement().satisfies(receipt -> {
            assertThat(receipt.outcome()).isEqualTo(outcome);
            if (alreadyCommitted != null) {
                assertThat(receipt.aggregateId()).isEqualTo(alreadyCommitted);
            }
        });
        if (outcome == LegalRequiredSetAggregateReceipt.Outcome.REUSED) {
            assertThat(metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isZero();
        } else {
            assertThat(metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isPositive();
        }
        assertThat(attempt.borrows).isEqualTo(1);
        assertCompleted(TransactionSynchronization.STATUS_COMMITTED, 1, 0);
        assertCounts(1);
        assertThat(owner.queryForObject("SELECT required_set_revision FROM legal_requisito_agregados", String.class))
                .isEqualTo(recovered.requiredSetRevision());
    }

    private static void assertCounts(long count) {
        // The owner is an independent observer only; all tested materialization uses the consumer role.
        assertThat(LegalPublicRequirementsReadITSupport.aggregateCounts(owner)).containsExactlyInAnyOrderEntriesOf(
                Map.of("legal_requisito_agregados", count, "legal_requisito_agregado_scopes", count));
    }

    private Connection instrument(Connection delegate, Attempt observed) {
        observed.borrows++;
        AtomicBoolean registered = new AtomicBoolean();
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "equals" -> proxy == arguments[0];
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "toString" -> "RequirementsCommitObservation";
                            default -> throw new IllegalStateException("Unsupported Object method");
                        };
                    }
                    if (TransactionSynchronizationManager.isSynchronizationActive()
                            && registered.compareAndSet(false, true)) {
                        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                            @Override
                            public void beforeCommit(boolean readOnly) {
                                if (observed.fault == Fault.BEFORE_COMMIT) {
                                    throw observed.runtimeFailure;
                                }
                            }

                            @Override
                            public void beforeCompletion() {
                                if (observed.fault == Fault.BEFORE_COMMIT_DEADLINE) {
                                    clock.set(BUDGET.toNanos());
                                }
                            }

                            @Override
                            public void afterCommit() {
                                observed.afterCommit++;
                                if (observed.fault == Fault.AFTER_COMMIT) {
                                    throw observed.runtimeFailure;
                                }
                            }

                            @Override
                            public void afterCompletion(int status) {
                                observed.completions.add(status);
                            }
                        });
                    }
                    if (method.getName().equals("commit")) {
                        observed.commitCalls++;
                        Object committed = invoke(delegate, method, arguments);
                        if (observed.fault == Fault.COMMIT_SQL_ACKNOWLEDGEMENT) {
                            throw observed.sqlFailure;
                        }
                        if (observed.fault == Fault.COMMIT_RUNTIME_ACKNOWLEDGEMENT) {
                            throw observed.runtimeFailure;
                        }
                        if (observed.fault == Fault.AFTER_COMMIT_DEADLINE) {
                            clock.set(BUDGET.toNanos());
                        }
                        return committed;
                    }
                    if (method.getName().equals("rollback") && method.getParameterCount() == 0) {
                        observed.rollbackCalls++;
                    }
                    Object result = invoke(delegate, method, arguments);
                    if (method.getName().equals("close")) {
                        observed.closes++;
                        if (observed.fault == Fault.CLOSE_SQL) {
                            throw observed.sqlCloseFailure;
                        }
                        if (observed.fault == Fault.CLOSE_RUNTIME) {
                            throw observed.runtimeCloseFailure;
                        }
                        if (observed.fault == Fault.CLOSE_DEADLINE) {
                            clock.set(BUDGET.toNanos());
                        }
                    }
                    return result;
                });
    }

    private static Object invoke(Object target, Method method, Object[] arguments) throws Throwable {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    private enum Fault {
        NONE, BEFORE_COMMIT, BEFORE_COMMIT_DEADLINE, COMMIT_SQL_ACKNOWLEDGEMENT,
        COMMIT_RUNTIME_ACKNOWLEDGEMENT, AFTER_COMMIT, AFTER_COMMIT_DEADLINE, CLOSE_DEADLINE,
        CLOSE_SQL, CLOSE_RUNTIME
    }

    private static final class Attempt {
        private final Fault fault;
        private final RuntimeException runtimeFailure = new IllegalStateException("simulated commit phase failure");
        private final SQLException sqlFailure = new SQLException("simulated lost commit acknowledgement", "08006");
        private final SQLException sqlCloseFailure = new SQLException("simulated failure after physical close", "08006");
        private final RuntimeException runtimeCloseFailure =
                new IllegalStateException("simulated unchecked failure after physical close");
        private final List<LegalRequiredSetAggregateReceipt> receipts = new ArrayList<>();
        private final List<Integer> completions = new ArrayList<>();
        private int borrows;
        private int commitCalls;
        private int rollbackCalls;
        private int afterCommit;
        private int closes;

        private Attempt(Fault fault) {
            this.fault = fault;
        }
    }
}
