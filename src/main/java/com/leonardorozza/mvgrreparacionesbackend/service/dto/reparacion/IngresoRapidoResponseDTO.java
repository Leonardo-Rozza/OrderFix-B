package com.leonardorozza.mvgrreparacionesbackend.service.dto.reparacion;

/**
 * Resultado de una carga rápida. Devuelve los IDs creados para que el frontend
 * pueda navegar a completar los datos de cliente/equipo si hace falta.
 * {@code clienteNuevo} indica si se creó un cliente o se reutilizó uno existente
 * (match por teléfono dentro del taller).
 */
public record IngresoRapidoResponseDTO(
        Long clienteId,
        Long equipoId,
        boolean clienteNuevo,
        ReparacionResponseDTO reparacion
) {
}
