package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequestMetadata;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoCampoMetadataLegal;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.UUID;

/** Write-only AES-GCM preparation, independent of the HMAC, JWT and device-credential keyrings. */
public final class LegalAcceptanceMetadataCodec {
    private static final int SECRET_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BYTES = 16;
    private static final int MAX_KEYS = 8;

    private final NavigableMap<Integer, byte[]> secrets;
    private final int activeWriteVersion;
    private final SecureRandom random;
    private final Object ownership = new Object();

    public LegalAcceptanceMetadataCodec(Map<Integer, String> base64Keys, int activeWriteVersion) {
        this(base64Keys, activeWriteVersion, new SecureRandom());
    }

    // Deterministic sources are available only inside this package for cryptographic/SQL tests.
    LegalAcceptanceMetadataCodec(Map<Integer, String> base64Keys, int activeWriteVersion, SecureRandom random) {
        TreeMap<Integer, byte[]> prepared = new TreeMap<>();
        boolean complete = false;
        try {
            requireConfiguration(base64Keys != null && !base64Keys.isEmpty() && base64Keys.size() <= MAX_KEYS);
            requireConfiguration(activeWriteVersion > 0 && random != null);
            TreeMap<Integer, String> encodedSnapshot = new TreeMap<>(base64Keys);
            requireConfiguration(!encodedSnapshot.isEmpty() && encodedSnapshot.size() <= MAX_KEYS
                    && encodedSnapshot.containsKey(activeWriteVersion));
            for (var entry : encodedSnapshot.entrySet()) {
                requireConfiguration(entry.getKey() != null && entry.getKey() > 0);
                byte[] decoded = decode(entry.getValue());
                boolean transferred = false;
                try {
                    for (byte[] existing : prepared.values()) {
                        requireConfiguration(!MessageDigest.isEqual(existing, decoded));
                    }
                    prepared.put(entry.getKey(), decoded);
                    transferred = true;
                } finally {
                    if (!transferred) Arrays.fill(decoded, (byte) 0);
                }
            }
            this.secrets = Collections.unmodifiableNavigableMap(prepared);
            this.activeWriteVersion = activeWriteVersion;
            this.random = random;
            complete = true;
        } catch (RuntimeException failure) {
            // A decoder or caller-owned map may include the secret in its message or cause.
            throw invalidConfiguration();
        } finally {
            if (!complete) prepared.values().forEach(secret -> Arrays.fill(secret, (byte) 0));
        }
    }

    public PreparedMetadata prepare(UUID lotId, LegalRequestMetadata metadata) {
        if (lotId == null || metadata == null) throw invalidInput();
        try {
            List<EncryptedField> fields = new ArrayList<>(2);
            fields.add(encrypt(lotId, TipoCampoMetadataLegal.IP, metadata.ipAddress(), fields));
            if (metadata.userAgent() != null) {
                fields.add(encrypt(lotId, TipoCampoMetadataLegal.USER_AGENT, metadata.userAgent(), fields));
            }
            return new PreparedMetadata(ownership, lotId, fields);
        } catch (GeneralSecurityException | RuntimeException failure) {
            // Do not leak provider, nonce-source or input diagnostics, or return partially prepared fields.
            throw encryptionUnavailable();
        }
    }

    boolean owns(PreparedMetadata prepared) {
        return prepared != null && prepared.ownership == ownership;
    }

    @Override
    public String toString() {
        return "LegalAcceptanceMetadataCodec[versions=" + secrets.navigableKeySet()
                + ", activeWriteVersion=" + activeWriteVersion + ", secrets=REDACTED]";
    }

