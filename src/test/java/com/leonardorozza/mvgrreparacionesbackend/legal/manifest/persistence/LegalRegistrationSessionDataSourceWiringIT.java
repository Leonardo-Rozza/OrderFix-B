package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Suscripcion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Taller;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.User;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.UserRepository;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.Ordered;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.EntityManagerFactoryInfo;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/** Boot's real DataSource/JPA auto-configuration with an explicit, otherwise inert legal module. */
class LegalRegistrationSessionDataSourceWiringIT {
    private static final String ROLE = "ordenfix_session_wiring_app_it";
    private static final String PASSWORD = "session-wiring-app-test-only";
    private static final String OTHER_ROLE = "ordenfix_session_wiring_other_it";
    private static final String OTHER_PASSWORD = "session-wiring-other+test%only";
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_session_wiring")
            .withUsername("ordenfix").withPassword("ordenfix");
    private static JdbcTemplate owner;
    private static long userId;
    private static long workshopId;

    @BeforeAll static void migrateAndSeedIndependentApplicationCredentials() {
        POSTGRES.start();
        LegalManifestPersistenceITSupport.migrateLatest(POSTGRES);
        owner = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        for (String[] credentials : List.of(new String[]{ROLE, PASSWORD}, new String[]{OTHER_ROLE, OTHER_PASSWORD})) {
            owner.execute("CREATE ROLE " + credentials[0] + " LOGIN PASSWORD '" + credentials[1] + "'");
            owner.execute("GRANT CONNECT ON DATABASE ordenfix_session_wiring TO " + credentials[0]);
            owner.execute("GRANT USAGE ON SCHEMA public TO " + credentials[0]);
            owner.execute("GRANT SELECT, UPDATE ON users, talleres, suscripciones TO " + credentials[0]);
        }
        workshopId = Objects.requireNonNull(owner.queryForObject(
                "INSERT INTO talleres(nombre,activo) VALUES ('Wiring workshop',true) RETURNING id", Long.class));
        userId = Objects.requireNonNull(owner.queryForObject("""
                INSERT INTO users(username,email,password,role,active,email_verificado,token_version,taller_id)
                VALUES ('Wiring actor','wiring@example.test','synthetic-unused-hash','ADMIN',true,false,0,?) RETURNING id
                """, Long.class, workshopId));
    }

    @AfterAll static void stopPostgres() { POSTGRES.stop(); }

    @Test void withoutExplicitImportBootKeepsItsSingleHistoricalHikariDataSource() {
        try (Harness h = new Harness(false, Map.of())) {
            h.refresh();
            assertThat(h.context.getBeansOfType(LegalRegistrationSessionResources.class)).isEmpty();
            assertThat(h.source()).isSameAs(h.probe.historical).isExactlyInstanceOf(HikariDataSource.class);
            h.assertOneIdentity();
            assertThat(h.read().role()).isEqualTo(ROLE);
            assertThat(h.probe.historical.getConnectionTimeout()).isEqualTo(3_000);
        }
    }

    @Test void bootJpaAndJdbcShareOneFinalIdentityWhileOnlyScopedWorkBorrowsThePrivatePool() {
        var before = durableRows();
        int loginTimeout;
        try (Harness h = new Harness(true, Map.of())) {
            h.refresh(); h.assertOneIdentity();
            loginTimeout = DriverManager.getLoginTimeout(); // Boot has now started its historical driver-backed pool.
            assertThat(h.source()).isExactlyInstanceOf(LegalRegistrationSessionDataSource.class);
            assertThat(h.pool().getHikariPoolMXBean().getTotalConnections()).isZero();
            assertThat(h.probe.historical.getHikariPoolMXBean().getTotalConnections()).isPositive();
            Observation historical = h.read();
            assertThat(h.pool().getHikariPoolMXBean().getTotalConnections()).isZero();
            Observation dedicated = h.scoped();
            assertThat(dedicated.role()).isEqualTo(historical.role()).isEqualTo(ROLE);
            assertThat(dedicated.database()).isEqualTo(historical.database()).isEqualTo(POSTGRES.getDatabaseName());
            assertThat(dedicated.pid()).isNotEqualTo(historical.pid());
            assertThat(dedicated.isolation()).isEqualTo("read committed");
            assertThat(dedicated.readOnly()).isEqualTo("on");
            assertThat(dedicated.autoCommit()).isFalse();
            assertThat(dedicated.statementTimeout()).isEqualTo("5s");
            assertThat(dedicated.networkTimeout()).isEqualTo(6_000);
            assertThat(historical.statementTimeout()).isEqualTo("0");
            h.assertPrivateLimits(); h.assertReturned();
            assertThat(h.probe.historical.getConnectionTimeout()).isEqualTo(3_000);
            assertThat(h.probe.historical.getDataSourceProperties().getProperty("socketTimeout")).isEqualTo("11");
        }
        assertThat(DriverManager.getLoginTimeout()).isEqualTo(loginTimeout);
        assertThat(durableRows()).isEqualTo(before);
    }

