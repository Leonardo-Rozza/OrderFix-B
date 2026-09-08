package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/** Monotonic budget beginning before connection acquisition, never reset by a new phase/FETCH. */
final class LegalPrivateRequirementsDeadline {

    private final LongSupplier clock;
    private final long startedAt;
    private final long budgetNanos;
    private final LegalRegistrationBudget registrationBudget;
    private final AtomicReference<Throwable> cleanupFailure = new AtomicReference<>();

    LegalPrivateRequirementsDeadline(Duration budget) {
        this(budget, System::nanoTime);
    }

    LegalPrivateRequirementsDeadline(Duration budget, LongSupplier clock) {
        this(historicalBudgetNanos(budget), clock);
    }

    /** Only the nominal registration path can open a thirty-second operation. */
    static LegalPrivateRequirementsDeadline registration(LongSupplier clock) {
        return adoptRegistrationBudget(LegalRegistrationBudget.start(clock));
    }

    /** Adopts the original owner without sampling another clock or opening a new budget. */
    static LegalPrivateRequirementsDeadline adoptRegistrationBudget(LegalRegistrationBudget owner) {
        return new LegalPrivateRequirementsDeadline(Objects.requireNonNull(owner, "owner"));
    }

    private LegalPrivateRequirementsDeadline(LegalRegistrationBudget owner) {
        this.registrationBudget = owner;
        this.clock = null;
        this.budgetNanos = 0;
        this.startedAt = 0;
    }

    boolean usesRegistrationBudget(LegalRegistrationBudget owner) {
        return registrationBudget != null && registrationBudget == owner;
    }

    private LegalPrivateRequirementsDeadline(long budgetNanos, LongSupplier clock) {
        this.registrationBudget = null;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.budgetNanos = budgetNanos;
        this.startedAt = clock.getAsLong();
    }

    private static long historicalBudgetNanos(Duration budget) {
        Objects.requireNonNull(budget, "budget");
        if (budget.isNegative() || budget.isZero() || budget.compareTo(Duration.ofSeconds(15)) > 0) {
            throw new IllegalArgumentException("El presupuesto de requisitos debe ser positivo y hasta 15 s");
        }
        return budget.toNanos();
    }

    void check() {
        remainingMillis();
    }

    int remainingMillis() {
        if (registrationBudget != null) {
            try {
                return registrationBudget.remainingMillis();
            } catch (LegalRegistrationBudget.UnavailableException unavailable) {
                // Keep the original cleanup cause; expiry retains the historical null cause.
                throw new LegalPrivateRequirementsReadException(unavailable.getCause());
            }
        }
        Throwable failure = cleanupFailure.get();
        if (failure != null) {
            throw new LegalPrivateRequirementsReadException(failure);
        }
        long remaining = budgetNanos - (clock.getAsLong() - startedAt);
        if (remaining <= 0 || Thread.currentThread().isInterrupted()) {
            throw new LegalPrivateRequirementsReadException();
        }
        return Math.toIntExact((remaining + 999_999L) / 1_000_000L);
    }

    /** Spring may absorb release errors; the owning operation must still reject delivery. */
    void recordCleanupFailure(Throwable failure) {
        if (registrationBudget != null) {
            registrationBudget.recordCleanupFailure(failure);
            return;
        }
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
