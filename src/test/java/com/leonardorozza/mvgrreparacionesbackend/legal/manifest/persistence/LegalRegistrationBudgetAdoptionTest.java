package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalApplicableScopeResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedConstruction;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Real Spring transaction/resource callbacks over a simulated driver; PostgreSQL has a separate IT. */
class LegalRegistrationBudgetAdoptionTest {
    @Test
    void adoptionPreservesElapsedTimeAndNestedDeadlineIdentityWithoutReadingTheLocalClock() {
        DataSource pool = mock(DataSource.class);
        AtomicLong clock = new AtomicLong();
        var owner = LegalRegistrationBudget.start(clock::get);
        clock.set(12_000_000_000L);
        try (var source = LegalPrivateRequirementsDataSource.registration(pool, () -> {
            throw new AssertionError("adoption must not start a local clock");
        })) {
            source.withinRegistrationBudget(owner, outer -> {
                assertThat(outer.remainingMillis()).isEqualTo(18_000);
                assertThat(outer.usesRegistrationBudget(owner)).isTrue();
                source.withinRegistrationBudget(owner, inner -> {
                    assertThat(inner).isSameAs(outer);
                    clock.addAndGet(5_000_000_000L);
                    assertThat(source.<LegalPrivateRequirementsDeadline>withinDeadline(nested -> nested)).isSameAs(outer);
                    return null;
                });
                assertThat(outer.remainingMillis()).isEqualTo(13_000);
                return null;
            });
            assertOutsideScope(source);
            clock.addAndGet(3_000_000_000L);
            assertThat(source.withinRegistrationBudget(owner, LegalPrivateRequirementsDeadline::remainingMillis))
                    .isEqualTo(10_000);
        }
        verifyNoInteractions(pool);
    }

    @Test
    void oneOwnerCanPassThroughIndependentBoundariesWithoutRestarting() {
        DataSource firstPool = mock(DataSource.class);
        DataSource secondPool = mock(DataSource.class);
        AtomicLong clock = new AtomicLong();
        var owner = LegalRegistrationBudget.start(clock::get);
        try (var first = LegalPrivateRequirementsDataSource.registration(firstPool);
             var second = LegalPrivateRequirementsDataSource.registration(secondPool)) {
            first.withinRegistrationBudget(owner, deadline -> {
                clock.set(14_000_000_000L);
                assertThat(deadline.remainingMillis()).isEqualTo(16_000);
                return null;
            });
            clock.set(21_000_000_000L);
            assertThat(second.withinRegistrationBudget(owner, LegalPrivateRequirementsDeadline::remainingMillis))
                    .isEqualTo(9_000);
            assertOutsideScope(first);
            assertOutsideScope(second);
        }
        verifyNoInteractions(firstPool, secondPool);
    }

    @Test
    void nullAndForeignOwnersAreRejectedWithoutReplacingAnActiveScope() {
        DataSource pool = mock(DataSource.class);
        var owner = LegalRegistrationBudget.start(() -> 0L);
        var foreign = LegalRegistrationBudget.start(() -> 0L);
        AtomicInteger callbacks = new AtomicInteger();
        try (var source = LegalPrivateRequirementsDataSource.registration(pool)) {
            assertThatThrownBy(() -> source.withinRegistrationBudget(null, deadline -> callbacks.incrementAndGet()))
                    .isExactlyInstanceOf(LegalPrivateRequirementsReadException.class);
            source.withinRegistrationBudget(owner, outer -> {
                assertThatThrownBy(() -> source.withinRegistrationBudget(foreign, deadline -> callbacks.incrementAndGet()))
                        .isExactlyInstanceOf(LegalPrivateRequirementsReadException.class);
                assertThatThrownBy(() -> source.withinRegistrationBudget(null, deadline -> callbacks.incrementAndGet()))
                        .isExactlyInstanceOf(LegalPrivateRequirementsReadException.class);
                assertThat(source.<LegalPrivateRequirementsDeadline>withinDeadline(deadline -> deadline)).isSameAs(outer);
                assertThat(source.<LegalPrivateRequirementsDeadline>withinRegistrationBudget(owner, deadline -> deadline)).isSameAs(outer);
                return null;
            });
            assertThat(callbacks).hasValue(0);
            assertOutsideScope(source);
        }
        verifyNoInteractions(pool);
    }

