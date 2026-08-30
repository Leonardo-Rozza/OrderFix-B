package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli.LegalEditorialArguments.Command;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialPlanValidator.ValidatedEditorialPlan;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialReadiness;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestLimits;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidation;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalEditorialPlanV1;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalEditorialApplyReceipt;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalEditorialApplyResult;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalEditorialPlanResult;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalEditorialReadinessObservation;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalEditorialReadinessResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Immutable v3 envelope for the isolated editorial commands.
 *
 * <p>The factories translate the already closed persistence result matrices without accepting
 * arbitrary outcome combinations. Input identity remains available after validation, while
 * database-derived identity, timestamps and counts are emitted only from an authoritative
 * observation or receipt.</p>
 */
public final class LegalEditorialReport {

    static final int REPORT_VERSION = 3;
    private static final String COMMIT_LOCATION = "database/commit";

    private final Command command;
    private final LegalManifestStatus status;
    private final Boolean persisted;
    private final Publication publication;
    private final Operation operation;
    private final Plan plan;
    private final Readiness readiness;
    private final Counts counts;
    private final List<LegalManifestIssue> issues;
    private final int omittedIssueCount;

    private LegalEditorialReport(
            Command command,
            LegalManifestStatus status,
            Boolean persisted,
            Publication publication,
            Operation operation,
            Plan plan,
            Readiness readiness,
            Counts counts,
            IssueSet issueSet) {
        this.command = Objects.requireNonNull(command, "command");
        this.status = Objects.requireNonNull(status, "status");
        this.persisted = persisted;
        this.publication = publication;
        this.operation = operation;
        this.plan = plan;
        this.readiness = readiness;
        this.counts = counts;
        IssueSet requiredIssues = Objects.requireNonNull(issueSet, "issueSet");
        this.issues = requiredIssues.issues();
        this.omittedIssueCount = requiredIssues.omittedIssueCount();
        requireValidMatrix();
    }

    /** Builds a known, non-persisted failure before or after release validation. */
    public static LegalEditorialReport forKnownFailure(
            Command command,
            ValidatedRelease release,
            LegalManifestValidation<?> failure) {
        return forKnownFailure(command, release, null, failure);
    }

    /** Builds a known failure while retaining only a previously confirmed plan identity. */
    public static LegalEditorialReport forKnownFailure(
            Command command,
            ValidatedRelease release,
            ValidatedEditorialPlan editorialPlan,
            LegalManifestValidation<?> failure) {
        Command requiredCommand = Objects.requireNonNull(command, "command");
        LegalManifestValidation<?> required = requireFailure(failure);
        if (editorialPlan != null
                && ((requiredCommand != Command.PLAN_REPLACE
                && requiredCommand != Command.APPLY_REPLACE)
                || release == null)) {
            throw invalidMatrix();
        }
        if (required.issues().stream()
                .anyMatch(issue -> issue.code() == LegalManifestIssueCode.COMMIT_OUTCOME_UNKNOWN)) {
            throw new IllegalArgumentException(
                    "Un commit editorial indeterminado no puede informarse como rollback");
        }
        Operation operation = switch (requiredCommand) {
            case READINESS -> null;
            case PLAN_PROMOTE, APPLY_PROMOTE -> new Operation(
                    OperationType.PROMOTE,
                    required.status() == LegalManifestStatus.BLOCKED
                            ? Outcome.BLOCKED
                            : Outcome.ERROR,
                    null);
            case PLAN_REPLACE, APPLY_REPLACE -> new Operation(
                    OperationType.REPLACE,
                    required.status() == LegalManifestStatus.BLOCKED
                            ? Outcome.BLOCKED
                            : Outcome.ERROR,
                    null);
        };
        return new LegalEditorialReport(
                requiredCommand,
                required.status(),
                Boolean.FALSE,
                release == null ? null : Publication.from(release, null),
                operation,
                editorialPlan == null ? null : Plan.identity(editorialPlan),
                null,
                release == null ? null : Counts.releaseOnly(release),
                IssueSet.from(required.issues(), required.omittedIssueCount()));
    }

