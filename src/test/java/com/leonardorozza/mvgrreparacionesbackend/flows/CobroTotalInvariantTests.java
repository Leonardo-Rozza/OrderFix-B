package com.leonardorozza.mvgrreparacionesbackend.flows;

import com.fasterxml.jackson.databind.JsonNode;
import com.leonardorozza.mvgrreparacionesbackend.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CobroTotalInvariantTests extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void rechazaBajarManoDeObraDebajoDeLoCobradoSinMutarLaOrden() throws Exception {
        String token = registrar("Taller Invariante Mano Obra", "invariante-mano-obra@test.com");
        activarPro(token);
        Orden orden = crearOrden(token, "9101", 50000);

        cobrar(token, orden.id(), 40000);

        JsonNode error = node(authPut("/api/reparaciones/" + orden.id(), token,
                json(Map.of(
                        "equipoId", orden.equipoId(),
                        "descripcionProblema", "Pantalla rota",
                        "precioEstimado", 30000)))
                .andExpect(status().isConflict()));

        assertThat(error.get("code").asText()).isEqualTo("TOTAL_MENOR_QUE_COBRADO");

        JsonNode reparacion = node(authGet("/api/reparaciones/" + orden.id(), token)
                .andExpect(status().isOk()));
        assertThat(reparacion.get("precioEstimado").asInt()).isEqualTo(50000);
        assertThat(reparacion.get("total").asInt()).isEqualTo(50000);
        assertThat(reparacion.get("cobrado").asInt()).isEqualTo(40000);
        assertThat(reparacion.get("saldo").asInt()).isEqualTo(10000);
    }

    @Test
    void permiteBajarManoDeObraSiElNuevoTotalCubreLoCobrado() throws Exception {
        String token = registrar("Taller Reducción Válida", "reduccion-valida@test.com");
        activarPro(token);
        Orden orden = crearOrden(token, "9102", 50000);

        cobrar(token, orden.id(), 20000);

        JsonNode actualizada = node(authPut("/api/reparaciones/" + orden.id(), token,
                json(Map.of(
                        "equipoId", orden.equipoId(),
                        "descripcionProblema", "Pantalla rota",
                        "precioEstimado", 30000)))
                .andExpect(status().isOk()));

        assertThat(actualizada.get("precioEstimado").asInt()).isEqualTo(30000);
        assertThat(actualizada.get("total").asInt()).isEqualTo(30000);
        assertThat(actualizada.get("cobrado").asInt()).isEqualTo(20000);
        assertThat(actualizada.get("saldo").asInt()).isEqualTo(10000);
    }

    @Test
    void rechazaReducirMoverODesasignarUnRepuestoPagadoYConservaVinculoYStock() throws Exception {
        String token = registrar("Taller Invariante Repuesto", "invariante-repuesto@test.com");
        activarPro(token);
        Orden origen = crearOrden(token, "9103", 10000);
        Orden destino = crearOrden(token, "9104", null);

        long articuloId = idOf(authPost("/api/inventario", token, json(Map.of(
                "nombre", "Módulo OLED", "sku", "OLED-INV", "precio", 20000,
                "stock", 10, "stockMinimo", 1)))
                .andExpect(status().isCreated()));

        long repuestoId = idOf(authPost("/api/repuestos", token,
                repuestoRequest("Módulo OLED", 20000, origen.id(), articuloId, 2))
                .andExpect(status().isCreated()));
        cobrar(token, origen.id(), 45000);

        assertConflictoTotal(authPut("/api/repuestos/" + repuestoId, token,
                repuestoRequest("Módulo OLED", 15000, origen.id(), articuloId, 2)));
        assertConflictoTotal(authDelete("/api/repuestos/" + repuestoId, token));
        assertConflictoTotal(authPut("/api/repuestos/" + repuestoId, token,
                repuestoRequest("Módulo OLED", 20000, destino.id(), articuloId, 2)));
        assertConflictoTotal(authPut("/api/repuestos/" + repuestoId, token,
                repuestoRequest("Módulo OLED", 20000, null, articuloId, 2)));

        JsonNode repuesto = node(authGet("/api/repuestos/" + repuestoId, token)
                .andExpect(status().isOk()));
        assertThat(repuesto.get("precio").asInt()).isEqualTo(20000);
        assertThat(repuesto.get("cantidad").asInt()).isEqualTo(2);
        assertThat(repuesto.get("reparacionId").asLong()).isEqualTo(origen.id());
        assertThat(repuesto.get("articuloId").asLong()).isEqualTo(articuloId);

        assertThat(node(authGet("/api/inventario/" + articuloId, token)
                .andExpect(status().isOk())).get("stock").asInt()).isEqualTo(8);
        assertThat(node(authGet("/api/repuestos/reparacion/" + origen.id(), token)
                .andExpect(status().isOk())).size()).isEqualTo(1);
        assertThat(node(authGet("/api/repuestos/reparacion/" + destino.id(), token)
                .andExpect(status().isOk())).size()).isZero();

        JsonNode resumen = node(authGet("/api/reparaciones/" + origen.id(), token)
                .andExpect(status().isOk()));
        assertThat(resumen.get("total").asInt()).isEqualTo(50000);
        assertThat(resumen.get("cobrado").asInt()).isEqualTo(45000);
        assertThat(resumen.get("saldo").asInt()).isEqualTo(5000);
    }

    @Test
    void unSobrepagoLegacyNoExponeSaldoNegativoNiAdmiteEmpeorarElExcedente() throws Exception {
        String token = registrar("Taller Legacy", "legacy-excedente@test.com");
        activarPro(token);
        Orden orden = crearOrden(token, "9105", 100000);
        cobrar(token, orden.id(), 100000);

        // Simula un dato histórico anterior a V21 y a la protección de totales.
        jdbcTemplate.update(
                "UPDATE reparaciones SET precio_estimado = 50000 WHERE id = ?",
                orden.id());

        JsonNode estadoLegacy = node(authGet("/api/reparaciones/" + orden.id() + "/cobros", token)
                .andExpect(status().isOk()));
        assertThat(estadoLegacy.get("total").asInt()).isEqualTo(50000);
        assertThat(estadoLegacy.get("cobrado").asInt()).isEqualTo(100000);
        assertThat(estadoLegacy.get("saldo").asInt()).isZero();
        assertThat(estadoLegacy.get("excedente").asInt()).isEqualTo(50000);
        assertThat(estadoLegacy.get("requiereRevision").asBoolean()).isTrue();
        assertThat(estadoLegacy.get("pagado").asBoolean()).isTrue();

        JsonNode cobroRechazado = node(authPost(
                "/api/reparaciones/" + orden.id() + "/cobros",
                token,
                json(Map.of("monto", 1, "metodo", "EFECTIVO")))
                .andExpect(status().isConflict()));
        assertThat(cobroRechazado.get("code").asText()).isEqualTo("COBRO_SUPERA_SALDO");

        JsonNode mejora = node(authPut("/api/reparaciones/" + orden.id(), token,
                json(Map.of(
                        "equipoId", orden.equipoId(),
                        "descripcionProblema", "Pantalla rota",
                        "precioEstimado", 75000)))
                .andExpect(status().isOk()));
        assertThat(mejora.get("total").asInt()).isEqualTo(75000);
        assertThat(mejora.get("saldo").asInt()).isZero();
        assertThat(mejora.get("excedente").asInt()).isEqualTo(25000);
        assertThat(mejora.get("requiereRevision").asBoolean()).isTrue();

        JsonNode empeora = node(authPut("/api/reparaciones/" + orden.id(), token,
                json(Map.of(
                        "equipoId", orden.equipoId(),
                        "descripcionProblema", "Pantalla rota",
                        "precioEstimado", 49999)))
                .andExpect(status().isConflict()));
        assertThat(empeora.get("code").asText()).isEqualTo("TOTAL_MENOR_QUE_COBRADO");

        JsonNode finalOrden = node(authGet("/api/reparaciones/" + orden.id(), token)
                .andExpect(status().isOk()));
        assertThat(finalOrden.get("total").asInt()).isEqualTo(75000);
        assertThat(finalOrden.get("cobrado").asInt()).isEqualTo(100000);
        assertThat(finalOrden.get("saldo").asInt()).isZero();
    }

    private Orden crearOrden(String token, String telefono, Integer precioEstimado) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("clienteNombre", "Cliente");
        request.put("clienteTelefono", telefono);
        request.put("equipoMarca", "Samsung");
        request.put("equipoModelo", "A54");
        request.put("descripcionProblema", "Pantalla rota");
        if (precioEstimado != null) {
            request.put("precioEstimado", precioEstimado);
        }

        JsonNode ingreso = node(authPost("/api/reparaciones/ingreso-rapido", token, json(request))
                .andExpect(status().isCreated()));
        return new Orden(ingreso.at("/reparacion/id").asLong(), ingreso.get("equipoId").asLong());
    }

    private void cobrar(String token, long reparacionId, int monto) throws Exception {
        authPost("/api/reparaciones/" + reparacionId + "/cobros", token,
                json(Map.of("monto", monto, "metodo", "EFECTIVO")))
                .andExpect(status().isCreated());
    }

    private String repuestoRequest(
            String nombre, int precio, Long reparacionId, long articuloId, int cantidad) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("nombre", nombre);
        request.put("precio", precio);
        request.put("reparacionId", reparacionId);
        request.put("articuloId", articuloId);
        request.put("cantidad", cantidad);
        return json(request);
    }

    private void assertConflictoTotal(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        JsonNode error = node(result.andExpect(status().isConflict()));
        assertThat(error.get("code").asText()).isEqualTo("TOTAL_MENOR_QUE_COBRADO");
    }

    private record Orden(long id, long equipoId) {
    }
}
