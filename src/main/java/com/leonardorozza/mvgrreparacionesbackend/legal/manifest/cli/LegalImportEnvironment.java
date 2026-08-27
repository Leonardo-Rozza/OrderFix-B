package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

/**
 * Resolución cerrada de habilitación y secretos para el contexto importador aislado.
 *
 * <p>No conserva el entorno completo ni lee valores de propiedades JVM prohibidas. El mapa
 * resultante contiene solamente las cuatro propiedades datasource que el contexto hijo puede
 * recibir.</p>
 */
final class LegalImportEnvironment {

    static final String ENABLED_VARIABLE = "ORDENFIX_LEGAL_IMPORT_ENABLED";
    static final String URL_VARIABLE = "ORDENFIX_LEGAL_IMPORT_DB_URL";
    static final String USERNAME_VARIABLE = "ORDENFIX_LEGAL_IMPORT_DB_USERNAME";
    static final String PASSWORD_VARIABLE = "ORDENFIX_LEGAL_IMPORT_DB_PASSWORD";
    static final String DRIVER_VARIABLE = "ORDENFIX_LEGAL_IMPORT_DB_DRIVER_CLASS_NAME";

    private static final String IMPORT_DB_PREFIX = "ORDENFIX_LEGAL_IMPORT_DB_";
    private static final String DATASOURCE_SYSTEM_PROPERTY_PREFIX = "spring.datasource.";
    private static final String ISSUE_LOCATION = "cli/import/environment";
    private static final Set<String> ALLOWED_IMPORT_DB_VARIABLES = Set.of(
            URL_VARIABLE,
            USERNAME_VARIABLE,
            PASSWORD_VARIABLE,
            DRIVER_VARIABLE);

    private final Map<String, Object> datasourceProperties;
    private final boolean driverConfigured;

    private LegalImportEnvironment(
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

    /** Resuelve contra el proceso real sin exponer valores en fallos. */
    static LegalManifestValidation<LegalImportEnvironment> resolve() {
        try {
            if (!"true".equals(System.getenv(ENABLED_VARIABLE))) {
                return failure(LegalManifestIssueCode.IMPORT_DISABLED);
            }
            return resolve(System.getenv(), System.getProperties());
        } catch (RuntimeException | LinkageError inaccessibleProcessConfiguration) {
            return failure(LegalManifestIssueCode.IMPORT_DB_CONFIGURATION_INVALID);
        }
    }

    /** Frontera inyectable para pruebas, sin dependencia obligatoria de globales del proceso. */
    static LegalManifestValidation<LegalImportEnvironment> resolve(
            Map<String, String> environment,
            Properties systemProperties) {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(systemProperties, "systemProperties");

        if (!"true".equals(environment.get(ENABLED_VARIABLE))) {
            return failure(LegalManifestIssueCode.IMPORT_DISABLED);
        }
        if (hasForbiddenDatasourceSystemProperty(systemProperties)) {
            return failure(
                    LegalManifestIssueCode.IMPORT_DATASOURCE_SYSTEM_PROPERTY_FORBIDDEN);
        }
        if (hasUnknownImportDatabaseVariable(environment)) {
            return failure(LegalManifestIssueCode.IMPORT_DB_CONFIGURATION_INVALID);
        }

        String url = environment.get(URL_VARIABLE);
        String username = environment.get(USERNAME_VARIABLE);
        String password = environment.get(PASSWORD_VARIABLE);
        String driver = environment.get(DRIVER_VARIABLE);
        if (!isConfigured(url)
                || !isConfigured(username)
                || !isConfigured(password)
                || (driver != null && driver.isBlank())) {
            return failure(LegalManifestIssueCode.IMPORT_DB_CONFIGURATION_INVALID);
        }

        return LegalManifestValidation.pass(
                new LegalImportEnvironment(url, username, password, driver));
    }

    /** Propiedades permitidas para el property source interno del contexto hijo. */
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

    private static boolean hasUnknownImportDatabaseVariable(Map<String, String> environment) {
        for (String name : environment.keySet()) {
            if (name != null
                    && name.startsWith(IMPORT_DB_PREFIX)
                    && !ALLOWED_IMPORT_DB_VARIABLES.contains(name)) {
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
        return "LegalImportEnvironment[configured=true, driverConfigured="
                + driverConfigured + ']';
    }
}
