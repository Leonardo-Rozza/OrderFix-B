package com.leonardorozza.mvgrreparacionesbackend.flows;

import com.fasterxml.jackson.databind.JsonNode;
import com.leonardorozza.mvgrreparacionesbackend.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InventarioStockTests extends IntegrationTestBase {

    @Test
    void stockSeDescuentaAlUsarYSeReponeAlBorrar() throws Exception {
        String t = registrar("Taller Inv", "inv@test.com");
        activarPro(t);

        long artId = idOf(authPost("/api/inventario", t, json(Map.of(
                "nombre", "Pin de carga", "sku", "PIN", "precio", 8000, "stock", 5, "stockMinimo", 2)))
                .andExpect(status().isCreated()));

        // Ajuste +10 → 15
        assertThat(node(authPost("/api/inventario/" + artId + "/ajuste", t,
                json(Map.of("delta", 10, "motivo", "compra"))).andExpect(status().isOk()))
                .get("stock").asInt()).isEqualTo(15);

        long repId = node(authPost("/api/reparaciones/ingreso-rapido", t, json(Map.of(
                "clienteNombre", "C", "clienteTelefono", "7001",
                "equipoMarca", "M", "equipoModelo", "X", "descripcionProblema", "z")))
                .andExpect(status().isCreated())).get("reparacion").get("id").asLong();

        // Usar 3 → stock 12
        long repuestoId = idOf(authPost("/api/repuestos", t, json(Map.of(
                "nombre", "Pin", "precio", 8000, "reparacionId", repId, "articuloId", artId, "cantidad", 3)))
                .andExpect(status().isCreated()));
        assertThat(node(authGet("/api/inventario/" + artId, t).andExpect(status().isOk()))
                .get("stock").asInt()).isEqualTo(12);

        // Borrar el repuesto → repone a 15
        authDelete("/api/repuestos/" + repuestoId, t).andExpect(status().isNoContent());
        assertThat(node(authGet("/api/inventario/" + artId, t).andExpect(status().isOk()))
                .get("stock").asInt()).isEqualTo(15);
    }

    @Test
    void stockInsuficienteDa400() throws Exception {
        String t = registrar("Taller Inv2", "inv2@test.com");
        activarPro(t);
        long artId = idOf(authPost("/api/inventario", t, json(Map.of(
                "nombre", "Bateria", "precio", 5000, "stock", 1, "stockMinimo", 0)))
                .andExpect(status().isCreated()));
        long repId = node(authPost("/api/reparaciones/ingreso-rapido", t, json(Map.of(
                "clienteNombre", "C", "clienteTelefono", "7002",
                "equipoMarca", "M", "equipoModelo", "X", "descripcionProblema", "z")))
                .andExpect(status().isCreated())).get("reparacion").get("id").asLong();

        authPost("/api/repuestos", t, json(Map.of(
                "nombre", "Bat", "precio", 5000, "reparacionId", repId, "articuloId", artId, "cantidad", 999)))
                .andExpect(status().is(400));
    }

    @Test
    void stockBajoApareceEnListadoYDashboard() throws Exception {
        String t = registrar("Taller Inv3", "inv3@test.com");
        activarPro(t);
        authPost("/api/inventario", t, json(Map.of(
                "nombre", "Tornillo", "precio", 100, "stock", 1, "stockMinimo", 5)))
                .andExpect(status().isCreated());

        JsonNode bajo = node(authGet("/api/inventario/stock-bajo", t).andExpect(status().isOk()));
        assertThat(bajo.size()).isEqualTo(1);
        assertThat(bajo.get(0).get("stockBajo").asBoolean()).isTrue();

        assertThat(node(authGet("/api/dashboard", t).andExpect(status().isOk()))
                .get("articulosStockBajo").asInt()).isEqualTo(1);
    }
}
