package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import java.util.Objects;

/** Internal outcome category; HTTP adapters must never serialize its database cause. */
public final class LegalIdempotencyException extends RuntimeException {
    public enum Reason { IN_PROGRESS, KEY_REUSED, UNAVAILABLE, INVALID_ACTOR }

    private final Reason reason;

    public LegalIdempotencyException(Reason reason) {
        this(reason, null);
    }

    public LegalIdempotencyException(Reason reason, Throwable cause) {
        super(message(reason), cause);
        this.reason = reason;
    }

    public Reason reason() { return reason; }

    private static String message(Reason reason) {
        return switch (Objects.requireNonNull(reason, "reason")) {
            case IN_PROGRESS -> "La operación idempotente está en curso";
            case KEY_REUSED -> "La clave idempotente ya pertenece a otra operación";
            case UNAVAILABLE -> "El resultado idempotente no está disponible";
            case INVALID_ACTOR -> "El actor legal no está disponible";
        };
    }
}
