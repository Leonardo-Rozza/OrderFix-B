package com.leonardorozza.mvgrreparacionesbackend;

import com.leonardorozza.mvgrreparacionesbackend.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

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

        authPost("/api/reparaciones/ingreso-rapido", t, ingresoRapido("7001")).andExpect(status().isCreated());
        authPost("/api/reparaciones/ingreso-rapido", t, ingresoRapido("7002")).andExpect(status().is(402));
    }
}
