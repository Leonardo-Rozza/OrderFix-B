package com.leonardorozza.mvgrreparacionesbackend.cuenta.export;

/** Sanitized internal failure; never carries SQL, paths, row values or credentials. */
public final class ExportPackageException extends RuntimeException {
    public enum Code { ACCESS_DENIED, INCONSISTENT_RELATION, INVALID_EVIDENCE, CAPACITY_EXCEEDED, UNAVAILABLE, INVALID_PACKAGE }
    private final Code code;
    public ExportPackageException(Code code) {
        super("No se pudo preparar el paquete de datos (" + code.name() + ").");
        this.code = code;
    }
    public Code code() { return code; }
}
