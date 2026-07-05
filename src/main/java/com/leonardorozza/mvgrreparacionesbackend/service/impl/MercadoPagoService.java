package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.config.mercadopago.MercadoPagoProperties;
import com.leonardorozza.mvgrreparacionesbackend.config.tenant.TenantService;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.PagoException;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.ResourceNotFoundException;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Suscripcion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoSuscripcion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PlanType;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.SuscripcionRepository;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.pago.CheckoutResponseDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Integración con MercadoPago para la suscripción PRO (cobro recurrente vía preapproval).
 *
 * Flujo:
 *  1) El taller (autenticado) pide iniciar el checkout -> creamos un preapproval y
 *     devolvemos el init_point al que el frontend redirige.
 *  2) Cuando MercadoPago confirma/cambia el estado, llama al webhook -> consultamos
 *     el preapproval y actualizamos plan/estado de la suscripción.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MercadoPagoService {

    private final MercadoPagoProperties props;
    private final RestClient mercadoPagoRestClient;
    private final SuscripcionRepository suscripcionRepository;
    private final TenantService tenantService;

    @Transactional
    public CheckoutResponseDto crearCheckoutSuscripcion() {
        if (!props.isEnabled()) {
            throw new PagoException(
                    "La integración con MercadoPago no está habilitada (falta configurar el Access Token).");
        }

        Long tallerId = tenantService.currentTallerId();
        Suscripcion suscripcion = suscripcionRepository.findByTallerId(tallerId)
                .orElseThrow(() -> new ResourceNotFoundException("El taller no tiene una suscripción asociada."));

        String payerEmail = suscripcion.getTaller().getEmailContacto();

        Map<String, Object> autoRecurring = new HashMap<>();
        autoRecurring.put("frequency", 1);
        autoRecurring.put("frequency_type", "months");
        autoRecurring.put("transaction_amount", props.getAmount());
        autoRecurring.put("currency_id", props.getCurrency());

        Map<String, Object> body = new HashMap<>();
        body.put("reason", props.getReason());
        body.put("auto_recurring", autoRecurring);
        body.put("back_url", props.getBackUrl());
        body.put("payer_email", payerEmail);
        body.put("status", "pending");
        body.put("external_reference", "taller-" + tallerId);

        Map<?, ?> resp;
        try {
            resp = mercadoPagoRestClient.post()
                    .uri("/preapproval")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + props.getAccessToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientResponseException ex) {
            log.error("Error creando preapproval en MercadoPago: {} - {}",
                    ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new PagoException("No se pudo iniciar el checkout de MercadoPago.", ex);
        }

        if (resp == null || resp.get("id") == null || resp.get("init_point") == null) {
            throw new PagoException("Respuesta inválida de MercadoPago al crear la suscripción.");
        }

        String preapprovalId = String.valueOf(resp.get("id"));
        String initPoint = String.valueOf(resp.get("init_point"));

        suscripcion.setMpPreapprovalId(preapprovalId);
        suscripcionRepository.save(suscripcion);

        log.info("Checkout de suscripción creado para taller {} (preapproval {})", tallerId, preapprovalId);
        return new CheckoutResponseDto(preapprovalId, initPoint);
    }

    /**
     * Procesa una notificación (webhook) de MercadoPago sobre un preapproval.
     * Es idempotente y nunca lanza: ante cualquier problema loguea y retorna,
     * así MercadoPago recibe 200 y no reintenta indefinidamente.
     */
    @Transactional
    public void procesarNotificacion(String type, String dataId) {
        if (!props.isEnabled()) {
            log.warn("Webhook de MercadoPago recibido pero la integración está deshabilitada. Ignorando.");
            return;
        }
        if (dataId == null || dataId.isBlank()) {
            log.warn("Webhook de MercadoPago sin data.id (type={}). Ignorando.", type);
            return;
        }
        // Solo nos interesan notificaciones de preapproval (suscripción).
        if (type != null && !type.contains("preapproval") && !type.contains("subscription")) {
            log.debug("Webhook de MercadoPago ignorado (type={}).", type);
            return;
        }

        Map<?, ?> pre;
        try {
            pre = mercadoPagoRestClient.get()
                    .uri("/preapproval/{id}", dataId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + props.getAccessToken())
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientResponseException ex) {
            log.error("Error consultando preapproval {} en MercadoPago: {} - {}",
                    dataId, ex.getStatusCode(), ex.getResponseBodyAsString());
            return;
        }
        if (pre == null) {
            log.warn("Preapproval {} sin cuerpo de respuesta.", dataId);
            return;
        }

        String status = String.valueOf(pre.get("status"));
        String externalReference = pre.get("external_reference") != null
                ? String.valueOf(pre.get("external_reference")) : null;
        String payerId = pre.get("payer_id") != null ? String.valueOf(pre.get("payer_id")) : null;

        Suscripcion suscripcion = suscripcionRepository.findByMpPreapprovalId(dataId)
                .or(() -> tallerIdFromRef(externalReference).flatMap(suscripcionRepository::findByTallerId))
                .orElse(null);

        if (suscripcion == null) {
            log.warn("Webhook de MercadoPago: no se encontró suscripción para preapproval {} (ref {}).",
                    dataId, externalReference);
            return;
        }

        suscripcion.setMpPreapprovalId(dataId);
        if (payerId != null) {
            suscripcion.setMpPayerId(payerId);
        }

        switch (status) {
            case "authorized" -> {
                suscripcion.setPlan(PlanType.PRO);
                suscripcion.setEstado(EstadoSuscripcion.ACTIVA);
                if (suscripcion.getFechaInicio() == null) {
                    suscripcion.setFechaInicio(LocalDate.now());
                }
                suscripcion.setProximoCobro(LocalDate.now().plusMonths(1));
            }
            case "paused" -> suscripcion.setEstado(EstadoSuscripcion.VENCIDA);
            case "cancelled" -> {
                // Cancelación → vuelve al plan FREE (sigue operando con el tope gratuito)
                suscripcion.setPlan(PlanType.FREE);
                suscripcion.setEstado(EstadoSuscripcion.ACTIVA);
                suscripcion.setProximoCobro(null);
            }
            default -> log.info("Preapproval {} en estado '{}', sin cambios de plan.", dataId, status);
        }

        suscripcionRepository.save(suscripcion);
        log.info("Suscripción del taller {} actualizada por webhook MercadoPago: status={}",
                suscripcion.getTaller().getId(), status);
    }

    /**
     * Cancela la suscripción PRO del taller actual: cancela el preapproval en MercadoPago
     * (si corresponde) y baja el plan a FREE (sigue operando con el tope gratuito).
     */
    @Transactional
    public void cancelarSuscripcion() {
        Long tallerId = tenantService.currentTallerId();
        Suscripcion suscripcion = suscripcionRepository.findByTallerId(tallerId)
                .orElseThrow(() -> new ResourceNotFoundException("El taller no tiene una suscripción asociada."));

        String preapprovalId = suscripcion.getMpPreapprovalId();
        if (props.isEnabled() && preapprovalId != null && !preapprovalId.isBlank()) {
            try {
                mercadoPagoRestClient.put()
                        .uri("/preapproval/{id}", preapprovalId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + props.getAccessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("status", "cancelled"))
                        .retrieve()
                        .toBodilessEntity();
            } catch (RestClientResponseException ex) {
                log.error("Error cancelando preapproval {} en MercadoPago: {} - {}",
                        preapprovalId, ex.getStatusCode(), ex.getResponseBodyAsString());
                throw new PagoException("No se pudo cancelar la suscripción en MercadoPago.", ex);
            }
        }

        suscripcion.setPlan(PlanType.FREE);
        suscripcion.setEstado(EstadoSuscripcion.ACTIVA);
        suscripcion.setProximoCobro(null);
        suscripcion.setMpPreapprovalId(null);
        suscripcion.setMpPayerId(null);
        suscripcionRepository.save(suscripcion);

        log.info("Suscripción del taller {} cancelada (downgrade a FREE).", tallerId);
    }

    /**
     * Valida la firma del webhook de MercadoPago (header x-signature) usando HMAC-SHA256.
     * Devuelve true si la firma es válida. Sin webhookSecret configurado: si MercadoPago
     * está deshabilitado el webhook es un no-op y se tolera; si está habilitado se rechaza
     * (fail-closed) — con la integración activa el secreto es obligatorio.
     */
    public boolean firmaWebhookValida(String dataId, String xSignature, String xRequestId) {
        String secret = props.getWebhookSecret();
        if (secret == null || secret.isBlank()) {
            if (!props.isEnabled()) {
                return true;
            }
            log.warn("Webhook MP rechazado: mercadopago.enabled=true sin webhook-secret. Seteá MP_WEBHOOK_SECRET.");
            return false;
        }
        if (xSignature == null || xSignature.isBlank()) {
            log.warn("Webhook MP rechazado: falta el header x-signature.");
            return false;
        }

        // x-signature viene como: "ts=1700000000,v1=abcdef..."
        String ts = null;
        String v1 = null;
        for (String part : xSignature.split(",")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2) {
                String k = kv[0].trim();
                String val = kv[1].trim();
                if (k.equals("ts")) ts = val;
                else if (k.equals("v1")) v1 = val;
            }
        }
        if (ts == null || v1 == null) {
            log.warn("Webhook MP rechazado: x-signature mal formado.");
            return false;
        }

        // Manifest segun MP: id:<data.id>;request-id:<x-request-id>;ts:<ts>;
        StringBuilder manifest = new StringBuilder();
        if (dataId != null && !dataId.isBlank()) {
            manifest.append("id:").append(dataId.toLowerCase()).append(";");
        }
        if (xRequestId != null && !xRequestId.isBlank()) {
            manifest.append("request-id:").append(xRequestId).append(";");
        }
        manifest.append("ts:").append(ts).append(";");

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(manifest.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            boolean ok = MessageDigest.isEqual(
                    hex.toString().getBytes(StandardCharsets.UTF_8),
                    v1.getBytes(StandardCharsets.UTF_8));
            if (!ok) {
                log.warn("Webhook MP rechazado: firma inválida.");
            }
            return ok;
        } catch (Exception e) {
            log.error("Error validando la firma del webhook MP", e);
            return false;
        }
    }

    private Optional<Long> tallerIdFromRef(String ref) {
        if (ref == null || !ref.startsWith("taller-")) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(ref.substring("taller-".length())));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
