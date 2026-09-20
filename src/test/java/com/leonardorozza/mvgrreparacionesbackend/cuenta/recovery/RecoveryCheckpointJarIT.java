package com.leonardorozza.mvgrreparacionesbackend.cuenta.recovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;

import static org.assertj.core.api.Assertions.assertThat;

/** Invalid CLI commands and a quarantined main jar must stop without creating an application context. */
class RecoveryCheckpointJarIT {
    @TempDir Path temporaryDirectory;

    @Test void packagedArtifactHasItsOwnEntryAndContainsNoPrivateProperties() throws Exception {
        try (JarFile jar = new JarFile(recoveryJar().toFile())) {
            var manifest = jar.getManifest();
            assertThat(manifest).isNotNull();
            var attributes = manifest.getMainAttributes();
            assertThat(attributes.getValue("Main-Class")).isEqualTo("org.springframework.boot.loader.launch.JarLauncher");
            assertThat(attributes.getValue("Start-Class"))
                    .isEqualTo("com.leonardorozza.mvgrreparacionesbackend.cuenta.recovery.RecoveryCheckpointCli");
            for (String agent : List.of("Premain-Class", "Agent-Class", "Launcher-Agent-Class"))
                assertThat(attributes.getValue(agent)).isNull();
            assertThat(jar.stream().map(entry -> entry.getName())
                    .filter(name -> name.equals("application-secret.properties") || name.endsWith("/application-secret.properties"))
                    .toList()).isEmpty();
            var factoryEntry = jar.getJarEntry("META-INF/spring.factories");
            assertThat(factoryEntry).isNotNull();
            var factories = new Properties();
            try (var source = jar.getInputStream(factoryEntry)) { factories.load(source); }
            assertThat(factories.getProperty("org.springframework.boot.EnvironmentPostProcessor"))
                    .isEqualTo(RecoveryQuarantineEnvironmentPostProcessor.class.getName());
        }
    }

    @Test void packagedMainDiscoversTheQuarantineBarrierBeforeApplicationStartup() throws Exception {
        Path out = temporaryDirectory.resolve("main-stdout.txt"), err = temporaryDirectory.resolve("main-stderr.txt");
        var builder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", packagedJar("").toString(),
                "--spring.config.location=optional:classpath:/synthetic-none.properties",
                "--spring.config.import=",
                "--ordenfix.recovery.quarantine=true")
                .directory(temporaryDirectory.toFile())
                .redirectOutput(out.toFile()).redirectError(err.toFile());
        // Do not inherit credentials, provider configuration, imports or JVM option channels.
        builder.environment().clear();
        Process process = builder.start();
        try {
            assertThat(process.waitFor(20, TimeUnit.SECONDS)).as("bounded quarantined main process").isTrue();
            assertThat(process.exitValue()).isNotZero();
            assertThat(Files.size(out) + Files.size(err)).isLessThan(65_536);
            String output = Files.readString(out) + Files.readString(err);
            assertThat(output)
                    .contains(RecoveryQuarantineEnvironmentPostProcessor.StartupBlocked.class.getName())
                    .contains("Inicio bloqueado por la barrera de cuarentena de recuperación.")
                    .doesNotContain("HikariPool", "Flyway", "Tomcat initialized", "Tomcat started",
                            "Netty started", "Started MvgrReparacionesBackendApplication",
                            "Taller + admin inicial creados correctamente");
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void standaloneCliIgnoresApplicationConfigurationAndTheLauncherRemovesJvmPoison(boolean invalidArgument) throws Exception {
        Path launcher = Path.of("scripts/recovery-checkpoint.sh").toAbsolutePath().normalize();
        assertThat(launcher).isRegularFile().isExecutable();
        Path poisonedConfig = temporaryDirectory.resolve("application.properties");
        String contents = """
                ordenfix.recovery.quarantine=true
                spring.config.import=missing:private-recovery-poison-do-not-read
                spring.datasource.url=jdbc:postgresql://127.0.0.1:1/unused
                spring.datasource.password=private-recovery-poison-do-not-log
                ordenfix.cuenta.cierre.operational-deletion-enabled=true
                ordenfix.cuenta.cierre.operational-worker-enabled=true
                """;
        Files.writeString(poisonedConfig, contents);
        Path out = temporaryDirectory.resolve("stdout.txt"), err = temporaryDirectory.resolve("stderr.txt");
        var command = new ArrayList<String>(); command.add(launcher.toString());
        if (invalidArgument) command.add("--not-a-recovery-command");
        var builder = new ProcessBuilder(command).directory(temporaryDirectory.toFile())
                .redirectOutput(out.toFile()).redirectError(err.toFile());
        var environment = builder.environment();
        environment.keySet().removeIf(key -> key.startsWith("ORDENFIX_RECOVERY_"));
        environment.put("ORDENFIX_RECOVERY_CLI_JAR", recoveryJar().toString());
        environment.put("ORDENFIX_JAVA_BIN", Path.of(System.getProperty("java.home"), "bin", "java").toString());
        environment.put("ORDENFIX_RECOVERY_QUARANTINE", "true");
        environment.put("SPRING_CONFIG_IMPORT", "missing:private-environment-import-do-not-read");
        environment.put("SPRING_DATASOURCE_PASSWORD", "private-environment-password-do-not-log");
        environment.put("ORDENFIX_CUENTA_CIERRE_OPERATIONAL_DELETION_ENABLED", "true");
        environment.put("ORDENFIX_CUENTA_CIERRE_OPERATIONAL_WORKER_ENABLED", "true");
        environment.put("JAVA_TOOL_OPTIONS", "-XX:PrivateInvalidRecoveryToolOption");
        environment.put("JDK_JAVA_OPTIONS", "-XX:PrivateInvalidRecoveryJdkOption");
        environment.put("_JAVA_OPTIONS", "-XX:PrivateInvalidRecoveryLegacyOption");
        Process process = builder.start();
        try {
            assertThat(process.waitFor(20, TimeUnit.SECONDS)).as("bounded standalone recovery process").isTrue();
            assertThat(process.exitValue()).isEqualTo(1);
            assertThat(Files.size(out)).isLessThan(1024);
            assertThat(Files.readString(out)).isEqualTo("RECOVERY_CHECK_FAILED NO_AUTORIZA_REAPERTURA\n");
            assertThat(Files.readString(err)).isEmpty();
            assertThat(Files.readString(poisonedConfig)).isEqualTo(contents);
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    private static Path recoveryJar() { return packagedJar("-recovery-cli"); }

    private static Path packagedJar(String classifier) {
        Path directory = Path.of(System.getProperty("ordenfix.build.directory", "target")).toAbsolutePath().normalize();
        String name = System.getProperty("ordenfix.build.final-name", "mvgr-reparaciones-backend-0.0.1-SNAPSHOT");
        Path jar = directory.resolve(name + classifier + ".jar");
        assertThat(jar).isRegularFile();
        return jar;
    }
}
