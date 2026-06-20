package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.config.tenant.TenantService;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.ResourceNotFoundException;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Cliente;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Cobro;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Reparacion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Taller;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.MetodoPago;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PlanFeature;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.CobroRepository;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.ReparacionRepository;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.cobro.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional
public class CobroService {

    private final CobroRepository cobroRepository;
    private final ReparacionRepository reparacionRepository;
    private final TenantService tenantService;
    private final PlanFeatureService planFeatureService;

    public CobroResponseDTO registrar(Long reparacionId, CobroRequestDTO request) {
        planFeatureService.requerir(PlanFeature.COBROS);
        Long tallerId = tenantService.currentTallerId();
        Reparacion reparacion = reparacionRepository.findByIdAndTallerId(reparacionId, tallerId)
                .orElseThrow(() -> new ResourceNotFoundException("Reparación no encontrada con ID: " + reparacionId));

        Cobro cobro = Cobro.builder()
                .reparacion(reparacion)
                .taller(tenantService.currentTallerRef())
                .monto(request.getMonto())
                .metodo(request.getMetodo())
                .observaciones(request.getObservaciones())
                .build();

        return toDTO(cobroRepository.save(cobro));
    }

    @Transactional(readOnly = true)
    public CobrosReparacionDTO listarPorReparacion(Long reparacionId) {
        planFeatureService.requerir(PlanFeature.COBROS);
        Long tallerId = tenantService.currentTallerId();
        Reparacion reparacion = reparacionRepository.findByIdAndTallerId(reparacionId, tallerId)
                .orElseThrow(() -> new ResourceNotFoundException("Reparación no encontrada con ID: " + reparacionId));

        List<CobroResponseDTO> cobros = cobroRepository
                .findByReparacionIdAndTallerIdOrderByCreatedAtDesc(reparacionId, tallerId)
                .stream().map(this::toDTO).toList();

        BigDecimal total = reparacion.calcularTotal();
        BigDecimal cobrado = cobroRepository.sumByReparacionId(reparacionId);
        BigDecimal saldo = total.subtract(cobrado);

        return new CobrosReparacionDTO(total, cobrado, saldo, saldo.signum() <= 0, cobros);
    }

    public void eliminar(Long cobroId) {
        planFeatureService.requerir(PlanFeature.COBROS);
        Cobro cobro = cobroRepository.findByIdAndTallerId(cobroId, tenantService.currentTallerId())
                .orElseThrow(() -> new ResourceNotFoundException("Cobro no encontrado con ID: " + cobroId));
        cobroRepository.delete(cobro);
    }

    @Transactional(readOnly = true)
    public CajaResumenDTO caja(LocalDate desde, LocalDate hasta) {
        planFeatureService.requerir(PlanFeature.COBROS);
        Long tallerId = tenantService.currentTallerId();
        LocalDate d = desde != null ? desde : LocalDate.now();
        LocalDate h = hasta != null ? hasta : LocalDate.now();

        List<Cobro> cobros = cobroRepository.findByTallerIdAndCreatedAtBetween(
                tallerId, d.atStartOfDay(), h.plusDays(1).atStartOfDay());

        Map<MetodoPago, BigDecimal> porMetodo = new LinkedHashMap<>();
        for (MetodoPago m : MetodoPago.values()) {
            porMetodo.put(m, BigDecimal.ZERO);
        }
        BigDecimal total = BigDecimal.ZERO;
        for (Cobro c : cobros) {
            total = total.add(c.getMonto());
            porMetodo.merge(c.getMetodo(), c.getMonto(), BigDecimal::add);
        }

        List<CobroResponseDTO> dtos = cobros.stream().map(this::toDTO).toList();
        return new CajaResumenDTO(d, h, total, cobros.size(), porMetodo, dtos);
    }

    @Transactional(readOnly = true)
    public ReciboDTO recibo(Long reparacionId) {
        planFeatureService.requerir(PlanFeature.COBROS);
        Long tallerId = tenantService.currentTallerId();
        Reparacion r = reparacionRepository.findByIdAndTallerId(reparacionId, tallerId)
                .orElseThrow(() -> new ResourceNotFoundException("Reparación no encontrada con ID: " + reparacionId));

        Taller taller = r.getTaller();
        Cliente cliente = r.getEquipo().getCliente();

        List<ReciboDTO.ItemReciboDTO> items = r.getRepuestos().stream()
                .map(rep -> {
                    BigDecimal precio = rep.getPrecio() == null ? BigDecimal.ZERO : rep.getPrecio();
                    int cant = Math.max(1, rep.getCantidad());
                    return new ReciboDTO.ItemReciboDTO(rep.getNombre(), cant, precio,
                            precio.multiply(BigDecimal.valueOf(cant)));
                })
                .toList();

        BigDecimal manoDeObra = r.getPrecioFinal() != null ? r.getPrecioFinal()
                : (r.getPrecioEstimado() != null ? r.getPrecioEstimado() : BigDecimal.ZERO);
        BigDecimal total = r.calcularTotal();
        BigDecimal cobrado = cobroRepository.sumByReparacionId(reparacionId);
        BigDecimal saldo = total.subtract(cobrado);

        return new ReciboDTO(
                r.getId(),
                r.getCodigoSeguimiento(),
                r.getEstado(),
                taller.getNombre(),
                taller.getTelefono(),
                cliente.getNombre(),
                cliente.getApellido(),
                cliente.getTelefono(),
                r.getEquipo().getMarca(),
                r.getEquipo().getModelo(),
                r.getDescripcionProblema(),
                items,
                manoDeObra,
                r.calcularTotalRepuestos(),
                total,
                cobrado,
                saldo,
                saldo.signum() <= 0,
                r.getCreatedAt()
        );
    }

    private CobroResponseDTO toDTO(Cobro c) {
        return new CobroResponseDTO(
                c.getId(), c.getReparacion().getId(), c.getMonto(),
                c.getMetodo(), c.getObservaciones(), c.getCreatedAt());
    }
}
