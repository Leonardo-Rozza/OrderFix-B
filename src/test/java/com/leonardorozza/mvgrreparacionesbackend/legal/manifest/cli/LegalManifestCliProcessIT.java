package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

@Testcontainers
class LegalManifestCliProcessIT {

    private static final String BUILD_DIRECTORY_PROPERTY = "ordenfix.build.directory";
    private static final String BUILD_FINAL_NAME_PROPERTY = "ordenfix.build.final-name";
    private static final String DEFAULT_BUILD_DIRECTORY = "target";
    private static final String DEFAULT_BUILD_FINAL_NAME =
            "mvgr-reparaciones-backend-0.0.1-SNAPSHOT";
    private static final String GOLDEN_MANIFEST =
            "/legal/manifest/release-valid-v1/publication-manifest.json";
    private static final String GOLDEN_MANIFEST_SHA256 =
            "b3b452c8f6f4deb459c31524466936f50265c165c772d50ea75364b8c9c6f3e1";
    private static final String REPLACE_PLAN =
            "/legal/editorial/replace-valid-v1/editorial-plan.json";
    private static final String REPLACE_OPERATION_ID =
            "00000000-0000-0000-0000-000000000010";
    private static final String REPLACE_PLAN_SHA256 =
            "534ef5a63292484c4cde6fc2fa6735f7d42dbc40a3df671a6f9550626aebe49c";
    private static final String ONE_TO_ONE_REPLACE_PLAN =
            "/legal/editorial/replace-one-to-one-release-valid-v1/editorial-plan.json";
    private static final String ONE_TO_ONE_REPLACE_OPERATION_ID =
            "00000000-0000-0000-0000-000000000012";
    private static final String ONE_TO_ONE_REPLACE_PLAN_SHA256 =
            "00af762750acea6e03a6bb43896a4411c8f725910cc0933f225113fb39d49bd0";
    private static final String NORMAL_START_CLASS =
            "com.leonardorozza.mvgrreparacionesbackend.MvgrReparacionesBackendApplication";
    private static final String LEGAL_CLI_START_CLASS =
            "com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli.LegalManifestCli";
    private static final String PROCESS_SECRET = "ordenfix-process-secret-must-not-leak";
    private static final String JVM_OPTION_BOUNDARY_MARKER =
            "ordenfix.cli.jvm-option-boundary=verified";
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(75);
    private static final int MAX_CAPTURE_BYTES = 1_048_576;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> REPORT_FIELDS = List.of(
            "reportVersion",
            "command",
            "status",
            "persisted",
            "publication",
            "counts",
            "dryRun",
            "issues",
            "omittedIssueCount");
    private static final List<String> EDITORIAL_REPORT_FIELDS = List.of(
            "reportVersion",
            "command",
            "status",
            "persisted",
            "publication",
            "operation",
            "plan",
            "readiness",
            "counts",
            "issues",
            "omittedIssueCount");
    private static final List<String> PUBLICATION_FIELDS = List.of(
            "publicationId",
            "schemaVersion",
            "manifestSha256");
    private static final List<String> COUNT_FIELDS = List.of(
            "documents",
            "requirements",
            "scopes");
    private static final List<String> DRY_RUN_FIELDS = List.of(
            "newDocumentLines",
            "newDocumentVersions",
            "reusedDocumentVersions",
            "newRequirementLines",
            "newRequirementVersions",
            "reusedRequirementVersions");
    private static final List<String> ISSUE_FIELDS = List.of(
            "severity",
            "code",
            "location",
            "message");
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
            LegalEditorialEnvironment.ENABLED_VARIABLE);

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_manifest_cli_process")
            .withUsername("ordenfix")
            .withPassword("ordenfix");

    private static Path normalJar;
    private static Path legalCliJar;
    private static Path javaExecutable;
    private static Path buildDirectory;
    private static String buildFinalName;

    @TempDir
    private Path temporaryDirectory;

    private Path copiedManifest;

    @BeforeAll
    static void locateRequiredArtifacts() {
        Path projectDirectory = Path.of(System.getProperty("user.dir"))
                .toAbsolutePath()
                .normalize();
        Path configuredBuildDirectory = Path.of(System.getProperty(
                BUILD_DIRECTORY_PROPERTY,
                DEFAULT_BUILD_DIRECTORY));
        buildDirectory = configuredBuildDirectory.isAbsolute()
                ? configuredBuildDirectory.normalize()
                : projectDirectory.resolve(configuredBuildDirectory).normalize();
        buildFinalName = System.getProperty(
                BUILD_FINAL_NAME_PROPERTY,
                DEFAULT_BUILD_FINAL_NAME);

        normalJar = buildDirectory.resolve(buildFinalName + ".jar");
        legalCliJar = buildDirectory.resolve(buildFinalName + "-legal-cli.jar");
        javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java")
                .toAbsolutePath()
                .normalize();

        requirePackagedArtifact(normalJar, "jar normal");
        requirePackagedArtifact(legalCliJar, "jar legal-cli");
        assertThat(javaExecutable)
                .withFailMessage("No se encontró el ejecutable Java de java.home en %s", javaExecutable)
                .isRegularFile()
                .isExecutable();
    }

    @BeforeEach
    void copyGoldenFixture() throws Exception {
        Path sourceManifest = Path.of(Objects.requireNonNull(
                getClass().getResource(GOLDEN_MANIFEST)).toURI());
        Path sourceDirectory = sourceManifest.getParent();
        Path destinationDirectory = temporaryDirectory.resolve(sourceDirectory.getFileName());
        try (Stream<Path> sources = Files.walk(sourceDirectory)) {
            for (Path source : sources.toList()) {
                Path destination = destinationDirectory.resolve(sourceDirectory.relativize(source));
                if (Files.isDirectory(source)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(source, destination);
                }
            }
        }
        copiedManifest = destinationDirectory.resolve("publication-manifest.json");
        assertThat(copiedManifest).isRegularFile();
    }

    @Test
    void packagedArtifactsDeclareTheirOwnStartClassAndExcludeSecretProperties()
            throws IOException {
        assertJarContract(normalJar, NORMAL_START_CLASS);
        assertJarContract(legalCliJar, LEGAL_CLI_START_CLASS);
        assertSinglePackagedImportStartClass();
    }

    @Test
    void validateGoldenReleasePassesWithoutAnyDatabaseConfiguration() throws Exception {
        ProcessResult process = executeCli(false, "validate", copiedManifest);

        JsonNode report = assertSafeReport(process, copiedManifest, 0, "validate", "PASS");
        assertThat(report.path("publication").path("publicationId").textValue())
                .isEqualTo("release-valid-v1");
        assertThat(report.path("counts").path("documents").intValue()).isEqualTo(11);
        assertThat(report.path("counts").path("requirements").intValue()).isEqualTo(6);
        assertThat(report.path("counts").path("scopes").intValue()).isEqualTo(8);
        assertThat(report.path("dryRun").isNull()).isTrue();
        assertThat(report.path("issues")).isEmpty();
    }

    @Test
    void javaToolOptionsAreExplicitlyOutsideTheCliRedactionBoundary() throws Exception {
        ProcessResult process = executeCli(
                false,
                "validate",
                copiedManifest,
                Map.of("JAVA_TOOL_OPTIONS", "-D" + JVM_OPTION_BOUNDARY_MARKER));

        assertThat(process.exitCode()).isZero();
        assertThat(process.stdoutLimitExceeded()).isFalse();
        assertThat(process.stderrLimitExceeded()).isFalse();
        assertThat(process.stderr())
                .contains("Picked up JAVA_TOOL_OPTIONS")
                .contains(JVM_OPTION_BOUNDARY_MARKER);
        assertThat(process.stdout()).doesNotContain(JVM_OPTION_BOUNDARY_MARKER);
        JsonNode report = JSON.readTree(process.stdout());
        assertThat(report.path("command").textValue()).isEqualTo("validate");
        assertThat(report.path("status").textValue()).isEqualTo("PASS");
    }

    @Test
    void validateInvalidManifestIsBlockedWithoutAnyDatabaseConfiguration() throws Exception {
        Files.writeString(copiedManifest, "{", StandardCharsets.UTF_8);

        ProcessResult process = executeCli(false, "validate", copiedManifest);

        JsonNode report = assertSafeReport(process, copiedManifest, 2, "validate", "BLOCKED");
        assertThat(report.path("publication").isNull()).isTrue();
        assertThat(report.path("counts").isNull()).isTrue();
        assertThat(report.path("dryRun").isNull()).isTrue();
        assertThat(report.path("issues")).isNotEmpty();
        assertThat(report.path("issues").get(0).path("code").textValue())
                .isEqualTo("MANIFEST_JSON_INVALID");
    }

    @Test
    void dryRunGoldenReleaseReturnsSafeOperationalErrorWithoutDatabase() throws Exception {
        ProcessResult process = executeCli(false, "dry-run", copiedManifest);

        JsonNode report = assertSafeReport(process, copiedManifest, 3, "dry-run", "ERROR");
        assertThat(report.path("publication").path("publicationId").textValue())
                .isEqualTo("release-valid-v1");
        assertThat(report.path("counts").path("documents").intValue()).isEqualTo(11);
        assertThat(report.path("dryRun").isNull()).isTrue();
        assertThat(report.path("issues")).hasSize(1);
        assertThat(report.path("issues").get(0).path("code").textValue())
                .isEqualTo("DB_CONNECTION");
    }

    @Test
    void rawEditorialCommandUsesItsPackagedV3BoundaryBeforeLegacyParsing()
            throws Exception {
        ProcessResult process = executeCli(false, "readiness", copiedManifest);

        assertThat(process.exitCode()).isEqualTo(2);
        assertThat(process.stderr()).isEmpty();
        assertThat(process.stdout())
                .endsWith("\n")
                .doesNotEndWith("\n\n")
                .doesNotContain("\r", copiedManifest.toAbsolutePath().toString());
        JsonNode report = JSON.readTree(process.stdout());
        assertExactFields(report, EDITORIAL_REPORT_FIELDS);
        assertThat(report.path("reportVersion").intValue()).isEqualTo(3);
        assertThat(report.path("command").textValue()).isEqualTo("readiness");
        assertThat(report.path("status").textValue()).isEqualTo("BLOCKED");
        assertThat(report.path("persisted").booleanValue()).isFalse();
        assertThat(report.path("publication").isNull()).isTrue();
        assertThat(report.path("operation").isNull()).isTrue();
        assertThat(report.path("plan").isNull()).isTrue();
        assertThat(report.path("readiness").isNull()).isTrue();
        assertThat(report.path("counts").isNull()).isTrue();
        assertThat(report.path("issues")).singleElement().satisfies(issue ->
                assertThat(issue.path("code").textValue())
                        .isEqualTo("CLI_ARGUMENTS_INVALID"));
        assertThat(report.path("omittedIssueCount").intValue()).isZero();
    }

    @Test
    void packagedPlanReplaceBlocksPlanBundleMismatchBeforeEnvironment()
            throws Exception {
        Path editorialPlan = Path.of(Objects.requireNonNull(
                LegalManifestCliProcessIT.class.getResource(REPLACE_PLAN)).toURI());
        ProcessResult process = executeCli(
                false,
                List.of(
                        "plan-replace",
                        "--manifest=" + copiedManifest.toAbsolutePath(),
                        "--editorial-plan=" + editorialPlan.toAbsolutePath(),
                        "--confirm-publication-id=release-valid-v1",
                        "--confirm-manifest-sha256=" + GOLDEN_MANIFEST_SHA256,
                        "--confirm-operation-id=" + REPLACE_OPERATION_ID,
                        "--confirm-editorial-plan-sha256=" + REPLACE_PLAN_SHA256),
                Map.of());

        assertThat(process.exitCode()).isEqualTo(2);
        assertThat(process.stderr()).isEmpty();
        JsonNode report = JSON.readTree(process.stdout());
        assertExactFields(report, EDITORIAL_REPORT_FIELDS);
        assertThat(report.path("reportVersion").intValue()).isEqualTo(3);
        assertThat(report.path("command").textValue()).isEqualTo("plan-replace");
        assertThat(report.path("status").textValue()).isEqualTo("BLOCKED");
        assertThat(report.path("persisted").booleanValue()).isFalse();
        assertThat(report.path("operation").path("operationType").textValue())
                .isEqualTo("REPLACE");
        assertThat(report.path("operation").path("outcome").textValue())
                .isEqualTo("BLOCKED");
        assertThat(report.path("plan").path("operationId").textValue())
                .isEqualTo(REPLACE_OPERATION_ID);
        assertThat(report.path("plan").path("editorialPlanSha256").textValue())
                .isEqualTo(REPLACE_PLAN_SHA256);
        assertThat(report.path("plan").path("changeRequired").isNull()).isTrue();
        assertThat(report.path("plan").path("observedAt").isNull()).isTrue();
        assertThat(report.path("plan").path("expectedReadinessAfter").textValue())
                .isEqualTo("READY");
        assertThat(report.path("readiness").isNull()).isTrue();
        assertThat(report.path("counts").path("state").isNull()).isTrue();
        assertThat(report.path("counts").path("delta").isNull()).isTrue();
        assertThat(report.path("issues")).singleElement().satisfies(issue -> {
            assertThat(issue.path("code").textValue())
                    .isEqualTo("REPLACEMENT_MAPPING_INVALID");
            assertThat(issue.path("location").textValue())
                    .isEqualTo("cli/editorial/plan-binding");
        });
        assertThat(process.stdout()).doesNotContain(
                copiedManifest.toAbsolutePath().toString(),
                editorialPlan.toAbsolutePath().toString());
    }

    @Test
    void packagedPlanReplacePassesOneToOneScopeAndStopsAtEnvironmentBoundary()
            throws Exception {
        Path editorialPlan = Path.of(Objects.requireNonNull(
                LegalManifestCliProcessIT.class.getResource(ONE_TO_ONE_REPLACE_PLAN)).toURI());
        ProcessResult process = executeCli(
                false,
                List.of(
                        "plan-replace",
                        "--manifest=" + copiedManifest.toAbsolutePath(),
                        "--editorial-plan=" + editorialPlan.toAbsolutePath(),
                        "--confirm-publication-id=release-valid-v1",
                        "--confirm-manifest-sha256=" + GOLDEN_MANIFEST_SHA256,
                        "--confirm-operation-id=" + ONE_TO_ONE_REPLACE_OPERATION_ID,
                        "--confirm-editorial-plan-sha256="
                                + ONE_TO_ONE_REPLACE_PLAN_SHA256),
                Map.of());

        assertThat(process.exitCode()).isEqualTo(3);
        assertThat(process.stderr()).isEmpty();
        assertThat(process.stdout())
                .endsWith("\n")
                .doesNotEndWith("\n\n")
                .doesNotContain(
                        "\r",
                        PROCESS_SECRET,
                        copiedManifest.toAbsolutePath().toString(),
                        editorialPlan.toAbsolutePath().toString());
        JsonNode report = JSON.readTree(process.stdout());
        assertExactFields(report, EDITORIAL_REPORT_FIELDS);
        assertThat(report.path("reportVersion").intValue()).isEqualTo(3);
        assertThat(report.path("command").textValue()).isEqualTo("plan-replace");
        assertThat(report.path("status").textValue()).isEqualTo("ERROR");
        assertThat(report.path("persisted").booleanValue()).isFalse();
        assertThat(report.path("publication").path("publicationId").textValue())
                .isEqualTo("release-valid-v1");
        assertThat(report.path("publication").path("publicationUuid").isNull()).isTrue();
        assertThat(report.path("operation").path("operationType").textValue())
                .isEqualTo("REPLACE");
        assertThat(report.path("operation").path("outcome").textValue())
                .isEqualTo("ERROR");
        assertThat(report.path("plan").path("operationId").textValue())
                .isEqualTo(ONE_TO_ONE_REPLACE_OPERATION_ID);
        assertThat(report.path("plan").path("editorialPlanSha256").textValue())
                .isEqualTo(ONE_TO_ONE_REPLACE_PLAN_SHA256);
        assertThat(report.path("plan").path("changeRequired").isNull()).isTrue();
        assertThat(report.path("plan").path("observedAt").isNull()).isTrue();
        assertThat(report.path("plan").path("expectedReadinessAfter").textValue())
                .isEqualTo("READY");
        assertThat(report.path("readiness").isNull()).isTrue();
        assertThat(report.path("counts").path("state").isNull()).isTrue();
        assertThat(report.path("counts").path("delta").isNull()).isTrue();
        assertThat(report.path("issues")).singleElement().satisfies(issue -> {
            assertThat(issue.path("code").textValue())
                    .isEqualTo("EDITORIAL_DB_CONFIGURATION_INVALID");
            assertThat(issue.path("location").textValue())
                    .isEqualTo("cli/editorial/environment");
        });
        assertThat(report.path("omittedIssueCount").intValue()).isZero();
    }

    @Test
    void packagedApplyReplaceRequiresTheExplicitMutationFlagBeforeJdbc()
            throws Exception {
        Path editorialPlan = Path.of(Objects.requireNonNull(
                LegalManifestCliProcessIT.class.getResource(
                        ONE_TO_ONE_REPLACE_PLAN)).toURI());
        ProcessResult process = executeCli(
                false,
                List.of(
                        "apply-replace",
                        "--manifest=" + copiedManifest.toAbsolutePath(),
                        "--editorial-plan=" + editorialPlan.toAbsolutePath(),
                        "--confirm-publication-id=release-valid-v1",
                        "--confirm-manifest-sha256=" + GOLDEN_MANIFEST_SHA256,
                        "--confirm-operation-id=" + ONE_TO_ONE_REPLACE_OPERATION_ID,
                        "--confirm-editorial-plan-sha256="
                                + ONE_TO_ONE_REPLACE_PLAN_SHA256),
                Map.of());

        assertThat(process.exitCode()).isEqualTo(3);
        assertThat(process.stderr()).isEmpty();
        assertThat(process.stdout())
                .endsWith("\n")
                .doesNotEndWith("\n\n")
                .doesNotContain(
                        "\r",
                        PROCESS_SECRET,
                        copiedManifest.toAbsolutePath().toString(),
                        editorialPlan.toAbsolutePath().toString());
        JsonNode report = JSON.readTree(process.stdout());
        assertExactFields(report, EDITORIAL_REPORT_FIELDS);
        assertThat(report.path("reportVersion").intValue()).isEqualTo(3);
        assertThat(report.path("command").textValue()).isEqualTo("apply-replace");
        assertThat(report.path("status").textValue()).isEqualTo("ERROR");
        assertThat(report.path("persisted").booleanValue()).isFalse();
        assertThat(report.path("publication").path("publicationId").textValue())
                .isEqualTo("release-valid-v1");
        assertThat(report.path("publication").path("publicationUuid").isNull()).isTrue();
        assertThat(report.path("operation").path("operationType").textValue())
                .isEqualTo("REPLACE");
        assertThat(report.path("operation").path("outcome").textValue())
                .isEqualTo("ERROR");
        assertThat(report.path("operation").path("appliedAt").isNull()).isTrue();
        assertThat(report.path("plan").path("operationId").textValue())
                .isEqualTo(ONE_TO_ONE_REPLACE_OPERATION_ID);
        assertThat(report.path("plan").path("editorialPlanSha256").textValue())
                .isEqualTo(ONE_TO_ONE_REPLACE_PLAN_SHA256);
        assertThat(report.path("plan").path("changeRequired").isNull()).isTrue();
        assertThat(report.path("plan").path("observedAt").isNull()).isTrue();
        assertThat(report.path("plan").path("expectedReadinessAfter").textValue())
                .isEqualTo("READY");
        assertThat(report.path("readiness").isNull()).isTrue();
        assertThat(report.path("counts").path("state").isNull()).isTrue();
        assertThat(report.path("counts").path("delta").isNull()).isTrue();
        assertThat(report.path("issues")).singleElement().satisfies(issue -> {
            assertThat(issue.path("code").textValue())
                    .isEqualTo("EDITORIAL_DISABLED");
            assertThat(issue.path("location").textValue())
                    .isEqualTo("cli/editorial/environment");
        });
        assertThat(report.path("omittedIssueCount").intValue()).isZero();
    }

    @Test
    void dryRunGoldenReleasePassesAgainstMigratedPostgreSqlV27AndRollsBack()
            throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        assertThat(legalPublicationCount()).isZero();
        assertThat(legalContextSequenceWasCalled()).isFalse();

        ProcessResult process = executeCli(true, "dry-run", copiedManifest);

        JsonNode report = assertSafeReport(process, copiedManifest, 0, "dry-run", "PASS");
        assertThat(report.path("publication").path("publicationId").textValue())
                .isEqualTo("release-valid-v1");
        assertThat(report.path("counts").path("documents").intValue()).isEqualTo(11);
        assertThat(report.path("counts").path("requirements").intValue()).isEqualTo(6);
        assertThat(report.path("counts").path("scopes").intValue()).isEqualTo(8);
        assertThat(report.path("dryRun").path("newDocumentLines").intValue()).isEqualTo(11);
        assertThat(report.path("dryRun").path("newDocumentVersions").intValue()).isEqualTo(11);
        assertThat(report.path("dryRun").path("reusedDocumentVersions").intValue()).isZero();
        assertThat(report.path("dryRun").path("newRequirementLines").intValue()).isEqualTo(6);
        assertThat(report.path("dryRun").path("newRequirementVersions").intValue()).isEqualTo(6);
        assertThat(report.path("dryRun").path("reusedRequirementVersions").intValue()).isZero();
        assertThat(report.path("issues")).isEmpty();
        assertThat(legalPublicationCount()).isZero();
        assertThat(legalContextSequenceWasCalled()).isTrue();
    }

    private ProcessResult executeCli(
            boolean configureDatabase,
            String command,
            Path manifest) throws Exception {
        return executeCli(configureDatabase, command, manifest, Map.of());
    }

    private ProcessResult executeCli(
            boolean configureDatabase,
            String command,
            Path manifest,
            Map<String, String> environmentOverrides) throws Exception {
        return executeCli(
                configureDatabase,
                List.of(command, "--manifest=" + manifest.toAbsolutePath()),
                environmentOverrides);
    }

    private ProcessResult executeCli(
            boolean configureDatabase,
            List<String> arguments,
            Map<String, String> environmentOverrides) throws Exception {
        List<String> processCommand = new ArrayList<>(List.of(
                javaExecutable.toString(),
                "-jar",
                legalCliJar.toString()));
        processCommand.addAll(arguments);
        ProcessBuilder processBuilder = new ProcessBuilder(processCommand);
        processBuilder.directory(temporaryDirectory.toFile());
        processBuilder.redirectErrorStream(false);
        Map<String, String> environment = processBuilder.environment();
        ENVIRONMENT_TO_REMOVE.forEach(environment::remove);
        environment.keySet().removeIf(name -> name != null
                && name.startsWith("ORDENFIX_LEGAL_EDITOR_DB_"));
        environment.put("ORDENFIX_CLI_PROCESS_SECRET", PROCESS_SECRET);
        environment.put("LOGGING_CONFIG", "file:/private/ordenfix-hostile-logback.xml");
        environment.put("LOGGING_LEVEL_ROOT", "TRACE");
        environment.put("SPRING_CONFIG_ADDITIONAL_LOCATION", "classpath:/");
        environment.put("SPRING_CONFIG_IMPORT",
                "file:/private/ordenfix-hostile-import.properties");
        environment.put("SPRING_CONFIG_LOCATION", "classpath:/");
        environment.put("SPRING_FLYWAY_ENABLED", "true");
        environment.put("SPRING_MAIN_BANNER_MODE", "console");
        environment.put("SPRING_MAIN_KEEP_ALIVE", "true");
        environment.put("SPRING_MAIN_LOG_STARTUP_INFO", "true");
        environment.put("SPRING_MAIN_SOURCES", NORMAL_START_CLASS);
        environment.put("SPRING_MAIN_WEB_APPLICATION_TYPE", "servlet");
        if (configureDatabase) {
            environment.put("SPRING_DATASOURCE_URL", POSTGRES.getJdbcUrl());
            environment.put("SPRING_DATASOURCE_USERNAME", POSTGRES.getUsername());
            environment.put("SPRING_DATASOURCE_PASSWORD", POSTGRES.getPassword());
            environment.put("SPRING_DATASOURCE_DRIVER_CLASS_NAME", POSTGRES.getDriverClassName());
        }
        environment.putAll(environmentOverrides);

        Process process = processBuilder.start();
        ExecutorService readers = Executors.newFixedThreadPool(2);
        Future<CapturedOutput> standardOutput = readers.submit(
                () -> capture(process.getInputStream()));
        Future<CapturedOutput> standardError = readers.submit(
                () -> capture(process.getErrorStream()));
        try {
            if (!process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroy();
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(2, TimeUnit.SECONDS);
                }
                fail("El proceso legal-cli superó el timeout de %s", PROCESS_TIMEOUT);
            }
            CapturedOutput stdout = standardOutput.get(5, TimeUnit.SECONDS);
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

    private JsonNode assertSafeReport(
            ProcessResult process,
            Path manifest,
            int expectedExitCode,
            String expectedCommand,
            String expectedStatus) throws IOException {
        assertThat(process.exitCode()).isEqualTo(expectedExitCode);
        assertThat(process.stdoutLimitExceeded()).isFalse();
        assertThat(process.stderrLimitExceeded()).isFalse();
        assertThat(process.stderr()).isEmpty();
        assertThat(process.stdout())
                .endsWith("\n")
                .doesNotEndWith("\n\n")
                .doesNotContain("\r");
        String jsonDocument = process.stdout().substring(0, process.stdout().length() - 1);
        assertThat(jsonDocument).doesNotContain("\n");

        JsonNode report;
        try (JsonParser parser = JSON.getFactory().createParser(jsonDocument)) {
            assertThat(parser.nextToken()).isEqualTo(JsonToken.START_OBJECT);
            report = JSON.readTree(parser);
            assertThat(parser.nextToken()).isNull();
        }
        assertThat(report.isObject()).isTrue();
        assertExactFields(report, REPORT_FIELDS);
        assertThat(report.path("reportVersion").intValue()).isEqualTo(1);
        assertThat(report.path("command").textValue()).isEqualTo(expectedCommand);
        assertThat(report.path("status").textValue()).isEqualTo(expectedStatus);
        assertThat(report.path("persisted").booleanValue()).isFalse();
        assertThat(report.path("omittedIssueCount").intValue()).isZero();
        if (!report.path("publication").isNull()) {
            assertExactFields(report.path("publication"), PUBLICATION_FIELDS);
        }
        if (!report.path("counts").isNull()) {
            assertExactFields(report.path("counts"), COUNT_FIELDS);
        }
        if (!report.path("dryRun").isNull()) {
            assertExactFields(report.path("dryRun"), DRY_RUN_FIELDS);
        }
        assertThat(report.path("issues").isArray()).isTrue();
        for (JsonNode issue : report.path("issues")) {
            assertExactFields(issue, ISSUE_FIELDS);
            assertThat(issue.path("severity").textValue()).isIn("BLOCKED", "ERROR");
            assertThat(issue.path("code").isTextual()).isTrue();
            assertThat(issue.path("message").isTextual()).isTrue();
            String location = issue.path("location").textValue();
            assertThat(location)
                    .isNotBlank()
                    .matches("[A-Za-z0-9_./,:#~-]+")
                    .doesNotStartWith("/")
                    .doesNotStartWith("\\")
                    .doesNotContain("\\", "://");
            assertThat(List.of(location.split("/", -1))).doesNotContain("..");
        }

        assertThat(process.stdout()).doesNotContain(
                manifest.toAbsolutePath().toString(),
                temporaryDirectory.toAbsolutePath().toString(),
                PROCESS_SECRET,
                POSTGRES.getPassword(),
                POSTGRES.getJdbcUrl(),
                "application-secret.properties",
                "OrdenFix Argentina SAS",
                "30-00000000-0",
                "Calle Pública 100",
                "legal@ordenfix.com",
                "privacidad@ordenfix.com",
                "soporte@ordenfix.com",
                "# Términos",
                "SELECT ",
                "Exception",
                "stacktrace",
                "Spring Boot",
                "Started ");
        return report;
    }

    private static void assertExactFields(JsonNode object, List<String> expectedFields) {
        assertThat(object.isObject()).isTrue();
        List<String> fieldNames = new ArrayList<>();
        object.fieldNames().forEachRemaining(fieldNames::add);
        assertThat(fieldNames).containsExactlyElementsOf(expectedFields);
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

    private static void assertJarContract(Path jarPath, String expectedStartClass)
            throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            assertThat(jar.getManifest())
                    .withFailMessage("El artefacto %s no contiene MANIFEST.MF", jarPath)
                    .isNotNull();
            assertThat(jar.getManifest().getMainAttributes().getValue("Start-Class"))
                    .as("Start-Class de %s", jarPath.getFileName())
                    .isEqualTo(expectedStartClass);
            assertThat(jar.stream()
                    .map(entry -> entry.getName())
                    .filter(LegalManifestCliProcessIT::isSecretProperties)
                    .toList())
                    .as("application-secret.properties dentro de %s", jarPath.getFileName())
                    .isEmpty();
        }
    }

    private static void assertSinglePackagedImportStartClass() throws IOException {
        List<Path> importArtifacts = new ArrayList<>();
        try (Stream<Path> candidates = Files.list(buildDirectory)) {
            for (Path candidate : candidates
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .sorted()
                    .toList()) {
                try (JarFile jar = new JarFile(candidate.toFile())) {
                    if (jar.getManifest() != null
                            && LEGAL_CLI_START_CLASS.equals(jar.getManifest()
                                    .getMainAttributes()
                                    .getValue("Start-Class"))) {
                        importArtifacts.add(candidate.toAbsolutePath().normalize());
                    }
                }
            }
        }
        assertThat(importArtifacts)
                .as("artefactos cuyo Start-Class expone el importador legal")
                .containsExactly(legalCliJar.toAbsolutePath().normalize());
    }

    private static boolean isSecretProperties(String entryName) {
        return entryName.equals("application-secret.properties")
                || entryName.endsWith("/application-secret.properties");
    }

    private static long legalPublicationCount() throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT count(*) FROM legal_publicaciones")) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }

    private static boolean legalContextSequenceWasCalled() throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
             var statement = connection.createStatement();
             var result = statement.executeQuery(
                     "SELECT is_called FROM legal_documento_contextos_id_seq")) {
            assertThat(result.next()).isTrue();
            return result.getBoolean(1);
        }
    }

    private static void requirePackagedArtifact(Path artifact, String description) {
        assertThat(artifact)
                .withFailMessage(
                        "No se encontró el %s en %s. Ejecute `./mvnw package` o "
                                + "`./mvnw verify` para construir ambos artefactos antes de esta IT; "
                                + "si usa rutas personalizadas, defina -D%s y -D%s.",
                        description,
                        artifact,
                        BUILD_DIRECTORY_PROPERTY,
                        BUILD_FINAL_NAME_PROPERTY)
                .isRegularFile();
    }

    private record CapturedOutput(byte[] bytes, boolean limitExceeded) { }

    private record ProcessResult(
            int exitCode,
            String stdout,
            String stderr,
            boolean stdoutLimitExceeded,
            boolean stderrLimitExceeded
    ) { }
}
