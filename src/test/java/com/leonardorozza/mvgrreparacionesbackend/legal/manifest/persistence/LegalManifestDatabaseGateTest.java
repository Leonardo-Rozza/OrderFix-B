package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LegalManifestDatabaseGateTest {

    @Test
    void ordersTimeoutsPreflightsEditorialLockAndCallback() {
        TransactionTemplate transaction = executingTransaction();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        LegalDatabasePreflight schema = mock(LegalDatabasePreflight.class);
        LegalDatabasePreflight privileges = mock(LegalDatabasePreflight.class);
        LegalDatabasePreflight lateMutation = mock(LegalDatabasePreflight.class);
        List<LegalDatabasePreflight> preflights = new ArrayList<>(List.of(schema, privileges));
        TransactionCallback<String> callback = mock(TransactionCallback.class);
        when(callback.doInTransaction(any())).thenReturn("receipt");
        LegalManifestDatabaseGate gate = new LegalManifestDatabaseGate(
                transaction,
                jdbc,
                LegalDatabaseBudgets.production(),
                preflights);
        preflights.add(lateMutation);

        String result = gate.execute(callback);

        assertThat(result).isEqualTo("receipt");
        InOrder order = inOrder(jdbc, schema, privileges, callback);
        order.verify(jdbc).execute("SET LOCAL statement_timeout TO '30s'");
        order.verify(schema).verify();
        order.verify(privileges).verify();
        order.verify(jdbc).execute("SET LOCAL lock_timeout TO '30s'");
        order.verify(jdbc).queryForList(
                org.mockito.ArgumentMatchers.contains("pg_advisory_xact_lock"),
                eq(LegalManifestDatabaseGate.EDITORIAL_LOCK_NAME));
        order.verify(jdbc).execute("SET LOCAL lock_timeout TO '5s'");
        order.verify(callback).doInTransaction(any());
        verifyNoInteractions(lateMutation);
    }

    @Test
    void failedPreflightEscapesAndPreventsLockAndCallback() {
        TransactionTemplate transaction = executingTransaction();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        LegalDatabasePreflight failing = mock(LegalDatabasePreflight.class);
        TransactionCallback<String> callback = mock(TransactionCallback.class);
        IllegalStateException failure = new IllegalStateException("schema drift");
        org.mockito.Mockito.doThrow(failure).when(failing).verify();
        LegalManifestDatabaseGate gate = new LegalManifestDatabaseGate(
                transaction,
                jdbc,
                LegalDatabaseBudgets.production(),
                List.of(failing));

        assertThatThrownBy(() -> gate.execute(callback)).isSameAs(failure);

        verify(jdbc).execute("SET LOCAL statement_timeout TO '30s'");
        verify(jdbc, never()).queryForList(
                anyString(),
                org.mockito.ArgumentMatchers.<Object[]>any());
        verifyNoInteractions(callback);
    }

    @Test
    void callbackFailureEscapesWithoutBeingMappedByTheGate() {
        TransactionTemplate transaction = executingTransaction();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TransactionCallback<String> callback = mock(TransactionCallback.class);
        IllegalArgumentException failure = new IllegalArgumentException("writer failure");
        when(callback.doInTransaction(any())).thenThrow(failure);
        LegalManifestDatabaseGate gate = new LegalManifestDatabaseGate(
                transaction,
                jdbc,
                LegalDatabaseBudgets.production(),
                List.of());

        assertThatThrownBy(() -> gate.execute(callback)).isSameAs(failure);
    }

    @Test
    void acceptsTheCommitOutcomeSafeManagerRequiredByImport() {
        DataSource dataSource = mock(DataSource.class);
        DataSourceTransactionManager manager = new DataSourceTransactionManager(
                dataSource);
        TransactionTemplate transaction = safeImportTransaction(manager);
        LegalManifestDatabaseGate gate = new LegalManifestDatabaseGate(
                transaction,
                new JdbcTemplate(dataSource),
                LegalDatabaseBudgets.production(),
                List.of());

        gate.requireCommitOutcomeSafe();
    }

    @Test
    void rejectsManagersThatCanMisclassifyAnAmbiguousCommit() {
        DataSource dataSource = mock(DataSource.class);
        JdbcTransactionManager translatedCommitFailures = new JdbcTransactionManager(dataSource);
        DataSourceTransactionManager rollbackAfterCommitFailure =
                new DataSourceTransactionManager(dataSource);
        rollbackAfterCommitFailure.setRollbackOnCommitFailure(true);

        assertThatThrownBy(() -> gateFor(translatedCommitFailures)
                .requireCommitOutcomeSafe())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> gateFor(rollbackAfterCommitFailure)
                .requireCommitOutcomeSafe())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAJoiningOrMisconfiguredTransactionTemplate() {
        DataSource dataSource = mock(DataSource.class);
        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
        TransactionTemplate joining = safeImportTransaction(manager);
        joining.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        TransactionTemplate wrongIsolation = safeImportTransaction(manager);
        wrongIsolation.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        TransactionTemplate readOnly = safeImportTransaction(manager);
        readOnly.setReadOnly(true);
        TransactionTemplate wrongTimeout = safeImportTransaction(manager);
        wrongTimeout.setTimeout(74);

        assertUnsafeTemplate(joining, dataSource);
        assertUnsafeTemplate(wrongIsolation, dataSource);
        assertUnsafeTemplate(readOnly, dataSource);
        assertUnsafeTemplate(wrongTimeout, dataSource);
    }

    @Test
    void rejectsAJdbcTemplateBoundToAnotherDataSource() {
        DataSource transactionDataSource = mock(DataSource.class);
        DataSource jdbcDataSource = mock(DataSource.class);
        DataSourceTransactionManager manager =
                new DataSourceTransactionManager(transactionDataSource);
        LegalManifestDatabaseGate gate = new LegalManifestDatabaseGate(
                safeImportTransaction(manager),
                new JdbcTemplate(jdbcDataSource),
                LegalDatabaseBudgets.production(),
                List.of());

        assertThatThrownBy(gate::requireCommitOutcomeSafe)
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertUnsafeTemplate(
            TransactionTemplate transaction,
            DataSource dataSource) {
        LegalManifestDatabaseGate gate = new LegalManifestDatabaseGate(
                transaction,
                new JdbcTemplate(dataSource),
                LegalDatabaseBudgets.production(),
                List.of());
        assertThatThrownBy(gate::requireCommitOutcomeSafe)
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static LegalManifestDatabaseGate gateFor(
            DataSourceTransactionManager transactionManager) {
        DataSource dataSource = transactionManager.getDataSource();
        return new LegalManifestDatabaseGate(
                safeImportTransaction(transactionManager),
                new JdbcTemplate(dataSource),
                LegalDatabaseBudgets.production(),
                List.of());
    }

    private static TransactionTemplate safeImportTransaction(
            DataSourceTransactionManager transactionManager) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(
                LegalDatabaseBudgets.production().transactionTimeoutSeconds());
        transaction.setReadOnly(false);
        return transaction;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static TransactionTemplate executingTransaction() {
        TransactionTemplate transaction = mock(TransactionTemplate.class);
        when(transaction.execute(any())).thenAnswer(invocation -> {
            TransactionCallback callback = invocation.getArgument(0);
            return callback.doInTransaction(new SimpleTransactionStatus());
        });
        return transaction;
    }
}
