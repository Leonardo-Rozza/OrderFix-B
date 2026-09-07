package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LegalPrivateRequirementsDataSourceTest {

    private static final Duration BUDGET = Duration.ofSeconds(2);

    @Test
    void historicalConstructorsCannotClaimRegistrationOrAcceptItsLargerBudget() {
        DataSource pool = mock(DataSource.class);
        AtomicLong clock = new AtomicLong();
        for (Duration excessive : List.of(Duration.ofSeconds(15).plusNanos(1), Duration.ofSeconds(30), Duration.ofSeconds(35))) {
            assertThatThrownBy(() -> new LegalPrivateRequirementsDataSource(pool, excessive))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new LegalPrivateRequirementsDataSource(pool, excessive, clock::get))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new LegalPrivateRequirementsDataSource(pool, excessive, clock::get, 1_000))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        try (var historical = new LegalPrivateRequirementsDataSource(pool, Duration.ofSeconds(15));
             var acceptance = new LegalPrivateRequirementsDataSource(pool, Duration.ofSeconds(15), clock::get, 1_000);
             var registration = LegalPrivateRequirementsDataSource.registration(pool)) {
            assertThat(historical.isRegistrationBoundary()).isFalse();
            assertThat(acceptance.isRegistrationBoundary()).isFalse();
            assertThat(registration.isRegistrationBoundary()).isTrue();
        }
        verifyNoInteractions(pool);
    }

    @Test
    void registrationStartsOnOperationEntryBeforeBorrowAndNestedPhasesKeepTheSameThirtySecondDeadline() throws SQLException {
        DataSource pool = mock(DataSource.class);
        Connection driver = mock(Connection.class);
        AtomicLong clock = new AtomicLong();
        AtomicInteger clockReads = new AtomicInteger();
        when(pool.getConnection()).thenReturn(driver);
        try (var source = LegalPrivateRequirementsDataSource.registration(pool, () -> {
            clockReads.incrementAndGet(); return clock.get();
        })) {
            assertThat(clockReads).hasValue(0);
            clock.set(50_000_000_000L);
            String result = source.withinDeadline(outer -> {
                assertThat(outer.remainingMillis()).isEqualTo(30_000);
                clock.addAndGet(11_000_000_000L);
                source.withinDeadline(inner -> {
                    assertThat(inner).isSameAs(outer);
                    assertThat(inner.remainingMillis()).isEqualTo(19_000);
                    clock.addAndGet(5_000_000_000L);
                    return null;
                });
                assertThat(outer.remainingMillis()).isEqualTo(14_000);
                try (Connection lease = source.getConnection()) {
                    verify(driver).setNetworkTimeout(any(), eq(6_000));
                    clock.addAndGet(13_000_000_000L);
                    lease.rollback();
                    assertThat(outer.remainingMillis()).isEqualTo(1_000);
                    return "within the original registration operation";
                } catch (SQLException failure) { throw new AssertionError(failure); }
            });
            assertThat(result).isEqualTo("within the original registration operation");
            assertThat(source.withinDeadline(LegalPrivateRequirementsDeadline::remainingMillis)).isEqualTo(30_000);
            assertThatThrownBy(source::getConnection).isInstanceOf(SQLException.class);
        }
        verify(pool).getConnection(); verify(driver).rollback(); verify(driver).close();
    }

    @Test
    void registrationChargesPreBorrowWorkAndDoesNotStartABorrowAfterThirtySeconds() {
        DataSource pool = mock(DataSource.class);
        AtomicLong clock = new AtomicLong();
        try (var source = LegalPrivateRequirementsDataSource.registration(pool, clock::get)) {
            assertThatThrownBy(() -> source.withinDeadline(deadline -> {
                clock.set(30_000_000_000L);
                assertThatThrownBy(source::getConnection).isInstanceOf(LegalPrivateRequirementsReadException.class);
                source.withinDeadline(inner -> "cannot reset the expired operation");
                return "must not escape";
            })).isInstanceOf(LegalPrivateRequirementsReadException.class);
        }
        verifyNoInteractions(pool);
    }

    @Test
    void registrationTransportMarginLeavesSqlAtFiveSecondsAndShrinksForFetchAndCommit() throws SQLException {
        DataSource pool = mock(DataSource.class);
        Connection driver = mock(Connection.class);
        Statement query = mock(Statement.class);
        Statement settings = mock(Statement.class);
        ResultSet rows = mock(ResultSet.class);
        AtomicLong clock = new AtomicLong();
        when(pool.getConnection()).thenReturn(driver);
        when(driver.createStatement()).thenReturn(query, settings);
        when(query.executeQuery("SELECT registration fixture")).thenReturn(rows);
        try (var source = LegalPrivateRequirementsDataSource.registration(pool, clock::get)) {
            source.withinDeadline(deadline -> {
                try (Connection lease = source.getConnection(); Statement statement = lease.createStatement()) {
                    try (ResultSet result = statement.executeQuery("SELECT registration fixture")) {
                        verify(driver, atLeastOnce()).setNetworkTimeout(any(), eq(6_000));
                        verify(query).setQueryTimeout(5);
                        verify(settings).execute("SET LOCAL statement_timeout TO '5000ms'");
                        clock.set(29_250_000_000L);
                        assertThat(result.next()).isFalse();
                        verify(driver).setNetworkTimeout(any(), eq(750));
                        assertThat(deadline.remainingMillis()).isEqualTo(750);
                    }
                    clock.set(29_500_000_000L);
                    lease.commit();
                    verify(driver).setNetworkTimeout(any(), eq(500));
                    assertThat(deadline.remainingMillis()).isEqualTo(500);
                    return null;
                } catch (SQLException failure) { throw new AssertionError(failure); }
            });
        }
        verify(driver).commit(); verify(driver, never()).rollback(); verify(driver).close();
        verify(rows).close(); verify(query).close(); verify(settings).close();
    }

    @Test
    void registrationCleanupFailureNearThirtySecondsRejectsDeliveryAfterCommittedNotification() throws SQLException {
        DataSource pool = mock(DataSource.class);
        Connection driver = mock(Connection.class);
        AtomicLong clock = new AtomicLong();
        when(pool.getConnection()).thenReturn(driver);
        mutableDriver(driver);
        SQLException failure = new SQLException("synthetic registration release failed", "08006");
        doAnswer(invocation -> { clock.set(29_500_000_000L); throw failure; }).when(driver).close();
        Notifications notifications = new Notifications();
        try (var source = LegalPrivateRequirementsDataSource.registration(pool, clock::get)) {
            TransactionTemplate transaction = mutableTransaction(source);
            assertThatThrownBy(() -> source.withinDeadline(deadline -> transaction.execute(status -> {
                TransactionSynchronizationManager.registerSynchronization(notifications);
                return "cannot deliver after failed cleanup";
            }))).isInstanceOf(LegalPrivateRequirementsReadException.class).hasCause(failure);
            assertThat(notifications.afterCommits).isEqualTo(1);
            assertThat(notifications.completions).containsExactly(TransactionSynchronization.STATUS_COMMITTED);
            assertTransactionReleased(source);
        }
        verify(driver).commit(); verify(driver, never()).rollback(); verify(driver).close();
    }

    @ParameterizedTest @ValueSource(ints = {-1, 1_001})
    void rejectsAnUnboundedTransportMarginBeforeBorrowing(int grace) {
        DataSource pool = mock(DataSource.class);
        assertThatThrownBy(() -> new LegalPrivateRequirementsDataSource(
                pool, Duration.ofSeconds(15), System::nanoTime, grace))
                .isInstanceOf(IllegalArgumentException.class).hasNoCause();
        verifyNoInteractions(pool);
    }

    @ParameterizedTest @ValueSource(ints = {0, 1_000})
    void transportMarginPreservesSqlLimitsAndShrinksForFetchAndCommit(int grace) throws SQLException {
        DataSource pool = mock(DataSource.class);
        Connection driver = mock(Connection.class);
        Statement query = mock(Statement.class);
        Statement settings = mock(Statement.class);
        ResultSet rows = mock(ResultSet.class);
        AtomicLong clock = new AtomicLong();
        when(pool.getConnection()).thenReturn(driver);
        when(driver.createStatement()).thenReturn(query, settings);
        when(query.executeQuery("SELECT fixture")).thenReturn(rows);

        try (LegalPrivateRequirementsDataSource source = grace == 0
                ? new LegalPrivateRequirementsDataSource(pool, Duration.ofSeconds(15), clock::get)
                : new LegalPrivateRequirementsDataSource(pool, Duration.ofSeconds(15), clock::get, grace)) {
            String receipt = source.withinDeadline(deadline -> {
                try (Connection lease = source.getConnection(); Statement statement = lease.createStatement()) {
                    try (ResultSet result = statement.executeQuery("SELECT fixture")) {
                        verify(driver, atLeastOnce()).setNetworkTimeout(any(), eq(5_000 + grace));
                        verify(query).setQueryTimeout(5);
                        verify(settings).execute("SET LOCAL statement_timeout TO '5000ms'");
                        clock.set(14_250_000_000L);
                        assertThat(result.next()).isFalse();
                        verify(driver).setNetworkTimeout(any(), eq(750));
                        assertThat(deadline.remainingMillis()).isEqualTo(750);
                    }
                    clock.set(14_500_000_000L);
                    lease.commit();
                    verify(driver).setNetworkTimeout(any(), eq(500));
                    assertThat(deadline.remainingMillis()).isEqualTo(500);
                    return "committed";
                } catch (SQLException failure) {
                    throw new AssertionError(failure);
                }
            });
            assertThat(receipt).isEqualTo("committed");
        }
        verify(driver).commit();
        verify(driver, never()).rollback();
        verify(driver).close();
        verify(rows).close();
        verify(query).close();
        verify(settings).close();
    }

    @Test void transportMarginDoesNotAuthorizeLateCommitAndStillPermitsRollbackAndClose() throws SQLException {
        DataSource pool = mock(DataSource.class);
        Connection driver = mock(Connection.class);
        AtomicLong clock = new AtomicLong();
        when(pool.getConnection()).thenReturn(driver);

        try (LegalPrivateRequirementsDataSource source = new LegalPrivateRequirementsDataSource(
                pool, Duration.ofSeconds(15), clock::get, 1_000)) {
            assertThatThrownBy(() -> source.withinDeadline(deadline -> {
                try (Connection lease = source.getConnection()) {
                    verify(driver).setNetworkTimeout(any(), eq(6_000));
                    clock.set(15_000_000_000L);
                    assertThatThrownBy(lease::commit).isInstanceOf(LegalPrivateRequirementsReadException.class);
                    lease.rollback();
                    return "must not escape after expiry";
                } catch (SQLException failure) {
                    throw new AssertionError(failure);
                }
            })).isInstanceOf(LegalPrivateRequirementsReadException.class);
        }
        verify(driver, never()).commit();
        verify(driver).rollback();
        verify(driver).close();
    }

    @Test
    void requiresAnActiveScopeAndNeverAcceptsAlternativeCredentials() {
        DataSource pool = mock(DataSource.class);
        try (LegalPrivateRequirementsDataSource source = new LegalPrivateRequirementsDataSource(pool, BUDGET)) {
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
        AtomicLong clock = new AtomicLong();
        try (LegalPrivateRequirementsDataSource source = new LegalPrivateRequirementsDataSource(
                pool, BUDGET, clock::get)) {
            String result = source.withinDeadline(outer -> {
                clock.addAndGet(100_000_000L);
                LegalPrivateRequirementsDeadline firstInner = source.withinDeadline(inner -> inner);
                assertThat(firstInner).isSameAs(outer);
                assertThat(firstInner.remainingMillis()).isEqualTo(1_900);
                assertThatThrownBy(() -> source.withinDeadline(inner -> {
                    assertThat(inner).isSameAs(outer);
                    clock.addAndGet(200_000_000L);
                    throw failure;
                })).isSameAs(failure);
                LegalPrivateRequirementsDeadline subsequentInner = source.withinDeadline(inner -> inner);
                assertThat(subsequentInner).isSameAs(outer);
                assertThat(subsequentInner.remainingMillis()).isEqualTo(1_700);
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

        try (LegalPrivateRequirementsDataSource source = new LegalPrivateRequirementsDataSource(pool, BUDGET)) {
            source.withinDeadline(deadline -> {
                try (Connection lease = source.getConnection()) {
                    DatabaseMetaData exposed = lease.getMetaData();
                    assertThatThrownBy(() -> lease.unwrap(Connection.class)).isInstanceOf(SQLException.class);
                    assertThat(lease.isWrapperFor(Connection.class)).isFalse();
                    assertThat(exposed).isNotSameAs(metadata);
                    assertThat(exposed.getConnection()).isSameAs(lease);
                    assertThatThrownBy(() -> exposed.unwrap(DatabaseMetaData.class))
                            .isInstanceOf(SQLException.class);
                    assertThat(exposed.isWrapperFor(DatabaseMetaData.class)).isFalse();
                    try (ResultSet catalog = exposed.getTables(null, null, "%", null)) {
                        Statement catalogStatement = catalog.getStatement();
                        assertThat(catalog).isNotSameAs(rows);
                        assertThat(catalogStatement).isNotSameAs(statement);
                        assertThat(catalogStatement.getConnection()).isSameAs(lease);
                        assertThatThrownBy(() -> catalog.unwrap(ResultSet.class))
                                .isInstanceOf(SQLException.class);
                        assertThat(catalog.isWrapperFor(ResultSet.class)).isFalse();
                        assertThatThrownBy(() -> catalogStatement.unwrap(Statement.class))
                                .isInstanceOf(SQLException.class);
                        assertThat(catalogStatement.isWrapperFor(Statement.class)).isFalse();
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
        try (LegalPrivateRequirementsDataSource source = new LegalPrivateRequirementsDataSource(pool, BUDGET)) {
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
        try (LegalPrivateRequirementsDataSource source = new LegalPrivateRequirementsDataSource(
                pool, Duration.ofMillis(999))) {
            assertThatThrownBy(() -> source.withinDeadline(deadline -> {
                try {
                    return source.getConnection();
                } catch (SQLException exception) {
                    throw new AssertionError(exception);
                }
            })).isInstanceOf(LegalPrivateRequirementsReadException.class);
        }
        verifyNoInteractions(pool);
    }

    @Test
    void executionAndFetchConsumeTheOriginalRemainderInsteadOfResettingFiveSeconds() throws SQLException {
        DataSource pool = mock(DataSource.class);
        Connection driver = mock(Connection.class);
        Statement query = mock(Statement.class);
        Statement settings = mock(Statement.class);
        ResultSet firstRows = mock(ResultSet.class);
        ResultSet secondRows = mock(ResultSet.class);
        when(pool.getConnection()).thenReturn(driver);
        when(driver.createStatement()).thenReturn(query, settings);
        when(query.executeQuery("SELECT fixture")).thenReturn(firstRows, secondRows);
        AtomicLong clock = new AtomicLong();

        try (LegalPrivateRequirementsDataSource source = new LegalPrivateRequirementsDataSource(
                pool, Duration.ofSeconds(15), clock::get)) {
            source.withinDeadline(deadline -> {
                try (Connection lease = source.getConnection(); Statement statement = lease.createStatement()) {
                    try (ResultSet rows = statement.executeQuery("SELECT fixture")) {
                        verify(query).setQueryTimeout(5);
                        verify(settings).execute("SET LOCAL statement_timeout TO '5000ms'");
                        clock.set(14_250_000_000L);
                        assertThat(rows.next()).isFalse();
                        verify(driver).setNetworkTimeout(any(), eq(750));
                        assertThat(deadline.remainingMillis()).isEqualTo(750);
                    }
                    clock.set(14_500_000_000L);
                    try (ResultSet ignored = statement.executeQuery("SELECT fixture")) {
                        verify(query).setQueryTimeout(1);
                        verify(settings).execute("SET LOCAL statement_timeout TO '500ms'");
                        assertThat(deadline.remainingMillis()).isEqualTo(500);
                    }
                    return null;
                } catch (SQLException failure) {
                    throw new AssertionError(failure);
                }
            });
        }
        verify(firstRows).close();
        verify(secondRows).close();
        verify(query).close();
        verify(settings, times(2)).close();
        verify(driver).close();
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
        try (LegalPrivateRequirementsDataSource source = new LegalPrivateRequirementsDataSource(pool, BUDGET)) {
            assertThatThrownBy(() -> source.withinDeadline(deadline -> {
                try {
                    return source.getConnection();
                } catch (SQLException exception) {
                    throw new AssertionError(exception);
                }
            })).isInstanceOf(LegalPrivateRequirementsReadException.class)
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

        try (LegalPrivateRequirementsDataSource source = new LegalPrivateRequirementsDataSource(pool, BUDGET)) {
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

        try (LegalPrivateRequirementsDataSource source = new LegalPrivateRequirementsDataSource(pool, BUDGET)) {
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

        try (LegalPrivateRequirementsDataSource source = new LegalPrivateRequirementsDataSource(pool, BUDGET)) {
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
            })).isInstanceOf(LegalPrivateRequirementsReadException.class);
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
    void interruptionDuringSuccessfulCommitFailsOnlyAfterSpringReportsCommitted() throws SQLException {
        boolean wasInterrupted = Thread.interrupted();
        DataSource pool = mock(DataSource.class);
        Connection driver = mock(Connection.class);
        when(pool.getConnection()).thenReturn(driver);
        mutableDriver(driver);
        doAnswer(invocation -> {
            Thread.currentThread().interrupt();
            return null;
        }).when(driver).commit();

        try (LegalPrivateRequirementsDataSource source = new LegalPrivateRequirementsDataSource(pool, BUDGET)) {
            TransactionTemplate transaction = mutableTransaction(source);
            Notifications notifications = new Notifications();
            assertThatThrownBy(() -> source.withinDeadline(deadline -> transaction.execute(status -> {
                TransactionSynchronizationManager.registerSynchronization(notifications);
                return "should not escape";
            }))).isInstanceOf(LegalPrivateRequirementsReadException.class);
            assertThat(notifications.afterCommits).isEqualTo(1);
            assertThat(notifications.completions).containsExactly(TransactionSynchronization.STATUS_COMMITTED);
            assertTransactionReleased(source);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            restoreInterrupt(wasInterrupted);
        }
        verify(driver).commit();
        verify(driver, never()).rollback();
        verify(driver).close();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void anExpiredPreCommitCheckPreventsDelegateCommitAndSpringReportsRolledBack(boolean registration) throws SQLException {
        DataSource pool = mock(DataSource.class);
        Connection driver = mock(Connection.class);
        when(pool.getConnection()).thenReturn(driver);
        mutableDriver(driver);
        AtomicLong clock = new AtomicLong();
        Notifications notifications = new Notifications();

        try (LegalPrivateRequirementsDataSource source = clockedSource(pool, clock, registration)) {
            TransactionTemplate transaction = mutableTransaction(source);
            assertThatThrownBy(() -> source.withinDeadline(deadline -> transaction.execute(status -> {
                TransactionSynchronizationManager.registerSynchronization(notifications);
                // The callback succeeds. Expiry happens just before Spring invokes JDBC COMMIT,
                // specifically exercising the proxy precheck rather than callback rollback.
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void beforeCompletion() {
                        clock.set(registration ? 30_000_000_000L : BUDGET.toNanos());
                    }
                });
                return "complete requirements";
            }))).isInstanceOf(LegalPrivateRequirementsReadException.class);

            assertThat(notifications.afterCommits).isZero();
            assertThat(notifications.completions).containsExactly(TransactionSynchronization.STATUS_ROLLED_BACK);
            assertTransactionReleased(source);
        }
        verify(driver, never()).commit();
        verify(driver).rollback();
        verify(driver).close();
    }

    @Test
    void aDelegateCommitSQLExceptionKeepsItsCauseAndReportsUnknownWithoutRollback() throws SQLException {
        DataSource pool = mock(DataSource.class);
        Connection driver = mock(Connection.class);
        when(pool.getConnection()).thenReturn(driver);
        mutableDriver(driver);
        SQLException failure = new SQLException("commit reply lost", "08006");
        doThrow(failure).when(driver).commit();
        Notifications notifications = new Notifications();

        try (LegalPrivateRequirementsDataSource source = new LegalPrivateRequirementsDataSource(pool, BUDGET)) {
            TransactionTemplate transaction = mutableTransaction(source);
            assertThatThrownBy(() -> source.withinDeadline(deadline -> transaction.execute(status -> {
                TransactionSynchronizationManager.registerSynchronization(notifications);
                return "must not escape";
            }))).isInstanceOf(TransactionSystemException.class).hasCause(failure);

            assertThat(notifications.afterCommits).isZero();
            assertThat(notifications.completions).containsExactly(TransactionSynchronization.STATUS_UNKNOWN);
            assertTransactionReleased(source);
        }
        verify(driver).commit();
        verify(driver, never()).rollback();
        verify(driver).close();
    }

    @Test
    void anUncheckedDelegateCommitFailureIsUnknownRatherThanReportedAsRolledBack() throws SQLException {
        DataSource pool = mock(DataSource.class);
        Connection driver = mock(Connection.class);
        when(pool.getConnection()).thenReturn(driver);
        mutableDriver(driver);
        IllegalStateException failure = new IllegalStateException("driver failed after sending commit");
        doThrow(failure).when(driver).commit();
        Notifications notifications = new Notifications();

        try (LegalPrivateRequirementsDataSource source = new LegalPrivateRequirementsDataSource(pool, BUDGET)) {
            TransactionTemplate transaction = mutableTransaction(source);
            assertThatThrownBy(() -> source.withinDeadline(deadline -> transaction.execute(status -> {
                TransactionSynchronizationManager.registerSynchronization(notifications);
                return "must not escape";
            }))).isInstanceOf(TransactionSystemException.class).satisfies(exception -> {
                assertThat(exception.getCause()).isInstanceOf(SQLException.class);
                assertThat(exception.getCause().getCause()).isSameAs(failure);
            });

            assertThat(notifications.afterCommits).isZero();
            assertThat(notifications.completions).containsExactly(TransactionSynchronization.STATUS_UNKNOWN);
            assertTransactionReleased(source);
        }
        verify(driver).commit();
        verify(driver, never()).rollback();
        verify(driver).close();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void expiryDuringSuccessfulCommitRejectsTheResultAfterCommittedNotificationAndCleanup(boolean registration) throws SQLException {
        DataSource pool = mock(DataSource.class);
        Connection driver = mock(Connection.class);
        when(pool.getConnection()).thenReturn(driver);
        mutableDriver(driver);
        AtomicLong clock = new AtomicLong();
        doAnswer(invocation -> {
            clock.set(registration ? 30_000_000_000L : BUDGET.toNanos());
            return null;
        }).when(driver).commit();
        Notifications notifications = new Notifications();

        try (LegalPrivateRequirementsDataSource source = clockedSource(pool, clock, registration)) {
            TransactionTemplate transaction = mutableTransaction(source);
            assertThatThrownBy(() -> source.withinDeadline(deadline -> transaction.execute(status -> {
                TransactionSynchronizationManager.registerSynchronization(notifications);
                return "confirmed but expired";
            }))).isInstanceOf(LegalPrivateRequirementsReadException.class);

            assertThat(notifications.afterCommits).isEqualTo(1);
            assertThat(notifications.completions).containsExactly(TransactionSynchronization.STATUS_COMMITTED);
            assertTransactionReleased(source);
            verify(driver).close();
        }
        verify(driver).commit();
        verify(driver, never()).rollback();
        verify(driver, never()).abort(any());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void expiryDuringConnectionReturnDoesNotRewriteTheCommittedTransactionOutcome(boolean registration) throws SQLException {
        DataSource pool = mock(DataSource.class);
        Connection driver = mock(Connection.class);
        when(pool.getConnection()).thenReturn(driver);
        mutableDriver(driver);
        AtomicLong clock = new AtomicLong();
        doAnswer(invocation -> {
            clock.set(registration ? 30_000_000_000L : BUDGET.toNanos());
            return null;
        }).when(driver).close();
        Notifications notifications = new Notifications();

        try (LegalPrivateRequirementsDataSource source = clockedSource(pool, clock, registration)) {
            TransactionTemplate transaction = mutableTransaction(source);
            assertThatThrownBy(() -> source.withinDeadline(deadline -> transaction.execute(status -> {
                TransactionSynchronizationManager.registerSynchronization(notifications);
                return "confirmed but cleanup exceeded the budget";
            }))).isInstanceOf(LegalPrivateRequirementsReadException.class);

            assertThat(notifications.afterCommits).isEqualTo(1);
            assertThat(notifications.completions).containsExactly(TransactionSynchronization.STATUS_COMMITTED);
            assertTransactionReleased(source);
        }
        verify(driver).commit();
        verify(driver, never()).rollback();
        verify(driver).close();
        verify(driver, never()).abort(any());
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

        try (LegalPrivateRequirementsDataSource source = new LegalPrivateRequirementsDataSource(pool, BUDGET)) {
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
        try (LegalPrivateRequirementsDataSource source = new LegalPrivateRequirementsDataSource(pool, BUDGET)) {
            assertThatThrownBy(() -> source.withinDeadline(deadline -> {
                Thread.currentThread().interrupt();
                return "must not escape";
            })).isInstanceOf(LegalPrivateRequirementsReadException.class);
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

        try (LegalPrivateRequirementsDataSource source = new LegalPrivateRequirementsDataSource(pool, BUDGET)) {
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
            })).isInstanceOf(LegalPrivateRequirementsReadException.class);
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

    private static void mutableDriver(Connection driver) throws SQLException {
        when(driver.getAutoCommit()).thenReturn(true);
        when(driver.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
    }

    private static LegalPrivateRequirementsDataSource clockedSource(DataSource pool, AtomicLong clock, boolean registration) {
        return registration ? LegalPrivateRequirementsDataSource.registration(pool, clock::get)
                : new LegalPrivateRequirementsDataSource(pool, BUDGET, clock::get);
    }

    private static TransactionTemplate mutableTransaction(LegalPrivateRequirementsDataSource source) {
        DataSourceTransactionManager manager = new DataSourceTransactionManager(source);
        manager.setRollbackOnCommitFailure(false);
        TransactionTemplate template = new TransactionTemplate(manager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setReadOnly(false);
        template.setTimeout(source.isRegistrationBoundary() ? 25 : 15);
        return template;
    }

    private static void assertTransactionReleased(LegalPrivateRequirementsDataSource source) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
        assertThat(TransactionSynchronizationManager.hasResource(source)).isFalse();
        assertThatThrownBy(source::getConnection).isInstanceOf(SQLException.class);
    }

    private static final class Notifications implements TransactionSynchronization {
        private final List<Integer> completions = new ArrayList<>();
        private int afterCommits;

        @Override public void afterCommit() {
            afterCommits++;
        }

        @Override public void afterCompletion(int status) {
            completions.add(status);
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void cleanupFailureAbsorbedBySpringRejectsDeliveryWhilePreservingCommittedOutcome(boolean unchecked)
            throws SQLException {
        DataSource pool = mock(DataSource.class);
        Connection driver = mock(Connection.class);
        when(pool.getConnection()).thenReturn(driver);
        mutableDriver(driver);
        Throwable failure = unchecked ? new IllegalStateException("private-close-sensitive")
                : new SQLException("private-close-sensitive", "08006");
        doThrow(failure).when(driver).close();
        Notifications notifications = new Notifications();

        try (LegalPrivateRequirementsDataSource source = new LegalPrivateRequirementsDataSource(pool, BUDGET)) {
            TransactionTemplate transaction = mutableTransaction(source);
            assertThatThrownBy(() -> source.withinDeadline(deadline -> transaction.execute(status -> {
                TransactionSynchronizationManager.registerSynchronization(notifications);
                return "must not escape after a failed release";
            }))).isInstanceOf(LegalPrivateRequirementsReadException.class)
                    .hasCause(failure).hasMessageNotContaining("private-close-sensitive");
            assertThat(notifications.afterCommits).isEqualTo(1);
            assertThat(notifications.completions).containsExactly(TransactionSynchronization.STATUS_COMMITTED);
            assertTransactionReleased(source);
        }
        verify(driver).commit();
        verify(driver, never()).rollback();
        verify(driver).close();
    }

}