    @Test void hikariOverridesWinOverTheBasePropertiesForBothRealConnections() {
        try (Harness h = new Harness(true, Map.of(
                "spring.datasource.url", "jdbc:postgresql://127.0.0.1:1/base_decoy",
                "spring.datasource.username", "base-decoy-user", "spring.datasource.password", "base-decoy-password",
                "spring.datasource.hikari.jdbc-url", urlWith("ApplicationName=hikari-effective-it"),
                "spring.datasource.hikari.username", OTHER_ROLE, "spring.datasource.hikari.password", OTHER_PASSWORD))) {
            h.refresh();
            assertThat(h.probe.historical.getUsername()).isEqualTo(OTHER_ROLE);
            assertThat(h.read().role()).isEqualTo(OTHER_ROLE);
            Observation scoped = h.scoped();
            assertThat(scoped.role()).isEqualTo(OTHER_ROLE);
            assertThat(scoped.applicationName()).isEqualTo("hikari-effective-it");
            assertThat(h.probe.historical.getJdbcUrl()).contains("ApplicationName=hikari-effective-it");
        }
    }

    @Test void connectionDetailsAreAppliedBeforeThePostInitializationSnapshot() {
        try (Harness h = new Harness(true, Map.of(
                "spring.datasource.hikari.jdbc-url", "jdbc:postgresql://127.0.0.1:1/hikari_decoy",
                "spring.datasource.hikari.username", "hikari-decoy", "spring.datasource.hikari.password", "hikari-decoy-password"),
                ConnectionDetailsConfiguration.class)) {
            h.probe.details = details(urlWith("ApplicationName=connection-details-it"), OTHER_ROLE, OTHER_PASSWORD);
            h.refresh();
            assertThat(h.probe.historical.getUsername()).isEqualTo(OTHER_ROLE);
            assertThat(h.probe.historical.getJdbcUrl()).contains("ApplicationName=connection-details-it");
            assertThat(h.read().role()).isEqualTo(OTHER_ROLE);
            Observation scoped = h.scoped();
            assertThat(scoped.role()).isEqualTo(OTHER_ROLE);
            assertThat(scoped.applicationName()).isEqualTo("connection-details-it");
        }
    }

    @Test void urlCredentialsOverrideConflictingHikariAndDriverPropertiesWithoutMutatingTheOriginal() {
        String url = urlWith("user=" + encode(OTHER_ROLE) + "&password=" + encode(OTHER_PASSWORD));
        try (Harness h = new Harness(true, Map.of(
                "spring.datasource.hikari.jdbc-url", url,
                "spring.datasource.hikari.username", "hikari-url-decoy", "spring.datasource.hikari.password", "hikari-url-decoy-password",
                "spring.datasource.hikari.data-source-properties.user", "driver-url-decoy",
                "spring.datasource.hikari.data-source-properties.password", "driver-url-decoy-password"))) {
            h.refresh();
            Properties original = copy(h.probe.historical.getDataSourceProperties());
            assertThat(h.read().role()).isEqualTo(OTHER_ROLE);
            assertThat(h.scoped().role()).isEqualTo(OTHER_ROLE);
            assertThat(h.probe.historical.getUsername()).isEqualTo("hikari-url-decoy");
            assertThat(h.probe.historical.getJdbcUrl()).isEqualTo(url);
            assertThat(h.probe.historical.getDataSourceProperties()).isEqualTo(original);
            assertThat(h.resources().toString()).doesNotContain(OTHER_PASSWORD, url, OTHER_ROLE);
        }
    }

