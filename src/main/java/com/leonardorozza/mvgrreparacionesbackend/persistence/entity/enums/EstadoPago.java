package com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums;

import java.math.BigDecimal;

/**
 * Estado de pago de una reparación. Es una dimensión <b>independiente</b> del
 * estado de reparación ({@link EstadoReparacion}): se <b>deriva</b> de cuánto se
 * cobró vs el total a cobrar, no se almacena.
 */
public enum EstadoPago {
    SIN_COBRAR,
    PARCIAL,
    PAGADO;

    /**
     * Deriva el estado de pago a partir del total y lo cobrado.
     * <ul>
     *   <li>cobrado ≤ 0 → SIN_COBRAR (incluye el caso total 0 sin movimientos).</li>
     *   <li>cobrado ≥ total (con total &gt; 0) → PAGADO.</li>
     *   <li>en el medio → PARCIAL.</li>
     * </ul>
     */
    public static EstadoPago de(BigDecimal total, BigDecimal cobrado) {
        BigDecimal t = total != null ? total : BigDecimal.ZERO;
        BigDecimal c = cobrado != null ? cobrado : BigDecimal.ZERO;
        if (c.signum() <= 0) {
            return SIN_COBRAR;
        }
        if (t.signum() > 0 && c.compareTo(t) >= 0) {
            return PAGADO;
        }
        return PARCIAL;
    }
}
