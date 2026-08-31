package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli.LegalCliProcessSupport.ProcessResult;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli.LegalCliProcessSupport.StdoutMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegalCliProcessSupportTest {

    private static final String STDOUT_FAILURE_AGENT_CLASS =
            "com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli."
                    + "LegalCliStdoutFailureAgent";
    private static final String STDOUT_FAILURE_AGENT_ENTRY =
            STDOUT_FAILURE_AGENT_CLASS.replace('.', '/') + ".class";
    private static final String STDOUT_CONTRACT = "0123456789";

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
        assertThat(result.captureLimitBytes()).isEqualTo(1_048_576);
        assertThat(result.stdoutBytes()).hasSize(result.captureLimitBytes());
        assertThat(result.stderrBytes()).hasSize(result.captureLimitBytes());
        assertThat(result.stdoutBytes()).containsOnly((byte) 'o');
        assertThat(result.stderrBytes()).containsOnly((byte) 'e');
    }

    @Test
    void capturesCompleteZeroAndExactPartialStdoutDeterministically() throws Exception {
        Path agentDirectory = Files.createDirectory(
                temporaryDirectory.resolve("agent jar with spaces"));
        Path agentJar = createStdoutFailureAgentJar(agentDirectory);

        ProcessResult complete = executeStdoutContract(List.of());
        ProcessResult empty = executeStdoutContract(List.of(
                javaAgentArgument(agentJar, 0)), agentJar);
        ProcessResult partial = executeStdoutContract(List.of(
                javaAgentArgument(agentJar, 7)), agentJar);

        assertThat(complete.exitCode()).isZero();
        assertThat(complete.timedOut()).isFalse();
        assertThat(complete.stdout()).isEqualTo(STDOUT_CONTRACT);
        assertThat(complete.stderrBytes()).isEmpty();
        assertThat(complete.stdoutLimitExceeded()).isFalse();
        assertThat(complete.stderrLimitExceeded()).isFalse();

        assertThat(empty.exitCode()).isEqualTo(3);
        assertThat(empty.timedOut()).isFalse();
        assertThat(empty.stdoutBytes()).isEmpty();
        assertThat(empty.stderrBytes()).isEmpty();
        assertThat(empty.stdoutLimitExceeded()).isFalse();
        assertThat(empty.stderrLimitExceeded()).isFalse();

        assertThat(partial.exitCode()).isEqualTo(3);
        assertThat(partial.timedOut()).isFalse();
        assertThat(partial.stdoutBytes()).containsExactly(
                Arrays.copyOf(STDOUT_CONTRACT.getBytes(StandardCharsets.US_ASCII), 7));
        assertThat(partial.stdout()).isEqualTo("0123456");
        assertThat(partial.stderrBytes()).isEmpty();
        assertThat(partial.stdoutLimitExceeded()).isFalse();
        assertThat(partial.stderrLimitExceeded()).isFalse();
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
    void retainsOnlyTheMinimalRuntimeEnvironment() {
        Map<String, String> environment = new HashMap<>();
        environment.put("JAVA_TOOL_OPTIONS", "-Dspring.datasource.url=jdbc:hostile");
        environment.put("JDK_JAVA_OPTIONS", "-Dspring.datasource.username=hostile");
        environment.put("_JAVA_OPTIONS", "-Dspring.datasource.password=hostile");
        environment.put("JAVA_HOME", "/private/hostile-jdk");
        environment.put("JRE_HOME", "/private/hostile-jre");
        environment.put("CLASSPATH", "/private/hostile-classes");
        environment.put("JAVA_OPTS", "-javaagent:/private/hostile-java-agent.jar");
        environment.put("HOME", "/private/hostile-home");
        environment.put("USERPROFILE", "C:\\private\\hostile-profile");
        environment.put("BASH_ENV", "/private/hostile-bashrc");
        environment.put("ENV", "/private/hostile-shrc");
        environment.put("MAVEN_OPTS", "-javaagent:/private/hostile.jar");
        environment.put("GRADLE_OPTS", "-javaagent:/private/hostile-gradle-agent.jar");
        environment.put("LD_PRELOAD", "/private/hostile.so");
        environment.put("DYLD_INSERT_LIBRARIES", "/private/hostile.dylib");
        environment.put("GLIBC_TUNABLES", "glibc.malloc.check=3");
        environment.put("TZ", "hostile/timezone");
        environment.put("HTTPS_PROXY", "http://hostile-proxy.invalid");
        environment.put("PGPASSWORD", "hostile-postgres-secret");
        environment.put("SPRING_DATASOURCE_PASSWORD", "hostile");
        environment.put("SPRING_CONFIG_IMPORT", "file:/private/hostile.properties");
        environment.put("SPRING_MAIN_WEB_APPLICATION_TYPE", "servlet");
        environment.put("LOGGING_CONFIG", "file:/private/hostile-logback.xml");
        environment.put(LegalImportEnvironment.ENABLED_VARIABLE, "true");
        environment.put("ORDENFIX_LEGAL_IMPORT_DB_PASSWORD", "hostile-import");
        environment.put(LegalEditorialEnvironment.ENABLED_VARIABLE, "true");
        environment.put("ORDENFIX_LEGAL_EDITOR_DB_URL", "jdbc:hostile-editor");
        environment.put("ORDENFIX_LEGAL_EDITOR_DB_PASSWORD", "hostile-editor");
        environment.put(
                "ORDENFIX_LEGAL_EDITOR_DB_PASSWORD_FILE",
                "/private/hostile-editor-secret");
        environment.put("ORDENFIX_UNRELATED", "hostile");
        environment.put("PATH", "/usr/bin");
        environment.put("TMPDIR", "/runtime/tmpdir");
        environment.put("TMP", "/runtime/tmp");
        environment.put("TEMP", "/runtime/temp");
        environment.put("LANG", "es_AR.UTF-8");
        environment.put("LC_ALL", "C");
        environment.put("LC_MESSAGES", "es_AR.UTF-8");
        environment.put("SystemRoot", "C:\\Windows");
        environment.put("WINDIR", "C:\\Windows");
        environment.put("COMSPEC", "C:\\Windows\\System32\\cmd.exe");
        environment.put("PATHEXT", ".COM;.EXE;.BAT;.CMD");

        LegalCliProcessSupport.sanitizeInheritedEnvironment(
                environment,
                temporaryDirectory);

        Map<String, String> expected = new HashMap<>();
        List<String> expectedPath = new ArrayList<>();
        expectedPath.add(Path.of(System.getProperty("java.home"), "bin")
                .toAbsolutePath()
                .normalize()
                .toString());
        if (File.separatorChar == '\\') {
            expectedPath.add("C:\\Windows\\System32");
            expectedPath.add("C:\\Windows");
            expected.put("SystemRoot", "C:\\Windows");
        } else {
            expectedPath.add("/usr/bin");
            expectedPath.add("/bin");
            expected.put("LANG", "C.UTF-8");
            expected.put("LC_ALL", "C.UTF-8");
        }
        expected.put("PATH", String.join(File.pathSeparator, expectedPath));
        String temporaryPath = temporaryDirectory.toAbsolutePath().normalize().toString();
        expected.put("TMPDIR", temporaryPath);
        expected.put("TMP", temporaryPath);
        expected.put("TEMP", temporaryPath);

        assertThat(environment).containsExactlyInAnyOrderEntriesOf(expected);
    }

    @Test
    void appliesExplicitOverridesAfterMinimalizingTheInheritedEnvironment() throws Exception {
        Path explicitTemporaryDirectory = Files.createDirectory(
                temporaryDirectory.resolve("explicit temp"));
        ProcessResult result = LegalCliProcessSupport.execute(
                probeCommand(
                        "environment",
                        "PATH",
                        "TMPDIR",
                        "HOME",
                        "ORDENFIX_UNRELATED"),
                temporaryDirectory,
                Map.of(
                        "PATH", "/explicit/bin",
                        "TMPDIR", explicitTemporaryDirectory.toString(),
                        "HOME", "/explicit/home",
                        "ORDENFIX_UNRELATED", "explicit"),
                StdoutMode.CAPTURE);

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).isEqualTo(
                "PATH=/explicit/bin\n"
                        + "TMPDIR=" + explicitTemporaryDirectory + "\n"
                        + "HOME=/explicit/home\n"
                        + "ORDENFIX_UNRELATED=explicit\n");
        assertThat(result.stderr()).isEmpty();
    }

    private ProcessResult executeProbe(String mode) throws Exception {
        return LegalCliProcessSupport.execute(
                probeCommand(mode),
                temporaryDirectory,
                Map.of(),
                StdoutMode.CAPTURE);
    }

    private ProcessResult executeStdoutContract(List<String> jvmArguments) throws Exception {
        return executeStdoutContract(jvmArguments, null);
    }

    private ProcessResult executeStdoutContract(
            List<String> jvmArguments,
            Path agentJar) throws Exception {
        return LegalCliProcessSupport.execute(
                probeCommand(jvmArguments, agentJar, "stdout-contract"),
                temporaryDirectory,
                Map.of(),
                StdoutMode.CAPTURE);
    }

    private static List<String> probeCommand(String mode, String... arguments) {
        return probeCommand(List.of(), null, mode, arguments);
    }

    private static List<String> probeCommand(
            List<String> jvmArguments,
            Path firstClasspathEntry,
            String mode,
            String... arguments) {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(jvmArguments);
        command.add("-cp");
        String classpath = System.getProperty("java.class.path");
        command.add(firstClasspathEntry == null
                ? classpath
                : firstClasspathEntry + File.pathSeparator + classpath);
        command.add(ProcessProbe.class.getName());
        command.add(mode);
        command.addAll(List.of(arguments));
        return List.copyOf(command);
    }

    private static String javaAgentArgument(Path agentJar, int prefixBytes) {
        return "-javaagent:" + agentJar.toAbsolutePath() + "=" + prefixBytes;
    }

    private static Path createStdoutFailureAgentJar(Path directory) throws IOException {
        InputStream classBytes = LegalCliProcessSupportTest.class
                .getClassLoader()
                .getResourceAsStream(STDOUT_FAILURE_AGENT_ENTRY);
        if (classBytes == null) {
            throw new AssertionError(
                    "No se encontraron los bytes compilados del agente de stdout");
        }

        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue(
                "Premain-Class",
                STDOUT_FAILURE_AGENT_CLASS);
        Path agentJar = directory.resolve("stdout failure agent.jar");
        try (classBytes;
             JarOutputStream output = new JarOutputStream(
                     Files.newOutputStream(agentJar, CREATE_NEW, WRITE),
                     manifest)) {
            JarEntry classEntry = new JarEntry(STDOUT_FAILURE_AGENT_ENTRY);
            classEntry.setTime(0L);
            output.putNextEntry(classEntry);
            classBytes.transferTo(output);
            output.closeEntry();
        }

        try (JarFile packagedAgent = new JarFile(agentJar.toFile())) {
            assertThat(packagedAgent.getManifest()
                    .getMainAttributes()
                    .getValue("Premain-Class"))
                    .isEqualTo(STDOUT_FAILURE_AGENT_CLASS);
            assertThat(packagedAgent.stream().map(JarEntry::getName).toList())
                    .containsExactlyInAnyOrder(
                            "META-INF/MANIFEST.MF",
                            STDOUT_FAILURE_AGENT_ENTRY);
        }
        return agentJar;
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
                case "environment" -> {
                    StringBuilder output = new StringBuilder();
                    for (int index = 1; index < args.length; index++) {
                        output.append(args[index])
                                .append('=')
                                .append(System.getenv(args[index]))
                                .append('\n');
                    }
                    System.out.write(output.toString().getBytes(StandardCharsets.UTF_8));
                }
                case "stdout-contract" -> {
                    System.out.write("0123".getBytes(StandardCharsets.US_ASCII));
                    System.out.flush();
                    System.out.write("456789".getBytes(StandardCharsets.US_ASCII));
                    System.out.flush();
                    System.exit(System.out.checkError() ? 3 : 0);
                }
                default -> throw new IllegalArgumentException("Modo probe desconocido: " + args[0]);
            }
            System.out.flush();
            System.err.flush();
        }
    }
}
