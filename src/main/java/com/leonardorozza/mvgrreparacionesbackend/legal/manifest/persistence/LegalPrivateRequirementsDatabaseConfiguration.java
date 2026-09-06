package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalApplicableScopeResolver;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateProvenanceCalculator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateRevisionCalculator;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/** Explicit non-scannable context for derived aggregates; no web fallback, Flyway or HTTP. */
@Conditional(LegalPrivateRequirementsDatabaseConfiguration.Enabled.class)
public class LegalPrivateRequirementsDatabaseConfiguration {

    public static final String ENABLED_PROPERTY = "ordenfix.legal.account-read.enabled";
    public static final String PROPERTY_PREFIX = "ordenfix.legal.account-read.";
    private static final Set<String> URL_OPTIONS = Set.of(
            "sslmode", "sslrootcert", "sslcert", "sslkey", "sslpassword", "loggerLevel");

    @Bean
    LegalDatabaseBoundaryMarker legalPrivateRequirementsBoundaryMarker() {
        return new LegalDatabaseBoundaryMarker(LegalDatabaseBoundaryMarker.Kind.PRIVATE_REQUIREMENTS);
    }

    @Bean
    LegalDatabaseBoundaryMarker.Guard legalPrivateRequirementsBoundaryGuard(
            List<LegalDatabaseBoundaryMarker> markers) {
        return new LegalDatabaseBoundaryMarker.Guard(
                markers, LegalDatabaseBoundaryMarker.Kind.PRIVATE_REQUIREMENTS);
    }

    @Bean(destroyMethod = "close")
    HikariDataSource legalPrivateRequirementsPool(Environment environment) {
        String jdbcUrl = required(environment, "jdbc-url");
        requireBoundedPostgresUrl(jdbcUrl);
        HikariConfig config = new HikariConfig();
        config.setPoolName("legal-private-requirements");
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(required(environment, "username"));
        config.setPassword(required(environment, "password"));
        config.setDriverClassName("org.postgresql.Driver");
        config.setMinimumIdle(0);
        config.setMaximumPoolSize(2);
        config.setConnectionTimeout(LegalPrivateRequirementsDataSource.BORROW_TIMEOUT_MILLIS);
        config.setValidationTimeout(1_000);
        config.setInitializationFailTimeout(-1);
        config.setReadOnly(false);
        config.addDataSourceProperty("connectTimeout", "1");
        config.addDataSourceProperty("loginTimeout", "1");
        config.addDataSourceProperty("socketTimeout", "5");
        config.addDataSourceProperty("cancelSignalTimeout", "1");
        config.addDataSourceProperty("ApplicationName", "ordenfix-legal-private-requirements");
        return new HikariDataSource(config);
    }

    @Bean(destroyMethod = "close")
    LegalPrivateRequirementsDataSource legalPrivateRequirementsDataSource(
            @Qualifier("legalPrivateRequirementsPool") HikariDataSource pool) {
        return new LegalPrivateRequirementsDataSource(pool, Duration.ofSeconds(15));
    }

