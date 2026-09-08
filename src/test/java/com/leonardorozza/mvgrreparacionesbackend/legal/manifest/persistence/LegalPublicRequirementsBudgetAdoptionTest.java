package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalApplicableScopeResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Real Spring callbacks over a simulated driver; durable PostgreSQL outcomes have a separate IT. */
class LegalPublicRequirementsBudgetAdoptionTest {
    private static final Duration PUBLIC_CAP = Duration.ofSeconds(15);

    @Test
    void nestedCallsShareTheOriginalPublicCapAndOwnerWhileANewScopeOnlyRestartsItsLocalCap() {
        DataSource pool = mock(DataSource.class);
        AtomicLong ownerClock = new AtomicLong();
        AtomicLong localClock = new AtomicLong(100_000_000_000L);
        var owner = LegalRegistrationBudget.start(ownerClock::get);
        ownerClock.set(12_000_000_000L);
        try (var source = new LegalPublicRequirementsDataSource(pool, PUBLIC_CAP, localClock::get)) {
            source.withinRegistrationBudget(owner, outer -> {
                assertThat(outer.remainingMillis()).isEqualTo(15_000);
                assertThat(outer.usesRegistrationBudget(owner)).isTrue();
                localClock.addAndGet(2_000_000_000L);
                ownerClock.addAndGet(2_000_000_000L);
                source.withinRegistrationBudget(owner, inner -> {
                    assertThat(inner).isSameAs(outer);
                    assertThat(inner.remainingMillis()).isEqualTo(13_000);
                    assertThat(source.<LegalPublicRequirementsDeadline>withinDeadline(deadline -> deadline)).isSameAs(outer);
                    return null;
                });
                var primary = new IllegalStateException("synthetic nested rejection");
                assertThatThrownBy(() -> source.withinRegistrationBudget(owner, inner -> {
                    localClock.addAndGet(3_000_000_000L);
                    ownerClock.addAndGet(3_000_000_000L);
                    throw primary;
                })).isSameAs(primary);
                assertThat(source.<LegalPublicRequirementsDeadline>withinDeadline(deadline -> deadline)).isSameAs(outer);
                assertThat(outer.remainingMillis()).isEqualTo(10_000);
                return null;
            });
            assertOutsideScope(source);
            assertThat(source.withinRegistrationBudget(owner, LegalPublicRequirementsDeadline::remainingMillis)).isEqualTo(13_000);
            assertThat(source.withinDeadline(LegalPublicRequirementsDeadline::remainingMillis)).isEqualTo(15_000);
            assertThat(owner.remainingMillis()).isEqualTo(13_000);
        }
        verifyNoInteractions(pool);
    }

    @Test
    void nullForeignAndLateIntroducedOwnersNeverReplaceTheActiveScopeOrReachThePool() {
        DataSource pool = mock(DataSource.class);
        var owner = LegalRegistrationBudget.start(() -> 0L);
        var foreign = LegalRegistrationBudget.start(() -> 0L);
        AtomicInteger callbacks = new AtomicInteger();
        try (var source = new LegalPublicRequirementsDataSource(pool, PUBLIC_CAP, () -> 0L)) {
            assertThatThrownBy(() -> source.withinRegistrationBudget(null, deadline -> callbacks.incrementAndGet()))
                    .isExactlyInstanceOf(LegalPublicRequirementsReadException.class).hasNoCause();
            source.withinRegistrationBudget(owner, outer -> {
                assertThatThrownBy(() -> source.withinRegistrationBudget(foreign, deadline -> callbacks.incrementAndGet()))
                        .isExactlyInstanceOf(LegalPublicRequirementsReadException.class);
                assertThatThrownBy(() -> source.withinRegistrationBudget(null, deadline -> callbacks.incrementAndGet()))
                        .isExactlyInstanceOf(LegalPublicRequirementsReadException.class);
                assertThat(source.<LegalPublicRequirementsDeadline>withinDeadline(deadline -> deadline)).isSameAs(outer);
                assertThat(source.<LegalPublicRequirementsDeadline>withinRegistrationBudget(owner, deadline -> deadline)).isSameAs(outer);
                return null;
            });
            source.withinDeadline(historical -> {
                assertThat(historical.hasRegistrationBudget()).isFalse();
                assertThatThrownBy(() -> source.withinRegistrationBudget(owner, deadline -> callbacks.incrementAndGet()))
                        .isExactlyInstanceOf(LegalPublicRequirementsReadException.class);
                assertThat(source.<LegalPublicRequirementsDeadline>withinDeadline(deadline -> deadline)).isSameAs(historical);
                return null;
            });
            assertThat(callbacks).hasValue(0);
            assertOutsideScope(source);
        }
        verifyNoInteractions(pool);
    }

