package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidation;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;

import java.util.Objects;
import java.util.regex.Pattern;

/** Literal confirmations binding an editorial invocation to one validated release. */
public record LegalEditorialConfirmation(String publicationId, String manifestSha256) {

    private static final Pattern LOWERCASE_SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final String ISSUE_LOCATION = "cli/editorial/confirmation";

    public LegalEditorialConfirmation {
        Objects.requireNonNull(publicationId, "publicationId");
        Objects.requireNonNull(manifestSha256, "manifestSha256");
        if (publicationId.isBlank() || !LOWERCASE_SHA256.matcher(manifestSha256).matches()) {
            throw new IllegalArgumentException("La confirmación editorial no es válida");
        }
    }

    /** Returns the same opaque token only when both literal confirmations match exactly. */
    public LegalManifestValidation<ValidatedRelease> verify(ValidatedRelease release) {
        ValidatedRelease required = Objects.requireNonNull(release, "release");
        if (!publicationId.equals(required.plan().manifest().publicationId())
                || !manifestSha256.equals(required.plan().manifestSha256())) {
            return LegalManifestValidation.failure(LegalManifestIssue.at(
                    LegalManifestIssueCode.EDITORIAL_CONFIRMATION_MISMATCH,
                    ISSUE_LOCATION));
        }
        return LegalManifestValidation.pass(required);
    }

    @Override
    public String toString() {
        return "LegalEditorialConfirmation[redacted]";
    }
}
