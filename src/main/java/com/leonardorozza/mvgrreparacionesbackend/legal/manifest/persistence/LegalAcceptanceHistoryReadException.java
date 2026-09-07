package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

/** An unaccredited historical observation must never become an empty successful page. */
public final class LegalAcceptanceHistoryReadException extends RuntimeException {

    public LegalAcceptanceHistoryReadException() {
        super("El historial legal no está disponible");
    }

    public LegalAcceptanceHistoryReadException(Throwable cause) {
        super("El historial legal no está disponible", cause);
    }
}
