package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegalManifestImportResultTest {

    private static final Instant IMPORTED_AT =
            Instant.parse("2026-08-25T18:00:00.123456Z");
    private static final Instant SEALED_AT =
            Instant.parse("2026-08-25T18:00:01.654321Z");

    @Test
    void confirmedOutcomesArePassPersistedAndCarryOnlyTheSafeReceipt() {
        UUID publicationId = UUID.randomUUID();
        LegalManifestGraphReceipt graphReceipt = graphReceipt(publicationId);

        LegalManifestImportResult imported =
                LegalManifestImportResult.imported(graphReceipt);
        LegalManifestImportResult replay =
                LegalManifestImportResult.alreadyImported(graphReceipt);

        assertConfirmed(imported, LegalManifestImportResult.Outcome.IMPORTED, publicationId);
        assertConfirmed(
                replay,
                LegalManifestImportResult.Outcome.ALREADY_IMPORTED,
                publicationId);
    }

    @Test
    void unknownIsTheOnlyIndeterminateCombinationAndDoesNotExposeAReceipt() {
        LegalManifestImportResult result = LegalManifestImportResult.unknown();

        assertThat(result.status()).isEqualTo(LegalManifestStatus.ERROR);
        assertThat(result.persisted()).isNull();
        assertThat(result.outcome()).contains(LegalManifestImportResult.Outcome.UNKNOWN);
        assertThat(result.receipt()).isEmpty();
        assertThat(result.issues())
                .singleElement()
                .satisfies(issue -> {
                    assertThat(issue.code())
                            .isEqualTo(LegalManifestIssueCode.IMPORT_DB_COMMIT_UNKNOWN);
                    assertThat(issue.location()).isEqualTo("database/commit");
                });
    }

    @Test
    void knownBlockedAndOperationalFailuresAreNotPersistedAndExposeNoOutcome() {
        LegalManifestImportResult blocked = LegalManifestImportResult.failure(
                LegalManifestIssue.at(
                        LegalManifestIssueCode.IMPORT_DB_PERSISTED_CONFLICT,
                        "database/publication"));
        LegalManifestImportResult error = LegalManifestImportResult.failure(
                LegalManifestIssue.at(
                        LegalManifestIssueCode.IMPORT_DB_CONNECTION,
                        "database"));

        assertKnownFailure(blocked, LegalManifestStatus.BLOCKED);
        assertKnownFailure(error, LegalManifestStatus.ERROR);
    }

    @Test
    void knownFailureRejectsV1AndCommitUnknownIssues() {
        assertThatThrownBy(() -> LegalManifestImportResult.failure(
                LegalManifestIssue.at(
                        LegalManifestIssueCode.DB_CONNECTION,
                        "database")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LegalManifestImportResult.failure(
                LegalManifestIssue.at(
                        LegalManifestIssueCode.IMPORT_DB_COMMIT_UNKNOWN,
                        "database/commit")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void publicReceiptRejectsInventedTimestampPrecisionOrOrdering() {
        UUID publicationId = UUID.randomUUID();

        assertThatThrownBy(() -> new LegalManifestImportResult.Receipt(
                publicationId,
                Instant.parse("2026-08-25T18:00:00.123456789Z"),
                SEALED_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LegalManifestImportResult.Receipt(
                publicationId,
                SEALED_AT,
                IMPORTED_AT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertConfirmed(
            LegalManifestImportResult result,
            LegalManifestImportResult.Outcome expectedOutcome,
            UUID expectedPublicationId) {
        assertThat(result.status()).isEqualTo(LegalManifestStatus.PASS);
        assertThat(result.persisted()).isTrue();
        assertThat(result.outcome()).contains(expectedOutcome);
        assertThat(result.issues()).isEmpty();
        assertThat(result.receipt())
                .contains(new LegalManifestImportResult.Receipt(
                        expectedPublicationId,
                        IMPORTED_AT,
                        SEALED_AT));
    }

    private static void assertKnownFailure(
            LegalManifestImportResult result,
            LegalManifestStatus expectedStatus) {
        assertThat(result.status()).isEqualTo(expectedStatus);
        assertThat(result.persisted()).isFalse();
        assertThat(result.outcome()).isEmpty();
        assertThat(result.receipt()).isEmpty();
        assertThat(result.issues()).hasSize(1);
    }

    private static LegalManifestGraphReceipt graphReceipt(UUID publicationId) {
        return new LegalManifestGraphReceipt(
                publicationId,
                IMPORTED_AT,
                SEALED_AT,
                11,
                6,
                8,
                3,
                5,
                6,
                2,
                3,
                3);
    }
}
