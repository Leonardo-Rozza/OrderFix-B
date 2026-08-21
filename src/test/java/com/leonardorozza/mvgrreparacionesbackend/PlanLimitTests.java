package com.leonardorozza.mvgrreparacionesbackend;

import com.fasterxml.jackson.databind.JsonNode;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoSuscripcion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PlanType;
import com.leonardorozza.mvgrreparacionesbackend.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Gating freemium: superado el tope mensual del plan FREE, crear una reparación devuelve 402.
 */
@TestPropertySource(properties = "plan.free.max-reparaciones-mes=1")
class PlanLimitTests extends IntegrationTestBase {

    private String ingresoRapido(String tel) throws Exception {
        return json(Map.of("clienteNombre", "C", "clienteTelefono", tel,
                "equipoMarca", "M", "equipoModelo", "X", "descripcionProblema", "falla"));
    }

    @Test
    void superarElTopeDevuelve402() throws Exception {
        String t = registrar("Taller Limite", "limit@test.com");
        configurarSuscripcion(t, PlanType.FREE, EstadoSuscripcion.ACTIVA);

        authPost("/api/reparaciones/ingreso-rapido", t, ingresoRapido("7001")).andExpect(status().isCreated());
        authPost("/api/reparaciones/ingreso-rapido", t, ingresoRapido("7002")).andExpect(status().is(402));
    }

    @Test
    void trialNoTieneTopeMensual() throws Exception {
        String t = registrar("Taller Trial Sin Limite", "trial-limit@test.com");

        authPost("/api/reparaciones/ingreso-rapido", t, ingresoRapido("7101")).andExpect(status().isCreated());
        authPost("/api/reparaciones/ingreso-rapido", t, ingresoRapido("7102")).andExpect(status().isCreated());
    }

    @Test
    void reclamoEnGarantiaNoConsumeCupo() throws Exception {
        String t = registrar("Taller Gar Limite", "gar-limit@test.com");
        configurarSuscripcion(t, PlanType.FREE, EstadoSuscripcion.ACTIVA);

        // Consume el único cupo del mes
        long repId = node(authPost("/api/reparaciones/ingreso-rapido", t, ingresoRapido("7201"))
                .andExpect(status().isCreated())).get("reparacion").get("id").asLong();
        // Una reparación normal más → 402
        authPost("/api/reparaciones/ingreso-rapido", t, ingresoRapido("7202")).andExpect(status().is(402));

        // Pero un reclamo en garantía del primero SÍ se crea (no consume cupo)
        JsonNode reclamo = node(authPost("/api/reparaciones/" + repId + "/garantia", t,
                json(Map.of("descripcionProblema", "vuelve la misma falla"))).andExpect(status().isCreated()));
        assertThat(reclamo.get("esGarantia").asBoolean()).isTrue();
        assertThat(reclamo.get("reparacionOrigenId").asLong()).isEqualTo(repId);
    }
}
