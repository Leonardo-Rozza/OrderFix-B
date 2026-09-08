package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import java.util.Objects;
import java.util.function.LongSupplier;

/** One fixed, monotonic registration budget, explicitly shared across operation phases. */
public final class LegalRegistrationBudget {
    private static final long BUDGET_NANOS = 30_000_000_000L;
    private static final long NANOS_PER_MILLI = 1_000_000L;
    private final LongSupplier clock;
    private final long startedAt;
    private long lastObserved;
    private boolean unavailable;
    private Throwable cleanupFailure;

    private LegalRegistrationBudget(LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.startedAt = clock.getAsLong();
        this.lastObserved = startedAt;
    }

    public static LegalRegistrationBudget start() {
        return start(System::nanoTime);
    }

    /** Test seam; callers cannot choose a duration or restart an existing owner. */
    static LegalRegistrationBudget start(LongSupplier clock) {
        return new LegalRegistrationBudget(clock);
    }

    public void check() {
        remainingMillis();
    }

    public synchronized int remainingMillis() {
        if (cleanupFailure != null || unavailable || Thread.currentThread().isInterrupted()) {
            unavailable = true;
            throw new UnavailableException(cleanupFailure);
        }
        final long now;
        try {
            now = clock.getAsLong();
        } catch (RuntimeException invalidClock) {
            unavailable = true;
            throw new UnavailableException(null);
        }
        // Subtraction supports nanoTime's signed wrap over this short, fixed interval.
        long sinceLastObservation = now - lastObserved;
        long elapsed = now - startedAt;
        if (sinceLastObservation < 0 || elapsed < 0 || elapsed >= BUDGET_NANOS) {
            unavailable = true;
            throw new UnavailableException(null);
        }
        lastObserved = now;
        long remaining = BUDGET_NANOS - elapsed;
        return Math.toIntExact(1 + (remaining - 1) / NANOS_PER_MILLI);
    }

    /** Preserve cleanup failures even when a transaction framework absorbs resource-release errors. */
    public synchronized void recordCleanupFailure(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        unavailable = true;
        if (cleanupFailure == null) {
            cleanupFailure = failure;
        } else if (cleanupFailure != failure) {
            cleanupFailure.addSuppressed(failure);
        }
    }

    @Override
    public String toString() {
        return "LegalRegistrationBudget[redacted]";
    }

    public static final class UnavailableException extends RuntimeException {
        private UnavailableException(Throwable cause) {
            super("El presupuesto de registro no está disponible", cause);
        }

        @Override
        public String toString() {
            return "LegalRegistrationBudget.UnavailableException: El presupuesto de registro no está disponible";
        }
    }
}
