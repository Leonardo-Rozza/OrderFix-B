package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.config.tenant.TenantService;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.BadRequestException;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.ConflictException;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.ResourceNotFoundException;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.UnauthorizedException;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Cliente;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Cobro;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Reparacion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Repuesto;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Taller;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.User;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoCobro;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.MetodoPago;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PlanFeature;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.CobroRepository;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.ReparacionRepository;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.TallerQrCobroRepository;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.UserRepository;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.cobro.*;
import com.leonardorozza.mvgrreparacionesbackend.service.finanzas.CobroInvariantPolicy;
import com.leonardorozza.mvgrreparacionesbackend.service.finanzas.EstadoCuentaOrden;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Transactional
public class CobroService {

    private static final String MOTIVO_ANULACION_LEGADA = "Anulación mediante endpoint legado";
    private static final String LEYENDA_RESUMEN_DIGITAL =
            "Documento informativo. No es factura ni comprobante fiscal y no reemplaza los emitidos por ARCA.";

    private final CobroRepository cobroRepository;
    private final ReparacionRepository reparacionRepository;
    private final TallerQrCobroRepository tallerQrCobroRepository;
    private final UserRepository userRepository;
    private final TenantService tenantService;
    private final PlanFeatureService planFeatureService;
    private final CobroInvariantPolicy cobroInvariantPolicy;

    public CobroResponseDTO registrar(Long reparacionId, CobroRequestDTO request) {
        planFeatureService.requerir(PlanFeature.COBROS);
        Long tallerId = tenantService.currentTallerId();
        Reparacion reparacion = reparacionRepository.findByIdAndTallerIdForUpdate(reparacionId, tallerId)
                .orElseThrow(() -> new ResourceNotFoundException("Reparación no encontrada con ID: " + reparacionId));
        EstadoCuentaOrden estado = estadoCuenta(reparacion);
        cobroInvariantPolicy.validarNuevoCobro(reparacionId, estado, request.getMonto());

        Cobro cobro = Cobro.builder()
                .reparacion(reparacion)
                .taller(tenantService.currentTallerRef())
                .monto(request.getMonto())
                .metodo(request.getMetodo())
                .referencia(normalizarReferencia(request.getReferencia()))
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

        EstadoCuentaOrden estado = estadoCuenta(reparacion);

        return new CobrosReparacionDTO(
                estado.total(), estado.cobrado(), estado.saldo(), estado.excedente(),
                estado.requiereRevision(), estado.pagado(), cobros);
    }

    public CobroResponseDTO anular(
            Long reparacionId, Long cobroId, CobroAnulacionRequestDTO request) {
        planFeatureService.requerir(PlanFeature.COBROS);
        return anularInterno(reparacionId, cobroId, request.motivo(), false);
    }

    public void anularLegado(Long reparacionId, Long cobroId) {
        planFeatureService.requerir(PlanFeature.COBROS);
        anularInterno(reparacionId, cobroId, MOTIVO_ANULACION_LEGADA, true);
    }

