package com.leonardorozza.mvgrreparacionesbackend.service.dto.inventario;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class ArticuloRequestDTO {

    @NotBlank
    @Size(max = 120)
    private String nombre;

    @Size(max = 255)
    private String descripcion;

    @Size(max = 60)
    private String sku;

    @NotNull
    @PositiveOrZero
    private BigDecimal precio;

    @PositiveOrZero
    private BigDecimal costo;

    /** Stock inicial (solo al crear; luego se ajusta con /ajuste). */
    @PositiveOrZero
    private Integer stock;

    @PositiveOrZero
    private Integer stockMinimo;
}
