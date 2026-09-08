package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Independent clock epochs make resetting or conflating either budget observable. */
class LegalPublicRequirementsBudgetTest {
    @ParameterizedTest
    @CsvSource({
            "0,15000,0,15000", "10000,15000,0,15000", "20000,15000,0,10000",
            "28000,15000,0,2000", "0,2000,500,1500", "28000,15000,14000,1000",
            "20000,15000,11000,4000", "29000,500,0,500"
    })
    void takesTheMinimumOfIndependentLocalAndPreviouslyConsumedOwnerTime(
            long spentOwnerMillis, long localBudgetMillis, long spentLocalMillis, int expected) {
        var globalClock = new AtomicLong(-90_000_000_000L);
        var localClock = new AtomicLong(700_000_000_000L);
        var owner = LegalRegistrationBudget.start(globalClock::get);
        globalClock.addAndGet(spentOwnerMillis * 1_000_000L);
        var deadline = LegalPublicRequirementsDeadline.adoptRegistrationBudget(
                owner, Duration.ofMillis(localBudgetMillis), localClock::get);
        localClock.addAndGet(spentLocalMillis * 1_000_000L);

        assertThat(deadline.remainingMillis()).isEqualTo(expected);
        assertThat(owner.remainingMillis()).isEqualTo(Math.toIntExact(30_000 - spentOwnerMillis));
        assertThat(deadline.usesRegistrationBudget(owner)).isTrue();
        assertThat(deadline.usesRegistrationBudget(null)).isFalse();
        assertThat(deadline.usesRegistrationBudget(LegalRegistrationBudget.start(() -> 0L))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void roundsEachPositiveFractionWithoutAcceptingEitherExactDeadline(boolean ownerExpiresFirst) {
        var globalClock = new AtomicLong();
        var localClock = new AtomicLong(500_000_000_000L);
        var owner = LegalRegistrationBudget.start(globalClock::get);
        var deadline = LegalPublicRequirementsDeadline.adoptRegistrationBudget(owner, Duration.ofSeconds(2), localClock::get);
        if (ownerExpiresFirst) globalClock.set(29_999_999_999L);
        else localClock.addAndGet(1_999_999_999L);
        assertThat(deadline.remainingMillis()).isEqualTo(1);
        if (ownerExpiresFirst) globalClock.incrementAndGet();
        else localClock.incrementAndGet();
        assertUnavailable(deadline);
        if (ownerExpiresFirst) assertThatThrownBy(owner::check).isExactlyInstanceOf(LegalRegistrationBudget.UnavailableException.class);
        else assertThat(owner.remainingMillis()).isEqualTo(30_000);
    }

    @Test
    void everyObservationChargesBothOriginalClocksInsteadOfOpeningANewBudget() {
        var globalClock = new AtomicLong();
        var localClock = new AtomicLong(400_000_000_000L);
        var owner = LegalRegistrationBudget.start(globalClock::get);
        globalClock.set(12_000_000_000L);
        var deadline = LegalPublicRequirementsDeadline.adoptRegistrationBudget(owner, Duration.ofSeconds(15), localClock::get);
        assertThat(deadline.remainingMillis()).isEqualTo(15_000);
        globalClock.addAndGet(4_000_000_000L);
        localClock.addAndGet(4_000_000_000L);
        assertThat(deadline.remainingMillis()).isEqualTo(11_000);
        globalClock.addAndGet(7_000_000_000L);
        localClock.addAndGet(7_000_000_000L);
        assertThat(deadline.remainingMillis()).isEqualTo(4_000);
        assertThat(owner.remainingMillis()).isEqualTo(7_000);
    }

    @Test
    void localExpiryIsTerminalForThatObservationWithoutPretendingTheOwnerExpired() {
        var localClock = new AtomicLong();
        var owner = LegalRegistrationBudget.start(() -> 0L);
        var deadline = LegalPublicRequirementsDeadline.adoptRegistrationBudget(owner, Duration.ofSeconds(2), localClock::get);
        localClock.set(2_000_000_000L);
        assertUnavailable(deadline);
        localClock.set(0);
        assertUnavailable(deadline);
        assertThat(owner.remainingMillis()).isEqualTo(30_000);
        var nextObservation = LegalPublicRequirementsDeadline.adoptRegistrationBudget(owner, Duration.ofSeconds(2), localClock::get);
        assertThat(nextObservation.remainingMillis()).isEqualTo(2_000);
    }

    @Test
    void aNewPublicObservationCanResetOnlyItsLocalCapAndNeverTheOriginalOwner() {
        var globalClock = new AtomicLong();
        var firstClock = new AtomicLong(100_000_000_000L);
        var owner = LegalRegistrationBudget.start(globalClock::get);
        var first = LegalPublicRequirementsDeadline.adoptRegistrationBudget(owner, Duration.ofSeconds(15), firstClock::get);
        globalClock.set(14_000_000_000L);
        firstClock.addAndGet(14_000_000_000L);
        assertThat(first.remainingMillis()).isEqualTo(1_000);
        globalClock.set(22_000_000_000L);
        var secondClock = new AtomicLong(-800_000_000_000L);
        var second = LegalPublicRequirementsDeadline.adoptRegistrationBudget(owner, Duration.ofSeconds(15), secondClock::get);
        assertThat(second.remainingMillis()).isEqualTo(8_000);
        assertThat(LegalPrivateRequirementsDeadline.adoptRegistrationBudget(owner).remainingMillis()).isEqualTo(8_000);
    }

    @Test
    void localClockRegressionBetweenObservationsCannotIncreaseAnAdoptedCap() {
        var localClock = new AtomicLong();
        var owner = LegalRegistrationBudget.start(() -> 0L);
        var deadline = LegalPublicRequirementsDeadline.adoptRegistrationBudget(owner, Duration.ofSeconds(15), localClock::get);
        localClock.set(8_000_000_000L);
        assertThat(deadline.remainingMillis()).isEqualTo(7_000);
        localClock.decrementAndGet();
        assertUnavailable(deadline);
        localClock.set(9_000_000_000L);
        assertUnavailable(deadline);
        assertThat(owner.remainingMillis()).isEqualTo(30_000);
    }

    @ParameterizedTest
    @ValueSource(longs = {-1L, Long.MIN_VALUE})
    void localRegressionBeforeTheStartOrAmbiguousOverflowCannotGrantMoreTime(long invalidTime) {
        var localClock = new AtomicLong();
        var owner = LegalRegistrationBudget.start(() -> 0L);
        var deadline = LegalPublicRequirementsDeadline.adoptRegistrationBudget(owner, Duration.ofSeconds(15), localClock::get);
        localClock.set(invalidTime);
        assertUnavailable(deadline);
        localClock.set(1);
        assertUnavailable(deadline);
        owner.check();
    }

    @Test
    void independentShortSignedWrapsPreserveBothRemainingBudgets() {
        var globalClock = new AtomicLong(Long.MAX_VALUE - 10_000_000_000L);
        var localClock = new AtomicLong(Long.MAX_VALUE - 2_000_000_000L);
        var owner = LegalRegistrationBudget.start(globalClock::get);
        globalClock.addAndGet(16_000_000_000L);
        var deadline = LegalPublicRequirementsDeadline.adoptRegistrationBudget(owner, Duration.ofSeconds(15), localClock::get);
        localClock.addAndGet(6_000_000_000L);
        globalClock.addAndGet(6_000_000_000L);
        assertThat(globalClock.get()).isNegative();
        assertThat(localClock.get()).isNegative();
        assertThat(deadline.remainingMillis()).isEqualTo(8_000);
        localClock.addAndGet(8_000_000_000L);
        globalClock.addAndGet(8_000_000_000L);
        assertUnavailable(deadline);
    }

    @Test
    void ownerExpiryIsMappedToThePublicFailureEvenWhileTheLocalCapIsFresh() {
        var globalClock = new AtomicLong();
        var owner = LegalRegistrationBudget.start(globalClock::get);
        var deadline = LegalPublicRequirementsDeadline.adoptRegistrationBudget(owner, Duration.ofSeconds(15), () -> 0L);
        globalClock.set(30_000_000_000L);
        assertUnavailable(deadline);
        globalClock.set(0);
        assertUnavailable(deadline);
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "owner"})
    void aCleanupRecordedAfterEitherExpiryStillExposesItsOriginalCause(String expired) {
        var globalClock = new AtomicLong();
        var localClock = new AtomicLong();
        var owner = LegalRegistrationBudget.start(globalClock::get);
        var deadline = LegalPublicRequirementsDeadline.adoptRegistrationBudget(owner, Duration.ofSeconds(15), localClock::get);
        if (expired.equals("owner")) globalClock.set(30_000_000_000L);
        else localClock.set(15_000_000_000L);
        assertUnavailable(deadline);
        var first = new SQLException("synthetic first release", "08006");
        var second = new IllegalStateException("synthetic second release");
        deadline.recordCleanupFailure(first);
        owner.recordCleanupFailure(second);
        assertThatThrownBy(deadline::check).isExactlyInstanceOf(LegalPublicRequirementsReadException.class).hasCause(first);
        assertThatThrownBy(owner::check).isExactlyInstanceOf(LegalRegistrationBudget.UnavailableException.class).hasCause(first);
        assertThat(first.getSuppressed()).containsExactly(second);
    }

    @Test
    void interruptionRemainsSetAndTerminalForAllAdaptersOfTheOwner() {
        boolean wasInterrupted = Thread.interrupted();
        var owner = LegalRegistrationBudget.start(() -> 0L);
        var deadline = LegalPublicRequirementsDeadline.adoptRegistrationBudget(owner, Duration.ofSeconds(15), () -> 0L);
        try {
            Thread.currentThread().interrupt();
            assertUnavailable(deadline);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
            if (wasInterrupted) Thread.currentThread().interrupt();
        }
        assertUnavailable(deadline);
        assertThatThrownBy(owner::check).isExactlyInstanceOf(LegalRegistrationBudget.UnavailableException.class);
    }

    @Test
    void aFailingLocalClockMakesOnlyTheAdoptedObservationTerminal() {
        var reads = new AtomicInteger();
        var owner = LegalRegistrationBudget.start(() -> 0L);
        var deadline = LegalPublicRequirementsDeadline.adoptRegistrationBudget(owner, Duration.ofSeconds(15), () -> {
            if (reads.incrementAndGet() == 2) throw new IllegalStateException("synthetic local clock failure");
            return 0L;
        });
        assertUnavailable(deadline);
        assertUnavailable(deadline);
        assertThat(reads).hasValue(2);
        assertThat(owner.remainingMillis()).isEqualTo(30_000);
    }

    @Test
    void adoptedConstructionKeepsTheOriginalLocalBoundsAndRequiresAnExplicitOwner() {
        var owner = LegalRegistrationBudget.start(() -> 0L);
        assertThat(LegalPublicRequirementsDeadline.adoptRegistrationBudget(owner, Duration.ofNanos(1), () -> 0L)
                .remainingMillis()).isEqualTo(1);
        for (var invalid : List.of(Duration.ZERO, Duration.ofNanos(-1), Duration.ofSeconds(15).plusNanos(1))) {
            assertThatThrownBy(() -> LegalPublicRequirementsDeadline.adoptRegistrationBudget(owner, invalid, () -> 0L))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> LegalPublicRequirementsDeadline.adoptRegistrationBudget(null, Duration.ofSeconds(15), () -> 0L))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> LegalPublicRequirementsDeadline.adoptRegistrationBudget(owner, null, () -> 0L))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> LegalPublicRequirementsDeadline.adoptRegistrationBudget(owner, Duration.ofSeconds(15), null))
                .isInstanceOf(NullPointerException.class);
        assertThat(new LegalPublicRequirementsDeadline(Duration.ofSeconds(15), () -> 0L)
                .usesRegistrationBudget(owner)).isFalse();
    }

    private static void assertUnavailable(LegalPublicRequirementsDeadline deadline) {
        assertThatThrownBy(deadline::remainingMillis).isExactlyInstanceOf(LegalPublicRequirementsReadException.class)
                .hasMessage("El contrato de requisitos no está disponible").hasNoCause();
    }
}