    /** Convenience overload for one constant safe issue. */
    public static LegalEditorialReport forKnownFailure(
            Command command,
            ValidatedRelease release,
            LegalManifestIssue issue) {
        return forKnownFailure(
                command,
                release,
                LegalManifestValidation.failure(Objects.requireNonNull(issue, "issue")));
    }

    /** Convenience overload retaining one confirmed plan identity and one constant safe issue. */
    public static LegalEditorialReport forKnownFailure(
            Command command,
            ValidatedRelease release,
            ValidatedEditorialPlan editorialPlan,
            LegalManifestIssue issue) {
        return forKnownFailure(
                command,
                release,
                editorialPlan,
                LegalManifestValidation.failure(Objects.requireNonNull(issue, "issue")));
    }

    /** Builds the conservative apply boundary when no terminal service result escaped. */
    public static LegalEditorialReport forUnknownApply(ValidatedRelease release) {
        LegalManifestIssue issue = LegalManifestIssue.at(
                LegalManifestIssueCode.COMMIT_OUTCOME_UNKNOWN,
                COMMIT_LOCATION);
        return new LegalEditorialReport(
                Command.APPLY_PROMOTE,
                LegalManifestStatus.ERROR,
                null,
                release == null ? null : Publication.from(release, null),
                new Operation(OperationType.PROMOTE, Outcome.UNKNOWN, null),
                null,
                null,
                release == null ? null : Counts.releaseOnly(release),
                IssueSet.from(List.of(issue), 0));
    }

    /** Builds the conservative replace boundary while retaining confirmed input identity. */
    public static LegalEditorialReport forUnknownApplyReplace(
            ValidatedRelease release,
            ValidatedEditorialPlan editorialPlan) {
        ValidatedRelease requiredRelease = Objects.requireNonNull(release, "release");
        ValidatedEditorialPlan requiredPlan = Objects.requireNonNull(
                editorialPlan,
                "editorialPlan");
        LegalManifestIssue issue = LegalManifestIssue.at(
                LegalManifestIssueCode.COMMIT_OUTCOME_UNKNOWN,
                COMMIT_LOCATION);
        return new LegalEditorialReport(
                Command.APPLY_REPLACE,
                LegalManifestStatus.ERROR,
                null,
                Publication.from(requiredRelease, null),
                new Operation(OperationType.REPLACE, Outcome.UNKNOWN, null),
                Plan.identity(requiredPlan),
                null,
                Counts.releaseOnly(requiredRelease),
                IssueSet.from(List.of(issue), 0));
    }

    /** Maps one complete read-only readiness result. */
    public static LegalEditorialReport forReadiness(
            ValidatedRelease release,
            LegalEditorialReadinessResult result) {
        ValidatedRelease requiredRelease = Objects.requireNonNull(release, "release");
        LegalEditorialReadinessResult requiredResult = Objects.requireNonNull(result, "result");
        LegalEditorialReadinessObservation observation = requiredResult.observation().orElse(null);
        UUID publicationUuid = observation == null
                ? null
                : observation.publicationUuid().orElse(null);
        return new LegalEditorialReport(
                Command.READINESS,
                requiredResult.status(),
                Boolean.FALSE,
                Publication.from(requiredRelease, publicationUuid),
                null,
                null,
                Readiness.from(requiredResult.readiness(), observation),
                Counts.fromReadiness(requiredRelease, observation),
                IssueSet.from(requiredResult.issues(), requiredResult.omittedIssueCount()));
    }

