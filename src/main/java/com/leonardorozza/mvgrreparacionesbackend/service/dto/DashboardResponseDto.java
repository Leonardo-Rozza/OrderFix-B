package com.leonardorozza.mvgrreparacionesbackend.service.dto;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoReparacion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoSuscripcion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PlanType;

import java.util.Map;

/**
 * Métricas del taller actual para la pantalla de inicio/dashboard.
 */
public record DashboardResponseDto(
        Map<EstadoReparacion, Long> reparacionesPorEstado,
        long totalReparaciones,
        long reparacionesEsteMes,
        long equiposListos,            // COMPLETADO (listos para entregar)
        PlanType plan,
        EstadoSuscripcion estadoSuscripcion,
        Integer limiteReparacionesMes  // null = ilimitado (PRO)
) {
}
