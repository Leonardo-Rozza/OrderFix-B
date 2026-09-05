package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

/** A failed accreditation or observation; never represents an absent document. */
public final class LegalPublicDocumentReadException extends RuntimeException {

    public LegalPublicDocumentReadException() {
        super("El contrato documental no está disponible");
    }

    public LegalPublicDocumentReadException(Throwable cause) {
        super("El contrato documental no está disponible", cause);
    }
}
