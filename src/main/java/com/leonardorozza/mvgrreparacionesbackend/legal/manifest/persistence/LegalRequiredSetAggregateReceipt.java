package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateProvenance;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Internal receipt for one fully replay-verified V28 aggregate. */
record LegalRequiredSetAggregateReceipt(
        Outcome outcome,
        UUID aggregateId,
        String requiredSetRevision,
        String provenanceFingerprint,
        LegalRequiredSetAggregateProvenance provenance,
        Instant createdAt
) {

    LegalRequiredSetAggregateReceipt {
        outcome = Objects.requireNonNull(outcome, "outcome");
        aggregateId = Objects.requireNonNull(aggregateId, "aggregateId");
        requiredSetRevision = requireDigest(requiredSetRevision, "requiredSetRevision");
        provenanceFingerprint = requireDigest(
                provenanceFingerprint,
                "provenanceFingerprint");
        provenance = Objects.requireNonNull(provenance, "provenance");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        if (createdAt.getNano() % 1_000 != 0) {
            throw new IllegalArgumentException("createdAt supera la precisión de PostgreSQL");
        }
    }

    private static String requireDigest(String value, String field) {
        String required = Objects.requireNonNull(value, field);
        if (required.length() != 71 || !required.startsWith("sha256:")) {
            throw new IllegalArgumentException(field + " no respeta sha256:<64-hex lowercase>");
        }
        for (int index = 7; index < required.length(); index++) {
            char current = required.charAt(index);
            if (!((current >= '0' && current <= '9')
                    || (current >= 'a' && current <= 'f'))) {
                throw new IllegalArgumentException(
                        field + " no respeta sha256:<64-hex lowercase>");
            }
        }
        return required;
    }

    enum Outcome {
        CREATED,
        REUSED
    }
}