    private CobroResponseDTO anularInterno(
            Long reparacionId, Long cobroId, String motivo, boolean idempotente) {
        Long tallerId = tenantService.currentTallerId();

        // Orden global de locks: reparación y luego cobro.
        reparacionRepository.findByIdAndTallerIdForUpdate(reparacionId, tallerId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Reparación no encontrada con ID: " + reparacionId));
        Cobro cobro = cobroRepository.findByIdAndReparacionIdAndTallerIdForUpdate(
                        cobroId, reparacionId, tallerId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Cobro no encontrado con ID: " + cobroId));

        if (cobro.estaAnulado()) {
            if (idempotente) {
                return toDTO(cobro);
            }
            throw new ConflictException(
                    "COBRO_YA_ANULADO",
                    "El cobro ya está anulado.",
                    Map.of("reparacionId", reparacionId, "cobroId", cobroId));
        }

        String motivoNormalizado = normalizarMotivo(motivo);
        cobro.anular(LocalDateTime.now(), usuarioActual(tallerId), motivoNormalizado);
        return toDTO(cobroRepository.save(cobro));
    }

    @Transactional(readOnly = true)
    public CajaResumenDTO caja(LocalDate desde, LocalDate hasta) {
        planFeatureService.requerir(PlanFeature.COBROS);
        Long tallerId = tenantService.currentTallerId();
        LocalDate d = desde != null ? desde : LocalDate.now();
        LocalDate h = hasta != null ? hasta : LocalDate.now();

        List<Cobro> cobros = cobroRepository.findByTallerIdAndAnuladoAtIsNullAndCreatedAtBetween(
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
    public ResumenDigitalOrdenDTO resumenDigital(Long reparacionId) {
        planFeatureService.requerir(PlanFeature.COBROS);
        Long tallerId = tenantService.currentTallerId();
        Reparacion r = reparacionRepository.findByIdAndTallerId(reparacionId, tallerId)
                .orElseThrow(() -> new ResourceNotFoundException("Reparación no encontrada con ID: " + reparacionId));

        Taller taller = r.getTaller();
        Cliente cliente = r.getEquipo().getCliente();

        List<ResumenDigitalOrdenDTO.RepuestoDTO> items = r.getRepuestos().stream()
                .sorted(Comparator.comparing(
                        Repuesto::getId, Comparator.nullsLast(Long::compareTo)))
                .map(rep -> {
                    BigDecimal precio = rep.getPrecio() == null ? BigDecimal.ZERO : rep.getPrecio();
                    int cant = Math.max(1, rep.getCantidad());
                    return new ResumenDigitalOrdenDTO.RepuestoDTO(rep.getNombre(), cant, precio,
                            precio.multiply(BigDecimal.valueOf(cant)));
                })
                .toList();

        BigDecimal manoDeObra = r.getPrecioFinal() != null ? r.getPrecioFinal()
                : (r.getPrecioEstimado() != null ? r.getPrecioEstimado() : BigDecimal.ZERO);
        List<Cobro> cobrosActivos = cobroRepository
                .findByReparacionIdAndTallerIdAndAnuladoAtIsNullOrderByCreatedAtAscIdAsc(
                        reparacionId, tallerId);
        BigDecimal cobrado = cobrosActivos.stream()
                .map(Cobro::getMonto)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        EstadoCuentaOrden estadoCuenta = EstadoCuentaOrden.de(r.calcularTotal(), cobrado);

        List<ResumenDigitalOrdenDTO.PagoDTO> pagos = cobrosActivos.stream()
                .map(cobro -> new ResumenDigitalOrdenDTO.PagoDTO(
                        cobro.getCreatedAt(), cobro.getMonto(), cobro.getMetodo(), cobro.getReferencia()))
                .toList();

        var resumenTaller = new ResumenDigitalOrdenDTO.TallerDTO(
                taller.getNombre(), taller.getTelefono());
        var resumenCliente = new ResumenDigitalOrdenDTO.ClienteDTO(
                cliente.getNombre(), cliente.getApellido(), cliente.getTelefono());
        var resumenEquipo = new ResumenDigitalOrdenDTO.EquipoDTO(
                r.getEquipo().getMarca(), r.getEquipo().getModelo(), r.getDescripcionProblema());
        var detalle = new ResumenDigitalOrdenDTO.DetalleDTO(
                items, manoDeObra, r.calcularTotalRepuestos());
        var importes = new ResumenDigitalOrdenDTO.ImportesDTO(
                estadoCuenta.total(), estadoCuenta.cobrado(), estadoCuenta.saldo(),
                estadoCuenta.excedente(), estadoCuenta.requiereRevision(), estadoCuenta.pagado());

        return new ResumenDigitalOrdenDTO(
                r.getNumeroOrden(),
                r.getCodigoSeguimiento(),
                r.getCreatedAt(),
                r.getEstado(),
                resumenTaller,
                resumenCliente,
                resumenEquipo,
                detalle,
                importes,
                pagos,
                datosCobroPublicos(estadoCuenta, taller, tallerId),
                false,
                LEYENDA_RESUMEN_DIGITAL,
                r.getId()
        );
    }

    private ResumenDigitalOrdenDTO.DatosCobroPublicosDTO datosCobroPublicos(
            EstadoCuentaOrden estadoCuenta, Taller taller, Long tallerId) {
        if (estadoCuenta.saldo().signum() <= 0 || !taller.isMostrarEnResumen()) {
            return null;
        }

        String qrVersion = tallerQrCobroRepository.findSha256ByTallerId(tallerId).orElse(null);
        boolean tieneDatoPublico = tieneTexto(taller.getAliasCobro())
                || tieneTexto(taller.getTitularCobro())
                || tieneTexto(taller.getEntidadCobro());
        if (!tieneDatoPublico && qrVersion == null) {
            return null;
        }

        return new ResumenDigitalOrdenDTO.DatosCobroPublicosDTO(
                taller.getAliasCobro(),
                taller.getTitularCobro(),
                taller.getEntidadCobro(),
                qrVersion != null,
                qrVersion);
    }

    private EstadoCuentaOrden estadoCuenta(Reparacion reparacion) {
        return EstadoCuentaOrden.de(
                reparacion.calcularTotal(),
                cobroRepository.sumByReparacionId(reparacion.getId()));
    }

    private CobroResponseDTO toDTO(Cobro c) {
        EstadoCobro estado = c.estaAnulado() ? EstadoCobro.ANULADO : EstadoCobro.ACTIVO;
        String anuladoPorNombre = c.getAnuladoPor() != null ? c.getAnuladoPor().getUsername() : null;
        return new CobroResponseDTO(
                c.getId(), c.getReparacion().getId(), c.getMonto(),
                c.getMetodo(), c.getReferencia(), c.getObservaciones(), c.getCreatedAt(),
                estado, c.getAnuladoAt(), anuladoPorNombre, c.getMotivoAnulacion());
    }

    private static String normalizarReferencia(String referencia) {
        if (referencia == null) {
            return null;
        }
        String normalizada = referencia.trim();
        return normalizada.isEmpty() ? null : normalizada;
    }

    private static String normalizarMotivo(String motivo) {
        String normalizado = motivo == null ? "" : motivo.trim();
        if (normalizado.isEmpty()) {
            throw new BadRequestException("El motivo de anulación es obligatorio.");
        }
        if (normalizado.length() > 255) {
            throw new BadRequestException("El motivo de anulación no puede superar los 255 caracteres.");
        }
        return normalizado;
    }

    private static boolean tieneTexto(String valor) {
        return valor != null && !valor.isBlank();
    }

    private User usuarioActual(Long tallerId) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !(authentication.getPrincipal() instanceof AuthenticatedUserPrincipal principal)
                || !Objects.equals(principal.getTallerId(), tallerId)) {
            throw new UnauthorizedException("No se pudo resolver el usuario autenticado.");
        }
        return userRepository.findByIdAndTallerId(principal.getUserId(), tallerId)
                .orElseThrow(() -> new UnauthorizedException(
                        "No se pudo resolver el usuario autenticado."));
    }
}
