package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommandValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalActorSnapshot;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Pure lock ordering/budget tests; PostgreSQL integration owns real reservation and replay behavior. */
class LegalIdempotencyCoordinatorTest {
    private static final String REQUEST_KEY = "76962ff8-557a-4a9f-aafa-2db4d69cfc74";
    private final DataSource source = mock(DataSource.class);
    private final DataSource foreignSource = mock(DataSource.class);
    private final JdbcTemplate jdbc = new JdbcTemplate(source);
    private final LegalV29AcceptanceSchemaVerifier schema = mock(LegalV29AcceptanceSchemaVerifier.class);
    private final LegalIdempotencyResultStore store = mock(LegalIdempotencyResultStore.class);
    private final LegalIdempotencyKeyring keyring = new LegalIdempotencyKeyring(
            java.util.Map.of(1, Base64.getEncoder().encodeToString(new byte[32])), 1);

    @BeforeEach void matchingGraph() {
        when(schema.usesJdbc(jdbc)).thenReturn(true);
        when(schema.expectedSchema()).thenReturn("public");
        when(store.usesJdbc(jdbc)).thenReturn(true);
    }

    @AfterEach void releaseOnlyThisTestsThreadState() {
        if (TransactionSynchronizationManager.hasResource(source)) TransactionSynchronizationManager.unbindResource(source);
        if (TransactionSynchronizationManager.hasResource(foreignSource)) TransactionSynchronizationManager.unbindResource(foreignSource);
        TransactionSynchronizationManager.clear();
    }

    @Test void allLockAttemptsConsumeOneMonotonicFiveSecondBudget() {
        AtomicLong clock = new AtomicLong(10_000);
        var budget = new LegalIdempotencyCoordinator.WaitBudget(clock::get, () -> 30_000);
        assertThat(budget.remainingMillis()).isEqualTo(5_000);
        clock.addAndGet(3_000_000_000L);
        assertThat(budget.remainingMillis()).isEqualTo(2_000);
        clock.addAndGet(1_999_000_000L);
        assertThat(budget.remainingMillis()).isEqualTo(1);
        clock.addAndGet(1_000_000L);
        assertReason(LegalIdempotencyException.Reason.IN_PROGRESS, budget::remainingMillis);
        // Repeated calls after expiry never start a fresh per-key budget.
        assertReason(LegalIdempotencyException.Reason.IN_PROGRESS, budget::remainingMillis);
    }

    @Test void fractionsAreFlooredAndNeverBecomePostgresZeroWhichMeansUnlimited() {
        AtomicLong clock = new AtomicLong();
        var budget = new LegalIdempotencyCoordinator.WaitBudget(clock::get, () -> Integer.MAX_VALUE);
        clock.set(1L);
        assertThat(budget.remainingMillis()).isEqualTo(4_999);
        clock.set(4_999_000_000L);
        assertThat(budget.remainingMillis()).isEqualTo(1);
        clock.set(4_999_000_001L);
        assertReason(LegalIdempotencyException.Reason.IN_PROGRESS, budget::remainingMillis);
    }

    @Test void outerDeadlineCapsEveryAttemptWithoutResettingTheReservationClock() {
        AtomicLong clock = new AtomicLong();
        AtomicInteger outer = new AtomicInteger(700);
        var budget = new LegalIdempotencyCoordinator.WaitBudget(clock::get, outer::get);
        assertThat(budget.remainingMillis()).isEqualTo(700);
        clock.addAndGet(3_000_000_000L);
        outer.set(250);
        assertThat(budget.remainingMillis()).isEqualTo(250);
        outer.set(30_000);
        assertThat(budget.remainingMillis()).isEqualTo(2_000);
    }

    @ParameterizedTest @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void expiredOuterBudgetIsUnavailableInsteadOfBeingReportedAsContention(int remaining) {
        assertReason(LegalIdempotencyException.Reason.UNAVAILABLE,
                () -> new LegalIdempotencyCoordinator.WaitBudget(() -> 0L, () -> remaining).remainingMillis());
    }

