package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;

import java.util.Objects;

/** Safe operational failure detected explicitly at the legal import boundary. */
final class LegalImportOperationalException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final LegalManifestIssue issue;

    LegalImportOperationalException(LegalManifestIssueCode code, String location) {
        this(LegalManifestIssue.at(code, location), null);
    }

    LegalImportOperationalException(
            LegalManifestIssueCode code,
            String location,
            Throwable cause) {
        this(LegalManifestIssue.at(code, location), cause);
    }

    LegalImportOperationalException(LegalManifestIssue issue) {
        this(issue, null);
    }

    LegalImportOperationalException(LegalManifestIssue issue, Throwable cause) {
        super(requireError(issue).message(), cause);
        this.issue = issue;
    }

    LegalManifestIssue issue() {
        return issue;
    }

    private static LegalManifestIssue requireError(LegalManifestIssue candidate) {
        LegalManifestIssue required = Objects.requireNonNull(candidate, "issue");
        if (required.severity() != LegalManifestStatus.ERROR
                || !required.code().name().startsWith("IMPORT_")
                || required.code() == LegalManifestIssueCode.IMPORT_DB_COMMIT_UNKNOWN) {
            throw new IllegalArgumentException(
                    "Un fallo operativo requiere un código IMPORT_* ERROR no ambiguo");
        }
        return required;
    }
}
