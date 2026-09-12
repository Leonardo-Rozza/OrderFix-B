package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.config.tenant.TenantService;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Cliente;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Cobro;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Presupuesto;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Reparacion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoPago;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.ClienteRepository;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.CobroRepository;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.PresupuestoRepository;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.ReparacionRepository;
import com.leonardorozza.mvgrreparacionesbackend.service.finanzas.EstadoCuentaOrden;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reporte operativo del taller actual: Clientes, Órdenes, Cobros y Presupuestos.
 * Disponible para su titular en cualquier plan. No es una exportación integral
 * ni un comprobante fiscal; los cobros describen registros manuales del taller.
 */
@Service
@RequiredArgsConstructor
public class ExportService {

    private static final String ALCANCE = "Reporte operativo de clientes, órdenes, cobros y presupuestos. "
            + "No incluye todas las categorías de datos ni archivos del taller. "
            + "Los cobros son registros manuales de pagos recibidos fuera de OrdenFix; "
            + "no acreditan pagos verificados por la app. Documento informativo, sin validez fiscal.";

    private final ClienteRepository clienteRepository;
    private final ReparacionRepository reparacionRepository;
    private final CobroRepository cobroRepository;
    private final PresupuestoRepository presupuestoRepository;
    private final TenantService tenantService;

