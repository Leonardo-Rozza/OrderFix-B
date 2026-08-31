package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli.LegalEditorialArguments.Command;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialPlanValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialPlanValidator.ValidatedEditorialPlan;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialReadiness;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidation;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalEditorialPlanV1;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalEditorialPlanV1.OperationType;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalEditorialApplyReceipt;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalEditorialApplyResult;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalEditorialApplyService;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalEditorialPlanResult;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalEditorialPlanService;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalEditorialReadinessObservation;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalEditorialReadinessResult;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalEditorialReadinessService;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalEditorialReplaceScopeGuard;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LegalEditorialCliTest {

    private static final String GOLDEN_MANIFEST =
            "/legal/manifest/release-valid-v1/publication-manifest.json";
    private static final UUID PUBLICATION_UUID =
            UUID.fromString("60870649-efbb-4d2b-8407-4f1901b86a45");
    private static final Instant OBSERVED_AT =
            Instant.parse("2026-08-28T18:00:00.123456Z");
    private static final String FINGERPRINT =
            "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final Path EDITORIAL_PLAN_PATH =
            Path.of("replace-one-to-one-v1", "editorial-plan.json");
    private static final UUID OPERATION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final String EDITORIAL_PLAN_SHA256 =
            "534ef5a63292484c4cde6fc2fa6735f7d42dbc40a3df671a6f9550626aebe49c";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static ValidatedRelease release;
    private static Path manifestPath;

    private LegalManifestValidator validator;
    private LegalEditorialPlanValidator editorialPlanValidator;
    private LegalEditorialReplaceScopeGuard replaceScopeGuard;
    private LegalEditorialPlanV1 editorialPlanModel;
    private ValidatedEditorialPlan editorialPlan;
    private LegalManifestReportWriter v1Writer;
    private LegalManifestImportReportWriter v2Writer;
    private Supplier<LegalManifestValidation<LegalImportEnvironment>> importEnvironment;
    private Function<LegalImportEnvironment, ConfigurableApplicationContext> importContext;
    private LegalEditorialReportWriter editorialWriter;
    private Function<Command, LegalManifestValidation<LegalEditorialEnvironment>>
            editorialEnvironment;
    private Function<LegalEditorialEnvironment, ConfigurableApplicationContext>
            editorialContext;
    private LegalEditorialEnvironment environment;
    private ConfigurableApplicationContext context;

    @BeforeAll
    static void validateGoldenRelease() throws URISyntaxException {
        manifestPath = Path.of(Objects.requireNonNull(
                LegalEditorialCliTest.class.getResource(GOLDEN_MANIFEST)).toURI());
        LegalManifestValidation<ValidatedRelease> validation =
                new LegalManifestValidator().validate(manifestPath);
        assertThat(validation.status())
                .as("issues=%s", validation.issues())
                .isEqualTo(LegalManifestStatus.PASS);
        release = validation.value().orElseThrow();
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        validator = mock(LegalManifestValidator.class);
        editorialPlanValidator = mock(LegalEditorialPlanValidator.class);
        replaceScopeGuard = mock(LegalEditorialReplaceScopeGuard.class);
        editorialPlanModel = mock(LegalEditorialPlanV1.class);
        when(editorialPlanModel.expectedReadinessAfter())
                .thenReturn(LegalEditorialReadiness.READY);
        when(editorialPlanModel.targetPublicationId())
                .thenReturn(release.plan().manifest().publicationId());
        when(editorialPlanModel.targetManifestSha256())
                .thenReturn(release.plan().manifestSha256());
        editorialPlan = mock(ValidatedEditorialPlan.class);
        when(editorialPlan.plan()).thenReturn(editorialPlanModel);
        when(editorialPlan.operationType()).thenReturn(OperationType.REPLACE);
        when(editorialPlan.operationId()).thenReturn(OPERATION_ID);
        when(editorialPlan.editorialPlanSha256()).thenReturn(EDITORIAL_PLAN_SHA256);
        v1Writer = mock(LegalManifestReportWriter.class);
        v2Writer = mock(LegalManifestImportReportWriter.class);
        importEnvironment = mock(Supplier.class);
        importContext = mock(Function.class);
        editorialWriter = new LegalEditorialReportWriter();
        editorialEnvironment = mock(Function.class);
        editorialContext = mock(Function.class);
        context = mock(ConfigurableApplicationContext.class);
        environment = validEnvironment();
    }

    @Test
    void invalidArgumentsNeverValidateResolveEnvironmentOrOpenContext() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = cli().runSafely(
                new String[]{"readiness", "--manifest=" + manifestPath},
                output);

        JsonNode report = report(output);
        assertThat(exit).isEqualTo(2);
        assertThat(report.path("reportVersion").intValue()).isEqualTo(3);
        assertThat(report.path("command").textValue()).isEqualTo("readiness");
        assertThat(report.path("status").textValue()).isEqualTo("BLOCKED");
        assertThat(report.path("persisted").booleanValue()).isFalse();
        assertThat(report.path("issues").get(0).path("code").textValue())
                .isEqualTo("CLI_ARGUMENTS_INVALID");
        verifyNoInteractions(validator, editorialEnvironment, editorialContext);
    }

    @Test
    void serializationFailureBeforeApplyInvocationRemainsKnownNotPersisted()
            throws IOException {
        LegalEditorialReportWriter failingWriter = mock(LegalEditorialReportWriter.class);
        doThrow(new IOException("writer-canary"))
                .when(failingWriter)
                .write(any(LegalEditorialReport.class), any(OutputStream.class));
        editorialWriter = failingWriter;
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = cli().runSafely(
                new String[]{"apply-promote", "--manifest=" + manifestPath},
                output);

        JsonNode report = report(output);
        assertThat(exit).isEqualTo(3);
        assertThat(report.path("status").textValue()).isEqualTo("ERROR");
        assertThat(report.path("persisted").booleanValue()).isFalse();
        assertThat(report.path("operation").path("outcome").textValue())
                .isEqualTo("ERROR");
        assertThat(output.toString(StandardCharsets.UTF_8))
                .doesNotContain("UNKNOWN", "writer-canary");
        verifyNoInteractions(validator, editorialEnvironment, editorialContext);
    }

    @Test
    void staticValidationFailsBeforeConfirmationEnvironmentOrContext() throws IOException {
        when(validator.validate(manifestPath)).thenReturn(LegalManifestValidation.failure(
                issue(LegalManifestIssueCode.MANIFEST_SCHEMA_INVALID, "manifest")));
        String[] arguments = validArguments(Command.PLAN_PROMOTE);
        arguments[2] = "--confirm-publication-id=must-not-win-over-static-validation";
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = cli().runSafely(arguments, output);

        JsonNode report = report(output);
        assertThat(exit).isEqualTo(2);
        assertThat(report.path("command").textValue()).isEqualTo("plan-promote");
        assertThat(report.path("publication").isNull()).isTrue();
        assertThat(report.path("operation").path("outcome").textValue())
                .isEqualTo("BLOCKED");
        verify(validator).validate(manifestPath);
        verifyNoInteractions(editorialEnvironment, editorialContext);
    }

    @Test
    void confirmationMismatchNeverResolvesEnvironmentOrOpensContext() throws IOException {
        when(validator.validate(manifestPath))
                .thenReturn(LegalManifestValidation.pass(release));
        String[] arguments = validArguments(Command.APPLY_PROMOTE);
        arguments[2] = "--confirm-publication-id=release-canary-wrong";
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = cli().runSafely(arguments, output);

        JsonNode report = report(output);
        assertThat(exit).isEqualTo(2);
        assertThat(report.path("status").textValue()).isEqualTo("BLOCKED");
        assertThat(report.path("issues").get(0).path("code").textValue())
                .isEqualTo("EDITORIAL_CONFIRMATION_MISMATCH");
        assertThat(output.toString(StandardCharsets.UTF_8))
                .doesNotContain("release-canary-wrong");
        verifyNoInteractions(editorialEnvironment, editorialContext);
    }

    @Test
    void disabledOrInvalidDatabaseConfigurationNeverOpensContext() throws IOException {
        when(validator.validate(manifestPath))
                .thenReturn(LegalManifestValidation.pass(release));
        when(editorialEnvironment.apply(Command.APPLY_PROMOTE))
                .thenReturn(LegalManifestValidation.failure(issue(
                        LegalManifestIssueCode.EDITORIAL_DISABLED,
                        "cli/editorial/environment")));
        ByteArrayOutputStream disabledOutput = new ByteArrayOutputStream();

        int disabledExit = cli().runSafely(
                validArguments(Command.APPLY_PROMOTE),
                disabledOutput);

        assertThat(disabledExit).isEqualTo(3);
        assertThat(report(disabledOutput).path("issues").get(0).path("code").textValue())
                .isEqualTo("EDITORIAL_DISABLED");
        verify(editorialContext, never()).apply(any());

        when(editorialEnvironment.apply(Command.READINESS))
                .thenReturn(LegalManifestValidation.failure(issue(
                        LegalManifestIssueCode.EDITORIAL_DB_CONFIGURATION_INVALID,
                        "cli/editorial/environment")));
        ByteArrayOutputStream invalidOutput = new ByteArrayOutputStream();

        int invalidExit = cli().runSafely(
                validArguments(Command.READINESS),
                invalidOutput);

        assertThat(invalidExit).isEqualTo(3);
        assertThat(report(invalidOutput).path("issues").get(0).path("code").textValue())
                .isEqualTo("EDITORIAL_DB_CONFIGURATION_INVALID");
        verify(editorialContext, never()).apply(any());
    }

    @Test
    void dispatchesReadinessAndEmitsV3OnlyAfterAllPreflights() throws IOException {
        LegalEditorialReadinessService service = mock(LegalEditorialReadinessService.class);
        LegalEditorialReadinessResult result = readyResult();
        when(service.evaluate(release)).thenReturn(result);
        prepareSuccessfulContext(Command.READINESS, LegalEditorialReadinessService.class, service);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = cli().runSafely(validArguments(Command.READINESS), output);

        JsonNode report = assertSuccessfulV3(output, "readiness", null, "READY");
        assertThat(exit).isZero();
        assertThat(report.path("persisted").booleanValue()).isFalse();
        InOrder order = inOrder(validator, editorialEnvironment, editorialContext, context, service);
        order.verify(validator).validate(manifestPath);
        order.verify(editorialEnvironment).apply(Command.READINESS);
        order.verify(editorialContext).apply(environment);
        order.verify(context).getBean(LegalEditorialReadinessService.class);
        order.verify(service).evaluate(release);
    }

    @Test
    void dispatchesPlanPromoteAndEmitsApplicableV3() throws IOException {
        LegalEditorialPlanService service = mock(LegalEditorialPlanService.class);
        LegalEditorialPlanResult result = applicablePlanResult();
        when(service.planPromote(release)).thenReturn(result);
        prepareSuccessfulContext(Command.PLAN_PROMOTE, LegalEditorialPlanService.class, service);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = cli().runSafely(validArguments(Command.PLAN_PROMOTE), output);

        JsonNode report = assertSuccessfulV3(
                output,
                "plan-promote",
                "APPLICABLE",
                "READY");
        assertThat(exit).isZero();
        assertThat(report.path("plan").path("changeRequired").booleanValue()).isTrue();
        assertThat(report.path("counts").path("delta").isObject()).isTrue();
        verify(service).planPromote(release);
    }

    @Test
    void dispatchesPlanReplaceAndEmitsConfirmedApplicableV3WithoutWriting()
            throws IOException {
        LegalEditorialPlanService service = mock(LegalEditorialPlanService.class);
        LegalEditorialPlanResult result = applicablePlanResult();
        when(service.planReplace(release, editorialPlan)).thenReturn(result);
        prepareSuccessfulContext(
                Command.PLAN_REPLACE,
                LegalEditorialPlanService.class,
                service);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = cli().runSafely(validArguments(Command.PLAN_REPLACE), output);

        JsonNode report = assertSuccessfulV3(
                output,
                "plan-replace",
                "APPLICABLE",
                "READY");
        assertThat(exit).isZero();
        assertThat(report.path("persisted").booleanValue()).isFalse();
        assertThat(report.path("operation").path("operationType").textValue())
                .isEqualTo("REPLACE");
        assertThat(report.path("plan").path("operationId").textValue())
                .isEqualTo(OPERATION_ID.toString());
        assertThat(report.path("plan").path("editorialPlanSha256").textValue())
                .isEqualTo(EDITORIAL_PLAN_SHA256);
        assertThat(report.path("plan").path("changeRequired").booleanValue()).isTrue();
        assertThat(report.path("plan").path("observedAt").textValue())
                .isEqualTo(OBSERVED_AT.toString());
        verify(service).planReplace(release, editorialPlan);
    }

    @Test
    void dispatchesPlanRetireAsReadOnlyNotReadyWithoutUsingTheReplaceGuard()
            throws IOException {
        LegalEditorialPlanService service = mock(LegalEditorialPlanService.class);
        LegalEditorialPlanResult result = applicablePlanResult(
                LegalEditorialReadiness.NOT_READY);
        when(service.planRetire(release, editorialPlan)).thenReturn(result);
        prepareSuccessfulContext(
                Command.PLAN_RETIRE,
                LegalEditorialPlanService.class,
                service);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = cli().runSafely(validArguments(Command.PLAN_RETIRE), output);

        JsonNode report = assertSuccessfulV3(
                output,
                "plan-retire",
                "APPLICABLE",
                "NOT_READY");
        assertThat(exit).isZero();
        assertThat(report.path("persisted").booleanValue()).isFalse();
        assertThat(report.path("operation").path("operationType").textValue())
                .isEqualTo("RETIRE");
        assertThat(report.path("plan").path("expectedReadinessAfter").textValue())
                .isEqualTo("NOT_READY");
        verifyNoInteractions(replaceScopeGuard);
        verify(service).planRetire(release, editorialPlan);
        verify(service, never()).planReplace(release, editorialPlan);
    }

    @Test
    void replaceEnvironmentFailureRetainsOnlyConfirmedInputIdentity() throws IOException {
        when(validator.validate(manifestPath))
                .thenReturn(LegalManifestValidation.pass(release));
        when(editorialPlanValidator.validate(EDITORIAL_PLAN_PATH))
                .thenReturn(LegalManifestValidation.pass(editorialPlan));
        when(replaceScopeGuard.validate(editorialPlan))
                .thenReturn(LegalManifestValidation.pass(editorialPlan));
        when(editorialEnvironment.apply(Command.PLAN_REPLACE))
                .thenReturn(LegalManifestValidation.failure(issue(
                        LegalManifestIssueCode.EDITORIAL_DB_CONFIGURATION_INVALID,
                        "cli/editorial/environment")));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = cli().runSafely(validArguments(Command.PLAN_REPLACE), output);

        JsonNode report = report(output);
        assertThat(exit).isEqualTo(3);
        assertThat(report.path("operation").path("operationType").textValue())
                .isEqualTo("REPLACE");
        assertThat(report.path("operation").path("outcome").textValue())
                .isEqualTo("ERROR");
        assertThat(report.path("plan").path("operationId").textValue())
                .isEqualTo(OPERATION_ID.toString());
        assertThat(report.path("plan").path("editorialPlanSha256").textValue())
                .isEqualTo(EDITORIAL_PLAN_SHA256);
        assertThat(report.path("plan").path("changeRequired").isNull()).isTrue();
        assertThat(report.path("plan").path("observedAt").isNull()).isTrue();
        assertThat(report.path("publication").path("publicationUuid").isNull()).isTrue();
        assertThat(report.path("readiness").isNull()).isTrue();
        assertThat(report.path("counts").path("state").isNull()).isTrue();
        assertThat(report.path("counts").path("delta").isNull()).isTrue();
        verify(editorialContext, never()).apply(any());
    }

    @Test
    void dispatchesApplyPromoteAndEmitsConfirmedV3() throws IOException {
        LegalEditorialApplyService service = mock(LegalEditorialApplyService.class);
        LegalEditorialApplyResult result = appliedResult();
        when(service.applyPromote(release)).thenReturn(result);
        prepareSuccessfulContext(Command.APPLY_PROMOTE, LegalEditorialApplyService.class, service);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = cli().runSafely(validArguments(Command.APPLY_PROMOTE), output);

        JsonNode report = assertSuccessfulV3(
                output,
                "apply-promote",
                "APPLIED",
                "READY");
        assertThat(exit).isZero();
        assertThat(report.path("persisted").booleanValue()).isTrue();
        assertThat(report.path("publication").path("publicationUuid").textValue())
                .isEqualTo(PUBLICATION_UUID.toString());
        verify(service).applyPromote(release);
    }

    @Test
    void dispatchesApplyReplaceAndEmitsConfirmedV3WithPlanIdentity() throws IOException {
        LegalEditorialApplyService service = mock(LegalEditorialApplyService.class);
        LegalEditorialApplyResult result = appliedReplaceResult();
        when(service.applyReplace(release, editorialPlan)).thenReturn(result);
        prepareSuccessfulContext(Command.APPLY_REPLACE, LegalEditorialApplyService.class, service);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = cli().runSafely(validArguments(Command.APPLY_REPLACE), output);

        JsonNode report = assertSuccessfulV3(
                output,
                "apply-replace",
                "APPLIED",
                "READY");
        assertThat(exit).isZero();
        assertThat(report.path("persisted").booleanValue()).isTrue();
        assertThat(report.path("operation").path("operationType").textValue())
                .isEqualTo("REPLACE");
        assertThat(report.path("publication").path("publicationUuid").textValue())
                .isEqualTo(PUBLICATION_UUID.toString());
        assertThat(report.path("plan").path("operationId").textValue())
                .isEqualTo(OPERATION_ID.toString());
        assertThat(report.path("plan").path("editorialPlanSha256").textValue())
                .isEqualTo(EDITORIAL_PLAN_SHA256);
        assertThat(report.path("plan").path("changeRequired").isNull()).isTrue();
        assertThat(report.path("plan").path("observedAt").isNull()).isTrue();
        assertThat(report.path("counts").path("state").path("replacementBatches").intValue())
                .isEqualTo(1);
        assertThat(report.path("counts").path("delta").isNull()).isTrue();
        verify(service).applyReplace(release, editorialPlan);
    }

    @ParameterizedTest
    @EnumSource(
            value = LegalEditorialApplyResult.Outcome.class,
            names = {"APPLIED", "ALREADY_APPLIED"})
    void dispatchesApplyRetireAndTreatsConfirmedNotReadyAsSuccess(
            LegalEditorialApplyResult.Outcome outcome) throws IOException {
        LegalEditorialApplyService service = mock(LegalEditorialApplyService.class);
        LegalEditorialApplyResult result = appliedRetireResult(outcome);
        when(service.applyRetire(release, editorialPlan)).thenReturn(result);
        prepareSuccessfulContext(
                Command.APPLY_RETIRE,
                LegalEditorialApplyService.class,
                service);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = cli().runSafely(validArguments(Command.APPLY_RETIRE), output);

        JsonNode report = assertSuccessfulV3(
                output,
                "apply-retire",
                outcome.name(),
                "NOT_READY");
        assertThat(exit).isZero();
        assertThat(report.path("persisted").booleanValue()).isTrue();
        assertThat(report.path("operation").path("operationType").textValue())
                .isEqualTo("RETIRE");
        assertThat(report.path("plan").path("expectedReadinessAfter").textValue())
                .isEqualTo("NOT_READY");
        verifyNoInteractions(replaceScopeGuard);
        verify(service).applyRetire(release, editorialPlan);
        verify(service, never()).applyReplace(release, editorialPlan);
    }

    @Test
    void rawEditorialConstructorFailureStillUsesTheV3Boundary() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = LegalManifestCli.runMain(
                validArguments(Command.APPLY_PROMOTE),
                output,
                () -> {
                    throw new IllegalStateException("constructor-canary");
                });

        JsonNode report = report(output);
        assertThat(exit).isEqualTo(3);
        assertThat(report.path("reportVersion").intValue()).isEqualTo(3);
        assertThat(report.path("command").textValue()).isEqualTo("apply-promote");
        assertThat(report.path("status").textValue()).isEqualTo("ERROR");
        assertThat(report.path("persisted").booleanValue()).isFalse();
        assertThat(report.path("operation").path("outcome").textValue())
                .isEqualTo("ERROR");
        assertThat(output.toString(StandardCharsets.UTF_8))
                .doesNotContain("constructor-canary");
    }

    @Test
    void rawPlanReplaceConstructorFailureUsesV3WithoutUnconfirmedPlanIdentity()
            throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = LegalManifestCli.runMain(
                validArguments(Command.PLAN_REPLACE),
                output,
                () -> {
                    throw new IllegalStateException("replace-constructor-canary");
                });

        JsonNode report = report(output);
        assertThat(exit).isEqualTo(3);
        assertThat(report.path("reportVersion").intValue()).isEqualTo(3);
        assertThat(report.path("command").textValue()).isEqualTo("plan-replace");
        assertThat(report.path("operation").path("operationType").textValue())
                .isEqualTo("REPLACE");
        assertThat(report.path("operation").path("outcome").textValue())
                .isEqualTo("ERROR");
        assertThat(report.path("plan").isNull()).isTrue();
        assertThat(output.toString(StandardCharsets.UTF_8))
                .doesNotContain("replace-constructor-canary", OPERATION_ID.toString());
    }

    @Test
    void applyInvocationWithoutTerminalResultEmitsUnknownAndNullablePersistence()
            throws IOException {
        LegalEditorialApplyService service = mock(LegalEditorialApplyService.class);
        when(service.applyPromote(release))
                .thenThrow(new IllegalStateException("apply-result-canary"));
        prepareSuccessfulContext(Command.APPLY_PROMOTE, LegalEditorialApplyService.class, service);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = cli().runSafely(validArguments(Command.APPLY_PROMOTE), output);

        JsonNode report = report(output);
        assertThat(exit).isEqualTo(3);
        assertThat(report.path("persisted").isNull()).isTrue();
        assertThat(report.path("operation").path("outcome").textValue())
                .isEqualTo("UNKNOWN");
        assertThat(report.path("publication").path("publicationUuid").isNull()).isTrue();
        assertThat(report.path("operation").path("appliedAt").isNull()).isTrue();
        assertThat(report.path("readiness").isNull()).isTrue();
        assertThat(report.path("counts").path("state").isNull()).isTrue();
        assertThat(output.toString(StandardCharsets.UTF_8))
                .doesNotContain("apply-result-canary");
    }

    @Test
    void applyReplaceInvocationWithoutTerminalResultIsUnknownAndIdentitySafe()
            throws IOException {
        LegalEditorialApplyService service = mock(LegalEditorialApplyService.class);
        when(service.applyReplace(release, editorialPlan))
                .thenThrow(new IllegalStateException("apply-replace-result-canary"));
        prepareSuccessfulContext(Command.APPLY_REPLACE, LegalEditorialApplyService.class, service);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = cli().runSafely(validArguments(Command.APPLY_REPLACE), output);

        JsonNode report = report(output);
        assertThat(exit).isEqualTo(3);
        assertThat(report.path("command").textValue()).isEqualTo("apply-replace");
        assertThat(report.path("persisted").isNull()).isTrue();
        assertThat(report.path("operation").path("operationType").textValue())
                .isEqualTo("REPLACE");
        assertThat(report.path("operation").path("outcome").textValue())
                .isEqualTo("UNKNOWN");
        assertThat(report.path("plan").path("operationId").textValue())
                .isEqualTo(OPERATION_ID.toString());
        assertThat(report.path("plan").path("editorialPlanSha256").textValue())
                .isEqualTo(EDITORIAL_PLAN_SHA256);
        assertThat(report.path("plan").path("changeRequired").isNull()).isTrue();
        assertThat(report.path("plan").path("observedAt").isNull()).isTrue();
        assertThat(report.path("publication").path("publicationUuid").isNull()).isTrue();
        assertThat(report.path("operation").path("appliedAt").isNull()).isTrue();
        assertThat(report.path("readiness").isNull()).isTrue();
        assertThat(report.path("counts").path("state").isNull()).isTrue();
        assertThat(report.path("counts").path("delta").isNull()).isTrue();
        assertThat(output.toString(StandardCharsets.UTF_8))
                .doesNotContain("apply-replace-result-canary");
    }

    @Test
    void applyRetireInvocationWithoutTerminalResultIsUnknownAndIdentitySafe()
            throws IOException {
        LegalEditorialApplyService service = mock(LegalEditorialApplyService.class);
        when(service.applyRetire(release, editorialPlan))
                .thenThrow(new IllegalStateException("apply-retire-result-canary"));
        prepareSuccessfulContext(Command.APPLY_RETIRE, LegalEditorialApplyService.class, service);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = cli().runSafely(validArguments(Command.APPLY_RETIRE), output);

        JsonNode report = report(output);
        assertThat(exit).isEqualTo(3);
        assertThat(report.path("command").textValue()).isEqualTo("apply-retire");
        assertThat(report.path("persisted").isNull()).isTrue();
        assertThat(report.path("operation").path("operationType").textValue())
                .isEqualTo("RETIRE");
        assertThat(report.path("operation").path("outcome").textValue())
                .isEqualTo("UNKNOWN");
        assertThat(report.path("plan").path("operationId").textValue())
                .isEqualTo(OPERATION_ID.toString());
        assertThat(report.path("plan").path("editorialPlanSha256").textValue())
                .isEqualTo(EDITORIAL_PLAN_SHA256);
        assertThat(report.path("plan").path("expectedReadinessAfter").textValue())
                .isEqualTo("NOT_READY");
        assertThat(report.path("publication").path("publicationUuid").isNull()).isTrue();
        assertThat(report.path("operation").path("appliedAt").isNull()).isTrue();
        assertThat(report.path("readiness").isNull()).isTrue();
        assertThat(report.path("counts").path("state").isNull()).isTrue();
        assertThat(report.path("counts").path("delta").isNull()).isTrue();
        assertThat(output.toString(StandardCharsets.UTF_8))
                .doesNotContain("apply-retire-result-canary");
    }

    @Test
    void confirmedResultSurvivesContextCloseAndFirstSerializationFailure()
            throws IOException {
        LegalEditorialApplyService service = mock(LegalEditorialApplyService.class);
        LegalEditorialApplyResult result = appliedResult();
        when(service.applyPromote(release)).thenReturn(result);
        prepareSuccessfulContext(Command.APPLY_PROMOTE, LegalEditorialApplyService.class, service);
        doThrow(new IllegalStateException("close-canary")).when(context).close();
        LegalEditorialReportWriter failingWriter = mock(LegalEditorialReportWriter.class);
        doThrow(new IOException("writer-canary"))
                .when(failingWriter)
                .write(any(LegalEditorialReport.class), any(OutputStream.class));
        editorialWriter = failingWriter;
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = cli().runSafely(validArguments(Command.APPLY_PROMOTE), output);

        JsonNode report = report(output);
        assertThat(exit).isZero();
        assertThat(report.path("status").textValue()).isEqualTo("PASS");
        assertThat(report.path("persisted").booleanValue()).isTrue();
        assertThat(report.path("operation").path("outcome").textValue())
                .isEqualTo("APPLIED");
        assertThat(output.toString(StandardCharsets.UTF_8)).doesNotContain(
                "close-canary",
                "writer-canary");
        verify(failingWriter).write(any(LegalEditorialReport.class), any(OutputStream.class));
    }

    @Test
    void stdoutFailureReturnsThreeWithoutAttemptingASecondEnvelope() throws IOException {
        LegalEditorialReadinessService service = mock(LegalEditorialReadinessService.class);
        LegalEditorialReadinessResult result = readyResult();
        when(service.evaluate(release)).thenReturn(result);
        prepareSuccessfulContext(Command.READINESS, LegalEditorialReadinessService.class, service);
        FailingOutputStream output = new FailingOutputStream();

        int exit = cli().runSafely(validArguments(Command.READINESS), output);

        assertThat(exit).isEqualTo(3);
        assertThat(output.writeAttempts()).isEqualTo(1);
    }

    @Test
    void throwingPrintStreamCheckErrorReturnsThreeWithoutASecondEnvelope() {
        LegalEditorialReadinessService service = mock(LegalEditorialReadinessService.class);
        LegalEditorialReadinessResult result = readyResult();
        when(service.evaluate(release)).thenReturn(result);
        prepareSuccessfulContext(Command.READINESS, LegalEditorialReadinessService.class, service);
        ThrowingCheckErrorPrintStream output = new ThrowingCheckErrorPrintStream();

        int exit = LegalManifestCli.runMain(
                validArguments(Command.READINESS),
                output,
                this::cli);

        assertThat(exit).isEqualTo(3);
        assertThat(output.contents()).containsOnlyOnce("\"reportVersion\":3");
    }

    @Test
    void stdoutNeverContainsPathsConfirmationsOrDatabaseSecrets() throws IOException {
        String pathCanary = "path-password-canary";
        String publicationCanary = "wrong-publication-secret-canary";
        String shaCanary = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        Path canaryPath = Path.of("/private", pathCanary, "publication-manifest.json");
        when(validator.validate(canaryPath))
                .thenReturn(LegalManifestValidation.pass(release));
        String[] arguments = {
                "readiness",
                "--manifest=" + canaryPath,
                "--confirm-publication-id=" + publicationCanary,
                "--confirm-manifest-sha256=" + shaCanary
        };
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = cli().runSafely(arguments, output);

        assertThat(exit).isEqualTo(2);
        assertThat(output.toString(StandardCharsets.UTF_8)).doesNotContain(
                pathCanary,
                publicationCanary,
                shaCanary,
                "jdbc:postgresql://db-secret-canary",
                "db-user-secret-canary",
                "db-password-secret-canary");
        verifyNoInteractions(editorialEnvironment, editorialContext);

        when(editorialEnvironment.apply(Command.READINESS))
                .thenReturn(LegalManifestValidation.pass(environment));
        when(editorialContext.apply(environment)).thenThrow(new IllegalStateException(
                pathCanary + publicationCanary + shaCanary
                        + "jdbc:postgresql://db-secret-canary/legal"
                        + "db-user-secret-canary"
                        + "db-password-secret-canary"));
        ByteArrayOutputStream runtimeFailureOutput = new ByteArrayOutputStream();

        int runtimeFailureExit = cli().runSafely(new String[]{
                "readiness",
                "--manifest=" + canaryPath,
                "--confirm-publication-id=" + release.plan().manifest().publicationId(),
                "--confirm-manifest-sha256=" + release.plan().manifestSha256()
        }, runtimeFailureOutput);

        assertThat(runtimeFailureExit).isEqualTo(3);
        assertThat(runtimeFailureOutput.toString(StandardCharsets.UTF_8)).doesNotContain(
                pathCanary,
                publicationCanary,
                shaCanary,
                "jdbc:postgresql://db-secret-canary",
                "db-user-secret-canary",
                "db-password-secret-canary");
    }

    private LegalManifestCli cli() {
        return new LegalManifestCli(
                validator,
                v1Writer,
                v2Writer,
                importEnvironment,
                importContext,
                editorialWriter,
                editorialPlanValidator,
                replaceScopeGuard,
                editorialEnvironment,
                editorialContext);
    }

    private <T> void prepareSuccessfulContext(
            Command command,
            Class<T> serviceType,
            T service) {
        when(validator.validate(manifestPath))
                .thenReturn(LegalManifestValidation.pass(release));
        if (command.requiresEditorialPlan()) {
            when(editorialPlanValidator.validate(EDITORIAL_PLAN_PATH))
                    .thenReturn(LegalManifestValidation.pass(editorialPlan));
            OperationType operationType = command.editorialPlanOperationType().orElseThrow();
            when(editorialPlan.operationType()).thenReturn(operationType);
            when(editorialPlanModel.expectedReadinessAfter()).thenReturn(
                    operationType == OperationType.RETIRE
                            ? LegalEditorialReadiness.NOT_READY
                            : LegalEditorialReadiness.READY);
            if (operationType == OperationType.REPLACE) {
                when(replaceScopeGuard.validate(editorialPlan))
                        .thenReturn(LegalManifestValidation.pass(editorialPlan));
            }
        }
        when(editorialEnvironment.apply(command))
                .thenReturn(LegalManifestValidation.pass(environment));
        when(editorialContext.apply(environment)).thenReturn(context);
        when(context.getBean(serviceType)).thenReturn(service);
    }

    private static JsonNode assertSuccessfulV3(
            ByteArrayOutputStream output,
            String command,
            String operationOutcome,
            String readiness) throws IOException {
        JsonNode report = report(output);
        assertThat(report.path("reportVersion").intValue()).isEqualTo(3);
        List<String> topLevelFields = new java.util.ArrayList<>();
        report.fieldNames().forEachRemaining(topLevelFields::add);
        assertThat(topLevelFields).containsExactly(
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
        assertThat(report.path("command").textValue()).isEqualTo(command);
        assertThat(report.path("status").textValue()).isEqualTo("PASS");
        if (operationOutcome == null) {
            assertThat(report.path("operation").isNull()).isTrue();
        } else {
            assertThat(report.path("operation").path("outcome").textValue())
                    .isEqualTo(operationOutcome);
        }
        assertThat(report.path("readiness").path("value").textValue())
                .isEqualTo(readiness);
        return report;
    }

    private static JsonNode report(ByteArrayOutputStream output) throws IOException {
        String contents = output.toString(StandardCharsets.UTF_8);
        assertThat(contents).endsWith("\n").doesNotContain("\r");
        return JSON.readTree(contents);
    }

    private static String[] validArguments(Command command) {
        if (command.requiresEditorialPlan()) {
            return new String[]{
                    command.externalValue(),
                    "--manifest=" + manifestPath,
                    "--confirm-publication-id="
                            + release.plan().manifest().publicationId(),
                    "--confirm-manifest-sha256=" + release.plan().manifestSha256(),
                    "--editorial-plan=" + EDITORIAL_PLAN_PATH,
                    "--confirm-operation-id=" + OPERATION_ID,
                    "--confirm-editorial-plan-sha256=" + EDITORIAL_PLAN_SHA256
            };
        }
        return new String[]{
                command.externalValue(),
                "--manifest=" + manifestPath,
                "--confirm-publication-id="
                        + release.plan().manifest().publicationId(),
                "--confirm-manifest-sha256=" + release.plan().manifestSha256()
        };
    }

    private static LegalEditorialEnvironment validEnvironment() {
        LegalManifestValidation<LegalEditorialEnvironment> validation =
                LegalEditorialEnvironment.resolve(
                        Command.APPLY_PROMOTE,
                        Map.of(
                                LegalEditorialEnvironment.ENABLED_VARIABLE, "true",
                                LegalEditorialEnvironment.URL_VARIABLE,
                                        "jdbc:postgresql://db-secret-canary/legal",
                                LegalEditorialEnvironment.USERNAME_VARIABLE,
                                        "db-user-secret-canary",
                                LegalEditorialEnvironment.PASSWORD_VARIABLE,
                                        "db-password-secret-canary"),
                        new Properties());
        return validation.value().orElseThrow();
    }

    private static LegalEditorialReadinessResult readyResult() {
        LegalEditorialReadinessResult result = mock(LegalEditorialReadinessResult.class);
        when(result.status()).thenReturn(LegalManifestStatus.PASS);
        when(result.readiness()).thenReturn(LegalEditorialReadiness.READY);
        when(result.observation()).thenReturn(Optional.of(observation()));
        when(result.issues()).thenReturn(List.of());
        when(result.omittedIssueCount()).thenReturn(0);
        return result;
    }

    private static LegalEditorialPlanResult applicablePlanResult() {
        return applicablePlanResult(LegalEditorialReadiness.READY);
    }

    private static LegalEditorialPlanResult applicablePlanResult(
            LegalEditorialReadiness expectedReadiness) {
        LegalEditorialPlanResult result = mock(LegalEditorialPlanResult.class);
        when(result.status()).thenReturn(LegalManifestStatus.PASS);
        when(result.outcome()).thenReturn(LegalEditorialPlanResult.Outcome.APPLICABLE);
        when(result.targetPublicationUuid()).thenReturn(Optional.of(PUBLICATION_UUID));
        when(result.changeRequired()).thenReturn(Optional.of(true));
        when(result.observedAt()).thenReturn(Optional.of(OBSERVED_AT));
        when(result.expectedReadinessAfter())
                .thenReturn(Optional.of(expectedReadiness));
        when(result.deltaCounts()).thenReturn(Optional.of(new LegalEditorialPlanResult.DeltaCounts(
                22, 0, 12, 0, 11, 0, 0, 0, 8, 0)));
        when(result.issues()).thenReturn(List.of());
        when(result.omittedIssueCount()).thenReturn(0);
        return result;
    }

    private static LegalEditorialApplyResult appliedResult() {
        LegalEditorialApplyReceipt receipt = new LegalEditorialApplyReceipt(
                LegalEditorialApplyReceipt.OperationType.PROMOTE,
                PUBLICATION_UUID,
                OBSERVED_AT,
                LegalEditorialReadiness.READY,
                11, 6, 34, 12, 11, 8, 0);
        LegalEditorialApplyResult result = mock(LegalEditorialApplyResult.class);
        when(result.status()).thenReturn(LegalManifestStatus.PASS);
        when(result.persisted()).thenReturn(Boolean.TRUE);
        when(result.outcome()).thenReturn(LegalEditorialApplyResult.Outcome.APPLIED);
        when(result.receipt()).thenReturn(Optional.of(receipt));
        when(result.issues()).thenReturn(List.of());
        when(result.omittedIssueCount()).thenReturn(0);
        return result;
    }

    private static LegalEditorialApplyResult appliedReplaceResult() {
        LegalEditorialApplyReceipt receipt = new LegalEditorialApplyReceipt(
                LegalEditorialApplyReceipt.OperationType.REPLACE,
                PUBLICATION_UUID,
                OBSERVED_AT,
                LegalEditorialReadiness.READY,
                11, 6, 34, 12, 11, 8, 1);
        LegalEditorialApplyResult result = mock(LegalEditorialApplyResult.class);
        when(result.status()).thenReturn(LegalManifestStatus.PASS);
        when(result.persisted()).thenReturn(Boolean.TRUE);
        when(result.outcome()).thenReturn(LegalEditorialApplyResult.Outcome.APPLIED);
        when(result.receipt()).thenReturn(Optional.of(receipt));
        when(result.issues()).thenReturn(List.of());
        when(result.omittedIssueCount()).thenReturn(0);
        return result;
    }

    private static LegalEditorialApplyResult appliedRetireResult(
            LegalEditorialApplyResult.Outcome outcome) {
        LegalEditorialApplyReceipt receipt = new LegalEditorialApplyReceipt(
                LegalEditorialApplyReceipt.OperationType.RETIRE,
                PUBLICATION_UUID,
                OBSERVED_AT,
                LegalEditorialReadiness.NOT_READY,
                11, 6, 35, 13, 9, 4, 1);
        LegalEditorialApplyResult result = mock(LegalEditorialApplyResult.class);
        when(result.status()).thenReturn(LegalManifestStatus.PASS);
        when(result.persisted()).thenReturn(Boolean.TRUE);
        when(result.outcome()).thenReturn(outcome);
        when(result.receipt()).thenReturn(Optional.of(receipt));
        when(result.issues()).thenReturn(List.of());
        when(result.omittedIssueCount()).thenReturn(0);
        return result;
    }

    private static LegalEditorialReadinessObservation observation() {
        return new LegalEditorialReadinessObservation(
                Optional.of(PUBLICATION_UUID),
                OBSERVED_AT,
                FINGERPRINT,
                11, 6, 34, 12, 11, 8, 0);
    }

    private static LegalManifestIssue issue(
            LegalManifestIssueCode code,
            String location) {
        return LegalManifestIssue.at(code, location);
    }

    private static final class FailingOutputStream extends OutputStream {

        private int writeAttempts;

        @Override
        public void write(int value) throws IOException {
            writeAttempts++;
            throw new IOException("stdout-failure");
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            writeAttempts++;
            throw new IOException("stdout-failure");
        }

        private int writeAttempts() {
            return writeAttempts;
        }
    }

    private static final class ThrowingCheckErrorPrintStream extends PrintStream {

        private final ByteArrayOutputStream captured;

        private ThrowingCheckErrorPrintStream() {
            this(new ByteArrayOutputStream());
        }

        private ThrowingCheckErrorPrintStream(ByteArrayOutputStream captured) {
            super(captured, true, StandardCharsets.UTF_8);
            this.captured = captured;
        }

        @Override
        public boolean checkError() {
            throw new IllegalStateException("check-error-canary");
        }

        private String contents() {
            return captured.toString(StandardCharsets.UTF_8);
        }
    }
}
