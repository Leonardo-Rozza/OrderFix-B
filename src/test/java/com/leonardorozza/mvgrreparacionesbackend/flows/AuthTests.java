package com.leonardorozza.mvgrreparacionesbackend.flows;

import com.leonardorozza.mvgrreparacionesbackend.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthTests extends IntegrationTestBase {

    @Test
    void registroDevuelveToken() throws Exception {
        assertThat(registrar("Taller Auth", "auth-reg@test.com")).isNotBlank();
    }

    @Test
    void loginOk() throws Exception {
        registrar("Taller Login", "auth-login@test.com");
        assertThat(login("auth-login@test.com", "secret123")).isNotBlank();
    }

    @Test
    void loginCredencialesInvalidas401() throws Exception {
        registrar("Taller Bad", "auth-bad@test.com");
        mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                        .content(json(Map.of("email", "auth-bad@test.com", "password", "incorrecta"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void emailDuplicado400() throws Exception {
        registrar("Taller Dup", "auth-dup@test.com");
        mvc.perform(post("/api/auth/register").contentType(APPLICATION_JSON).content(json(Map.of(
                        "nombreTaller", "Otro", "telefonoTaller", "1", "nombreAdmin", "A",
                        "email", "auth-dup@test.com", "password", "secret123"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void endpointProtegidoSinTokenDa403() throws Exception {
        mvc.perform(get("/api/clientes")).andExpect(status().isForbidden());
    }
}
