package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidation;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestImportResult;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestImportResult.Outcome;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestImportResult.Receipt;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LegalManifestImportReportWriterTest {

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
    private static final ObjectMapper JSON = new ObjectMapper();

    private static ValidatedRelease goldenRelease;

    private final LegalManifestImportReportWriter writer =
            new LegalManifestImportReportWriter();

    @BeforeAll
    static void validateGoldenRelease() throws URISyntaxException {
        Path manifest = Path.of(Objects.requireNonNull(
                LegalManifestImportReportWriterTest.class.getResource(GOLDEN_MANIFEST)).toURI());
        LegalManifestValidation<ValidatedRelease> validation =
                new LegalManifestValidator().validate(manifest);
        assertThat(validation.status())
                .as("issues=%s", validation.issues())
                .isEqualTo(LegalManifestStatus.PASS);
        goldenRelease = validation.value().orElseThrow();
    }

    @Test
    void writesConfirmedImportInTheExactFrozenOrderWithoutClosingOrAddingNewline()
            throws IOException {
        LegalManifestImportReport report = LegalManifestImportReport.forImport(
                goldenRelease,
                importResult(
                        LegalManifestStatus.PASS,
                        Boolean.TRUE,
                        Outcome.IMPORTED,
                        new Receipt(PUBLICATION_UUID, IMPORTED_AT, SEALED_AT),
                        List.of()));
        CloseTrackingOutputStream output = new CloseTrackingOutputStream();

        writer.write(report, output);

        String json = output.toString(StandardCharsets.UTF_8);
        assertThat(json).isEqualTo("""
                {"reportVersion":2,"command":"import","status":"PASS","persisted":true,"publication":{"publicationId":"release-valid-v1","schemaVersion":1,"manifestSha256":"%s"},"counts":{"documents":11,"requirements":6,"scopes":8},"dryRun":null,"import":{"outcome":"IMPORTED","publicationUuid":"60870649-efbb-4d2b-8407-4f1901b86a45","importedAt":"2026-08-25T18:00:00.123456Z","sealedAt":"2026-08-25T18:00:01.654321Z","sealed":true,"promotionChanged":false},"issues":[],"omittedIssueCount":0}"""
                .formatted(GOLDEN_JCS_SHA256));
        assertThat(json).doesNotEndWith("\n").doesNotEndWith("\r");
        assertThat(output.closed()).isFalse();
        assertExactlyOneJsonObject(output.toByteArray());
    }

    @Test
    void writesKnownFailureWithExplicitTopLevelNullsAndNoImportObject()
            throws IOException {
        LegalManifestImportReport report =
                LegalManifestImportReport.forKnownOperationalFailure(
                        null,
                        LegalManifestIssue.at(
                                LegalManifestIssueCode.IMPORT_DB_CONNECTION,
                                "database"));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        writer.write(report, output);

        assertThat(output.toString(StandardCharsets.UTF_8)).isEqualTo("""
                {"reportVersion":2,"command":"import","status":"ERROR","persisted":false,"publication":null,"counts":null,"dryRun":null,"import":null,"issues":[{"severity":"ERROR","code":"IMPORT_DB_CONNECTION","location":"database","message":"No se pudo mantener una conexión válida durante la importación legal."}],"omittedIssueCount":0}""");
    }

    @Test
    void writesUnknownWithEveryNullableReceiptPropertyPresent()
            throws IOException {
        LegalManifestImportReport report =
                LegalManifestImportReport.forUnknownOperationalFailure(goldenRelease);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        writer.write(report, output);

        String json = output.toString(StandardCharsets.UTF_8);
        assertThat(json).isEqualTo("""
                {"reportVersion":2,"command":"import","status":"ERROR","persisted":null,"publication":{"publicationId":"release-valid-v1","schemaVersion":1,"manifestSha256":"%s"},"counts":{"documents":11,"requirements":6,"scopes":8},"dryRun":null,"import":{"outcome":"UNKNOWN","publicationUuid":null,"importedAt":null,"sealedAt":null,"sealed":null,"promotionChanged":false},"issues":[{"severity":"ERROR","code":"IMPORT_DB_COMMIT_UNKNOWN","location":"database/commit","message":"No se pudo determinar si la importación legal fue confirmada."}],"omittedIssueCount":0}"""
                .formatted(GOLDEN_JCS_SHA256));
        JsonNode parsed = JSON.readTree(json);
        assertThat(parsed.path("persisted").isNull()).isTrue();
        assertThat(parsed.path("import").has("publicationUuid")).isTrue();
        assertThat(parsed.path("import").has("importedAt")).isTrue();
        assertThat(parsed.path("import").has("sealedAt")).isTrue();
        assertThat(parsed.path("import").has("sealed")).isTrue();
    }

    @Test
    void timestampsAlwaysUseUtcZSecondsAndAtMostMicrosecondPrecision()
            throws IOException {
        LegalManifestImportReport report = LegalManifestImportReport.forImport(
                goldenRelease,
                importResult(
                        LegalManifestStatus.PASS,
                        Boolean.TRUE,
                        Outcome.ALREADY_IMPORTED,
                        new Receipt(
                                PUBLICATION_UUID,
                                Instant.parse("2026-08-25T18:00:00Z"),
                                Instant.parse("2026-08-25T18:00:01.100000Z")),
                        List.of()));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        writer.write(report, output);

        JsonNode importNode = JSON.readTree(output.toByteArray()).path("import");
        assertThat(importNode.path("importedAt").textValue())
                .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z");
        assertThat(importNode.path("sealedAt").textValue())
                .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,6})?Z");
        assertThat(importNode.path("sealedAt").textValue()).doesNotContain("+");
    }

    @Test
    void outputNeverLeaksLegalContentPathsSqlOrRuntimeDetails() throws IOException {
        LegalManifestImportReport report = LegalManifestImportReport.forImport(
                goldenRelease,
                importResult(
                        LegalManifestStatus.PASS,
                        Boolean.TRUE,
                        Outcome.IMPORTED,
                        new Receipt(PUBLICATION_UUID, IMPORTED_AT, SEALED_AT),
                        List.of()));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        writer.write(report, output);

        assertThat(output.toString(StandardCharsets.UTF_8)).doesNotContain(
                "OrdenFix Argentina SAS",
                "30-00000000-0",
                "legal@ordenfix.com",
                "# Términos",
                "/Volumes/",
                "publication-manifest.json",
                "SELECT ",
                "Exception",
                "password");
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
