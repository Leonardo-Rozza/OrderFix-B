package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli.LegalCliProcessSupport.ProcessResult;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli.LegalCliProcessSupport.StdoutMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegalCliProcessSupportTest {

    @TempDir
    private Path temporaryDirectory;

    @Test
    void preservesBinaryStreamsWithDefensiveCopiesAndStrictUtf8Views() throws Exception {
        byte[] expectedStdout = "salida-ñ\n".getBytes(StandardCharsets.UTF_8);
        byte[] expectedStderr = "error-á\n".getBytes(StandardCharsets.UTF_8);

        ProcessResult result = executeProbe("emit");

        assertThat(result.exitCode()).isZero();
        assertThat(result.timedOut()).isFalse();
        assertThat(result.wallDuration()).isPositive();
        assertThat(result.stdoutBytes()).containsExactly(expectedStdout);
        assertThat(result.stderrBytes()).containsExactly(expectedStderr);
        assertThat(result.stdout()).isEqualTo("salida-ñ\n");
        assertThat(result.stderr()).isEqualTo("error-á\n");
        assertThat(result.stdoutLimitExceeded()).isFalse();
        assertThat(result.stderrLimitExceeded()).isFalse();

        byte[] exposedStdout = result.stdoutBytes();
        byte[] exposedStderr = result.stderrBytes();
        exposedStdout[0] = 'X';
        exposedStderr[0] = 'X';
        assertThat(result.stdoutBytes()).containsExactly(expectedStdout);
        assertThat(result.stderrBytes()).containsExactly(expectedStderr);
    }

    @Test
    void preservesMalformedUtf8BytesAndRejectsOnlyTheStringView() throws Exception {
        ProcessResult result = executeProbe("invalid-utf8");

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdoutBytes()).containsExactly((byte) 0xc3, (byte) 0x28);
        assertThat(result.stderrBytes()).isEmpty();
        assertThatThrownBy(result::stdout)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("stdout")
                .hasCauseInstanceOf(CharacterCodingException.class);
    }

    @Test
    void capsAndDrainsBothStreamsIndependently() throws Exception {
        ProcessResult result = executeProbe("overflow");

        assertThat(result.exitCode()).isZero();
        assertThat(result.timedOut()).isFalse();
        assertThat(result.stdoutLimitExceeded()).isTrue();
        assertThat(result.stderrLimitExceeded()).isTrue();
        assertThat(result.stdoutBytes()).hasSize(result.captureLimitBytes());
        assertThat(result.stderrBytes()).hasSize(result.captureLimitBytes());
        assertThat(result.stdoutBytes()).containsOnly((byte) 'o');
        assertThat(result.stderrBytes()).containsOnly((byte) 'e');
    }

    @Test
    void removesInheritedImportAndEditorialDatabaseControlChannels() {
        Map<String, String> environment = new HashMap<>();
        environment.put("JAVA_TOOL_OPTIONS", "-Dspring.datasource.url=jdbc:hostile");
        environment.put("JDK_JAVA_OPTIONS", "-Dspring.datasource.username=hostile");
        environment.put("_JAVA_OPTIONS", "-Dspring.datasource.password=hostile");
        environment.put("SPRING_DATASOURCE_PASSWORD", "hostile");
        environment.put("SPRING_CONFIG_IMPORT", "file:/private/hostile.properties");
        environment.put(LegalImportEnvironment.ENABLED_VARIABLE, "true");
        environment.put("ORDENFIX_LEGAL_IMPORT_DB_PASSWORD", "hostile-import");
        environment.put(LegalEditorialEnvironment.ENABLED_VARIABLE, "true");
        environment.put("ORDENFIX_LEGAL_EDITOR_DB_URL", "jdbc:hostile-editor");
        environment.put("ORDENFIX_LEGAL_EDITOR_DB_PASSWORD", "hostile-editor");
        environment.put(
                "ORDENFIX_LEGAL_EDITOR_DB_PASSWORD_FILE",
                "/private/hostile-editor-secret");
        environment.put("ORDENFIX_UNRELATED", "preserved");
        environment.put("PATH", "/usr/bin");

        LegalCliProcessSupport.sanitizeInheritedEnvironment(environment);

        assertThat(environment).containsExactlyInAnyOrderEntriesOf(Map.of(
                "ORDENFIX_UNRELATED", "preserved",
                "PATH", "/usr/bin"));
    }

    private ProcessResult executeProbe(String mode) throws Exception {
        return LegalCliProcessSupport.execute(
                probeCommand(mode),
                temporaryDirectory,
                Map.of(),
                StdoutMode.CAPTURE);
    }

    private static List<String> probeCommand(String mode) {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(ProcessProbe.class.getName());
        command.add(mode);
        return List.copyOf(command);
    }

    public static final class ProcessProbe {

        private static final int CAPTURE_LIMIT_BYTES = 1_048_576;

        private ProcessProbe() { }

        public static void main(String[] args) throws Exception {
            switch (args[0]) {
                case "emit" -> {
                    System.out.write("salida-ñ\n".getBytes(StandardCharsets.UTF_8));
                    System.err.write("error-á\n".getBytes(StandardCharsets.UTF_8));
                }
                case "invalid-utf8" -> System.out.write(new byte[]{(byte) 0xc3, (byte) 0x28});
                case "overflow" -> {
                    byte[] stdout = new byte[CAPTURE_LIMIT_BYTES + 17];
                    byte[] stderr = new byte[CAPTURE_LIMIT_BYTES + 23];
                    Arrays.fill(stdout, (byte) 'o');
                    Arrays.fill(stderr, (byte) 'e');
                    System.out.write(stdout);
                    System.err.write(stderr);
                }
                default -> throw new IllegalArgumentException("Modo probe desconocido: " + args[0]);
            }
            System.out.flush();
            System.err.flush();
        }
    }
}
