package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.config.tenant.TenantService;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.ResourceNotFoundException;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Cliente;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Equipo;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Reparacion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoReparacion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.ClienteRepository;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.EquipoRepository;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.ReparacionRepository;
import com.leonardorozza.mvgrreparacionesbackend.service.ReparacionService;
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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class ReparacionServiceImpl implements ReparacionService {

    private final ReparacionRepository reparacionRepository;
    private final EquipoRepository equipoRepository;
    private final ClienteRepository clienteRepository;
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
        planLimitService.assertPuedeCrearReparacion(tallerId);

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
        reparacion.setCodigoSeguimiento(generarCodigoSeguimiento());

        Reparacion guardada = reparacionRepository.save(reparacion);

        return reparacionMapper.toDTO(guardada);
    }

    @Override
    public IngresoRapidoResponseDTO crearIngresoRapido(IngresoRapidoRequestDTO request) {
        Long tallerId = tenantService.currentTallerId();

        // Gating freemium: respeta el tope del plan / suscripción vigente
        planLimitService.assertPuedeCrearReparacion(tallerId);

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
        reparacion.setEquipo(equipo);
        reparacion.setTaller(tenantService.currentTallerRef());
        Reparacion guardada = reparacionRepository.save(reparacion);

        return new IngresoRapidoResponseDTO(
                cliente.getId(),
                equipo.getId(),
                clienteNuevo,
                reparacionMapper.toDTO(guardada)
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
        // No pisamos el estado con null: si el request no trae estado, conservamos el actual
        if (request.getEstado() != null) {
            reparacion.setEstado(request.getEstado());
        }
        reparacion.setPrecioEstimado(request.getPrecioEstimado());
        reparacion.setPrecioFinal(request.getPrecioFinal());
        reparacion.setFechaIngreso(request.getFechaIngreso());
        reparacion.setFechaEstimadaEntrega(request.getFechaEstimadaEntrega());
        reparacion.setFechaEntrega(request.getFechaEntrega());

        return reparacionMapper.toDTO(reparacionRepository.save(reparacion));
    }

    @Override
    public ReparacionResponseDTO cambiarEstado(Long id, EstadoReparacion nuevoEstado) {
        Reparacion reparacion = reparacionRepository.findByIdAndTallerId(id, tenantService.currentTallerId())
                .orElseThrow(() ->
                        new ResourceNotFoundException("Reparación no encontrada con ID: " + id));

        reparacion.setEstado(nuevoEstado);

        return reparacionMapper.toDTO(reparacionRepository.save(reparacion));
    }

    @Override
    @Transactional(readOnly = true)
    public ReparacionResponseDTO obtenerPorId(Long id) {
        Reparacion reparacion = reparacionRepository.findByIdAndTallerId(id, tenantService.currentTallerId())
                .orElseThrow(() ->
                        new ResourceNotFoundException("Reparación no encontrada con ID: " + id));

        return reparacionMapper.toDTO(reparacion);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ReparacionResponseDTO> listar(String q, EstadoReparacion estado, Pageable pageable) {
        return reparacionRepository.search(tenantService.currentTallerId(), q, estado, pageable)
                .map(reparacionMapper::toDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReparacionResponseDTO> listarPorEquipo(Long equipoId) {
        Long tallerId = tenantService.currentTallerId();

        if (!equipoRepository.existsByIdAndTallerId(equipoId, tallerId)) {
            throw new ResourceNotFoundException("Equipo no encontrado con ID: " + equipoId);
        }

        return reparacionRepository.findByEquipoIdAndTallerId(equipoId, tallerId)
                .stream()
                .map(reparacionMapper::toDTO)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReparacionResponseDTO> listarPorEstado(EstadoReparacion estado) {
        return reparacionRepository.findByEstadoAndTallerId(estado, tenantService.currentTallerId())
                .stream()
                .map(reparacionMapper::toDTO)
                .toList();
    }

    @Override
    public void eliminar(Long id) {
        Reparacion reparacion = reparacionRepository.findByIdAndTallerId(id, tenantService.currentTallerId())
                .orElseThrow(() ->
                        new ResourceNotFoundException("Reparación no encontrada con ID: " + id));

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
}