    @Test void driverPropertyCredentialsAreUsedWhenTheEffectiveHikariCredentialsAreNull() {
        try (Harness h = new Harness(true, Map.of(
                "spring.datasource.hikari.data-source-properties.user", OTHER_ROLE,
                "spring.datasource.hikari.data-source-properties.password", OTHER_PASSWORD), ConnectionDetailsConfiguration.class)) {
            h.probe.details = details(POSTGRES.getJdbcUrl(), null, null);
            h.refresh();
            assertThat(h.probe.historical.getUsername()).isNull();
            assertThat(h.probe.historical.getPassword()).isNull();
            assertThat(h.read().role()).isEqualTo(OTHER_ROLE);
            assertThat(h.scoped().role()).isEqualTo(OTHER_ROLE);
        }
    }

    @Test void urlTimeoutsCannotOverridePrivateBoundsOrChangeTheHistoricalDriverConfiguration() {
        String url = urlWith("connectTimeout=9&loginTimeout=10&socketTimeout=11&cancelSignalTimeout=8&queryTimeout=12");
        int globalLogin;
        try (Harness h = new Harness(true, Map.of("spring.datasource.hikari.jdbc-url", url))) {
            h.refresh(); h.assertPrivateLimits();
            globalLogin = DriverManager.getLoginTimeout(); // Exclude the historical Hikari bootstrap from this assertion.
            Observation historical = h.read();
            Observation scoped = h.scoped();
            assertThat(historical.networkTimeout()).isEqualTo(11_000);
            assertThat(scoped.networkTimeout()).isEqualTo(6_000);
            assertThat(scoped.statementTimeout()).isEqualTo("5s");
            assertThat(h.probe.historical.getJdbcUrl()).isEqualTo(url);
            assertThat(DriverManager.getLoginTimeout()).isEqualTo(globalLogin);
        }
        assertThat(DriverManager.getLoginTimeout()).isEqualTo(globalLogin);
    }

    @Test void autoCommitFalseAndBaseStateArePreservedWhileJpaStartsOneNewReadOnlyReadCommittedTransaction() {
        try (Harness h = new Harness(true, Map.of(
                "spring.datasource.hikari.auto-commit", "false", "spring.datasource.hikari.read-only", "true",
                "spring.datasource.hikari.transaction-isolation", "TRANSACTION_REPEATABLE_READ",
                "spring.datasource.hikari.catalog", POSTGRES.getDatabaseName(), "spring.datasource.hikari.schema", "public",
                "spring.datasource.hikari.data-source-properties.currentSchema", "pg_catalog"))) {
            h.refresh();
            assertThat(h.probe.historical.isAutoCommit()).isFalse();
            assertThat(h.pool().isAutoCommit()).isFalse();
            assertThat(h.pool().isReadOnly()).isTrue();
            assertThat(h.pool().getTransactionIsolation()).isEqualTo("TRANSACTION_REPEATABLE_READ");
            assertThat(h.pool().getCatalog()).isEqualTo(POSTGRES.getDatabaseName());
            assertThat(h.pool().getSchema()).isEqualTo("public");
            assertThat(h.pool().getHikariPoolMXBean().getTotalConnections()).isZero();
            var before = durableRows();
            // No test warm-up: JPA must prepare RC on the first physical connection created by the pool.
            Observation first = h.scoped();
            h.assertReturned();
            h.pool().getHikariPoolMXBean().softEvictConnections();
            Observation replacement = h.scoped();
            assertThat(replacement.pid()).isNotEqualTo(first.pid());
            for (Observation scoped : List.of(first, replacement)) {
                assertThat(scoped.autoCommit()).isFalse();
                assertThat(scoped.isolation()).isEqualTo("read committed");
                assertThat(scoped.readOnly()).isEqualTo("on");
                assertThat(scoped.schema()).isEqualTo("public");
                assertThat(scoped.searchPath()).isEqualTo("public");
                assertThat(scoped.role()).isEqualTo(ROLE);
                assertThat(scoped.name()).isEqualTo("Wiring actor");
            }
            assertThat(h.probe.historical.getSchema()).isEqualTo("public");
            assertThat(h.probe.historical.getDataSourceProperties().getProperty("currentSchema")).isEqualTo("pg_catalog");
            assertThat(durableRows()).isEqualTo(before);
            h.assertReturned();
        }
    }

