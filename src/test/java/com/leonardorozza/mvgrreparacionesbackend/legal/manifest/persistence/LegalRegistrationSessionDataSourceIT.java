package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Suscripcion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Taller;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.User;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.EntityManagerFactoryInfo;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/** Real Hibernate/JPA and PostgreSQL, without Boot, repositories, authentication or test-only entities. */
class LegalRegistrationSessionDataSourceIT {
    private static final String ROLE = "ordenfix_registration_session_app_it";
    private static final String PASSWORD = "registration-session-app-test-only";
    private static final String ORIGINAL_NAME = "Session JDBC actor";
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_registration_session_jpa")
            .withUsername("ordenfix").withPassword("ordenfix");
    private static JdbcTemplate owner;
    private static long userId;
    private Harness harness;

    @BeforeAll
    static void migrateAndSeedWithAnOwnerWhileBothConsumersUseTheSameApplicationRole() {
        POSTGRES.start();
        LegalManifestPersistenceITSupport.migrateLatest(POSTGRES);
        owner = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        owner.execute("CREATE ROLE " + ROLE + " LOGIN PASSWORD '" + PASSWORD + "'");
        owner.execute("GRANT CONNECT ON DATABASE ordenfix_legal_registration_session_jpa TO " + ROLE);
        owner.execute("GRANT USAGE ON SCHEMA public TO " + ROLE);
        owner.execute("GRANT SELECT, UPDATE ON users, talleres, suscripciones TO " + ROLE);
        Long workshop = owner.queryForObject("""
                INSERT INTO talleres(nombre, activo, mostrar_en_resumen, secuencia_orden)
                VALUES ('Session JDBC workshop', true, true, 0) RETURNING id
                """, Long.class);
        userId = Objects.requireNonNull(owner.queryForObject("""
                INSERT INTO users(username, password, email, role, active, email_verificado, token_version, taller_id)
                VALUES (?, 'synthetic-unused-password-hash', 'jdbc-session@example.test', 'ADMIN', true, false, 0, ?)
                RETURNING id
                """, Long.class, ORIGINAL_NAME, workshop));
    }

    @BeforeEach
    void openOneManualJpaFactoryWithExternallyOwnedPools() throws Exception {
        harness = new Harness();
    }

    @AfterEach
    void closeFactoryProtectionAndThenTheOwnedPools() {
        if (harness != null) harness.close();
    }

    @AfterAll
    static void stopPostgres() {
        POSTGRES.stop();
    }

    @Test
    void bootstrapAndUnscopedJpaUseOnlyTheHistoricalDelegateAndPreserveConnectionIdentity() throws Exception {
        assertThat(harness.bootstrapHistoricalBorrows).isPositive();
        assertThat(harness.bootstrapDedicatedBorrows).isZero();
        assertThat(((EntityManagerFactoryInfo) harness.factory).getDataSource()).isSameAs(harness.source);
        assertThat(harness.manager.getDataSource()).isSameAs(harness.source);
        var before = durableRows();
        try (Connection connection = harness.source.getConnection()) {
            assertThat(connection).isSameAs(harness.attempt.lastHistoricalConnection);
        }
        Observation observation = harness.transaction(false, TransactionDefinition.ISOLATION_READ_COMMITTED)
                .execute(status -> {
                    EntityManager entityManager = harness.entityManager();
                    Observation current = harness.observe(entityManager);
                    assertThat(harness.readUser(entityManager).getUsername()).isEqualTo(ORIGINAL_NAME);
                    return current;
                });
        assertApplicationRole(observation);
        assertThat(observation.readOnly()).isEqualTo("off");
        assertThat(harness.attempt.dedicated).isEmpty();
        assertThat(harness.historicalPool.getConnectionTimeout()).isEqualTo(3_000);
        assertThat(harness.historicalPool.isAutoCommit()).isTrue();
        assertThat(harness.historicalPool.isReadOnly()).isFalse();
        assertThat(durableRows()).isEqualTo(before);
        harness.assertReleased();
    }

