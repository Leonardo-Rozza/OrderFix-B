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
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LegalManifestDatabaseGateTest {

    private static final Instant TRANSACTION_AT =
            Instant.parse("2026-08-31T12:00:00.123456Z");
    private static final Instant OBSERVED_AT =
            Instant.parse("2026-08-31T12:00:01.654321Z");
    private static final String EXCLUSIVE_LOCK_SQL = """
            SELECT pg_catalog.pg_advisory_xact_lock(
                pg_catalog.hashtextextended(?, 0)
            )
            """;
    private static final String SHARED_LOCK_SQL = """
            SELECT pg_catalog.pg_advisory_xact_lock_shared(
                pg_catalog.hashtextextended(?, 0)
            )
            """;

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
                eq(EXCLUSIVE_LOCK_SQL),
                eq(LegalManifestDatabaseGate.EDITORIAL_LOCK_NAME));
        order.verify(jdbc).execute("SET LOCAL lock_timeout TO '5s'");
        order.verify(callback).doInTransaction(any());
        verify(jdbc, never()).queryForList(
                eq(SHARED_LOCK_SQL),
                eq(LegalManifestDatabaseGate.EDITORIAL_LOCK_NAME));
        verify(jdbc, never()).queryForObject(
                "SELECT transaction_timestamp()",
                OffsetDateTime.class);
        verify(jdbc, never()).queryForObject(
                "SELECT statement_timestamp()",
                OffsetDateTime.class);
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
    void mutableGateAccreditsEffectiveModeBeforeTheExclusiveProtectedGraph() {
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
        stubTimeBoundary(jdbc);
        LegalManifestDatabaseGate.EditorialTransactionCallback<String> callback =
                mock(LegalManifestDatabaseGate.EditorialTransactionCallback.class);
        when(callback.doInTransaction(any(), any())).thenReturn("receipt");
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
                eq(EXCLUSIVE_LOCK_SQL),
                eq(LegalManifestDatabaseGate.EDITORIAL_LOCK_NAME));
        order.verify(jdbc).execute("SET LOCAL lock_timeout TO '5s'");
        order.verify(jdbc).queryForObject(
                "SELECT transaction_timestamp()",
                OffsetDateTime.class);
        order.verify(jdbc).queryForObject(
                "SELECT statement_timestamp()",
                OffsetDateTime.class);
        order.verify(callback).doInTransaction(
                any(),
                eq(new LegalEditorialTimeBoundary(TRANSACTION_AT, OBSERVED_AT)));
        verify(jdbc, never()).queryForList(
                eq(SHARED_LOCK_SQL),
                eq(LegalManifestDatabaseGate.EDITORIAL_LOCK_NAME));
    }

    @Test
    void sharedMutableGateKeepsTheAccreditedOrderAndUsesTheExactSharedLock() {
        DataSource dataSource = mock(DataSource.class);
        TransactionTemplate transaction = executingMutableTransaction(dataSource);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        stubMutableMode(jdbc, dataSource);
        LegalDatabasePreflight schema = mock(LegalDatabasePreflight.class);
        stubTimeBoundary(jdbc);
        LegalManifestDatabaseGate.EditorialTransactionCallback<String> callback =
                mock(LegalManifestDatabaseGate.EditorialTransactionCallback.class);
        when(callback.doInTransaction(any(), any())).thenReturn("aggregate");
        LegalManifestDatabaseGate gate = new LegalManifestDatabaseGate(
                transaction,
                jdbc,
                LegalDatabaseBudgets.production(),
                List.of(schema));

        String result = gate.executeMutableShared(callback);

        assertThat(result).isEqualTo("aggregate");
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
                eq(SHARED_LOCK_SQL),
                eq(LegalManifestDatabaseGate.EDITORIAL_LOCK_NAME));
        order.verify(jdbc).execute("SET LOCAL lock_timeout TO '5s'");
        order.verify(jdbc).queryForObject(
                "SELECT transaction_timestamp()",
                OffsetDateTime.class);
        order.verify(jdbc).queryForObject(
                "SELECT statement_timestamp()",
                OffsetDateTime.class);
        order.verify(callback).doInTransaction(
                any(),
                eq(new LegalEditorialTimeBoundary(TRANSACTION_AT, OBSERVED_AT)));
        verify(jdbc, never()).queryForList(
                eq(EXCLUSIVE_LOCK_SQL),
                eq(LegalManifestDatabaseGate.EDITORIAL_LOCK_NAME));
    }

    @Test
    void sharedMutableGateRejectsAnUnsafeDeclaredBoundaryBeforeOpeningPostgres() {
        DataSource dataSource = mock(DataSource.class);
        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
        TransactionTemplate unsafe = safeImportTransaction(manager);
        unsafe.setReadOnly(true);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.getDataSource()).thenReturn(dataSource);
        LegalManifestDatabaseGate.EditorialTransactionCallback<String> callback =
                mock(LegalManifestDatabaseGate.EditorialTransactionCallback.class);
        LegalManifestDatabaseGate gate = new LegalManifestDatabaseGate(
                unsafe,
                jdbc,
                LegalDatabaseBudgets.production(),
                List.of());

        assertThatThrownBy(() -> gate.executeMutableShared(callback))
                .isInstanceOf(IllegalArgumentException.class);

        verify(jdbc, never()).execute(anyString());
        verify(jdbc, never()).queryForList(
                anyString(),
                org.mockito.ArgumentMatchers.<Object[]>any());
        verifyNoInteractions(callback);
    }

    @Test
    void sharedMutableGateRejectsEveryInvalidEffectiveModeBeforePreflights() {
        assertEffectiveSharedMutableModeRejected("repeatable read", "off");
        assertEffectiveSharedMutableModeRejected("read committed", "on");
        assertEffectiveSharedMutableModeRejected(null, "off");
        assertEffectiveSharedMutableModeRejected("read committed", null);
    }

    @Test
    void sharedMutableGateFailsClosedWhenAPreflightFails() {
        DataSource dataSource = mock(DataSource.class);
        TransactionTemplate transaction = executingMutableTransaction(dataSource);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        stubMutableMode(jdbc, dataSource);
        LegalDatabasePreflight failing = mock(LegalDatabasePreflight.class);
        IllegalStateException failure = new IllegalStateException("aggregate schema drift");
        org.mockito.Mockito.doThrow(failure).when(failing).verify();
        LegalManifestDatabaseGate.EditorialTransactionCallback<String> callback =
                mock(LegalManifestDatabaseGate.EditorialTransactionCallback.class);
        LegalManifestDatabaseGate gate = new LegalManifestDatabaseGate(
                transaction,
                jdbc,
                LegalDatabaseBudgets.production(),
                List.of(failing));

        assertThatThrownBy(() -> gate.executeMutableShared(callback)).isSameAs(failure);

        verify(jdbc, never()).queryForList(
                anyString(),
                org.mockito.ArgumentMatchers.<Object[]>any());
        verify(jdbc, never()).queryForObject(
                "SELECT transaction_timestamp()",
                OffsetDateTime.class);
        verifyNoInteractions(callback);
    }

    @Test
    void sharedMutableGatePropagatesLockFailureWithoutFallbackClocksOrCallback() {
        DataSource dataSource = mock(DataSource.class);
        TransactionTemplate transaction = executingMutableTransaction(dataSource);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        stubMutableMode(jdbc, dataSource);
        IllegalStateException failure = new IllegalStateException("shared lock timeout");
        org.mockito.Mockito.doThrow(failure).when(jdbc).queryForList(
                eq(SHARED_LOCK_SQL),
                eq(LegalManifestDatabaseGate.EDITORIAL_LOCK_NAME));
        LegalManifestDatabaseGate.EditorialTransactionCallback<String> callback =
                mock(LegalManifestDatabaseGate.EditorialTransactionCallback.class);
        LegalManifestDatabaseGate gate = new LegalManifestDatabaseGate(
                transaction,
                jdbc,
                LegalDatabaseBudgets.production(),
                List.of());

        assertThatThrownBy(() -> gate.executeMutableShared(callback)).isSameAs(failure);

        verify(jdbc, times(1)).queryForList(
                eq(SHARED_LOCK_SQL),
                eq(LegalManifestDatabaseGate.EDITORIAL_LOCK_NAME));
        verify(jdbc, never()).queryForList(
                eq(EXCLUSIVE_LOCK_SQL),
                eq(LegalManifestDatabaseGate.EDITORIAL_LOCK_NAME));
        verify(jdbc, never()).execute("SET LOCAL lock_timeout TO '5s'");
        verify(jdbc, never()).queryForObject(
                "SELECT transaction_timestamp()",
                OffsetDateTime.class);
        verify(jdbc, never()).queryForObject(
                "SELECT statement_timestamp()",
                OffsetDateTime.class);
        verifyNoInteractions(callback);
    }

    @Test
    void sharedMutableGateRejectsInvalidClockCausalityBeforeCallback() {
        DataSource dataSource = mock(DataSource.class);
        TransactionTemplate transaction = executingMutableTransaction(dataSource);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        stubMutableMode(jdbc, dataSource);
        when(jdbc.queryForObject(
                "SELECT transaction_timestamp()",
                OffsetDateTime.class)).thenReturn(
                        OffsetDateTime.ofInstant(OBSERVED_AT, ZoneOffset.UTC));
        when(jdbc.queryForObject(
                "SELECT statement_timestamp()",
                OffsetDateTime.class)).thenReturn(
                        OffsetDateTime.ofInstant(TRANSACTION_AT, ZoneOffset.UTC));
        LegalManifestDatabaseGate.EditorialTransactionCallback<String> callback =
                mock(LegalManifestDatabaseGate.EditorialTransactionCallback.class);
        LegalManifestDatabaseGate gate = new LegalManifestDatabaseGate(
                transaction,
                jdbc,
                LegalDatabaseBudgets.production(),
                List.of());

        assertThatThrownBy(() -> gate.executeMutableShared(callback))
                .isInstanceOf(IllegalArgumentException.class);

        verify(jdbc).queryForList(
                eq(SHARED_LOCK_SQL),
                eq(LegalManifestDatabaseGate.EDITORIAL_LOCK_NAME));
        verifyNoInteractions(callback);
    }

    @Test
    void sharedMutableCallbackFailureEscapesOnceWithoutRetry() {
        DataSource dataSource = mock(DataSource.class);
        TransactionTemplate transaction = executingMutableTransaction(dataSource);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        stubMutableMode(jdbc, dataSource);
        stubTimeBoundary(jdbc);
        IllegalStateException failure = new IllegalStateException("aggregate writer failed");
        LegalManifestDatabaseGate.EditorialTransactionCallback<String> callback =
                mock(LegalManifestDatabaseGate.EditorialTransactionCallback.class);
        when(callback.doInTransaction(any(), any())).thenThrow(failure);
        LegalManifestDatabaseGate gate = new LegalManifestDatabaseGate(
                transaction,
                jdbc,
                LegalDatabaseBudgets.production(),
                List.of());

        assertThatThrownBy(() -> gate.executeMutableShared(callback)).isSameAs(failure);

        verify(transaction, times(1)).execute(any());
        verify(jdbc, times(1)).queryForList(
                eq(SHARED_LOCK_SQL),
                eq(LegalManifestDatabaseGate.EDITORIAL_LOCK_NAME));
        verify(callback, times(1)).doInTransaction(any(), any());
    }

    @Test
    void mutableGateRejectsAnEffectiveReadOnlyOrForeignIsolationTransaction() {
        assertEffectiveMutableModeRejected("repeatable read", "off");
        assertEffectiveMutableModeRejected("read committed", "on");
        assertEffectiveMutableModeRejected(null, "off");
        assertEffectiveMutableModeRejected("read committed", null);
    }

    @Test
    void readOnlyGateAccreditsEffectiveModeBeforeTheExclusiveProtectedGraph() {
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
        stubTimeBoundary(jdbc);
        LegalManifestDatabaseGate.EditorialTransactionCallback<String> callback =
                mock(LegalManifestDatabaseGate.EditorialTransactionCallback.class);
        when(callback.doInTransaction(any(), any())).thenReturn("observation");
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
                eq(EXCLUSIVE_LOCK_SQL),
                eq(LegalManifestDatabaseGate.EDITORIAL_LOCK_NAME));
        order.verify(jdbc).execute("SET LOCAL lock_timeout TO '5s'");
        order.verify(jdbc).queryForObject(
                "SELECT transaction_timestamp()",
                OffsetDateTime.class);
        order.verify(jdbc).queryForObject(
                "SELECT statement_timestamp()",
                OffsetDateTime.class);
        order.verify(callback).doInTransaction(
                any(),
                eq(new LegalEditorialTimeBoundary(TRANSACTION_AT, OBSERVED_AT)));
        verify(jdbc, never()).queryForList(
                eq(SHARED_LOCK_SQL),
                eq(LegalManifestDatabaseGate.EDITORIAL_LOCK_NAME));
    }

    @Test
    void reconciliationExecutesTheEffectiveModePreflightsLockAndCallbackInOrder() {
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
        LegalDatabasePreflight privileges = mock(LegalDatabasePreflight.class);
        stubTimeBoundary(jdbc);
        LegalManifestDatabaseGate.EditorialTransactionCallback<String> callback =
                mock(LegalManifestDatabaseGate.EditorialTransactionCallback.class);
        when(callback.doInTransaction(any(), any())).thenReturn("reconciled");
        LegalManifestDatabaseGate gate = new LegalManifestDatabaseGate(
                transaction,
                jdbc,
                LegalDatabaseBudgets.production(),
                List.of(schema, privileges));

        String result = gate.executeEditorialReconciliation(callback);

        assertThat(result).isEqualTo("reconciled");
        InOrder order = inOrder(jdbc, schema, privileges, callback);
        order.verify(jdbc).execute("SET LOCAL statement_timeout TO '30s'");
        order.verify(jdbc).queryForObject(
                "SELECT pg_catalog.current_setting('transaction_isolation')",
                String.class);
        order.verify(jdbc).queryForObject(
                "SELECT pg_catalog.current_setting('transaction_read_only')",
                String.class);
        order.verify(schema).verify();
        order.verify(privileges).verify();
        order.verify(jdbc).execute("SET LOCAL lock_timeout TO '30s'");
        order.verify(jdbc).queryForList(
                eq(EXCLUSIVE_LOCK_SQL),
                eq(LegalManifestDatabaseGate.EDITORIAL_LOCK_NAME));
        order.verify(jdbc).execute("SET LOCAL lock_timeout TO '5s'");
        order.verify(jdbc).queryForObject(
                "SELECT transaction_timestamp()",
                OffsetDateTime.class);
        order.verify(jdbc).queryForObject(
                "SELECT statement_timestamp()",
                OffsetDateTime.class);
        order.verify(callback).doInTransaction(
                any(),
                eq(new LegalEditorialTimeBoundary(TRANSACTION_AT, OBSERVED_AT)));
        verify(jdbc, never()).queryForList(
                eq(SHARED_LOCK_SQL),
                eq(LegalManifestDatabaseGate.EDITORIAL_LOCK_NAME));
    }

    @Test
    void editorialClockReadsFailClosedBeforeCallback() {
        assertClockBoundaryRejected(null, null, false);
        assertClockBoundaryRejected(TRANSACTION_AT, null, true);
        assertClockBoundaryRejected(OBSERVED_AT, TRANSACTION_AT, true);
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
        DataSourceTransactionManager rollbackAfterCommitFailure =
                new DataSourceTransactionManager(dataSource);
        rollbackAfterCommitFailure.setRollbackOnCommitFailure(true);

        assertUnsafeReadOnlyTemplate(joining, dataSource);
        assertUnsafeReadOnlyTemplate(wrongIsolation, dataSource);
        assertUnsafeReadOnlyTemplate(wrongTimeout, dataSource);
        assertUnsafeReadOnlyTemplate(writable, dataSource);
        assertUnsafeReadOnlyTemplate(
                safeReadOnlyTransaction(rollbackAfterCommitFailure),
                dataSource);
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
    void reconciliationAccreditsTheExactReadOnlyBoundaryAndOrderedPreflights() {
        DataSource dataSource = mock(DataSource.class);
        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        LegalDatabasePreflight schema = mock(LegalDatabasePreflight.class);
        LegalDatabasePreflight privileges = mock(LegalDatabasePreflight.class);
        when(schema.usesJdbc(jdbc)).thenReturn(true);
        when(privileges.usesJdbc(jdbc)).thenReturn(true);
        LegalManifestDatabaseGate gate = new LegalManifestDatabaseGate(
                safeReadOnlyTransaction(manager),
                jdbc,
                LegalDatabaseBudgets.production(),
                List.of(schema, privileges));

        assertThatCode(() -> gate.requireExactEditorialReconciliationBoundary(
                jdbc,
                schema,
                privileges)).doesNotThrowAnyException();
    }

    @Test
    void reconciliationRejectsCommitUnsafeMutableTranslatedOrForeignBoundaries() {
        DataSource dataSource = mock(DataSource.class);
        DataSourceTransactionManager rollbackAfterCommitFailure =
                new DataSourceTransactionManager(dataSource);
        rollbackAfterCommitFailure.setRollbackOnCommitFailure(true);
        DataSourceTransactionManager safeManager = new DataSourceTransactionManager(dataSource);
        JdbcTransactionManager translatedManager = new JdbcTransactionManager(dataSource);
        TransactionTemplate mutable = safeReadOnlyTransaction(safeManager);
        mutable.setReadOnly(false);

        assertUnsafeReconciliationBoundary(
                safeReadOnlyTransaction(rollbackAfterCommitFailure),
                dataSource);
        assertUnsafeReconciliationBoundary(mutable, dataSource);
        assertUnsafeReconciliationBoundary(
                safeReadOnlyTransaction(translatedManager),
                dataSource);
        assertUnsafeReconciliationBoundary(
                safeReadOnlyTransaction(safeManager),
                mock(DataSource.class));

        TransactionTemplate missingManager = new TransactionTemplate();
        missingManager.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        missingManager.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        missingManager.setTimeout(
                LegalDatabaseBudgets.production().transactionTimeoutSeconds());
        missingManager.setReadOnly(true);
        JdbcTemplate missingDataSource = new JdbcTemplate();
        LegalDatabasePreflight schema = mock(LegalDatabasePreflight.class);
        LegalDatabasePreflight privileges = mock(LegalDatabasePreflight.class);
        when(schema.usesJdbc(missingDataSource)).thenReturn(true);
        when(privileges.usesJdbc(missingDataSource)).thenReturn(true);
        assertThatThrownBy(() -> new LegalManifestDatabaseGate(
                missingManager,
                missingDataSource,
                LegalDatabaseBudgets.production(),
                List.of(schema, privileges))
                .requireExactEditorialReconciliationBoundary(
                        missingDataSource,
                        schema,
                        privileges)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reconciliationRejectsMissingReorderedOrForeignPreflights() {
        DataSource dataSource = mock(DataSource.class);
        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        LegalDatabasePreflight schema = mock(LegalDatabasePreflight.class);
        LegalDatabasePreflight privileges = mock(LegalDatabasePreflight.class);
        when(schema.usesJdbc(jdbc)).thenReturn(true);
        when(privileges.usesJdbc(jdbc)).thenReturn(true);

        assertThatThrownBy(() -> new LegalManifestDatabaseGate(
                safeReadOnlyTransaction(manager),
                jdbc,
                LegalDatabaseBudgets.production(),
                List.of(schema))
                .requireExactEditorialReconciliationBoundary(jdbc, schema, privileges))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LegalManifestDatabaseGate(
                safeReadOnlyTransaction(manager),
                jdbc,
                LegalDatabaseBudgets.production(),
                List.of(privileges, schema))
                .requireExactEditorialReconciliationBoundary(jdbc, schema, privileges))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LegalManifestDatabaseGate(
                safeReadOnlyTransaction(manager),
                jdbc,
                LegalDatabaseBudgets.production(),
                List.of(schema, privileges))
                .requireExactEditorialReconciliationBoundary(
                        new JdbcTemplate(dataSource),
                        schema,
                        privileges))
                .isInstanceOf(IllegalArgumentException.class);
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
        LegalManifestDatabaseGate.EditorialTransactionCallback<String> callback =
                mock(LegalManifestDatabaseGate.EditorialTransactionCallback.class);
        LegalManifestDatabaseGate gate = new LegalManifestDatabaseGate(
                transaction,
                jdbc,
                LegalDatabaseBudgets.production(),
                List.of());

        assertThatThrownBy(() -> gate.executeReadOnly(callback))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(callback);
    }

    private static void assertUnsafeReconciliationBoundary(
            TransactionTemplate transaction,
            DataSource jdbcDataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(jdbcDataSource);
        LegalDatabasePreflight schema = mock(LegalDatabasePreflight.class);
        LegalDatabasePreflight privileges = mock(LegalDatabasePreflight.class);
        when(schema.usesJdbc(jdbc)).thenReturn(true);
        when(privileges.usesJdbc(jdbc)).thenReturn(true);
        LegalManifestDatabaseGate gate = new LegalManifestDatabaseGate(
                transaction,
                jdbc,
                LegalDatabaseBudgets.production(),
                List.of(schema, privileges));

        assertThatThrownBy(() -> gate.requireExactEditorialReconciliationBoundary(
                jdbc,
                schema,
                privileges)).isInstanceOf(IllegalArgumentException.class);
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
        LegalManifestDatabaseGate.EditorialTransactionCallback<String> callback =
                mock(LegalManifestDatabaseGate.EditorialTransactionCallback.class);
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
        LegalManifestDatabaseGate.EditorialTransactionCallback<String> callback =
                mock(LegalManifestDatabaseGate.EditorialTransactionCallback.class);
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

    private static void assertEffectiveSharedMutableModeRejected(
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
        LegalManifestDatabaseGate.EditorialTransactionCallback<String> callback =
                mock(LegalManifestDatabaseGate.EditorialTransactionCallback.class);
        LegalManifestDatabaseGate gate = new LegalManifestDatabaseGate(
                transaction,
                jdbc,
                LegalDatabaseBudgets.production(),
                List.of(schema));

        assertThatThrownBy(() -> gate.executeMutableShared(callback))
                .isInstanceOf(IllegalArgumentException.class);
        verify(jdbc).execute("SET LOCAL statement_timeout TO '30s'");
        verify(jdbc, never()).queryForList(
                anyString(),
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

    private static void assertClockBoundaryRejected(
            Instant transactionAt,
            Instant observedAt,
            boolean secondClockExpected) {
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
        when(jdbc.queryForObject(
                "SELECT transaction_timestamp()",
                OffsetDateTime.class)).thenReturn(transactionAt == null
                        ? null
                        : OffsetDateTime.ofInstant(transactionAt, ZoneOffset.UTC));
        if (observedAt != null) {
            when(jdbc.queryForObject(
                    "SELECT statement_timestamp()",
                    OffsetDateTime.class)).thenReturn(
                            OffsetDateTime.ofInstant(observedAt, ZoneOffset.UTC));
        }
        LegalManifestDatabaseGate.EditorialTransactionCallback<String> callback =
                mock(LegalManifestDatabaseGate.EditorialTransactionCallback.class);
        LegalManifestDatabaseGate gate = new LegalManifestDatabaseGate(
                transaction,
                jdbc,
                LegalDatabaseBudgets.production(),
                List.of());

        assertThatThrownBy(() -> gate.executeReadOnly(callback))
                .isInstanceOf(RuntimeException.class);
        if (secondClockExpected) {
            verify(jdbc).queryForObject(
                    "SELECT statement_timestamp()",
                    OffsetDateTime.class);
        } else {
            verify(jdbc, never()).queryForObject(
                    "SELECT statement_timestamp()",
                    OffsetDateTime.class);
        }
        verifyNoInteractions(callback);
    }

    private static void stubTimeBoundary(JdbcTemplate jdbc) {
        when(jdbc.queryForObject(
                "SELECT transaction_timestamp()",
                OffsetDateTime.class)).thenReturn(
                        OffsetDateTime.ofInstant(TRANSACTION_AT, ZoneOffset.UTC));
        when(jdbc.queryForObject(
                "SELECT statement_timestamp()",
                OffsetDateTime.class)).thenReturn(
                        OffsetDateTime.ofInstant(OBSERVED_AT, ZoneOffset.UTC));
    }

    private static void stubMutableMode(JdbcTemplate jdbc, DataSource dataSource) {
        when(jdbc.getDataSource()).thenReturn(dataSource);
        when(jdbc.queryForObject(
                "SELECT pg_catalog.current_setting('transaction_isolation')",
                String.class)).thenReturn("read committed");
        when(jdbc.queryForObject(
                "SELECT pg_catalog.current_setting('transaction_read_only')",
                String.class)).thenReturn("off");
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
