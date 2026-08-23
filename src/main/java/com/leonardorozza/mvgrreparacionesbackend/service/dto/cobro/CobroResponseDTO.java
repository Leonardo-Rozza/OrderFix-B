package com.leonardorozza.mvgrreparacionesbackend.service.dto.cobro;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.MetodoPago;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoCobro;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record CobroResponseDTO(
        Long id,
        Long reparacionId,
        BigDecimal monto,
        MetodoPago metodo,
        String referencia,
        String observaciones,
        LocalDateTime fecha,
        EstadoCobro estado,
        LocalDateTime anuladoAt,
        String anuladoPorNombre,
        String motivoAnulacion
) {
}
