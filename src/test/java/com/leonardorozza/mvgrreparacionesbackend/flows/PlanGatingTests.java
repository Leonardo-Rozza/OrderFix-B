package com.leonardorozza.mvgrreparacionesbackend.flows;

import com.fasterxml.jackson.databind.JsonNode;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoSuscripcion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PlanType;
import com.leonardorozza.mvgrreparacionesbackend.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PlanGatingTests extends IntegrationTestBase {

    @Test
    void freeActivoNoAccedeAFuncionesProYExponeCapacidades() throws Exception {
        String t = registrar("Taller Free", "gating-free@test.com");
        long reparacionId = node(authPost("/api/reparaciones/ingreso-rapido", t, json(Map.of(
                "clienteNombre", "Cliente",
                "clienteTelefono", "8201",
                "equipoMarca", "Samsung",
                "equipoModelo", "A54",
                "descripcionProblema", "Pantalla")))
                .andExpect(status().isCreated())).at("/reparacion/id").asLong();
        configurarSuscripcion(t, PlanType.FREE, EstadoSuscripcion.ACTIVA);

        authGet("/api/inventario", t).andExpect(status().is(402));
        authGet("/api/caja", t).andExpect(status().is(402));
        JsonNode resumenError = node(authGet(
                "/api/reparaciones/" + reparacionId + "/resumen-digital", t)
                .andExpect(status().is(402)));
        JsonNode aliasError = node(authGet(
                "/api/reparaciones/" + reparacionId + "/recibo", t)
                .andExpect(status().is(402)));
        assertThat(resumenError.get("message").asText())
                .contains("cobros manuales", "resumen digital")
                .doesNotContain("caja", "recibo");
        assertThat(aliasError.get("message").asText()).isEqualTo(resumenError.get("message").asText());

        // El dashboard es FREE
        authGet("/api/dashboard", t).andExpect(status().isOk());

        JsonNode funciones = node(authGet("/api/suscripcion", t).andExpect(status().isOk())).get("funciones");
        assertThat(funciones.get("inventario").asBoolean()).isFalse();
        assertThat(funciones.get("cobros").asBoolean()).isFalse();
        assertThat(funciones.get("empleadosMultiples").asBoolean()).isFalse();
    }

    @Test
    void trialAccedeAFuncionesProAunqueElPlanComercialSeaFree() throws Exception {
        String t = registrar("Taller Trial", "gating-trial@test.com");
        long reparacionId = node(authPost("/api/reparaciones/ingreso-rapido", t, json(Map.of(
                "clienteNombre", "Cliente",
                "clienteTelefono", "8202",
                "equipoMarca", "Apple",
                "equipoModelo", "iPhone",
                "descripcionProblema", "Carga")))
                .andExpect(status().isCreated())).at("/reparacion/id").asLong();

        authGet("/api/inventario", t).andExpect(status().isOk());
        authGet("/api/caja", t).andExpect(status().isOk());
        authGet("/api/reparaciones/" + reparacionId + "/resumen-digital", t)
                .andExpect(status().isOk());
        authGet("/api/reparaciones/" + reparacionId + "/recibo", t)
                .andExpect(status().isOk());

        JsonNode funciones = node(authGet("/api/suscripcion", t).andExpect(status().isOk())).get("funciones");
        assertThat(funciones.get("inventario").asBoolean()).isTrue();
        assertThat(funciones.get("cobros").asBoolean()).isTrue();
        assertThat(funciones.get("empleadosMultiples").asBoolean()).isTrue();
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
    void proVencidoNoAccedeAFuncionesPro() throws Exception {
        String t = registrar("Taller Pro Vencido", "gating-pro-vencido@test.com");
        configurarSuscripcion(t, PlanType.PRO, EstadoSuscripcion.VENCIDA);

        authGet("/api/inventario", t).andExpect(status().is(402));
        authGet("/api/caja", t).andExpect(status().is(402));

        JsonNode funciones = node(authGet("/api/suscripcion", t).andExpect(status().isOk())).get("funciones");
        assertThat(funciones.get("inventario").asBoolean()).isFalse();
        assertThat(funciones.get("cobros").asBoolean()).isFalse();
        assertThat(funciones.get("empleadosMultiples").asBoolean()).isFalse();
    }

    @Test
    void proCanceladoNoAccedeAFuncionesPro() throws Exception {
        String t = registrar("Taller Pro Cancelado", "gating-pro-cancelado@test.com");
        configurarSuscripcion(t, PlanType.PRO, EstadoSuscripcion.CANCELADA);

        authGet("/api/inventario", t).andExpect(status().is(402));
        authGet("/api/caja", t).andExpect(status().is(402));

        JsonNode funciones = node(authGet("/api/suscripcion", t).andExpect(status().isOk())).get("funciones");
        assertThat(funciones.get("inventario").asBoolean()).isFalse();
        assertThat(funciones.get("cobros").asBoolean()).isFalse();
        assertThat(funciones.get("empleadosMultiples").asBoolean()).isFalse();
    }

    @Test
    void agregarSegundoEmpleadoEsPro() throws Exception {
        String free = registrar("Taller Emp Free", "emp-free@test.com");
        configurarSuscripcion(free, PlanType.FREE, EstadoSuscripcion.ACTIVA);
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
