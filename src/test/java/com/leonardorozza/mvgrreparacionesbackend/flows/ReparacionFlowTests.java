package com.leonardorozza.mvgrreparacionesbackend.flows;

import com.fasterxml.jackson.databind.JsonNode;
import com.leonardorozza.mvgrreparacionesbackend.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReparacionFlowTests extends IntegrationTestBase {

    private String ingresoRapido(String nombre, String tel, String marca, String modelo, String problema) throws Exception {
        return json(Map.of(
                "clienteNombre", nombre, "clienteTelefono", tel,
                "equipoMarca", marca, "equipoModelo", modelo, "descripcionProblema", problema));
    }

    @Test
    void ingresoRapidoCreaTodoYReusaClientePorTelefono() throws Exception {
        String t = registrar("Taller IR", "ir@test.com");

        JsonNode r1 = node(authPost("/api/reparaciones/ingreso-rapido", t,
                ingresoRapido("Ana", "5551", "Apple", "iPhone 12", "pantalla")).andExpect(status().isCreated()));
        assertThat(r1.get("clienteNuevo").asBoolean()).isTrue();
        long clienteId = r1.get("clienteId").asLong();

        // Mismo teléfono → reutiliza el cliente, crea equipo nuevo
        JsonNode r2 = node(authPost("/api/reparaciones/ingreso-rapido", t,
                ingresoRapido("Ana", "5551", "Samsung", "A52", "no carga")).andExpect(status().isCreated()));
        assertThat(r2.get("clienteNuevo").asBoolean()).isFalse();
        assertThat(r2.get("clienteId").asLong()).isEqualTo(clienteId);
    }

    @Test
    void ingresoRapidoSinMarcaDa400() throws Exception {
        String t = registrar("Taller IR2", "ir2@test.com");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/reparaciones/ingreso-rapido").header("Authorization", "Bearer " + t)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(json(Map.of("clienteNombre", "X", "clienteTelefono", "1",
                        "equipoModelo", "Y", "descripcionProblema", "z"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listadoTraeEquipoYClienteDenormalizadoYBuscaPorCliente() throws Exception {
        String t = registrar("Taller Den", "den@test.com");
        authPost("/api/reparaciones/ingreso-rapido", t,
                ingresoRapido("Marcos", "5552", "Samsung", "A52", "bateria")).andExpect(status().isCreated());

        JsonNode page = node(authGet("/api/reparaciones?q=Marcos", t).andExpect(status().isOk()));
        assertThat(page.get("page").get("totalElements").asInt()).isEqualTo(1);
        JsonNode item = page.get("content").get(0);
        assertThat(item.get("clienteNombre").asText()).isEqualTo("Marcos");
        assertThat(item.get("equipoMarca").asText()).isEqualTo("Samsung");
        assertThat(item.get("equipoModelo").asText()).isEqualTo("A52");

        // también matchea por equipo
        assertThat(node(authGet("/api/reparaciones?q=A52", t).andExpect(status().isOk()))
                .get("page").get("totalElements").asInt()).isEqualTo(1);
    }

    @Test
    void cambiarEstadoConDtoYRechazaEstadoInvalido() throws Exception {
        String t = registrar("Taller Est", "est@test.com");
        long repId = node(authPost("/api/reparaciones/ingreso-rapido", t,
                ingresoRapido("Leo", "5553", "LG", "K50", "no enciende")).andExpect(status().isCreated()))
                .get("reparacion").get("id").asLong();

        JsonNode upd = node(authPatch("/api/reparaciones/" + repId + "/estado", t,
                json(Map.of("estado", "EN_PROCESO"))).andExpect(status().isOk()));
        assertThat(upd.get("estado").asText()).isEqualTo("EN_PROCESO");

        authPatch("/api/reparaciones/" + repId + "/estado", t, json(Map.of("estado", "VOLANDO")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ordenDeTrabajoAmpliadaSeGuarda() throws Exception {
        String t = registrar("Taller OT", "ot@test.com");
        JsonNode ir = node(authPost("/api/reparaciones/ingreso-rapido", t,
                ingresoRapido("Sol", "5554", "Apple", "iPhone 14", "no carga")).andExpect(status().isCreated()));
        long repId = ir.get("reparacion").get("id").asLong();
        long equipoId = ir.get("equipoId").asLong();

        Map<String, Object> put = Map.of(
                "equipoId", equipoId,
                "descripcionProblema", "no carga",
                "patronDesbloqueo", "L invertida",
                "accesorios", "cargador, SIM",
                "observaciones", "interno",
                "fotos", List.of(
                        Map.of("url", "https://cdn/1.jpg", "momento", "INGRESO"),
                        Map.of("url", "https://cdn/2.jpg", "momento", "POST_REPARACION")));

        JsonNode upd = node(authPut("/api/reparaciones/" + repId, t, json(put)).andExpect(status().isOk()));
        assertThat(upd.get("patronDesbloqueo").asText()).isEqualTo("L invertida");
        assertThat(upd.get("accesorios").asText()).isEqualTo("cargador, SIM");
        assertThat(upd.get("fotos")).hasSize(2);
        assertThat(upd.get("fotos").get(0).get("url").asText()).isEqualTo("https://cdn/1.jpg");
        assertThat(upd.get("fotos").get(1).get("momento").asText()).isEqualTo("POST_REPARACION");
    }

    @Test
    void listadoEsPaginado() throws Exception {
        String t = registrar("Taller Pag", "pag@test.com");
        authPost("/api/reparaciones/ingreso-rapido", t,
                ingresoRapido("P", "5555", "M", "X", "z")).andExpect(status().isCreated());

        JsonNode page = node(authGet("/api/reparaciones?size=1&page=0", t).andExpect(status().isOk()));
        assertThat(page.has("content")).isTrue();
        assertThat(page.get("page").get("size").asInt()).isEqualTo(1);
    }
}
