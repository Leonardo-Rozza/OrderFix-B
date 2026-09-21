package com.leonardorozza.mvgrreparacionesbackend.cuenta.recovery;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.leonardorozza.mvgrreparacionesbackend.cuenta.recovery.RecoveryCheckpoint.*;

/** Local external checkpoint only: authenticity is not journal completeness or permission to reopen. */
public final class RecoveryCheckpointFiles {
    public static final int MAX_FILE_BYTES = 4 * 1024 * 1024;
    private static final long MAGIC = 0x4f46585245433031L; // OFXREC01
    private static final int HASH_BYTES = 32;
    private static final int HEADER_BYTES = 60;
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = PosixFilePermissions.fromString("rw-------");
    private static final String FAILURE = "No se pudo leer o guardar el checkpoint de recuperación.";

    /** Parent must already be a trusted private POSIX directory. Existing paths are never replaced. */
    public Receipt write(Path file, Snapshot snapshot, byte[] key) {
        byte[] secret = checkedKey(key);
        try {
            byte[] payload = encode(Objects.requireNonNull(snapshot));
            byte[] signed = Arrays.copyOf(payload, payload.length + HASH_BYTES);
            System.arraycopy(hmac(payload, secret), 0, signed, payload.length, HASH_BYTES);
            Path target = checkedParent(file);
            // CREATE_NEW also refuses dangling links. Permissions apply at creation, not afterward.
            try (FileChannel channel = FileChannel.open(target,
                    Set.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS),
                    PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS))) {
                ByteBuffer content = ByteBuffer.wrap(signed);
                while (content.hasRemaining()) channel.write(content);
                channel.force(true);
            }
            // If durability cannot be confirmed, return no receipt and leave the file for inspection.
            // Never delete a path during recovery from an error: it may no longer name our inode.
            try (FileChannel directory = FileChannel.open(target.getParent(), StandardOpenOption.READ,
                    LinkOption.NOFOLLOW_LINKS)) {
                directory.force(true);
            }
            return new Receipt(snapshot.environmentId(), snapshot.checkpointId(), sha256(signed));
        } catch (IOException | GeneralSecurityException | RuntimeException failure) {
            throw new Rejected();
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }

    /** The expected receipt must come from outside the restored database and the supplied file. */
    public Snapshot read(Path file, byte[] key, Receipt expected) {
        byte[] secret = checkedKey(key);
        try {
            Objects.requireNonNull(expected);
            Path target = checkedParent(file);
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) throw new Rejected();
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(target, LinkOption.NOFOLLOW_LINKS);
            if (!FILE_PERMISSIONS.containsAll(permissions) || !permissions.contains(PosixFilePermission.OWNER_READ))
                throw new Rejected();
            byte[] signed;
            try (FileChannel channel = FileChannel.open(target, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                long size = channel.size();
                if (size < HEADER_BYTES + HASH_BYTES || size > MAX_FILE_BYTES) throw new Rejected();
                signed = new byte[(int) size];
                ByteBuffer content = ByteBuffer.wrap(signed);
                while (content.hasRemaining()) if (channel.read(content) < 0) throw new Rejected();
                // A file that grew or shrank while read is not the selected immutable artifact.
                if (channel.read(ByteBuffer.allocate(1)) != -1 || channel.size() != size) throw new Rejected();
            }
            if (!MessageDigest.isEqual(HexFormat.of().parseHex(expected.sha256()), digest(signed))) throw new Rejected();
            byte[] payload = Arrays.copyOf(signed, signed.length - HASH_BYTES);
            byte[] signature = Arrays.copyOfRange(signed, payload.length, signed.length);
            if (!MessageDigest.isEqual(signature, hmac(payload, secret))) throw new Rejected();
            Snapshot snapshot = decode(payload);
            if (!snapshot.environmentId().equals(expected.environmentId())
                    || !snapshot.checkpointId().equals(expected.checkpointId())) throw new Rejected();
            return snapshot;
        } catch (IOException | GeneralSecurityException | RuntimeException failure) {
            throw new Rejected();
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }

    private static Path checkedParent(Path file) throws IOException {
        Objects.requireNonNull(file);
        if (!file.isAbsolute()) throw new Rejected();
        Path target = file.normalize();
        Path parent = target.getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
                || !Files.getPosixFilePermissions(parent, LinkOption.NOFOLLOW_LINKS).equals(DIRECTORY_PERMISSIONS))
            throw new Rejected();
        return target;
    }

    private static byte[] checkedKey(byte[] key) {
        if (key == null || key.length != HASH_BYTES) throw new Rejected();
        return key.clone();
    }

    private static byte[] encode(Snapshot snapshot) throws IOException {
        List<Surface> captured = surfaces(snapshot.formatVersion());
        int workshopBytes = Long.BYTES + captured.size() * (Long.BYTES + HASH_BYTES);
        int count = snapshot.workshops().size();
        if (count < 0 || count > MAX_WORKSHOPS) throw new Rejected();
        int length = HEADER_BYTES + count * workshopBytes;
        if (length > MAX_FILE_BYTES - HASH_BYTES) throw new Rejected();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(length);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeLong(MAGIC);
            out.writeInt(snapshot.formatVersion());
            uuid(out, snapshot.environmentId());
            uuid(out, snapshot.checkpointId());
            out.writeLong(snapshot.observedAt().getEpochSecond());
            out.writeInt(snapshot.observedAt().getNano());
            out.writeInt(count);
            long previous = 0;
            for (Workshop workshop : snapshot.workshops()) {
                if (workshop.tallerId() <= previous || workshop.surfaces().size() != captured.size()) throw new Rejected();
                previous = workshop.tallerId();
                out.writeLong(workshop.tallerId());
                for (Surface surface : captured) {
                    Digest value = Objects.requireNonNull(workshop.surfaces().get(surface));
                    if (value.rows() < 0 || value.rows() > MAX_ROWS_PER_SURFACE) throw new Rejected();
                    out.writeLong(value.rows());
                    byte[] hash = HexFormat.of().parseHex(value.sha256());
                    if (hash.length != HASH_BYTES) throw new Rejected();
                    out.write(hash);
                }
            }
        }
        if (bytes.size() != length) throw new Rejected();
        return bytes.toByteArray();
    }

    private static Snapshot decode(byte[] payload) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (in.readLong() != MAGIC) throw new Rejected();
            int formatVersion = in.readInt();
            List<Surface> captured = surfaces(formatVersion);
            int workshopBytes = Long.BYTES + captured.size() * (Long.BYTES + HASH_BYTES);
            UUID environment = uuid(in), checkpoint = uuid(in);
            long seconds = in.readLong();
            int nanos = in.readInt();
            if (nanos < 0 || nanos > 999_999_999) throw new Rejected();
            Instant observed = Instant.ofEpochSecond(seconds, nanos);
            int count = in.readInt();
            // Validate dimensions and exact byte count before allocating any records from the file.
            if (count < 0 || count > MAX_WORKSHOPS || payload.length != HEADER_BYTES + count * workshopBytes)
                throw new Rejected();
            var workshops = new ArrayList<Workshop>(count);
            long[] totals = new long[Surface.values().length];
            long previous = 0;
            for (int index = 0; index < count; index++) {
                long taller = in.readLong();
                if (taller <= previous) throw new Rejected();
                previous = taller;
                var surfaces = new EnumMap<Surface, Digest>(Surface.class);
                for (Surface surface : captured) {
                    long rows = in.readLong();
                    if (rows < 0 || rows > MAX_ROWS_PER_SURFACE) throw new Rejected();
                    totals[surface.ordinal()] += rows;
                    if (totals[surface.ordinal()] > MAX_ROWS_PER_SURFACE) throw new Rejected();
                    byte[] hash = new byte[HASH_BYTES];
                    in.readFully(hash);
                    surfaces.put(surface, new Digest(rows, HexFormat.of().formatHex(hash)));
                }
                workshops.add(new Workshop(taller, surfaces));
            }
            if (in.read() != -1) throw new Rejected();
            return new Snapshot(formatVersion, environment, checkpoint, observed, workshops);
        }
    }

    private static void uuid(DataOutputStream out, UUID value) throws IOException {
        out.writeLong(value.getMostSignificantBits());
        out.writeLong(value.getLeastSignificantBits());
    }
    private static UUID uuid(DataInputStream in) throws IOException { return new UUID(in.readLong(), in.readLong()); }
    private static byte[] digest(byte[] value) throws GeneralSecurityException {
        return MessageDigest.getInstance("SHA-256").digest(value);
    }
    private static String sha256(byte[] value) throws GeneralSecurityException { return HexFormat.of().formatHex(digest(value)); }
    private static byte[] hmac(byte[] value, byte[] key) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(value);
    }

    public record Receipt(UUID environmentId, UUID checkpointId, String sha256) {
        public Receipt {
            if (environmentId == null || checkpointId == null || sha256 == null || !sha256.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Referencia de checkpoint inválida.");
        }
        @Override public String toString() { return "RecoveryCheckpointReceipt[redacted]"; }
    }
    public static final class Rejected extends RuntimeException {
        private Rejected() { super(FAILURE); }
    }
}
