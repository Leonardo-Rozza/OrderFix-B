package com.leonardorozza.mvgrreparacionesbackend.cuenta.recovery;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import static com.leonardorozza.mvgrreparacionesbackend.cuenta.recovery.RecoveryCheckpoint.*;

/** Equality only covers the authenticated checkpoint's captured surfaces, never later operations. */
public final class RecoveryCheckpointComparison {
    private RecoveryCheckpointComparison() { }
    public enum Status { MATCH, DIFFERENCES }
    public enum Issue { MISSING_WORKSHOP, UNEXPECTED_WORKSHOP, SURFACE_CHANGED }
    public record Finding(long tallerId, Surface surface, Issue issue) {
        @Override public String toString() { return "RecoveryFinding[redacted]"; }
    }
    public record Report(Status status, List<Finding> findings) {
        public Report { findings = List.copyOf(findings); }
        public String notice() { return "NO_AUTORIZA_REAPERTURA"; }
        @Override public String toString() { return "RecoveryComparison[" + status + ",NO_AUTORIZA_REAPERTURA]"; }
    }

    public static Report compare(Snapshot expected, Snapshot actual) {
        if (expected == null || actual == null || !expected.environmentId().equals(actual.environmentId()))
            throw new IllegalArgumentException("Contexto de recuperación incompatible.");
        var previous = new TreeMap<Long, Workshop>();
        var current = new TreeMap<Long, Workshop>();
        expected.workshops().forEach(workshop -> previous.put(workshop.tallerId(), workshop));
        actual.workshops().forEach(workshop -> current.put(workshop.tallerId(), workshop));
        var findings = new ArrayList<Finding>();
        previous.forEach((id, workshop) -> {
            Workshop restored = current.get(id);
            if (restored == null) findings.add(new Finding(id, null, Issue.MISSING_WORKSHOP));
            else for (Surface surface : Surface.values())
                if (!workshop.surfaces().get(surface).equals(restored.surfaces().get(surface)))
                    findings.add(new Finding(id, surface, Issue.SURFACE_CHANGED));
        });
        current.keySet().stream().filter(id -> !previous.containsKey(id))
                .forEach(id -> findings.add(new Finding(id, null, Issue.UNEXPECTED_WORKSHOP)));
        return new Report(findings.isEmpty() ? Status.MATCH : Status.DIFFERENCES, findings);
    }
}
