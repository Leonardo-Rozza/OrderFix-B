package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.config.tenant.TenantService;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Cliente;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Cobro;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Equipo;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Presupuesto;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Reparacion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.User;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoPresupuesto;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EquipoTipo;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.MetodoPago;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.ClienteRepository;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.CobroRepository;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.PresupuestoRepository;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.ReparacionRepository;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyList;

class ExportServiceTest {
    private static final long TALLER_ID = 42L;
    private static final LocalDate INGRESO = LocalDate.of(2026, 9, 10);
    private static final LocalDateTime REGISTRO = LocalDateTime.of(2026, 9, 11, 14, 37, 42);
    private final ClienteRepository clientes = mock(ClienteRepository.class);
    private final ReparacionRepository reparaciones = mock(ReparacionRepository.class);
    private final CobroRepository cobros = mock(CobroRepository.class);
    private final PresupuestoRepository presupuestos = mock(PresupuestoRepository.class);
    private final TenantService tenant = mock(TenantService.class);
    private final ExportService servicio = new ExportService(clientes, reparaciones, cobros, presupuestos, tenant);

    @Test
    void tallerVacioConservaCuatroHojasEncabezadosYFiltrosSinFilasArtificiales() throws Exception {
        when(tenant.currentTallerId()).thenReturn(TALLER_ID);
        when(clientes.findAllByTallerId(TALLER_ID)).thenReturn(List.of());
        when(reparaciones.findAllByTallerId(TALLER_ID)).thenReturn(List.of());
        when(cobros.findAllByTallerIdOrderByIdAsc(TALLER_ID)).thenReturn(List.of());
        when(presupuestos.findAllByTallerIdOrderByIdAsc(TALLER_ID)).thenReturn(List.of());

        try (XSSFWorkbook libro = abrir(servicio.exportarExcel())) {
            assertThat(libro.getNumberOfSheets()).isEqualTo(4);
            comprobarHoja(libro.getSheet("Clientes"), 0, 6, "A1:F1");
            comprobarHoja(libro.getSheet("Órdenes"), 0, 19, "A1:S1");
            comprobarHoja(libro.getSheet("Cobros"), 0, 10, "A1:J1");
            comprobarHoja(libro.getSheet("Presupuestos"), 0, 7, "A1:G1");
            assertThat(libro.getSheetName(0)).isEqualTo("Clientes");
            assertThat(libro.getSheetName(1)).isEqualTo("Órdenes");
            assertThat(libro.getSheetName(2)).isEqualTo("Cobros");
            assertThat(libro.getSheetName(3)).isEqualTo("Presupuestos");
        }
        verify(cobros, never()).sumByReparacionIds(anyList());
        verificarConsultasDelTaller();
    }

