package com.leonardorozza.mvgrreparacionesbackend.flows;

import com.fasterxml.jackson.databind.JsonNode;
import com.leonardorozza.mvgrreparacionesbackend.support.IntegrationTestBase;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.StringJoiner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ExportTests extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void adminExportaExcelConSusDatosYSoloLosSuyos() throws Exception {
        String a = registrar("Taller Export A", "exp-a@test.com");
        activarPro(a); // los cobros son función PRO; el export en sí es para todos los planes
        String b = registrar("Taller Export B", "exp-b@test.com");

        // Datos del taller A: orden con cobro parcial
        JsonNode ingreso = node(authPost("/api/reparaciones/ingreso-rapido", a, json(Map.of(
                "clienteNombre", "ClienteExport", "clienteTelefono", "7301",
                "equipoMarca", "Samsung", "equipoModelo", "A54",
                "descripcionProblema", "pantalla rota", "precioEstimado", 80000)))
                .andExpect(status().isCreated()));
        long repId = ingreso.at("/reparacion/id").asLong();
        String numeroOrden = ingreso.at("/reparacion/numeroOrden").asText();
        authPost("/api/reparaciones/" + repId + "/cobros", a,
                json(Map.of(
                        "monto", 30000,
                        "metodo", "EFECTIVO",
                        "referencia", "EXP-30000",
                        "observaciones", "Nota interna")))
                .andExpect(status().isCreated());

        // Segunda orden del mismo taller con un sobrepago histórico para verificar
        // que el export lo marca sin convertir EstadoPago en un enum incompatible.
        JsonNode ingresoLegacy = node(authPost("/api/reparaciones/ingreso-rapido", a, json(Map.of(
                "clienteNombre", "ClienteLegacy", "clienteTelefono", "7303",
                "equipoMarca", "Nokia", "equipoModelo", "G50",
                "descripcionProblema", "batería", "precioEstimado", 20000)))
                .andExpect(status().isCreated()));
        long repLegacyId = ingresoLegacy.at("/reparacion/id").asLong();
        String numeroOrdenLegacy = ingresoLegacy.at("/reparacion/numeroOrden").asText();
        authPost("/api/reparaciones/" + repLegacyId + "/cobros", a,
                json(Map.of(
                        "monto", 20000,
                        "metodo", "TRANSFERENCIA",
                        "referencia", "EXP-LEGACY")))
                .andExpect(status().isCreated());
        jdbcTemplate.update("UPDATE reparaciones SET precio_estimado = 10000 WHERE id = ?", repLegacyId);

        // Tercera orden: el movimiento anulado permanece en Cobros pero deja de
        // participar en las sumas de la hoja Órdenes.
        JsonNode ingresoAnulado = node(authPost("/api/reparaciones/ingreso-rapido", a, json(Map.of(
                "clienteNombre", "ClienteAnulado", "clienteTelefono", "7304",
                "equipoMarca", "Motorola", "equipoModelo", "G84",
                "descripcionProblema", "conector", "precioEstimado", 15000)))
                .andExpect(status().isCreated()));
        long repAnuladoId = ingresoAnulado.at("/reparacion/id").asLong();
        String numeroOrdenAnulada = ingresoAnulado.at("/reparacion/numeroOrden").asText();
        long cobroAnuladoId = idOf(authPost("/api/reparaciones/" + repAnuladoId + "/cobros", a,
                json(Map.of(
                        "monto", 5000,
                        "metodo", "TARJETA",
                        "referencia", "EXP-ANULADO",
                        "observaciones", "Nota anulada")))
                .andExpect(status().isCreated()));
        authPost("/api/reparaciones/" + repAnuladoId + "/cobros/" + cobroAnuladoId + "/anulacion",
                a, json(Map.of("motivo", "Error de caja")))
                .andExpect(status().isOk());

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

            Sheet hojaOrdenes = wb.getSheet("Órdenes");
            assertThat(textoDe(hojaOrdenes))
                    .contains("Pendiente de cobro", "Excedente", "Requiere revisión");

            Row ordenParcial = filaPorValor(hojaOrdenes, 0, numeroOrden);
            assertThat(ordenParcial.getCell(11).getNumericCellValue()).isEqualTo(50000);
            assertThat(ordenParcial.getCell(12).getNumericCellValue()).isEqualTo(0);
            assertThat(valor(ordenParcial, 13)).isEqualTo("No");
            assertThat(valor(ordenParcial, 14)).isEqualTo("PARCIAL");

            Row ordenLegacy = filaPorValor(hojaOrdenes, 0, numeroOrdenLegacy);
            assertThat(ordenLegacy.getCell(11).getNumericCellValue()).isEqualTo(0);
            assertThat(ordenLegacy.getCell(12).getNumericCellValue()).isEqualTo(10000);
            assertThat(valor(ordenLegacy, 13)).isEqualTo("Sí");
            assertThat(valor(ordenLegacy, 14)).isEqualTo("PAGADO");

            Row ordenAnulada = filaPorValor(hojaOrdenes, 0, numeroOrdenAnulada);
            assertThat(ordenAnulada.getCell(10).getNumericCellValue()).isEqualTo(0);
            assertThat(ordenAnulada.getCell(11).getNumericCellValue()).isEqualTo(15000);
            assertThat(valor(ordenAnulada, 14)).isEqualTo("SIN_COBRAR");

            Sheet hojaCobros = wb.getSheet("Cobros");
            String cobros = textoDe(hojaCobros);
            assertThat(cobros)
                    .contains(
                            "Referencia", "Estado", "Anulado el", "Anulado por", "Motivo de anulación",
                            "EXP-30000", "EXP-LEGACY", "EXP-ANULADO",
                            "EFECTIVO", "TRANSFERENCIA", "TARJETA")
                    .doesNotContain("ClienteAjeno");

            Row cobroActivo = filaPorValor(hojaCobros, 1, numeroOrden);
            assertThat(valor(cobroActivo, 5)).isEqualTo("ACTIVO");
            assertThat(valor(cobroActivo, 6)).isEmpty();
            assertThat(valor(cobroActivo, 7)).isEmpty();
            assertThat(valor(cobroActivo, 8)).isEmpty();

            Row cobroAnulado = filaPorValor(hojaCobros, 1, numeroOrdenAnulada);
            assertThat(valor(cobroAnulado, 4)).isEqualTo("EXP-ANULADO");
            assertThat(valor(cobroAnulado, 5)).isEqualTo("ANULADO");
            assertThat(valor(cobroAnulado, 6)).isNotBlank();
            assertThat(valor(cobroAnulado, 7)).isEqualTo("Admin");
            assertThat(valor(cobroAnulado, 8)).isEqualTo("Error de caja");
            assertThat(valor(cobroAnulado, 9)).isEqualTo("Nota anulada");
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

    private Row filaPorValor(Sheet hoja, int columna, String esperado) {
        for (Row fila : hoja) {
            if (esperado.equals(valor(fila, columna))) {
                return fila;
            }
        }
        throw new AssertionError("No se encontró la fila con valor " + esperado);
    }

    private String valor(Row fila, int columna) {
        Cell celda = fila.getCell(columna);
        return celda == null ? "" : new DataFormatter().formatCellValue(celda);
    }
}
