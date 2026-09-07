package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import java.util.Objects;

/** Neutral input rejection: no transport dependency, external text or retained cause. */
public final class LegalAcceptanceInputException extends RuntimeException {
    public enum Reason { REQUIRED_KEY, INVALID_KEY, INVALID_PAYLOAD }

    private final Reason reason;

    public LegalAcceptanceInputException(Reason reason) {
        super("La entrada de aceptación legal no es válida.", null);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() { return reason; }
}
