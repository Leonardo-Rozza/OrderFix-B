package com.leonardorozza.mvgrreparacionesbackend.service.finanzas;

import com.leonardorozza.mvgrreparacionesbackend.exceptions.BadRequestException;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.ConflictException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class CobroInvariantPolicy {

    public void validarNuevoCobro(Long reparacionId, EstadoCuentaOrden estado, BigDecimal monto) {
        if (monto == null || monto.signum() <= 0) {
            throw new BadRequestException("El monto del cobro debe ser mayor que cero.");
        }
        if (estado.requiereRevision() || monto.compareTo(estado.saldo()) > 0) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("reparacionId", reparacionId);
            details.put("total", estado.total());
            details.put("cobrado", estado.cobrado());
            details.put("monto", monto);
            details.put("pendiente", estado.saldo());
            throw new ConflictException(
                    "COBRO_SUPERA_SALDO",
                    "El monto supera el pendiente de cobro.",
                    details);
        }
    }

    public void validarTotalProyectado(
            Long reparacionId, EstadoCuentaOrden actual, BigDecimal totalProyectado) {
        EstadoCuentaOrden proyectado = EstadoCuentaOrden.de(totalProyectado, actual.cobrado());
        if (proyectado.excedente().compareTo(actual.excedente()) > 0) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("reparacionId", reparacionId);
            details.put("totalActual", actual.total());
            details.put("totalPropuesto", proyectado.total());
            details.put("cobrado", actual.cobrado());
            details.put("excedenteActual", actual.excedente());
            details.put("excedentePropuesto", proyectado.excedente());
            throw new ConflictException(
                    "TOTAL_MENOR_QUE_COBRADO",
                    "El nuevo total queda por debajo de lo cobrado. Anulá o corregí primero los cobros correspondientes.",
                    details);
        }
    }
}
