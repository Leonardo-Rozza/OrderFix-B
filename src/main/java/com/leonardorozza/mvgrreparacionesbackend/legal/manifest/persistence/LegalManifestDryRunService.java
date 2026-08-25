package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidation;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Simulates the complete V27 legal graph and always rolls the transaction back.
 *
 * <p>The service accepts only the opaque token emitted by the static manifest validator. A PASS
 * means that the provisional graph crossed the current database constraints; it never means that
 * a publication was imported.</p>
 */
public final class LegalManifestDryRunService {

    private final LegalManifestDatabaseGate databaseGate;
    private final JdbcTemplate jdbc;
    private final LegalManifestGraphWriter graphWriter;
    private final LegalDatabaseFailureMapper failureMapper;

    LegalManifestDryRunService(
            LegalManifestDatabaseGate databaseGate,
            JdbcTemplate jdbc,
            LegalManifestGraphWriter graphWriter,
            LegalDatabaseFailureMapper failureMapper) {
        this.databaseGate = Objects.requireNonNull(databaseGate, "databaseGate");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.graphWriter = Objects.requireNonNull(graphWriter, "graphWriter");
        this.failureMapper = Objects.requireNonNull(failureMapper, "failureMapper");
    }

    /** Executes a rollback-only simulation against an already migrated V27 database. */
    public LegalManifestValidation<DryRunResult> dryRun(ValidatedRelease release) {
        Objects.requireNonNull(release, "release");
        try {
            LegalManifestGraphReceipt receipt = databaseGate.execute(status -> {
                try {
                    requirePublicationAbsent(release.plan().manifest().publicationId());
                    return graphWriter.writeNew(release);
                } finally {
                    status.setRollbackOnly();
                }
            });
            if (receipt == null) {
                throw new IllegalStateException("El dry-run no produjo un resultado");
            }
            return LegalManifestValidation.pass(toDryRunResult(receipt));
        } catch (RuntimeException exception) {
            return LegalManifestValidation.failure(failureMapper.map(exception));
        }
    }

    private void requirePublicationAbsent(String externalId) {
        List<UUID> existing = jdbc.query("""
                SELECT id
                  FROM legal_publicaciones
                 WHERE publication_external_id = ?
                 FOR UPDATE
                """, (rs, rowNumber) -> rs.getObject(1, UUID.class), externalId);
        if (!existing.isEmpty()) {
            throw new LegalDryRunBlockedException(LegalManifestIssue.at(
                    LegalManifestIssueCode.DB_PERSISTED_CONFLICT,
                    "database/publication"));
        }
    }

    private static DryRunResult toDryRunResult(LegalManifestGraphReceipt receipt) {
        return new DryRunResult(
                receipt.documents(),
                receipt.requirements(),
                receipt.scopes(),
                receipt.newDocumentLines(),
                receipt.newDocumentVersions(),
                receipt.reusedDocumentVersions(),
                receipt.newRequirementLines(),
                receipt.newRequirementVersions(),
                receipt.reusedRequirementVersions());
    }

    /** Safe aggregate counts for the tentative operations; no provisional identifier is exposed. */
    public record DryRunResult(
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
        public DryRunResult {
            requireNonNegative(documents);
            requireNonNegative(requirements);
            requireNonNegative(scopes);
            requireNonNegative(newDocumentLines);
            requireNonNegative(newDocumentVersions);
            requireNonNegative(reusedDocumentVersions);
            requireNonNegative(newRequirementLines);
            requireNonNegative(newRequirementVersions);
            requireNonNegative(reusedRequirementVersions);
            if (newDocumentVersions + reusedDocumentVersions != documents
                    || newRequirementVersions + reusedRequirementVersions != requirements) {
                throw new IllegalArgumentException("Los conteos del dry-run no son consistentes");
            }
        }

        private static void requireNonNegative(int value) {
            if (value < 0) {
                throw new IllegalArgumentException("Un conteo del dry-run no puede ser negativo");
            }
        }
    }
}
