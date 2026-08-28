package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialReadiness;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidation;

import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Closed result matrix for a mutating editorial operation and its transaction completion. */
public final class LegalEditorialApplyResult {

    private static final String COMMIT_LOCATION = "database/commit";
    private static final Set<LegalManifestIssueCode> BLOCKED_CODES = Set.copyOf(EnumSet.of(
            LegalManifestIssueCode.PUBLICATION_NOT_SEALED,
            LegalManifestIssueCode.PUBLICATION_CONTENT_MISMATCH,
            LegalManifestIssueCode.EFFECTIVE_DATE_NOT_REACHED,
            LegalManifestIssueCode.CURRENT_STATE_MISMATCH,
            LegalManifestIssueCode.SOURCE_FINGERPRINT_MISMATCH,
            LegalManifestIssueCode.INITIAL_PROJECTION_ALREADY_EXISTS,
            LegalManifestIssueCode.SCOPE_COVERAGE_INCOMPLETE,
            LegalManifestIssueCode.REPLACEMENT_MAPPING_INVALID,
            LegalManifestIssueCode.RETIREMENT_REASON_REQUIRED,
            LegalManifestIssueCode.EXPECTED_READINESS_MISMATCH,
            LegalManifestIssueCode.FAIL_CLOSED_GAP_NOT_ACKNOWLEDGED,
            LegalManifestIssueCode.REVISION_MISMATCH));
    private static final Set<LegalManifestIssueCode> ERROR_CODES = Set.copyOf(EnumSet.of(
            LegalManifestIssueCode.CONCURRENT_OPERATION,
            LegalManifestIssueCode.ROLE_PRIVILEGE_DRIFT,
            LegalManifestIssueCode.SCHEMA_DRIFT,
            LegalManifestIssueCode.POSTCONDITION_NOT_READY,
            LegalManifestIssueCode.EDITORIAL_OBSERVATION_FAILED));

    private final LegalManifestStatus status;
    private final Boolean persisted;
    private final Outcome outcome;
    private final LegalEditorialApplyReceipt receipt;
    private final List<LegalManifestIssue> issues;
    private final int omittedIssueCount;

    private LegalEditorialApplyResult(
            LegalManifestStatus status,
            Boolean persisted,
            Outcome outcome,
            LegalEditorialApplyReceipt receipt,
            List<LegalManifestIssue> issues,
            int omittedIssueCount) {
        this.status = Objects.requireNonNull(status, "status");
        this.persisted = persisted;
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        this.receipt = receipt;
        this.issues = List.copyOf(Objects.requireNonNull(issues, "issues"));
        if (omittedIssueCount < 0) {
            throw new IllegalArgumentException(
                    "El conteo de issues omitidos no puede ser negativo");
        }
        this.omittedIssueCount = omittedIssueCount;
        requireValidMatrix();
    }

    static LegalEditorialApplyResult applied(LegalEditorialApplyReceipt receipt) {
        return success(Outcome.APPLIED, receipt);
    }

    static LegalEditorialApplyResult alreadyApplied(LegalEditorialApplyReceipt receipt) {
        return success(Outcome.ALREADY_APPLIED, receipt);
    }

    static LegalEditorialApplyResult blocked(Collection<LegalManifestIssue> issues) {
        IssueSet normalized = normalize(issues, LegalManifestStatus.BLOCKED, BLOCKED_CODES);
        return new LegalEditorialApplyResult(
                LegalManifestStatus.BLOCKED,
                Boolean.FALSE,
                Outcome.BLOCKED,
                null,
                normalized.issues(),
                normalized.omittedIssueCount());
    }

    static LegalEditorialApplyResult error(Collection<LegalManifestIssue> issues) {
        IssueSet normalized = normalize(issues, LegalManifestStatus.ERROR, ERROR_CODES);
        return new LegalEditorialApplyResult(
                LegalManifestStatus.ERROR,
                Boolean.FALSE,
                Outcome.ERROR,
                null,
                normalized.issues(),
                normalized.omittedIssueCount());
    }

    static LegalEditorialApplyResult unknown() {
        return new LegalEditorialApplyResult(
                LegalManifestStatus.ERROR,
                null,
                Outcome.UNKNOWN,
                null,
                List.of(LegalManifestIssue.at(
                        LegalManifestIssueCode.COMMIT_OUTCOME_UNKNOWN,
                        COMMIT_LOCATION)),
                0);
    }

    public LegalManifestStatus status() {
        return status;
    }

    /** Returns true, false or null when transaction completion is indeterminate. */
    public Boolean persisted() {
        return persisted;
    }