    /** Maps one deterministic first-promotion planning result. */
    public static LegalEditorialReport forPlanPromote(
            ValidatedRelease release,
            LegalEditorialPlanResult result) {
        ValidatedRelease requiredRelease = Objects.requireNonNull(release, "release");
        LegalEditorialPlanResult requiredResult = Objects.requireNonNull(result, "result");
        Outcome outcome = switch (requiredResult.outcome()) {
            case APPLICABLE -> Outcome.APPLICABLE;
            case BLOCKED -> Outcome.BLOCKED;
            case ERROR -> Outcome.ERROR;
        };
        boolean applicable = outcome == Outcome.APPLICABLE;
        UUID publicationUuid = applicable
                ? requiredResult.targetPublicationUuid().orElseThrow()
                : null;
        Plan plan = applicable ? Plan.from(requiredResult) : null;
        Readiness readiness = applicable
                ? Readiness.expected(requiredResult.expectedReadinessAfter().orElseThrow())
                : null;
        return new LegalEditorialReport(
                Command.PLAN_PROMOTE,
                requiredResult.status(),
                Boolean.FALSE,
                Publication.from(requiredRelease, publicationUuid),
                new Operation(OperationType.PROMOTE, outcome, null),
                plan,
                readiness,
                Counts.fromPlan(requiredRelease, requiredResult.deltaCounts().orElse(null)),
                IssueSet.from(requiredResult.issues(), requiredResult.omittedIssueCount()));
    }

    /** Maps one deterministic read-only replacement planning result. */
    public static LegalEditorialReport forPlanReplace(
            ValidatedRelease release,
            ValidatedEditorialPlan editorialPlan,
            LegalEditorialPlanResult result) {
        ValidatedRelease requiredRelease = Objects.requireNonNull(release, "release");
        ValidatedEditorialPlan requiredPlan = Objects.requireNonNull(
                editorialPlan,
                "editorialPlan");
        LegalEditorialPlanResult requiredResult = Objects.requireNonNull(result, "result");
        Outcome outcome = switch (requiredResult.outcome()) {
            case APPLICABLE -> Outcome.APPLICABLE;
            case BLOCKED -> Outcome.BLOCKED;
            case ERROR -> Outcome.ERROR;
        };
        boolean applicable = outcome == Outcome.APPLICABLE;
        UUID publicationUuid = applicable
                ? requiredResult.targetPublicationUuid().orElseThrow()
                : null;
        Plan reportPlan = applicable
                ? Plan.fromReplace(requiredPlan, requiredResult)
                : Plan.identity(requiredPlan);
        Readiness readiness = applicable
                ? Readiness.expected(requiredResult.expectedReadinessAfter().orElseThrow())
                : null;
        return new LegalEditorialReport(
                Command.PLAN_REPLACE,
                requiredResult.status(),
                Boolean.FALSE,
                Publication.from(requiredRelease, publicationUuid),
                new Operation(OperationType.REPLACE, outcome, null),
                reportPlan,
                readiness,
                Counts.fromPlan(
                        requiredRelease,
                        applicable ? requiredResult.deltaCounts().orElseThrow() : null),
                IssueSet.from(requiredResult.issues(), requiredResult.omittedIssueCount()));
    }

    /** Maps one first-promotion apply result, including exact replay and UNKNOWN. */
    public static LegalEditorialReport forApplyPromote(
            ValidatedRelease release,
            LegalEditorialApplyResult result) {
        ValidatedRelease requiredRelease = Objects.requireNonNull(release, "release");
        LegalEditorialApplyResult requiredResult = Objects.requireNonNull(result, "result");
        Outcome outcome = switch (requiredResult.outcome()) {
            case APPLIED -> Outcome.APPLIED;
            case ALREADY_APPLIED -> Outcome.ALREADY_APPLIED;
            case BLOCKED -> Outcome.BLOCKED;
            case ERROR -> Outcome.ERROR;
            case UNKNOWN -> Outcome.UNKNOWN;
        };
        LegalEditorialApplyReceipt receipt = requiredResult.receipt().orElse(null);
        if (receipt != null
                && receipt.operationType()
                != LegalEditorialApplyReceipt.OperationType.PROMOTE) {
            throw new IllegalArgumentException(
                    "apply-promote sólo admite receipts PROMOTE");
        }
        UUID publicationUuid = receipt == null ? null : receipt.targetPublicationUuid();
        Instant appliedAt = receipt == null ? null : receipt.appliedAt();
        Readiness readiness = receipt == null
                ? null
                : Readiness.confirmed(receipt.readinessAfter(), receipt.appliedAt());
        return new LegalEditorialReport(
                Command.APPLY_PROMOTE,
                requiredResult.status(),
                requiredResult.persisted(),
                Publication.from(requiredRelease, publicationUuid),
                new Operation(OperationType.PROMOTE, outcome, appliedAt),
                null,
                readiness,
                Counts.fromApply(requiredRelease, receipt),
                IssueSet.from(requiredResult.issues(), requiredResult.omittedIssueCount()));
    }

