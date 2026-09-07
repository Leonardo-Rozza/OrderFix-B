package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegalAcceptanceMetadataPolicyTest {
    @ParameterizedTest
    @MethodSource("invalidRetentions")
    void requiresAnExplicitPositiveRepresentableRetention(Duration retention) {
        assertThatThrownBy(() -> new LegalAcceptanceMetadataPolicy(retention))
                .isInstanceOf(IllegalArgumentException.class).hasNoCause();
    }

    static Stream<Duration> invalidRetentions() {
        return Stream.of(null, Duration.ZERO, Duration.ofNanos(-1), Duration.ofDays(-1),
                Duration.ofNanos(Long.MAX_VALUE).plusNanos(1), Duration.ofSeconds(Long.MAX_VALUE));
    }

    @Test
    void preservesTheExplicitRetentionWithoutInventingAMinimumOrDefault() {
        Duration precise = Duration.ofNanos(1);
        var policy = new LegalAcceptanceMetadataPolicy(precise);
        assertThat(policy.retention()).isEqualTo(precise);
        assertThat(policy.expiresAt(Instant.EPOCH)).isEqualTo(Instant.EPOCH.plusNanos(1_000));
    }

    @Test
    void derivesExpirationFromTheSuppliedServerCaptureRatherThanTheWallClock() {
        Instant capturedAt = Instant.parse("2026-09-07T04:00:00.123456Z");
        var policy = new LegalAcceptanceMetadataPolicy(Duration.ofDays(3));
        assertThat(policy.expiresAt(capturedAt)).isEqualTo(Instant.parse("2026-09-10T04:00:00.123456Z"));
    }

    @Test
    void roundsTheFinalInstantUpWithoutShorteningRetention() {
        Instant capturedAt = Instant.parse("2026-09-07T04:00:00.123456789Z");
        Duration retention = Duration.ofSeconds(2).plusNanos(12);
        var policy = new LegalAcceptanceMetadataPolicy(retention);
        Instant expiration = policy.expiresAt(capturedAt);
        assertThat(expiration).isEqualTo(Instant.parse("2026-09-07T04:00:02.123457Z"));
        assertThat(Duration.between(capturedAt, expiration)).isGreaterThanOrEqualTo(retention);
    }

    @Test
    void carriesRoundingIntoTheNextSecondAndWorksBeforeEpoch() {
        var policy = new LegalAcceptanceMetadataPolicy(Duration.ofNanos(1));
        assertThat(policy.expiresAt(Instant.parse("2026-09-07T04:00:00.999999Z")))
                .isEqualTo(Instant.parse("2026-09-07T04:00:01Z"));
        assertThat(policy.expiresAt(Instant.EPOCH.minusNanos(1)))
                .isEqualTo(Instant.EPOCH);
        assertThat(policy.expiresAt(Instant.EPOCH.minusNanos(1_001)))
                .isEqualTo(Instant.EPOCH.minusNanos(1_000));
    }

    @Test
    void doesNotAdvanceAnAlreadyExactMicrosecond() {
        var policy = new LegalAcceptanceMetadataPolicy(Duration.ofNanos(211));
        assertThat(policy.expiresAt(Instant.parse("2026-09-07T04:00:00.123456789Z")))
                .isEqualTo(Instant.parse("2026-09-07T04:00:00.123457Z"));
    }

    @Test
    void acceptsTheLargestNanosecondDurationWithoutOverflowDuringRounding() {
        Duration retention = Duration.ofNanos(Long.MAX_VALUE);
        var policy = new LegalAcceptanceMetadataPolicy(retention);
        Instant expiration = policy.expiresAt(Instant.EPOCH);
        assertThat(policy.retention()).isEqualTo(retention);
        assertThat(expiration).isAfterOrEqualTo(Instant.EPOCH.plus(retention));
        assertThat(expiration.getNano() % 1_000).isZero();
    }

    @Test
    void rejectsNullCaptureAndInstantOverflowIncludingRoundingOverflow() {
        var policy = new LegalAcceptanceMetadataPolicy(Duration.ofNanos(1));
        assertThatThrownBy(() -> policy.expiresAt(null)).isInstanceOf(IllegalArgumentException.class).hasNoCause();
        assertThatThrownBy(() -> policy.expiresAt(Instant.MAX)).isInstanceOf(IllegalArgumentException.class).hasNoCause();
        assertThatThrownBy(() -> policy.expiresAt(Instant.MAX.minusNanos(1)))
                .isInstanceOf(IllegalArgumentException.class).hasNoCause();
        Instant lastMicrosecond = Instant.MAX.minusNanos(999);
        assertThat(policy.expiresAt(lastMicrosecond.minusNanos(1))).isEqualTo(lastMicrosecond);
        assertThatThrownBy(() -> policy.expiresAt(lastMicrosecond))
                .isInstanceOf(IllegalArgumentException.class).hasNoCause();
    }
}