    public Outcome outcome() {
        return outcome;
    }

    public Optional<LegalEditorialApplyReceipt> receipt() {
        return Optional.ofNullable(receipt);
    }

    public Optional<LegalEditorialApplyReceipt.OperationType> operationType() {
        return receipt().map(LegalEditorialApplyReceipt::operationType);
    }

    public Optional<UUID> targetPublicationUuid() {
        return receipt().map(LegalEditorialApplyReceipt::targetPublicationUuid);
    }

    public Optional<Instant> appliedAt() {
        return receipt().map(LegalEditorialApplyReceipt::appliedAt);
    }

    public Optional<LegalEditorialReadiness> readinessAfter() {
        return receipt().map(LegalEditorialApplyReceipt::readinessAfter);
    }

    public List<LegalManifestIssue> issues() {
        return issues;
    }

    public int omittedIssueCount() {
        return omittedIssueCount;
    }

    private static LegalEditorialApplyResult success(
            Outcome outcome,
            LegalEditorialApplyReceipt receipt) {
        if (outcome != Outcome.APPLIED && outcome != Outcome.ALREADY_APPLIED) {
            throw new IllegalArgumentException("Un éxito requiere un outcome confirmado");
        }
        return new LegalEditorialApplyResult(
                LegalManifestStatus.PASS,
                Boolean.TRUE,
                outcome,
                Objects.requireNonNull(receipt, "receipt"),
                List.of(),
                0);
    }

    private static IssueSet normalize(
            Collection<LegalManifestIssue> candidates,
            LegalManifestStatus requiredSeverity,
            Set<LegalManifestIssueCode> allowedCodes) {
        List<LegalManifestIssue> snapshot = List.copyOf(Objects.requireNonNull(
                candidates,
                "issues"));
        if (snapshot.isEmpty()) {
            throw new IllegalArgumentException(
                    "Un resultado apply no exitoso requiere al menos un issue");
        }
        for (LegalManifestIssue issue : snapshot) {
            if (issue.severity() != requiredSeverity || !allowedCodes.contains(issue.code())) {
                throw new IllegalArgumentException(
                        "El issue no pertenece a la matriz apply editorial");
            }
        }
        LegalManifestValidation<Object> normalized = LegalManifestValidation.failure(snapshot);
        if (normalized.status() != requiredSeverity) {
            throw new IllegalArgumentException(
                    "La severidad agregada no coincide con el resultado apply");
        }
        return new IssueSet(normalized.issues(), normalized.omittedIssueCount());
    }

    private void requireValidMatrix() {
        boolean valid = switch (outcome) {
            case APPLIED, ALREADY_APPLIED -> status == LegalManifestStatus.PASS
                    && Boolean.TRUE.equals(persisted)
                    && receipt != null
                    && issues.isEmpty()
                    && omittedIssueCount == 0;
            case BLOCKED -> status == LegalManifestStatus.BLOCKED
                    && Boolean.FALSE.equals(persisted)
                    && receipt == null
                    && !issues.isEmpty()
                    && issues.stream().allMatch(issue ->
                            issue.severity() == LegalManifestStatus.BLOCKED
                                    && BLOCKED_CODES.contains(issue.code()));
            case ERROR -> status == LegalManifestStatus.ERROR
                    && Boolean.FALSE.equals(persisted)
                    && receipt == null
                    && !issues.isEmpty()
                    && issues.stream().allMatch(issue ->
                            issue.severity() == LegalManifestStatus.ERROR
                                    && ERROR_CODES.contains(issue.code()));
            case UNKNOWN -> status == LegalManifestStatus.ERROR
                    && persisted == null
                    && receipt == null
                    && issues.size() == 1
                    && issues.getFirst().code() == LegalManifestIssueCode.COMMIT_OUTCOME_UNKNOWN
                    && COMMIT_LOCATION.equals(issues.getFirst().location())
                    && omittedIssueCount == 0;
        };
        if (!valid) {
            throw new IllegalArgumentException(
                    "La combinación del resultado apply editorial no es válida");
        }
    }

    public enum Outcome {
        APPLIED,
        ALREADY_APPLIED,
        BLOCKED,
        ERROR,
        UNKNOWN
    }

    private record IssueSet(List<LegalManifestIssue> issues, int omittedIssueCount) {

        private IssueSet {
            issues = List.copyOf(Objects.requireNonNull(issues, "issues"));
            if (omittedIssueCount < 0) {
                throw new IllegalArgumentException(
                        "El conteo de issues omitidos no puede ser negativo");
            }
        }
    }
}
