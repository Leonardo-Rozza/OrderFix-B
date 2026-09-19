package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.config.tenant.TenantService;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.BadRequestException;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.ResourceNotFoundException;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Cliente;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Equipo;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.FotoReparacion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Reparacion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Repuesto;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Taller;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.CuentaVinculada;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EquipoTipo;
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
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.RepuestoRepository;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.TallerRepository;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.UserRepository;
import com.leonardorozza.mvgrreparacionesbackend.service.ReparacionService;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.reparacion.FotoDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.reparacion.GarantiaReclamoRequestDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.reparacion.IngresoRapidoRequestDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.reparacion.IngresoRapidoResponseDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.reparacion.ReparacionDetalleResponseDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.reparacion.ReparacionRequestDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.reparacion.ReparacionResponseDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.reparacion.WhatsappLinkDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.finanzas.CobroInvariantPolicy;
import com.leonardorozza.mvgrreparacionesbackend.service.finanzas.EstadoCuentaOrden;
import com.leonardorozza.mvgrreparacionesbackend.service.security.DeviceCredentialCipher;
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
import java.util.TreeSet;

@Service
@RequiredArgsConstructor
@Transactional
public class ReparacionServiceImpl implements ReparacionService {

    private final ReparacionRepository reparacionRepository;
    private final EquipoRepository equipoRepository;
    private final ClienteRepository clienteRepository;
    private final UserRepository userRepository;
    private final CobroRepository cobroRepository;
    private final RepuestoRepository repuestoRepository;
    private final ArticuloRepository articuloRepository;
    private final TallerRepository tallerRepository;
    private final ReparacionMapper reparacionMapper;
    private final TenantService tenantService;
    private final PlanLimitService planLimitService;
    private final DeviceCredentialCipher deviceCredentialCipher;
    private final CobroInvariantPolicy cobroInvariantPolicy;

    @Value("${app.public-url:http://localhost:5173}")
    private String publicUrl;

    @Value("${garantia.dias-default:90}")
    private int garantiaDiasDefault;

    @Value("${photos.private.enabled:false}")
    private boolean privatePhotosEnabled;