    @Transactional(readOnly = true)
    public byte[] exportarExcel() {
        Long tallerId = tenantService.currentTallerId();

        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Estilos estilos = new Estilos(wb);
            wb.getProperties().getCoreProperties().setTitle("OrdenFix — reporte operativo");
            wb.getProperties().getCoreProperties().setDescription(ALCANCE);

            hojaClientes(wb, estilos, clienteRepository.findAllByTallerId(tallerId));
            hojaOrdenes(wb, estilos, reparacionRepository.findAllByTallerId(tallerId));
            hojaCobros(wb, estilos, cobroRepository.findAllByTallerIdOrderByIdAsc(tallerId));
            hojaPresupuestos(wb, estilos, presupuestoRepository.findAllByTallerIdOrderByIdAsc(tallerId));

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo generar el Excel de exportación", e);
        }
    }

    private void hojaClientes(Workbook wb, Estilos estilos, List<Cliente> clientes) {
        Sheet hoja = nuevaHoja(wb, "Clientes", estilos,
                "ID", "Nombre", "Apellido", "Teléfono", "Email", "Dirección");
        int fila = 1;
        for (Cliente c : clientes) {
            Row r = hoja.createRow(fila++);
            numero(r, 0, c.getId());
            texto(r, 1, c.getNombre());
            texto(r, 2, c.getApellido());
            texto(r, 3, c.getTelefono());
            texto(r, 4, c.getEmail());
            texto(r, 5, c.getDireccion());
        }
        terminarHoja(hoja, estilos,
                col(10, Formato.ENTERO), col(24), col(24), col(23), col(36), col(44));
    }

    private void hojaOrdenes(Workbook wb, Estilos estilos, List<Reparacion> reparaciones) {
        Sheet hoja = nuevaHoja(wb, "Órdenes", estilos,
                "N° orden", "Fecha ingreso", "Cliente", "Teléfono", "Equipo", "IMEI",
                "Problema", "Estado", "Técnico", "Total", "Cobrado", "Pendiente de cobro",
                "Excedente", "Requiere revisión", "Estado de pago", "Fecha entrega",
                "Garantía hasta", "Código seguimiento");

        Map<Long, BigDecimal> cobradoPorOrden = cobradoPorOrden(reparaciones);

        int fila = 1;
        for (Reparacion rep : reparaciones) {
            Cliente cliente = rep.getEquipo().getCliente();
            BigDecimal cobrado = cobradoPorOrden.getOrDefault(rep.getId(), BigDecimal.ZERO);
            EstadoCuentaOrden cuenta = EstadoCuentaOrden.de(rep.calcularTotal(), cobrado);

            Row r = hoja.createRow(fila++);
            texto(r, 0, rep.getNumeroOrden() != null ? rep.getNumeroOrden() : "#" + rep.getId());
            fecha(r, 1, rep.getFechaIngreso());
            texto(r, 2, cliente.getNombre() + " " + cliente.getApellido());
            texto(r, 3, cliente.getTelefono());
            texto(r, 4, rep.getEquipo().getMarca() + " " + rep.getEquipo().getModelo());
            texto(r, 5, rep.getEquipo().getImei());
            texto(r, 6, rep.getDescripcionProblema());
            texto(r, 7, rep.getEstado().name());
            texto(r, 8, rep.getTecnico() != null ? rep.getTecnico().getUsername() : null);
            numero(r, 9, cuenta.total());
            numero(r, 10, cuenta.cobrado());
            numero(r, 11, cuenta.saldo());
            numero(r, 12, cuenta.excedente());
            texto(r, 13, cuenta.requiereRevision() ? "Sí" : "No");
            texto(r, 14, EstadoPago.de(cuenta.total(), cuenta.cobrado()).name());
            fecha(r, 15, rep.getFechaEntrega());
            fecha(r, 16, rep.getGarantiaFin());
            texto(r, 17, rep.getCodigoSeguimiento());
        }
        terminarHoja(hoja, estilos,
                col(19), col(17, Formato.FECHA), col(30), col(23), col(28), col(23),
                col(48), col(24), col(23), col(22, Formato.MONEDA), col(22, Formato.MONEDA),
                col(22, Formato.MONEDA), col(22, Formato.MONEDA), col(19), col(23),
                col(17, Formato.FECHA), col(17, Formato.FECHA), col(28));
    }

    private void hojaCobros(Workbook wb, Estilos estilos, List<Cobro> cobros) {
        Sheet hoja = nuevaHoja(wb, "Cobros", estilos,
                "Fecha", "N° orden", "Monto", "Método", "Referencia", "Estado",
                "Anulado el", "Anulado por", "Motivo de anulación", "Observaciones");
        int fila = 1;
        for (Cobro c : cobros) {
            Row r = hoja.createRow(fila++);
            fechaHora(r, 0, c.getCreatedAt());
            texto(r, 1, numeroOrden(c.getReparacion()));
            numero(r, 2, c.getMonto());
            texto(r, 3, c.getMetodo().name());
            texto(r, 4, c.getReferencia());
            texto(r, 5, c.estaAnulado() ? "ANULADO" : "ACTIVO");
            fechaHora(r, 6, c.getAnuladoAt());
            texto(r, 7, c.getAnuladoPor() != null ? c.getAnuladoPor().getUsername() : null);
            texto(r, 8, c.getMotivoAnulacion());
            texto(r, 9, c.getObservaciones());
        }
        terminarHoja(hoja, estilos,
                col(23, Formato.FECHA_HORA), col(19), col(22, Formato.MONEDA), col(23),
                col(30), col(17), col(23, Formato.FECHA_HORA), col(23), col(44), col(48));
    }

    private void hojaPresupuestos(Workbook wb, Estilos estilos, List<Presupuesto> presupuestos) {
        Sheet hoja = nuevaHoja(wb, "Presupuestos", estilos,
                "Fecha", "N° orden", "Tipo", "Estado", "Total", "Válido hasta", "Respondido");
        int fila = 1;
        for (Presupuesto p : presupuestos) {
            Row r = hoja.createRow(fila++);
            fechaHora(r, 0, p.getCreatedAt());
            texto(r, 1, numeroOrden(p.getReparacion()));
            texto(r, 2, p.getTipo().name());
            texto(r, 3, p.getEstadoEfectivo().name());
            numero(r, 4, p.getTotal());
            fechaHora(r, 5, p.getValidoHasta());
            fechaHora(r, 6, p.getFechaRespuesta());
        }
        terminarHoja(hoja, estilos,
                col(23, Formato.FECHA_HORA), col(19), col(24), col(24), col(22, Formato.MONEDA),
                col(23, Formato.FECHA_HORA), col(23, Formato.FECHA_HORA));
    }

    // ---- helpers ----

    /** Cobrado agrupado por orden en una sola query (evita N+1). */
    private Map<Long, BigDecimal> cobradoPorOrden(List<Reparacion> reparaciones) {
        Map<Long, BigDecimal> resultado = new HashMap<>();
        List<Long> ids = reparaciones.stream().map(Reparacion::getId).toList();
        if (!ids.isEmpty()) {
            for (Object[] par : cobroRepository.sumByReparacionIds(ids)) {
                resultado.put((Long) par[0], (BigDecimal) par[1]);
            }
        }
        return resultado;
    }

    private Sheet nuevaHoja(Workbook wb, String nombre, Estilos estilos, String... columnas) {
        Sheet hoja = wb.createSheet(nombre);
        hoja.createFreezePane(0, 1);
        hoja.setDisplayGridlines(false);
        hoja.setPrintGridlines(false);
        hoja.setRepeatingRows(new CellRangeAddress(0, 0, -1, -1));
        hoja.getPrintSetup().setLandscape(true);
        hoja.getPrintSetup().setPaperSize(PrintSetup.A4_PAPERSIZE);
        hoja.getHeader().setLeft("&BOrdenFix · " + nombre);
        hoja.getFooter().setCenter("&8Informativo, sin validez fiscal. Cobros registrados por el taller.");
        hoja.getFooter().setRight("&8&P / &N");
        ((XSSFSheet) hoja).setTabColor(color("0B6F66"));
        Row header = hoja.createRow(0);
        header.setHeightInPoints(36);
        for (int i = 0; i < columnas.length; i++) {
            Cell celda = header.createCell(i);
            celda.setCellValue(columnas[i]);
            celda.setCellStyle(estilos.encabezado);
        }

        // El alcance viaja con el archivo, sin desplazar la tabla ni sus filtros.
        CreationHelper helper = wb.getCreationHelper();
        ClientAnchor anchor = helper.createClientAnchor();
        anchor.setCol1(0);
        anchor.setCol2(5);
        anchor.setRow1(1);
        anchor.setRow2(8);
        Comment nota = hoja.createDrawingPatriarch().createCellComment(anchor);
        nota.setAuthor("OrdenFix");
        nota.setString(helper.createRichTextString(ALCANCE));
        header.getCell(0).setCellComment(nota);
        return hoja;
    }

    private enum Formato {
        TEXTO("@"), ENTERO("0"), MONEDA("\"ARS\" #,##0.00;[Red]\"ARS\" -#,##0.00"),
        FECHA("dd/mm/yyyy"), FECHA_HORA("dd/mm/yyyy hh:mm");

        private final String patron;

        Formato(String patron) {
            this.patron = patron;
        }
    }

    private record Columna(int ancho, Formato formato) {}

    private static Columna col(int ancho) {
        return col(ancho, Formato.TEXTO);
    }

    private static Columna col(int ancho, Formato formato) {
        return new Columna(ancho, formato);
    }

    /** Cantidad fija de estilos por libro; nunca se crea uno por celda o fila. */
    private static final class Estilos {
        private final CellStyle encabezado;
        private final Map<Formato, CellStyle[]> cuerpo = new EnumMap<>(Formato.class);

        private Estilos(XSSFWorkbook wb) {
            XSSFFont fuente = wb.createFont();
            fuente.setFontName("Arial");
            fuente.setFontHeightInPoints((short) 11);
            fuente.setColor(color("0E1726"));
            XSSFFont titulo = wb.createFont();
            titulo.setFontName("Arial");
            titulo.setFontHeightInPoints((short) 11);
            titulo.setBold(true);
            titulo.setColor(color("FFFFFF"));
            XSSFCellStyle header = wb.createCellStyle();
            header.setFont(titulo);
            header.setFillForegroundColor(color("0B6F66"));
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setVerticalAlignment(VerticalAlignment.CENTER);
            header.setAlignment(HorizontalAlignment.LEFT);
            header.setIndention((short) 1);
            header.setWrapText(true);
            encabezado = header;

            DataFormat formatos = wb.createDataFormat();
            for (Formato formato : Formato.values()) {
                CellStyle[] filas = new CellStyle[2];
                for (int banda = 0; banda < filas.length; banda++) {
                    XSSFCellStyle estilo = wb.createCellStyle();
                    estilo.setFont(fuente);
                    estilo.setDataFormat(formatos.getFormat(formato.patron));
                    estilo.setFillForegroundColor(color(banda == 0 ? "FFFFFF" : "F6F8FB"));
                    estilo.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                    estilo.setVerticalAlignment(VerticalAlignment.TOP);
                    estilo.setAlignment(formato == Formato.MONEDA || formato == Formato.ENTERO
                            ? HorizontalAlignment.RIGHT : HorizontalAlignment.LEFT);
                    estilo.setIndention((short) 1);
                    estilo.setWrapText(formato == Formato.TEXTO);
                    filas[banda] = estilo;
                }
                cuerpo.put(formato, filas);
            }
        }

        private CellStyle celda(Formato formato, int fila) {
            return cuerpo.get(formato)[(fila - 1) % 2];
        }
    }

    private static XSSFColor color(String rgb) {
        return new XSSFColor(java.util.HexFormat.of().parseHex(rgb), null);
    }

    private void terminarHoja(Sheet hoja, Estilos estilos, Columna... columnas) {
        hoja.setAutoFilter(new CellRangeAddress(0, hoja.getLastRowNum(), 0, columnas.length - 1));
        for (int i = 0; i < columnas.length; i++) {
            hoja.setColumnWidth(i, columnas[i].ancho() * 256);
        }
        for (int fila = 1; fila <= hoja.getLastRowNum(); fila++) {
            Row row = hoja.getRow(fila);
            int lineas = 1;
            for (int i = 0; i < columnas.length; i++) {
                Cell cell = row.getCell(i, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                cell.setCellStyle(estilos.celda(columnas[i].formato(), fila));
                if (cell.getCellType() == CellType.STRING) {
                    lineas = Math.max(lineas, lineasTexto(cell.getStringCellValue(), columnas[i].ancho()));
                }
            }
            // POI no calcula el alto de texto envuelto. Se reserva aire y se respeta
            // el máximo de Excel; el valor completo sigue disponible en la celda.
            row.setHeightInPoints(Math.min(409, Math.max(30, lineas * 15 + 10)));
        }
    }

    private int lineasTexto(String texto, int ancho) {
        int lineas = 0;
        int disponibles = Math.max(1, ancho - 3);
        for (String linea : texto.split("\\R", -1)) {
            // Margen para palabras completas y caracteres más anchos que el promedio.
            lineas += Math.max(1, (int) Math.ceil(linea.length() / (disponibles * 0.85)));
        }
        return lineas;
    }

    private void texto(Row fila, int col, String valor) {
        if (valor != null && !valor.isBlank()) {
            fila.createCell(col).setCellValue(valor);
        }
    }

    private void numero(Row fila, int col, Number valor) {
        if (valor != null) {
            fila.createCell(col).setCellValue(valor.doubleValue());
        }
    }

    private String numeroOrden(Reparacion rep) {
        return rep.getNumeroOrden() != null ? rep.getNumeroOrden() : "#" + rep.getId();
    }

    private void fecha(Row fila, int col, LocalDate valor) {
        if (valor != null) {
            fila.createCell(col).setCellValue(valor);
        }
    }

    private void fechaHora(Row fila, int col, LocalDateTime valor) {
        if (valor != null) {
            fila.createCell(col).setCellValue(valor);
        }
    }
}
