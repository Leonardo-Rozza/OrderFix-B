package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli;

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
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalEditorialPlanV1.DocumentRef;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalEditorialPlanV1.DocumentReplacementBatch;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalEditorialPlanV1.OperationType;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalEditorialApplyService;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalEditorialPlanService;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalEditorialReadinessService;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalEditorialReplaceScopeGuard;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InOrder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.ByteArrayOutputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LegalEditorialPreflightTest {

    private static final String GOLDEN_MANIFEST =
            "/legal/manifest/release-valid-v1/publication-manifest.json";
    private static final Path EDITORIAL_PLAN_PATH =
            Path.of("replace-one-to-one-v1", "editorial-plan.json");
    private static final UUID OPERATION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final String EDITORIAL_PLAN_SHA256 =
            "534ef5a63292484c4cde6fc2fa6735f7d42dbc40a3df671a6f9550626aebe49c";

    private static ValidatedRelease release;
    private static Path manifestPath;

    private LegalManifestValidator validator;
    private LegalEditorialPlanValidator editorialPlanValidator;
    private LegalEditorialReplaceScopeGuard replaceScopeGuard;
    private LegalEditorialPlanV1 editorialPlanModel;
    private ValidatedEditorialPlan editorialPlan;
    private Function<Command, LegalManifestValidation<LegalEditorialEnvironment>>
            environmentResolver;
    private Function<LegalEditorialEnvironment, ConfigurableApplicationContext>
            contextFactory;
    private ConfigurableApplicationContext context;
    private LegalEditorialReadinessService readinessService;
    private LegalEditorialPlanService planService;
    private LegalEditorialApplyService applyService;

    @BeforeAll
    static void validateGoldenRelease() throws URISyntaxException {
        manifestPath = Path.of(Objects.requireNonNull(
                LegalEditorialPreflightTest.class.getResource(GOLDEN_MANIFEST)).toURI());
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
        environmentResolver = mock(Function.class);
        contextFactory = mock(Function.class);
        context = mock(ConfigurableApplicationContext.class);
        readinessService = mock(LegalEditorialReadinessService.class);
        planService = mock(LegalEditorialPlanService.class);
        applyService = mock(LegalEditorialApplyService.class);
    }

    @ParameterizedTest
    @EnumSource(Command.class)
    void argumentsAreParsedBeforeTheReleaseAndEveryExternalBoundary(Command command) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exitCode = cli().runSafely(new String[]{
                command.externalValue(),
                "--manifest=" + manifestPath,
                "--confirm-publication-id=" + release.plan().manifest().publicationId()
        }, output);

        assertThat(exitCode).isEqualTo(LegalManifestStatus.BLOCKED.exitCode());
        assertThat(output.toString(StandardCharsets.UTF_8))
                .contains(LegalManifestIssueCode.CLI_ARGUMENTS_INVALID.name());
        verifyNoInteractions(
                validator,
                editorialPlanValidator,
                replaceScopeGuard,
                environmentResolver,
                contextFactory,
                context,
                readinessService,
                planService,
                applyService);
    }

    @ParameterizedTest
    @EnumSource(Command.class)
    void releaseValidationRunsBeforeConfirmationEnvironmentContextAndService(Command command) {
        when(validator.validate(manifestPath)).thenReturn(LegalManifestValidation.failure(
                issue(LegalManifestIssueCode.MANIFEST_SCHEMA_INVALID, "manifest")));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exitCode = cli().runSafely(validArguments(command), output);

        assertThat(exitCode).isEqualTo(LegalManifestStatus.BLOCKED.exitCode());
        assertThat(output.toString(StandardCharsets.UTF_8))
                .contains(LegalManifestIssueCode.MANIFEST_SCHEMA_INVALID.name());
        verify(validator).validate(manifestPath);
        verifyNoInteractions(editorialPlanValidator, replaceScopeGuard);
        verifyNoEnvironmentContextOrServiceInteractions();
    }

    @ParameterizedTest(name = "{0} blocks a {1} mismatch before credentials and Spring")
    @MethodSource("commandsAndConfirmationMismatches")
    void everyConfirmationMismatchStopsBeforeEnvironmentContextAndService(
            Command command,
            ConfirmationMismatch mismatch) {
        when(validator.validate(manifestPath))
                .thenReturn(LegalManifestValidation.pass(release));
        stubSuccessfulPlanPreflight(command);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exitCode = cli().runSafely(mismatchedArguments(command, mismatch), output);

        assertThat(exitCode).isEqualTo(LegalManifestStatus.BLOCKED.exitCode());
        assertThat(output.toString(StandardCharsets.UTF_8))
                .contains(LegalManifestIssueCode.EDITORIAL_CONFIRMATION_MISMATCH.name());
        verify(validator).validate(manifestPath);
        if (command.requiresEditorialPlan()) {
            verify(editorialPlanValidator).validate(EDITORIAL_PLAN_PATH);
        } else {
            verifyNoInteractions(editorialPlanValidator);
        }
        verifyNoInteractions(replaceScopeGuard);
        verifyNoEnvironmentContextOrServiceInteractions();
    }

    @ParameterizedTest
    @EnumSource(Command.class)
    void environmentFailureStopsBeforeContextAndService(Command command) {
        when(validator.validate(manifestPath))
                .thenReturn(LegalManifestValidation.pass(release));
        stubSuccessfulPlanPreflight(command);
        Map<String, String> variables = command.mutating()
                ? databaseEnvironment(false)
                : Map.of();
        LegalManifestIssueCode expectedCode = command.mutating()
                ? LegalManifestIssueCode.EDITORIAL_DISABLED
                : LegalManifestIssueCode.EDITORIAL_DB_CONFIGURATION_INVALID;
        when(environmentResolver.apply(command)).thenAnswer(invocation ->
                LegalEditorialEnvironment.resolve(command, variables, new Properties()));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exitCode = cli().runSafely(validArguments(command), output);

        assertThat(exitCode).isEqualTo(LegalManifestStatus.ERROR.exitCode());
        assertThat(output.toString(StandardCharsets.UTF_8)).contains(expectedCode.name());
        InOrder order = inOrder(
                validator,
                editorialPlanValidator,
                replaceScopeGuard,
                environmentResolver);
        order.verify(validator).validate(manifestPath);
        if (command.requiresEditorialPlan()) {
            order.verify(editorialPlanValidator).validate(EDITORIAL_PLAN_PATH);
            if (command.editorialPlanOperationType().orElseThrow() == OperationType.REPLACE) {
                order.verify(replaceScopeGuard).validate(editorialPlan);
            } else {
                verifyNoInteractions(replaceScopeGuard);
            }
        }
        order.verify(environmentResolver).apply(command);
        verifyNoInteractions(
                contextFactory,
                context,
                readinessService,
                planService,
                applyService);
    }

    @ParameterizedTest
    @EnumSource(Command.class)
    void successfulPreflightUsesTheFrozenOrderAndOnlyApplyGetsTheEnableFlag(
            Command command) {
        Map<String, String> variables = databaseEnvironment(command.mutating());
        assertThat(variables.containsKey(LegalEditorialEnvironment.ENABLED_VARIABLE))
                .isEqualTo(command.mutating());
        LegalEditorialEnvironment environment = LegalEditorialEnvironment
                .resolve(command, variables, new Properties())
                .value()
                .orElseThrow();
        when(validator.validate(manifestPath))
                .thenReturn(LegalManifestValidation.pass(release));
        stubSuccessfulPlanPreflight(command);
        when(environmentResolver.apply(command))
                .thenReturn(LegalManifestValidation.pass(environment));
        when(contextFactory.apply(environment)).thenReturn(context);
        stubServiceInvocation(command);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exitCode = cli().runSafely(validArguments(command), output);

        assertThat(exitCode).isEqualTo(LegalManifestStatus.ERROR.exitCode());
        InOrder order = inOrder(
                validator,
                editorialPlanValidator,
                replaceScopeGuard,
                environmentResolver,
                contextFactory,
                context,
                serviceFor(command));
        order.verify(validator).validate(manifestPath);
        if (command.requiresEditorialPlan()) {
            order.verify(editorialPlanValidator).validate(EDITORIAL_PLAN_PATH);
            if (command.editorialPlanOperationType().orElseThrow() == OperationType.REPLACE) {
                order.verify(replaceScopeGuard).validate(editorialPlan);
            } else {
                verifyNoInteractions(replaceScopeGuard);
            }
        }
        order.verify(environmentResolver).apply(command);
        order.verify(contextFactory).apply(environment);
        verifyServiceInvocationInOrder(command, order);
        order.verify(context).close();
    }

    @ParameterizedTest
    @MethodSource("editorialPlanCommandsAndPlanConfirmationMismatches")
    void editorialPlanConfirmationMismatchStopsBeforeScopeEnvironmentAndSpring(
            Command command,
            PlanConfirmationMismatch mismatch) {
        when(validator.validate(manifestPath))
                .thenReturn(LegalManifestValidation.pass(release));
        ValidatedEditorialPlan mismatched = mismatchedPlan(command, mismatch);
        when(editorialPlanValidator.validate(EDITORIAL_PLAN_PATH))
                .thenReturn(LegalManifestValidation.pass(mismatched));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exitCode = cli().runSafely(
                validArguments(command),
                output);

        assertThat(exitCode).isEqualTo(LegalManifestStatus.BLOCKED.exitCode());
        assertThat(output.toString(StandardCharsets.UTF_8))
                .contains(LegalManifestIssueCode.EDITORIAL_CONFIRMATION_MISMATCH.name(),
                        "\"plan\":null");
        verify(editorialPlanValidator).validate(EDITORIAL_PLAN_PATH);
        verifyNoInteractions(
                replaceScopeGuard,
                environmentResolver,
                contextFactory,
                context,
                planService,
                applyService);
    }

    @ParameterizedTest
    @EnumSource(
            value = Command.class,
            names = {"PLAN_REPLACE", "APPLY_REPLACE", "PLAN_RETIRE", "APPLY_RETIRE"})
    void planValidationFailureWinsBeforeBundleConfirmationScopeAndEnvironment(Command command) {
        when(validator.validate(manifestPath))
                .thenReturn(LegalManifestValidation.pass(release));
        when(editorialPlanValidator.validate(EDITORIAL_PLAN_PATH))
                .thenReturn(LegalManifestValidation.failure(issue(
                        LegalManifestIssueCode.EDITORIAL_PLAN_SCHEMA_INVALID,
                        "editorial-plan.json")));
        String[] arguments = validArguments(command);
        arguments[2] = "--confirm-publication-id=wrong-must-not-win";
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exitCode = cli().runSafely(arguments, output);

        assertThat(exitCode).isEqualTo(LegalManifestStatus.BLOCKED.exitCode());
        assertThat(output.toString(StandardCharsets.UTF_8))
                .contains(LegalManifestIssueCode.EDITORIAL_PLAN_SCHEMA_INVALID.name())
                .contains("\"plan\":null")
                .doesNotContain("wrong-must-not-win");
        verify(editorialPlanValidator).validate(EDITORIAL_PLAN_PATH);
        verifyNoInteractions(
                replaceScopeGuard,
                environmentResolver,
                contextFactory,
                planService,
                applyService);
    }

    @ParameterizedTest
    @MethodSource("editorialPlanCommandsAndTargetMismatches")
    void editorialPlanTargetBindingMismatchStopsBeforeScopeEnvironmentSpringAndJdbc(
            Command command,
            PlanTargetMismatch mismatch) {
        OperationType operationType = command.editorialPlanOperationType().orElseThrow();
        when(editorialPlan.operationType()).thenReturn(operationType);
        when(editorialPlanModel.expectedReadinessAfter()).thenReturn(
                operationType == OperationType.RETIRE
                        ? LegalEditorialReadiness.NOT_READY
                        : LegalEditorialReadiness.READY);
        when(validator.validate(manifestPath))
                .thenReturn(LegalManifestValidation.pass(release));
        when(editorialPlanValidator.validate(EDITORIAL_PLAN_PATH))
                .thenReturn(LegalManifestValidation.pass(editorialPlan));
        String mismatchedValue = switch (mismatch) {
            case PUBLICATION_ID -> {
                String value = "release-for-a-different-bundle";
                when(editorialPlanModel.targetPublicationId()).thenReturn(value);
                yield value;
            }
            case MANIFEST_SHA256 -> {
                String value = differentSha256();
                when(editorialPlanModel.targetManifestSha256()).thenReturn(value);
                yield value;
            }
        };
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exitCode = cli().runSafely(
                validArguments(command),
                output);

        assertThat(exitCode).isEqualTo(LegalManifestStatus.BLOCKED.exitCode());
        String json = output.toString(StandardCharsets.UTF_8);
        assertThat(json)
                .contains(
                        LegalManifestIssueCode.REPLACEMENT_MAPPING_INVALID.name(),
                        "cli/editorial/plan-binding",
                        OPERATION_ID.toString(),
                        EDITORIAL_PLAN_SHA256,
                        "\"publicationUuid\":null",
                        "\"changeRequired\":null",
                        "\"observedAt\":null")
                .doesNotContain(mismatchedValue, "jdbc:postgresql");
        verify(editorialPlanValidator).validate(EDITORIAL_PLAN_PATH);
        verifyNoInteractions(
                replaceScopeGuard,
                environmentResolver,
                contextFactory,
                context,
                planService,
                applyService);
    }

    @ParameterizedTest
    @EnumSource(
            value = Command.class,
            names = {"PLAN_REPLACE", "APPLY_REPLACE"})
    void manyToManyReplaceRetainsConfirmedIdentityWithoutResolvingEnvironment(
            Command command) {
        when(validator.validate(manifestPath))
                .thenReturn(LegalManifestValidation.pass(release));
        when(editorialPlanValidator.validate(EDITORIAL_PLAN_PATH))
                .thenReturn(LegalManifestValidation.pass(editorialPlan));
        replaceScopeGuard = new LegalEditorialReplaceScopeGuard();
        when(editorialPlanModel.documentReplacementBatches())
                .thenReturn(List.of(manyToManyBatch()));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exitCode = cli().runSafely(validArguments(command), output);

        assertThat(exitCode).isEqualTo(LegalManifestStatus.BLOCKED.exitCode());
        String json = output.toString(StandardCharsets.UTF_8);
        assertThat(json)
                .contains(OPERATION_ID.toString(), EDITORIAL_PLAN_SHA256)
                .contains(
                        LegalManifestIssueCode.REPLACEMENT_MAPPING_INVALID.name(),
                        "documentReplacementBatches")
                .contains("\"changeRequired\":null", "\"observedAt\":null")
                .doesNotContain("publicationUuid\":\"", "jdbc:postgresql");
        verifyNoInteractions(
                environmentResolver,
                contextFactory,
                context,
                planService,
                applyService);
    }

    private static DocumentReplacementBatch manyToManyBatch() {
        return new DocumentReplacementBatch(
                UUID.fromString("00000000-0000-0000-0000-000000000301"),
                List.of(ContextoLegal.USO_CONTINUADO),
                List.of(
                        documentRef("00000000-0000-0000-0000-000000000302", 'a'),
                        documentRef("00000000-0000-0000-0000-000000000303", 'b')),
                List.of(
                        documentRef("00000000-0000-0000-0000-000000000304", 'c'),
                        documentRef("00000000-0000-0000-0000-000000000305", 'd')));
    }

    private static DocumentRef documentRef(String id, char digestCharacter) {
        return new DocumentRef(
                UUID.fromString(id),
                String.valueOf(digestCharacter).repeat(64));
    }

    private LegalManifestCli cli() {
        return new LegalManifestCli(
                validator,
                mock(LegalManifestReportWriter.class),
                mock(LegalManifestImportReportWriter.class),
                () -> {
                    throw new AssertionError("El boundary import no debe participar");
                },
                ignored -> {
                    throw new AssertionError("El contexto import no debe participar");
                },
                new LegalEditorialReportWriter(),
                editorialPlanValidator,
                replaceScopeGuard,
                environmentResolver,
                contextFactory);
    }

    private void stubServiceInvocation(Command command) {
        IllegalStateException expectedBoundary = new IllegalStateException(
                "expected-service-boundary");
        switch (command) {
            case READINESS -> {
                when(context.getBean(LegalEditorialReadinessService.class))
                        .thenReturn(readinessService);
                when(readinessService.evaluate(release)).thenThrow(expectedBoundary);
            }
            case PLAN_PROMOTE -> {
                when(context.getBean(LegalEditorialPlanService.class))
                        .thenReturn(planService);
                when(planService.planPromote(release)).thenThrow(expectedBoundary);
            }
            case APPLY_PROMOTE -> {
                when(context.getBean(LegalEditorialApplyService.class))
                        .thenReturn(applyService);
                when(applyService.applyPromote(release)).thenThrow(expectedBoundary);
            }
            case PLAN_REPLACE -> {
                when(context.getBean(LegalEditorialPlanService.class))
                        .thenReturn(planService);
                when(planService.planReplace(release, editorialPlan))
                        .thenThrow(expectedBoundary);
            }
            case APPLY_REPLACE -> {
                when(context.getBean(LegalEditorialApplyService.class))
                        .thenReturn(applyService);
                when(applyService.applyReplace(release, editorialPlan))
                        .thenThrow(expectedBoundary);
            }
            case PLAN_RETIRE -> {
                when(context.getBean(LegalEditorialPlanService.class))
                        .thenReturn(planService);
                when(planService.planRetire(release, editorialPlan))
                        .thenThrow(expectedBoundary);
            }
            case APPLY_RETIRE -> {
                when(context.getBean(LegalEditorialApplyService.class))
                        .thenReturn(applyService);
                when(applyService.applyRetire(release, editorialPlan))
                        .thenThrow(expectedBoundary);
            }
        }
    }

    private void verifyServiceInvocationInOrder(Command command, InOrder order) {
        switch (command) {
            case READINESS -> {
                order.verify(context).getBean(LegalEditorialReadinessService.class);
                order.verify(readinessService).evaluate(release);
            }
            case PLAN_PROMOTE -> {
                order.verify(context).getBean(LegalEditorialPlanService.class);
                order.verify(planService).planPromote(release);
            }
            case APPLY_PROMOTE -> {
                order.verify(context).getBean(LegalEditorialApplyService.class);
                order.verify(applyService).applyPromote(release);
            }
            case PLAN_REPLACE -> {
                order.verify(context).getBean(LegalEditorialPlanService.class);
                order.verify(planService).planReplace(release, editorialPlan);
            }
            case APPLY_REPLACE -> {
                order.verify(context).getBean(LegalEditorialApplyService.class);
                order.verify(applyService).applyReplace(release, editorialPlan);
            }
            case PLAN_RETIRE -> {
                order.verify(context).getBean(LegalEditorialPlanService.class);
                order.verify(planService).planRetire(release, editorialPlan);
            }
            case APPLY_RETIRE -> {
                order.verify(context).getBean(LegalEditorialApplyService.class);
                order.verify(applyService).applyRetire(release, editorialPlan);
            }
        }
    }

    private Object serviceFor(Command command) {
        return switch (command) {
            case READINESS -> readinessService;
            case PLAN_PROMOTE -> planService;
            case APPLY_PROMOTE -> applyService;
            case PLAN_REPLACE -> planService;
            case APPLY_REPLACE -> applyService;
            case PLAN_RETIRE -> planService;
            case APPLY_RETIRE -> applyService;
        };
    }

    private void verifyNoEnvironmentContextOrServiceInteractions() {
        verifyNoInteractions(
                environmentResolver,
                contextFactory,
                context,
                readinessService,
                planService,
                applyService);
    }

    private static String[] validArguments(Command command) {
        if (command.requiresEditorialPlan()) {
            return new String[]{
                    command.externalValue(),
                    "--manifest=" + manifestPath,
                    "--confirm-publication-id=" + release.plan().manifest().publicationId(),
                    "--confirm-manifest-sha256=" + release.plan().manifestSha256(),
                    "--editorial-plan=" + EDITORIAL_PLAN_PATH,
                    "--confirm-operation-id=" + OPERATION_ID,
                    "--confirm-editorial-plan-sha256=" + EDITORIAL_PLAN_SHA256
            };
        }
        return new String[]{
                command.externalValue(),
                "--manifest=" + manifestPath,
                "--confirm-publication-id=" + release.plan().manifest().publicationId(),
                "--confirm-manifest-sha256=" + release.plan().manifestSha256()
        };
    }

    private void stubSuccessfulPlanPreflight(Command command) {
        if (!command.requiresEditorialPlan()) {
            return;
        }
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

    private ValidatedEditorialPlan mismatchedPlan(
            Command command,
            PlanConfirmationMismatch mismatch) {
        LegalEditorialPlanV1 planModel = mock(LegalEditorialPlanV1.class);
        OperationType expectedType = command.editorialPlanOperationType().orElseThrow();
        when(planModel.expectedReadinessAfter()).thenReturn(
                expectedType == OperationType.RETIRE
                        ? LegalEditorialReadiness.NOT_READY
                        : LegalEditorialReadiness.READY);
        ValidatedEditorialPlan mismatched = mock(ValidatedEditorialPlan.class);
        when(mismatched.plan()).thenReturn(planModel);
        when(mismatched.operationType()).thenReturn(
                mismatch == PlanConfirmationMismatch.TYPE
                        ? expectedType == OperationType.REPLACE
                                ? OperationType.RETIRE
                                : OperationType.REPLACE
                        : expectedType);
        when(mismatched.operationId()).thenReturn(
                mismatch == PlanConfirmationMismatch.OPERATION_ID
                        ? UUID.fromString("00000000-0000-0000-0000-000000000011")
                        : OPERATION_ID);
        when(mismatched.editorialPlanSha256()).thenReturn(
                mismatch == PlanConfirmationMismatch.SHA256
                        ? "a".repeat(64)
                        : EDITORIAL_PLAN_SHA256);
        return mismatched;
    }

    private static String[] mismatchedArguments(
            Command command,
            ConfirmationMismatch mismatch) {
        String[] arguments = validArguments(command);
        switch (mismatch) {
            case PUBLICATION_ID -> arguments[2] =
                    "--confirm-publication-id=wrong-publication-id";
            case MANIFEST_SHA256 -> arguments[3] =
                    "--confirm-manifest-sha256=" + differentSha256();
        }
        return arguments;
    }

    private static String differentSha256() {
        String firstCandidate = "a".repeat(64);
        return firstCandidate.equals(release.plan().manifestSha256())
                ? "b".repeat(64)
                : firstCandidate;
    }

    private static Map<String, String> databaseEnvironment(boolean enableMutation) {
        Map<String, String> variables = new HashMap<>();
        variables.put(
                LegalEditorialEnvironment.URL_VARIABLE,
                "jdbc:postgresql://localhost/legal");
        variables.put(LegalEditorialEnvironment.USERNAME_VARIABLE, "legal_editor");
        variables.put(LegalEditorialEnvironment.PASSWORD_VARIABLE, "private-password");
        if (enableMutation) {
            variables.put(LegalEditorialEnvironment.ENABLED_VARIABLE, "true");
        }
        return variables;
    }

    private static Stream<Arguments> commandsAndConfirmationMismatches() {
        return Stream.of(Command.values()).flatMap(command ->
                Stream.of(ConfirmationMismatch.values()).map(mismatch ->
                        Arguments.of(command, mismatch)));
    }

    private static Stream<Arguments> editorialPlanCommandsAndPlanConfirmationMismatches() {
        return Stream.of(
                        Command.PLAN_REPLACE,
                        Command.APPLY_REPLACE,
                        Command.PLAN_RETIRE,
                        Command.APPLY_RETIRE)
                .flatMap(command ->
                Stream.of(PlanConfirmationMismatch.values()).map(mismatch ->
                        Arguments.of(command, mismatch)));
    }

    private static Stream<Arguments> editorialPlanCommandsAndTargetMismatches() {
        return Stream.of(
                        Command.PLAN_REPLACE,
                        Command.APPLY_REPLACE,
                        Command.PLAN_RETIRE,
                        Command.APPLY_RETIRE)
                .flatMap(command ->
                Stream.of(PlanTargetMismatch.values()).map(mismatch ->
                        Arguments.of(command, mismatch)));
    }

    private static LegalManifestIssue issue(
            LegalManifestIssueCode code,
            String location) {
        return LegalManifestIssue.at(code, location);
    }

    private enum ConfirmationMismatch {
        PUBLICATION_ID,
        MANIFEST_SHA256
    }

    private enum PlanConfirmationMismatch {
        TYPE,
        OPERATION_ID,
        SHA256
    }

    private enum PlanTargetMismatch {
        PUBLICATION_ID,
        MANIFEST_SHA256
    }
}
