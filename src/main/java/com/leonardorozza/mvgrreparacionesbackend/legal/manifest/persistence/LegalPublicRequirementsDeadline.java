package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/** Monotonic budget beginning before connection acquisition, never reset by a new phase/FETCH. */
final class LegalPublicRequirementsDeadline {

    private final LongSupplier clock;
    private final long startedAt;
    private final long budgetNanos;
    private final AtomicReference<Throwable> cleanupFailure = new AtomicReference<>();

    LegalPublicRequirementsDeadline(Duration budget) {
        this(budget, System::nanoTime);
    }

    LegalPublicRequirementsDeadline(Duration budget, LongSupplier clock) {
        Objects.requireNonNull(budget, "budget");
        if (budget.isNegative() || budget.isZero() || budget.compareTo(Duration.ofSeconds(15)) > 0) {
            throw new IllegalArgumentException("El presupuesto de requisitos debe ser positivo y hasta 15 s");
        }
        this.clock = Objects.requireNonNull(clock, "clock");
        this.budgetNanos = budget.toNanos();
        this.startedAt = clock.getAsLong();
    }

    void check() {
        remainingMillis();
    }

    int remainingMillis() {
        Throwable failure = cleanupFailure.get();
        if (failure != null) {
            throw new LegalPublicRequirementsReadException(failure);
        }
        long remaining = budgetNanos - (clock.getAsLong() - startedAt);
        if (remaining <= 0 || Thread.currentThread().isInterrupted()) {
            throw new LegalPublicRequirementsReadException();
        }
        return Math.toIntExact((remaining + 999_999L) / 1_000_000L);
    }

    /** Spring may absorb release errors; the owning operation must still reject delivery. */
    void recordCleanupFailure(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        if (!cleanupFailure.compareAndSet(null, failure)) {
            Throwable first = cleanupFailure.get();
            if (first != failure) {
                first.addSuppressed(failure);
            }
        }
    }

    /** Cleanup must not replace the primary failure; driver cancellation has its own timeout. */
    void cancel(Statement statement) {
        if (statement != null) {
            try {
                statement.cancel();
            } catch (SQLException | RuntimeException ignored) {
                // The observation already fails closed; the owning scope still closes resources.
            }
        }
    }
}