    @Test void aConsumedOwnerBoundsTheActualJpaConnectionWithoutStartingAnotherBudget() {
        try (Harness h = new Harness(true, Map.of())) {
            h.refresh();
            // Warm before consuming the owner; this test isolates adoption and actual SQL/connection bounds.
            h.scoped();
            AtomicLong clock = new AtomicLong();
            var budget = LegalRegistrationBudget.start(clock::get);
            clock.set(Duration.ofSeconds(28).toNanos());
            Observation scoped = h.resources().withinRegistrationBudget(budget, same -> {
                assertThat(same).isSameAs(budget);
                return h.read();
            });
            assertThat(scoped.statementTimeout()).isEqualTo("2s");
            assertThat(scoped.networkTimeout()).isEqualTo(2_000);
            assertThat(budget.remainingMillis()).isEqualTo(2_000);
            h.assertReturned();
        }
    }

    @Test void aNonOrderedObserverStaysOutsideTheRouterAndSeesTheRealJpaCommit() {
        try (Harness h = new Harness(true, Map.of(), OuterObservationConfiguration.class)) {
            h.refresh(); h.assertOneIdentity();
            assertThat(h.source()).isSameAs(h.probe.observer).isExactlyInstanceOf(ObservedDataSource.class);
            assertThat(h.probe.observer.delegate).isExactlyInstanceOf(LegalRegistrationSessionDataSource.class);
            h.probe.observer.reset();
            Observation scoped = h.scoped();
            assertThat(scoped.role()).isEqualTo(ROLE);
            assertThat(h.probe.observer.borrows).isEqualTo(1);
            assertThat(h.probe.observer.commits).isEqualTo(1);
            assertThat(h.probe.observer.rollbacks).isZero();
            assertThat(h.probe.observer.returns).isEqualTo(1);
            h.assertReturned();
        }
    }

    @Test void pendingCompositionRejectsWorkBeforeTheMandatoryGateMakesTheHolderReady() {
        try (Harness h = new Harness(true, Map.of(), PendingObservationConfiguration.class)) {
            h.refresh();
            assertThat(h.probe.pendingRejections).isEqualTo(1);
            assertThat(h.probe.pendingFailure).isExactlyInstanceOf(LegalRegistrationSessionUnavailableException.class);
            assertThat(h.probe.pendingWork).isZero();
            assertThat(h.scoped().role()).isEqualTo(ROLE);
        }
    }

    @Test void requiresNewSuspendsTheHistoricalJpaResourceAndRestoresTheExactOuterContext() {
        try (Harness h = new Harness(true, Map.of())) {
            h.refresh();
            var before = durableRows();
            h.transaction(false, TransactionDefinition.ISOLATION_REPEATABLE_READ).executeWithoutResult(status -> {
                var outerEntityManager = h.entityManager();
                Object outerResource = TransactionSynchronizationManager.getResource(h.factory());
                Observation outer = h.observe();
                User user = outerEntityManager.find(User.class, userId);
                user.setUsername("Uncommitted outer wiring name"); outerEntityManager.flush();
                Observation scoped = h.scoped();
                assertThat(scoped.name()).isEqualTo("Wiring actor");
                assertThat(scoped.pid()).isNotEqualTo(outer.pid());
                assertThat(h.entityManager()).isSameAs(outerEntityManager);
                assertThat(TransactionSynchronizationManager.getResource(h.factory())).isSameAs(outerResource);
                assertThat(h.observe().pid()).isEqualTo(outer.pid());
                assertThat(user.getUsername()).isEqualTo("Uncommitted outer wiring name");
                status.setRollbackOnly();
            });
            h.assertReturned(); assertThat(durableRows()).isEqualTo(before);
        }
    }

