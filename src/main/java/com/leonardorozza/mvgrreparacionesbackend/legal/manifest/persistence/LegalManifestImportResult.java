package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Closed import outcome matrix. Invalid status, persistence and receipt combinations cannot be
 * constructed through this API.
 */
public final class LegalManifestImportResult {

    private static final String COMMIT_LOCATION = "database/commit";

    private final LegalManifestStatus status;
    private final Boolean persisted;
    private final Outcome outcome;
    private final Receipt receipt;
    private final List<LegalManifestIssue> issues;

    private LegalManifestImportResult(
            LegalManifestStatus status,
            Boolean persisted,
            Outcome outcome,
            Receipt receipt,
            List<LegalManifestIssue> issues) {
        this.status = Objects.requireNonNull(status, "status");
        this.persisted = persisted;
        this.outcome = outcome;
        this.receipt = receipt;
        this.issues = List.copyOf(Objects.requireNonNull(issues, "issues"));
    }

    static LegalManifestImportResult imported(LegalManifestGraphReceipt receipt) {
        return success(Outcome.IMPORTED, receipt);
    }

    static LegalManifestImportResult alreadyImported(LegalManifestGraphReceipt receipt) {
        return success(Outcome.ALREADY_IMPORTED, receipt);
    }

    static LegalManifestImportResult unknown() {
        LegalManifestIssue issue = LegalManifestIssue.at(
                LegalManifestIssueCode.IMPORT_DB_COMMIT_UNKNOWN,
                COMMIT_LOCATION);
        return new LegalManifestImportResult(
                LegalManifestStatus.ERROR,
                null,
                Outcome.UNKNOWN,
                null,
                List.of(issue));
    }

    static LegalManifestImportResult failure(LegalManifestIssue issue) {
        LegalManifestIssue required = requireKnownImportFailure(issue);
        return new LegalManifestImportResult(
                required.severity(),
                Boolean.FALSE,
                null,
                null,
                List.of(required));
    }

    public LegalManifestStatus status() {
        return status;
    }

    /** Returns true, false or null when commit completion is indeterminate. */
    public Boolean persisted() {
        return persisted;
    }

    public Optional<Outcome> outcome() {
        return Optional.ofNullable(outcome);
    }

    public Optional<Receipt> receipt() {
        return Optional.ofNullable(receipt);
    }

    public List<LegalManifestIssue> issues() {
        return issues;
    }

    private static LegalManifestImportResult success(
            Outcome outcome,
            LegalManifestGraphReceipt graphReceipt) {
        if (outcome != Outcome.IMPORTED && outcome != Outcome.ALREADY_IMPORTED) {
            throw new IllegalArgumentException("Un éxito requiere un outcome confirmado");
        }
        LegalManifestGraphReceipt required = Objects.requireNonNull(graphReceipt, "receipt");
        return new LegalManifestImportResult(
                LegalManifestStatus.PASS,
                Boolean.TRUE,
                outcome,
                new Receipt(
                        required.publicationUuid(),
                        required.importedAt(),
                        required.sealedAt()),
                List.of());
    }

    private static LegalManifestIssue requireKnownImportFailure(LegalManifestIssue candidate) {
        LegalManifestIssue required = Objects.requireNonNull(candidate, "issue");
        if (required.severity() == LegalManifestStatus.PASS
                || !required.code().name().startsWith("IMPORT_")
                || required.code() == LegalManifestIssueCode.IMPORT_DB_COMMIT_UNKNOWN) {
            throw new IllegalArgumentException(
                    "Un fallo conocido requiere un issue IMPORT_* BLOCKED o ERROR no ambiguo");
        }
        return required;
    }

    public enum Outcome {
        IMPORTED,
        ALREADY_IMPORTED,
        UNKNOWN
    }

    /** Safe confirmed receipt exposed to the future import report. */
    public record Receipt(
            UUID publicationUuid,
            Instant importedAt,
            Instant sealedAt
    ) {

        public Receipt {
            Objects.requireNonNull(publicationUuid, "publicationUuid");
            Objects.requireNonNull(importedAt, "importedAt");
            Objects.requireNonNull(sealedAt, "sealedAt");
            requirePostgresPrecision(importedAt, "importedAt");
            requirePostgresPrecision(sealedAt, "sealedAt");
            if (sealedAt.isBefore(importedAt)) {
                throw new IllegalArgumentException(
                        "El sello no puede preceder a la importación");
            }
        }

        private static void requirePostgresPrecision(Instant value, String name) {
            if (value.getNano() % 1_000 != 0) {
                throw new IllegalArgumentException(name + " supera la precisión de PostgreSQL");
            }
        }
    }
}
