package com.leonardorozza.mvgrreparacionesbackend.cuenta.recovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class RecoveryCheckpointCliTest {
    @TempDir Path temporary;
    @Test void invalidArgumentsNeverStartTheApplicationOrEchoValues() {
        check(new String[]{"bad-secret-command", "secret-path"}, Map.of());
        check(new String[0], Map.of("SPRING_CONFIG_IMPORT", "secret-location"));
    }
    @Test void malformedEvidenceIsRejectedBeforeConnectingToDatabase() throws Exception {
        Path key = temporary.resolve("key");
        Files.write(key, new byte[32]);
        Files.setPosixFilePermissions(key, PosixFilePermissions.fromString("rw-------"));
        Path file = temporary.resolve("checkpoint");
        Files.writeString(file, "fake-secret-checkpoint");
        var environment = new HashMap<String, String>();
        environment.put("ORDENFIX_RECOVERY_ENVIRONMENT_ID", UUID.randomUUID().toString());
        environment.put("ORDENFIX_RECOVERY_CHECKPOINT_ID", UUID.randomUUID().toString());
        environment.put("ORDENFIX_RECOVERY_KEY_FILE", key.toString());
        environment.put("ORDENFIX_RECOVERY_EXPECTED_SHA256", "a".repeat(64));
        // Deliberately no datasource configuration: rejection must already be possible at evidence verification.
        check(new String[]{"compare", file.toString()}, environment);
    }
    @Test void lossOfTheReportCannotProduceASuccessOrDifferenceExitCode() {
        var broken = new PrintStream(new java.io.OutputStream() {
            @Override public void write(int value) throws java.io.IOException { throw new java.io.IOException("private sink failure"); }
        });
        broken.println("CAPTURED synthetic-receipt");
        assertThat(RecoveryCheckpointCli.finishReport(broken, 0)).isEqualTo(1);
        assertThat(RecoveryCheckpointCli.finishReport(broken, 2)).isEqualTo(1);
        var working = new PrintStream(new ByteArrayOutputStream());
        assertThat(RecoveryCheckpointCli.finishReport(working, 0)).isZero();
        assertThat(RecoveryCheckpointCli.finishReport(working, 2)).isEqualTo(2);
    }

    private void check(String[] args, Map<String, String> environment) {
        var bytes = new ByteArrayOutputStream();
        assertThat(RecoveryCheckpointCli.run(args, environment, new PrintStream(bytes))).isEqualTo(1);
        assertThat(bytes.toString()).isEqualTo("RECOVERY_CHECK_FAILED NO_AUTORIZA_REAPERTURA\n");
    }
}
