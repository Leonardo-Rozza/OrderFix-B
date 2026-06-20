package com.leonardorozza.mvgrreparacionesbackend.service.dto.inventario;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Ajuste de stock: delta positivo (entrada/compra) o negativo (salida).
 */
@Data
public class AjusteStockRequestDTO {

    @NotNull
    private Integer delta;

    @Size(max = 255)
    private String motivo;
}
