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
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Exporta todos los datos del taller actual a un Excel (.xlsx) con una hoja por
 * recurso: Clientes, Órdenes, Cobros y Presupuestos. Portabilidad de datos:
 * cada taller se lleva lo suyo (y solo lo suyo), en cualquier plan.
 */
@Service
@RequiredArgsConstructor
public class ExportService {

    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter FECHA_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final ClienteRepository clienteRepository;
    private final ReparacionRepository reparacionRepository;
    private final CobroRepository cobroRepository;
    private final PresupuestoRepository presupuestoRepository;
    private final TenantService tenantService;

    @Transactional(readOnly = true)
    public byte[] exportarExcel() {
        Long tallerId = tenantService.currentTallerId();

        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            CellStyle encabezado = estiloEncabezado(wb);

            hojaClientes(wb, encabezado, clienteRepository.findAllByTallerId(tallerId));
            hojaOrdenes(wb, encabezado, reparacionRepository.findAllByTallerId(tallerId));
            hojaCobros(wb, encabezado, cobroRepository.findAllByTallerIdOrderByIdAsc(tallerId));
            hojaPresupuestos(wb, encabezado, presupuestoRepository.findAllByTallerIdOrderByIdAsc(tallerId));

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo generar el Excel de exportación", e);
        }
    }

    private void hojaClientes(Workbook wb, CellStyle encabezado, List<Cliente> clientes) {
        Sheet hoja = nuevaHoja(wb, "Clientes", encabezado,
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
        autoajustar(hoja, 6);
    }

    private void hojaOrdenes(Workbook wb, CellStyle encabezado, List<Reparacion> reparaciones) {
        Sheet hoja = nuevaHoja(wb, "Órdenes", encabezado,
                "N° orden", "Fecha ingreso", "Cliente", "Teléfono", "Equipo", "IMEI",
                "Problema", "Estado", "Técnico", "Total", "Cobrado", "Saldo",
                "Estado de pago", "Fecha entrega", "Garantía hasta", "Código seguimiento");

        Map<Long, BigDecimal> cobradoPorOrden = cobradoPorOrden(reparaciones);

        int fila = 1;
        for (Reparacion rep : reparaciones) {
            Cliente cliente = rep.getEquipo().getCliente();
            BigDecimal total = rep.calcularTotal();
            BigDecimal cobrado = cobradoPorOrden.getOrDefault(rep.getId(), BigDecimal.ZERO);
            BigDecimal saldo = total.subtract(cobrado).max(BigDecimal.ZERO);

            Row r = hoja.createRow(fila++);
            texto(r, 0, rep.getNumeroOrden() != null ? rep.getNumeroOrden() : "#" + rep.getId());
            texto(r, 1, fecha(rep.getFechaIngreso()));
            texto(r, 2, cliente.getNombre() + " " + cliente.getApellido());
            texto(r, 3, cliente.getTelefono());
            texto(r, 4, rep.getEquipo().getMarca() + " " + rep.getEquipo().getModelo());
            texto(r, 5, rep.getEquipo().getImei());
            texto(r, 6, rep.getDescripcionProblema());
            texto(r, 7, rep.getEstado().name());
            texto(r, 8, rep.getTecnico() != null ? rep.getTecnico().getUsername() : null);
            numero(r, 9, total);
            numero(r, 10, cobrado);
            numero(r, 11, saldo);
            texto(r, 12, EstadoPago.de(total, cobrado).name());
            texto(r, 13, fecha(rep.getFechaEntrega()));
            texto(r, 14, fecha(rep.getGarantiaFin()));
            texto(r, 15, rep.getCodigoSeguimiento());
        }
        autoajustar(hoja, 16);
    }

    private void hojaCobros(Workbook wb, CellStyle encabezado, List<Cobro> cobros) {
        Sheet hoja = nuevaHoja(wb, "Cobros", encabezado,
                "Fecha", "N° orden", "Monto", "Método", "Observaciones");
        int fila = 1;
        for (Cobro c : cobros) {
            Row r = hoja.createRow(fila++);
            texto(r, 0, fechaHora(c.getCreatedAt()));
            texto(r, 1, numeroOrden(c.getReparacion()));
            numero(r, 2, c.getMonto());
            texto(r, 3, c.getMetodo().name());
            texto(r, 4, c.getObservaciones());
        }
        autoajustar(hoja, 5);
    }

    private void hojaPresupuestos(Workbook wb, CellStyle encabezado, List<Presupuesto> presupuestos) {
        Sheet hoja = nuevaHoja(wb, "Presupuestos", encabezado,
                "Fecha", "N° orden", "Tipo", "Estado", "Total", "Válido hasta", "Respondido");
        int fila = 1;
        for (Presupuesto p : presupuestos) {
            Row r = hoja.createRow(fila++);
            texto(r, 0, fechaHora(p.getCreatedAt()));
            texto(r, 1, numeroOrden(p.getReparacion()));
            texto(r, 2, p.getTipo().name());
            texto(r, 3, p.getEstadoEfectivo().name());
            numero(r, 4, p.getTotal());
            texto(r, 5, fechaHora(p.getValidoHasta()));
            texto(r, 6, fechaHora(p.getFechaRespuesta()));
        }
        autoajustar(hoja, 7);
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

    private Sheet nuevaHoja(Workbook wb, String nombre, CellStyle estilo, String... columnas) {
        Sheet hoja = wb.createSheet(nombre);
        hoja.createFreezePane(0, 1);
        Row header = hoja.createRow(0);
        for (int i = 0; i < columnas.length; i++) {
            Cell celda = header.createCell(i);
            celda.setCellValue(columnas[i]);
            celda.setCellStyle(estilo);
        }
        return hoja;
    }

    private CellStyle estiloEncabezado(Workbook wb) {
        Font negrita = wb.createFont();
        negrita.setBold(true);
        CellStyle estilo = wb.createCellStyle();
        estilo.setFont(negrita);
        return estilo;
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

    private String fecha(LocalDate valor) {
        return valor != null ? FECHA.format(valor) : null;
    }

    private String fechaHora(LocalDateTime valor) {
        return valor != null ? FECHA_HORA.format(valor) : null;
    }

    private void autoajustar(Sheet hoja, int columnas) {
        for (int i = 0; i < columnas; i++) {
            hoja.autoSizeColumn(i);
        }
    }
}
