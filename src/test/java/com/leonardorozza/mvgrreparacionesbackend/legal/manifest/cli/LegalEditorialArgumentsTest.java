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

    @Test
    void exposesOnlyTheThreeInitialCaseSensitiveCommandsAndTheirMutationMode() {
        assertThat(Command.values())
                .extracting(Command::externalValue)
                .containsExactly("readiness", "plan-promote", "apply-promote");
        assertThat(Command.READINESS.mutating()).isFalse();
        assertThat(Command.PLAN_PROMOTE.mutating()).isFalse();
        assertThat(Command.APPLY_PROMOTE.mutating()).isTrue();
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

    private static Stream<Arguments> validArgumentOrders() {
        List<String[]> permutations = List.of(
                new String[]{MANIFEST, PUBLICATION, CONFIRM_SHA},
                new String[]{MANIFEST, CONFIRM_SHA, PUBLICATION},
                new String[]{PUBLICATION, MANIFEST, CONFIRM_SHA},
                new String[]{PUBLICATION, CONFIRM_SHA, MANIFEST},
                new String[]{CONFIRM_SHA, MANIFEST, PUBLICATION},
                new String[]{CONFIRM_SHA, PUBLICATION, MANIFEST});
        List<Arguments> cases = new ArrayList<>();
        for (Command command : Command.values()) {
            for (String[] permutation : permutations) {
                cases.add(Arguments.of(
                        command,
                        cliArguments(command.externalValue(), permutation)));
            }
        }
        return cases.stream();
    }

    private static Stream<Arguments> missingManifestArguments() {
        return Stream.<String[]>of(
                cliArguments("readiness"),
                cliArguments("plan-promote"),
                cliArguments("apply-promote"),
                cliArguments("readiness", "--manifest=", PUBLICATION, CONFIRM_SHA),
                cliArguments("plan-promote", "--manifest=   ", PUBLICATION, CONFIRM_SHA),
                cliArguments("apply-promote", "--manifest=\t", PUBLICATION, CONFIRM_SHA))
                .map(arguments -> Arguments.of((Object) arguments));
    }

    private static Stream<Arguments> invalidArgumentShapes() {
        return Stream.<String[]>of(
                cliArguments(),
                cliArguments(null, MANIFEST, PUBLICATION, CONFIRM_SHA),
                cliArguments("READINESS", MANIFEST, PUBLICATION, CONFIRM_SHA),
                cliArguments("plan_promote", MANIFEST, PUBLICATION, CONFIRM_SHA),
                cliArguments("apply", MANIFEST, PUBLICATION, CONFIRM_SHA),
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
                        "--spring.datasource.url=jdbc:private"))
                .map(arguments -> Arguments.of((Object) arguments));
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
