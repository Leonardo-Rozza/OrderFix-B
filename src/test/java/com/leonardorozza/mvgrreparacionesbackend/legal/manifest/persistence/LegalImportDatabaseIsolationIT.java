package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.config.DataLoader;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.MercadoPagoEventRetryScheduler;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.MercadoPagoReconciliationScheduler;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.SuscripcionScheduler;
import com.leonardorozza.mvgrreparacionesbackend.service.security.DeviceCredentialLegacyMigration;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.web.server.servlet.ServletWebServerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegalImportDatabaseIsolationIT {

    private static PostgreSQLContainer postgres;
    private static String jdbcUrl;
    private static String username;
    private static String password;

    @BeforeAll
    static void database() {
        jdbcUrl = System.getProperty(
                "ordenfix.test.isolation.postgresql.url",
                System.getProperty("ordenfix.test.postgresql.url"));
        username = System.getProperty("ordenfix.test.postgresql.username", "ordenfix");
        password = System.getProperty("ordenfix.test.postgresql.password", "");
        if (jdbcUrl == null) {
            postgres = new PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("ordenfix_legal_import_isolation")
                    .withUsername("ordenfix")
                    .withPassword("ordenfix");
            postgres.start();
            jdbcUrl = postgres.getJdbcUrl();
            username = postgres.getUsername();
            password = postgres.getPassword();
        }
    }

    @AfterAll
    static void stopContainer() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void importContextContainsOnlyItsAccreditedJdbcBoundaryAndNeverRunsFlyway() {
        HikariDataSource pool;
        try (AnnotationConfigApplicationContext context = importContext()) {
            assertThat(context.getBeansOfType(DataSource.class)).hasSize(1);
            assertThat(context.getBeansOfType(JdbcTemplate.class)).hasSize(1);
            DataSource dataSource = context.getBean(DataSource.class);
            assertThat(dataSource).isInstanceOf(HikariDataSource.class);
            pool = (HikariDataSource) dataSource;
            assertThat(pool.isClosed()).isFalse();

            assertThat(context.getBeansOfType(LegalManifestImportService.class)).hasSize(1);
            assertThat(context.getBeansOfType(LegalV27ImportSchemaVerifier.class)).hasSize(1);
            assertThat(context.getBeansOfType(LegalImportPrivilegeVerifier.class)).hasSize(1);
            assertThat(context.getBeansOfType(LegalManifestDatabaseGate.class)).hasSize(1);
            assertThat(context.getBeansOfType(DataSourceTransactionManager.class)).hasSize(1);
            DataSourceTransactionManager manager =
                    context.getBean(DataSourceTransactionManager.class);
            assertThat(manager.getClass()).isEqualTo(DataSourceTransactionManager.class);
            assertThat(manager.isRollbackOnCommitFailure()).isFalse();
            assertThat(manager.getDataSource()).isSameAs(dataSource);
            TransactionTemplate transaction = context.getBean(TransactionTemplate.class);
            assertThat(transaction.getTransactionManager()).isSameAs(manager);
            assertThat(transaction.getPropagationBehavior())
                    .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            assertThat(transaction.getIsolationLevel())
                    .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
            assertThat(transaction.getTimeout()).isEqualTo(75);
            assertThat(transaction.isReadOnly()).isFalse();

            assertThat(context.getBeansOfType(LegalManifestDryRunService.class)).isEmpty();
            assertThat(context.getBeansOfType(JdbcTransactionManager.class)).isEmpty();
            assertThat(context.getBeansOfType(Flyway.class)).isEmpty();
            assertThat(context.getBeansOfType(EntityManagerFactory.class)).isEmpty();
            assertThat(context.getBeansOfType(ServletWebServerFactory.class)).isEmpty();
            assertThat(context.getBeansOfType(ApplicationRunner.class)).isEmpty();
            assertThat(context.getBeansOfType(CommandLineRunner.class)).isEmpty();
            assertThat(context.getBeansOfType(TaskScheduler.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
            assertThat(context.getBeansOfType(DataLoader.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    DeviceCredentialLegacyMigration.class)).isEmpty();
            assertThat(context.getBeansOfType(SuscripcionScheduler.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    MercadoPagoEventRetryScheduler.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    MercadoPagoReconciliationScheduler.class)).isEmpty();
        }

        assertThat(pool.isClosed()).isTrue();
        assertThat(historyExists()).isFalse();
    }

    @Test
    void dryRunSourceDoesNotDiscoverImportBeansEvenWithAHostileImportFlag() {
        try (AnnotationConfigApplicationContext context = context(
                LegalDryRunDatabaseConfiguration.class,
                Map.of(
                        LegalDryRunDatabaseConfiguration.ENABLED_PROPERTY, "true",
                        LegalImportDatabaseConfiguration.ENABLED_PROPERTY, "true"))) {
            assertThat(context.getBeansOfType(LegalManifestDryRunService.class)).hasSize(1);
            assertThat(context.getBeansOfType(LegalManifestImportService.class)).isEmpty();
            assertThat(context.getBeansOfType(LegalImportPrivilegeVerifier.class)).isEmpty();
            assertThat(context.getBeansOfType(DataSourceTransactionManager.class).values())
                    .noneMatch(manager -> manager.getClass()
                            == DataSourceTransactionManager.class);
        }
    }

    @Test
    void registeringBothDatabaseBoundariesFailsClosedInsteadOfMixingThem() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        addProperties(context, Map.of(
                LegalDryRunDatabaseConfiguration.ENABLED_PROPERTY, "true",
                LegalImportDatabaseConfiguration.ENABLED_PROPERTY, "true"));
        context.register(
                LegalDryRunDatabaseConfiguration.class,
                LegalImportDatabaseConfiguration.class);
        try {
            assertThatThrownBy(context::refresh)
                    .isInstanceOf(RuntimeException.class)
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("Los contextos DB legales no pueden combinarse");
        } finally {
            context.close();
        }
    }

    private static AnnotationConfigApplicationContext importContext() {
        return context(
                LegalImportDatabaseConfiguration.class,
                Map.of(LegalImportDatabaseConfiguration.ENABLED_PROPERTY, "true"));
    }

    private static AnnotationConfigApplicationContext context(
            Class<?> source,
            Map<String, Object> overrides) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        addProperties(context, overrides);
        context.register(source);
        context.refresh();
        return context;
    }

    private static void addProperties(
            AnnotationConfigApplicationContext context,
            Map<String, Object> overrides) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("spring.datasource.url", jdbcUrl);
        properties.put("spring.datasource.username", username);
        properties.put("spring.datasource.password", password);
        properties.put("spring.datasource.driver-class-name", "org.postgresql.Driver");
        properties.put(LegalImportDatabaseConfiguration.SCHEMA_PROPERTY, "public");
        properties.putAll(overrides);
        context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("legal-import-isolation-it", properties));
    }

    private static boolean historyExists() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                jdbcUrl,
                username,
                password);
        return Boolean.TRUE.equals(new JdbcTemplate(dataSource).queryForObject(
                "SELECT pg_catalog.to_regclass('public.flyway_schema_history') IS NOT NULL",
                Boolean.class));
    }
}
