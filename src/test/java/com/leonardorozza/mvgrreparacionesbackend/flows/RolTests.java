package com.leonardorozza.mvgrreparacionesbackend.flows;

import com.leonardorozza.mvgrreparacionesbackend.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RolTests extends IntegrationTestBase {

    /** Crea un empleado USER (requiere PRO) y devuelve su token. */
    private String empleadoUser(String adminToken, String email) throws Exception {
        authPost("/api/usuarios", adminToken, json(Map.of(
                "username", "Empleado", "email", email, "password", "secret123")))
                .andExpect(status().isCreated());
        return login(email, "secret123");
    }

    @Test
    void userPuedeCrearPeroNoBorrarNiGestionarUsuarios() throws Exception {
        String admin = registrar("Taller Rol", "rol-admin@test.com");
        activarPro(admin); // para poder crear empleados
        String user = empleadoUser(admin, "rol-user@test.com");

        // USER puede crear un cliente (CRUD normal)
        long clienteId = idOf(authPost("/api/clientes", user, json(Map.of(
                "nombre", "Cli", "apellido", "Ente", "telefono", "9100")))
                .andExpect(status().isOk()));

        // USER NO puede borrar (solo ADMIN) → 403
        authDelete("/api/clientes/" + clienteId, user).andExpect(status().isForbidden());

        // USER NO puede entrar a la gestión de usuarios → 403
        authGet("/api/usuarios", user).andExpect(status().isForbidden());

        // ADMIN sí puede borrar → 204
        authDelete("/api/clientes/" + clienteId, admin).andExpect(status().isNoContent());
    }

    @Test
    void empleadoDesactivadoNoPuedeLoguear() throws Exception {
        String admin = registrar("Taller Rol2", "rol2-admin@test.com");
        activarPro(admin);
        long empId = idOf(authPost("/api/usuarios", admin, json(Map.of(
                "username", "Emp", "email", "rol2-emp@test.com", "password", "secret123")))
                .andExpect(status().isCreated()));

        authPatch("/api/usuarios/" + empId, admin, json(Map.of("active", false)))
                .andExpect(status().isOk());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/auth/login").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(json(Map.of("email", "rol2-emp@test.com", "password", "secret123"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void empleadoDesactivadoPierdeAccesoConTokenViejo() throws Exception {
        String admin = registrar("Taller Rol3", "rol3-admin@test.com");
        activarPro(admin);
        long empId = idOf(authPost("/api/usuarios", admin, json(Map.of(
                "username", "Emp3", "email", "rol3-emp@test.com", "password", "secret123")))
                .andExpect(status().isCreated()));
        String emp = login("rol3-emp@test.com", "secret123");

        // Con el token vigente el empleado opera normal
        authGet("/api/clientes", emp).andExpect(status().isOk());

        // Lo desactivan: el token ya emitido deja de servir al instante
        authPatch("/api/usuarios/" + empId, admin, json(Map.of("active", false)))
                .andExpect(status().isOk());
        authGet("/api/clientes", emp).andExpect(status().isForbidden());
    }
}
