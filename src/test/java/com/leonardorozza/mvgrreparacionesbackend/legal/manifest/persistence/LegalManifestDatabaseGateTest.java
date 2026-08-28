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
import static org.assertj.core.api.Assertions.assertThatCode;
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
    void mutableGateAccreditsEffectiveModeBeforeTheSharedProtectedGraph() {
        DataSource dataSource = mock(DataSource.class);
        TransactionTemplate transaction = executingMutableTransaction(dataSource);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.getDataSource()).thenReturn(dataSource);
        when(jdbc.queryForObject(
                "SELECT pg_catalog.current_setting('transaction_isolation')",
                String.class)).thenReturn("read committed");
        when(jdbc.queryForObject(
                "SELECT pg_catalog.current_setting('transaction_read_only')",
                String.class)).thenReturn("off");
        LegalDatabasePreflight schema = mock(LegalDatabasePreflight.class);
        TransactionCallback<String> callback = mock(TransactionCallback.class);
        when(callback.doInTransaction(any())).thenReturn("receipt");
        LegalManifestDatabaseGate gate = new LegalManifestDatabaseGate(
                transaction,
                jdbc,
                LegalDatabaseBudgets.production(),
                List.of(schema));

        String result = gate.executeMutable(callback);

        assertThat(result).isEqualTo("receipt");
        InOrder order = inOrder(jdbc, schema, callback);
        order.verify(jdbc).execute("SET LOCAL statement_timeout TO '30s'");
        order.verify(jdbc).queryForObject(
                "SELECT pg_catalog.current_setting('transaction_isolation')",
                String.class);
        order.verify(jdbc).queryForObject(
                "SELECT pg_catalog.current_setting('transaction_read_only')",
                String.class);
        order.verify(schema).verify();
        order.verify(jdbc).execute("SET LOCAL lock_timeout TO '30s'");
        order.verify(jdbc).queryForList(
                org.mockito.ArgumentMatchers.contains("pg_advisory_xact_lock"),
                eq(LegalManifestDatabaseGate.EDITORIAL_LOCK_NAME));
        order.verify(jdbc).execute("SET LOCAL lock_timeout TO '5s'");
        order.verify(callback).doInTransaction(any());
    }

    @Test
    void mutableGateRejectsAnEffectiveReadOnlyOrForeignIsolationTransaction() {
        assertEffectiveMutableModeRejected("repeatable read", "off");
        assertEffectiveMutableModeRejected("read committed", "on");
        assertEffectiveMutableModeRejected(null, "off");
        assertEffectiveMutableModeRejected("read committed", null);
    }

    @Test
    void readOnlyGateAccreditsTheEffectiveModeBeforeTheSharedProtectedGraph() {
        DataSource dataSource = mock(DataSource.class);
        TransactionTemplate transaction = executingReadOnlyTransaction(dataSource);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.getDataSource()).thenReturn(dataSource);
        when(jdbc.queryForObject(
                "SELECT pg_catalog.current_setting('transaction_isolation')",
                String.class)).thenReturn("read committed");
        when(jdbc.queryForObject(
                "SELECT pg_catalog.current_setting('transaction_read_only')",
                String.class)).thenReturn("on");
        LegalDatabasePreflight schema = mock(LegalDatabasePreflight.class);
        TransactionCallback<String> callback = mock(TransactionCallback.class);
        when(callback.doInTransaction(any())).thenReturn("observation");
        LegalManifestDatabaseGate gate = new LegalManifestDatabaseGate(
                transaction,
                jdbc,
                LegalDatabaseBudgets.production(),
                List.of(schema));

        String result = gate.executeReadOnly(callback);

        assertThat(result).isEqualTo("observation");
        InOrder order = inOrder(jdbc, schema, callback);
        order.verify(jdbc).execute("SET LOCAL statement_timeout TO '30s'");
        order.verify(jdbc).queryForObject(
                "SELECT pg_catalog.current_setting('transaction_isolation')",
                String.class);
        order.verify(jdbc).queryForObject(
                "SELECT pg_catalog.current_setting('transaction_read_only')",
                String.class);
        order.verify(schema).verify();
        order.verify(jdbc).execute("SET LOCAL lock_timeout TO '30s'");
        order.verify(jdbc).queryForList(
                org.mockito.ArgumentMatchers.contains("pg_advisory_xact_lock"),
                eq(LegalManifestDatabaseGate.EDITORIAL_LOCK_NAME));
        order.verify(jdbc).execute("SET LOCAL lock_timeout TO '5s'");
        order.verify(callback).doInTransaction(any());
    }

    @Test
    void readOnlyGateRejectsEveryDeclaredBoundaryDriftBeforeOpeningATransaction() {
        DataSource dataSource = mock(DataSource.class);
        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
        TransactionTemplate joining = safeReadOnlyTransaction(manager);
        joining.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        TransactionTemplate wrongIsolation = safeReadOnlyTransaction(manager);
        wrongIsolation.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        TransactionTemplate wrongTimeout = safeReadOnlyTransaction(manager);
        wrongTimeout.setTimeout(74);
        TransactionTemplate writable = safeReadOnlyTransaction(manager);
        writable.setReadOnly(false);
        JdbcTransactionManager translatedManager = new JdbcTransactionManager(dataSource);

        assertUnsafeReadOnlyTemplate(joining, dataSource);
        assertUnsafeReadOnlyTemplate(wrongIsolation, dataSource);
        assertUnsafeReadOnlyTemplate(wrongTimeout, dataSource);
        assertUnsafeReadOnlyTemplate(writable, dataSource);
        assertUnsafeReadOnlyTemplate(
                safeReadOnlyTransaction(translatedManager),
                dataSource);

        DataSource foreignDataSource = mock(DataSource.class);
        assertUnsafeReadOnlyTemplate(
                safeReadOnlyTransaction(manager),
                foreignDataSource);
    }

    @Test
    void readOnlyGateRejectsAnEffectiveWritableOrForeignIsolationTransaction() {
        assertEffectiveReadOnlyModeRejected("repeatable read", "on");
        assertEffectiveReadOnlyModeRejected("read committed", "off");
        assertEffectiveReadOnlyModeRejected(null, "on");
        assertEffectiveReadOnlyModeRejected("read committed", null);
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

    @Test
    void acceptsExactlyTheOrderedImportPreflightsOnTheSharedJdbcSession() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        LegalDatabasePreflight schema = mock(LegalDatabasePreflight.class);
        LegalDatabasePreflight privileges = mock(LegalDatabasePreflight.class);
        when(schema.usesJdbc(jdbc)).thenReturn(true);
        when(privileges.usesJdbc(jdbc)).thenReturn(true);
        LegalManifestDatabaseGate gate = new LegalManifestDatabaseGate(
                executingTransaction(),
                jdbc,
                LegalDatabaseBudgets.production(),
                List.of(schema, privileges));

        assertThatCode(() -> gate.requireExactImportPreflights(
                jdbc,
                schema,
                privileges)).doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingExtraReorderedOrForeignImportPreflights() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        JdbcTemplate foreignJdbc = mock(JdbcTemplate.class);
        LegalDatabasePreflight schema = mock(LegalDatabasePreflight.class);
        LegalDatabasePreflight privileges = mock(LegalDatabasePreflight.class);
        LegalDatabasePreflight extra = mock(LegalDatabasePreflight.class);
        when(schema.usesJdbc(jdbc)).thenReturn(true);
        when(privileges.usesJdbc(jdbc)).thenReturn(true);

        assertThatThrownBy(() -> new LegalManifestDatabaseGate(
                executingTransaction(),
                jdbc,
                LegalDatabaseBudgets.production(),
                List.of(schema)).requireExactImportPreflights(
                        jdbc, schema, privileges))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LegalManifestDatabaseGate(
                executingTransaction(),
                jdbc,
                LegalDatabaseBudgets.production(),
                List.of(schema, privileges, extra)).requireExactImportPreflights(
                        jdbc, schema, privileges))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LegalManifestDatabaseGate(
                executingTransaction(),
                jdbc,
                LegalDatabaseBudgets.production(),
                List.of(privileges, schema)).requireExactImportPreflights(
                        jdbc, schema, privileges))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LegalManifestDatabaseGate(
                executingTransaction(),
                jdbc,
                LegalDatabaseBudgets.production(),
                List.of(schema, privileges)).requireExactImportPreflights(
                        foreignJdbc, schema, privileges))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsExactlyTheReadinessSchemaPreflightOnTheSharedJdbcSession() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        LegalDatabasePreflight schema = mock(LegalDatabasePreflight.class);
        when(schema.usesJdbc(jdbc)).thenReturn(true);
        LegalManifestDatabaseGate gate = new LegalManifestDatabaseGate(
                executingTransaction(),
                jdbc,
                LegalDatabaseBudgets.production(),
                List.of(schema));

        assertThatCode(() -> gate.requireExactReadinessPreflights(jdbc, schema))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingExtraForeignOrUnboundReadinessPreflights() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        JdbcTemplate foreignJdbc = mock(JdbcTemplate.class);
        LegalDatabasePreflight schema = mock(LegalDatabasePreflight.class);
        LegalDatabasePreflight extra = mock(LegalDatabasePreflight.class);
        when(schema.usesJdbc(jdbc)).thenReturn(true);

        assertThatThrownBy(() -> new LegalManifestDatabaseGate(
                executingTransaction(),
                jdbc,
                LegalDatabaseBudgets.production(),
                List.of()).requireExactReadinessPreflights(jdbc, schema))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LegalManifestDatabaseGate(
                executingTransaction(),
                jdbc,
                LegalDatabaseBudgets.production(),
                List.of(schema, extra)).requireExactReadinessPreflights(jdbc, schema))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LegalManifestDatabaseGate(
                executingTransaction(),
                jdbc,
                LegalDatabaseBudgets.production(),
                List.of(schema)).requireExactReadinessPreflights(foreignJdbc, schema))
                .isInstanceOf(IllegalArgumentException.class);

        when(schema.usesJdbc(jdbc)).thenReturn(false);
        assertThatThrownBy(() -> new LegalManifestDatabaseGate(
                executingTransaction(),
                jdbc,
                LegalDatabaseBudgets.production(),
                List.of(schema)).requireExactReadinessPreflights(jdbc, schema))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsExactlyTheOrderedEditorialPreflightsOnTheSharedJdbcSession() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        LegalDatabasePreflight schema = mock(LegalDatabasePreflight.class);
        LegalDatabasePreflight privileges = mock(LegalDatabasePreflight.class);
        when(schema.usesJdbc(jdbc)).thenReturn(true);
        when(privileges.usesJdbc(jdbc)).thenReturn(true);
        LegalManifestDatabaseGate gate = new LegalManifestDatabaseGate(
                executingTransaction(),
                jdbc,
                LegalDatabaseBudgets.production(),
                List.of(schema, privileges));

        assertThatCode(() -> gate.requireExactEditorialPreflights(
                jdbc,
                schema,
                privileges)).doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingExtraReorderedForeignOrUnboundEditorialPreflights() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        JdbcTemplate foreignJdbc = mock(JdbcTemplate.class);
        LegalDatabasePreflight schema = mock(LegalDatabasePreflight.class);
        LegalDatabasePreflight privileges = mock(LegalDatabasePreflight.class);
        LegalDatabasePreflight extra = mock(LegalDatabasePreflight.class);
        when(schema.usesJdbc(jdbc)).thenReturn(true);
        when(privileges.usesJdbc(jdbc)).thenReturn(true);

        assertThatThrownBy(() -> new LegalManifestDatabaseGate(
                executingTransaction(), jdbc, LegalDatabaseBudgets.production(), List.of(schema))
                .requireExactEditorialPreflights(jdbc, schema, privileges))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LegalManifestDatabaseGate(
                executingTransaction(), jdbc, LegalDatabaseBudgets.production(),
                List.of(schema, privileges, extra))
                .requireExactEditorialPreflights(jdbc, schema, privileges))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LegalManifestDatabaseGate(
                executingTransaction(), jdbc, LegalDatabaseBudgets.production(),
                List.of(privileges, schema))
                .requireExactEditorialPreflights(jdbc, schema, privileges))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LegalManifestDatabaseGate(
                executingTransaction(), jdbc, LegalDatabaseBudgets.production(),
                List.of(schema, privileges))
                .requireExactEditorialPreflights(foreignJdbc, schema, privileges))
                .isInstanceOf(IllegalArgumentException.class);

        when(privileges.usesJdbc(jdbc)).thenReturn(false);
        assertThatThrownBy(() -> new LegalManifestDatabaseGate(
                executingTransaction(), jdbc, LegalDatabaseBudgets.production(),
                List.of(schema, privileges))
                .requireExactEditorialPreflights(jdbc, schema, privileges))
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

    private static void assertUnsafeReadOnlyTemplate(
            TransactionTemplate transaction,
            DataSource jdbcDataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(jdbcDataSource);
        TransactionCallback<String> callback = mock(TransactionCallback.class);
        LegalManifestDatabaseGate gate = new LegalManifestDatabaseGate(
                transaction,
                jdbc,
                LegalDatabaseBudgets.production(),
                List.of());

        assertThatThrownBy(() -> gate.executeReadOnly(callback))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(callback);
    }

    private static void assertEffectiveReadOnlyModeRejected(
            String isolation,
            String readOnly) {
        DataSource dataSource = mock(DataSource.class);
        TransactionTemplate transaction = executingReadOnlyTransaction(dataSource);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.getDataSource()).thenReturn(dataSource);
        when(jdbc.queryForObject(
                "SELECT pg_catalog.current_setting('transaction_isolation')",
                String.class)).thenReturn(isolation);
        when(jdbc.queryForObject(
                "SELECT pg_catalog.current_setting('transaction_read_only')",
                String.class)).thenReturn(readOnly);
        LegalDatabasePreflight schema = mock(LegalDatabasePreflight.class);
        TransactionCallback<String> callback = mock(TransactionCallback.class);
        LegalManifestDatabaseGate gate = new LegalManifestDatabaseGate(
                transaction,
                jdbc,
                LegalDatabaseBudgets.production(),
                List.of(schema));

        assertThatThrownBy(() -> gate.executeReadOnly(callback))
                .isInstanceOf(IllegalArgumentException.class);
        verify(jdbc).execute("SET LOCAL statement_timeout TO '30s'");
        verify(jdbc, never()).queryForList(
                org.mockito.ArgumentMatchers.contains("pg_advisory_xact_lock"),
                org.mockito.ArgumentMatchers.<Object[]>any());
        verifyNoInteractions(schema, callback);
    }

    private static void assertEffectiveMutableModeRejected(
            String isolation,
            String readOnly) {
        DataSource dataSource = mock(DataSource.class);
        TransactionTemplate transaction = executingMutableTransaction(dataSource);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.getDataSource()).thenReturn(dataSource);
        when(jdbc.queryForObject(
                "SELECT pg_catalog.current_setting('transaction_isolation')",
                String.class)).thenReturn(isolation);
        when(jdbc.queryForObject(
                "SELECT pg_catalog.current_setting('transaction_read_only')",
                String.class)).thenReturn(readOnly);
        LegalDatabasePreflight schema = mock(LegalDatabasePreflight.class);
        TransactionCallback<String> callback = mock(TransactionCallback.class);
        LegalManifestDatabaseGate gate = new LegalManifestDatabaseGate(
                transaction,
                jdbc,
                LegalDatabaseBudgets.production(),
                List.of(schema));

        assertThatThrownBy(() -> gate.executeMutable(callback))
                .isInstanceOf(IllegalArgumentException.class);
        verify(jdbc).execute("SET LOCAL statement_timeout TO '30s'");
        verify(jdbc, never()).queryForList(
                org.mockito.ArgumentMatchers.contains("pg_advisory_xact_lock"),
                org.mockito.ArgumentMatchers.<Object[]>any());
        verifyNoInteractions(schema, callback);
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

    private static TransactionTemplate safeReadOnlyTransaction(
            DataSourceTransactionManager transactionManager) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(
                LegalDatabaseBudgets.production().transactionTimeoutSeconds());
        transaction.setReadOnly(true);
        return transaction;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static TransactionTemplate executingReadOnlyTransaction(DataSource dataSource) {
        TransactionTemplate transaction = mock(TransactionTemplate.class);
        when(transaction.getTransactionManager()).thenReturn(
                new DataSourceTransactionManager(dataSource));
        when(transaction.getPropagationBehavior()).thenReturn(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        when(transaction.getIsolationLevel()).thenReturn(
                TransactionDefinition.ISOLATION_READ_COMMITTED);
        when(transaction.getTimeout()).thenReturn(
                LegalDatabaseBudgets.production().transactionTimeoutSeconds());
        when(transaction.isReadOnly()).thenReturn(true);
        when(transaction.execute(any())).thenAnswer(invocation -> {
            TransactionCallback callback = invocation.getArgument(0);
            return callback.doInTransaction(new SimpleTransactionStatus());
        });
        return transaction;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static TransactionTemplate executingMutableTransaction(DataSource dataSource) {
        TransactionTemplate transaction = mock(TransactionTemplate.class);
        when(transaction.getTransactionManager()).thenReturn(
                new DataSourceTransactionManager(dataSource));
        when(transaction.getPropagationBehavior()).thenReturn(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        when(transaction.getIsolationLevel()).thenReturn(
                TransactionDefinition.ISOLATION_READ_COMMITTED);
        when(transaction.getTimeout()).thenReturn(
                LegalDatabaseBudgets.production().transactionTimeoutSeconds());
        when(transaction.isReadOnly()).thenReturn(false);
        when(transaction.execute(any())).thenAnswer(invocation -> {
            TransactionCallback callback = invocation.getArgument(0);
            return callback.doInTransaction(new SimpleTransactionStatus());
        });
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
