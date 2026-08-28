package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** Safe operational failure detected explicitly at the editorial database boundary. */
final class LegalEditorialOperationalException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private static final Set<LegalManifestIssueCode> ALLOWED_CODES = Set.copyOf(EnumSet.of(
            LegalManifestIssueCode.CONCURRENT_OPERATION,
            LegalManifestIssueCode.ROLE_PRIVILEGE_DRIFT,
            LegalManifestIssueCode.SCHEMA_DRIFT,
            LegalManifestIssueCode.POSTCONDITION_NOT_READY,
            LegalManifestIssueCode.EDITORIAL_OBSERVATION_FAILED));

    private final LegalManifestIssue issue;

    LegalEditorialOperationalException(LegalManifestIssueCode code, String location) {
        this(LegalManifestIssue.at(code, location), null);
    }

    LegalEditorialOperationalException(
            LegalManifestIssueCode code,
            String location,
            Throwable cause) {
        this(LegalManifestIssue.at(code, location), cause);
    }

    LegalEditorialOperationalException(LegalManifestIssue issue) {
        this(issue, null);
    }

    LegalEditorialOperationalException(LegalManifestIssue issue, Throwable cause) {
        super(requireError(issue).message(), cause);
        this.issue = issue;
    }

    LegalManifestIssue issue() {
        return issue;
    }

    private static LegalManifestIssue requireError(LegalManifestIssue candidate) {
        LegalManifestIssue required = Objects.requireNonNull(candidate, "issue");
        if (required.severity() != LegalManifestStatus.ERROR
                || !ALLOWED_CODES.contains(required.code())) {
            throw new IllegalArgumentException(
                    "Un fallo operativo editorial requiere un código ERROR no ambiguo");
        }
        return required;
    }
}
