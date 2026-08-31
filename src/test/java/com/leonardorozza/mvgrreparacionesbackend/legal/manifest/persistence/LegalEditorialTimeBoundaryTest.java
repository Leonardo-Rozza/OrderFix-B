package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegalEditorialTimeBoundaryTest {

    private static final Instant TRANSACTION_AT =
            Instant.parse("2026-08-31T12:00:00.123456Z");
    private static final Instant OBSERVED_AT =
            Instant.parse("2026-08-31T12:00:00.123457Z");

    @Test
    void acceptsOrderedPostgresInstants() {
        LegalEditorialTimeBoundary boundary =
                new LegalEditorialTimeBoundary(TRANSACTION_AT, OBSERVED_AT);

        assertThat(boundary.transactionAt()).isEqualTo(TRANSACTION_AT);
        assertThat(boundary.observedAt()).isEqualTo(OBSERVED_AT);
    }

    @Test
    void acceptsEqualPostgresInstants() {
        LegalEditorialTimeBoundary boundary =
                new LegalEditorialTimeBoundary(TRANSACTION_AT, TRANSACTION_AT);

        assertThat(boundary.transactionAt()).isEqualTo(TRANSACTION_AT);
        assertThat(boundary.observedAt()).isEqualTo(TRANSACTION_AT);
    }

    @Test
    void rejectsNullInstants() {
        assertThatThrownBy(() -> new LegalEditorialTimeBoundary(null, OBSERVED_AT))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LegalEditorialTimeBoundary(TRANSACTION_AT, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsInstantsBeyondPostgresMicrosecondPrecision() {
        assertThatThrownBy(() -> new LegalEditorialTimeBoundary(
                TRANSACTION_AT.plusNanos(1),
                OBSERVED_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LegalEditorialTimeBoundary(
                TRANSACTION_AT,
                OBSERVED_AT.plusNanos(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAnObservedInstantBeforeTheTransaction() {
        assertThatThrownBy(() -> new LegalEditorialTimeBoundary(
                OBSERVED_AT,
                TRANSACTION_AT))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