    /** Maps one replacement apply result, retaining the confirmed plan identity. */
    public static LegalEditorialReport forApplyReplace(
            ValidatedRelease release,
            ValidatedEditorialPlan editorialPlan,
            LegalEditorialApplyResult result) {
        ValidatedRelease requiredRelease = Objects.requireNonNull(release, "release");
        ValidatedEditorialPlan requiredPlan = Objects.requireNonNull(
                editorialPlan,
                "editorialPlan");
        LegalEditorialApplyResult requiredResult = Objects.requireNonNull(result, "result");
        Outcome outcome = switch (requiredResult.outcome()) {
            case APPLIED -> Outcome.APPLIED;
            case ALREADY_APPLIED -> Outcome.ALREADY_APPLIED;
            case BLOCKED -> Outcome.BLOCKED;
            case ERROR -> Outcome.ERROR;
            case UNKNOWN -> Outcome.UNKNOWN;
        };
        LegalEditorialApplyReceipt receipt = requiredResult.receipt().orElse(null);
        if (receipt != null
                && receipt.operationType()
                != LegalEditorialApplyReceipt.OperationType.REPLACE) {
            throw new IllegalArgumentException(
                    "apply-replace sólo admite receipts REPLACE");
        }
        UUID publicationUuid = receipt == null ? null : receipt.targetPublicationUuid();
        Instant appliedAt = receipt == null ? null : receipt.appliedAt();
        Readiness readiness = receipt == null
                ? null
                : Readiness.confirmed(receipt.readinessAfter(), receipt.appliedAt());
        return new LegalEditorialReport(
                Command.APPLY_REPLACE,
                requiredResult.status(),
                requiredResult.persisted(),
                Publication.from(requiredRelease, publicationUuid),
                new Operation(OperationType.REPLACE, outcome, appliedAt),
                Plan.identity(requiredPlan),
                readiness,
                Counts.fromApply(requiredRelease, receipt),
                IssueSet.from(requiredResult.issues(), requiredResult.omittedIssueCount()));
    }

    /** Status consumed by the process boundary for the stable exit code. */
    public LegalManifestStatus status() {
        return status;
    }

    int reportVersion() {
        return REPORT_VERSION;
    }

    Command commandValue() {
        return command;
    }

    String command() {
        return command.externalValue();
    }

    Boolean persisted() {
        return persisted;
    }

    Publication publication() {
        return publication;
    }

    Operation operation() {
        return operation;
    }

    Plan plan() {
        return plan;
    }

    Readiness readiness() {
        return readiness;
    }

    Counts counts() {
        return counts;
    }

    List<LegalManifestIssue> issues() {
        return issues;
    }

    int omittedIssueCount() {
        return omittedIssueCount;
    }

    private void requireValidMatrix() {
        if (status == LegalManifestStatus.PASS) {
            if (!issues.isEmpty() || omittedIssueCount != 0) {
                throw invalidMatrix();
            }
        } else if (issues.isEmpty() || mostSevereIssueStatus() != status) {
            throw invalidMatrix();
        }
        if (publication == null && counts != null) {
            throw invalidMatrix();
        }
        if (counts != null && counts.release() == null) {
            throw invalidMatrix();
        }

        switch (command) {
            case READINESS -> requireReadinessMatrix();
            case PLAN_PROMOTE -> requirePlanPromoteMatrix();
            case APPLY_PROMOTE -> requireApplyMatrix();
            case PLAN_REPLACE -> requirePlanReplaceMatrix();
            case APPLY_REPLACE -> requireApplyReplaceMatrix();
        }
    }

