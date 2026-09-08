package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class LegalRegistrationBudgetTest {
    @Test
    void startsOnceAndChargesEveryPhaseToTheOriginalThirtySeconds() {
        AtomicLong clock = new AtomicLong(5_000_000_000L);
        AtomicInteger reads = new AtomicInteger();
        var owner = LegalRegistrationBudget.start(() -> { reads.incrementAndGet(); return clock.get(); });
        assertThat(reads).hasValue(1);
        assertThat(owner.remainingMillis()).isEqualTo(30_000);
        clock.addAndGet(12_000_000_000L);
        assertThat(owner.remainingMillis()).isEqualTo(18_000);
        clock.addAndGet(7_000_000_000L);
        owner.check();
        assertThat(owner.remainingMillis()).isEqualTo(11_000);
        clock.addAndGet(10_999_999_999L);
        assertThat(owner.remainingMillis()).isEqualTo(1);
        clock.incrementAndGet();
        assertUnavailable(owner);
    }

    @ParameterizedTest
    @CsvSource({"0,30000", "1,30000", "1000000,29999", "29998999999,2", "29999000000,1", "29999999999,1"})
    void roundsOnlyPositiveFractionsUp(long elapsed, int expected) {
        AtomicLong clock = new AtomicLong();
        var owner = LegalRegistrationBudget.start(clock::get);
        clock.set(elapsed);
        assertThat(owner.remainingMillis()).isEqualTo(expected);
    }

    @Test
    void exactExpiryIsTerminalEvenIfTheClockSubsequentlyMovesBack() {
        AtomicLong clock = new AtomicLong();
        var owner = LegalRegistrationBudget.start(clock::get);
        clock.set(30_000_000_000L);
        assertUnavailable(owner);
        clock.set(1);
        assertUnavailable(owner);
    }

    @Test
    void detectsRegressionBetweenObservationsEvenWhileBothAreAfterTheStart() {
        AtomicLong clock = new AtomicLong();
        var owner = LegalRegistrationBudget.start(clock::get);
        clock.set(20_000_000_000L);
        assertThat(owner.remainingMillis()).isEqualTo(10_000);
        clock.set(19_999_999_999L);
        assertUnavailable(owner);
        clock.set(21_000_000_000L);
        assertUnavailable(owner);
    }

    @Test
    void supportsShortSignedNanoTimeWrapWithoutGrantingMoreTime() {
        AtomicLong clock = new AtomicLong(Long.MAX_VALUE - 10_000_000_000L);
        var owner = LegalRegistrationBudget.start(clock::get);
        clock.addAndGet(16_000_000_000L);
        assertThat(clock.get()).isNegative();
        assertThat(owner.remainingMillis()).isEqualTo(14_000);
        clock.addAndGet(13_999_999_999L);
        assertThat(owner.remainingMillis()).isEqualTo(1);
        clock.incrementAndGet();
        assertUnavailable(owner);
    }

    @Test
    void rejectsAnAmbiguousLongClockJumpInsteadOfOverflowingIntoAnAvailableBudget() {
        AtomicLong clock = new AtomicLong();
        var owner = LegalRegistrationBudget.start(clock::get);
        clock.set(Long.MIN_VALUE);
        assertUnavailable(owner);
        clock.set(0);
        assertUnavailable(owner);
    }

    @Test
    void interruptionIsTerminalAndDoesNotConsumeTheThreadFlag() {
        var owner = LegalRegistrationBudget.start(() -> 0L);
        try {
            Thread.currentThread().interrupt();
            assertUnavailable(owner);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        assertUnavailable(owner);
        assertThat(LegalRegistrationBudget.start(() -> 0L).remainingMillis()).isEqualTo(30_000);
    }

    @Test
    void retainsTheFirstCleanupCauseAndSuppressesSubsequentFailuresWithoutSelfSuppression() {
        var owner = LegalRegistrationBudget.start(() -> 0L);
        SQLException first = new SQLException("synthetic close one", "08006");
        SQLException second = new SQLException("synthetic close two", "08006");
        IllegalStateException third = new IllegalStateException("synthetic release three");
        owner.recordCleanupFailure(first);
        owner.recordCleanupFailure(first);
        owner.recordCleanupFailure(second);
        owner.recordCleanupFailure(third);
        assertThatThrownBy(owner::check).isExactlyInstanceOf(LegalRegistrationBudget.UnavailableException.class)
                .hasCause(first);
        assertThat(first.getSuppressed()).containsExactly(second, third);
    }

    @Test
    void cleanupRecordedAfterExpiryStillPreservesItsOriginalCause() {
        AtomicLong clock = new AtomicLong();
        var owner = LegalRegistrationBudget.start(clock::get);
        clock.set(30_000_000_000L);
        assertUnavailable(owner);
        var cleanup = new SQLException("synthetic late cleanup", "08006");
        owner.recordCleanupFailure(cleanup);
        clock.set(0);
        assertThatThrownBy(owner::remainingMillis).hasCause(cleanup);
    }

    @Test
    void nullCleanupIsRejectedWithoutInvalidatingTheOwner() {
        var owner = LegalRegistrationBudget.start(() -> 0L);
        assertThatThrownBy(() -> owner.recordCleanupFailure(null)).isInstanceOf(NullPointerException.class);
        assertThat(owner.remainingMillis()).isEqualTo(30_000);
        assertThatThrownBy(() -> LegalRegistrationBudget.start(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void diagnosticsDoNotContainClockOrCleanupInputs() {
        var owner = LegalRegistrationBudget.start(() -> 123_456_789L);
        SQLException original = new SQLException("password=synthetic-private-value", "08006");
        owner.recordCleanupFailure(original);
        Throwable failure = catchThrowable(owner::check);
        assertThat(failure).isExactlyInstanceOf(LegalRegistrationBudget.UnavailableException.class).hasCause(original);
        assertThat(failure.getMessage()).isEqualTo("El presupuesto de registro no está disponible");
        assertThat(failure.toString()).doesNotContain("password", "synthetic-private-value", "08006", "123456789");
        assertThat(owner.toString()).isEqualTo("LegalRegistrationBudget[redacted]");
    }

    @Test
    void aFailingClockCannotBeRecoveredToResumeTheSameOperation() {
        AtomicInteger reads = new AtomicInteger();
        var owner = LegalRegistrationBudget.start(() -> {
            if (reads.incrementAndGet() == 2) throw new IllegalStateException("synthetic clock detail");
            return 0;
        });
        assertUnavailable(owner);
        assertUnavailable(owner);
        assertThat(reads).hasValue(2);
    }

    private static void assertUnavailable(LegalRegistrationBudget owner) {
        assertThatThrownBy(owner::remainingMillis)
                .isExactlyInstanceOf(LegalRegistrationBudget.UnavailableException.class).hasNoCause();
    }
}
