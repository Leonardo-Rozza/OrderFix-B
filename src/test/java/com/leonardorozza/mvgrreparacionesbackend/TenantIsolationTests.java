package com.leonardorozza.mvgrreparacionesbackend;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.security.web.FilterChainProxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Garantiza el aislamiento multi-tenant: un taller NUNCA accede a datos de otro.
 * Es el invariante más crítico del sistema.
 */
@SpringBootTest
class TenantIsolationTests {

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

    private String register(String taller, String email) throws Exception {
        String body = "{\"nombreTaller\":\"" + taller + "\",\"telefonoTaller\":\"111\","
                + "\"nombreAdmin\":\"Admin\",\"email\":\"" + email + "\",\"password\":\"secret123\"}";
        String resp = mvc().perform(post("/api/auth/register").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return om.readTree(resp).get("token").asText();
    }

    private long crearCliente(String token, String nombre, String tel) throws Exception {
        String body = "{\"nombre\":\"" + nombre + "\",\"apellido\":\"X\",\"telefono\":\"" + tel + "\"}";
        String resp = mvc().perform(post("/api/clientes")
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return om.readTree(resp).get("id").asLong();
    }

    @Test
    void unTallerNoVeNiAccedeAClientesDeOtro() throws Exception {
        String tokenA = register("Taller A", "iso-a@test.com");
        String tokenB = register("Taller B", "iso-b@test.com");

        long cliA = crearCliente(tokenA, "AnaDeA", "9001");

        // B no ve el cliente de A en su listado
        String listB = mvc().perform(get("/api/clientes").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(listB).doesNotContain("AnaDeA");
        assertThat(om.readTree(listB).get("page").get("totalElements").asInt()).isZero();

        // B no puede leer el cliente de A -> 404
        mvc().perform(get("/api/clientes/" + cliA).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());

        // A sí lo ve
        mvc().perform(get("/api/clientes/" + cliA).header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk());
    }

    @Test
    void unTallerNoPuedeBorrarClienteDeOtro() throws Exception {
        String tokenA = register("Taller C", "iso-c@test.com");
        String tokenB = register("Taller D", "iso-d@test.com");

        long cliA = crearCliente(tokenA, "PedroDeC", "9002");

        // B intenta borrar el cliente de A -> 404 (no existe para su taller), nunca 204
        mvc().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/clientes/" + cliA)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());

        // A todavía lo tiene
        mvc().perform(get("/api/clientes/" + cliA).header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk());
    }

    @Test
    void sinTokenNoSeAccede() throws Exception {
        mvc().perform(get("/api/clientes")).andExpect(status().isForbidden());
    }
}
