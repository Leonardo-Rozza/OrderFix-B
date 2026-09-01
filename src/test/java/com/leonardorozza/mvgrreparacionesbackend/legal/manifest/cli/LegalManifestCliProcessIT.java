package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli.LegalCliProcessSupport.Artifacts;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli.LegalCliProcessSupport.ProcessResult;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli.LegalCliProcessSupport.StdoutMode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialPlanValidator;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class LegalManifestCliProcessIT {

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
    private static final String RETIRE_PLAN =
            "/legal/editorial/retire-valid-v1/editorial-plan.json";
    private static final String RETIRE_OPERATION_ID =
            "00000000-0000-0000-0000-000000000011";
    private static final String RETIRE_PLAN_SHA256 =
            "603780426e4e9729375432bc7bed8547ef03cdeb09e88d27abc41774c0a2ea15";
    private static final String NORMAL_START_CLASS =
            "com.leonardorozza.mvgrreparacionesbackend.MvgrReparacionesBackendApplication";
    private static final String LEGAL_CLI_START_CLASS =
            "com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli.LegalManifestCli";
    private static final String PROCESS_SECRET = "ordenfix-process-secret-must-not-leak";
    private static final String JVM_OPTION_BOUNDARY_MARKER =
            "ordenfix.cli.jvm-option-boundary=verified";
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(75);
    private static final String STDOUT_FAILURE_AGENT_CLASS_PATH =
            "com/leonardorozza/mvgrreparacionesbackend/legal/manifest/cli/"
                    + "LegalCliStdoutFailureAgent";
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
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_manifest_cli_process")
            .withUsername("ordenfix")
            .withPassword("ordenfix");

    private static Artifacts artifacts;

    @TempDir
    private Path temporaryDirectory;

    private Path copiedManifest;
    private Path copiedRetirePlan;

    @BeforeAll
    static void locateRequiredArtifacts() {
        artifacts = LegalCliProcessSupport.locateArtifacts();
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
        assertJarContract(artifacts.normalJar(), NORMAL_START_CLASS);
        assertJarContract(artifacts.legalCliJar(), LEGAL_CLI_START_CLASS);
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
    void packagedPlanRetireReachesTheEnvironmentBoundaryWithoutReplaceScopeInterception()
            throws Exception {
        ProcessResult process = executeCli(
                false,
                retireArguments("plan-retire"),
                Map.of());

        JsonNode report = assertSafeRetireBoundaryReport(
                process,
                "plan-retire",
                "EDITORIAL_DB_CONFIGURATION_INVALID");
        assertThat(report.path("persisted").booleanValue()).isFalse();
        assertThat(report.path("operation").path("operationType").textValue())
                .isEqualTo("RETIRE");
        assertThat(report.path("operation").path("outcome").textValue())
                .isEqualTo("ERROR");
        assertThat(report.path("operation").path("appliedAt").isNull()).isTrue();
        assertConfirmedRetirePlanIdentity(report);
    }

    @Test
    void packagedApplyRetireRequiresTheExistingMutationFlagBeforeJdbc()
            throws Exception {
        ProcessResult process = executeCli(
                false,
                retireArguments("apply-retire"),
                Map.of());

        JsonNode report = assertSafeRetireBoundaryReport(
                process,
                "apply-retire",
                "EDITORIAL_DISABLED");
        assertThat(report.path("persisted").booleanValue()).isFalse();
        assertThat(report.path("operation").path("operationType").textValue())
                .isEqualTo("RETIRE");
        assertThat(report.path("operation").path("outcome").textValue())
                .isEqualTo("ERROR");
        assertThat(report.path("operation").path("appliedAt").isNull()).isTrue();
        assertConfirmedRetirePlanIdentity(report);
    }

    @Test
    void dryRunGoldenReleasePassesAgainstMigratedPostgreSqlV27AndRollsBack()
            throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target("27")
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
        Map<String, String> environment = new LinkedHashMap<>();
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

        ProcessResult result = LegalCliProcessSupport.executeJar(
                artifacts,
                temporaryDirectory,
                List.of(),
                arguments,
                environment,
                StdoutMode.CAPTURE,
                PROCESS_TIMEOUT);
        assertThat(result.timedOut())
                .as("watchdog del proceso legal-cli")
                .isFalse();
        return result;
    }

    private List<String> retireArguments(String command) throws Exception {
        prepareRetirePlan();
        return List.of(
                command,
                "--manifest=" + copiedManifest.toAbsolutePath(),
                "--editorial-plan=" + copiedRetirePlan.toAbsolutePath(),
                "--confirm-publication-id=release-valid-v1",
                "--confirm-manifest-sha256=" + GOLDEN_MANIFEST_SHA256,
                "--confirm-operation-id=" + RETIRE_OPERATION_ID,
                "--confirm-editorial-plan-sha256=" + RETIRE_PLAN_SHA256);
    }

    private void prepareRetirePlan() throws Exception {
        Path sourceRetirePlan = Path.of(Objects.requireNonNull(
                getClass().getResource(RETIRE_PLAN)).toURI());
        ObjectNode retirePlan = (ObjectNode) JSON.readTree(sourceRetirePlan.toFile());
        retirePlan.put("expectedCurrentPublicationId", "release-valid-v1");
        retirePlan.put("expectedCurrentManifestSha256", GOLDEN_MANIFEST_SHA256);
        retirePlan.put("targetPublicationId", "release-valid-v1");
        retirePlan.put("targetManifestSha256", GOLDEN_MANIFEST_SHA256);
        Path retirePlanDirectory = temporaryDirectory.toRealPath()
                .resolve("retire-plan-release-valid-v1");
        Files.createDirectories(retirePlanDirectory);
        copiedRetirePlan = retirePlanDirectory.resolve("editorial-plan.json");
        Files.writeString(
                copiedRetirePlan,
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(retirePlan) + '\n',
                StandardCharsets.UTF_8);

        var validation = new LegalEditorialPlanValidator().validate(copiedRetirePlan);
        assertThat(validation.passed())
                .as("issues=%s", validation.issues())
                .isTrue();
        var validatedRetirePlan = validation.value().orElseThrow();
        assertThat(validatedRetirePlan.operationId().toString())
                .isEqualTo(RETIRE_OPERATION_ID);
        assertThat(validatedRetirePlan.editorialPlanSha256())
                .isEqualTo(RETIRE_PLAN_SHA256);
    }

    private JsonNode assertSafeRetireBoundaryReport(
            ProcessResult process,
            String expectedCommand,
            String expectedIssueCode) throws IOException {
        assertThat(process.exitCode()).isEqualTo(3);
        assertThat(process.stdoutLimitExceeded()).isFalse();
        assertThat(process.stderrLimitExceeded()).isFalse();
        assertThat(process.stderr()).isEmpty();
        assertThat(process.stdout())
                .endsWith("\n")
                .doesNotEndWith("\n\n")
                .doesNotContain(
                        "\r",
                        PROCESS_SECRET,
                        copiedManifest.toAbsolutePath().toString(),
                        copiedRetirePlan.toAbsolutePath().toString(),
                        temporaryDirectory.toAbsolutePath().toString(),
                        POSTGRES.getPassword(),
                        POSTGRES.getJdbcUrl(),
                        "/private/ordenfix-hostile-logback.xml",
                        "/private/ordenfix-hostile-import.properties",
                        "SELECT ",
                        "Exception",
                        "stacktrace",
                        "Spring Boot",
                        "Started ");
        String jsonDocument = process.stdout().substring(0, process.stdout().length() - 1);
        assertThat(jsonDocument).doesNotContain("\n");

        JsonNode report = JSON.readTree(jsonDocument);
        assertExactFields(report, EDITORIAL_REPORT_FIELDS);
        assertThat(report.path("reportVersion").intValue()).isEqualTo(3);
        assertThat(report.path("command").textValue()).isEqualTo(expectedCommand);
        assertThat(report.path("status").textValue()).isEqualTo("ERROR");
        assertThat(report.path("publication").path("publicationId").textValue())
                .isEqualTo("release-valid-v1");
        assertThat(report.path("publication").path("publicationUuid").isNull()).isTrue();
        assertThat(report.path("readiness").isNull()).isTrue();
        assertThat(report.path("counts").path("state").isNull()).isTrue();
        assertThat(report.path("counts").path("delta").isNull()).isTrue();
        assertThat(report.path("issues")).singleElement().satisfies(issue -> {
            assertThat(issue.path("code").textValue()).isEqualTo(expectedIssueCode);
            assertThat(issue.path("location").textValue())
                    .isEqualTo("cli/editorial/environment");
        });
        assertThat(report.path("omittedIssueCount").intValue()).isZero();
        return report;
    }

    private void assertConfirmedRetirePlanIdentity(JsonNode report) {
        assertThat(report.path("plan").path("operationId").textValue())
                .isEqualTo(RETIRE_OPERATION_ID);
        assertThat(report.path("plan").path("editorialPlanSha256").textValue())
                .isEqualTo(RETIRE_PLAN_SHA256);
        assertThat(report.path("plan").path("changeRequired").isNull()).isTrue();
        assertThat(report.path("plan").path("observedAt").isNull()).isTrue();
        assertThat(report.path("plan").path("expectedReadinessAfter").textValue())
                .isEqualTo("NOT_READY");
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

    private static void assertJarContract(Path jarPath, String expectedStartClass)
            throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            assertThat(jar.getManifest())
                    .withFailMessage("El artefacto %s no contiene MANIFEST.MF", jarPath)
                    .isNotNull();
            var attributes = jar.getManifest().getMainAttributes();
            assertThat(attributes.getValue("Start-Class"))
                    .as("Start-Class de %s", jarPath.getFileName())
                    .isEqualTo(expectedStartClass);
            assertThat(attributes.getValue("Premain-Class"))
                    .as("Premain-Class de %s", jarPath.getFileName())
                    .isNull();
            assertThat(attributes.getValue("Agent-Class"))
                    .as("Agent-Class de %s", jarPath.getFileName())
                    .isNull();
            assertThat(attributes.getValue("Launcher-Agent-Class"))
                    .as("Launcher-Agent-Class de %s", jarPath.getFileName())
                    .isNull();
            assertThat(jar.stream()
                    .map(entry -> entry.getName())
                    .filter(LegalManifestCliProcessIT::isSecretProperties)
                    .toList())
                    .as("application-secret.properties dentro de %s", jarPath.getFileName())
                    .isEmpty();
            assertThat(jar.stream()
                    .map(entry -> entry.getName())
                    .filter(entryName -> entryName.endsWith(
                                    STDOUT_FAILURE_AGENT_CLASS_PATH + ".class")
                            || entryName.contains(
                                    STDOUT_FAILURE_AGENT_CLASS_PATH + "$"))
                    .toList())
                    .as("agente test-only dentro de %s", jarPath.getFileName())
                    .isEmpty();
        }
    }

    private static void assertSinglePackagedImportStartClass() throws IOException {
        List<Path> importArtifacts = new ArrayList<>();
        try (Stream<Path> candidates = Files.list(artifacts.buildDirectory())) {
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
                .containsExactly(artifacts.legalCliJar().toAbsolutePath().normalize());
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

}
