package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialPlanValidator.ValidatedEditorialPlan;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidation;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalEditorialPlanV1.OperationType;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Literal confirmations binding an invocation to one validated editorial plan. */
public record LegalEditorialPlanConfirmation(UUID operationId, String editorialPlanSha256) {

    private static final Pattern LOWERCASE_SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final String ISSUE_LOCATION = "cli/editorial/plan-confirmation";

    public LegalEditorialPlanConfirmation {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(editorialPlanSha256, "editorialPlanSha256");
        if (!LOWERCASE_SHA256.matcher(editorialPlanSha256).matches()) {
            throw new IllegalArgumentException("La confirmación del plan editorial no es válida");
        }
    }

    /** Returns the same opaque token only when type, operation ID and SHA match exactly. */
    public LegalManifestValidation<ValidatedEditorialPlan> verify(
            OperationType expectedOperationType,
            ValidatedEditorialPlan editorialPlan) {
        OperationType requiredType = Objects.requireNonNull(
                expectedOperationType,
                "expectedOperationType");
        ValidatedEditorialPlan requiredPlan = Objects.requireNonNull(
                editorialPlan,
                "editorialPlan");
        if (requiredPlan.operationType() != requiredType
                || !operationId.equals(requiredPlan.operationId())
                || !editorialPlanSha256.equals(requiredPlan.editorialPlanSha256())) {
            return LegalManifestValidation.failure(LegalManifestIssue.at(
                    LegalManifestIssueCode.EDITORIAL_CONFIRMATION_MISMATCH,
                    ISSUE_LOCATION));
        }
        return LegalManifestValidation.pass(requiredPlan);
    }

    @Override
    public String toString() {
        return "LegalEditorialPlanConfirmation[redacted]";
    }
}
