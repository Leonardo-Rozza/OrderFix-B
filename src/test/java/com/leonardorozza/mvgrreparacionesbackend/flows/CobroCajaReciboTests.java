package com.leonardorozza.mvgrreparacionesbackend.flows;

import com.fasterxml.jackson.databind.JsonNode;
import com.leonardorozza.mvgrreparacionesbackend.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CobroCajaReciboTests extends IntegrationTestBase {

    @Test
    void cobrosParcialesSaldoReciboYCaja() throws Exception {
        String t = registrar("Taller Cobro", "cobro@test.com");
        activarPro(t);

        // Reparación con mano de obra 30000
        long repId = node(authPost("/api/reparaciones/ingreso-rapido", t, json(Map.of(
                "clienteNombre", "Caro", "clienteApellido", "P", "clienteTelefono", "8001",
                "equipoMarca", "Samsung", "equipoModelo", "A52",
                "descripcionProblema", "pantalla", "precioEstimado", 30000)))
                .andExpect(status().isCreated())).get("reparacion").get("id").asLong();

        // + repuesto 20000 → total 50000
        authPost("/api/repuestos", t, json(Map.of(
                "nombre", "Pantalla", "precio", 20000, "reparacionId", repId)))
                .andExpect(status().isCreated());

        // Seña 20000
        authPost("/api/reparaciones/" + repId + "/cobros", t,
                json(Map.of("monto", 20000, "metodo", "EFECTIVO"))).andExpect(status().isCreated());

        JsonNode resumen = node(authGet("/api/reparaciones/" + repId + "/cobros", t).andExpect(status().isOk()));
        assertThat(resumen.get("total").asInt()).isEqualTo(50000);
        assertThat(resumen.get("cobrado").asInt()).isEqualTo(20000);
        assertThat(resumen.get("saldo").asInt()).isEqualTo(30000);
        assertThat(resumen.get("pagado").asBoolean()).isFalse();

        // Saldo 30000
        authPost("/api/reparaciones/" + repId + "/cobros", t,
                json(Map.of("monto", 30000, "metodo", "TRANSFERENCIA"))).andExpect(status().isCreated());

        JsonNode recibo = node(authGet("/api/reparaciones/" + repId + "/recibo", t).andExpect(status().isOk()));
        assertThat(recibo.get("total").asInt()).isEqualTo(50000);
        assertThat(recibo.get("cobrado").asInt()).isEqualTo(50000);
        assertThat(recibo.get("saldo").asInt()).isEqualTo(0);
        assertThat(recibo.get("pagado").asBoolean()).isTrue();

        JsonNode caja = node(authGet("/api/caja", t).andExpect(status().isOk()));
        assertThat(caja.get("totalCobrado").asInt()).isEqualTo(50000);
        assertThat(caja.get("cantidad").asInt()).isEqualTo(2);
        assertThat(caja.get("porMetodo").get("EFECTIVO").asInt()).isEqualTo(20000);
        assertThat(caja.get("porMetodo").get("TRANSFERENCIA").asInt()).isEqualTo(30000);
    }

    @Test
    void rechazaUnCobroQueSuperaElPendienteYConservaElEstado() throws Exception {
        String token = registrar("Taller Límite Cobro", "cobro-limite@test.com");
        activarPro(token);
        long reparacionId = node(authPost("/api/reparaciones/ingreso-rapido", token, json(Map.of(
                "clienteNombre", "Luz", "clienteTelefono", "8002",
                "equipoMarca", "Apple", "equipoModelo", "iPhone 13",
                "descripcionProblema", "No carga", "precioEstimado", 50000)))
                .andExpect(status().isCreated())).at("/reparacion/id").asLong();

        authPost("/api/reparaciones/" + reparacionId + "/cobros", token,
                json(Map.of("monto", 20000, "metodo", "EFECTIVO")))
                .andExpect(status().isCreated());

        JsonNode error = node(authPost("/api/reparaciones/" + reparacionId + "/cobros", token,
                json(Map.of("monto", 30001, "metodo", "TRANSFERENCIA")))
                .andExpect(status().isConflict()));

        assertThat(error.get("code").asText()).isEqualTo("COBRO_SUPERA_SALDO");
        assertThat(error.get("message").asText()).isEqualTo("El monto supera el pendiente de cobro.");
        assertThat(error.at("/details/reparacionId").asLong()).isEqualTo(reparacionId);
        assertThat(error.at("/details/total").asInt()).isEqualTo(50000);
        assertThat(error.at("/details/cobrado").asInt()).isEqualTo(20000);
        assertThat(error.at("/details/monto").asInt()).isEqualTo(30001);
        assertThat(error.at("/details/pendiente").asInt()).isEqualTo(30000);

        JsonNode resumen = node(authGet("/api/reparaciones/" + reparacionId + "/cobros", token)
                .andExpect(status().isOk()));
        assertThat(resumen.get("cobrado").asInt()).isEqualTo(20000);
        assertThat(resumen.get("saldo").asInt()).isEqualTo(30000);
        assertThat(resumen.get("cobros")).hasSize(1);
    }
}
