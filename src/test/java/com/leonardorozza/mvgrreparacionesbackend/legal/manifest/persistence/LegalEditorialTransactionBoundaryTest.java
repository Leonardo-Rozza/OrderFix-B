package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialReadiness;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LegalEditorialTransactionBoundaryTest {

    @Test
    void commitSQLExceptionIsUnknownAndCannotExposeTentativeEditorialEvidence()
            throws Exception {
        Connection connection = connectionWithManualTransaction();
        doThrow(new SQLException("ambiguous commit", "08006"))
                .when(connection).commit();
        TransactionTemplate transaction = transaction(connection);
        LegalEditorialTransactionState<LegalEditorialApplyReceipt> state =
                new LegalEditorialTransactionState<>();

        assertThatThrownBy(() -> transaction.execute(status -> {
            state.callbackStarted();
            state.planConstructed();
            state.receiptDelivered(receipt());
            return receipt();
        })).isInstanceOf(TransactionSystemException.class);

        assertThat(state.snapshot().persistence())
                .isEqualTo(LegalEditorialTransactionState.Persistence.UNKNOWN);
        assertThat(state.snapshot().planConstructed()).isTrue();
        assertThat(state.snapshot().receipt()).isEmpty();
    }

    @Test
    void rollbackSQLExceptionIsUnknownAndCannotBeReportedAsKnownFalse()
            throws Exception {
        Connection connection = connectionWithManualTransaction();
        doThrow(new SQLException("ambiguous rollback", "08006"))
                .when(connection).rollback();
        TransactionTemplate transaction = transaction(connection);
        LegalEditorialTransactionState<LegalEditorialApplyReceipt> state =
                new LegalEditorialTransactionState<>();

        assertThatThrownBy(() -> transaction.execute(status -> {
            state.callbackStarted();
            state.planConstructed();
            state.receiptDelivered(receipt());
            status.setRollbackOnly();
            return receipt();
        })).isInstanceOf(TransactionSystemException.class);

        assertThat(state.snapshot().persistence())
                .isEqualTo(LegalEditorialTransactionState.Persistence.UNKNOWN);
        assertThat(state.snapshot().planConstructed()).isTrue();
        assertThat(state.snapshot().receipt()).isEmpty();
    }

    private static Connection connectionWithManualTransaction() throws SQLException {
        Connection connection = mock(Connection.class);
        when(connection.getAutoCommit()).thenReturn(true);
        return connection;
    }

    private static TransactionTemplate transaction(Connection connection) throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);
        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
        manager.setRollbackOnCommitFailure(false);
        TransactionTemplate transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(LegalDatabaseBudgets.production().transactionTimeoutSeconds());
        transaction.setReadOnly(false);
        return transaction;
    }

    private static LegalEditorialApplyReceipt receipt() {
        return new LegalEditorialApplyReceipt(
                LegalEditorialApplyReceipt.OperationType.PROMOTE,
                UUID.fromString("02e1d8bc-50a8-4a30-985e-3a320eab23dd"),
                Instant.parse("2026-08-28T18:00:00.123456Z"),
                LegalEditorialReadiness.READY,
                2,
                1,
                4,
                2,
                3,
                1,
                0);
    }
}