    @Test
    void anExpiredInitialOwnerDoesNotLeaveAnAmbientScopeOrBlockTheNextIndependentOperation() {
        DataSource pool = mock(DataSource.class);
        AtomicLong clock = new AtomicLong();
        var owner = LegalRegistrationBudget.start(clock::get);
        clock.set(30_000_000_000L);
        AtomicInteger callbacks = new AtomicInteger();
        try (var source = new LegalPublicRequirementsDataSource(pool, PUBLIC_CAP, () -> 0L)) {
            assertThatThrownBy(() -> source.withinRegistrationBudget(owner, deadline -> callbacks.incrementAndGet()))
                    .isExactlyInstanceOf(LegalPublicRequirementsReadException.class).hasNoCause();
            assertThat(callbacks).hasValue(0);
            assertOutsideScope(source);
            var next = LegalRegistrationBudget.start(() -> 0L);
            assertThat(source.withinRegistrationBudget(next, LegalPublicRequirementsDeadline::remainingMillis)).isEqualTo(15_000);
            assertThat(source.withinDeadline(LegalPublicRequirementsDeadline::remainingMillis)).isEqualTo(15_000);
        }
        verifyNoInteractions(pool);
    }

    @Test
    void eachThreadMustAdoptExplicitlyAndItsLocalScopeIsRemovedIndependently() throws Exception {
        DataSource pool = mock(DataSource.class);
        var owner = LegalRegistrationBudget.start(() -> 0L);
        try (var source = new LegalPublicRequirementsDataSource(pool, PUBLIC_CAP, () -> 0L);
             var worker = Executors.newSingleThreadExecutor()) {
            source.withinRegistrationBudget(owner, outer -> {
                try {
                    var other = worker.submit(() -> {
                        assertOutsideScope(source);
                        var result = source.<LegalPublicRequirementsDeadline>withinRegistrationBudget(owner, deadline -> deadline);
                        assertOutsideScope(source);
                        return result;
                    }).get(5, TimeUnit.SECONDS);
                    assertThat(other).isNotSameAs(outer);
                    assertThat(other.usesRegistrationBudget(owner)).isTrue();
                    assertThat(source.<LegalPublicRequirementsDeadline>withinDeadline(deadline -> deadline)).isSameAs(outer);
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
                return null;
            });
            assertOutsideScope(source);
        }
        verifyNoInteractions(pool);
    }

    @Test
    void publicThenPrivatePhasesRetainTheOriginalRemainingOwnerWithoutCarryingThePublicCap() {
        DataSource publicPool = mock(DataSource.class);
        DataSource privatePool = mock(DataSource.class);
        AtomicLong ownerClock = new AtomicLong();
        AtomicLong publicClock = new AtomicLong(40_000_000_000L);
        var owner = LegalRegistrationBudget.start(ownerClock::get);
        ownerClock.set(10_000_000_000L);
        try (var publicSource = new LegalPublicRequirementsDataSource(publicPool, PUBLIC_CAP, publicClock::get);
             var privateSource = LegalPrivateRequirementsDataSource.registration(privatePool, () -> {
                 throw new AssertionError("adoption cannot open another private clock");
             })) {
            publicSource.withinRegistrationBudget(owner, deadline -> {
                assertThat(deadline.remainingMillis()).isEqualTo(15_000);
                publicClock.addAndGet(7_000_000_000L);
                ownerClock.addAndGet(7_000_000_000L);
                assertThat(deadline.remainingMillis()).isEqualTo(8_000);
                return null;
            });
            assertThat(privateSource.withinRegistrationBudget(owner, LegalPrivateRequirementsDeadline::remainingMillis))
                    .isEqualTo(13_000);
            assertOutsideScope(publicSource);
            assertThatThrownBy(privateSource::getConnection).isExactlyInstanceOf(SQLException.class);
        }
        verifyNoInteractions(publicPool, privatePool);
    }

    @Test
    void publicCleanupInvalidatesTheSameOwnerAndLaterPrivatePhasesWithItsOriginalCause() {
        DataSource publicPool = mock(DataSource.class);
        DataSource privatePool = mock(DataSource.class);
        var owner = LegalRegistrationBudget.start(() -> 0L);
        var first = new SQLException("synthetic public cleanup", "08006");
        var next = new SQLException("synthetic later cleanup", "08006");
        try (var publicSource = new LegalPublicRequirementsDataSource(publicPool, PUBLIC_CAP, () -> 0L);
             var privateSource = LegalPrivateRequirementsDataSource.registration(privatePool)) {
            assertThatThrownBy(() -> publicSource.withinRegistrationBudget(owner, deadline -> {
                deadline.recordCleanupFailure(first);
                deadline.recordCleanupFailure(next);
                return "cannot deliver";
            })).isExactlyInstanceOf(LegalPublicRequirementsReadException.class).hasCause(first);
            assertThat(first.getSuppressed()).containsExactly(next);
            assertThatThrownBy(() -> privateSource.withinRegistrationBudget(owner, deadline -> "cannot continue"))
                    .isExactlyInstanceOf(LegalPrivateRequirementsReadException.class).hasCause(first);
            assertThatThrownBy(owner::check).isExactlyInstanceOf(LegalRegistrationBudget.UnavailableException.class).hasCause(first);
            assertOutsideScope(publicSource);
            assertThatThrownBy(privateSource::getConnection).isExactlyInstanceOf(SQLException.class);
        }
        verifyNoInteractions(publicPool, privatePool);
    }

    @ParameterizedTest @ValueSource(strings = {"owner", "local"})
    void insufficientRemainingBorrowBudgetNeverReachesThePool(String limitingBudget) {
        DataSource pool = mock(DataSource.class);
        AtomicLong ownerClock = new AtomicLong();
        AtomicLong localClock = new AtomicLong();
        var owner = LegalRegistrationBudget.start(ownerClock::get);
        if (limitingBudget.equals("owner")) ownerClock.set(29_500_000_000L);
        try (var source = new LegalPublicRequirementsDataSource(pool, PUBLIC_CAP, localClock::get)) {
            source.withinRegistrationBudget(owner, deadline -> {
                if (limitingBudget.equals("local")) localClock.set(14_500_000_000L);
                assertThat(deadline.remainingMillis()).isEqualTo(500);
                assertThatThrownBy(source::getConnection).isExactlyInstanceOf(LegalPublicRequirementsReadException.class);
                return null;
            });
            assertOutsideScope(source);
        }
        verifyNoInteractions(pool);
    }

    @ParameterizedTest @ValueSource(strings = {"owner", "local"})
    void sqlFetchAndCommitUseTheSmallerRemainingBudgetInARealSpringTransaction(String limitingBudget) throws Exception {
        try (var f = new SpringFixture(limitingBudget.equals("local") ? Duration.ofSeconds(2) : PUBLIC_CAP)) {
            Statement query = mock(Statement.class);
            Statement settings = mock(Statement.class);
            ResultSet rows = mock(ResultSet.class);
            when(f.driver.createStatement()).thenReturn(query, settings);
            when(query.executeQuery("SELECT public_budget_fixture")).thenReturn(rows);
            f.ownerClock.set(limitingBudget.equals("local") ? 20_000_000_000L : 28_000_000_000L);
            String result = f.source.withinRegistrationBudget(f.owner, deadline -> f.transaction.execute(status -> {
                TransactionSynchronizationManager.registerSynchronization(f.notifications);
                assertThat(f.source.<LegalPublicRequirementsDeadline>withinDeadline(inner -> inner)).isSameAs(deadline);
                Connection connection = DataSourceUtils.getConnection(f.source);
                try (Statement statement = connection.createStatement(); ResultSet data = statement.executeQuery("SELECT public_budget_fixture")) {
                    verify(query).setQueryTimeout(2);
                    verify(settings).execute("SET LOCAL statement_timeout TO '2000ms'");
                    f.advance(600_000_000L);
                    assertThat(data.next()).isFalse();
                    verify(f.driver, atLeastOnce()).setNetworkTimeout(any(), eq(1_400));
                } catch (SQLException failure) {
                    throw new AssertionError(failure);
                }
                f.advance(400_000_000L);
                return "committed before both deadlines";
            }));
            assertThat(result).isEqualTo("committed before both deadlines");
            assertThat(f.owner.remainingMillis()).isEqualTo(limitingBudget.equals("local") ? 9_000 : 1_000);
            verify(f.driver, atLeastOnce()).setNetworkTimeout(any(), eq(1_000));
            verify(f.driver).commit();
            verify(f.driver, never()).rollback();
            verify(f.driver).close();
            verify(rows).close();
            verify(query).close();
            assertThat(f.notifications.completions).containsExactly(TransactionSynchronization.STATUS_COMMITTED);
            f.assertReleased();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"owner", "local"})
    void callbackExpiryRollsBackWithoutRestartingNestedScopesOrInventingOwnerExpiry(String limitingBudget) throws Exception {
        try (var f = new SpringFixture(PUBLIC_CAP)) {
            assertThatThrownBy(() -> f.source.withinRegistrationBudget(f.owner, deadline -> f.transaction.execute(status -> {
                TransactionSynchronizationManager.registerSynchronization(f.notifications);
                f.expire(limitingBudget);
                return f.source.withinDeadline(inner -> "expired");
            }))).isExactlyInstanceOf(LegalPublicRequirementsReadException.class).hasNoCause();
            verify(f.driver).rollback();
            verify(f.driver, never()).commit();
            verify(f.driver).close();
            assertThat(f.notifications.completions).containsExactly(TransactionSynchronization.STATUS_ROLLED_BACK);
            if (limitingBudget.equals("owner")) {
                assertThatThrownBy(f.owner::check).isExactlyInstanceOf(LegalRegistrationBudget.UnavailableException.class);
            } else {
                assertThat(f.owner.remainingMillis()).isEqualTo(30_000);
            }
            f.assertReleased();
        }
    }

    @ParameterizedTest @CsvSource({"owner,commit", "owner,close", "local,commit", "local,close"})
    void expiryAfterSuccessfulDelegateCommitRejectsDeliveryButPreservesSpringsCommittedNotification(
            String limitingBudget, String latePhase) throws Exception {
        try (var f = new SpringFixture(PUBLIC_CAP)) {
            if (latePhase.equals("commit")) doAnswer(call -> { f.expire(limitingBudget); return null; }).when(f.driver).commit();
            else doAnswer(call -> { f.expire(limitingBudget); return null; }).when(f.driver).close();
            assertThatThrownBy(() -> f.source.withinRegistrationBudget(f.owner, deadline -> f.transaction.execute(status -> {
                TransactionSynchronizationManager.registerSynchronization(f.notifications);
                return "late public observation";
            }))).isExactlyInstanceOf(LegalPublicRequirementsReadException.class).hasNoCause();
            assertThat(f.notifications.afterCommits).isEqualTo(1);
            assertThat(f.notifications.completions).containsExactly(TransactionSynchronization.STATUS_COMMITTED);
            verify(f.driver).commit();
            verify(f.driver, never()).rollback();
            verify(f.driver).close();
            if (limitingBudget.equals("local")) assertThat(f.owner.remainingMillis()).isEqualTo(30_000);
            f.assertReleased();
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void cleanupAbsorbedBySpringInvalidatesTheOwnerAfterItsCommittedNotification(boolean unchecked) throws Exception {
        try (var f = new SpringFixture(PUBLIC_CAP)) {
            Throwable cleanup = unchecked ? new IllegalStateException("synthetic public release")
                    : new SQLException("synthetic public close", "08006");
            doThrow(cleanup).when(f.driver).close();
            assertThatThrownBy(() -> f.source.withinRegistrationBudget(f.owner, deadline -> f.transaction.execute(status -> {
                TransactionSynchronizationManager.registerSynchronization(f.notifications);
                return "cannot deliver after release failure";
            }))).isExactlyInstanceOf(LegalPublicRequirementsReadException.class).hasCause(cleanup);
            assertThatThrownBy(f.owner::check).isExactlyInstanceOf(LegalRegistrationBudget.UnavailableException.class).hasCause(cleanup);
            assertThat(f.notifications.afterCommits).isEqualTo(1);
            assertThat(f.notifications.completions).containsExactly(TransactionSynchronization.STATUS_COMMITTED);
            verify(f.driver).commit();
            verify(f.driver, never()).rollback();
            verify(f.driver).close();
            f.assertReleased();
        }
    }

    @Test
    void uncertainCommitKeepsUnknownWithoutRecordingABusinessSqlFailureAsCleanup() throws Exception {
        try (var f = new SpringFixture(PUBLIC_CAP)) {
            var uncertain = new SQLException("synthetic missing commit acknowledgement", "08006");
            doThrow(uncertain).when(f.driver).commit();
            f.ownerClock.set(23_000_000_000L);
            assertThatThrownBy(() -> f.source.withinRegistrationBudget(f.owner, deadline -> f.transaction.execute(status -> {
                TransactionSynchronizationManager.registerSynchronization(f.notifications);
                return "tentative public observation";
            }))).isExactlyInstanceOf(TransactionSystemException.class).hasCause(uncertain);
            assertThat(f.notifications.afterCommits).isZero();
            assertThat(f.notifications.completions).containsExactly(TransactionSynchronization.STATUS_UNKNOWN);
            assertThat(f.owner.remainingMillis()).isEqualTo(7_000);
            verify(f.driver, never()).rollback();
            verify(f.driver).close();
            f.assertReleased();
        }
    }

    @ParameterizedTest @CsvSource({"true,false", "true,true", "false,false", "false,true"})
    void preLeaseSetupFailurePreservesItsPrimaryAndOnlyAdoptedCleanupInvalidatesTheOwner(
            boolean adopted, boolean uncheckedCleanup) throws Exception {
        DataSource pool = mock(DataSource.class);
        Connection driver = mock(Connection.class);
        var owner = LegalRegistrationBudget.start(() -> 0L);
        var primary = new SQLException("synthetic network setup failure", "08006");
        Throwable cleanup = uncheckedCleanup ? new IllegalStateException("synthetic pre-lease close")
                : new SQLException("synthetic pre-lease close", "08006");
        when(pool.getConnection()).thenReturn(driver);
        doThrow(primary).when(driver).setNetworkTimeout(any(), anyInt());
        doThrow(cleanup).when(driver).close();
        AtomicReference<Throwable> observed = new AtomicReference<>();
        try (var source = new LegalPublicRequirementsDataSource(pool, PUBLIC_CAP, () -> 0L)) {
            java.util.function.Function<LegalPublicRequirementsDeadline, String> operation = deadline -> {
                observed.set(catchThrowable(source::getConnection));
                return "setup failure was caught by the caller";
            };
            if (adopted) {
                assertThatThrownBy(() -> source.withinRegistrationBudget(owner, operation))
                        .isExactlyInstanceOf(LegalPublicRequirementsReadException.class).hasCause(cleanup);
                assertThatThrownBy(owner::check).isExactlyInstanceOf(LegalRegistrationBudget.UnavailableException.class).hasCause(cleanup);
            } else {
                assertThat(source.withinDeadline(operation)).isEqualTo("setup failure was caught by the caller");
                assertThat(owner.remainingMillis()).isEqualTo(30_000);
            }
            assertThat(observed.get()).isSameAs(primary);
            assertThat(primary.getSuppressed()).containsExactly(cleanup);
            assertThat(cleanup.getSuppressed()).isEmpty();
            assertOutsideScope(source);
        }
        verify(driver, times(1)).close();
        verify(driver, never()).abort(any());
    }

    @Test
    void aSuccessfulPreLeaseCloseDoesNotTurnTheSetupFailureIntoOwnerCleanup() throws Exception {
        DataSource pool = mock(DataSource.class);
        Connection driver = mock(Connection.class);
        var owner = LegalRegistrationBudget.start(() -> 0L);
        var primary = new SQLException("synthetic setup rejection", "08006");
        when(pool.getConnection()).thenReturn(driver);
        doThrow(primary).when(driver).setNetworkTimeout(any(), anyInt());
        try (var source = new LegalPublicRequirementsDataSource(pool, PUBLIC_CAP, () -> 0L)) {
            source.withinRegistrationBudget(owner, deadline -> {
                assertThat(catchThrowable(source::getConnection)).isSameAs(primary);
                assertThat(primary.getSuppressed()).isEmpty();
                return null;
            });
            assertThat(owner.remainingMillis()).isEqualTo(30_000);
            assertOutsideScope(source);
        }
        verify(driver, times(1)).close();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void aConstructedLeaseOwnsExactlyOneCloseWhenItsProxyCannotBeDelivered(boolean failedClose) throws Exception {
        DataSource pool = mock(DataSource.class);
        Connection driver = mock(Connection.class);
        var owner = LegalRegistrationBudget.start(() -> 0L);
        var primary = new IllegalStateException("synthetic connection proxy failure");
        var cleanup = new SQLException("synthetic owned lease close", "08006");
        when(pool.getConnection()).thenReturn(driver);
        if (failedClose) doThrow(cleanup).when(driver).close();
        AtomicReference<Throwable> observed = new AtomicReference<>();
        try (var source = new LegalPublicRequirementsDataSource(pool, PUBLIC_CAP, () -> 0L)) {
            try (MockedStatic<Proxy> proxies = mockStatic(Proxy.class)) {
                proxies.when(() -> Proxy.newProxyInstance(any(ClassLoader.class), any(Class[].class), any(InvocationHandler.class)))
                        .thenThrow(primary);
                java.util.function.Function<LegalPublicRequirementsDeadline, String> operation = deadline -> {
                    observed.set(catchThrowable(source::getConnection));
                    return "proxy failure caught";
                };
                if (failedClose) {
                    assertThatThrownBy(() -> source.withinRegistrationBudget(owner, operation))
                            .isExactlyInstanceOf(LegalPublicRequirementsReadException.class).hasCause(cleanup);
                } else {
                    assertThat(source.withinRegistrationBudget(owner, operation)).isEqualTo("proxy failure caught");
                    assertThat(owner.remainingMillis()).isEqualTo(30_000);
                }
            }
            assertThat(observed.get()).isSameAs(primary);
            if (failedClose) assertThat(primary.getSuppressed()).containsExactly(cleanup);
            else assertThat(primary.getSuppressed()).isEmpty();
            assertThat(cleanup.getSuppressed()).isEmpty();
            assertOutsideScope(source);
        }
        verify(driver, times(1)).close();
        verify(driver, never()).abort(any());
    }

    @ParameterizedTest @ValueSource(strings = {"null", "expired", "cleanup"})
    void serviceRejectsAnUnavailableSuppliedOwnerBeforeResolvingOrOpeningTheGate(String defect) {
        try (var f = new ServiceFixture()) {
            AtomicLong clock = new AtomicLong();
            var owner = LegalRegistrationBudget.start(clock::get);
            if (defect.equals("expired")) clock.set(30_000_000_000L);
            var cleanup = new SQLException("synthetic prior phase close", "08006");
            if (defect.equals("cleanup")) owner.recordCleanupFailure(cleanup);
            var supplied = defect.equals("null") ? null : owner;
            var failure = catchThrowable(() -> f.service.readRegistration(supplied));
            assertThat(failure).isExactlyInstanceOf(LegalPublicRequirementsReadException.class)
                    .hasMessage("El contrato de requisitos no está disponible");
            if (defect.equals("cleanup")) assertThat(failure).hasCause(cleanup);
            else assertThat(failure).hasNoCause();
            verifyNoInteractions(f.pool, f.resolver, f.store, f.reader);
            verify(f.gate, never()).executeMutableShared(any());
            assertOutsideScope(f.source);
        }
    }

    @Test
    void availableOwnerAndHistoricalServiceApiRetainTheSameSafeResolutionFailure() {
        try (var f = new ServiceFixture()) {
            var primary = new IllegalStateException("synthetic resolution failure");
            when(f.resolver.resolve(any(), any(), any())).thenThrow(primary);
            var owner = LegalRegistrationBudget.start(() -> 0L);
            assertThatThrownBy(() -> f.service.readRegistration(owner))
                    .isExactlyInstanceOf(LegalPublicRequirementsReadException.class).hasCause(primary);
            assertThatThrownBy(f.service::readRegistration)
                    .isExactlyInstanceOf(LegalPublicRequirementsReadException.class).hasCause(primary);
            assertThat(owner.remainingMillis()).isEqualTo(30_000);
            verifyNoInteractions(f.pool, f.store, f.reader);
            verify(f.gate, never()).executeMutableShared(any());
            assertOutsideScope(f.source);
        }
    }

    private static void assertOutsideScope(LegalPublicRequirementsDataSource source) {
        assertThatThrownBy(source::getConnection).isExactlyInstanceOf(SQLException.class);
    }

    private static final class Notifications implements TransactionSynchronization {
        int afterCommits;
        final List<Integer> completions = new ArrayList<>();
        @Override public void afterCommit() { afterCommits++; }
        @Override public void afterCompletion(int status) { completions.add(status); }
    }

    private static final class SpringFixture implements AutoCloseable {
        private static final long LOCAL_START = 70_000_000_000L;
        final DataSource pool = mock(DataSource.class);
        final Connection driver = mock(Connection.class);
        final AtomicLong ownerClock = new AtomicLong();
        final AtomicLong localClock = new AtomicLong(LOCAL_START);
        final LegalRegistrationBudget owner = LegalRegistrationBudget.start(ownerClock::get);
        final LegalPublicRequirementsDataSource source;
        final Duration localCap;
        final TransactionTemplate transaction;
        final Notifications notifications = new Notifications();

        SpringFixture(Duration localCap) throws SQLException {
            this.localCap = localCap;
            source = new LegalPublicRequirementsDataSource(pool, localCap, localClock::get);
            AtomicBoolean autoCommit = new AtomicBoolean(true);
            when(pool.getConnection()).thenReturn(driver);
            when(driver.getAutoCommit()).thenAnswer(call -> autoCommit.get());
            doAnswer(call -> { autoCommit.set(call.getArgument(0)); return null; }).when(driver).setAutoCommit(anyBoolean());
            when(driver.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
            var manager = new DataSourceTransactionManager(source);
            manager.setRollbackOnCommitFailure(false);
            transaction = new TransactionTemplate(manager);
            transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            transaction.setTimeout(15);
        }

        void advance(long nanos) { ownerClock.addAndGet(nanos); localClock.addAndGet(nanos); }
        void expire(String budget) {
            if (budget.equals("owner")) ownerClock.set(30_000_000_000L);
            else localClock.set(LOCAL_START + localCap.toNanos());
        }
        void assertReleased() {
            assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
            assertOutsideScope(source);
        }
        @Override public void close() { source.close(); }
    }

    /** Only the pre-transaction path is mocked; the tests above exercise real Spring callbacks. */
    private static final class ServiceFixture implements AutoCloseable {
        final DataSource pool = mock(DataSource.class);
        final LegalPublicRequirementsDataSource source = new LegalPublicRequirementsDataSource(pool, PUBLIC_CAP, () -> 0L);
        final LegalManifestDatabaseGate gate = mock(LegalManifestDatabaseGate.class);
        final LegalApplicableScopeResolver resolver = mock(LegalApplicableScopeResolver.class);
        final LegalRequiredSetAggregateStore store = mock(LegalRequiredSetAggregateStore.class);
        final LegalPublicRequirementsReader reader = mock(LegalPublicRequirementsReader.class);
        final LegalPublicRequirementsReadService service;

        ServiceFixture() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            when(jdbc.getDataSource()).thenReturn(source);
            when(store.usesJdbc(jdbc)).thenReturn(true);
            when(reader.usesJdbc(jdbc)).thenReturn(true);
            service = new LegalPublicRequirementsReadService(jdbc, source, gate, resolver, store, reader,
                    mock(LegalV28AggregateSchemaVerifier.class), mock(LegalPublicRequirementsPrivilegeVerifier.class));
            clearInvocations(gate, resolver, store, reader);
        }
        @Override public void close() { source.close(); }
    }
}
