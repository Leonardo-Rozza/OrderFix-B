package com.leonardorozza.mvgrreparacionesbackend.flows;

import com.fasterxml.jackson.databind.JsonNode;
import com.leonardorozza.mvgrreparacionesbackend.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PresupuestoFlowTests extends IntegrationTestBase {

    @Autowired private JdbcTemplate jdbc;

    @Test
    void crearPresupuestoYAprobarDesdeElLinkPublico() throws Exception {
        String t = registrar("Taller Presu", "presu@test.com");
        JsonNode ir = node(authPost("/api/reparaciones/ingreso-rapido", t, json(Map.of(
                "clienteNombre", "Pia", "clienteTelefono", "6001",
                "equipoMarca", "Motorola", "equipoModelo", "G60", "descripcionProblema", "no carga")))
                .andExpect(status().isCreated()));
        long repId = ir.get("reparacion").get("id").asLong();
        String codigo = ir.get("reparacion").get("codigoSeguimiento").asText();

        // El taller crea el presupuesto (total = 8000 + 12000 = 20000)
        String body = json(Map.of(
                "items", List.of(
                        Map.of("descripcion", "Pin de carga", "cantidad", 1, "precioUnitario", 8000),
                        Map.of("descripcion", "Mano de obra", "cantidad", 1, "precioUnitario", 12000)),
                "observaciones", "48hs"));
        JsonNode creado = node(authPost("/api/reparaciones/" + repId + "/presupuestos", t, body)
                .andExpect(status().isCreated()));
        assertThat(creado.get("estado").asText()).isEqualTo("PENDIENTE");
        assertThat(creado.get("total").asInt()).isEqualTo(20000);

        // El cliente lo ve en el seguimiento público (sin token)
        JsonNode seg = node(mvc.perform(get("/api/seguimiento/" + codigo)).andExpect(status().isOk()));
        assertThat(seg.get("presupuesto").get("estado").asText()).isEqualTo("PENDIENTE");

        // El cliente aprueba (público)
        JsonNode aprob = node(mvc.perform(post("/api/seguimiento/" + codigo + "/presupuesto/aprobar"))
                .andExpect(status().isOk()));
        assertThat(aprob.get("estado").asText()).isEqualTo("APROBADO");

        // Ya no hay pendiente → 400
        mvc.perform(post("/api/seguimiento/" + codigo + "/presupuesto/aprobar"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void presupuestoSinItemsDa400() throws Exception {
        String t = registrar("Taller Presu2", "presu2@test.com");
        long repId = node(authPost("/api/reparaciones/ingreso-rapido", t, json(Map.of(
                "clienteNombre", "Tom", "clienteTelefono", "6002",
                "equipoMarca", "Nokia", "equipoModelo", "X", "descripcionProblema", "z")))
                .andExpect(status().isCreated())).get("reparacion").get("id").asLong();

        mvc.perform(post("/api/reparaciones/" + repId + "/presupuestos")
                        .header("Authorization", "Bearer " + t).contentType(APPLICATION_JSON)
                        .content(json(Map.of("items", List.of()))))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"aprobar", "rechazar"})
    void tallerInactivoOcultaSuSeguimientoYNoPermiteResponderPresupuestos(String action) throws Exception {
        TrackingFixture inactive = trackingFixture();
        TrackingFixture active = trackingFixture();
        mvc.perform(get("/api/seguimiento/" + inactive.code())).andExpect(status().isOk());
        var budgetBefore = jdbc.queryForMap("SELECT estado, fecha_respuesta FROM presupuestos WHERE id = ?", inactive.budgetId());
        var repairBefore = jdbc.queryForMap("SELECT estado FROM reparaciones WHERE id = ?", inactive.repairId());
        jdbc.update("UPDATE talleres SET activo = false WHERE id = "
                + "(SELECT taller_id FROM reparaciones WHERE id = ?)", inactive.repairId());

        JsonNode missing = node(mvc.perform(get("/api/seguimiento/CODIGO-INEXISTENTE"))
                .andExpect(status().isNotFound()));
        JsonNode rejectedRead = node(mvc.perform(get("/api/seguimiento/" + inactive.code()))
                .andExpect(status().isNotFound()));
        JsonNode rejectedWrite = node(mvc.perform(post("/api/seguimiento/" + inactive.code() + "/presupuesto/" + action))
                .andExpect(status().isNotFound()));
        for (JsonNode error : List.of(rejectedRead, rejectedWrite)) {
            assertThat(error.get("message")).isEqualTo(missing.get("message"));
            assertThat(error.get("message").asText()).isEqualTo("No encontramos una reparación con ese código.");
            assertThat(error.toString()).doesNotContain("Taller Privado", "Detalle reservado", "presupuestoId", "total");
        }
        assertThat(jdbc.queryForMap("SELECT estado, fecha_respuesta FROM presupuestos WHERE id = ?", inactive.budgetId()))
                .isEqualTo(budgetBefore);
        assertThat(jdbc.queryForMap("SELECT estado FROM reparaciones WHERE id = ?", inactive.repairId()))
                .isEqualTo(repairBefore);

        // Another active workshop keeps the same public contract for either answer.
        mvc.perform(get("/api/seguimiento/" + active.code())).andExpect(status().isOk());
        JsonNode answer = node(mvc.perform(post("/api/seguimiento/" + active.code() + "/presupuesto/" + action))
                .andExpect(status().isOk()));
        assertThat(answer.get("estado").asText()).isEqualTo(action.equals("aprobar") ? "APROBADO" : "RECHAZADO");
        mvc.perform(get("/api/seguimiento/" + active.code())).andExpect(status().isOk());
    }

    private TrackingFixture trackingFixture() throws Exception {
        String unique = UUID.randomUUID().toString();
        String token = registrar("Taller Privado " + unique, "tracking-" + unique + "@test.com");
        JsonNode repair = node(authPost("/api/reparaciones/ingreso-rapido", token, json(Map.of(
                "clienteNombre", "Cliente", "clienteTelefono", "6004", "equipoMarca", "Motorola",
                "equipoModelo", "G60", "descripcionProblema", "Detalle reservado")))
                .andExpect(status().isCreated())).get("reparacion");
        long repairId = repair.get("id").asLong();
        long budgetId = idOf(authPost("/api/reparaciones/" + repairId + "/presupuestos", token,
                json(Map.of("items", List.of(Map.of("descripcion", "Reparación", "cantidad", 1, "precioUnitario", 1000)))))
                .andExpect(status().isCreated()));
        return new TrackingFixture(repairId, budgetId, repair.get("codigoSeguimiento").asText());
    }

    private record TrackingFixture(long repairId, long budgetId, String code) { }

}
