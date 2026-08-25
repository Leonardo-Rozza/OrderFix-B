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
import java.util.List;

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

    @Bean
    LegalDatabaseBudgets legalDatabaseBudgets() {
        return LegalDatabaseBudgets.production();
    }

    @Bean
    JdbcTransactionManager legalDryRunTransactionManager(DataSource dataSource) {
        return new JdbcTransactionManager(dataSource);
    }

    @Bean
    TransactionTemplate legalDryRunTransactionTemplate(
            JdbcTransactionManager legalDryRunTransactionManager,
            LegalDatabaseBudgets legalDatabaseBudgets) {
        TransactionTemplate transaction = new TransactionTemplate(
                legalDryRunTransactionManager);
        transaction.setName("legal-manifest-dry-run");
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(legalDatabaseBudgets.transactionTimeoutSeconds());
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
    LegalManifestGraphWriter legalManifestGraphWriter(
            JdbcTemplate jdbcTemplate,
            LegalRequiredSetRevisionCalculator legalRequiredSetRevisionCalculator) {
        return new LegalManifestGraphWriter(
                jdbcTemplate,
                legalRequiredSetRevisionCalculator);
    }

    @Bean
    LegalManifestDatabaseGate legalManifestDatabaseGate(
            TransactionTemplate legalDryRunTransactionTemplate,
            JdbcTemplate jdbcTemplate,
            LegalDatabaseBudgets legalDatabaseBudgets,
            LegalV27SchemaVerifier legalV27SchemaVerifier) {
        return new LegalManifestDatabaseGate(
                legalDryRunTransactionTemplate,
                jdbcTemplate,
                legalDatabaseBudgets,
                List.of(legalV27SchemaVerifier));
    }

    @Bean
    LegalManifestDryRunService legalManifestDryRunService(
            LegalManifestDatabaseGate legalManifestDatabaseGate,
            JdbcTemplate jdbcTemplate,
            LegalManifestGraphWriter legalManifestGraphWriter,
            LegalDatabaseFailureMapper legalDatabaseFailureMapper) {
        return new LegalManifestDryRunService(
                legalManifestDatabaseGate,
                jdbcTemplate,
                legalManifestGraphWriter,
                legalDatabaseFailureMapper);
    }
}
