package com.leonardorozza.mvgrreparacionesbackend.service.dto.inventario;

import java.math.BigDecimal;

public record ArticuloResponseDTO(
        Long id,
        String nombre,
        String descripcion,
        String sku,
        BigDecimal precio,
        BigDecimal costo,
        int stock,
        int stockMinimo,
        boolean activo,
        boolean stockBajo
) {
}
