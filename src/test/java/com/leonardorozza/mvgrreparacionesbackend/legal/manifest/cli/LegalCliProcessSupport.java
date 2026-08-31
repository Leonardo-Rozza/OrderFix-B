package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Shared subprocess boundary for packaged legal CLI integration tests. */
final class LegalCliProcessSupport {

    private static final String BUILD_DIRECTORY_PROPERTY = "ordenfix.build.directory";
    private static final String BUILD_FINAL_NAME_PROPERTY = "ordenfix.build.final-name";
    private static final String DEFAULT_BUILD_DIRECTORY = "target";
    private static final String DEFAULT_BUILD_FINAL_NAME =
            "mvgr-reparaciones-backend-0.0.1-SNAPSHOT";
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration TERMINATION_GRACE = Duration.ofSeconds(2);
    private static final Duration CAPTURE_COMPLETION_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READER_SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_CAPTURE_BYTES = 1_048_576;
    static final String READER_THREAD_PREFIX = "ordenfix-legal-cli-reader-";
    private static final List<String> ENVIRONMENT_TO_REMOVE = List.of(
            "SPRING_DATASOURCE_URL",
            "SPRING_DATASOURCE_USERNAME",
            "SPRING_DATASOURCE_PASSWORD",
            "SPRING_DATASOURCE_DRIVER_CLASS_NAME",
            "SPRING_APPLICATION_JSON",
            "SPRING_CONFIG_ADDITIONAL_LOCATION",
            "SPRING_CONFIG_IMPORT",
            "SPRING_CONFIG_LOCATION",
            "SPRING_CONFIG_NAME",
            "SPRING_PROFILES_ACTIVE",
            "JAVA_TOOL_OPTIONS",
            "JDK_JAVA_OPTIONS",
            "_JAVA_OPTIONS",
            LegalImportEnvironment.ENABLED_VARIABLE,
            LegalEditorialEnvironment.ENABLED_VARIABLE);

    private LegalCliProcessSupport() { }

    static Artifacts locateArtifacts() {
        Path projectDirectory = Path.of(System.getProperty("user.dir"))
                .toAbsolutePath()
                .normalize();
        Path configuredBuildDirectory = Path.of(System.getProperty(
                BUILD_DIRECTORY_PROPERTY,
                DEFAULT_BUILD_DIRECTORY));
        Path buildDirectory = configuredBuildDirectory.isAbsolute()
                ? configuredBuildDirectory.normalize()
                : projectDirectory.resolve(configuredBuildDirectory).normalize();
        String finalName = System.getProperty(
                BUILD_FINAL_NAME_PROPERTY,
                DEFAULT_BUILD_FINAL_NAME);
        Path normalJar = buildDirectory.resolve(finalName + ".jar");
        Path legalCliJar = buildDirectory.resolve(finalName + "-legal-cli.jar");
        Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java")
                .toAbsolutePath()
                .normalize();

        requireRegularFile(normalJar, "jar normal");
        requireRegularFile(legalCliJar, "jar legal-cli");
        requireRegularFile(javaExecutable, "ejecutable Java");
        if (!Files.isExecutable(javaExecutable)) {
            throw new AssertionError("El ejecutable Java localizado no es ejecutable");
        }
        return new Artifacts(
                projectDirectory,
                buildDirectory,
                finalName,
                normalJar,
                legalCliJar,
                javaExecutable);
    }

    static ProcessResult executeJar(
            Artifacts artifacts,
            Path workingDirectory,
            List<String> jvmArguments,
            List<String> cliArguments,
            Map<String, String> environmentOverrides,
            StdoutMode stdoutMode) throws Exception {
        return executeJar(
                artifacts,
                workingDirectory,
                jvmArguments,
                cliArguments,
                environmentOverrides,
                stdoutMode,
                PROCESS_TIMEOUT);
    }

