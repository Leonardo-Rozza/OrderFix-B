package com.leonardorozza.mvgrreparacionesbackend.flows;

import com.leonardorozza.mvgrreparacionesbackend.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Flujos de cuenta por email: olvido/reset de contraseña y verificación de email.
 */
class CuentaTests extends IntegrationTestBase {

    private ResultActions publicPost(String url, Object body) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    @Test
    void resetDePasswordFlujoCompleto() throws Exception {
        registrar("Taller Cuenta", "cuenta@test.com");

        // Pide el link de reset
        publicPost("/api/auth/password/olvide", Map.of("email", "cuenta@test.com"))
                .andExpect(status().isOk());
        String token = emails.ultimoToken("cuenta@test.com");
        assertThat(token).isNotBlank();

        // Resetea con el token del email
        publicPost("/api/auth/password/reset", Map.of("token", token, "nuevaPassword", "nueva123"))
                .andExpect(status().isOk());

        // La contraseña vieja ya no sirve; la nueva sí
        publicPost("/api/auth/login", Map.of("email", "cuenta@test.com", "password", "secret123"))
                .andExpect(status().isUnauthorized());
        publicPost("/api/auth/login", Map.of("email", "cuenta@test.com", "password", "nueva123"))
                .andExpect(status().isOk());

        // El token es de un solo uso
        publicPost("/api/auth/password/reset", Map.of("token", token, "nuevaPassword", "otra123"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void olvideConEmailInexistenteDevuelve200SinMandarNada() throws Exception {
        publicPost("/api/auth/password/olvide", Map.of("email", "no-existe@test.com"))
                .andExpect(status().isOk());
        assertThat(emails.ultimoCuerpo("no-existe@test.com")).isNull();
    }

    @Test
    void resetConTokenInventadoEs400() throws Exception {
        publicPost("/api/auth/password/reset", Map.of("token", "token-trucho", "nuevaPassword", "loquesea1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void pedirNuevoLinkInvalidaElAnterior() throws Exception {
        registrar("Taller Cuenta2", "cuenta2@test.com");

        publicPost("/api/auth/password/olvide", Map.of("email", "cuenta2@test.com")).andExpect(status().isOk());
        String primero = emails.ultimoToken("cuenta2@test.com");
        publicPost("/api/auth/password/olvide", Map.of("email", "cuenta2@test.com")).andExpect(status().isOk());
        String segundo = emails.ultimoToken("cuenta2@test.com");
        assertThat(segundo).isNotEqualTo(primero);

        // El primero quedó muerto; el segundo funciona
        publicPost("/api/auth/password/reset", Map.of("token", primero, "nuevaPassword", "nueva123"))
                .andExpect(status().isBadRequest());
        publicPost("/api/auth/password/reset", Map.of("token", segundo, "nuevaPassword", "nueva123"))
                .andExpect(status().isOk());
    }

    @Test
    void verificacionDeEmailFlujoCompleto() throws Exception {
        // El registro devuelve emailVerificado:false y manda el email de bienvenida
        String body = json(Map.of(
                "nombreTaller", "Taller Verif", "telefonoTaller", "1100000000",
                "nombreAdmin", "Admin", "email", "verif@test.com", "password", "secret123"));
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.emailVerificado").value(false));

        String token = emails.ultimoToken("verif@test.com");
        assertThat(token).isNotBlank();

        // Confirma con el token
        publicPost("/api/auth/verificar-email", Map.of("token", token)).andExpect(status().isOk());

        // El login ahora refleja emailVerificado:true
        publicPost("/api/auth/login", Map.of("email", "verif@test.com", "password", "secret123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailVerificado").value(true));

        // Reenviar para una cuenta ya verificada: 200 pero no manda nada nuevo
        emails.enviar("verif@test.com", "marca", "sin-token");
        publicPost("/api/auth/verificar-email/reenviar", Map.of("email", "verif@test.com"))
                .andExpect(status().isOk());
        assertThat(emails.ultimoToken("verif@test.com")).isNull();
    }

    @Test
    void reenviarVerificacionMandaTokenNuevoUtilizable() throws Exception {
        registrar("Taller Verif2", "verif2@test.com");
        String original = emails.ultimoToken("verif2@test.com");

        publicPost("/api/auth/verificar-email/reenviar", Map.of("email", "verif2@test.com"))
                .andExpect(status().isOk());
        String reenviado = emails.ultimoToken("verif2@test.com");
        assertThat(reenviado).isNotBlank().isNotEqualTo(original);

        publicPost("/api/auth/verificar-email", Map.of("token", reenviado)).andExpect(status().isOk());
    }
}
