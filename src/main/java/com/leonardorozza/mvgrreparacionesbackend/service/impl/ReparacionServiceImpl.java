package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.config.tenant.TenantService;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.BadRequestException;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.ResourceNotFoundException;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Cliente;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Equipo;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.FotoReparacion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Reparacion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Taller;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.CuentaVinculada;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoPago;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoReparacion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.MomentoFoto;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TransicionesEstado;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.User;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.ArticuloRepository;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.ClienteRepository;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.CobroRepository;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.EquipoRepository;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.ReparacionRepository;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.TallerRepository;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.UserRepository;
import com.leonardorozza.mvgrreparacionesbackend.service.ReparacionService;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.reparacion.FotoDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.reparacion.IngresoRapidoRequestDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.reparacion.IngresoRapidoResponseDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.reparacion.ReparacionRequestDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.reparacion.ReparacionResponseDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.reparacion.WhatsappLinkDTO;
import com.leonardorozza.mvgrreparacionesbackend.utils.mapper.ReparacionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional
public class ReparacionServiceImpl implements ReparacionService {

    private final ReparacionRepository reparacionRepository;
    private final EquipoRepository equipoRepository;
    private final ClienteRepository clienteRepository;
    private final UserRepository userRepository;
    private final CobroRepository cobroRepository;
    private final ArticuloRepository articuloRepository;
    private final TallerRepository tallerRepository;
    private final ReparacionMapper reparacionMapper;
    private final TenantService tenantService;
    private final PlanLimitService planLimitService;

    @Value("${app.public-url:http://localhost:5173}")
    private String publicUrl;

