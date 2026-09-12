package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Technical deadlines for a future confirmed workshop closure. These durations are not legal
 * retention rules, and deletionExpectedBy does not accredit that any deletion was executed.
 * Preview/preparation must not establish the confirmation instant or start these windows.
 */
public final class WorkshopClosurePolicy {
    public static final String POLICY_VERSION = "ordenfix-cierre/1";
    public static final Duration RESTORATION_WINDOW = Duration.ofDays(7);
    public static final Duration PROCESSING_WINDOW = Duration.ofDays(30);

    private WorkshopClosurePolicy() { }

    /** UTC elapsed durations, normalized to the precision of future PostgreSQL persistence. */
    public static Schedule scheduleAt(Instant confirmedAt) {
        if (confirmedAt == null) throw invalid();
        try {
            Instant confirmed = confirmedAt.truncatedTo(ChronoUnit.MICROS);
            Instant reversible = confirmed.plus(RESTORATION_WINDOW);
            return new Schedule(confirmed, reversible, reversible.plus(PROCESSING_WINDOW));
        } catch (DateTimeException | ArithmeticException failure) {
            throw invalid();
        }
    }

    public record Schedule(Instant confirmedAt, Instant reversibleUntil, Instant deletionExpectedBy) {
        public Schedule {
            if (confirmedAt == null || reversibleUntil == null || deletionExpectedBy == null) throw invalid();
            try {
                if (!confirmedAt.equals(confirmedAt.truncatedTo(ChronoUnit.MICROS))
                        || !reversibleUntil.equals(confirmedAt.plus(RESTORATION_WINDOW))
                        || !deletionExpectedBy.equals(reversibleUntil.plus(PROCESSING_WINDOW))) {
                    throw invalid();
                }
            } catch (DateTimeException | ArithmeticException failure) {
                throw invalid();
            }
        }

        /** The confirmation instant is included; the end of the grace period is excluded. */
        public boolean canRestoreAt(Instant now) {
            if (now == null) throw invalid();
            return !now.isBefore(confirmedAt) && now.isBefore(reversibleUntil);
        }

        public boolean graceExpiredAt(Instant now) {
            if (now == null) throw invalid();
            return !now.isBefore(reversibleUntil);
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Los plazos de cierre del taller no son válidos.");
    }
}
