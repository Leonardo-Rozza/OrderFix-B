package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LegalPublicDocumentDataSourceTest {

    private static final Duration BUDGET = Duration.ofSeconds(2);

    @Test
    void requiresAnActiveScopeAndNeverAcceptsAlternativeCredentials() {
        DataSource pool = mock(DataSource.class);
        try (LegalPublicDocumentDataSource source = new LegalPublicDocumentDataSource(pool, BUDGET)) {
            assertThatThrownBy(source::getConnection).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> source.getConnection("owner", "unused"))
                    .isInstanceOf(SQLFeatureNotSupportedException.class);
            source.withinDeadline(deadline -> {
                assertThatThrownBy(() -> source.getConnection("owner", "unused"))
                        .isInstanceOf(SQLFeatureNotSupportedException.class);
                return null;
            });
        }
        verifyNoInteractions(pool);
    }

    @Test
    void nestedOperationsReuseTheOuterDeadlineEvenAfterAnInnerFailure() {
        DataSource pool = mock(DataSource.class);
        IllegalStateException failure = new IllegalStateException("inner reader failed");
        try (LegalPublicDocumentDataSource source = new LegalPublicDocumentDataSource(pool, BUDGET)) {
            String result = source.withinDeadline(outer -> {
                LegalPublicDocumentDeadline firstInner = source.withinDeadline(inner -> inner);
                assertThat(firstInner).isSameAs(outer);
                assertThatThrownBy(() -> source.withinDeadline(inner -> {
                    assertThat(inner).isSameAs(outer);
                    throw failure;
                })).isSameAs(failure);
                LegalPublicDocumentDeadline subsequentInner = source.withinDeadline(inner -> inner);
                assertThat(subsequentInner).isSameAs(outer);
                return "catalog";
            });
            assertThat(result).isEqualTo("catalog");
            assertThatThrownBy(source::getConnection).isInstanceOf(SQLException.class);
        }
        verifyNoInteractions(pool);
    }

    @Test
    void metadataAndItsResultSetKeepTheSameRestrictedConnection() throws SQLException {
        DataSource pool = mock(DataSource.class);
        Connection driver = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        Statement statement = mock(Statement.class);
        ResultSet rows = mock(ResultSet.class);
        ResultSet detachedRows = mock(ResultSet.class);
        when(pool.getConnection()).thenReturn(driver);
        when(driver.getMetaData()).thenReturn(metadata);
        when(metadata.getConnection()).thenReturn(driver);
        when(statement.getConnection()).thenReturn(driver);
        when(metadata.getTables(null, null, "%", null)).thenReturn(rows, detachedRows);
        when(rows.getStatement()).thenReturn(statement);

        try (LegalPublicDocumentDataSource source = new LegalPublicDocumentDataSource(pool, BUDGET)) {
            source.withinDeadline(deadline -> {
                try (Connection lease = source.getConnection()) {
                    DatabaseMetaData exposed = lease.getMetaData();
                    assertThat(exposed).isNotSameAs(metadata);
                    assertThat(exposed.getConnection()).isSameAs(lease);
                    try (ResultSet catalog = exposed.getTables(null, null, "%", null)) {
                        Statement catalogStatement = catalog.getStatement();
                        assertThat(catalog).isNotSameAs(rows);
                        assertThat(catalogStatement).isNotSameAs(statement);
                        assertThat(catalogStatement.getConnection()).isSameAs(lease);
                    }
                    try (ResultSet detached = exposed.getTables(null, null, "%", null)) {
                        assertThat(detached.getStatement()).isNull();
                    }
                    return null;
                } catch (SQLException exception) {
                    throw new AssertionError(exception);
                }
            });
        }
        verify(metadata, never()).getConnection();
        verify(statement, never()).getConnection();
        verify(rows).close();
        verify(detachedRows).close();
        verify(driver).close();
    }

    @Test
    void aFailedOperationClearsItsScopeAndTheNextOperationCanAcquireItsOwnLease() throws SQLException {
        DataSource pool = mock(DataSource.class);
        Connection driver = mock(Connection.class);
        when(pool.getConnection()).thenReturn(driver);
        IllegalStateException failure = new IllegalStateException("projection rejected");
        try (LegalPublicDocumentDataSource source = new LegalPublicDocumentDataSource(pool, BUDGET)) {
            assertThatThrownBy(() -> source.withinDeadline(deadline -> {
                throw failure;
            })).isSameAs(failure);
            assertThatThrownBy(source::getConnection).isInstanceOf(SQLException.class);

            source.withinDeadline(deadline -> {
                try (Connection ignored = source.getConnection()) {
                    return null;
                } catch (SQLException exception) {
                    throw new AssertionError(exception);
                }
            });
            assertThatThrownBy(source::getConnection).isInstanceOf(SQLException.class);
        }
        verify(pool).getConnection();
        verify(driver).close();
        verify(driver, never()).abort(any());
    }

    @Test
    void doesNotBeginBorrowingWhenLessThanThePoolBoundRemains() {
        DataSource pool = mock(DataSource.class);
        try (LegalPublicDocumentDataSource source = new LegalPublicDocumentDataSource(
                pool, Duration.ofMillis(999))) {
            assertThatThrownBy(() -> source.withinDeadline(deadline -> {
                try {
                    return source.getConnection();
                } catch (SQLException exception) {
                    throw new AssertionError(exception);
                }
            })).isInstanceOf(LegalPublicDocumentReadException.class);
        }
        verifyNoInteractions(pool);
    }

    @Test
    void interruptionDuringBorrowClosesTheLeaseAndPreservesAnyCloseFailure() throws SQLException {
        boolean wasInterrupted = Thread.interrupted();
        DataSource pool = mock(DataSource.class);
        Connection driver = mock(Connection.class);
        SQLException closeFailure = new SQLException("close failed");
        when(pool.getConnection()).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            return driver;
        });
        doThrow(closeFailure).when(driver).close();
        try (LegalPublicDocumentDataSource source = new LegalPublicDocumentDataSource(pool, BUDGET)) {
            assertThatThrownBy(() -> source.withinDeadline(deadline -> {
                try {
                    return source.getConnection();
                } catch (SQLException exception) {
                    throw new AssertionError(exception);
                }
            })).isInstanceOf(LegalPublicDocumentReadException.class)
                    .hasSuppressedException(closeFailure);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            restoreInterrupt(wasInterrupted);
        }
        verify(driver).close();
        verify(driver, never()).setNetworkTimeout(any(), anyInt());
    }

    @Test
    void networkTimeoutSetupFailureClosesTheAcquiredConnectionWithoutReplacingTheFailure()
            throws SQLException {
        DataSource pool = mock(DataSource.class);
        Connection driver = mock(Connection.class);
        SQLException setupFailure = new SQLException("cannot bound network I/O");
        SQLException closeFailure = new SQLException("cannot return lease");
        when(pool.getConnection()).thenReturn(driver);
        doThrow(setupFailure).when(driver).setNetworkTimeout(any(), anyInt());
        doThrow(closeFailure).when(driver).close();

        try (LegalPublicDocumentDataSource source = new LegalPublicDocumentDataSource(pool, BUDGET)) {
            source.withinDeadline(deadline -> {
                assertThatThrownBy(source::getConnection).isSameAs(setupFailure)
                        .hasSuppressedException(closeFailure);
                return null;
            });
        }
        verify(driver).close();
        verify(driver, never()).abort(any());
    }

    @Test
    void closingALeaseIsIdempotentAndPreventsLateJdbcActionsOrShutdownAbort() throws SQLException {
        DataSource pool = mock(DataSource.class);
        Connection driver = mock(Connection.class);
        when(pool.getConnection()).thenReturn(driver);

        try (LegalPublicDocumentDataSource source = new LegalPublicDocumentDataSource(pool, BUDGET)) {
            source.withinDeadline(deadline -> {
                try {
                    Connection lease = source.getConnection();
                    lease.close();
                    lease.close();
                    assertThatThrownBy(lease::createStatement)
                            .isInstanceOf(SQLException.class);
                    assertThatThrownBy(lease::rollback)
                            .isInstanceOf(SQLException.class);
                    assertThatThrownBy(() -> lease.setAutoCommit(true))
                            .isInstanceOf(SQLException.class);
                    return null;
                } catch (SQLException exception) {
                    throw new AssertionError(exception);
                }
            });
        }
        verify(driver, times(1)).close();
        verify(driver, never()).rollback();
        verify(driver, never()).setAutoCommit(true);
        verify(driver, never()).createStatement();
        verify(driver, never()).abort(any());
    }

    @Test
    void interruptionDuringFetchFailsClosedAndStillAllowsRollbackAndAllResourceCloses()
            throws SQLException {
        boolean wasInterrupted = Thread.interrupted();
        DataSource pool = mock(DataSource.class);
        Connection driver = mock(Connection.class);
        Statement query = mock(Statement.class);
        Statement settings = mock(Statement.class);
        ResultSet rows = mock(ResultSet.class);
        when(pool.getConnection()).thenReturn(driver);
        when(driver.createStatement()).thenReturn(query, settings);
        when(query.executeQuery("SELECT fixture")).thenReturn(rows);
        when(rows.next()).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            return true;
        });

        try (LegalPublicDocumentDataSource source = new LegalPublicDocumentDataSource(pool, BUDGET)) {
            assertThatThrownBy(() -> source.withinDeadline(deadline -> {
                try (Connection lease = source.getConnection()) {
                    try (Statement statement = lease.createStatement();
                         ResultSet cursor = statement.executeQuery("SELECT fixture")) {
                        return cursor.next();
                    } finally {
                        lease.rollback();
                    }
                } catch (SQLException exception) {
                    throw new AssertionError(exception);
                }
            })).isInstanceOf(LegalPublicDocumentReadException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            restoreInterrupt(wasInterrupted);
        }
        verify(rows).next();
        verify(rows).close();
        verify(query).close();
        verify(settings).execute(anyString());
        verify(settings).close();
        verify(driver).rollback();
        verify(driver).close();
    }

    @Test
    void interruptionDuringCommitCannotReturnSuccessAndRollbackRemainsAvailable() throws SQLException {
        boolean wasInterrupted = Thread.interrupted();
        DataSource pool = mock(DataSource.class);
        Connection driver = mock(Connection.class);
        when(pool.getConnection()).thenReturn(driver);
        doAnswer(invocation -> {
            Thread.currentThread().interrupt();
            return null;
        }).when(driver).commit();

        try (LegalPublicDocumentDataSource source = new LegalPublicDocumentDataSource(pool, BUDGET)) {
            assertThatThrownBy(() -> source.withinDeadline(deadline -> {
                try (Connection lease = source.getConnection()) {
                    try {
                        lease.commit();
                        return "should not escape";
                    } finally {
                        lease.rollback();
                    }
                } catch (SQLException exception) {
                    throw new AssertionError(exception);
                }
            })).isInstanceOf(LegalPublicDocumentReadException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            restoreInterrupt(wasInterrupted);
        }
        verify(driver).commit();
        verify(driver).rollback();
        verify(driver).close();
    }

    @Test
    void sqlExecutionFailurePropagatesUnchangedAndDoesNotPreventRollbackOrClosing() throws SQLException {
        DataSource pool = mock(DataSource.class);
        Connection driver = mock(Connection.class);
        Statement query = mock(Statement.class);
        Statement settings = mock(Statement.class);
        SQLException failure = new SQLException("cancelled by PostgreSQL", "57014");
        when(pool.getConnection()).thenReturn(driver);
        when(driver.createStatement()).thenReturn(query, settings);
        when(query.executeQuery("SELECT fixture")).thenThrow(failure);

        try (LegalPublicDocumentDataSource source = new LegalPublicDocumentDataSource(pool, BUDGET)) {
            source.withinDeadline(deadline -> {
                try (Connection lease = source.getConnection();
                     Statement statement = lease.createStatement()) {
                    assertThatThrownBy(() -> statement.executeQuery("SELECT fixture"))
                            .isSameAs(failure);
                    lease.rollback();
                    return null;
                } catch (SQLException exception) {
                    throw new AssertionError(exception);
                }
            });
        }
        verify(query).close();
        verify(settings).close();
        verify(driver).rollback();
        verify(driver).close();
        verify(query, times(1)).executeQuery("SELECT fixture");
    }

    @Test
    void theFinalDeadlineCheckRejectsAResultProducedByAnInterruptedCallback() {
        boolean wasInterrupted = Thread.interrupted();
        DataSource pool = mock(DataSource.class);
        try (LegalPublicDocumentDataSource source = new LegalPublicDocumentDataSource(pool, BUDGET)) {
            assertThatThrownBy(() -> source.withinDeadline(deadline -> {
                Thread.currentThread().interrupt();
                return "must not escape";
            })).isInstanceOf(LegalPublicDocumentReadException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            restoreInterrupt(wasInterrupted);
        }
        verifyNoInteractions(pool);
    }

    @Test
    void shutdownCancelsTheActiveCursorAndAbortsBeforeTheOwningScopeClosesItsLease()
            throws SQLException {
        DataSource pool = mock(DataSource.class);
        Connection driver = mock(Connection.class);
        Statement query = mock(Statement.class);
        Statement settings = mock(Statement.class);
        ResultSet rows = mock(ResultSet.class);
        when(pool.getConnection()).thenReturn(driver);
        when(driver.createStatement()).thenReturn(query, settings);
        when(query.executeQuery("SELECT fixture")).thenReturn(rows);

        try (LegalPublicDocumentDataSource source = new LegalPublicDocumentDataSource(pool, BUDGET)) {
            assertThatThrownBy(() -> source.withinDeadline(deadline -> {
                try (Connection lease = source.getConnection()) {
                    try (Statement statement = lease.createStatement();
                         ResultSet cursor = statement.executeQuery("SELECT fixture")) {
                        source.close();
                        return cursor.next();
                    } finally {
                        lease.rollback();
                    }
                } catch (SQLException exception) {
                    throw new AssertionError(exception);
                }
            })).isInstanceOf(LegalPublicDocumentReadException.class);
        }

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(query, driver, rows);
        order.verify(query).cancel();
        order.verify(driver).abort(any());
        order.verify(rows).close();
        order.verify(query).close();
        order.verify(driver).rollback();
        order.verify(driver).close();
        verify(rows, never()).next();
        verify(driver, times(1)).abort(any());
    }

    private static void restoreInterrupt(boolean wasInterrupted) {
        Thread.interrupted();
        if (wasInterrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
