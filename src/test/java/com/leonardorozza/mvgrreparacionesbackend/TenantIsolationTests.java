package com.leonardorozza.mvgrreparacionesbackend;

import com.fasterxml.jackson.databind.JsonNode;
import com.leonardorozza.mvgrreparacionesbackend.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Invariante más crítico del sistema: un taller NUNCA accede a datos de otro.
 */
class TenantIsolationTests extends IntegrationTestBase {

    @Test
    void unTallerNoVeNiAccedeAClientesDeOtro() throws Exception {
        String a = registrar("Taller A", "iso-a@test.com");
        String b = registrar("Taller B", "iso-b@test.com");

        long cliA = idOf(authPost("/api/clientes", a, json(Map.of(
                "nombre", "AnaDeA", "apellido", "X", "telefono", "9001"))).andExpect(status().isOk()));

        JsonNode listaB = node(authGet("/api/clientes", b).andExpect(status().isOk()));
        assertThat(listaB.get("page").get("totalElements").asInt()).isZero();

        authGet("/api/clientes/" + cliA, b).andExpect(status().isNotFound());
        authGet("/api/clientes/" + cliA, a).andExpect(status().isOk());
    }

    @Test
    void unTallerNoAccedeAEquiposNiReparacionesDeOtro() throws Exception {
        String a = registrar("Taller C", "iso-c@test.com");
        String b = registrar("Taller D", "iso-d@test.com");

        JsonNode ir = node(authPost("/api/reparaciones/ingreso-rapido", a, json(Map.of(
                "clienteNombre", "Cli", "clienteTelefono", "9002",
                "equipoMarca", "Apple", "equipoModelo", "iPhone", "descripcionProblema", "z")))
                .andExpect(status().isCreated()));
        long equipoId = ir.get("equipoId").asLong();
        long repId = ir.get("reparacion").get("id").asLong();

        authGet("/api/equipos/" + equipoId, b).andExpect(status().isNotFound());
        authGet("/api/reparaciones/" + repId, b).andExpect(status().isNotFound());
        assertThat(node(authGet("/api/reparaciones", b).andExpect(status().isOk()))
                .get("page").get("totalElements").asInt()).isZero();

        // El dueño sí accede
        authGet("/api/reparaciones/" + repId, a).andExpect(status().isOk());
    }

    @Test
    void unTallerNoBorraClienteDeOtro() throws Exception {
        String a = registrar("Taller E", "iso-e@test.com");
        String b = registrar("Taller F", "iso-f@test.com");

        long cliA = idOf(authPost("/api/clientes", a, json(Map.of(
                "nombre", "Pedro", "apellido", "X", "telefono", "9003"))).andExpect(status().isOk()));

        authDelete("/api/clientes/" + cliA, b).andExpect(status().isNotFound());
        authGet("/api/clientes/" + cliA, a).andExpect(status().isOk());
    }
}
