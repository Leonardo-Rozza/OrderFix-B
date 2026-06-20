package com.leonardorozza.mvgrreparacionesbackend.service.impl;


import com.leonardorozza.mvgrreparacionesbackend.config.tenant.TenantService;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.BadRequestException;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.ResourceNotFoundException;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Articulo;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Reparacion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Repuesto;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.ArticuloRepository;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.ReparacionRepository;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.RepuestoRepository;
import com.leonardorozza.mvgrreparacionesbackend.service.RepuestoService;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.repuesto.RepuestoRequestDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.repuesto.RepuestoResponseDTO;
import com.leonardorozza.mvgrreparacionesbackend.utils.mapper.RepuestoMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class RepuestoServiceImpl implements RepuestoService {

    private final RepuestoRepository repuestoRepository;
    private final ReparacionRepository reparacionRepository;
    private final ArticuloRepository articuloRepository;
    private final RepuestoMapper repuestoMapper;
    private final TenantService tenantService;

    // =====================================================
    // CREAR
    // =====================================================
    @Override
    public RepuestoResponseDTO crear(RepuestoRequestDTO request) {
        Long tallerId = tenantService.currentTallerId();

        Reparacion reparacion = resolverReparacion(request.getReparacionId(), tallerId);

        Repuesto repuesto = repuestoMapper.toEntity(request);
        repuesto.setReparacion(reparacion);
        repuesto.setTaller(tenantService.currentTallerRef());

        int cantidad = cantidadDe(request);
        repuesto.setCantidad(cantidad);
        if (request.getArticuloId() != null) {
            Articulo articulo = resolverArticulo(request.getArticuloId(), tallerId);
            descontarStock(articulo, cantidad);
            repuesto.setArticulo(articulo);
        }

        return repuestoMapper.toDTO(repuestoRepository.save(repuesto));
    }

    // =====================================================
    // ACTUALIZAR
    // =====================================================
    @Override
    public RepuestoResponseDTO actualizar(Long id, RepuestoRequestDTO request) {
        Long tallerId = tenantService.currentTallerId();

        Repuesto repuesto = repuestoRepository.findByIdAndTallerId(id, tallerId)
                .orElseThrow(() -> new ResourceNotFoundException("Repuesto no encontrado con ID: " + id));

        Reparacion reparacion = resolverReparacion(request.getReparacionId(), tallerId);

        repuesto.setNombre(request.getNombre());
        repuesto.setDescripcion(request.getDescripcion());
        repuesto.setPrecio(request.getPrecio());
        repuesto.setReparacion(reparacion);

        // Ajuste de stock: repongo lo viejo y descuento lo nuevo
        if (repuesto.getArticulo() != null) {
            reponerStock(repuesto.getArticulo(), repuesto.getCantidad());
        }
        int cantidad = cantidadDe(request);
        repuesto.setCantidad(cantidad);
        if (request.getArticuloId() != null) {
            Articulo articulo = resolverArticulo(request.getArticuloId(), tallerId);
            descontarStock(articulo, cantidad);
            repuesto.setArticulo(articulo);
        } else {
            repuesto.setArticulo(null);
        }

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
                .orElseThrow(() -> new ResourceNotFoundException("Repuesto no encontrado con ID: " + id));
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
        Repuesto repuesto = repuestoRepository.findByIdAndTallerId(id, tenantService.currentTallerId())
                .orElseThrow(() -> new ResourceNotFoundException("Repuesto no encontrado con ID: " + id));

        // Si descontó stock de un artículo, lo repongo al borrarlo
        if (repuesto.getArticulo() != null) {
            reponerStock(repuesto.getArticulo(), repuesto.getCantidad());
        }

        repuestoRepository.delete(repuesto);
    }

    private int cantidadDe(RepuestoRequestDTO request) {
        return (request.getCantidad() != null && request.getCantidad() > 0) ? request.getCantidad() : 1;
    }

    private Articulo resolverArticulo(Long articuloId, Long tallerId) {
        return articuloRepository.findByIdAndTallerId(articuloId, tallerId)
                .orElseThrow(() -> new ResourceNotFoundException("Artículo no encontrado con ID: " + articuloId));
    }

    private void descontarStock(Articulo articulo, int cantidad) {
        if (articulo.getStock() < cantidad) {
            throw new BadRequestException("Stock insuficiente de '" + articulo.getNombre()
                    + "' (disponible: " + articulo.getStock() + ", pedido: " + cantidad + ").");
        }
        articulo.setStock(articulo.getStock() - cantidad);
        articuloRepository.save(articulo);
    }

    private void reponerStock(Articulo articulo, int cantidad) {
        articulo.setStock(articulo.getStock() + cantidad);
        articuloRepository.save(articulo);
    }

    /**
     * Resuelve la reparación asociada (opcional) verificando que pertenezca al taller actual.
     */
    private Reparacion resolverReparacion(Long reparacionId, Long tallerId) {
        if (reparacionId == null) {
            return null;
        }
        return reparacionRepository.findByIdAndTallerId(reparacionId, tallerId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Reparación no encontrada con ID: " + reparacionId));
    }
}