    private void requireReadinessMatrix() {
        if (!Boolean.FALSE.equals(persisted)
                || operation != null
                || plan != null
                || counts != null && counts.delta() != null) {
            throw invalidMatrix();
        }
        if (readiness == null) {
            if (status == LegalManifestStatus.PASS) {
                throw invalidMatrix();
            }
            return;
        }
        boolean valid = switch (readiness.value()) {
            case READY -> status == LegalManifestStatus.PASS
                    && readiness.authoritativeObservation()
                    && counts != null
                    && counts.state() != null;
            case NOT_READY -> status == LegalManifestStatus.BLOCKED
                    && readiness.authoritativeObservation()
                    && counts != null
                    && counts.state() != null;
            case ERROR -> status == LegalManifestStatus.ERROR
                    && !readiness.hasDatabaseMetadata()
                    && (counts == null || counts.state() == null);
        };
        if (!valid) {
            throw invalidMatrix();
        }
    }

    private void requirePlanPromoteMatrix() {
        if (!Boolean.FALSE.equals(persisted)
                || operation == null
                || operation.operationType() != OperationType.PROMOTE
                || operation.appliedAt() != null
                || counts != null && counts.state() != null) {
            throw invalidMatrix();
        }
        boolean valid = switch (operation.outcome()) {
            case APPLICABLE -> status == LegalManifestStatus.PASS
                    && plan != null
                    && readiness != null
                    && readiness.value() == LegalEditorialReadiness.READY
                    && !readiness.hasDatabaseMetadata()
                    && counts != null
                    && counts.delta() != null;
            case BLOCKED -> status == LegalManifestStatus.BLOCKED
                    && plan == null
                    && readiness == null
                    && (counts == null || counts.delta() == null);
            case ERROR -> status == LegalManifestStatus.ERROR
                    && plan == null
                    && readiness == null
                    && (counts == null || counts.delta() == null);
            default -> false;
        };
        if (!valid) {
            throw invalidMatrix();
        }
    }

    private void requirePlanReplaceMatrix() {
        if (!Boolean.FALSE.equals(persisted)
                || operation == null
                || operation.operationType() != OperationType.REPLACE
                || operation.appliedAt() != null
                || counts != null && counts.state() != null) {
            throw invalidMatrix();
        }
        boolean valid = switch (operation.outcome()) {
            case APPLICABLE -> status == LegalManifestStatus.PASS
                    && publication != null
                    && publication.publicationUuid() != null
                    && plan != null
                    && plan.hasExternalIdentity()
                    && plan.hasObservation()
                    && readiness != null
                    && readiness.value() == LegalEditorialReadiness.READY
                    && !readiness.hasDatabaseMetadata()
                    && counts != null
                    && counts.delta() != null;
            case BLOCKED -> status == LegalManifestStatus.BLOCKED
                    && validReplaceFailureMetadata();
            case ERROR -> status == LegalManifestStatus.ERROR
                    && validReplaceFailureMetadata();
            default -> false;
        };
        if (!valid) {
            throw invalidMatrix();
        }
    }

    private boolean validReplaceFailureMetadata() {
        return (plan == null || plan.hasExternalIdentity() && !plan.hasObservation())
                && (publication == null || publication.publicationUuid() == null)
                && readiness == null
                && (counts == null || counts.delta() == null);
    }

    private void requireApplyMatrix() {
        if (operation == null
                || operation.operationType() != OperationType.PROMOTE
                || plan != null
                || counts != null && counts.delta() != null) {
            throw invalidMatrix();
        }
        boolean valid = switch (operation.outcome()) {
            case APPLIED, ALREADY_APPLIED -> status == LegalManifestStatus.PASS
                    && Boolean.TRUE.equals(persisted)
                    && publication != null
                    && publication.publicationUuid() != null
                    && operation.appliedAt() != null
                    && readiness != null
                    && readiness.value() == LegalEditorialReadiness.READY
                    && counts != null
                    && counts.state() != null;
            case BLOCKED -> status == LegalManifestStatus.BLOCKED
                    && Boolean.FALSE.equals(persisted)
                    && noApplyDatabaseMetadata();
            case ERROR -> status == LegalManifestStatus.ERROR
                    && Boolean.FALSE.equals(persisted)
                    && noApplyDatabaseMetadata();
            case UNKNOWN -> status == LegalManifestStatus.ERROR
                    && persisted == null
                    && noApplyDatabaseMetadata()
                    && issues.size() == 1
                    && issues.getFirst().code()
                    == LegalManifestIssueCode.COMMIT_OUTCOME_UNKNOWN
                    && COMMIT_LOCATION.equals(issues.getFirst().location())
                    && omittedIssueCount == 0;
            default -> false;
        };
        if (!valid) {
            throw invalidMatrix();
        }
    }

