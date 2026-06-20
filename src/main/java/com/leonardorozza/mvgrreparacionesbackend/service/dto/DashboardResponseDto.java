package com.leonardorozza.mvgrreparacionesbackend.service.dto;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoReparacion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoSuscripcion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PlanType;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.reparacion.ReparacionResponseDTO;

import java.util.List;
import java.util.Map;

/**
 * Métricas del taller actual para la pantalla de inicio/dashboard.
 */
public record DashboardResponseDto(
        Map<EstadoReparacion, Long> reparacionesPorEstado,
        long totalReparaciones,
        long reparacionesEsteMes,
        long equiposListos,            // COMPLETADO (listos para entregar)
        long articulosStockBajo,       // artículos con stock <= mínimo
        PlanType plan,
        EstadoSuscripcion estadoSuscripcion,
        Integer limiteReparacionesMes, // null = ilimitado (PRO)
        List<ReparacionResponseDTO> ultimasReparaciones
) {
}
