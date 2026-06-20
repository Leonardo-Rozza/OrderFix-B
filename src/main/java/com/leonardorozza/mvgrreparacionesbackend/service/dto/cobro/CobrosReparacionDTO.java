package com.leonardorozza.mvgrreparacionesbackend.service.dto.cobro;

import java.math.BigDecimal;
import java.util.List;

/**
 * Cobros de una reparación + resumen (total a cobrar, cobrado y saldo).
 */
public record CobrosReparacionDTO(
        BigDecimal total,
        BigDecimal cobrado,
        BigDecimal saldo,
        boolean pagado,
        List<CobroResponseDTO> cobros
) {
}
