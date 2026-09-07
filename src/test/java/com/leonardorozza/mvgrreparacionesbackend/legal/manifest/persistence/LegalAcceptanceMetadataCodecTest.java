package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequestMetadata;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoCampoMetadataLegal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegalAcceptanceMetadataCodecTest {
    private static final UUID LOT = UUID.fromString("6f7d8fb8-6f90-4c47-a45c-4fbe86e67f70");
    private static final String IP = "203.0.113.42";

    @Test
    void matchesTheFixedAes256GcmVectorWithExactContractAadAndSeparateTag() throws Exception {
        byte[] secret = sequence(32);
        byte[] nonce = sequence(12);
        var codec = new LegalAcceptanceMetadataCodec(Map.of(7, encode(secret)), 7, fixed(nonce));
        var prepared = codec.prepare(LOT, metadata(null));
        var field = prepared.fields().getFirst();
        assertThat(prepared.lotId()).isEqualTo(LOT);
        assertThat(prepared.fields()).hasSize(1);
        assertThat(field.tipo()).isEqualTo(TipoCampoMetadataLegal.IP);
        assertThat(field.keyVersion()).isEqualTo(7);
        assertThat(field.nonce()).containsExactly(nonce);
        // Fixture generated independently with Java 21 JCE, outside the production codec.
        assertThat(HexFormat.of().formatHex(field.ciphertext())).isEqualTo("7532e535f5cbf32abe6fa3b9");
        assertThat(HexFormat.of().formatHex(field.tag())).isEqualTo("a0aac64c16d7425c3989fe41baa57917");
        assertThat(field.originalLength()).isEqualTo(IP.length());
        assertThat(decrypt(secret, field, aad(LOT, "IP", 7))).isEqualTo(IP);
    }

    @Test
    void preservesExactUserAgentAndCountsUnicodeScalarsInsteadOfBytesOrUtf16Units() throws Exception {
        String agent = "🛠".repeat(512);
        var codec = codec(new CounterRandom());
        var prepared = codec.prepare(LOT, metadata(agent));
        var ip = prepared.fields().get(0);
        var ua = prepared.fields().get(1);
        assertThat(prepared.fields()).extracting(LegalAcceptanceMetadataCodec.EncryptedField::tipo)
                .containsExactly(TipoCampoMetadataLegal.IP, TipoCampoMetadataLegal.USER_AGENT);
        assertThat(ua.originalLength()).isEqualTo(512);
        assertThat(ua.ciphertext()).hasSize(2_048);
        assertThat(ua.tag()).hasSize(16);
        assertThat(ua.nonce()).hasSize(12).isNotEqualTo(ip.nonce());
        assertThat(decrypt(key(1), ua, aad(LOT, "USER_AGENT", 1))).isEqualTo(agent);
        String exact = "  Browser/e\u0301 (🛠)  ";
        var exactField = codec.prepare(LOT, metadata(exact)).fields().get(1);
        assertThat(decrypt(key(1), exactField, aad(LOT, "USER_AGENT", 1))).isEqualTo(exact);
    }

    @Test
    void omitsAbsentOrEmptyUserAgentButNeverOmitsIp() {
        var codec = codec(new CounterRandom());
        assertThat(codec.prepare(LOT, metadata(null)).fields()).hasSize(1);
        assertThat(codec.prepare(LOT, metadata("")).fields()).hasSize(1);
    }

    @ParameterizedTest
    @MethodSource("invalidUserAgents")
    void invalidUnicodeAndExcessiveUaCannotReachEncryption(String agent) {
        var random = new CounterRandom();
        var codec = codec(random);
        assertThatThrownBy(() -> codec.prepare(LOT, metadata(agent)))
                .isInstanceOf(IllegalArgumentException.class).hasNoCause().hasMessageNotContaining(agent);
        assertThat(random.count.get()).isZero();
    }

    static Stream<String> invalidUserAgents() {
        return Stream.of("🛠".repeat(513), "private\ud800", "private\udc00", "private\u0000", "private\r\n", "private\u0085");
    }

    @ParameterizedTest
    @MethodSource("incorrectAad")
    void independentAuthenticatedDecryptionRejectsChangedBinding(String incorrect) {
        var field = codec(fixed(sequence(12))).prepare(LOT, metadata(null)).fields().getFirst();
        assertThatThrownBy(() -> decrypt(key(1), field, incorrect))
                .isInstanceOf(AEADBadTagException.class);
    }

    static Stream<String> incorrectAad() {
        return Stream.of(aad(UUID.fromString("6f7d8fb8-6f90-4c47-a45c-4fbe86e67f71"), "IP", 1),
                aad(LOT, "USER_AGENT", 1), aad(LOT, "IP", 2),
                "ordenfix:legal-metadata:v2:" + LOT + ":IP:1",
                "ordenfix:legal-metadata:v1:" + LOT + ":ip:1",
                "reparacion:12:pin:v1", "", aad(LOT, "IP", 1) + " ");
    }

    @ParameterizedTest
    @ValueSource(strings = {"nonce", "ciphertext", "tag"})
    void independentDecryptionRejectsEachTamperedCipherComponent(String component) {
        var field = codec(fixed(sequence(12))).prepare(LOT, metadata(null)).fields().getFirst();
        byte[] nonce = field.nonce();
        byte[] ciphertext = field.ciphertext();
        byte[] tag = field.tag();
        switch (component) {
            case "nonce" -> nonce[0] ^= 1;
            case "ciphertext" -> ciphertext[0] ^= 1;
            case "tag" -> tag[0] ^= 1;
            default -> throw new AssertionError("fixture");
        }
        assertThatThrownBy(() -> decrypt(key(1), nonce, ciphertext, tag, aad(LOT, "IP", 1)))
                .isInstanceOf(AEADBadTagException.class);
    }

    @Test
    void onlyTheActiveRetainedKeyWritesAndOriginalCiphertextsKeepTheirOriginalKeyVersion() throws Exception {
        Map<Integer, String> keys = Map.of(1, encode(key(1)), 7, encode(key(7)));
        var oldCodec = new LegalAcceptanceMetadataCodec(keys, 1, fixed(sequence(12)));
        var newCodec = new LegalAcceptanceMetadataCodec(keys, 7, fixed(sequence(12)));
        var oldField = oldCodec.prepare(LOT, metadata(null)).fields().getFirst();
        var newField = newCodec.prepare(LOT, metadata(null)).fields().getFirst();
        assertThat(oldField.keyVersion()).isEqualTo(1);
        assertThat(newField.keyVersion()).isEqualTo(7);
        assertThat(decrypt(key(1), oldField, aad(LOT, "IP", 1))).isEqualTo(IP);
        assertThat(decrypt(key(7), newField, aad(LOT, "IP", 7))).isEqualTo(IP);
        assertThatThrownBy(() -> decrypt(key(7), oldField, aad(LOT, "IP", 1))).isInstanceOf(AEADBadTagException.class);
        assertThatThrownBy(() -> decrypt(key(1), newField, aad(LOT, "IP", 7))).isInstanceOf(AEADBadTagException.class);
    }

    @Test
    void independentRingsWithTheSameVersionCannotDecryptUsingDifferentSecretBytes() {
        var metadataRing = new LegalAcceptanceMetadataCodec(Map.of(1, encode(key(7))), 1, fixed(sequence(12)));
        var field = metadataRing.prepare(LOT, metadata(null)).fields().getFirst();
        byte[] separateDomainSecret = key(1);
        assertThatThrownBy(() -> decrypt(separateDomainSecret, field, aad(LOT, "IP", 1)))
                .isInstanceOf(AEADBadTagException.class);
    }

    @Test
    void acceptsEightDistinctKeysAndDoesNotRenumberPositiveIntVersions() {
        Map<Integer, String> keys = new LinkedHashMap<>();
        for (int index = 8; index > 0; index--) keys.put(index, encode(key(index)));
        var codec = new LegalAcceptanceMetadataCodec(keys, 5, fixed(sequence(12)));
        assertThat(codec.prepare(LOT, metadata(null)).fields().getFirst().keyVersion()).isEqualTo(5);
        var maximum = new LegalAcceptanceMetadataCodec(Map.of(Integer.MAX_VALUE, encode(key(1))), Integer.MAX_VALUE);
        assertThat(maximum.prepare(LOT, metadata(null)).fields().getFirst().keyVersion()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void rejectsNullEmptyNinthAndDuplicateKeysWithoutDroppingAnyRetainedKey() {
        assertInvalid(() -> new LegalAcceptanceMetadataCodec(null, 1));
        assertInvalid(() -> new LegalAcceptanceMetadataCodec(Map.of(), 1));
        Map<Integer, String> excessive = new HashMap<>();
        for (int version = 1; version <= 9; version++) excessive.put(version, encode(key(version)));
        assertInvalid(() -> new LegalAcceptanceMetadataCodec(excessive, 1));
        assertInvalid(() -> new LegalAcceptanceMetadataCodec(Map.of(1, encode(key(1)), 7, encode(key(1))), 1));
        assertInvalid(() -> new LegalAcceptanceMetadataCodec(Map.of(1, encode(key(1))), 1, null));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void everyKeyVersionMustBePositiveEvenIfNotActive(int version) {
        assertInvalid(() -> new LegalAcceptanceMetadataCodec(Map.of(1, encode(key(1)), version, encode(key(2))), 1));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 2, Integer.MAX_VALUE})
    void activeVersionMustBePositiveAndPresent(int active) {
        assertInvalid(() -> new LegalAcceptanceMetadataCodec(Map.of(1, encode(key(1))), active));
    }

    @Test
    void nullVersionsAndSecretsAreRejectedWithSanitizedDiagnostics() {
        Map<Integer, String> nullVersion = new HashMap<>();
        nullVersion.put(null, encode(key(1)));
        assertInvalid(() -> new LegalAcceptanceMetadataCodec(nullVersion, 1));
        Map<Integer, String> nullSecret = new HashMap<>();
        nullSecret.put(1, null);
        assertInvalid(() -> new LegalAcceptanceMetadataCodec(nullSecret, 1));
    }

    @ParameterizedTest
    @MethodSource("invalidEncodings")
    void acceptsOnlyCanonicalPaddedStandardBase64OfThirtyTwoBytes(String invalid) {
        assertInvalid(() -> new LegalAcceptanceMetadataCodec(Map.of(1, invalid), 1));
        assertInvalid(() -> new LegalAcceptanceMetadataCodec(Map.of(1, encode(key(1)), 2, invalid), 1));
    }

    static Stream<String> invalidEncodings() {
        String zero = encode(key(0));
        return Stream.of("", "private-raw-key", zero.substring(0, 43), zero + " ", " " + zero,
                zero.substring(0, 12) + "\n" + zero.substring(13),
                encode(new byte[31]), encode(new byte[33]),
                Base64.getUrlEncoder().encodeToString(key(255)), zero.substring(0, 42) + "B=");
    }

    @Test
    void inputMapMutationCannotChangeTheConstructedKeySnapshot() throws Exception {
        Map<Integer, String> source = new HashMap<>(Map.of(1, encode(key(1)), 7, encode(key(7))));
        var codec = new LegalAcceptanceMetadataCodec(source, 7, fixed(sequence(12)));
        source.clear();
        source.put(7, encode(key(9)));
        var field = codec.prepare(LOT, metadata(null)).fields().getFirst();
        assertThat(decrypt(key(7), field, aad(LOT, "IP", 7))).isEqualTo(IP);
        assertThatThrownBy(() -> decrypt(key(9), field, aad(LOT, "IP", 7))).isInstanceOf(AEADBadTagException.class);
    }

    @Test
    void onlyTheIssuingCodecOwnsAPreparedValueEvenWithTheSameKeys() {
        var first = codec(new CounterRandom());
        var second = codec(new CounterRandom());
        var prepared = first.prepare(LOT, metadata("Browser"));
        assertThat(first.owns(prepared)).isTrue();
        assertThat(second.owns(prepared)).isFalse();
        assertThat(first.owns(null)).isFalse();
    }

    @Test
    void fieldsAndAllExportedArraysAreDefensiveAndCannotChangeTheReceipt() throws Exception {
        List<byte[]> suppliedBuffers = new ArrayList<>();
        SecureRandom random = new SecureRandom() {
            @Override public void nextBytes(byte[] bytes) {
                Arrays.fill(bytes, (byte) (suppliedBuffers.size() + 1));
                suppliedBuffers.add(bytes);
            }
        };
        var prepared = codec(random).prepare(LOT, metadata("Browser"));
        assertThatThrownBy(prepared.fields()::clear).isInstanceOf(UnsupportedOperationException.class);
        var field = prepared.fields().getFirst();
        byte[] nonce = field.nonce();
        byte[] ciphertext = field.ciphertext();
        byte[] tag = field.tag();
        suppliedBuffers.forEach(buffer -> Arrays.fill(buffer, (byte) 0));
        Arrays.fill(field.nonce(), (byte) 0);
        Arrays.fill(field.ciphertext(), (byte) 0);
        Arrays.fill(field.tag(), (byte) 0);
        assertThat(field.nonce()).containsExactly(nonce);
        assertThat(field.ciphertext()).containsExactly(ciphertext);
        assertThat(field.tag()).containsExactly(tag);
        assertThat(decrypt(key(1), field, aad(LOT, "IP", 1))).isEqualTo(IP);
    }

    @Test
    void duplicateNoncesWithinOnePreparationFailBeforeAnyReceiptEscapes() {
        var codec = codec(fixed(sequence(12)));
        assertThatThrownBy(() -> codec.prepare(LOT, metadata("Browser")))
                .isInstanceOf(IllegalStateException.class).hasNoCause().hasMessageNotContaining(IP).hasMessageNotContaining("Browser");
    }

    @Test
    void nullPrepareArgumentsFailBeforeTheNonceSourceRuns() {
        var random = new CounterRandom();
        var codec = codec(random);
        assertInvalid(() -> codec.prepare(null, metadata(null)));
        assertInvalid(() -> codec.prepare(LOT, null));
        assertThat(random.count.get()).isZero();
    }

    @Test
    void nonceSourceAndCallerMapExceptionsCannotExposeSecretsInMessagesOrCauses() {
        String sensitive = "synthetic-private-key-and-ip-203.0.113.42";
        SecureRandom failingRandom = new SecureRandom() {
            @Override public void nextBytes(byte[] bytes) {
                throw new IllegalStateException(sensitive, new IllegalArgumentException(sensitive));
            }
        };
        assertThatThrownBy(() -> codec(failingRandom).prepare(LOT, metadata(sensitive)))
                .isInstanceOf(IllegalStateException.class).hasNoCause().hasMessageNotContaining(sensitive);
        Map<Integer, String> failingMap = new HashMap<>(Map.of(1, encode(key(1)))) {
            @Override public Set<Entry<Integer, String>> entrySet() {
                throw new IllegalStateException(sensitive, new IllegalArgumentException(sensitive));
            }
        };
        assertThatThrownBy(() -> new LegalAcceptanceMetadataCodec(failingMap, 1))
                .isInstanceOf(IllegalArgumentException.class).hasNoCause().hasMessageNotContaining(sensitive);
    }

    @Test
    void diagnosticStringsNeverContainPlaintextSecretsOrCryptographicMaterial() {
        String userAgent = "private-agent-value";
        String encoded = encode(key(1));
        var codec = codec(new CounterRandom());
        var prepared = codec.prepare(LOT, metadata(userAgent));
        assertThat(codec.toString()).contains("REDACTED").doesNotContain(encoded, IP, userAgent);
        assertThat(prepared.toString()).contains("REDACTED").doesNotContain(encoded, IP, userAgent, LOT.toString());
        for (var field : prepared.fields()) {
            assertThat(field.toString()).contains("REDACTED").doesNotContain(encoded, IP, userAgent,
                    HexFormat.of().formatHex(field.nonce()), HexFormat.of().formatHex(field.ciphertext()),
                    HexFormat.of().formatHex(field.tag()));
        }
    }

    @Test
    void concurrentPreparationKeepsCipherStateAndNoncesIndependent() throws Exception {
        var codec = codec(new CounterRandom());
        List<Callable<LegalAcceptanceMetadataCodec.PreparedMetadata>> jobs = new ArrayList<>();
        for (int index = 0; index < 24; index++) jobs.add(() -> codec.prepare(LOT, metadata("Browser 🛠")));
        List<String> nonces = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(4)) {
            for (var future : executor.invokeAll(jobs, 5, TimeUnit.SECONDS)) {
                var prepared = future.get(1, TimeUnit.SECONDS);
                assertThat(codec.owns(prepared)).isTrue();
                for (var field : prepared.fields()) {
                    String plaintext = field.tipo() == TipoCampoMetadataLegal.IP ? IP : "Browser 🛠";
                    assertThat(decrypt(key(1), field, aad(LOT, field.tipo().name(), 1))).isEqualTo(plaintext);
                    nonces.add(HexFormat.of().formatHex(field.nonce()));
                }
            }
        }
        assertThat(nonces).hasSize(48).doesNotHaveDuplicates();
    }

    private static LegalAcceptanceMetadataCodec codec(SecureRandom random) {
        return new LegalAcceptanceMetadataCodec(Map.of(1, encode(key(1))), 1, random);
    }

    private static LegalRequestMetadata metadata(String userAgent) {
        return LegalRequestMetadata.of(LegalRequestMetadata.parseIpLiteral(IP), userAgent);
    }

    private static String aad(UUID lotId, String type, int version) {
        return "ordenfix:legal-metadata:v1:" + lotId + ":" + type + ":" + version;
    }

    private static String decrypt(byte[] key, LegalAcceptanceMetadataCodec.EncryptedField field, String aad) throws Exception {
        return decrypt(key, field.nonce(), field.ciphertext(), field.tag(), aad);
    }

    private static String decrypt(byte[] key, byte[] nonce, byte[] ciphertext, byte[] tag, String aad) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(aad.getBytes(StandardCharsets.US_ASCII));
        byte[] combined = Arrays.copyOf(ciphertext, ciphertext.length + tag.length);
        System.arraycopy(tag, 0, combined, ciphertext.length, tag.length);
        return new String(cipher.doFinal(combined), StandardCharsets.UTF_8);
    }

    private static SecureRandom fixed(byte[] nonce) {
        byte[] snapshot = nonce.clone();
        return new SecureRandom() {
            @Override public void nextBytes(byte[] target) { System.arraycopy(snapshot, 0, target, 0, target.length); }
        };
    }

    private static byte[] sequence(int length) {
        byte[] bytes = new byte[length];
        for (int index = 0; index < length; index++) bytes[index] = (byte) index;
        return bytes;
    }

    private static byte[] key(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private static String encode(byte[] bytes) { return Base64.getEncoder().encodeToString(bytes); }

    private static void assertInvalid(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(IllegalArgumentException.class).hasNoCause();
    }

    private static final class CounterRandom extends SecureRandom {
        private final AtomicLong count = new AtomicLong();

        @Override public void nextBytes(byte[] bytes) {
            long value = count.incrementAndGet();
            Arrays.fill(bytes, (byte) 0);
            for (int index = 0; index < Long.BYTES; index++) bytes[bytes.length - 1 - index] = (byte) (value >>> (index * 8));
        }
    }
}
