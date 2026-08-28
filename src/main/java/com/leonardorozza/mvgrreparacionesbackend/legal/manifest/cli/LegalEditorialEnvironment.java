package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli.LegalEditorialArguments.Command;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

/** Closed and redacted datasource configuration for the isolated editorial context. */
final class LegalEditorialEnvironment {

    static final String ENABLED_VARIABLE = "ORDENFIX_LEGAL_EDITOR_ENABLED";
    static final String URL_VARIABLE = "ORDENFIX_LEGAL_EDITOR_DB_URL";
    static final String USERNAME_VARIABLE = "ORDENFIX_LEGAL_EDITOR_DB_USERNAME";
    static final String PASSWORD_VARIABLE = "ORDENFIX_LEGAL_EDITOR_DB_PASSWORD";
    static final String DRIVER_VARIABLE = "ORDENFIX_LEGAL_EDITOR_DB_DRIVER_CLASS_NAME";

    private static final String EDITOR_DB_PREFIX = "ORDENFIX_LEGAL_EDITOR_DB_";
    private static final String DATASOURCE_SYSTEM_PROPERTY_PREFIX = "spring.datasource.";
    private static final String ISSUE_LOCATION = "cli/editorial/environment";
    private static final Set<String> ALLOWED_EDITOR_DB_VARIABLES = Set.of(
            URL_VARIABLE,
            USERNAME_VARIABLE,
            PASSWORD_VARIABLE,
            DRIVER_VARIABLE);

    private final Map<String, Object> datasourceProperties;
    private final boolean driverConfigured;

    private LegalEditorialEnvironment(
            String url,
            String username,
            String password,
            String driverClassName) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("spring.datasource.url", url);
        properties.put("spring.datasource.username", username);
        properties.put("spring.datasource.password", password);
        if (driverClassName != null) {
            properties.put("spring.datasource.driver-class-name", driverClassName);
        }
        this.datasourceProperties = Collections.unmodifiableMap(properties);
        this.driverConfigured = driverClassName != null;
    }

    /** Resolves the actual process configuration after the release preflight has completed. */
    static LegalManifestValidation<LegalEditorialEnvironment> resolve(Command command) {
        try {
            return resolve(command, System.getenv(), System.getProperties());
        } catch (RuntimeException | LinkageError inaccessibleProcessConfiguration) {
            return failure(LegalManifestIssueCode.EDITORIAL_DB_CONFIGURATION_INVALID);
        }
    }

    /** Injectable resolution boundary used to prove ordering and redaction. */
    static LegalManifestValidation<LegalEditorialEnvironment> resolve(
            Command command,
            Map<String, String> environment,
            Properties systemProperties) {
        Command requiredCommand = Objects.requireNonNull(command, "command");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(systemProperties, "systemProperties");

        if (requiredCommand.mutating()
                && !"true".equals(environment.get(ENABLED_VARIABLE))) {
            return failure(LegalManifestIssueCode.EDITORIAL_DISABLED);
        }
        if (hasForbiddenDatasourceSystemProperty(systemProperties)) {
            return failure(
                    LegalManifestIssueCode.EDITORIAL_DATASOURCE_SYSTEM_PROPERTY_FORBIDDEN);
        }
        if (hasUnknownEditorDatabaseVariable(environment)) {
            return failure(LegalManifestIssueCode.EDITORIAL_DB_CONFIGURATION_INVALID);
        }

        String url = environment.get(URL_VARIABLE);
        String username = environment.get(USERNAME_VARIABLE);
        String password = environment.get(PASSWORD_VARIABLE);
        String driver = environment.get(DRIVER_VARIABLE);
        if (!isConfigured(url)
                || !isConfigured(username)
                || !isConfigured(password)
                || (driver != null && driver.isBlank())) {
            return failure(LegalManifestIssueCode.EDITORIAL_DB_CONFIGURATION_INVALID);
        }

        return LegalManifestValidation.pass(
                new LegalEditorialEnvironment(url, username, password, driver));
    }

    Map<String, Object> datasourceProperties() {
        return datasourceProperties;
    }

    private static boolean hasForbiddenDatasourceSystemProperty(Properties properties) {
        for (Object candidateName : properties.keySet()) {
            if (candidateName instanceof String name
                    && name.startsWith(DATASOURCE_SYSTEM_PROPERTY_PREFIX)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasUnknownEditorDatabaseVariable(Map<String, String> environment) {
        for (String name : environment.keySet()) {
            if (name != null
                    && name.startsWith(EDITOR_DB_PREFIX)
                    && !ALLOWED_EDITOR_DB_VARIABLES.contains(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isConfigured(String value) {
        return value != null && !value.isBlank();
    }

    private static <T> LegalManifestValidation<T> failure(LegalManifestIssueCode code) {
        return LegalManifestValidation.failure(LegalManifestIssue.at(code, ISSUE_LOCATION));
    }

    @Override
    public String toString() {
        return "LegalEditorialEnvironment[configured=true, driverConfigured="
                + driverConfigured + ']';
    }
}
