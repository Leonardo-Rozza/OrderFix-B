package com.leonardorozza.mvgrreparacionesbackend.config.tenant;

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

    public Long currentTallerId() {
        Long tallerId = TenantContext.getTallerId();
        if (tallerId == null) {
            throw new UnauthorizedException("No hay un taller asociado a la petición.");
        }
        return tallerId;
    }

    /**
     * Referencia liviana (proxy) al taller actual, para setear la FK sin golpear la DB.
     */
    public Taller currentTallerRef() {
        return tallerRepository.getReferenceById(currentTallerId());
    }
}
