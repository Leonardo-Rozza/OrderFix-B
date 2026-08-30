package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialStateFingerprintCalculator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetRevisionCalculator;
import org.springframework.beans.factory.annotation.Qualifier;
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

/**
 * Minimal non-web Spring context for offline editorial operations.
 *
 * <p>Read-only observations and commit-sensitive mutations deliberately use separate transaction
 * templates and gates over one datasource. Both execute the same ordered schema and privilege
 * preflights before acquiring the shared advisory lock.</p>
 */
@ConditionalOnProperty(
        name = LegalEditorialDatabaseConfiguration.ENABLED_PROPERTY,
        havingValue = "true")
@ImportAutoConfiguration({
        DataSourceAutoConfiguration.class,
        JdbcTemplateAutoConfiguration.class
})
public class LegalEditorialDatabaseConfiguration {

    public static final String ENABLED_PROPERTY =
            "ordenfix.legal.editorial-context.enabled";
    public static final String SCHEMA_PROPERTY =
            "ordenfix.legal.editorial-schema";

    static final String READ_ONLY_TRANSACTION = "legalEditorialReadOnlyTransaction";
    static final String MUTABLE_TRANSACTION = "legalEditorialMutableTransaction";
    static final String READ_ONLY_GATE = "legalEditorialReadOnlyGate";
    static final String MUTABLE_GATE = "legalEditorialMutableGate";

    @Bean
    LegalDatabaseBoundaryMarker legalEditorialDatabaseBoundaryMarker() {
        return new LegalDatabaseBoundaryMarker(LegalDatabaseBoundaryMarker.Kind.EDITORIAL);
    }

    @Bean
    LegalDatabaseBoundaryMarker.Guard legalEditorialDatabaseBoundaryGuard(
            List<LegalDatabaseBoundaryMarker> markers) {
        return new LegalDatabaseBoundaryMarker.Guard(
                markers,
                LegalDatabaseBoundaryMarker.Kind.EDITORIAL);
    }

    @Bean
    LegalDatabaseBudgets legalDatabaseBudgets() {
        return LegalDatabaseBudgets.production();
    }

    @Bean
    DataSourceTransactionManager legalEditorialTransactionManager(DataSource dataSource) {
        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
        manager.setRollbackOnCommitFailure(false);
        return manager;
    }

    @Bean(READ_ONLY_TRANSACTION)
    TransactionTemplate legalEditorialReadOnlyTransaction(
            DataSourceTransactionManager legalEditorialTransactionManager,
            LegalDatabaseBudgets legalDatabaseBudgets) {
        return transactionTemplate(
                "legal-editorial-read-only",
                legalEditorialTransactionManager,
                legalDatabaseBudgets,
                true);
    }

    @Bean(MUTABLE_TRANSACTION)
    TransactionTemplate legalEditorialMutableTransaction(
            DataSourceTransactionManager legalEditorialTransactionManager,
            LegalDatabaseBudgets legalDatabaseBudgets) {
        return transactionTemplate(
                "legal-editorial-mutable",
                legalEditorialTransactionManager,
                legalDatabaseBudgets,
                false);
    }

    @Bean
    LegalEditorialSchemaVerifier legalEditorialSchemaVerifier(
            JdbcTemplate jdbcTemplate,
            @Value("${" + SCHEMA_PROPERTY + ":"
                    + LegalV27EditorialInventory.DEFAULT_SCHEMA + "}") String expectedSchema) {
        return new LegalEditorialSchemaVerifier(jdbcTemplate, expectedSchema);
    }

    @Bean
    LegalEditorialPrivilegeVerifier legalEditorialPrivilegeVerifier(
            JdbcTemplate jdbcTemplate,
            @Value("${spring.datasource.username}") String expectedRole,
            @Value("${" + SCHEMA_PROPERTY + ":"
                    + LegalV27EditorialInventory.DEFAULT_SCHEMA + "}") String expectedSchema) {
        return new LegalEditorialPrivilegeVerifier(
                jdbcTemplate,
                expectedRole,
                expectedSchema);
    }

    @Bean(READ_ONLY_GATE)
    LegalManifestDatabaseGate legalEditorialReadOnlyGate(
            @Qualifier(READ_ONLY_TRANSACTION) TransactionTemplate transactionTemplate,
            JdbcTemplate jdbcTemplate,
            LegalDatabaseBudgets legalDatabaseBudgets,
            LegalEditorialSchemaVerifier schemaVerifier,
            LegalEditorialPrivilegeVerifier privilegeVerifier) {
        return databaseGate(
                transactionTemplate,
                jdbcTemplate,
                legalDatabaseBudgets,
                schemaVerifier,
                privilegeVerifier);
    }

    @Bean(MUTABLE_GATE)
    LegalManifestDatabaseGate legalEditorialMutableGate(
            @Qualifier(MUTABLE_TRANSACTION) TransactionTemplate transactionTemplate,
            JdbcTemplate jdbcTemplate,
            LegalDatabaseBudgets legalDatabaseBudgets,
            LegalEditorialSchemaVerifier schemaVerifier,
            LegalEditorialPrivilegeVerifier privilegeVerifier) {
        return databaseGate(
                transactionTemplate,
                jdbcTemplate,
                legalDatabaseBudgets,
                schemaVerifier,
                privilegeVerifier);
    }

    @Bean
    LegalRequiredSetRevisionCalculator legalRequiredSetRevisionCalculator() {
        return new LegalRequiredSetRevisionCalculator();
    }

    @Bean
    LegalEditorialStateFingerprintCalculator legalEditorialStateFingerprintCalculator() {
        return new LegalEditorialStateFingerprintCalculator();
    }

