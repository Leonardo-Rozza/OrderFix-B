package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidation;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;

import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;

/**
 * Simulates the complete V27 legal graph and always rolls the transaction back.
 *
 * <p>The service accepts only the opaque token emitted by the static manifest validator. A PASS
 * means that the provisional graph crossed the current database constraints; it never means that
 * a publication was imported.</p>
 */
public final class LegalManifestDryRunService {

    private final TransactionTemplate transactionTemplate;
    private final LegalV27SchemaVerifier schemaVerifier;
    private final LegalDryRunPersistence persistence;
    private final LegalDatabaseFailureMapper failureMapper;

    LegalManifestDryRunService(
            TransactionTemplate transactionTemplate,
            LegalV27SchemaVerifier schemaVerifier,
            LegalDryRunPersistence persistence,
            LegalDatabaseFailureMapper failureMapper) {
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "transactionTemplate");
        this.schemaVerifier = Objects.requireNonNull(schemaVerifier, "schemaVerifier");
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.failureMapper = Objects.requireNonNull(failureMapper, "failureMapper");
    }

    /** Executes a rollback-only simulation against an already migrated V27 database. */
    public LegalManifestValidation<DryRunResult> dryRun(ValidatedRelease release) {
        Objects.requireNonNull(release, "release");
        try {
            DryRunResult result = transactionTemplate.execute(status -> {
                try {
                    persistence.configureTransaction();
                    schemaVerifier.verify();
                    return persistence.stageAndValidate(release);
                } finally {
                    status.setRollbackOnly();
                }
            });
            if (result == null) {
                throw new IllegalStateException("El dry-run no produjo un resultado");
            }
            return LegalManifestValidation.pass(result);
        } catch (RuntimeException exception) {
            return LegalManifestValidation.failure(failureMapper.map(exception));
        }
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
