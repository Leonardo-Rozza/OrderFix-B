package com.leonardorozza.mvgrreparacionesbackend.config.tenant;

import com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.WorkshopClosureGate;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.UnauthorizedException;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Taller;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.TallerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Acceso al taller (tenant) del request actual.
 * Centraliza la lectura del TenantContext para que los services no lo toquen directo.
 */
@Service
@RequiredArgsConstructor
public class TenantService {

    private final TallerRepository tallerRepository;
    private final WorkshopClosureGate closureGate;

    public Long currentTallerId() {
        Long tallerId = currentTallerIdForAccount();
        closureGate.requireOperational(tallerId);
        return tallerId;
    }

    /** Identity only for the restricted account surface; it does not authorize workshop operations. */
    public Long currentTallerIdForAccount() {
        Long tallerId = TenantContext.getTallerId();
        if (tallerId == null) {
            throw new UnauthorizedException("No hay un taller asociado a la petición.");
        }
        return tallerId;
    }

    /**
     * Referencia al taller actual después de verificar que admite operaciones.
     */
    public Taller currentTallerRef() {
        return tallerRepository.getReferenceById(currentTallerId());
    }
}
