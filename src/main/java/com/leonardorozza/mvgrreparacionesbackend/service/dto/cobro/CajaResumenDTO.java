package com.leonardorozza.mvgrreparacionesbackend.service.dto.cobro;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.MetodoPago;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Resumen de caja de un período: total cobrado, cantidad de cobros, desglose por método.
 */
public record CajaResumenDTO(
        LocalDate desde,
        LocalDate hasta,
        BigDecimal totalCobrado,
        long cantidad,
        Map<MetodoPago, BigDecimal> porMetodo,
        List<CobroResponseDTO> cobros
) {
}
