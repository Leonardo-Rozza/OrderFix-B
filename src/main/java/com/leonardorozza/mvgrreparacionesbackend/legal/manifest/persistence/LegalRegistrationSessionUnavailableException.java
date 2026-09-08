package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

/** A session observation cannot be delivered; this says nothing about registration persistence. */
public final class LegalRegistrationSessionUnavailableException extends RuntimeException {
    public LegalRegistrationSessionUnavailableException() {
        super("La sesión de registro no está disponible");
    }

    public LegalRegistrationSessionUnavailableException(Throwable cause) {
        super("La sesión de registro no está disponible", cause);
    }

    @Override public String toString() {
        return "LegalRegistrationSessionUnavailableException: La sesión de registro no está disponible";
    }
}
