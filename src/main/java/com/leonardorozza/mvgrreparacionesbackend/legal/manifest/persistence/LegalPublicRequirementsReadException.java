package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

/** A failed accreditation or observation; never an acceptable empty registration requirement set. */
public final class LegalPublicRequirementsReadException extends RuntimeException {

    public LegalPublicRequirementsReadException() {
        super("El contrato de requisitos no está disponible");
    }

    public LegalPublicRequirementsReadException(Throwable cause) {
        super("El contrato de requisitos no está disponible", cause);
    }
}
