package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalDocumentSetRevisionCalculator;
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

/** Explicit non-scannable composition: no web datasource fallback, Flyway or HTTP registration. */
@ConditionalOnProperty(name = LegalPublicDocumentReadDatabaseConfiguration.ENABLED_PROPERTY,
        havingValue = "true")
public class LegalPublicDocumentReadDatabaseConfiguration {

    public static final String ENABLED_PROPERTY = "ordenfix.legal.public-document-read-context.enabled";
    public static final String PROPERTY_PREFIX = "ordenfix.legal.public-document-read.";
    private static final Set<String> URL_OPTIONS = Set.of(
            "sslmode", "sslrootcert", "sslcert", "sslkey", "sslpassword", "loggerLevel");

    @Bean
    LegalDatabaseBoundaryMarker legalPublicDocumentBoundaryMarker() {
        return new LegalDatabaseBoundaryMarker(LegalDatabaseBoundaryMarker.Kind.PUBLIC_DOCUMENT_READ);
    }

    @Bean
    LegalDatabaseBoundaryMarker.Guard legalPublicDocumentBoundaryGuard(
            List<LegalDatabaseBoundaryMarker> markers) {
        return new LegalDatabaseBoundaryMarker.Guard(
                markers, LegalDatabaseBoundaryMarker.Kind.PUBLIC_DOCUMENT_READ);
    }

    @Bean(destroyMethod = "close")
    HikariDataSource legalPublicDocumentPool(Environment environment) {
        String jdbcUrl = required(environment, "jdbc-url");
        requireBoundedPostgresUrl(jdbcUrl);
        HikariConfig config = new HikariConfig();
        config.setPoolName("legal-public-document-read");
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(required(environment, "username"));
        config.setPassword(required(environment, "password"));
        config.setDriverClassName("org.postgresql.Driver");
        config.setMinimumIdle(0);
        config.setMaximumPoolSize(2);
        config.setConnectionTimeout(LegalPublicDocumentDataSource.BORROW_TIMEOUT_MILLIS);
        config.setValidationTimeout(1_000);
        config.setInitializationFailTimeout(-1);
        config.setReadOnly(true);
        config.addDataSourceProperty("connectTimeout", "1");
        config.addDataSourceProperty("loginTimeout", "1");
        config.addDataSourceProperty("socketTimeout", "5");
        config.addDataSourceProperty("cancelSignalTimeout", "1");
        config.addDataSourceProperty("ApplicationName", "ordenfix-legal-public-document-read");
        return new HikariDataSource(config);
    }

    @Bean(destroyMethod = "close")
    LegalPublicDocumentDataSource legalPublicDocumentDataSource(
            @Qualifier("legalPublicDocumentPool") HikariDataSource pool) {
        return new LegalPublicDocumentDataSource(pool, Duration.ofSeconds(15));
    }

    @Bean
    JdbcTemplate legalPublicDocumentJdbc(LegalPublicDocumentDataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    LegalDatabaseBudgets legalPublicDocumentBudgets() {
        return new LegalDatabaseBudgets(15, 5, 1, 1);
    }

    @Bean
    DataSourceTransactionManager legalPublicDocumentTransactionManager(LegalPublicDocumentDataSource dataSource) {
        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
        manager.setRollbackOnCommitFailure(false);
        manager.setEnforceReadOnly(true);
        return manager;
    }

    @Bean
    TransactionTemplate legalPublicDocumentTransactionTemplate(
            DataSourceTransactionManager manager, LegalDatabaseBudgets budgets) {
        TransactionTemplate template = new TransactionTemplate(manager);
        template.setName("legal-public-document-read");
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setReadOnly(true);
        template.setTimeout(budgets.transactionTimeoutSeconds());
        return template;
    }

    @Bean
    LegalV28AggregateSchemaVerifier legalPublicDocumentSchemaVerifier(JdbcTemplate jdbc) {
        return new LegalV28AggregateSchemaVerifier(jdbc, "public");
    }

    @Bean
    LegalPublicDocumentPrivilegeVerifier legalPublicDocumentPrivilegeVerifier(
            JdbcTemplate jdbc, Environment environment) {
        return new LegalPublicDocumentPrivilegeVerifier(jdbc, required(environment, "username"), "public");
    }

    @Bean
    LegalManifestDatabaseGate legalPublicDocumentGate(TransactionTemplate template, JdbcTemplate jdbc,
            LegalDatabaseBudgets budgets, LegalV28AggregateSchemaVerifier schema,
            LegalPublicDocumentPrivilegeVerifier privileges) {
        return new LegalManifestDatabaseGate(template, jdbc, budgets, List.of(schema, privileges));
    }

    @Bean
    LegalPublicDocumentReader legalPublicDocumentReader(JdbcTemplate jdbc) {
        return new LegalPublicDocumentReader(jdbc, new LegalDocumentSetRevisionCalculator());
    }

    @Bean
    LegalPublicDocumentReadService legalPublicDocumentReadService(
            JdbcTemplate jdbc, LegalPublicDocumentDataSource dataSource, LegalManifestDatabaseGate gate,
            LegalPublicDocumentReader reader, LegalV28AggregateSchemaVerifier schema,
            LegalPublicDocumentPrivilegeVerifier privileges, LegalDatabaseBoundaryMarker.Guard guard) {
        return new LegalPublicDocumentReadService(jdbc, dataSource, gate, reader, schema, privileges);
    }

    private static String required(Environment environment, String suffix) {
        String value = environment.getRequiredProperty(PROPERTY_PREFIX + suffix);
        if (value.isBlank()) {
            throw new IllegalArgumentException("La configuración documental requerida está vacía");
        }
        return value;
    }

    private static void requireBoundedPostgresUrl(String url) {
        if (!url.startsWith("jdbc:postgresql://") || url.indexOf('#') >= 0) {
            throw new IllegalArgumentException("El lector requiere una URL PostgreSQL explícita");
        }
        int query = url.indexOf('?');
        if (query >= 0) {
            for (String option : url.substring(query + 1).split("&")) {
                String key = URLDecoder.decode(option.split("=", 2)[0], StandardCharsets.UTF_8);
                if (!URL_OPTIONS.contains(key)) {
                    throw new IllegalArgumentException(
                            "La URL documental no admite reemplazar credenciales, driver o presupuestos");
                }
            }
        }
    }
}
