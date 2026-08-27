package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli;

import com.leonardorozza.mvgrreparacionesbackend.MvgrReparacionesBackendApplication;
import com.leonardorozza.mvgrreparacionesbackend.config.DataLoader;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalDryRunDatabaseConfiguration;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalImportDatabaseConfiguration;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestDryRunService;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestImportService;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.MercadoPagoEventRetryScheduler;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.MercadoPagoReconciliationScheduler;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.SuscripcionScheduler;
import com.leonardorozza.mvgrreparacionesbackend.service.security.DeviceCredentialLegacyMigration;
import com.zaxxer.hikari.HikariDataSource;

import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.web.server.servlet.ServletWebServerFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class LegalManifestCliIsolationIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_manifest_cli_isolation")
            .withUsername("ordenfix")
            .withPassword("ordenfix");

    @Test
    void dryRunContextContainsOnlyTheNonWebJdbcBoundaryAndNeverRunsFlyway() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("spring.datasource.url", POSTGRES.getJdbcUrl());
        properties.put("spring.datasource.username", POSTGRES.getUsername());
        properties.put("spring.datasource.password", POSTGRES.getPassword());
        properties.put("spring.datasource.driver-class-name", POSTGRES.getDriverClassName());
        Map<String, String> hostileSystemProperties = Map.of(
                LegalDryRunDatabaseConfiguration.ENABLED_PROPERTY, "false",
                "spring.config.import", "file:/private/ordenfix-hostile-import.properties",
                "spring.config.additional-location", "classpath:/",
                "spring.main.sources", MvgrReparacionesBackendApplication.class.getName(),
                "spring.main.web-application-type", "servlet",
                "spring.main.banner-mode", "console",
                "spring.main.log-startup-info", "true",
                "spring.flyway.enabled", "true",
                "logging.config", "classpath:logback-spring.xml",
                "logging.level.root", "TRACE");
        Map<String, String> previousSystemProperties = replaceSystemProperties(
                hostileSystemProperties);
        HikariDataSource connectionPool = null;
        try {
            try (ConfigurableApplicationContext context =
                         LegalManifestCli.openDryRunContext(properties)) {
                assertThat(context).isNotInstanceOf(WebApplicationContext.class);
                assertThat(context.getEnvironment().getProperty(
                        LegalDryRunDatabaseConfiguration.ENABLED_PROPERTY)).isEqualTo("true");
                assertThat(context.getEnvironment().getProperty("spring.config.location"))
                        .isEqualTo("optional:classpath:/ordenfix-legal-cli/");
                assertThat(context.getEnvironment().getProperty("spring.config.import")).isNull();
                assertThat(context.getEnvironment().getProperty(
                        "spring.config.additional-location")).isNull();
                assertThat(context.getEnvironment().getProperty("spring.main.sources")).isNull();
                assertThat(context.getEnvironment().getProperty(
                        "spring.main.web-application-type")).isEqualTo("none");
                assertThat(context.getEnvironment().getProperty("spring.main.banner-mode"))
                        .isEqualTo("off");
                assertThat(context.getEnvironment().getProperty("spring.flyway.enabled"))
                        .isEqualTo("false");
                assertThat(context.getEnvironment().getProperty("logging.config")).isNull();
                assertThat(context.getEnvironment().getProperty("logging.level.root"))
                        .isEqualTo("OFF");

                assertThat(context.getBeansOfType(DataSource.class)).hasSize(1);
                DataSource dataSource = context.getBean(DataSource.class);
                assertThat(dataSource).isInstanceOf(HikariDataSource.class);
                connectionPool = (HikariDataSource) dataSource;
                assertThat(connectionPool.isClosed()).isFalse();
                assertThat(context.getBeansOfType(LegalManifestDryRunService.class)).hasSize(1);

                assertThat(context.getBeansOfType(Flyway.class)).isEmpty();
                assertThat(context.getBeansOfType(ApplicationRunner.class)).isEmpty();
                assertThat(context.getBeansOfType(CommandLineRunner.class)).isEmpty();
                assertThat(context.getBeansOfType(TaskScheduler.class)).isEmpty();
                assertThat(context.getBeansOfType(
                        ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
                assertThat(context.getBeansOfType(ServletWebServerFactory.class)).isEmpty();
                assertThat(context.getBeansOfType(EntityManagerFactory.class)).isEmpty();
                assertThat(context.getBeansOfType(DataLoader.class)).isEmpty();
                assertThat(context.getBeansOfType(
                        DeviceCredentialLegacyMigration.class)).isEmpty();
                assertThat(context.getBeansOfType(SuscripcionScheduler.class)).isEmpty();
                assertThat(context.getBeansOfType(
                        MercadoPagoEventRetryScheduler.class)).isEmpty();
                assertThat(context.getBeansOfType(
                        MercadoPagoReconciliationScheduler.class)).isEmpty();
            }
        } finally {
            restoreSystemProperties(previousSystemProperties);
        }

        assertThat(connectionPool).isNotNull();
        assertThat(connectionPool.isClosed()).isTrue();
        assertThat(flywaySchemaHistoryExists()).isFalse();
    }

    @Test
    void importCliBuilderRejectsAmbientSpringConfigurationAndClosesItsPool() {
        Map<String, String> importVariables = Map.of(
                LegalImportEnvironment.ENABLED_VARIABLE, "true",
                LegalImportEnvironment.URL_VARIABLE, POSTGRES.getJdbcUrl(),
                LegalImportEnvironment.USERNAME_VARIABLE, POSTGRES.getUsername(),
                LegalImportEnvironment.PASSWORD_VARIABLE, POSTGRES.getPassword(),
                LegalImportEnvironment.DRIVER_VARIABLE, POSTGRES.getDriverClassName());
        var resolved = LegalImportEnvironment.resolve(importVariables, new Properties());
        assertThat(resolved.passed()).isTrue();
        LegalImportEnvironment importEnvironment = resolved.value().orElseThrow();
        Map<String, String> hostileSystemProperties = Map.ofEntries(
                Map.entry(LegalImportDatabaseConfiguration.ENABLED_PROPERTY, "false"),
                Map.entry("spring.datasource.url", "jdbc:h2:mem:hostile"),
                Map.entry("spring.datasource.username", "hostile-user"),
                Map.entry("spring.datasource.password", "hostile-secret"),
                Map.entry("spring.config.import",
                        "file:/private/ordenfix-hostile-import.properties"),
                Map.entry("spring.config.additional-location", "classpath:/"),
                Map.entry("spring.main.sources",
                        MvgrReparacionesBackendApplication.class.getName()),
                Map.entry("spring.main.web-application-type", "servlet"),
                Map.entry("spring.main.banner-mode", "console"),
                Map.entry("spring.flyway.enabled", "true"),
                Map.entry("logging.config", "classpath:logback-spring.xml"),
                Map.entry("logging.level.root", "TRACE"));
        Map<String, String> previousSystemProperties = replaceSystemProperties(
                hostileSystemProperties);
        HikariDataSource connectionPool = null;
        try {
            try (ConfigurableApplicationContext context =
                         LegalManifestCli.openImportContext(importEnvironment)) {
                assertThat(context).isNotInstanceOf(WebApplicationContext.class);
                assertThat(context.getEnvironment().getPropertySources().contains(
                        StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)).isFalse();
                assertThat(context.getEnvironment().getPropertySources().contains(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)).isFalse();
                assertThat(context.getEnvironment().getProperty(
                        LegalImportDatabaseConfiguration.ENABLED_PROPERTY)).isEqualTo("true");
                assertThat(context.getEnvironment().getProperty("spring.datasource.url"))
                        .isEqualTo(POSTGRES.getJdbcUrl());
                assertThat(context.getEnvironment().getProperty("spring.datasource.username"))
                        .isEqualTo(POSTGRES.getUsername());
                assertThat(context.getEnvironment().getProperty("spring.config.location"))
                        .isEqualTo("optional:classpath:/ordenfix-legal-cli/");
                assertThat(context.getEnvironment().getProperty("spring.config.import")).isNull();
                assertThat(context.getEnvironment().getProperty(
                        "spring.config.additional-location")).isNull();
                assertThat(context.getEnvironment().getProperty("spring.main.sources")).isNull();
                assertThat(context.getEnvironment().getProperty(
                        "spring.main.web-application-type")).isEqualTo("none");
                assertThat(context.getEnvironment().getProperty("spring.main.banner-mode"))
                        .isEqualTo("off");
                assertThat(context.getEnvironment().getProperty("spring.flyway.enabled"))
                        .isEqualTo("false");
                assertThat(context.getEnvironment().getProperty("logging.config")).isNull();
                assertThat(context.getEnvironment().getProperty("logging.level.root"))
                        .isEqualTo("OFF");

                assertThat(context.getBeansOfType(DataSource.class)).hasSize(1);
                assertThat(context.getBeansOfType(JdbcTemplate.class)).hasSize(1);
                DataSource dataSource = context.getBean(DataSource.class);
                assertThat(dataSource).isInstanceOf(HikariDataSource.class);
                connectionPool = (HikariDataSource) dataSource;
                assertThat(connectionPool.isClosed()).isFalse();
                assertThat(context.getBeansOfType(LegalManifestImportService.class)).hasSize(1);
                assertThat(context.getBeansOfType(LegalManifestDryRunService.class)).isEmpty();

                assertThat(context.getBeansOfType(Flyway.class)).isEmpty();
                assertThat(context.getBeansOfType(ApplicationRunner.class)).isEmpty();
                assertThat(context.getBeansOfType(CommandLineRunner.class)).isEmpty();
                assertThat(context.getBeansOfType(TaskScheduler.class)).isEmpty();
                assertThat(context.getBeansOfType(
                        ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
                assertThat(context.getBeansOfType(ServletWebServerFactory.class)).isEmpty();
                assertThat(context.getBeansOfType(EntityManagerFactory.class)).isEmpty();
                assertThat(context.getBeansOfType(DataLoader.class)).isEmpty();
                assertThat(context.getBeansOfType(
                        DeviceCredentialLegacyMigration.class)).isEmpty();
                assertThat(context.getBeansOfType(SuscripcionScheduler.class)).isEmpty();
                assertThat(context.getBeansOfType(
                        MercadoPagoEventRetryScheduler.class)).isEmpty();
                assertThat(context.getBeansOfType(
                        MercadoPagoReconciliationScheduler.class)).isEmpty();
            }
        } finally {
            restoreSystemProperties(previousSystemProperties);
        }

        assertThat(connectionPool).isNotNull();
        assertThat(connectionPool.isClosed()).isTrue();
        assertThat(flywaySchemaHistoryExists()).isFalse();
    }

    private static Map<String, String> replaceSystemProperties(
            Map<String, String> replacements) {
        Map<String, String> previous = new LinkedHashMap<>();
        replacements.forEach((key, value) -> {
            previous.put(key, System.getProperty(key));
            System.setProperty(key, value);
        });
        return previous;
    }

    private static void restoreSystemProperties(Map<String, String> previous) {
        previous.forEach((key, value) -> {
            if (value == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, value);
            }
        });
    }

    private static boolean flywaySchemaHistoryExists() {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
             var statement = connection.createStatement();
             var result = statement.executeQuery(
                     "SELECT to_regclass('public.flyway_schema_history') IS NOT NULL")) {
            assertThat(result.next()).isTrue();
            return result.getBoolean(1);
        } catch (Exception exception) {
            throw new AssertionError("No se pudo verificar el schema aislado", exception);
        }
    }
}
