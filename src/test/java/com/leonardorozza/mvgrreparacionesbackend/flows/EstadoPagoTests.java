package com.leonardorozza.mvgrreparacionesbackend.flows;

import com.fasterxml.jackson.databind.JsonNode;
import com.leonardorozza.mvgrreparacionesbackend.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Estado de pago derivado en el DTO de reparación (dimensión independiente del
 * estado de reparación): cobrado / saldo / estadoPago según total vs cobrado.
 */
class EstadoPagoTests extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
        assertThat(r0.get("excedente").asInt()).isZero();
        assertThat(r0.get("requiereRevision").asBoolean()).isFalse();

        // Cobro parcial 20000 → PARCIAL, saldo 30000
        authPost("/api/reparaciones/" + repId + "/cobros", t,
                json(Map.of("monto", 20000, "metodo", "EFECTIVO"))).andExpect(status().isCreated());
        JsonNode r1 = node(authGet("/api/reparaciones/" + repId, t).andExpect(status().isOk()));
        assertThat(r1.get("estadoPago").asText()).isEqualTo("PARCIAL");
        assertThat(r1.get("cobrado").asInt()).isEqualTo(20000);
        assertThat(r1.get("saldo").asInt()).isEqualTo(30000);
        assertThat(r1.get("excedente").asInt()).isZero();
        assertThat(r1.get("requiereRevision").asBoolean()).isFalse();

        // Cobro del saldo → PAGADO, saldo 0
        authPost("/api/reparaciones/" + repId + "/cobros", t,
                json(Map.of("monto", 30000, "metodo", "TRANSFERENCIA"))).andExpect(status().isCreated());
        JsonNode r2 = node(authGet("/api/reparaciones/" + repId, t).andExpect(status().isOk()));
        assertThat(r2.get("estadoPago").asText()).isEqualTo("PAGADO");
        assertThat(r2.get("saldo").asInt()).isEqualTo(0);
        assertThat(r2.get("excedente").asInt()).isZero();
        assertThat(r2.get("requiereRevision").asBoolean()).isFalse();

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
        assertThat(r.get("excedente").asInt()).isZero();
        assertThat(r.get("requiereRevision").asBoolean()).isFalse();
    }

    @Test
    void sobrepagoLegacyPriorizaRevisionSinRomperEstadoPagoCompatible() throws Exception {
        String token = registrar("Taller Pago Legacy", "pago-legacy@test.com");
        activarPro(token);

        JsonNode ingreso = node(authPost("/api/reparaciones/ingreso-rapido", token, json(Map.of(
                "clienteNombre", "Lara", "clienteTelefono", "8103",
                "equipoMarca", "Apple", "equipoModelo", "iPhone 12",
                "descripcionProblema", "Pantalla", "precioEstimado", 50000)))
                .andExpect(status().isCreated()));
        long reparacionId = ingreso.at("/reparacion/id").asLong();

        authPost("/api/reparaciones/" + reparacionId + "/cobros", token,
                json(Map.of("monto", 50000, "metodo", "TRANSFERENCIA", "referencia", "LEGACY-50")))
                .andExpect(status().isCreated());

        // Simula un total histórico reducido antes de existir la invariancia transaccional.
        jdbcTemplate.update("UPDATE reparaciones SET precio_estimado = 20000 WHERE id = ?", reparacionId);

        assertEstadoLegacy(node(authGet("/api/reparaciones/" + reparacionId, token)
                .andExpect(status().isOk())), reparacionId);

        JsonNode lista = node(authGet("/api/reparaciones?q=8103", token)
                .andExpect(status().isOk()));
        assertEstadoLegacy(lista.at("/content/0"), reparacionId);

        JsonNode dashboard = node(authGet("/api/dashboard", token).andExpect(status().isOk()));
        assertEstadoLegacy(buscarPorId(dashboard.get("ultimasReparaciones"), reparacionId), reparacionId);
    }

    private void assertEstadoLegacy(JsonNode reparacion, long reparacionId) {
        assertThat(reparacion.get("id").asLong()).isEqualTo(reparacionId);
        assertThat(reparacion.get("total").asInt()).isEqualTo(20000);
        assertThat(reparacion.get("cobrado").asInt()).isEqualTo(50000);
        assertThat(reparacion.get("saldo").asInt()).isZero();
        assertThat(reparacion.get("excedente").asInt()).isEqualTo(30000);
        assertThat(reparacion.get("requiereRevision").asBoolean()).isTrue();
        // Compatibilidad: el enum no cambia; la señal requiereRevision tiene prioridad contractual.
        assertThat(reparacion.get("estadoPago").asText()).isEqualTo("PAGADO");
    }

    private JsonNode buscarPorId(JsonNode reparaciones, long reparacionId) {
        for (JsonNode reparacion : reparaciones) {
            if (reparacion.get("id").asLong() == reparacionId) {
                return reparacion;
            }
        }
        throw new AssertionError("No se encontró la reparación " + reparacionId);
    }
}