    static ProcessResult executeJar(
            Artifacts artifacts,
            Path workingDirectory,
            List<String> jvmArguments,
            List<String> cliArguments,
            Map<String, String> environmentOverrides,
            StdoutMode stdoutMode,
            Duration timeout) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(artifacts.javaExecutable().toString());
        command.addAll(List.copyOf(jvmArguments));
        command.add("-jar");
        command.add(artifacts.legalCliJar().toString());
        command.addAll(List.copyOf(cliArguments));
        return execute(
                command,
                workingDirectory,
                environmentOverrides,
                stdoutMode,
                timeout);
    }

    static ProcessResult execute(
            List<String> command,
            Path workingDirectory,
            Map<String, String> environmentOverrides,
            StdoutMode stdoutMode) throws Exception {
        return execute(
                command,
                workingDirectory,
                environmentOverrides,
                stdoutMode,
                PROCESS_TIMEOUT);
    }

    static ProcessResult execute(
            List<String> command,
            Path workingDirectory,
            Map<String, String> environmentOverrides,
            StdoutMode stdoutMode,
            Duration timeout) throws Exception {
        return execute(
                command,
                workingDirectory,
                environmentOverrides,
                stdoutMode,
                timeout,
                null);
    }

    static ProcessResult executeAfterCheckpoint(
            List<String> command,
            Path workingDirectory,
            Map<String, String> environmentOverrides,
            StdoutMode stdoutMode,
            Duration timeout,
            ProcessCheckpoint checkpoint) throws Exception {
        return execute(
                command,
                workingDirectory,
                environmentOverrides,
                stdoutMode,
                timeout,
                Objects.requireNonNull(checkpoint, "checkpoint"));
    }

    private static ProcessResult execute(
            List<String> command,
            Path workingDirectory,
            Map<String, String> environmentOverrides,
            StdoutMode stdoutMode,
            Duration timeout,
            ProcessCheckpoint checkpoint) throws Exception {
        List<String> validatedCommand = List.copyOf(
                Objects.requireNonNull(command, "command"));
        Path validatedWorkingDirectory = Objects.requireNonNull(
                workingDirectory,
                "workingDirectory");
        Map<String, String> validatedEnvironmentOverrides = Map.copyOf(
                Objects.requireNonNull(environmentOverrides, "environmentOverrides"));
        StdoutMode validatedStdoutMode = Objects.requireNonNull(stdoutMode, "stdoutMode");
        long timeoutNanos = requirePositiveTimeoutNanos(timeout);

        ProcessBuilder processBuilder = new ProcessBuilder(validatedCommand);
        processBuilder.directory(validatedWorkingDirectory.toFile());
        processBuilder.redirectErrorStream(false);
        Map<String, String> environment = processBuilder.environment();
        sanitizeInheritedEnvironment(environment);
        environment.putAll(validatedEnvironmentOverrides);

        long started = System.nanoTime();
        Process process = processBuilder.start();
        ExecutorService readers = null;
        Future<CapturedOutput> standardOutput = null;
        Future<CapturedOutput> standardError = null;
        Throwable primaryFailure = null;
        try {
            process.getOutputStream().close();
            if (validatedStdoutMode == StdoutMode.CLOSE_IMMEDIATELY) {
                process.getInputStream().close();
            }
            int readerCount = validatedStdoutMode == StdoutMode.CAPTURE ? 2 : 1;
            readers = newReaderExecutor(readerCount, process.pid());
            standardOutput = validatedStdoutMode == StdoutMode.CAPTURE
                    ? readers.submit(() -> capture(process.getInputStream()))
                    : null;
            standardError = readers.submit(() -> capture(process.getErrorStream()));

            long waitStarted = started;
            if (checkpoint != null) {
                checkpoint.await(process);
                waitStarted = System.nanoTime();
            }
            long remaining = remainingNanos(waitStarted, timeoutNanos);
            boolean completed = !process.isAlive();
            if (!completed && remaining > 0L) {
                completed = process.waitFor(remaining, TimeUnit.NANOSECONDS);
            }
            boolean timedOut = !completed;
            if (timedOut) {
                terminateAndAwait(process);
            }
            if (process.isAlive()) {
                throw new AssertionError("El proceso legal-cli no entregó un exit final");
            }
            long captureDeadline = System.nanoTime()
                    + CAPTURE_COMPLETION_TIMEOUT.toNanos();
            CapturedOutput stdout = standardOutput == null
                    ? CapturedOutput.empty()
                    : awaitCapture(standardOutput, captureDeadline, "stdout");
            CapturedOutput stderr = awaitCapture(
                    Objects.requireNonNull(standardError, "standardError"),
                    captureDeadline,
                    "stderr");
            return new ProcessResult(
                    process.exitValue(),
                    stdout.bytes(),
                    stderr.bytes(),
                    stdout.limitExceeded(),
                    stderr.limitExceeded(),
                    MAX_CAPTURE_BYTES,
                    Duration.ofNanos(System.nanoTime() - started),
                    timedOut);
        } catch (Exception | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            cleanupProcess(
                    process,
                    standardOutput,
                    standardError,
                    readers,
                    primaryFailure);
        }
    }

    static void sanitizeInheritedEnvironment(Map<String, String> environment) {
        ENVIRONMENT_TO_REMOVE.forEach(environment::remove);
        environment.keySet().removeIf(name -> name != null
                && (name.startsWith("ORDENFIX_LEGAL_IMPORT_DB_")
                    || name.startsWith("ORDENFIX_LEGAL_EDITOR_DB_")));
    }

    private static CapturedOutput capture(InputStream input) throws IOException {
        try (InputStream source = input;
             ByteArrayOutputStream captured = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            boolean limitExceeded = false;
            int read;
            while ((read = source.read(buffer)) != -1) {
                int remaining = MAX_CAPTURE_BYTES - captured.size();
                if (remaining > 0) {
                    captured.write(buffer, 0, Math.min(remaining, read));
                }
                if (read > remaining) {
                    limitExceeded = true;
                }
            }
            return new CapturedOutput(captured.toByteArray(), limitExceeded);
        }
    }

    private static ExecutorService newReaderExecutor(int readerCount, long processId) {
        AtomicInteger index = new AtomicInteger();
        return Executors.newFixedThreadPool(readerCount, task -> {
            Thread reader = new Thread(
                    task,
                    READER_THREAD_PREFIX + processId + "-" + index.incrementAndGet());
            reader.setDaemon(true);
            return reader;
        });
    }

    private static CapturedOutput awaitCapture(
            Future<CapturedOutput> capture,
            long deadline,
            String streamName) throws Exception {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0L) {
            throw new AssertionError(
                    "Se agotó el plazo común al capturar " + streamName);
        }
        return capture.get(remaining, TimeUnit.NANOSECONDS);
    }

    private static long requirePositiveTimeoutNanos(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout debe ser positivo");
        }
        try {
            return timeout.toNanos();
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("timeout excede el rango soportado", failure);
        }
    }

    private static long remainingNanos(long started, long timeoutNanos) {
        long elapsed = System.nanoTime() - started;
        if (elapsed < 0L || elapsed >= timeoutNanos) {
            return 0L;
        }
        return timeoutNanos - elapsed;
    }

    private static void terminateAndAwait(Process process) throws InterruptedException {
        process.destroy();
        if (process.waitFor(TERMINATION_GRACE.toNanos(), TimeUnit.NANOSECONDS)) {
            return;
        }
        process.destroyForcibly();
        if (!process.waitFor(TERMINATION_GRACE.toNanos(), TimeUnit.NANOSECONDS)) {
            throw new AssertionError("No se pudo terminar el proceso legal-cli vencido");
        }
    }

    private static void terminateForCleanup(Process process) {
        if (!process.isAlive()) {
            return;
        }
        boolean interrupted = false;
        process.destroy();
        long gracefulDeadline = System.nanoTime() + TERMINATION_GRACE.toNanos();
        while (process.isAlive() && System.nanoTime() < gracefulDeadline) {
            try {
                process.waitFor(50, TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (process.isAlive()) {
            process.destroyForcibly();
            long forcedDeadline = System.nanoTime() + TERMINATION_GRACE.toNanos();
            while (process.isAlive() && System.nanoTime() < forcedDeadline) {
                try {
                    process.waitFor(50, TimeUnit.MILLISECONDS);
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        if (process.isAlive()) {
            throw new AssertionError("El cleanup no pudo terminar el proceso legal-cli");
        }
    }

    private static void cleanupProcess(
            Process process,
            Future<?> standardOutput,
            Future<?> standardError,
            ExecutorService readers,
            Throwable primaryFailure) {
        List<Throwable> cleanupFailures = new ArrayList<>();
        runCleanup(cleanupFailures, () -> terminateForCleanup(process));
        runCleanup(cleanupFailures, () -> process.getOutputStream().close());
        runCleanup(cleanupFailures, () -> process.getInputStream().close());
        runCleanup(cleanupFailures, () -> process.getErrorStream().close());
        runCleanup(cleanupFailures, () -> cancelIfRunning(standardOutput));
        runCleanup(cleanupFailures, () -> cancelIfRunning(standardError));
        runCleanup(cleanupFailures, () -> shutdownReaders(readers));

        if (cleanupFailures.isEmpty()) {
            return;
        }
        if (primaryFailure != null) {
            cleanupFailures.forEach(primaryFailure::addSuppressed);
            return;
        }
        Throwable firstFailure = cleanupFailures.getFirst();
        cleanupFailures.stream()
                .skip(1L)
                .forEach(firstFailure::addSuppressed);
        rethrowCleanupFailure(firstFailure);
    }

    private static void runCleanup(
            List<Throwable> failures,
            CleanupAction action) {
        try {
            action.run();
        } catch (Throwable failure) {
            failures.add(failure);
        }
    }

    private static void rethrowCleanupFailure(Throwable failure) {
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        throw new AssertionError("Falló el cleanup del proceso legal-cli", failure);
    }

    private static void cancelIfRunning(Future<?> future) {
        if (future != null && !future.isDone()) {
            future.cancel(true);
        }
    }

    private static void shutdownReaders(ExecutorService readers) {
        if (readers == null) {
            return;
        }
        readers.shutdownNow();
        boolean interrupted = false;
        long deadline = System.nanoTime() + READER_SHUTDOWN_TIMEOUT.toNanos();
        while (!readers.isTerminated() && System.nanoTime() < deadline) {
            try {
                readers.awaitTermination(50, TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        if (!readers.isTerminated()) {
            throw new AssertionError("Los lectores del proceso legal-cli no terminaron");
        }
    }

    private static String decodeUtf8(byte[] bytes, String streamName) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new AssertionError(
                    "El stream " + streamName + " del proceso no es UTF-8 válido",
                    exception);
        }
    }

    private static void requireRegularFile(Path artifact, String description) {
        if (!Files.isRegularFile(artifact)) {
            throw new AssertionError("No se encontró el " + description + " requerido");
        }
    }

    enum StdoutMode {
        CAPTURE,
        CLOSE_IMMEDIATELY
    }

    @FunctionalInterface
    interface ProcessCheckpoint {
        void await(Process process) throws Exception;
    }

    @FunctionalInterface
    private interface CleanupAction {
        void run() throws Exception;
    }

    record Artifacts(
            Path projectDirectory,
            Path buildDirectory,
            String finalName,
            Path normalJar,
            Path legalCliJar,
            Path javaExecutable
    ) { }

    record ProcessResult(
            int exitCode,
            byte[] stdoutBytes,
            byte[] stderrBytes,
            boolean stdoutLimitExceeded,
            boolean stderrLimitExceeded,
            int captureLimitBytes,
            Duration wallDuration,
            boolean timedOut
    ) {

        ProcessResult {
            stdoutBytes = Objects.requireNonNull(stdoutBytes, "stdoutBytes").clone();
            stderrBytes = Objects.requireNonNull(stderrBytes, "stderrBytes").clone();
            wallDuration = Objects.requireNonNull(wallDuration, "wallDuration");
            if (captureLimitBytes <= 0) {
                throw new IllegalArgumentException("captureLimitBytes debe ser positivo");
            }
            if (wallDuration.isNegative()) {
                throw new IllegalArgumentException("wallDuration no puede ser negativa");
            }
        }

        @Override
        public byte[] stdoutBytes() {
            return stdoutBytes.clone();
        }

        @Override
        public byte[] stderrBytes() {
            return stderrBytes.clone();
        }

        String stdout() {
            return decodeUtf8(stdoutBytes, "stdout");
        }

        String stderr() {
            return decodeUtf8(stderrBytes, "stderr");
        }
    }

    private record CapturedOutput(byte[] bytes, boolean limitExceeded) {
        private static CapturedOutput empty() {
            return new CapturedOutput(new byte[0], false);
        }
    }
}
