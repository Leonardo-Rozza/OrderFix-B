package com.leonardorozza.mvgrreparacionesbackend;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoSuscripcion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PlanType;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.SubscriptionEntitlementPolicy;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SubscriptionEntitlementPolicyTests {

    private final SubscriptionEntitlementPolicy policy = new SubscriptionEntitlementPolicy();

    @Test
    void trialConcedeAccesoProAunqueElPlanSeaFree() {
        assertThat(policy.tieneAccesoPro(PlanType.FREE, EstadoSuscripcion.TRIAL)).isTrue();
    }

    @Test
    void proActivoConcedeAccesoPro() {
        assertThat(policy.tieneAccesoPro(PlanType.PRO, EstadoSuscripcion.ACTIVA)).isTrue();
    }

    @Test
    void freeActivoNoConcedeAccesoPro() {
        assertThat(policy.tieneAccesoPro(PlanType.FREE, EstadoSuscripcion.ACTIVA)).isFalse();
    }

    @Test
    void proVencidoNoConcedeAccesoPro() {
        assertThat(policy.tieneAccesoPro(PlanType.PRO, EstadoSuscripcion.VENCIDA)).isFalse();
    }

    @Test
    void proCanceladoNoConcedeAccesoPro() {
        assertThat(policy.tieneAccesoPro(PlanType.PRO, EstadoSuscripcion.CANCELADA)).isFalse();
    }
}
