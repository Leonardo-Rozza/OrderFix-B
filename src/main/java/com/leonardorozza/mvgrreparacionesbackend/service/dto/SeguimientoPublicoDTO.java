package com.leonardorozza.mvgrreparacionesbackend.service.dto;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoReparacion;

import java.time.LocalDate;

/**
 * Vista pública (sin login) del estado de una reparación. Datos mínimos, sin info sensible.
 */
public record SeguimientoPublicoDTO(
        String codigo,
        EstadoReparacion estado,
        String marca,
        String modelo,
        String taller,
        LocalDate fechaIngreso,
        LocalDate fechaEstimadaEntrega
) {
}
