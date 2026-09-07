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

/** Explicit non-scannable context for authenticated acceptance infrastructure; no web fallback, Flyway or HTTP. */
@Conditional(LegalAcceptanceDatabaseConfiguration.Enabled.class)
public class LegalAcceptanceDatabaseConfiguration {

    public static final String ENABLED_PROPERTY = "ordenfix.legal.account-acceptance.enabled";
    public static final String PROPERTY_PREFIX = "ordenfix.legal.account-acceptance.";
    private static final Set<String> URL_OPTIONS = Set.of(
            "sslmode", "sslrootcert", "sslcert", "sslkey", "sslpassword", "loggerLevel");

    @Bean
    LegalDatabaseBoundaryMarker legalAcceptanceBoundaryMarker() {
        return new LegalDatabaseBoundaryMarker(LegalDatabaseBoundaryMarker.Kind.ACCEPTANCE);
    }

    @Bean
    LegalDatabaseBoundaryMarker.Guard legalAcceptanceBoundaryGuard(
            List<LegalDatabaseBoundaryMarker> markers) {
        return new LegalDatabaseBoundaryMarker.Guard(
                markers, LegalDatabaseBoundaryMarker.Kind.ACCEPTANCE);
    }

    @Bean(destroyMethod = "close")
    HikariDataSource legalAcceptancePool(Environment environment, LegalAcceptanceKeyConfiguration keys) {
        String jdbcUrl = required(environment, "jdbc-url");
        requireBoundedPostgresUrl(jdbcUrl);
        HikariConfig config = new HikariConfig();
        config.setPoolName("legal-account-acceptance");
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
        config.addDataSourceProperty("socketTimeout", "6");
        config.addDataSourceProperty("cancelSignalTimeout", "1");
        config.addDataSourceProperty("ApplicationName", "ordenfix-legal-account-acceptance");
        return new HikariDataSource(config);
    }

    @Bean(destroyMethod = "close")
    LegalPrivateRequirementsDataSource legalAcceptanceDataSource(
            @Qualifier("legalAcceptancePool") HikariDataSource pool) {
        // Keep SQL/idempotency waits at five seconds, with bounded time to receive their PG result.
        return new LegalPrivateRequirementsDataSource(pool, Duration.ofSeconds(15), System::nanoTime, 1_000);
    }

