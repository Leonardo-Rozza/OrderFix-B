package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Objects;

/** Owns one isolated commit-sensitive transaction; editorial entry follows an idempotency MISS. */
final class LegalRegistrationTransactionBoundary {
    private final JdbcTemplate jdbc;
    private final LegalPrivateRequirementsDataSource dataSource;
    private final TransactionTemplate transaction;
    private final LegalDatabaseBudgets budgets;
    private final LegalRegistrationSchemaVerifier schema;
    private final LegalRegistrationPrivilegeVerifier privileges;

    LegalRegistrationTransactionBoundary(JdbcTemplate jdbc, LegalPrivateRequirementsDataSource dataSource,
            TransactionTemplate transaction, LegalDatabaseBudgets budgets,
            LegalRegistrationSchemaVerifier schema, LegalRegistrationPrivilegeVerifier privileges) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.dataSource = Objects.requireNonNull(dataSource);
        this.transaction = Objects.requireNonNull(transaction);
        this.budgets = Objects.requireNonNull(budgets);
        this.schema = Objects.requireNonNull(schema);
        this.privileges = Objects.requireNonNull(privileges);
        requireExactBoundary();
    }

    boolean usesJdbc(JdbcTemplate candidate) { return jdbc == candidate; }

    void requireExactBoundary() {
        Object candidate = transaction.getTransactionManager();
        if (candidate == null || candidate.getClass() != DataSourceTransactionManager.class
                || ((DataSourceTransactionManager) candidate).getDataSource() != dataSource
                || ((DataSourceTransactionManager) candidate).isRollbackOnCommitFailure()
                || !dataSource.isRegistrationBoundary()
                || jdbc.getDataSource() != dataSource
                || transaction.getPropagationBehavior() != TransactionDefinition.PROPAGATION_REQUIRES_NEW
                || transaction.getIsolationLevel() != TransactionDefinition.ISOLATION_READ_COMMITTED
                || transaction.isReadOnly() || transaction.getTimeout() != 25
                || budgets.transactionTimeoutSeconds() != 25 || budgets.statementTimeoutSeconds() != 5
                || budgets.editorialLockTimeoutSeconds() != 1 || budgets.graphLockTimeoutSeconds() != 1
                || !schema.usesJdbc(jdbc) || !privileges.usesJdbc(jdbc)
                || !"public".equals(schema.expectedSchema()) || !"public".equals(privileges.expectedSchema())) {
            throw new IllegalArgumentException("El alta legal requiere una frontera JDBC aislada y acreditable");
        }
    }

    <T> T execute(LegalTransactionCompletionState<T> state, Work<T> callback) {
        Objects.requireNonNull(state);
        Objects.requireNonNull(callback);
        requireExactBoundary();
        return dataSource.withinDeadline(deadline -> {
            try {
                T receipt = transaction.execute(status -> {
                    state.callbackStarted();
                    budget(deadline);
                    requireEffectiveTransaction();
                    schema.verify();
                    deadline.check();
                    privileges.verify();
                    deadline.check();
                    T tentative = callback.run(status, deadline);
                    deadline.check();
                    state.receiptDelivered(tentative);
                    return tentative;
                });
                state.transactionReturnedNormally();
                return receipt;
            } catch (RuntimeException failure) {
                // execute has completed rollback and release. Their recorded failures and an expired
                // budget must prevail over a payload rejection even when Spring absorbed cleanup.
                try {
                    deadline.check();
                } catch (RuntimeException boundaryFailure) {
                    if (boundaryFailure != failure) boundaryFailure.addSuppressed(failure);
                    throw boundaryFailure;
                }
                throw failure;
            }
        });
    }

    LegalEditorialTimeBoundary enterEditorialShared(LegalIdempotencyCoordinator.Reservation reservation,
            LegalPrivateRequirementsDeadline deadline) {
        Objects.requireNonNull(reservation);
        Objects.requireNonNull(deadline);
        try {
            requireExactBoundary();
            requireEffectiveTransaction();
            if (reservation.jdbc() != jdbc) throw new IllegalArgumentException("Reserva de otra frontera");
            reservation.requireNew();
            if (reservation.command().operation()
                    != com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand.Operation.REGISTRATION) {
                throw new IllegalArgumentException("La frontera de registro exige una reserva REGISTRATION");
            }
            budget(deadline);
            jdbc.queryForList("SELECT pg_catalog.pg_advisory_xact_lock_shared(pg_catalog.hashtextextended(?, 0))",
                    LegalManifestDatabaseGate.EDITORIAL_LOCK_NAME);
            deadline.check();
            reservation.readBudget();
            OffsetDateTime transactionAt = Objects.requireNonNull(jdbc.queryForObject(
                    "SELECT pg_catalog.transaction_timestamp()", OffsetDateTime.class));
            OffsetDateTime observedAt = Objects.requireNonNull(jdbc.queryForObject(
                    "SELECT pg_catalog.statement_timestamp()", OffsetDateTime.class));
            deadline.check();
            return new LegalEditorialTimeBoundary(transactionAt.toInstant(), observedAt.toInstant());
        } catch (RuntimeException failure) {
            throw reservation.fail(failure);
        }
    }

    private void budget(LegalPrivateRequirementsDeadline deadline) {
        setTimeout("statement_timeout", Math.min(5_000, deadline.remainingMillis()));
        setTimeout("lock_timeout", Math.min(1_000, deadline.remainingMillis()));
    }

    private void setTimeout(String name, int millis) {
        jdbc.queryForObject("SELECT pg_catalog.set_config(?, ?, true)", String.class, name, millis + "ms");
    }

    private void requireEffectiveTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !Integer.valueOf(Connection.TRANSACTION_READ_COMMITTED).equals(
                        TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                || !(TransactionSynchronizationManager.getResource(dataSource) instanceof ConnectionHolder holder)) {
            throw new IllegalStateException("El alta legal requiere su transacción mutable activa");
        }
        try {
            Connection connection = holder.getConnection();
            if (holder.isRollbackOnly() || connection.getAutoCommit() || connection.isReadOnly()
                    || connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
                throw new IllegalStateException("El alta legal requiere READ_COMMITTED efectivo");
            }
            var mode = jdbc.queryForMap("""
                    SELECT pg_catalog.current_setting('transaction_isolation') AS isolation,
                           pg_catalog.current_setting('transaction_read_only') AS read_only
                    """);
            if (!"read committed".equals(mode.get("isolation")) || !"off".equals(mode.get("read_only"))) {
                throw new IllegalStateException("El alta legal requiere modo PostgreSQL mutable READ_COMMITTED");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("No se pudo acreditar la transacción de alta legal", failure);
        }
    }

    @FunctionalInterface
    interface Work<T> {
        T run(TransactionStatus status, LegalPrivateRequirementsDeadline deadline);
    }
}
