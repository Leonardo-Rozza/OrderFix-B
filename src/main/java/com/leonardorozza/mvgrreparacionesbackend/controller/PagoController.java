package com.leonardorozza.mvgrreparacionesbackend.controller;

import com.leonardorozza.mvgrreparacionesbackend.service.dto.SuscripcionResponseDto;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.pago.CheckoutResponseDto;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.MercadoPagoService;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.SuscripcionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/pagos")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Pagos", description = "Suscripción PRO vía MercadoPago")
public class PagoController {

    private final MercadoPagoService mercadoPagoService;
    private final SuscripcionService suscripcionService;

    @PostMapping("/suscripcion")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Iniciar el checkout de la suscripción PRO (solo ADMIN; devuelve el init_point)")
    public ResponseEntity<CheckoutResponseDto> crearCheckout() {
        return ResponseEntity.ok(mercadoPagoService.crearCheckoutSuscripcion());
    }

    @PostMapping("/suscripcion/cancelar")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Cancelar la suscripción PRO (solo ADMIN; baja a plan FREE)")
    public ResponseEntity<SuscripcionResponseDto> cancelar() {
        mercadoPagoService.cancelarSuscripcion();
        return ResponseEntity.ok(suscripcionService.miSuscripcion());
    }

    /**
     * Webhook público de MercadoPago. MP puede mandar la info por query params
     * (?type=...&data.id=...) o en el cuerpo JSON, así que contemplamos ambos.
     * Siempre respondemos 200 para que MP no reintente en loop.
     */
    @PostMapping("/webhook")
    @Operation(summary = "Webhook de notificaciones de MercadoPago (uso interno)")
    public ResponseEntity<Void> webhook(
            @RequestParam(name = "type", required = false) String type,
            @RequestParam(name = "topic", required = false) String topic,
            @RequestParam(name = "data.id", required = false) String dataIdParam,
            @RequestParam(name = "id", required = false) String idParam,
            @RequestHeader(name = "x-signature", required = false) String xSignature,
            @RequestHeader(name = "x-request-id", required = false) String xRequestId,
            @RequestBody(required = false) Map<String, Object> body) {

        String resolvedType = type != null ? type : topic;
        String resolvedDataId = dataIdParam != null ? dataIdParam : idParam;

        if (body != null) {
            if (resolvedType == null && body.get("type") != null) {
                resolvedType = String.valueOf(body.get("type"));
            }
            if (resolvedType == null && body.get("action") != null) {
                resolvedType = String.valueOf(body.get("action"));
            }
            if (resolvedDataId == null && body.get("data") instanceof Map<?, ?> data && data.get("id") != null) {
                resolvedDataId = String.valueOf(data.get("id"));
            }
        }

        // Seguridad: verificamos que la notificación venga realmente de MercadoPago.
        if (!mercadoPagoService.firmaWebhookValida(resolvedDataId, xSignature, xRequestId)) {
            log.warn("Webhook MercadoPago rechazado por firma inválida (data.id={})", resolvedDataId);
            return ResponseEntity.status(401).build();
        }

        log.info("Webhook MercadoPago recibido: type={}, data.id={}", resolvedType, resolvedDataId);
        mercadoPagoService.procesarNotificacion(resolvedType, resolvedDataId);
        return ResponseEntity.ok().build();
    }
}
