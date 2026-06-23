package com.leonardorozza.mvgrreparacionesbackend.service.dto.presupuesto;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoPresupuesto;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoPresupuesto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record PresupuestoResponseDTO(
        Long id,
        Long reparacionId,
        /** Estado efectivo: VENCIDO si la validez ya pasó, si no el real. */
        EstadoPresupuesto estado,
        TipoPresupuesto tipo,
        List<ItemPresupuestoDTO> items,
        BigDecimal total,
        /** Total discriminado: mano de obra vs repuestos. */
        BigDecimal manoDeObraTotal,
        BigDecimal repuestosTotal,
        Integer validezDias,
        LocalDateTime validoHasta,
        boolean vencido,
        String observaciones,
        LocalDateTime fechaRespuesta,
        LocalDateTime createdAt
) {
}
