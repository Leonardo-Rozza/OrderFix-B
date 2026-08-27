package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidation;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalDryRunDatabaseConfiguration;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalImportDatabaseConfiguration;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestDryRunService;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestDryRunService.DryRunResult;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestImportResult;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestImportService;

import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/** Isolated command-line entry point for legal validation, dry-run and controlled import. */
public final class LegalManifestCli {

    private static final String INTERNAL_PROPERTY_SOURCE = "ordenfix-legal-cli-internal";
    private static final String IMPORT_INTERNAL_PROPERTY_SOURCE =
            "ordenfix-legal-import-cli-internal";
    private static final String IMPORT_ISSUE_LOCATION = "cli/import";
    private static final String EMERGENCY_REPORT =
            "{\"reportVersion\":1,\"command\":null,\"status\":\"ERROR\","
                    + "\"persisted\":false,\"publication\":null,\"counts\":null,"
                    + "\"dryRun\":null,\"issues\":[{\"severity\":\"ERROR\","
                    + "\"code\":\"CLI_OPERATION_FAILED\",\"location\":\"cli\","
                    + "\"message\":\"No se pudo completar el comando legal.\"}],"
                    + "\"omittedIssueCount\":0}\n";
    private static final Map<String, String> ALLOWED_DATASOURCE_PROPERTIES = Map.of(
            "spring.datasource.url", "SPRING_DATASOURCE_URL",
            "spring.datasource.username", "SPRING_DATASOURCE_USERNAME",
            "spring.datasource.password", "SPRING_DATASOURCE_PASSWORD",
            "spring.datasource.driver-class-name", "SPRING_DATASOURCE_DRIVER_CLASS_NAME");

    private final LegalManifestValidator validator;
    private final LegalManifestReportWriter reportWriter;
    private final LegalManifestImportReportWriter importReportWriter;
    private final Supplier<LegalManifestValidation<LegalImportEnvironment>>
            importEnvironmentResolver;
    private final Function<LegalImportEnvironment, ConfigurableApplicationContext>
            importContextFactory;

    public LegalManifestCli() {
        this(
                new LegalManifestValidator(),
                new LegalManifestReportWriter(),
                new LegalManifestImportReportWriter(),
                LegalImportEnvironment::resolve,
                LegalManifestCli::openImportContext);
    }

    LegalManifestCli(
            LegalManifestValidator validator,
            LegalManifestReportWriter reportWriter) {
        this(
                validator,
                reportWriter,
                new LegalManifestImportReportWriter(),
                LegalImportEnvironment::resolve,
                LegalManifestCli::openImportContext);
    }

    LegalManifestCli(
            LegalManifestValidator validator,
            LegalManifestReportWriter reportWriter,
            LegalManifestImportReportWriter importReportWriter,
            Supplier<LegalManifestValidation<LegalImportEnvironment>>
                    importEnvironmentResolver,
            Function<LegalImportEnvironment, ConfigurableApplicationContext>
                    importContextFactory) {
        this.validator = Objects.requireNonNull(validator, "validator");
        this.reportWriter = Objects.requireNonNull(reportWriter, "reportWriter");
        this.importReportWriter = Objects.requireNonNull(
                importReportWriter,
                "importReportWriter");
        this.importEnvironmentResolver = Objects.requireNonNull(
                importEnvironmentResolver,
                "importEnvironmentResolver");
        this.importContextFactory = Objects.requireNonNull(
                importContextFactory,
                "importContextFactory");
    }

    public static void main(String[] args) {
        System.exit(runMain(args, System.out, LegalManifestCli::new));
    }

    static int runMain(
            String[] rawArguments,
            OutputStream standardOutput,
            Supplier<LegalManifestCli> cliFactory) {
        if (isRawImportCommand(rawArguments)) {
            LegalManifestCliExecutionState executionState =
                    LegalManifestCliExecutionState.recognizedImport();
            try {
                return Objects.requireNonNull(cliFactory, "cliFactory")
                        .get()
                        .runImportSafely(rawArguments, standardOutput, executionState);
            } catch (RuntimeException | LinkageError unexpectedFailure) {
                return writeImportBoundaryReport(standardOutput, executionState);
            }
        }
        try {
            return Objects.requireNonNull(cliFactory, "cliFactory")
                    .get()
                    .runSafely(rawArguments, standardOutput);
        } catch (RuntimeException | LinkageError unexpectedFailure) {
            return writeEmergencyReport(standardOutput);
        }
    }

