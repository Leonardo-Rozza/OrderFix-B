package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import java.time.Instant;
import java.util.Objects;

/** PostgreSQL transaction and post-lock observation instants for one editorial operation. */
record LegalEditorialTimeBoundary(
        Instant transactionAt,
        Instant observedAt
) {

    LegalEditorialTimeBoundary {
        transactionAt = requirePostgresInstant(transactionAt, "transactionAt");
        observedAt = requirePostgresInstant(observedAt, "observedAt");
        if (transactionAt.isAfter(observedAt)) {
            throw new IllegalArgumentException(
                    "transactionAt no puede ser posterior a observedAt");
        }
    }

    private static Instant requirePostgresInstant(Instant value, String field) {
        Instant required = Objects.requireNonNull(value, field);
        if (required.getNano() % 1_000 != 0) {
            throw new IllegalArgumentException(
                    field + " supera la precisión de PostgreSQL");
        }
        return required;
    }
}
