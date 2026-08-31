package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialPlanValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialPlanValidator.ValidatedEditorialPlan;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidation;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalEditorialPlanV1.OperationType;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalDryRunDatabaseConfiguration;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalEditorialApplyResult;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalEditorialApplyService;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalEditorialDatabaseConfiguration;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalEditorialPlanResult;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalEditorialPlanService;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalEditorialReadinessResult;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalEditorialReadinessService;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalEditorialReplaceScopeGuard;
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
    private static final String EDITORIAL_INTERNAL_PROPERTY_SOURCE =
            "ordenfix-legal-editorial-cli-internal";
    private static final String IMPORT_ISSUE_LOCATION = "cli/import";
    private static final String EDITORIAL_ISSUE_LOCATION = "cli/editorial";
    private static final String EDITORIAL_PLAN_BINDING_LOCATION =
            "cli/editorial/plan-binding";
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
    private final LegalEditorialReportWriter editorialReportWriter;
    private final LegalEditorialPlanValidator editorialPlanValidator;
    private final LegalEditorialReplaceScopeGuard editorialReplaceScopeGuard;
    private final Function<LegalEditorialArguments.Command,
            LegalManifestValidation<LegalEditorialEnvironment>>
            editorialEnvironmentResolver;
    private final Function<LegalEditorialEnvironment, ConfigurableApplicationContext>
            editorialContextFactory;

    public LegalManifestCli() {
        this(
                new LegalManifestValidator(),
                new LegalManifestReportWriter(),
                new LegalManifestImportReportWriter(),
                LegalImportEnvironment::resolve,
                LegalManifestCli::openImportContext,
                new LegalEditorialReportWriter(),
                new LegalEditorialPlanValidator(),
                new LegalEditorialReplaceScopeGuard(),
                LegalEditorialEnvironment::resolve,
                LegalManifestCli::openEditorialContext);
    }

    LegalManifestCli(
            LegalManifestValidator validator,
            LegalManifestReportWriter reportWriter) {
        this(
                validator,
                reportWriter,
                new LegalManifestImportReportWriter(),
                LegalImportEnvironment::resolve,
                LegalManifestCli::openImportContext,
                new LegalEditorialReportWriter(),
                new LegalEditorialPlanValidator(),
                new LegalEditorialReplaceScopeGuard(),
                LegalEditorialEnvironment::resolve,
                LegalManifestCli::openEditorialContext);
    }

    LegalManifestCli(
            LegalManifestValidator validator,
            LegalManifestReportWriter reportWriter,
            LegalManifestImportReportWriter importReportWriter,
            Supplier<LegalManifestValidation<LegalImportEnvironment>>
                    importEnvironmentResolver,
            Function<LegalImportEnvironment, ConfigurableApplicationContext>
                    importContextFactory) {
        this(
                validator,
                reportWriter,
                importReportWriter,
                importEnvironmentResolver,
                importContextFactory,
                new LegalEditorialReportWriter(),
                new LegalEditorialPlanValidator(),
                new LegalEditorialReplaceScopeGuard(),
                LegalEditorialEnvironment::resolve,
                LegalManifestCli::openEditorialContext);
    }

    LegalManifestCli(
            LegalManifestValidator validator,
            LegalManifestReportWriter reportWriter,
            LegalManifestImportReportWriter importReportWriter,
            Supplier<LegalManifestValidation<LegalImportEnvironment>>
                    importEnvironmentResolver,
            Function<LegalImportEnvironment, ConfigurableApplicationContext>
                    importContextFactory,
            LegalEditorialReportWriter editorialReportWriter,
            Function<LegalEditorialArguments.Command,
                    LegalManifestValidation<LegalEditorialEnvironment>>
                    editorialEnvironmentResolver,
            Function<LegalEditorialEnvironment, ConfigurableApplicationContext>
                    editorialContextFactory) {
        this(
                validator,
                reportWriter,
                importReportWriter,
                importEnvironmentResolver,
                importContextFactory,
                editorialReportWriter,
                new LegalEditorialPlanValidator(),
                new LegalEditorialReplaceScopeGuard(),
                editorialEnvironmentResolver,
                editorialContextFactory);
    }

    LegalManifestCli(
            LegalManifestValidator validator,
            LegalManifestReportWriter reportWriter,
            LegalManifestImportReportWriter importReportWriter,
            Supplier<LegalManifestValidation<LegalImportEnvironment>>
                    importEnvironmentResolver,
            Function<LegalImportEnvironment, ConfigurableApplicationContext>
                    importContextFactory,
            LegalEditorialReportWriter editorialReportWriter,
            LegalEditorialPlanValidator editorialPlanValidator,
            LegalEditorialReplaceScopeGuard editorialReplaceScopeGuard,
            Function<LegalEditorialArguments.Command,
                    LegalManifestValidation<LegalEditorialEnvironment>>
                    editorialEnvironmentResolver,
            Function<LegalEditorialEnvironment, ConfigurableApplicationContext>
                    editorialContextFactory) {
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
        this.editorialReportWriter = Objects.requireNonNull(
                editorialReportWriter,
                "editorialReportWriter");
        this.editorialPlanValidator = Objects.requireNonNull(
                editorialPlanValidator,
                "editorialPlanValidator");
        this.editorialReplaceScopeGuard = Objects.requireNonNull(
                editorialReplaceScopeGuard,
                "editorialReplaceScopeGuard");
        this.editorialEnvironmentResolver = Objects.requireNonNull(
                editorialEnvironmentResolver,
                "editorialEnvironmentResolver");
        this.editorialContextFactory = Objects.requireNonNull(
                editorialContextFactory,
                "editorialContextFactory");
    }

    public static void main(String[] args) {
        System.exit(runMain(args, System.out, LegalManifestCli::new));
    }

    static int runMain(
            String[] rawArguments,
            OutputStream standardOutput,
            Supplier<LegalManifestCli> cliFactory) {
        LegalEditorialArguments.Command editorialCommand =
                rawEditorialCommand(rawArguments);
        if (editorialCommand != null) {
            LegalEditorialCliExecutionState executionState =
                    LegalEditorialCliExecutionState.recognized(editorialCommand);
            try {
                return Objects.requireNonNull(cliFactory, "cliFactory")
                        .get()
                        .runEditorialSafely(
                                rawArguments,
                                standardOutput,
                                executionState);
            } catch (RuntimeException | LinkageError unexpectedFailure) {
                return writeEditorialBoundaryReport(standardOutput, executionState);
            }
        }
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
        LegalEditorialArguments.Command editorialCommand =
                rawEditorialCommand(rawArguments);
        if (editorialCommand != null) {
            return runEditorialSafely(
                    rawArguments,
                    standardOutput,
                    LegalEditorialCliExecutionState.recognized(editorialCommand));
        }
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
            if (standardOutput instanceof PrintStream printStream && printStream.checkError()) {
                return LegalManifestStatus.ERROR.exitCode();
            }
        } catch (IOException | RuntimeException | LinkageError outputFailure) {
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

    private int runEditorialSafely(
            String[] rawArguments,
            OutputStream standardOutput,
            LegalEditorialCliExecutionState executionState) {
        Objects.requireNonNull(standardOutput, "standardOutput");
        Objects.requireNonNull(executionState, "executionState");
        ByteArrayOutputStream bufferedReport = new ByteArrayOutputStream();
        LegalEditorialReport report;
        try {
            report = runEditorial(rawArguments, executionState);
        } catch (RuntimeException | LinkageError unexpectedFailure) {
            try {
                report = fallbackEditorialReport(executionState);
            } catch (RuntimeException | LinkageError fallbackFailure) {
                return LegalManifestStatus.ERROR.exitCode();
            }
        }

        try {
            executionState.reportSerializationStarted();
        } catch (RuntimeException stateFailure) {
            return LegalManifestStatus.ERROR.exitCode();
        }
        if (!serializeEditorialReport(report, bufferedReport, editorialReportWriter)) {
            bufferedReport.reset();
            try {
                executionState.reportSerializationFailed();
                report = fallbackEditorialReport(executionState);
            } catch (RuntimeException | LinkageError fallbackFailure) {
                return LegalManifestStatus.ERROR.exitCode();
            }
            if (!serializeEditorialReport(
                    report,
                    bufferedReport,
                    new LegalEditorialReportWriter())) {
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

    private LegalEditorialReport runEditorial(
            String[] rawArguments,
            LegalEditorialCliExecutionState executionState) {
        LegalEditorialArguments.Command command = executionState.snapshot().command();
        LegalManifestValidation<LegalEditorialArguments> parsed =
                LegalEditorialArguments.parse(rawArguments);
        if (!parsed.passed()) {
            return LegalEditorialReport.forKnownFailure(command, null, parsed);
        }

        LegalEditorialArguments arguments = parsed.value().orElseThrow();
        if (arguments.command() != command) {
            throw new IllegalArgumentException(
                    "El comando editorial cambió dentro de su boundary");
        }

        LegalManifestValidation<ValidatedRelease> staticValidation =
                validator.validate(arguments.manifestPath());
        if (!staticValidation.passed()) {
            return LegalEditorialReport.forKnownFailure(
                    command,
                    null,
                    staticValidation);
        }

        ValidatedRelease release = staticValidation.value().orElseThrow();
        executionState.releaseValidated(release);
        ValidatedEditorialPlan editorialPlan = null;
        if (command.requiresEditorialPlan()) {
            LegalManifestValidation<ValidatedEditorialPlan> planValidation =
                    editorialPlanValidator.validate(
                            arguments.editorialPlanPath().orElseThrow());
            if (!planValidation.passed()) {
                return LegalEditorialReport.forKnownFailure(
                        command,
                        release,
                        planValidation);
            }
            editorialPlan = planValidation.value().orElseThrow();
        }

        LegalManifestValidation<ValidatedRelease> confirmation =
                arguments.confirmation().verify(release);
        if (!confirmation.passed()) {
            return LegalEditorialReport.forKnownFailure(
                    command,
                    release,
                    confirmation);
        }

        if (editorialPlan != null) {
            LegalManifestValidation<ValidatedEditorialPlan> planConfirmation =
                    arguments.planConfirmation()
                            .orElseThrow()
                            .verify(
                                    command.editorialPlanOperationType().orElseThrow(),
                                    editorialPlan);
            if (!planConfirmation.passed()) {
                return LegalEditorialReport.forKnownFailure(
                        command,
                        release,
                        planConfirmation);
            }
            editorialPlan = planConfirmation.value().orElseThrow();
            executionState.editorialPlanConfirmed(editorialPlan);

            LegalManifestValidation<ValidatedEditorialPlan> planBinding =
                    verifyEditorialPlanBinding(release, editorialPlan);
            if (!planBinding.passed()) {
                return LegalEditorialReport.forKnownFailure(
                        command,
                        release,
                        editorialPlan,
                        planBinding);
            }
            editorialPlan = planBinding.value().orElseThrow();

            if (command.editorialPlanOperationType().orElseThrow() == OperationType.REPLACE) {
                LegalManifestValidation<ValidatedEditorialPlan> supportedScope =
                        editorialReplaceScopeGuard.validate(editorialPlan);
                if (!supportedScope.passed()) {
                    return LegalEditorialReport.forKnownFailure(
                            command,
                            release,
                            editorialPlan,
                            supportedScope);
                }
                editorialPlan = supportedScope.value().orElseThrow();
            }
        }

        LegalManifestValidation<LegalEditorialEnvironment> environmentValidation =
                Objects.requireNonNull(
                        editorialEnvironmentResolver.apply(command),
                        "editorial environment validation");
        if (!environmentValidation.passed()) {
            return LegalEditorialReport.forKnownFailure(
                    command,
                    release,
                    editorialPlan,
                    environmentValidation);
        }

        LegalEditorialEnvironment environment = environmentValidation.value().orElseThrow();
        try (ConfigurableApplicationContext context = Objects.requireNonNull(
                editorialContextFactory.apply(environment),
                "editorial context")) {
            executionState.contextOpened();
            invokeEditorialOperation(
                    command,
                    release,
                    editorialPlan,
                    context,
                    executionState);
        }
        executionState.contextClosed();
        return fallbackEditorialReport(executionState);
    }

    private static LegalManifestValidation<ValidatedEditorialPlan>
            verifyEditorialPlanBinding(
                    ValidatedRelease release,
                    ValidatedEditorialPlan editorialPlan) {
        ValidatedRelease requiredRelease = Objects.requireNonNull(release, "release");
        ValidatedEditorialPlan requiredPlan = Objects.requireNonNull(
                editorialPlan,
                "editorialPlan");
        if (!requiredRelease.plan().manifest().publicationId()
                        .equals(requiredPlan.plan().targetPublicationId())
                || !requiredRelease.plan().manifestSha256()
                        .equals(requiredPlan.plan().targetManifestSha256())) {
            return LegalManifestValidation.failure(LegalManifestIssue.at(
                    LegalManifestIssueCode.REPLACEMENT_MAPPING_INVALID,
                    EDITORIAL_PLAN_BINDING_LOCATION));
        }
        return LegalManifestValidation.pass(requiredPlan);
    }

    private static void invokeEditorialOperation(
            LegalEditorialArguments.Command command,
            ValidatedRelease release,
            ValidatedEditorialPlan editorialPlan,
            ConfigurableApplicationContext context,
            LegalEditorialCliExecutionState executionState) {
        switch (command) {
            case READINESS -> {
                LegalEditorialReadinessService service =
                        context.getBean(LegalEditorialReadinessService.class);
                executionState.operationInvocationStarted();
                LegalEditorialReadinessResult result = service.evaluate(release);
                executionState.resultReceived(result);
            }
            case PLAN_PROMOTE -> {
                LegalEditorialPlanService service =
                        context.getBean(LegalEditorialPlanService.class);
                executionState.operationInvocationStarted();
                LegalEditorialPlanResult result = service.planPromote(release);
                executionState.resultReceived(result);
            }
            case APPLY_PROMOTE -> {
                LegalEditorialApplyService service =
                        context.getBean(LegalEditorialApplyService.class);
                executionState.operationInvocationStarted();
                LegalEditorialApplyResult result = service.applyPromote(release);
                executionState.resultReceived(result);
            }
            case PLAN_REPLACE -> {
                LegalEditorialPlanService service =
                        context.getBean(LegalEditorialPlanService.class);
                executionState.operationInvocationStarted();
                LegalEditorialPlanResult result = service.planReplace(
                        release,
                        Objects.requireNonNull(editorialPlan, "editorialPlan"));
                executionState.resultReceived(result);
            }
            case APPLY_REPLACE -> {
                LegalEditorialApplyService service =
                        context.getBean(LegalEditorialApplyService.class);
                executionState.operationInvocationStarted();
                LegalEditorialApplyResult result = service.applyReplace(
                        release,
                        Objects.requireNonNull(editorialPlan, "editorialPlan"));
                executionState.resultReceived(result);
            }
            case PLAN_RETIRE -> {
                LegalEditorialPlanService service =
                        context.getBean(LegalEditorialPlanService.class);
                executionState.operationInvocationStarted();
                LegalEditorialPlanResult result = service.planRetire(
                        release,
                        Objects.requireNonNull(editorialPlan, "editorialPlan"));
                executionState.resultReceived(result);
            }
            case APPLY_RETIRE -> {
                LegalEditorialApplyService service =
                        context.getBean(LegalEditorialApplyService.class);
                executionState.operationInvocationStarted();
                LegalEditorialApplyResult result = service.applyRetire(
                        release,
                        Objects.requireNonNull(editorialPlan, "editorialPlan"));
                executionState.resultReceived(result);
            }
        }
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

    private static int writeEditorialBoundaryReport(
            OutputStream standardOutput,
            LegalEditorialCliExecutionState executionState) {
        if (standardOutput == null || executionState == null) {
            return LegalManifestStatus.ERROR.exitCode();
        }
        LegalEditorialReport report;
        try {
            report = fallbackEditorialReport(executionState);
        } catch (RuntimeException | LinkageError fallbackFailure) {
            return LegalManifestStatus.ERROR.exitCode();
        }
        ByteArrayOutputStream bufferedReport = new ByteArrayOutputStream();
        if (!serializeEditorialReport(
                report,
                bufferedReport,
                new LegalEditorialReportWriter())
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

    private static LegalEditorialReport fallbackEditorialReport(
            LegalEditorialCliExecutionState executionState) {
        LegalEditorialCliExecutionState.Snapshot snapshot = Objects
                .requireNonNull(executionState, "executionState")
                .snapshot();
        ValidatedRelease release = snapshot.release().orElse(null);
        if (snapshot.readinessResult().isPresent()) {
            return LegalEditorialReport.forReadiness(
                    Objects.requireNonNull(release, "release"),
                    snapshot.readinessResult().orElseThrow());
        }
        if (snapshot.planResult().isPresent()) {
            return switch (snapshot.command()) {
                case PLAN_PROMOTE -> LegalEditorialReport.forPlanPromote(
                        Objects.requireNonNull(release, "release"),
                        snapshot.planResult().orElseThrow());
                case PLAN_REPLACE -> LegalEditorialReport.forPlanReplace(
                        Objects.requireNonNull(release, "release"),
                        snapshot.editorialPlan().orElseThrow(),
                        snapshot.planResult().orElseThrow());
                case PLAN_RETIRE -> LegalEditorialReport.forPlanRetire(
                        Objects.requireNonNull(release, "release"),
                        snapshot.editorialPlan().orElseThrow(),
                        snapshot.planResult().orElseThrow());
                default -> throw new IllegalStateException(
                        "Un resultado de plan no coincide con el comando editorial");
            };
        }
        if (snapshot.applyResult().isPresent()) {
            return switch (snapshot.command()) {
                case APPLY_PROMOTE -> LegalEditorialReport.forApplyPromote(
                        Objects.requireNonNull(release, "release"),
                        snapshot.applyResult().orElseThrow());
                case APPLY_REPLACE -> LegalEditorialReport.forApplyReplace(
                        Objects.requireNonNull(release, "release"),
                        snapshot.editorialPlan().orElseThrow(),
                        snapshot.applyResult().orElseThrow());
                case APPLY_RETIRE -> LegalEditorialReport.forApplyRetire(
                        Objects.requireNonNull(release, "release"),
                        snapshot.editorialPlan().orElseThrow(),
                        snapshot.applyResult().orElseThrow());
                default -> throw new IllegalStateException(
                        "Un resultado de apply no coincide con el comando editorial");
            };
        }
        if (snapshot.command() == LegalEditorialArguments.Command.APPLY_PROMOTE
                && snapshot.persisted() == null) {
            return LegalEditorialReport.forUnknownApply(release);
        }
        if (snapshot.command() == LegalEditorialArguments.Command.APPLY_REPLACE
                && snapshot.persisted() == null) {
            return LegalEditorialReport.forUnknownApplyReplace(
                    Objects.requireNonNull(release, "release"),
                    snapshot.editorialPlan().orElseThrow());
        }
        if (snapshot.command() == LegalEditorialArguments.Command.APPLY_RETIRE
                && snapshot.persisted() == null) {
            return LegalEditorialReport.forUnknownApplyRetire(
                    Objects.requireNonNull(release, "release"),
                    snapshot.editorialPlan().orElseThrow());
        }
        return LegalEditorialReport.forKnownFailure(
                snapshot.command(),
                release,
                snapshot.editorialPlan().orElse(null),
                LegalManifestIssue.at(
                        LegalManifestIssueCode.EDITORIAL_CLI_OPERATION_FAILED,
                        EDITORIAL_ISSUE_LOCATION));
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

    private static boolean serializeEditorialReport(
            LegalEditorialReport report,
            ByteArrayOutputStream output,
            LegalEditorialReportWriter writer) {
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
            return !(standardOutput instanceof PrintStream printStream)
                    || !printStream.checkError();
        } catch (IOException | RuntimeException | LinkageError outputFailure) {
            return false;
        }
    }

    private static boolean isRawImportCommand(String[] rawArguments) {
        return rawArguments != null
                && rawArguments.length > 0
                && "import".equals(rawArguments[0]);
    }

    private static LegalEditorialArguments.Command rawEditorialCommand(
            String[] rawArguments) {
        if (rawArguments == null || rawArguments.length == 0) {
            return null;
        }
        return LegalEditorialArguments.Command.fromExternalValue(rawArguments[0]);
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

    static ConfigurableApplicationContext openEditorialContext(
            LegalEditorialEnvironment environment) {
        LegalEditorialEnvironment requiredEnvironment = Objects.requireNonNull(
                environment,
                "environment");
        Map<String, Object> internalProperties = new LinkedHashMap<>(
                requiredEnvironment.datasourceProperties());
        internalProperties.put(
                LegalEditorialDatabaseConfiguration.ENABLED_PROPERTY,
                "true");
        addIsolationProperties(internalProperties);

        StandardEnvironment childEnvironment = isolatedEnvironment(
                EDITORIAL_INTERNAL_PROPERTY_SOURCE,
                internalProperties);
        return new SpringApplicationBuilder(LegalEditorialDatabaseConfiguration.class)
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
