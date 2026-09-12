package com.leonardorozza.mvgrreparacionesbackend.cuenta.export;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;

/** Authenticated temporary artifacts. This component neither grants access nor publishes downloads. */
public final class ExportArtifactCodec {
    public static final int MAX_CIPHERTEXT_BYTES = 140 * 1024 * 1024;
    public static final int MAX_PHOTO_BYTES = 64 * 1024 * 1024;
    public static final int MAX_ARCHIVE_CONTENT_BYTES = 128 * 1024 * 1024;
    private static final byte[] MAGIC = "OFXART01".getBytes(StandardCharsets.US_ASCII);
    private static final int HEADER_BYTES = 8 + 4 + 1 + 12 + 4;
    private static final int TAG_BYTES = 16;
    private final NavigableMap<Integer, byte[]> keys;
    private final int activeVersion;
    private final SecureRandom random;

    public record Context(UUID jobId, long tallerId, long actorId) {
        public Context {
            if (jobId == null || tallerId <= 0 || actorId <= 0) throw invalid();
        }
    }

    /** The caller must obtain each binary through the current private-photo authorization protocol. */
    public static final class PhotoFile {
        private final UUID id;
        private final byte[] content;
        public PhotoFile(UUID id, byte[] content) {
            if (id == null || content == null || content.length < 12 || content.length > 8_000_000) throw invalid();
            this.id = id; this.content = content.clone();
        }
        public UUID id() { return id; }
        public byte[] content() { return content.clone(); }
        int size() { return content.length; }
        @Override public String toString() { return "PhotoFile[bytes=" + content.length + "]"; }
    }

    public ExportArtifactCodec(Map<Integer, String> base64Keyring, int activeVersion) {
        this(base64Keyring, activeVersion, new SecureRandom());
    }

    ExportArtifactCodec(Map<Integer, String> base64Keyring, int activeVersion, SecureRandom random) {
        TreeMap<Integer, byte[]> prepared = new TreeMap<>();
        boolean successful = false;
        try {
            if (base64Keyring == null || base64Keyring.isEmpty() || base64Keyring.size() > 8
                    || activeVersion <= 0 || random == null) throw new IllegalArgumentException();
            for (var entry : new TreeMap<>(base64Keyring).entrySet()) {
                if (entry.getKey() == null || entry.getKey() <= 0 || entry.getValue() == null || entry.getValue().length() != 44) throw new IllegalArgumentException();
                byte[] decoded = Base64.getDecoder().decode(entry.getValue());
                boolean retained = false;
                try {
                    if (decoded.length != 32 || !Base64.getEncoder().encodeToString(decoded).equals(entry.getValue())) throw new IllegalArgumentException();
                    for (byte[] previous : prepared.values()) if (MessageDigest.isEqual(previous, decoded)) throw new IllegalArgumentException();
                    prepared.put(entry.getKey(), decoded); retained = true;
                } finally { if (!retained) Arrays.fill(decoded, (byte) 0); }
            }
            if (!prepared.containsKey(activeVersion)) throw new IllegalArgumentException();
            this.keys = Collections.unmodifiableNavigableMap(prepared);
            this.activeVersion = activeVersion;
            this.random = random;
            successful = true;
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("El keyring de exportación no es válido.");
        } finally { if (!successful) prepared.values().forEach(value -> Arrays.fill(value, (byte) 0)); }
    }

    public byte[] encryptSnapshot(Context context, ExportSnapshot snapshot) {
        requireContext(context, snapshot);
        byte[] plaintext = ExportArtifactSnapshotCodec.encode(snapshot);
        try { return encrypt(context, (byte) 1, plaintext); }
        finally { Arrays.fill(plaintext, (byte) 0); }
    }

    public ExportSnapshot decryptSnapshot(Context context, byte[] ciphertext) {
        byte[] plaintext = decrypt(context, (byte) 1, ciphertext);
        try {
            ExportSnapshot snapshot = ExportArtifactSnapshotCodec.decode(plaintext);
            requireContext(context, snapshot);
            return snapshot;
        } finally { Arrays.fill(plaintext, (byte) 0); }
    }