    @Bean
    JdbcTemplate legalAcceptanceJdbc(LegalPrivateRequirementsDataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    LegalDatabaseBudgets legalAcceptanceBudgets() {
        return new LegalDatabaseBudgets(15, 5, 1, 1);
    }

    @Bean
    DataSourceTransactionManager legalAcceptanceTransactionManager(
            LegalPrivateRequirementsDataSource dataSource) {
        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
        manager.setRollbackOnCommitFailure(false);
        manager.setEnforceReadOnly(false);
        return manager;
    }

    @Bean
    TransactionTemplate legalAcceptanceTransactionTemplate(
            DataSourceTransactionManager manager, LegalDatabaseBudgets budgets) {
        TransactionTemplate template = new TransactionTemplate(manager);
        template.setName("legal-account-acceptance");
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setReadOnly(false);
        template.setTimeout(budgets.transactionTimeoutSeconds());
        return template;
    }

    @Bean
    LegalV29AcceptanceSchemaVerifier legalAcceptanceSchemaVerifier(JdbcTemplate jdbc) {
        return new LegalV29AcceptanceSchemaVerifier(jdbc, "public");
    }

    @Bean
    LegalAcceptancePrivilegeVerifier legalAcceptancePrivilegeVerifier(
            JdbcTemplate jdbc, Environment environment) {
        return new LegalAcceptancePrivilegeVerifier(jdbc, required(environment, "username"), "public");
    }

    @Bean
    LegalAcceptanceTransactionBoundary legalAcceptanceBoundary(
            TransactionTemplate template, JdbcTemplate jdbc, LegalPrivateRequirementsDataSource dataSource,
            LegalDatabaseBudgets budgets, LegalV29AcceptanceSchemaVerifier schema,
            LegalAcceptancePrivilegeVerifier privileges, LegalDatabaseBoundaryMarker.Guard guard) {
        return new LegalAcceptanceTransactionBoundary(jdbc, dataSource, template, budgets, schema, privileges);
    }

    @Bean
    LegalAcceptanceKeyConfiguration legalAcceptanceKeyConfiguration(Environment environment) {
        return LegalAcceptanceKeyConfiguration.from(environment);
    }

    @Bean
    LegalIdempotencyKeyring legalAcceptanceKeyring(LegalAcceptanceKeyConfiguration keys) {
        return keys.keyring();
    }

    @Bean
    LegalAcceptanceMetadataCodec legalAcceptanceMetadataCodec(LegalAcceptanceKeyConfiguration keys) {
        return keys.codec();
    }

    @Bean
    LegalAcceptanceMetadataPolicy legalAcceptanceMetadataPolicy(LegalAcceptanceKeyConfiguration keys) {
        return keys.retentionPolicy();
    }

    @Bean
    LegalIdempotencyResultStore legalAcceptanceResultStore(JdbcTemplate jdbc) {
        return new LegalIdempotencyResultStore(jdbc);
    }

    @Bean
    LegalIdempotencyCoordinator legalAcceptanceCoordinator(JdbcTemplate jdbc,
            LegalV29AcceptanceSchemaVerifier schema, LegalIdempotencyKeyring keyring,
            LegalIdempotencyResultStore store) {
        return new LegalIdempotencyCoordinator(jdbc, schema, keyring, store);
    }

    @Bean
    LegalApplicableScopeResolver legalAcceptanceScopeResolver() {
        return new LegalApplicableScopeResolver();
    }

    @Bean
    LegalRequiredSetAggregateRevisionCalculator legalAcceptanceRevisionCalculator() {
        return new LegalRequiredSetAggregateRevisionCalculator();
    }

    @Bean
    LegalRequiredSetAggregateProvenanceCalculator legalAcceptanceProvenanceCalculator() {
        return new LegalRequiredSetAggregateProvenanceCalculator();
    }

    @Bean
    LegalRequiredSetAggregateReplayVerifier legalAcceptanceReplayVerifier(
            JdbcTemplate jdbc, LegalRequiredSetAggregateRevisionCalculator revisionCalculator,
            LegalRequiredSetAggregateProvenanceCalculator provenanceCalculator) {
        return new LegalRequiredSetAggregateReplayVerifier(jdbc, revisionCalculator, provenanceCalculator);
    }

    @Bean
    LegalRequiredSetAggregateStore legalAcceptanceStore(
            JdbcTemplate jdbc, LegalRequiredSetAggregateRevisionCalculator revisionCalculator,
            LegalRequiredSetAggregateProvenanceCalculator provenanceCalculator,
            LegalRequiredSetAggregateReplayVerifier replayVerifier) {
        return new LegalRequiredSetAggregateStore(jdbc, revisionCalculator, provenanceCalculator, replayVerifier);
    }

    @Bean
    LegalActorSnapshotReader legalAcceptanceActorReader(JdbcTemplate jdbc) {
        return new LegalActorSnapshotReader(jdbc);
    }

    @Bean
    LegalPrivateRequirementsReader legalAcceptanceReader(JdbcTemplate jdbc) {
        return new LegalPrivateRequirementsReader(jdbc);
    }

    @Bean
    LegalAcceptanceEvidenceReader legalAcceptanceEvidenceReader(JdbcTemplate jdbc) {
        return new LegalAcceptanceEvidenceReader(jdbc);
    }

    @Bean
    LegalAcceptanceSelection legalAcceptanceSelection() {
        return new LegalAcceptanceSelection();
    }

    @Bean
    LegalAcceptanceService legalAcceptanceService(
            JdbcTemplate jdbc, LegalAcceptanceTransactionBoundary boundary,
            LegalApplicableScopeResolver resolver, LegalRequiredSetAggregateStore aggregates,
            LegalPrivateRequirementsReader requirements, LegalAcceptanceEvidenceReader evidence,
            LegalV29AcceptanceSchemaVerifier schema, LegalAcceptanceKeyConfiguration keys) {
        return new LegalAcceptanceService(jdbc, boundary, resolver, aggregates, requirements, evidence, schema, keys);
    }

    private static String required(Environment environment, String suffix) {
        String value = environment.getRequiredProperty(PROPERTY_PREFIX + suffix);
        if (value.isBlank()) {
            throw new IllegalArgumentException("La configuración de aceptaciones requerida está vacía");
        }
        return value;
    }

    private static void requireBoundedPostgresUrl(String url) {
        if (!url.startsWith("jdbc:postgresql://") || url.indexOf('#') >= 0) {
            throw new IllegalArgumentException("Las aceptaciones requieren una URL PostgreSQL explícita");
        }
        int query = url.indexOf('?');
        if (query >= 0) {
            for (String option : url.substring(query + 1).split("&")) {
                String key = URLDecoder.decode(option.split("=", 2)[0], StandardCharsets.UTF_8);
                if (!URL_OPTIONS.contains(key)) {
                    throw new IllegalArgumentException(
                            "La URL de aceptaciones no admite reemplazar credenciales, driver o presupuestos");
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
                throw new IllegalArgumentException("El flag de aceptación legal requiere true o false exactos");
            }
            if (!"true".equals(context.getEnvironment().getProperty(
                    LegalPrivateRequirementsDatabaseConfiguration.ENABLED_PROPERTY))) {
                throw new IllegalArgumentException("La aceptación legal requiere account-read=true");
            }
            return true;
        }
    }

}
