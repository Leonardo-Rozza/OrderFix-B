package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Suscripcion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoSuscripcion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.SuscripcionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * Tareas programadas de suscripciones. Hoy: cerrar los trials vencidos
 * (TRIAL cuyo fechaFinTrial ya pasó) pasándolos a FREE/ACTIVA (siguen operando
 * con el tope gratuito). Los cambios de PRO los maneja el webhook de MercadoPago.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SuscripcionScheduler {

    private final SuscripcionRepository suscripcionRepository;

    /** Todos los días a las 03:00. */
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void cerrarTrialsVencidos() {
        LocalDate hoy = LocalDate.now();
        List<Suscripcion> vencidos =
                suscripcionRepository.findByEstadoAndFechaFinTrialBefore(EstadoSuscripcion.TRIAL, hoy);

        if (vencidos.isEmpty()) {
            return;
        }
        for (Suscripcion s : vencidos) {
            s.setEstado(EstadoSuscripcion.ACTIVA); // queda en plan FREE con su tope mensual
        }
        suscripcionRepository.saveAll(vencidos);
        log.info("Trials vencidos cerrados (pasados a FREE/ACTIVA): {}", vencidos.size());
    }
}
