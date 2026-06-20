package com.leonardorozza.mvgrreparacionesbackend.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.*;

import java.math.BigDecimal;

/**
 * Línea de un presupuesto (se guarda en la tabla presupuesto_items).
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ItemPresupuesto {

    @Column(nullable = false, length = 255)
    private String descripcion;

    @Column(nullable = false)
    private int cantidad;

    @Column(name = "precio_unitario", nullable = false, precision = 10, scale = 2)
    private BigDecimal precioUnitario;
}
