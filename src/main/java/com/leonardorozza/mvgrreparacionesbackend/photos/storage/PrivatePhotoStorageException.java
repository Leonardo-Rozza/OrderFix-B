package com.leonardorozza.mvgrreparacionesbackend.photos.storage;

/** Deliberately excludes provider payloads, credentials and signed URLs from public errors. */
public final class PrivatePhotoStorageException extends RuntimeException {
    public enum Reason { UNAVAILABLE, INVALID_ASSET, MISSING }
    private final Reason reason;

    public PrivatePhotoStorageException(Reason reason) {
        super("No se pudo completar la operación de almacenamiento privado.");
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
