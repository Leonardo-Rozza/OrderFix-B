package com.leonardorozza.mvgrreparacionesbackend.flows;

import com.fasterxml.jackson.databind.JsonNode;
import com.leonardorozza.mvgrreparacionesbackend.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Bloque 6: garantía del trabajo. Se fija al entregar (default 90 días) y el
 * reclamo en garantía crea una reparación nueva vinculada a la original.
 */
class GarantiaTests extends IntegrationTestBase {

    private long nuevaReparacion(String t, String tel) throws Exception {
        return node(authPost("/api/reparaciones/ingreso-rapido", t, json(Map.of(
                "clienteNombre", "C", "clienteTelefono", tel,
                "equipoMarca", "Apple", "equipoModelo", "iPhone 12", "descripcionProblema", "pantalla")))
                .andExpect(status().isCreated())).get("reparacion").get("id").asLong();
    }

    private void entregar(String t, long repId) throws Exception {
        for (String estado : List.of("EN_PROCESO", "COMPLETADO", "ENTREGADO")) {
            authPatch("/api/reparaciones/" + repId + "/estado", t, json(Map.of("estado", estado)))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void garantiaSeFijaAlEntregarConElDefault() throws Exception {
        String t = registrar("Taller Gar", "gar@test.com");
        long repId = nuevaReparacion(t, "5301");

        // Antes de entregar no hay garantía
        assertThat(node(authGet("/api/reparaciones/" + repId, t)).get("garantiaFin").isNull()).isTrue();

        entregar(t, repId);

        JsonNode r = node(authGet("/api/reparaciones/" + repId, t).andExpect(status().isOk()));
        assertThat(r.get("garantiaDias").asInt()).isEqualTo(90);
        assertThat(r.get("garantiaInicio").isNull()).isFalse();
        assertThat(r.get("garantiaFin").isNull()).isFalse();
        assertThat(r.get("garantiaVigente").asBoolean()).isTrue();
    }

    @Test
    void reclamoEnGarantiaVinculaAlOriginal() throws Exception {
        String t = registrar("Taller Gar2", "gar2@test.com");
        long original = nuevaReparacion(t, "5302");
        entregar(t, original);

        JsonNode reclamo = node(authPost("/api/reparaciones/" + original + "/garantia", t,
                json(Map.of("descripcionProblema", "vuelve a fallar la pantalla")))
                .andExpect(status().isCreated()));

        assertThat(reclamo.get("id").asLong()).isNotEqualTo(original);
        assertThat(reclamo.get("esGarantia").asBoolean()).isTrue();
        assertThat(reclamo.get("reparacionOrigenId").asLong()).isEqualTo(original);
        assertThat(reclamo.get("estado").asText()).isEqualTo("INGRESADO");
        // Reúsa el mismo equipo del original
        assertThat(reclamo.get("equipoModelo").asText()).isEqualTo("iPhone 12");
        // Arranca sin precio
        assertThat(reclamo.get("total").asInt()).isEqualTo(0);
    }
}