    @Bean
    LegalManifestOriginGraphVerifier legalManifestOriginGraphVerifier(
            JdbcTemplate jdbcTemplate,
            LegalRequiredSetRevisionCalculator requiredSetRevisionCalculator) {
        return new LegalManifestOriginGraphVerifier(
                jdbcTemplate,
                requiredSetRevisionCalculator);
    }

    @Bean
    LegalEditorialReadinessCore legalEditorialReadinessCore(
            JdbcTemplate jdbcTemplate,
            LegalManifestOriginGraphVerifier originGraphVerifier,
            LegalRequiredSetRevisionCalculator requiredSetRevisionCalculator,
            LegalEditorialStateFingerprintCalculator fingerprintCalculator) {
        return new LegalEditorialReadinessCore(
                jdbcTemplate,
                originGraphVerifier,
                requiredSetRevisionCalculator,
                fingerprintCalculator);
    }

    @Bean
    LegalEditorialPlannerCore legalEditorialPlannerCore(
            JdbcTemplate jdbcTemplate,
            LegalEditorialReadinessCore readinessCore,
            LegalManifestOriginGraphVerifier originGraphVerifier) {
        return new LegalEditorialPlannerCore(
                jdbcTemplate,
                readinessCore,
                originGraphVerifier);
    }

    @Bean
    LegalInitialPromotionCore legalInitialPromotionCore(JdbcTemplate jdbcTemplate) {
        return new LegalInitialPromotionCore(jdbcTemplate);
    }

    @Bean
    LegalDocumentReplacementWriter legalDocumentReplacementWriter(
            JdbcTemplate jdbcTemplate,
            LegalEditorialReadinessCore readinessCore) {
        return new LegalDocumentReplacementWriter(jdbcTemplate, readinessCore);
    }

    @Bean
    LegalEditorialPostStateVerifier legalEditorialPostStateVerifier(
            JdbcTemplate jdbcTemplate) {
        return new LegalEditorialPostStateVerifier(jdbcTemplate);
    }

    @Bean
    LegalEditorialFailureMapper legalEditorialFailureMapper() {
        return new LegalEditorialFailureMapper();
    }

    @Bean
    LegalEditorialReplaceScopeGuard legalEditorialReplaceScopeGuard() {
        return new LegalEditorialReplaceScopeGuard();
    }

    @Bean
    LegalEditorialReadinessService legalEditorialReadinessService(
            @Qualifier(READ_ONLY_GATE) LegalManifestDatabaseGate databaseGate,
            JdbcTemplate jdbcTemplate,
            LegalEditorialReadinessCore readinessCore,
            LegalEditorialFailureMapper failureMapper,
            LegalEditorialSchemaVerifier schemaVerifier,
            LegalEditorialPrivilegeVerifier privilegeVerifier) {
        return new LegalEditorialReadinessService(
                databaseGate,
                jdbcTemplate,
                readinessCore,
                failureMapper,
                schemaVerifier,
                privilegeVerifier);
    }

    @Bean
    LegalEditorialPlanService legalEditorialPlanService(
            @Qualifier(READ_ONLY_GATE) LegalManifestDatabaseGate databaseGate,
            JdbcTemplate jdbcTemplate,
            LegalEditorialPlannerCore plannerCore,
            LegalEditorialReplaceScopeGuard replaceScopeGuard,
            LegalEditorialFailureMapper failureMapper,
            LegalEditorialSchemaVerifier schemaVerifier,
            LegalEditorialPrivilegeVerifier privilegeVerifier) {
        return new LegalEditorialPlanService(
                databaseGate,
                jdbcTemplate,
                plannerCore,
                replaceScopeGuard,
                failureMapper,
                schemaVerifier,
                privilegeVerifier);
    }

    @Bean
    LegalEditorialApplyService legalEditorialApplyService(
            @Qualifier(MUTABLE_GATE) LegalManifestDatabaseGate databaseGate,
            JdbcTemplate jdbcTemplate,
            LegalEditorialPlannerCore plannerCore,
            LegalInitialPromotionCore promotionCore,
            LegalDocumentReplacementWriter replacementWriter,
            LegalEditorialPostStateVerifier postStateVerifier,
            LegalEditorialReadinessCore readinessCore,
            LegalEditorialReplaceScopeGuard replaceScopeGuard,
            LegalEditorialFailureMapper failureMapper,
            LegalEditorialSchemaVerifier schemaVerifier,
            LegalEditorialPrivilegeVerifier privilegeVerifier) {
        return new LegalEditorialApplyService(
                databaseGate,
                jdbcTemplate,
                plannerCore,
                promotionCore,
                replacementWriter,
                postStateVerifier,
                readinessCore,
                replaceScopeGuard,
                failureMapper,
                schemaVerifier,
                privilegeVerifier);
    }

    private static TransactionTemplate transactionTemplate(
            String name,
            DataSourceTransactionManager transactionManager,
            LegalDatabaseBudgets budgets,
            boolean readOnly) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setName(name);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(budgets.transactionTimeoutSeconds());
        transaction.setReadOnly(readOnly);
        return transaction;
    }

    private static LegalManifestDatabaseGate databaseGate(
            TransactionTemplate transactionTemplate,
            JdbcTemplate jdbcTemplate,
            LegalDatabaseBudgets budgets,
            LegalEditorialSchemaVerifier schemaVerifier,
            LegalEditorialPrivilegeVerifier privilegeVerifier) {
        return new LegalManifestDatabaseGate(
                transactionTemplate,
                jdbcTemplate,
                budgets,
                List.of(schemaVerifier, privilegeVerifier));
    }
}
