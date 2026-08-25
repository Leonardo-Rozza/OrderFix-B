package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli;

import com.leonardorozza.mvgrreparacionesbackend.MvgrReparacionesBackendApplication;
import com.leonardorozza.mvgrreparacionesbackend.config.DataLoader;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalDryRunDatabaseConfiguration;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestDryRunService;
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
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.Map;

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
