package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidation;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestDryRunService.DryRunResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegalManifestReportTest {

    private static final String GOLDEN_MANIFEST =
            "/legal/manifest/release-valid-v1/publication-manifest.json";
    private static final String GOLDEN_JCS_SHA256 =
            "b3b452c8f6f4deb459c31524466936f50265c165c772d50ea75364b8c9c6f3e1";

    private static ValidatedRelease goldenRelease;

    @BeforeAll
    static void validateGoldenRelease() throws URISyntaxException {
        Path manifest = Path.of(Objects.requireNonNull(
                LegalManifestReportTest.class.getResource(GOLDEN_MANIFEST)).toURI());
        LegalManifestValidation<ValidatedRelease> validation =
                new LegalManifestValidator().validate(manifest);
        assertThat(validation.status())
                .as("issues=%s", validation.issues())
                .isEqualTo(LegalManifestStatus.PASS);
        goldenRelease = validation.value().orElseThrow();
    }

    @Test
    void argumentFailureHasNoTrustedCommandOrReleaseMetadataAndOrdersIssues() {
        LegalManifestValidation<Object> failure = LegalManifestValidation.failure(List.of(
                LegalManifestIssue.at(
                        LegalManifestIssueCode.MANIFEST_PATH_REQUIRED,
                        "arguments/manifest"),
                LegalManifestIssue.at(
                        LegalManifestIssueCode.MANIFEST_FILENAME_INVALID,
                        "arguments/manifest")));

        LegalManifestReport report = LegalManifestReport.forArgumentFailure(failure);

        assertThat(report.reportVersion()).isEqualTo(1);
        assertThat(report.command()).isNull();
        assertThat(report.status()).isEqualTo(LegalManifestStatus.BLOCKED);
        assertThat(report.persisted()).isFalse();
        assertThat(report.publication()).isNull();
        assertThat(report.counts()).isNull();
        assertThat(report.dryRun()).isNull();
        assertThat(report.issues())
                .extracting(LegalManifestIssue::code)
                .containsExactly(
                        LegalManifestIssueCode.MANIFEST_FILENAME_INVALID,
                        LegalManifestIssueCode.MANIFEST_PATH_REQUIRED);
        assertThat(report.omittedIssueCount()).isZero();
    }

    @Test
    void unexpectedFailureUsesAConstantErrorWithoutTrustedInputOrMetadata() {
        LegalManifestReport report = LegalManifestReport.forUnexpectedFailure();

        assertThat(report.command()).isNull();
        assertThat(report.status()).isEqualTo(LegalManifestStatus.ERROR);
        assertThat(report.persisted()).isFalse();
        assertThat(report.publication()).isNull();
        assertThat(report.counts()).isNull();
        assertThat(report.dryRun()).isNull();
        assertThat(report.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.code()).isEqualTo(LegalManifestIssueCode.CLI_OPERATION_FAILED);
            assertThat(issue.location()).isEqualTo("cli");
        });
    }

    @Test
    void successfulStaticValidationExposesOnlySafeReleaseMetadataAndCounts() {
        LegalManifestReport report = LegalManifestReport.forStaticValidation(
                "validate",
                LegalManifestValidation.pass(goldenRelease));

        assertThat(report.command()).isEqualTo("validate");
        assertThat(report.status()).isEqualTo(LegalManifestStatus.PASS);
        assertThat(report.publication()).isEqualTo(new LegalManifestReport.Publication(
                "release-valid-v1",
                1,
                GOLDEN_JCS_SHA256));
        assertThat(report.counts()).isEqualTo(new LegalManifestReport.Counts(11, 6, 8));
        assertThat(report.dryRun()).isNull();
        assertThat(report.issues()).isEmpty();
    }

    @Test
    void failedStaticValidationDoesNotFabricateMetadata() {
        LegalManifestValidation<ValidatedRelease> failure = LegalManifestValidation.failure(
                LegalManifestIssue.at(
                        LegalManifestIssueCode.MANIFEST_SCHEMA_INVALID,
                        "publication-manifest.json"));

        LegalManifestReport report =
                LegalManifestReport.forStaticValidation("dry-run", failure);

        assertThat(report.command()).isEqualTo("dry-run");
        assertThat(report.status()).isEqualTo(LegalManifestStatus.BLOCKED);
        assertThat(report.publication()).isNull();
        assertThat(report.counts()).isNull();
        assertThat(report.dryRun()).isNull();
        assertThat(report.issues())
                .extracting(LegalManifestIssue::code)
                .containsExactly(LegalManifestIssueCode.MANIFEST_SCHEMA_INVALID);
    }

    @Test
    void successfulDryRunExposesOnlyTheSixTentativeOperationCounts() {
        DryRunResult result = new DryRunResult(
                11, 6, 8,
                3, 5, 6,
                2, 4, 2);

        LegalManifestReport report = LegalManifestReport.forDryRun(
                goldenRelease,
                LegalManifestValidation.pass(result));

        assertThat(report.command()).isEqualTo("dry-run");
        assertThat(report.status()).isEqualTo(LegalManifestStatus.PASS);
        assertThat(report.publication()).isNotNull();
        assertThat(report.counts()).isEqualTo(new LegalManifestReport.Counts(11, 6, 8));
        assertThat(report.dryRun()).isEqualTo(new LegalManifestReport.DryRunCounts(
                3, 5, 6,
                2, 4, 2));
        assertThat(report.issues()).isEmpty();
    }

    @Test
    void failedDryRunRetainsAccreditedMetadataButNoTentativeCounts() {
        LegalManifestValidation<DryRunResult> failure = LegalManifestValidation.failure(
                LegalManifestIssue.at(
                        LegalManifestIssueCode.DB_CONNECTION,
                        "database/legal-manifest"));

        LegalManifestReport report = LegalManifestReport.forDryRun(goldenRelease, failure);

        assertThat(report.command()).isEqualTo("dry-run");
        assertThat(report.status()).isEqualTo(LegalManifestStatus.ERROR);
        assertThat(report.publication()).isNotNull();
        assertThat(report.counts()).isEqualTo(new LegalManifestReport.Counts(11, 6, 8));
        assertThat(report.dryRun()).isNull();
        assertThat(report.issues())
                .extracting(LegalManifestIssue::code)
                .containsExactly(LegalManifestIssueCode.DB_CONNECTION);
    }

    @Test
    void rejectsInvalidFactoryStatesInsteadOfEmittingAnAmbiguousReport() {
        assertThatThrownBy(() -> LegalManifestReport.forArgumentFailure(
                LegalManifestValidation.pass("unexpected")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LegalManifestReport.forStaticValidation(
                "unknown",
                LegalManifestValidation.pass(goldenRelease)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LegalManifestReport.forDryRun(
                goldenRelease,
                LegalManifestValidation.pass(new DryRunResult(
                        10, 6, 8,
                        10, 10, 0,
                        6, 6, 0))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
