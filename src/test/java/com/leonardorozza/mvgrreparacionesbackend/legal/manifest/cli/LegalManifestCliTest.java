package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LegalManifestCliTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final LegalManifestCli cli = new LegalManifestCli();

    @Test
    void commandBoundaryTerminatesItsSingleCompactJsonReportWithOneLf() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exitCode = cli.runSafely(new String[0], output);

        assertThat(exitCode).isEqualTo(2);
        String stdout = output.toString(StandardCharsets.UTF_8);
        assertThat(stdout).endsWith("\n").doesNotEndWith("\n\n").doesNotContain("\r");
        String jsonDocument = stdout.substring(0, stdout.length() - 1);
        assertThat(jsonDocument).doesNotContain("\n");
        JsonNode report;
        try (JsonParser parser = JSON.getFactory().createParser(jsonDocument)) {
            assertThat(parser.nextToken()).isEqualTo(JsonToken.START_OBJECT);
            report = JSON.readTree(parser);
            assertThat(parser.nextToken()).isNull();
        }
        assertThat(report.path("command").isNull()).isTrue();
        assertThat(report.path("status").textValue()).isEqualTo("BLOCKED");
        assertThat(report.path("persisted").booleanValue()).isFalse();
        assertThat(report.path("issues").get(0).path("code").textValue())
                .isEqualTo("CLI_ARGUMENTS_INVALID");
    }

    @Test
    void brokenStandardOutputReturnsTheStableOperationalExitCodeWithoutThrowing() {
        OutputStream brokenOutput = new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                throw new IOException("private output failure");
            }
        };

        int exitCode = cli.runSafely(new String[0], brokenOutput);

        assertThat(exitCode).isEqualTo(3);
    }

    @Test
    void printStreamThatSwallowsItsWriteFailureStillReturnsTheOperationalExitCode() {
        OutputStream brokenOutput = new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                throw new IOException("private output failure");
            }
        };
        PrintStream swallowingOutput = new PrintStream(brokenOutput, true, StandardCharsets.UTF_8);

        int exitCode = cli.runSafely(new String[0], swallowingOutput);

        assertThat(exitCode).isEqualTo(3);
        assertThat(swallowingOutput.checkError()).isTrue();
    }

    @Test
    void unexpectedInternalFailureBecomesAConstantSafeEnvelope() throws Exception {
        LegalManifestValidator failingValidator = mock(LegalManifestValidator.class);
        when(failingValidator.validate(any(Path.class)))
                .thenThrow(new IllegalStateException("private-path-and-secret"));
        LegalManifestCli failingCli = new LegalManifestCli(
                failingValidator,
                new LegalManifestReportWriter());
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exitCode = failingCli.runSafely(
                new String[]{"validate", "--manifest=publication-manifest.json"},
                output);

        assertThat(exitCode).isEqualTo(3);
        String stdout = output.toString(StandardCharsets.UTF_8);
        assertThat(stdout).endsWith("\n").doesNotContain("private-path-and-secret");
        JsonNode report = JSON.readTree(stdout);
        assertThat(report.path("command").isNull()).isTrue();
        assertThat(report.path("status").textValue()).isEqualTo("ERROR");
        assertThat(report.path("issues")).singleElement().satisfies(issue ->
                assertThat(issue.path("code").textValue()).isEqualTo("CLI_OPERATION_FAILED"));
    }

    @Test
    void constructorFailureAtMainBoundaryUsesThePreSerializedSafeEnvelope() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exitCode = LegalManifestCli.runMain(
                new String[]{"validate", "--manifest=publication-manifest.json"},
                output,
                () -> {
                    throw new ExceptionInInitializerError("private-constructor-secret");
                });
        ByteArrayOutputStream expectedOutput = new ByteArrayOutputStream();
        new LegalManifestReportWriter().write(
                LegalManifestReport.forUnexpectedFailure(),
                expectedOutput);
        expectedOutput.write('\n');

        assertThat(exitCode).isEqualTo(3);
        String stdout = output.toString(StandardCharsets.UTF_8);
        assertThat(stdout)
                .isEqualTo(expectedOutput.toString(StandardCharsets.UTF_8))
                .endsWith("\n")
                .doesNotEndWith("\n\n")
                .doesNotContain("private-constructor-secret");
        JsonNode report = JSON.readTree(stdout);
        assertThat(report.path("command").isNull()).isTrue();
        assertThat(report.path("status").textValue()).isEqualTo("ERROR");
        assertThat(report.path("issues")).singleElement().satisfies(issue ->
                assertThat(issue.path("code").textValue()).isEqualTo("CLI_OPERATION_FAILED"));
    }
}
