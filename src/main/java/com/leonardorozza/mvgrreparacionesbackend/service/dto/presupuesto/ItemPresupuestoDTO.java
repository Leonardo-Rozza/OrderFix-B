package com.leonardorozza.mvgrreparacionesbackend.service.dto.presupuesto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record ItemPresupuestoDTO(
        @NotBlank @Size(max = 255) String descripcion,
        @Min(1) int cantidad,
        @NotNull @PositiveOrZero BigDecimal precioUnitario
) {
}
