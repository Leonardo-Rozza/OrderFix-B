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
class InventarioConcurrencyIT extends IntegrationTestBase {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_inventario_concurrency")
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
    void dosConsumosConcurrentesNoPuedenUsarLaMismaUltimaUnidad() throws Exception {
        String token = registrar("Taller Stock Concurrente", "stock-concurrente@test.com");
        activarPro(token);
        long articuloId = idOf(authPost("/api/inventario", token, json(Map.of(
                "nombre", "Último módulo", "sku", "LAST-1", "precio", 15000,
                "stock", 1, "stockMinimo", 0)))
                .andExpect(status().isCreated()));
        long reparacionA = crearReparacion(token, "9201");
        long reparacionB = crearReparacion(token, "9202");

        CountDownLatch listos = new CountDownLatch(2);
        CountDownLatch inicio = new CountDownLatch(1);
        Callable<Resultado> consumirA = consumoConcurrente(
                token, articuloId, reparacionA, "Módulo A", listos, inicio);
        Callable<Resultado> consumirB = consumoConcurrente(
                token, articuloId, reparacionB, "Módulo B", listos, inicio);

        Connection gate = dataSource.getConnection();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            gate.setAutoCommit(false);
            try (PreparedStatement lock = gate.prepareStatement(
                    "SELECT id FROM articulos WHERE id = ? FOR NO KEY UPDATE")) {
                lock.setLong(1, articuloId);
                try (var fila = lock.executeQuery()) {
                    assertThat(fila.next()).isTrue();
                }
            }

            Future<Resultado> primero = executor.submit(consumirA);
            Future<Resultado> segundo = executor.submit(consumirB);
            assertThat(listos.await(10, TimeUnit.SECONDS)).isTrue();
            inicio.countDown();

            assertSigueBloqueado(primero);
            assertSigueBloqueado(segundo);
            gate.commit();

            Resultado resultadoA = primero.get(15, TimeUnit.SECONDS);
            Resultado resultadoB = segundo.get(15, TimeUnit.SECONDS);
            assertThat(new int[]{resultadoA.status(), resultadoB.status()})
                    .containsExactlyInAnyOrder(201, 400);

            Resultado rechazado = resultadoA.status() == 400 ? resultadoA : resultadoB;
            assertThat(om.readTree(rechazado.body()).get("message").asText())
                    .contains("Stock insuficiente");
        } finally {
            inicio.countDown();
            liberarGate(gate, executor);
        }

        JsonNode articulo = node(authGet("/api/inventario/" + articuloId, token)
                .andExpect(status().isOk()));
        JsonNode repuestosA = node(authGet("/api/repuestos/reparacion/" + reparacionA, token)
                .andExpect(status().isOk()));
        JsonNode repuestosB = node(authGet("/api/repuestos/reparacion/" + reparacionB, token)
                .andExpect(status().isOk()));

        assertThat(articulo.get("stock").asInt()).isZero();
        assertThat(repuestosA.size() + repuestosB.size()).isEqualTo(1);
    }

    private Callable<Resultado> consumoConcurrente(
            String token,
            long articuloId,
            long reparacionId,
            String nombre,
            CountDownLatch listos,
            CountDownLatch inicio) {
        return () -> {
            listos.countDown();
            if (!inicio.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Los consumos no iniciaron juntos");
            }
            ResultActions result = authPost("/api/repuestos", token, json(Map.of(
                    "nombre", nombre,
                    "precio", 15000,
                    "reparacionId", reparacionId,
                    "articuloId", articuloId,
                    "cantidad", 1)));
            var response = result.andReturn().getResponse();
            return new Resultado(response.getStatus(), response.getContentAsString());
        };
    }

    private long crearReparacion(String token, String telefono) throws Exception {
        return node(authPost("/api/reparaciones/ingreso-rapido", token, json(Map.of(
                "clienteNombre", "Cliente",
                "clienteTelefono", telefono,
                "equipoMarca", "Motorola",
                "equipoModelo", "Edge",
                "descripcionProblema", "No enciende")))
                .andExpect(status().isCreated())).at("/reparacion/id").asLong();
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
