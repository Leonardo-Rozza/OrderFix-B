package com.leonardorozza.mvgrreparacionesbackend.flows;

import com.fasterxml.jackson.databind.JsonNode;
import com.leonardorozza.mvgrreparacionesbackend.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ingreso enriquecido (§1/§2): número de orden correlativo por taller con
 * reinicio anual, flags de riesgo y bandera roja de cuenta sin credenciales.
 */
class IngresoEnriquecidoTests extends IntegrationTestBase {

    private JsonNode ingreso(String t, String tel) throws Exception {
        return node(authPost("/api/reparaciones/ingreso-rapido", t, json(Map.of(
                "clienteNombre", "A", "clienteTelefono", tel,
                "equipoMarca", "Apple", "equipoModelo", "iPhone 12", "descripcionProblema", "pantalla")))
                .andExpect(status().isCreated()));
    }

    @Test
    void numeroOrdenEsCorrelativoPorTaller() throws Exception {
        String t = registrar("Taller Num", "num@test.com");
        assertThat(ingreso(t, "9001").get("reparacion").get("numeroOrden").asText()).matches("ORD-\\d{4}-0001");
        assertThat(ingreso(t, "9002").get("reparacion").get("numeroOrden").asText()).matches("ORD-\\d{4}-0002");

        // Otro taller arranca su propia numeración en 0001
        String t2 = registrar("Taller Num2", "num2@test.com");
        assertThat(ingreso(t2, "9003").get("reparacion").get("numeroOrden").asText()).matches("ORD-\\d{4}-0001");
    }

    @Test
    void banderaRojaCuentaSinCredenciales() throws Exception {
        String t = registrar("Taller Flag", "flag@test.com");
        JsonNode ir = ingreso(t, "9101");
        long repId = ir.get("reparacion").get("id").asLong();
        long equipoId = ir.get("equipoId").asLong();

        // Recién ingresada: sin flags ni riesgo
        JsonNode r0 = node(authGet("/api/reparaciones/" + repId, t).andExpect(status().isOk()));
        assertThat(r0.get("tieneCuentaVinculada").asText()).isEqualTo("NINGUNA");
        assertThat(r0.get("riesgoCuentaSinCredenciales").asBoolean()).isFalse();

        // Cuenta iCloud activa + cliente NO conoce credenciales → bandera roja + flags
        Map<String, Object> body = new HashMap<>();
        body.put("equipoId", equipoId);
        body.put("descripcionProblema", "pantalla");
        body.put("tieneCuentaVinculada", "ICLOUD");
        body.put("clienteConoceCredenciales", false);
        body.put("mojado", true);
        body.put("trabajoEnPlaca", true);
        body.put("tieneBloqueoPantalla", true);
        JsonNode upd = node(authPut("/api/reparaciones/" + repId, t, json(body)).andExpect(status().isOk()));
        assertThat(upd.get("tieneCuentaVinculada").asText()).isEqualTo("ICLOUD");
        assertThat(upd.get("riesgoCuentaSinCredenciales").asBoolean()).isTrue();
        assertThat(upd.get("mojado").asBoolean()).isTrue();
        assertThat(upd.get("trabajoEnPlaca").asBoolean()).isTrue();
        assertThat(upd.get("tieneBloqueoPantalla").asBoolean()).isTrue();

        // Si el cliente SÍ conoce las credenciales → ya no hay riesgo
        body.put("clienteConoceCredenciales", true);
        JsonNode upd2 = node(authPut("/api/reparaciones/" + repId, t, json(body)).andExpect(status().isOk()));
        assertThat(upd2.get("riesgoCuentaSinCredenciales").asBoolean()).isFalse();
    }
}
