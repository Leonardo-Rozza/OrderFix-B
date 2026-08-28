package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialReadiness;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Safe database evidence for one confirmed editorial application or exact replay. */
public record LegalEditorialApplyReceipt(
        OperationType operationType,
        UUID targetPublicationUuid,
        Instant appliedAt,
        LegalEditorialReadiness readinessAfter,
        int documentVersions,
        int requirementVersions,
        int documentTransitions,
        int requirementTransitions,
        int documentSlots,
        int requiredSetPointers,
        int replacementBatches
) {

    public LegalEditorialApplyReceipt {
        operationType = Objects.requireNonNull(operationType, "operationType");
        targetPublicationUuid = Objects.requireNonNull(
                targetPublicationUuid,
                "targetPublicationUuid");
        appliedAt = requirePostgresPrecision(appliedAt);
        readinessAfter = Objects.requireNonNull(readinessAfter, "readinessAfter");
        requireReadinessMatrix(operationType, readinessAfter);
        requireNonNegative(
                documentVersions,
                requirementVersions,
                documentTransitions,
                requirementTransitions,
                documentSlots,
                requiredSetPointers,
                replacementBatches);
    }

    private static Instant requirePostgresPrecision(Instant value) {
        Instant required = Objects.requireNonNull(value, "appliedAt");
        if (required.getNano() % 1_000 != 0) {
            throw new IllegalArgumentException(
                    "appliedAt supera la precisión de PostgreSQL");
        }
        return required;
    }

    private static void requireReadinessMatrix(
            OperationType operationType,
            LegalEditorialReadiness readinessAfter) {
        boolean valid = switch (operationType) {
            case PROMOTE, REPLACE -> readinessAfter == LegalEditorialReadiness.READY;
            case RETIRE -> readinessAfter == LegalEditorialReadiness.NOT_READY;
        };
        if (!valid) {
            throw new IllegalArgumentException(
                    "La operación editorial no admite ese readiness confirmado");
        }
    }

    private static void requireNonNegative(int... counts) {
        for (int count : counts) {
            if (count < 0) {
                throw new IllegalArgumentException(
                        "Los conteos del postestado editorial no pueden ser negativos");
            }
        }
    }

    public enum OperationType {
        PROMOTE,
        REPLACE,
        RETIRE
    }
}
