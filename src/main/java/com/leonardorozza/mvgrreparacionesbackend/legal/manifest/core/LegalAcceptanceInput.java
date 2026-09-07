package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand.Acceptance;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Deferred server input; shape validation follows the persisted actor observation. */
public record LegalAcceptanceInput(String idempotencyKey, String requiredSetRevision,
                                   List<Acceptance> acceptances, LegalRequestMetadata metadata) {
    public LegalAcceptanceInput {
        if (acceptances != null) {
            int size = acceptances.size();
            if (size > LegalAcceptanceCommandValidator.MAX_ACCEPTANCES) throw tooManyAcceptances();
            List<Acceptance> copied = new ArrayList<>(size);
            for (Acceptance acceptance : acceptances) {
                // Bound the copy even if a supplied list changes after the initial size observation.
                if (copied.size() == LegalAcceptanceCommandValidator.MAX_ACCEPTANCES) throw tooManyAcceptances();
                copied.add(acceptance);
            }
            // Missing lists and null members remain available for the later shape validator.
            acceptances = Collections.unmodifiableList(copied);
        }
    }

    private static LegalAcceptanceInputException tooManyAcceptances() {
        return new LegalAcceptanceInputException(LegalAcceptanceInputException.Reason.INVALID_PAYLOAD);
    }

    @Override public String toString() { return "LegalAcceptanceInput[redacted]"; }

    /** The checkpoint cooperatively checks the transaction budget; it cannot interrupt blocked input. */
    @FunctionalInterface
    public interface Reader {
        LegalAcceptanceInput read(Runnable checkpoint);
    }
}
