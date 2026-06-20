package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.exceptions.PlanLimitException;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Suscripcion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoSuscripcion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PlanType;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.ReparacionRepository;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.SuscripcionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Hace cumplir los límites del plan del taller (freemium).
 * FREE/TRIAL: tope mensual de reparaciones. PRO: ilimitado.
 * Suscripción VENCIDA/CANCELADA: bloquea la escritura.
 */
@Service
@RequiredArgsConstructor
public class PlanLimitService {

    private final SuscripcionRepository suscripcionRepository;
    private final ReparacionRepository reparacionRepository;

    @Value("${plan.free.max-reparaciones-mes:50}")
    private int freeMaxReparacionesMes;

    public void assertPuedeCrearReparacion(Long tallerId) {
        Suscripcion suscripcion = suscripcionRepository.findByTallerId(tallerId)
                .orElseThrow(() -> new PlanLimitException("El taller no tiene una suscripción asociada."));

        if (suscripcion.getEstado() == EstadoSuscripcion.VENCIDA
                || suscripcion.getEstado() == EstadoSuscripcion.CANCELADA) {
            throw new PlanLimitException(
                    "Tu suscripción no está vigente. Reactivá tu plan para seguir cargando reparaciones.");
        }

        // PRO vigente → sin límite
        if (suscripcion.getPlan() == PlanType.PRO) {
            return;
        }

        // FREE / TRIAL → tope mensual
        LocalDateTime inicioMes = LocalDate.now().withDayOfMonth(1).atStartOfDay();
        long usadasEsteMes = reparacionRepository.countByTallerIdAndCreatedAtAfter(tallerId, inicioMes);

        if (usadasEsteMes >= freeMaxReparacionesMes) {
            throw new PlanLimitException(
                    "Alcanzaste el límite de " + freeMaxReparacionesMes
                            + " reparaciones por mes del plan FREE. Pasá a PRO para tener reparaciones ilimitadas.");
        }
    }
}
