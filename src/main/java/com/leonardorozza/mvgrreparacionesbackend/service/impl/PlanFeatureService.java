package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.config.tenant.TenantService;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.PlanLimitException;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PlanFeature;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PlanType;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.SuscripcionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Gating de funciones por plan: define qué funciones son exclusivas de PRO,
 * las hace cumplir (402 si el taller es FREE) y expone el mapa de capacidades al frontend.
 */
@Service
@RequiredArgsConstructor
public class PlanFeatureService {

    private final SuscripcionRepository suscripcionRepository;
    private final TenantService tenantService;

    @Transactional(readOnly = true)
    public boolean esPro(Long tallerId) {
        return suscripcionRepository.findByTallerId(tallerId)
                .map(s -> s.getPlan() == PlanType.PRO)
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
            case COBROS -> "Los cobros, la caja y el recibo";
            case EMPLEADOS_MULTIPLES -> "Agregar más empleados";
        };
        return que + " es una función del plan PRO. Pasá a PRO para habilitarla.";
    }
}
