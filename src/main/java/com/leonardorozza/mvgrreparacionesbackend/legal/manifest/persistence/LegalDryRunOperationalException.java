package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;

import java.util.Objects;

/** Safe operational failure detected explicitly at the legal JDBC boundary. */
final class LegalDryRunOperationalException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final LegalManifestIssue issue;

    LegalDryRunOperationalException(LegalManifestIssueCode code, String location) {
        this(LegalManifestIssue.at(code, location), null);
    }

    LegalDryRunOperationalException(
            LegalManifestIssueCode code,
            String location,
            Throwable cause) {
        this(LegalManifestIssue.at(code, location), cause);
    }

    LegalDryRunOperationalException(LegalManifestIssue issue, Throwable cause) {
        super(requireError(issue).message(), cause);
        this.issue = issue;
    }

    LegalManifestIssue issue() {
        return issue;
    }

    private static LegalManifestIssue requireError(LegalManifestIssue candidate) {
        LegalManifestIssue required = Objects.requireNonNull(candidate, "issue");
        if (required.severity() != LegalManifestStatus.ERROR) {
            throw new IllegalArgumentException("Un fallo JDBC operativo debe tener severidad ERROR");
        }
        return required;
    }
}
