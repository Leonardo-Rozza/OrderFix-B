package com.leonardorozza.mvgrreparacionesbackend.flows;

import com.fasterxml.jackson.databind.JsonNode;
import com.leonardorozza.mvgrreparacionesbackend.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Bloque 5: fotos con momento (INGRESO/POST_REPARACION) y conformidad de entrega
 * (se sella al pasar a ENTREGADO).
 */
class EntregaYFotosTests extends IntegrationTestBase {

    private JsonNode ingreso(String t, String tel) throws Exception {
        return node(authPost("/api/reparaciones/ingreso-rapido", t, json(Map.of(
                "clienteNombre", "C", "clienteTelefono", tel,
                "equipoMarca", "Apple", "equipoModelo", "iPhone 12", "descripcionProblema", "pantalla")))
                .andExpect(status().isCreated()));
    }

    @Test
    void fotoSinMomentoDefaultaIngreso() throws Exception {
        String t = registrar("Taller Foto", "foto@test.com");
        JsonNode ir = ingreso(t, "5201");
        long repId = ir.get("reparacion").get("id").asLong();
        long equipoId = ir.get("equipoId").asLong();

        Map<String, Object> put = new HashMap<>();
        put.put("equipoId", equipoId);
        put.put("descripcionProblema", "pantalla");
        put.put("fotos", List.of(
                Map.of("url", "https://cdn/a.jpg"),                              // sin momento → INGRESO
                Map.of("url", "https://cdn/b.jpg", "momento", "POST_REPARACION")));

        JsonNode upd = node(authPut("/api/reparaciones/" + repId, t, json(put)).andExpect(status().isOk()));
        assertThat(upd.get("fotos")).hasSize(2);
        assertThat(upd.get("fotos").get(0).get("momento").asText()).isEqualTo("INGRESO");
        assertThat(upd.get("fotos").get(1).get("momento").asText()).isEqualTo("POST_REPARACION");
    }

    @Test
    void conformidadDeEntregaSeSellaAlEntregar() throws Exception {
        String t = registrar("Taller Entrega", "entrega@test.com");
        long repId = ingreso(t, "5202").get("reparacion").get("id").asLong();

        // Todavía sin entregar → sin conformidad
        assertThat(node(authGet("/api/reparaciones/" + repId, t))
                .get("fechaConformidadEntrega").isNull()).isTrue();

        // Camino legal hasta ENTREGADO
        for (String estado : List.of("EN_PROCESO", "COMPLETADO", "ENTREGADO")) {
            authPatch("/api/reparaciones/" + repId + "/estado", t, json(Map.of("estado", estado)))
                    .andExpect(status().isOk());
        }

        JsonNode entregada = node(authGet("/api/reparaciones/" + repId, t).andExpect(status().isOk()));
        assertThat(entregada.get("estado").asText()).isEqualTo("ENTREGADO");
        assertThat(entregada.get("fechaConformidadEntrega").isNull()).isFalse();
    }
}
