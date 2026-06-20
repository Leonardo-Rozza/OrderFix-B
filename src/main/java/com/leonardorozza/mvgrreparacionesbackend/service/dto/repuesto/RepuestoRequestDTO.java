package com.leonardorozza.mvgrreparacionesbackend.service.dto.repuesto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class RepuestoRequestDTO {


    @NotBlank
    private String nombre;

    private String descripcion;

    @NotNull
    private BigDecimal precio;

    private Long reparacionId; // puede ser null si se carga sin asignar todavía

    /** Si se enlaza a un artículo del inventario, se descuenta su stock. */
    private Long articuloId;

    /** Cantidad usada (default 1). */
    private Integer cantidad;
}
