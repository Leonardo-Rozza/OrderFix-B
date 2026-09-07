package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommandValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalActorSnapshot;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalIdempotencyFingerprint;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegalIdempotencyKeyringTest {
    private static final String REQUEST_KEY = "76962ff8-557a-4a9f-aafa-2db4d69cfc74";

    @Test
    void defaultsOnlyTheResultTtlToTwentyFiveHours() {
        var ring = new LegalIdempotencyKeyring(Map.of(1, encoded(0)), 1);
        assertThat(ring.resultTtl()).isEqualTo(Duration.ofHours(25));
        assertThat(ring.activeWriteVersion()).isEqualTo(1);
        assertThat(ring.versions()).containsExactly(1);
    }

    @Test
    void retainsAllEightDistinctKeysInNaturalVersionOrder() {
        Map<Integer, String> encoded = new LinkedHashMap<>();
        for (int version = 8; version > 0; version--) encoded.put(version, encoded(version));
        var ring = new LegalIdempotencyKeyring(encoded, 5, Duration.ofHours(24));
        assertThat(ring.versions()).containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
        assertThat(ring.activeWriteVersion()).isEqualTo(5);
        assertThat(ring.candidates(command(), REQUEST_KEY)).extracting(LegalIdempotencyFingerprint::keyVersion)
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
    }

    @Test
    void permitsAnyPositiveIntVersionWithoutRenumberingIt() {
        var ring = new LegalIdempotencyKeyring(Map.of(Integer.MAX_VALUE, encoded(1)), Integer.MAX_VALUE);
        assertThat(ring.versions()).containsExactly(Integer.MAX_VALUE);
        assertThat(ring.candidates(command(), REQUEST_KEY)).singleElement()
                .extracting(LegalIdempotencyFingerprint::keyVersion).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void rejectsEmptyNullAndNinthKeyInsteadOfSilentlyDroppingRetainedKeys() {
        assertInvalid(() -> new LegalIdempotencyKeyring(null, 1));
        assertInvalid(() -> new LegalIdempotencyKeyring(Map.of(), 1));
        Map<Integer, String> excessive = new LinkedHashMap<>();
        for (int version = 1; version <= 9; version++) excessive.put(version, encoded(version));
        assertInvalid(() -> new LegalIdempotencyKeyring(excessive, 9));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void rejectsNonPositiveVersionsEvenWhenTheyAreNotTheActiveWriter(int invalidVersion) {
        assertInvalid(() -> new LegalIdempotencyKeyring(Map.of(1, encoded(1), invalidVersion, encoded(2)), 1));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 2, Integer.MAX_VALUE})
    void activeVersionMustBePositiveAndPresent(int active) {
        assertInvalid(() -> new LegalIdempotencyKeyring(Map.of(1, encoded(1)), active));
    }

    @Test
    void rejectsNullMapKeysOrValuesWithSanitizedDiagnostics() {
        Map<Integer, String> nullVersion = new HashMap<>();
        nullVersion.put(null, encoded(1));
        assertInvalid(() -> new LegalIdempotencyKeyring(nullVersion, 1));
        Map<Integer, String> nullSecret = new HashMap<>();
        nullSecret.put(1, null);
        assertInvalid(() -> new LegalIdempotencyKeyring(nullSecret, 1));
    }

    @ParameterizedTest
    @MethodSource("invalidEncodings")
    void acceptsOnlyCanonicalPaddedStandardBase64OfExactlyThirtyTwoBytes(String invalid) {
        assertInvalid(() -> new LegalIdempotencyKeyring(Map.of(1, invalid), 1));
    }

    static Stream<String> invalidEncodings() {
        String canonical = encoded(0);
        byte[] allOnes = new byte[32];
        Arrays.fill(allOnes, (byte) 0xff);
        return Stream.of(
                "", "private-not-base64", canonical.substring(0, 43), canonical + " ", " " + canonical,
                canonical.substring(0, 12) + "\n" + canonical.substring(13),
                Base64.getEncoder().encodeToString(new byte[31]), Base64.getEncoder().encodeToString(new byte[33]),
                Base64.getUrlEncoder().encodeToString(allOnes),
                canonical.substring(0, 42) + "B="); // Same decoded zero bytes, forbidden non-zero padding bits.
    }

    @Test
    void rejectsDuplicateSecretBytesUnderDifferentVersions() {
        assertInvalid(() -> new LegalIdempotencyKeyring(Map.of(1, encoded(5), 7, encoded(5)), 7));
    }

    @ParameterizedTest
    @MethodSource("invalidTtls")
    void rejectsTooShortOrNanosecondOverflowingTtl(Duration ttl) {
        assertInvalid(() -> new LegalIdempotencyKeyring(Map.of(1, encoded(1)), 1, ttl));
    }

    static Stream<Duration> invalidTtls() {
        return Stream.of(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofHours(23),
                Duration.ofHours(24).minusNanos(1), Duration.ofNanos(Long.MAX_VALUE).plusNanos(1),
                Duration.ofSeconds(Long.MAX_VALUE));
    }

    @Test
    void explicitNullTtlDoesNotUseTheDefault() {
        assertInvalid(() -> new LegalIdempotencyKeyring(Map.of(1, encoded(1)), 1, null));
    }

    @Test
    void retainsExactTtlIncludingFractionsAndLargestRepresentableNanoseconds() {
        Duration precise = Duration.ofHours(24).plusNanos(1);
        var ring = new LegalIdempotencyKeyring(Map.of(1, encoded(1)), 1, precise);
        assertThat(ring.resultTtl()).isEqualTo(precise);
        assertThat(ring.expiresAt(Instant.EPOCH)).isEqualTo(Instant.EPOCH.plus(precise));
        Duration maximum = Duration.ofNanos(Long.MAX_VALUE);
        var longest = new LegalIdempotencyKeyring(Map.of(1, encoded(1)), 1, maximum);
        assertThat(longest.resultTtl()).isEqualTo(maximum);
        assertThat(longest.expiresAt(Instant.EPOCH)).isEqualTo(Instant.EPOCH.plus(maximum));
    }

    @Test
    void computesExpirationFromCompletionAndRejectsOverflowBeforeAnyStoreCanRun() {
        Duration ttl = Duration.ofHours(24);
        var ring = new LegalIdempotencyKeyring(Map.of(1, encoded(1)), 1, ttl);
        Instant completed = Instant.parse("2026-09-06T20:00:00.123456Z");
        assertThat(ring.expiresAt(completed)).isEqualTo(Instant.parse("2026-09-07T20:00:00.123456Z"));
        assertThat(ring.expiresAt(Instant.MAX.minus(ttl))).isEqualTo(Instant.MAX);
        assertThatThrownBy(() -> ring.expiresAt(Instant.MAX)).isInstanceOf(IllegalArgumentException.class).hasNoCause();
        assertThatThrownBy(() -> ring.expiresAt(Instant.MAX.minus(ttl).plusNanos(1))).isInstanceOf(IllegalArgumentException.class).hasNoCause();
        assertThatThrownBy(() -> ring.expiresAt(null)).isInstanceOf(IllegalArgumentException.class).hasNoCause();
    }

    @Test
    void candidatesUseEachRetainedVersionAndItsOwnOriginalSecret() {
        var ring = new LegalIdempotencyKeyring(Map.of(9, encoded(3), 1, encoded(1), 3, encoded(2)), 3);
        LegalAcceptanceCommand command = command();
        assertThat(ring.candidates(command, REQUEST_KEY)).containsExactly(
                LegalIdempotencyFingerprint.derive(command, REQUEST_KEY, 1, bytes(1)),
                LegalIdempotencyFingerprint.derive(command, REQUEST_KEY, 3, bytes(2)),
                LegalIdempotencyFingerprint.derive(command, REQUEST_KEY, 9, bytes(3)));
        assertThat(ring.candidates(command, REQUEST_KEY)).extracting(LegalIdempotencyFingerprint::scopeHmac)
                .doesNotHaveDuplicates();
    }

    @Test
    void changingOnlyTheActiveWriterDoesNotRemoveOldLookupCandidates() {
        Map<Integer, String> keys = Map.of(1, encoded(1), 7, encoded(7));
        var before = new LegalIdempotencyKeyring(keys, 1);
        var after = new LegalIdempotencyKeyring(keys, 7);
        assertThat(after.candidates(command(), REQUEST_KEY)).isEqualTo(before.candidates(command(), REQUEST_KEY));
        assertThat(before.activeWriteVersion()).isEqualTo(1);
        assertThat(after.activeWriteVersion()).isEqualTo(7);
    }

    @Test
    void inputMutationCannotChangeTheConstructedKeySnapshot() {
        Map<Integer, String> source = new HashMap<>(Map.of(1, encoded(1), 2, encoded(2)));
        var ring = new LegalIdempotencyKeyring(source, 2);
        var original = ring.candidates(command(), REQUEST_KEY);
        source.put(1, encoded(8)); source.remove(2); source.put(3, encoded(3));
        assertThat(ring.versions()).containsExactly(1, 2);
        assertThat(ring.activeWriteVersion()).isEqualTo(2);
        assertThat(ring.candidates(command(), REQUEST_KEY)).isEqualTo(original);
    }

    @Test
    void exportedVersionAndCandidateListsCannotBeMutated() {
        var ring = new LegalIdempotencyKeyring(Map.of(1, encoded(1), 2, encoded(2)), 2);
        assertThatThrownBy(() -> ring.versions().clear()).isInstanceOf(UnsupportedOperationException.class);
        var candidates = ring.candidates(command(), REQUEST_KEY);
        assertThatThrownBy(candidates::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> candidates.set(0, candidates.get(1))).isInstanceOf(UnsupportedOperationException.class);
        assertThat(ring.candidates(command(), REQUEST_KEY)).isEqualTo(candidates);
    }

    @Test
    void concurrentDerivationsNeverOverwriteRetainedSecretsOrShareMutableMacState() throws Exception {
        var ring = new LegalIdempotencyKeyring(Map.of(1, encoded(1), 3, encoded(3), 7, encoded(7)), 7);
        LegalAcceptanceCommand command = command();
        var expected = ring.candidates(command, REQUEST_KEY);
        List<Callable<List<LegalIdempotencyFingerprint>>> jobs = new ArrayList<>();
        for (int index = 0; index < 40; index++) jobs.add(() -> ring.candidates(command, REQUEST_KEY));
        try (var executor = Executors.newFixedThreadPool(4)) {
            for (var future : executor.invokeAll(jobs, 5, TimeUnit.SECONDS)) {
                assertThat(future.get(1, TimeUnit.SECONDS)).isEqualTo(expected);
            }
        }
        assertThat(ring.candidates(command, REQUEST_KEY)).isEqualTo(expected);
    }

    @Test
    void nullAndInvalidCandidateInputsNeverReturnAnIncompleteCandidateListOrSensitiveCause() {
        var ring = new LegalIdempotencyKeyring(Map.of(1, encoded(1), 2, encoded(2)), 2);
        assertThatThrownBy(() -> ring.candidates(null, REQUEST_KEY)).isInstanceOf(IllegalArgumentException.class).hasNoCause();
        assertThatThrownBy(() -> ring.candidates(command(), null)).isInstanceOf(IllegalArgumentException.class).hasNoCause();
        String secretInput = "raw-private-key\ninvalid";
        assertThatThrownBy(() -> ring.candidates(command(), secretInput))
                .isInstanceOf(IllegalArgumentException.class).hasNoCause().hasMessageNotContaining(secretInput);
        assertThat(ring.candidates(command(), REQUEST_KEY)).hasSize(2);
    }

    @Test
    void diagnosticsDoNotExposeEncodedOrDecodedSecrets() {
        String plain = "synthetic-private-key-32-bytes!!!";
        // Keep the diagnostic fixture exactly 32 bytes without relying on its human-readable length.
        byte[] secret = Arrays.copyOf(plain.getBytes(StandardCharsets.UTF_8), 32);
        String encoded = Base64.getEncoder().encodeToString(secret);
        var ring = new LegalIdempotencyKeyring(Map.of(1, encoded), 1);
        assertThat(ring.toString()).contains("REDACTED").doesNotContain(encoded, plain);
        String invalid = encoded.substring(0, 43) + "!";
        assertThatThrownBy(() -> new LegalIdempotencyKeyring(Map.of(1, invalid), 1))
                .isInstanceOf(IllegalArgumentException.class).hasNoCause().hasMessageNotContaining(invalid).hasMessageNotContaining(encoded);
    }

    @Test
    void exceptionsFromInputMapCannotSmuggleSecretsIntoConfigurationCauses() {
        String privateValue = encoded(4);
        Map<Integer, String> failing = new HashMap<>(Map.of(1, privateValue)) {
            @Override public Set<Entry<Integer, String>> entrySet() {
                throw new IllegalStateException("map-secret=" + privateValue, new IllegalArgumentException(privateValue));
            }
        };
        assertThatThrownBy(() -> new LegalIdempotencyKeyring(failing, 1))
                .isInstanceOf(IllegalArgumentException.class).hasNoCause().hasMessageNotContaining(privateValue);
    }

    private static LegalAcceptanceCommand command() {
        return LegalAcceptanceCommandValidator.authenticated(
                new LegalActorSnapshot(12, 34, UserRole.USER, 0, true, true), "sha256:" + "a".repeat(64), List.of());
    }

    private static byte[] bytes(int value) {
        byte[] secret = new byte[32];
        Arrays.fill(secret, (byte) value);
        return secret;
    }

    private static String encoded(int value) {
        return Base64.getEncoder().encodeToString(bytes(value));
    }

    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .hasMessage("La configuración del keyring idempotente no es válida").isInstanceOf(IllegalArgumentException.class).hasNoCause();
    }
}
