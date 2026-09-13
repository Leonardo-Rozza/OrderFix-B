package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.WorkshopClosureBlockedException;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.WorkshopClosureGate;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.ResourceNotFoundException;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Reparacion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.ReparacionRepository;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.SeguimientoPublicoDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.presupuesto.PresupuestoResponseDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consulta pública del estado de una reparación por su código de seguimiento.
 * No requiere autenticación ni filtra por taller (el código es el secreto).
 */
@Service
@RequiredArgsConstructor
public class SeguimientoService {

    private final ReparacionRepository reparacionRepository;
    private final PresupuestoService presupuestoService;
    private final WorkshopClosureGate closureGate;

    @Transactional(readOnly = true)
    public SeguimientoPublicoDTO consultar(String codigo) {
        Reparacion r = reparacionRepository.findByCodigoSeguimientoAndTallerActivoTrue(codigo)
                .orElseThrow(() -> new ResourceNotFoundException("No encontramos una reparación con ese código."));

        try {
            closureGate.requireOperational(r.getTaller().getId());
        } catch (WorkshopClosureBlockedException closed) {
            throw new ResourceNotFoundException("No encontramos una reparación con ese código.");
        }

        return new SeguimientoPublicoDTO(
                r.getCodigoSeguimiento(),
                r.getEstado(),
                r.getEquipo().getMarca(),
                r.getEquipo().getModelo(),
                r.getTaller().getNombre(),
                r.getFechaIngreso(),
                r.getFechaEstimadaEntrega(),
                presupuestoService.ultimoDeReparacion(r.getId())
        );
    }
}