    private static final SecureRandom RANDOM = new SecureRandom();
    // Sin caracteres ambiguos (O/0, I/1) para dictarlo por teléfono
    private static final String ALFABETO = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    @Override
    public ReparacionResponseDTO crear(ReparacionRequestDTO request) {
        requireNoNewLegacyPhotos(request.getFotos(), List.of());
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

        procesarEntrega(reparacion);
        Reparacion guardada = reparacionRepository.save(reparacion);
        actualizarCredenciales(guardada, request, false);

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
        equipo.setTipo(request.getEquipoTipo() != null ? request.getEquipoTipo() : EquipoTipo.OTRO);
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
    public ReparacionResponseDTO crearReclamoGarantia(Long origenId, GarantiaReclamoRequestDTO request) {
        Long tallerId = tenantService.currentTallerId();
        Reparacion original = reparacionRepository.findByIdAndTallerId(origenId, tallerId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Reparación no encontrada con ID: " + origenId));

        // Reclamo en garantía: NO consume cupo del plan ni arranca con precio.
        Reparacion reclamo = new Reparacion();
        reclamo.setDescripcionProblema(request.getDescripcionProblema());
        reclamo.setEstado(EstadoReparacion.INGRESADO);
        reclamo.setEquipo(original.getEquipo());
        reclamo.setTaller(tenantService.currentTallerRef());
        reclamo.setEsGarantia(true);
        reclamo.setReparacionOrigenId(original.getId());
        reclamo.setCodigoSeguimiento(generarCodigoSeguimiento());
        reclamo.setNumeroOrden(generarNumeroOrden(tallerId));

        return toDtoConPago(reparacionRepository.save(reclamo));
    }

    @Override
    public ReparacionResponseDTO actualizar(Long id, ReparacionRequestDTO request) {
        Long tallerId = tenantService.currentTallerId();

        Reparacion reparacion = reparacionRepository.findByIdAndTallerIdForUpdate(id, tallerId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Reparación no encontrada con ID: " + id));

        requireNoNewLegacyPhotos(request.getFotos(), reparacion.getFotos());

        Equipo equipo = equipoRepository.findByIdAndTallerId(request.getEquipoId(), tallerId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Equipo no encontrado con ID: " + request.getEquipoId()));

        BigDecimal manoDeObraPropuesta = request.getPrecioFinal() != null
                ? request.getPrecioFinal()
                : (request.getPrecioEstimado() != null ? request.getPrecioEstimado() : BigDecimal.ZERO);
        BigDecimal totalPropuesto = manoDeObraPropuesta.add(reparacion.calcularTotalRepuestos());
        EstadoCuentaOrden estadoActual = EstadoCuentaOrden.de(
                reparacion.calcularTotal(),
                cobroRepository.sumByReparacionId(reparacion.getId()));
        cobroInvariantPolicy.validarTotalProyectado(reparacion.getId(), estadoActual, totalPropuesto);

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
        actualizarCredenciales(reparacion, request, true);
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

        // Garantía: el taller puede fijar días/condiciones antes de entregar.
        reparacion.setGarantiaDias(request.getGarantiaDias());
        reparacion.setGarantiaCondiciones(request.getGarantiaCondiciones());

        // Conformidad de entrega: explícita por request, o auto al quedar ENTREGADO.
        if (request.getFechaConformidadEntrega() != null) {
            reparacion.setFechaConformidadEntrega(request.getFechaConformidadEntrega());
        }
        procesarEntrega(reparacion);

        return toDtoConPago(reparacionRepository.save(reparacion));
    }

    @Override
    public ReparacionResponseDTO cambiarEstado(Long id, EstadoReparacion nuevoEstado) {
        Reparacion reparacion = reparacionRepository.findByIdAndTallerId(id, tenantService.currentTallerId())
                .orElseThrow(() ->
                        new ResourceNotFoundException("Reparación no encontrada con ID: " + id));

        TransicionesEstado.validar(reparacion.getEstado(), nuevoEstado);
        reparacion.setEstado(nuevoEstado);
        procesarEntrega(reparacion);

        return toDtoConPago(reparacionRepository.save(reparacion));
    }

    @Override
    @Transactional(readOnly = true)
    public ReparacionDetalleResponseDTO obtenerPorId(Long id) {
        Reparacion reparacion = reparacionRepository.findByIdAndTallerId(id, tenantService.currentTallerId())
                .orElseThrow(() ->
                        new ResourceNotFoundException("Reparación no encontrada con ID: " + id));

        return toDetalleDtoConPago(reparacion);
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

    /** Descifra exclusivamente para el detalle autenticado y tenant-safe. */
    private ReparacionDetalleResponseDTO toDetalleDtoConPago(Reparacion entity) {
        ReparacionDetalleResponseDTO dto = reparacionMapper.toDetalleDTO(entity);
        aplicarPago(dto, cobroRepository.sumByReparacionId(entity.getId()));
        if (entity.getEstado() != EstadoReparacion.ENTREGADO) {
            dto.setPatronDesbloqueo(deviceCredentialCipher.decryptPattern(
                    entity.getPatronDesbloqueoCifrado(), entity.getId()));
            dto.setPinDesbloqueo(deviceCredentialCipher.decryptPin(
                    entity.getPinDesbloqueoCifrado(), entity.getId()));
        }
        return dto;
    }

    private void aplicarPago(ReparacionResponseDTO dto, BigDecimal cobrado) {
        EstadoCuentaOrden estado = EstadoCuentaOrden.de(dto.getTotal(), cobrado);
        dto.setCobrado(estado.cobrado());
        dto.setSaldo(estado.saldo());
        dto.setExcedente(estado.excedente());
        dto.setRequiereRevision(estado.requiereRevision());
        dto.setEstadoPago(EstadoPago.de(estado.total(), estado.cobrado()));
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

    /**
     * En alta, null significa ausencia. En actualización, null conserva el valor
     * actual para no obligar al cliente a reenviar secretos; cadena vacía lo borra.
     */
    private void actualizarCredenciales(
            Reparacion reparacion, ReparacionRequestDTO request, boolean conservarSiOmitida) {
        if (reparacion.getEstado() == EstadoReparacion.ENTREGADO) {
            reparacion.setPatronDesbloqueoCifrado(null);
            reparacion.setPinDesbloqueoCifrado(null);
            reparacion.setCredencialesCifradoVersion((short) 1);
            return;
        }
        if (!conservarSiOmitida || request.getPatronDesbloqueo() != null) {
            String pattern = normalizarCredencial(request.getPatronDesbloqueo());
            reparacion.setPatronDesbloqueoCifrado(
                    deviceCredentialCipher.encryptPattern(pattern, reparacion.getId()));
        }
        if (!conservarSiOmitida || request.getPinDesbloqueo() != null) {
            String pin = normalizarCredencial(request.getPinDesbloqueo());
            reparacion.setPinDesbloqueoCifrado(
                    deviceCredentialCipher.encryptPin(pin, reparacion.getId()));
        }
        reparacion.setCredencialesCifradoVersion((short) 1);
    }

    private static String normalizarCredencial(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    @Override
    public void eliminar(Long id) {
        Long tallerId = tenantService.currentTallerId();
        Reparacion reparacion = reparacionRepository.findByIdAndTallerIdForUpdate(id, tallerId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Reparación no encontrada con ID: " + id));

        // La anulación conserva el movimiento: cualquier historial impide la baja física.
        if (cobroRepository.existsByReparacionId(id)) {
            throw new BadRequestException(
                    "No podés borrar una reparación con historial de cobros. "
                            + "Los movimientos, incluso anulados, deben conservarse para auditoría.");
        }

        List<Repuesto> repuestos = repuestoRepository
                .findByReparacionIdAndTallerIdForUpdateOrderByIdAsc(id, tallerId);
        Map<Long, Long> cantidadesPorArticulo = new HashMap<>();
        for (Repuesto repuesto : repuestos) {
            if (repuesto.getArticulo() == null) {
                continue;
            }
            cantidadesPorArticulo.merge(
                    repuesto.getArticulo().getId(),
                    (long) Math.max(1, repuesto.getCantidad()),
                    (acumulado, cantidad) -> {
                        if (acumulado > Integer.MAX_VALUE - cantidad) {
                            throw new BadRequestException(
                                    "La cantidad de stock a reponer excede el máximo permitido.");
                        }
                        return acumulado + cantidad;
                    });
        }

        // Bloquea artículos por ID y repone cada stock una sola vez.
        for (Long articuloId : new TreeSet<>(cantidadesPorArticulo.keySet())) {
            var articulo = articuloRepository.findByIdAndTallerIdForUpdate(articuloId, tallerId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Artículo no encontrado con ID: " + articuloId));
            long stockProyectado = (long) articulo.getStock() + cantidadesPorArticulo.get(articuloId);
            if (stockProyectado > Integer.MAX_VALUE) {
                throw new BadRequestException("El stock resultante excede el máximo permitido.");
            }
            articulo.setStock((int) stockProyectado);
            articuloRepository.save(articulo);
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

    /** Preserve exact legacy references during edits, but never associate a new public URL. */
    private void requireNoNewLegacyPhotos(List<FotoDTO> requested, List<FotoReparacion> existing) {
        if (!privatePhotosEnabled || requested == null || requested.isEmpty()) return;
        Map<FotoDTO, Integer> available = new HashMap<>();
        if (existing != null) existing.forEach(photo -> available.merge(
                new FotoDTO(photo.getUrl(), photo.getMomento() == null ? MomentoFoto.INGRESO : photo.getMomento()), 1, Integer::sum));
        for (FotoDTO photo : requested) {
            if (photo == null) throw new BadRequestException("FOTO_PRIVADA_REQUERIDA", "Las fotos nuevas requieren una carga privada.");
            FotoDTO normalized = new FotoDTO(photo.url(), photo.momento() == null ? MomentoFoto.INGRESO : photo.momento());
            int remaining = available.getOrDefault(normalized, 0);
            if (remaining < 1) throw new BadRequestException("FOTO_PRIVADA_REQUERIDA", "Las fotos nuevas requieren una carga privada.");
            available.put(normalized, remaining - 1);
        }
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

    /**
     * Al quedar ENTREGADA: sella la conformidad (si falta) y fija la garantía
     * (inicio = hoy, fin = inicio + garantiaDias), una sola vez.
     */
    private void procesarEntrega(Reparacion reparacion) {
        if (reparacion.getEstado() != EstadoReparacion.ENTREGADO) {
            return;
        }
        reparacion.setPatronDesbloqueoCifrado(null);
        reparacion.setPinDesbloqueoCifrado(null);
        reparacion.setCredencialesCifradoVersion((short) 1);
        if (reparacion.getFechaConformidadEntrega() == null) {
            reparacion.setFechaConformidadEntrega(LocalDateTime.now());
        }
        if (reparacion.getGarantiaFin() == null) {
            int dias = reparacion.getGarantiaDias() != null ? reparacion.getGarantiaDias() : garantiaDiasDefault;
            LocalDate inicio = LocalDate.now();
            reparacion.setGarantiaDias(dias);
            reparacion.setGarantiaInicio(inicio);
            reparacion.setGarantiaFin(inicio.plusDays(dias));
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
