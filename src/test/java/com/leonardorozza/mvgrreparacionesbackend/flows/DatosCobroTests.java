package com.leonardorozza.mvgrreparacionesbackend.flows;

import com.fasterxml.jackson.databind.JsonNode;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoSuscripcion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PlanType;
import com.leonardorozza.mvgrreparacionesbackend.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DatosCobroTests extends IntegrationTestBase {

    @Test
    void tallerNuevoParteSinDatosPublicosYConResumenHabilitado() throws Exception {
        String token = registrar("Taller Datos Default", "datos-default@test.com");
        activarPro(token);

        JsonNode datos = node(authGet("/api/taller/datos-cobro", token)
                .andExpect(status().isOk()));

        assertThat(datos.size()).isEqualTo(6);
        assertThat(datos.get("alias").isNull()).isTrue();
        assertThat(datos.get("titular").isNull()).isTrue();
        assertThat(datos.get("entidad").isNull()).isTrue();
        assertThat(datos.get("mostrarEnResumen").asBoolean()).isTrue();
        assertThat(datos.get("qrDisponible").asBoolean()).isFalse();
        assertThat(datos.get("qrVersion").isNull()).isTrue();
    }

    @Test
    void putNormalizaYReemplazaElRecursoCompleto() throws Exception {
        String token = registrar("Taller Datos Replace", "datos-replace@test.com");
        activarPro(token);

        JsonNode guardados = node(authPut("/api/taller/datos-cobro", token, json(Map.of(
                "alias", "  celexpress.mp  ",
                "titular", "  Juan Pérez  ",
                "entidad", "  Mercado Pago  ",
                "mostrarEnResumen", false)))
                .andExpect(status().isOk()));

        assertThat(guardados.get("alias").asText()).isEqualTo("celexpress.mp");
        assertThat(guardados.get("titular").asText()).isEqualTo("Juan Pérez");
        assertThat(guardados.get("entidad").asText()).isEqualTo("Mercado Pago");
        assertThat(guardados.get("mostrarEnResumen").asBoolean()).isFalse();
        assertThat(guardados.get("qrDisponible").asBoolean()).isFalse();
        assertThat(guardados.get("qrVersion").isNull()).isTrue();

        var limpieza = om.createObjectNode();
        limpieza.put("alias", "   ");
        limpieza.putNull("titular");
        limpieza.put("entidad", "\t");
        limpieza.put("mostrarEnResumen", true);
        JsonNode borrados = node(authPut(
                "/api/taller/datos-cobro", token, limpieza.toString())
                .andExpect(status().isOk()));

        assertThat(borrados.get("alias").isNull()).isTrue();
        assertThat(borrados.get("titular").isNull()).isTrue();
        assertThat(borrados.get("entidad").isNull()).isTrue();
        assertThat(borrados.get("mostrarEnResumen").asBoolean()).isTrue();

        JsonNode releidos = node(authGet("/api/taller/datos-cobro", token)
                .andExpect(status().isOk()));
        assertThat(releidos).isEqualTo(borrados);
    }

    @Test
    void validaCamposObligatoriosYLimitesLuegoDelTrim() throws Exception {
        String token = registrar("Taller Datos Límites", "datos-limites@test.com");
        activarPro(token);

        authPut("/api/taller/datos-cobro", token, json(Map.of(
                "alias", "alias-sin-bandera")))
                .andExpect(status().isBadRequest());

        authPut("/api/taller/datos-cobro", token, json(Map.of(
                "alias", "a".repeat(121),
                "titular", "t".repeat(161),
                "entidad", "e".repeat(121),
                "mostrarEnResumen", true)))
                .andExpect(status().isBadRequest());

        JsonNode maximos = node(authPut("/api/taller/datos-cobro", token, json(Map.of(
                "alias", " " + "a".repeat(120) + " ",
                "titular", " " + "t".repeat(160) + " ",
                "entidad", " " + "e".repeat(120) + " ",
                "mostrarEnResumen", false)))
                .andExpect(status().isOk()));

        assertThat(maximos.get("alias").asText()).hasSize(120);
        assertThat(maximos.get("titular").asText()).hasSize(160);
        assertThat(maximos.get("entidad").asText()).hasSize(120);
    }

    @Test
    void ambosEndpointsRequierenLaFeatureCobros() throws Exception {
        String token = registrar("Taller Datos Free", "datos-free@test.com");
        configurarSuscripcion(token, PlanType.FREE, EstadoSuscripcion.ACTIVA);

        authGet("/api/taller/datos-cobro", token).andExpect(status().is(402));
        authPut("/api/taller/datos-cobro", token, json(Map.of(
                "alias", "free.mp",
                "mostrarEnResumen", true)))
                .andExpect(status().is(402));
    }
}
