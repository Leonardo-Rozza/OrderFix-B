package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;

import java.util.Objects;

/** Expected incompatibility between an accredited release and persisted import state. */
final class LegalImportBlockedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final LegalManifestIssue issue;

    LegalImportBlockedException(LegalManifestIssueCode code, String location) {
        this(LegalManifestIssue.at(code, location), null);
    }

    LegalImportBlockedException(
            LegalManifestIssueCode code,
            String location,
            Throwable cause) {
        this(LegalManifestIssue.at(code, location), cause);
    }

    LegalImportBlockedException(LegalManifestIssue issue) {
        this(issue, null);
    }

    LegalImportBlockedException(LegalManifestIssue issue, Throwable cause) {
        super(requireBlocked(issue).message(), cause);
        this.issue = issue;
    }

    LegalManifestIssue issue() {
        return issue;
    }

    private static LegalManifestIssue requireBlocked(LegalManifestIssue candidate) {
        LegalManifestIssue required = Objects.requireNonNull(candidate, "issue");
        if (required.severity() != LegalManifestStatus.BLOCKED
                || !required.code().name().startsWith("IMPORT_")) {
            throw new IllegalArgumentException(
                    "Un bloqueo de importación debe usar un código IMPORT_* BLOCKED");
        }
        return required;
    }
}
