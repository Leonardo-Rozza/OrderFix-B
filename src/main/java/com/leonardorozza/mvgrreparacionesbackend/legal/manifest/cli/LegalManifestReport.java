package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidation;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestDryRunService.DryRunResult;

import java.util.List;
import java.util.Objects;

/** Immutable and deliberately redacted report emitted by the legal-manifest CLI. */
public final class LegalManifestReport {

    static final int REPORT_VERSION = 1;
    private static final String VALIDATE_COMMAND = "validate";
    private static final String DRY_RUN_COMMAND = "dry-run";

    private final String command;
    private final LegalManifestStatus status;
    private final Publication publication;
    private final Counts counts;
    private final DryRunCounts dryRun;
    private final List<LegalManifestIssue> issues;
    private final int omittedIssueCount;

    private LegalManifestReport(
            String command,
            LegalManifestStatus status,
            Publication publication,
            Counts counts,
            DryRunCounts dryRun,
            List<LegalManifestIssue> issues,
            int omittedIssueCount) {
        this.command = command;
        this.status = Objects.requireNonNull(status, "status");
        this.publication = publication;
        this.counts = counts;
        this.dryRun = dryRun;
        this.issues = orderedIssues(issues);
        if (omittedIssueCount < 0) {
            throw new IllegalArgumentException("El conteo de issues omitidos no puede ser negativo");
        }
        this.omittedIssueCount = omittedIssueCount;
    }

    /** Builds a report for an argument error that happened before a command could be trusted. */
    public static LegalManifestReport forArgumentFailure(
            LegalManifestValidation<?> failure) {
        LegalManifestValidation<?> required = requireFailure(failure, "argument failure");
        return fromValidation(null, required, null, null, null);
    }

    /** Builds the constant last-resort envelope for an unexpected CLI-boundary failure. */
    public static LegalManifestReport forUnexpectedFailure() {
        return fromValidation(
                null,
                LegalManifestValidation.failure(LegalManifestIssue.at(
                        LegalManifestIssueCode.CLI_OPERATION_FAILED,
                        "cli")),
                null,
                null,
                null);
    }

    /** Builds the report produced by static validation for either supported command. */
    public static LegalManifestReport forStaticValidation(
            String command,
            LegalManifestValidation<ValidatedRelease> validation) {
        String requiredCommand = requireCommand(command);
        LegalManifestValidation<ValidatedRelease> required =
                Objects.requireNonNull(validation, "validation");
        if (!required.passed()) {
            return fromValidation(requiredCommand, required, null, null, null);
        }

        ValidatedRelease release = required.value().orElseThrow();
        return fromValidation(
                requiredCommand,
                required,
                Publication.from(release),
                Counts.from(release),
                null);
    }

    /** Builds the dry-run report without exposing any provisional identifier. */
    public static LegalManifestReport forDryRun(
            ValidatedRelease release,
            LegalManifestValidation<DryRunResult> validation) {
        ValidatedRelease requiredRelease = Objects.requireNonNull(release, "release");
        LegalManifestValidation<DryRunResult> required =
                Objects.requireNonNull(validation, "validation");
        DryRunCounts dryRunCounts = null;
        if (required.passed()) {
            DryRunResult result = required.value().orElseThrow();
            requireMatchingCounts(requiredRelease, result);
            dryRunCounts = DryRunCounts.from(result);
        }
        return fromValidation(
                DRY_RUN_COMMAND,
                required,
                Publication.from(requiredRelease),
                Counts.from(requiredRelease),
                dryRunCounts);
    }

    /** Status consumed by the CLI to select its stable process exit code. */
    public LegalManifestStatus status() {
        return status;
    }

    int reportVersion() {
        return REPORT_VERSION;
    }

    String command() {
        return command;
    }

    boolean persisted() {
        return false;
    }

    Publication publication() {
        return publication;
    }

    Counts counts() {
        return counts;
    }

    DryRunCounts dryRun() {
        return dryRun;
    }

    List<LegalManifestIssue> issues() {
        return issues;
    }

    int omittedIssueCount() {
        return omittedIssueCount;
    }

    private static LegalManifestReport fromValidation(
            String command,
            LegalManifestValidation<?> validation,
            Publication publication,
            Counts counts,
            DryRunCounts dryRun) {
        return new LegalManifestReport(
                command,
                validation.status(),
                publication,
                counts,
                dryRun,
                validation.issues(),
                validation.omittedIssueCount());
    }

    private static LegalManifestValidation<?> requireFailure(
            LegalManifestValidation<?> validation,
            String name) {
        LegalManifestValidation<?> required = Objects.requireNonNull(validation, name);
        if (required.passed()) {
            throw new IllegalArgumentException(name + " no puede tener estado PASS");
        }
        return required;
    }

    private static String requireCommand(String command) {
        if (!VALIDATE_COMMAND.equals(command) && !DRY_RUN_COMMAND.equals(command)) {
            throw new IllegalArgumentException("El comando del reporte no está soportado");
        }
        return command;
    }

    private static List<LegalManifestIssue> orderedIssues(List<LegalManifestIssue> issues) {
        return Objects.requireNonNull(issues, "issues").stream()
                .map(issue -> Objects.requireNonNull(issue, "issue"))
                .sorted(LegalManifestIssue.ORDERING)
                .toList();
    }

    private static void requireMatchingCounts(
            ValidatedRelease release,
            DryRunResult dryRun) {
        if (release.documentCount() != dryRun.documents()
                || release.requirementCount() != dryRun.requirements()
                || release.scopeCount() != dryRun.scopes()) {
            throw new IllegalArgumentException(
                    "Los conteos del dry-run no coinciden con el release acreditado");
        }
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

    record DryRunCounts(
            int newDocumentLines,
            int newDocumentVersions,
            int reusedDocumentVersions,
            int newRequirementLines,
            int newRequirementVersions,
            int reusedRequirementVersions
    ) {
        private static DryRunCounts from(DryRunResult result) {
            return new DryRunCounts(
                    result.newDocumentLines(),
                    result.newDocumentVersions(),
                    result.reusedDocumentVersions(),
                    result.newRequirementLines(),
                    result.newRequirementVersions(),
                    result.reusedRequirementVersions());
        }
    }
}