    @Test
    void scopedRequiresNewReadsCommittedStateAndRestoresTheOuterJpaContextAndHistoricalRoute() throws Exception {
        var before = durableRows();
        harness.transaction(false, TransactionDefinition.ISOLATION_REPEATABLE_READ).executeWithoutResult(outerStatus -> {
            EntityManager outer = harness.entityManager();
            Object outerResource = TransactionSynchronizationManager.getResource(harness.factory);
            Observation outerObservation = harness.observe(outer);
            User managed = harness.readUser(outer);
            managed.setUsername("Uncommitted outer session name");
            outer.flush();

            String currentName = harness.source.withinRegistrationBudget(harness.attempt.budget, budget ->
                    harness.transaction(true, TransactionDefinition.ISOLATION_READ_COMMITTED).execute(innerStatus -> {
                        EntityManager inner = harness.entityManager();
                        Observation innerObservation = harness.observe(inner);
                        assertApplicationRole(innerObservation);
                        assertThat(innerObservation.isolation()).isEqualTo("read committed");
                        assertThat(innerObservation.readOnly()).isEqualTo("on");
                        assertThat(innerObservation.autoCommit()).isFalse();
                        assertThat(innerObservation.pid()).isNotEqualTo(outerObservation.pid());
                        assertThat(TransactionSynchronizationManager.getResource(harness.factory)).isNotSameAs(outerResource);
                        String name = harness.readUser(inner).getUsername();
                        budget.check();
                        return name;
                    }));

            assertThat(currentName).isEqualTo(ORIGINAL_NAME);
            assertThat(TransactionSynchronizationManager.getResource(harness.factory)).isSameAs(outerResource);
            assertThat(harness.entityManager()).isSameAs(outer);
            assertThat(outer.find(User.class, userId)).isSameAs(managed);
            Observation restored = harness.observe(outer);
            assertThat(restored.pid()).isEqualTo(outerObservation.pid());
            assertThat(restored.isolation()).isEqualTo("repeatable read");
            assertThat(restored.readOnly()).isEqualTo("off");
            assertThat(outerStatus.isRollbackOnly()).isFalse();
            outerStatus.setRollbackOnly();
        });
        assertDedicatedCompletion(TransactionSynchronization.STATUS_COMMITTED, 1, 0);
        assertThat(harness.dedicatedMetrics.snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isZero();
        assertThat(harness.historicalMetrics.snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isEqualTo(1);
        assertThat(harness.historicalMetrics.snapshot().rollbacks()).isEqualTo(1);
        int previousHistoricalBorrows = harness.attempt.historical.size();
        try (Connection connection = harness.source.getConnection()) {
            assertThat(connection).isSameAs(harness.attempt.lastHistoricalConnection);
        }
        assertThat(harness.attempt.historical).hasSize(previousHistoricalBorrows + 1);
        assertThat(harness.attempt.dedicated).hasSize(1);
        assertThat(durableRows()).isEqualTo(before);
        harness.assertReleased();
    }

    @Test
    void twentyEightSecondsAlreadySpentLeaveTwoSecondsForRealJpaSqlAndFetch() {
        harness.start(Fault.NONE, 28);
        var before = durableRows();

        String name = harness.readScoped();

        assertThat(name).isEqualTo(ORIGINAL_NAME);
        assertThat(harness.attempt.observation.statementTimeout()).isEqualTo("2s");
        assertThat(harness.attempt.observation.readOnly()).isEqualTo("on");
        assertThat(harness.attempt.observation.isolation()).isEqualTo("read committed");
        assertApplicationRole(harness.attempt.observation);
        assertThat(harness.attempt.budget.remainingMillis()).isEqualTo(2_000);
        ConnectionEvents events = dedicated();
        assertThat(events.networkTimeouts).contains(2_000);
        assertThat(events.businessQueryTimeouts).contains(2);
        assertThat(events.businessRows).isEqualTo(1);
        assertDedicatedCompletion(TransactionSynchronization.STATUS_COMMITTED, 1, 0);
        assertNoSessionDml(before);
    }

    @Test
    void realLockTimeoutRemainsAnOperationalErrorWhenCleanupSucceeds() throws Exception {
        harness.start(Fault.NONE, 0);
        var before = durableRows();
        Throwable failure;
        try (Connection blocker = Objects.requireNonNull(owner.getDataSource()).getConnection()) {
            blocker.setAutoCommit(false);
            try {
                try (Statement lock = blocker.createStatement()) {
                    lock.execute("LOCK TABLE public.users IN ACCESS EXCLUSIVE MODE");
                }
                failure = catchThrowable(() -> harness.source.withinRegistrationBudget(harness.attempt.budget, budget ->
                        harness.transaction(true, TransactionDefinition.ISOLATION_READ_COMMITTED).execute(status -> {
                            EntityManager entityManager = harness.entityManager();
                            harness.attempt.observation = harness.observe(entityManager);
                            entityManager.unwrap(Session.class).doWork(connection -> {
                                try (Statement limits = connection.createStatement()) {
                                    limits.execute("SET LOCAL lock_timeout = '200ms'");
                                }
                            });
                            return harness.readUser(entityManager).getUsername();
                        })));
            } finally {
                blocker.rollback();
            }
        }
        assertThat(failure).isNotNull().isNotInstanceOf(LegalRegistrationSessionUnavailableException.class);
        assertThat(findSqlState(failure, "55P03")).isTrue();
        assertDedicatedCompletion(TransactionSynchronization.STATUS_ROLLED_BACK, 0, 1);
        assertThat(harness.attempt.budget.remainingMillis()).isEqualTo(30_000);
        assertNoSessionDml(before);
        LegalRegistrationBudget sameOwner = harness.attempt.budget;
        harness.newAttempt(Fault.NONE, sameOwner);
        assertThat(harness.readScoped()).isEqualTo(ORIGINAL_NAME);
        assertDedicatedCompletion(TransactionSynchronization.STATUS_COMMITTED, 1, 0);
        assertNoSessionDml(before);
    }

    @ParameterizedTest
    @EnumSource(value = Fault.class, names = {"RESULT_CLOSE", "STATEMENT_CLOSE", "CONNECTION_RETURN",
            "GET_MAX_ROWS", "GET_QUERY_TIMEOUT", "RESET_MAX_ROWS", "RESET_QUERY_TIMEOUT",
            "CONNECTION_READ_ONLY_RESET", "CONNECTION_CLEAR_WARNINGS"})
    void hibernateControlAndCleanupFailuresCannotEscapeAsASuccessfulSessionRead(Fault phase) {
        harness.start(phase, 0);
        var before = durableRows();

        Throwable failure = catchThrowable(harness::readScoped);

        assertThat(failure).isExactlyInstanceOf(LegalRegistrationSessionUnavailableException.class)
                .hasCause(harness.attempt.cleanupFailure);
        assertThat(harness.attempt.injections).isEqualTo(1);
        assertThat(dedicated().businessQueries).isEqualTo(1);
        assertThat(dedicated().businessRows).isEqualTo(1);
        assertThatThrownBy(harness.attempt.budget::check)
                .isExactlyInstanceOf(LegalRegistrationBudget.UnavailableException.class).hasCause(harness.attempt.cleanupFailure);
        boolean committed = phase == Fault.CONNECTION_RETURN || phase == Fault.CONNECTION_READ_ONLY_RESET
                || phase == Fault.CONNECTION_CLEAR_WARNINGS;
        if (committed) assertThat(harness.attempt.applicationReceivedRows).isTrue();
        assertDedicatedCompletion(committed ? TransactionSynchronization.STATUS_COMMITTED : TransactionSynchronization.STATUS_ROLLED_BACK,
                committed ? 1 : 0, committed ? 0 : 1);
        assertNoSessionDml(before);
        int borrows = harness.attempt.dedicated.size();
        assertThatThrownBy(harness::readScoped).isExactlyInstanceOf(LegalRegistrationSessionUnavailableException.class)
                .hasCause(harness.attempt.cleanupFailure);
        assertThat(harness.attempt.dedicated).hasSize(borrows);
        harness.assertReleased();
    }

    @Test
    void expiryAfterARealFetchRollsBackBeforeAnyResultIsReturned() {
        harness.start(Fault.FETCH_EXPIRY, 20);
        var before = durableRows();

        Throwable failure = catchThrowable(harness::readScoped);

        assertThat(failure).isExactlyInstanceOf(LegalRegistrationSessionUnavailableException.class).hasNoCause();
        assertThat(harness.attempt.injections).isEqualTo(1);
        assertThat(dedicated().businessQueries).isEqualTo(1);
        assertThat(dedicated().businessRows).isEqualTo(1);
        assertThat(harness.attempt.applicationReceivedRows).isFalse();
        assertDedicatedCompletion(TransactionSynchronization.STATUS_ROLLED_BACK, 0, 1);
        assertNoSessionDml(before);
    }

    @Test
    void expiryAfterHydrationButBeforeCallbackReturnPreventsCommitAndRollsBack() {
        harness.start(Fault.CALLBACK_EXPIRY, 20);
        var before = durableRows();

        Throwable failure = catchThrowable(harness::readScoped);

        assertThat(failure).isExactlyInstanceOf(LegalRegistrationSessionUnavailableException.class).hasNoCause();
        assertThat(harness.attempt.applicationReceivedRows).isTrue();
        assertThat(harness.attempt.injections).isEqualTo(1);
        assertDedicatedCompletion(TransactionSynchronization.STATUS_ROLLED_BACK, 0, 1);
        assertNoSessionDml(before);
    }

    @Test
    void expiryAfterTheDelegateCommitKeepsJpaCommittedButPreventsDelivery() {
        harness.start(Fault.COMMIT_EXPIRY, 20);
        var before = durableRows();

        Throwable failure = catchThrowable(harness::readScoped);

        assertThat(failure).isExactlyInstanceOf(LegalRegistrationSessionUnavailableException.class).hasNoCause();
        assertThat(harness.attempt.injections).isEqualTo(1);
        assertDedicatedCompletion(TransactionSynchronization.STATUS_COMMITTED, 1, 0);
        assertNoSessionDml(before);
    }

    @Test
    void lostCommitAcknowledgementShowsTheJpaNotificationWithoutInventingAPhysicalRollback() {
        harness.start(Fault.COMMIT_ACK, 0);
        var before = durableRows();

        Throwable failure = catchThrowable(harness::readScoped);

        // Hibernate7 wraps08006 in TransactionException/RollbackException; JpaTM translates it to
        // DataAccessResourceFailureException. APTM's RuntimeException path notifies ROLLED_BACK.
        // That notification does not undo the delegate COMMIT which already returned successfully.
        assertThat(failure).isExactlyInstanceOf(DataAccessResourceFailureException.class).hasCause(harness.attempt.ackFailure);
        assertThat(harness.attempt.injections).isEqualTo(1);
        assertDedicatedCompletion(TransactionSynchronization.STATUS_ROLLED_BACK, 1, 0);
        assertThat(harness.attempt.budget.remainingMillis()).isEqualTo(30_000);
        assertNoSessionDml(before);
    }

    private ConnectionEvents dedicated() {
        assertThat(harness.attempt.dedicated).hasSize(1);
        return harness.attempt.dedicated.getFirst();
    }

    private void assertDedicatedCompletion(int completion, int successfulCommits, int successfulRollbacks) {
        ConnectionEvents events = dedicated();
        assertThat(events.completions).containsExactly(completion);
        assertThat(events.afterCommits).isEqualTo(completion == TransactionSynchronization.STATUS_COMMITTED ? 1 : 0);
        assertThat(events.successfulCommits).isEqualTo(successfulCommits);
        assertThat(events.successfulRollbacks).isEqualTo(successfulRollbacks);
        assertThat(events.returns).isEqualTo(1);
        assertThat(harness.dedicatedMetrics.snapshot().commits()).isEqualTo(successfulCommits);
        assertThat(harness.dedicatedMetrics.snapshot().rollbacks()).isEqualTo(successfulRollbacks);
        harness.assertReleased();
    }

    private void assertNoSessionDml(Map<String, List<Map<String, Object>>> before) {
        assertThat(harness.dedicatedMetrics.snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isZero();
        assertThat(durableRows()).isEqualTo(before);
    }

    private static void assertApplicationRole(Observation observation) {
        assertThat(observation).isNotNull();
        assertThat(observation.currentRole()).isEqualTo(ROLE);
        assertThat(observation.sessionRole()).isEqualTo(ROLE);
    }

    private static Map<String, List<Map<String, Object>>> durableRows() {
        return Map.of(
                "users", owner.queryForList("SELECT id, xmin::text, username, email, active, taller_id, token_version FROM users ORDER BY id"),
                "talleres", owner.queryForList("SELECT id, xmin::text, nombre, activo FROM talleres ORDER BY id"),
                "suscripciones", owner.queryForList("SELECT id, xmin::text, taller_id, plan, estado FROM suscripciones ORDER BY id"));
    }

    private static boolean findSqlState(Throwable failure, String state) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql && state.equals(sql.getSQLState())) return true;
        }
        return false;
    }

