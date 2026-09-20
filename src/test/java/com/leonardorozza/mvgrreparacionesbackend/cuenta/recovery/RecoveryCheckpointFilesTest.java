package com.leonardorozza.mvgrreparacionesbackend.cuenta.recovery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static com.leonardorozza.mvgrreparacionesbackend.cuenta.recovery.RecoveryCheckpoint.*;
import static com.leonardorozza.mvgrreparacionesbackend.cuenta.recovery.RecoveryCheckpointFiles.*;
import static org.assertj.core.api.Assertions.*;

class RecoveryCheckpointFilesTest {
    private static final UUID ENVIRONMENT = UUID.fromString("11111111-2222-4333-8444-555555555555");
    private static final UUID CHECKPOINT = UUID.fromString("66666666-7777-4888-8999-aaaaaaaaaaaa");
    private static final Instant NOW = Instant.parse("2026-09-19T21:03:04.123456789Z");
    private static final String SHA = "a1".repeat(32);
    private static final String ERROR = "No se pudo leer o guardar el checkpoint de recuperación.";
    private final RecoveryCheckpointFiles files = new RecoveryCheckpointFiles();
    private final byte[] key = new byte[32];
    @TempDir Path directory;

    @BeforeEach void privateDirectory() throws Exception {
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        Arrays.fill(key, (byte) 41);
    }

