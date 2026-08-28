package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetRevisionCalculator;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Reaccredits one sealed publication while preserving the importer's locking protocol. */
final class LegalManifestReplayVerifier {

    private static final String PUBLICATION_LOCATION = "database/publication";

    private final JdbcTemplate jdbc;
    private final LegalManifestOriginGraphVerifier originGraphVerifier;

    LegalManifestReplayVerifier(
            JdbcTemplate jdbc,
            LegalRequiredSetRevisionCalculator requiredSetRevisionCalculator) {
        this(
                jdbc,
                new LegalManifestOriginGraphVerifier(
                        jdbc,
                        Objects.requireNonNull(
                                requiredSetRevisionCalculator,
                                "requiredSetRevisionCalculator")));
    }

    LegalManifestReplayVerifier(
            JdbcTemplate jdbc,
            LegalManifestOriginGraphVerifier originGraphVerifier) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.originGraphVerifier = Objects.requireNonNull(
                originGraphVerifier,
                "originGraphVerifier");
        if (!originGraphVerifier.usesJdbc(jdbc)) {
            throw new IllegalArgumentException(
                    "El replay legal requiere una única sesión JDBC compartida");
        }
    }

    /**
     * Locks the target first, then compares its exact header and origin graph. The V27 sealing
     * validator runs only through the callback reached after the SELECT-only verifier has accepted
     * the sealed header.
     */
    LegalManifestGraphReceipt verify(ValidatedRelease release, UUID publicationId) {
        Objects.requireNonNull(release, "release");
        Objects.requireNonNull(publicationId, "publicationId");
        lockPublication(publicationId);
        try {
            return originGraphVerifier.verify(
                    release,
                    publicationId,
                    this::validateSealedPublication);
        } catch (LegalEditorialBlockedException failure) {
            throw new LegalImportBlockedException(
                    LegalManifestIssueCode.IMPORT_DB_PERSISTED_CONFLICT,
                    failure.issue().location(),
                    failure);
        }
    }

    boolean usesJdbc(JdbcTemplate candidate) {
        return jdbc == candidate && originGraphVerifier.usesJdbc(candidate);
    }

    private void lockPublication(UUID publicationId) {
        List<UUID> rows = jdbc.query(
                """
                SELECT id
                 FROM legal_publicaciones
                 WHERE id = ?
                 LIMIT 2
                 FOR UPDATE
                """,
                (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
                publicationId);
        if (rows.size() > 1) {
            throw new LegalImportBlockedException(
                    LegalManifestIssueCode.IMPORT_DB_PERSISTED_CONFLICT,
                    PUBLICATION_LOCATION);
        }
    }

    private void validateSealedPublication(UUID publicationId) {
        try {
            jdbc.queryForList(
                    "SELECT legal_validar_publicacion_sellada(?)",
                    publicationId);
        } catch (DataIntegrityViolationException failure) {
            throw new LegalImportBlockedException(
                    LegalManifestIssueCode.IMPORT_DB_PERSISTED_CONFLICT,
                    PUBLICATION_LOCATION,
                    failure);
        }
    }
}
