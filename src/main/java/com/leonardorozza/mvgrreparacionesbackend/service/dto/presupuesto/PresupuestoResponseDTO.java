package com.leonardorozza.mvgrreparacionesbackend.service.dto.presupuesto;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoPresupuesto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record PresupuestoResponseDTO(
        Long id,
        Long reparacionId,
        EstadoPresupuesto estado,
        List<ItemPresupuestoDTO> items,
        BigDecimal total,
        String observaciones,
        LocalDateTime fechaRespuesta,
        LocalDateTime createdAt
) {
}
