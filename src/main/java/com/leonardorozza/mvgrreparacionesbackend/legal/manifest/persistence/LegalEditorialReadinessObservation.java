package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Safe immutable evidence from one complete editorial readiness observation. */
public record LegalEditorialReadinessObservation(
        Optional<UUID> publicationUuid,
        Instant observedAt,
        String editorialStateFingerprint,
        int documentVersions,
        int requirementVersions,
        int documentTransitions,
        int requirementTransitions,
        int documentSlots,
        int currentRequirementSets,
        int replacementLots
) {

    private static final Pattern SHA256_FINGERPRINT = Pattern.compile("sha256:[0-9a-f]{64}");

    public LegalEditorialReadinessObservation {
        publicationUuid = Objects.requireNonNull(publicationUuid, "publicationUuid");
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
        editorialStateFingerprint = Objects.requireNonNull(
                editorialStateFingerprint,
                "editorialStateFingerprint");
        if (!SHA256_FINGERPRINT.matcher(editorialStateFingerprint).matches()) {
            throw new IllegalArgumentException(
                    "El fingerprint editorial debe ser un SHA-256 canónico");
        }
        if (observedAt.getNano() % 1_000 != 0) {
            throw new IllegalArgumentException(
                    "El instante observado supera la precisión de PostgreSQL");
        }
        requireNonNegative(
                documentVersions,
                requirementVersions,
                documentTransitions,
                requirementTransitions,
                documentSlots,
                currentRequirementSets,
                replacementLots);
    }

    private static void requireNonNegative(int... values) {
        for (int value : values) {
            if (value < 0) {
                throw new IllegalArgumentException(
                        "Un conteo de readiness editorial no puede ser negativo");
            }
        }
    }
}
