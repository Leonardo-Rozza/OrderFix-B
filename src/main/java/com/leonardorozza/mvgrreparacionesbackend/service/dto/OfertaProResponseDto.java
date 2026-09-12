package com.leonardorozza.mvgrreparacionesbackend.service.dto;

import java.math.BigDecimal;

/**
 * Oferta mensual vigente de PRO, sin configuración privada del proveedor.
 * La disponibilidad operativa no concede permisos ni garantiza elegibilidad:
 * el checkout conserva sus validaciones de rol, estado e idempotencia.
 */
public record OfertaProResponseDto(
        BigDecimal precioMensual,
        String moneda,
        boolean contratacionDisponible
) {}
