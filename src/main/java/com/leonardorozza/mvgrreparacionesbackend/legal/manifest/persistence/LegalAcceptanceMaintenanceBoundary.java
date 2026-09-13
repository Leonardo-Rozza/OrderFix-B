package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/** Dedicated maintenance connection, preflight and outcome boundary; never inherits a web transaction. */
final class LegalAcceptanceMaintenanceBoundary {
    private final JdbcTemplate jdbc;
    private final LegalPrivateRequirementsDataSource source;
    private final TransactionTemplate transaction;
    private final LegalV29AcceptanceSchemaVerifier schema;
    private final LegalAcceptanceMaintenancePrivilegeVerifier privileges;

    LegalAcceptanceMaintenanceBoundary(JdbcTemplate jdbc, LegalPrivateRequirementsDataSource source,
            TransactionTemplate transaction, LegalV29AcceptanceSchemaVerifier schema,
            LegalAcceptanceMaintenancePrivilegeVerifier privileges) {
        this.jdbc=Objects.requireNonNull(jdbc); this.source=Objects.requireNonNull(source);
        this.transaction=Objects.requireNonNull(transaction); this.schema=Objects.requireNonNull(schema);
        this.privileges=Objects.requireNonNull(privileges);
        requireExactBoundary();
    }
    boolean usesJdbc(JdbcTemplate candidate) { return jdbc==candidate; }
    void requireExactBoundary() {
        Object manager=transaction.getTransactionManager();
        if (manager==null || manager.getClass()!=DataSourceTransactionManager.class
                || ((DataSourceTransactionManager)manager).getDataSource()!=source
                || ((DataSourceTransactionManager)manager).isRollbackOnCommitFailure()
                || source.isRegistrationBoundary() || jdbc.getDataSource()!=source
                || transaction.getPropagationBehavior()!=TransactionDefinition.PROPAGATION_REQUIRES_NEW
                || transaction.getIsolationLevel()!=TransactionDefinition.ISOLATION_READ_COMMITTED
                || transaction.isReadOnly() || transaction.getTimeout()!=15
                || !schema.usesJdbc(jdbc) || !privileges.usesJdbc(jdbc)
                || !"public".equals(schema.expectedSchema()) || !"public".equals(privileges.expectedSchema()))
            throw new LegalAcceptanceMaintenanceException(LegalAcceptanceMaintenanceException.Code.INVALID_BOUNDARY);
    }
    <T> T execute(Work<T> work) {
        Objects.requireNonNull(work);
        requireExactBoundary();
        var completion=new LegalTransactionCompletionState<T>();
        try {
            return source.withinDeadline(deadline -> {
                T receipt=transaction.execute(status -> {
                    completion.callbackStarted();
                    budget(deadline);
                    requireEffectiveTransaction();
                    schema.verify(); deadline.check();
                    privileges.verify(); deadline.check();
                    T tentative=Objects.requireNonNull(work.run(deadline));
                    budget(deadline);
                    jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
                    deadline.check();
                    completion.receiptDelivered(tentative);
                    return tentative;
                });
                completion.transactionReturnedNormally();
                deadline.check();
                return Objects.requireNonNull(receipt);
            });
        } catch (RuntimeException failure) {
            var snapshot=completion.snapshot();
            // A committed result whose delivery/cleanup failed is also not a successful batch receipt.
            var code=snapshot.persistence()==LegalTransactionCompletionState.Persistence.NOT_PERSISTED
                    ? LegalAcceptanceMaintenanceException.Code.UNAVAILABLE
                    : LegalAcceptanceMaintenanceException.Code.UNKNOWN;
            throw new LegalAcceptanceMaintenanceException(code);
        }
    }
    private void budget(LegalPrivateRequirementsDeadline deadline) {
        jdbc.queryForObject("SELECT pg_catalog.set_config('statement_timeout',?,true)",String.class,
                Math.min(5_000,deadline.remainingMillis())+"ms");
        jdbc.queryForObject("SELECT pg_catalog.set_config('lock_timeout',?,true)",String.class,
                Math.min(1_000,deadline.remainingMillis())+"ms");
    }
    private void requireEffectiveTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !Integer.valueOf(Connection.TRANSACTION_READ_COMMITTED).equals(
                    TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                || !(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder holder))
            throw new IllegalStateException("Frontera de mantenimiento no disponible");
        try {
            Connection connection=holder.getConnection();
            if (holder.isRollbackOnly() || connection.getAutoCommit() || connection.isReadOnly()
                    || connection.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED)
                throw new IllegalStateException("Transacción de mantenimiento no disponible");
            var effective=jdbc.queryForMap("SELECT current_setting('transaction_isolation') AS isolation,current_setting('transaction_read_only') AS read_only");
            if (!"read committed".equals(effective.get("isolation")) || !"off".equals(effective.get("read_only")))
                throw new IllegalStateException("Modo PostgreSQL no disponible");
        } catch (SQLException failure) {
            throw new IllegalStateException("Conexión de mantenimiento no disponible");
        }
    }
    @FunctionalInterface interface Work<T> { T run(LegalPrivateRequirementsDeadline deadline); }
}
