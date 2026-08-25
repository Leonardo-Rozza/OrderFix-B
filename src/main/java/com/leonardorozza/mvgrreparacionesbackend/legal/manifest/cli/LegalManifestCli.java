package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidation;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalDryRunDatabaseConfiguration;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestDryRunService;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestDryRunService.DryRunResult;

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
import java.util.function.Supplier;

/** Isolated command-line entry point for offline validation and rollback-only database dry-runs. */
public final class LegalManifestCli {

    private static final String INTERNAL_PROPERTY_SOURCE = "ordenfix-legal-cli-internal";
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

    public LegalManifestCli() {
        this(new LegalManifestValidator(), new LegalManifestReportWriter());
    }

    LegalManifestCli(
            LegalManifestValidator validator,
            LegalManifestReportWriter reportWriter) {
        this.validator = Objects.requireNonNull(validator, "validator");
        this.reportWriter = Objects.requireNonNull(reportWriter, "reportWriter");
    }

    public static void main(String[] args) {
        System.exit(runMain(args, System.out, LegalManifestCli::new));
    }

    static int runMain(
            String[] rawArguments,
            OutputStream standardOutput,
            Supplier<LegalManifestCli> cliFactory) {
        try {
            return Objects.requireNonNull(cliFactory, "cliFactory")
                    .get()
                    .runSafely(rawArguments, standardOutput);
        } catch (RuntimeException | LinkageError unexpectedFailure) {
            return writeEmergencyReport(standardOutput);
        }
    }

    int runSafely(String[] rawArguments, OutputStream standardOutput) {
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

    static ConfigurableApplicationContext openDryRunContext(
            Map<String, Object> propertyOverrides) {
        Objects.requireNonNull(propertyOverrides, "propertyOverrides");
        Map<String, Object> internalProperties = new LinkedHashMap<>();
        copyAllowedDatasourceProperties(propertyOverrides, internalProperties);
        internalProperties.put(LegalDryRunDatabaseConfiguration.ENABLED_PROPERTY, "true");
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

        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(
                StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource(
                INTERNAL_PROPERTY_SOURCE,
                internalProperties));

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
