package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.config.mercadopago.MercadoPagoProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class MercadoPagoEventRetryScheduler {

    private final MercadoPagoProperties properties;
    private final PaymentEventInboxService inboxService;
    private final MercadoPagoService mercadoPagoService;

    @Scheduled(fixedDelayString = "${mercadopago.webhook-retry-delay:5m}")
    public void retryFailedEvents() {
        if (!properties.isEnabled()) {
            return;
        }
        int recovered = inboxService.recoverStaleProcessing(properties.getWebhookProcessingTimeout());
        if (recovered > 0) {
            log.warn("Se recuperaron {} eventos MP cuyo procesamiento quedó interrumpido.", recovered);
        }
        var eventIds = inboxService.failedEventIds(properties.getWebhookMaxAttempts());
        if (!eventIds.isEmpty()) {
            log.info("Reintentando {} eventos fallidos de Mercado Pago.", eventIds.size());
        }
        eventIds.forEach(mercadoPagoService::retryPersistedEvent);
    }
}
