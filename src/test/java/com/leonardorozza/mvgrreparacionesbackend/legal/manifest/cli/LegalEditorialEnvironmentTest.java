package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli.LegalEditorialArguments.Command;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegalEditorialEnvironmentTest {

    private static final String PRIVATE_PASSWORD = "private-editor-password";

    @ParameterizedTest
    @MethodSource("disabledFlagCases")
    void mutatingCommandRequiresTheExactLowercaseEnableFlagBeforeAnyOtherCheck(
            Command command,
            String value) {
        Map<String, String> environment = baseEnvironment();
        if (value == null) {
            environment.remove(LegalEditorialEnvironment.ENABLED_VARIABLE);
        } else {
            environment.put(LegalEditorialEnvironment.ENABLED_VARIABLE, value);
        }
        Properties forbiddenProperties = new Properties();
        forbiddenProperties.put("spring.datasource.password", PRIVATE_PASSWORD);

        LegalManifestValidation<LegalEditorialEnvironment> result =
                LegalEditorialEnvironment.resolve(
                        command,
                        environment,
                        forbiddenProperties);

        assertFailure(result, LegalManifestIssueCode.EDITORIAL_DISABLED);
    }

    @ParameterizedTest
    @EnumSource(
            value = Command.class,
            names = {"READINESS", "PLAN_PROMOTE", "PLAN_REPLACE"})
    void readOnlyCommandsDoNotRequireOrInterpretTheMutationEnableFlag(Command command) {
        Map<String, String> environment = baseEnvironment();
        environment.put(LegalEditorialEnvironment.ENABLED_VARIABLE, "hostile-non-boolean");

        LegalManifestValidation<LegalEditorialEnvironment> result =
                LegalEditorialEnvironment.resolve(command, environment, new Properties());

        assertThat(result.status()).isEqualTo(LegalManifestStatus.PASS);
        assertThat(result.value()).isPresent();
    }

    @ParameterizedTest
    @EnumSource(
            value = Command.class,
            names = {"APPLY_PROMOTE", "APPLY_REPLACE"})
    void mutatingCommandPassesWhenTheEnableFlagIsExactlyTrue(Command command) {
        LegalManifestValidation<LegalEditorialEnvironment> result =
                LegalEditorialEnvironment.resolve(
                        command,
                        enabledEnvironment(),
                        new Properties());

        assertThat(result.status()).isEqualTo(LegalManifestStatus.PASS);
    }

    @Test
    void rejectsDatasourceSystemPropertyByNameWithoutReadingItsValue() {
        Properties properties = new Properties();
        properties.put("spring.datasource.hikari.password", new Object() {
            @Override
            public String toString() {
                throw new AssertionError("El valor prohibido no debe leerse");
            }
        });

        LegalManifestValidation<LegalEditorialEnvironment> result =
                LegalEditorialEnvironment.resolve(
                        Command.READINESS,
                        baseEnvironment(),
                        properties);

        assertFailure(
                result,
                LegalManifestIssueCode.EDITORIAL_DATASOURCE_SYSTEM_PROPERTY_FORBIDDEN);
    }

    @ParameterizedTest
    @MethodSource("invalidRequiredConfiguration")
    void rejectsMissingOrBlankEditorSpecificConfiguration(String variable, String value) {
        Map<String, String> environment = baseEnvironment();
        if (value == null) {
            environment.remove(variable);
        } else {
            environment.put(variable, value);
        }

        assertFailure(
                LegalEditorialEnvironment.resolve(
                        Command.READINESS,
                        environment,
                        new Properties()),
                LegalManifestIssueCode.EDITORIAL_DB_CONFIGURATION_INVALID);
    }

    @Test
    void rejectsUnknownVariablesInsideTheReservedEditorDatabaseNamespace() {
        Map<String, String> environment = baseEnvironment();
        environment.put("ORDENFIX_LEGAL_EDITOR_DB_PASSWORD_FILE", "/private/editor-secret");

        assertFailure(
                LegalEditorialEnvironment.resolve(
                        Command.PLAN_PROMOTE,
                        environment,
                        new Properties()),
                LegalManifestIssueCode.EDITORIAL_DB_CONFIGURATION_INVALID);
    }

    @Test
    void copiesOnlyRequiredDatasourcePropertiesAndIgnoresHostileSpringEnvironment() {
        Map<String, String> environment = baseEnvironment();
        environment.put("SPRING_DATASOURCE_URL", "jdbc:private-hostile");
        environment.put("SPRING_DATASOURCE_PASSWORD", "hostile-password");
        environment.put("SPRING_CONFIG_LOCATION", "/private/application.properties");

        LegalManifestValidation<LegalEditorialEnvironment> result =
                LegalEditorialEnvironment.resolve(
                        Command.READINESS,
                        environment,
                        new Properties());

        assertThat(result.status()).isEqualTo(LegalManifestStatus.PASS);
        Map<String, Object> properties = result.value().orElseThrow().datasourceProperties();
        assertThat(properties).containsExactly(
                Map.entry("spring.datasource.url", "jdbc:postgresql://db/legal"),
                Map.entry("spring.datasource.username", "legal_editor"),
                Map.entry("spring.datasource.password", PRIVATE_PASSWORD));
        assertThat(properties.toString())
                .doesNotContain("jdbc:private-hostile", "hostile-password");
        assertThatThrownBy(() -> properties.put("spring.datasource.url", "other"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void copiesTheOptionalDriverOnlyWhenExplicitlyConfigured() {
        Map<String, String> environment = baseEnvironment();
        environment.put(LegalEditorialEnvironment.DRIVER_VARIABLE, "org.postgresql.Driver");

        LegalManifestValidation<LegalEditorialEnvironment> result =
                LegalEditorialEnvironment.resolve(
                        Command.READINESS,
                        environment,
                        new Properties());

        assertThat(result.value().orElseThrow().datasourceProperties())
                .containsEntry("spring.datasource.driver-class-name", "org.postgresql.Driver");
    }

    @Test
    void diagnosticStringNeverExposesDatasourceValues() {
        LegalEditorialEnvironment resolved = LegalEditorialEnvironment
                .resolve(Command.READINESS, baseEnvironment(), new Properties())
                .value()
                .orElseThrow();

        assertThat(resolved.toString())
                .contains("configured=true")
                .doesNotContain(
                        "jdbc:postgresql://db/legal",
                        "legal_editor",
                        PRIVATE_PASSWORD);
    }

    private static Stream<Arguments> disabledFlagCases() {
        return Stream.of(null, "false", "TRUE", "True", " true", "true ", "1", "")
                .flatMap(value -> Stream.of(Command.APPLY_PROMOTE, Command.APPLY_REPLACE)
                        .map(command -> Arguments.of(command, value)));
    }

    private static Stream<Arguments> invalidRequiredConfiguration() {
        return Stream.of(
                Arguments.of(LegalEditorialEnvironment.URL_VARIABLE, null),
                Arguments.of(LegalEditorialEnvironment.URL_VARIABLE, " "),
                Arguments.of(LegalEditorialEnvironment.USERNAME_VARIABLE, null),
                Arguments.of(LegalEditorialEnvironment.USERNAME_VARIABLE, "\t"),
                Arguments.of(LegalEditorialEnvironment.PASSWORD_VARIABLE, null),
                Arguments.of(LegalEditorialEnvironment.PASSWORD_VARIABLE, "  "),
                Arguments.of(LegalEditorialEnvironment.DRIVER_VARIABLE, " "));
    }

    private static Map<String, String> enabledEnvironment() {
        Map<String, String> environment = baseEnvironment();
        environment.put(LegalEditorialEnvironment.ENABLED_VARIABLE, "true");
        return environment;
    }

    private static Map<String, String> baseEnvironment() {
        Map<String, String> environment = new HashMap<>();
        environment.put(LegalEditorialEnvironment.URL_VARIABLE, "jdbc:postgresql://db/legal");
        environment.put(LegalEditorialEnvironment.USERNAME_VARIABLE, "legal_editor");
        environment.put(LegalEditorialEnvironment.PASSWORD_VARIABLE, PRIVATE_PASSWORD);
        return environment;
    }

    private static void assertFailure(
            LegalManifestValidation<LegalEditorialEnvironment> result,
            LegalManifestIssueCode code) {
        assertThat(result.status()).isEqualTo(code.severity());
        assertThat(result.value()).isEmpty();
        assertThat(result.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.code()).isEqualTo(code);
            assertThat(issue.location()).isEqualTo("cli/editorial/environment");
            assertThat(issue.message()).isEqualTo(code.safeMessage());
            assertThat(issue.toString()).doesNotContain(PRIVATE_PASSWORD);
        });
    }
}
