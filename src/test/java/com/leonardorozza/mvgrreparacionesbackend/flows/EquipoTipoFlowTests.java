package com.leonardorozza.mvgrreparacionesbackend.flows;

import com.fasterxml.jackson.databind.JsonNode;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EquipoTipo;
import com.leonardorozza.mvgrreparacionesbackend.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP/JPA contract on the existing H2 business fixture; V36 migration is tested separately on PostgreSQL. */
class EquipoTipoFlowTests extends IntegrationTestBase {

    @ParameterizedTest
    @EnumSource(EquipoTipo.class)
    void ambasAltasPersistenLaCategoriaYLasReparacionesLaExponen(EquipoTipo tipo) throws Exception {
        String token = registrarTaller();
        long cliente = cliente(token);
        JsonNode equipo = crearEquipo(token, cliente, tipo, "Modelo normal", null);
        long equipoId = equipo.get("id").asLong();
        assertThat(equipo.get("tipo").asText()).isEqualTo(tipo.name());
        assertThat(equipo.get("imei").isNull()).isTrue();
        assertThat(node(authGet("/api/equipos/" + equipoId, token).andExpect(status().isOk()))
                .get("tipo").asText()).isEqualTo(tipo.name());
        assertThat(node(authGet("/api/equipos/cliente/" + cliente, token).andExpect(status().isOk()))
                .get(0).get("tipo").asText()).isEqualTo(tipo.name());

        JsonNode reparacion = node(authPost("/api/reparaciones", token, json(Map.of(
                "equipoId", equipoId, "descripcionProblema", "No enciende")))
                .andExpect(status().isCreated()));
        assertThat(reparacion.get("equipoTipo").asText()).isEqualTo(tipo.name());
        assertThat(node(authGet("/api/reparaciones/" + reparacion.get("id").asLong(), token)
                .andExpect(status().isOk())).get("equipoTipo").asText()).isEqualTo(tipo.name());

        Map<String, Object> ingreso = ingreso();
        ingreso.put("equipoTipo", tipo.name());
        JsonNode rapido = node(authPost("/api/reparaciones/ingreso-rapido", token, json(ingreso))
                .andExpect(status().isCreated()));
        assertThat(rapido.get("clienteNuevo").asBoolean()).isFalse();
        assertThat(rapido.get("clienteId").asLong()).isEqualTo(cliente);
        assertThat(rapido.at("/reparacion/equipoTipo").asText()).isEqualTo(tipo.name());
        assertThat(node(authGet("/api/equipos/" + rapido.get("equipoId").asLong(), token)
                .andExpect(status().isOk())).get("tipo").asText()).isEqualTo(tipo.name());
        JsonNode listado = node(authGet("/api/reparaciones", token).andExpect(status().isOk()));
        assertThat(listado.get("content")).hasSize(2);
        listado.get("content").forEach(item -> assertThat(item.get("equipoTipo").asText()).isEqualTo(tipo.name()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void tipoOmitidoONuloCreaOtroSinInferirYEditarConservaElActual(boolean nuloExplicito) throws Exception {
        String token = registrarTaller();
        long cliente = cliente(token);
        String serie = "000012345678901234567890123456";
        Map<String, Object> body = equipo(cliente, null, "iPhone 14", serie);
        body.put("marca", "Apple");
        if (nuloExplicito) body.put("tipo", null);
        JsonNode creado = node(authPost("/api/equipos", token, json(body)).andExpect(status().isCreated()));
        long id = creado.get("id").asLong();
        assertThat(creado.get("tipo").asText()).isEqualTo("OTRO");
        assertThat(creado.get("imei").asText()).isEqualTo(serie);

        body.put("tipo", "NOTEBOOK");
        assertThat(node(authPut("/api/equipos/" + id, token, json(body)).andExpect(status().isOk()))
                .get("tipo").asText()).isEqualTo("NOTEBOOK");
        if (nuloExplicito) body.put("tipo", null); else body.remove("tipo");
        body.put("color", "Negro");
        JsonNode actualizado = node(authPut("/api/equipos/" + id, token, json(body)).andExpect(status().isOk()));
        assertThat(actualizado.get("tipo").asText()).isEqualTo("NOTEBOOK");
        assertThat(actualizado.get("imei").asText()).isEqualTo(serie);
        assertThat(actualizado.get("color").asText()).isEqualTo("Negro");

        Map<String, Object> rapido = ingreso();
        rapido.put("equipoMarca", "Apple");
        rapido.put("equipoModelo", "iPhone 14");
        if (nuloExplicito) rapido.put("equipoTipo", null);
        JsonNode respuesta = node(authPost("/api/reparaciones/ingreso-rapido", token, json(rapido))
                .andExpect(status().isCreated()));
        assertThat(respuesta.at("/reparacion/equipoTipo").asText()).isEqualTo("OTRO");
        assertThat(node(authGet("/api/equipos/" + respuesta.get("equipoId").asLong(), token)
                .andExpect(status().isOk())).get("tipo").asText()).isEqualTo("OTRO");
    }

    @Test
    void filtroExactoSeCombinaConSeriePaginacionYTaller() throws Exception {
        String token = registrarTaller();
        String ajeno = registrarTaller();
        long cliente = cliente(token);
        long clienteAjeno = cliente(ajeno);
        long primero = crearEquipo(token, cliente, EquipoTipo.NOTEBOOK, "Modelo 1", "serie-comun-001").get("id").asLong();
        long segundo = crearEquipo(token, cliente, EquipoTipo.NOTEBOOK, "Modelo 2", "serie-comun-002").get("id").asLong();
        long tercero = crearEquipo(token, cliente, EquipoTipo.NOTEBOOK, "Modelo 3", "serie-comun-003").get("id").asLong();
        crearEquipo(token, cliente, EquipoTipo.CELULAR, "Modelo 4", "serie-comun-004");
        crearEquipo(token, cliente, EquipoTipo.NOTEBOOK, "Modelo 5", "otra-serie");
        long equipoAjeno = crearEquipo(ajeno, clienteAjeno, EquipoTipo.NOTEBOOK, "Modelo 6", "serie-comun-006").get("id").asLong();

        String filtro = "/api/equipos?tipo=NOTEBOOK&q=serie-comun&size=2&sort=id,asc&page=";
        JsonNode pagina = node(authGet(filtro + "0", token).andExpect(status().isOk()));
        assertThat(pagina.at("/page/totalElements").asInt()).isEqualTo(3);
        assertThat(pagina.at("/page/totalPages").asInt()).isEqualTo(2);
        assertThat(pagina.get("content")).hasSize(2);
        assertThat(pagina.at("/content/0/id").asLong()).isEqualTo(primero);
        assertThat(pagina.at("/content/1/id").asLong()).isEqualTo(segundo);
        JsonNode ultima = node(authGet(filtro + "1", token).andExpect(status().isOk()));
        assertThat(ultima.get("content")).hasSize(1);
        assertThat(ultima.at("/content/0/id").asLong()).isEqualTo(tercero);
        assertThat(ultima.at("/content/0/tipo").asText()).isEqualTo("NOTEBOOK");
        assertThat(node(authGet("/api/equipos", token).andExpect(status().isOk()))
                .at("/page/totalElements").asInt()).isEqualTo(5);
        assertThat(node(authGet("/api/equipos?tipo=NOTEBOOK", token).andExpect(status().isOk()))
                .at("/page/totalElements").asInt()).isEqualTo(4);
        assertThat(node(authGet("/api/equipos?tipo=CELULAR&q=serie-comun", token).andExpect(status().isOk()))
                .at("/page/totalElements").asInt()).isEqualTo(1);
        assertThat(node(authGet(filtro + "0", ajeno).andExpect(status().isOk()))
                .at("/page/totalElements").asInt()).isEqualTo(1);

        authGet("/api/equipos/" + equipoAjeno, token).andExpect(status().isNotFound());
        authPut("/api/equipos/" + equipoAjeno, token, json(equipo(cliente, EquipoTipo.TV, "Intento", null)))
                .andExpect(status().isNotFound());
        authPost("/api/equipos", token, json(equipo(clienteAjeno, EquipoTipo.TV, "Intento", null)))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"TABLET\"", "\"notebook\"", "\"\"", "0", "true", "{}", "[]"})
    void valoresInvalidosNoCreanEquiposNiIngresos(String valorJson) throws Exception {
        String token = registrarTaller();
        long cliente = cliente(token);
        Map<String, Object> body = equipo(cliente, null, "Modelo", null);
        body.put("tipo", om.readTree(valorJson));
        authPost("/api/equipos", token, json(body)).andExpect(status().isBadRequest());
        Map<String, Object> rapido = ingreso();
        rapido.put("equipoTipo", om.readTree(valorJson));
        authPost("/api/reparaciones/ingreso-rapido", token, json(rapido)).andExpect(status().isBadRequest());
        assertThat(node(authGet("/api/equipos", token).andExpect(status().isOk()))
                .at("/page/totalElements").asInt()).isZero();
        assertThat(node(authGet("/api/reparaciones", token).andExpect(status().isOk()))
                .at("/page/totalElements").asInt()).isZero();
    }

    @Test
    void filtroYEdicionInvalidosSeRechazanSinReclasificar() throws Exception {
        String token = registrarTaller();
        long cliente = cliente(token);
        long id = crearEquipo(token, cliente, EquipoTipo.MONITOR, "Modelo", null).get("id").asLong();
        for (String tipo : new String[]{"TABLET", "notebook", "0", "NOTE"}) {
            authGet("/api/equipos?tipo=" + tipo, token).andExpect(status().isBadRequest());
        }
        Map<String, Object> body = equipo(cliente, null, "Modelo", null);
        body.put("tipo", "TABLET");
        authPut("/api/equipos/" + id, token, json(body)).andExpect(status().isBadRequest());
        assertThat(node(authGet("/api/equipos/" + id, token).andExpect(status().isOk()))
                .get("tipo").asText()).isEqualTo("MONITOR");
    }

    @Test
    void reclasificarElEquipoNoModificaIdentificadorNiCredencialesDeLaReparacion() throws Exception {
        String token = registrarTaller();
        long cliente = cliente(token);
        String serie = "000123-ABC";
        long equipoId = crearEquipo(token, cliente, EquipoTipo.CELULAR, "Modelo", serie).get("id").asLong();
        long reparacionId = idOf(authPost("/api/reparaciones", token, json(Map.of(
                "equipoId", equipoId, "descripcionProblema", "Revisión",
                "pinDesbloqueo", "1234", "patronDesbloqueo", "L invertida")))
                .andExpect(status().isCreated()));
        JsonNode actualizado = node(authPut("/api/equipos/" + equipoId, token,
                json(equipo(cliente, EquipoTipo.TV, "Modelo", serie))).andExpect(status().isOk()));
        assertThat(actualizado.get("tipo").asText()).isEqualTo("TV");
        assertThat(actualizado.get("imei").asText()).isEqualTo(serie);
        JsonNode detalle = node(authGet("/api/reparaciones/" + reparacionId, token).andExpect(status().isOk()));
        assertThat(detalle.get("equipoTipo").asText()).isEqualTo("TV");
        assertThat(detalle.get("pinDesbloqueo").asText()).isEqualTo("1234");
        assertThat(detalle.get("patronDesbloqueo").asText()).isEqualTo("L invertida");
    }

    private String registrarTaller() throws Exception {
        return registrar("Taller multidispositivo", "tipo-" + UUID.randomUUID() + "@test.com");
    }

    private long cliente(String token) throws Exception {
        return idOf(authPost("/api/clientes", token, json(Map.of(
                "nombre", "Cliente", "apellido", "Prueba", "telefono", "39001"))).andExpect(status().isOk()));
    }

    private JsonNode crearEquipo(String token, long cliente, EquipoTipo tipo, String modelo, String imei) throws Exception {
        return node(authPost("/api/equipos", token, json(equipo(cliente, tipo, modelo, imei)))
                .andExpect(status().isCreated()));
    }

    private Map<String, Object> equipo(long cliente, EquipoTipo tipo, String modelo, String imei) {
        Map<String, Object> body = new HashMap<>(Map.of("clienteId", cliente, "marca", "Marca", "modelo", modelo));
        if (tipo != null) body.put("tipo", tipo.name());
        if (imei != null) body.put("imei", imei);
        return body;
    }

    private Map<String, Object> ingreso() {
        return new HashMap<>(Map.of("clienteNombre", "Cliente", "clienteTelefono", "39001",
                "equipoMarca", "Marca", "equipoModelo", "Modelo rápido", "descripcionProblema", "No enciende"));
    }
}
