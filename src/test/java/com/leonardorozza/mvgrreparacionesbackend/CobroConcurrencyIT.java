package com.leonardorozza.mvgrreparacionesbackend;

import com.fasterxml.jackson.databind.JsonNode;
import com.leonardorozza.mvgrreparacionesbackend.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
class CobroConcurrencyIT extends IntegrationTestBase {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_cobro_concurrency")
            .withUsername("ordenfix")
            .withPassword("ordenfix");

    @Autowired
    private DataSource dataSource;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.dialect",
                () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @Test
    void dosCobrosConcurrentesNoPuedenSuperarElTotal() throws Exception {
        String token = registrar("Taller Cobro Concurrente", "cobro-concurrente@test.com");
        activarPro(token);
        long reparacionId = node(authPost("/api/reparaciones/ingreso-rapido", token, json(Map.of(
                "clienteNombre", "Con", "clienteTelefono", "8801",
                "equipoMarca", "Nokia", "equipoModelo", "G50",
                "descripcionProblema", "Pantalla", "precioEstimado", 100000)))
                .andExpect(status().isCreated())).at("/reparacion/id").asLong();

        CountDownLatch listos = new CountDownLatch(2);
        CountDownLatch inicio = new CountDownLatch(1);
        Callable<Resultado> registrarCobro = () -> {
            listos.countDown();
            if (!inicio.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Los cobros no iniciaron juntos");
            }
            ResultActions result = authPost("/api/reparaciones/" + reparacionId + "/cobros", token,
                    json(Map.of("monto", 70000, "metodo", "TRANSFERENCIA")));
            var response = result.andReturn().getResponse();
            return new Resultado(response.getStatus(), response.getContentAsString());
        };

        Connection gate = dataSource.getConnection();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            gate.setAutoCommit(false);
            try (PreparedStatement lock = gate.prepareStatement(
                    "SELECT id FROM reparaciones WHERE id = ? FOR NO KEY UPDATE")) {
                lock.setLong(1, reparacionId);
                try (var fila = lock.executeQuery()) {
                    assertThat(fila.next()).isTrue();
                }
            }

            Future<Resultado> primero = executor.submit(registrarCobro);
            Future<Resultado> segundo = executor.submit(registrarCobro);
            assertThat(listos.await(10, TimeUnit.SECONDS)).isTrue();
            inicio.countDown();

            assertSigueBloqueado(primero);
            assertSigueBloqueado(segundo);
            gate.commit();

            Resultado resultadoA = primero.get(15, TimeUnit.SECONDS);
            Resultado resultadoB = segundo.get(15, TimeUnit.SECONDS);
            assertThat(new int[]{resultadoA.status(), resultadoB.status()})
                    .containsExactlyInAnyOrder(201, 409);

            Resultado conflicto = resultadoA.status() == 409 ? resultadoA : resultadoB;
            assertThat(om.readTree(conflicto.body()).get("code").asText())
                    .isEqualTo("COBRO_SUPERA_SALDO");
        } finally {
            inicio.countDown();
            liberarGate(gate, executor);
        }