    @Test
    void exportaTiposUtilizablesYTextoLiteralSinPerderFechasNiHoras() throws Exception {
        configurarDatosSinteticos();
        byte[] archivo = servicio.exportarExcel();

        // Opt-in para revisar el diseño con datos ficticios; el test normal no deja archivos.
        String muestra = System.getProperty("ordenfix.export.sample");
        if (muestra != null && !muestra.isBlank()) {
            Files.write(Path.of(muestra), archivo);
        }

        try (XSSFWorkbook libro = abrir(archivo)) {
            assertThat(libro.getNumberOfSheets()).isEqualTo(4);
            comprobarHoja(libro.getSheet("Clientes"), 3, 6, "A1:F4");
            comprobarHoja(libro.getSheet("Órdenes"), 3, 19, "A1:S4");
            comprobarHoja(libro.getSheet("Cobros"), 2, 10, "A1:J3");
            comprobarHoja(libro.getSheet("Presupuestos"), 2, 7, "A1:G3");

            Row cliente = libro.getSheet("Clientes").getRow(1);
            comprobarNumero(cliente.getCell(0), 101);
            comprobarTexto(cliente.getCell(1), "=Cliente de prueba");
            comprobarTexto(cliente.getCell(3), "001155001122");
            comprobarTexto(cliente.getCell(5), "+Dirección sintética 123, primer piso");

            Row orden = libro.getSheet("Órdenes").getRow(1);
            comprobarTexto(orden.getCell(0), "00000042");
            comprobarFecha(orden.getCell(1), INGRESO.atStartOfDay(), "dd/MM/yyyy");
            comprobarTexto(orden.getCell(3), "001155001122");
            comprobarTexto(libro.getSheet("Órdenes").getRow(0).getCell(5), "Tipo de equipo");
            comprobarTexto(libro.getSheet("Órdenes").getRow(0).getCell(6), "Serie / IMEI");
            comprobarTexto(orden.getCell(5), "Celular");
            comprobarTexto(orden.getCell(6), "000123456789012");
            comprobarTexto(libro.getSheet("Órdenes").getRow(2).getCell(5), "Notebook");
            comprobarTexto(libro.getSheet("Órdenes").getRow(3).getCell(5), "Consola");
            comprobarTexto(orden.getCell(7), "=SUM(1,2)\nPantalla sin imagen; conservar el texto original.");
            comprobarImporte(orden.getCell(10), 80250.75);
            comprobarImporte(orden.getCell(11), 30000.25);
            comprobarImporte(orden.getCell(12), 50250.50);
            comprobarImporte(orden.getCell(13), 0);
            comprobarFecha(orden.getCell(16), INGRESO.plusDays(2).atStartOfDay(), "dd/MM/yyyy");
            comprobarFecha(orden.getCell(17), INGRESO.plusDays(32).atStartOfDay(), "dd/MM/yyyy");
            comprobarTexto(orden.getCell(18), "SYNTHETIC-001");

            Row cobro = libro.getSheet("Cobros").getRow(1);
            comprobarFecha(cobro.getCell(0), REGISTRO, "dd/MM/yyyy HH:mm");
            comprobarTexto(cobro.getCell(1), "00000042");
            comprobarImporte(cobro.getCell(2), 30000.25);
            comprobarTexto(cobro.getCell(4), "@referencia sintética");
            comprobarTexto(cobro.getCell(9), "-Nota de control manual, pago recibido fuera de OrdenFix");
            Row anulado = libro.getSheet("Cobros").getRow(2);
            comprobarFecha(anulado.getCell(6), REGISTRO.plusHours(2), "dd/MM/yyyy HH:mm");
            comprobarTexto(anulado.getCell(5), "ANULADO");
            comprobarTexto(anulado.getCell(7), "Titular de prueba");

            Row presupuesto = libro.getSheet("Presupuestos").getRow(1);
            comprobarFecha(presupuesto.getCell(0), REGISTRO.minusDays(1), "dd/MM/yyyy HH:mm");
            comprobarImporte(presupuesto.getCell(4), 80250.75);
            comprobarFecha(presupuesto.getCell(5), REGISTRO.plusDays(7), "dd/MM/yyyy HH:mm");
            comprobarFecha(presupuesto.getCell(6), REGISTRO.minusHours(1), "dd/MM/yyyy HH:mm");

            for (var hoja : libro) {
                for (Row fila : hoja) {
                    for (Cell celda : fila) {
                        assertThat(celda.getCellType()).isNotEqualTo(CellType.FORMULA);
                        if (celda.getCellType() == CellType.STRING)
                            assertThat(celda.getStringCellValue()).doesNotContain("SYNTHETIC-PIN", "SYNTHETIC-PATTERN");
                    }
                }
            }
        }
        verificarConsultasDelTaller();
        verify(cobros).sumByReparacionIds(List.of(201L, 202L, 203L));
    }

    @Test
    void opcionalesAusentesQuedanVaciosConFormatoLegible() throws Exception {
        configurarDatosSinteticos();
        try (XSSFWorkbook libro = abrir(servicio.exportarExcel())) {
            Row cliente = libro.getSheet("Clientes").getRow(2);
            comprobarVacio(cliente, 4, 5);
            Row orden = libro.getSheet("Órdenes").getRow(2);
            comprobarVacio(orden, 1, 6, 9, 16, 17, 18);
            comprobarTexto(libro.getSheet("Órdenes").getRow(3).getCell(0), "#203");
            comprobarVacio(libro.getSheet("Cobros").getRow(1), 6, 7, 8);
            comprobarVacio(libro.getSheet("Presupuestos").getRow(2), 0, 5, 6);
            assertThat(libro.getSheet("Órdenes").getRow(1).getCell(7).getCellStyle().getWrapText()).isTrue();
            assertThat(libro.getSheet("Clientes").getRow(1).getCell(5).getCellStyle().getWrapText()).isTrue();
        }
    }

