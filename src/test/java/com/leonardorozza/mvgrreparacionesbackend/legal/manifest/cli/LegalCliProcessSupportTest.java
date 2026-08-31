package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli.LegalCliProcessSupport.ProcessResult;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli.LegalCliProcessSupport.StdoutMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

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
    void reportsTimeoutOnlyAfterFinalTerminationAndReaderCleanup() throws Exception {
        Duration timeout = Duration.ofMillis(250);
        Path marker = temporaryDirectory.resolve("timeout-ready");
        Path shutdownMarker = temporaryDirectory.resolve("timeout-shutdown-hook");

        ProcessResult result;
        try (WatchService signals = marker.getFileSystem().newWatchService()) {
            temporaryDirectory.register(signals, ENTRY_CREATE, ENTRY_MODIFY);
            result = LegalCliProcessSupport.executeAfterCheckpoint(
                    probeCommand(
                            "block-force",
                            marker.toString(),
                            shutdownMarker.toString()),
                    temporaryDirectory,
                    Map.of(),
                    StdoutMode.CAPTURE,
                    timeout,
                    process -> awaitMarker(marker, signals));
        }

        assertThat(result.timedOut()).isTrue();
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.wallDuration())
                .isGreaterThanOrEqualTo(timeout)
                .isLessThan(Duration.ofSeconds(12));
        assertThat(shutdownMarker).hasContent("entered");
        assertThat(result.stdout()).isEqualTo("READY\n");
        assertThat(result.stderr()).startsWith("PID=").endsWith("\n");
        long childPid = Long.parseLong(result.stderr().strip().substring("PID=".length()));
        assertThat(ProcessHandle.of(childPid)
                .map(ProcessHandle::isAlive)
                .orElse(false)).isFalse();
        assertThat(activeReaderThreads()).isEmpty();
    }

    @Test
    void interruptionStillTerminatesProcessAndReadersBeforePropagating() throws Exception {
        Path marker = temporaryDirectory.resolve("interrupt-ready");
        Path shutdownMarker = temporaryDirectory.resolve("interrupt-shutdown-hook");
        CountDownLatch checkpointReached = new CountDownLatch(1);
        AtomicReference<Thread> workerThread = new AtomicReference<>();
        ExecutorService worker = Executors.newSingleThreadExecutor();

        try (WatchService signals = marker.getFileSystem().newWatchService()) {
            temporaryDirectory.register(signals, ENTRY_CREATE, ENTRY_MODIFY);
            Future<Throwable> execution = worker.submit(() -> {
                workerThread.set(Thread.currentThread());
                try {
                    LegalCliProcessSupport.executeAfterCheckpoint(
                            probeCommand(
                                    "block-force",
                                    marker.toString(),
                                    shutdownMarker.toString()),
                            temporaryDirectory,
                            Map.of(),
                            StdoutMode.CAPTURE,
                            Duration.ofSeconds(30),
                            process -> {
                                awaitMarker(marker, signals);
                                checkpointReached.countDown();
                            });
                    return null;
                } catch (Throwable failure) {
                    return failure;
                }
            });

            assertThat(checkpointReached.await(5, TimeUnit.SECONDS)).isTrue();
            workerThread.get().interrupt();
            assertThat(execution.get(15, TimeUnit.SECONDS))
                    .isInstanceOf(InterruptedException.class);
        } finally {
            worker.shutdownNow();
            assertThat(worker.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        long childPid = Long.parseLong(Files.readString(marker, StandardCharsets.UTF_8));
        assertThat(ProcessHandle.of(childPid)
                .map(ProcessHandle::isAlive)
                .orElse(false)).isFalse();
        assertThat(shutdownMarker).hasContent("entered");
        assertThat(activeReaderThreads()).isEmpty();
    }

    @Test
    void rejectsInvalidExecutionConfigurationBeforeStartingTheChild() {
        Path marker = temporaryDirectory.resolve("must-not-start");
        List<String> command = probeCommand("mark", marker.toString());

        assertThatThrownBy(() -> LegalCliProcessSupport.execute(
                command,
                temporaryDirectory,
                Map.of(),
                null,
                Duration.ofSeconds(1)))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("stdoutMode");
        assertThat(marker).doesNotExist();

        assertThatThrownBy(() -> LegalCliProcessSupport.execute(
                command,
                temporaryDirectory,
                Map.of(),
                StdoutMode.CAPTURE,
                Duration.ofSeconds(Long.MAX_VALUE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("timeout excede el rango soportado")
                .hasCauseInstanceOf(ArithmeticException.class);
        assertThat(marker).doesNotExist();
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

    private static List<String> probeCommand(String mode, String... arguments) {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(ProcessProbe.class.getName());
        command.add(mode);
        command.addAll(List.of(arguments));
        return List.copyOf(command);
    }

    private static void awaitMarker(Path marker, WatchService signals) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!Files.isRegularFile(marker)) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) {
                throw new AssertionError("El proceso hijo no publicó su checkpoint");
            }
            WatchKey key = signals.poll(remaining, TimeUnit.NANOSECONDS);
            if (key == null) {
                throw new AssertionError("El proceso hijo no publicó su checkpoint");
            }
            key.pollEvents();
            if (!key.reset()) {
                throw new AssertionError("El watcher del checkpoint dejó de ser válido");
            }
        }
    }

    private static List<String> activeReaderThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .map(Thread::getName)
                .filter(name -> name.startsWith(LegalCliProcessSupport.READER_THREAD_PREFIX))
                .sorted()
                .toList();
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
                case "block-force" -> {
                    long processId = ProcessHandle.current().pid();
                    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                        try {
                            Files.writeString(
                                    Path.of(args[2]),
                                    "entered",
                                    StandardCharsets.UTF_8);
                            new CountDownLatch(1).await();
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                        } catch (Exception exception) {
                            throw new IllegalStateException(
                                    "No se pudo publicar el shutdown hook",
                                    exception);
                        }
                    }, "ordenfix-process-probe-shutdown-blocker"));
                    System.out.write("READY\n".getBytes(StandardCharsets.UTF_8));
                    System.err.write(("PID=" + processId + "\n")
                            .getBytes(StandardCharsets.UTF_8));
                    System.out.flush();
                    System.err.flush();
                    Path marker = Path.of(args[1]);
                    Path pendingMarker = marker.resolveSibling(
                            marker.getFileName() + ".pending");
                    Files.writeString(
                            pendingMarker,
                            Long.toString(processId),
                            StandardCharsets.UTF_8);
                    Files.move(
                            pendingMarker,
                            marker,
                            StandardCopyOption.ATOMIC_MOVE);
                    new CountDownLatch(1).await();
                }
                case "mark" -> Files.writeString(
                        Path.of(args[1]),
                        "started",
                        StandardCharsets.UTF_8);
                default -> throw new IllegalArgumentException("Modo probe desconocido: " + args[0]);
            }
            System.out.flush();
            System.err.flush();
        }
    }
}