    @Test void separateContextsOwnSeparateRoutersAndPoolsAndClosingOneDoesNotBreakTheOther() {
        try (Harness first = new Harness(true, Map.of()); Harness second = new Harness(true, Map.of())) {
            first.refresh(); second.refresh();
            assertThat(first.resources()).isNotSameAs(second.resources());
            assertThat(first.source()).isNotSameAs(second.source());
            assertThat(first.pool()).isNotSameAs(second.pool());
            assertThat(first.probe.historical).isNotSameAs(second.probe.historical);
            first.scoped(); second.scoped();
            HikariDataSource firstPrivate = first.pool();
            first.close();
            assertThat(firstPrivate.isClosed()).isTrue();
            assertThat(first.probe.historical.isClosed()).isTrue();
            assertThat(second.pool().isClosed()).isFalse();
            assertThat(second.scoped().role()).isEqualTo(ROLE);
        }
    }

    @Test void holderClosesOnlyItsResourcesAndBootStillOwnsTheHistoricalPool() {
        Harness h = new Harness(true, Map.of());
        HikariDataSource dedicated;
        try (h) {
            h.refresh(); h.scoped();
            dedicated = h.pool();
            h.resources().close(); h.resources().close();
            assertThat(dedicated.isClosed()).isTrue();
            assertThat(h.probe.historical.isClosed()).isFalse();
            assertThatThrownBy(h::scoped).isExactlyInstanceOf(LegalRegistrationSessionUnavailableException.class);
            assertThat(h.read().role()).isEqualTo(ROLE);
        }
        assertThat(h.probe.historical.isClosed()).isTrue();
        assertThat(dedicated.isClosed()).isTrue();
    }

    @Test void aLaterRefreshFailureClosesBothRegisteredOwnersAfterRealBootstrapAndScopedIo() {
        var before = durableRows();
        try (Harness h = new Harness(true, Map.of(), LateFailureConfiguration.class)) {
            Throwable failure = catchThrowable(h::refresh);
            assertThat(failure).hasRootCause(h.probe.lateFailure);
            assertThat(h.probe.lateQuery).isEqualTo(ROLE);
            assertThat(h.probe.failedPrivatePool).isNotNull();
            assertThat(h.probe.failedPrivatePool.isClosed()).isTrue();
            assertThat(h.probe.historical.isClosed()).isTrue();
            assertThat(h.probe.failedResources).isNotNull();
            assertThatThrownBy(() -> h.probe.failedResources.withinRegistrationBudget(LegalRegistrationBudget.start(), ignored -> true))
                    .isExactlyInstanceOf(LegalRegistrationSessionUnavailableException.class);
        }
        assertThat(durableRows()).isEqualTo(before);
    }

    @Test void compositionRejectionIsRetainedUntilTheOriginalPoolHasRegisteredItsDestruction() {
        try (Harness h = new Harness(true, Map.of("spring.datasource.hikari.connection-init-sql", "SELECT 1"))) {
            Throwable failure = catchThrowable(h::refresh);
            assertThat(failure).isNotNull();
            assertThat(hasCause(failure, LegalRegistrationSessionUnavailableException.class)).isTrue();
            assertThat(h.probe.historical).isNotNull();
            assertThat(h.probe.historical.isClosed()).isTrue();
            assertThat(h.probe.historical.getConnectionInitSql()).isEqualTo("SELECT 1");
            assertThat(rootCause(failure)).isExactlyInstanceOf(IllegalArgumentException.class);
            assertThat(rootCause(failure).getMessage()).doesNotContain("SELECT 1", PASSWORD, POSTGRES.getJdbcUrl());
        }
    }

