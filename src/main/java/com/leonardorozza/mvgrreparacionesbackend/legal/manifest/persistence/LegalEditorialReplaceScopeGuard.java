package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialPlanValidator.ValidatedEditorialPlan;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidation;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalEditorialPlanV1.DocumentReplacementBatch;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalEditorialPlanV1.OperationType;

import java.util.List;
import java.util.Objects;

/**
 * Pure capability guard for REPLACE plans composed of one-to-one, split, merge,
 * or multiple supported replacement batches.
 */
public final class LegalEditorialReplaceScopeGuard {

    private static final String MAPPING_LOCATION = "documentReplacementBatches";

    public LegalManifestValidation<ValidatedEditorialPlan> validate(
            ValidatedEditorialPlan validatedPlan) {
        ValidatedEditorialPlan requiredPlan = Objects.requireNonNull(
                validatedPlan,
                "validatedPlan");
        List<DocumentReplacementBatch> batches = requiredPlan.plan()
                .documentReplacementBatches();

        if (requiredPlan.operationType() != OperationType.REPLACE
                || hasUnsupportedCardinality(batches)) {
            return LegalManifestValidation.failure(LegalManifestIssue.at(
                    LegalManifestIssueCode.REPLACEMENT_MAPPING_INVALID,
                    MAPPING_LOCATION));
        }

        return LegalManifestValidation.pass(requiredPlan);
    }

    private static boolean hasUnsupportedCardinality(List<DocumentReplacementBatch> batches) {
        return batches.stream().anyMatch(batch -> {
            int predecessorCount = batch.predecessors().size();
            int successorCount = batch.successors().size();
            return predecessorCount == 0
                    || successorCount == 0
                    || (predecessorCount != 1 && successorCount != 1);
        });
    }
}