    @Test
    void aHistoricalPrivateBoundaryCannotAdoptARegistrationBudget() {
        DataSource pool = mock(DataSource.class);
        var owner = LegalRegistrationBudget.start(() -> 0L);
        try (var source = new LegalPrivateRequirementsDataSource(pool, Duration.ofSeconds(15), () -> 0L)) {
            assertThatThrownBy(() -> source.withinRegistrationBudget(owner, deadline -> "forbidden"))
                    .isExactlyInstanceOf(LegalPrivateRequirementsReadException.class);
            source.withinDeadline(outer -> {
                assertThatThrownBy(() -> source.withinRegistrationBudget(owner, deadline -> "forbidden"))
                        .isExactlyInstanceOf(LegalPrivateRequirementsReadException.class);
                assertThat(source.<LegalPrivateRequirementsDeadline>withinDeadline(deadline -> deadline)).isSameAs(outer);
                assertThat(outer.remainingMillis()).isEqualTo(15_000);
                return null;
            });
            assertOutsideScope(source);
        }
        verifyNoInteractions(pool);
    }

    @Test
    void exceptionsAndFailedInitialChecksRestoreThePreviousScopeAndThenRemoveIt() {
        DataSource pool = mock(DataSource.class);
        AtomicLong clock = new AtomicLong();
        var owner = LegalRegistrationBudget.start(clock::get);
        var original = new IllegalStateException("synthetic operation rejection");
        try (var source = LegalPrivateRequirementsDataSource.registration(pool, () -> 0L)) {
            source.withinRegistrationBudget(owner, outer -> {
                assertThatThrownBy(() -> source.withinRegistrationBudget(owner, inner -> { throw original; }))
                        .isSameAs(original);
                assertThat(source.<LegalPrivateRequirementsDeadline>withinDeadline(inner -> inner)).isSameAs(outer);
                return null;
            });
            assertOutsideScope(source);
            clock.set(30_000_000_000L);
            AtomicInteger callbacks = new AtomicInteger();
            assertThatThrownBy(() -> source.withinRegistrationBudget(owner, deadline -> callbacks.incrementAndGet()))
                    .isExactlyInstanceOf(LegalPrivateRequirementsReadException.class).hasNoCause();
            assertThat(callbacks).hasValue(0);
            assertOutsideScope(source);
            var nextOperation = LegalRegistrationBudget.start(() -> 0L);
            assertThat(source.withinRegistrationBudget(nextOperation, LegalPrivateRequirementsDeadline::remainingMillis))
                    .isEqualTo(30_000);
            assertThat(source.withinDeadline(LegalPrivateRequirementsDeadline::remainingMillis)).isEqualTo(30_000);
        }
        verifyNoInteractions(pool);
    }

