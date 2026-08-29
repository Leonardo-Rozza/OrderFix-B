package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialPlanValidator.ValidatedEditorialPlan;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidation;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalEditorialPlanV1.DocumentReplacementBatch;

import java.util.List;
import java.util.Objects;

/** Pure capability guard for the one-to-one REPLACE cutover supported by Corte 6. */
public final class LegalEditorialReplaceScopeGuard {

    private static final String MAPPING_LOCATION = "documentReplacementBatches";

    public LegalManifestValidation<ValidatedEditorialPlan> validate(
            ValidatedEditorialPlan validatedPlan) {
        ValidatedEditorialPlan requiredPlan = Objects.requireNonNull(
                validatedPlan,
                "validatedPlan");
        List<DocumentReplacementBatch> batches = requiredPlan.plan()
                .documentReplacementBatches();

        if (batches.size() > 1 || hasUnsupportedCardinality(batches)) {
            return LegalManifestValidation.failure(LegalManifestIssue.at(
                    LegalManifestIssueCode.REPLACEMENT_MAPPING_INVALID,
                    MAPPING_LOCATION));
        }

        return LegalManifestValidation.pass(requiredPlan);
    }

    private static boolean hasUnsupportedCardinality(List<DocumentReplacementBatch> batches) {
        if (batches.isEmpty()) {
            return false;
        }
        DocumentReplacementBatch batch = batches.getFirst();
        return batch.predecessors().size() != 1 || batch.successors().size() != 1;
    }
}
