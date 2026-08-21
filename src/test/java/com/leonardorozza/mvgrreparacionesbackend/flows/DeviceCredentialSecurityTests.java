package com.leonardorozza.mvgrreparacionesbackend.flows;

import com.fasterxml.jackson.databind.JsonNode;
import com.leonardorozza.mvgrreparacionesbackend.service.security.DeviceCredentialLegacyMigration;
import com.leonardorozza.mvgrreparacionesbackend.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties =
        "DEVICE_CREDENTIALS_ENCRYPTION_KEY=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
class DeviceCredentialSecurityTests extends IntegrationTestBase {

    private static final String PATTERN = "L invertida";
    private static final String PIN = "7391";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DeviceCredentialLegacyMigration legacyMigration;

    @Test
    void cifraEnBaseYDescifraSoloEnDetalleAutenticado() throws Exception {
        Scenario scenario = createWithCredentials("db", "9701");

        Map<String, Object> row = credentialRow(scenario.repairId());
        String encryptedPattern = (String) row.get("patron_desbloqueo_cifrado");
        String encryptedPin = (String) row.get("pin_desbloqueo_cifrado");

        assertThat(encryptedPattern)
                .startsWith("v1:")
                .doesNotContain(PATTERN)
                .isNotEqualTo(PATTERN);
        assertThat(encryptedPin)
                .startsWith("v1:")
                .doesNotContain(PIN)
                .isNotEqualTo(PIN);
        assertThat(((Number) row.get("credenciales_cifrado_version")).intValue()).isEqualTo(1);

        JsonNode detail = node(authGet("/api/reparaciones/" + scenario.repairId(), scenario.token())
                .andExpect(status().isOk()));
        assertThat(detail.get("patronDesbloqueo").asText()).isEqualTo(PATTERN);
        assertThat(detail.get("pinDesbloqueo").asText()).isEqualTo(PIN);

        JsonNode unrelatedUpdate = node(authPut(
                "/api/reparaciones/" + scenario.repairId(),
                scenario.token(),
                json(Map.of(
                        "equipoId", scenario.equipmentId(),
                        "descripcionProblema", "Diagnóstico actualizado")))
                .andExpect(status().isOk()));
        assertNoCredentials(unrelatedUpdate);

        JsonNode retained = node(authGet("/api/reparaciones/" + scenario.repairId(), scenario.token())
                .andExpect(status().isOk()));
        assertThat(retained.get("patronDesbloqueo").asText()).isEqualTo(PATTERN);
        assertThat(retained.get("pinDesbloqueo").asText()).isEqualTo(PIN);

        mvc.perform(get("/api/reparaciones/" + scenario.repairId()))
                .andExpect(status().isForbidden());
    }

    @Test
    void noExponeCredencialesEnAltasActualizacionesListadosOSeguimientoPublico() throws Exception {
        Scenario scenario = createWithCredentials("responses", "9702");

        JsonNode updateResponse = node(authPut(
                "/api/reparaciones/" + scenario.repairId(),
                scenario.token(),
                json(Map.of(
                        "equipoId", scenario.equipmentId(),
                        "descripcionProblema", "No enciende",
                        "patronDesbloqueo", PATTERN,
                        "pinDesbloqueo", PIN)))
                .andExpect(status().isOk()));
        assertNoCredentials(updateResponse);

        JsonNode page = node(authGet("/api/reparaciones?q=Cliente", scenario.token())
                .andExpect(status().isOk()));
        JsonNode listItem = page.get("content").get(0);
        assertNoCredentials(listItem);

        JsonNode byEquipment = node(authGet(
                "/api/reparaciones/equipo/" + scenario.equipmentId(), scenario.token())
                .andExpect(status().isOk()));
        assertNoCredentials(byEquipment.get(0));

        JsonNode detail = node(authGet("/api/reparaciones/" + scenario.repairId(), scenario.token())
                .andExpect(status().isOk()));
        String trackingCode = detail.get("codigoSeguimiento").asText();

        JsonNode publicTracking = node(mvc.perform(get("/api/seguimiento/" + trackingCode))
                .andExpect(status().isOk()));
        assertNoCredentials(publicTracking);
    }