    private void requireApplyReplaceMatrix() {
        if (operation == null
                || operation.operationType() != OperationType.REPLACE
                || plan != null && (!plan.hasExternalIdentity() || plan.hasObservation())
                || counts != null && counts.delta() != null) {
            throw invalidMatrix();
        }
        boolean valid = switch (operation.outcome()) {
            case APPLIED, ALREADY_APPLIED -> status == LegalManifestStatus.PASS
                    && Boolean.TRUE.equals(persisted)
                    && plan != null
                    && publication != null
                    && publication.publicationUuid() != null
                    && operation.appliedAt() != null
                    && readiness != null
                    && readiness.value() == LegalEditorialReadiness.READY
                    && counts != null
                    && counts.state() != null;
            case BLOCKED -> status == LegalManifestStatus.BLOCKED
                    && Boolean.FALSE.equals(persisted)
                    && noApplyDatabaseMetadata();
            case ERROR -> status == LegalManifestStatus.ERROR
                    && Boolean.FALSE.equals(persisted)
                    && noApplyDatabaseMetadata();
            case UNKNOWN -> status == LegalManifestStatus.ERROR
                    && persisted == null
                    && plan != null
                    && noApplyDatabaseMetadata()
                    && issues.size() == 1
                    && issues.getFirst().code()
                    == LegalManifestIssueCode.COMMIT_OUTCOME_UNKNOWN
                    && COMMIT_LOCATION.equals(issues.getFirst().location())
                    && omittedIssueCount == 0;
            default -> false;
        };
        if (!valid) {
            throw invalidMatrix();
        }
    }

    private boolean noApplyDatabaseMetadata() {
        return (publication == null || publication.publicationUuid() == null)
                && operation.appliedAt() == null
                && readiness == null
                && (counts == null || counts.state() == null);
    }

    private LegalManifestStatus mostSevereIssueStatus() {
        LegalManifestStatus result = LegalManifestStatus.PASS;
        for (LegalManifestIssue issue : issues) {
            result = LegalManifestStatus.mostSevere(result, issue.severity());
        }
        return result;
    }

    private static LegalManifestValidation<?> requireFailure(
            LegalManifestValidation<?> validation) {
        LegalManifestValidation<?> required = Objects.requireNonNull(validation, "failure");
        if (required.passed()) {
            throw new IllegalArgumentException("Un reporte editorial de fallo requiere issues");
        }
        return required;
    }

    private static IllegalArgumentException invalidMatrix() {
        return new IllegalArgumentException(
                "La combinación del reporte editorial v3 no es válida");
    }

    enum OperationType {
        PROMOTE,
        REPLACE
    }

    enum Outcome {
        APPLICABLE,
        APPLIED,
        ALREADY_APPLIED,
        BLOCKED,
        ERROR,
        UNKNOWN
    }

    record Publication(
            String publicationId,
            int schemaVersion,
            String manifestSha256,
            UUID publicationUuid
    ) {
        private static Publication from(ValidatedRelease release, UUID publicationUuid) {
            return new Publication(
                    release.plan().manifest().publicationId(),
                    release.plan().manifest().schemaVersion(),
                    release.plan().manifestSha256(),
                    publicationUuid);
        }
    }

    record Operation(OperationType operationType, Outcome outcome, Instant appliedAt) {
        Operation {
            Objects.requireNonNull(operationType, "operationType");
            Objects.requireNonNull(outcome, "outcome");
            requirePostgresPrecision(appliedAt, "appliedAt");
        }
    }

