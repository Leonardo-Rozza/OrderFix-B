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
    private static final String SHA256 =
            "b3b452c8f6f4deb459c31524466936f50265c165c772d50ea75364b8c9c6f3e1";

    @ParameterizedTest
    @EnumSource(value = Command.class, names = {"VALIDATE", "DRY_RUN"})
    void preservesTheExactTwoArgumentContractForV1Commands(Command command) {
        Path manifest = Path.of("release-valid-v1", "publication-manifest.json");

        LegalManifestValidation<LegalManifestArguments> result = LegalManifestArguments.parse(
                new String[]{command.externalValue(), "--manifest=" + manifest});

        assertThat(result.status()).isEqualTo(LegalManifestStatus.PASS);
        assertThat(result.issues()).isEmpty();
        assertThat(result.omittedIssueCount()).isZero();
        assertThat(result.value()).contains(new LegalManifestArguments(command, manifest));
        assertThat(result.value().orElseThrow().importConfirmation()).isEmpty();
    }

    @Test
    void exposesOnlyTheThreeStableCaseSensitiveExternalCommands() {
        assertThat(Command.values())
                .extracting(Command::externalValue)
                .containsExactly("validate", "dry-run", "import");
    }

    @ParameterizedTest
    @MethodSource("validImportArgumentOrders")
    void parsesImportNamedArgumentsExactlyOnceInAnyOrder(String[] arguments) {
        LegalManifestValidation<LegalManifestArguments> result =
                LegalManifestArguments.parse(arguments);

        assertThat(result.status()).isEqualTo(LegalManifestStatus.PASS);
        LegalManifestArguments parsed = result.value().orElseThrow();
        assertThat(parsed.command()).isEqualTo(Command.IMPORT);
        assertThat(parsed.manifestPath())
                .isEqualTo(Path.of("release-valid-v1", "publication-manifest.json"));
        assertThat(parsed.importConfirmation()).contains(
                new LegalManifestImportConfirmation("release-valid-v1", SHA256));
    }

    @Test
    void keepsEqualsAndSpacesThatBelongToANonEmptyV1Path() {
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
    void rejectsUnknownSeparatedDuplicatedExtraAndNonCanonicalArguments(String[] arguments) {
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
    void rejectsImportPasswordForceAndSpringArgumentsWithoutEchoingAnyValue() {
        String secret = "never-print-this-password";
        String privatePath = "/private/customer-a/release/publication-manifest.json";

        LegalManifestValidation<LegalManifestArguments> result = LegalManifestArguments.parse(
                new String[]{
                        "import",
                        "--manifest=" + privatePath,
                        "--confirm-publication-id=release-valid-v1",
                        "--confirm-manifest-sha256=" + SHA256,
                        "--spring.datasource.password=" + secret
                });

        assertBlocked(result, LegalManifestIssueCode.CLI_ARGUMENTS_INVALID);
        assertThat(result.issues()).allSatisfy(issue -> {
            assertThat(issue.location()).doesNotContain(privatePath, secret);
            assertThat(issue.message()).doesNotContain(privatePath, secret);
            assertThat(issue.toString()).doesNotContain(privatePath, secret);
        });
    }

    @Test
    void keepsV1SecretRejectionFreeOfEchoedValues() {
        String secret = "never-print-this-v1-password";
        String privatePath = "/private/v1/release/publication-manifest.json";

        LegalManifestValidation<LegalManifestArguments> result = LegalManifestArguments.parse(
                new String[]{
                        "dry-run",
                        "--manifest=" + privatePath,
                        "--spring.datasource.password=" + secret
                });

        assertBlocked(result, LegalManifestIssueCode.CLI_ARGUMENTS_INVALID);
        assertThat(result.issues()).allSatisfy(issue ->
                assertThat(issue.toString()).doesNotContain(privatePath, secret));
    }

    private static Stream<Arguments> validImportArgumentOrders() {
        String manifest = "--manifest=release-valid-v1/publication-manifest.json";
        String publication = "--confirm-publication-id=release-valid-v1";
        String sha256 = "--confirm-manifest-sha256=" + SHA256;
        return Stream.of(
                cliArguments("import", manifest, publication, sha256),
                cliArguments("import", manifest, sha256, publication),
                cliArguments("import", publication, manifest, sha256),
                cliArguments("import", publication, sha256, manifest),
                cliArguments("import", sha256, manifest, publication),
                cliArguments("import", sha256, publication, manifest));
    }

    private static Stream<Arguments> missingManifestArguments() {
        return Stream.of(
                cliArguments("validate"),
                cliArguments("dry-run"),
                cliArguments("import"),
                cliArguments("validate", "--manifest="),
                cliArguments("dry-run", "--manifest=   "),
                cliArguments("validate", "--manifest=\t"),
                cliArguments(
                        "import",
                        "--manifest=",
                        "--confirm-publication-id=release-valid-v1",
                        "--confirm-manifest-sha256=" + SHA256));
    }

    private static Stream<Arguments> invalidArgumentShapes() {
        return Stream.of(
                cliArguments(),
                cliArguments(null, "--manifest=publication-manifest.json"),
                cliArguments("", "--manifest=publication-manifest.json"),
                cliArguments("VALIDATE", "--manifest=publication-manifest.json"),
                cliArguments("IMPORT", "--manifest=publication-manifest.json"),
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
                cliArguments("dry-run", "--manifest=--spring.datasource.password=secret"),
                cliArguments(validImport(
                        "--confirm-manifest-sha256=" + SHA256.toUpperCase())),
                cliArguments(validImport(
                        "--confirm-manifest-sha256=" + SHA256.substring(1))),
                cliArguments(validImport("--confirm-manifest-sha256=" + SHA256 + "0")),
                cliArguments(validImport("--confirm-manifest-sha256=")),
                cliArguments(validImport("--confirm-publication-id=")),
                cliArguments(validImport("--confirm-publication-id=   ")),
                cliArguments(validImport("--confirm-publication=release-valid-v1")),
                cliArguments(validImport("--manifest-path=publication-manifest.json")),
                cliArguments(validImport("--force")),
                cliArguments(validImport("--password=secret")),
                cliArguments(validImport("--spring.datasource.url=jdbc:private")),
                cliArguments(
                        "import",
                        "--manifest=publication-manifest.json",
                        "--confirm-publication-id=release-valid-v1"),
                cliArguments(
                        "import",
                        "--manifest=publication-manifest.json",
                        "--confirm-publication-id=release-valid-v1",
                        "--confirm-publication-id=other"),
                cliArguments(
                        "import",
                        "--manifest=publication-manifest.json",
                        "--manifest=other.json",
                        "--confirm-manifest-sha256=" + SHA256),
                cliArguments(
                        "import",
                        "--manifest",
                        "publication-manifest.json",
                        "--confirm-publication-id=release-valid-v1",
                        "--confirm-manifest-sha256=" + SHA256));
    }

    private static String[] validImport(String replacement) {
        String[] arguments = {
                "import",
                "--manifest=publication-manifest.json",
                "--confirm-publication-id=release-valid-v1",
                "--confirm-manifest-sha256=" + SHA256
        };
        if (replacement.startsWith("--manifest")) {
            arguments[1] = replacement;
        } else if (replacement.startsWith("--confirm-publication")) {
            arguments[2] = replacement;
        } else {
            arguments[3] = replacement;
        }
        return arguments;
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
