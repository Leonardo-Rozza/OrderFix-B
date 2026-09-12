package com.leonardorozza.mvgrreparacionesbackend.cuenta.export;

import java.io.*;
import java.time.Instant;
import java.util.*;

/** Bounded, versioned framing for exact B snapshots; never Java object deserialization. */
final class ExportArtifactSnapshotCodec {
    private static final int MAGIC = 0x4f465301;
    static final int MAX_FRAME_BYTES = 72 * 1024 * 1024;
    static final int MAX_ENTRIES = 50_032;
    private ExportArtifactSnapshotCodec() { }

    static byte[] encode(ExportSnapshot snapshot) {
        validate(snapshot);
        var buffer = new ExportBuffer(MAX_FRAME_BYTES);
        try (var output = new DataOutputStream(buffer)) {
            output.writeInt(MAGIC); output.writeLong(snapshot.actorId()); output.writeLong(snapshot.tallerId());
            output.writeLong(snapshot.observedAt().getEpochSecond()); output.writeInt(snapshot.observedAt().getNano());
            output.writeInt(snapshot.files().size());
            for (var file : snapshot.files()) {
                output.writeUTF(file.path()); output.writeUTF(file.mediaType()); output.writeLong(file.records());
                output.writeInt(file.size()); file.writeTo(output);
            }
            output.writeInt(snapshot.pendingPhotos().size());
            for (var photo : snapshot.pendingPhotos()) {
                output.writeLong(photo.id().getMostSignificantBits()); output.writeLong(photo.id().getLeastSignificantBits());
                output.writeUTF(photo.path()); output.writeUTF(photo.sha256()); output.writeLong(photo.bytes());
            }
            output.flush(); return buffer.toByteArray();
        } catch (IOException failure) { throw ExportArtifactCodec.invalid(); }
    }

    static ExportSnapshot decode(byte[] bytes) {
        if (bytes == null || bytes.length > MAX_FRAME_BYTES) throw ExportArtifactCodec.invalid();
        try (var input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != MAGIC) throw ExportArtifactCodec.invalid();
            long actor = input.readLong(), taller = input.readLong(), seconds = input.readLong(); int nanos = input.readInt();
            if (nanos < 0 || nanos > 999_999_999) throw ExportArtifactCodec.invalid();
            Instant observed = Instant.ofEpochSecond(seconds, nanos);
            int fileCount = count(input.readInt());
            List<ExportFile> files = new ArrayList<>(); long total = 0;
            for (int index = 0; index < fileCount; index++) {
                String path = input.readUTF(), type = input.readUTF(); long records = input.readLong(); int size = input.readInt();
                if (size < 0 || size > WorkshopExportSnapshotService.MAX_BYTES - total || size > input.available()) throw ExportArtifactCodec.invalid();
                byte[] content = input.readNBytes(size);
                try { files.add(new ExportFile(path, type, records, content)); }
                finally { Arrays.fill(content, (byte) 0); }
                total += size;
            }
            int photoCount = count(input.readInt());
            List<ExportSnapshot.PendingPhoto> photos = new ArrayList<>();
            for (int index = 0; index < photoCount; index++) {
                UUID id = new UUID(input.readLong(), input.readLong());
                photos.add(new ExportSnapshot.PendingPhoto(id, input.readUTF(), input.readUTF(), input.readLong()));
            }
            if (input.read() != -1) throw ExportArtifactCodec.invalid();
            var snapshot = new ExportSnapshot(actor, taller, observed, files, photos); validate(snapshot); return snapshot;
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof ExportPackageException known) throw known;
            throw ExportArtifactCodec.invalid();
        }
    }

    static void validate(ExportSnapshot snapshot) {
        if (snapshot == null || snapshot.actorId() <= 0 || snapshot.tallerId() <= 0 || snapshot.observedAt() == null) throw ExportArtifactCodec.invalid();
        count(snapshot.files().size()); count(snapshot.pendingPhotos().size());
        Set<String> required = requiredPaths(), seen = new HashSet<>(); long total = 0;
        for (var file : snapshot.files()) {
            if (file == null || !seen.add(file.path()) || file.mediaType().length() > 128) throw ExportArtifactCodec.invalid();
            if (!required.contains(file.path()) && !file.path().equals("archivos/qr-cobro.png") && !documentPath(file.path())) throw ExportArtifactCodec.invalid();
            if (file.path().startsWith("datos/") && (file.records() < 0 || !file.mediaType().equals("application/json"))) throw ExportArtifactCodec.invalid();
            total += file.size(); if (total > WorkshopExportSnapshotService.MAX_BYTES) throw ExportArtifactCodec.capacity();
        }
        if (!seen.containsAll(required)) throw ExportArtifactCodec.invalid();
        Set<UUID> ids = new HashSet<>(); long photoBytes = 0;
        for (var photo : snapshot.pendingPhotos()) {
            if (photo == null || photo.id() == null || !ids.add(photo.id()) || photo.path() == null || photo.sha256() == null
                    || !photo.sha256().matches("[0-9a-f]{64}") || photo.bytes() < 12 || photo.bytes() > 8_000_000
                    || !(photo.path().equals("archivos/fotos/" + photo.id() + ".png") || photo.path().equals("archivos/fotos/" + photo.id() + ".jpg"))) throw ExportArtifactCodec.invalid();
            photoBytes += photo.bytes(); if (photoBytes > ExportArtifactCodec.MAX_PHOTO_BYTES) throw ExportArtifactCodec.capacity();
        }
    }
    private static int count(int value) { if (value < 0 || value > MAX_ENTRIES) throw ExportArtifactCodec.capacity(); return value; }
    private static boolean documentPath(String path) {
        if (!path.startsWith("documentos/") || !path.endsWith(".md")) return false;
        String id = path.substring(11, path.length() - 3);
        try { return UUID.fromString(id).toString().equals(id); }
        catch (IllegalArgumentException failure) { return false; }
    }
    static Set<String> requiredPaths() {
        Set<String> paths = new HashSet<>(List.of("LEEME.txt", "datos/fotos_privadas.json", "datos/fotos_legacy.json", "datos/qr_cobro.json", "datos/aceptaciones_propias.json"));
        for (var query : ExportBusinessCatalog.queries()) paths.add("datos/" + query.category() + ".json");
        return paths;
    }
}
