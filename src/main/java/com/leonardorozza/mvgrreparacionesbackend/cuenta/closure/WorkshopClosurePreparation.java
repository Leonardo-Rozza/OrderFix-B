package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Consistent diagnostic only: it is neither a closure request nor permission to close. */
public record WorkshopClosurePreparation(long userId, long tallerId, long tokenVersion,
        String tallerName, Instant observedAt, Workforce workforce, Resources resources,
        ClosureRenewalAssessment.Result renewal) {
    public WorkshopClosurePreparation {
        if (userId <= 0 || tallerId <= 0 || tokenVersion < 0 || tallerName == null || tallerName.isBlank())
            throw new IllegalArgumentException("Preparación de cierre inválida.");
        Objects.requireNonNull(observedAt);
        Objects.requireNonNull(workforce);
        Objects.requireNonNull(resources);
        Objects.requireNonNull(renewal);
    }
    public String policyVersion() { return WorkshopClosurePolicy.POLICY_VERSION; }
    public Duration restorationWindow() { return WorkshopClosurePolicy.RESTORATION_WINDOW; }
    public Duration processingWindow() { return WorkshopClosurePolicy.PROCESSING_WINDOW; }
    @Override public String toString() { return "WorkshopClosurePreparation[redacted]"; }

    public record Workforce(long activeEmployees, long inactiveEmployees) {
        public Workforce {
            if (activeEmployees < 0 || inactiveEmployees < 0)
                throw new IllegalArgumentException("Resumen de usuarios inválido.");
        }
    }
    /** Counts describe persisted work, not remotely deleted objects or authorized downloads. */
    public record Resources(long privatePhotos, long photosWithLease, long photosPendingCleanup,
            long pendingExports, long readyExports) {
        public Resources {
            if (privatePhotos < 0 || photosWithLease < 0 || photosPendingCleanup < 0
                    || pendingExports < 0 || readyExports < 0
                    || photosWithLease > privatePhotos || photosPendingCleanup > privatePhotos)
                throw new IllegalArgumentException("Resumen de recursos inválido.");
        }
    }
}
