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
    private final LegalRegistrationBudget registrationBudget;
    private long lastObserved;
    private boolean localUnavailable;
    private final AtomicReference<Throwable> cleanupFailure = new AtomicReference<>();

    LegalPublicRequirementsDeadline(Duration budget) {
        this(budget, System::nanoTime);
    }

    LegalPublicRequirementsDeadline(Duration budget, LongSupplier clock) {
        this(budget, clock, null);
    }

    /** A new public scope keeps its own cap and observes the original registration owner. */
    static LegalPublicRequirementsDeadline adoptRegistrationBudget(LegalRegistrationBudget owner,
            Duration localBudget, LongSupplier clock) {
        return new LegalPublicRequirementsDeadline(localBudget, clock, Objects.requireNonNull(owner, "owner"));
    }

    private LegalPublicRequirementsDeadline(Duration budget, LongSupplier clock, LegalRegistrationBudget owner) {
        Objects.requireNonNull(budget, "budget");
        if (budget.isNegative() || budget.isZero() || budget.compareTo(Duration.ofSeconds(15)) > 0) {
            throw new IllegalArgumentException("El presupuesto de requisitos debe ser positivo y hasta 15 s");
        }
        this.clock = Objects.requireNonNull(clock, "clock");
        this.budgetNanos = budget.toNanos();
        this.startedAt = clock.getAsLong();
        this.lastObserved = startedAt;
        this.registrationBudget = owner;
    }

    boolean usesRegistrationBudget(LegalRegistrationBudget owner) {
        return registrationBudget != null && registrationBudget == owner;
    }

    boolean hasRegistrationBudget() {
        return registrationBudget != null;
    }

    void check() {
        remainingMillis();
    }

    int remainingMillis() {
        if (registrationBudget != null) {
            return adoptedRemainingMillis();
        }
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

    private synchronized int adoptedRemainingMillis() {
        final int ownerRemaining;
        try {
            // Observe the owner even after local expiry: a later cleanup keeps its original cause.
            ownerRemaining = registrationBudget.remainingMillis();
        } catch (LegalRegistrationBudget.UnavailableException unavailable) {
            throw new LegalPublicRequirementsReadException(unavailable.getCause());
        }
        if (localUnavailable) {
            throw new LegalPublicRequirementsReadException();
        }
        final long now;
        try {
            now = clock.getAsLong();
        } catch (RuntimeException invalidClock) {
            localUnavailable = true;
            throw new LegalPublicRequirementsReadException();
        }
        long sinceLastObservation = now - lastObserved;
        long elapsed = now - startedAt;
        if (sinceLastObservation < 0 || elapsed < 0 || elapsed >= budgetNanos) {
            localUnavailable = true;
            throw new LegalPublicRequirementsReadException();
        }
        lastObserved = now;
        long remaining = budgetNanos - elapsed;
        int localRemaining = Math.toIntExact(1 + (remaining - 1) / 1_000_000L);
        return Math.min(ownerRemaining, localRemaining);
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