    private static final class Harness implements AutoCloseable {
        final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        final Probe probe = new Probe();
        Harness(boolean composed, Map<String, String> overrides, Class<?>... extraConfigurations) {
            Map<String, String> properties = new LinkedHashMap<>();
            properties.put("spring.datasource.url", POSTGRES.getJdbcUrl());
            properties.put("spring.datasource.username", ROLE); properties.put("spring.datasource.password", PASSWORD);
            properties.put("spring.datasource.driver-class-name", "org.postgresql.Driver");
            properties.put("spring.datasource.hikari.maximum-pool-size", "2");
            properties.put("spring.datasource.hikari.minimum-idle", "0");
            properties.put("spring.datasource.hikari.connection-timeout", "3000");
            properties.put("spring.datasource.hikari.validation-timeout", "1000");
            properties.put("spring.datasource.hikari.initialization-fail-timeout", "-1");
            properties.put("spring.datasource.hikari.data-source-properties.socketTimeout", "11");
            properties.put("spring.jpa.hibernate.ddl-auto", "validate");
            properties.put("spring.jpa.open-in-view", "false");
            properties.put("spring.jpa.properties.hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
            properties.put("spring.jpa.show-sql", "false");
            properties.putAll(overrides);
            TestPropertyValues.of(properties).applyTo(context);
            context.getBeanFactory().registerSingleton("wiringProbe", probe);
            context.register(BootJpaConfiguration.class);
            if (composed) context.register(LegalRegistrationSessionDataSourceConfiguration.class);
            if (extraConfigurations.length > 0) context.register(extraConfigurations);
        }
        void refresh() { context.refresh(); }
        DataSource source() { return context.getBean(DataSource.class); }
        EntityManagerFactory factory() { return context.getBean(EntityManagerFactory.class); }
        LegalRegistrationSessionResources resources() { return context.getBean(LegalRegistrationSessionResources.class); }
        HikariDataSource pool() { return privatePool(resources()); }
        jakarta.persistence.EntityManager entityManager() {
            return Objects.requireNonNull(EntityManagerFactoryUtils.getTransactionalEntityManager(factory()));
        }
        TransactionTemplate transaction(boolean readOnly, int isolation) {
            var transaction = new TransactionTemplate(context.getBean(JpaTransactionManager.class));
            transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            transaction.setIsolationLevel(isolation); transaction.setReadOnly(readOnly); transaction.setTimeout(20);
            return transaction;
        }
        Observation read() {
            return transaction(true, TransactionDefinition.ISOLATION_READ_COMMITTED).execute(status -> {
                User user = context.getBean(UserRepository.class).findSessionByIdAndTallerId(userId, workshopId).orElseThrow();
                assertThat(user.getTaller().getId()).isEqualTo(workshopId);
                return observe().withName(user.getUsername());
            });
        }
        Observation scoped() { return resources().withinRegistrationBudget(LegalRegistrationBudget.start(() -> 0L), ignored -> read()); }
        Observation observe() {
            return entityManager().unwrap(Session.class).doReturningWork(connection -> {
                try (var statement = connection.createStatement(); var row = statement.executeQuery("""
                        SELECT pg_backend_pid(),current_user,current_database(),current_setting('application_name'),
                               current_setting('transaction_isolation'),current_setting('transaction_read_only'),
                               current_setting('statement_timeout'),current_schema(),current_setting('search_path')
                        """)) {
                    assertThat(row.next()).isTrue();
                    return new Observation(row.getInt(1), row.getString(2), row.getString(3), row.getString(4),
                            row.getString(5), row.getString(6), row.getString(7), connection.getNetworkTimeout(), connection.getAutoCommit(),
                            row.getString(8), row.getString(9), null);
                }
            });
        }
        void assertOneIdentity() {
            assertThat(context.getBeanNamesForType(DataSource.class)).containsExactly("dataSource");
            assertThat(((EntityManagerFactoryInfo) factory()).getDataSource()).isSameAs(source());
            assertThat(context.getBean(JpaTransactionManager.class).getDataSource()).isSameAs(source());
            assertThat(context.getBean(JdbcTemplate.class).getDataSource()).isSameAs(source());
        }
        void assertPrivateLimits() {
            assertThat(pool().getMinimumIdle()).isZero(); assertThat(pool().getMaximumPoolSize()).isEqualTo(2);
            assertThat(pool().getConnectionTimeout()).isEqualTo(1_000); assertThat(pool().getValidationTimeout()).isEqualTo(1_000);
            assertThat(pool().getInitializationFailTimeout()).isEqualTo(-1);
            assertThat(pool().getJdbcUrl()).isNull(); assertThat(pool().getUsername()).isNull(); assertThat(pool().getPassword()).isNull();
            assertThat(pool().getDataSource()).isNotNull();
            Properties properties = (Properties) ReflectionTestUtils.getField(pool().getDataSource(), "properties");
            assertThat(properties).isNotNull();
            assertThat(properties.getProperty("connectTimeout")).isEqualTo("1");
            assertThat(properties.getProperty("loginTimeout")).isEqualTo("1");
            assertThat(properties.getProperty("cancelSignalTimeout")).isEqualTo("1");
            assertThat(properties.getProperty("socketTimeout")).isEqualTo("6");
            assertThat(properties.getProperty("queryTimeout")).isEqualTo("5");
        }
        void assertReturned() {
            assertThat(probe.historical.getHikariPoolMXBean().getActiveConnections()).isZero();
            assertThat(pool().getHikariPoolMXBean().getActiveConnections()).isZero();
            assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
        }
        @Override public void close() { context.close(); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @ImportAutoConfiguration({DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class, TransactionAutoConfiguration.class})
    static class BootJpaConfiguration {
        @Bean static CaptureOriginalPostProcessor captureWiringOriginal(Probe probe) { return new CaptureOriginalPostProcessor(probe); }
        @Bean static PersistenceManagedTypes wiringManagedTypes() {
            return PersistenceManagedTypes.of(User.class.getName(), Taller.class.getName(), Suscripcion.class.getName());
        }
        @Bean UserRepository wiringUserRepository(EntityManagerFactory factory) {
            return new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(factory)).getRepository(UserRepository.class);
        }
    }
    private static final class CaptureOriginalPostProcessor implements BeanPostProcessor, Ordered {
        private final Probe probe;
        CaptureOriginalPostProcessor(Probe probe) { this.probe = probe; }
        @Override public int getOrder() { return -1; }
        @Override public Object postProcessAfterInitialization(Object bean, String name) {
            if (name.equals("dataSource")) {
                assertThat(bean).isExactlyInstanceOf(HikariDataSource.class);
                probe.historical = (HikariDataSource) bean;
            }
            return bean;
        }
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class ConnectionDetailsConfiguration {
        @Bean JdbcConnectionDetails wiringConnectionDetails(Probe probe) { return Objects.requireNonNull(probe.details); }
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class OuterObservationConfiguration {
        @Bean static BeanPostProcessor outerWiringObservation(Probe probe) {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!name.equals("dataSource")) return bean;
                    probe.observer = new ObservedDataSource((DataSource) bean);
                    return probe.observer;
                }
            };
        }
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class PendingObservationConfiguration {
        @Bean static PendingPostProcessor pendingWiringObservation(Probe probe,
                org.springframework.beans.factory.ObjectProvider<LegalRegistrationSessionResources> resources) {
            return new PendingPostProcessor(probe, resources);
        }
    }
    private static final class PendingPostProcessor implements BeanPostProcessor, Ordered {
        private final Probe probe;
        private final org.springframework.beans.factory.ObjectProvider<LegalRegistrationSessionResources> resources;
        PendingPostProcessor(Probe probe, org.springframework.beans.factory.ObjectProvider<LegalRegistrationSessionResources> resources) {
            this.probe = probe; this.resources = resources;
        }
        @Override public int getOrder() { return 1; }
        @Override public Object postProcessAfterInitialization(Object bean, String name) {
            if (name.equals("dataSource")) {
                probe.pendingFailure = catchThrowable(() -> resources.getObject().withinRegistrationBudget(
                        LegalRegistrationBudget.start(), ignored -> { probe.pendingWork++; return true; }));
                if (probe.pendingFailure != null) probe.pendingRejections++;
            }
            return bean;
        }
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class LateFailureConfiguration {
        @Bean @Lazy(false) @DependsOn("legalRegistrationSessionWiringReady")
        Object failAfterRegisteredWiring(EntityManagerFactory factory, DataSource dataSource,
                LegalRegistrationSessionResources resources, Probe probe) {
            assertThat(factory.isOpen()).isTrue();
            probe.failedResources = resources; probe.failedPrivatePool = privatePool(resources);
            probe.lateQuery = resources.withinRegistrationBudget(LegalRegistrationBudget.start(), ignored ->
                    new JdbcTemplate(dataSource).queryForObject("SELECT current_user", String.class));
            throw probe.lateFailure;
        }
    }
    private static final class Probe {
        HikariDataSource historical;
        JdbcConnectionDetails details;
        ObservedDataSource observer;
        Throwable pendingFailure;
        int pendingRejections;
        int pendingWork;
        final IllegalStateException lateFailure = new IllegalStateException("Synthetic later refresh failure");
        LegalRegistrationSessionResources failedResources;
        HikariDataSource failedPrivatePool;
        String lateQuery;
    }
    private static final class ObservedDataSource extends AbstractDataSource {
        final DataSource delegate;
        int borrows; int commits; int rollbacks; int returns;
        ObservedDataSource(DataSource delegate) { this.delegate = delegate; }
        void reset() { borrows = commits = rollbacks = returns = 0; }
        @Override public Connection getConnection() throws SQLException {
            Connection connection = delegate.getConnection(); borrows++;
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                    case "toString" -> "WiringObservedConnection[redacted]";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new AssertionError(method.getName());
                };
                Object result = invoke(connection, method, args);
                switch (method.getName()) {
                    case "commit" -> commits++;
                    case "rollback" -> { if (method.getParameterCount() == 0) rollbacks++; }
                    case "close" -> returns++;
                    default -> { }
                }
                return result;
            });
        }
        @Override public Connection getConnection(String username, String password) throws SQLException {
            return delegate.getConnection(username, password);
        }
        @Override public <T> T unwrap(Class<T> type) throws SQLException {
            return type.isInstance(this) ? type.cast(this) : delegate.unwrap(type);
        }
        @Override public boolean isWrapperFor(Class<?> type) throws SQLException {
            return type.isInstance(this) || delegate.isWrapperFor(type);
        }
    }
    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
    private static HikariDataSource privatePool(LegalRegistrationSessionResources resources) {
        return (HikariDataSource) Objects.requireNonNull(ReflectionTestUtils.getField(resources, "pool"));
    }
    private static JdbcConnectionDetails details(String url, String username, String password) {
        return new JdbcConnectionDetails() {
            @Override public String getJdbcUrl() { return url; }
            @Override public String getUsername() { return username; }
            @Override public String getPassword() { return password; }
            @Override public String getDriverClassName() { return "org.postgresql.Driver"; }
        };
    }
    private static String urlWith(String query) { return POSTGRES.getJdbcUrl() + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?") + query; }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static Properties copy(Properties properties) { Properties result = new Properties(); result.putAll(properties); return result; }
    private static Throwable rootCause(Throwable failure) { while (failure.getCause() != null) failure = failure.getCause(); return failure; }
    private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        while (failure != null) { if (type.isInstance(failure)) return true; failure = failure.getCause(); } return false;
    }
    private static List<Map<String, Object>> durableRows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.addAll(owner.queryForList("SELECT 'users' AS source,id,xmin::text AS xmin,username AS name FROM users ORDER BY id"));
        rows.addAll(owner.queryForList("SELECT 'talleres' AS source,id,xmin::text AS xmin,nombre AS name FROM talleres ORDER BY id"));
        rows.addAll(owner.queryForList("SELECT 'suscripciones' AS source,id,xmin::text AS xmin,plan AS name FROM suscripciones ORDER BY id"));
        return rows;
    }
    private record Observation(int pid, String role, String database, String applicationName, String isolation,
            String readOnly, String statementTimeout, int networkTimeout, boolean autoCommit, String schema, String searchPath, String name) {
        Observation withName(String name) { return new Observation(pid,role,database,applicationName,isolation,readOnly,
                statementTimeout,networkTimeout,autoCommit,schema,searchPath,name); }
    }
}