    public byte[] archive(Context context, ExportSnapshot snapshot, List<PhotoFile> photos) {
        requireContext(context, snapshot);
        byte[] plaintext = ExportArtifactArchiveWriter.write(snapshot, photos);
        try { return encrypt(context, (byte) 2, plaintext); }
        finally { Arrays.fill(plaintext, (byte) 0); }
    }

    /** Returns authenticated ZIP bytes only; authorization, expiry and revocation remain the job service's responsibility. */
    public byte[] decryptArchive(Context context, byte[] ciphertext) { return decrypt(context, (byte) 2, ciphertext); }

    private byte[] encrypt(Context context, byte kind, byte[] plaintext) {
        if (context == null || plaintext.length > MAX_CIPHERTEXT_BYTES - HEADER_BYTES - TAG_BYTES) throw capacity();
        byte[] workingKey = keys.get(activeVersion).clone();
        byte[] encrypted = null;
        try {
            byte[] nonce = new byte[12]; random.nextBytes(nonce);
            byte[] header = ByteBuffer.allocate(HEADER_BYTES).put(MAGIC).putInt(activeVersion).put(kind).put(nonce).putInt(plaintext.length).array();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(workingKey, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(header); cipher.updateAAD(contextBytes(context));
            encrypted = cipher.doFinal(plaintext);
            return ByteBuffer.allocate(header.length + encrypted.length).put(header).put(encrypted).array();
        } catch (GeneralSecurityException | RuntimeException failure) {
            if (failure instanceof ExportPackageException known) throw known;
            throw new ExportPackageException(ExportPackageException.Code.UNAVAILABLE);
        } finally {
            Arrays.fill(workingKey, (byte) 0);
            if (encrypted != null) Arrays.fill(encrypted, (byte) 0);
        }
    }

    private byte[] decrypt(Context context, byte kind, byte[] ciphertext) {
        if (context == null || ciphertext == null || ciphertext.length < HEADER_BYTES + TAG_BYTES
                || ciphertext.length > MAX_CIPHERTEXT_BYTES) throw invalid();
        byte[] workingKey = null;
        try {
            ByteBuffer header = ByteBuffer.wrap(ciphertext, 0, HEADER_BYTES);
            byte[] magic = new byte[MAGIC.length]; header.get(magic);
            int version = header.getInt(); byte encodedKind = header.get();
            byte[] nonce = new byte[12]; header.get(nonce);
            int plaintextLength = header.getInt();
            if (!Arrays.equals(magic, MAGIC) || encodedKind != kind || !keys.containsKey(version)
                    || plaintextLength < 0 || plaintextLength != ciphertext.length - HEADER_BYTES - TAG_BYTES) throw invalid();
            workingKey = keys.get(version).clone();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(workingKey, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(ciphertext, 0, HEADER_BYTES); cipher.updateAAD(contextBytes(context));
            // No CipherInputStream and no plaintext callback: doFinal must authenticate the entire artifact first.
            return cipher.doFinal(ciphertext, HEADER_BYTES, ciphertext.length - HEADER_BYTES);
        } catch (GeneralSecurityException | RuntimeException failure) {
            throw invalid();
        } finally { if (workingKey != null) Arrays.fill(workingKey, (byte) 0); }
    }

    private static byte[] contextBytes(Context context) {
        return ByteBuffer.allocate(32).putLong(context.jobId().getMostSignificantBits()).putLong(context.jobId().getLeastSignificantBits())
                .putLong(context.tallerId()).putLong(context.actorId()).array();
    }
    private static void requireContext(Context context, ExportSnapshot snapshot) {
        if (context == null || snapshot == null || context.actorId() != snapshot.actorId() || context.tallerId() != snapshot.tallerId()) throw invalid();
    }
    static ExportPackageException invalid() { return new ExportPackageException(ExportPackageException.Code.INVALID_PACKAGE); }
    static ExportPackageException capacity() { return new ExportPackageException(ExportPackageException.Code.CAPACITY_EXCEEDED); }
    @Override public String toString() { return "ExportArtifactCodec[versions=" + keys.navigableKeySet() + ", active=" + activeVersion + ", keys=REDACTED]"; }
}
