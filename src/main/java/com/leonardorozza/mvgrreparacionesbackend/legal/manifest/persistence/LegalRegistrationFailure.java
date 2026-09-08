package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import java.util.Objects;
import java.util.Optional;

/** Safe classification plus separate evidence of delivery completion and the durable registration. */
public final class LegalRegistrationFailure extends RuntimeException {
    public enum Reason { INVALID_ACTOR, INVALID_PAYLOAD, STALE, INVALID, KEY_REUSED, IN_PROGRESS, UNAVAILABLE }
    public enum Completion { NONE, COMMITTED, ROLLED_BACK, UNKNOWN }
    public enum Persistence { PERSISTED, NOT_PERSISTED, UNKNOWN }

    private final Reason reason;
    private final Completion completion;
    private final Persistence persistence;
    private final Optional<LegalRegistrationReceipt> confirmedReceipt;
    private final Optional<LegalRegistrationValidationException> validation;

    LegalRegistrationFailure(Reason reason, LegalTransactionCompletionState.Snapshot<LegalRegistrationReceipt> state,
                             LegalRegistrationReceipt verifiedReplay, RuntimeException cause) {
        super("La operación de registro legal no pudo entregarse.", Objects.requireNonNull(cause, "cause"));
        this.reason = Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(state, "state");
        if (verifiedReplay != null && (!verifiedReplay.replay()
                || state.receipt().filter(receipt -> !receipt.equals(verifiedReplay)).isPresent())) {
            throw new IllegalArgumentException("La evidencia de registro no es coherente");
        }
        completion = Completion.valueOf(state.completion().name());
        // Without an accredited replay, this describes the current write attempt, never a claim
        // that the account or key did not exist historically. Replay evidence survives failed delivery.
        persistence = verifiedReplay != null ? Persistence.PERSISTED : Persistence.valueOf(state.persistence().name());
        confirmedReceipt = verifiedReplay != null ? Optional.of(verifiedReplay) : state.receipt();
        validation = cause instanceof LegalRegistrationValidationException typed ? Optional.of(typed) : Optional.empty();
    }

    public Reason reason() { return reason; }
    public Completion completion() { return completion; }
    public Persistence persistence() { return persistence; }
    public Optional<LegalRegistrationReceipt> confirmedReceipt() { return confirmedReceipt; }
    public Optional<LegalRegistrationValidationException> validation() { return validation; }

    @Override public String toString() { return "LegalRegistrationFailure[" + reason + "]"; }
}
