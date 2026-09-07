package com.leonardorozza.mvgrreparacionesbackend.flows;

import com.leonardorozza.mvgrreparacionesbackend.support.IntegrationTestBase;
import com.auth0.jwt.JWT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthTests extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    @ParameterizedTest
    @ValueSource(strings = {"usuario", "taller"})
    void loginRechazaEstadoActualInactivo(String target) throws Exception {
        String email = "auth-disabled-" + target + "@test.com";
        registrar("Taller inactivo " + target, email);
        if (target.equals("usuario")) {
            jdbc.update("UPDATE users SET active = false WHERE email = ?", email);
        } else {
            jdbc.update("UPDATE talleres SET activo = false WHERE id = "
                    + "(SELECT taller_id FROM users WHERE email = ?)", email);
        }

        var response = mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                        .content(json(Map.of("email", email, "password", "secret123"))))
                .andExpect(status().isUnauthorized()).andReturn().getResponse();
        assertThat(om.readTree(response.getContentAsString()).get("message").asText())
                .isEqualTo("Usuario o contraseña incorrectos");
        assertThat(response.getContentAsString()).doesNotContain(email, "secret123", "tokenVersion");
    }

    @Test
    void loginEmpleadoUsaRolYVerificacionActuales() throws Exception {
        String email = "auth-employee-current@test.com";
        registrar("Taller empleado", email);
        jdbc.update("UPDATE users SET role = 'USER', email_verificado = true, token_version = 7 WHERE email = ?", email);

        var response = mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                        .content(json(Map.of("email", email, "password", "secret123"))))
                .andExpect(status().isOk()).andReturn().getResponse();
        var body = om.readTree(response.getContentAsString());
        var jwt = JWT.decode(body.get("token").asText());
        assertThat(body.get("email").asText()).isEqualTo(email);
        assertThat(body.get("emailVerificado").asBoolean()).isTrue();
        assertThat(body.get("type").asText()).isEqualTo("Bearer");
        assertThat(jwt.getClaim("role").asString()).isEqualTo("ROLE_USER");
        assertThat(jwt.getClaim("tokenVersion").asLong()).isEqualTo(7L);
        authGet("/api/suscripcion", body.get("token").asText()).andExpect(status().isOk());
    }


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
