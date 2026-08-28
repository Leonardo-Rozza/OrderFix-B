package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialReadiness;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidation;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Closed readiness matrix that always represents a non-mutating editorial observation. */
public final class LegalEditorialReadinessResult {

    private static final Set<LegalManifestIssueCode> EDITORIAL_REASON_CODES = Set.copyOf(
            EnumSet.of(
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
                    LegalManifestIssueCode.REVISION_MISMATCH,
                    LegalManifestIssueCode.CONCURRENT_OPERATION,
                    LegalManifestIssueCode.ROLE_PRIVILEGE_DRIFT,
                    LegalManifestIssueCode.SCHEMA_DRIFT,
                    LegalManifestIssueCode.POSTCONDITION_NOT_READY,
                    LegalManifestIssueCode.COMMIT_OUTCOME_UNKNOWN));

    private final LegalEditorialReadiness readiness;
    private final LegalEditorialReadinessObservation observation;
    private final List<LegalManifestIssue> issues;
    private final int omittedIssueCount;

    private LegalEditorialReadinessResult(
            LegalEditorialReadiness readiness,
            LegalEditorialReadinessObservation observation,
            List<LegalManifestIssue> issues,
            int omittedIssueCount) {
        this.readiness = Objects.requireNonNull(readiness, "readiness");
        this.observation = observation;
        this.issues = List.copyOf(Objects.requireNonNull(issues, "issues"));
        if (omittedIssueCount < 0) {
            throw new IllegalArgumentException(
                    "El conteo de issues omitidos no puede ser negativo");
        }
        this.omittedIssueCount = omittedIssueCount;
        requireValidMatrix();
    }

    static LegalEditorialReadinessResult ready(
            LegalEditorialReadinessObservation observation) {
        LegalEditorialReadinessObservation required = Objects.requireNonNull(
                observation,
                "observation");
        if (required.publicationUuid().isEmpty()) {
            throw new IllegalArgumentException(
                    "READY requiere una publicación persistida identificable");
        }
        return new LegalEditorialReadinessResult(
                LegalEditorialReadiness.READY,
                required,
                List.of(),
                0);
    }

    static LegalEditorialReadinessResult notReady(
            LegalEditorialReadinessObservation observation,
            Collection<LegalManifestIssue> findings) {
        LegalEditorialReadinessObservation required = Objects.requireNonNull(
                observation,
                "observation");
        IssueSet normalized = normalize(findings, LegalManifestStatus.BLOCKED);
        return new LegalEditorialReadinessResult(
                LegalEditorialReadiness.NOT_READY,
                required,
                normalized.issues(),
                normalized.omittedIssueCount());
    }

    static LegalEditorialReadinessResult error(
            Collection<LegalManifestIssue> issues) {
        IssueSet normalized = normalize(issues, LegalManifestStatus.ERROR);
        return new LegalEditorialReadinessResult(
                LegalEditorialReadiness.ERROR,
                null,
                normalized.issues(),
                normalized.omittedIssueCount());
    }

    public LegalManifestStatus status() {
        return switch (readiness) {
            case READY -> LegalManifestStatus.PASS;
            case NOT_READY -> LegalManifestStatus.BLOCKED;
            case ERROR -> LegalManifestStatus.ERROR;
        };
    }

    public boolean persisted() {
        return false;
    }

    public LegalEditorialReadiness readiness() {
        return readiness;
    }

    public Optional<LegalEditorialReadinessObservation> observation() {
        return Optional.ofNullable(observation);
    }

    public List<LegalManifestIssue> issues() {
        return issues;
    }

    public int omittedIssueCount() {
        return omittedIssueCount;
    }

    private static IssueSet normalize(
            Collection<LegalManifestIssue> candidates,
            LegalManifestStatus requiredSeverity) {
        List<LegalManifestIssue> snapshot = List.copyOf(Objects.requireNonNull(
                candidates,
                "issues"));
        if (snapshot.isEmpty()) {
            throw new IllegalArgumentException(
                    "Un readiness no exitoso requiere al menos un issue");
        }
        for (LegalManifestIssue issue : snapshot) {
            if (issue.severity() != requiredSeverity
                    || !EDITORIAL_REASON_CODES.contains(issue.code())) {
                throw new IllegalArgumentException(
                        "El issue no pertenece a la matriz editorial requerida");
            }
        }

        LegalManifestValidation<Object> normalized = LegalManifestValidation.failure(snapshot);
        if (normalized.status() != requiredSeverity) {
            throw new IllegalArgumentException(
                    "La severidad agregada no coincide con el readiness requerido");
        }
        return new IssueSet(normalized.issues(), normalized.omittedIssueCount());
    }

    private void requireValidMatrix() {
        switch (readiness) {
            case READY -> {
                if (observation == null
                        || observation.publicationUuid().isEmpty()
                        || !issues.isEmpty()
                        || omittedIssueCount != 0) {
                    throw invalidMatrix();
                }
            }
            case NOT_READY -> {
                if (observation == null
                        || issues.isEmpty()
                        || issues.stream().anyMatch(issue ->
                                issue.severity() != LegalManifestStatus.BLOCKED
                                        || !EDITORIAL_REASON_CODES.contains(issue.code()))) {
                    throw invalidMatrix();
                }
            }
            case ERROR -> {
                if (observation != null
                        || issues.isEmpty()
                        || issues.stream().anyMatch(issue ->
                                issue.severity() != LegalManifestStatus.ERROR
                                        || !EDITORIAL_REASON_CODES.contains(issue.code()))) {
                    throw invalidMatrix();
                }
            }
            default -> throw invalidMatrix();
        }
    }

    private static IllegalArgumentException invalidMatrix() {
        return new IllegalArgumentException(
                "La combinación del readiness editorial no es válida");
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
