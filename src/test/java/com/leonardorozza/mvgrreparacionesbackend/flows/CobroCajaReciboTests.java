package com.leonardorozza.mvgrreparacionesbackend.flows;

import com.fasterxml.jackson.databind.JsonNode;
import com.leonardorozza.mvgrreparacionesbackend.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CobroCajaReciboTests extends IntegrationTestBase {

    @Test
    void cobrosParcialesSaldoReciboYCaja() throws Exception {
        String t = registrar("Taller Cobro", "cobro@test.com");
        activarPro(t);

        // Reparación con mano de obra 30000
        long repId = node(authPost("/api/reparaciones/ingreso-rapido", t, json(Map.of(
                "clienteNombre", "Caro", "clienteApellido", "P", "clienteTelefono", "8001",
                "equipoMarca", "Samsung", "equipoModelo", "A52",
                "descripcionProblema", "pantalla", "precioEstimado", 30000)))
                .andExpect(status().isCreated())).get("reparacion").get("id").asLong();

        // + repuesto 20000 → total 50000
        authPost("/api/repuestos", t, json(Map.of(
                "nombre", "Pantalla", "precio", 20000, "reparacionId", repId)))
                .andExpect(status().isCreated());

        // Seña 20000
        JsonNode cobroCreado = node(authPost("/api/reparaciones/" + repId + "/cobros", t,
                json(Map.of(
                        "monto", 20000,
                        "metodo", "EFECTIVO",
                        "referencia", "  CAJA-001  ",
                        "observaciones", "Seña interna")))
                .andExpect(status().isCreated()));
        assertThat(cobroCreado.get("referencia").asText()).isEqualTo("CAJA-001");
        assertThat(cobroCreado.get("observaciones").asText()).isEqualTo("Seña interna");

        JsonNode resumen = node(authGet("/api/reparaciones/" + repId + "/cobros", t).andExpect(status().isOk()));
        assertThat(resumen.get("total").asInt()).isEqualTo(50000);
        assertThat(resumen.get("cobrado").asInt()).isEqualTo(20000);
        assertThat(resumen.get("saldo").asInt()).isEqualTo(30000);
        assertThat(resumen.get("excedente").asInt()).isZero();
        assertThat(resumen.get("requiereRevision").asBoolean()).isFalse();
        assertThat(resumen.get("pagado").asBoolean()).isFalse();
        assertThat(resumen.at("/cobros/0/referencia").asText()).isEqualTo("CAJA-001");

        // Saldo 30000
        JsonNode saldoCreado = node(authPost("/api/reparaciones/" + repId + "/cobros", t,
                json(Map.of("monto", 30000, "metodo", "TRANSFERENCIA", "referencia", "   ")))
                .andExpect(status().isCreated()));
        assertThat(saldoCreado.get("referencia").isNull()).isTrue();

        JsonNode recibo = node(authGet("/api/reparaciones/" + repId + "/recibo", t).andExpect(status().isOk()));
        assertThat(recibo.get("total").asInt()).isEqualTo(50000);
        assertThat(recibo.get("cobrado").asInt()).isEqualTo(50000);
        assertThat(recibo.get("saldo").asInt()).isEqualTo(0);
        assertThat(recibo.get("pagado").asBoolean()).isTrue();

        JsonNode caja = node(authGet("/api/caja", t).andExpect(status().isOk()));
        assertThat(caja.get("totalCobrado").asInt()).isEqualTo(50000);
        assertThat(caja.get("cantidad").asInt()).isEqualTo(2);
        assertThat(caja.get("porMetodo").get("EFECTIVO").asInt()).isEqualTo(20000);
        assertThat(caja.get("porMetodo").get("TRANSFERENCIA").asInt()).isEqualTo(30000);
    }

    @Test
    void rechazaUnCobroQueSuperaElPendienteYConservaElEstado() throws Exception {
        String token = registrar("Taller Límite Cobro", "cobro-limite@test.com");
        activarPro(token);
        long reparacionId = node(authPost("/api/reparaciones/ingreso-rapido", token, json(Map.of(
                "clienteNombre", "Luz", "clienteTelefono", "8002",
                "equipoMarca", "Apple", "equipoModelo", "iPhone 13",
                "descripcionProblema", "No carga", "precioEstimado", 50000)))
                .andExpect(status().isCreated())).at("/reparacion/id").asLong();

        authPost("/api/reparaciones/" + reparacionId + "/cobros", token,
                json(Map.of("monto", 20000, "metodo", "EFECTIVO")))
                .andExpect(status().isCreated());

        JsonNode error = node(authPost("/api/reparaciones/" + reparacionId + "/cobros", token,
                json(Map.of("monto", 30001, "metodo", "TRANSFERENCIA")))
                .andExpect(status().isConflict()));

        assertThat(error.get("code").asText()).isEqualTo("COBRO_SUPERA_SALDO");
        assertThat(error.get("message").asText()).isEqualTo("El monto supera el pendiente de cobro.");
        assertThat(error.at("/details/reparacionId").asLong()).isEqualTo(reparacionId);
        assertThat(error.at("/details/total").asInt()).isEqualTo(50000);
        assertThat(error.at("/details/cobrado").asInt()).isEqualTo(20000);
        assertThat(error.at("/details/monto").asInt()).isEqualTo(30001);
        assertThat(error.at("/details/pendiente").asInt()).isEqualTo(30000);

        JsonNode resumen = node(authGet("/api/reparaciones/" + reparacionId + "/cobros", token)
                .andExpect(status().isOk()));
        assertThat(resumen.get("cobrado").asInt()).isEqualTo(20000);
        assertThat(resumen.get("saldo").asInt()).isEqualTo(30000);
        assertThat(resumen.get("excedente").asInt()).isZero();
        assertThat(resumen.get("requiereRevision").asBoolean()).isFalse();
        assertThat(resumen.get("cobros")).hasSize(1);
    }

    @Test
    void referenciaSeValidaPersisteYNoSeExponeEntreTalleres() throws Exception {
        String tokenA = registrar("Taller Referencia A", "referencia-a@test.com");
        String tokenB = registrar("Taller Referencia B", "referencia-b@test.com");
        activarPro(tokenA);
        activarPro(tokenB);

        long reparacionId = node(authPost("/api/reparaciones/ingreso-rapido", tokenA, json(Map.of(
                "clienteNombre", "Rita", "clienteTelefono", "8003",
                "equipoMarca", "Samsung", "equipoModelo", "S23",
                "descripcionProblema", "No enciende", "precioEstimado", 40000)))
                .andExpect(status().isCreated())).at("/reparacion/id").asLong();

        JsonNode creado = node(authPost("/api/reparaciones/" + reparacionId + "/cobros", tokenA,
                json(Map.of(
                        "monto", 10000,
                        "metodo", "TRANSFERENCIA",
                        "referencia", "  OP-123  ",
                        "observaciones", "Dato sólo interno")))
                .andExpect(status().isCreated()));
        assertThat(creado.get("referencia").asText()).isEqualTo("OP-123");

        JsonNode listado = node(authGet("/api/reparaciones/" + reparacionId + "/cobros", tokenA)
                .andExpect(status().isOk()));
        assertThat(listado.at("/cobros/0/referencia").asText()).isEqualTo("OP-123");
        assertThat(listado.at("/cobros/0/observaciones").asText()).isEqualTo("Dato sólo interno");

        authGet("/api/reparaciones/" + reparacionId + "/cobros", tokenB)
                .andExpect(status().isNotFound());

        authPost("/api/reparaciones/" + reparacionId + "/cobros", tokenA,
                json(Map.of(
                        "monto", 1,
                        "metodo", "EFECTIVO",
                        "referencia", "R".repeat(121))))
                .andExpect(status().isBadRequest());

        JsonNode sinCobroInvalido = node(authGet("/api/reparaciones/" + reparacionId + "/cobros", tokenA)
                .andExpect(status().isOk()));
        assertThat(sinCobroInvalido.get("cobros")).hasSize(1);
        assertThat(sinCobroInvalido.get("cobrado").asInt()).isEqualTo(10000);
    }

    @Test
    void anulacionCanonicaConservaHistoriaYExcluyeElCobroDeTodosLosAgregados() throws Exception {
        String token = registrar("Taller Anulación", "anulacion@test.com");
        activarPro(token);
        long reparacionId = crearOrden(token, "8004", 50000);

        JsonNode cobro = node(authPost("/api/reparaciones/" + reparacionId + "/cobros", token,
                json(Map.of(
                        "monto", 20000,
                        "metodo", "TRANSFERENCIA",
                        "referencia", "ANU-001")))
                .andExpect(status().isCreated()));
        long cobroId = cobro.get("id").asLong();
        assertThat(cobro.get("estado").asText()).isEqualTo("ACTIVO");
        assertThat(cobro.get("anuladoAt").isNull()).isTrue();

        JsonNode anulado = node(authPost(
                "/api/reparaciones/" + reparacionId + "/cobros/" + cobroId + "/anulacion",
                token,
                json(Map.of("motivo", "  Carga duplicada  ")))
                .andExpect(status().isOk()));
        assertThat(anulado.get("estado").asText()).isEqualTo("ANULADO");
        assertThat(anulado.get("motivoAnulacion").asText()).isEqualTo("Carga duplicada");
        assertThat(anulado.get("anuladoPorNombre").asText()).isEqualTo("Admin");
        assertThat(anulado.get("anuladoAt").asText()).isNotBlank();
        String anuladoAt = anulado.get("anuladoAt").asText();

        // El alias legado sobre una anulación canónica es idempotente y no pisa auditoría.
        authDelete("/api/reparaciones/" + reparacionId + "/cobros/" + cobroId, token)
                .andExpect(status().isNoContent());
        JsonNode trasAlias = buscarCobro(node(authGet(
                "/api/reparaciones/" + reparacionId + "/cobros", token)
                .andExpect(status().isOk())).get("cobros"), cobroId);
        assertThat(trasAlias.get("anuladoAt").asText()).isEqualTo(anuladoAt);
        assertThat(trasAlias.get("motivoAnulacion").asText()).isEqualTo("Carga duplicada");

        JsonNode repetida = node(authPost(
                "/api/reparaciones/" + reparacionId + "/cobros/" + cobroId + "/anulacion",
                token,
                json(Map.of("motivo", "Otro motivo")))
                .andExpect(status().isConflict()));
        assertThat(repetida.get("code").asText()).isEqualTo("COBRO_YA_ANULADO");
        assertThat(repetida.at("/details/reparacionId").asLong()).isEqualTo(reparacionId);
        assertThat(repetida.at("/details/cobroId").asLong()).isEqualTo(cobroId);

        JsonNode resumen = node(authGet("/api/reparaciones/" + reparacionId + "/cobros", token)
                .andExpect(status().isOk()));
        assertThat(resumen.get("cobrado").asInt()).isZero();
        assertThat(resumen.get("saldo").asInt()).isEqualTo(50000);
        assertThat(resumen.get("cobros")).hasSize(1);
        assertThat(resumen.at("/cobros/0/estado").asText()).isEqualTo("ANULADO");

        JsonNode detalle = node(authGet("/api/reparaciones/" + reparacionId, token)
                .andExpect(status().isOk()));
        assertThat(detalle.get("cobrado").asInt()).isZero();
        assertThat(detalle.get("saldo").asInt()).isEqualTo(50000);

        JsonNode listado = node(authGet("/api/reparaciones?q=8004", token)
                .andExpect(status().isOk())).at("/content/0");
        assertThat(listado.get("cobrado").asInt()).isZero();
        assertThat(listado.get("saldo").asInt()).isEqualTo(50000);

        JsonNode dashboard = buscarReparacion(node(authGet("/api/dashboard", token)
                .andExpect(status().isOk())).get("ultimasReparaciones"), reparacionId);
        assertThat(dashboard.get("cobrado").asInt()).isZero();
        assertThat(dashboard.get("saldo").asInt()).isEqualTo(50000);

        JsonNode recibo = node(authGet("/api/reparaciones/" + reparacionId + "/recibo", token)
                .andExpect(status().isOk()));
        assertThat(recibo.get("cobrado").asInt()).isZero();
        assertThat(recibo.get("saldo").asInt()).isEqualTo(50000);

        JsonNode cajaVacia = node(authGet("/api/caja", token).andExpect(status().isOk()));
        assertThat(cajaVacia.get("totalCobrado").asInt()).isZero();
        assertThat(cajaVacia.get("cantidad").asInt()).isZero();
        assertThat(cajaVacia.get("cobros")).hasSize(0);

        JsonNode reemplazo = node(authPost("/api/reparaciones/" + reparacionId + "/cobros", token,
                json(Map.of("monto", 50000, "metodo", "EFECTIVO", "referencia", "ANU-002")))
                .andExpect(status().isCreated()));
        assertThat(reemplazo.get("estado").asText()).isEqualTo("ACTIVO");

        JsonNode reabierto = node(authGet("/api/reparaciones/" + reparacionId + "/cobros", token)
                .andExpect(status().isOk()));
        assertThat(reabierto.get("cobrado").asInt()).isEqualTo(50000);
        assertThat(reabierto.get("saldo").asInt()).isZero();
        assertThat(reabierto.get("cobros")).hasSize(2);

        JsonNode caja = node(authGet("/api/caja", token).andExpect(status().isOk()));
        assertThat(caja.get("totalCobrado").asInt()).isEqualTo(50000);
        assertThat(caja.get("cantidad").asInt()).isEqualTo(1);
        assertThat(caja.get("cobros")).hasSize(1);
        assertThat(caja.at("/cobros/0/id").asLong()).isEqualTo(reemplazo.get("id").asLong());
    }

    @Test
    void anulacionValidaMotivoYRelacionExactaSinMutarElCobro() throws Exception {
        String token = registrar("Taller Validación Anulación", "anulacion-validacion@test.com");
        activarPro(token);
        long reparacionId = crearOrden(token, "8005", 30000);
        long otraReparacionId = crearOrden(token, "8006", 10000);
        long cobroId = idOf(authPost("/api/reparaciones/" + reparacionId + "/cobros", token,
                json(Map.of("monto", 10000, "metodo", "EFECTIVO")))
                .andExpect(status().isCreated()));

        authPost("/api/reparaciones/" + reparacionId + "/cobros/" + cobroId + "/anulacion",
                token, json(Map.of("motivo", "   ")))
                .andExpect(status().isBadRequest());
        authPost("/api/reparaciones/" + reparacionId + "/cobros/" + cobroId + "/anulacion",
                token, json(Map.of("motivo", "M".repeat(256))))
                .andExpect(status().isBadRequest());

        // Reparación existente del mismo tenant, pero el cobro no le pertenece.
        authPost("/api/reparaciones/" + otraReparacionId + "/cobros/" + cobroId + "/anulacion",
                token, json(Map.of("motivo", "Relación incorrecta")))
                .andExpect(status().isNotFound());
        authDelete("/api/reparaciones/" + otraReparacionId + "/cobros/" + cobroId, token)
                .andExpect(status().isNotFound());

        JsonNode activo = buscarCobro(node(authGet(
                "/api/reparaciones/" + reparacionId + "/cobros", token)
                .andExpect(status().isOk())).get("cobros"), cobroId);
        assertThat(activo.get("estado").asText()).isEqualTo("ACTIVO");

        JsonNode limiteValido = node(authPost(
                "/api/reparaciones/" + reparacionId + "/cobros/" + cobroId + "/anulacion",
                token,
                json(Map.of("motivo", "M".repeat(255))))
                .andExpect(status().isOk()));
        assertThat(limiteValido.get("motivoAnulacion").asText()).hasSize(255);
    }

    @Test
    void aliasLegadoEsIdempotenteConservaAuditoriaYLaHistoriaBloqueaLaBaja() throws Exception {
        String token = registrar("Taller Alias Anulación", "anulacion-alias@test.com");
        activarPro(token);
        long reparacionId = crearOrden(token, "8007", 25000);
        long cobroId = idOf(authPost("/api/reparaciones/" + reparacionId + "/cobros", token,
                json(Map.of("monto", 25000, "metodo", "EFECTIVO")))
                .andExpect(status().isCreated()));

        authDelete("/api/reparaciones/" + reparacionId + "/cobros/" + cobroId, token)
                .andExpect(status().isNoContent());
        JsonNode primera = buscarCobro(node(authGet(
                "/api/reparaciones/" + reparacionId + "/cobros", token)
                .andExpect(status().isOk())).get("cobros"), cobroId);
        assertThat(primera.get("estado").asText()).isEqualTo("ANULADO");
        assertThat(primera.get("motivoAnulacion").asText())
                .isEqualTo("Anulación mediante endpoint legado");
        assertThat(primera.get("anuladoPorNombre").asText()).isEqualTo("Admin");

        authDelete("/api/reparaciones/" + reparacionId + "/cobros/" + cobroId, token)
                .andExpect(status().isNoContent());
        JsonNode segunda = buscarCobro(node(authGet(
                "/api/reparaciones/" + reparacionId + "/cobros", token)
                .andExpect(status().isOk())).get("cobros"), cobroId);
        assertThat(segunda.get("anuladoAt").asText()).isEqualTo(primera.get("anuladoAt").asText());
        assertThat(segunda.get("motivoAnulacion").asText())
                .isEqualTo("Anulación mediante endpoint legado");

        JsonNode conflictoCanonico = node(authPost(
                "/api/reparaciones/" + reparacionId + "/cobros/" + cobroId + "/anulacion",
                token,
                json(Map.of("motivo", "Intento posterior")))
                .andExpect(status().isConflict()));
        assertThat(conflictoCanonico.get("code").asText()).isEqualTo("COBRO_YA_ANULADO");

        // existsByReparacionId incluye toda la historia, aun cuando cobrado activo sea cero.
        authDelete("/api/reparaciones/" + reparacionId, token)
                .andExpect(status().isBadRequest());
        authGet("/api/reparaciones/" + reparacionId, token).andExpect(status().isOk());
    }

    private long crearOrden(String token, String telefono, int precioEstimado) throws Exception {
        return node(authPost("/api/reparaciones/ingreso-rapido", token, json(Map.of(
                "clienteNombre", "Cliente", "clienteTelefono", telefono,
                "equipoMarca", "Samsung", "equipoModelo", "A54",
                "descripcionProblema", "Pantalla", "precioEstimado", precioEstimado)))
                .andExpect(status().isCreated())).at("/reparacion/id").asLong();
    }

    private JsonNode buscarCobro(JsonNode cobros, long cobroId) {
        for (JsonNode cobro : cobros) {
            if (cobro.get("id").asLong() == cobroId) {
                return cobro;
            }
        }
        throw new AssertionError("No se encontró el cobro " + cobroId);
    }

    private JsonNode buscarReparacion(JsonNode reparaciones, long reparacionId) {
        for (JsonNode reparacion : reparaciones) {
            if (reparacion.get("id").asLong() == reparacionId) {
                return reparacion;
            }
        }
        throw new AssertionError("No se encontró la reparación " + reparacionId);
    }
}
