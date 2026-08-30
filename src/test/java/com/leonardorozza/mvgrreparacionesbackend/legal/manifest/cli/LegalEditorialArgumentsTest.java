package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli.LegalEditorialArguments.Command;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class LegalEditorialArgumentsTest {

    private static final String ISSUE_LOCATION = "cli/editorial/arguments";
    private static final String SHA256 =
            "b3b452c8f6f4deb459c31524466936f50265c165c772d50ea75364b8c9c6f3e1";
    private static final String MANIFEST =
            "--manifest=release-valid-v1/publication-manifest.json";
    private static final String PUBLICATION =
            "--confirm-publication-id=release-valid-v1";
    private static final String CONFIRM_SHA = "--confirm-manifest-sha256=" + SHA256;
    private static final String EDITORIAL_PLAN =
            "--editorial-plan=replace-one-to-one-v1/editorial-plan.json";
    private static final UUID OPERATION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final String PLAN_SHA256 =
            "534ef5a63292484c4cde6fc2fa6735f7d42dbc40a3df671a6f9550626aebe49c";
    private static final String CONFIRM_OPERATION =
            "--confirm-operation-id=" + OPERATION_ID;
    private static final String CONFIRM_PLAN_SHA =
            "--confirm-editorial-plan-sha256=" + PLAN_SHA256;

    @Test
    void exposesTheFiveCaseSensitiveCommandsAndTheirMutationMode() {
        assertThat(Command.values())
                .extracting(Command::externalValue)
                .containsExactly(
                        "readiness",
                        "plan-promote",
                        "apply-promote",
                        "plan-replace",
                        "apply-replace");
        assertThat(Command.READINESS.mutating()).isFalse();
        assertThat(Command.PLAN_PROMOTE.mutating()).isFalse();
        assertThat(Command.APPLY_PROMOTE.mutating()).isTrue();
        assertThat(Command.PLAN_REPLACE.mutating()).isFalse();
        assertThat(Command.PLAN_REPLACE.requiresEditorialPlan()).isTrue();
        assertThat(Command.APPLY_REPLACE.mutating()).isTrue();
        assertThat(Command.APPLY_REPLACE.requiresEditorialPlan()).isTrue();
    }

    @ParameterizedTest
    @MethodSource("validArgumentOrders")
    void parsesEveryNamedArgumentExactlyOnceInAnyOrder(
            Command command,
            String[] arguments) {
        LegalManifestValidation<LegalEditorialArguments> result =
                LegalEditorialArguments.parse(arguments);

        assertThat(result.status()).isEqualTo(LegalManifestStatus.PASS);
        assertThat(result.issues()).isEmpty();
        assertThat(result.omittedIssueCount()).isZero();
        LegalEditorialArguments parsed = result.value().orElseThrow();
        assertThat(parsed.command()).isEqualTo(command);
        assertThat(parsed.manifestPath())
                .isEqualTo(Path.of("release-valid-v1", "publication-manifest.json"));
        assertThat(parsed.confirmation()).isEqualTo(
                new LegalEditorialConfirmation("release-valid-v1", SHA256));
        assertThat(parsed.editorialPlanPath()).isEmpty();
        assertThat(parsed.planConfirmation()).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("validReplaceArgumentOrders")
    void parsesEveryReplaceArgumentExactlyOnceInAnyOrder(
            Command command,
            String[] arguments) {
        LegalManifestValidation<LegalEditorialArguments> result =
                LegalEditorialArguments.parse(arguments);

        assertThat(result.status()).isEqualTo(LegalManifestStatus.PASS);
        LegalEditorialArguments parsed = result.value().orElseThrow();
        assertThat(parsed.command()).isEqualTo(command);
        assertThat(parsed.manifestPath())
                .isEqualTo(Path.of("release-valid-v1", "publication-manifest.json"));
        assertThat(parsed.editorialPlanPath())
                .contains(Path.of("replace-one-to-one-v1", "editorial-plan.json"));
        assertThat(parsed.confirmation()).isEqualTo(
                new LegalEditorialConfirmation("release-valid-v1", SHA256));
        assertThat(parsed.planConfirmation()).contains(
                new LegalEditorialPlanConfirmation(OPERATION_ID, PLAN_SHA256));
    }

    @Test
    void keepsEqualsAndSpacesThatBelongToANonEmptyManifestPath() {
        String path = "release = 2026/publication-manifest.json?digest=a=b";

        LegalManifestValidation<LegalEditorialArguments> result =
                LegalEditorialArguments.parse(new String[]{
                        "readiness",
                        "--manifest=" + path,
                        PUBLICATION,
                        CONFIRM_SHA
                });

        assertThat(result.value().orElseThrow().manifestPath()).isEqualTo(Path.of(path));
    }

    @ParameterizedTest
    @MethodSource("missingManifestArguments")
    void reportsTheRequiredManifestWithoutEchoingInput(String[] arguments) {
        assertBlocked(
                LegalEditorialArguments.parse(arguments),
                LegalManifestIssueCode.MANIFEST_PATH_REQUIRED);
    }

    @Test
    void rejectsANullArgumentArrayAsTypedInputFailure() {
        assertBlocked(
                LegalEditorialArguments.parse(null),
                LegalManifestIssueCode.CLI_ARGUMENTS_INVALID);
    }

    @ParameterizedTest
    @MethodSource("invalidArgumentShapes")
    void rejectsUnknownSeparatedDuplicatedExtraAndNonCanonicalArguments(String[] arguments) {
        assertBlocked(
                LegalEditorialArguments.parse(arguments),
                LegalManifestIssueCode.CLI_ARGUMENTS_INVALID);
    }

    @ParameterizedTest
    @MethodSource("missingEditorialPlanArguments")
    void reportsTheRequiredEditorialPlanPathWithoutEchoingInput(String[] arguments) {
        assertBlocked(
                LegalEditorialArguments.parse(arguments),
                LegalManifestIssueCode.EDITORIAL_PLAN_PATH_REQUIRED);
    }

    @Test
    void rejectsAnInvalidPlatformPathWithoutLeakingItsValue() {
        String privatePath = "private-" + '\0' + "publication-manifest.json";

        LegalManifestValidation<LegalEditorialArguments> result =
                LegalEditorialArguments.parse(new String[]{
                        "plan-promote",
                        "--manifest=" + privatePath,
                        PUBLICATION,
                        CONFIRM_SHA
                });

        assertBlocked(result, LegalManifestIssueCode.CLI_ARGUMENTS_INVALID);
        assertThat(result.issues()).allSatisfy(issue ->
                assertThat(issue.toString()).doesNotContain("private-"));
    }

    @Test
    void rejectsPasswordForcePlanAndSpringArgumentsWithoutEchoingValues() {
        String secret = "never-print-editor-password";
        String privatePath = "/private/editor/release/publication-manifest.json";

        LegalManifestValidation<LegalEditorialArguments> result =
                LegalEditorialArguments.parse(new String[]{
                        "apply-promote",
                        "--manifest=" + privatePath,
                        PUBLICATION,
                        CONFIRM_SHA,
                        "--spring.datasource.password=" + secret
                });

        assertBlocked(result, LegalManifestIssueCode.CLI_ARGUMENTS_INVALID);
        assertThat(result.issues()).allSatisfy(issue ->
                assertThat(issue.toString()).doesNotContain(privatePath, secret));
    }

    @Test
    void diagnosticStringRedactsPathsIdsAndHashes() {
        String privatePath = "/private/editor/release/publication-manifest.json";
        LegalEditorialArguments arguments = new LegalEditorialArguments(
                Command.APPLY_PROMOTE,
                Path.of(privatePath),
                new LegalEditorialConfirmation("private-publication", SHA256));

        assertThat(arguments.toString())
                .contains("apply-promote", "redacted")
                .doesNotContain(privatePath, "private-publication", SHA256);
    }

    @Test
    void replaceDiagnosticStringRedactsBothPathsIdsAndHashes() {
        String privateManifest = "/private/editor/release/publication-manifest.json";
        String privatePlan = "/private/editor/plan/editorial-plan.json";
        for (Command command : List.of(Command.PLAN_REPLACE, Command.APPLY_REPLACE)) {
            LegalEditorialArguments arguments = new LegalEditorialArguments(
                    command,
                    Path.of(privateManifest),
                    java.util.Optional.of(Path.of(privatePlan)),
                    new LegalEditorialConfirmation("private-publication", SHA256),
                    java.util.Optional.of(
                            new LegalEditorialPlanConfirmation(OPERATION_ID, PLAN_SHA256)));

            assertThat(arguments.toString())
                    .contains(command.externalValue(), "redacted")
                    .doesNotContain(
                            privateManifest,
                            privatePlan,
                            "private-publication",
                            OPERATION_ID.toString(),
                            SHA256,
                            PLAN_SHA256);
        }
    }

    private static Stream<Arguments> validArgumentOrders() {
        List<String[]> permutations = List.of(
                new String[]{MANIFEST, PUBLICATION, CONFIRM_SHA},
                new String[]{MANIFEST, CONFIRM_SHA, PUBLICATION},
                new String[]{PUBLICATION, MANIFEST, CONFIRM_SHA},
                new String[]{PUBLICATION, CONFIRM_SHA, MANIFEST},
                new String[]{CONFIRM_SHA, MANIFEST, PUBLICATION},
                new String[]{CONFIRM_SHA, PUBLICATION, MANIFEST});
        List<Arguments> cases = new ArrayList<>();
        for (Command command : List.of(
                Command.READINESS,
                Command.PLAN_PROMOTE,
                Command.APPLY_PROMOTE)) {
            for (String[] permutation : permutations) {
                cases.add(Arguments.of(
                        command,
                        cliArguments(command.externalValue(), permutation)));
            }
        }
        return cases.stream();
    }

    private static Stream<Arguments> validReplaceArgumentOrders() {
        List<String> namedArguments = List.of(
                MANIFEST,
                EDITORIAL_PLAN,
                PUBLICATION,
                CONFIRM_SHA,
                CONFIRM_OPERATION,
                CONFIRM_PLAN_SHA);
        List<Arguments> cases = new ArrayList<>();
        for (Command command : List.of(Command.PLAN_REPLACE, Command.APPLY_REPLACE)) {
            addPermutations(command, namedArguments, new ArrayList<>(), cases);
        }
        return cases.stream();
    }

    private static void addPermutations(
            Command command,
            List<String> remaining,
            List<String> ordered,
            List<Arguments> cases) {
        if (remaining.isEmpty()) {
            cases.add(Arguments.of(
                    command,
                    cliArguments(command.externalValue(), ordered.toArray(String[]::new))));
            return;
        }
        for (int index = 0; index < remaining.size(); index++) {
            List<String> nextRemaining = new ArrayList<>(remaining);
            String next = nextRemaining.remove(index);
            List<String> nextOrdered = new ArrayList<>(ordered);
            nextOrdered.add(next);
            addPermutations(command, nextRemaining, nextOrdered, cases);
        }
    }

    private static Stream<Arguments> missingManifestArguments() {
        return Stream.<String[]>of(
                cliArguments("readiness"),
                cliArguments("plan-promote"),
                cliArguments("apply-promote"),
                cliArguments("plan-replace"),
                cliArguments("apply-replace"),
                cliArguments("readiness", "--manifest=", PUBLICATION, CONFIRM_SHA),
                cliArguments("plan-promote", "--manifest=   ", PUBLICATION, CONFIRM_SHA),
                cliArguments("apply-promote", "--manifest=\t", PUBLICATION, CONFIRM_SHA),
                cliArguments(
                        "plan-replace",
                        "--manifest= ",
                        EDITORIAL_PLAN,
                        PUBLICATION,
                        CONFIRM_SHA,
                        CONFIRM_OPERATION,
                        CONFIRM_PLAN_SHA),
                cliArguments(
                        "apply-replace",
                        "--manifest= ",
                        EDITORIAL_PLAN,
                        PUBLICATION,
                        CONFIRM_SHA,
                        CONFIRM_OPERATION,
                        CONFIRM_PLAN_SHA))
                .map(arguments -> Arguments.of((Object) arguments));
    }

    private static Stream<Arguments> invalidArgumentShapes() {
        return Stream.<String[]>of(
                cliArguments(),
                cliArguments(null, MANIFEST, PUBLICATION, CONFIRM_SHA),
                cliArguments("READINESS", MANIFEST, PUBLICATION, CONFIRM_SHA),
                cliArguments("plan_promote", MANIFEST, PUBLICATION, CONFIRM_SHA),
                cliArguments("apply", MANIFEST, PUBLICATION, CONFIRM_SHA),
                cliArguments("PLAN-REPLACE", MANIFEST, PUBLICATION, CONFIRM_SHA),
                cliArguments("APPLY-REPLACE", MANIFEST, PUBLICATION, CONFIRM_SHA),
                cliArguments("readiness", null, PUBLICATION, CONFIRM_SHA),
                cliArguments("readiness", "--manifest", PUBLICATION, CONFIRM_SHA),
                cliArguments("readiness", "--manifest", "publication-manifest.json", PUBLICATION),
                cliArguments("readiness", "publication-manifest.json", PUBLICATION, CONFIRM_SHA),
                cliArguments("readiness", "--manifest=--password=secret", PUBLICATION, CONFIRM_SHA),
                cliArguments("readiness", MANIFEST, PUBLICATION),
                cliArguments("readiness", MANIFEST, PUBLICATION, CONFIRM_SHA, "extra"),
                cliArguments("readiness", MANIFEST, MANIFEST, CONFIRM_SHA),
                cliArguments("readiness", MANIFEST, PUBLICATION, PUBLICATION),
                cliArguments("readiness", MANIFEST, CONFIRM_SHA, CONFIRM_SHA),
                cliArguments("readiness", MANIFEST, "--confirm-publication-id=", CONFIRM_SHA),
                cliArguments("readiness", MANIFEST, "--confirm-publication-id=   ", CONFIRM_SHA),
                cliArguments("readiness", MANIFEST, PUBLICATION, "--confirm-manifest-sha256="),
                cliArguments(
                        "readiness",
                        MANIFEST,
                        PUBLICATION,
                        "--confirm-manifest-sha256=" + SHA256.toUpperCase()),
                cliArguments(
                        "readiness",
                        MANIFEST,
                        PUBLICATION,
                        "--confirm-manifest-sha256=" + SHA256.substring(1)),
                cliArguments("readiness", MANIFEST, PUBLICATION, "--force"),
                cliArguments("readiness", MANIFEST, PUBLICATION, "--editorial-plan=plan.json"),
                cliArguments("readiness", MANIFEST, PUBLICATION, "--password=secret"),
                cliArguments(
                        "readiness",
                        MANIFEST,
                        PUBLICATION,
                        "--spring.datasource.url=jdbc:private"),
                replaceArguments("--editorial-plan=--password=secret"),
                replaceArguments("--confirm-operation-id="),
                replaceArguments("--confirm-operation-id=0-0-0-0-10"),
                replaceArguments(
                        "--confirm-operation-id=ABCDEFAB-0000-0000-0000-000000000010"),
                replaceArguments("--confirm-editorial-plan-sha256="),
                replaceArguments("--confirm-editorial-plan-sha256=" + PLAN_SHA256.toUpperCase()),
                replaceArguments("--confirm-editorial-plan-sha256=" + PLAN_SHA256.substring(1)),
                cliArguments(
                        "plan-replace",
                        MANIFEST,
                        EDITORIAL_PLAN,
                        PUBLICATION,
                        CONFIRM_SHA,
                        CONFIRM_OPERATION),
                cliArguments(
                        "plan-replace",
                        MANIFEST,
                        EDITORIAL_PLAN,
                        PUBLICATION,
                        CONFIRM_SHA,
                        CONFIRM_OPERATION,
                        CONFIRM_PLAN_SHA,
                        "extra"),
                cliArguments(
                        "plan-replace",
                        MANIFEST,
                        EDITORIAL_PLAN,
                        EDITORIAL_PLAN,
                        CONFIRM_SHA,
                        CONFIRM_OPERATION,
                        CONFIRM_PLAN_SHA),
                cliArguments(
                        "plan-replace",
                        MANIFEST,
                        EDITORIAL_PLAN,
                        PUBLICATION,
                        CONFIRM_SHA,
                        CONFIRM_OPERATION,
                        CONFIRM_OPERATION))
                .map(arguments -> Arguments.of((Object) arguments));
    }

    private static Stream<Arguments> missingEditorialPlanArguments() {
        return Stream.<String[]>of(
                replaceArguments("--editorial-plan="),
                replaceArguments("--editorial-plan=   "),
                replaceArguments(Command.APPLY_REPLACE, "--editorial-plan="),
                replaceArguments(Command.APPLY_REPLACE, "--editorial-plan=   "))
                .map(arguments -> Arguments.of((Object) arguments));
    }

    private static String[] replaceArguments(String replacement) {
        return replaceArguments(Command.PLAN_REPLACE, replacement);
    }

    private static String[] replaceArguments(Command command, String replacement) {
        List<String> arguments = new ArrayList<>(List.of(
                command.externalValue(),
                MANIFEST,
                EDITORIAL_PLAN,
                PUBLICATION,
                CONFIRM_SHA,
                CONFIRM_OPERATION,
                CONFIRM_PLAN_SHA));
        String prefix = replacement.substring(0, replacement.indexOf('=') + 1);
        for (int index = 1; index < arguments.size(); index++) {
            if (arguments.get(index).startsWith(prefix)) {
                arguments.set(index, replacement);
                return arguments.toArray(String[]::new);
            }
        }
        throw new IllegalArgumentException("No se encontró el argumento a reemplazar");
    }

    private static String[] cliArguments(String command, String[] namedArguments) {
        String[] arguments = new String[namedArguments.length + 1];
        arguments[0] = command;
        System.arraycopy(namedArguments, 0, arguments, 1, namedArguments.length);
        return arguments;
    }

    private static String[] cliArguments(String... arguments) {
        return arguments;
    }

    private static void assertBlocked(
            LegalManifestValidation<LegalEditorialArguments> result,
            LegalManifestIssueCode expectedCode) {
        assertThat(result.status()).isEqualTo(LegalManifestStatus.BLOCKED);
        assertThat(result.value()).isEmpty();
        assertThat(result.omittedIssueCount()).isZero();
        assertThat(result.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.code()).isEqualTo(expectedCode);
            assertThat(issue.severity()).isEqualTo(LegalManifestStatus.BLOCKED);
            assertThat(issue.location()).isEqualTo(ISSUE_LOCATION);
            assertThat(issue.message()).isEqualTo(expectedCode.safeMessage());
        });
    }
}
