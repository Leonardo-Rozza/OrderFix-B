package com.leonardorozza.mvgrreparacionesbackend.service.finanzas;

import java.math.BigDecimal;

/** Importes derivados de una orden, siempre normalizados a valores no negativos. */
public record EstadoCuentaOrden(
        BigDecimal total,
        BigDecimal cobrado,
        BigDecimal saldo,
        BigDecimal excedente
) {

    private static final BigDecimal CERO = BigDecimal.ZERO;

    public static EstadoCuentaOrden de(BigDecimal total, BigDecimal cobrado) {
        BigDecimal totalSeguro = noNegativo(total);
        BigDecimal cobradoSeguro = noNegativo(cobrado);
        return new EstadoCuentaOrden(
                totalSeguro,
                cobradoSeguro,
                noNegativo(totalSeguro.subtract(cobradoSeguro)),
                noNegativo(cobradoSeguro.subtract(totalSeguro))
        );
    }

    public boolean requiereRevision() {
        return excedente.signum() > 0;
    }

    public boolean pagado() {
        return total.signum() > 0 && cobrado.compareTo(total) >= 0;
    }

    private static BigDecimal noNegativo(BigDecimal value) {
        return value == null || value.signum() < 0 ? CERO : value;
    }
}
