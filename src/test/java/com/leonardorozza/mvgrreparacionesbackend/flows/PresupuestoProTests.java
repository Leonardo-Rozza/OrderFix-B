package com.leonardorozza.mvgrreparacionesbackend.flows;

import com.fasterxml.jackson.databind.JsonNode;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Presupuesto;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.PresupuestoRepository;
import com.leonardorozza.mvgrreparacionesbackend.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Presupuesto pro (§5): totales discriminados (mano de obra vs repuesto + calidad),
 * auto-estado de la reparación, aprobación del lado taller, re-presupuestar y vencimiento.
 */
class PresupuestoProTests extends IntegrationTestBase {

    @Autowired
    private PresupuestoRepository presupuestoRepository;

    private JsonNode ingreso(String t, String tel) throws Exception {
        return node(authPost("/api/reparaciones/ingreso-rapido", t, json(Map.of(
                "clienteNombre", "C", "clienteTelefono", tel,
                "equipoMarca", "Apple", "equipoModelo", "iPhone 12", "descripcionProblema", "pantalla")))
                .andExpect(status().isCreated()));
    }

    private String itemsManoYRepuesto() throws Exception {
        return json(Map.of("items", List.of(
                Map.of("descripcion", "Mano de obra", "cantidad", 1, "precioUnitario", 12000, "tipoItem", "MANO_DE_OBRA"),
                Map.of("descripcion", "Pantalla", "cantidad", 1, "precioUnitario", 30000, "tipoItem", "REPUESTO", "calidad", "ALTERNATIVO"))));
    }

    @Test
    void totalesDiscriminadosAutoEstadoYAprobacionTaller() throws Exception {
        String t = registrar("Taller Pro", "presupro@test.com");
        long repId = ingreso(t, "6101").get("reparacion").get("id").asLong();

        // Para que crear presupuesto ORIGINAL → PRESUPUESTADO sea una transición legal
        authPatch("/api/reparaciones/" + repId + "/estado", t, json(Map.of("estado", "EN_DIAGNOSTICO")))
                .andExpect(status().isOk());

        JsonNode creado = node(authPost("/api/reparaciones/" + repId + "/presupuestos", t, itemsManoYRepuesto())
                .andExpect(status().isCreated()));
        assertThat(creado.get("tipo").asText()).isEqualTo("ORIGINAL");
        assertThat(creado.get("estado").asText()).isEqualTo("PENDIENTE");
        assertThat(creado.get("total").asInt()).isEqualTo(42000);
        assertThat(creado.get("manoDeObraTotal").asInt()).isEqualTo(12000);
        assertThat(creado.get("repuestosTotal").asInt()).isEqualTo(30000);
        assertThat(creado.get("validezDias").asInt()).isEqualTo(7);
        assertThat(creado.get("vencido").asBoolean()).isFalse();

        JsonNode repuesto = creado.get("items").get(1);
        assertThat(repuesto.get("tipoItem").asText()).isEqualTo("REPUESTO");
        assertThat(repuesto.get("calidad").asText()).isEqualTo("ALTERNATIVO");

        // Auto-estado: la reparación pasó a PRESUPUESTADO al crear el presupuesto
        assertThat(node(authGet("/api/reparaciones/" + repId, t)).get("estado").asText()).isEqualTo("PRESUPUESTADO");

        // El taller aprueba → presupuesto APROBADO y reparación EN_PROCESO
        long presId = creado.get("id").asLong();
        JsonNode aprob = node(authPost("/api/reparaciones/" + repId + "/presupuestos/" + presId + "/aprobar", t)
                .andExpect(status().isOk()));
        assertThat(aprob.get("estado").asText()).isEqualTo("APROBADO");
        assertThat(node(authGet("/api/reparaciones/" + repId, t)).get("estado").asText()).isEqualTo("EN_PROCESO");
    }

    @Test
    void represupuestarClonaLosItems() throws Exception {
        String t = registrar("Taller Repre", "represu@test.com");
        long repId = ingreso(t, "6102").get("reparacion").get("id").asLong();

        JsonNode original = node(authPost("/api/reparaciones/" + repId + "/presupuestos", t, itemsManoYRepuesto())
                .andExpect(status().isCreated()));
        long presId = original.get("id").asLong();

        // Re-presupuestar sin body → clona ítems con validez nueva, nuevo PENDIENTE
        JsonNode clon = node(authPost("/api/reparaciones/" + repId + "/presupuestos/" + presId + "/represupuestar", t)
                .andExpect(status().isCreated()));
        assertThat(clon.get("id").asLong()).isNotEqualTo(presId);
        assertThat(clon.get("estado").asText()).isEqualTo("PENDIENTE");
        assertThat(clon.get("total").asInt()).isEqualTo(42000);
        assertThat(clon.get("items").size()).isEqualTo(2);
        assertThat(clon.get("items").get(1).get("calidad").asText()).isEqualTo("ALTERNATIVO");
    }

    @Test
    void presupuestoVencidoNoSeAprueba() throws Exception {
        String t = registrar("Taller Venc", "venc@test.com");
        JsonNode ir = ingreso(t, "6103");
        long repId = ir.get("reparacion").get("id").asLong();
        String codigo = ir.get("reparacion").get("codigoSeguimiento").asText();

        long presId = node(authPost("/api/reparaciones/" + repId + "/presupuestos", t, itemsManoYRepuesto())
                .andExpect(status().isCreated())).get("id").asLong();

        // Back-date la validez para simular vencimiento
        Presupuesto p = presupuestoRepository.findById(presId).orElseThrow();
        p.setValidoHasta(LocalDateTime.now().minusDays(1));
        presupuestoRepository.save(p);

        // El listado lo muestra como VENCIDO
        JsonNode lista = node(authGet("/api/reparaciones/" + repId + "/presupuestos", t).andExpect(status().isOk()));
        assertThat(lista.get(0).get("estado").asText()).isEqualTo("VENCIDO");
        assertThat(lista.get(0).get("vencido").asBoolean()).isTrue();

        // No se puede aprobar un vencido (público) → 400
        mvc.perform(post("/api/seguimiento/" + codigo + "/presupuesto/aprobar"))
                .andExpect(status().isBadRequest());
    }
}