    @Test void anExpiredOuterDeadlineHasPriorityWhenTheReservationBudgetAlsoExpired() {
        AtomicLong clock = new AtomicLong();
        AtomicInteger outer = new AtomicInteger(5_000);
        var budget = new LegalIdempotencyCoordinator.WaitBudget(clock::get, outer::get);
        clock.set(5_000_000_000L); outer.set(0);
        assertReason(LegalIdempotencyException.Reason.UNAVAILABLE, budget::remainingMillis);
    }

    @ParameterizedTest @ValueSource(strings = {"55P03", "57014"})
    void anExpiredOuterDeadlineCannotBeReportedAsLockContention(String sqlState) {
        AtomicLong clock = new AtomicLong();
        AtomicInteger outer = new AtomicInteger(5_000);
        var budget = new LegalIdempotencyCoordinator.WaitBudget(clock::get, outer::get);
        clock.set(5_000_000_000L); outer.set(0);
        RuntimeException failure = new RuntimeException(new SQLException("synthetic timeout", sqlState));
        assertThat(LegalIdempotencyCoordinator.lockFailure(failure, budget))
                .hasCause(failure).satisfies(result -> assertThat(result.reason()).isEqualTo(LegalIdempotencyException.Reason.UNAVAILABLE));
    }

    @Test void lockTimeoutWithOuterBudgetRemainingRepresentsContention() {
        var budget = new LegalIdempotencyCoordinator.WaitBudget(() -> 0L, () -> 25_000);
        RuntimeException failure = new RuntimeException(new SQLException("synthetic timeout", "55P03"));
        assertThat(LegalIdempotencyCoordinator.lockFailure(failure, budget))
                .hasCause(failure).satisfies(result -> assertThat(result.reason()).isEqualTo(LegalIdempotencyException.Reason.IN_PROGRESS));
    }

    @Test void queryCancellationIsContentionOnlyWhenTheReservationDeadlineExplainsIt() {
        AtomicLong clock = new AtomicLong();
        var budget = new LegalIdempotencyCoordinator.WaitBudget(clock::get, () -> 25_000);
        RuntimeException failure = new RuntimeException(new SQLException("synthetic cancellation", "57014"));
        assertThat(LegalIdempotencyCoordinator.lockFailure(failure, budget))
                .hasCause(failure).satisfies(result -> assertThat(result.reason()).isEqualTo(LegalIdempotencyException.Reason.UNAVAILABLE));
        clock.set(5_000_000_000L);
        assertThat(LegalIdempotencyCoordinator.lockFailure(failure, budget))
                .hasCause(failure).satisfies(result -> assertThat(result.reason()).isEqualTo(LegalIdempotencyException.Reason.IN_PROGRESS));
    }

    @ParameterizedTest @ValueSource(strings = {"40P01", "42501", "08006", "23505"})
    void deadlocksPermissionsConnectionLossAndUniqueViolationsAreNotSuccessfulReservationsOrContention(String state) {
        var budget = new LegalIdempotencyCoordinator.WaitBudget(() -> 0L, () -> 25_000);
        RuntimeException failure = new RuntimeException(new SQLException("synthetic failure", state));
        assertThat(LegalIdempotencyCoordinator.lockFailure(failure, budget))
                .hasCause(failure).satisfies(result -> assertThat(result.reason()).isEqualTo(LegalIdempotencyException.Reason.UNAVAILABLE));
    }

    @Test void anOuterDeadlineThatThrowsPreservesTheOriginalSqlFailureAndReportsUnavailable() {
        var outerFailure = new IllegalStateException("outer expired");
        var budget = new LegalIdempotencyCoordinator.WaitBudget(() -> 0L, () -> { throw outerFailure; });
        SQLException sqlFailure = new SQLException("synthetic timeout", "55P03");
        RuntimeException failure = new RuntimeException(sqlFailure);
        assertThat(LegalIdempotencyCoordinator.lockFailure(failure, budget))
                .hasCause(failure).satisfies(result -> assertThat(result.reason()).isEqualTo(LegalIdempotencyException.Reason.UNAVAILABLE));
        assertThat(failure).hasCause(sqlFailure);
        assertThat(failure.getSuppressed()).containsExactly(outerFailure);
    }

