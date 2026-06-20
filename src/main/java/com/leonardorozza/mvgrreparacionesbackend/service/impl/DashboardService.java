package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.config.tenant.TenantService;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.ResourceNotFoundException;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Suscripcion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoReparacion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PlanType;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.ReparacionRepository;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.SuscripcionRepository;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.DashboardResponseDto;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private final ReparacionRepository reparacionRepository;
    private final SuscripcionRepository suscripcionRepository;
    private final TenantService tenantService;

    @Value("${plan.free.max-reparaciones-mes:50}")
    private int freeMaxReparacionesMes;

    @Transactional(readOnly = true)
    public DashboardResponseDto obtener() {
        Long tallerId = tenantService.currentTallerId();

        Map<EstadoReparacion, Long> porEstado = new LinkedHashMap<>();
        for (EstadoReparacion estado : EstadoReparacion.values()) {
            porEstado.put(estado, reparacionRepository.countByTallerIdAndEstado(tallerId, estado));
        }

        long total = reparacionRepository.countByTallerId(tallerId);

        LocalDateTime inicioMes = LocalDate.now().withDayOfMonth(1).atStartOfDay();
        long esteMes = reparacionRepository.countByTallerIdAndCreatedAtAfter(tallerId, inicioMes);

        long listos = porEstado.getOrDefault(EstadoReparacion.COMPLETADO, 0L);

        Suscripcion suscripcion = suscripcionRepository.findByTallerId(tallerId)
                .orElseThrow(() -> new ResourceNotFoundException("El taller no tiene una suscripción asociada."));

        Integer limite = (suscripcion.getPlan() == PlanType.PRO) ? null : freeMaxReparacionesMes;

        return new DashboardResponseDto(
                porEstado,
                total,
                esteMes,
                listos,
                suscripcion.getPlan(),
                suscripcion.getEstado(),
                limite
        );
    }
}
