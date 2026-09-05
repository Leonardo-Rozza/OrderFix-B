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

class LegalPublicDocumentDeadlineTest {

    @Test
    void acceptsTheInclusiveBudgetBounds() {
        assertThat(new LegalPublicDocumentDeadline(Duration.ofNanos(1), () -> 0L)
                .remainingMillis()).isEqualTo(1);
        assertThat(new LegalPublicDocumentDeadline(Duration.ofSeconds(15), () -> 0L)
                .remainingMillis()).isEqualTo(15_000);
    }

    @Test
    void rejectsMissingNonPositiveOrExcessiveBudgets() {
        assertThatThrownBy(() -> new LegalPublicDocumentDeadline(null, () -> 0L))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LegalPublicDocumentDeadline(Duration.ofSeconds(2), null))
                .isInstanceOf(NullPointerException.class);
        for (Duration invalid : List.of(Duration.ZERO, Duration.ofNanos(-1),
                Duration.ofSeconds(15).plusNanos(1), Duration.ofSeconds(Long.MAX_VALUE))) {
            assertThatThrownBy(() -> new LegalPublicDocumentDeadline(invalid, () -> 0L))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void roundsPositiveFractionsUpWithoutGrantingAnExtraMillisecond() {
        AtomicLong clock = new AtomicLong(50_000_000L);
        LegalPublicDocumentDeadline deadline = new LegalPublicDocumentDeadline(
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
        LegalPublicDocumentDeadline deadline = new LegalPublicDocumentDeadline(
                Duration.ofSeconds(2), clock::get);

        clock.addAndGet(2_000_000_000L);
        assertThatThrownBy(deadline::remainingMillis)
                .isInstanceOf(LegalPublicDocumentReadException.class);
        assertThatThrownBy(deadline::check)
                .isInstanceOf(LegalPublicDocumentReadException.class);
        clock.incrementAndGet();
        assertThatThrownBy(deadline::remainingMillis)
                .isInstanceOf(LegalPublicDocumentReadException.class);
    }

    @Test
    void repeatedChecksShareTheOriginalBudgetAcrossPhases() {
        AtomicLong clock = new AtomicLong();
        LegalPublicDocumentDeadline deadline = new LegalPublicDocumentDeadline(
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
                .isInstanceOf(LegalPublicDocumentReadException.class);
    }

    @Test
    void supportsNanoTimeSignedOverflowDuringAShortObservation() {
        AtomicLong clock = new AtomicLong(Long.MAX_VALUE - 500_000_000L);
        LegalPublicDocumentDeadline deadline = new LegalPublicDocumentDeadline(
                Duration.ofSeconds(2), clock::get);

        clock.addAndGet(750_000_000L);
        assertThat(clock.get()).isNegative();
        assertThat(deadline.remainingMillis()).isEqualTo(1_250);
        clock.addAndGet(1_250_000_000L);
        assertThatThrownBy(deadline::check)
                .isInstanceOf(LegalPublicDocumentReadException.class);
    }

    @Test
    void interruptionFailsClosedWithoutClearingTheInterruptFlag() {
        boolean wasInterrupted = Thread.interrupted();
        try {
            LegalPublicDocumentDeadline deadline = new LegalPublicDocumentDeadline(
                    Duration.ofSeconds(2), () -> 0L);
            Thread.currentThread().interrupt();

            assertThatThrownBy(deadline::remainingMillis)
                    .isInstanceOf(LegalPublicDocumentReadException.class);
            assertThatThrownBy(deadline::check)
                    .isInstanceOf(LegalPublicDocumentReadException.class);
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
        LegalPublicDocumentDeadline deadline = new LegalPublicDocumentDeadline(
                Duration.ofSeconds(2), clock::get);
        Statement statement = mock(Statement.class);
        clock.set(2_000_000_000L);

        deadline.cancel(null);
        deadline.cancel(statement);

        verify(statement).cancel();
        verifyNoMoreInteractions(statement);
        assertThatThrownBy(deadline::check)
                .isInstanceOf(LegalPublicDocumentReadException.class);
    }

    @Test
    void cancellationFailureDoesNotReplaceThePrimaryReadFailure() throws SQLException {
        LegalPublicDocumentDeadline deadline = new LegalPublicDocumentDeadline(
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
