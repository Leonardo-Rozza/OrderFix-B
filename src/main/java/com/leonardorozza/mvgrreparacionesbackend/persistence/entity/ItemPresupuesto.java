package com.leonardorozza.mvgrreparacionesbackend.persistence.entity;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.CalidadRepuesto;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoItemPresupuesto;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

    /** Mano de obra o repuesto (para discriminar el total). */
    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_item", nullable = false, length = 15)
    @Builder.Default
    private TipoItemPresupuesto tipoItem = TipoItemPresupuesto.MANO_DE_OBRA;

    /** Solo para repuestos: ORIGINAL | ALTERNATIVO | USADO_REACONDICIONADO (null si es mano de obra). */
    @Enumerated(EnumType.STRING)
    @Column(length = 25)
    private CalidadRepuesto calidad;
}
