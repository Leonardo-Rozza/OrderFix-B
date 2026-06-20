package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.exceptions.PlanLimitException;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Suscripcion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoSuscripcion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PlanType;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.SuscripcionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;

/**
 * Hace cumplir los límites del plan del taller (freemium).
 * FREE/TRIAL: tope mensual de reparaciones. PRO: ilimitado.
 * Suscripción VENCIDA/CANCELADA: bloquea la escritura.
 *
 * El consumo se lleva con un contador mensual en la suscripción ({@code consumoMes}/{@code reparacionesMes})
 * que NO baja al borrar reparaciones (no se puede esquivar el límite) y se reinicia al cambiar de mes.
 */
@Service
@RequiredArgsConstructor
public class PlanLimitService {

    private final SuscripcionRepository suscripcionRepository;

    @Value("${plan.free.max-reparaciones-mes:30}")
    private int freeMaxReparacionesMes;

    /**
     * Valida que el taller pueda crear una reparación y registra el uso (incrementa el contador del mes).
     * Debe llamarse dentro de la transacción de creación: si la creación falla, el contador se revierte.
     */
    @Transactional
    public void registrarUsoReparacion(Long tallerId) {
        Suscripcion suscripcion = suscripcionRepository.findByTallerId(tallerId)
                .orElseThrow(() -> new PlanLimitException("El taller no tiene una suscripción asociada."));

        if (suscripcion.getEstado() == EstadoSuscripcion.VENCIDA
                || suscripcion.getEstado() == EstadoSuscripcion.CANCELADA) {
            throw new PlanLimitException(
                    "Tu suscripción no está vigente. Reactivá tu plan para seguir cargando reparaciones.");
        }

        // Reinicio del contador al cambiar de mes
        String mesActual = YearMonth.now().toString(); // "2026-06"
        if (!mesActual.equals(suscripcion.getConsumoMes())) {
            suscripcion.setConsumoMes(mesActual);
            suscripcion.setReparacionesMes(0);
        }

        // FREE / TRIAL → tope mensual (PRO no tiene límite)
        if (suscripcion.getPlan() != PlanType.PRO
                && suscripcion.getReparacionesMes() >= freeMaxReparacionesMes) {
            throw new PlanLimitException(
                    "Alcanzaste el límite de " + freeMaxReparacionesMes
                            + " reparaciones del mes del plan FREE. Pasá a PRO para tener reparaciones ilimitadas.");
        }

        suscripcion.setReparacionesMes(suscripcion.getReparacionesMes() + 1);
        suscripcionRepository.save(suscripcion);
    }
}
