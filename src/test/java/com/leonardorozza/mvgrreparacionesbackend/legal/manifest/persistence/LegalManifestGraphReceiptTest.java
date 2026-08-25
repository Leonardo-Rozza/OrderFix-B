package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegalManifestGraphReceiptTest {

    private static final Instant IMPORTED_AT = Instant.parse("2026-08-25T18:00:00.123456Z");

    @Test
    void acceptsSafeMetadataAndConsistentCounts() {
        LegalManifestGraphReceipt receipt = receipt(IMPORTED_AT, IMPORTED_AT.plusNanos(1_000), 3, 5);

        assertThat(receipt.documents()).isEqualTo(11);
        assertThat(receipt.newDocumentVersions()).isEqualTo(5);
        assertThat(receipt.reusedDocumentVersions()).isEqualTo(6);
        assertThat(receipt.requirements()).isEqualTo(6);
        assertThat(receipt.newRequirementVersions()).isEqualTo(3);
        assertThat(receipt.reusedRequirementVersions()).isEqualTo(3);
    }

    @Test
    void rejectsInvalidTimestampsAndCounts() {
        assertThatThrownBy(() -> receipt(
                IMPORTED_AT.plusSeconds(1),
                IMPORTED_AT,
                3,
                5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> receipt(
                IMPORTED_AT.plusNanos(1),
                IMPORTED_AT.plusSeconds(1),
                3,
                5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> receipt(IMPORTED_AT, IMPORTED_AT, 6, 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LegalManifestGraphReceipt(
                UUID.randomUUID(),
                IMPORTED_AT,
                IMPORTED_AT,
                11,
                6,
                8,
                3,
                5,
                5,
                2,
                3,
                3)).isInstanceOf(IllegalArgumentException.class);
    }

    private static LegalManifestGraphReceipt receipt(
            Instant importedAt,
            Instant sealedAt,
            int newDocumentLines,
            int newDocumentVersions) {
        return new LegalManifestGraphReceipt(
                UUID.randomUUID(),
                importedAt,
                sealedAt,
                11,
                6,
                8,
                newDocumentLines,
                newDocumentVersions,
                11 - newDocumentVersions,
                2,
                3,
                3);
    }
}
