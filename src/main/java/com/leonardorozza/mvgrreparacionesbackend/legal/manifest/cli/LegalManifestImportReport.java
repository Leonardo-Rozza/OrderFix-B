package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestLimits;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidation;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestImportResult;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestImportResult.Outcome;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestImportResult.Receipt;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Immutable v2 envelope emitted exclusively by the legal {@code import} command.
 *
 * <p>The factories preserve the closed persistence matrix: callers cannot accidentally describe a
 * confirmed commit as rolled back, nor an indeterminate commit as absent.</p>
 */
public final class LegalManifestImportReport {

    static final int REPORT_VERSION = 2;
    private static final String IMPORT_COMMAND = "import";

    private final LegalManifestStatus status;
    private final Boolean persisted;
    private final Publication publication;
    private final Counts counts;
    private final ImportDetails importDetails;
    private final List<LegalManifestIssue> issues;
    private final int omittedIssueCount;

    private LegalManifestImportReport(
            LegalManifestStatus status,
            Boolean persisted,
            Publication publication,
            Counts counts,
            ImportDetails importDetails,
            IssueSet issueSet) {
        this.status = Objects.requireNonNull(status, "status");
        this.persisted = persisted;
        this.publication = publication;
        this.counts = counts;
        this.importDetails = importDetails;
        IssueSet requiredIssues = Objects.requireNonNull(issueSet, "issueSet");
        this.issues = requiredIssues.issues();
        this.omittedIssueCount = requiredIssues.omittedIssueCount();
        requireValidMatrix();
    }

    /** Builds v2 for an argument failure after the raw command was recognized as import. */
    public static LegalManifestImportReport forArgumentFailure(
            LegalManifestValidation<?> failure) {
        return forKnownOperationalFailure(null, failure);
    }

    /** Builds v2 for static validation that failed before a trusted release token existed. */
    public static LegalManifestImportReport forStaticValidation(
            LegalManifestValidation<ValidatedRelease> failure) {
        return forKnownOperationalFailure(null, failure);
    }

    /** Builds v2 for a confirmation or enablement failure after release validation. */
    public static LegalManifestImportReport forConfirmationFailure(
            ValidatedRelease release,
            LegalManifestValidation<?> failure) {
        return forReleaseFailure(release, failure);
    }

    /** Builds v2 for any expected failure after release validation. */
    public static LegalManifestImportReport forReleaseFailure(
            ValidatedRelease release,
            LegalManifestValidation<?> failure) {
        return forKnownOperationalFailure(
                Objects.requireNonNull(release, "release"),
                failure);
    }

    /** Builds a known non-persisted result; release may still be unknown at this boundary. */
    public static LegalManifestImportReport forKnownOperationalFailure(
            ValidatedRelease release,
            LegalManifestValidation<?> failure) {
        LegalManifestValidation<?> required = requireFailure(failure);
        if (required.issues().stream()
                .anyMatch(issue -> issue.code() == LegalManifestIssueCode.IMPORT_DB_COMMIT_UNKNOWN)) {
            throw new IllegalArgumentException(
                    "Un commit indeterminado no puede informarse como no persistido");
        }
        return failureReport(
                release,
                required.status(),
                Boolean.FALSE,
                null,
                required.issues(),
                required.omittedIssueCount());
    }

    /** Convenience overload for one safe known operational issue. */
    public static LegalManifestImportReport forKnownOperationalFailure(
            ValidatedRelease release,
            LegalManifestIssue issue) {
        return forKnownOperationalFailure(
                release,
                LegalManifestValidation.failure(Objects.requireNonNull(issue, "issue")));
    }

    /** Builds the only allowed indeterminate result, without inventing receipt metadata. */
    public static LegalManifestImportReport forUnknownOperationalFailure(
            ValidatedRelease release) {
        LegalManifestIssue issue = LegalManifestIssue.at(
                LegalManifestIssueCode.IMPORT_DB_COMMIT_UNKNOWN,
                "database/commit");
        return failureReport(
                release,
                LegalManifestStatus.ERROR,
                null,
                ImportDetails.unknownDetails(),
                List.of(issue),
                0);
    }

