package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

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

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Clock;
import java.time.LocalDateTime;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/** Explicit non-scannable context for registration infrastructure; no web fallback, Flyway or HTTP. */
@Conditional(LegalRegistrationDatabaseConfiguration.Enabled.class)
public class LegalRegistrationDatabaseConfiguration {

    public static final String ENABLED_PROPERTY = "ordenfix.legal.registration-consent.enabled";
    public static final String PROPERTY_PREFIX = "ordenfix.legal.registration-consent.";
    private static final Set<String> URL_OPTIONS = Set.of(
            "sslmode", "sslrootcert", "sslcert", "sslkey", "sslpassword", "loggerLevel");

    @Bean
    LegalDatabaseBoundaryMarker legalRegistrationBoundaryMarker() {
        return new LegalDatabaseBoundaryMarker(LegalDatabaseBoundaryMarker.Kind.REGISTRATION);
    }

    @Bean
    LegalDatabaseBoundaryMarker.Guard legalRegistrationBoundaryGuard(
            List<LegalDatabaseBoundaryMarker> markers) {
        return new LegalDatabaseBoundaryMarker.Guard(
                markers, LegalDatabaseBoundaryMarker.Kind.REGISTRATION);
    }

    @Bean(destroyMethod = "close")
    HikariDataSource legalRegistrationPool(Environment environment, LegalAcceptanceKeyConfiguration keys) {
        String jdbcUrl = required(environment, "jdbc-url");
        requireBoundedPostgresUrl(jdbcUrl);
        HikariConfig config = new HikariConfig();
        config.setPoolName("legal-registration-consent");
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
        config.addDataSourceProperty("ApplicationName", "ordenfix-legal-registration-consent");
        return new HikariDataSource(config);
    }

    @Bean(destroyMethod = "close")
    LegalPrivateRequirementsDataSource legalRegistrationDataSource(
            @Qualifier("legalRegistrationPool") HikariDataSource pool) {
        // Keep SQL/idempotency waits at five seconds, with bounded time to receive their PG result.
        return LegalPrivateRequirementsDataSource.registration(pool);
    }

    @Bean
    JdbcTemplate legalRegistrationJdbc(LegalPrivateRequirementsDataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    LegalDatabaseBudgets legalRegistrationBudgets() {
        return new LegalDatabaseBudgets(25, 5, 1, 1);
    }

    @Bean
    DataSourceTransactionManager legalRegistrationTransactionManager(
            LegalPrivateRequirementsDataSource dataSource) {
        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
        manager.setRollbackOnCommitFailure(false);
        manager.setEnforceReadOnly(false);
        return manager;
    }

    @Bean
    TransactionTemplate legalRegistrationTransactionTemplate(
            DataSourceTransactionManager manager, LegalDatabaseBudgets budgets) {
        TransactionTemplate template = new TransactionTemplate(manager);
        template.setName("legal-registration-consent");
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setReadOnly(false);
        template.setTimeout(budgets.transactionTimeoutSeconds());
        return template;
    }

    @Bean
    LegalRegistrationSchemaVerifier legalRegistrationSchemaVerifier(JdbcTemplate jdbc) {
        return new LegalRegistrationSchemaVerifier(jdbc, "public");
    }

    @Bean
    LegalRegistrationPrivilegeVerifier legalRegistrationPrivilegeVerifier(
            JdbcTemplate jdbc, Environment environment) {
        return new LegalRegistrationPrivilegeVerifier(jdbc, required(environment, "username"), "public");
    }

    @Bean
    LegalRegistrationTransactionBoundary legalRegistrationBoundary(
            TransactionTemplate template, JdbcTemplate jdbc, LegalPrivateRequirementsDataSource dataSource,
            LegalDatabaseBudgets budgets, LegalRegistrationSchemaVerifier schema,
            LegalRegistrationPrivilegeVerifier privileges, LegalDatabaseBoundaryMarker.Guard guard) {
        return new LegalRegistrationTransactionBoundary(jdbc, dataSource, template, budgets, schema, privileges);
    }

    @Bean
    LegalAcceptanceKeyConfiguration legalRegistrationKeyConfiguration(Environment environment) {
        return LegalAcceptanceKeyConfiguration.from(environment);
    }

    @Bean
    LegalRegistrationPreparation legalRegistrationPreparation(Environment environment) {
        // Preserve the current UTC trial clock and JVM-local JPA audit semantics separately.
        return new LegalRegistrationPreparation(new BCryptPasswordEncoder(), Clock.systemUTC(),
                LocalDateTime::now, environment.getProperty("plan.trial-dias", Integer.class, 14));
    }

    @Bean
    LegalIdempotencyResultStore legalRegistrationResults(JdbcTemplate jdbc) {
        return new LegalIdempotencyResultStore(jdbc);
    }

    @Bean
    LegalRegistrationWriter legalRegistrationWriter(JdbcTemplate jdbc, LegalAcceptanceKeyConfiguration keys,
                                                    LegalIdempotencyResultStore results) {
        return new LegalRegistrationWriter(jdbc, keys.codec(), keys.retentionPolicy(), results);
    }

    private static String required(Environment environment, String suffix) {
        String value = environment.getRequiredProperty(PROPERTY_PREFIX + suffix);
        if (value.isBlank()) {
            throw new IllegalArgumentException("La configuración de registro requerida está vacía");
        }
        return value;
    }

    private static void requireBoundedPostgresUrl(String url) {
        if (!url.startsWith("jdbc:postgresql://") || url.indexOf('#') >= 0) {
            throw new IllegalArgumentException("El registro requiere una URL PostgreSQL explícita");
        }
        int query = url.indexOf('?');
        if (query >= 0) {
            for (String option : url.substring(query + 1).split("&")) {
                String key = URLDecoder.decode(option.split("=", 2)[0], StandardCharsets.UTF_8);
                if (!URL_OPTIONS.contains(key)) {
                    throw new IllegalArgumentException(
                            "La URL de registro no admite reemplazar credenciales, driver o presupuestos");
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
                throw new IllegalArgumentException("El flag de registro legal requiere true o false exactos");
            }
            return true;
        }
    }

}
