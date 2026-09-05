package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalApplicableScopeResolver;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateProvenanceCalculator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateRevisionCalculator;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
@ConditionalOnProperty(name = LegalPublicRequirementsDatabaseConfiguration.ENABLED_PROPERTY,
        havingValue = "true")
public class LegalPublicRequirementsDatabaseConfiguration {

    public static final String ENABLED_PROPERTY = "ordenfix.legal.public-requirements-context.enabled";
    public static final String PROPERTY_PREFIX = "ordenfix.legal.public-requirements.";
    private static final Set<String> URL_OPTIONS = Set.of(
            "sslmode", "sslrootcert", "sslcert", "sslkey", "sslpassword", "loggerLevel");

    @Bean
    LegalDatabaseBoundaryMarker legalPublicRequirementsBoundaryMarker() {
        return new LegalDatabaseBoundaryMarker(LegalDatabaseBoundaryMarker.Kind.PUBLIC_REQUIREMENTS);
    }

    @Bean
    LegalDatabaseBoundaryMarker.Guard legalPublicRequirementsBoundaryGuard(
            List<LegalDatabaseBoundaryMarker> markers) {
        return new LegalDatabaseBoundaryMarker.Guard(
                markers, LegalDatabaseBoundaryMarker.Kind.PUBLIC_REQUIREMENTS);
    }

    @Bean(destroyMethod = "close")
    HikariDataSource legalPublicRequirementsPool(Environment environment) {
        String jdbcUrl = required(environment, "jdbc-url");
        requireBoundedPostgresUrl(jdbcUrl);
        HikariConfig config = new HikariConfig();
        config.setPoolName("legal-public-requirements");
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(required(environment, "username"));
        config.setPassword(required(environment, "password"));
        config.setDriverClassName("org.postgresql.Driver");
        config.setMinimumIdle(0);
        config.setMaximumPoolSize(2);
        config.setConnectionTimeout(LegalPublicRequirementsDataSource.BORROW_TIMEOUT_MILLIS);
        config.setValidationTimeout(1_000);
        config.setInitializationFailTimeout(-1);
        config.setReadOnly(false);
        config.addDataSourceProperty("connectTimeout", "1");
        config.addDataSourceProperty("loginTimeout", "1");
        config.addDataSourceProperty("socketTimeout", "5");
        config.addDataSourceProperty("cancelSignalTimeout", "1");
        config.addDataSourceProperty("ApplicationName", "ordenfix-legal-public-requirements");
        return new HikariDataSource(config);
    }

    @Bean(destroyMethod = "close")
    LegalPublicRequirementsDataSource legalPublicRequirementsDataSource(
            @Qualifier("legalPublicRequirementsPool") HikariDataSource pool) {
        return new LegalPublicRequirementsDataSource(pool, Duration.ofSeconds(15));
    }