    /** Maps the persistence service result without weakening its closed outcome matrix. */
    public static LegalManifestImportReport forImport(
            ValidatedRelease release,
            LegalManifestImportResult result) {
        ValidatedRelease requiredRelease = Objects.requireNonNull(release, "release");
        LegalManifestImportResult requiredResult = Objects.requireNonNull(result, "result");
        return new LegalManifestImportReport(
                requiredResult.status(),
                requiredResult.persisted(),
                Publication.from(requiredRelease),
                Counts.from(requiredRelease),
                ImportDetails.from(requiredResult),
                IssueSet.from(requiredResult.issues(), 0));
    }

    /** Status consumed by the CLI to select its stable process exit code. */
    public LegalManifestStatus status() {
        return status;
    }

    int reportVersion() {
        return REPORT_VERSION;
    }

    String command() {
        return IMPORT_COMMAND;
    }

    Boolean persisted() {
        return persisted;
    }

    Publication publication() {
        return publication;
    }

    Counts counts() {
        return counts;
    }

    Object dryRun() {
        return null;
    }

    ImportDetails importDetails() {
        return importDetails;
    }

    List<LegalManifestIssue> issues() {
        return issues;
    }

    int omittedIssueCount() {
        return omittedIssueCount;
    }

    private static LegalManifestImportReport failureReport(
            ValidatedRelease release,
            LegalManifestStatus status,
            Boolean persisted,
            ImportDetails importDetails,
            Collection<LegalManifestIssue> issues,
            int upstreamOmittedIssueCount) {
        return new LegalManifestImportReport(
                status,
                persisted,
                release == null ? null : Publication.from(release),
                release == null ? null : Counts.from(release),
                importDetails,
                IssueSet.from(issues, upstreamOmittedIssueCount));
    }

    private static LegalManifestValidation<?> requireFailure(
            LegalManifestValidation<?> validation) {
        LegalManifestValidation<?> required = Objects.requireNonNull(validation, "failure");
        if (required.passed()) {
            throw new IllegalArgumentException("Un reporte terminal de importación requiere un fallo");
        }
        return required;
    }

    private void requireValidMatrix() {
        LegalManifestStatus issueStatus = mostSevereIssueStatus();
        boolean containsCommitUnknown = issues.stream()
                .anyMatch(issue -> issue.code()
                        == LegalManifestIssueCode.IMPORT_DB_COMMIT_UNKNOWN);
        boolean exactCommitUnknown = issues.size() == 1 && containsCommitUnknown;
        if (status == LegalManifestStatus.PASS) {
            if (!Boolean.TRUE.equals(persisted)
                    || importDetails == null
                    || !importDetails.confirmed()
                    || !issues.isEmpty()
                    || omittedIssueCount != 0) {
                throw invalidMatrix();
            }
            return;
        }

        if (issues.isEmpty() || issueStatus != status) {
            throw invalidMatrix();
        }
        if (status == LegalManifestStatus.BLOCKED) {
            if (!Boolean.FALSE.equals(persisted)
                    || importDetails != null
                    || containsCommitUnknown) {
                throw invalidMatrix();
            }
            return;
        }

        boolean knownError = Boolean.FALSE.equals(persisted)
                && importDetails == null
                && !containsCommitUnknown;
        boolean unknownError = persisted == null
                && importDetails != null
                && importDetails.unknown()
                && exactCommitUnknown;
        if (!knownError && !unknownError) {
            throw invalidMatrix();
        }
    }

    private LegalManifestStatus mostSevereIssueStatus() {
        LegalManifestStatus result = LegalManifestStatus.PASS;
        for (LegalManifestIssue issue : issues) {
            result = LegalManifestStatus.mostSevere(result, issue.severity());
        }
        return result;
    }

    private static IllegalArgumentException invalidMatrix() {
        return new IllegalArgumentException("La combinación del reporte de importación no es válida");
    }

