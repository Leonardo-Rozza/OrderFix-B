package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetRevisionCalculator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;

/** Minimal, non-scannable Spring context that is solely allowed to assemble the importer. */
@ConditionalOnProperty(
        name = LegalImportDatabaseConfiguration.ENABLED_PROPERTY,
        havingValue = "true")
@ImportAutoConfiguration({
        DataSourceAutoConfiguration.class,
        JdbcTemplateAutoConfiguration.class
})
public class LegalImportDatabaseConfiguration {

    public static final String ENABLED_PROPERTY =
            "ordenfix.legal.import-context.enabled";
    public static final String SCHEMA_PROPERTY =
            "ordenfix.legal.import-schema";

    @Bean
    LegalDatabaseBoundaryMarker legalImportDatabaseBoundaryMarker() {
        return new LegalDatabaseBoundaryMarker(LegalDatabaseBoundaryMarker.Kind.IMPORT);
    }

    @Bean
    LegalDatabaseBoundaryMarker.Guard legalImportDatabaseBoundaryGuard(
            List<LegalDatabaseBoundaryMarker> markers) {
        return new LegalDatabaseBoundaryMarker.Guard(
                markers,
                LegalDatabaseBoundaryMarker.Kind.IMPORT);
    }

    @Bean
    LegalDatabaseBudgets legalDatabaseBudgets() {
        return LegalDatabaseBudgets.production();
    }

    @Bean
    DataSourceTransactionManager legalImportTransactionManager(DataSource dataSource) {
        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
        manager.setRollbackOnCommitFailure(false);
        return manager;
    }

    @Bean
    TransactionTemplate legalImportTransactionTemplate(
            DataSourceTransactionManager legalImportTransactionManager,
            LegalDatabaseBudgets legalDatabaseBudgets) {
        TransactionTemplate transaction = new TransactionTemplate(
                legalImportTransactionManager);
        transaction.setName("legal-manifest-import");
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(legalDatabaseBudgets.transactionTimeoutSeconds());
        transaction.setReadOnly(false);
        return transaction;
    }

    @Bean
    LegalV27ImportSchemaVerifier legalV27ImportSchemaVerifier(
            JdbcTemplate jdbcTemplate,
            @Value("${" + SCHEMA_PROPERTY + ":"
                    + LegalV27ImportInventory.DEFAULT_SCHEMA + "}") String expectedSchema) {
        return new LegalV27ImportSchemaVerifier(jdbcTemplate, expectedSchema);
    }

    @Bean
    LegalImportPrivilegeVerifier legalImportPrivilegeVerifier(
            JdbcTemplate jdbcTemplate,
            @Value("${spring.datasource.username}") String expectedRole,
            @Value("${" + SCHEMA_PROPERTY + ":"
                    + LegalV27ImportInventory.DEFAULT_SCHEMA + "}") String expectedSchema) {
        return new LegalImportPrivilegeVerifier(
                jdbcTemplate,
                expectedRole,
                expectedSchema);
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
    LegalManifestReplayVerifier legalManifestReplayVerifier(
            JdbcTemplate jdbcTemplate,
            LegalRequiredSetRevisionCalculator legalRequiredSetRevisionCalculator) {
        return new LegalManifestReplayVerifier(
                jdbcTemplate,
                legalRequiredSetRevisionCalculator);
    }

    @Bean
    LegalImportFailureMapper legalImportFailureMapper() {
        return new LegalImportFailureMapper(new LegalDatabaseFailureMapper());
    }

    @Bean
    LegalManifestDatabaseGate legalManifestDatabaseGate(
            TransactionTemplate legalImportTransactionTemplate,
            JdbcTemplate jdbcTemplate,
            LegalDatabaseBudgets legalDatabaseBudgets,
            LegalV27ImportSchemaVerifier legalV27ImportSchemaVerifier,
            LegalImportPrivilegeVerifier legalImportPrivilegeVerifier) {
        return new LegalManifestDatabaseGate(
                legalImportTransactionTemplate,
                jdbcTemplate,
                legalDatabaseBudgets,
                List.of(
                        legalV27ImportSchemaVerifier,
                        legalImportPrivilegeVerifier));
    }

    @Bean
    LegalManifestImportService legalManifestImportService(
            LegalManifestDatabaseGate legalManifestDatabaseGate,
            JdbcTemplate jdbcTemplate,
            LegalManifestGraphWriter legalManifestGraphWriter,
            LegalManifestReplayVerifier legalManifestReplayVerifier,
            LegalImportFailureMapper legalImportFailureMapper,
            LegalV27ImportSchemaVerifier legalV27ImportSchemaVerifier,
            LegalImportPrivilegeVerifier legalImportPrivilegeVerifier) {
        return new LegalManifestImportService(
                legalManifestDatabaseGate,
                jdbcTemplate,
                legalManifestGraphWriter,
                legalManifestReplayVerifier,
                legalImportFailureMapper,
                legalV27ImportSchemaVerifier,
                legalImportPrivilegeVerifier);
    }
}
