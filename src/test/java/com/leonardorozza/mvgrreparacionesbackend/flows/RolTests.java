package com.leonardorozza.mvgrreparacionesbackend.flows;

import com.fasterxml.jackson.databind.JsonNode;
import com.leonardorozza.mvgrreparacionesbackend.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
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
    void noPermiteCrearOtroAdminDesdeElAltaDeEmpleados() throws Exception {
        String admin = registrar("Taller Titular Único", "titular-unico-admin@test.com");
        activarPro(admin);

        var rechazo = authPost("/api/usuarios", admin, json(Map.of(
                        "username", "Segundo Admin",
                        "email", "segundo-admin@test.com",
                        "password", "secret123",
                        "role", "ADMIN")))
                .andExpect(status().isBadRequest());

        JsonNode error = node(rechazo);
        assertThat(error.get("code").asText()).isEqualTo("EMPLEADO_DEBE_SER_USER");
        assertThat(error.at("/details/rolPermitido").asText()).isEqualTo("USER");

        JsonNode usuarios = node(authGet("/api/usuarios", admin).andExpect(status().isOk()));
        assertThat(usuarios).hasSize(1);
        assertThat(usuarios.get(0).get("role").asText()).isEqualTo("ADMIN");
    }

    @Test
    void noPermitePromoverUnEmpleadoNiDegradarAlTitular() throws Exception {
        String admin = registrar("Taller Roles Inmutables", "roles-inmutables-admin@test.com");
        activarPro(admin);
        JsonNode iniciales = node(authGet("/api/usuarios", admin).andExpect(status().isOk()));
        long adminId = iniciales.get(0).get("id").asLong();

        authPatch("/api/usuarios/" + adminId, admin, json(Map.of("active", false)))
                .andExpect(status().isBadRequest());
        JsonNode titular = node(authGet("/api/usuarios/" + adminId, admin)
                .andExpect(status().isOk()));
        assertThat(titular.get("active").asBoolean()).isTrue();
        assertThat(titular.get("role").asText()).isEqualTo("ADMIN");

        long empleadoId = idOf(authPost("/api/usuarios", admin, json(Map.of(
                        "username", "Empleado",
                        "email", "roles-inmutables-user@test.com",
                        "password", "secret123",
                        "role", "USER")))
                .andExpect(status().isCreated()));

        var promocion = authPatch("/api/usuarios/" + empleadoId, admin,
                        json(Map.of("role", "ADMIN")))
                .andExpect(status().isBadRequest());
        assertThat(node(promocion).get("code").asText()).isEqualTo("ROL_USUARIO_INMUTABLE");

        var degradacion = authPatch("/api/usuarios/" + adminId, admin,
                        json(Map.of("role", "USER")))
                .andExpect(status().isBadRequest());
        assertThat(node(degradacion).get("code").asText()).isEqualTo("ROL_USUARIO_INMUTABLE");

        JsonNode empleado = node(authGet("/api/usuarios/" + empleadoId, admin)
                .andExpect(status().isOk()));
        assertThat(empleado.get("role").asText()).isEqualTo("USER");

        // Compatibilidad temporal: repetir el rol actual sigue siendo un no-op.
        authPatch("/api/usuarios/" + empleadoId, admin, json(Map.of("role", "USER")))
                .andExpect(status().isOk());
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

    @Test
    void userConsultaLaHistoriaPeroNoPuedeAnularCobros() throws Exception {
        String admin = registrar("Taller Rol Cobro", "rol-cobro-admin@test.com");
        activarPro(admin);
        String user = empleadoUser(admin, "rol-cobro-user@test.com");

        long reparacionId = node(authPost("/api/reparaciones/ingreso-rapido", admin, json(Map.of(
                "clienteNombre", "Cliente", "clienteTelefono", "9101",
                "equipoMarca", "Samsung", "equipoModelo", "A54",
                "descripcionProblema", "Pantalla", "precioEstimado", 20000)))
                .andExpect(status().isCreated())).at("/reparacion/id").asLong();
        long cobroId = idOf(authPost("/api/reparaciones/" + reparacionId + "/cobros", admin,
                json(Map.of("monto", 10000, "metodo", "EFECTIVO")))
                .andExpect(status().isCreated()));

        authGet("/api/reparaciones/" + reparacionId + "/cobros", user)
                .andExpect(status().isOk());
        JsonNode resumenUser = node(authGet(
                "/api/reparaciones/" + reparacionId + "/resumen-digital", user)
                .andExpect(status().isOk()));
        JsonNode aliasUser = node(authGet(
                "/api/reparaciones/" + reparacionId + "/recibo", user)
                .andExpect(status().isOk()));
        assertThat(aliasUser).isEqualTo(resumenUser);
        assertThat(resumenUser.at("/pagos/0/monto").asInt()).isEqualTo(10000);
        authPost("/api/reparaciones/" + reparacionId + "/cobros/" + cobroId + "/anulacion",
                user, json(Map.of("motivo", "No autorizado")))
                .andExpect(status().isForbidden());
        authDelete("/api/reparaciones/" + reparacionId + "/cobros/" + cobroId, user)
                .andExpect(status().isForbidden());

        authPost("/api/reparaciones/" + reparacionId + "/cobros/" + cobroId + "/anulacion",
                admin, json(Map.of("motivo", "Autorizado por admin")))
                .andExpect(status().isOk());
    }

    @Test
    void userConsultaDatosDeCobroPeroSoloAdminLosModifica() throws Exception {
        String admin = registrar("Taller Rol Datos", "rol-datos-admin@test.com");
        activarPro(admin);
        String user = empleadoUser(admin, "rol-datos-user@test.com");

        authPut("/api/taller/datos-cobro", admin, json(Map.of(
                "alias", "taller-rol.mp",
                "titular", "Titular Admin",
                "mostrarEnResumen", true)))
                .andExpect(status().isOk());

        JsonNode visibles = node(authGet("/api/taller/datos-cobro", user)
                .andExpect(status().isOk()));
        assertThat(visibles.get("alias").asText()).isEqualTo("taller-rol.mp");
        assertThat(visibles.get("titular").asText()).isEqualTo("Titular Admin");

        authPut("/api/taller/datos-cobro", user, json(Map.of(
                "alias", "intento-user.mp",
                "mostrarEnResumen", false)))
                .andExpect(status().isForbidden());

        JsonNode sinCambios = node(authGet("/api/taller/datos-cobro", admin)
                .andExpect(status().isOk()));
        assertThat(sinCambios.get("alias").asText()).isEqualTo("taller-rol.mp");
        assertThat(sinCambios.get("mostrarEnResumen").asBoolean()).isTrue();
    }
}