    private EncryptedField encrypt(UUID lotId, TipoCampoMetadataLegal type, String plaintext,
                                   List<EncryptedField> previous) throws GeneralSecurityException {
        // The opaque core value already validates literal IPs and exact, bounded Unicode UA text.
        if (plaintext == null || plaintext.isEmpty()) throw invalidInput();
        int originalLength = plaintext.codePointCount(0, plaintext.length());
        if (type == TipoCampoMetadataLegal.USER_AGENT && originalLength > 512) throw invalidInput();
        byte[] cleartext = plaintext.getBytes(StandardCharsets.UTF_8);
        byte[] workingSecret = secrets.get(activeWriteVersion).clone();
        byte[] encrypted = null;
        try {
            if (cleartext.length == 0 || cleartext.length > 2_048) throw invalidInput();
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            for (EncryptedField field : previous) {
                // The frozen SQL unique constraint covers other operations and retained tombstones.
                if (MessageDigest.isEqual(field.nonce, nonce)) throw encryptionUnavailable();
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(workingSecret, "AES"),
                    new GCMParameterSpec(TAG_BYTES * 8, nonce));
            cipher.updateAAD(("ordenfix:legal-metadata:v1:" + lotId + ":" + type.name() + ":" + activeWriteVersion)
                    .getBytes(StandardCharsets.US_ASCII));
            encrypted = cipher.doFinal(cleartext);
            int ciphertextLength = encrypted.length - TAG_BYTES;
            if (ciphertextLength != cleartext.length || ciphertextLength <= 0) throw encryptionUnavailable();
            return new EncryptedField(type, activeWriteVersion, nonce,
                    Arrays.copyOf(encrypted, ciphertextLength),
                    Arrays.copyOfRange(encrypted, ciphertextLength, encrypted.length), originalLength);
        } finally {
            Arrays.fill(cleartext, (byte) 0);
            Arrays.fill(workingSecret, (byte) 0);
            if (encrypted != null) Arrays.fill(encrypted, (byte) 0);
        }
    }

    private static byte[] decode(String encoded) {
        byte[] decoded = null;
        try {
            requireConfiguration(encoded != null && encoded.length() == 44);
            decoded = Base64.getDecoder().decode(encoded);
            requireConfiguration(decoded.length == SECRET_BYTES && Base64.getEncoder().encodeToString(decoded).equals(encoded));
            return decoded;
        } catch (RuntimeException failure) {
            if (decoded != null) Arrays.fill(decoded, (byte) 0);
            throw invalidConfiguration();
        }
    }

    private static void requireConfiguration(boolean condition) {
        if (!condition) throw invalidConfiguration();
    }

    private static IllegalArgumentException invalidConfiguration() {
        return new IllegalArgumentException("El keyring de metadata legal no es válido");
    }

    private static IllegalArgumentException invalidInput() {
        return new IllegalArgumentException("La entrada de metadata legal no es válida");
    }

    private static IllegalStateException encryptionUnavailable() {
        return new IllegalStateException("No se pudo proteger la metadata legal");
    }

    public static final class PreparedMetadata {
        private final Object ownership;
        private final UUID lotId;
        private final List<EncryptedField> fields;

        private PreparedMetadata(Object ownership, UUID lotId, List<EncryptedField> fields) {
            this.ownership = ownership;
            this.lotId = lotId;
            this.fields = List.copyOf(fields);
        }

        UUID lotId() { return lotId; }
        List<EncryptedField> fields() { return fields; }

        @Override
        public String toString() {
            return "PreparedMetadata[metadata=REDACTED]";
        }
    }

    static final class EncryptedField {
        private final TipoCampoMetadataLegal type;
        private final int keyVersion;
        private final byte[] nonce;
        private final byte[] ciphertext;
        private final byte[] tag;
        private final int originalLength;

        private EncryptedField(TipoCampoMetadataLegal type, int keyVersion, byte[] nonce,
                               byte[] ciphertext, byte[] tag, int originalLength) {
            this.type = type;
            this.keyVersion = keyVersion;
            this.nonce = nonce.clone();
            this.ciphertext = ciphertext.clone();
            this.tag = tag.clone();
            this.originalLength = originalLength;
        }

        TipoCampoMetadataLegal tipo() { return type; }
        int keyVersion() { return keyVersion; }
        byte[] nonce() { return nonce.clone(); }
        byte[] ciphertext() { return ciphertext.clone(); }
        byte[] tag() { return tag.clone(); }
        int originalLength() { return originalLength; }

        @Override
        public String toString() {
            return "EncryptedField[metadata=REDACTED]";
        }
    }
}
