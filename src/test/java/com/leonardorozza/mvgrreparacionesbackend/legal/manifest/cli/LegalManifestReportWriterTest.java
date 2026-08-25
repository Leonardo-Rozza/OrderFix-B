package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestLimits;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidation;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestDryRunService.DryRunResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class LegalManifestReportWriterTest {

    private static final String GOLDEN_MANIFEST =
            "/legal/manifest/release-valid-v1/publication-manifest.json";
    private static final String GOLDEN_JCS_SHA256 =
            "b3b452c8f6f4deb459c31524466936f50265c165c772d50ea75364b8c9c6f3e1";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static ValidatedRelease goldenRelease;

    private final LegalManifestReportWriter writer = new LegalManifestReportWriter();

    @BeforeAll
    static void validateGoldenRelease() throws URISyntaxException {
        Path manifest = Path.of(Objects.requireNonNull(
                LegalManifestReportWriterTest.class.getResource(GOLDEN_MANIFEST)).toURI());
        LegalManifestValidation<ValidatedRelease> validation =
                new LegalManifestValidator().validate(manifest);
        assertThat(validation.status())
                .as("issues=%s", validation.issues())
                .isEqualTo(LegalManifestStatus.PASS);
        goldenRelease = validation.value().orElseThrow();
    }

    @Test
    void writesOneCompactStaticReportWithCanonicalFieldOrderAndNoNewline()
            throws IOException {
        LegalManifestReport report = LegalManifestReport.forStaticValidation(
                "validate",
                LegalManifestValidation.pass(goldenRelease));
        CloseTrackingOutputStream output = new CloseTrackingOutputStream();

        writer.write(report, output);

        String json = output.toString(StandardCharsets.UTF_8);
        assertThat(json).isEqualTo("""
                {"reportVersion":1,"command":"validate","status":"PASS","persisted":false,"publication":{"publicationId":"release-valid-v1","schemaVersion":1,"manifestSha256":"%s"},"counts":{"documents":11,"requirements":6,"scopes":8},"dryRun":null,"issues":[],"omittedIssueCount":0}"""
                .formatted(GOLDEN_JCS_SHA256));
        assertThat(json).doesNotEndWith("\n").doesNotEndWith("\r");
        assertThat(output.closed()).isFalse();
        assertExactlyOneJsonObject(output.toByteArray());
    }

    @Test
    void writesDryRunFieldsInTheirFrozenOrder() throws IOException {
        LegalManifestReport report = LegalManifestReport.forDryRun(
                goldenRelease,
                LegalManifestValidation.pass(new DryRunResult(
                        11, 6, 8,
                        3, 5, 6,
                        2, 4, 2)));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        writer.write(report, output);

        String json = output.toString(StandardCharsets.UTF_8);
        assertThat(json).isEqualTo("""
                {"reportVersion":1,"command":"dry-run","status":"PASS","persisted":false,"publication":{"publicationId":"release-valid-v1","schemaVersion":1,"manifestSha256":"%s"},"counts":{"documents":11,"requirements":6,"scopes":8},"dryRun":{"newDocumentLines":3,"newDocumentVersions":5,"reusedDocumentVersions":6,"newRequirementLines":2,"newRequirementVersions":4,"reusedRequirementVersions":2},"issues":[],"omittedIssueCount":0}"""
                .formatted(GOLDEN_JCS_SHA256));
    }

    @Test
    void keepsTheBlockedDryRunV1BytesFrozen() throws IOException {
        LegalManifestReport report = LegalManifestReport.forDryRun(
                goldenRelease,
                LegalManifestValidation.failure(LegalManifestIssue.at(
                        LegalManifestIssueCode.DB_PERSISTED_CONFLICT,
                        "database/publication")));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        writer.write(report, output);

        assertThat(output.toString(StandardCharsets.UTF_8)).isEqualTo("""
                {"reportVersion":1,"command":"dry-run","status":"BLOCKED","persisted":false,"publication":{"publicationId":"release-valid-v1","schemaVersion":1,"manifestSha256":"%s"},"counts":{"documents":11,"requirements":6,"scopes":8},"dryRun":null,"issues":[{"severity":"BLOCKED","code":"DB_PERSISTED_CONFLICT","location":"database/publication","message":"El release entra en conflicto con una identidad legal ya persistida."}],"omittedIssueCount":0}"""
                .formatted(GOLDEN_JCS_SHA256));
    }

    @Test
    void keepsTheErrorDryRunV1BytesFrozen() throws IOException {
        LegalManifestReport report = LegalManifestReport.forDryRun(
                goldenRelease,
                LegalManifestValidation.failure(LegalManifestIssue.at(
                        LegalManifestIssueCode.DB_LOCK_TIMEOUT,
                        "database")));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        writer.write(report, output);

        assertThat(output.toString(StandardCharsets.UTF_8)).isEqualTo("""
                {"reportVersion":1,"command":"dry-run","status":"ERROR","persisted":false,"publication":{"publicationId":"release-valid-v1","schemaVersion":1,"manifestSha256":"%s"},"counts":{"documents":11,"requirements":6,"scopes":8},"dryRun":null,"issues":[{"severity":"ERROR","code":"DB_LOCK_TIMEOUT","location":"database","message":"La simulación legal agotó el tiempo de espera de un lock."}],"omittedIssueCount":0}"""
                .formatted(GOLDEN_JCS_SHA256));
    }

    @Test
    void writesOnlyCappedOrderedSafeIssuesAndTheOmittedCount() throws IOException {
        List<LegalManifestIssue> candidates = new ArrayList<>();
        for (int index = LegalManifestLimits.MAX_EXPOSED_ISSUES + 1; index >= 0; index--) {
            candidates.add(LegalManifestIssue.at(
                    LegalManifestIssueCode.RELEASE_ROOT_INVALID,
                    "arguments/value-" + index));
        }
        LegalManifestReport report = LegalManifestReport.forArgumentFailure(
                LegalManifestValidation.failure(candidates));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        writer.write(report, output);

        String json = output.toString(StandardCharsets.UTF_8);
        JsonNode parsed = JSON.readTree(json);
        assertThat(parsed.path("command").isNull()).isTrue();
        assertThat(parsed.path("publication").isNull()).isTrue();
        assertThat(parsed.path("counts").isNull()).isTrue();
        assertThat(parsed.path("dryRun").isNull()).isTrue();
        assertThat(parsed.path("issues")).hasSize(LegalManifestLimits.MAX_EXPOSED_ISSUES);
        assertThat(parsed.path("omittedIssueCount").intValue()).isEqualTo(2);
        assertThat(parsed.path("issues").get(0).path("severity").textValue())
                .isEqualTo("BLOCKED");
        assertThat(parsed.path("issues").get(0).path("code").textValue())
                .isEqualTo("RELEASE_ROOT_INVALID");
        assertThat(json).contains("raíz");
        assertThat(json).doesNotContain("Exception", "SELECT ", "stacktrace");
    }

    @Test
    void redactsLegalContentsPublisherContactsTaxIdAndFilesystemDetails() throws IOException {
        LegalManifestReport report = LegalManifestReport.forStaticValidation(
                "validate",
                LegalManifestValidation.pass(goldenRelease));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        writer.write(report, output);

        String json = output.toString(StandardCharsets.UTF_8);
        assertThat(json).doesNotContain(
                "OrdenFix Argentina SAS",
                "30-00000000-0",
                "Calle Pública 100",
                "legal@ordenfix.com",
                "privacidad@ordenfix.com",
                "soporte@ordenfix.com",
                "# Términos",
                "/Volumes/",
                "publication-manifest.json",
                "SELECT ",
                "Exception");
    }

    private static void assertExactlyOneJsonObject(byte[] bytes) throws IOException {
        try (JsonParser parser = JSON.getFactory().createParser(bytes)) {
            assertThat(parser.nextToken()).isEqualTo(JsonToken.START_OBJECT);
            parser.skipChildren();
            assertThat(parser.nextToken()).isNull();
        }
    }

    private static final class CloseTrackingOutputStream extends ByteArrayOutputStream {

        private boolean closed;

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }

        private boolean closed() {
            return closed;
        }
    }
}