    private static final class Harness implements AutoCloseable {
        final AtomicLong clock = new AtomicLong();
        final HikariDataSource historicalPool = pool("registration-session-history-it", 3_000);
        final HikariDataSource dedicatedPool = pool("registration-session-dedicated-it", 1_000);
        final LegalJdbcMetricsSupport historicalMetrics = LegalJdbcMetricsSupport.instrument(historicalPool, Duration.ZERO);
        final LegalJdbcMetricsSupport dedicatedMetrics = LegalJdbcMetricsSupport.instrument(dedicatedPool, Duration.ZERO);
        final ObservedDataSource historical = new ObservedDataSource(this, historicalMetrics.dataSource(), false);
        final ObservedDataSource dedicated = new ObservedDataSource(this, dedicatedMetrics.dataSource(), true);
        final LegalRegistrationSessionDataSource source = new LegalRegistrationSessionDataSource(historical, dedicated);
        final LocalContainerEntityManagerFactoryBean factoryBean = new LocalContainerEntityManagerFactoryBean();
        final EntityManagerFactory factory;
        final JpaTransactionManager manager;
        int bootstrapHistoricalBorrows;
        int bootstrapDedicatedBorrows;
        Attempt attempt;

        Harness() throws Exception {
            try {
                factoryBean.setPersistenceUnitName("registration-session-jdbc-it");
                factoryBean.setDataSource(source);
                factoryBean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
                factoryBean.setManagedTypes(PersistenceManagedTypes.of(User.class.getName(), Taller.class.getName(), Suscripcion.class.getName()));
                factoryBean.setJpaPropertyMap(Map.of(
                        "hibernate.hbm2ddl.auto", "validate",
                        "hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect",
                        "hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl",
                        "hibernate.connection.provider_disables_autocommit", "false",
                        "hibernate.cache.use_second_level_cache", "false",
                        "hibernate.cache.use_query_cache", "false",
                        "hibernate.show_sql", "false"));
                factoryBean.afterPropertiesSet();
                factory = Objects.requireNonNull(factoryBean.getObject());
                manager = new JpaTransactionManager(factory);
                manager.setRollbackOnCommitFailure(false);
                // Warm the supplied pool independently before the 2s-owner test; routing still opens
                // its own measured lease, and the pool retains its externally configured 1s bound.
                try (Connection connection = dedicatedPool.getConnection(); Statement statement = connection.createStatement();
                     ResultSet result = statement.executeQuery("SELECT current_user")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo(ROLE);
                }
                assertThat(dedicatedPool.getConnectionTimeout()).isEqualTo(1_000);
                assertThat(historicalPool.getUsername()).isEqualTo(dedicatedPool.getUsername()).isEqualTo(ROLE);
                start(Fault.NONE, 0);
            } catch (Throwable failure) {
                for (AutoCloseable resource : List.<AutoCloseable>of(factoryBean::destroy, source, historicalPool, dedicatedPool)) {
                    try { resource.close(); }
                    catch (Throwable cleanup) {
                        if (cleanup != failure) failure.addSuppressed(cleanup);
                    }
                }
                throw failure;
            }
        }

