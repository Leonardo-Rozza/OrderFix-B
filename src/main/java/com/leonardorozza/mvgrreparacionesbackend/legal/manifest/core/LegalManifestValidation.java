package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Resultado explícito para validaciones esperables del manifiesto.
 *
 * <p>Los fallos nunca conservan un valor parcial. Sus issues se deduplican, ordenan y acotan de
 * forma estable antes de exponerse.</p>
 */
public final class LegalManifestValidation<T> {

    private final LegalManifestStatus status;
    private final T value;
    private final List<LegalManifestIssue> issues;
    private final int omittedIssueCount;

    private LegalManifestValidation(
            LegalManifestStatus status,
            T value,
            List<LegalManifestIssue> issues,
            int omittedIssueCount) {
        this.status = status;
        this.value = value;
        this.issues = issues;
        this.omittedIssueCount = omittedIssueCount;
    }

    public static <T> LegalManifestValidation<T> pass(T value) {
        return new LegalManifestValidation<>(
                LegalManifestStatus.PASS,
                Objects.requireNonNull(value, "value"),
                List.of(),
                0);
    }

    public static <T> LegalManifestValidation<T> failure(LegalManifestIssue issue) {
        return failure(List.of(Objects.requireNonNull(issue, "issue")));
    }

    public static <T> LegalManifestValidation<T> failure(
            Collection<LegalManifestIssue> candidateIssues) {
        Objects.requireNonNull(candidateIssues, "candidateIssues");
        if (candidateIssues.isEmpty()) {
            throw new IllegalArgumentException("Un resultado fallido debe contener al menos un issue");
        }

        TreeSet<LegalManifestIssue> ordered = new TreeSet<>(LegalManifestIssue.ORDERING);
        LegalManifestStatus status = LegalManifestStatus.PASS;
        for (LegalManifestIssue issue : candidateIssues) {
            LegalManifestIssue required = Objects.requireNonNull(issue, "issue");
            ordered.add(required);
            status = LegalManifestStatus.mostSevere(status, required.severity());
        }

        if (status == LegalManifestStatus.PASS) {
            throw new IllegalArgumentException("Un issue no puede tener severidad PASS");
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

        return new LegalManifestValidation<>(
                status,
                null,
                List.copyOf(exposed),
                ordered.size() - exposedCount);
    }

    public LegalManifestStatus status() {
        return status;
    }

    public Optional<T> value() {
        return Optional.ofNullable(value);
    }

    public List<LegalManifestIssue> issues() {
        return issues;
    }

    public int omittedIssueCount() {
        return omittedIssueCount;
    }

    public boolean passed() {
        return status == LegalManifestStatus.PASS;
    }

    public boolean blocked() {
        return status == LegalManifestStatus.BLOCKED;
    }

    public boolean error() {
        return status == LegalManifestStatus.ERROR;
    }

    public <R> LegalManifestValidation<R> asFailure() {
        if (passed()) {
            throw new IllegalStateException("Un resultado PASS no puede propagarse como fallo");
        }
        return new LegalManifestValidation<>(status, null, issues, omittedIssueCount);
    }
}
