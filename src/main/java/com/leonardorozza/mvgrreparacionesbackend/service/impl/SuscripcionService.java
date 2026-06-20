package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.config.tenant.TenantService;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.ResourceNotFoundException;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Suscripcion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PlanType;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.SuscripcionRepository;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.SuscripcionResponseDto;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;

@Service
@RequiredArgsConstructor
public class SuscripcionService {

    private final SuscripcionRepository suscripcionRepository;
    private final TenantService tenantService;
    private final PlanFeatureService planFeatureService;

    @Value("${plan.free.max-reparaciones-mes:30}")
    private int freeMaxReparacionesMes;

    @Transactional(readOnly = true)
    public SuscripcionResponseDto miSuscripcion() {
        Long tallerId = tenantService.currentTallerId();

        Suscripcion suscripcion = suscripcionRepository.findByTallerId(tallerId)
                .orElseThrow(() -> new ResourceNotFoundException("El taller no tiene una suscripción asociada."));

        // Consumo del mes desde el contador (0 si cambió el mes y todavía no se cargó nada)
        String mesActual = YearMonth.now().toString();
        long usadasEsteMes = mesActual.equals(suscripcion.getConsumoMes()) ? suscripcion.getReparacionesMes() : 0;

        Integer limite = (suscripcion.getPlan() == PlanType.PRO) ? null : freeMaxReparacionesMes;

        return new SuscripcionResponseDto(
                suscripcion.getPlan(),
                suscripcion.getEstado(),
                suscripcion.getFechaInicio(),
                suscripcion.getFechaFinTrial(),
                suscripcion.getProximoCobro(),
                usadasEsteMes,
                limite,
                planFeatureService.capacidades()
        );
    }
}
