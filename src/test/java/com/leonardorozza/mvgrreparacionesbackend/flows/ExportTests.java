package com.leonardorozza.mvgrreparacionesbackend.flows;

import com.leonardorozza.mvgrreparacionesbackend.support.IntegrationTestBase;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.StringJoiner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ExportTests extends IntegrationTestBase {

    @Test
    void adminExportaExcelConSusDatosYSoloLosSuyos() throws Exception {
        String a = registrar("Taller Export A", "exp-a@test.com");
        activarPro(a); // los cobros son función PRO; el export en sí es para todos los planes
        String b = registrar("Taller Export B", "exp-b@test.com");

        // Datos del taller A: orden con cobro parcial
        long repId = node(authPost("/api/reparaciones/ingreso-rapido", a, json(Map.of(
                "clienteNombre", "ClienteExport", "clienteTelefono", "7301",
                "equipoMarca", "Samsung", "equipoModelo", "A54",
                "descripcionProblema", "pantalla rota", "precioEstimado", 80000)))
                .andExpect(status().isCreated())).get("reparacion").get("id").asLong();
        authPost("/api/reparaciones/" + repId + "/cobros", a,
                json(Map.of("monto", 30000, "metodo", "EFECTIVO"))).andExpect(status().isCreated());

        // Datos del taller B: NO deben aparecer en el export de A
        authPost("/api/reparaciones/ingreso-rapido", b, json(Map.of(
                "clienteNombre", "ClienteAjeno", "clienteTelefono", "7302",
                "equipoMarca", "Xiaomi", "equipoModelo", "Note12",
                "descripcionProblema", "no enciende"))).andExpect(status().isCreated());

        byte[] xlsx = authGet("/api/export/excel", a)
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString(".xlsx")))
                .andReturn().getResponse().getContentAsByteArray();

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            assertThat(wb.getSheet("Clientes")).isNotNull();
            assertThat(wb.getSheet("Órdenes")).isNotNull();
            assertThat(wb.getSheet("Cobros")).isNotNull();
            assertThat(wb.getSheet("Presupuestos")).isNotNull();

            String clientes = textoDe(wb.getSheet("Clientes"));
            assertThat(clientes).contains("ClienteExport");
            assertThat(clientes).doesNotContain("ClienteAjeno"); // aislamiento entre talleres

            String ordenes = textoDe(wb.getSheet("Órdenes"));
            assertThat(ordenes).contains("pantalla rota", "Samsung A54", "PARCIAL");
            assertThat(ordenes).doesNotContain("no enciende");

            assertThat(textoDe(wb.getSheet("Cobros"))).contains("30000", "EFECTIVO");
        }
    }

    @Test
    void empleadoUserNoPuedeExportar() throws Exception {
        String admin = registrar("Taller Export C", "exp-c@test.com");
        activarPro(admin);
        authPost("/api/usuarios", admin, json(Map.of(
                "username", "EmpExport", "email", "exp-emp@test.com", "password", "secret123")))
                .andExpect(status().isCreated());
        String emp = login("exp-emp@test.com", "secret123");

        authGet("/api/export/excel", emp).andExpect(status().isForbidden());
    }

    /** Todo el contenido de una hoja como un solo string, para asserts simples. */
    private String textoDe(Sheet hoja) {
        DataFormatter fmt = new DataFormatter();
        StringJoiner sj = new StringJoiner(" | ");
        for (Row fila : hoja) {
            for (Cell celda : fila) {
                sj.add(fmt.formatCellValue(celda));
            }
        }
        return sj.toString();
    }
}
