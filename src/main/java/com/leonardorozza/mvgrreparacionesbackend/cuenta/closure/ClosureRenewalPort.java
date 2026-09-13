package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure;

import java.util.UUID;

/** No production adapter is installed. An adapter must validate remote account/application ownership. */
public interface ClosureRenewalPort {
    Observation inspect(Target target);
    /** operationKey is stable across retries; an ambiguous result must be inspected before another call. */
    Observation cancel(Target target);

    enum State { ACTIVE, CANCELED, RETRYABLE, UNCERTAIN }
    record Target(UUID operationKey, long linkId, String externalReference, String externalId) {
        @Override public String toString() { return "ClosureRenewalTarget[redacted]"; }
    }
    record Observation(String externalReference, String externalId, State state) {
        @Override public String toString() { return "ClosureRenewalObservation[redacted]"; }
    }
}
