package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.LocalDateTime;
import java.util.stream.Stream;

import static com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.WorkshopClosurePolicy.Schedule;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkshopClosurePolicyTest {
    private static final Instant CONFIRMED = Instant.parse("2026-09-12T15:13:14.123456Z");
    private static final Instant REVERSIBLE = Instant.parse("2026-09-19T15:13:14.123456Z");
    private static final Instant EXPECTED = Instant.parse("2026-10-19T15:13:14.123456Z");

    @Test void confirmationIsNormalizedBeforeComputingBothDeadlines() {
        Schedule schedule = WorkshopClosurePolicy.scheduleAt(Instant.parse("2026-09-12T15:13:14.123456789Z"));

        assertThat(WorkshopClosurePolicy.POLICY_VERSION).isEqualTo("ordenfix-cierre/1");
        assertThat(schedule.confirmedAt()).isEqualTo(CONFIRMED);
        assertThat(schedule.reversibleUntil()).isEqualTo(REVERSIBLE);
        assertThat(schedule.deletionExpectedBy()).isEqualTo(EXPECTED);
        assertThat(new Schedule(CONFIRMED, REVERSIBLE, EXPECTED)).isEqualTo(schedule);
    }

    @Test void normalizationAlsoWorksForInstantsBeforeTheEpoch() {
        Schedule schedule = WorkshopClosurePolicy.scheduleAt(Instant.parse("1969-12-31T23:59:59.999999999Z"));

        assertThat(schedule.confirmedAt()).isEqualTo(Instant.parse("1969-12-31T23:59:59.999999Z"));
        assertThat(schedule.reversibleUntil()).isEqualTo(Instant.parse("1970-01-07T23:59:59.999999Z"));
        assertThat(schedule.deletionExpectedBy()).isEqualTo(Instant.parse("1970-02-06T23:59:59.999999Z"));
    }

    @Test void restorationIncludesConfirmationButExcludesTheExactEndOfGrace() {
        Schedule schedule = WorkshopClosurePolicy.scheduleAt(CONFIRMED);

        assertThat(schedule.canRestoreAt(CONFIRMED.minusNanos(1))).isFalse();
        assertThat(schedule.canRestoreAt(CONFIRMED)).isTrue();
        assertThat(schedule.canRestoreAt(REVERSIBLE.minusNanos(1))).isTrue();
        assertThat(schedule.canRestoreAt(REVERSIBLE)).isFalse();
        assertThat(schedule.canRestoreAt(REVERSIBLE.plusNanos(1))).isFalse();
        assertThat(schedule.canRestoreAt(EXPECTED)).isFalse();
    }

    @Test void graceExpiresExactlyAtItsDeadlineIndependentlyOfProcessingDeadline() {
        Schedule schedule = WorkshopClosurePolicy.scheduleAt(CONFIRMED);

        assertThat(schedule.graceExpiredAt(CONFIRMED)).isFalse();
        assertThat(schedule.graceExpiredAt(REVERSIBLE.minusNanos(1))).isFalse();
        assertThat(schedule.graceExpiredAt(REVERSIBLE)).isTrue();
        assertThat(schedule.graceExpiredAt(REVERSIBLE.plusNanos(1))).isTrue();
        assertThat(schedule.graceExpiredAt(EXPECTED)).isTrue();
        assertThat(schedule.graceExpiredAt(EXPECTED.plusSeconds(1))).isTrue();
    }

    @Test void clockMovingBeforeConfirmationDoesNotEnableRestorationOrAccreditExpiration() {
        Schedule schedule = WorkshopClosurePolicy.scheduleAt(CONFIRMED);
        Instant earlier = CONFIRMED.minus(Duration.ofDays(2));

        assertThat(schedule.canRestoreAt(earlier)).isFalse();
        assertThat(schedule.graceExpiredAt(earlier)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"2026-03-05T12:00:00,13", "2026-10-29T12:00:00,11"})
    void deadlinesUseElapsedUtcDaysAcrossBothDaylightSavingChanges(String local, int expectedLocalHour) {
        ZoneId zone = ZoneId.of("America/New_York");
        Instant confirmed = LocalDateTime.parse(local).atZone(zone).toInstant();
        Schedule schedule = WorkshopClosurePolicy.scheduleAt(confirmed);

        assertThat(Duration.between(confirmed, schedule.reversibleUntil())).isEqualTo(Duration.ofHours(168));
        assertThat(Duration.between(schedule.reversibleUntil(), schedule.deletionExpectedBy())).isEqualTo(Duration.ofHours(720));
        assertThat(schedule.reversibleUntil().atZone(zone).getHour()).isEqualTo(expectedLocalHour);
    }

    @ParameterizedTest @MethodSource("invalidSchedules")
    void publicConstructorRejectsNullsNonMicrosecondPrecisionAndAlteredWindows(ScheduleInput input) {
        invalid(() -> new Schedule(input.confirmed(), input.reversible(), input.expected()));
    }

    private static Stream<ScheduleInput> invalidSchedules() {
        return Stream.of(
                new ScheduleInput(null, REVERSIBLE, EXPECTED),
                new ScheduleInput(CONFIRMED, null, EXPECTED),
                new ScheduleInput(CONFIRMED, REVERSIBLE, null),
                new ScheduleInput(CONFIRMED.plusNanos(1), REVERSIBLE.plusNanos(1), EXPECTED.plusNanos(1)),
                new ScheduleInput(CONFIRMED, REVERSIBLE.minusNanos(1), EXPECTED),
                new ScheduleInput(CONFIRMED, REVERSIBLE.plusNanos(1), EXPECTED),
                new ScheduleInput(CONFIRMED, REVERSIBLE, EXPECTED.minusNanos(1)),
                new ScheduleInput(CONFIRMED, REVERSIBLE, EXPECTED.plusNanos(1)),
                new ScheduleInput(CONFIRMED, CONFIRMED, EXPECTED),
                new ScheduleInput(CONFIRMED, REVERSIBLE, CONFIRMED));
    }

    @Test void nullInputsFailWithTheSameSanitizedError() {
        Schedule schedule = WorkshopClosurePolicy.scheduleAt(CONFIRMED);

        invalid(() -> WorkshopClosurePolicy.scheduleAt(null));
        invalid(() -> schedule.canRestoreAt(null));
        invalid(() -> schedule.graceExpiredAt(null));
    }

    @Test void overflowOfEitherWindowIsSanitizedWithoutClippingTheDeadlines() {
        invalid(() -> WorkshopClosurePolicy.scheduleAt(Instant.MAX));
        // Grace fits, but the processing deadline would overflow.
        Instant late = Instant.MAX.minus(Duration.ofDays(8));
        invalid(() -> WorkshopClosurePolicy.scheduleAt(late));
        Instant micro = Instant.MAX.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        invalid(() -> new Schedule(micro, micro, micro));
        Instant confirmation = micro.minus(Duration.ofDays(8));
        invalid(() -> new Schedule(confirmation, confirmation.plus(Duration.ofDays(7)), micro));
    }

    private static void invalid(Runnable operation) {
        assertThatThrownBy(operation::run).isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("Los plazos de cierre del taller no son válidos.")
                .hasNoCause().satisfies(failure -> assertThat(failure.getSuppressed()).isEmpty());
    }

    private record ScheduleInput(Instant confirmed, Instant reversible, Instant expected) { }
}
