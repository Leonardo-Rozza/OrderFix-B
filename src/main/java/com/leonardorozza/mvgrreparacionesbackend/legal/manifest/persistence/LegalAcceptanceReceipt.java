package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Technical result; the service delivers it only after its commit and resource boundary succeeds. */
public record LegalAcceptanceReceipt(Kind kind, boolean replay, UUID lotId, List<UUID> acceptanceIds) {
    public enum Kind { WITH_ACTS, DEDUP, EMPTY }

    public LegalAcceptanceReceipt {
        Objects.requireNonNull(kind, "kind");
        acceptanceIds = List.copyOf(Objects.requireNonNull(acceptanceIds, "acceptanceIds"));
        if (acceptanceIds.size() > 2_048 || new HashSet<>(acceptanceIds).size() != acceptanceIds.size()
                || (kind == Kind.WITH_ACTS) != (lotId != null)
                || (kind == Kind.EMPTY) != acceptanceIds.isEmpty()) {
            throw new IllegalArgumentException("El resultado de aceptación no es coherente");
        }
        acceptanceIds = acceptanceIds.stream().sorted((left, right) -> left.toString().compareTo(right.toString())).toList();
    }

    @Override public String toString() { return "LegalAcceptanceReceipt[kind=" + kind + ", replay=" + replay + "]"; }
}