    int runSafely(String[] rawArguments, OutputStream standardOutput) {
        if (isRawImportCommand(rawArguments)) {
            return runImportSafely(
                    rawArguments,
                    standardOutput,
                    LegalManifestCliExecutionState.recognizedImport());
        }
        Objects.requireNonNull(standardOutput, "standardOutput");
        ByteArrayOutputStream bufferedReport = new ByteArrayOutputStream();
        int exitCode;
        try {
            exitCode = run(rawArguments, bufferedReport);
        } catch (RuntimeException | LinkageError unexpectedFailure) {
            bufferedReport.reset();
            exitCode = writeEmergencyReport(bufferedReport);
        }

        try {
            bufferedReport.writeTo(standardOutput);
            standardOutput.flush();
        } catch (IOException outputFailure) {
            return LegalManifestStatus.ERROR.exitCode();
        }
        if (standardOutput instanceof PrintStream printStream && printStream.checkError()) {
            return LegalManifestStatus.ERROR.exitCode();
        }
        return exitCode;
    }

    private int runImportSafely(
            String[] rawArguments,
            OutputStream standardOutput,
            LegalManifestCliExecutionState executionState) {
        Objects.requireNonNull(standardOutput, "standardOutput");
        Objects.requireNonNull(executionState, "executionState");
        ByteArrayOutputStream bufferedReport = new ByteArrayOutputStream();
        LegalManifestImportReport report;
        try {
            report = runImport(rawArguments, executionState);
        } catch (RuntimeException | LinkageError unexpectedFailure) {
            try {
                report = fallbackImportReport(executionState);
            } catch (RuntimeException | LinkageError fallbackFailure) {
                return LegalManifestStatus.ERROR.exitCode();
            }
        }

        if (!serializeImportReport(report, bufferedReport, importReportWriter)) {
            bufferedReport.reset();
            try {
                report = fallbackImportReport(executionState);
            } catch (RuntimeException | LinkageError fallbackFailure) {
                return LegalManifestStatus.ERROR.exitCode();
            }
            if (!serializeImportReport(
                    report,
                    bufferedReport,
                    new LegalManifestImportReportWriter())) {
                return LegalManifestStatus.ERROR.exitCode();
            }
        }

        if (!copyBufferedReport(bufferedReport, standardOutput)) {
            return LegalManifestStatus.ERROR.exitCode();
        }
        return report.status().exitCode();
    }

    int run(String[] rawArguments, OutputStream standardOutput) {
        Objects.requireNonNull(standardOutput, "standardOutput");
        LegalManifestValidation<LegalManifestArguments> parsed =
                LegalManifestArguments.parse(rawArguments);
        if (!parsed.passed()) {
            return write(
                    LegalManifestReport.forArgumentFailure(parsed),
                    standardOutput);
        }

        LegalManifestArguments arguments = parsed.value().orElseThrow();
        if (arguments.command() == LegalManifestArguments.Command.IMPORT) {
            throw new IllegalArgumentException(
                    "El comando import requiere su boundary v2 dedicado");
        }
        String command = arguments.command().externalValue();
        LegalManifestValidation<ValidatedRelease> staticValidation =
                validator.validate(arguments.manifestPath());
        if (!staticValidation.passed()
                || arguments.command() == LegalManifestArguments.Command.VALIDATE) {
            return write(
                    LegalManifestReport.forStaticValidation(command, staticValidation),
                    standardOutput);
        }

        ValidatedRelease release = staticValidation.value().orElseThrow();
        LegalManifestReport report;
        try {
            report = LegalManifestReport.forDryRun(release, dryRun(release));
        } catch (RuntimeException | LinkageError unexpectedFailure) {
            report = LegalManifestReport.forDryRun(
                    release,
                    databaseFailure(LegalManifestIssueCode.DB_OPERATION_FAILED));
        }
        return write(report, standardOutput);
    }