    record Plan(
            UUID operationId,
            String editorialPlanSha256,
            Boolean changeRequired,
            Instant observedAt,
            LegalEditorialReadiness expectedReadinessAfter
    ) {
        Plan {
            if ((operationId == null) != (editorialPlanSha256 == null)) {
                throw new IllegalArgumentException(
                        "La identidad del plan editorial debe ser completa o ausente");
            }
            if ((changeRequired == null) != (observedAt == null)) {
                throw new IllegalArgumentException(
                        "La observación del plan editorial debe ser completa o ausente");
            }
            requirePostgresPrecision(observedAt, "observedAt");
            if (expectedReadinessAfter != LegalEditorialReadiness.READY) {
                throw new IllegalArgumentException(
                        "El plan editorial sólo admite readiness esperado READY");
            }
        }

        private static Plan from(LegalEditorialPlanResult result) {
            return new Plan(
                    null,
                    null,
                    result.changeRequired().orElseThrow(),
                    result.observedAt().orElseThrow(),
                    result.expectedReadinessAfter().orElseThrow());
        }

        private static Plan identity(ValidatedEditorialPlan editorialPlan) {
            ValidatedEditorialPlan required = Objects.requireNonNull(
                    editorialPlan,
                    "editorialPlan");
            if (required.operationType()
                    != LegalEditorialPlanV1.OperationType.REPLACE) {
                throw new IllegalArgumentException(
                        "plan-replace sólo admite planes REPLACE");
            }
            return new Plan(
                    required.operationId(),
                    required.editorialPlanSha256(),
                    null,
                    null,
                    required.plan().expectedReadinessAfter());
        }

        private static Plan fromReplace(
                ValidatedEditorialPlan editorialPlan,
                LegalEditorialPlanResult result) {
            Plan identity = identity(editorialPlan);
            return new Plan(
                    identity.operationId(),
                    identity.editorialPlanSha256(),
                    result.changeRequired().orElseThrow(),
                    result.observedAt().orElseThrow(),
                    identity.expectedReadinessAfter());
        }

        private boolean hasExternalIdentity() {
            return operationId != null && editorialPlanSha256 != null;
        }

        private boolean hasObservation() {
            return changeRequired != null && observedAt != null;
        }
    }

    record Readiness(
            LegalEditorialReadiness value,
            Instant observedAt,
            String editorialStateFingerprint
    ) {
        Readiness {
            Objects.requireNonNull(value, "value");
            requirePostgresPrecision(observedAt, "observedAt");
            if ((observedAt == null) != (editorialStateFingerprint == null)) {
                throw new IllegalArgumentException(
                        "La metadata de readiness debe ser completa o ausente");
            }
            if (value == LegalEditorialReadiness.ERROR && observedAt != null) {
                throw new IllegalArgumentException(
                        "ERROR no puede exponer una observación de base");
            }
        }

        private static Readiness from(
                LegalEditorialReadiness value,
                LegalEditorialReadinessObservation observation) {
            return observation == null
                    ? new Readiness(value, null, null)
                    : new Readiness(
                            value,
                            observation.observedAt(),
                            observation.editorialStateFingerprint());
        }

        private static Readiness expected(LegalEditorialReadiness value) {
            return new Readiness(value, null, null);
        }

        private static Readiness confirmed(
                LegalEditorialReadiness value,
                Instant observedAt) {
            // Apply receipts intentionally do not expose a fingerprint. The timestamp belongs to
            // the operation object, not to an independently observed readiness projection.
            Objects.requireNonNull(observedAt, "observedAt");
            return new Readiness(value, null, null);
        }

        private boolean authoritativeObservation() {
            return observedAt != null && editorialStateFingerprint != null;
        }

        private boolean hasDatabaseMetadata() {
            return observedAt != null || editorialStateFingerprint != null;
        }
    }

    record Counts(ReleaseCounts release, StateCounts state, DeltaCounts delta) {
        Counts {
            Objects.requireNonNull(release, "release");
        }

        private static Counts releaseOnly(ValidatedRelease release) {
            return new Counts(ReleaseCounts.from(release), null, null);
        }

        private static Counts fromReadiness(
                ValidatedRelease release,
                LegalEditorialReadinessObservation observation) {
            return new Counts(
                    ReleaseCounts.from(release),
                    observation == null ? null : StateCounts.from(observation),
                    null);
        }

