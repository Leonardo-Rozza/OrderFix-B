package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalApplicableScopeResolver;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateProvenanceCalculator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateRevisionCalculator;
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

/** Minimal, non-scannable Spring context for V28 aggregate materialization. */
@ConditionalOnProperty(
        name = LegalRequiredSetAggregateDatabaseConfiguration.ENABLED_PROPERTY,
        havingValue = "true")
@ImportAutoConfiguration({
        DataSourceAutoConfiguration.class,
        JdbcTemplateAutoConfiguration.class
})
public class LegalRequiredSetAggregateDatabaseConfiguration {

    public static final String ENABLED_PROPERTY =
            "ordenfix.legal.aggregate-context.enabled";
    public static final String SCHEMA_PROPERTY =
            "ordenfix.legal.aggregate-schema";

    @Bean
    LegalDatabaseBoundaryMarker legalAggregateDatabaseBoundaryMarker() {
        return new LegalDatabaseBoundaryMarker(LegalDatabaseBoundaryMarker.Kind.AGGREGATE);
    }

    @Bean
    LegalDatabaseBoundaryMarker.Guard legalAggregateDatabaseBoundaryGuard(
            List<LegalDatabaseBoundaryMarker> markers) {
        return new LegalDatabaseBoundaryMarker.Guard(
                markers,
                LegalDatabaseBoundaryMarker.Kind.AGGREGATE);
    }

    @Bean
    LegalDatabaseBudgets legalDatabaseBudgets() {
        return LegalDatabaseBudgets.production();
    }

    @Bean
    DataSourceTransactionManager legalAggregateTransactionManager(DataSource dataSource) {
        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
        manager.setRollbackOnCommitFailure(false);
        return manager;
    }

    @Bean
    TransactionTemplate legalAggregateTransactionTemplate(
            DataSourceTransactionManager legalAggregateTransactionManager,
            LegalDatabaseBudgets legalDatabaseBudgets) {
        TransactionTemplate transaction = new TransactionTemplate(
                legalAggregateTransactionManager);
        transaction.setName("legal-required-set-aggregate");
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(legalDatabaseBudgets.transactionTimeoutSeconds());
        transaction.setReadOnly(false);
        return transaction;
    }

    @Bean
    LegalV28AggregateSchemaVerifier legalV28AggregateSchemaVerifier(
            JdbcTemplate jdbcTemplate,
            @Value("${" + SCHEMA_PROPERTY + ":"
                    + LegalV28AggregateInventory.DEFAULT_SCHEMA + "}") String expectedSchema) {
        return new LegalV28AggregateSchemaVerifier(jdbcTemplate, expectedSchema);
    }

    @Bean
    LegalV28AggregatePrivilegeVerifier legalV28AggregatePrivilegeVerifier(
            JdbcTemplate jdbcTemplate,
            @Value("${spring.datasource.username}") String expectedRole,
            @Value("${" + SCHEMA_PROPERTY + ":"
                    + LegalV28AggregateInventory.DEFAULT_SCHEMA + "}") String expectedSchema) {
        return new LegalV28AggregatePrivilegeVerifier(
                jdbcTemplate,
                expectedRole,
                expectedSchema);
    }

    @Bean
    LegalManifestDatabaseGate legalAggregateDatabaseGate(
            TransactionTemplate legalAggregateTransactionTemplate,
            JdbcTemplate jdbcTemplate,
            LegalDatabaseBudgets legalDatabaseBudgets,
            LegalV28AggregateSchemaVerifier schemaVerifier,
            LegalV28AggregatePrivilegeVerifier privilegeVerifier) {
        return new LegalManifestDatabaseGate(
                legalAggregateTransactionTemplate,
                jdbcTemplate,
                legalDatabaseBudgets,
                List.of(schemaVerifier, privilegeVerifier));
    }

    @Bean
    LegalApplicableScopeResolver legalApplicableScopeResolver() {
        return new LegalApplicableScopeResolver();
    }

    @Bean
    LegalRequiredSetAggregateService legalRequiredSetAggregateService(
            LegalApplicableScopeResolver resolver,
            JdbcTemplate jdbcTemplate,
            LegalManifestDatabaseGate legalAggregateDatabaseGate,
            LegalRequiredSetAggregateStore store,
            LegalV28AggregateSchemaVerifier schemaVerifier,
            LegalV28AggregatePrivilegeVerifier privilegeVerifier,
            LegalDatabaseBoundaryMarker.Guard legalAggregateDatabaseBoundaryGuard) {
        return new LegalRequiredSetAggregateService(
                resolver,
                jdbcTemplate,
                legalAggregateDatabaseGate,
                store,
                schemaVerifier,
                privilegeVerifier);
    }

    @Bean
    LegalRequiredSetAggregateRevisionCalculator legalRequiredSetAggregateRevisionCalculator() {
        return new LegalRequiredSetAggregateRevisionCalculator();
    }

    @Bean
    LegalRequiredSetAggregateProvenanceCalculator legalRequiredSetAggregateProvenanceCalculator() {
        return new LegalRequiredSetAggregateProvenanceCalculator();
    }

    @Bean
    LegalRequiredSetAggregateReplayVerifier legalRequiredSetAggregateReplayVerifier(
            JdbcTemplate jdbcTemplate,
            LegalRequiredSetAggregateRevisionCalculator revisionCalculator,
            LegalRequiredSetAggregateProvenanceCalculator provenanceCalculator) {
        return new LegalRequiredSetAggregateReplayVerifier(
                jdbcTemplate,
                revisionCalculator,
                provenanceCalculator);
    }

    @Bean
    LegalRequiredSetAggregateStore legalRequiredSetAggregateStore(
            JdbcTemplate jdbcTemplate,
            LegalRequiredSetAggregateRevisionCalculator revisionCalculator,
            LegalRequiredSetAggregateProvenanceCalculator provenanceCalculator,
            LegalRequiredSetAggregateReplayVerifier replayVerifier) {
        return new LegalRequiredSetAggregateStore(
                jdbcTemplate,
                revisionCalculator,
                provenanceCalculator,
                replayVerifier);
    }
}