    private LegalManifestImportReport runImport(
            String[] rawArguments,
            LegalManifestCliExecutionState executionState) {
        LegalManifestValidation<LegalManifestArguments> parsed =
                LegalManifestArguments.parse(rawArguments);
        if (!parsed.passed()) {
            return LegalManifestImportReport.forArgumentFailure(parsed);
        }

        LegalManifestArguments arguments = parsed.value().orElseThrow();
        if (arguments.command() != LegalManifestArguments.Command.IMPORT) {
            throw new IllegalArgumentException("El boundary v2 sólo admite import");
        }

        LegalManifestValidation<ValidatedRelease> staticValidation =
                validator.validate(arguments.manifestPath());
        if (!staticValidation.passed()) {
            return LegalManifestImportReport.forStaticValidation(staticValidation);
        }

        ValidatedRelease release = staticValidation.value().orElseThrow();
        executionState.releaseValidated(release);
        LegalManifestValidation<ValidatedRelease> confirmation = arguments
                .importConfirmation()
                .orElseThrow()
                .verify(release);
        if (!confirmation.passed()) {
            return LegalManifestImportReport.forConfirmationFailure(
                    release,
                    confirmation);
        }

        LegalManifestValidation<LegalImportEnvironment> environmentValidation =
                Objects.requireNonNull(
                        importEnvironmentResolver.get(),
                        "import environment validation");
        if (!environmentValidation.passed()) {
            return LegalManifestImportReport.forReleaseFailure(
                    release,
                    environmentValidation);
        }

        LegalImportEnvironment environment = environmentValidation.value().orElseThrow();
        try (ConfigurableApplicationContext context = Objects.requireNonNull(
                importContextFactory.apply(environment),
                "import context")) {
            executionState.contextOpened();
            LegalManifestImportService importService =
                    context.getBean(LegalManifestImportService.class);
            LegalManifestImportResult result = importService.importManifest(
                    release,
                    executionState::importCallbackStarted);
            executionState.importResultReceived(result);
        }

        LegalManifestImportResult result = executionState.snapshot()
                .importResult()
                .orElseThrow();
        return LegalManifestImportReport.forImport(release, result);
    }

    private LegalManifestValidation<DryRunResult> dryRun(ValidatedRelease release) {
        if (!databaseUrlConfigured()) {
            return databaseFailure(LegalManifestIssueCode.DB_CONNECTION);
        }
        try (ConfigurableApplicationContext context = openDryRunContext(Map.of())) {
            return context.getBean(LegalManifestDryRunService.class).dryRun(release);
        } catch (RuntimeException exception) {
            return databaseFailure(LegalManifestIssueCode.DB_OPERATION_FAILED);
        } catch (LinkageError error) {
            return databaseFailure(LegalManifestIssueCode.DB_OPERATION_FAILED);
        }
    }

    private int write(LegalManifestReport report, OutputStream standardOutput) {
        try {
            reportWriter.write(report, standardOutput);
            standardOutput.write('\n');
            standardOutput.flush();
        } catch (IOException exception) {
            return LegalManifestStatus.ERROR.exitCode();
        }
        return report.status().exitCode();
    }

    private static int writeEmergencyReport(OutputStream standardOutput) {
        try {
            standardOutput.write(EMERGENCY_REPORT.getBytes(StandardCharsets.UTF_8));
            standardOutput.flush();
            if (standardOutput instanceof PrintStream printStream) {
                printStream.checkError();
            }
        } catch (IOException | RuntimeException | LinkageError emergencyFailure) {
            // No existe un canal alternativo seguro si también falla stdout.
        }
        return LegalManifestStatus.ERROR.exitCode();
    }

    private static int writeImportBoundaryReport(
            OutputStream standardOutput,
            LegalManifestCliExecutionState executionState) {
        if (standardOutput == null || executionState == null) {
            return LegalManifestStatus.ERROR.exitCode();
        }
        LegalManifestImportReport report;
        try {
            report = fallbackImportReport(executionState);
        } catch (RuntimeException | LinkageError fallbackFailure) {
            return LegalManifestStatus.ERROR.exitCode();
        }
        ByteArrayOutputStream bufferedReport = new ByteArrayOutputStream();
        if (!serializeImportReport(
                report,
                bufferedReport,
                new LegalManifestImportReportWriter())
                || !copyBufferedReport(bufferedReport, standardOutput)) {
            return LegalManifestStatus.ERROR.exitCode();
        }
        return report.status().exitCode();
    }

    private static LegalManifestImportReport fallbackImportReport(
            LegalManifestCliExecutionState executionState) {
        LegalManifestCliExecutionState.Snapshot snapshot = Objects
                .requireNonNull(executionState, "executionState")
                .snapshot();
        ValidatedRelease release = snapshot.release().orElse(null);
        if (snapshot.importResult().isPresent()) {
            return LegalManifestImportReport.forImport(
                    Objects.requireNonNull(release, "release"),
                    snapshot.importResult().orElseThrow());
        }
        if (snapshot.persisted() == null) {
            return LegalManifestImportReport.forUnknownOperationalFailure(release);
        }
        return LegalManifestImportReport.forKnownOperationalFailure(
                release,
                LegalManifestIssue.at(
                        LegalManifestIssueCode.IMPORT_CLI_OPERATION_FAILED,
                        IMPORT_ISSUE_LOCATION));
    }

    private static boolean serializeImportReport(
            LegalManifestImportReport report,
            ByteArrayOutputStream output,
            LegalManifestImportReportWriter writer) {
        try {
            writer.write(report, output);
            output.write('\n');
            output.flush();
            return true;
        } catch (IOException | RuntimeException | LinkageError serializationFailure) {
            return false;
        }
    }

