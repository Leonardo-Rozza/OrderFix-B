package com.leonardorozza.mvgrreparacionesbackend.cuenta.reauth;

import java.time.Instant;
import java.util.Objects;

/** The raw credential is returned once to the caller and must remain transient. */
public record ReauthenticationGrant(String token, Instant expiresAt) {
    public ReauthenticationGrant {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    @Override
    public String toString() {
        return "ReauthenticationGrant[token=[REDACTED], expiresAt=" + expiresAt + "]";
    }
}
