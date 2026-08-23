package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.config.tenant.TenantService;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.ResourceNotFoundException;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.UnauthorizedException;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Taller;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PlanFeature;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.TallerRepository;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.taller.DatosCobroDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.taller.DatosCobroRequestDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
@RequiredArgsConstructor
public class DatosCobroTallerService {

    private final TallerRepository tallerRepository;
    private final TenantService tenantService;
    private final PlanFeatureService planFeatureService;

    @Transactional(readOnly = true)
    public DatosCobroDTO obtener(AuthenticatedUserPrincipal principal) {
        planFeatureService.requerir(PlanFeature.COBROS);
        return toDTO(tallerActual(principal, false));
    }

    @Transactional
    public DatosCobroDTO actualizar(
            AuthenticatedUserPrincipal principal, DatosCobroRequestDTO request) {
        planFeatureService.requerir(PlanFeature.COBROS);
        Taller taller = tallerActual(principal, true);

        // PUT reemplaza el recurso completo: un null o blanco borra el valor anterior.
        taller.setAliasCobro(request.alias());
        taller.setTitularCobro(request.titular());
        taller.setEntidadCobro(request.entidad());
        taller.setMostrarEnResumen(request.mostrarEnResumen());

        return toDTO(tallerRepository.save(taller));
    }

    private Taller tallerActual(AuthenticatedUserPrincipal principal, boolean forUpdate) {
        Long tallerId = tenantService.currentTallerId();
        if (principal == null
                || principal.getUserId() == null
                || !Objects.equals(principal.getTallerId(), tallerId)) {
            throw new UnauthorizedException(
                    "La identidad autenticada no corresponde al taller actual.");
        }
        var taller = forUpdate
                ? tallerRepository.findByIdForUpdate(tallerId)
                : tallerRepository.findById(tallerId);
        return taller
                .orElseThrow(() -> new ResourceNotFoundException("Taller no encontrado."));
    }

    private DatosCobroDTO toDTO(Taller taller) {
        return new DatosCobroDTO(
                taller.getAliasCobro(),
                taller.getTitularCobro(),
                taller.getEntidadCobro(),
                taller.isMostrarEnResumen(),
                false,
                null);
    }
}
