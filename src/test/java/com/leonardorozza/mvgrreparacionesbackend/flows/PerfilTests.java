package com.leonardorozza.mvgrreparacionesbackend.flows;

import com.fasterxml.jackson.databind.JsonNode;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.User;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.UserRepository;
import com.leonardorozza.mvgrreparacionesbackend.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PerfilTests extends IntegrationTestBase {

    @Autowired
    private UserRepository userRepository;

    @Test
    void adminRecibeSoloSuIdentidadVisibleYLaDelTaller() throws Exception {
        String token = registrar("CelExpress", "perfil-admin@test.com");

        JsonNode perfil = node(authGet("/api/perfil", token).andExpect(status().isOk()));

        JsonNode usuario = perfil.get("usuario");
        assertThat(usuario.get("id").asLong()).isPositive();
        assertThat(usuario.get("username").asText()).isEqualTo("Admin");
        assertThat(usuario.get("email").asText()).isEqualTo("perfil-admin@test.com");
        assertThat(usuario.get("role").asText()).isEqualTo("ADMIN");
        assertThat(usuario.has("password")).isFalse();
        assertThat(usuario.has("tokenVersion")).isFalse();
        assertThat(usuario.has("active")).isFalse();
        assertThat(usuario.get("emailVerificado").asBoolean()).isTrue();

        JsonNode taller = perfil.get("taller");
        assertThat(taller.get("id").asLong()).isPositive();
        assertThat(taller.get("nombre").asText()).isEqualTo("CelExpress");
        assertThat(taller.get("telefono").asText()).isEqualTo("1100000000");
    }

    @Test
    void empleadoRecibeSuUsuarioYElMismoTallerQueElAdmin() throws Exception {
        String admin = registrar("Taller Equipo", "perfil-equipo-admin@test.com");
        activarPro(admin);
        authPost("/api/usuarios", admin, json(Map.of(
                "username", "María Empleada",
                "email", "perfil-equipo-user@test.com",
                "password", "secret123")))
                .andExpect(status().isCreated());
        String empleado = login("perfil-equipo-user@test.com", "secret123");

        JsonNode perfilAdmin = node(authGet("/api/perfil", admin).andExpect(status().isOk()));
        JsonNode perfilEmpleado = node(authGet("/api/perfil", empleado).andExpect(status().isOk()));

        assertThat(perfilEmpleado.at("/usuario/username").asText()).isEqualTo("María Empleada");
        assertThat(perfilEmpleado.at("/usuario/email").asText()).isEqualTo("perfil-equipo-user@test.com");
        assertThat(perfilEmpleado.at("/usuario/role").asText()).isEqualTo("USER");
        assertThat(perfilEmpleado.at("/usuario/emailVerificado").asBoolean()).isFalse();
        assertThat(perfilEmpleado.at("/usuario/id").asLong())
                .isNotEqualTo(perfilAdmin.at("/usuario/id").asLong());
        assertThat(perfilEmpleado.at("/taller/id").asLong())
                .isEqualTo(perfilAdmin.at("/taller/id").asLong());
        assertThat(perfilEmpleado.at("/taller/nombre").asText()).isEqualTo("Taller Equipo");
    }

    @Test
    void perfilesDeTalleresDistintosNoCruzanIdentidad() throws Exception {
        String tokenA = registrar("Taller Perfil A", "perfil-a@test.com");
        String tokenB = registrar("Taller Perfil B", "perfil-b@test.com");

        JsonNode perfilA = node(authGet("/api/perfil", tokenA).andExpect(status().isOk()));
        JsonNode perfilB = node(authGet("/api/perfil", tokenB).andExpect(status().isOk()));

        assertThat(perfilA.at("/taller/nombre").asText()).isEqualTo("Taller Perfil A");
        assertThat(perfilB.at("/taller/nombre").asText()).isEqualTo("Taller Perfil B");
        assertThat(perfilA.at("/taller/id").asLong()).isNotEqualTo(perfilB.at("/taller/id").asLong());
        assertThat(perfilA.at("/usuario/email").asText()).isEqualTo("perfil-a@test.com");
        assertThat(perfilB.at("/usuario/email").asText()).isEqualTo("perfil-b@test.com");
    }

    @Test
    void perfilSinTokenQuedaProtegido() throws Exception {
        mvc.perform(get("/api/perfil")).andExpect(status().isForbidden());
    }

    @Test
    void perfilRechazaUnTokenRevocado() throws Exception {
        String token = registrar("Taller Perfil Revocado", "perfil-revocado@test.com");
        User user = userRepository.findByEmail("perfil-revocado@test.com").orElseThrow();
        user.setTokenVersion(user.getTokenVersion() + 1);
        userRepository.saveAndFlush(user);

        authGet("/api/perfil", token).andExpect(status().isForbidden());
    }

    @Test
    void perfilRechazaElTokenViejoDeUnEmpleadoDesactivado() throws Exception {
        String admin = registrar("Taller Perfil Inactivo", "perfil-inactivo-admin@test.com");
        activarPro(admin);
        long empleadoId = idOf(authPost("/api/usuarios", admin, json(Map.of(
                "username", "Empleado Inactivo",
                "email", "perfil-inactivo-user@test.com",
                "password", "secret123")))
                .andExpect(status().isCreated()));
        String empleado = login("perfil-inactivo-user@test.com", "secret123");

        authGet("/api/perfil", empleado).andExpect(status().isOk());
        authPatch("/api/usuarios/" + empleadoId, admin, json(Map.of("active", false)))
                .andExpect(status().isOk());

        authGet("/api/perfil", empleado).andExpect(status().isForbidden());
    }
}
