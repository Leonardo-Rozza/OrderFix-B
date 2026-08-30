package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.config.DataLoader;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.MercadoPagoEventRetryScheduler;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.MercadoPagoReconciliationScheduler;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.SuscripcionScheduler;
import com.leonardorozza.mvgrreparacionesbackend.service.security.DeviceCredentialLegacyMigration;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.Banner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.ServletWebServerFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegalEditorialDatabaseIsolationIT {

    @Test
    void isolatedContextBuildsTwoExactGatesOverOneDatasourceWithoutHostSubsystems() {
        HikariDataSource pool;
        try (ConfigurableApplicationContext context = openContext()) {
            assertThat(context.getBeansOfType(DataSource.class)).hasSize(1);
            DataSource dataSource = context.getBean(DataSource.class);
            assertThat(dataSource).isInstanceOf(HikariDataSource.class);
            pool = (HikariDataSource) dataSource;

            assertThat(context.getBeansOfType(JdbcTemplate.class)).hasSize(1);
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            assertThat(jdbc.getDataSource()).isSameAs(dataSource);
            assertThat(context.getBeansOfType(DataSourceTransactionManager.class)).hasSize(1);
            DataSourceTransactionManager manager = context.getBean(
                    DataSourceTransactionManager.class);
            assertThat(manager.getClass()).isEqualTo(DataSourceTransactionManager.class);
            assertThat(manager.getDataSource()).isSameAs(dataSource);
            assertThat(manager.isRollbackOnCommitFailure()).isFalse();
            assertThat(context.getBeansOfType(JdbcTransactionManager.class)).isEmpty();

            Map<String, TransactionTemplate> transactions =
                    context.getBeansOfType(TransactionTemplate.class);
            assertThat(transactions).containsOnlyKeys(
                    LegalEditorialDatabaseConfiguration.READ_ONLY_TRANSACTION,
                    LegalEditorialDatabaseConfiguration.MUTABLE_TRANSACTION);
            assertTemplate(
                    transactions.get(LegalEditorialDatabaseConfiguration.READ_ONLY_TRANSACTION),
                    manager,
                    "legal-editorial-read-only",
                    true);
            assertTemplate(
                    transactions.get(LegalEditorialDatabaseConfiguration.MUTABLE_TRANSACTION),
                    manager,
                    "legal-editorial-mutable",
                    false);

            Map<String, LegalManifestDatabaseGate> gates =
                    context.getBeansOfType(LegalManifestDatabaseGate.class);
            assertThat(gates)
                    .containsOnlyKeys(
                            LegalEditorialDatabaseConfiguration.READ_ONLY_GATE,
                            LegalEditorialDatabaseConfiguration.MUTABLE_GATE);
            LegalManifestDatabaseGate readOnlyGate = gates.get(
                    LegalEditorialDatabaseConfiguration.READ_ONLY_GATE);
            LegalManifestDatabaseGate mutableGate = gates.get(
                    LegalEditorialDatabaseConfiguration.MUTABLE_GATE);
            LegalEditorialSchemaVerifier schemaVerifier = context.getBean(
                    LegalEditorialSchemaVerifier.class);
            LegalEditorialPrivilegeVerifier privilegeVerifier = context.getBean(
                    LegalEditorialPrivilegeVerifier.class);
            assertThat(schemaVerifier.usesJdbc(jdbc)).isTrue();
            assertThat(privilegeVerifier.usesJdbc(jdbc)).isTrue();
            assertThat(privilegeVerifier.expectedRole()).isEqualTo("legal_editorial_test");
            assertThat(readOnlyGate.usesJdbc(jdbc)).isTrue();
            assertThat(mutableGate.usesJdbc(jdbc)).isTrue();
            assertThatCode(() -> readOnlyGate.requireExactEditorialPreflights(
                    jdbc,
                    schemaVerifier,
                    privilegeVerifier)).doesNotThrowAnyException();
            assertThatCode(() -> mutableGate.requireExactEditorialPreflights(
                    jdbc,
                    schemaVerifier,
                    privilegeVerifier)).doesNotThrowAnyException();
            assertThatCode(mutableGate::requireCommitOutcomeSafe)
                    .doesNotThrowAnyException();
            assertThatThrownBy(readOnlyGate::requireCommitOutcomeSafe)
                    .isInstanceOf(IllegalArgumentException.class);

            assertThat(context.getBeansOfType(LegalDatabaseBoundaryMarker.class).values())
                    .singleElement()
                    .satisfies(marker -> assertThat(marker.kind())
                            .isEqualTo(LegalDatabaseBoundaryMarker.Kind.EDITORIAL));
            assertThat(context.getBeansOfType(LegalDatabaseBoundaryMarker.Guard.class))
                    .hasSize(1);
            assertThat(context.getBeansOfType(LegalEditorialReadinessService.class)).hasSize(1);
            assertThat(context.getBeansOfType(LegalEditorialPlanService.class)).hasSize(1);
            assertThat(context.getBeansOfType(LegalEditorialApplyService.class)).hasSize(1);
            assertThat(context.getBeansOfType(LegalEditorialMutationWriter.class).values())
                    .hasSize(3)
                    .allSatisfy(writer -> assertThat(writer.usesJdbc(jdbc)).isTrue())
                    .anySatisfy(writer -> assertThat(writer)
                            .isInstanceOf(LegalInitialPromotionCore.class))
                    .anySatisfy(writer -> assertThat(writer)
                            .isInstanceOf(LegalDocumentReplacementWriter.class))
                    .anySatisfy(writer -> assertThat(writer)
                            .isInstanceOf(LegalEditorialRetirementWriter.class));
            assertThat(context.getBeansOfType(LegalEditorialPostStateVerifier.class).values())
                    .singleElement()
                    .satisfies(verifier -> assertThat(verifier.usesJdbc(jdbc)).isTrue());
            assertThat(context.getBeansOfType(LegalManifestDryRunService.class)).isEmpty();
            assertThat(context.getBeansOfType(LegalManifestImportService.class)).isEmpty();
            assertThat(context.getBeansOfType(LegalImportPrivilegeVerifier.class)).isEmpty();

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
            assertThat(context.getEnvironment().getProperty("spring.flyway.enabled"))
                    .isEqualTo("false");
            assertThat(context.getEnvironment().getProperty("spring.main.web-application-type"))
                    .isEqualTo("none");
        }
        assertThat(pool.isClosed()).isTrue();
    }

    @Test
    void boundaryGuardRejectsEveryMixedLegalDatabaseContext() {
        LegalDatabaseBoundaryMarker editorial = new LegalDatabaseBoundaryMarker(
                LegalDatabaseBoundaryMarker.Kind.EDITORIAL);
        LegalDatabaseBoundaryMarker importMarker = new LegalDatabaseBoundaryMarker(
                LegalDatabaseBoundaryMarker.Kind.IMPORT);
        LegalDatabaseBoundaryMarker dryRun = new LegalDatabaseBoundaryMarker(
                LegalDatabaseBoundaryMarker.Kind.DRY_RUN);

        assertThatThrownBy(() -> new LegalDatabaseBoundaryMarker.Guard(
                List.of(editorial, importMarker),
                LegalDatabaseBoundaryMarker.Kind.EDITORIAL))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new LegalDatabaseBoundaryMarker.Guard(
                List.of(editorial, dryRun),
                LegalDatabaseBoundaryMarker.Kind.EDITORIAL))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new LegalDatabaseBoundaryMarker.Guard(
                List.of(editorial, importMarker, dryRun),
                LegalDatabaseBoundaryMarker.Kind.EDITORIAL))
                .isInstanceOf(IllegalStateException.class);
    }

    private static ConfigurableApplicationContext openContext() {
        return new SpringApplicationBuilder(LegalEditorialDatabaseConfiguration.class)
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .logStartupInfo(false)
                .registerShutdownHook(false)
                .headless(true)
                .addCommandLineProperties(false)
                .properties(Map.ofEntries(
                        Map.entry(LegalEditorialDatabaseConfiguration.ENABLED_PROPERTY, "true"),
                        Map.entry(
                                "spring.datasource.url",
                                "jdbc:h2:mem:legal_editorial_context;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"),
                        Map.entry("spring.datasource.username", "legal_editorial_test"),
                        Map.entry("spring.datasource.password", "test-only"),
                        Map.entry("spring.datasource.driver-class-name", "org.h2.Driver"),
                        Map.entry("spring.config.location", "optional:classpath:/ordenfix-legal-cli/"),
                        Map.entry("spring.main.web-application-type", "none"),
                        Map.entry("spring.flyway.enabled", "false"),
                        Map.entry("spring.jmx.enabled", "false"),
                        Map.entry("logging.level.root", "OFF"),
                        Map.entry("logging.console.enabled", "false")))
                .run();
    }

    private static void assertTemplate(
            TransactionTemplate transaction,
            DataSourceTransactionManager manager,
            String expectedName,
            boolean readOnly) {
        assertThat(transaction).isNotNull();
        assertThat(transaction.getName()).isEqualTo(expectedName);
        assertThat(transaction.getTransactionManager()).isSameAs(manager);
        assertThat(transaction.getPropagationBehavior())
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThat(transaction.getIsolationLevel())
                .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
        assertThat(transaction.getTimeout())
                .isEqualTo(LegalDatabaseBudgets.PRODUCTION_TRANSACTION_TIMEOUT_SECONDS);
        assertThat(transaction.isReadOnly()).isEqualTo(readOnly);
    }
}