    @Test void roundTripPreservesExactSnapshotAndBindsTheCompleteFile() throws Exception {
        Path file = path("checkpoint.bin");
        Snapshot snapshot = snapshot(CHECKPOINT);
        Receipt receipt = files.write(file, snapshot, key);
        assertThat(files.read(file, key, receipt)).isEqualTo(snapshot);
        assertThat(receipt).isEqualTo(receipt(Files.readAllBytes(file), ENVIRONMENT, CHECKPOINT));
        assertThat(Files.getPosixFilePermissions(file)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(key).containsOnly((byte) 41);
        assertThat(directory.toFile().list()).containsExactly("checkpoint.bin");
    }

    @Test void emptyScopeStillHasAnAuthenticatedExplicitCheckpoint() throws Exception {
        Snapshot empty = new Snapshot(ENVIRONMENT, CHECKPOINT, NOW, List.of());
        Path file = path("empty.bin");
        Receipt receipt = files.write(file, empty, key);
        assertThat(Files.size(file)).isEqualTo(92);
        assertThat(files.read(file, key, receipt)).isEqualTo(empty);
    }

    @Test void equivalentSnapshotProducesIdenticalCanonicalBytes() throws Exception {
        Receipt first = files.write(path("first.bin"), snapshot(CHECKPOINT), key);
        Receipt second = files.write(path("second.bin"), snapshot(CHECKPOINT), key);
        assertThat(Files.readAllBytes(path("second.bin"))).containsExactly(Files.readAllBytes(path("first.bin")));
        assertThat(first).isEqualTo(second);
    }

    @Test void tamperingFailsEvenWhenCallerSuppliesTheNewWholeFileHash() throws Exception {
        Path file = path("tampered.bin");
        Receipt original = files.write(file, snapshot(CHECKPOINT), key);
        byte[] altered = Files.readAllBytes(file);
        altered[altered.length - 40] ^= 1;
        Files.write(file, altered);
        rejected(() -> files.read(file, key, original));
        rejected(() -> files.read(file, key, receipt(altered, ENVIRONMENT, CHECKPOINT)));
    }

    @Test void wrongKeyFailsWithoutChangingTheFileOrLeakingCauses() throws Exception {
        Path file = path("private-token-in-path.bin");
        Receipt receipt = files.write(file, snapshot(CHECKPOINT), key);
        byte[] original = Files.readAllBytes(file), wrong = new byte[32];
        rejected(() -> files.read(file, wrong, receipt));
        assertThat(Files.readAllBytes(file)).containsExactly(original);
    }

    @Test void previousExternalReceiptCannotValidateANewerCheckpoint() throws Exception {
        Receipt old = files.write(path("old.bin"), snapshot(CHECKPOINT), key);
        UUID newer = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");
        files.write(path("new.bin"), snapshot(newer), key);
        rejected(() -> files.read(path("new.bin"), key, old));
    }

    @Test void environmentAndCheckpointIdsAreCheckedIndependentlyOfFileHash() throws Exception {
        Path file = path("bound.bin");
        Receipt receipt = files.write(file, snapshot(CHECKPOINT), key);
        rejected(() -> files.read(file, key, new Receipt(UUID.randomUUID(), CHECKPOINT, receipt.sha256())));
        rejected(() -> files.read(file, key, new Receipt(ENVIRONMENT, UUID.randomUUID(), receipt.sha256())));
        rejected(() -> files.read(file, key, null));
    }

    @Test void truncationAndAuthenticatedTrailingBytesAreRejected() throws Exception {
        Path file = path("original.bin");
        files.write(file, snapshot(CHECKPOINT), key);
        byte[] signed = Files.readAllBytes(file);
        byte[] truncated = Arrays.copyOf(signed, signed.length - 1);
        writePrivate(path("short.bin"), truncated);
        rejected(() -> files.read(path("short.bin"), key, receipt(truncated, ENVIRONMENT, CHECKPOINT)));
        byte[] payload = Arrays.copyOf(signed, signed.length - 32 + 1);
        payload[payload.length - 1] = 7;
        byte[] trailing = sign(payload);
        writePrivate(path("trailing.bin"), trailing);
        rejected(() -> files.read(path("trailing.bin"), key, receipt(trailing, ENVIRONMENT, CHECKPOINT)));
    }

    @ParameterizedTest @ValueSource(ints = {0, -1, 1001, Integer.MAX_VALUE})
    void authenticatedWorkshopCountIsBoundedBeforeRecordAllocation(int count) throws Exception {
        Path file = path("count.bin");
        files.write(file, snapshot(CHECKPOINT), key);
        byte[] payload = payload(file);
        ByteBuffer.wrap(payload).putInt(56, count);
        byte[] signed = sign(payload);
        Files.write(file, signed);
        rejected(() -> files.read(file, key, receipt(signed, ENVIRONMENT, CHECKPOINT)));
    }

    @ParameterizedTest @ValueSource(longs = {-1, 10001, Long.MAX_VALUE})
    void authenticatedSurfaceRowCountCannotExceedTheCaptureLimit(long rows) throws Exception {
        Path file = path("rows.bin");
        files.write(file, snapshot(CHECKPOINT), key);
        byte[] payload = payload(file);
        ByteBuffer.wrap(payload).putLong(68, rows);
        byte[] signed = sign(payload);
        Files.write(file, signed);
        rejected(() -> files.read(file, key, receipt(signed, ENVIRONMENT, CHECKPOINT)));
    }

    @Test void authenticatedSurfaceTotalsAreBoundedAcrossWorkshops() throws Exception {
        Path file = path("totals.bin");
        files.write(file, snapshot(CHECKPOINT), key);
        byte[] payload = payload(file);
        ByteBuffer bytes = ByteBuffer.wrap(payload);
        bytes.putLong(68 + 40, 5001); // USERS in first workshop
        bytes.putLong(68 + 40 + 528, 5001); // USERS in second workshop
        byte[] signed = sign(payload);
        Files.write(file, signed);
        rejected(() -> files.read(file, key, receipt(signed, ENVIRONMENT, CHECKPOINT)));
    }

    @Test void formatVersionTimestampAndWorkshopIdentityMustBeCanonical() throws Exception {
        Path original = path("valid.bin");
        files.write(original, snapshot(CHECKPOINT), key);
        for (int variant = 0; variant < 7; variant++) {
            byte[] payload = payload(original);
            ByteBuffer bytes = ByteBuffer.wrap(payload);
            switch (variant) {
                case 0 -> bytes.putLong(0, 0);
                case 1 -> bytes.putInt(8, 2);
                case 2 -> bytes.putInt(52, 1_000_000_000);
                case 3 -> bytes.putLong(60, 0);
                case 4 -> bytes.putLong(60 + 528, 7); // duplicate second workshop
                case 5 -> bytes.putLong(44, -1);
                case 6 -> bytes.putLong(44, Long.MAX_VALUE);
                default -> throw new AssertionError();
            }
            byte[] signed = sign(payload);
            Path malformed = path("malformed-" + variant + ".bin");
            writePrivate(malformed, signed);
            rejected(() -> files.read(malformed, key, receipt(signed, ENVIRONMENT, CHECKPOINT)));
        }
    }

    @Test void maximumSupportedSnapshotRoundTripsWithinTheFileCap() throws Exception {
        var workshops = new ArrayList<Workshop>(MAX_WORKSHOPS);
        for (int id = 1; id <= MAX_WORKSHOPS; id++) workshops.add(workshop(id));
        Snapshot snapshot = new Snapshot(ENVIRONMENT, CHECKPOINT, NOW, workshops);
        Path file = path("max.bin");
        Receipt receipt = files.write(file, snapshot, key);
        assertThat(Files.size(file)).isLessThanOrEqualTo(MAX_FILE_BYTES);
        assertThat(files.read(file, key, receipt)).isEqualTo(snapshot);
    }

    @Test void oversizedFileIsRejectedWithoutLoadingItsContents() throws Exception {
        Path file = path("oversized.bin");
        writePrivate(file, new byte[0]);
        try (var sparse = new RandomAccessFile(file.toFile(), "rw")) { sparse.setLength((long) MAX_FILE_BYTES + 1); }
        rejected(() -> files.read(file, key, new Receipt(ENVIRONMENT, CHECKPOINT, SHA)));
    }

    @ParameterizedTest @ValueSource(ints = {0, 1, 31, 33, 64})
    void keysMustContainExactlyThirtyTwoBytesBeforeAnyWrite(int size) {
        Path file = path("no-write.bin");
        rejected(() -> files.write(file, snapshot(CHECKPOINT), new byte[size]));
        assertThat(file).doesNotExist();
        rejected(() -> files.read(file, new byte[size], new Receipt(ENVIRONMENT, CHECKPOINT, SHA)));
    }

    @Test void nullKeyAndSnapshotAreSanitizedBeforeCreatingAnArtifact() {
        Path file = path("no-null.bin");
        rejected(() -> files.write(file, snapshot(CHECKPOINT), null));
        rejected(() -> files.write(file, null, key));
        assertThat(file).doesNotExist();
    }

    @Test void existingFileIsNeverOverwrittenOrDeleted() throws Exception {
        Path file = path("existing.bin");
        byte[] original = new byte[] {1, 2, 3, 4};
        writePrivate(file, original);
        rejected(() -> files.write(file, snapshot(CHECKPOINT), key));
        assertThat(Files.readAllBytes(file)).containsExactly(original);
    }

    @Test void finalSymlinkAndDanglingSymlinkAreRejectedWithoutTouchingTheirTargets() throws Exception {
        Path file = path("real.bin");
        Receipt receipt = files.write(file, snapshot(CHECKPOINT), key);
        byte[] original = Files.readAllBytes(file);
        Path link = path("link.bin");
        Files.createSymbolicLink(link, file);
        rejected(() -> files.read(link, key, receipt));
        rejected(() -> files.write(link, snapshot(CHECKPOINT), key));
        Path dangling = path("dangling.bin"), absent = path("absent.bin");
        Files.createSymbolicLink(dangling, absent);
        rejected(() -> files.write(dangling, snapshot(CHECKPOINT), key));
        assertThat(Files.readAllBytes(file)).containsExactly(original);
        assertThat(Files.isSymbolicLink(link)).isTrue();
        assertThat(Files.isSymbolicLink(dangling)).isTrue();
        assertThat(absent).doesNotExist();
    }

    @Test void publicFileOrNonPrivateParentCannotBeAccepted() throws Exception {
        Path file = path("permissions.bin");
        Receipt receipt = files.write(file, snapshot(CHECKPOINT), key);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        rejected(() -> files.read(file, key, receipt));
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxr-xr-x"));
        try {
            rejected(() -> files.read(file, key, receipt));
            rejected(() -> files.write(path("public-parent.bin"), snapshot(CHECKPOINT), key));
            assertThat(path("public-parent.bin")).doesNotExist();
        } finally { Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------")); }
    }

    @Test void directParentSymlinkIsRejectedAndRelativePathsAreNotResolvedImplicitly() throws Exception {
        Path parent = Files.createDirectory(path("private"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path alias = path("alias");
        Files.createSymbolicLink(alias, parent);
        rejected(() -> files.write(alias.resolve("checkpoint.bin"), snapshot(CHECKPOINT), key));
        assertThat(parent.resolve("checkpoint.bin")).doesNotExist();
        rejected(() -> files.write(Path.of("relative-private-checkpoint.bin"), snapshot(CHECKPOINT), key));
    }

    @Test void receiptDoesNotExposeIdentifiersAndRejectsInvalidAnchors() {
        assertThat(new Receipt(ENVIRONMENT, CHECKPOINT, SHA).toString()).isEqualTo("RecoveryCheckpointReceipt[redacted]");
        assertThatThrownBy(() -> new Receipt(null, CHECKPOINT, SHA)).isInstanceOf(IllegalArgumentException.class).hasNoCause();
        assertThatThrownBy(() -> new Receipt(ENVIRONMENT, null, SHA)).isInstanceOf(IllegalArgumentException.class).hasNoCause();
        for (String invalid : List.of("", "a".repeat(63), "A".repeat(64), "g".repeat(64)))
            assertThatThrownBy(() -> new Receipt(ENVIRONMENT, CHECKPOINT, invalid)).isInstanceOf(IllegalArgumentException.class).hasNoCause();
    }

    private Path path(String name) { return directory.resolve(name); }
    private static Snapshot snapshot(UUID id) { return new Snapshot(ENVIRONMENT, id, NOW, List.of(workshop(7), workshop(12))); }
    private static Workshop workshop(long id) {
        var surfaces = new EnumMap<Surface, Digest>(Surface.class);
        for (Surface surface : Surface.values()) surfaces.put(surface, new Digest(surface == Surface.WORKSHOP ? 1 : 2, SHA));
        return new Workshop(id, surfaces);
    }
    private static void writePrivate(Path file, byte[] bytes) throws Exception {
        Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.write(file, bytes);
    }
    private byte[] payload(Path file) throws Exception { byte[] bytes = Files.readAllBytes(file); return Arrays.copyOf(bytes, bytes.length - 32); }
    private byte[] sign(byte[] payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        byte[] signed = Arrays.copyOf(payload, payload.length + 32);
        System.arraycopy(mac.doFinal(payload), 0, signed, payload.length, 32);
        return signed;
    }
    private static Receipt receipt(byte[] bytes, UUID environment, UUID checkpoint) throws Exception {
        return new Receipt(environment, checkpoint, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    }
    private static void rejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(Rejected.class).hasMessage(ERROR).hasNoCause();
    }
}
