package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialReadiness;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegalEditorialApplyReceiptTest {

    private static final UUID PUBLICATION_UUID = new UUID(0, 1);
    private static final Instant APPLIED_AT =
            Instant.parse("2026-08-28T20:15:30.123456Z");

    @Test
    void freezesTheSafeDatabaseEvidenceShape() {
        assertThat(LegalEditorialApplyReceipt.class.isRecord()).isTrue();
        RecordComponent[] components = LegalEditorialApplyReceipt.class.getRecordComponents();

        assertThat(Arrays.stream(components).map(RecordComponent::getName))
                .containsExactly(
                        "operationType",
                        "targetPublicationUuid",
                        "appliedAt",
                        "readinessAfter",
                        "documentVersions",
                        "requirementVersions",
                        "documentTransitions",
                        "requirementTransitions",
                        "documentSlots",
                        "requiredSetPointers",
                        "replacementBatches");
        assertThat(LegalEditorialApplyReceipt.OperationType.values())
                .containsExactly(
                        LegalEditorialApplyReceipt.OperationType.PROMOTE,
                        LegalEditorialApplyReceipt.OperationType.REPLACE,
                        LegalEditorialApplyReceipt.OperationType.RETIRE);
    }

    @Test
    void acceptsReadyPromoteAndReplaceAndFailClosedRetire() {
        LegalEditorialApplyReceipt promote = receipt(
                LegalEditorialApplyReceipt.OperationType.PROMOTE,
                LegalEditorialReadiness.READY);
        LegalEditorialApplyReceipt replace = receipt(
                LegalEditorialApplyReceipt.OperationType.REPLACE,
                LegalEditorialReadiness.READY);
        LegalEditorialApplyReceipt retire = receipt(
                LegalEditorialApplyReceipt.OperationType.RETIRE,
                LegalEditorialReadiness.NOT_READY);

        assertThat(promote.targetPublicationUuid()).isEqualTo(PUBLICATION_UUID);
        assertThat(promote.appliedAt()).isEqualTo(APPLIED_AT);
        assertThat(replace.readinessAfter()).isEqualTo(LegalEditorialReadiness.READY);
        assertThat(retire.readinessAfter()).isEqualTo(LegalEditorialReadiness.NOT_READY);
    }

    @Test
    void rejectsNullsInvalidReadinessAndSubMicrosecondTimestamps() {
        assertThatThrownBy(() -> new LegalEditorialApplyReceipt(
                null, PUBLICATION_UUID, APPLIED_AT, LegalEditorialReadiness.READY,
                1, 1, 2, 2, 1, 1, 0))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LegalEditorialApplyReceipt(
                LegalEditorialApplyReceipt.OperationType.PROMOTE,
                null,
                APPLIED_AT,
                LegalEditorialReadiness.READY,
                1, 1, 2, 2, 1, 1, 0))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LegalEditorialApplyReceipt(
                LegalEditorialApplyReceipt.OperationType.PROMOTE,
                PUBLICATION_UUID,
                null,
                LegalEditorialReadiness.READY,
                1, 1, 2, 2, 1, 1, 0))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> receipt(
                LegalEditorialApplyReceipt.OperationType.PROMOTE,
                LegalEditorialReadiness.NOT_READY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> receipt(
                LegalEditorialApplyReceipt.OperationType.REPLACE,
                LegalEditorialReadiness.ERROR))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> receipt(
                LegalEditorialApplyReceipt.OperationType.RETIRE,
                LegalEditorialReadiness.READY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LegalEditorialApplyReceipt(
                LegalEditorialApplyReceipt.OperationType.PROMOTE,
                PUBLICATION_UUID,
                APPLIED_AT.plusNanos(1),
                LegalEditorialReadiness.READY,
                1, 1, 2, 2, 1, 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsEveryNegativePostStateCount() {
        for (int negativeIndex = 0; negativeIndex < 7; negativeIndex++) {
            int[] counts = {1, 1, 2, 2, 1, 1, 0};
            counts[negativeIndex] = -1;
            assertThatThrownBy(() -> new LegalEditorialApplyReceipt(
                    LegalEditorialApplyReceipt.OperationType.PROMOTE,
                    PUBLICATION_UUID,
                    APPLIED_AT,
                    LegalEditorialReadiness.READY,
                    counts[0], counts[1], counts[2], counts[3], counts[4], counts[5], counts[6]))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static LegalEditorialApplyReceipt receipt(
            LegalEditorialApplyReceipt.OperationType operationType,
            LegalEditorialReadiness readiness) {
        return new LegalEditorialApplyReceipt(
                operationType,
                PUBLICATION_UUID,
                APPLIED_AT,
                readiness,
                3,
                4,
                6,
                8,
                3,
                2,
                1);
    }
}
