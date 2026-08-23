package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.config.tenant.TenantService;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.BadRequestException;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.ConflictException;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.ResourceNotFoundException;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Articulo;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Reparacion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Repuesto;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.ArticuloRepository;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.CobroRepository;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.ReparacionRepository;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.RepuestoRepository;
import com.leonardorozza.mvgrreparacionesbackend.service.RepuestoService;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.repuesto.RepuestoRequestDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.repuesto.RepuestoResponseDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.finanzas.CobroInvariantPolicy;
import com.leonardorozza.mvgrreparacionesbackend.service.finanzas.EstadoCuentaOrden;
import com.leonardorozza.mvgrreparacionesbackend.utils.mapper.RepuestoMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

@Service
@RequiredArgsConstructor
@Transactional
public class RepuestoServiceImpl implements RepuestoService {

    private final RepuestoRepository repuestoRepository;
    private final ReparacionRepository reparacionRepository;
    private final ArticuloRepository articuloRepository;
    private final CobroRepository cobroRepository;
    private final RepuestoMapper repuestoMapper;
    private final TenantService tenantService;
    private final CobroInvariantPolicy cobroInvariantPolicy;

    // =====================================================
    // CREAR
    // =====================================================
    @Override
    public RepuestoResponseDTO crear(RepuestoRequestDTO request) {
        Long tallerId = tenantService.currentTallerId();
        int cantidad = cantidadDe(request);

        // Orden global de locks: reparaciones -> repuesto -> artículos.
        Reparacion reparacion = resolverReparacionForUpdate(request.getReparacionId(), tallerId);
        if (reparacion != null) {
            validarTotalProyectado(
                    reparacion,
                    reparacion.calcularTotal().add(contribucion(request.getPrecio(), cantidad)));
        }

        Articulo articulo = resolverArticuloForUpdate(request.getArticuloId(), tallerId);
        ajustarStock(null, 0, articulo, cantidad);

        Repuesto repuesto = repuestoMapper.toEntity(request);
        repuesto.setReparacion(reparacion);
        repuesto.setArticulo(articulo);
        repuesto.setCantidad(cantidad);
        repuesto.setTaller(tenantService.currentTallerRef());

        return repuestoMapper.toDTO(repuestoRepository.save(repuesto));
    }

    // =====================================================
    // ACTUALIZAR
    // =====================================================
    @Override
    public RepuestoResponseDTO actualizar(Long id, RepuestoRequestDTO request) {
        Long tallerId = tenantService.currentTallerId();
        RepuestoRepository.RepuestoVinculo vinculo = repuestoRepository
                .findVinculoByIdAndTallerId(id, tallerId)
                .orElseThrow(() -> repuestoNoEncontrado(id));

        Map<Long, Reparacion> reparaciones = bloquearReparaciones(
                vinculo.getReparacionId(), request.getReparacionId(), tallerId);

        Repuesto repuesto = repuestoRepository.findByIdAndTallerIdForUpdate(id, tallerId)
                .orElseThrow(() -> repuestoNoEncontrado(id));
        revalidarVinculo(vinculo, repuesto);

        int cantidadAnterior = cantidadLegacy(repuesto.getCantidad());
        int cantidadNueva = cantidadDe(request);
        validarCambioDeTotal(repuesto, request, reparaciones, cantidadAnterior, cantidadNueva);

        Map<Long, Articulo> articulos = bloquearArticulos(
                vinculo.getArticuloId(), request.getArticuloId(), tallerId);
        Articulo articuloAnterior = articulos.get(vinculo.getArticuloId());
        Articulo articuloNuevo = articulos.get(request.getArticuloId());
        ajustarStock(articuloAnterior, cantidadAnterior, articuloNuevo, cantidadNueva);

        repuesto.setNombre(request.getNombre());
        repuesto.setDescripcion(request.getDescripcion());
        repuesto.setPrecio(request.getPrecio());
        repuesto.setCantidad(cantidadNueva);
        repuesto.setReparacion(reparaciones.get(request.getReparacionId()));
        repuesto.setArticulo(articuloNuevo);

        return repuestoMapper.toDTO(repuestoRepository.save(repuesto));
    }

    // =====================================================
    // OBTENER POR ID
    // =====================================================
    @Override
    @Transactional(readOnly = true)
    public RepuestoResponseDTO obtenerPorId(Long id) {
        return repuestoRepository.findByIdAndTallerId(id, tenantService.currentTallerId())
                .map(repuestoMapper::toDTO)
                .orElseThrow(() -> repuestoNoEncontrado(id));
    }

    // =====================================================
    // LISTAR TODOS
    // =====================================================
    @Override
    @Transactional(readOnly = true)
    public Page<RepuestoResponseDTO> listar(String q, Pageable pageable) {
        return repuestoRepository.search(tenantService.currentTallerId(), q, pageable)
                .map(repuestoMapper::toDTO);
    }

