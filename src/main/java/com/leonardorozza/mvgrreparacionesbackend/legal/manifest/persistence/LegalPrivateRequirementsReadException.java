package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

/** A failed accreditation or observation; never an acceptable empty private requirement set. */
public final class LegalPrivateRequirementsReadException extends RuntimeException {

    public LegalPrivateRequirementsReadException() {
        super("El contrato de requisitos no está disponible");
    }

    public LegalPrivateRequirementsReadException(Throwable cause) {
        super("El contrato de requisitos no está disponible", cause);
    }
}