        JsonNode estado = node(authGet("/api/reparaciones/" + reparacionId + "/cobros", token)
                .andExpect(status().isOk()));
        assertThat(estado.get("cobrado").asInt()).isEqualTo(70000);
        assertThat(estado.get("saldo").asInt()).isEqualTo(30000);
        assertThat(estado.get("cobros")).hasSize(1);
    }

    @Test
    void cobroConcurrenteConReduccionNuncaDejaCobradoPorEncimaDelTotal() throws Exception {
        String token = registrar("Taller Cobro vs Total", "cobro-vs-total@test.com");
        activarPro(token);
        JsonNode ingreso = node(authPost("/api/reparaciones/ingreso-rapido", token, json(Map.of(
                "clienteNombre", "Rita", "clienteTelefono", "8802",
                "equipoMarca", "Apple", "equipoModelo", "iPhone 15",
                "descripcionProblema", "Pantalla", "precioEstimado", 100000)))
                .andExpect(status().isCreated()));
        long reparacionId = ingreso.at("/reparacion/id").asLong();
        long equipoId = ingreso.get("equipoId").asLong();

        CountDownLatch listos = new CountDownLatch(2);
        CountDownLatch inicio = new CountDownLatch(1);
        Callable<Resultado> cobrar = () -> {
            listos.countDown();
            if (!inicio.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("El cobro no inició junto con la reducción");
            }
            ResultActions result = authPost("/api/reparaciones/" + reparacionId + "/cobros", token,
                    json(Map.of("monto", 80000, "metodo", "TRANSFERENCIA")));
            var response = result.andReturn().getResponse();
            return new Resultado(response.getStatus(), response.getContentAsString());
        };
        Callable<Resultado> reducirTotal = () -> {
            listos.countDown();
            if (!inicio.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("La reducción no inició junto con el cobro");
            }
            ResultActions result = authPut("/api/reparaciones/" + reparacionId, token, json(Map.of(
                    "equipoId", equipoId,
                    "descripcionProblema", "Pantalla",
                    "precioEstimado", 50000)));
            var response = result.andReturn().getResponse();
            return new Resultado(response.getStatus(), response.getContentAsString());
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Resultado> resultadoCobroFuture = executor.submit(cobrar);
            Future<Resultado> resultadoReduccionFuture = executor.submit(reducirTotal);
            assertThat(listos.await(10, TimeUnit.SECONDS)).isTrue();
            inicio.countDown();

            Resultado resultadoCobro = resultadoCobroFuture.get(15, TimeUnit.SECONDS);
            Resultado resultadoReduccion = resultadoReduccionFuture.get(15, TimeUnit.SECONDS);
            boolean cobroGano = resultadoCobro.status() == 201 && resultadoReduccion.status() == 409;
            boolean reduccionGano = resultadoCobro.status() == 409 && resultadoReduccion.status() == 200;
            assertThat(cobroGano || reduccionGano).isTrue();

            Resultado conflicto = resultadoCobro.status() == 409 ? resultadoCobro : resultadoReduccion;
            assertThat(om.readTree(conflicto.body()).get("code").asText())
                    .isEqualTo(cobroGano ? "TOTAL_MENOR_QUE_COBRADO" : "COBRO_SUPERA_SALDO");
        } finally {
            inicio.countDown();
            executor.shutdownNow();
        }

        JsonNode estado = node(authGet("/api/reparaciones/" + reparacionId + "/cobros", token)
                .andExpect(status().isOk()));
        int total = estado.get("total").asInt();
        int cobrado = estado.get("cobrado").asInt();

        assertThat(cobrado).isLessThanOrEqualTo(total);
        assertThat(total == 100000 && cobrado == 80000
                || total == 50000 && cobrado == 0).isTrue();
        assertThat(estado.get("saldo").asInt()).isEqualTo(total - cobrado);
        assertThat(estado.get("cobros").size()).isEqualTo(cobrado == 0 ? 0 : 1);
    }

    @Test
    void dosAnulacionesCanonicasConcurrentesAuditanUnaSolaTransicion() throws Exception {
        String token = registrar("Taller Anulación Concurrente", "anulacion-concurrente@test.com");
        activarPro(token);
        long reparacionId = node(authPost("/api/reparaciones/ingreso-rapido", token, json(Map.of(
                "clienteNombre", "Ana", "clienteTelefono", "8803",
                "equipoMarca", "Samsung", "equipoModelo", "S24",
                "descripcionProblema", "Pantalla", "precioEstimado", 40000)))
                .andExpect(status().isCreated())).at("/reparacion/id").asLong();
        long cobroId = idOf(authPost("/api/reparaciones/" + reparacionId + "/cobros", token,
                json(Map.of("monto", 20000, "metodo", "TRANSFERENCIA")))
                .andExpect(status().isCreated()));

        CountDownLatch listos = new CountDownLatch(2);
        CountDownLatch inicio = new CountDownLatch(1);
        Callable<Resultado> anular = () -> {
            listos.countDown();
            if (!inicio.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Las anulaciones no iniciaron juntas");
            }
            ResultActions result = authPost(
                    "/api/reparaciones/" + reparacionId + "/cobros/" + cobroId + "/anulacion",
                    token,
                    json(Map.of("motivo", "Carrera controlada")));
            var response = result.andReturn().getResponse();
            return new Resultado(response.getStatus(), response.getContentAsString());
        };

        Connection gate = dataSource.getConnection();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            gate.setAutoCommit(false);
            try (PreparedStatement lock = gate.prepareStatement(
                    "SELECT id FROM reparaciones WHERE id = ? FOR NO KEY UPDATE")) {
                lock.setLong(1, reparacionId);
                try (var fila = lock.executeQuery()) {
                    assertThat(fila.next()).isTrue();
                }
            }

            Future<Resultado> primera = executor.submit(anular);
            Future<Resultado> segunda = executor.submit(anular);
            assertThat(listos.await(10, TimeUnit.SECONDS)).isTrue();
            inicio.countDown();

            assertSigueBloqueado(primera);
            assertSigueBloqueado(segunda);
            gate.commit();

            Resultado resultadoA = primera.get(15, TimeUnit.SECONDS);
            Resultado resultadoB = segunda.get(15, TimeUnit.SECONDS);
            assertThat(new int[]{resultadoA.status(), resultadoB.status()})
                    .containsExactlyInAnyOrder(200, 409);
            Resultado conflicto = resultadoA.status() == 409 ? resultadoA : resultadoB;
            assertThat(om.readTree(conflicto.body()).get("code").asText())
                    .isEqualTo("COBRO_YA_ANULADO");
        } finally {
            inicio.countDown();
            liberarGate(gate, executor);
        }

        JsonNode estado = node(authGet("/api/reparaciones/" + reparacionId + "/cobros", token)
                .andExpect(status().isOk()));
        assertThat(estado.get("cobrado").asInt()).isZero();
        assertThat(estado.get("cobros")).hasSize(1);
        assertThat(estado.at("/cobros/0/estado").asText()).isEqualTo("ANULADO");
        assertThat(estado.at("/cobros/0/motivoAnulacion").asText()).isEqualTo("Carrera controlada");
        assertThat(estado.at("/cobros/0/anuladoPorNombre").asText()).isEqualTo("Admin");
    }

    @Test
    void dosAliasLegadosConcurrentesSonIdempotentesYNoSobrescribenAuditoria() throws Exception {
        String token = registrar("Taller Alias Concurrente", "alias-concurrente@test.com");
        activarPro(token);
        long reparacionId = node(authPost("/api/reparaciones/ingreso-rapido", token, json(Map.of(
                "clienteNombre", "Leo", "clienteTelefono", "8804",
                "equipoMarca", "Motorola", "equipoModelo", "G84",
                "descripcionProblema", "Conector", "precioEstimado", 30000)))
                .andExpect(status().isCreated())).at("/reparacion/id").asLong();
        long cobroId = idOf(authPost("/api/reparaciones/" + reparacionId + "/cobros", token,
                json(Map.of("monto", 10000, "metodo", "EFECTIVO")))
                .andExpect(status().isCreated()));

        CountDownLatch listos = new CountDownLatch(2);
        CountDownLatch inicio = new CountDownLatch(1);
        Callable<Resultado> anularLegado = () -> {
            listos.countDown();
            if (!inicio.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Los aliases no iniciaron juntos");
            }
            ResultActions result = authDelete(
                    "/api/reparaciones/" + reparacionId + "/cobros/" + cobroId,
                    token);
            var response = result.andReturn().getResponse();
            return new Resultado(response.getStatus(), response.getContentAsString());
        };

        Connection gate = dataSource.getConnection();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            gate.setAutoCommit(false);
            try (PreparedStatement lock = gate.prepareStatement(
                    "SELECT id FROM reparaciones WHERE id = ? FOR NO KEY UPDATE")) {
                lock.setLong(1, reparacionId);
                try (var fila = lock.executeQuery()) {
                    assertThat(fila.next()).isTrue();
                }
            }

            Future<Resultado> primero = executor.submit(anularLegado);
            Future<Resultado> segundo = executor.submit(anularLegado);
            assertThat(listos.await(10, TimeUnit.SECONDS)).isTrue();
            inicio.countDown();

            assertSigueBloqueado(primero);
            assertSigueBloqueado(segundo);
            gate.commit();

            assertThat(primero.get(15, TimeUnit.SECONDS).status()).isEqualTo(204);
            assertThat(segundo.get(15, TimeUnit.SECONDS).status()).isEqualTo(204);
        } finally {
            inicio.countDown();
            liberarGate(gate, executor);
        }

        JsonNode estado = node(authGet("/api/reparaciones/" + reparacionId + "/cobros", token)
                .andExpect(status().isOk()));
        assertThat(estado.get("cobrado").asInt()).isZero();
        assertThat(estado.get("cobros")).hasSize(1);
        assertThat(estado.at("/cobros/0/estado").asText()).isEqualTo("ANULADO");
        assertThat(estado.at("/cobros/0/motivoAnulacion").asText())
                .isEqualTo("Anulación mediante endpoint legado");
        assertThat(estado.at("/cobros/0/anuladoAt").asText()).isNotBlank();
    }

    private static void assertSigueBloqueado(Future<?> future) {
        assertThatThrownBy(() -> future.get(400, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);
    }

    private static void liberarGate(Connection gate, ExecutorService executor) throws Exception {
        try {
            if (!gate.isClosed() && !gate.getAutoCommit()) {
                gate.rollback();
            }
        } finally {
            try {
                gate.close();
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private record Resultado(int status, String body) {
    }
}