    // =====================================================
    // LISTAR POR REPARACION
    // =====================================================
    @Override
    @Transactional(readOnly = true)
    public List<RepuestoResponseDTO> listarPorReparacion(Long reparacionId) {
        Long tallerId = tenantService.currentTallerId();

        if (!reparacionRepository.existsByIdAndTallerId(reparacionId, tallerId)) {
            throw new ResourceNotFoundException("Reparación no encontrada con ID: " + reparacionId);
        }

        return repuestoRepository.findByReparacionIdAndTallerId(reparacionId, tallerId)
                .stream()
                .map(repuestoMapper::toDTO)
                .toList();
    }

    // =====================================================
    // ELIMINAR
    // =====================================================
    @Override
    public void eliminar(Long id) {
        Long tallerId = tenantService.currentTallerId();
        RepuestoRepository.RepuestoVinculo vinculo = repuestoRepository
                .findVinculoByIdAndTallerId(id, tallerId)
                .orElseThrow(() -> repuestoNoEncontrado(id));

        Reparacion reparacion = resolverReparacionForUpdate(vinculo.getReparacionId(), tallerId);
        Repuesto repuesto = repuestoRepository.findByIdAndTallerIdForUpdate(id, tallerId)
                .orElseThrow(() -> repuestoNoEncontrado(id));
        revalidarVinculo(vinculo, repuesto);

        int cantidad = cantidadLegacy(repuesto.getCantidad());
        if (reparacion != null) {
            validarTotalProyectado(
                    reparacion,
                    reparacion.calcularTotal().subtract(contribucion(repuesto.getPrecio(), cantidad)));
        }

        Articulo articulo = resolverArticuloForUpdate(vinculo.getArticuloId(), tallerId);
        ajustarStock(articulo, cantidad, null, 0);
        repuestoRepository.delete(repuesto);
    }

