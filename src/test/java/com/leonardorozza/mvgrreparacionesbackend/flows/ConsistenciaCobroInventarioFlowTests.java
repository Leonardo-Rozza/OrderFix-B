package com.leonardorozza.mvgrreparacionesbackend.flows;

import com.fasterxml.jackson.databind.JsonNode;
import com.leonardorozza.mvgrreparacionesbackend.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ConsistenciaCobroInventarioFlowTests extends IntegrationTestBase {

    @Test
    void permiteActualizarPrecioFinalYReducirloMientrasCubraLoCobrado() throws Exception {
        String token = registrar("Taller Precio Final", "precio-final-valido@test.com");
        activarPro(token);
        Orden orden = crearOrden(token, "9401", 60000);

        cobrar(token, orden.id(), 30000);

        JsonNode precioFinalInicial = node(authPut("/api/reparaciones/" + orden.id(), token,
                reparacionRequest(orden.equipoId(), 60000, 70000))
                .andExpect(status().isOk()));
        assertThat(precioFinalInicial.get("precioFinal").asInt()).isEqualTo(70000);
        assertThat(precioFinalInicial.get("total").asInt()).isEqualTo(70000);
        assertThat(precioFinalInicial.get("cobrado").asInt()).isEqualTo(30000);
        assertThat(precioFinalInicial.get("saldo").asInt()).isEqualTo(40000);

        JsonNode precioFinalReducido = node(authPut("/api/reparaciones/" + orden.id(), token,
                reparacionRequest(orden.equipoId(), 60000, 40000))
                .andExpect(status().isOk()));
        assertThat(precioFinalReducido.get("precioEstimado").asInt()).isEqualTo(60000);
        assertThat(precioFinalReducido.get("precioFinal").asInt()).isEqualTo(40000);
        assertThat(precioFinalReducido.get("total").asInt()).isEqualTo(40000);
        assertThat(precioFinalReducido.get("cobrado").asInt()).isEqualTo(30000);
        assertThat(precioFinalReducido.get("saldo").asInt()).isEqualTo(10000);
    }

    @Test
    void actualizaDeltaDeStockYMantieneConsistenciaAlMoverYDesasignarRepuesto() throws Exception {
        String token = registrar("Taller Flujo Repuesto", "flujo-repuesto-valido@test.com");
        activarPro(token);
        Orden origen = crearOrden(token, "9402", 40000);
        Orden destino = crearOrden(token, "9403", null);
        long articuloId = crearArticulo(token, "Flex principal", "FLEX-FLUJO", 10);

        long repuestoId = idOf(authPost("/api/repuestos", token,
                repuestoRequest("Flex", 1000, origen.id(), articuloId, 3))
                .andExpect(status().isCreated()));
        assertStock(token, articuloId, 7);

        JsonNode ampliado = node(authPut("/api/repuestos/" + repuestoId, token,
                repuestoRequest("Flex reforzado", 1200, origen.id(), articuloId, 5))
                .andExpect(status().isOk()));
        assertThat(ampliado.get("precio").asInt()).isEqualTo(1200);
        assertThat(ampliado.get("cantidad").asInt()).isEqualTo(5);
        assertThat(ampliado.get("reparacionId").asLong()).isEqualTo(origen.id());
        assertThat(ampliado.get("articuloId").asLong()).isEqualTo(articuloId);
        assertStock(token, articuloId, 5);
        assertTotales(token, origen.id(), 6000, 46000);

        JsonNode reducido = node(authPut("/api/repuestos/" + repuestoId, token,
                repuestoRequest("Flex ajustado", 900, origen.id(), articuloId, 2))
                .andExpect(status().isOk()));
        assertThat(reducido.get("precio").asInt()).isEqualTo(900);
        assertThat(reducido.get("cantidad").asInt()).isEqualTo(2);
        assertStock(token, articuloId, 8);
        assertTotales(token, origen.id(), 1800, 41800);

        JsonNode movido = node(authPut("/api/repuestos/" + repuestoId, token,
                repuestoRequest("Flex ajustado", 900, destino.id(), articuloId, 2))
                .andExpect(status().isOk()));
        assertThat(movido.get("reparacionId").asLong()).isEqualTo(destino.id());
        assertStock(token, articuloId, 8);
        assertTotales(token, origen.id(), 0, 40000);
        assertTotales(token, destino.id(), 1800, 1800);
        assertCantidadRepuestos(token, origen.id(), 0);
        assertCantidadRepuestos(token, destino.id(), 1);

        JsonNode desasignado = node(authPut("/api/repuestos/" + repuestoId, token,
                repuestoRequest("Flex ajustado", 900, null, articuloId, 2))
                .andExpect(status().isOk()));
        assertThat(desasignado.get("reparacionId").isNull()).isTrue();
        assertThat(desasignado.get("articuloId").asLong()).isEqualTo(articuloId);
        assertStock(token, articuloId, 8);
        assertTotales(token, origen.id(), 0, 40000);
        assertTotales(token, destino.id(), 0, 0);
        assertCantidadRepuestos(token, origen.id(), 0);
        assertCantidadRepuestos(token, destino.id(), 0);
    }

    @Test
    void nuevosCaminosMantienenAislamientoEntreTalleresSinMutarDatos() throws Exception {
        String tokenA = registrar("Taller Aislado A", "consistencia-tenant-a@test.com");
        String tokenB = registrar("Taller Aislado B", "consistencia-tenant-b@test.com");
        activarPro(tokenA);
        activarPro(tokenB);

        Orden ordenA = crearOrden(tokenA, "9404", 20000);
        Orden ordenB = crearOrden(tokenB, "9405", 15000);
        long articuloA = crearArticulo(tokenA, "Batería A", "BAT-TENANT-A", 6);
        long articuloB = crearArticulo(tokenB, "Batería B", "BAT-TENANT-B", 9);
        long repuestoA = idOf(authPost("/api/repuestos", tokenA,
                repuestoRequest("Batería propia", 5000, ordenA.id(), articuloA, 2))
                .andExpect(status().isCreated()));

        authPost("/api/reparaciones/" + ordenA.id() + "/cobros", tokenB,
                json(Map.of("monto", 1000, "metodo", "EFECTIVO")))
                .andExpect(status().isNotFound());

        authPut("/api/repuestos/" + repuestoA, tokenB,
                repuestoRequest("Edición ajena", 6000, ordenB.id(), articuloB, 1))
                .andExpect(status().isNotFound());

        authPut("/api/repuestos/" + repuestoA, tokenA,
                repuestoRequest("Batería propia", 5000, ordenA.id(), articuloB, 2))
                .andExpect(status().isNotFound());

        authPost("/api/repuestos", tokenA,
                repuestoRequest("Artículo ajeno", 3000, ordenA.id(), articuloB, 1))
                .andExpect(status().isNotFound());

        JsonNode cobrosA = node(authGet("/api/reparaciones/" + ordenA.id() + "/cobros", tokenA)
                .andExpect(status().isOk()));
        assertThat(cobrosA.get("cobrado").asInt()).isZero();
        assertThat(cobrosA.get("cobros").size()).isZero();

        JsonNode repuestoSinCambios = node(authGet("/api/repuestos/" + repuestoA, tokenA)
                .andExpect(status().isOk()));
        assertThat(repuestoSinCambios.get("nombre").asText()).isEqualTo("Batería propia");
        assertThat(repuestoSinCambios.get("precio").asInt()).isEqualTo(5000);
        assertThat(repuestoSinCambios.get("cantidad").asInt()).isEqualTo(2);
        assertThat(repuestoSinCambios.get("reparacionId").asLong()).isEqualTo(ordenA.id());
        assertThat(repuestoSinCambios.get("articuloId").asLong()).isEqualTo(articuloA);
        assertCantidadRepuestos(tokenA, ordenA.id(), 1);
        assertCantidadRepuestos(tokenB, ordenB.id(), 0);
        assertStock(tokenA, articuloA, 4);
        assertStock(tokenB, articuloB, 9);
        assertTotales(tokenA, ordenA.id(), 10000, 30000);
        assertTotales(tokenB, ordenB.id(), 0, 15000);
    }

    private Orden crearOrden(String token, String telefono, Integer precioEstimado) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("clienteNombre", "Cliente");
        request.put("clienteTelefono", telefono);
        request.put("equipoMarca", "Motorola");
        request.put("equipoModelo", "Edge");
        request.put("descripcionProblema", "No enciende");
        if (precioEstimado != null) {
            request.put("precioEstimado", precioEstimado);
        }

        JsonNode ingreso = node(authPost("/api/reparaciones/ingreso-rapido", token, json(request))
                .andExpect(status().isCreated()));
        return new Orden(ingreso.at("/reparacion/id").asLong(), ingreso.get("equipoId").asLong());
    }

    private long crearArticulo(String token, String nombre, String sku, int stock) throws Exception {
        return idOf(authPost("/api/inventario", token, json(Map.of(
                "nombre", nombre,
                "sku", sku,
                "precio", 5000,
                "stock", stock,
                "stockMinimo", 1)))
                .andExpect(status().isCreated()));
    }

    private void cobrar(String token, long reparacionId, int monto) throws Exception {
        authPost("/api/reparaciones/" + reparacionId + "/cobros", token,
                json(Map.of("monto", monto, "metodo", "EFECTIVO")))
                .andExpect(status().isCreated());
    }

    private String reparacionRequest(long equipoId, int precioEstimado, int precioFinal) throws Exception {
        return json(Map.of(
                "equipoId", equipoId,
                "descripcionProblema", "No enciende",
                "precioEstimado", precioEstimado,
                "precioFinal", precioFinal));
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

    private void assertStock(String token, long articuloId, int esperado) throws Exception {
        JsonNode articulo = node(authGet("/api/inventario/" + articuloId, token)
                .andExpect(status().isOk()));
        assertThat(articulo.get("stock").asInt()).isEqualTo(esperado);
    }

    private void assertTotales(String token, long reparacionId, int repuestos, int total) throws Exception {
        JsonNode reparacion = node(authGet("/api/reparaciones/" + reparacionId, token)
                .andExpect(status().isOk()));
        assertThat(reparacion.get("totalRepuestos").asInt()).isEqualTo(repuestos);
        assertThat(reparacion.get("total").asInt()).isEqualTo(total);
    }

    private void assertCantidadRepuestos(String token, long reparacionId, int esperada) throws Exception {
        JsonNode repuestos = node(authGet("/api/repuestos/reparacion/" + reparacionId, token)
                .andExpect(status().isOk()));
        assertThat(repuestos.size()).isEqualTo(esperada);
    }

    private record Orden(long id, long equipoId) {
    }
}
