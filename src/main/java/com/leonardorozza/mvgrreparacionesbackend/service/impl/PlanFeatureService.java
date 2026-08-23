package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.config.tenant.TenantService;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.PlanLimitException;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PlanFeature;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.SuscripcionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Gating por entitlement: las funciones PRO requieren un trial o una suscripción PRO activa.
 * Hace cumplir la regla con 402 y expone el mismo resultado al frontend.
 */
@Service
@RequiredArgsConstructor
public class PlanFeatureService {

    private final SuscripcionRepository suscripcionRepository;
    private final TenantService tenantService;
    private final SubscriptionEntitlementPolicy entitlementPolicy;

    /** Devuelve true si el taller tiene entitlement PRO, incluido el período de trial. */
    @Transactional(readOnly = true)
    public boolean esPro(Long tallerId) {
        return suscripcionRepository.findByTallerId(tallerId)
                .map(s -> entitlementPolicy.tieneAccesoPro(s.getPlan(), s.getEstado()))
                .orElse(false);
    }

    /** Lanza 402 si la función pedida no está disponible en el plan del taller actual. */
    public void requerir(PlanFeature feature) {
        if (!esPro(tenantService.currentTallerId())) {
            throw new PlanLimitException(mensaje(feature));
        }
    }

    /** Mapa { funcion -> disponible } para que el frontend habilite/deshabilite la UI. */
    @Transactional(readOnly = true)
    public Map<String, Boolean> capacidades() {
        boolean pro = esPro(tenantService.currentTallerId());
        Map<String, Boolean> caps = new LinkedHashMap<>();
        for (PlanFeature f : PlanFeature.values()) {
            caps.put(f.getKey(), pro);
        }
        return caps;
    }

    private String mensaje(PlanFeature feature) {
        String que = switch (feature) {
            case INVENTARIO -> "El inventario";
            case COBROS -> "La función de cobros manuales y resumen digital";
            case EMPLEADOS_MULTIPLES -> "Agregar más empleados";
        };
        return que + " requiere una suscripción con acceso PRO vigente. Activá o reactivá PRO para habilitarla.";
    }
}
