package com.leonardorozza.mvgrreparacionesbackend.cuenta.recovery;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Offline maintenance launcher. Never starts SpringApplication, Flyway, HTTP, workers or providers. */
public final class RecoveryCheckpointCli {
    private RecoveryCheckpointCli() { }

    public static void main(String[] args) {
        PrintStream report = System.out;
        // No database exception, logger, JDBC URL, credential or path reaches the console.
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        System.setErr(new PrintStream(OutputStream.nullOutputStream()));
        System.exit(run(args, System.getenv(), report));
    }

    static int run(String[] args, Map<String, String> environment, PrintStream output) {
        byte[] key = null;
        try {
            if (args.length != 2 || !("capture".equals(args[0]) || "compare".equals(args[0]))) throw invalid();
            Path file = Path.of(args[1]);
            UUID environmentId = UUID.fromString(required(environment, "ENVIRONMENT_ID"));
            UUID checkpointId = UUID.fromString(required(environment, "CHECKPOINT_ID"));
            key = key(Path.of(required(environment, "KEY_FILE")));
            RecoveryCheckpointFiles files = new RecoveryCheckpointFiles();
            RecoveryCheckpoint.Snapshot expected = null;
            if ("compare".equals(args[0])) {
                // Authenticate evidence and independently pinned receipt before even constructing a datasource.
                expected = files.read(file, key, new RecoveryCheckpointFiles.Receipt(environmentId, checkpointId,
                        required(environment, "EXPECTED_SHA256")));
            } else if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) throw invalid();

            String url = required(environment, "JDBC_URL");
            if (!url.startsWith("jdbc:postgresql://")) throw invalid();
            var dataSource = new DriverManagerDataSource(url, required(environment, "JDBC_USERNAME"),
                    required(environment, "JDBC_PASSWORD"));
            var manager = new DataSourceTransactionManager(dataSource);
            manager.setEnforceReadOnly(true);
            var reader = new RecoverySnapshotReader(new JdbcTemplate(dataSource), manager);
            if (expected == null) {
                var snapshot = reader.capture(environmentId, checkpointId);
                var receipt = files.write(file, snapshot, key);
                output.printf("CAPTURED environment=%s checkpoint=%s sha256=%s NO_AUTORIZA_REAPERTURA%n",
                        receipt.environmentId(), receipt.checkpointId(), receipt.sha256());
                return finishReport(output, 0);
            }
            var actual = reader.capture(environmentId, UUID.randomUUID());
            var report = RecoveryCheckpointComparison.compare(expected, actual);
            output.printf("%s findings=%d %s%n", report.status(), report.findings().size(), report.notice());
            // Only technical identifiers, enum names and counts. Never content, per-row hashes or secrets.
            report.findings().forEach(finding -> output.printf("workshop=%d surface=%s issue=%s%n",
                    finding.tallerId(), finding.surface() == null ? "INVENTORY" : finding.surface().name(), finding.issue()));
            return finishReport(output, report.status() == RecoveryCheckpointComparison.Status.MATCH ? 0 : 2);
        } catch (Exception failure) {
            output.println("RECOVERY_CHECK_FAILED NO_AUTORIZA_REAPERTURA");
            return 1;
        } finally { if (key != null) Arrays.fill(key, (byte) 0); }
    }

    static int finishReport(PrintStream output, int result) {
        output.flush();
        // PrintStream suppresses IOExceptions: loss of the independently stored receipt is a failure.
        return output.checkError() ? 1 : result;
    }

    private static String required(Map<String, String> environment, String suffix) {
        String value = environment.get("ORDENFIX_RECOVERY_" + suffix);
        if (value == null || value.isBlank()) throw invalid();
        return value;
    }

    private static byte[] key(Path file) throws java.io.IOException {
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || permissions.stream().anyMatch(permission ->
                permission != PosixFilePermission.OWNER_READ && permission != PosixFilePermission.OWNER_WRITE)) throw invalid();
        try (var channel = Files.newByteChannel(file, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
             var input = java.nio.channels.Channels.newInputStream(channel)) {
            byte[] key = input.readNBytes(33);
            if (key.length != 32) { Arrays.fill(key, (byte) 0); throw invalid(); }
            return key;
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Configuración de recuperación inválida.");
    }
}