    @Bean
    JdbcTemplate legalPrivateRequirementsJdbc(LegalPrivateRequirementsDataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    LegalDatabaseBudgets legalPrivateRequirementsBudgets() {
        return new LegalDatabaseBudgets(15, 5, 1, 1);
    }

    @Bean
    DataSourceTransactionManager legalPrivateRequirementsTransactionManager(
            LegalPrivateRequirementsDataSource dataSource) {
        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
        manager.setRollbackOnCommitFailure(false);
        manager.setEnforceReadOnly(false);
        return manager;
    }

    @Bean
    TransactionTemplate legalPrivateRequirementsTransactionTemplate(
            DataSourceTransactionManager manager, LegalDatabaseBudgets budgets) {
        TransactionTemplate template = new TransactionTemplate(manager);
        template.setName("legal-private-requirements");
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setReadOnly(false);
        template.setTimeout(budgets.transactionTimeoutSeconds());
        return template;
    }

    @Bean
    LegalV29AcceptanceSchemaVerifier legalPrivateRequirementsSchemaVerifier(JdbcTemplate jdbc) {
        return new LegalV29AcceptanceSchemaVerifier(jdbc, "public");
    }

    @Bean
    LegalPrivateRequirementsPrivilegeVerifier legalPrivateRequirementsPrivilegeVerifier(
            JdbcTemplate jdbc, Environment environment) {
        return new LegalPrivateRequirementsPrivilegeVerifier(jdbc, required(environment, "username"), "public");
    }

    @Bean
    LegalManifestDatabaseGate legalPrivateRequirementsGate(
            TransactionTemplate template, JdbcTemplate jdbc, LegalDatabaseBudgets budgets,
            LegalV29AcceptanceSchemaVerifier schema, LegalPrivateRequirementsPrivilegeVerifier privileges,
            LegalDatabaseBoundaryMarker.Guard guard) {
        LegalManifestDatabaseGate gate = new LegalManifestDatabaseGate(
                template, jdbc, budgets, List.of(schema, privileges));
        gate.requireExactPrivateRequirementsBoundary(jdbc, schema, privileges);
        return gate;
    }

    @Bean
    LegalApplicableScopeResolver legalPrivateRequirementsScopeResolver() {
        return new LegalApplicableScopeResolver();
    }

    @Bean
    LegalRequiredSetAggregateRevisionCalculator legalPrivateRequirementsRevisionCalculator() {
        return new LegalRequiredSetAggregateRevisionCalculator();
    }

    @Bean
    LegalRequiredSetAggregateProvenanceCalculator legalPrivateRequirementsProvenanceCalculator() {
        return new LegalRequiredSetAggregateProvenanceCalculator();
    }

    @Bean
    LegalRequiredSetAggregateReplayVerifier legalPrivateRequirementsReplayVerifier(
            JdbcTemplate jdbc, LegalRequiredSetAggregateRevisionCalculator revisionCalculator,
            LegalRequiredSetAggregateProvenanceCalculator provenanceCalculator) {
        return new LegalRequiredSetAggregateReplayVerifier(jdbc, revisionCalculator, provenanceCalculator);
    }

    @Bean
    LegalRequiredSetAggregateStore legalPrivateRequirementsStore(
            JdbcTemplate jdbc, LegalRequiredSetAggregateRevisionCalculator revisionCalculator,
            LegalRequiredSetAggregateProvenanceCalculator provenanceCalculator,
            LegalRequiredSetAggregateReplayVerifier replayVerifier) {
        return new LegalRequiredSetAggregateStore(jdbc, revisionCalculator, provenanceCalculator, replayVerifier);
    }

    @Bean
    LegalActorSnapshotReader legalPrivateRequirementsActorReader(JdbcTemplate jdbc) {
        return new LegalActorSnapshotReader(jdbc);
    }

    @Bean
    LegalPrivateRequirementsReader legalPrivateRequirementsReader(JdbcTemplate jdbc) {
        return new LegalPrivateRequirementsReader(jdbc);
    }

    @Bean
    LegalPrivateRequirementsReadService legalPrivateRequirementsReadService(
            JdbcTemplate jdbc, LegalPrivateRequirementsDataSource dataSource, LegalManifestDatabaseGate gate,
            LegalApplicableScopeResolver resolver, LegalRequiredSetAggregateStore store,
            LegalPrivateRequirementsReader reader, LegalActorSnapshotReader actorReader,
            LegalV29AcceptanceSchemaVerifier schema,
            LegalPrivateRequirementsPrivilegeVerifier privileges, LegalDatabaseBoundaryMarker.Guard guard) {
        return new LegalPrivateRequirementsReadService(
                jdbc, dataSource, gate, resolver, store, reader, actorReader, schema, privileges);
    }

    private static String required(Environment environment, String suffix) {
        String value = environment.getRequiredProperty(PROPERTY_PREFIX + suffix);
        if (value.isBlank()) {
            throw new IllegalArgumentException("La configuración de requisitos privados requerida está vacía");
        }
        return value;
    }

    private static void requireBoundedPostgresUrl(String url) {
        if (!url.startsWith("jdbc:postgresql://") || url.indexOf('#') >= 0) {
            throw new IllegalArgumentException("Los requisitos privados requieren una URL PostgreSQL explícita");
        }
        int query = url.indexOf('?');
        if (query >= 0) {
            for (String option : url.substring(query + 1).split("&")) {
                String key = URLDecoder.decode(option.split("=", 2)[0], StandardCharsets.UTF_8);
                if (!URL_OPTIONS.contains(key)) {
                    throw new IllegalArgumentException(
                            "La URL de requisitos privados no admite reemplazar credenciales, driver o presupuestos");
                }
            }
        }
    }

    /** Exact literals, including when the explicitly registered context is disabled. */
    static final class Enabled implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String value = context.getEnvironment().getProperty(ENABLED_PROPERTY);
            if (value == null || "false".equals(value)) {
                return false;
            }
            if (!"true".equals(value)) {
                throw new IllegalArgumentException("El flag de lectura legal requiere true o false exactos");
            }
            return true;
        }
    }

}
