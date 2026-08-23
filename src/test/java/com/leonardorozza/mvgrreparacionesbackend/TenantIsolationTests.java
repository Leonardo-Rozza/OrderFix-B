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

    @Test
    void unTallerNoAnulaCobrosDeOtroNiPorEndpointCanonicoNiLegado() throws Exception {
        String a = registrar("Taller Cobro A", "iso-cobro-a@test.com");
        String b = registrar("Taller Cobro B", "iso-cobro-b@test.com");
        activarPro(a);
        activarPro(b);

        long reparacionA = node(authPost("/api/reparaciones/ingreso-rapido", a, json(Map.of(
                "clienteNombre", "Cliente A", "clienteTelefono", "9004",
                "equipoMarca", "Samsung", "equipoModelo", "A54",
                "descripcionProblema", "Pantalla", "precioEstimado", 30000)))
                .andExpect(status().isCreated())).at("/reparacion/id").asLong();
        long cobroA = idOf(authPost("/api/reparaciones/" + reparacionA + "/cobros", a,
                json(Map.of("monto", 10000, "metodo", "TRANSFERENCIA")))
                .andExpect(status().isCreated()));

        authPost("/api/reparaciones/" + reparacionA + "/cobros/" + cobroA + "/anulacion",
                b, json(Map.of("motivo", "Intento ajeno")))
                .andExpect(status().isNotFound());
        authDelete("/api/reparaciones/" + reparacionA + "/cobros/" + cobroA, b)
                .andExpect(status().isNotFound());

        JsonNode estadoA = node(authGet("/api/reparaciones/" + reparacionA + "/cobros", a)
                .andExpect(status().isOk()));
        assertThat(estadoA.get("cobrado").asInt()).isEqualTo(10000);
        assertThat(estadoA.at("/cobros/0/estado").asText()).isEqualTo("ACTIVO");
    }

    @Test
    void datosDeCobroSeResuelvenSiempreDesdeElTenantAutenticado() throws Exception {
        String a = registrar("Taller Datos A", "iso-datos-a@test.com");
        String b = registrar("Taller Datos B", "iso-datos-b@test.com");
        activarPro(a);
        activarPro(b);

        authPut("/api/taller/datos-cobro", a, json(Map.of(
                "alias", "taller-a.mp",
                "titular", "Titular A",
                "mostrarEnResumen", true)))
                .andExpect(status().isOk());
        authPut("/api/taller/datos-cobro", b, json(Map.of(
                "alias", "taller-b.mp",
                "entidad", "Billetera B",
                "mostrarEnResumen", false)))
                .andExpect(status().isOk());

        JsonNode datosA = node(authGet("/api/taller/datos-cobro", a)
                .andExpect(status().isOk()));
        JsonNode datosB = node(authGet("/api/taller/datos-cobro", b)
                .andExpect(status().isOk()));

        assertThat(datosA.get("alias").asText()).isEqualTo("taller-a.mp");
        assertThat(datosA.get("titular").asText()).isEqualTo("Titular A");
        assertThat(datosA.get("entidad").isNull()).isTrue();
        assertThat(datosA.get("mostrarEnResumen").asBoolean()).isTrue();

        assertThat(datosB.get("alias").asText()).isEqualTo("taller-b.mp");
        assertThat(datosB.get("titular").isNull()).isTrue();
        assertThat(datosB.get("entidad").asText()).isEqualTo("Billetera B");
        assertThat(datosB.get("mostrarEnResumen").asBoolean()).isFalse();
    }
}
