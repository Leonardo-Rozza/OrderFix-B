package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ConnectionBuilder;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.ShardingKeyBuilder;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/** Simulated JDBC only; real Hibernate cleanup and JPA/PostgreSQL have separate nominal suites. */
class LegalRegistrationSessionDataSourceTest {
    @Test
    void requiresDistinctNonNullDelegatesWithoutAccessingThem() {
        DataSource historical = mock(DataSource.class);
        DataSource dedicated = mock(DataSource.class);
        assertThatThrownBy(() -> new LegalRegistrationSessionDataSource(null, dedicated)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LegalRegistrationSessionDataSource(historical, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LegalRegistrationSessionDataSource(historical, historical)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(historical, dedicated);
    }

    @Test
    void outsideScopeEveryDataSourceOperationDelegatesLiterallyEvenAfterRouterShutdown() throws Exception {
        try (var f = new Fixture()) {
            Connection credentialsConnection = mock(Connection.class);
            DataSource unwrapped = mock(DataSource.class);
            ConnectionBuilder connectionBuilder = mock(ConnectionBuilder.class);
            ShardingKeyBuilder shardingBuilder = mock(ShardingKeyBuilder.class);
            PrintWriter writer = new PrintWriter(new StringWriter());
            Logger logger = Logger.getAnonymousLogger();
            when(f.historical.getConnection("supplied-user", "supplied-password")).thenReturn(credentialsConnection);
            when(f.historical.getLogWriter()).thenReturn(writer);
            when(f.historical.getLoginTimeout()).thenReturn(9);
            when(f.historical.getParentLogger()).thenReturn(logger);
            when(f.historical.unwrap(DataSource.class)).thenReturn(unwrapped);
            when(f.historical.isWrapperFor(DataSource.class)).thenReturn(true);
            when(f.historical.createConnectionBuilder()).thenReturn(connectionBuilder);
            when(f.historical.createShardingKeyBuilder()).thenReturn(shardingBuilder);

            assertThat(f.source.getConnection()).isSameAs(f.historicalConnection);
            assertThat(f.source.getConnection("supplied-user", "supplied-password")).isSameAs(credentialsConnection);
            assertThat(f.source.getLogWriter()).isSameAs(writer);
            assertThat(f.source.getLoginTimeout()).isEqualTo(9);
            assertThat(f.source.getParentLogger()).isSameAs(logger);
            assertThat(f.source.unwrap(DataSource.class)).isSameAs(unwrapped);
            assertThat(f.source.isWrapperFor(DataSource.class)).isTrue();
            assertThat(f.source.createConnectionBuilder()).isSameAs(connectionBuilder);
            assertThat(f.source.createShardingKeyBuilder()).isSameAs(shardingBuilder);
            f.source.setLogWriter(writer);
            f.source.setLoginTimeout(7);
            verify(f.historical).setLogWriter(writer);
            verify(f.historical).setLoginTimeout(7);
            f.source.close();
            f.source.close();
            assertThat(f.source.getConnection()).isSameAs(f.historicalConnection);
            assertThatThrownBy(() -> f.source.withinRegistrationBudget(f.owner, owner -> "closed"))
                    .isExactlyInstanceOf(LegalRegistrationSessionUnavailableException.class);
            verifyNoInteractions(f.historicalConnection, credentialsConnection, f.driver, f.dedicated);
        }
    }

    @Test
    void historicalFailuresAreNotWrappedOrAttributedToARegistrationOwner() throws Exception {
        try (var f = new Fixture()) {
            SQLException failure = new SQLException("synthetic historical failure", "08006");
            when(f.historical.getConnection()).thenThrow(failure);
            assertThatThrownBy(f.source::getConnection).isSameAs(failure);
            assertThat(f.owner.remainingMillis()).isEqualTo(30_000);
            verifyNoInteractions(f.dedicated, f.driver);
        }
    }

    @Test
    void sameOwnerNestingRetainsElapsedTimeAndRejectsReplacementWithoutChangingTheScope() throws Exception {
        try (var f = new Fixture()) {
            var foreign = LegalRegistrationBudget.start(() -> 0L);
            AtomicInteger callbacks = new AtomicInteger();
            f.clock.set(12_000_000_000L);
            assertThatThrownBy(() -> f.source.withinRegistrationBudget(null, owner -> callbacks.incrementAndGet()))
                    .isExactlyInstanceOf(LegalRegistrationSessionUnavailableException.class);
            assertThatThrownBy(() -> f.source.withinRegistrationBudget(f.owner, null))
                    .isExactlyInstanceOf(LegalRegistrationSessionUnavailableException.class);
            f.source.withinRegistrationBudget(f.owner, outer -> {
                assertThat(outer).isSameAs(f.owner);
                assertThat(outer.remainingMillis()).isEqualTo(18_000);
                assertThatThrownBy(() -> f.source.withinRegistrationBudget(foreign, owner -> callbacks.incrementAndGet()))
                        .isExactlyInstanceOf(LegalRegistrationSessionUnavailableException.class);
                assertThat(f.source.<LegalRegistrationBudget>withinRegistrationBudget(f.owner, inner -> inner)).isSameAs(outer);
                f.clock.addAndGet(5_000_000_000L);
                assertThat(outer.remainingMillis()).isEqualTo(13_000);
                var primary = new IllegalArgumentException("synthetic inner rejection");
                assertThatThrownBy(() -> f.source.withinRegistrationBudget(f.owner, inner -> { throw primary; })).isSameAs(primary);
                assertThat(f.source.<LegalRegistrationBudget>withinRegistrationBudget(f.owner, inner -> inner)).isSameAs(outer);
                return null;
            });
            assertThat(callbacks).hasValue(0);
            assertThat(f.source.getConnection()).isSameAs(f.historicalConnection);
            verifyNoInteractions(f.dedicated, f.driver);
        }
    }

    @Test
    void scopeIsThreadLocalAndExplicitAdoptionElsewhereDoesNotReplaceTheCallingThread() throws Exception {
        try (var f = new Fixture(); var worker = Executors.newSingleThreadExecutor()) {
            f.source.withinRegistrationBudget(f.owner, outer -> {
                try {
                    worker.submit(() -> {
                        assertThat(f.source.getConnection()).isSameAs(f.historicalConnection);
                        assertThat(f.source.<LegalRegistrationBudget>withinRegistrationBudget(f.owner, inner -> inner)).isSameAs(outer);
                        assertThat(f.source.getConnection()).isSameAs(f.historicalConnection);
                        return null;
                    }).get(5, TimeUnit.SECONDS);
                    assertThat(f.source.<LegalRegistrationBudget>withinRegistrationBudget(f.owner, inner -> inner)).isSameAs(outer);
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
                return null;
            });
            assertThat(f.source.getConnection()).isSameAs(f.historicalConnection);
            verifyNoInteractions(f.dedicated, f.driver);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"expired", "cleanup", "interrupted"})
    void finalCheckDominatesAClientRuntimeFailureWithoutLosingItsPrimaryOrInterrupt(String defect) throws Exception {
        try (var f = new Fixture()) {
            var primary = new IllegalArgumentException("synthetic client rejection");
            var cleanup = new SQLException("synthetic sensitive cleanup", "08006");
            Throwable failure;
            try {
                failure = catchThrowable(() -> f.source.withinRegistrationBudget(f.owner, owner -> {
                    if (defect.equals("expired")) f.clock.set(30_000_000_000L);
                    else if (defect.equals("cleanup")) owner.recordCleanupFailure(cleanup);
                    else Thread.currentThread().interrupt();
                    throw primary;
                }));
                if (defect.equals("interrupted")) assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally {
                Thread.interrupted();
            }
            assertThat(failure).isExactlyInstanceOf(LegalRegistrationSessionUnavailableException.class)
                    .hasMessage("La sesión de registro no está disponible");
            assertThat(failure.getSuppressed()).containsExactly(primary);
            if (defect.equals("cleanup")) assertThat(failure).hasCause(cleanup);
            else assertThat(failure).hasNoCause();
            assertThat(failure.toString()).doesNotContain("sensitive", "client", "08006");
            assertThat(f.source.getConnection()).isSameAs(f.historicalConnection);
            verifyNoInteractions(f.dedicated, f.driver);
        }
    }

    @Test
    void errorIsPreservedWhileTheScopeIsRestoredEvenIfTheOwnerAlsoExpired() throws Exception {
        try (var f = new Fixture()) {
            AssertionError primary = new AssertionError("synthetic assertion");
            assertThatThrownBy(() -> f.source.withinRegistrationBudget(f.owner, owner -> {
                f.clock.set(30_000_000_000L);
                throw primary;
            })).isSameAs(primary);
            assertThat(primary.getSuppressed()).isEmpty();
            assertThat(f.source.getConnection()).isSameAs(f.historicalConnection);
            verifyNoInteractions(f.dedicated, f.driver);
        }
    }

    @Test
    void scopedAdministrativeMutationsBuildersAndAlternateCredentialsCannotTouchEitherDelegate() throws Exception {
        try (var f = new Fixture()) {
            PrintWriter writer = new PrintWriter(new StringWriter());
            when(f.historical.getLogWriter()).thenReturn(writer);
            when(f.historical.getLoginTimeout()).thenReturn(11);
            Logger logger = Logger.getAnonymousLogger();
            when(f.historical.getParentLogger()).thenReturn(logger);
            f.source.withinRegistrationBudget(f.owner, owner -> {
                assertThatThrownBy(() -> f.source.getConnection("u", "p")).isExactlyInstanceOf(SQLFeatureNotSupportedException.class);
                assertThatThrownBy(() -> f.source.unwrap(DataSource.class)).isExactlyInstanceOf(SQLFeatureNotSupportedException.class);
                assertThatThrownBy(f.source::createConnectionBuilder).isExactlyInstanceOf(SQLFeatureNotSupportedException.class);
                assertThatThrownBy(f.source::createShardingKeyBuilder).isExactlyInstanceOf(SQLFeatureNotSupportedException.class);
                assertThatThrownBy(() -> f.source.setLoginTimeout(99)).isExactlyInstanceOf(SQLFeatureNotSupportedException.class);
                assertThatThrownBy(() -> f.source.setLogWriter(writer)).isExactlyInstanceOf(SQLFeatureNotSupportedException.class);
                try {
                    assertThat(f.source.isWrapperFor(DataSource.class)).isFalse();
                    assertThat(f.source.getLogWriter()).isSameAs(writer);
                    assertThat(f.source.getLoginTimeout()).isEqualTo(11);
                    assertThat(f.source.getParentLogger()).isSameAs(logger);
                } catch (SQLException failure) { throw new AssertionError(failure); }
                return null;
            });
            verify(f.historical, never()).setLoginTimeout(anyInt());
            verify(f.historical, never()).setLogWriter(any());
            verify(f.historical, never()).getConnection(anyString(), anyString());
            verifyNoInteractions(f.dedicated, f.driver);
        }
    }

    @ParameterizedTest @ValueSource(longs = {29_500_000_000L, 30_000_000_000L})
    void insufficientOwnerBudgetNeverStartsADedicatedBorrow(long consumed) throws Exception {
        try (var f = new Fixture()) {
            f.clock.set(consumed);
            assertThatThrownBy(() -> f.source.withinRegistrationBudget(f.owner, owner -> {
                assertThatThrownBy(f.source::getConnection).isExactlyInstanceOf(LegalRegistrationSessionUnavailableException.class);
                throw new LegalRegistrationSessionUnavailableException();
            })).isExactlyInstanceOf(LegalRegistrationSessionUnavailableException.class);
            verifyNoInteractions(f.dedicated, f.driver);
            assertThat(f.source.getConnection()).isSameAs(f.historicalConnection);
        }
    }

    @Test
    void sqlFetchAndMetadataUseTheRemainingOwnerAndRetainSafeResourceIdentity() throws Exception {
        try (var f = new Fixture()) {
            PreparedStatement query = mock(PreparedStatement.class);
            Statement settings = mock(Statement.class);
            Statement metadataStatement = mock(Statement.class);
            ResultSet rows = mock(ResultSet.class);
            ResultSet catalog = mock(ResultSet.class);
            DatabaseMetaData metadata = mock(DatabaseMetaData.class);
            when(f.driver.getAutoCommit()).thenReturn(false);
            when(f.driver.prepareStatement("SELECT session_fixture")).thenReturn(query);
            when(f.driver.createStatement()).thenReturn(settings);
            when(query.executeQuery()).thenReturn(rows);
            when(query.getResultSet()).thenReturn(rows);
            when(f.driver.getMetaData()).thenReturn(metadata);
            when(metadata.getTables(null, null, "%", null)).thenReturn(catalog);
            when(catalog.getStatement()).thenReturn(metadataStatement);
            f.clock.set(28_000_000_000L);
            f.source.withinRegistrationBudget(f.owner, owner -> {
                try (Connection connection = f.source.getConnection(); PreparedStatement statement = connection.prepareStatement("SELECT session_fixture")) {
                    assertThat(connection).isNotSameAs(f.driver);
                    assertThat(statement.getConnection()).isSameAs(connection);
                    assertThatThrownBy(() -> connection.unwrap(Connection.class)).isExactlyInstanceOf(SQLFeatureNotSupportedException.class);
                    assertThatThrownBy(() -> statement.unwrap(PreparedStatement.class)).isExactlyInstanceOf(SQLFeatureNotSupportedException.class);
                    assertThat(connection.isWrapperFor(Connection.class)).isFalse();
                    try (ResultSet data = statement.executeQuery()) {
                        assertThat(statement.getResultSet()).isSameAs(data);
                        assertThat(data.getStatement()).isSameAs(statement);
                        assertThatThrownBy(() -> data.unwrap(ResultSet.class)).isExactlyInstanceOf(SQLFeatureNotSupportedException.class);
                        verify(query).setQueryTimeout(2);
                        verify(settings).execute("SET LOCAL statement_timeout TO '2000ms'");
                        f.clock.set(28_600_000_000L);
                        assertThat(data.next()).isFalse();
                        verify(f.driver, atLeastOnce()).setNetworkTimeout(any(), eq(1_400));
                    }
                    DatabaseMetaData exposed = connection.getMetaData();
                    assertThat(exposed.getConnection()).isSameAs(connection);
                    assertThatThrownBy(() -> exposed.unwrap(DatabaseMetaData.class)).isExactlyInstanceOf(SQLFeatureNotSupportedException.class);
                    try (ResultSet tables = exposed.getTables(null, null, "%", null)) {
                        assertThat(tables.getStatement()).isNotSameAs(metadataStatement);
                        assertThat(tables.getStatement().getConnection()).isSameAs(connection);
                    }
                    connection.setNetworkTimeout(Runnable::run, 99_000);
                    verify(f.driver, never()).setNetworkTimeout(any(), eq(99_000));
                } catch (SQLException failure) { throw new AssertionError(failure); }
                return null;
            });
            verify(rows, times(1)).close();
            verify(catalog, times(1)).close();
            verify(query, times(1)).close();
            verify(settings, times(1)).close();
            verify(f.driver, times(1)).close();
            verify(f.driver, never()).abort(any());
        }
    }

    @Test
    void statementCallableAndMetadataWithoutAStatementDoNotLoseTheirJdbcInterfaces() throws Exception {
        try (var f = new Fixture()) {
            CallableStatement call = mock(CallableStatement.class);
            DatabaseMetaData metadata = mock(DatabaseMetaData.class);
            ResultSet detached = mock(ResultSet.class);
            when(f.driver.prepareCall("CALL fixture()")).thenReturn(call);
            when(f.driver.getMetaData()).thenReturn(metadata);
            when(metadata.getTables(null, null, "%", null)).thenReturn(detached);
            f.source.withinRegistrationBudget(f.owner, owner -> {
                try (Connection connection = f.source.getConnection(); CallableStatement exposed = connection.prepareCall("CALL fixture()");
                     ResultSet catalog = connection.getMetaData().getTables(null, null, "%", null)) {
                    assertThat(exposed).isNotSameAs(call);
                    assertThat(exposed.getConnection()).isSameAs(connection);
                    assertThat(catalog.getStatement()).isNull();
                } catch (SQLException failure) { throw new AssertionError(failure); }
                return null;
            });
            verify(call).close();
            verify(detached).close();
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void commitFailuresKeepTheirCheckedOrUncheckedDelegateChainWithoutPoisoningTheOwner(boolean unchecked) throws Exception {
        try (var f = new Fixture()) {
            Throwable delegate = unchecked ? new IllegalStateException("synthetic commit driver failure")
                    : new SQLException("synthetic unacknowledged commit", "08006");
            doThrow(delegate).when(f.driver).commit();
            AtomicReference<Throwable> observed = new AtomicReference<>();
            f.source.withinRegistrationBudget(f.owner, owner -> {
                try (Connection connection = f.source.getConnection()) {
                    observed.set(catchThrowable(connection::commit));
                } catch (SQLException failure) { throw new AssertionError(failure); }
                return null;
            });
            if (unchecked) assertThat(observed.get()).isExactlyInstanceOf(SQLException.class).hasCause(delegate);
            else assertThat(observed.get()).isSameAs(delegate);
            assertThat(f.owner.remainingMillis()).isEqualTo(30_000);
            verify(f.driver).commit();
            verify(f.driver, never()).rollback();
        }
    }

    @Test
    void delegateCommitCanReturnNormallyBeforeTheOuterScopeRejectsItsExpiredDelivery() throws Exception {
        try (var f = new Fixture()) {
            AtomicInteger delegateReturned = new AtomicInteger();
            doAnswer(call -> { f.clock.set(30_000_000_000L); return null; }).when(f.driver).commit();
            assertThatThrownBy(() -> f.source.withinRegistrationBudget(f.owner, owner -> {
                try (Connection connection = f.source.getConnection()) {
                    connection.commit();
                    delegateReturned.incrementAndGet();
                } catch (SQLException failure) { throw new AssertionError(failure); }
                return "late";
            })).isExactlyInstanceOf(LegalRegistrationSessionUnavailableException.class).hasNoCause();
            assertThat(delegateReturned).hasValue(1);
            verify(f.driver).commit();
            verify(f.driver, never()).rollback();
            verify(f.driver).close();
        }
    }

    @Test
    void expiryBeforeCommitLeavesRollbackResetAndResourceCloseAvailable() throws Exception {
        try (var f = new Fixture()) {
            Statement statement = mock(Statement.class);
            ResultSet rows = mock(ResultSet.class);
            when(f.driver.createStatement()).thenReturn(statement);
            when(statement.executeQuery("SELECT expiry_fixture")).thenReturn(rows);
            assertThatThrownBy(() -> f.source.withinRegistrationBudget(f.owner, owner -> {
                try (Connection connection = f.source.getConnection(); Statement query = connection.createStatement();
                     ResultSet data = query.executeQuery("SELECT expiry_fixture")) {
                    f.clock.set(30_000_000_000L);
                    assertThatThrownBy(connection::commit).isExactlyInstanceOf(LegalRegistrationSessionUnavailableException.class);
                    connection.rollback();
                    connection.setAutoCommit(true);
                    connection.setReadOnly(false);
                    connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                    query.setMaxRows(0);
                    query.setQueryTimeout(0);
                } catch (SQLException failure) { throw new AssertionError(failure); }
                return null;
            })).isExactlyInstanceOf(LegalRegistrationSessionUnavailableException.class);
            verify(f.driver, never()).commit();
            verify(f.driver).rollback();
            verify(f.driver).setAutoCommit(true);
            verify(f.driver).setReadOnly(false);
            verify(f.driver).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            verify(rows).close();
            verify(statement).close();
            verify(f.driver).close();
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void partialAcquisitionCleanupKeepsPrimarySuppressedAndInvalidatesOnlyForARealCloseFailure(boolean failedClose) throws Exception {
        try (var f = new Fixture()) {
            var primary = new SQLException("synthetic setup failure", "08006");
            var cleanup = new SQLException("synthetic partial close", "08006");
            doThrow(primary).when(f.driver).setNetworkTimeout(any(), anyInt());
            if (failedClose) doThrow(cleanup).when(f.driver).close();
            AtomicReference<Throwable> observed = new AtomicReference<>();
            if (failedClose) {
                assertThatThrownBy(() -> f.source.withinRegistrationBudget(f.owner, owner -> {
                    observed.set(catchThrowable(f.source::getConnection));
                    return null;
                })).isExactlyInstanceOf(LegalRegistrationSessionUnavailableException.class).hasCause(cleanup);
                assertThat(primary.getSuppressed()).containsExactly(cleanup);
                assertThatThrownBy(f.owner::check).hasCause(cleanup);
            } else {
                f.source.withinRegistrationBudget(f.owner, owner -> {
                    observed.set(catchThrowable(f.source::getConnection));
                    return null;
                });
                assertThat(primary.getSuppressed()).isEmpty();
                assertThat(f.owner.remainingMillis()).isEqualTo(30_000);
            }
            assertThat(observed.get()).isSameAs(primary);
            verify(f.driver, times(1)).close();
            verify(f.driver, never()).abort(any());
        }
    }

    @Test
    void expirationDuringBorrowClosesThePartialAcquisitionBeforeAnyNetworkSetup() throws Exception {
        try (var f = new Fixture()) {
            when(f.dedicated.getConnection()).thenAnswer(call -> { f.clock.set(30_000_000_000L); return f.driver; });
            assertThatThrownBy(() -> f.source.withinRegistrationBudget(f.owner, owner -> {
                try { return f.source.getConnection(); }
                catch (SQLException failure) { throw new AssertionError(failure); }
            })).isExactlyInstanceOf(LegalRegistrationSessionUnavailableException.class).hasNoCause();
            verify(f.driver, never()).setNetworkTimeout(any(), anyInt());
            verify(f.driver, times(1)).close();
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void routerShutdownAbortsAndReturnsAnActiveLeaseOnceWithoutClosingItsPools(boolean failedClose) throws Exception {
        try (var f = new Fixture()) {
            Statement query = mock(Statement.class);
            ResultSet rows = mock(ResultSet.class);
            when(f.driver.createStatement()).thenReturn(query);
            when(query.executeQuery("SELECT active_fixture")).thenReturn(rows);
            var cleanup = new SQLException("synthetic shutdown close", "08006");
            if (failedClose) doThrow(cleanup).when(f.driver).close();
            AtomicReference<Connection> held = new AtomicReference<>();
            Throwable result = catchThrowable(() -> f.source.withinRegistrationBudget(f.owner, owner -> {
                try (Connection connection = f.source.getConnection(); Statement statement = connection.createStatement();
                     ResultSet data = statement.executeQuery("SELECT active_fixture")) {
                    held.set(connection);
                    f.source.close();
                    assertThat(connection.isClosed()).isTrue();
                } catch (SQLException failure) { throw new AssertionError(failure); }
                return null;
            }));
            assertThat(result).isExactlyInstanceOf(LegalRegistrationSessionUnavailableException.class);
            if (failedClose) assertThat(result).hasCause(cleanup);
            else assertThat(result).hasNoCause();
            held.get().close();
            f.source.close();
            verify(query, times(1)).cancel();
            verify(f.driver, times(1)).abort(any());
            verify(f.driver, times(1)).close();
            assertThat(f.source.getConnection()).isSameAs(f.historicalConnection);
        }
    }

    @Test
    void realWatchdogCancelsAndAbortsAnActiveLeaseWhileItsOwnerStillOwnsTheSingleClose() throws Exception {
        try (var f = new Fixture()) {
            Statement query = mock(Statement.class);
            ResultSet rows = mock(ResultSet.class);
            CountDownLatch aborted = new CountDownLatch(1);
            when(f.driver.createStatement()).thenReturn(query);
            when(query.executeQuery("SELECT watchdog_fixture")).thenReturn(rows);
            doAnswer(call -> { aborted.countDown(); return null; }).when(f.driver).abort(any());
            f.clock.set(29_000_000_000L);
            // The fixed owner clock isolates real watchdog cancellation and I/O rejection;
            // its healthy final scope check does not claim physical owner-clock expiry.
            f.source.withinRegistrationBudget(f.owner, owner -> {
                try (Connection connection = f.source.getConnection(); Statement statement = connection.createStatement();
                     ResultSet data = statement.executeQuery("SELECT watchdog_fixture")) {
                    assertThat(aborted.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThatThrownBy(data::next).isExactlyInstanceOf(LegalRegistrationSessionUnavailableException.class);
                } catch (SQLException | InterruptedException failure) { throw new AssertionError(failure); }
                return null;
            });
            verify(query, times(1)).cancel();
            verify(f.driver, times(1)).abort(any());
            verify(f.driver, times(1)).close();
        }
    }

    @Test
    void aReleasedLeaseCannotBeAbortedByItsOldWatchdogOrRouterShutdown() throws Exception {
        try (var f = new Fixture()) {
            CountDownLatch aborted = new CountDownLatch(1);
            doAnswer(call -> { aborted.countDown(); return null; }).when(f.driver).abort(any());
            f.clock.set(29_000_000_000L);
            f.source.withinRegistrationBudget(f.owner, owner -> {
                try (Connection connection = f.source.getConnection()) {
                    connection.close();
                } catch (SQLException failure) { throw new AssertionError(failure); }
                return null;
            });
            assertThat(aborted.await(1_250, TimeUnit.MILLISECONDS)).isFalse();
            f.source.close();
            verify(f.driver, times(1)).close();
            verify(f.driver, never()).abort(any());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final DataSource historical = mock(DataSource.class, withSettings().extraInterfaces(AutoCloseable.class));
        final DataSource dedicated = mock(DataSource.class, withSettings().extraInterfaces(AutoCloseable.class));
        final Connection historicalConnection = mock(Connection.class);
        final Connection driver = mock(Connection.class);
        final AtomicLong clock = new AtomicLong();
        final LegalRegistrationBudget owner = LegalRegistrationBudget.start(clock::get);
        final LegalRegistrationSessionDataSource source = new LegalRegistrationSessionDataSource(historical, dedicated);

        Fixture() throws SQLException {
            when(historical.getConnection()).thenReturn(historicalConnection);
            when(dedicated.getConnection()).thenReturn(driver);
            when(driver.getAutoCommit()).thenReturn(true);
        }

        @Override public void close() throws Exception {
            source.close();
            verify((AutoCloseable) historical, never()).close();
            verify((AutoCloseable) dedicated, never()).close();
        }
    }
}
