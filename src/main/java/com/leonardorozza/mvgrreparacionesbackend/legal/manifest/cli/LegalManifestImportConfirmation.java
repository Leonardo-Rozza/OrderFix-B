package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidation;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;

import java.util.Objects;
import java.util.regex.Pattern;

/** Confirmaciones literales que ligan una invocación de import al release ya validado. */
public record LegalManifestImportConfirmation(String publicationId, String manifestSha256) {

    private static final Pattern LOWERCASE_SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final String ISSUE_LOCATION = "cli/import/confirmation";

    public LegalManifestImportConfirmation {
        Objects.requireNonNull(publicationId, "publicationId");
        Objects.requireNonNull(manifestSha256, "manifestSha256");
        if (publicationId.isBlank() || !LOWERCASE_SHA256.matcher(manifestSha256).matches()) {
            throw new IllegalArgumentException("La confirmación de import no es válida");
        }
    }

    /** Compara valores exactos y devuelve el mismo token opaco ya validado. */
    public LegalManifestValidation<ValidatedRelease> verify(ValidatedRelease release) {
        ValidatedRelease required = Objects.requireNonNull(release, "release");
        if (!publicationId.equals(required.plan().manifest().publicationId())
                || !manifestSha256.equals(required.plan().manifestSha256())) {
            return LegalManifestValidation.failure(LegalManifestIssue.at(
                    LegalManifestIssueCode.IMPORT_CONFIRMATION_MISMATCH,
                    ISSUE_LOCATION));
        }
        return LegalManifestValidation.pass(required);
    }

    @Override
    public String toString() {
        return "LegalManifestImportConfirmation[redacted]";
    }
}
