package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

/** A server principal no longer matches its locked account; never exposes identity details. */
public final class LegalActorSnapshotException extends RuntimeException {

    public LegalActorSnapshotException() {
        super("La identidad legal no está disponible");
    }
}