    @Test void interruptionDuringLockFailureRemainsUnavailableAndKeepsTheFlag() {
        var budget = new LegalIdempotencyCoordinator.WaitBudget(() -> 0L, () -> 25_000);
        RuntimeException failure = new RuntimeException(new SQLException("synthetic timeout", "55P03"));
        try {
            Thread.currentThread().interrupt();
            assertThat(LegalIdempotencyCoordinator.lockFailure(failure, budget))
                    .hasCause(failure).satisfies(result -> assertThat(result.reason()).isEqualTo(LegalIdempotencyException.Reason.UNAVAILABLE));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }

    @Test void aBackwardMonotonicClockFailsClosed() {
        AtomicLong clock = new AtomicLong(100);
        var budget = new LegalIdempotencyCoordinator.WaitBudget(clock::get, () -> 5_000);
        clock.set(99);
        assertReason(LegalIdempotencyException.Reason.UNAVAILABLE, budget::remainingMillis);
    }

    @Test void elapsedSubtractionSupportsTheNormalNanoTimeSignedWraparound() {
        AtomicLong clock = new AtomicLong(Long.MAX_VALUE - 100);
        var budget = new LegalIdempotencyCoordinator.WaitBudget(clock::get, () -> 5_000);
        clock.addAndGet(1_000_000);
        assertThat(budget.remainingMillis()).isEqualTo(4_999);
    }

    @Test void interruptionFailsClosedAndPreservesTheCallersInterruptFlag() {
        var budget = new LegalIdempotencyCoordinator.WaitBudget(System::nanoTime, () -> 5_000);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
        try {
            Thread.currentThread().interrupt();
            assertReason(LegalIdempotencyException.Reason.UNAVAILABLE, budget::remainingMillis);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test void physicalLocksUseUnsignedBigEndianOrderAndDeduplicateCollisionsOnly() {
        List<Long> supplied = new ArrayList<>(List.of(-1L, Long.MIN_VALUE, 1L, Long.MAX_VALUE, 0L, Long.MIN_VALUE));
        var ordered = LegalIdempotencyCoordinator.orderedLocks(supplied);
        assertThat(ordered).containsExactly(0L, 1L, Long.MAX_VALUE, Long.MIN_VALUE, -1L);
        assertThat(supplied).containsExactly(-1L, Long.MIN_VALUE, 1L, Long.MAX_VALUE, 0L, Long.MIN_VALUE);
        supplied.clear();
        assertThat(ordered).hasSize(5);
        assertThatThrownBy(() -> ordered.add(2L)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void permitsAllEightLogicalCandidatesToShareOnePhysicalAdvisory() {
        assertThat(LegalIdempotencyCoordinator.orderedLocks(java.util.Collections.nCopies(8, -1L)))
                .containsExactly(-1L);
    }

    @Test void rejectsMissingOrOversizedPhysicalKeyVectorsBeforeSorting() {
        assertReason(LegalIdempotencyException.Reason.UNAVAILABLE, () -> LegalIdempotencyCoordinator.orderedLocks(null));
        assertReason(LegalIdempotencyException.Reason.UNAVAILABLE, () -> LegalIdempotencyCoordinator.orderedLocks(List.of()));
        assertReason(LegalIdempotencyException.Reason.UNAVAILABLE,
                () -> LegalIdempotencyCoordinator.orderedLocks(java.util.Collections.nCopies(9, 1L)));
        assertReason(LegalIdempotencyException.Reason.UNAVAILABLE,
                () -> LegalIdempotencyCoordinator.orderedLocks(Arrays.asList(0L, null)));
    }

    @ParameterizedTest @ValueSource(strings = {"schema-jdbc", "schema-name", "store-jdbc"})
    void constructorRejectsCollaboratorsOutsideTheAccreditedJdbcGraphWithoutBorrowing(String mismatch) {
        switch (mismatch) {
            case "schema-jdbc" -> when(schema.usesJdbc(jdbc)).thenReturn(false);
            case "schema-name" -> when(schema.expectedSchema()).thenReturn("foreign_schema");
            case "store-jdbc" -> when(store.usesJdbc(jdbc)).thenReturn(false);
            default -> throw new AssertionError(mismatch);
        }
        assertThatThrownBy(this::coordinator).isInstanceOf(IllegalArgumentException.class);
        verify(schema, never()).verify(); verifyNoInteractions(source, foreignSource);
    }

    @Test void constructorRefusesAnUnconfiguredJdbcTemplateBeforeAnySql() {
        var noSource = new JdbcTemplate();
        when(schema.usesJdbc(noSource)).thenReturn(true);
        when(store.usesJdbc(noSource)).thenReturn(true);
        assertThatThrownBy(() -> new LegalIdempotencyCoordinator(noSource, schema, keyring, store))
                .isInstanceOf(RuntimeException.class);
        verify(schema, never()).verify(); verifyNoInteractions(source, foreignSource);
    }

    @ParameterizedTest @ValueSource(strings = {"no-transaction", "no-synchronization", "read-only", "repeatable-read", "undeclared-isolation", "missing-resource", "foreign-resource", "wrong-holder"})
    void reserveRejectsAnAbsentOrForeignSpringTransactionBeforePreflightOrBorrow(String invalid) {
        var coordinator = coordinator();
        if (!invalid.equals("no-synchronization")) TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(!invalid.equals("no-transaction"));
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(invalid.equals("read-only"));
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
                invalid.equals("undeclared-isolation") ? null
                        : invalid.equals("repeatable-read") ? Connection.TRANSACTION_REPEATABLE_READ : Connection.TRANSACTION_READ_COMMITTED);
        Connection connection = mock(Connection.class);
        if (invalid.equals("foreign-resource")) {
            TransactionSynchronizationManager.bindResource(foreignSource, new ConnectionHolder(connection));
        } else if (invalid.equals("wrong-holder")) {
            TransactionSynchronizationManager.bindResource(source, new Object());
        } else if (!invalid.equals("missing-resource")) {
            TransactionSynchronizationManager.bindResource(source, new ConnectionHolder(connection));
        }
        clearInvocations(schema, store);
        assertReason(LegalIdempotencyException.Reason.UNAVAILABLE,
                () -> coordinator.reserve(command(), REQUEST_KEY, () -> 30_000));
        verifyNoInteractions(schema, store, source, foreignSource, connection);
    }

    @ParameterizedTest @ValueSource(strings = {"auto-commit", "read-only", "isolation"})
    void realConnectionModeMustMatchTheDeclaredSpringBoundaryAndFailureMarksItRollbackOnly(String invalid) throws SQLException {
        var coordinator = coordinator();
        Connection connection = mock(Connection.class);
        when(connection.getAutoCommit()).thenReturn(invalid.equals("auto-commit"));
        when(connection.isReadOnly()).thenReturn(invalid.equals("read-only"));
        when(connection.getTransactionIsolation()).thenReturn(invalid.equals("isolation")
                ? Connection.TRANSACTION_REPEATABLE_READ : Connection.TRANSACTION_READ_COMMITTED);
        ConnectionHolder holder = new ConnectionHolder(connection);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(Connection.TRANSACTION_READ_COMMITTED);
        TransactionSynchronizationManager.bindResource(source, holder);
        clearInvocations(schema, store);
        assertReason(LegalIdempotencyException.Reason.UNAVAILABLE,
                () -> coordinator.reserve(command(), REQUEST_KEY, () -> 25_000));
        assertThat(holder.isRollbackOnly()).isTrue();
        verifyNoInteractions(source, foreignSource, schema, store);
        verify(connection, never()).commit(); verify(connection, never()).rollback();
        verify(connection, never()).createStatement(); verify(connection, never()).prepareStatement(anyString());
    }

    private LegalIdempotencyCoordinator coordinator() {
        return new LegalIdempotencyCoordinator(jdbc, schema, keyring, store);
    }

    private static LegalAcceptanceCommand command() {
        return LegalAcceptanceCommandValidator.authenticated(
                new LegalActorSnapshot(51, 72, UserRole.USER, 3, true, true), "sha256:" + "a".repeat(64), List.of());
    }

    private static void assertReason(LegalIdempotencyException.Reason expected,
                                     org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(LegalIdempotencyException.class,
                failure -> assertThat(failure.reason()).isEqualTo(expected));
    }
}
