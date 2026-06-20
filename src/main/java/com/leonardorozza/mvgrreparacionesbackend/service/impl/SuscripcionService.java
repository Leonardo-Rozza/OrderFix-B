package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.config.tenant.TenantService;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.ResourceNotFoundException;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Suscripcion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PlanType;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.ReparacionRepository;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.SuscripcionRepository;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.SuscripcionResponseDto;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class SuscripcionService {

    private final SuscripcionRepository suscripcionRepository;
    private final ReparacionRepository reparacionRepository;
    private final TenantService tenantService;

    @Value("${plan.free.max-reparaciones-mes:50}")
    private int freeMaxReparacionesMes;

    @Transactional(readOnly = true)
    public SuscripcionResponseDto miSuscripcion() {
        Long tallerId = tenantService.currentTallerId();

        Suscripcion suscripcion = suscripcionRepository.findByTallerId(tallerId)
                .orElseThrow(() -> new ResourceNotFoundException("El taller no tiene una suscripción asociada."));

        LocalDateTime inicioMes = LocalDate.now().withDayOfMonth(1).atStartOfDay();
        long usadasEsteMes = reparacionRepository.countByTallerIdAndCreatedAtAfter(tallerId, inicioMes);

        Integer limite = (suscripcion.getPlan() == PlanType.PRO) ? null : freeMaxReparacionesMes;

        return new SuscripcionResponseDto(
                suscripcion.getPlan(),
                suscripcion.getEstado(),
                suscripcion.getFechaInicio(),
                suscripcion.getFechaFinTrial(),
                suscripcion.getProximoCobro(),
                usadasEsteMes,
                limite
        );
    }
}
