package com.leonardorozza.mvgrreparacionesbackend;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifica el gating freemium: superado el tope mensual del plan FREE, la creación
 * de reparaciones devuelve 402.
 */
@SpringBootTest
@TestPropertySource(properties = "plan.free.max-reparaciones-mes=1")
class PlanLimitTests {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private FilterChainProxy springSecurityFilterChain;

    private final ObjectMapper om = new ObjectMapper();
    private MockMvc mvc;

    private MockMvc mvc() {
        if (mvc == null) {
            mvc = MockMvcBuilders.webAppContextSetup(context)
                    .addFilters(springSecurityFilterChain)
                    .build();
        }
        return mvc;
    }

    private String register() throws Exception {
        String body = "{\"nombreTaller\":\"Taller Limite\",\"telefonoTaller\":\"111\","
                + "\"nombreAdmin\":\"Admin\",\"email\":\"limit@test.com\",\"password\":\"secret123\"}";
        String resp = mvc().perform(post("/api/auth/register").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return om.readTree(resp).get("token").asText();
    }

    private org.springframework.test.web.servlet.ResultActions ingresoRapido(String token, String tel) throws Exception {
        String body = "{\"clienteNombre\":\"C\",\"clienteTelefono\":\"" + tel + "\","
                + "\"equipoMarca\":\"M\",\"equipoModelo\":\"X\",\"descripcionProblema\":\"falla\"}";
        return mvc().perform(post("/api/reparaciones/ingreso-rapido")
                .header("Authorization", "Bearer " + token)
                .contentType(APPLICATION_JSON).content(body));
    }

    @Test
    void superarElTopeDevuelve402() throws Exception {
        String token = register();

        // Primera reparación: OK (límite = 1)
        ingresoRapido(token, "7001").andExpect(status().isCreated());

        // Segunda: supera el tope del plan FREE -> 402
        ingresoRapido(token, "7002").andExpect(status().is(402));
    }
}