    @Test
    void borraCiphertextAlEntregarYNoLoVuelveAExponer() throws Exception {
        Scenario scenario = createWithCredentials("delivery", "9703");

        for (String state : List.of("EN_PROCESO", "COMPLETADO", "ENTREGADO")) {
            JsonNode response = node(authPatch(
                    "/api/reparaciones/" + scenario.repairId() + "/estado",
                    scenario.token(),
                    json(Map.of("estado", state)))
                    .andExpect(status().isOk()));
            assertNoCredentials(response);
        }

        Map<String, Object> row = credentialRow(scenario.repairId());
        assertThat(row.get("patron_desbloqueo_cifrado")).isNull();
        assertThat(row.get("pin_desbloqueo_cifrado")).isNull();
        assertThat(((Number) row.get("credenciales_cifrado_version")).intValue()).isEqualTo(1);

        JsonNode delivered = node(authGet("/api/reparaciones/" + scenario.repairId(), scenario.token())
                .andExpect(status().isOk()));
        assertThat(delivered.get("estado").asText()).isEqualTo("ENTREGADO");
        assertThat(delivered.get("patronDesbloqueo").isNull()).isTrue();
        assertThat(delivered.get("pinDesbloqueo").isNull()).isTrue();
    }

    @Test
    void migraLegacyEnFormaAtomicaSinPerderElValor() throws Exception {
        Scenario scenario = createWithCredentials("legacy", "9704");
        String legacyPattern = "zigzag legacy";
        String legacyPin = "2468";

        jdbcTemplate.update("""
                        UPDATE reparaciones
                        SET patron_desbloqueo_cifrado = ?,
                            pin_desbloqueo_cifrado = ?,
                            credenciales_cifrado_version = 0
                        WHERE id = ?
                        """,
                legacyPattern, legacyPin, scenario.repairId());

        legacyMigration.run(null);

        Map<String, Object> migrated = credentialRow(scenario.repairId());
        assertThat((String) migrated.get("patron_desbloqueo_cifrado"))
                .startsWith("v1:")
                .doesNotContain(legacyPattern);
        assertThat((String) migrated.get("pin_desbloqueo_cifrado"))
                .startsWith("v1:")
                .doesNotContain(legacyPin);
        assertThat(((Number) migrated.get("credenciales_cifrado_version")).intValue()).isEqualTo(1);

        JsonNode detail = node(authGet("/api/reparaciones/" + scenario.repairId(), scenario.token())
                .andExpect(status().isOk()));
        assertThat(detail.get("patronDesbloqueo").asText()).isEqualTo(legacyPattern);
        assertThat(detail.get("pinDesbloqueo").asText()).isEqualTo(legacyPin);
    }

    private Scenario createWithCredentials(String suffix, String phone) throws Exception {
        String token = registrar(
                "Taller credenciales " + suffix,
                "credenciales-" + suffix + "@test.com");

        JsonNode intake = node(authPost("/api/reparaciones/ingreso-rapido", token, json(Map.of(
                "clienteNombre", "Cliente",
                "clienteTelefono", phone,
                "equipoMarca", "Marca",
                "equipoModelo", "Modelo",
                "descripcionProblema", "No enciende")))
                .andExpect(status().isCreated()));

        long equipmentId = intake.get("equipoId").asLong();

        JsonNode created = node(authPost("/api/reparaciones", token, json(Map.of(
                "equipoId", equipmentId,
                "descripcionProblema", "No enciende",
                "tieneBloqueoPantalla", true,
                "patronDesbloqueo", PATTERN,
                "pinDesbloqueo", PIN)))
                .andExpect(status().isCreated()));
        assertNoCredentials(created);
        long repairId = created.get("id").asLong();

        return new Scenario(token, equipmentId, repairId);
    }

    private Map<String, Object> credentialRow(long repairId) {
        return jdbcTemplate.queryForMap("""
                        SELECT patron_desbloqueo_cifrado,
                               pin_desbloqueo_cifrado,
                               credenciales_cifrado_version
                        FROM reparaciones
                        WHERE id = ?
                        """,
                repairId);
    }

    private void assertNoCredentials(JsonNode response) {
        assertThat(response.has("patronDesbloqueo")).isFalse();
        assertThat(response.has("pinDesbloqueo")).isFalse();
        assertThat(response.toString()).doesNotContain(PATTERN, PIN);
    }

    private record Scenario(String token, long equipmentId, long repairId) {
    }
}
