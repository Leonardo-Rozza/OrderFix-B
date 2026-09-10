package com.leonardorozza.mvgrreparacionesbackend.flows;

import com.leonardorozza.mvgrreparacionesbackend.controller.AccountAccessExitController;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoSuscripcion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PlanType;
import com.leonardorozza.mvgrreparacionesbackend.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PersonalAccessExitTests extends IntegrationTestBase {
    private static final String PATH = AccountAccessExitController.PATH;

    @Test void invalidPasswordAndInvalidPayloadLeaveTheUserSessionUsable() throws Exception {
        var account = account();
        var wrong = authPost(PATH, account.employee(), json(Map.of("passwordActual", "wrong", "confirmado", true)))
                .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control", "private, no-store"));
        assertThat(node(wrong).get("code").asText()).isEqualTo("PASSWORD_ACTUAL_INVALIDA");
        for (String body : new String[]{
                "{\"passwordActual\":\"secret123\",\"confirmado\":true,\"userId\":1}",
                "{\"passwordActual\":\"secret123\",\"confirmado\":\"true\"}",
                "{\"passwordActual\":\"secret123\",\"confirmado\":true,\"confirmado\":true}",
                "{\"passwordActual\":\"secret123\",\"confirmado\":true} {}"}) {
            var error = authPost(PATH, account.employee(), body).andExpect(status().isBadRequest());
            assertThat(node(error).get("code").asText()).isEqualTo("BAJA_ACCESO_INVALIDA");
        }
        authPost(PATH + "?tallerId=123", account.employee(), validBody()).andExpect(status().isBadRequest());
        authGet("/api/perfil", account.employee()).andExpect(status().isOk());
        authGet("/api/perfil", account.admin()).andExpect(status().isOk());
    }

    @Test void roleAndAuthenticationRemainRequired() throws Exception {
        var account = account();
        authPost(PATH, account.admin(), validBody()).andExpect(status().isForbidden());
        mvc.perform(post(PATH).contentType(APPLICATION_JSON).content(validBody())).andExpect(status().isForbidden());
        authGet("/api/perfil", account.admin()).andExpect(status().isOk());
        authGet("/api/perfil", account.employee()).andExpect(status().isOk());
    }

    @Test void deactivationIsAvailableWithAnExpiredPlanAndOldTokensDoNotReviveOnExplicitReactivation() throws Exception {
        var account = account();
        configurarSuscripcion(account.admin(), PlanType.FREE, EstadoSuscripcion.VENCIDA);
        authPost(PATH, account.employee(), validBody()).andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(content().string(""));
        authGet("/api/perfil", account.employee()).andExpect(status().isForbidden());
        authGet("/api/perfil", account.admin()).andExpect(status().isOk());
        authPatch("/api/usuarios/" + account.employeeId(), account.admin(), json(Map.of("active", true)))
                .andExpect(status().isOk());
        authGet("/api/perfil", account.employee()).andExpect(status().isForbidden());
        authGet("/api/perfil", login(account.email(), "secret123")).andExpect(status().isOk());
    }

    private Account account() throws Exception {
        String unique = UUID.randomUUID().toString();
        String admin = registrar("Acceso " + unique, "a-" + unique + "@test.com");
        activarPro(admin);
        String email = "e-" + unique + "@test.com";
        long employeeId = idOf(authPost("/api/usuarios", admin,
                json(Map.of("username", "Empleado", "email", email, "password", "secret123")))
                .andExpect(status().isCreated()));
        return new Account(admin, login(email, "secret123"), employeeId, email);
    }
    private String validBody() throws Exception { return json(Map.of("passwordActual", "secret123", "confirmado", true)); }
    private record Account(String admin, String employee, long employeeId, String email) { }
}
