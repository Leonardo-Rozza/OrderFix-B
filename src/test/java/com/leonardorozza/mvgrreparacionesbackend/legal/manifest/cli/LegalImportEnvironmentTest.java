package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegalImportEnvironmentTest {

    private static final String PRIVATE_PASSWORD = "private-import-password";

    @Test
    void requiresTheEnableFlagAsTheExactLiteralTrueBeforeAnyOtherCheck() {
        Properties forbiddenProperties = new Properties();
        forbiddenProperties.put("spring.datasource.password", PRIVATE_PASSWORD);

        LegalManifestValidation<LegalImportEnvironment> result =
                LegalImportEnvironment.resolve(baseEnvironmentWithoutFlag(), forbiddenProperties);

        assertFailure(result, LegalManifestIssueCode.IMPORT_DISABLED);
    }

    @ParameterizedTest
    @MethodSource("disabledFlagValues")
    void rejectsAnyEnableFlagValueOtherThanExactLowercaseTrue(String value) {
        Map<String, String> environment = baseEnvironment();
        environment.put(LegalImportEnvironment.ENABLED_VARIABLE, value);

        assertFailure(
                LegalImportEnvironment.resolve(environment, new Properties()),
                LegalManifestIssueCode.IMPORT_DISABLED);
    }

    @Test
    void rejectsDatasourceSystemPropertyByNameWithoutReadingOrReflectingItsValue() {
        String secret = "private-system-property-secret";
        Properties properties = new Properties();
        properties.put("spring.datasource.password", new Object() {
            @Override
            public String toString() {
                throw new AssertionError("El valor prohibido no debe leerse");
            }
        });

        LegalManifestValidation<LegalImportEnvironment> result =
                LegalImportEnvironment.resolve(baseEnvironment(), properties);

        assertFailure(
                result,
                LegalManifestIssueCode.IMPORT_DATASOURCE_SYSTEM_PROPERTY_FORBIDDEN);
        assertThat(result.issues()).allSatisfy(issue ->
                assertThat(issue.toString()).doesNotContain(secret, PRIVATE_PASSWORD));
    }

    @ParameterizedTest
    @MethodSource("invalidRequiredConfiguration")
    void rejectsMissingOrBlankRequiredImportSpecificConfiguration(
            String variable,
            String value) {
        Map<String, String> environment = baseEnvironment();
        if (value == null) {
            environment.remove(variable);
        } else {
            environment.put(variable, value);
        }

        assertFailure(
                LegalImportEnvironment.resolve(environment, new Properties()),
                LegalManifestIssueCode.IMPORT_DB_CONFIGURATION_INVALID);
    }

    @Test
    void rejectsUnknownVariablesInsideTheReservedImportDatabaseNamespace() {
        Map<String, String> environment = baseEnvironment();
        environment.put("ORDENFIX_LEGAL_IMPORT_DB_PASSWORD_FILE", "/private/secret");

        assertFailure(
                LegalImportEnvironment.resolve(environment, new Properties()),
                LegalManifestIssueCode.IMPORT_DB_CONFIGURATION_INVALID);
    }

    @Test
    void copiesOnlyTheThreeRequiredDatasourcePropertiesAndIgnoresHostileSpringEnvironment() {
        Map<String, String> environment = baseEnvironment();
        environment.put("SPRING_DATASOURCE_URL", "jdbc:private-hostile");
        environment.put("SPRING_DATASOURCE_PASSWORD", "hostile-password");
        environment.put("SPRING_CONFIG_LOCATION", "/private/application.properties");

        LegalManifestValidation<LegalImportEnvironment> result =
                LegalImportEnvironment.resolve(environment, new Properties());

        assertThat(result.status()).isEqualTo(LegalManifestStatus.PASS);
        Map<String, Object> properties = result.value().orElseThrow().datasourceProperties();
        assertThat(properties).containsExactly(
                Map.entry("spring.datasource.url", "jdbc:postgresql://db/legal"),
                Map.entry("spring.datasource.username", "legal_importer"),
                Map.entry("spring.datasource.password", PRIVATE_PASSWORD));
        assertThat(properties.toString())
                .doesNotContain("jdbc:private-hostile", "hostile-password");
        assertThatThrownBy(() -> properties.put("spring.datasource.url", "other"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void copiesTheOptionalDriverOnlyWhenItIsExplicitlyConfigured() {
        Map<String, String> environment = baseEnvironment();
        environment.put(
                LegalImportEnvironment.DRIVER_VARIABLE,
                "org.postgresql.Driver");

        LegalManifestValidation<LegalImportEnvironment> result =
                LegalImportEnvironment.resolve(environment, new Properties());

        assertThat(result.status()).isEqualTo(LegalManifestStatus.PASS);
        assertThat(result.value().orElseThrow().datasourceProperties())
                .containsEntry("spring.datasource.driver-class-name", "org.postgresql.Driver");
    }

    @Test
    void diagnosticStringNeverExposesDatasourceValues() {
        LegalImportEnvironment resolved = LegalImportEnvironment
                .resolve(baseEnvironment(), new Properties())
                .value()
                .orElseThrow();

        assertThat(resolved.toString())
                .doesNotContain(
                        "jdbc:postgresql://db/legal",
                        "legal_importer",
                        PRIVATE_PASSWORD)
                .contains("configured=true");
    }

    private static Stream<String> disabledFlagValues() {
        return Stream.of("false", "TRUE", "True", " true", "true ", "1", "");
    }

    private static Stream<Arguments> invalidRequiredConfiguration() {
        return Stream.of(
                Arguments.of(LegalImportEnvironment.URL_VARIABLE, null),
                Arguments.of(LegalImportEnvironment.URL_VARIABLE, " "),
                Arguments.of(LegalImportEnvironment.USERNAME_VARIABLE, null),
                Arguments.of(LegalImportEnvironment.USERNAME_VARIABLE, "\t"),
                Arguments.of(LegalImportEnvironment.PASSWORD_VARIABLE, null),
                Arguments.of(LegalImportEnvironment.PASSWORD_VARIABLE, "  "),
                Arguments.of(LegalImportEnvironment.DRIVER_VARIABLE, " "));
    }

    private static Map<String, String> baseEnvironment() {
        Map<String, String> environment = baseEnvironmentWithoutFlag();
        environment.put(LegalImportEnvironment.ENABLED_VARIABLE, "true");
        return environment;
    }

    private static Map<String, String> baseEnvironmentWithoutFlag() {
        Map<String, String> environment = new HashMap<>();
        environment.put(LegalImportEnvironment.URL_VARIABLE, "jdbc:postgresql://db/legal");
        environment.put(LegalImportEnvironment.USERNAME_VARIABLE, "legal_importer");
        environment.put(LegalImportEnvironment.PASSWORD_VARIABLE, PRIVATE_PASSWORD);
        return environment;
    }

    private static void assertFailure(
            LegalManifestValidation<LegalImportEnvironment> result,
            LegalManifestIssueCode code) {
        assertThat(result.status()).isEqualTo(code.severity());
        assertThat(result.value()).isEmpty();
        assertThat(result.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.code()).isEqualTo(code);
            assertThat(issue.location()).isEqualTo("cli/import/environment");
            assertThat(issue.message()).isEqualTo(code.safeMessage());
            assertThat(issue.toString()).doesNotContain(PRIVATE_PASSWORD);
        });
    }
}
