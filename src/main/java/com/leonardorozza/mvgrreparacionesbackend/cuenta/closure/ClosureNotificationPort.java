package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure;

import java.time.Instant;
import java.util.UUID;

/** Internal leased delivery. The adapter resolves a verified owner; no recipient or body is stored here. */
public interface ClosureNotificationPort {
    Result send(Event event);
    /** RETRYABLE is permitted only when the adapter knows the provider did not accept the message. */
    enum Result { ACCEPTED, RETRYABLE, UNCERTAIN }
    enum Kind { CLOSED, RESTORED }
    record Event(UUID operationKey, UUID closureReference, long tallerId, long userId, Kind kind, Instant occurredAt,
                 UUID leaseToken, Instant leaseUntil) {
        @Override public String toString() { return "ClosureNotificationEvent[redacted]"; }
    }
}
