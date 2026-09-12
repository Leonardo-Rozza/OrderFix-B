package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure;

/** No credentials, raw source values or driver diagnostics in errors. No HTTP mapping in this cut. */
public final class ClosurePreparationException extends RuntimeException {
    public enum Code { SESSION_INVALID, FORBIDDEN, SOURCE_INVALID, CAPACITY_EXCEEDED, UNAVAILABLE }
    private final Code code;
    public ClosurePreparationException(Code code) {
        super("No se pudo preparar el cierre del taller.");
        this.code = java.util.Objects.requireNonNull(code);
    }
    public Code code() { return code; }
}
