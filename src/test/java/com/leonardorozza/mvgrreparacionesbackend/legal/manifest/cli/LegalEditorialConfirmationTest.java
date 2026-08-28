package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidation;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegalEditorialConfirmationTest {

    private static final String GOLDEN_MANIFEST =
            "/legal/manifest/release-valid-v1/publication-manifest.json";
    private static final String GOLDEN_SHA256 =
            "b3b452c8f6f4deb459c31524466936f50265c165c772d50ea75364b8c9c6f3e1";
    private static ValidatedRelease goldenRelease;

    @BeforeAll
    static void validateGoldenRelease() throws URISyntaxException {
        Path manifest = Path.of(Objects.requireNonNull(
                LegalEditorialConfirmationTest.class.getResource(GOLDEN_MANIFEST)).toURI());
        LegalManifestValidation<ValidatedRelease> validation =
                new LegalManifestValidator().validate(manifest);
        assertThat(validation.status())
                .as("issues=%s", validation.issues())
                .isEqualTo(LegalManifestStatus.PASS);
        goldenRelease = validation.value().orElseThrow();
    }

    @Test
    void returnsTheSameOpaqueReleaseWhenBothLiteralConfirmationsMatch() {
        LegalEditorialConfirmation confirmation = new LegalEditorialConfirmation(
                "release-valid-v1",
                GOLDEN_SHA256);

        LegalManifestValidation<ValidatedRelease> result = confirmation.verify(goldenRelease);

        assertThat(result.status()).isEqualTo(LegalManifestStatus.PASS);
        assertThat(result.value().orElseThrow()).isSameAs(goldenRelease);
        assertThat(result.issues()).isEmpty();
    }

    @Test
    void blocksIdAndShaMismatchesWithoutEchoingCandidates() {
        assertMismatch(
                new LegalEditorialConfirmation("private-wrong-id", GOLDEN_SHA256),
                "private-wrong-id");
        String wrongSha = "0".repeat(64);
        assertMismatch(
                new LegalEditorialConfirmation("release-valid-v1", wrongSha),
                wrongSha);
    }

    @Test
    void constructorRejectsBlankIdsAndNonCanonicalHashes() {
        assertThatThrownBy(() -> new LegalEditorialConfirmation(" ", GOLDEN_SHA256))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LegalEditorialConfirmation(
                "release-valid-v1",
                GOLDEN_SHA256.toUpperCase()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void diagnosticStringRedactsBothConfirmationValues() {
        LegalEditorialConfirmation confirmation = new LegalEditorialConfirmation(
                "private-publication",
                GOLDEN_SHA256);

        assertThat(confirmation.toString())
                .contains("redacted")
                .doesNotContain("private-publication", GOLDEN_SHA256);
    }

    private static void assertMismatch(
            LegalEditorialConfirmation confirmation,
            String privateValue) {
        LegalManifestValidation<ValidatedRelease> result = confirmation.verify(goldenRelease);

        assertThat(result.status()).isEqualTo(LegalManifestStatus.BLOCKED);
        assertThat(result.value()).isEmpty();
        assertThat(result.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.code())
                    .isEqualTo(LegalManifestIssueCode.EDITORIAL_CONFIRMATION_MISMATCH);
            assertThat(issue.location()).isEqualTo("cli/editorial/confirmation");
            assertThat(issue.message()).doesNotContain(privateValue);
            assertThat(issue.toString()).doesNotContain(privateValue);
        });
    }
}
