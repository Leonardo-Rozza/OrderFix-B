package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli.LegalCliProcessSupport.ProcessResult;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli.LegalCliProcessSupport.StdoutMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LegalManifestEditorLauncherTest {

    @TempDir
    private Path temporaryDirectory;

    @Test
    void invokesTheLegalCliJarWithoutPreMainJvmOptionChannels() throws Exception {
        Path launcher = Path.of(System.getProperty("user.dir"))
                .resolve("scripts/legal-manifest-editor.sh")
                .toAbsolutePath()
                .normalize();
        assertThat(launcher).isRegularFile().isExecutable();

        Path legalCliJar = Files.createFile(temporaryDirectory.resolve("legal-cli.jar"));
        Path javaProbe = temporaryDirectory.resolve("java-probe.sh");
        Files.writeString(javaProbe, """
                #!/bin/sh
                set -eu
                [ "${JAVA_TOOL_OPTIONS+x}" != x ] || exit 21
                [ "${JDK_JAVA_OPTIONS+x}" != x ] || exit 22
                [ "${_JAVA_OPTIONS+x}" != x ] || exit 23
                printf '%s\\n' "$@"
                """, StandardCharsets.UTF_8);
        assertThat(javaProbe.toFile().setExecutable(true, true)).isTrue();

        ProcessResult process = LegalCliProcessSupport.execute(
                List.of(
                        launcher.toString(),
                        "readiness",
                        "--manifest=release/publication-manifest.json",
                        "--confirm-publication-id=legal-v1",
                        "--confirm-manifest-sha256=" + "a".repeat(64)),
                temporaryDirectory,
                Map.of(
                        "ORDENFIX_LEGAL_CLI_JAR", legalCliJar.toString(),
                        "ORDENFIX_JAVA_BIN", javaProbe.toString(),
                        "JAVA_TOOL_OPTIONS", "-Dspring.datasource.url=jdbc:hostile",
                        "JDK_JAVA_OPTIONS", "-Dspring.datasource.username=hostile",
                        "_JAVA_OPTIONS", "-Dspring.datasource.password=hostile"),
                StdoutMode.CAPTURE);

        assertThat(process.exitCode()).isZero();
        assertThat(process.stderr()).isEmpty();
        assertThat(process.stdout()).isEqualTo(
                "-jar\n"
                        + legalCliJar + "\n"
                        + "readiness\n"
                        + "--manifest=release/publication-manifest.json\n"
                        + "--confirm-publication-id=legal-v1\n"
                        + "--confirm-manifest-sha256=" + "a".repeat(64) + "\n");
        assertThat(process.stdoutLimitExceeded()).isFalse();
        assertThat(process.stderrLimitExceeded()).isFalse();
    }
}
