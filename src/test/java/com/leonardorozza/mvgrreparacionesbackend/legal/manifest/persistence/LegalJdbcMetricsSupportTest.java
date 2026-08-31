package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalJdbcMetricsSupport.Category.ADVISORY_LOCK;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalJdbcMetricsSupport.Category.DML;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalJdbcMetricsSupport.Category.OTHER;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalJdbcMetricsSupport.Category.ROW_LOCK;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalJdbcMetricsSupport.Category.SELECT;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalJdbcMetricsSupport.Category.TX_CONTROL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LegalJdbcMetricsSupportTest {

    private DataSource delegate;
    private Connection driverConnection;

    @BeforeEach
    void setUp() throws SQLException {
        delegate = mock(DataSource.class);
        driverConnection = mock(Connection.class);
        when(delegate.getConnection()).thenReturn(driverConnection);
        when(delegate.getConnection("capacity", "secret")).thenReturn(driverConnection);
    }

    @Test
    void countsEachExecutionMethodOnceAndTreatsEachBatchAsOneExecution() throws Exception {
        Statement driverStatement = mock(Statement.class);
        ResultSet driverRows = mock(ResultSet.class);
        when(driverConnection.createStatement()).thenReturn(driverStatement);
        when(driverStatement.execute("  SELECT  1 ")).thenReturn(false);
        when(driverStatement.executeQuery("SELECT 2")).thenReturn(driverRows);
        when(driverStatement.executeUpdate("UPDATE legal_publicaciones SET status = 'x'"))
                .thenReturn(1);
        when(driverStatement.executeLargeUpdate("DELETE FROM legal_publicaciones"))
                .thenReturn(1L);
        when(driverStatement.executeBatch()).thenReturn(new int[]{1, 1, 1});
        when(driverStatement.executeLargeBatch()).thenReturn(new long[]{1L, 1L, 1L});
        when(driverRows.next()).thenReturn(true, false);
        AtomicLong delays = new AtomicLong();
        LegalJdbcMetricsSupport support = LegalJdbcMetricsSupport.instrument(
                delegate,
                Duration.ofMillis(7),
                delay -> {
                    assertThat(delay).isEqualTo(Duration.ofMillis(7));
                    delays.incrementAndGet();
                });

        Statement statement = support.dataSource().getConnection().createStatement();
        statement.execute("  SELECT  1 ");
        ResultSet rows = statement.executeQuery("SELECT 2");
        assertThat(rows.next()).isTrue();
        assertThat(rows.next()).isFalse();
        statement.executeUpdate("UPDATE legal_publicaciones SET status = 'x'");
        statement.executeLargeUpdate("DELETE FROM legal_publicaciones");
        statement.addBatch("INSERT INTO legal_auditoria(id) VALUES (1)");
        statement.executeBatch();
        statement.addBatch("INSERT INTO legal_auditoria(id) VALUES (2)");
        statement.executeLargeBatch();
        LegalJdbcMetricsSupport.Snapshot snapshot = support.snapshot();

        assertThat(snapshot.statementExecutions()).isEqualTo(6L);
        assertThat(snapshot.roundTrips()).isEqualTo(6L);
        assertThat(snapshot.rowsRead()).isEqualTo(1L);
        assertThat(snapshot.executions(SELECT)).isEqualTo(2L);
        assertThat(snapshot.executions(DML)).isEqualTo(4L);
        assertThat(snapshot.executions(OTHER)).isZero();
        assertThat(snapshot.executionsContaining("SELECT", "2")).isEqualTo(1L);
        assertThat(snapshot.rowsReadContaining("SELECT", "2")).isEqualTo(1L);
        assertThat(snapshot.maximumRowsReadContaining("SELECT", "2")).isEqualTo(1L);
        assertThat(snapshot.bySql()).containsKey("SELECT 1");
        assertThat(snapshot.executionsContaining("INSERT INTO legal_auditoria")).isEqualTo(2L);
        assertThat(snapshot.bySql().keySet()).noneMatch(sql -> sql.contains(";"));
        assertThat(delays).hasValue(6L);
        verify(driverStatement).executeBatch();
        verify(driverStatement).executeLargeBatch();
    }

    @Test
    void aDriverFailureKeepsItsDurationAndARealRetryCountsAgain() throws Exception {
        PreparedStatement driverStatement = mock(PreparedStatement.class);
        SQLException driverFailure = new SQLException("driver rejected first attempt", "40001");
        when(driverConnection.prepareStatement("INSERT INTO legal_publicaciones VALUES (?)"))
                .thenReturn(driverStatement);
        when(driverStatement.executeUpdate()).thenThrow(driverFailure).thenReturn(1);
        LegalJdbcMetricsSupport support = LegalJdbcMetricsSupport.instrument(
                delegate,
                Duration.ofMillis(2));
        PreparedStatement statement = support.dataSource()
                .getConnection()
                .prepareStatement("INSERT INTO legal_publicaciones VALUES (?)");

        assertThatThrownBy(statement::executeUpdate).isSameAs(driverFailure);
        assertThat(statement.executeUpdate()).isEqualTo(1);
        LegalJdbcMetricsSupport.Snapshot snapshot = support.snapshot();
        LegalJdbcMetricsSupport.SqlSnapshot sql = snapshot.bySql()
                .get("INSERT INTO legal_publicaciones VALUES (?)");

        assertThat(snapshot.statementExecutions()).isEqualTo(2L);
        assertThat(snapshot.executions(DML)).isEqualTo(2L);
        assertThat(sql.executions()).isEqualTo(2L);
        assertThat(sql.failures()).isEqualTo(1L);
        assertThat(sql.totalDuration()).isGreaterThanOrEqualTo(Duration.ofMillis(2));
        assertThat(sql.maximumDuration()).isPositive();
        assertThat(snapshot.maximumStatementDuration()).isEqualTo(sql.maximumDuration());
        verify(driverStatement, times(2)).executeUpdate();
    }

    @Test
    void countsOnlySuccessfulTrueNextCallsAndKeepsTheInstrumentedRoute() throws Exception {
        Statement driverStatement = mock(Statement.class);
        ResultSet driverRows = mock(ResultSet.class);
        SQLException nextFailure = new SQLException("cursor failed");
        when(driverConnection.createStatement()).thenReturn(driverStatement);
        when(driverStatement.executeQuery("SELECT id FROM legal_publicaciones"))
                .thenReturn(driverRows);
        when(driverRows.next()).thenReturn(true, false).thenThrow(nextFailure);
        LegalJdbcMetricsSupport support = LegalJdbcMetricsSupport.instrument(
                delegate,
                Duration.ZERO);

        Connection connection = support.dataSource().getConnection();
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("SELECT id FROM legal_publicaciones");

        assertThat(rows.next()).isTrue();
        assertThat(rows.next()).isFalse();
        assertThatThrownBy(rows::next).isSameAs(nextFailure);
        assertThat(rows.getStatement()).isSameAs(statement);
        assertThat(statement.getConnection()).isSameAs(connection);
        assertThat(rows.unwrap(ResultSet.class)).isSameAs(rows);
        assertThat(statement.unwrap(Statement.class)).isSameAs(statement);
        assertThat(connection.unwrap(Connection.class)).isSameAs(connection);
        assertThat(support.snapshot().rowsRead()).isEqualTo(1L);
        assertThat(support.snapshot().rowsReadContaining("legal_publicaciones")).isEqualTo(1L);
        assertThat(support.snapshot().maximumRowsReadContaining("legal_publicaciones"))
                .isEqualTo(1L);
    }

    @Test
    void keepsAggregateRowsAndMaximumCardinalityPerResultSetSeparate() throws Exception {
        Statement driverStatement = mock(Statement.class);
        ResultSet firstRows = mock(ResultSet.class);
        ResultSet secondRows = mock(ResultSet.class);
        when(driverConnection.createStatement()).thenReturn(driverStatement);
        when(driverStatement.executeQuery("SELECT id FROM legal_documentos"))
                .thenReturn(firstRows, secondRows);
        when(firstRows.next()).thenReturn(true, true, false);
        when(secondRows.next()).thenReturn(true, true, true, false);
        LegalJdbcMetricsSupport support = LegalJdbcMetricsSupport.instrument(
                delegate,
                Duration.ZERO);
        Statement statement = support.dataSource().getConnection().createStatement();

        ResultSet first = statement.executeQuery("SELECT id FROM legal_documentos");
        while (first.next()) {
            // Consume the complete first driver result.
        }
        ResultSet second = statement.executeQuery("SELECT id FROM legal_documentos");
        while (second.next()) {
            // Consume the complete second driver result.
        }
        LegalJdbcMetricsSupport.SqlSnapshot sql = support.snapshot().bySql()
                .get("SELECT id FROM legal_documentos");

        assertThat(sql.executions()).isEqualTo(2L);
        assertThat(sql.rowsRead()).isEqualTo(5L);
        assertThat(sql.maximumRowsRead()).isEqualTo(3L);
    }

    @Test
    void instrumentsBothConnectionRoutesAndNeverDelaysCommitOrRollback() throws Exception {
        Connection credentialsConnection = mock(Connection.class);
        Statement driverStatement = mock(Statement.class);
        when(delegate.getConnection("capacity", "secret")).thenReturn(credentialsConnection);
        when(credentialsConnection.createStatement()).thenReturn(driverStatement);
        when(driverStatement.execute("SELECT 1")).thenReturn(false);
        AtomicLong delays = new AtomicLong();
        LegalJdbcMetricsSupport support = LegalJdbcMetricsSupport.instrument(
                delegate,
                Duration.ofSeconds(9),
                ignored -> delays.incrementAndGet());

        Connection plain = support.dataSource().getConnection();
        Connection credentials = support.dataSource().getConnection("capacity", "secret");
        assertThat(plain).isNotSameAs(driverConnection);
        assertThat(credentials).isNotSameAs(credentialsConnection);
        assertThat(support.dataSource().unwrap(DataSource.class)).isSameAs(support.dataSource());
        credentials.commit();
        credentials.rollback();
        credentials.rollback(mock(Savepoint.class));
        credentials.createStatement().execute("SELECT 1");
        LegalJdbcMetricsSupport.Snapshot snapshot = support.snapshot();

        assertThat(delays).hasValue(1L);
        assertThat(snapshot.statementExecutions()).isEqualTo(1L);
        assertThat(snapshot.commits()).isEqualTo(1L);
        assertThat(snapshot.rollbacks()).isEqualTo(1L);
        assertThat(snapshot.roundTrips()).isEqualTo(1L);
        assertThat(snapshot.executions(TX_CONTROL)).isZero();
        assertThat(snapshot.bySql()).doesNotContainKeys("<commit>", "<rollback>");
        verify(credentialsConnection).commit();
        verify(credentialsConnection).rollback();
        verify(credentialsConnection).rollback(org.mockito.ArgumentMatchers.any(Savepoint.class));
    }

    @Test
    void countsOnlySuccessfulTransactionCompletions() throws Exception {
        SQLException commitFailure = new SQLException("commit failed");
        SQLException rollbackFailure = new SQLException("rollback failed");
        org.mockito.Mockito.doThrow(commitFailure).when(driverConnection).commit();
        org.mockito.Mockito.doThrow(rollbackFailure).when(driverConnection).rollback();
        LegalJdbcMetricsSupport support = LegalJdbcMetricsSupport.instrument(
                delegate,
                Duration.ZERO);
        Connection connection = support.dataSource().getConnection();

        assertThatThrownBy(connection::commit).isSameAs(commitFailure);
        assertThatThrownBy(connection::rollback).isSameAs(rollbackFailure);

        assertThat(support.snapshot().commits()).isZero();
        assertThat(support.snapshot().rollbacks()).isZero();
    }

    @Test
    void delayFailureBeforeTheDriverIsNotCountedAsARoundTrip() throws Exception {
        Statement driverStatement = mock(Statement.class);
        SQLException delayFailure = new SQLException("latency probe interrupted");
        when(driverConnection.createStatement()).thenReturn(driverStatement);
        LegalJdbcMetricsSupport support = LegalJdbcMetricsSupport.instrument(
                delegate,
                Duration.ofMillis(5),
                ignored -> {
                    throw delayFailure;
                });

        Statement statement = support.dataSource().getConnection().createStatement();
        assertThatThrownBy(() -> statement.execute("SELECT 1")).isSameAs(delayFailure);

        assertThat(support.snapshot().roundTrips()).isZero();
        assertThat(support.snapshot().bySql()).isEmpty();
        verify(driverStatement, times(0)).execute("SELECT 1");
    }

    @Test
    void normalizesSqlAndExposesAllCapacityCategoriesAndMaxima() throws Exception {
        PreparedStatement driverStatement = mock(PreparedStatement.class);
        when(driverConnection.prepareStatement(anyString())).thenReturn(driverStatement);
        when(driverStatement.execute()).thenReturn(false);
        LegalJdbcMetricsSupport support = LegalJdbcMetricsSupport.instrument(
                delegate,
                Duration.ofMillis(1));
        Connection connection = support.dataSource().getConnection();

        execute(connection, "SELECT pg_catalog.pg_advisory_xact_lock(?)");
        execute(connection, "SELECT id FROM legal_publicaciones FOR UPDATE");
        execute(connection, "SET LOCAL statement_timeout TO '30s'");
        execute(connection, "INSERT INTO legal_publicaciones(id) VALUES (?)");
        execute(connection, "  SELECT   id\n FROM legal_documentos ");
        execute(connection, "SELECT id FROM legal_documentos");
        execute(connection, "VACUUM ANALYZE legal_publicaciones");
        LegalJdbcMetricsSupport.Snapshot snapshot = support.snapshot();

        assertThat(snapshot.executions(ADVISORY_LOCK)).isEqualTo(1L);
        assertThat(snapshot.executions(ROW_LOCK)).isEqualTo(1L);
        assertThat(snapshot.executions(TX_CONTROL)).isEqualTo(1L);
        assertThat(snapshot.executions(DML)).isEqualTo(1L);
        assertThat(snapshot.executions(SELECT)).isEqualTo(2L);
        assertThat(snapshot.executions(OTHER)).isEqualTo(1L);
        assertThat(snapshot.bySql().get("SELECT id FROM legal_documentos").executions())
                .isEqualTo(2L);
        assertThat(snapshot.maximumAdvisoryLockDuration())
                .isGreaterThanOrEqualTo(Duration.ofMillis(1));
        assertThat(snapshot.maximumStatementDuration())
                .isGreaterThanOrEqualTo(snapshot.maximumAdvisoryLockDuration());
        assertThat(snapshot.maximumStatementDuration()).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void preparedAndCallableStatementsKeepTheirOwnNormalizedSql() throws Exception {
        PreparedStatement preparedDriver = mock(PreparedStatement.class);
        CallableStatement callableDriver = mock(CallableStatement.class);
        when(driverConnection.prepareStatement(" UPDATE  legal_publicaciones SET status = ? "))
                .thenReturn(preparedDriver);
        when(driverConnection.prepareCall("{ call legal_refresh(?) }")).thenReturn(callableDriver);
        when(preparedDriver.executeLargeUpdate()).thenReturn(1L);
        when(callableDriver.execute()).thenReturn(false);
        LegalJdbcMetricsSupport support = LegalJdbcMetricsSupport.instrument(
                delegate,
                Duration.ZERO);
        Connection connection = support.dataSource().getConnection();

        connection.prepareStatement(" UPDATE  legal_publicaciones SET status = ? ")
                .executeLargeUpdate();
        connection.prepareCall("{ call legal_refresh(?) }").execute();
        LegalJdbcMetricsSupport.Snapshot snapshot = support.snapshot();

        assertThat(snapshot.bySql())
                .containsKeys(
                        "UPDATE legal_publicaciones SET status = ?",
                        "{ call legal_refresh(?) }");
        assertThat(snapshot.executions(DML)).isEqualTo(1L);
        assertThat(snapshot.executions(OTHER)).isEqualTo(1L);
    }

    @Test
    void resetClearsEveryCounterAndSqlInventory() throws Exception {
        Statement driverStatement = mock(Statement.class);
        when(driverConnection.createStatement()).thenReturn(driverStatement);
        when(driverStatement.execute("SELECT 1")).thenReturn(false);
        LegalJdbcMetricsSupport support = LegalJdbcMetricsSupport.instrument(
                delegate,
                Duration.ZERO);
        support.dataSource().getConnection().createStatement().execute("SELECT 1");

        support.reset();
        LegalJdbcMetricsSupport.Snapshot snapshot = support.snapshot();

        assertThat(snapshot.roundTrips()).isZero();
        assertThat(snapshot.statementExecutions()).isZero();
        assertThat(snapshot.commits()).isZero();
        assertThat(snapshot.rollbacks()).isZero();
        assertThat(snapshot.rowsRead()).isZero();
        assertThat(snapshot.maximumStatementDuration()).isZero();
        assertThat(snapshot.maximumAdvisoryLockDuration()).isZero();
        assertThat(snapshot.byCategory()).containsOnly(
                org.assertj.core.api.Assertions.entry(ADVISORY_LOCK, 0L),
                org.assertj.core.api.Assertions.entry(DML, 0L),
                org.assertj.core.api.Assertions.entry(ROW_LOCK, 0L),
                org.assertj.core.api.Assertions.entry(TX_CONTROL, 0L),
                org.assertj.core.api.Assertions.entry(SELECT, 0L),
                org.assertj.core.api.Assertions.entry(OTHER, 0L));
        assertThat(snapshot.bySql()).isEmpty();
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        connection.prepareStatement(sql).execute();
    }
}
