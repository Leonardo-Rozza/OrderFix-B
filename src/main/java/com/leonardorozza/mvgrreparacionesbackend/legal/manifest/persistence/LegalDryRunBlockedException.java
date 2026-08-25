package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;

import java.util.Objects;

/** Expected incompatibility between an accredited release and persisted legal state. */
final class LegalDryRunBlockedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final LegalManifestIssue issue;

    LegalDryRunBlockedException(LegalManifestIssueCode code, String location) {
        this(LegalManifestIssue.at(code, location));
    }

    LegalDryRunBlockedException(LegalManifestIssue issue) {
        super(requireBlocked(issue).message());
        this.issue = issue;
    }

    LegalManifestIssue issue() {
        return issue;
    }

    private static LegalManifestIssue requireBlocked(LegalManifestIssue candidate) {
        LegalManifestIssue required = Objects.requireNonNull(candidate, "issue");
        if (required.severity() != LegalManifestStatus.BLOCKED) {
            throw new IllegalArgumentException("Un blocker JDBC debe tener severidad BLOCKED");
        }
        return required;
    }
}
