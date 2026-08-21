package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.config.mercadopago.MercadoPagoProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MercadoPagoReconciliationScheduler {

    private final MercadoPagoProperties properties;
    private final MercadoPagoService mercadoPagoService;

    @Scheduled(
            fixedDelayString = "${mercadopago.reconciliation-delay:6h}",
            initialDelayString = "${mercadopago.reconciliation-initial-delay:5m}")
    public void reconcile() {
        if (properties.isEnabled()) {
            mercadoPagoService.reconcileCurrentSubscriptions();
        }
    }
}
