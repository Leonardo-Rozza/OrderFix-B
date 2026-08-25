package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Internal evidence returned after a complete V27 graph has been sealed in the transaction. */
record LegalManifestGraphReceipt(
        UUID publicationUuid,
        Instant importedAt,
        Instant sealedAt,
        int documents,
        int requirements,
        int scopes,
        int newDocumentLines,
        int newDocumentVersions,
        int reusedDocumentVersions,
        int newRequirementLines,
        int newRequirementVersions,
        int reusedRequirementVersions
) {

    LegalManifestGraphReceipt {
        Objects.requireNonNull(publicationUuid, "publicationUuid");
        Objects.requireNonNull(importedAt, "importedAt");
        Objects.requireNonNull(sealedAt, "sealedAt");
        requirePostgresPrecision(importedAt, "importedAt");
        requirePostgresPrecision(sealedAt, "sealedAt");
        if (sealedAt.isBefore(importedAt)) {
            throw new IllegalArgumentException("El sello no puede preceder a la importación");
        }
        requireNonNegative(
                documents,
                requirements,
                scopes,
                newDocumentLines,
                newDocumentVersions,
                reusedDocumentVersions,
                newRequirementLines,
                newRequirementVersions,
                reusedRequirementVersions);
        if (newDocumentVersions + reusedDocumentVersions != documents
                || newRequirementVersions + reusedRequirementVersions != requirements
                || newDocumentLines > newDocumentVersions
                || newRequirementLines > newRequirementVersions) {
            throw new IllegalArgumentException("Los conteos del grafo legal no son consistentes");
        }
    }

    private static void requireNonNegative(int... values) {
        for (int value : values) {
            if (value < 0) {
                throw new IllegalArgumentException("Un conteo del grafo legal no puede ser negativo");
            }
        }
    }

    private static void requirePostgresPrecision(Instant value, String name) {
        if (value.getNano() % 1_000 != 0) {
            throw new IllegalArgumentException(name + " supera la precisión de PostgreSQL");
        }
    }
}
