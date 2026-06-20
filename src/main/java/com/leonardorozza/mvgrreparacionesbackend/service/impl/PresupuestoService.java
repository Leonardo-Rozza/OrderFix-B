package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.config.tenant.TenantService;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.BadRequestException;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.ResourceNotFoundException;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.ItemPresupuesto;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Presupuesto;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Reparacion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoPresupuesto;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.PresupuestoRepository;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.ReparacionRepository;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.presupuesto.ItemPresupuestoDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.presupuesto.PresupuestoRequestDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.presupuesto.PresupuestoResponseDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class PresupuestoService {

    private final PresupuestoRepository presupuestoRepository;
    private final ReparacionRepository reparacionRepository;
    private final TenantService tenantService;

    // ===== Lado taller (autenticado) =====

    public PresupuestoResponseDTO crear(Long reparacionId, PresupuestoRequestDTO request) {
        Long tallerId = tenantService.currentTallerId();
        Reparacion reparacion = reparacionRepository.findByIdAndTallerId(reparacionId, tallerId)
                .orElseThrow(() -> new ResourceNotFoundException("Reparación no encontrada con ID: " + reparacionId));

        List<ItemPresupuesto> items = request.getItems().stream()
                .map(i -> ItemPresupuesto.builder()
                        .descripcion(i.descripcion())
                        .cantidad(i.cantidad())
                        .precioUnitario(i.precioUnitario())
                        .build())
                .toList();

        Presupuesto presupuesto = Presupuesto.builder()
                .reparacion(reparacion)
                .taller(tenantService.currentTallerRef())
                .estado(EstadoPresupuesto.PENDIENTE)
                .items(new java.util.ArrayList<>(items))
                .total(calcularTotal(items))
                .observaciones(request.getObservaciones())
                .build();

        return toDTO(presupuestoRepository.save(presupuesto));
    }

    @Transactional(readOnly = true)
    public List<PresupuestoResponseDTO> listarPorReparacion(Long reparacionId) {
        Long tallerId = tenantService.currentTallerId();
        if (!reparacionRepository.existsByIdAndTallerId(reparacionId, tallerId)) {
            throw new ResourceNotFoundException("Reparación no encontrada con ID: " + reparacionId);
        }
        return presupuestoRepository.findByReparacionIdAndTallerIdOrderByCreatedAtDesc(reparacionId, tallerId)
                .stream().map(this::toDTO).toList();
    }

    // ===== Lado cliente (público, vía código de seguimiento) =====

    public PresupuestoResponseDTO responderPorCodigo(String codigo, boolean aprobar) {
        Reparacion reparacion = reparacionRepository.findByCodigoSeguimiento(codigo)
                .orElseThrow(() -> new ResourceNotFoundException("No encontramos una reparación con ese código."));

        Presupuesto presupuesto = presupuestoRepository
                .findFirstByReparacionIdAndEstadoOrderByCreatedAtDesc(reparacion.getId(), EstadoPresupuesto.PENDIENTE)
                .orElseThrow(() -> new BadRequestException("No hay un presupuesto pendiente para responder."));

        presupuesto.setEstado(aprobar ? EstadoPresupuesto.APROBADO : EstadoPresupuesto.RECHAZADO);
        presupuesto.setFechaRespuesta(LocalDateTime.now());
        return toDTO(presupuestoRepository.save(presupuesto));
    }

    /** Último presupuesto de la reparación (para mostrar en el seguimiento público). Puede ser null. */
    @Transactional(readOnly = true)
    public PresupuestoResponseDTO ultimoDeReparacion(Long reparacionId) {
        return presupuestoRepository.findFirstByReparacionIdOrderByCreatedAtDesc(reparacionId)
                .map(this::toDTO)
                .orElse(null);
    }

    // ===== Helpers =====

    private BigDecimal calcularTotal(List<ItemPresupuesto> items) {
        return items.stream()
                .map(i -> i.getPrecioUnitario().multiply(BigDecimal.valueOf(i.getCantidad())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private PresupuestoResponseDTO toDTO(Presupuesto p) {
        List<ItemPresupuestoDTO> items = p.getItems().stream()
                .map(i -> new ItemPresupuestoDTO(i.getDescripcion(), i.getCantidad(), i.getPrecioUnitario()))
                .toList();
        return new PresupuestoResponseDTO(
                p.getId(),
                p.getReparacion().getId(),
                p.getEstado(),
                items,
                p.getTotal(),
                p.getObservaciones(),
                p.getFechaRespuesta(),
                p.getCreatedAt()
        );
    }
}
