package com.leonardorozza.mvgrreparacionesbackend.flows;

import com.fasterxml.jackson.databind.JsonNode;
import com.leonardorozza.mvgrreparacionesbackend.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Estado de pago derivado en el DTO de reparación (dimensión independiente del
 * estado de reparación): cobrado / saldo / estadoPago según total vs cobrado.
 */
class EstadoPagoTests extends IntegrationTestBase {

    @Test
    void estadoPagoEvolucionaSinCobrarParcialPagado() throws Exception {
        String t = registrar("Taller Pago", "pago@test.com");
        activarPro(t);

        // Reparación con total 50000 (mano de obra)
        long repId = node(authPost("/api/reparaciones/ingreso-rapido", t, json(Map.of(
                "clienteNombre", "Pa", "clienteTelefono", "8101",
                "equipoMarca", "Motorola", "equipoModelo", "G31",
                "descripcionProblema", "no carga", "precioEstimado", 50000)))
                .andExpect(status().isCreated())).get("reparacion").get("id").asLong();

        // Sin cobros → SIN_COBRAR, saldo = total
        JsonNode r0 = node(authGet("/api/reparaciones/" + repId, t).andExpect(status().isOk()));
        assertThat(r0.get("estadoPago").asText()).isEqualTo("SIN_COBRAR");
        assertThat(r0.get("cobrado").asInt()).isEqualTo(0);
        assertThat(r0.get("saldo").asInt()).isEqualTo(50000);

        // Cobro parcial 20000 → PARCIAL, saldo 30000
        authPost("/api/reparaciones/" + repId + "/cobros", t,
                json(Map.of("monto", 20000, "metodo", "EFECTIVO"))).andExpect(status().isCreated());
        JsonNode r1 = node(authGet("/api/reparaciones/" + repId, t).andExpect(status().isOk()));
        assertThat(r1.get("estadoPago").asText()).isEqualTo("PARCIAL");
        assertThat(r1.get("cobrado").asInt()).isEqualTo(20000);
        assertThat(r1.get("saldo").asInt()).isEqualTo(30000);

        // Cobro del saldo → PAGADO, saldo 0
        authPost("/api/reparaciones/" + repId + "/cobros", t,
                json(Map.of("monto", 30000, "metodo", "TRANSFERENCIA"))).andExpect(status().isCreated());
        JsonNode r2 = node(authGet("/api/reparaciones/" + repId, t).andExpect(status().isOk()));
        assertThat(r2.get("estadoPago").asText()).isEqualTo("PAGADO");
        assertThat(r2.get("saldo").asInt()).isEqualTo(0);

        // También aparece denormalizado en el listado paginado
        JsonNode lista = node(authGet("/api/reparaciones?q=8101", t).andExpect(status().isOk()));
        JsonNode item = lista.get("content").get(0);
        assertThat(item.get("estadoPago").asText()).isEqualTo("PAGADO");
        assertThat(item.get("saldo").asInt()).isEqualTo(0);
    }

    @Test
    void reparacionFreeSinCobrosQuedaSinCobrar() throws Exception {
        String t = registrar("Taller PagoFree", "pago-free@test.com");

        long repId = node(authPost("/api/reparaciones/ingreso-rapido", t, json(Map.of(
                "clienteNombre", "Fr", "clienteTelefono", "8102",
                "equipoMarca", "LG", "equipoModelo", "K50",
                "descripcionProblema", "x", "precioEstimado", 10000)))
                .andExpect(status().isCreated())).get("reparacion").get("id").asLong();

        JsonNode r = node(authGet("/api/reparaciones/" + repId, t).andExpect(status().isOk()));
        assertThat(r.get("estadoPago").asText()).isEqualTo("SIN_COBRAR");
        assertThat(r.get("cobrado").asInt()).isEqualTo(0);
        assertThat(r.get("saldo").asInt()).isEqualTo(10000);
    }
}
