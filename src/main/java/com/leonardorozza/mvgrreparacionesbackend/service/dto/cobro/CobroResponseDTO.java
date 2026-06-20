package com.leonardorozza.mvgrreparacionesbackend.service.dto.cobro;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.MetodoPago;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record CobroResponseDTO(
        Long id,
        Long reparacionId,
        BigDecimal monto,
        MetodoPago metodo,
        String observaciones,
        LocalDateTime fecha
) {
}
