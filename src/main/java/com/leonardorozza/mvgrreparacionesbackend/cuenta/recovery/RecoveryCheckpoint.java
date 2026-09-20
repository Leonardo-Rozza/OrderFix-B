package com.leonardorozza.mvgrreparacionesbackend.cuenta.recovery;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Logical, bounded observation. Neither a backup nor a complete transactional closure journal. */
public final class RecoveryCheckpoint {
    public static final int MAX_WORKSHOPS = 1000;
    public static final int MAX_ROWS_PER_SURFACE = 10000;
    private RecoveryCheckpoint() { }

    // Order is part of the version 1 external format; changes require a new format version.
    public enum Surface { WORKSHOP, USERS, CLOSURES, OPERATIONS, DELETION_BATCHES,
        ITEMS, PRESUPUESTOS, COBROS, REPUESTOS, REPARACIONES, EQUIPOS, CLIENTES, ARTICULOS }

    public record Digest(long rows, String sha256) {
        public Digest {
            if (rows < 0 || rows > MAX_ROWS_PER_SURFACE || sha256 == null || !sha256.matches("[0-9a-f]{64}"))
                throw invalid();
        }
        @Override public String toString() { return "RecoveryDigest[redacted]"; }
    }

    public record Workshop(long tallerId, Map<Surface, Digest> surfaces) {
        public Workshop {
            if (tallerId <= 0 || surfaces == null || surfaces.size() != Surface.values().length) throw invalid();
            surfaces = Map.copyOf(surfaces);
            for (Surface surface : Surface.values()) if (!surfaces.containsKey(surface)) throw invalid();
            if (surfaces.get(Surface.WORKSHOP).rows() != 1) throw invalid();
        }
        @Override public String toString() { return "RecoveryWorkshop[redacted]"; }
    }

    public record Snapshot(UUID environmentId, UUID checkpointId, Instant observedAt, List<Workshop> workshops) {
        public Snapshot {
            if (environmentId == null || checkpointId == null || observedAt == null || workshops == null
                    || workshops.size() > MAX_WORKSHOPS || observedAt.getEpochSecond() < 0
                    || observedAt.getEpochSecond() > 253402300799L) throw invalid();
            workshops = List.copyOf(workshops);
            long previous = 0;
            long[] totals = new long[Surface.values().length];
            for (Workshop workshop : workshops) {
                if (workshop.tallerId() <= previous) throw invalid();
                previous = workshop.tallerId();
                for (Surface surface : Surface.values()) {
                    totals[surface.ordinal()] += workshop.surfaces().get(surface).rows();
                    if (totals[surface.ordinal()] > MAX_ROWS_PER_SURFACE) throw invalid();
                }
            }
        }
        @Override public String toString() { return "RecoverySnapshot[redacted]"; }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Checkpoint de recuperación inválido.");
    }
}
