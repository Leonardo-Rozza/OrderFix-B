package com.leonardorozza.mvgrreparacionesbackend.flows;

import com.leonardorozza.mvgrreparacionesbackend.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class EmailVerificationAccessTests extends IntegrationTestBase {
    @Test void newOwnerHasOnlyPreviewUntilTheActualEmailLinkIsConfirmed() throws Exception {
        String email = "pending-owner-" + UUID.randomUUID() + "@test.com";
        String token = registrarSinVerificar("Pending workshop", email);
        authGet("/api/perfil", token).andExpect(status().isOk())
                .andExpect(jsonPath("$.usuario.emailVerificado").value(false));
        for (String path : new String[]{"/api/clientes", "/api/equipos", "/api/reparaciones", "/api/dashboard",
                "/api/usuarios", "/api/taller/datos-cobro", "/api/export/excel", "/api/cuenta/cierre"}) {
            authGet(path, token).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("EMAIL_NO_VERIFICADO"))
                    .andExpect(header().string("Cache-Control", "private, no-store"));
        }
        String cliente = json(Map.of("nombre", "Not created", "apellido", "Pending", "telefono", "112233"));
        authPost("/api/clientes", token, cliente).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EMAIL_NO_VERIFICADO"));
        authPost("/api/pagos/suscripcion", token, "{}").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EMAIL_NO_VERIFICADO"));
        verificarEmail(email);
        authGet("/api/perfil", token).andExpect(status().isOk())
                .andExpect(jsonPath("$.usuario.emailVerificado").value(true));
        authGet("/api/clientes", token).andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty());
        authPost("/api/clientes", token, cliente).andExpect(status().isOk());
    }

    @Test void newEmployeeReceivesARealVerificationLinkAndCannotOperateUntilConfirmed() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String ownerEmail = "employee-owner-" + suffix + "@test.com";
        String owner = registrar("Employee workshop", ownerEmail);
        activarPro(owner);
        String email = "pending-employee-" + suffix + "@test.com";
        authPost("/api/usuarios", owner, json(Map.of("username", "Employee", "email", email, "password", "secret123")))
                .andExpect(status().isCreated());
        assertThat(emails.ultimoToken(email)).isNotBlank();
        String employee = login(email, "secret123");
        authGet("/api/perfil", employee).andExpect(status().isOk())
                .andExpect(jsonPath("$.usuario.emailVerificado").value(false));
        authGet("/api/clientes", employee).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EMAIL_NO_VERIFICADO"));
        authGet("/api/suscripcion", employee).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EMAIL_NO_VERIFICADO"));
        authPost("/api/pagos/suscripcion/cancelar", employee).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EMAIL_NO_VERIFICADO"));
        verificarEmail(email);
        authGet("/api/perfil", employee).andExpect(status().isOk())
                .andExpect(jsonPath("$.usuario.emailVerificado").value(true));
        authGet("/api/clientes", employee).andExpect(status().isOk());
        authGet("/api/usuarios", employee).andExpect(status().isForbidden());
        authGet("/api/perfil", owner).andExpect(status().isOk())
                .andExpect(jsonPath("$.usuario.emailVerificado").value(true));
    }

    @Test void confirmingAnotherAccountDoesNotUnlockTheOpenSession() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String first = registrarSinVerificar("First pending", "first-" + suffix + "@test.com");
        String secondEmail = "second-" + suffix + "@test.com";
        String second = registrarSinVerificar("Second pending", secondEmail);
        authPost("/api/auth/verificar-email", first, json(Map.of("token", emails.ultimoToken(secondEmail))))
                .andExpect(status().isOk());
        authGet("/api/perfil", first).andExpect(status().isOk())
                .andExpect(jsonPath("$.usuario.emailVerificado").value(false));
        authGet("/api/clientes", first).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EMAIL_NO_VERIFICADO"));
        authGet("/api/perfil", second).andExpect(status().isOk())
                .andExpect(jsonPath("$.usuario.emailVerificado").value(true));
        authGet("/api/clientes", second).andExpect(status().isOk());
    }

    @Test void unverifiedOwnerCanReadAndCancelExistingPlanWithoutAccessingCheckout() throws Exception {
        String owner = registrarSinVerificar("Historical owner", "historical-" + UUID.randomUUID() + "@test.com");
        activarPro(owner); // Synthetic existing paid state; Mercado Pago remains disabled in this test profile.
        authGet("/api/suscripcion", owner).andExpect(status().isOk()).andExpect(jsonPath("$.plan").value("PRO"));
        authPost("/api/pagos/suscripcion/cancelar", owner).andExpect(status().isOk());
        authGet("/api/suscripcion", owner).andExpect(status().isOk()).andExpect(jsonPath("$.plan").value("FREE"));
        authPost("/api/pagos/suscripcion", owner, "{}").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EMAIL_NO_VERIFICADO"));
        authGet("/api/perfil", owner).andExpect(status().isOk())
                .andExpect(jsonPath("$.usuario.emailVerificado").value(false));
    }
}