        void start(Fault fault, long secondsSpent) {
            clock.set(0);
            var budget = LegalRegistrationBudget.start(clock::get);
            clock.set(Duration.ofSeconds(secondsSpent).toNanos());
            newAttempt(fault, budget);
        }

        void newAttempt(Fault fault, LegalRegistrationBudget budget) {
            attempt = new Attempt(fault, budget);
            historicalMetrics.reset();
            dedicatedMetrics.reset();
        }

        TransactionTemplate transaction(boolean readOnly, int isolation) {
            var transaction = new TransactionTemplate(manager);
            transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            transaction.setIsolationLevel(isolation);
            transaction.setReadOnly(readOnly);
            transaction.setTimeout(25);
            return transaction;
        }

        EntityManager entityManager() {
            return Objects.requireNonNull(EntityManagerFactoryUtils.getTransactionalEntityManager(factory));
        }

        User readUser(EntityManager entityManager) {
            return entityManager.createQuery("select u from User u where u.id = :id", User.class)
                    .setParameter("id", userId).getSingleResult();
        }

        String readScoped() {
            return source.withinRegistrationBudget(attempt.budget, budget ->
                    transaction(true, TransactionDefinition.ISOLATION_READ_COMMITTED).execute(status -> {
                        EntityManager entityManager = entityManager();
                        attempt.observation = observe(entityManager);
                        String name = readUser(entityManager).getUsername();
                        attempt.applicationReceivedRows = true;
                        if (attempt.fault == Fault.CALLBACK_EXPIRY) expire();
                        // The future caller must observe control/cleanup failure before its next phase.
                        // Here that phase is returning the queried data; K/JWT are outside this IT.
                        budget.check();
                        return name;
                    }));
        }

