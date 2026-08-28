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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Shared subprocess boundary for packaged legal CLI integration tests. */
final class LegalCliProcessSupport {

    private static final String BUILD_DIRECTORY_PROPERTY = "ordenfix.build.directory";
    private static final String BUILD_FINAL_NAME_PROPERTY = "ordenfix.build.final-name";
    private static final String DEFAULT_BUILD_DIRECTORY = "target";
    private static final String DEFAULT_BUILD_FINAL_NAME =
            "mvgr-reparaciones-backend-0.0.1-SNAPSHOT";
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(90);
    private static final int MAX_CAPTURE_BYTES = 1_048_576;
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
                stdoutMode);
    }

    static ProcessResult execute(
            List<String> command,
            Path workingDirectory,
            Map<String, String> environmentOverrides,
            StdoutMode stdoutMode) throws Exception {
        ProcessBuilder processBuilder = new ProcessBuilder(List.copyOf(command));
        processBuilder.directory(workingDirectory.toFile());
        processBuilder.redirectErrorStream(false);
        Map<String, String> environment = processBuilder.environment();
        sanitizeInheritedEnvironment(environment);
        environment.putAll(Map.copyOf(environmentOverrides));

        Process process = processBuilder.start();
        if (stdoutMode == StdoutMode.CLOSE_IMMEDIATELY) {
            process.getInputStream().close();
        }
        int readerCount = stdoutMode == StdoutMode.CAPTURE ? 2 : 1;
        ExecutorService readers = Executors.newFixedThreadPool(readerCount);
        Future<CapturedOutput> standardOutput = stdoutMode == StdoutMode.CAPTURE
                ? readers.submit(() -> capture(process.getInputStream()))
                : null;
        Future<CapturedOutput> standardError = readers.submit(
                () -> capture(process.getErrorStream()));
        try {
            if (!process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroy();
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(2, TimeUnit.SECONDS);
                }
                throw new AssertionError(
                        "El proceso legal-cli superó el timeout de " + PROCESS_TIMEOUT);
            }
            CapturedOutput stdout = standardOutput == null
                    ? CapturedOutput.empty()
                    : standardOutput.get(5, TimeUnit.SECONDS);
            CapturedOutput stderr = standardError.get(5, TimeUnit.SECONDS);
            return new ProcessResult(
                    process.exitValue(),
                    decodeUtf8(stdout.bytes()),
                    decodeUtf8(stderr.bytes()),
                    stdout.limitExceeded(),
                    stderr.limitExceeded());
        } finally {
            readers.shutdownNow();
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

    private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
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
            String stdout,
            String stderr,
            boolean stdoutLimitExceeded,
            boolean stderrLimitExceeded
    ) { }

    private record CapturedOutput(byte[] bytes, boolean limitExceeded) {
        private static CapturedOutput empty() {
            return new CapturedOutput(new byte[0], false);
        }
    }
}
