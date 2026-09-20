package com.leonardorozza.mvgrreparacionesbackend.cuenta.recovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class RecoveryCheckpointLauncherTest {
    @TempDir Path temporaryDirectory;

    @Test void removesJvmPreMainChannelsAndPreservesEveryArgumentLiterally() throws Exception {
        Path launcher = Path.of("scripts/recovery-checkpoint.sh").toAbsolutePath().normalize();
        assertThat(launcher).isRegularFile().isExecutable();
        Path fixtures = Files.createDirectory(temporaryDirectory.resolve("paths with spaces"));
        Path jar = Files.createFile(fixtures.resolve("recovery cli.jar"));
        Path java = fixtures.resolve("java probe.sh");
        Files.writeString(java, """
                #!/bin/sh
                set -eu
                [ "${JAVA_TOOL_OPTIONS+x}" != x ] || exit 21
                [ "${JDK_JAVA_OPTIONS+x}" != x ] || exit 22
                [ "${_JAVA_OPTIONS+x}" != x ] || exit 23
                for argument do
                    printf '<%s>\\n' "$argument"
                done
                """);
        assertThat(java.toFile().setExecutable(true, true)).isTrue();
        List<String> arguments = List.of("compare", "--checkpoint=file with spaces.json", "glob-*.json",
                "$ORDENFIX_NOT_EXPANDED", "quotes-'single'-\"double\"", "");
        var command = new ArrayList<String>(); command.add(launcher.toString()); command.addAll(arguments);
        Path out = temporaryDirectory.resolve("stdout.txt"), err = temporaryDirectory.resolve("stderr.txt");
        var builder = new ProcessBuilder(command).directory(temporaryDirectory.toFile())
                .redirectOutput(out.toFile()).redirectError(err.toFile());
        builder.environment().put("ORDENFIX_RECOVERY_CLI_JAR", jar.toString());
        builder.environment().put("ORDENFIX_JAVA_BIN", java.toString());
        builder.environment().put("JAVA_TOOL_OPTIONS", "-Dprivate.marker=tool-options");
        builder.environment().put("JDK_JAVA_OPTIONS", "-Dprivate.marker=jdk-options");
        builder.environment().put("_JAVA_OPTIONS", "-Dprivate.marker=legacy-options");
        Process process = builder.start();
        try {
            assertThat(process.waitFor(5, TimeUnit.SECONDS)).as("bounded launcher probe").isTrue();
            assertThat(process.exitValue()).isZero();
            assertThat(Files.readString(err)).isEmpty();
            String expected = "<-jar>\n<" + jar + ">\n";
            for (String argument : arguments) expected += "<" + argument + ">\n";
            assertThat(Files.readString(out)).isEqualTo(expected);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
            }
        }
    }
}