    private static boolean copyBufferedReport(
            ByteArrayOutputStream bufferedReport,
            OutputStream standardOutput) {
        try {
            bufferedReport.writeTo(standardOutput);
            standardOutput.flush();
        } catch (IOException | RuntimeException | LinkageError outputFailure) {
            return false;
        }
        return !(standardOutput instanceof PrintStream printStream)
                || !printStream.checkError();
    }

    private static boolean isRawImportCommand(String[] rawArguments) {
        return rawArguments != null
                && rawArguments.length > 0
                && "import".equals(rawArguments[0]);
    }

    static ConfigurableApplicationContext openImportContext(
            LegalImportEnvironment environment) {
        LegalImportEnvironment requiredEnvironment = Objects.requireNonNull(
                environment,
                "environment");
        Map<String, Object> internalProperties = new LinkedHashMap<>(
                requiredEnvironment.datasourceProperties());
        internalProperties.put(LegalImportDatabaseConfiguration.ENABLED_PROPERTY, "true");
        addIsolationProperties(internalProperties);

        StandardEnvironment childEnvironment = isolatedEnvironment(
                IMPORT_INTERNAL_PROPERTY_SOURCE,
                internalProperties);
        return new SpringApplicationBuilder(LegalImportDatabaseConfiguration.class)
                .main(LegalManifestCli.class)
                .environment(childEnvironment)
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .logStartupInfo(false)
                .registerShutdownHook(false)
                .headless(true)
                .addCommandLineProperties(false)
                .run();
    }

    static ConfigurableApplicationContext openDryRunContext(
            Map<String, Object> propertyOverrides) {
        Objects.requireNonNull(propertyOverrides, "propertyOverrides");
        Map<String, Object> internalProperties = new LinkedHashMap<>();
        copyAllowedDatasourceProperties(propertyOverrides, internalProperties);
        internalProperties.put(LegalDryRunDatabaseConfiguration.ENABLED_PROPERTY, "true");
        addIsolationProperties(internalProperties);

        StandardEnvironment environment = isolatedEnvironment(
                INTERNAL_PROPERTY_SOURCE,
                internalProperties);

        return new SpringApplicationBuilder(LegalDryRunDatabaseConfiguration.class)
                .main(LegalManifestCli.class)
                .environment(environment)
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .logStartupInfo(false)
                .registerShutdownHook(false)
                .headless(true)
                .addCommandLineProperties(false)
                .run();
    }

    private static void addIsolationProperties(Map<String, Object> internalProperties) {
        internalProperties.put(
                "spring.config.location",
                "optional:classpath:/ordenfix-legal-cli/");
        internalProperties.put("spring.main.web-application-type", "none");
        internalProperties.put("spring.main.banner-mode", "off");
        internalProperties.put("spring.main.log-startup-info", "false");
        internalProperties.put("spring.main.register-shutdown-hook", "false");
        internalProperties.put("spring.main.keep-alive", "false");
        internalProperties.put("spring.jmx.enabled", "false");
        internalProperties.put("spring.flyway.enabled", "false");
        internalProperties.put("logging.level.root", "OFF");
        internalProperties.put("logging.console.enabled", "false");
    }

    private static StandardEnvironment isolatedEnvironment(
            String propertySourceName,
            Map<String, Object> internalProperties) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(
                StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource(
                propertySourceName,
                internalProperties));
        return environment;
    }

    private static boolean databaseUrlConfigured() {
        return isConfigured(System.getProperty("spring.datasource.url"))
                || isConfigured(System.getenv("SPRING_DATASOURCE_URL"));
    }

    private static boolean isConfigured(String value) {
        return value != null && !value.isBlank();
    }

    private static void copyAllowedDatasourceProperties(
            Map<String, Object> propertyOverrides,
            Map<String, Object> destination) {
        for (Map.Entry<String, String> allowed : ALLOWED_DATASOURCE_PROPERTIES.entrySet()) {
            Object override = propertyOverrides.get(allowed.getKey());
            if (override != null) {
                destination.put(allowed.getKey(), override);
                continue;
            }
            String systemValue = System.getProperty(allowed.getKey());
            if (isConfigured(systemValue)) {
                destination.put(allowed.getKey(), systemValue);
                continue;
            }
            String environmentValue = System.getenv(allowed.getValue());
            if (isConfigured(environmentValue)) {
                destination.put(allowed.getKey(), environmentValue);
            }
        }
    }

    private static <T> LegalManifestValidation<T> databaseFailure(
            LegalManifestIssueCode code) {
        return LegalManifestValidation.failure(LegalManifestIssue.at(code, "database"));
    }
}