    private void configurarDatosSinteticos() {
        when(tenant.currentTallerId()).thenReturn(TALLER_ID);
        Cliente ana = Cliente.builder().id(101L).nombre("=Cliente de prueba").apellido("Pérez")
                .telefono("001155001122").email("ana@synthetic.invalid")
                .direccion("+Dirección sintética 123, primer piso").build();
        Cliente bruno = Cliente.builder().id(102L).nombre("Bruno").apellido("Gómez")
                .telefono("+5491155003344").build();
        Cliente carla = Cliente.builder().id(103L).nombre("Carla").apellido("Fernández")
                .telefono("001155005566").email("carla@synthetic.invalid")
                .direccion("Avenida de prueba 456, departamento 12. Entrada por el pasillo lateral.").build();
        Reparacion primera = orden(201L, "00000042", ana, "Samsung", "Galaxy A54", new BigDecimal("80250.75"));
        primera.getEquipo().setTipo(EquipoTipo.CELULAR);
        primera.getEquipo().setImei("000123456789012");
        primera.setPinDesbloqueoCifrado("SYNTHETIC-PIN");
        primera.setPatronDesbloqueoCifrado("SYNTHETIC-PATTERN");
        primera.setFechaIngreso(INGRESO);
        primera.setFechaEntrega(INGRESO.plusDays(2));
        primera.setGarantiaFin(INGRESO.plusDays(32));
        primera.setTecnico(User.builder().username("Técnica de prueba").build());
        primera.setCodigoSeguimiento("SYNTHETIC-001");
        primera.setDescripcionProblema("=SUM(1,2)\nPantalla sin imagen; conservar el texto original.");
        Reparacion segunda = orden(202L, "ORD-2026-0043", bruno, "Lenovo", "ThinkPad", new BigDecimal("15000.00"));
        Reparacion tercera = orden(203L, null, carla, "Sony", "PlayStation 5", BigDecimal.ZERO);
        segunda.getEquipo().setTipo(EquipoTipo.NOTEBOOK);
        tercera.getEquipo().setTipo(EquipoTipo.CONSOLA);
        tercera.setFechaIngreso(INGRESO.plusDays(1));
        tercera.setDescripcionProblema("No enciende. Diagnóstico pendiente y revisión de conector de carga.");
        Cobro activo = Cobro.builder().id(301L).reparacion(primera).monto(new BigDecimal("30000.25"))
                .metodo(MetodoPago.TRANSFERENCIA).createdAt(REGISTRO).referencia("@referencia sintética")
                .observaciones("-Nota de control manual, pago recibido fuera de OrdenFix").build();
        Cobro anulado = Cobro.builder().id(302L).reparacion(segunda).monto(new BigDecimal("5000.50"))
                .metodo(MetodoPago.EFECTIVO).createdAt(REGISTRO.minusHours(3)).referencia("SYNTHETIC-ANULADO")
                .observaciones("Registro conservado en el historial").build();
        anulado.anular(REGISTRO.plusHours(2), User.builder().username("Titular de prueba").build(), "Carga duplicada de prueba");
        Presupuesto aprobado = Presupuesto.builder().id(401L).reparacion(primera).estado(EstadoPresupuesto.APROBADO)
                .total(new BigDecimal("80250.75")).createdAt(REGISTRO.minusDays(1))
                .validoHasta(REGISTRO.plusDays(7)).fechaRespuesta(REGISTRO.minusHours(1)).build();
        Presupuesto pendiente = Presupuesto.builder().id(402L).reparacion(tercera).total(BigDecimal.ZERO).build();
        when(clientes.findAllByTallerId(TALLER_ID)).thenReturn(List.of(ana, bruno, carla));
        when(reparaciones.findAllByTallerId(TALLER_ID)).thenReturn(List.of(primera, segunda, tercera));
        when(cobros.findAllByTallerIdOrderByIdAsc(TALLER_ID)).thenReturn(List.of(activo, anulado));
        when(presupuestos.findAllByTallerIdOrderByIdAsc(TALLER_ID)).thenReturn(List.of(aprobado, pendiente));
        when(cobros.sumByReparacionIds(List.of(201L, 202L, 203L)))
                .thenReturn(List.<Object[]>of(new Object[]{201L, new BigDecimal("30000.25")}));
    }

