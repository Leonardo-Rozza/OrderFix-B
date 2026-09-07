package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;

/** Explicit technical retention; construction does not accredit approval of its configured value. */
public final class LegalAcceptanceMetadataPolicy {
    private final Duration retention;

    public LegalAcceptanceMetadataPolicy(Duration retention) {
        try {
            if (retention == null || retention.isZero() || retention.isNegative()) throw invalidRetention();
            // Keep the configured duration representable by the same bounded nanosecond arithmetic.
            retention.toNanos();
            this.retention = retention;
        } catch (RuntimeException failure) {
            throw invalidRetention();
        }
    }

    public Duration retention() {
        return retention;
    }

    /** PostgreSQL stores microseconds: ceiling the final instant never shortens the explicit duration. */
    public Instant expiresAt(Instant capturedAt) {
        if (capturedAt == null) throw invalidExpiration();
        try {
            Instant expiration = capturedAt.plus(retention);
            int remainingNanos = expiration.getNano() % 1_000;
            if (remainingNanos != 0) expiration = expiration.plusNanos(1_000 - remainingNanos);
            if (!expiration.isAfter(capturedAt)) throw invalidExpiration();
            return expiration;
        } catch (DateTimeException | ArithmeticException failure) {
            throw invalidExpiration();
        }
    }

    @Override
    public String toString() {
        return "LegalAcceptanceMetadataPolicy[retention=" + retention + "]";
    }

    private static IllegalArgumentException invalidRetention() {
        return new IllegalArgumentException("La retención de metadata legal no es válida");
    }

    private static IllegalArgumentException invalidExpiration() {
        return new IllegalArgumentException("El vencimiento de metadata legal no es representable");
    }
}
