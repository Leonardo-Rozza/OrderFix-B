package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.config.tenant.TenantService;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.BadRequestException;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.ResourceNotFoundException;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.ItemPresupuesto;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Presupuesto;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Reparacion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoPresupuesto;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoReparacion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoItemPresupuesto;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoPresupuesto;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TransicionesEstado;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.PresupuestoRepository;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.ReparacionRepository;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.presupuesto.ItemPresupuestoDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.presupuesto.PresupuestoRequestDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.presupuesto.PresupuestoResponseDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class PresupuestoService {

    private final PresupuestoRepository presupuestoRepository;
    private final ReparacionRepository reparacionRepository;
    private final TenantService tenantService;

    @Value("${presupuesto.validez-dias-default:7}")
    private int validezDiasDefault;

    // ===== Lado taller (autenticado) =====

    public PresupuestoResponseDTO crear(Long reparacionId, PresupuestoRequestDTO request) {
        Long tallerId = tenantService.currentTallerId();
        Reparacion reparacion = reparacionRepository.findByIdAndTallerId(reparacionId, tallerId)
                .orElseThrow(() -> new ResourceNotFoundException("Reparación no encontrada con ID: " + reparacionId));

        TipoPresupuesto tipo = request.getTipo() != null ? request.getTipo() : TipoPresupuesto.ORIGINAL;
        int validezDias = request.getValidezDias() != null ? request.getValidezDias() : validezDiasDefault;

        Presupuesto presupuesto = construir(reparacion, mapItems(request.getItems()), tipo,
                validezDias, request.getObservaciones());
        Presupuesto guardado = presupuestoRepository.save(presupuesto);

        // Auto-estado de la reparación (best-effort: solo si la transición es legal)
        moverEstadoAlCrear(reparacion, tipo);

        return toDTO(guardado);
    }

    /**
     * Re-presupuestar: clona un presupuesto (típicamente vencido) en uno nuevo PENDIENTE
     * con validez fresca. Si el request trae ítems nuevos, los usa (precios actualizados);
     * si no, copia los del original.
     */
    public PresupuestoResponseDTO represupuestar(Long reparacionId, Long presupuestoId, PresupuestoRequestDTO request) {
        Long tallerId = tenantService.currentTallerId();
        Presupuesto original = presupuestoRepository
                .findByIdAndReparacionIdAndTallerId(presupuestoId, reparacionId, tallerId)
                .orElseThrow(() -> new ResourceNotFoundException("Presupuesto no encontrado con ID: " + presupuestoId));

        Reparacion reparacion = original.getReparacion();

        boolean traeItems = request != null && request.getItems() != null && !request.getItems().isEmpty();
        List<ItemPresupuesto> items = traeItems ? mapItems(request.getItems()) : copiarItems(original.getItems());

        TipoPresupuesto tipo = (request != null && request.getTipo() != null) ? request.getTipo() : original.getTipo();
        int validezDias = (request != null && request.getValidezDias() != null)
                ? request.getValidezDias() : validezDiasDefault;
        String observaciones = (request != null && request.getObservaciones() != null)
                ? request.getObservaciones() : original.getObservaciones();

        Presupuesto nuevo = presupuestoRepository.save(
                construir(reparacion, items, tipo, validezDias, observaciones));
        moverEstadoAlCrear(reparacion, tipo);
        return toDTO(nuevo);
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

    /** El taller responde el presupuesto (ej: el cliente lo aprueba en persona). */
    public PresupuestoResponseDTO responder(Long reparacionId, Long presupuestoId, boolean aprobar) {
        Long tallerId = tenantService.currentTallerId();
        Presupuesto presupuesto = presupuestoRepository
                .findByIdAndReparacionIdAndTallerId(presupuestoId, reparacionId, tallerId)
                .orElseThrow(() -> new ResourceNotFoundException("Presupuesto no encontrado con ID: " + presupuestoId));
        return responderInterno(presupuesto, aprobar);
    }

    // ===== Lado cliente (público, vía código de seguimiento) =====

    public PresupuestoResponseDTO responderPorCodigo(String codigo, boolean aprobar) {
        Reparacion reparacion = reparacionRepository.findByCodigoSeguimiento(codigo)
                .orElseThrow(() -> new ResourceNotFoundException("No encontramos una reparación con ese código."));

        Presupuesto presupuesto = presupuestoRepository
                .findFirstByReparacionIdAndEstadoOrderByCreatedAtDesc(reparacion.getId(), EstadoPresupuesto.PENDIENTE)
                .orElseThrow(() -> new BadRequestException("No hay un presupuesto pendiente para responder."));

        return responderInterno(presupuesto, aprobar);
    }

    /** Último presupuesto de la reparación (para mostrar en el seguimiento público). Puede ser null. */
    @Transactional(readOnly = true)
    public PresupuestoResponseDTO ultimoDeReparacion(Long reparacionId) {
        return presupuestoRepository.findFirstByReparacionIdOrderByCreatedAtDesc(reparacionId)
                .map(this::toDTO)
                .orElse(null);
    }

    // ===== Helpers =====

    private PresupuestoResponseDTO responderInterno(Presupuesto presupuesto, boolean aprobar) {
        if (presupuesto.getEstado() != EstadoPresupuesto.PENDIENTE) {
            throw new BadRequestException("El presupuesto ya fue respondido.");
        }
        if (presupuesto.isVencido()) {
            throw new BadRequestException("El presupuesto está vencido; generá uno nuevo (re-presupuestar).");
        }

        presupuesto.setEstado(aprobar ? EstadoPresupuesto.APROBADO : EstadoPresupuesto.RECHAZADO);
        presupuesto.setFechaRespuesta(LocalDateTime.now());
        Presupuesto guardado = presupuestoRepository.save(presupuesto);

        moverEstadoAlResponder(presupuesto.getReparacion(), presupuesto.getTipo(), aprobar);
        return toDTO(guardado);
    }

    private Presupuesto construir(Reparacion reparacion, List<ItemPresupuesto> items, TipoPresupuesto tipo,
                                  int validezDias, String observaciones) {
        return Presupuesto.builder()
                .reparacion(reparacion)
                .taller(tenantService.currentTallerRef())
                .estado(EstadoPresupuesto.PENDIENTE)
                .tipo(tipo)
                .items(new ArrayList<>(items))
                .total(calcularTotal(items))
                .validezDias(validezDias)
                .validoHasta(LocalDateTime.now().plusDays(validezDias))
                .observaciones(observaciones)
                .build();
    }

    private List<ItemPresupuesto> mapItems(List<ItemPresupuestoDTO> items) {
        return items.stream()
                .map(i -> ItemPresupuesto.builder()
                        .descripcion(i.descripcion())
                        .cantidad(i.cantidad())
                        .precioUnitario(i.precioUnitario())
                        .tipoItem(i.tipoItem() != null ? i.tipoItem() : TipoItemPresupuesto.MANO_DE_OBRA)
                        .calidad(i.calidad())
                        .build())
                .toList();
    }

    private List<ItemPresupuesto> copiarItems(List<ItemPresupuesto> items) {
        return items.stream()
                .map(i -> ItemPresupuesto.builder()
                        .descripcion(i.getDescripcion())
                        .cantidad(i.getCantidad())
                        .precioUnitario(i.getPrecioUnitario())
                        .tipoItem(i.getTipoItem())
                        .calidad(i.getCalidad())
                        .build())
                .toList();
    }

    /** Crear ORIGINAL → PRESUPUESTADO; crear ADICIONAL → ESPERANDO_ADICIONAL (si la transición es legal). */
    private void moverEstadoAlCrear(Reparacion reparacion, TipoPresupuesto tipo) {
        EstadoReparacion destino = (tipo == TipoPresupuesto.ADICIONAL)
                ? EstadoReparacion.ESPERANDO_ADICIONAL
                : EstadoReparacion.PRESUPUESTADO;
        intentarTransicion(reparacion, destino);
    }

    /** Aprobar → EN_PROCESO; rechazar original → LISTO_SIN_REPARAR; rechazar adicional → EN_PROCESO. */
    private void moverEstadoAlResponder(Reparacion reparacion, TipoPresupuesto tipo, boolean aprobar) {
        EstadoReparacion destino;
        if (aprobar) {
            destino = EstadoReparacion.EN_PROCESO;
        } else {
            destino = (tipo == TipoPresupuesto.ADICIONAL)
                    ? EstadoReparacion.EN_PROCESO          // se sigue con lo ya aprobado
                    : EstadoReparacion.LISTO_SIN_REPARAR;  // rechazó el trabajo
        }
        intentarTransicion(reparacion, destino);
    }

    private void intentarTransicion(Reparacion reparacion, EstadoReparacion destino) {
        if (reparacion.getEstado() != destino
                && TransicionesEstado.permitida(reparacion.getEstado(), destino)) {
            reparacion.setEstado(destino);
            reparacionRepository.save(reparacion);
        }
    }

    private BigDecimal calcularTotal(List<ItemPresupuesto> items) {
        return items.stream()
                .map(this::subtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal totalPorTipo(List<ItemPresupuesto> items, TipoItemPresupuesto tipoItem) {
        return items.stream()
                .filter(i -> i.getTipoItem() == tipoItem)
                .map(this::subtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal subtotal(ItemPresupuesto i) {
        return i.getPrecioUnitario().multiply(BigDecimal.valueOf(i.getCantidad()));
    }

    private PresupuestoResponseDTO toDTO(Presupuesto p) {
        List<ItemPresupuestoDTO> items = p.getItems().stream()
                .map(i -> new ItemPresupuestoDTO(i.getDescripcion(), i.getCantidad(),
                        i.getPrecioUnitario(), i.getTipoItem(), i.getCalidad()))
                .toList();
        return new PresupuestoResponseDTO(
                p.getId(),
                p.getReparacion().getId(),
                p.getEstadoEfectivo(),
                p.getTipo(),
                items,
                p.getTotal(),
                totalPorTipo(p.getItems(), TipoItemPresupuesto.MANO_DE_OBRA),
                totalPorTipo(p.getItems(), TipoItemPresupuesto.REPUESTO),
                p.getValidezDias(),
                p.getValidoHasta(),
                p.isVencido(),
                p.getObservaciones(),
                p.getFechaRespuesta(),
                p.getCreatedAt()
        );
    }
}