    private Reparacion orden(long id, String numero, Cliente cliente, String marca, String modelo, BigDecimal total) {
        Equipo equipo = Equipo.builder().cliente(cliente).marca(marca).modelo(modelo).build();
        return Reparacion.builder().id(id).numeroOrden(numero).equipo(equipo)
                .precioEstimado(total).descripcionProblema("Revisión de equipo").build();
    }

    private XSSFWorkbook abrir(byte[] bytes) throws Exception {
        return new XSSFWorkbook(new ByteArrayInputStream(bytes));
    }

    private void comprobarHoja(XSSFSheet hoja, int filasDatos, int columnas, String filtro) {
        assertThat(hoja).isNotNull();
        assertThat(hoja.getPhysicalNumberOfRows()).isEqualTo(filasDatos + 1);
        assertThat(hoja.getRow(0).getLastCellNum()).isEqualTo((short) columnas);
        assertThat(hoja.getCTWorksheet().isSetAutoFilter()).isTrue();
        assertThat(hoja.getCTWorksheet().getAutoFilter().getRef()).isEqualTo(filtro);
        assertThat(hoja.getPaneInformation()).isNotNull();
        assertThat(hoja.getPaneInformation().isFreezePane()).isTrue();
        assertThat(hoja.getPaneInformation().getHorizontalSplitPosition()).isEqualTo((short) 1);
        for (int col = 0; col < columnas; col++) {
            assertThat(hoja.getRow(0).getCell(col).getCellType()).isEqualTo(CellType.STRING);
            assertThat(hoja.getColumnWidth(col)).isBetween(8 * 256, 60 * 256);
        }
    }

    private void comprobarTexto(Cell celda, String esperado) {
        assertThat(celda.getCellType()).isEqualTo(CellType.STRING);
        assertThat(celda.getStringCellValue()).isEqualTo(esperado);
    }

    private void comprobarNumero(Cell celda, double esperado) {
        assertThat(celda.getCellType()).isEqualTo(CellType.NUMERIC);
        assertThat(celda.getNumericCellValue()).isEqualTo(esperado);
    }

    private void comprobarImporte(Cell celda, double esperado) {
        comprobarNumero(celda, esperado);
        assertThat(celda.getCellStyle().getDataFormatString()).contains("ARS", "0.00");
    }

    private void comprobarFecha(Cell celda, LocalDateTime esperada, String formato) {
        assertThat(celda.getCellType()).isEqualTo(CellType.NUMERIC);
        assertThat(DateUtil.isCellDateFormatted(celda)).isTrue();
        assertThat(celda.getLocalDateTimeCellValue()).isEqualTo(esperada);
        assertThat(celda.getCellStyle().getDataFormatString()).isEqualToIgnoringCase(formato);
    }

    private void comprobarVacio(Row fila, int... columnas) {
        for (int col : columnas) {
            Cell celda = fila.getCell(col);
            assertThat(celda == null || celda.getCellType() == CellType.BLANK).isTrue();
        }
    }

    private void verificarConsultasDelTaller() {
        verify(clientes).findAllByTallerId(TALLER_ID);
        verify(reparaciones).findAllByTallerId(TALLER_ID);
        verify(cobros).findAllByTallerIdOrderByIdAsc(TALLER_ID);
        verify(presupuestos).findAllByTallerIdOrderByIdAsc(TALLER_ID);
    }
}
