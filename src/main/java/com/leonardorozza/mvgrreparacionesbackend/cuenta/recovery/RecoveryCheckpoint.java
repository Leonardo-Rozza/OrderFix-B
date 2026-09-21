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

    public static final int CURRENT_FORMAT = 2;

    // The first 13 entries retain the version 1 contract. Version 2 adds profile suppression evidence.
    public enum Surface { WORKSHOP, USERS, CLOSURES, OPERATIONS, DELETION_BATCHES,
        ITEMS, PRESUPUESTOS, COBROS, REPUESTOS, REPARACIONES, EQUIPOS, CLIENTES, ARTICULOS, PROFILE_DELETIONS }

    private static final List<Surface> V1_SURFACES = List.of(Surface.WORKSHOP, Surface.USERS,
            Surface.CLOSURES, Surface.OPERATIONS, Surface.DELETION_BATCHES, Surface.ITEMS,
            Surface.PRESUPUESTOS, Surface.COBROS, Surface.REPUESTOS, Surface.REPARACIONES,
            Surface.EQUIPOS, Surface.CLIENTES, Surface.ARTICULOS);
    private static final List<Surface> V2_SURFACES = java.util.stream.Stream.concat(
            V1_SURFACES.stream(), java.util.stream.Stream.of(Surface.PROFILE_DELETIONS)).toList();

    public static List<Surface> surfaces(int formatVersion) {
        return switch (formatVersion) {
            case 1 -> V1_SURFACES;
            case 2 -> V2_SURFACES;
            default -> throw invalid();
        };
    }

    public record Digest(long rows, String sha256) {
        public Digest {
            if (rows < 0 || rows > MAX_ROWS_PER_SURFACE || sha256 == null || !sha256.matches("[0-9a-f]{64}"))
                throw invalid();
        }
        @Override public String toString() { return "RecoveryDigest[redacted]"; }
    }

    public record Workshop(long tallerId, Map<Surface, Digest> surfaces) {
        public Workshop {
            if (tallerId <= 0 || surfaces == null) throw invalid();
            surfaces = Map.copyOf(surfaces);
            if (!surfaces.keySet().equals(java.util.Set.copyOf(V1_SURFACES))
                    && !surfaces.keySet().equals(java.util.Set.copyOf(V2_SURFACES))) throw invalid();
            if (surfaces.get(Surface.WORKSHOP).rows() != 1) throw invalid();
        }
        @Override public String toString() { return "RecoveryWorkshop[redacted]"; }
    }

    public record Snapshot(int formatVersion, UUID environmentId, UUID checkpointId, Instant observedAt, List<Workshop> workshops) {
        public Snapshot(UUID environmentId, UUID checkpointId, Instant observedAt, List<Workshop> workshops) {
            this(CURRENT_FORMAT, environmentId, checkpointId, observedAt, workshops);
        }
        public Snapshot {
            List<Surface> captured = surfaces(formatVersion);
            if (environmentId == null || checkpointId == null || observedAt == null || workshops == null
                    || workshops.size() > MAX_WORKSHOPS || observedAt.getEpochSecond() < 0
                    || observedAt.getEpochSecond() > 253402300799L) throw invalid();
            workshops = List.copyOf(workshops);
            long previous = 0;
            long[] totals = new long[Surface.values().length];
            for (Workshop workshop : workshops) {
                if (workshop.tallerId() <= previous) throw invalid();
                previous = workshop.tallerId();
                if (!workshop.surfaces().keySet().equals(java.util.Set.copyOf(captured))) throw invalid();
                for (Surface surface : captured) {
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
