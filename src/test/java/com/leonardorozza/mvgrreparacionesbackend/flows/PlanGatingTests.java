package com.leonardorozza.mvgrreparacionesbackend.flows;

import com.fasterxml.jackson.databind.JsonNode;
import com.leonardorozza.mvgrreparacionesbackend.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PlanGatingTests extends IntegrationTestBase {

    @Test
    void freeNoAccedeAFuncionesProYExponeCapacidades() throws Exception {
        String t = registrar("Taller Free", "gating-free@test.com");

        authGet("/api/inventario", t).andExpect(status().is(402));
        authGet("/api/caja", t).andExpect(status().is(402));

        // El dashboard es FREE
        authGet("/api/dashboard", t).andExpect(status().isOk());

        JsonNode funciones = node(authGet("/api/suscripcion", t).andExpect(status().isOk())).get("funciones");
        assertThat(funciones.get("inventario").asBoolean()).isFalse();
        assertThat(funciones.get("cobros").asBoolean()).isFalse();
        assertThat(funciones.get("empleadosMultiples").asBoolean()).isFalse();
    }

    @Test
    void proAccedeAFuncionesProYCapacidadesEnTrue() throws Exception {
        String t = registrar("Taller Pro", "gating-pro@test.com");
        activarPro(t);

        authGet("/api/inventario", t).andExpect(status().isOk());
        authGet("/api/caja", t).andExpect(status().isOk());

        JsonNode funciones = node(authGet("/api/suscripcion", t).andExpect(status().isOk())).get("funciones");
        assertThat(funciones.get("inventario").asBoolean()).isTrue();
        assertThat(funciones.get("cobros").asBoolean()).isTrue();
        assertThat(funciones.get("empleadosMultiples").asBoolean()).isTrue();
    }

    @Test
    void agregarSegundoEmpleadoEsPro() throws Exception {
        String free = registrar("Taller Emp Free", "emp-free@test.com");
        authPost("/api/usuarios", free, json(Map.of(
                "username", "Emp", "email", "emp1@test.com", "password", "secret123")))
                .andExpect(status().is(402));

        String pro = registrar("Taller Emp Pro", "emp-pro@test.com");
        activarPro(pro);
        authPost("/api/usuarios", pro, json(Map.of(
                "username", "Emp", "email", "emp2@test.com", "password", "secret123")))
                .andExpect(status().isCreated());
    }
}