    @Bean
    JdbcTemplate legalPublicRequirementsJdbc(LegalPublicRequirementsDataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    LegalDatabaseBudgets legalPublicRequirementsBudgets() {
        return new LegalDatabaseBudgets(15, 5, 1, 1);
    }

    @Bean
    DataSourceTransactionManager legalPublicRequirementsTransactionManager(
            LegalPublicRequirementsDataSource dataSource) {
        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
        manager.setRollbackOnCommitFailure(false);
        manager.setEnforceReadOnly(false);
        return manager;
    }

    @Bean
    TransactionTemplate legalPublicRequirementsTransactionTemplate(
            DataSourceTransactionManager manager, LegalDatabaseBudgets budgets) {
        TransactionTemplate template = new TransactionTemplate(manager);
        template.setName("legal-public-requirements");
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setReadOnly(false);
        template.setTimeout(budgets.transactionTimeoutSeconds());
        return template;
    }

    @Bean
    LegalV28AggregateSchemaVerifier legalPublicRequirementsSchemaVerifier(JdbcTemplate jdbc) {
        return new LegalV28AggregateSchemaVerifier(jdbc, "public");
    }

    @Bean
    LegalPublicRequirementsPrivilegeVerifier legalPublicRequirementsPrivilegeVerifier(
            JdbcTemplate jdbc, Environment environment) {
        return new LegalPublicRequirementsPrivilegeVerifier(jdbc, required(environment, "username"), "public");
    }

    @Bean
    LegalManifestDatabaseGate legalPublicRequirementsGate(
            TransactionTemplate template, JdbcTemplate jdbc, LegalDatabaseBudgets budgets,
            LegalV28AggregateSchemaVerifier schema, LegalPublicRequirementsPrivilegeVerifier privileges,
            LegalDatabaseBoundaryMarker.Guard guard) {
        LegalManifestDatabaseGate gate = new LegalManifestDatabaseGate(
                template, jdbc, budgets, List.of(schema, privileges));
        gate.requireExactPublicRequirementsBoundary(jdbc, schema, privileges);
        return gate;
    }

    @Bean
    LegalApplicableScopeResolver legalPublicRequirementsScopeResolver() {
        return new LegalApplicableScopeResolver();
    }

    @Bean
    LegalRequiredSetAggregateRevisionCalculator legalPublicRequirementsRevisionCalculator() {
        return new LegalRequiredSetAggregateRevisionCalculator();
    }

    @Bean
    LegalRequiredSetAggregateProvenanceCalculator legalPublicRequirementsProvenanceCalculator() {
        return new LegalRequiredSetAggregateProvenanceCalculator();
    }

    @Bean
    LegalRequiredSetAggregateReplayVerifier legalPublicRequirementsReplayVerifier(
            JdbcTemplate jdbc, LegalRequiredSetAggregateRevisionCalculator revisionCalculator,
            LegalRequiredSetAggregateProvenanceCalculator provenanceCalculator) {
        return new LegalRequiredSetAggregateReplayVerifier(jdbc, revisionCalculator, provenanceCalculator);
    }

    @Bean
    LegalRequiredSetAggregateStore legalPublicRequirementsStore(
            JdbcTemplate jdbc, LegalRequiredSetAggregateRevisionCalculator revisionCalculator,
            LegalRequiredSetAggregateProvenanceCalculator provenanceCalculator,
            LegalRequiredSetAggregateReplayVerifier replayVerifier) {
        return new LegalRequiredSetAggregateStore(jdbc, revisionCalculator, provenanceCalculator, replayVerifier);
    }

    @Bean
    LegalPublicRequirementsReader legalPublicRequirementsReader(JdbcTemplate jdbc) {
        return new LegalPublicRequirementsReader(jdbc);
    }

    @Bean
    LegalPublicRequirementsReadService legalPublicRequirementsReadService(
            JdbcTemplate jdbc, LegalPublicRequirementsDataSource dataSource, LegalManifestDatabaseGate gate,
            LegalApplicableScopeResolver resolver, LegalRequiredSetAggregateStore store,
            LegalPublicRequirementsReader reader, LegalV28AggregateSchemaVerifier schema,
            LegalPublicRequirementsPrivilegeVerifier privileges, LegalDatabaseBoundaryMarker.Guard guard) {
        return new LegalPublicRequirementsReadService(
                jdbc, dataSource, gate, resolver, store, reader, schema, privileges);
    }

    private static String required(Environment environment, String suffix) {
        String value = environment.getRequiredProperty(PROPERTY_PREFIX + suffix);
        if (value.isBlank()) {
            throw new IllegalArgumentException("La configuración de requisitos públicos requerida está vacía");
        }
        return value;
    }

    private static void requireBoundedPostgresUrl(String url) {
        if (!url.startsWith("jdbc:postgresql://") || url.indexOf('#') >= 0) {
            throw new IllegalArgumentException("Los requisitos públicos requieren una URL PostgreSQL explícita");
        }
        int query = url.indexOf('?');
        if (query >= 0) {
            for (String option : url.substring(query + 1).split("&")) {
                String key = URLDecoder.decode(option.split("=", 2)[0], StandardCharsets.UTF_8);
                if (!URL_OPTIONS.contains(key)) {
                    throw new IllegalArgumentException(
                            "La URL de requisitos públicos no admite reemplazar credenciales, driver o presupuestos");
                }
            }
        }
    }
}