    @Test
    void adoptionIsExplicitOnEachThreadAndCannotMakeAConnectionAmbientElsewhere() throws Exception {
        DataSource pool = mock(DataSource.class);
        var owner = LegalRegistrationBudget.start(() -> 0L);
        try (var source = LegalPrivateRequirementsDataSource.registration(pool);
             var worker = Executors.newSingleThreadExecutor()) {
            source.withinRegistrationBudget(owner, outer -> {
                try {
                    var other = worker.submit(() -> {
                        assertOutsideScope(source);
                        return source.<LegalPrivateRequirementsDeadline>withinRegistrationBudget(owner, deadline -> deadline);
                    }).get(5, TimeUnit.SECONDS);
                    assertThat(other).isNotSameAs(outer);
                    assertThat(other.usesRegistrationBudget(owner)).isTrue();
                    assertThat(source.<LegalPrivateRequirementsDeadline>withinDeadline(deadline -> deadline)).isSameAs(outer);
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
    void adapterCleanupPoisonsTheOriginalOwnerAndAnyLaterBoundaryWithTheOriginalCause() {
        DataSource pool = mock(DataSource.class);
        var owner = LegalRegistrationBudget.start(() -> 0L);
        SQLException first = new SQLException("synthetic first cleanup", "08006");
        SQLException next = new SQLException("synthetic second cleanup", "08006");
        var adapter = LegalPrivateRequirementsDeadline.adoptRegistrationBudget(owner);
        adapter.recordCleanupFailure(first);
        owner.recordCleanupFailure(next);
        assertThatThrownBy(adapter::check).isExactlyInstanceOf(LegalPrivateRequirementsReadException.class).hasCause(first);
        assertThatThrownBy(owner::check).isExactlyInstanceOf(LegalRegistrationBudget.UnavailableException.class).hasCause(first);
        assertThat(first.getSuppressed()).containsExactly(next);
        try (var source = LegalPrivateRequirementsDataSource.registration(pool)) {
            assertThatThrownBy(() -> source.withinRegistrationBudget(owner, deadline -> "cannot deliver"))
                    .isExactlyInstanceOf(LegalPrivateRequirementsReadException.class).hasCause(first);
            assertOutsideScope(source);
        }
        verifyNoInteractions(pool);
    }

    @ParameterizedTest @ValueSource(strings = {"expired", "interrupted"})
    void adapterKeepsALaterCleanupCauseEvenWhenItsOwnerWasAlreadyTerminal(String defect) {
        AtomicLong clock = new AtomicLong();
        var owner = LegalRegistrationBudget.start(clock::get);
        var adapter = LegalPrivateRequirementsDeadline.adoptRegistrationBudget(owner);
        try {
            if (defect.equals("expired")) clock.set(30_000_000_000L);
            else Thread.currentThread().interrupt();
            assertThatThrownBy(adapter::check).isExactlyInstanceOf(LegalPrivateRequirementsReadException.class).hasNoCause();
            if (defect.equals("interrupted")) assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        SQLException cleanup = new SQLException("synthetic cleanup after terminal observation", "08006");
        adapter.recordCleanupFailure(cleanup);
        assertThatThrownBy(adapter::check).isExactlyInstanceOf(LegalPrivateRequirementsReadException.class).hasCause(cleanup);
        assertThatThrownBy(owner::check).isExactlyInstanceOf(LegalRegistrationBudget.UnavailableException.class).hasCause(cleanup);
    }

    @Test
    void alreadySpentTimeConstrainsSqlFetchAndCommitInARealSpringTransaction() throws Exception {
        try (var f = new SpringFixture()) {
            Statement query = mock(Statement.class);
            Statement settings = mock(Statement.class);
            ResultSet rows = mock(ResultSet.class);
            when(f.driver.createStatement()).thenReturn(query, settings);
            when(query.executeQuery("SELECT budget_fixture")).thenReturn(rows);
            f.clock.set(28_000_000_000L);
            String result = f.source.withinRegistrationBudget(f.owner, deadline -> f.transaction.execute(status -> {
                TransactionSynchronizationManager.registerSynchronization(f.notifications);
                assertThat(f.source.<LegalPrivateRequirementsDeadline>withinDeadline(inner -> inner)).isSameAs(deadline);
                Connection connection = DataSourceUtils.getConnection(f.source);
                try (Statement statement = connection.createStatement(); ResultSet data = statement.executeQuery("SELECT budget_fixture")) {
                    verify(query).setQueryTimeout(2);
                    verify(settings).execute("SET LOCAL statement_timeout TO '2000ms'");
                    f.clock.set(28_600_000_000L);
                    assertThat(data.next()).isFalse();
                    verify(f.driver, atLeastOnce()).setNetworkTimeout(any(), eq(1_400));
                } catch (SQLException failure) {
                    throw new AssertionError(failure);
                }
                f.clock.set(29_000_000_000L);
                return "committed within the original owner";
            }));
            assertThat(result).isEqualTo("committed within the original owner");
            assertThat(f.owner.remainingMillis()).isEqualTo(1_000);
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

    @Test
    void insufficientRemainingBorrowBudgetNeverReachesThePool() {
        DataSource pool = mock(DataSource.class);
        AtomicLong clock = new AtomicLong();
        var owner = LegalRegistrationBudget.start(clock::get);
        clock.set(29_500_000_000L);
        try (var source = LegalPrivateRequirementsDataSource.registration(pool)) {
            source.withinRegistrationBudget(owner, deadline -> {
                assertThat(deadline.remainingMillis()).isEqualTo(500);
                assertThatThrownBy(source::getConnection).isExactlyInstanceOf(LegalPrivateRequirementsReadException.class);
                return null;
            });
            assertOutsideScope(source);
        }
        verifyNoInteractions(pool);
    }

    @Test
    void expirationInTheCallbackRollsBackAndCannotBeResetByANestedHistoricalCall() throws Exception {
        try (var f = new SpringFixture()) {
            f.clock.set(20_000_000_000L);
            assertThatThrownBy(() -> f.source.withinRegistrationBudget(f.owner, deadline -> f.transaction.execute(status -> {
                TransactionSynchronizationManager.registerSynchronization(f.notifications);
                f.clock.set(30_000_000_000L);
                return f.source.withinDeadline(inner -> "expired");
            }))).isExactlyInstanceOf(LegalPrivateRequirementsReadException.class).hasNoCause();
            verify(f.driver).rollback();
            verify(f.driver, never()).commit();
            verify(f.driver).close();
            assertThat(f.notifications.completions).containsExactly(TransactionSynchronization.STATUS_ROLLED_BACK);
            assertThatThrownBy(f.owner::check).isExactlyInstanceOf(LegalRegistrationBudget.UnavailableException.class);
            f.assertReleased();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"commit", "close"})
    void expirationAfterPhysicalCommitDoesNotChangeSpringsCommittedNotification(String latePhase) throws Exception {
        try (var f = new SpringFixture()) {
            f.clock.set(22_000_000_000L);
            if (latePhase.equals("commit")) doAnswer(call -> { f.clock.set(30_000_000_000L); return null; }).when(f.driver).commit();
            else doAnswer(call -> { f.clock.set(30_000_000_000L); return null; }).when(f.driver).close();
            assertThatThrownBy(() -> f.source.withinRegistrationBudget(f.owner, deadline -> f.transaction.execute(status -> {
                TransactionSynchronizationManager.registerSynchronization(f.notifications);
                return "cannot deliver a late result";
            }))).isExactlyInstanceOf(LegalPrivateRequirementsReadException.class).hasNoCause();
            assertThat(f.notifications.afterCommits).isEqualTo(1);
            assertThat(f.notifications.completions).containsExactly(TransactionSynchronization.STATUS_COMMITTED);
            verify(f.driver).commit();
            verify(f.driver, never()).rollback();
            verify(f.driver).close();
            f.assertReleased();
        }
    }

    @Test
    void cleanupAbsorbedBySpringStillInvalidatesTheOriginalOwnerAfterCommit() throws Exception {
        try (var f = new SpringFixture()) {
            SQLException cleanup = new SQLException("synthetic release failure", "08006");
            doThrow(cleanup).when(f.driver).close();
            assertThatThrownBy(() -> f.source.withinRegistrationBudget(f.owner, deadline -> f.transaction.execute(status -> {
                TransactionSynchronizationManager.registerSynchronization(f.notifications);
                return "cannot deliver after failed release";
            }))).isExactlyInstanceOf(LegalPrivateRequirementsReadException.class).hasCause(cleanup);
            assertThatThrownBy(f.owner::check).isExactlyInstanceOf(LegalRegistrationBudget.UnavailableException.class).hasCause(cleanup);
            assertThat(f.notifications.afterCommits).isEqualTo(1);
            assertThat(f.notifications.completions).containsExactly(TransactionSynchronization.STATUS_COMMITTED);
            verify(f.driver).commit();
            verify(f.driver, never()).rollback();
            f.assertReleased();
        }
    }

    @Test
    void uncertainCommitRemainsUnknownWithoutTreatingEverySqlFailureAsOwnerCleanup() throws Exception {
        try (var f = new SpringFixture()) {
            SQLException uncertain = new SQLException("synthetic lost commit acknowledgement", "08006");
            doThrow(uncertain).when(f.driver).commit();
            f.clock.set(23_000_000_000L);
            assertThatThrownBy(() -> f.source.withinRegistrationBudget(f.owner, deadline -> f.transaction.execute(status -> {
                TransactionSynchronizationManager.registerSynchronization(f.notifications);
                return "receipt";
            }))).isExactlyInstanceOf(TransactionSystemException.class).hasCause(uncertain);
            assertThat(f.notifications.afterCommits).isZero();
            assertThat(f.notifications.completions).containsExactly(TransactionSynchronization.STATUS_UNKNOWN);
            assertThat(f.owner.remainingMillis()).isEqualTo(7_000);
            verify(f.driver, never()).rollback();
            verify(f.driver).close();
            f.assertReleased();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"null", "expired", "cleanup"})
    void serviceRejectsAnUnavailableSuppliedOwnerBeforeShapeValidationAndBorrow(String defect) {
        try (var f = new ServiceShapeFixture()) {
            AtomicLong clock = new AtomicLong();
            var owner = LegalRegistrationBudget.start(clock::get);
            if (defect.equals("expired")) clock.set(30_000_000_000L);
            if (defect.equals("cleanup")) owner.recordCleanupFailure(new SQLException("synthetic prior cleanup"));
            var supplied = defect.equals("null") ? null : owner;
            var failure = (LegalRegistrationFailure) catchThrowable(() -> f.service.register(null, null, null, null, null, supplied));
            assertThat(failure.reason()).isEqualTo(LegalRegistrationFailure.Reason.UNAVAILABLE);
            assertNoTransaction(failure);
            verifyNoInteractions(f.pool);
            verify(f.boundary, never()).execute(any(), any());
            assertOutsideScope(f.source);
        }
    }

    @Test
    void serviceKeepsHistoricalShapeClassificationWithAnAvailableSuppliedOwnerOrItsOriginalApi() {
        try (var f = new ServiceShapeFixture()) {
            var owner = LegalRegistrationBudget.start(() -> 0L);
            var adopted = (LegalRegistrationFailure) catchThrowable(() -> f.service.register(null, null, null, null, null, owner));
            var historical = (LegalRegistrationFailure) catchThrowable(() -> f.service.register(null, null, null, null, null));
            for (var failure : List.of(adopted, historical)) {
                assertThat(failure.reason()).isEqualTo(LegalRegistrationFailure.Reason.INVALID_PAYLOAD);
                assertNoTransaction(failure);
            }
            verifyNoInteractions(f.pool);
            verify(f.boundary, never()).execute(any(), any());
            assertOutsideScope(f.source);
        }
    }

    private static void assertNoTransaction(LegalRegistrationFailure failure) {
        assertThat(failure.completion()).isEqualTo(LegalRegistrationFailure.Completion.NONE);
        assertThat(failure.persistence()).isEqualTo(LegalRegistrationFailure.Persistence.NOT_PERSISTED);
        assertThat(failure.confirmedReceipt()).isEmpty();
        assertThat(failure.validation()).isEmpty();
    }

    private static void assertOutsideScope(LegalPrivateRequirementsDataSource source) {
        assertThatThrownBy(source::getConnection).isExactlyInstanceOf(SQLException.class);
    }

    private static final class Notifications implements TransactionSynchronization {
        int afterCommits;
        final List<Integer> completions = new ArrayList<>();
        @Override public void afterCommit() { afterCommits++; }
        @Override public void afterCompletion(int status) { completions.add(status); }
    }

    private static final class SpringFixture implements AutoCloseable {
        final DataSource pool = mock(DataSource.class);
        final Connection driver = mock(Connection.class);
        final AtomicLong clock = new AtomicLong();
        final LegalRegistrationBudget owner = LegalRegistrationBudget.start(clock::get);
        final LegalPrivateRequirementsDataSource source = LegalPrivateRequirementsDataSource.registration(pool, () -> {
            throw new AssertionError("an adopted Spring operation cannot start a private budget");
        });
        final TransactionTemplate transaction;
        final Notifications notifications = new Notifications();

        SpringFixture() throws SQLException {
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
            transaction.setTimeout(25);
        }

        void assertReleased() {
            assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
            assertOutsideScope(source);
        }

        @Override public void close() { source.close(); }
    }

    /** Only the pre-transaction service gate is mocked here; transaction evidence is tested above. */
    private static final class ServiceShapeFixture implements AutoCloseable {
        final DataSource pool = mock(DataSource.class);
        final LegalPrivateRequirementsDataSource source = LegalPrivateRequirementsDataSource.registration(pool, () -> 0L);
        final LegalRegistrationTransactionBoundary boundary = mock(LegalRegistrationTransactionBoundary.class);
        final MockedConstruction<LegalIdempotencyCoordinator> coordinator = mockConstruction(LegalIdempotencyCoordinator.class);
        final LegalRegistrationService service;

        ServiceShapeFixture() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            var aggregates = mock(LegalRequiredSetAggregateStore.class);
            var reader = mock(LegalPublicRequirementsReader.class);
            var writer = mock(LegalRegistrationWriter.class);
            var schema = mock(LegalV29AcceptanceSchemaVerifier.class);
            when(jdbc.getDataSource()).thenReturn(source);
            when(boundary.usesJdbc(jdbc)).thenReturn(true);
            when(aggregates.usesJdbc(jdbc)).thenReturn(true);
            when(reader.usesJdbc(jdbc)).thenReturn(true);
            when(writer.usesJdbc(jdbc)).thenReturn(true);
            when(schema.usesJdbc(jdbc)).thenReturn(true);
            service = new LegalRegistrationService(jdbc, source, boundary, new LegalApplicableScopeResolver(), aggregates,
                    reader, mock(LegalRegistrationPreparation.class), writer, schema, mock(LegalAcceptanceKeyConfiguration.class));
        }

        @Override public void close() { try { source.close(); } finally { coordinator.close(); } }
    }
}
