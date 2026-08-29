package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialPlanValidator.ValidatedEditorialPlan;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidation;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalEditorialPlanV1;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalEditorialPlanV1.DocumentRef;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalEditorialPlanV1.DocumentReplacementBatch;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LegalEditorialReplaceScopeGuardTest {

    private static final String MAPPING_LOCATION = "documentReplacementBatches";

    private final LegalEditorialReplaceScopeGuard guard =
            new LegalEditorialReplaceScopeGuard();

    @Test
    void acceptsAPlanWithoutDocumentReplacementBatches() {
        ValidatedEditorialPlan plan = planWithBatches(List.of());

        LegalManifestValidation<ValidatedEditorialPlan> result = guard.validate(plan);

        assertPassedWithSamePlan(result, plan);
    }

    @Test
    void acceptsOneOneToOneDocumentReplacementBatch() {
        ValidatedEditorialPlan plan = planWithBatches(List.of(batchWithCardinality(1, 1)));

        LegalManifestValidation<ValidatedEditorialPlan> result = guard.validate(plan);

        assertPassedWithSamePlan(result, plan);
    }

    @Test
    void blocksMoreThanOneDocumentReplacementBatch() {
        ValidatedEditorialPlan plan = planWithBatches(List.of(
                batchWithCardinality(1, 1),
                batchWithCardinality(1, 1)));

        LegalManifestValidation<ValidatedEditorialPlan> result = guard.validate(plan);

        assertBlockedMapping(result);
    }

    @Test
    void blocksOneToManyDocumentReplacementBatch() {
        ValidatedEditorialPlan plan = planWithBatches(List.of(batchWithCardinality(1, 2)));

        LegalManifestValidation<ValidatedEditorialPlan> result = guard.validate(plan);

        assertBlockedMapping(result);
    }

    @Test
    void blocksManyToOneDocumentReplacementBatch() {
        ValidatedEditorialPlan plan = planWithBatches(List.of(batchWithCardinality(2, 1)));

        LegalManifestValidation<ValidatedEditorialPlan> result = guard.validate(plan);

        assertBlockedMapping(result);
    }

    private static ValidatedEditorialPlan planWithBatches(
            List<DocumentReplacementBatch> batches) {
        ValidatedEditorialPlan validatedPlan = mock(ValidatedEditorialPlan.class);
        LegalEditorialPlanV1 plan = mock(LegalEditorialPlanV1.class);
        when(validatedPlan.plan()).thenReturn(plan);
        when(plan.documentReplacementBatches()).thenReturn(batches);
        return validatedPlan;
    }

    private static DocumentReplacementBatch batchWithCardinality(
            int predecessorCount,
            int successorCount) {
        DocumentReplacementBatch batch = mock(DocumentReplacementBatch.class);
        when(batch.predecessors()).thenReturn(documentRefs(predecessorCount));
        when(batch.successors()).thenReturn(documentRefs(successorCount));
        return batch;
    }

    private static List<DocumentRef> documentRefs(int count) {
        return Collections.nCopies(count, mock(DocumentRef.class));
    }

    private static void assertPassedWithSamePlan(
            LegalManifestValidation<ValidatedEditorialPlan> result,
            ValidatedEditorialPlan plan) {
        assertThat(result.status()).isEqualTo(LegalManifestStatus.PASS);
        assertThat(result.value().orElseThrow()).isSameAs(plan);
        assertThat(result.issues()).isEmpty();
    }

    private static void assertBlockedMapping(
            LegalManifestValidation<ValidatedEditorialPlan> result) {
        assertThat(result.status()).isEqualTo(LegalManifestStatus.BLOCKED);
        assertThat(result.value()).isEmpty();
        assertThat(result.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.code())
                    .isEqualTo(LegalManifestIssueCode.REPLACEMENT_MAPPING_INVALID);
            assertThat(issue.location()).isEqualTo(MAPPING_LOCATION);
        });
    }
}
