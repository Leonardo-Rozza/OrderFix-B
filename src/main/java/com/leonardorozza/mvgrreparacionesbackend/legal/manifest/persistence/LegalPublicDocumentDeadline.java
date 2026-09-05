package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Monotonic budget beginning before connection acquisition, never reset by a new phase/FETCH. */
final class LegalPublicDocumentDeadline {

    private final LongSupplier clock;
    private final long startedAt;
    private final long budgetNanos;

    LegalPublicDocumentDeadline(Duration budget) {
        this(budget, System::nanoTime);
    }

    LegalPublicDocumentDeadline(Duration budget, LongSupplier clock) {
        Objects.requireNonNull(budget, "budget");
        if (budget.isNegative() || budget.isZero() || budget.compareTo(Duration.ofSeconds(15)) > 0) {
            throw new IllegalArgumentException("El presupuesto documental debe ser positivo y hasta 15 s");
        }
        this.clock = Objects.requireNonNull(clock, "clock");
        this.budgetNanos = budget.toNanos();
        this.startedAt = clock.getAsLong();
    }

    void check() {
        remainingMillis();
    }

    int remainingMillis() {
        long remaining = budgetNanos - (clock.getAsLong() - startedAt);
        if (remaining <= 0 || Thread.currentThread().isInterrupted()) {
            throw new LegalPublicDocumentReadException();
        }
        return Math.toIntExact((remaining + 999_999L) / 1_000_000L);
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