        private static Counts fromPlan(
                ValidatedRelease release,
                LegalEditorialPlanResult.DeltaCounts delta) {
            return new Counts(
                    ReleaseCounts.from(release),
                    null,
                    delta == null ? null : DeltaCounts.from(delta));
        }

        private static Counts fromApply(
                ValidatedRelease release,
                LegalEditorialApplyReceipt receipt) {
            return new Counts(
                    ReleaseCounts.from(release),
                    receipt == null ? null : StateCounts.from(receipt),
                    null);
        }
    }

    record ReleaseCounts(int documents, int requirements, int scopes) {
        ReleaseCounts {
            requireNonNegative(documents, requirements, scopes);
        }

        private static ReleaseCounts from(ValidatedRelease release) {
            return new ReleaseCounts(
                    release.documentCount(),
                    release.requirementCount(),
                    release.scopeCount());
        }
    }

    record StateCounts(
            int documentVersions,
            int requirementVersions,
            int documentTransitions,
            int requirementTransitions,
            int documentSlots,
            int requiredSetPointers,
            int replacementBatches
    ) {
        StateCounts {
            requireNonNegative(
                    documentVersions,
                    requirementVersions,
                    documentTransitions,
                    requirementTransitions,
                    documentSlots,
                    requiredSetPointers,
                    replacementBatches);
        }

        private static StateCounts from(LegalEditorialReadinessObservation observation) {
            return new StateCounts(
                    observation.documentVersions(),
                    observation.requirementVersions(),
                    observation.documentTransitions(),
                    observation.requirementTransitions(),
                    observation.documentSlots(),
                    observation.currentRequirementSets(),
                    observation.replacementLots());
        }

        private static StateCounts from(LegalEditorialApplyReceipt receipt) {
            return new StateCounts(
                    receipt.documentVersions(),
                    receipt.requirementVersions(),
                    receipt.documentTransitions(),
                    receipt.requirementTransitions(),
                    receipt.documentSlots(),
                    receipt.requiredSetPointers(),
                    receipt.replacementBatches());
        }
    }

    record DeltaCounts(
            int directDocumentTransitions,
            int triggerDerivedDocumentTransitions,
            int directRequirementTransitions,
            int directDocumentSlotDeletes,
            int directDocumentSlotInserts,
            int triggerDerivedDocumentSlotDeletes,
            int triggerDerivedDocumentSlotInserts,
            int requiredSetPointerDeletes,
            int requiredSetPointerInserts,
            int replacementBatches
    ) {
        DeltaCounts {
            requireNonNegative(
                    directDocumentTransitions,
                    triggerDerivedDocumentTransitions,
                    directRequirementTransitions,
                    directDocumentSlotDeletes,
                    directDocumentSlotInserts,
                    triggerDerivedDocumentSlotDeletes,
                    triggerDerivedDocumentSlotInserts,
                    requiredSetPointerDeletes,
                    requiredSetPointerInserts,
                    replacementBatches);
        }

        private static DeltaCounts from(LegalEditorialPlanResult.DeltaCounts source) {
            return new DeltaCounts(
                    source.directDocumentTransitions(),
                    source.triggerDerivedDocumentTransitions(),
                    source.directRequirementTransitions(),
                    source.directDocumentSlotDeletes(),
                    source.directDocumentSlotInserts(),
                    source.triggerDerivedDocumentSlotDeletes(),
                    source.triggerDerivedDocumentSlotInserts(),
                    source.requiredSetPointerDeletes(),
                    source.requiredSetPointerInserts(),
                    source.replacementBatches());
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

    private static void requirePostgresPrecision(Instant value, String name) {
        if (value != null && value.getNano() % 1_000 != 0) {
            throw new IllegalArgumentException(name + " supera la precisión de PostgreSQL");
        }
    }

    private static void requireNonNegative(int... values) {
        for (int value : values) {
            if (value < 0) {
                throw new IllegalArgumentException(
                        "Los conteos editoriales no pueden ser negativos");
            }
        }
    }
}
