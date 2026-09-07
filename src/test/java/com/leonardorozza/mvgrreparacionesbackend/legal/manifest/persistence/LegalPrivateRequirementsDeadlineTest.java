package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class LegalPrivateRequirementsDeadlineTest {

    @Test
    void acceptsTheInclusiveBudgetBounds() {
        assertThat(new LegalPrivateRequirementsDeadline(Duration.ofNanos(1), () -> 0L)
                .remainingMillis()).isEqualTo(1);
        assertThat(new LegalPrivateRequirementsDeadline(Duration.ofSeconds(15), () -> 0L)
                .remainingMillis()).isEqualTo(15_000);
    }

    @Test
    void rejectsMissingNonPositiveOrExcessiveBudgets() {
        assertThatThrownBy(() -> new LegalPrivateRequirementsDeadline(null, () -> 0L))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LegalPrivateRequirementsDeadline(Duration.ofSeconds(2), null))
                .isInstanceOf(NullPointerException.class);
        for (Duration invalid : List.of(Duration.ZERO, Duration.ofNanos(-1),
                Duration.ofSeconds(15).plusNanos(1), Duration.ofSeconds(30), Duration.ofSeconds(35),
                Duration.ofSeconds(Long.MAX_VALUE))) {
            assertThatThrownBy(() -> new LegalPrivateRequirementsDeadline(invalid, () -> 0L))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void onlyTheRegistrationFactoryOpensThirtySecondsAndNeverResetsAcrossPhasesOrSignedOverflow() {
        AtomicLong clock = new AtomicLong(Long.MAX_VALUE - 10_000_000_000L);
        LegalPrivateRequirementsDeadline deadline = LegalPrivateRequirementsDeadline.registration(clock::get);
        assertThat(deadline.remainingMillis()).isEqualTo(30_000);
        clock.addAndGet(16_000_000_000L);
        assertThat(clock.get()).isNegative();
        deadline.check();
        assertThat(deadline.remainingMillis()).isEqualTo(14_000);
        clock.addAndGet(13_999_999_999L);
        assertThat(deadline.remainingMillis()).isEqualTo(1);
        clock.incrementAndGet();
        assertThatThrownBy(deadline::check).isInstanceOf(LegalPrivateRequirementsReadException.class);
        clock.addAndGet(5_000_000_000L);
        assertThatThrownBy(deadline::remainingMillis).isInstanceOf(LegalPrivateRequirementsReadException.class);
    }

    @Test
    void registrationRequiresItsClockAndRetainsCleanupFailureInsteadOfGrantingRemainingTime() {
        assertThatThrownBy(() -> LegalPrivateRequirementsDeadline.registration(null))
                .isInstanceOf(NullPointerException.class);
        AtomicLong clock = new AtomicLong();
        LegalPrivateRequirementsDeadline deadline = LegalPrivateRequirementsDeadline.registration(clock::get);
        clock.set(29_000_000_000L);
        assertThat(deadline.remainingMillis()).isEqualTo(1_000);
        var cleanup = new SQLException("synthetic cleanup failure");
        deadline.recordCleanupFailure(cleanup);
        assertThatThrownBy(deadline::remainingMillis).isInstanceOf(LegalPrivateRequirementsReadException.class)
                .hasCause(cleanup);
        clock.set(35_000_000_000L);
        assertThatThrownBy(deadline::check).isInstanceOf(LegalPrivateRequirementsReadException.class).hasCause(cleanup);
    }

    @Test
    void roundsPositiveFractionsUpWithoutGrantingAnExtraMillisecond() {
        AtomicLong clock = new AtomicLong(50_000_000L);
        LegalPrivateRequirementsDeadline deadline = new LegalPrivateRequirementsDeadline(
                Duration.ofSeconds(2), clock::get);

        assertThat(deadline.remainingMillis()).isEqualTo(2_000);
        clock.addAndGet(1);
        assertThat(deadline.remainingMillis()).isEqualTo(2_000);
        clock.addAndGet(999_999);
        assertThat(deadline.remainingMillis()).isEqualTo(1_999);
        clock.set(50_000_000L + 2_000_000_000L - 1);
        assertThat(deadline.remainingMillis()).isEqualTo(1);
    }

    @Test
    void rejectsTheExactDeadlineAndAllLaterObservations() {
        AtomicLong clock = new AtomicLong(-100L);
        LegalPrivateRequirementsDeadline deadline = new LegalPrivateRequirementsDeadline(
                Duration.ofSeconds(2), clock::get);

        clock.addAndGet(2_000_000_000L);
        assertThatThrownBy(deadline::remainingMillis)
                .isInstanceOf(LegalPrivateRequirementsReadException.class);
        assertThatThrownBy(deadline::check)
                .isInstanceOf(LegalPrivateRequirementsReadException.class);
        clock.incrementAndGet();
        assertThatThrownBy(deadline::remainingMillis)
                .isInstanceOf(LegalPrivateRequirementsReadException.class);
    }

    @Test
    void repeatedChecksShareTheOriginalBudgetAcrossPhases() {
        AtomicLong clock = new AtomicLong();
        LegalPrivateRequirementsDeadline deadline = new LegalPrivateRequirementsDeadline(
                Duration.ofSeconds(2), clock::get);

        clock.addAndGet(400_000_000L);
        deadline.check();
        assertThat(deadline.remainingMillis()).isEqualTo(1_600);
        clock.addAndGet(600_000_000L);
        deadline.check();
        assertThat(deadline.remainingMillis()).isEqualTo(1_000);
        clock.addAndGet(999_000_000L);
        deadline.check();
        assertThat(deadline.remainingMillis()).isEqualTo(1);
        clock.addAndGet(1_000_000L);
        assertThatThrownBy(deadline::check)
                .isInstanceOf(LegalPrivateRequirementsReadException.class);
    }

    @Test
    void supportsNanoTimeSignedOverflowDuringAShortObservation() {
        AtomicLong clock = new AtomicLong(Long.MAX_VALUE - 500_000_000L);
        LegalPrivateRequirementsDeadline deadline = new LegalPrivateRequirementsDeadline(
                Duration.ofSeconds(2), clock::get);

        clock.addAndGet(750_000_000L);
        assertThat(clock.get()).isNegative();
        assertThat(deadline.remainingMillis()).isEqualTo(1_250);
        clock.addAndGet(1_250_000_000L);
        assertThatThrownBy(deadline::check)
                .isInstanceOf(LegalPrivateRequirementsReadException.class);
    }

    @Test
    void interruptionFailsClosedWithoutClearingTheInterruptFlag() {
        boolean wasInterrupted = Thread.interrupted();
        try {
            LegalPrivateRequirementsDeadline deadline = new LegalPrivateRequirementsDeadline(
                    Duration.ofSeconds(2), () -> 0L);
            Thread.currentThread().interrupt();

            assertThatThrownBy(deadline::remainingMillis)
                    .isInstanceOf(LegalPrivateRequirementsReadException.class);
            assertThatThrownBy(deadline::check)
                    .isInstanceOf(LegalPrivateRequirementsReadException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
            if (wasInterrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Test
    void cancellationRemainsAvailableAfterTheBudgetExpires() throws SQLException {
        AtomicLong clock = new AtomicLong();
        LegalPrivateRequirementsDeadline deadline = new LegalPrivateRequirementsDeadline(
                Duration.ofSeconds(2), clock::get);
        Statement statement = mock(Statement.class);
        clock.set(2_000_000_000L);

        deadline.cancel(null);
        deadline.cancel(statement);

        verify(statement).cancel();
        verifyNoMoreInteractions(statement);
        assertThatThrownBy(deadline::check)
                .isInstanceOf(LegalPrivateRequirementsReadException.class);
    }

    @Test
    void cancellationFailureDoesNotReplaceThePrimaryReadFailure() throws SQLException {
        LegalPrivateRequirementsDeadline deadline = new LegalPrivateRequirementsDeadline(
                Duration.ofSeconds(2), () -> 0L);
        for (Exception failure : List.of(new SQLException("driver cancellation failed"),
                new IllegalStateException("connection already aborted"))) {
            Statement statement = mock(Statement.class);
            doThrow(failure).when(statement).cancel();

            assertThatCode(() -> deadline.cancel(statement)).doesNotThrowAnyException();

            verify(statement).cancel();
            verifyNoMoreInteractions(statement);
        }
    }
}
