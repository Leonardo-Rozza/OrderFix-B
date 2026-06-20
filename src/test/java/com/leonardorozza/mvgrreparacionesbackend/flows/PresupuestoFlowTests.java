package com.leonardorozza.mvgrreparacionesbackend.flows;

import com.fasterxml.jackson.databind.JsonNode;
import com.leonardorozza.mvgrreparacionesbackend.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PresupuestoFlowTests extends IntegrationTestBase {

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
}
