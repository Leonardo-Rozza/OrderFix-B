package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LegalImportTransactionBoundaryTest {

    @Test
    void commitSQLExceptionIsReportedAsUnknownInsteadOfFalseRollback() throws Exception {
        Connection connection = connectionWithManualTransaction();
        doThrow(new SQLException("ambiguous commit", "08006"))
                .when(connection).commit();
        TransactionTemplate transaction = transaction(connection);
        LegalImportTransactionState<String> state = new LegalImportTransactionState<>();

        assertThatThrownBy(() -> transaction.execute(status -> {
            state.callbackStarted();
            state.receiptDelivered("tentative receipt");
            return "tentative receipt";
        })).isInstanceOf(TransactionSystemException.class);

        assertThat(state.snapshot().persistence())
                .isEqualTo(LegalImportTransactionState.Persistence.UNKNOWN);
        assertThat(state.snapshot().receipt()).isEmpty();
    }

    @Test
    void rollbackSQLExceptionIsAlsoReportedAsUnknownAndHidesTheReceipt() throws Exception {
        Connection connection = connectionWithManualTransaction();
        doThrow(new SQLException("ambiguous rollback", "08006"))
                .when(connection).rollback();
        TransactionTemplate transaction = transaction(connection);
        LegalImportTransactionState<String> state = new LegalImportTransactionState<>();

        assertThatThrownBy(() -> transaction.execute(status -> {
            state.callbackStarted();
            state.receiptDelivered("tentative receipt");
            status.setRollbackOnly();
            return "tentative receipt";
        })).isInstanceOf(TransactionSystemException.class);

        assertThat(state.snapshot().persistence())
                .isEqualTo(LegalImportTransactionState.Persistence.UNKNOWN);
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
        return new TransactionTemplate(manager);
    }
}
