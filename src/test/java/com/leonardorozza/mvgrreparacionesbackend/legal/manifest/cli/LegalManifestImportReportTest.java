package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestLimits;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidation;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestImportResult;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestImportResult.Outcome;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestImportResult.Receipt;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LegalManifestImportReportTest {

    private static final String GOLDEN_MANIFEST =
            "/legal/manifest/release-valid-v1/publication-manifest.json";
    private static final String GOLDEN_JCS_SHA256 =
            "b3b452c8f6f4deb459c31524466936f50265c165c772d50ea75364b8c9c6f3e1";
    private static final UUID PUBLICATION_UUID =
            UUID.fromString("60870649-efbb-4d2b-8407-4f1901b86a45");
    private static final Instant IMPORTED_AT =
            Instant.parse("2026-08-25T18:00:00.123456Z");
    private static final Instant SEALED_AT =
            Instant.parse("2026-08-25T18:00:01.654321Z");

    private static ValidatedRelease goldenRelease;

    @BeforeAll
    static void validateGoldenRelease() throws URISyntaxException {
        Path manifest = Path.of(Objects.requireNonNull(
                LegalManifestImportReportTest.class.getResource(GOLDEN_MANIFEST)).toURI());
        LegalManifestValidation<ValidatedRelease> validation =
                new LegalManifestValidator().validate(manifest);
        assertThat(validation.status())
                .as("issues=%s", validation.issues())
                .isEqualTo(LegalManifestStatus.PASS);
        goldenRelease = validation.value().orElseThrow();
    }

    @Test
    void confirmedImportAndReplayAreTheOnlyPassPersistedCombinations() {
        for (Outcome outcome : List.of(Outcome.IMPORTED, Outcome.ALREADY_IMPORTED)) {
            LegalManifestImportReport report = LegalManifestImportReport.forImport(
                    goldenRelease,
                    importResult(
                            LegalManifestStatus.PASS,
                            Boolean.TRUE,
                            outcome,
                            receipt(),
                            List.of()));

            assertThat(report.reportVersion()).isEqualTo(2);
            assertThat(report.command()).isEqualTo("import");
            assertThat(report.status()).isEqualTo(LegalManifestStatus.PASS);
            assertThat(report.persisted()).isTrue();
            assertThat(report.publication()).isEqualTo(
                    new LegalManifestImportReport.Publication(
                            "release-valid-v1",
                            1,
                            GOLDEN_JCS_SHA256));
            assertThat(report.counts()).isEqualTo(
                    new LegalManifestImportReport.Counts(11, 6, 8));
            assertThat(report.dryRun()).isNull();
            assertThat(report.importDetails()).isEqualTo(
                    new LegalManifestImportReport.ImportDetails(
                            outcome,
                            PUBLICATION_UUID,
                            IMPORTED_AT,
                            SEALED_AT,
                            Boolean.TRUE,
                            false));
            assertThat(report.issues()).isEmpty();
            assertThat(report.omittedIssueCount()).isZero();
        }
    }

    @Test
    void knownBlockedAndErrorOutcomesAreExplicitlyNotPersistedWithoutImportReceipt() {
        LegalManifestImportReport blocked = LegalManifestImportReport.forImport(
                goldenRelease,
                importResult(
                        LegalManifestStatus.BLOCKED,
                        Boolean.FALSE,
                        null,
                        null,
                        List.of(issue(
                                LegalManifestIssueCode.IMPORT_DB_PERSISTED_CONFLICT,
                                "database/publication"))));
        LegalManifestImportReport error =
                LegalManifestImportReport.forKnownOperationalFailure(
                        goldenRelease,
                        issue(LegalManifestIssueCode.IMPORT_DB_CONNECTION, "database"));

        assertKnownFailure(blocked, LegalManifestStatus.BLOCKED);
        assertKnownFailure(error, LegalManifestStatus.ERROR);
        assertThat(blocked.publication()).isNotNull();
        assertThat(blocked.counts()).isNotNull();
        assertThat(error.publication()).isNotNull();
        assertThat(error.counts()).isNotNull();
    }

    @Test
    void unknownIsErrorWithNullablePersistenceAndNoInventedReceiptMetadata() {
        LegalManifestImportReport report =
                LegalManifestImportReport.forUnknownOperationalFailure(goldenRelease);

        assertThat(report.status()).isEqualTo(LegalManifestStatus.ERROR);
        assertThat(report.persisted()).isNull();
        assertThat(report.publication()).isNotNull();
        assertThat(report.counts()).isNotNull();
        assertThat(report.importDetails()).isEqualTo(
                new LegalManifestImportReport.ImportDetails(
                        Outcome.UNKNOWN,
                        null,
                        null,
                        null,
                        null,
                        false));
        assertThat(report.issues())
                .singleElement()
                .satisfies(issue -> assertThat(issue.code())
                        .isEqualTo(LegalManifestIssueCode.IMPORT_DB_COMMIT_UNKNOWN));
    }

    @Test
    void failuresBeforeValidatedReleaseDoNotFabricatePublicationOrCounts() {
        LegalManifestImportReport argumentFailure =
                LegalManifestImportReport.forArgumentFailure(
                        LegalManifestValidation.failure(issue(
                                LegalManifestIssueCode.CLI_ARGUMENTS_INVALID,
                                "arguments")));
        LegalManifestImportReport staticFailure =
                LegalManifestImportReport.forStaticValidation(
                        LegalManifestValidation.failure(issue(
                                LegalManifestIssueCode.MANIFEST_SCHEMA_INVALID,
                                "publication-manifest.json")));
        LegalManifestImportReport unknown =
                LegalManifestImportReport.forUnknownOperationalFailure(null);

        assertNoReleaseMetadata(argumentFailure);
        assertNoReleaseMetadata(staticFailure);
        assertNoReleaseMetadata(unknown);
    }

    @Test
    void independentlyOrdersCapsAndAccountsForIssues() {
        List<LegalManifestIssue> candidates = new ArrayList<>();
        for (int index = LegalManifestLimits.MAX_EXPOSED_ISSUES + 1; index >= 0; index--) {
            candidates.add(issue(
                    LegalManifestIssueCode.IMPORT_DB_CONNECTION,
                    "database/value-" + index));
        }
        LegalManifestImportResult result = importResult(
                LegalManifestStatus.ERROR,
                Boolean.FALSE,
                null,
                null,
                candidates);

        LegalManifestImportReport report =
                LegalManifestImportReport.forImport(goldenRelease, result);

        assertThat(report.issues()).hasSize(LegalManifestLimits.MAX_EXPOSED_ISSUES);
        assertThat(report.omittedIssueCount()).isEqualTo(2);
        assertThat(report.issues().getFirst().location()).isEqualTo("database/value-0");
        assertThat(report.issues().getLast().location()).isEqualTo("database/value-97");
    }

    @Test
    void rejectsAnyImportResultOutsideTheFrozenMatrix() {
        assertThatThrownBy(() -> LegalManifestImportReport.forImport(
                goldenRelease,
                importResult(
                        LegalManifestStatus.PASS,
                        Boolean.FALSE,
                        Outcome.IMPORTED,
                        receipt(),
                        List.of())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LegalManifestImportReport.forImport(
                goldenRelease,
                importResult(
                        LegalManifestStatus.ERROR,
                        Boolean.FALSE,
                        null,
                        null,
                        List.of(issue(
                                LegalManifestIssueCode.IMPORT_DB_COMMIT_UNKNOWN,
                                "database/commit")))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LegalManifestImportReport.forImport(
                goldenRelease,
                importResult(
                        LegalManifestStatus.ERROR,
                        null,
                        Outcome.UNKNOWN,
                        null,
                        List.of(issue(
                                LegalManifestIssueCode.IMPORT_DB_CONNECTION,
                                "database")))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LegalManifestImportReport.forImport(
                goldenRelease,
                importResult(
                        LegalManifestStatus.ERROR,
                        null,
                        null,
                        null,
                        List.of(issue(
                                LegalManifestIssueCode.IMPORT_DB_COMMIT_UNKNOWN,
                                "database/commit")))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LegalManifestImportReport.forImport(
                goldenRelease,
                importResult(
                        LegalManifestStatus.ERROR,
                        Boolean.FALSE,
                        Outcome.UNKNOWN,
                        null,
                        List.of(issue(
                                LegalManifestIssueCode.IMPORT_DB_COMMIT_UNKNOWN,
                                "database/commit")))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LegalManifestImportReport.forKnownOperationalFailure(
                goldenRelease,
                issue(
                        LegalManifestIssueCode.IMPORT_DB_COMMIT_UNKNOWN,
                        "database/commit")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LegalManifestImportReport.forStaticValidation(
                LegalManifestValidation.pass(goldenRelease)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertKnownFailure(
            LegalManifestImportReport report,
            LegalManifestStatus expectedStatus) {
        assertThat(report.status()).isEqualTo(expectedStatus);
        assertThat(report.persisted()).isFalse();
        assertThat(report.importDetails()).isNull();
        assertThat(report.issues()).isNotEmpty();
    }

    private static void assertNoReleaseMetadata(LegalManifestImportReport report) {
        assertThat(report.publication()).isNull();
        assertThat(report.counts()).isNull();
    }

    private static LegalManifestImportResult importResult(
            LegalManifestStatus status,
            Boolean persisted,
            Outcome outcome,
            Receipt receipt,
            List<LegalManifestIssue> issues) {
        LegalManifestImportResult result = mock(LegalManifestImportResult.class);
        when(result.status()).thenReturn(status);
        when(result.persisted()).thenReturn(persisted);
        when(result.outcome()).thenReturn(Optional.ofNullable(outcome));
        when(result.receipt()).thenReturn(Optional.ofNullable(receipt));
        when(result.issues()).thenReturn(issues);
        return result;
    }

    private static Receipt receipt() {
        return new Receipt(PUBLICATION_UUID, IMPORTED_AT, SEALED_AT);
    }

    private static LegalManifestIssue issue(
            LegalManifestIssueCode code,
            String location) {
        return LegalManifestIssue.at(code, location);
    }
}
