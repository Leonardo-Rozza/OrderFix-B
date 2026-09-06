package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;

import java.util.Objects;

/** Immutable actor input; construction does not authenticate a principal or accredit a database read. */
public record LegalActorSnapshot(
        long userId,
        long tallerId,
        UserRole role,
        long tokenVersion,
        boolean active,
        boolean workshopActive
) {
    public LegalActorSnapshot {
        if (userId <= 0 || tallerId <= 0 || tokenVersion < 0) {
            throw new IllegalArgumentException("La identidad legal requiere IDs positivos y tokenVersion no negativa");
        }
        Objects.requireNonNull(role, "role");
    }

    public AudienciaLegal audience() {
        return role.toAudienciaLegal();
    }

    public void requireEnabled() {
        if (!active || !workshopActive) {
            throw new IllegalArgumentException("El actor y su taller deben estar habilitados");
        }
    }
}
