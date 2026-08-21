package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoSuscripcion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PlanType;
import org.springframework.stereotype.Component;

/**
 * Fuente única de verdad para decidir si una suscripción tiene entitlement PRO vigente.
 */
@Component
public class SubscriptionEntitlementPolicy {

    public boolean tieneAccesoPro(PlanType plan, EstadoSuscripcion estado) {
        return estado == EstadoSuscripcion.TRIAL
                || (estado == EstadoSuscripcion.ACTIVA && plan == PlanType.PRO);
    }
}
