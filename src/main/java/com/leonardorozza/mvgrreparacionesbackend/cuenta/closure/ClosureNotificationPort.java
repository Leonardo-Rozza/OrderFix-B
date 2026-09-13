package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure;

import java.time.Instant;
import java.util.UUID;

/** A future adapter resolves a verified recipient from userId. No recipient or rendered message is stored here. */
public interface ClosureNotificationPort {
    Result send(Event event);
    /** RETRYABLE is permitted only when the adapter knows the provider did not accept the message. */
    enum Result { ACCEPTED, RETRYABLE, UNCERTAIN }
    enum Kind { CLOSED, RESTORED }
    record Event(UUID operationKey, UUID closureReference, long tallerId, long userId, Kind kind, Instant occurredAt) {
        @Override public String toString() { return "ClosureNotificationEvent[redacted]"; }
    }
}
