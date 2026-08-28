package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** Expected incompatibility between an accredited release and observed editorial state. */
final class LegalEditorialBlockedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private static final Set<LegalManifestIssueCode> ALLOWED_CODES = Set.copyOf(EnumSet.of(
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

    private final LegalManifestIssue issue;

    LegalEditorialBlockedException(LegalManifestIssueCode code, String location) {
        this(LegalManifestIssue.at(code, location), null);
    }

    LegalEditorialBlockedException(
            LegalManifestIssueCode code,
            String location,
            Throwable cause) {
        this(LegalManifestIssue.at(code, location), cause);
    }

    LegalEditorialBlockedException(LegalManifestIssue issue) {
        this(issue, null);
    }

    LegalEditorialBlockedException(LegalManifestIssue issue, Throwable cause) {
        super(requireBlocked(issue).message(), cause);
        this.issue = issue;
    }

    LegalManifestIssue issue() {
        return issue;
    }

    private static LegalManifestIssue requireBlocked(LegalManifestIssue candidate) {
        LegalManifestIssue required = Objects.requireNonNull(candidate, "issue");
        if (required.severity() != LegalManifestStatus.BLOCKED
                || !ALLOWED_CODES.contains(required.code())) {
            throw new IllegalArgumentException(
                    "Un bloqueo editorial requiere un código editorial BLOCKED");
        }
        return required;
    }
}
