package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Durable identity only; a writer receipt is tentative until its owning commit and release succeed. */
public record LegalRegistrationReceipt(long userId, long tallerId, boolean replay,
                                       UUID lotId, List<UUID> acceptanceIds) {
    public LegalRegistrationReceipt {
        Objects.requireNonNull(lotId, "lotId");
        acceptanceIds = List.copyOf(Objects.requireNonNull(acceptanceIds, "acceptanceIds"));
        if (userId <= 0 || tallerId <= 0 || acceptanceIds.isEmpty() || acceptanceIds.size() > 256
                || new HashSet<>(acceptanceIds).size() != acceptanceIds.size()) {
            throw new IllegalArgumentException("La identidad de registro no es coherente");
        }
        acceptanceIds = acceptanceIds.stream().sorted((left, right) -> left.toString().compareTo(right.toString())).toList();
    }

    @Override public String toString() { return "LegalRegistrationReceipt[replay=" + replay + "]"; }
}
