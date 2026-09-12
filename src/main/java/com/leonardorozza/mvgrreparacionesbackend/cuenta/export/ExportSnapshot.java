package com.leonardorozza.mvgrreparacionesbackend.cuenta.export;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** Database data only. Completion, authorization for delivery and remote files belong to C/D. */
public final class ExportSnapshot {
    public record PendingPhoto(UUID id, String path, String sha256, long bytes) { }
    private final long actorId;
    private final long tallerId;
    private final Instant observedAt;
    private final List<ExportFile> files;
    private final List<PendingPhoto> pendingPhotos;
    ExportSnapshot(long actorId, long tallerId, Instant observedAt, List<ExportFile> files, List<PendingPhoto> pendingPhotos) {
        this.actorId = actorId; this.tallerId = tallerId; this.observedAt = observedAt;
        this.files = List.copyOf(files); this.pendingPhotos = List.copyOf(pendingPhotos);
        var paths = new HashSet<String>();
        for (var file : files) if (!paths.add(file.path())) throw new ExportPackageException(ExportPackageException.Code.INVALID_PACKAGE);
    }
    public long actorId() { return actorId; }
    public long tallerId() { return tallerId; }
    public Instant observedAt() { return observedAt; }
    public List<ExportFile> files() { return files; }
    public List<PendingPhoto> pendingPhotos() { return pendingPhotos; }
    @Override public String toString() { return "ExportSnapshot[files=" + files.size() + ", pendingPhotos=" + pendingPhotos.size() + "]"; }
}
