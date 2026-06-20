package com.leonardorozza.mvgrreparacionesbackend.service.dto.pago;

/**
 * Respuesta al iniciar un checkout de suscripción.
 * El frontend redirige al usuario a {@code initPoint}.
 */
public record CheckoutResponseDto(
        String preapprovalId,
        String initPoint
) {
}