    private Map<Long, Reparacion> bloquearReparaciones(
            Long primerId, Long segundoId, Long tallerId) {
        TreeSet<Long> ids = idsOrdenados(primerId, segundoId);
        Map<Long, Reparacion> resultado = new LinkedHashMap<>();
        for (Long reparacionId : ids) {
            resultado.put(reparacionId, reparacionRepository
                    .findByIdAndTallerIdForUpdate(reparacionId, tallerId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Reparación no encontrada con ID: " + reparacionId)));
        }
        return resultado;
    }

    private Map<Long, Articulo> bloquearArticulos(
            Long primerId, Long segundoId, Long tallerId) {
        TreeSet<Long> ids = idsOrdenados(primerId, segundoId);
        Map<Long, Articulo> resultado = new LinkedHashMap<>();
        for (Long articuloId : ids) {
            resultado.put(articuloId, articuloRepository
                    .findByIdAndTallerIdForUpdate(articuloId, tallerId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Artículo no encontrado con ID: " + articuloId)));
        }
        return resultado;
    }

    private TreeSet<Long> idsOrdenados(Long primerId, Long segundoId) {
        TreeSet<Long> ids = new TreeSet<>();
        if (primerId != null) {
            ids.add(primerId);
        }
        if (segundoId != null) {
            ids.add(segundoId);
        }
        return ids;
    }

    private void validarCambioDeTotal(
            Repuesto repuesto,
            RepuestoRequestDTO request,
            Map<Long, Reparacion> reparaciones,
            int cantidadAnterior,
            int cantidadNueva) {
        Long reparacionAnteriorId = idDe(repuesto.getReparacion());
        Long reparacionNuevaId = request.getReparacionId();
        BigDecimal aporteAnterior = contribucion(repuesto.getPrecio(), cantidadAnterior);
        BigDecimal aporteNuevo = contribucion(request.getPrecio(), cantidadNueva);

        if (Objects.equals(reparacionAnteriorId, reparacionNuevaId)) {
            Reparacion reparacion = reparaciones.get(reparacionAnteriorId);
            if (reparacion != null) {
                validarTotalProyectado(
                        reparacion,
                        reparacion.calcularTotal().subtract(aporteAnterior).add(aporteNuevo));
            }
            return;
        }

        Reparacion anterior = reparaciones.get(reparacionAnteriorId);
        if (anterior != null) {
            validarTotalProyectado(anterior, anterior.calcularTotal().subtract(aporteAnterior));
        }

        Reparacion nueva = reparaciones.get(reparacionNuevaId);
        if (nueva != null) {
            validarTotalProyectado(nueva, nueva.calcularTotal().add(aporteNuevo));
        }
    }

    private void validarTotalProyectado(Reparacion reparacion, BigDecimal totalProyectado) {
        EstadoCuentaOrden estadoActual = EstadoCuentaOrden.de(
                reparacion.calcularTotal(),
                cobroRepository.sumByReparacionId(reparacion.getId()));
        cobroInvariantPolicy.validarTotalProyectado(
                reparacion.getId(), estadoActual, totalProyectado);
    }

    private void ajustarStock(
            Articulo articuloAnterior,
            int cantidadAnterior,
            Articulo articuloNuevo,
            int cantidadNueva) {
        if (articuloAnterior == null && articuloNuevo == null) {
            return;
        }

        if (articuloAnterior != null && articuloNuevo != null
                && Objects.equals(articuloAnterior.getId(), articuloNuevo.getId())) {
            long stockDisponible = (long) articuloAnterior.getStock() + cantidadAnterior;
            long stockProyectado = stockDisponible - cantidadNueva;
            validarStockDisponible(
                    articuloAnterior, stockProyectado, stockDisponible, cantidadNueva);
            articuloAnterior.setStock(stockSeguro(stockProyectado));
            articuloRepository.save(articuloAnterior);
            return;
        }

        long stockNuevo = articuloNuevo == null
                ? 0L
                : (long) articuloNuevo.getStock() - cantidadNueva;
        if (articuloNuevo != null) {
            validarStockDisponible(
                    articuloNuevo, stockNuevo, articuloNuevo.getStock(), cantidadNueva);
        }

        if (articuloAnterior != null) {
            articuloAnterior.setStock(stockSeguro((long) articuloAnterior.getStock() + cantidadAnterior));
            articuloRepository.save(articuloAnterior);
        }
        if (articuloNuevo != null) {
            articuloNuevo.setStock(stockSeguro(stockNuevo));
            articuloRepository.save(articuloNuevo);
        }
    }

    private void validarStockDisponible(
            Articulo articulo,
            long stockProyectado,
            long stockDisponible,
            int cantidadPedida) {
        if (stockProyectado >= 0) {
            return;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("articuloId", articulo.getId());
        details.put("stock", stockDisponible);
        details.put("cantidad", cantidadPedida);
        throw new BadRequestException(
                "STOCK_INSUFICIENTE",
                "Stock insuficiente de '" + articulo.getNombre()
                        + "' (disponible: " + stockDisponible
                        + ", pedido: " + cantidadPedida + ").",
                details);
    }

    private int stockSeguro(long value) {
        if (value > Integer.MAX_VALUE) {
            throw new BadRequestException("El stock resultante excede el máximo permitido.");
        }
        return (int) value;
    }

    private int cantidadDe(RepuestoRequestDTO request) {
        Integer cantidad = request.getCantidad();
        if (cantidad == null) {
            return 1;
        }
        if (cantidad <= 0) {
            throw new BadRequestException("La cantidad debe ser mayor que cero.");
        }
        return cantidad;
    }

    private int cantidadLegacy(int cantidad) {
        return Math.max(1, cantidad);
    }

    private BigDecimal contribucion(BigDecimal precio, int cantidad) {
        BigDecimal precioSeguro = precio == null ? BigDecimal.ZERO : precio;
        return precioSeguro.multiply(BigDecimal.valueOf(cantidadLegacy(cantidad)));
    }

    private Reparacion resolverReparacionForUpdate(Long reparacionId, Long tallerId) {
        if (reparacionId == null) {
            return null;
        }
        return reparacionRepository.findByIdAndTallerIdForUpdate(reparacionId, tallerId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Reparación no encontrada con ID: " + reparacionId));
    }

    private Articulo resolverArticuloForUpdate(Long articuloId, Long tallerId) {
        if (articuloId == null) {
            return null;
        }
        return articuloRepository.findByIdAndTallerIdForUpdate(articuloId, tallerId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Artículo no encontrado con ID: " + articuloId));
    }

    private void revalidarVinculo(
            RepuestoRepository.RepuestoVinculo esperado, Repuesto actual) {
        if (Objects.equals(esperado.getReparacionId(), idDe(actual.getReparacion()))
                && Objects.equals(esperado.getArticuloId(), idDe(actual.getArticulo()))) {
            return;
        }
        throw new ConflictException(
                "REPUESTO_CAMBIO_CONCURRENTE",
                "El repuesto cambió mientras se procesaba la operación. Reintentá.");
    }

    private Long idDe(Reparacion reparacion) {
        return reparacion == null ? null : reparacion.getId();
    }

    private Long idDe(Articulo articulo) {
        return articulo == null ? null : articulo.getId();
    }

    private ResourceNotFoundException repuestoNoEncontrado(Long id) {
        return new ResourceNotFoundException("Repuesto no encontrado con ID: " + id);
    }
}
