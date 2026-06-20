package com.leonardorozza.mvgrreparacionesbackend.flows;

import com.leonardorozza.mvgrreparacionesbackend.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReparacionDeleteTests extends IntegrationTestBase {

    @Test
    void borrarReparacionLimpiaPresupuestosYReponeStock() throws Exception {
        String t = registrar("Taller Del", "del-ok@test.com");
        activarPro(t);

        long artId = idOf(authPost("/api/inventario", t, json(Map.of(
                "nombre", "Modulo", "precio", 9000, "stock", 10, "stockMinimo", 0)))
                .andExpect(status().isCreated()));

        long repId = node(authPost("/api/reparaciones/ingreso-rapido", t, json(Map.of(
                "clienteNombre", "C", "clienteTelefono", "9501",
                "equipoMarca", "M", "equipoModelo", "X", "descripcionProblema", "z")))
                .andExpect(status().isCreated())).get("reparacion").get("id").asLong();

        // usa 4 del stock (10 → 6)
        authPost("/api/repuestos", t, json(Map.of(
                "nombre", "Modulo", "precio", 9000, "reparacionId", repId, "articuloId", artId, "cantidad", 4)))
                .andExpect(status().isCreated());
        assertThat(node(authGet("/api/inventario/" + artId, t)).get("stock").asInt()).isEqualTo(6);

        // y tiene un presupuesto
        authPost("/api/reparaciones/" + repId + "/presupuestos", t, json(Map.of(
                "items", List.of(Map.of("descripcion", "Modulo", "cantidad", 1, "precioUnitario", 9000)))))
                .andExpect(status().isCreated());

        // borrar → limpia presupuesto (cascade) y repone stock (10)
        authDelete("/api/reparaciones/" + repId, t).andExpect(status().isNoContent());
        assertThat(node(authGet("/api/inventario/" + artId, t)).get("stock").asInt()).isEqualTo(10);
        authGet("/api/reparaciones/" + repId, t).andExpect(status().isNotFound());
    }

    @Test
    void noSePuedeBorrarReparacionConCobros() throws Exception {
        String t = registrar("Taller Del2", "del-cobro@test.com");
        activarPro(t);

        long repId = node(authPost("/api/reparaciones/ingreso-rapido", t, json(Map.of(
                "clienteNombre", "C", "clienteTelefono", "9502",
                "equipoMarca", "M", "equipoModelo", "X", "descripcionProblema", "z", "precioEstimado", 5000)))
                .andExpect(status().isCreated())).get("reparacion").get("id").asLong();

        authPost("/api/reparaciones/" + repId + "/cobros", t,
                json(Map.of("monto", 5000, "metodo", "EFECTIVO"))).andExpect(status().isCreated());

        // borrar bloqueado → 400, y la reparación sigue existiendo
        authDelete("/api/reparaciones/" + repId, t).andExpect(status().isBadRequest());
        authGet("/api/reparaciones/" + repId, t).andExpect(status().isOk());
    }
}
