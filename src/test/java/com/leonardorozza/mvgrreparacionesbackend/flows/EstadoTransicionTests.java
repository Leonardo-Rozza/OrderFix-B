package com.leonardorozza.mvgrreparacionesbackend.flows;

import com.fasterxml.jackson.databind.JsonNode;
import com.leonardorozza.mvgrreparacionesbackend.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Máquina de transiciones de estado: un salto ilegal devuelve 409, el camino
 * legal avanza, el mismo estado es idempotente y los estados terminales no salen.
 */
class EstadoTransicionTests extends IntegrationTestBase {

    private long nuevaReparacion(String t, String tel) throws Exception {
        return node(authPost("/api/reparaciones/ingreso-rapido", t, json(Map.of(
                "clienteNombre", "C", "clienteTelefono", tel,
                "equipoMarca", "M", "equipoModelo", "X", "descripcionProblema", "z")))
                .andExpect(status().isCreated())).get("reparacion").get("id").asLong();
    }

    private void patchEstado(String t, long id, String estado, int expectedStatus) throws Exception {
        authPatch("/api/reparaciones/" + id + "/estado", t, json(Map.of("estado", estado)))
                .andExpect(status().is(expectedStatus));
    }

    @Test
    void caminoLegalCompletoAvanza() throws Exception {
        String t = registrar("Taller Tr", "tr-ok@test.com");
        long id = nuevaReparacion(t, "7001");

        patchEstado(t, id, "EN_DIAGNOSTICO", 200);
        patchEstado(t, id, "PRESUPUESTADO", 200);
        patchEstado(t, id, "EN_PROCESO", 200);
        patchEstado(t, id, "ESPERANDO_REPUESTO", 200);
        patchEstado(t, id, "EN_PROCESO", 200);
        patchEstado(t, id, "COMPLETADO", 200);
        JsonNode fin = node(authPatch("/api/reparaciones/" + id + "/estado", t,
                json(Map.of("estado", "ENTREGADO"))).andExpect(status().isOk()));
        assertThat(fin.get("estado").asText()).isEqualTo("ENTREGADO");
    }

    @Test
    void saltoIlegalDevuelve409() throws Exception {
        String t = registrar("Taller Tr2", "tr-409@test.com");
        long id = nuevaReparacion(t, "7002");

        // INGRESADO → ENTREGADO no es legal
        patchEstado(t, id, "ENTREGADO", 409);
        // la reparación sigue en INGRESADO
        assertThat(node(authGet("/api/reparaciones/" + id, t)).get("estado").asText()).isEqualTo("INGRESADO");
    }

    @Test
    void mismoEstadoEsIdempotente() throws Exception {
        String t = registrar("Taller Tr3", "tr-idem@test.com");
        long id = nuevaReparacion(t, "7003");

        patchEstado(t, id, "INGRESADO", 200);
    }

    @Test
    void estadoTerminalNoTransiciona() throws Exception {
        String t = registrar("Taller Tr4", "tr-term@test.com");
        long id = nuevaReparacion(t, "7004");

        patchEstado(t, id, "CANCELADO", 200);   // INGRESADO → CANCELADO es legal
        patchEstado(t, id, "EN_PROCESO", 409);  // CANCELADO es terminal
    }
}
