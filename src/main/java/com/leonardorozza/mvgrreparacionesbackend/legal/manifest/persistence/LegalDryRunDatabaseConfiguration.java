package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetRevisionCalculator;

import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

/** Minimal non-web Spring context used exclusively by the legal manifest dry-run. */
@ConditionalOnProperty(
        name = LegalDryRunDatabaseConfiguration.ENABLED_PROPERTY,
        havingValue = "true")
@ImportAutoConfiguration({
        DataSourceAutoConfiguration.class,
        JdbcTemplateAutoConfiguration.class
})
public class LegalDryRunDatabaseConfiguration {

    public static final String ENABLED_PROPERTY = "ordenfix.legal.dry-run-context.enabled";
    static final int TRANSACTION_TIMEOUT_SECONDS = 45;

    @Bean
    JdbcTransactionManager legalDryRunTransactionManager(DataSource dataSource) {
        return new JdbcTransactionManager(dataSource);
    }

    @Bean
    TransactionTemplate legalDryRunTransactionTemplate(
            JdbcTransactionManager legalDryRunTransactionManager) {
        TransactionTemplate transaction = new TransactionTemplate(
                legalDryRunTransactionManager);
        transaction.setName("legal-manifest-dry-run");
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(TRANSACTION_TIMEOUT_SECONDS);
        transaction.setReadOnly(false);
        return transaction;
    }

    @Bean
    LegalV27SchemaVerifier legalV27SchemaVerifier(JdbcTemplate jdbcTemplate) {
        return new LegalV27SchemaVerifier(jdbcTemplate);
    }

    @Bean
    LegalDatabaseFailureMapper legalDatabaseFailureMapper() {
        return new LegalDatabaseFailureMapper();
    }

    @Bean
    LegalRequiredSetRevisionCalculator legalRequiredSetRevisionCalculator() {
        return new LegalRequiredSetRevisionCalculator();
    }

    @Bean
    LegalDryRunPersistence legalDryRunPersistence(
            JdbcTemplate jdbcTemplate,
            LegalRequiredSetRevisionCalculator legalRequiredSetRevisionCalculator) {
        return new LegalDryRunPersistence(
                jdbcTemplate,
                legalRequiredSetRevisionCalculator);
    }

    @Bean
    LegalManifestDryRunService legalManifestDryRunService(
            TransactionTemplate legalDryRunTransactionTemplate,
            LegalV27SchemaVerifier legalV27SchemaVerifier,
            LegalDryRunPersistence legalDryRunPersistence,
            LegalDatabaseFailureMapper legalDatabaseFailureMapper) {
        return new LegalManifestDryRunService(
                legalDryRunTransactionTemplate,
                legalV27SchemaVerifier,
                legalDryRunPersistence,
                legalDatabaseFailureMapper);
    }
}
