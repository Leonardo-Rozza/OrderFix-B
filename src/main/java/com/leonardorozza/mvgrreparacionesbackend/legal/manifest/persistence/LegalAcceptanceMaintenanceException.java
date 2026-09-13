package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

/** Safe operational outcome: UNKNOWN never means that no rows changed. No SQL or secret causes. */
public final class LegalAcceptanceMaintenanceException extends RuntimeException {
    public enum Code { INVALID_BOUNDARY, UNAVAILABLE, UNKNOWN }
    private final Code code;
    public LegalAcceptanceMaintenanceException(Code code) {
        super("No se pudo acreditar el mantenimiento legal.");
        this.code = java.util.Objects.requireNonNull(code);
    }
    public Code code() { return code; }
}
