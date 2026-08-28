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

/** Closed, non-mutating result matrix for an editorial execution plan. */
public final class LegalEditorialPlanResult {

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
            LegalManifestIssueCode.EDITORIAL_OBSERVATION_FAILED));

    private final LegalManifestStatus status;
    private final Outcome outcome;
    private final LegalEditorialExecutionPlan executionPlan;
    private final List<LegalManifestIssue> issues;
    private final int omittedIssueCount;

    private LegalEditorialPlanResult(
            LegalManifestStatus status,
            Outcome outcome,
            LegalEditorialExecutionPlan executionPlan,
            List<LegalManifestIssue> issues,
            int omittedIssueCount) {
        this.status = Objects.requireNonNull(status, "status");
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        this.executionPlan = executionPlan;
        this.issues = List.copyOf(Objects.requireNonNull(issues, "issues"));
        if (omittedIssueCount < 0) {
            throw new IllegalArgumentException(
                    "El conteo de issues omitidos no puede ser negativo");
        }
        this.omittedIssueCount = omittedIssueCount;
        requireValidMatrix();
    }

    static LegalEditorialPlanResult applicable(LegalEditorialExecutionPlan executionPlan) {
        return new LegalEditorialPlanResult(
                LegalManifestStatus.PASS,
                Outcome.APPLICABLE,
                Objects.requireNonNull(executionPlan, "executionPlan"),
                List.of(),
                0);
    }

    static LegalEditorialPlanResult blocked(Collection<LegalManifestIssue> issues) {
        IssueSet normalized = normalize(issues, LegalManifestStatus.BLOCKED, BLOCKED_CODES);
        return new LegalEditorialPlanResult(
                LegalManifestStatus.BLOCKED,
                Outcome.BLOCKED,
                null,
                normalized.issues(),
                normalized.omittedIssueCount());
    }

    static LegalEditorialPlanResult error(Collection<LegalManifestIssue> issues) {
        IssueSet normalized = normalize(issues, LegalManifestStatus.ERROR, ERROR_CODES);
        return new LegalEditorialPlanResult(
                LegalManifestStatus.ERROR,
                Outcome.ERROR,
                null,
                normalized.issues(),
                normalized.omittedIssueCount());
    }

    public LegalManifestStatus status() {
        return status;
    }

    public boolean persisted() {
        return false;
    }

    public Outcome outcome() {
        return outcome;
    }

    public Optional<LegalEditorialReadiness> expectedReadinessAfter() {
        return optionalPlan().map(LegalEditorialExecutionPlan::expectedReadinessAfter);
    }

    public Optional<Boolean> changeRequired() {
        return optionalPlan().map(LegalEditorialExecutionPlan::changeRequired);
    }

    public Optional<Instant> observedAt() {
        return optionalPlan().map(LegalEditorialExecutionPlan::observedAt);
    }

    public Optional<UUID> targetPublicationUuid() {
        return optionalPlan().map(plan -> plan.target().publicationUuid());
    }

    public Optional<DeltaCounts> deltaCounts() {
        return optionalPlan().map(plan -> DeltaCounts.from(plan.deltaCounts()));
    }

    public List<LegalManifestIssue> issues() {
        return issues;
    }

    public int omittedIssueCount() {
        return omittedIssueCount;
    }

    Optional<LegalEditorialExecutionPlan> executionPlan() {
        return optionalPlan();
    }

    private Optional<LegalEditorialExecutionPlan> optionalPlan() {
        return Optional.ofNullable(executionPlan);
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
                    "Un resultado de plan no exitoso requiere al menos un issue");
        }
        for (LegalManifestIssue issue : snapshot) {
            if (issue.severity() != requiredSeverity
                    || !allowedCodes.contains(issue.code())) {
                throw new IllegalArgumentException(
                        "El issue no pertenece a la matriz del plan editorial");
            }
        }

        LegalManifestValidation<Object> normalized = LegalManifestValidation.failure(snapshot);
        if (normalized.status() != requiredSeverity) {
            throw new IllegalArgumentException(
                    "La severidad agregada no coincide con el resultado del plan");
        }
        return new IssueSet(normalized.issues(), normalized.omittedIssueCount());
    }

    private void requireValidMatrix() {
        boolean valid = switch (outcome) {
            case APPLICABLE -> status == LegalManifestStatus.PASS
                    && executionPlan != null
                    && issues.isEmpty()
                    && omittedIssueCount == 0;
            case BLOCKED -> status == LegalManifestStatus.BLOCKED
                    && executionPlan == null
                    && !issues.isEmpty()
                    && issues.stream().allMatch(issue ->
                            issue.severity() == LegalManifestStatus.BLOCKED
                                    && BLOCKED_CODES.contains(issue.code()));
            case ERROR -> status == LegalManifestStatus.ERROR
                    && executionPlan == null
                    && !issues.isEmpty()
                    && issues.stream().allMatch(issue ->
                            issue.severity() == LegalManifestStatus.ERROR
                                    && ERROR_CODES.contains(issue.code()));
        };
        if (!valid) {
            throw new IllegalArgumentException(
                    "La combinación del resultado de plan editorial no es válida");
        }
    }

    public enum Outcome {
        APPLICABLE,
        BLOCKED,
        ERROR
    }

    /** Public, safe delta summary; it contains no plan artifact or database content. */
    public record DeltaCounts(
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

        public DeltaCounts {
            if (directDocumentTransitions < 0
                    || triggerDerivedDocumentTransitions < 0
                    || directRequirementTransitions < 0
                    || directDocumentSlotDeletes < 0
                    || directDocumentSlotInserts < 0
                    || triggerDerivedDocumentSlotDeletes < 0
                    || triggerDerivedDocumentSlotInserts < 0
                    || requiredSetPointerDeletes < 0
                    || requiredSetPointerInserts < 0
                    || replacementBatches < 0) {
                throw new IllegalArgumentException(
                        "Los conteos públicos del delta no pueden ser negativos");
            }
        }

        private static DeltaCounts from(
                LegalEditorialExecutionPlan.DeltaCounts source) {
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

        public boolean isZero() {
            return equals(new DeltaCounts(0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
        }
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
