package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import java.util.Objects;
import java.util.Optional;

/** Safe public classification with honest completion evidence; diagnostics stay in the internal cause. */
public final class LegalAcceptanceFailure extends RuntimeException {
    public enum Reason { INVALID_ACTOR, INVALID_PAYLOAD, STALE, INVALID, KEY_REUSED, IN_PROGRESS, UNAVAILABLE }
    public enum Completion { NONE, COMMITTED, ROLLED_BACK, UNKNOWN }
    public enum Persistence { PERSISTED, NOT_PERSISTED, UNKNOWN }

    private final Reason reason;
    private final Completion completion;
    private final Persistence persistence;
    private final Optional<LegalAcceptanceReceipt> confirmedReceipt;
    private final Optional<LegalAcceptanceValidationException> validation;

    LegalAcceptanceFailure(Reason reason, LegalTransactionCompletionState.Snapshot<LegalAcceptanceReceipt> state,
                           RuntimeException cause) {
        super("La operación de aceptación legal no pudo entregarse.", Objects.requireNonNull(cause, "cause"));
        this.reason = Objects.requireNonNull(reason, "reason");
        this.completion = Completion.valueOf(state.completion().name());
        this.persistence = Persistence.valueOf(state.persistence().name());
        this.confirmedReceipt = state.receipt();
        this.validation = cause instanceof LegalAcceptanceValidationException typed ? Optional.of(typed) : Optional.empty();
    }

    public Reason reason() { return reason; }
    public Completion completion() { return completion; }
    public Persistence persistence() { return persistence; }
    public Optional<LegalAcceptanceReceipt> confirmedReceipt() { return confirmedReceipt; }
    public Optional<LegalAcceptanceValidationException> validation() { return validation; }
}