        Observation observe(EntityManager entityManager) {
            return entityManager.unwrap(Session.class).doReturningWork(connection -> {
                try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("""
                        SELECT pg_backend_pid(), current_user, session_user,
                               current_setting('transaction_isolation'), current_setting('transaction_read_only'),
                               current_setting('statement_timeout')
                        """)) {
                    assertThat(result.next()).isTrue();
                    return new Observation(result.getInt(1), result.getString(2), result.getString(3),
                            result.getString(4), result.getString(5), result.getString(6), connection.getAutoCommit());
                }
            });
        }

        void expire() {
            attempt.injections++;
            clock.set(30_000_000_000L);
        }

        void injectCleanup() throws SQLException {
            attempt.injections++;
            throw attempt.cleanupFailure;
        }

        void assertReleased() {
            assertThat(historicalPool.getHikariPoolMXBean().getActiveConnections()).isZero();
            assertThat(dedicatedPool.getHikariPoolMXBean().getActiveConnections()).isZero();
            assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
        }

        @Override public void close() {
            try { factoryBean.destroy(); }
            finally {
                try { source.close(); }
                finally {
                    try { historicalPool.close(); }
                    finally { dedicatedPool.close(); }
                }
            }
        }

        private static HikariDataSource pool(String name, long borrowMillis) {
            var config = new HikariConfig();
            config.setPoolName(name);
            config.setJdbcUrl(POSTGRES.getJdbcUrl());
            config.setUsername(ROLE);
            config.setPassword(PASSWORD);
            config.setDriverClassName(POSTGRES.getDriverClassName());
            config.setMaximumPoolSize(2);
            config.setMinimumIdle(0);
            config.setConnectionTimeout(borrowMillis);
            config.setValidationTimeout(1_000);
            config.setInitializationFailTimeout(-1);
            config.setAutoCommit(true);
            config.setReadOnly(false);
            config.addDataSourceProperty("connectTimeout", "1");
            config.addDataSourceProperty("socketTimeout", "6");
            config.addDataSourceProperty("cancelSignalTimeout", "1");
            return new HikariDataSource(config);
        }
    }

    private static final class ObservedDataSource extends AbstractDataSource {
        private final Harness harness;
        private final DataSource delegate;
        private final boolean dedicated;

        ObservedDataSource(Harness harness, DataSource delegate, boolean dedicated) {
            this.harness = harness;
            this.delegate = delegate;
            this.dedicated = dedicated;
        }

        @Override public Connection getConnection() throws SQLException {
            Connection connection = delegate.getConnection();
            if (harness.attempt == null) {
                if (dedicated) harness.bootstrapDedicatedBorrows++;
                else harness.bootstrapHistoricalBorrows++;
                return connection;
            }
            var events = new ConnectionEvents();
            (dedicated ? harness.attempt.dedicated : harness.attempt.historical).add(events);
            Connection observed = instrument(connection, events);
            if (!dedicated) harness.attempt.lastHistoricalConnection = observed;
            return observed;
        }

        @Override public Connection getConnection(String username, String password) throws SQLException {
            return delegate.getConnection(username, password);
        }

        private Connection instrument(Connection connection, ConnectionEvents events) {
            AtomicBoolean registered = new AtomicBoolean();
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, arguments) -> {
                        if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, arguments);
                        if (TransactionSynchronizationManager.isSynchronizationActive() && registered.compareAndSet(false, true)) {
                            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                                @Override public void afterCommit() { events.afterCommits++; }
                                @Override public void afterCompletion(int status) { events.completions.add(status); }
                            });
                        }
                        String name = method.getName();
                        if (name.equals("setNetworkTimeout")) events.networkTimeouts.add((Integer) arguments[1]);
                        Object result = invoke(connection, method, arguments);
                        if (name.equals("commit")) {
                            events.successfulCommits++;
                            if (dedicated && harness.attempt.fault == Fault.COMMIT_EXPIRY) harness.expire();
                            if (dedicated && harness.attempt.fault == Fault.COMMIT_ACK) {
                                harness.attempt.injections++;
                                throw harness.attempt.ackFailure;
                            }
                        }
                        if (name.equals("rollback") && method.getParameterCount() == 0) events.successfulRollbacks++;
                        // These are real post-commit cleanup calls: DataSourceUtils absorbs reset
                        // failures; Hibernate always clears connection warnings during release.
                        // Invoke the delegate first and inject once, after a real application row.
                        if (dedicated && events.successfulCommits == 1 && events.businessRows == 1
                                && harness.attempt.injections == 0
                                && ((name.equals("setReadOnly") && Boolean.FALSE.equals(arguments[0])
                                && harness.attempt.fault == Fault.CONNECTION_READ_ONLY_RESET)
                                || (name.equals("clearWarnings") && harness.attempt.fault == Fault.CONNECTION_CLEAR_WARNINGS))) {
                            harness.injectCleanup();
                        }
                        if (name.equals("close")) {
                            events.returns++;
                            if (dedicated && harness.attempt.fault == Fault.CONNECTION_RETURN) harness.injectCleanup();
                        }
                        if (dedicated && name.equals("prepareStatement") && arguments[0] instanceof String sql
                                && result instanceof PreparedStatement statement && isUserQuery(sql)) {
                            if (harness.attempt.fault == Fault.RESET_MAX_ROWS) statement.setMaxRows(1);
                            return instrumentStatement(statement, events);
                        }
                        return result;
                    });
        }

        private PreparedStatement instrumentStatement(PreparedStatement statement, ConnectionEvents events) {
            AtomicBoolean resultClosed = new AtomicBoolean();
            return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, (proxy, method, arguments) -> {
                        if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, arguments);
                        String name = method.getName();
                        boolean cleanup = resultClosed.get() && harness.attempt.injections == 0;
                        if (cleanup && ((name.equals("getMaxRows") && harness.attempt.fault == Fault.GET_MAX_ROWS)
                                || (name.equals("getQueryTimeout") && harness.attempt.fault == Fault.GET_QUERY_TIMEOUT))) {
                            harness.injectCleanup();
                        }
                        if (name.equals("setQueryTimeout")) events.businessQueryTimeouts.add((Integer) arguments[0]);
                        Object result = invoke(statement, method, arguments);
                        if (cleanup && ((name.equals("setMaxRows") && Integer.valueOf(0).equals(arguments[0])
                                && harness.attempt.fault == Fault.RESET_MAX_ROWS)
                                || (name.equals("setQueryTimeout") && Integer.valueOf(0).equals(arguments[0])
                                && harness.attempt.fault == Fault.RESET_QUERY_TIMEOUT)
                                || (name.equals("close") && harness.attempt.fault == Fault.STATEMENT_CLOSE))) {
                            harness.injectCleanup();
                        }
                        if (name.equals("executeQuery")) {
                            events.businessQueries++;
                            return instrumentRows((ResultSet) result, events, resultClosed);
                        }
                        return result;
                    });
        }

        private ResultSet instrumentRows(ResultSet rows, ConnectionEvents events, AtomicBoolean closed) {
            return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[]{ResultSet.class},
                    (proxy, method, arguments) -> {
                        if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, arguments);
                        Object result = invoke(rows, method, arguments);
                        if (method.getName().equals("next") && Boolean.TRUE.equals(result)) {
                            events.businessRows++;
                            if (harness.attempt.fault == Fault.FETCH_EXPIRY && harness.attempt.injections == 0) harness.expire();
                        }
                        if (method.getName().equals("close")) {
                            closed.set(true);
                            if (harness.attempt.fault == Fault.RESULT_CLOSE && harness.attempt.injections == 0) harness.injectCleanup();
                        }
                        return result;
                    });
        }

        private static boolean isUserQuery(String sql) {
            String normalized = sql.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
            return normalized.startsWith("select ") && (normalized.contains(" from users ") || normalized.contains(" from public.users "));
        }
    }

    private static Object invoke(Object target, Method method, Object[] arguments) throws Throwable {
        try { return method.invoke(target, arguments); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    private static Object objectMethod(Object proxy, Method method, Object[] arguments) {
        return switch (method.getName()) {
            case "equals" -> proxy == arguments[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "RegistrationSessionJpaObservation";
            default -> throw new IllegalStateException("Unsupported Object method");
        };
    }

    private enum Fault {
        NONE, RESULT_CLOSE, STATEMENT_CLOSE, CONNECTION_RETURN, GET_MAX_ROWS, GET_QUERY_TIMEOUT,
        RESET_MAX_ROWS, RESET_QUERY_TIMEOUT, CONNECTION_READ_ONLY_RESET, CONNECTION_CLEAR_WARNINGS,
        FETCH_EXPIRY, CALLBACK_EXPIRY, COMMIT_EXPIRY, COMMIT_ACK
    }

    private static final class Attempt {
        final Fault fault;
        final LegalRegistrationBudget budget;
        final SQLException cleanupFailure = new SQLException("synthetic JPA resource control or cleanup failure", "08006");
        final SQLException ackFailure = new SQLException("synthetic acknowledgement loss after the delegate commit", "08006");
        final List<ConnectionEvents> historical = new ArrayList<>();
        final List<ConnectionEvents> dedicated = new ArrayList<>();
        Connection lastHistoricalConnection;
        Observation observation;
        int injections;
        boolean applicationReceivedRows;

        Attempt(Fault fault, LegalRegistrationBudget budget) {
            this.fault = fault;
            this.budget = budget;
        }
    }

    private static final class ConnectionEvents {
        final List<Integer> completions = new ArrayList<>();
        final List<Integer> networkTimeouts = new ArrayList<>();
        final List<Integer> businessQueryTimeouts = new ArrayList<>();
        int successfulCommits;
        int successfulRollbacks;
        int returns;
        int afterCommits;
        int businessQueries;
        int businessRows;
    }

    private record Observation(int pid, String currentRole, String sessionRole, String isolation,
                               String readOnly, String statementTimeout, boolean autoCommit) { }
}
