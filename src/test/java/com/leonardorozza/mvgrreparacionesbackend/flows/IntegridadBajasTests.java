package com.leonardorozza.mvgrreparacionesbackend.flows;

import com.fasterxml.jackson.databind.JsonNode;
import com.leonardorozza.mvgrreparacionesbackend.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class IntegridadBajasTests extends IntegrationTestBase {

    @Test
    void noBorraClienteConDependenciasNiPierdeReparacionCobroOStock() throws Exception {
        Escenario escenario = escenarioCompleto("cliente", "9601");

        authDelete("/api/clientes/" + escenario.clienteId(), escenario.token())
                .andExpect(status().isConflict());

        assertEscenarioPreservado(escenario);
    }

    @Test
    void noBorraEquipoConDependenciasNiPierdeReparacionCobroOStock() throws Exception {
        Escenario escenario = escenarioCompleto("equipo", "9602");

        authDelete("/api/equipos/" + escenario.equipoId(), escenario.token())
                .andExpect(status().isConflict());

        assertEscenarioPreservado(escenario);
    }

    private Escenario escenarioCompleto(String sufijo, String telefono) throws Exception {
        String token = registrar("Taller integridad " + sufijo, "integridad-" + sufijo + "@test.com");
        activarPro(token);

        long articuloId = idOf(authPost("/api/inventario", token, json(Map.of(
                "nombre", "Módulo " + sufijo,
                "precio", 8000,
                "stock", 10,
                "stockMinimo", 0)))
                .andExpect(status().isCreated()));

        JsonNode ingreso = node(authPost("/api/reparaciones/ingreso-rapido", token, json(Map.of(
                "clienteNombre", "Cliente",
                "clienteTelefono", telefono,
                "equipoMarca", "Marca",
                "equipoModelo", "Modelo",
                "descripcionProblema", "No enciende",
                "precioEstimado", 5000)))
                .andExpect(status().isCreated()));

        long clienteId = ingreso.get("clienteId").asLong();
        long equipoId = ingreso.get("equipoId").asLong();
        long reparacionId = ingreso.get("reparacion").get("id").asLong();

        authPost("/api/repuestos", token, json(Map.of(
                "nombre", "Módulo",
                "precio", 8000,
                "reparacionId", reparacionId,
                "articuloId", articuloId,
                "cantidad", 4)))
                .andExpect(status().isCreated());

        authPost("/api/reparaciones/" + reparacionId + "/cobros", token,
                json(Map.of("monto", 2000, "metodo", "EFECTIVO")))
                .andExpect(status().isCreated());

        return new Escenario(token, clienteId, equipoId, reparacionId, articuloId);
    }

    private void assertEscenarioPreservado(Escenario escenario) throws Exception {
        authGet("/api/clientes/" + escenario.clienteId(), escenario.token())
                .andExpect(status().isOk());
        authGet("/api/equipos/" + escenario.equipoId(), escenario.token())
                .andExpect(status().isOk());
        authGet("/api/reparaciones/" + escenario.reparacionId(), escenario.token())
                .andExpect(status().isOk());

        JsonNode cobros = node(authGet(
                "/api/reparaciones/" + escenario.reparacionId() + "/cobros", escenario.token())
                .andExpect(status().isOk()));
        assertThat(cobros.get("cobrado").asInt()).isEqualTo(2000);
        assertThat(cobros.get("cobros").size()).isEqualTo(1);

        JsonNode articulo = node(authGet("/api/inventario/" + escenario.articuloId(), escenario.token())
                .andExpect(status().isOk()));
        assertThat(articulo.get("stock").asInt()).isEqualTo(6);
    }

    private record Escenario(
            String token,
            long clienteId,
            long equipoId,
            long reparacionId,
            long articuloId
    ) {
    }
}
