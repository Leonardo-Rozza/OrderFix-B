package com.leonardorozza.mvgrreparacionesbackend.service.dto;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoSuscripcion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PlanType;

import java.time.LocalDate;

/**
 * Estado del plan del taller actual + consumo del mes (para mostrar en la UI).
 * limiteReparacionesMes es null cuando el plan no tiene tope (PRO).
 */
public record SuscripcionResponseDto(
        PlanType plan,
        EstadoSuscripcion estado,
        LocalDate fechaInicio,
        LocalDate fechaFinTrial,
        LocalDate proximoCobro,
        long reparacionesEsteMes,
        Integer limiteReparacionesMes
) {}
