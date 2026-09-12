package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.auth0.jwt.JWT;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leonardorozza.mvgrreparacionesbackend.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Exercises committed workshop deactivation through the existing public HTTP stack on PostgreSQL 16. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class InactiveWorkshopPublicAccessIT extends IntegrationTestBase {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_inactive_public_access")
            .withUsername("ordenfix")
            .withPassword("ordenfix");

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @Test
    void committedDeactivationHidesTrackingAndRejectsBothBudgetActionsWithoutChangingEitherWorkshop() throws Exception {
        String inactiveToken = registerWorkshop();
        String activeToken = registerWorkshop();
        long inactiveWorkshop = JWT.decode(inactiveToken).getClaim("tallerId").asLong();
        long activeWorkshop = JWT.decode(activeToken).getClaim("tallerId").asLong();
        Repair inactive = createPendingRepair(inactiveToken, "110001");
        Repair approved = createPendingRepair(activeToken, "110002");
        Repair rejected = createPendingRepair(activeToken, "110003");

        mvc.perform(get(path(inactive))).andExpect(status().isOk())
                .andExpect(jsonPath("$.presupuesto.estado").value("PENDIENTE"));
        JsonNode activeTracking = node(mvc.perform(get(path(approved))).andExpect(status().isOk()));
        var activeBefore = workshopRows(activeWorkshop);

        // Fixture-only committed write; this test does not introduce or simulate a closure service.
        assertThat(owner().update("UPDATE talleres SET activo = false WHERE id = ?", inactiveWorkshop)).isOne();
        var inactiveBefore = workshopRows(inactiveWorkshop);
        String unknown = "/api/seguimiento/" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);

        assertThat(notFound(mvc.perform(get(path(inactive)))))
                .isEqualTo(notFound(mvc.perform(get(unknown))));
        assertThat(workshopRows(inactiveWorkshop)).isEqualTo(inactiveBefore);
        assertThat(workshopRows(activeWorkshop)).isEqualTo(activeBefore);
        for (String action : List.of("aprobar", "rechazar")) {
            assertThat(notFound(mvc.perform(post(path(inactive) + "/presupuesto/" + action))))
                    .isEqualTo(notFound(mvc.perform(post(unknown + "/presupuesto/" + action))));
            assertThat(workshopRows(inactiveWorkshop)).isEqualTo(inactiveBefore);
            assertThat(workshopRows(activeWorkshop)).isEqualTo(activeBefore);
        }

        assertThat(node(mvc.perform(get(path(approved))).andExpect(status().isOk())))
                .isEqualTo(activeTracking);
        mvc.perform(post(path(approved) + "/presupuesto/aprobar")).andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("APROBADO"));
        mvc.perform(post(path(rejected) + "/presupuesto/rechazar")).andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("RECHAZADO"));
        assertDurableResponse(approved, "APROBADO", "EN_PROCESO");
        assertDurableResponse(rejected, "RECHAZADO", "LISTO_SIN_REPARAR");
        assertThat(workshopRows(inactiveWorkshop)).isEqualTo(inactiveBefore);
    }

    private String registerWorkshop() throws Exception {
        return registrar("Public access fixture " + UUID.randomUUID(), UUID.randomUUID() + "@public-access.synthetic.invalid");
    }

    private Repair createPendingRepair(String token, String phone) throws Exception {
        JsonNode repair = node(authPost("/api/reparaciones/ingreso-rapido", token, json(Map.of(
                "clienteNombre", "Synthetic customer", "clienteTelefono", phone,
                "equipoMarca", "Fixture brand", "equipoModelo", "Fixture model",
                "descripcionProblema", "Synthetic repair"))).andExpect(status().isCreated())).get("reparacion");
        long repairId = repair.get("id").asLong();
        JsonNode budget = node(authPost("/api/reparaciones/" + repairId + "/presupuestos", token, json(Map.of(
                "items", List.of(Map.of("descripcion", "Synthetic labor", "cantidad", 1, "precioUnitario", 1000)))))
                .andExpect(status().isCreated()));
        assertThat(budget.get("estado").asText()).isEqualTo("PENDIENTE");
        return new Repair(repairId, budget.get("id").asLong(), repair.get("codigoSeguimiento").asText());
    }

    private JsonNode notFound(ResultActions response) throws Exception {
        ObjectNode error = (ObjectNode) node(response.andExpect(status().isNotFound()));
        error.remove(List.of("timestamp", "path"));
        return error;
    }

    private static void assertDurableResponse(Repair repair, String budgetState, String repairState) {
        assertThat(owner().queryForObject("SELECT estado FROM presupuestos WHERE id = ?", String.class, repair.budgetId()))
                .isEqualTo(budgetState);
        assertThat(owner().queryForObject("SELECT fecha_respuesta IS NOT NULL FROM presupuestos WHERE id = ?",
                Boolean.class, repair.budgetId())).isTrue();
        assertThat(owner().queryForObject("SELECT estado FROM reparaciones WHERE id = ?", String.class, repair.id()))
                .isEqualTo(repairState);
    }

    private static String path(Repair repair) { return "/api/seguimiento/" + repair.code(); }

    private static JdbcTemplate owner() {
        return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    private static List<String> workshopRows(long workshopId) {
        return owner().queryForList("""
                SELECT 'repair:' || row_to_json(r)::text || ':' || r.xmin::text AS snapshot FROM reparaciones r WHERE taller_id = ?
                UNION ALL SELECT 'budget:' || row_to_json(p)::text || ':' || p.xmin::text FROM presupuestos p WHERE taller_id = ?
                UNION ALL SELECT 'item:' || row_to_json(i)::text || ':' || i.xmin::text FROM presupuesto_items i
                    JOIN presupuestos p ON p.id = i.presupuesto_id WHERE p.taller_id = ?
                UNION ALL SELECT 'workshop:' || row_to_json(t)::text || ':' || t.xmin::text FROM talleres t WHERE id = ?
                UNION ALL SELECT 'user:' || row_to_json(u)::text || ':' || u.xmin::text FROM users u WHERE taller_id = ?
                UNION ALL SELECT 'client:' || row_to_json(c)::text || ':' || c.xmin::text FROM clientes c WHERE taller_id = ?
                UNION ALL SELECT 'device:' || row_to_json(e)::text || ':' || e.xmin::text FROM equipos e WHERE taller_id = ?
                ORDER BY snapshot
                """, String.class, workshopId, workshopId, workshopId, workshopId, workshopId, workshopId, workshopId);
    }

    private record Repair(long id, long budgetId, String code) {}
}
