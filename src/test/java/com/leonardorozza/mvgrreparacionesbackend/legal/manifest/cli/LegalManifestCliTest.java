package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidation;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalDryRunDatabaseConfiguration;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalImportDatabaseConfiguration;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestDryRunService;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestImportResult;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestImportResult.Outcome;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestImportResult.Receipt;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestImportService;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestImportService.ImportExecutionObserver;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LegalManifestCliTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String GOLDEN_MANIFEST =
            "/legal/manifest/release-valid-v1/publication-manifest.json";
    private static final String GOLDEN_SHA256 =
            "b3b452c8f6f4deb459c31524466936f50265c165c772d50ea75364b8c9c6f3e1";
    private static final UUID PUBLICATION_UUID =
            UUID.fromString("60870649-efbb-4d2b-8407-4f1901b86a45");
    private static final Instant IMPORTED_AT =
            Instant.parse("2026-08-25T18:00:00.123456Z");
    private static final Instant SEALED_AT =
            Instant.parse("2026-08-25T18:00:01.654321Z");

    private final LegalManifestCli cli = new LegalManifestCli();

    @Test
    void commandBoundaryTerminatesItsSingleCompactJsonReportWithOneLf() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exitCode = cli.runSafely(new String[0], output);

        assertThat(exitCode).isEqualTo(2);
        String stdout = output.toString(StandardCharsets.UTF_8);
        assertThat(stdout).endsWith("\n").doesNotEndWith("\n\n").doesNotContain("\r");
        String jsonDocument = stdout.substring(0, stdout.length() - 1);
        assertThat(jsonDocument).doesNotContain("\n");
        JsonNode report;
        try (JsonParser parser = JSON.getFactory().createParser(jsonDocument)) {
            assertThat(parser.nextToken()).isEqualTo(JsonToken.START_OBJECT);
            report = JSON.readTree(parser);
            assertThat(parser.nextToken()).isNull();
        }
        assertThat(report.path("command").isNull()).isTrue();
        assertThat(report.path("status").textValue()).isEqualTo("BLOCKED");
        assertThat(report.path("persisted").booleanValue()).isFalse();
        assertThat(report.path("issues").get(0).path("code").textValue())
                .isEqualTo("CLI_ARGUMENTS_INVALID");
    }

    @Test
    void brokenStandardOutputReturnsTheStableOperationalExitCodeWithoutThrowing() {
        OutputStream brokenOutput = new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                throw new IOException("private output failure");
            }
        };

        int exitCode = cli.runSafely(new String[0], brokenOutput);

        assertThat(exitCode).isEqualTo(3);
    }

    @Test
    void printStreamThatSwallowsItsWriteFailureStillReturnsTheOperationalExitCode() {
        OutputStream brokenOutput = new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                throw new IOException("private output failure");
            }
        };
        PrintStream swallowingOutput = new PrintStream(brokenOutput, true, StandardCharsets.UTF_8);

        int exitCode = cli.runSafely(new String[0], swallowingOutput);

        assertThat(exitCode).isEqualTo(3);
        assertThat(swallowingOutput.checkError()).isTrue();
    }

    @Test
    void unexpectedInternalFailureBecomesAConstantSafeEnvelope() throws Exception {
        LegalManifestValidator failingValidator = mock(LegalManifestValidator.class);
        when(failingValidator.validate(any(Path.class)))
                .thenThrow(new IllegalStateException("private-path-and-secret"));
        LegalManifestCli failingCli = new LegalManifestCli(
                failingValidator,
                new LegalManifestReportWriter());
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exitCode = failingCli.runSafely(
                new String[]{"validate", "--manifest=publication-manifest.json"},
                output);

        assertThat(exitCode).isEqualTo(3);
        String stdout = output.toString(StandardCharsets.UTF_8);
        assertThat(stdout).endsWith("\n").doesNotContain("private-path-and-secret");
        JsonNode report = JSON.readTree(stdout);
        assertThat(report.path("command").isNull()).isTrue();
        assertThat(report.path("status").textValue()).isEqualTo("ERROR");
        assertThat(report.path("issues")).singleElement().satisfies(issue ->
                assertThat(issue.path("code").textValue()).isEqualTo("CLI_OPERATION_FAILED"));
    }

    @Test
    void constructorFailureAtMainBoundaryUsesThePreSerializedSafeEnvelope() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exitCode = LegalManifestCli.runMain(
                new String[]{"validate", "--manifest=publication-manifest.json"},
                output,
                () -> {
                    throw new ExceptionInInitializerError("private-constructor-secret");
                });
        ByteArrayOutputStream expectedOutput = new ByteArrayOutputStream();
        new LegalManifestReportWriter().write(
                LegalManifestReport.forUnexpectedFailure(),
                expectedOutput);
        expectedOutput.write('\n');

        assertThat(exitCode).isEqualTo(3);
        String stdout = output.toString(StandardCharsets.UTF_8);
        assertThat(stdout)
                .isEqualTo(expectedOutput.toString(StandardCharsets.UTF_8))
                .endsWith("\n")
                .doesNotEndWith("\n\n")
                .doesNotContain("private-constructor-secret");
        JsonNode report = JSON.readTree(stdout);
        assertThat(report.path("command").isNull()).isTrue();
        assertThat(report.path("status").textValue()).isEqualTo("ERROR");
        assertThat(report.path("issues")).singleElement().satisfies(issue ->
                assertThat(issue.path("code").textValue()).isEqualTo("CLI_OPERATION_FAILED"));
    }

    @Test
    void rawImportIsV2EvenWhenTheCliFactoryFailsBeforeConstruction() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exitCode = LegalManifestCli.runMain(
                validImportArguments(),
                output,
                () -> {
                    throw new ExceptionInInitializerError("private-import-constructor-secret");
                });

        assertThat(exitCode).isEqualTo(3);
        JsonNode report = assertSingleImportReport(output);
        assertThat(report.path("status").textValue()).isEqualTo("ERROR");
        assertThat(report.path("persisted").booleanValue()).isFalse();
        assertThat(report.path("publication").isNull()).isTrue();
        assertThat(report.path("import").isNull()).isTrue();
        assertThat(report.path("issues").get(0).path("code").textValue())
                .isEqualTo("IMPORT_CLI_OPERATION_FAILED");
        assertThat(output.toString(StandardCharsets.UTF_8))
                .doesNotContain("private-import-constructor-secret");
    }

    @Test
    void invalidImportArgumentsNeverValidateTheBundleOrResolveDatabaseConfiguration()
            throws Exception {
        LegalManifestValidator validator = mock(LegalManifestValidator.class);
        AtomicBoolean environmentRead = new AtomicBoolean();
        LegalManifestCli importCli = importCli(
                validator,
                new LegalManifestImportReportWriter(),
                () -> {
                    environmentRead.set(true);
                    throw new AssertionError("No debe resolverse el entorno");
                },
                environment -> {
                    throw new AssertionError("No debe abrirse la base");
                });
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exitCode = importCli.runSafely(
                new String[]{"import", "--manifest=publication-manifest.json"},
                output);

        assertThat(exitCode).isEqualTo(2);
        JsonNode report = assertSingleImportReport(output);
        assertThat(report.path("status").textValue()).isEqualTo("BLOCKED");
        assertThat(report.path("issues").get(0).path("code").textValue())
                .isEqualTo("CLI_ARGUMENTS_INVALID");
        assertThat(environmentRead).isFalse();
        verifyNoInteractions(validator);
    }

    @Test
    void confirmationMismatchStopsBeforeEnvironmentAndDatabase() throws Exception {
        AtomicBoolean environmentRead = new AtomicBoolean();
        LegalManifestCli importCli = importCli(
                new LegalManifestValidator(),
                new LegalManifestImportReportWriter(),
                () -> {
                    environmentRead.set(true);
                    throw new AssertionError("No debe resolverse el entorno");
                },
                environment -> {
                    throw new AssertionError("No debe abrirse la base");
                });
        String[] arguments = validImportArguments();
        arguments[2] = "--confirm-publication-id=private-wrong-release";
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exitCode = importCli.runSafely(arguments, output);

        assertThat(exitCode).isEqualTo(2);
        JsonNode report = assertSingleImportReport(output);
        assertThat(report.path("publication").path("publicationId").textValue())
                .isEqualTo("release-valid-v1");
        assertThat(report.path("persisted").booleanValue()).isFalse();
        assertThat(report.path("issues").get(0).path("code").textValue())
                .isEqualTo("IMPORT_CONFIRMATION_MISMATCH");
        assertThat(environmentRead).isFalse();
        assertThat(output.toString(StandardCharsets.UTF_8))
                .doesNotContain("private-wrong-release");
    }

    @Test
    void disabledImportKeepsValidatedMetadataAndNeverOpensDatabase() throws Exception {
        AtomicBoolean databaseOpened = new AtomicBoolean();
        LegalManifestCli importCli = importCli(
                new LegalManifestValidator(),
                new LegalManifestImportReportWriter(),
                () -> LegalManifestValidation.failure(LegalManifestIssue.at(
                        LegalManifestIssueCode.IMPORT_DISABLED,
                        "cli/import/environment")),
                environment -> {
                    databaseOpened.set(true);
                    throw new AssertionError("No debe abrirse la base");
                });
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exitCode = importCli.runSafely(validImportArguments(), output);

        assertThat(exitCode).isEqualTo(3);
        JsonNode report = assertSingleImportReport(output);
        assertThat(report.path("persisted").booleanValue()).isFalse();
        assertThat(report.path("publication").path("manifestSha256").textValue())
                .isEqualTo(GOLDEN_SHA256);
        assertThat(report.path("issues").get(0).path("code").textValue())
                .isEqualTo("IMPORT_DISABLED");
        assertThat(databaseOpened).isFalse();
    }

    @Test
    void realImportContextCopiesOnlyAccreditedDatasourceProperties() {
        Map<String, String> processEnvironment = new LinkedHashMap<>();
        processEnvironment.put(LegalImportEnvironment.ENABLED_VARIABLE, "true");
        processEnvironment.put(
                LegalImportEnvironment.URL_VARIABLE,
                "jdbc:h2:mem:legal_import_cli_context;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        processEnvironment.put(LegalImportEnvironment.USERNAME_VARIABLE, "sa");
        processEnvironment.put(LegalImportEnvironment.PASSWORD_VARIABLE, "private-h2-password");
        processEnvironment.put(LegalImportEnvironment.DRIVER_VARIABLE, "org.h2.Driver");
        processEnvironment.put("SPRING_DATASOURCE_URL", "jdbc:private-hostile");
        processEnvironment.put("SPRING_DATASOURCE_PASSWORD", "private-hostile-password");
        processEnvironment.put("SPRING_CONFIG_IMPORT", "file:/private/hostile.properties");
        LegalImportEnvironment environment = LegalImportEnvironment
                .resolve(processEnvironment, new Properties())
                .value()
                .orElseThrow();
        Map<String, String> hostileSystemProperties = Map.of(
                "spring.datasource.url", "jdbc:private-system-hostile",
                "spring.datasource.password", "private-system-password",
                LegalImportDatabaseConfiguration.ENABLED_PROPERTY, "false",
                LegalDryRunDatabaseConfiguration.ENABLED_PROPERTY, "true",
                "spring.config.import", "file:/private/system-hostile.properties",
                "ordenfix.private.canary", "private-system-canary");
        Map<String, String> previousSystemProperties = replaceSystemProperties(
                hostileSystemProperties);
        HikariDataSource pool = null;

        try {
            try (ConfigurableApplicationContext context =
                         LegalManifestCli.openImportContext(environment)) {
                assertThat(context.getEnvironment().getProperty(
                        LegalImportDatabaseConfiguration.ENABLED_PROPERTY)).isEqualTo("true");
                assertThat(context.getEnvironment().getProperty(
                        LegalDryRunDatabaseConfiguration.ENABLED_PROPERTY)).isNull();
                assertThat(context.getEnvironment().getProperty("spring.datasource.url"))
                        .startsWith("jdbc:h2:mem:legal_import_cli_context");
                assertThat(context.getEnvironment().getProperty("spring.datasource.username"))
                        .isEqualTo("sa");
                assertThat(context.getEnvironment().getProperty(
                        "spring.datasource.driver-class-name")).isEqualTo("org.h2.Driver");
                assertThat(context.getEnvironment().getProperty("spring.config.import")).isNull();
                assertThat(context.getEnvironment().getProperty("ordenfix.private.canary")).isNull();
                assertThat(context.getEnvironment().getProperty("spring.flyway.enabled"))
                        .isEqualTo("false");

                assertThat(context.getBeansOfType(DataSource.class)).hasSize(1);
                DataSource dataSource = context.getBean(DataSource.class);
                assertThat(dataSource).isInstanceOf(HikariDataSource.class);
                pool = (HikariDataSource) dataSource;
                assertThat(context.getBeansOfType(LegalManifestImportService.class)).hasSize(1);
                assertThat(context.getBeansOfType(LegalManifestDryRunService.class)).isEmpty();
                assertThat(context.getBeansOfType(Flyway.class)).isEmpty();
            }
        } finally {
            restoreSystemProperties(previousSystemProperties);
        }

        assertThat(pool).isNotNull();
        assertThat(pool.isClosed()).isTrue();
    }

    @Test
    void confirmedImportEmitsPassV2AndClosesItsContext() throws Exception {
        LegalManifestImportService service = mock(LegalManifestImportService.class);
        LegalManifestImportResult result = importedResult();
        stubImportResult(service, result);
        ConfigurableApplicationContext context = importContext(service);
        LegalManifestCli importCli = importCli(
                new LegalManifestValidator(),
                new LegalManifestImportReportWriter(),
                LegalManifestCliTest::enabledEnvironment,
                environment -> context);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exitCode = importCli.runSafely(validImportArguments(), output);

        assertThat(exitCode).isZero();
        JsonNode report = assertSingleImportReport(output);
        assertThat(report.path("status").textValue()).isEqualTo("PASS");
        assertThat(report.path("persisted").booleanValue()).isTrue();
        assertThat(report.path("import").path("outcome").textValue())
                .isEqualTo("IMPORTED");
        assertThat(report.path("import").path("publicationUuid").textValue())
                .isEqualTo(PUBLICATION_UUID.toString());
        verify(service).importManifest(any(), any());
        verify(context).close();
    }

    @Test
    void unexpectedFailureBeforeServiceInvocationRemainsKnownNotPersisted()
            throws Exception {
        ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
        when(context.getBean(LegalManifestImportService.class))
                .thenThrow(new IllegalStateException("private-get-bean-secret"));
        LegalManifestCli importCli = importCli(
                new LegalManifestValidator(),
                new LegalManifestImportReportWriter(),
                LegalManifestCliTest::enabledEnvironment,
                environment -> context);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exitCode = importCli.runSafely(validImportArguments(), output);

        assertThat(exitCode).isEqualTo(3);
        JsonNode report = assertSingleImportReport(output);
        assertThat(report.path("persisted").booleanValue()).isFalse();
        assertThat(report.path("import").isNull()).isTrue();
        assertThat(report.path("issues").get(0).path("code").textValue())
                .isEqualTo("IMPORT_CLI_OPERATION_FAILED");
        assertThat(output.toString(StandardCharsets.UTF_8))
                .doesNotContain("private-get-bean-secret");
        verify(context).close();
    }

    @Test
    void knownServiceFailureBeforeTheCallbackPreservesItsSpecificIssue() throws Exception {
        LegalManifestImportService service = mock(LegalManifestImportService.class);
        LegalManifestImportResult result = knownFailureResult(
                LegalManifestIssueCode.IMPORT_DB_OPERATION_FAILED);
        when(service.importManifest(any(), any())).thenReturn(result);
        ConfigurableApplicationContext context = importContext(service);
        LegalManifestCli importCli = importCli(
                new LegalManifestValidator(),
                new LegalManifestImportReportWriter(),
                LegalManifestCliTest::enabledEnvironment,
                environment -> context);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exitCode = importCli.runSafely(validImportArguments(), output);

        assertThat(exitCode).isEqualTo(3);
        JsonNode report = assertSingleImportReport(output);
        assertThat(report.path("persisted").booleanValue()).isFalse();
        assertThat(report.path("import").isNull()).isTrue();
        assertThat(report.path("issues").get(0).path("code").textValue())
                .isEqualTo("IMPORT_DB_OPERATION_FAILED");
        verify(context).close();
    }

    @Test
    void unexpectedFailureAfterServiceInvocationIsReportedAsUnknown() throws Exception {
        LegalManifestImportService service = mock(LegalManifestImportService.class);
        when(service.importManifest(any(), any())).thenAnswer(invocation -> {
            ImportExecutionObserver observer = invocation.getArgument(1);
            observer.callbackStarted();
            throw new IllegalStateException("private-service-secret");
        });
        ConfigurableApplicationContext context = importContext(service);
        LegalManifestCli importCli = importCli(
                new LegalManifestValidator(),
                new LegalManifestImportReportWriter(),
                LegalManifestCliTest::enabledEnvironment,
                environment -> context);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exitCode = importCli.runSafely(validImportArguments(), output);

        assertThat(exitCode).isEqualTo(3);
        JsonNode report = assertSingleImportReport(output);
        assertThat(report.path("persisted").isNull()).isTrue();
        assertThat(report.path("import").path("outcome").textValue())
                .isEqualTo("UNKNOWN");
        assertThat(report.path("issues").get(0).path("code").textValue())
                .isEqualTo("IMPORT_DB_COMMIT_UNKNOWN");
        assertThat(output.toString(StandardCharsets.UTF_8))
                .doesNotContain("private-service-secret");
        verify(context).close();
    }

    @Test
    void contextCloseFailureAfterConfirmedCommitPreservesPassAndReceipt() throws Exception {
        LegalManifestImportService service = mock(LegalManifestImportService.class);
        LegalManifestImportResult result = importedResult();
        stubImportResult(service, result);
        ConfigurableApplicationContext context = importContext(service);
        doThrow(new IllegalStateException("private-close-secret"))
                .when(context)
                .close();
        LegalManifestCli importCli = importCli(
                new LegalManifestValidator(),
                new LegalManifestImportReportWriter(),
                LegalManifestCliTest::enabledEnvironment,
                environment -> context);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exitCode = importCli.runSafely(validImportArguments(), output);

        assertThat(exitCode).isZero();
        JsonNode report = assertSingleImportReport(output);
        assertThat(report.path("persisted").booleanValue()).isTrue();
        assertThat(report.path("import").path("outcome").textValue())
                .isEqualTo("IMPORTED");
        assertThat(output.toString(StandardCharsets.UTF_8))
                .doesNotContain("private-close-secret");
    }

    @Test
    void serializerFailureAfterConfirmedCommitUsesFreshWriterWithoutLosingReceipt()
            throws Exception {
        LegalManifestImportReportWriter failingWriter =
                mock(LegalManifestImportReportWriter.class);
        doThrow(new IOException("private-writer-secret"))
                .when(failingWriter)
                .write(any(), any());
        LegalManifestImportService service = mock(LegalManifestImportService.class);
        LegalManifestImportResult result = importedResult();
        stubImportResult(service, result);
        ConfigurableApplicationContext context = importContext(service);
        LegalManifestCli importCli = importCli(
                new LegalManifestValidator(),
                failingWriter,
                LegalManifestCliTest::enabledEnvironment,
                environment -> context);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exitCode = importCli.runSafely(validImportArguments(), output);

        assertThat(exitCode).isZero();
        JsonNode report = assertSingleImportReport(output);
        assertThat(report.path("persisted").booleanValue()).isTrue();
        assertThat(report.path("import").path("publicationUuid").textValue())
                .isEqualTo(PUBLICATION_UUID.toString());
        assertThat(output.toString(StandardCharsets.UTF_8))
                .doesNotContain("private-writer-secret");
    }

    @Test
    void brokenStdoutAfterConfirmedImportReturnsExitThreeWithoutAnotherChannel()
            throws Exception {
        LegalManifestImportService service = mock(LegalManifestImportService.class);
        LegalManifestImportResult result = importedResult();
        stubImportResult(service, result);
        ConfigurableApplicationContext context = importContext(service);
        LegalManifestCli importCli = importCli(
                new LegalManifestValidator(),
                new LegalManifestImportReportWriter(),
                LegalManifestCliTest::enabledEnvironment,
                environment -> context);
        OutputStream brokenOutput = new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                throw new IOException("private-stdout-secret");
            }
        };

        int exitCode = importCli.runSafely(validImportArguments(), brokenOutput);

        assertThat(exitCode).isEqualTo(3);
        verify(service).importManifest(any(), any());
        verify(context).close();
    }

    private static LegalManifestCli importCli(
            LegalManifestValidator validator,
            LegalManifestImportReportWriter importWriter,
            Supplier<LegalManifestValidation<LegalImportEnvironment>> environmentResolver,
            Function<LegalImportEnvironment, ConfigurableApplicationContext> contextFactory) {
        return new LegalManifestCli(
                validator,
                new LegalManifestReportWriter(),
                importWriter,
                environmentResolver,
                contextFactory);
    }

    private static LegalManifestValidation<LegalImportEnvironment> enabledEnvironment() {
        return LegalImportEnvironment.resolve(Map.of(
                LegalImportEnvironment.ENABLED_VARIABLE, "true",
                LegalImportEnvironment.URL_VARIABLE, "jdbc:postgresql://db/legal",
                LegalImportEnvironment.USERNAME_VARIABLE, "legal_importer",
                LegalImportEnvironment.PASSWORD_VARIABLE, "private-import-password"),
                new Properties());
    }

    private static ConfigurableApplicationContext importContext(
            LegalManifestImportService service) {
        ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
        when(context.getBean(LegalManifestImportService.class)).thenReturn(service);
        return context;
    }

    private static Map<String, String> replaceSystemProperties(
            Map<String, String> replacements) {
        Map<String, String> previous = new LinkedHashMap<>();
        replacements.forEach((key, value) -> {
            previous.put(key, System.getProperty(key));
            System.setProperty(key, value);
        });
        return previous;
    }

    private static void restoreSystemProperties(Map<String, String> previous) {
        previous.forEach((key, value) -> {
            if (value == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, value);
            }
        });
    }

    private static LegalManifestImportResult importedResult() {
        LegalManifestImportResult result = mock(LegalManifestImportResult.class);
        when(result.status()).thenReturn(LegalManifestStatus.PASS);
        when(result.persisted()).thenReturn(Boolean.TRUE);
        when(result.outcome()).thenReturn(Optional.of(Outcome.IMPORTED));
        when(result.receipt()).thenReturn(Optional.of(
                new Receipt(PUBLICATION_UUID, IMPORTED_AT, SEALED_AT)));
        when(result.issues()).thenReturn(List.of());
        return result;
    }

    private static LegalManifestImportResult knownFailureResult(
            LegalManifestIssueCode issueCode) {
        LegalManifestImportResult result = mock(LegalManifestImportResult.class);
        LegalManifestIssue issue = LegalManifestIssue.at(issueCode, "database/import");
        when(result.status()).thenReturn(issue.severity());
        when(result.persisted()).thenReturn(Boolean.FALSE);
        when(result.outcome()).thenReturn(Optional.empty());
        when(result.receipt()).thenReturn(Optional.empty());
        when(result.issues()).thenReturn(List.of(issue));
        return result;
    }

    private static void stubImportResult(
            LegalManifestImportService service,
            LegalManifestImportResult result) {
        when(service.importManifest(any(), any())).thenAnswer(invocation -> {
            ImportExecutionObserver observer = invocation.getArgument(1);
            observer.callbackStarted();
            return result;
        });
    }

    private static String[] validImportArguments() {
        return new String[]{
                "import",
                "--manifest=" + goldenManifestPath(),
                "--confirm-publication-id=release-valid-v1",
                "--confirm-manifest-sha256=" + GOLDEN_SHA256
        };
    }

    private static Path goldenManifestPath() {
        try {
            java.net.URL resource = LegalManifestCliTest.class.getResource(GOLDEN_MANIFEST);
            if (resource == null) {
                throw new AssertionError("No se encontró la fixture golden");
            }
            return Path.of(resource.toURI());
        } catch (Exception exception) {
            throw new AssertionError("No se pudo resolver la fixture golden", exception);
        }
    }

    private static JsonNode assertSingleImportReport(ByteArrayOutputStream output)
            throws Exception {
        String stdout = output.toString(StandardCharsets.UTF_8);
        assertThat(stdout)
                .endsWith("\n")
                .doesNotEndWith("\n\n")
                .doesNotContain("\r");
        String jsonDocument = stdout.substring(0, stdout.length() - 1);
        try (JsonParser parser = JSON.getFactory().createParser(jsonDocument)) {
            assertThat(parser.nextToken()).isEqualTo(JsonToken.START_OBJECT);
            JsonNode report = JSON.readTree(parser);
            assertThat(parser.nextToken()).isNull();
            assertThat(report.path("reportVersion").intValue()).isEqualTo(2);
            assertThat(report.path("command").textValue()).isEqualTo("import");
            return report;
        }
    }
}
