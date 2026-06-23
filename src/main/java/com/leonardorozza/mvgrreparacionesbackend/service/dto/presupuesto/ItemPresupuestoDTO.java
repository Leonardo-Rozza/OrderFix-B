package com.leonardorozza.mvgrreparacionesbackend.service.dto.presupuesto;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.CalidadRepuesto;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoItemPresupuesto;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record ItemPresupuestoDTO(
        @NotBlank @Size(max = 255) String descripcion,
        @Min(1) int cantidad,
        @NotNull @PositiveOrZero BigDecimal precioUnitario,
        /** MANO_DE_OBRA | REPUESTO (null = MANO_DE_OBRA). */
        TipoItemPresupuesto tipoItem,
        /** Solo repuestos: ORIGINAL | ALTERNATIVO | USADO_REACONDICIONADO (opcional). */
        CalidadRepuesto calidad
) {
}
