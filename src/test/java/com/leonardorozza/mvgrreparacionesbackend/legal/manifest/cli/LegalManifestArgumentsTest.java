package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli.LegalManifestArguments.Command;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class LegalManifestArgumentsTest {

    private static final String ISSUE_LOCATION = "cli/arguments";

    @ParameterizedTest
    @EnumSource(Command.class)
    void parsesEverySupportedCommandAndPreservesTheManifestPath(Command command) {
        Path manifest = Path.of("release-valid-v1", "publication-manifest.json");

        LegalManifestValidation<LegalManifestArguments> result = LegalManifestArguments.parse(
                new String[]{command.externalValue(), "--manifest=" + manifest});

        assertThat(result.status()).isEqualTo(LegalManifestStatus.PASS);
        assertThat(result.issues()).isEmpty();
        assertThat(result.omittedIssueCount()).isZero();
        assertThat(result.value()).contains(new LegalManifestArguments(command, manifest));
    }

    @Test
    void exposesOnlyTheTwoStableCaseSensitiveExternalCommands() {
        assertThat(Command.values())
                .extracting(Command::externalValue)
                .containsExactly("validate", "dry-run");
    }

    @Test
    void keepsEqualsAndSpacesThatBelongToANonEmptyPath() {
        String suppliedPath = "release = 2026/publication-manifest.json?digest=a=b";

        LegalManifestValidation<LegalManifestArguments> result = LegalManifestArguments.parse(
                new String[]{"validate", "--manifest=" + suppliedPath});

        assertThat(result.value().orElseThrow().manifestPath()).isEqualTo(Path.of(suppliedPath));
    }

    @ParameterizedTest
    @MethodSource("missingManifestArguments")
    void reportsTheRequiredManifestWithoutExposingInput(String[] arguments) {
        assertBlocked(
                LegalManifestArguments.parse(arguments),
                LegalManifestIssueCode.MANIFEST_PATH_REQUIRED);
    }

    @Test
    void rejectsANullArgumentArrayAsTypedInputFailure() {
        assertBlocked(
                LegalManifestArguments.parse(null),
                LegalManifestIssueCode.CLI_ARGUMENTS_INVALID);
    }

    @ParameterizedTest
    @MethodSource("invalidArgumentShapes")
    void rejectsUnknownSeparatedDuplicatedAndExtraArguments(String[] arguments) {
        assertBlocked(
                LegalManifestArguments.parse(arguments),
                LegalManifestIssueCode.CLI_ARGUMENTS_INVALID);
    }

    @Test
    void rejectsAnInvalidPlatformPathWithoutLeakingItsValue() {
        String invalidPath = "private-" + '\0' + "manifest.json";

        LegalManifestValidation<LegalManifestArguments> result = LegalManifestArguments.parse(
                new String[]{"validate", "--manifest=" + invalidPath});

        assertBlocked(result, LegalManifestIssueCode.CLI_ARGUMENTS_INVALID);
        assertThat(result.issues()).allSatisfy(issue -> {
            assertThat(issue.message()).doesNotContain("private-");
            assertThat(issue.location()).doesNotContain("private-");
        });
    }

    @Test
    void rejectsPasswordAndSpringPropertiesWithoutEchoingSecretsOrPaths() {
        String secret = "never-print-this-password";
        String privatePath = "/private/customer-a/release/publication-manifest.json";

        LegalManifestValidation<LegalManifestArguments> result = LegalManifestArguments.parse(
                new String[]{
                        "dry-run",
                        "--manifest=" + privatePath,
                        "--spring.datasource.password=" + secret
                });

        assertBlocked(result, LegalManifestIssueCode.CLI_ARGUMENTS_INVALID);
        assertThat(result.issues()).allSatisfy(issue -> {
            assertThat(issue.location()).doesNotContain(privatePath, secret);
            assertThat(issue.message()).doesNotContain(privatePath, secret);
            assertThat(issue.toString()).doesNotContain(privatePath, secret);
        });
    }

    private static Stream<Arguments> missingManifestArguments() {
        return Stream.of(
                cliArguments("validate"),
                cliArguments("dry-run"),
                cliArguments("validate", "--manifest="),
                cliArguments("dry-run", "--manifest=   "),
                cliArguments("validate", "--manifest=\t"));
    }

    private static Stream<Arguments> invalidArgumentShapes() {
        return Stream.of(
                cliArguments(),
                cliArguments(null, "--manifest=publication-manifest.json"),
                cliArguments("", "--manifest=publication-manifest.json"),
                cliArguments("VALIDATE", "--manifest=publication-manifest.json"),
                cliArguments("dry_run", "--manifest=publication-manifest.json"),
                cliArguments("validate", null),
                cliArguments("validate", ""),
                cliArguments("validate", "--manifest"),
                cliArguments("validate", "--manifest", "publication-manifest.json"),
                cliArguments(
                        "validate",
                        "--manifest=publication-manifest.json",
                        "--manifest=other.json"),
                cliArguments("validate", "publication-manifest.json"),
                cliArguments("validate", "--unknown=publication-manifest.json"),
                cliArguments("validate", "--manifest=publication-manifest.json", "extra"),
                cliArguments(
                        "--spring.main.web-application-type=none",
                        "--manifest=publication-manifest.json"),
                cliArguments("dry-run", "--spring.datasource.password=secret"),
                cliArguments(
                        "dry-run",
                        "--password=secret",
                        "--manifest=publication-manifest.json"),
                cliArguments("dry-run", "--manifest=--spring.datasource.password=secret"));
    }

    private static Arguments cliArguments(String... arguments) {
        return Arguments.of((Object) arguments);
    }

    private static void assertBlocked(
            LegalManifestValidation<LegalManifestArguments> result,
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
