package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalIdempotencyFingerprint;

import java.security.MessageDigest;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * One immutable retained-key snapshot. Construction does not accredit retention or deployment
 * across replicas; the caller must complete that protocol before serving an idempotent write.
 */
public final class LegalIdempotencyKeyring {
    private static final int MAX_KEYS = 8;
    private static final int SECRET_BYTES = 32;
    private static final Duration MINIMUM_RESULT_TTL = Duration.ofHours(24);
    private static final Duration DEFAULT_RESULT_TTL = Duration.ofHours(25);

    private final NavigableMap<Integer, byte[]> secrets;
    private final List<Integer> versions;
    private final int activeWriteVersion;
    private final Duration resultTtl;

    public LegalIdempotencyKeyring(Map<Integer, String> base64Keys, int activeWriteVersion) {
        this(base64Keys, activeWriteVersion, DEFAULT_RESULT_TTL);
    }

    public LegalIdempotencyKeyring(Map<Integer, String> base64Keys, int activeWriteVersion, Duration resultTtl) {
        TreeMap<Integer, byte[]> prepared = new TreeMap<>();
        boolean complete = false;
        try {
            require(base64Keys != null && !base64Keys.isEmpty() && base64Keys.size() <= MAX_KEYS);
            require(activeWriteVersion > 0 && resultTtl != null && resultTtl.compareTo(MINIMUM_RESULT_TTL) >= 0);
            // Future monotonic budgets and expiry computations must not silently overflow nanoseconds.
            resultTtl.toNanos();
            TreeMap<Integer, String> encodedSnapshot = new TreeMap<>(base64Keys);
            require(!encodedSnapshot.isEmpty() && encodedSnapshot.size() <= MAX_KEYS
                    && encodedSnapshot.containsKey(activeWriteVersion));
            for (var entry : encodedSnapshot.entrySet()) {
                require(entry.getKey() != null && entry.getKey() > 0);
                byte[] decoded = decode(entry.getValue());
                boolean transferred = false;
                try {
                    for (byte[] existing : prepared.values()) require(!MessageDigest.isEqual(existing, decoded));
                    prepared.put(entry.getKey(), decoded);
                    transferred = true;
                } finally {
                    if (!transferred) Arrays.fill(decoded, (byte) 0);
                }
            }
            this.secrets = Collections.unmodifiableNavigableMap(prepared);
            this.versions = List.copyOf(prepared.navigableKeySet());
            this.activeWriteVersion = activeWriteVersion;
            this.resultTtl = resultTtl;
            complete = true;
        } catch (RuntimeException failure) {
            // Decoder/map failures may include the supplied value. Never retain their diagnostic or cause.
            throw invalidConfiguration();
        } finally {
            if (!complete) prepared.values().forEach(secret -> Arrays.fill(secret, (byte) 0));
        }
    }

    public int activeWriteVersion() {
        return activeWriteVersion;
    }

    public Duration resultTtl() {
        return resultTtl;
    }

    public List<Integer> versions() {
        return versions;
    }

    /** Stable natural version order includes every retained key, regardless of the active writer. */
    public List<LegalIdempotencyFingerprint> candidates(LegalAcceptanceCommand command, String idempotencyKey) {
        if (command == null || idempotencyKey == null) throw invalidCandidate();
        try {
            List<LegalIdempotencyFingerprint> result = new ArrayList<>(versions.size());
            for (var entry : secrets.entrySet()) {
                byte[] workingSecret = entry.getValue().clone();
                try {
                    result.add(LegalIdempotencyFingerprint.derive(command, idempotencyKey, entry.getKey(), workingSecret));
                } finally {
                    Arrays.fill(workingSecret, (byte) 0);
                }
            }
            // No candidate set escapes unless all retained versions were derived successfully.
            return List.copyOf(result);
        } catch (IllegalArgumentException failure) {
            throw invalidCandidate();
        } catch (RuntimeException failure) {
            throw new IllegalStateException("No se pudieron calcular los candidatos idempotentes");
        }
    }

    /** Compute before a future INSERT; an unrepresentable expiration cannot become durable success. */
    public Instant expiresAt(Instant completedAt) {
        if (completedAt == null) throw invalidExpiration();
        try {
            Instant expiration = completedAt.plus(resultTtl);
            if (!expiration.isAfter(completedAt)) throw invalidExpiration();
            return expiration;
        } catch (DateTimeException | ArithmeticException failure) {
            throw invalidExpiration();
        }
    }

    @Override
    public String toString() {
        return "LegalIdempotencyKeyring[versions=" + versions + ", activeWriteVersion=" + activeWriteVersion
                + ", resultTtl=" + resultTtl + ", secrets=REDACTED]";
    }

    private static byte[] decode(String encoded) {
        byte[] decoded = null;
        try {
            // Exactly 32 bytes produce 44 padded standard-Base64 characters; bound input before decoding.
            require(encoded != null && encoded.length() == 44);
            decoded = Base64.getDecoder().decode(encoded);
            require(decoded.length == SECRET_BYTES && Base64.getEncoder().encodeToString(decoded).equals(encoded));
            return decoded;
        } catch (RuntimeException failure) {
            if (decoded != null) Arrays.fill(decoded, (byte) 0);
            throw invalidConfiguration();
        }
    }

    private static void require(boolean condition) {
        if (!condition) throw invalidConfiguration();
    }

    private static IllegalArgumentException invalidConfiguration() {
        return new IllegalArgumentException("La configuración del keyring idempotente no es válida");
    }

    private static IllegalArgumentException invalidCandidate() {
        return new IllegalArgumentException("La entrada de candidatos idempotentes no es válida");
    }

    private static IllegalArgumentException invalidExpiration() {
        return new IllegalArgumentException("El vencimiento idempotente no es representable");
    }
}