    record Publication(
            String publicationId,
            int schemaVersion,
            String manifestSha256
    ) {
        private static Publication from(ValidatedRelease release) {
            return new Publication(
                    release.plan().manifest().publicationId(),
                    release.plan().manifest().schemaVersion(),
                    release.plan().manifestSha256());
        }
    }

    record Counts(int documents, int requirements, int scopes) {
        private static Counts from(ValidatedRelease release) {
            return new Counts(
                    release.documentCount(),
                    release.requirementCount(),
                    release.scopeCount());
        }
    }

    record ImportDetails(
            Outcome outcome,
            UUID publicationUuid,
            Instant importedAt,
            Instant sealedAt,
            Boolean sealed,
            boolean promotionChanged
    ) {
        ImportDetails {
            Objects.requireNonNull(outcome, "outcome");
            if (promotionChanged) {
                throw new IllegalArgumentException(
                        "El comando import no puede informar una promoción editorial");
            }
            if (outcome == Outcome.UNKNOWN) {
                if (publicationUuid != null
                        || importedAt != null
                        || sealedAt != null
                        || sealed != null) {
                    throw invalidMatrix();
                }
            } else if (publicationUuid == null
                    || importedAt == null
                    || sealedAt == null
                    || !Boolean.TRUE.equals(sealed)) {
                throw invalidMatrix();
            }
            requirePostgresPrecision(importedAt, "importedAt");
            requirePostgresPrecision(sealedAt, "sealedAt");
        }

        private static ImportDetails from(LegalManifestImportResult result) {
            Outcome outcome = result.outcome().orElse(null);
            Receipt receipt = result.receipt().orElse(null);
            if (outcome == null) {
                if (receipt != null) {
                    throw invalidMatrix();
                }
                return null;
            }
            if (outcome == Outcome.UNKNOWN) {
                if (receipt != null) {
                    throw invalidMatrix();
                }
                return unknownDetails();
            }
            if (receipt == null) {
                throw invalidMatrix();
            }
            return new ImportDetails(
                    outcome,
                    receipt.publicationUuid(),
                    receipt.importedAt(),
                    receipt.sealedAt(),
                    Boolean.TRUE,
                    false);
        }

        private static ImportDetails unknownDetails() {
            return new ImportDetails(Outcome.UNKNOWN, null, null, null, null, false);
        }

        private boolean confirmed() {
            return outcome == Outcome.IMPORTED || outcome == Outcome.ALREADY_IMPORTED;
        }

        private boolean unknown() {
            return outcome == Outcome.UNKNOWN;
        }

        private static void requirePostgresPrecision(Instant value, String name) {
            if (value != null && value.getNano() % 1_000 != 0) {
                throw new IllegalArgumentException(name + " supera la precisión de PostgreSQL");
            }
        }
    }

    private record IssueSet(List<LegalManifestIssue> issues, int omittedIssueCount) {
        private static IssueSet from(
                Collection<LegalManifestIssue> candidates,
                int upstreamOmittedIssueCount) {
            Objects.requireNonNull(candidates, "issues");
            if (upstreamOmittedIssueCount < 0) {
                throw new IllegalArgumentException(
                        "El conteo de issues omitidos no puede ser negativo");
            }
            TreeSet<LegalManifestIssue> ordered = new TreeSet<>(LegalManifestIssue.ORDERING);
            for (LegalManifestIssue issue : candidates) {
                ordered.add(Objects.requireNonNull(issue, "issue"));
            }
            int exposedCount = Math.min(ordered.size(), LegalManifestLimits.MAX_EXPOSED_ISSUES);
            List<LegalManifestIssue> exposed = new ArrayList<>(exposedCount);
            int index = 0;
            for (LegalManifestIssue issue : ordered) {
                if (index++ >= exposedCount) {
                    break;
                }
                exposed.add(issue);
            }
            return new IssueSet(
                    List.copyOf(exposed),
                    Math.addExact(upstreamOmittedIssueCount, ordered.size() - exposedCount));
        }
    }
}
