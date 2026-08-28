package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli.LegalEditorialArguments.Command;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidation;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalEditorialApplyService;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalEditorialPlanService;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalEditorialReadinessService;
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
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
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

    private static ValidatedRelease release;
    private static Path manifestPath;

    private LegalManifestValidator validator;
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
        verifyNoEnvironmentContextOrServiceInteractions();
    }

    @ParameterizedTest(name = "{0} blocks a {1} mismatch before credentials and Spring")
    @MethodSource("commandsAndConfirmationMismatches")
    void everyConfirmationMismatchStopsBeforeEnvironmentContextAndService(
            Command command,
            ConfirmationMismatch mismatch) {
        when(validator.validate(manifestPath))
                .thenReturn(LegalManifestValidation.pass(release));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exitCode = cli().runSafely(mismatchedArguments(command, mismatch), output);

        assertThat(exitCode).isEqualTo(LegalManifestStatus.BLOCKED.exitCode());
        assertThat(output.toString(StandardCharsets.UTF_8))
                .contains(LegalManifestIssueCode.EDITORIAL_CONFIRMATION_MISMATCH.name());
        verify(validator).validate(manifestPath);
        verifyNoEnvironmentContextOrServiceInteractions();
    }

    @ParameterizedTest
    @EnumSource(Command.class)
    void environmentFailureStopsBeforeContextAndService(Command command) {
        when(validator.validate(manifestPath))
                .thenReturn(LegalManifestValidation.pass(release));
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
        InOrder order = inOrder(validator, environmentResolver);
        order.verify(validator).validate(manifestPath);
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
        when(environmentResolver.apply(command))
                .thenReturn(LegalManifestValidation.pass(environment));
        when(contextFactory.apply(environment)).thenReturn(context);
        stubServiceInvocation(command);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exitCode = cli().runSafely(validArguments(command), output);

        assertThat(exitCode).isEqualTo(LegalManifestStatus.ERROR.exitCode());
        InOrder order = inOrder(
                validator,
                environmentResolver,
                contextFactory,
                context,
                serviceFor(command));
        order.verify(validator).validate(manifestPath);
        order.verify(environmentResolver).apply(command);
        order.verify(contextFactory).apply(environment);
        verifyServiceInvocationInOrder(command, order);
        order.verify(context).close();
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
        }
    }

    private Object serviceFor(Command command) {
        return switch (command) {
            case READINESS -> readinessService;
            case PLAN_PROMOTE -> planService;
            case APPLY_PROMOTE -> applyService;
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
        return new String[]{
                command.externalValue(),
                "--manifest=" + manifestPath,
                "--confirm-publication-id=" + release.plan().manifest().publicationId(),
                "--confirm-manifest-sha256=" + release.plan().manifestSha256()
        };
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

    private static LegalManifestIssue issue(
            LegalManifestIssueCode code,
            String location) {
        return LegalManifestIssue.at(code, location);
    }

    private enum ConfirmationMismatch {
        PUBLICATION_ID,
        MANIFEST_SHA256
    }
}
