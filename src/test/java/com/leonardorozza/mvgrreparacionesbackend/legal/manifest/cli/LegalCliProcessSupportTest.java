package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LegalCliProcessSupportTest {

    @Test
    void removesInheritedImportAndEditorialDatabaseControlChannels() {
        Map<String, String> environment = new HashMap<>();
        environment.put("JAVA_TOOL_OPTIONS", "-Dspring.datasource.url=jdbc:hostile");
        environment.put("JDK_JAVA_OPTIONS", "-Dspring.datasource.username=hostile");
        environment.put("_JAVA_OPTIONS", "-Dspring.datasource.password=hostile");
        environment.put("SPRING_DATASOURCE_PASSWORD", "hostile");
        environment.put("SPRING_CONFIG_IMPORT", "file:/private/hostile.properties");
        environment.put(LegalImportEnvironment.ENABLED_VARIABLE, "true");
        environment.put("ORDENFIX_LEGAL_IMPORT_DB_PASSWORD", "hostile-import");
        environment.put(LegalEditorialEnvironment.ENABLED_VARIABLE, "true");
        environment.put("ORDENFIX_LEGAL_EDITOR_DB_URL", "jdbc:hostile-editor");
        environment.put("ORDENFIX_LEGAL_EDITOR_DB_PASSWORD", "hostile-editor");
        environment.put(
                "ORDENFIX_LEGAL_EDITOR_DB_PASSWORD_FILE",
                "/private/hostile-editor-secret");
        environment.put("ORDENFIX_UNRELATED", "preserved");
        environment.put("PATH", "/usr/bin");

        LegalCliProcessSupport.sanitizeInheritedEnvironment(environment);

        assertThat(environment).containsExactlyInAnyOrderEntriesOf(Map.of(
                "ORDENFIX_UNRELATED", "preserved",
                "PATH", "/usr/bin"));
    }
}
