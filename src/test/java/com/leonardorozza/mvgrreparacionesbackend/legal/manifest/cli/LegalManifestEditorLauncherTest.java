package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli.LegalCliProcessSupport.ProcessResult;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli.LegalCliProcessSupport.StdoutMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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

        Path fixtures = Files.createDirectory(
                temporaryDirectory.resolve("launcher fixtures with spaces"));
        Files.createFile(temporaryDirectory.resolve("glob-match.json"));
        Path legalCliJar = Files.createFile(fixtures.resolve("legal cli.jar"));
        Path javaProbe = fixtures.resolve("java probe.sh");
        Files.writeString(javaProbe, """
                #!/bin/sh
                set -eu
                [ "${JAVA_TOOL_OPTIONS+x}" != x ] || exit 21
                [ "${JDK_JAVA_OPTIONS+x}" != x ] || exit 22
                [ "${_JAVA_OPTIONS+x}" != x ] || exit 23
                for argument do
                    printf '<%s>\\n' "$argument"
                done
                """, StandardCharsets.UTF_8);
        assertThat(javaProbe.toFile().setExecutable(true, true)).isTrue();

        List<String> forwardedArguments = List.of(
                "readiness",
                "--manifest=release with spaces/manifest.json",
                "glob-*.json",
                "$ORDENFIX_DO_NOT_EXPAND",
                "quotes-'single'-\"double\"",
                "--flag=value with spaces",
                "");
        ProcessResult process = LegalCliProcessSupport.execute(
                launcherCommand(launcher, forwardedArguments),
                temporaryDirectory,
                Map.of(
                        "ORDENFIX_LEGAL_CLI_JAR", legalCliJar.toString(),
                        "ORDENFIX_JAVA_BIN", javaProbe.toString(),
                        "JAVA_TOOL_OPTIONS", "-Dspring.datasource.url=jdbc:hostile",
                        "JDK_JAVA_OPTIONS", "-Dspring.datasource.username=hostile",
                        "_JAVA_OPTIONS", "-Dspring.datasource.password=hostile"),
                StdoutMode.CAPTURE);

        assertThat(process.exitCode()).isZero();
        assertThat(process.timedOut()).isFalse();
        assertThat(process.stderr()).isEmpty();
        assertThat(process.stdout()).isEqualTo(
                "<-jar>\n"
                        + "<" + legalCliJar + ">\n"
                        + "<readiness>\n"
                        + "<--manifest=release with spaces/manifest.json>\n"
                        + "<glob-*.json>\n"
                        + "<$ORDENFIX_DO_NOT_EXPAND>\n"
                        + "<quotes-'single'-\"double\">\n"
                        + "<--flag=value with spaces>\n"
                        + "<>\n");
        assertThat(process.stdoutLimitExceeded()).isFalse();
        assertThat(process.stderrLimitExceeded()).isFalse();
    }

    private static List<String> launcherCommand(
            Path launcher,
            List<String> arguments) {
        var command = new ArrayList<String>();
        command.add(launcher.toString());
        command.addAll(arguments);
        return List.copyOf(command);
    }
}