    private static final SecureRandom RANDOM = new SecureRandom();
    // Sin caracteres ambiguos (O/0, I/1) para dictarlo por teléfono
    private static final String ALFABETO = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    @Override
    public ReparacionResponseDTO crear(ReparacionRequestDTO request) {
        Long tallerId = tenantService.currentTallerId();

        // Gating freemium: respeta el tope del plan / suscripción vigente
        planLimitService.registrarUsoReparacion(tallerId);

        Equipo equipo = equipoRepository.findByIdAndTallerId(request.getEquipoId(), tallerId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Equipo no encontrado con ID: " + request.getEquipoId()));

        Reparacion reparacion = reparacionMapper.toEntity(request);
        reparacion.setEquipo(equipo);
        reparacion.setTaller(tenantService.currentTallerRef());

        // Si el usuario no envía estado → equipo recién ingresado
        if (request.getEstado() == null) {
            reparacion.setEstado(EstadoReparacion.INGRESADO);
        }
        // Cuenta vinculada: el mapper la ignora; default NINGUNA si no vino.
        reparacion.setTieneCuentaVinculada(request.getTieneCuentaVinculada() != null
                ? request.getTieneCuentaVinculada() : CuentaVinculada.NINGUNA);
        reparacion.setNumeroOrden(generarNumeroOrden(tallerId));
        reparacion.setTecnico(resolverTecnico(request.getTecnicoId(), tallerId));
        if (reparacion.getFotos() == null) {
            reparacion.setFotos(new ArrayList<>());
        }
        reparacion.getFotos().addAll(mapFotos(request.getFotos()));
        reparacion.setCodigoSeguimiento(generarCodigoSeguimiento());

        Reparacion guardada = reparacionRepository.save(reparacion);

        return toDtoConPago(guardada);
    }

    @Override
    public IngresoRapidoResponseDTO crearIngresoRapido(IngresoRapidoRequestDTO request) {
        Long tallerId = tenantService.currentTallerId();

        // Gating freemium: respeta el tope del plan / suscripción vigente
        planLimitService.registrarUsoReparacion(tallerId);

        // 1) Cliente: si ya existe uno con ese teléfono en el taller, lo reutilizamos
        //    (evita duplicados); si no, lo creamos con los datos mínimos.
        boolean clienteNuevo = false;
        Cliente cliente = clienteRepository
                .findByTelefonoAndTallerId(request.getClienteTelefono(), tallerId)
                .orElse(null);

        if (cliente == null) {
            cliente = new Cliente();
            cliente.setNombre(request.getClienteNombre());
            // apellido es NOT NULL en DB; en carga rápida puede venir vacío y completarse luego
            cliente.setApellido(request.getClienteApellido() != null ? request.getClienteApellido() : "");
            cliente.setTelefono(request.getClienteTelefono());
            cliente.setTaller(tenantService.currentTallerRef());
            cliente = clienteRepository.save(cliente);
            clienteNuevo = true;
        }

        // 2) Equipo nuevo bajo ese cliente
        Equipo equipo = new Equipo();
        equipo.setMarca(request.getEquipoMarca());
        equipo.setModelo(request.getEquipoModelo());
        equipo.setCliente(cliente);
        equipo.setTaller(tenantService.currentTallerRef());
        equipo = equipoRepository.save(equipo);

        // 3) Reparación
        Reparacion reparacion = new Reparacion();
        reparacion.setDescripcionProblema(request.getDescripcionProblema());
        reparacion.setPrecioEstimado(request.getPrecioEstimado());
        reparacion.setEstado(EstadoReparacion.INGRESADO);
        reparacion.setCodigoSeguimiento(generarCodigoSeguimiento());
        reparacion.setNumeroOrden(generarNumeroOrden(tallerId));
        reparacion.setEquipo(equipo);
        reparacion.setTaller(tenantService.currentTallerRef());
        Reparacion guardada = reparacionRepository.save(reparacion);

        return new IngresoRapidoResponseDTO(
                cliente.getId(),
                equipo.getId(),
                clienteNuevo,
                toDtoConPago(guardada)
        );
    }

    @Override
    public ReparacionResponseDTO actualizar(Long id, ReparacionRequestDTO request) {
        Long tallerId = tenantService.currentTallerId();

        Reparacion reparacion = reparacionRepository.findByIdAndTallerId(id, tallerId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Reparación no encontrada con ID: " + id));

        Equipo equipo = equipoRepository.findByIdAndTallerId(request.getEquipoId(), tallerId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Equipo no encontrado con ID: " + request.getEquipoId()));

        reparacion.setEquipo(equipo);
        reparacion.setDescripcionProblema(request.getDescripcionProblema());
        // No pisamos el estado con null: si el request no trae estado, conservamos el actual.
        // Si trae un estado distinto, debe ser una transición válida desde el actual.
        if (request.getEstado() != null) {
            TransicionesEstado.validar(reparacion.getEstado(), request.getEstado());
            reparacion.setEstado(request.getEstado());
        }
        reparacion.setPrecioEstimado(request.getPrecioEstimado());
        reparacion.setPrecioFinal(request.getPrecioFinal());
        reparacion.setFechaIngreso(request.getFechaIngreso());
        reparacion.setFechaEstimadaEntrega(request.getFechaEstimadaEntrega());
        reparacion.setFechaEntrega(request.getFechaEntrega());

        // Orden de trabajo ampliada
        reparacion.setPatronDesbloqueo(request.getPatronDesbloqueo());
        reparacion.setPinDesbloqueo(request.getPinDesbloqueo());
        reparacion.setAccesorios(request.getAccesorios());
        reparacion.setCondicionesIngreso(request.getCondicionesIngreso());
        reparacion.setObservaciones(request.getObservaciones());

        // Flags de riesgo del ingreso
        reparacion.setMojado(request.isMojado());
        reparacion.setTrabajoEnPlaca(request.isTrabajoEnPlaca());
        reparacion.setNoTesteableAlIngreso(request.isNoTesteableAlIngreso());
        reparacion.setTieneBloqueoPantalla(request.isTieneBloqueoPantalla());
        reparacion.setTieneCuentaVinculada(request.getTieneCuentaVinculada() != null
                ? request.getTieneCuentaVinculada() : CuentaVinculada.NINGUNA);
        reparacion.setClienteConoceCredenciales(request.isClienteConoceCredenciales());

        reparacion.setTecnico(resolverTecnico(request.getTecnicoId(), tallerId));
        if (request.getFotos() != null) {
            if (reparacion.getFotos() == null) {
                reparacion.setFotos(new ArrayList<>());
            }
            reparacion.getFotos().clear();
            reparacion.getFotos().addAll(mapFotos(request.getFotos()));
        }

        // Conformidad de entrega: explícita por request, o auto al quedar ENTREGADO.
        if (request.getFechaConformidadEntrega() != null) {
            reparacion.setFechaConformidadEntrega(request.getFechaConformidadEntrega());
        }
        marcarConformidadSiEntregado(reparacion);

        return toDtoConPago(reparacionRepository.save(reparacion));
    }

    @Override
    public ReparacionResponseDTO cambiarEstado(Long id, EstadoReparacion nuevoEstado) {
        Reparacion reparacion = reparacionRepository.findByIdAndTallerId(id, tenantService.currentTallerId())
                .orElseThrow(() ->
                        new ResourceNotFoundException("Reparación no encontrada con ID: " + id));

        TransicionesEstado.validar(reparacion.getEstado(), nuevoEstado);
        reparacion.setEstado(nuevoEstado);
        marcarConformidadSiEntregado(reparacion);

        return toDtoConPago(reparacionRepository.save(reparacion));
    }

    @Override
    @Transactional(readOnly = true)
    public ReparacionResponseDTO obtenerPorId(Long id) {
        Reparacion reparacion = reparacionRepository.findByIdAndTallerId(id, tenantService.currentTallerId())
                .orElseThrow(() ->
                        new ResourceNotFoundException("Reparación no encontrada con ID: " + id));

        return toDtoConPago(reparacion);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ReparacionResponseDTO> listar(String q, EstadoReparacion estado, Pageable pageable) {
        Page<ReparacionResponseDTO> page = reparacionRepository
                .search(tenantService.currentTallerId(), q, estado, pageable)
                .map(reparacionMapper::toDTO);
        aplicarPagoBatch(page.getContent());
        return page;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReparacionResponseDTO> listarPorEquipo(Long equipoId) {
        Long tallerId = tenantService.currentTallerId();

        if (!equipoRepository.existsByIdAndTallerId(equipoId, tallerId)) {
            throw new ResourceNotFoundException("Equipo no encontrado con ID: " + equipoId);
        }

        return aplicarPagoBatch(reparacionRepository.findByEquipoIdAndTallerId(equipoId, tallerId)
                .stream()
                .map(reparacionMapper::toDTO)
                .toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReparacionResponseDTO> listarPorEstado(EstadoReparacion estado) {
        return aplicarPagoBatch(reparacionRepository.findByEstadoAndTallerId(estado, tenantService.currentTallerId())
                .stream()
                .map(reparacionMapper::toDTO)
                .toList());
    }

    // ---- Estado de pago (dimensión derivada de los cobros) ----

    /** Mapea una reparación a DTO y lo enriquece con cobrado/saldo/estadoPago. */
    private ReparacionResponseDTO toDtoConPago(Reparacion entity) {
        ReparacionResponseDTO dto = reparacionMapper.toDTO(entity);
        aplicarPago(dto, cobroRepository.sumByReparacionId(entity.getId()));
        return dto;
    }

    private void aplicarPago(ReparacionResponseDTO dto, BigDecimal cobrado) {
        BigDecimal total = dto.getTotal() != null ? dto.getTotal() : BigDecimal.ZERO;
        BigDecimal c = cobrado != null ? cobrado : BigDecimal.ZERO;
        BigDecimal saldo = total.subtract(c);
        dto.setCobrado(c);
        dto.setSaldo(saldo.signum() > 0 ? saldo : BigDecimal.ZERO);
        dto.setEstadoPago(EstadoPago.de(total, c));
    }

    /** Enriquece una lista de DTOs con una sola query de cobros (sin N+1). */
    private List<ReparacionResponseDTO> aplicarPagoBatch(List<ReparacionResponseDTO> dtos) {
        if (dtos.isEmpty()) {
            return dtos;
        }
        List<Long> ids = dtos.stream().map(ReparacionResponseDTO::getId).toList();
        Map<Long, BigDecimal> cobrados = new HashMap<>();
        for (Object[] row : cobroRepository.sumByReparacionIds(ids)) {
            cobrados.put((Long) row[0], toBigDecimal(row[1]));
        }
        dtos.forEach(d -> aplicarPago(d, cobrados.get(d.getId())));
        return dtos;
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return value instanceof BigDecimal b ? b : new BigDecimal(value.toString());
    }

    @Override
    public void eliminar(Long id) {
        Reparacion reparacion = reparacionRepository.findByIdAndTallerId(id, tenantService.currentTallerId())
                .orElseThrow(() ->
                        new ResourceNotFoundException("Reparación no encontrada con ID: " + id));

        // Protege el historial/caja: una reparación con cobros no se borra (anular cobros primero)
        if (cobroRepository.existsByReparacionId(id)) {
            throw new BadRequestException(
                    "No podés borrar una reparación con cobros registrados (afectaría la caja). "
                            + "Anulá los cobros primero.");
        }

        // Devuelve al inventario el stock de los repuestos enlazados a un artículo
        for (var repuesto : reparacion.getRepuestos()) {
            if (repuesto.getArticulo() != null) {
                var articulo = repuesto.getArticulo();
                articulo.setStock(articulo.getStock() + repuesto.getCantidad());
                articuloRepository.save(articulo);
            }
        }

        // Cascade JPA: repuestos, presupuestos y fotos se borran junto con la reparación
        reparacionRepository.delete(reparacion);
    }

    @Override
    @Transactional(readOnly = true)
    public WhatsappLinkDTO linkWhatsapp(Long id) {
        Reparacion r = reparacionRepository.findByIdAndTallerId(id, tenantService.currentTallerId())
                .orElseThrow(() -> new ResourceNotFoundException("Reparación no encontrada con ID: " + id));

        var cliente = r.getEquipo().getCliente();
        String tel = cliente.getTelefono() == null ? "" : cliente.getTelefono().replaceAll("[^0-9]", "");
        String track = publicUrl + "/seguimiento/" + r.getCodigoSeguimiento();

        String estadoTxt = switch (r.getEstado()) {
            case COMPLETADO -> "lista para retirar";
            case ENTREGADO -> "entregada";
            default -> "en estado " + r.getEstado();
        };
        String mensaje = "Hola " + cliente.getNombre() + "! Tu " + r.getEquipo().getMarca() + " "
                + r.getEquipo().getModelo() + " está " + estadoTxt + ". Seguí el estado acá: " + track;

        String url = "https://wa.me/" + tel + "?text=" + URLEncoder.encode(mensaje, StandardCharsets.UTF_8);
        return new WhatsappLinkDTO(url, tel, mensaje, track);
    }

    /** Resuelve el técnico (usuario del taller) validando que pertenezca al taller actual. */
    private User resolverTecnico(Long tecnicoId, Long tallerId) {
        if (tecnicoId == null) {
            return null;
        }
        return userRepository.findByIdAndTallerId(tecnicoId, tallerId)
                .orElseThrow(() -> new ResourceNotFoundException("Técnico no encontrado con ID: " + tecnicoId));
    }

    private String generarCodigoSeguimiento() {
        String codigo;
        do {
            StringBuilder sb = new StringBuilder(8);
            for (int i = 0; i < 8; i++) {
                sb.append(ALFABETO.charAt(RANDOM.nextInt(ALFABETO.length())));
            }
            codigo = sb.toString();
        } while (reparacionRepository.existsByCodigoSeguimiento(codigo));
        return codigo;
    }

    /** Mapea las fotos del request a entidad; momento default INGRESO si no vino. */
    private List<FotoReparacion> mapFotos(List<FotoDTO> fotos) {
        if (fotos == null) {
            return List.of();
        }
        return fotos.stream()
                .map(f -> FotoReparacion.builder()
                        .url(f.url())
                        .momento(f.momento() != null ? f.momento() : MomentoFoto.INGRESO)
                        .build())
                .toList();
    }

    /** Si la reparación quedó ENTREGADA y no hay conformidad registrada, la sella con ahora. */
    private void marcarConformidadSiEntregado(Reparacion reparacion) {
        if (reparacion.getEstado() == EstadoReparacion.ENTREGADO
                && reparacion.getFechaConformidadEntrega() == null) {
            reparacion.setFechaConformidadEntrega(LocalDateTime.now());
        }
    }

    /**
     * Número de orden correlativo por taller con reinicio anual (ORD-2026-0042).
     * Toma el taller con lock de escritura para que dos ingresos simultáneos no
     * repitan el número; el correlativo reinicia al cambiar de año.
     */
    private String generarNumeroOrden(Long tallerId) {
        Taller taller = tallerRepository.findByIdForUpdate(tallerId)
                .orElseThrow(() -> new ResourceNotFoundException("Taller no encontrado con ID: " + tallerId));

        int anio = LocalDate.now().getYear();
        Integer anioActual = taller.getAnioSecuenciaOrden();
        int secuencia = (anioActual != null && anioActual == anio) ? taller.getSecuenciaOrden() + 1 : 1;

        taller.setAnioSecuenciaOrden(anio);
        taller.setSecuenciaOrden(secuencia);
        tallerRepository.save(taller);

        return String.format("ORD-%d-%04d", anio, secuencia);
    }
}
