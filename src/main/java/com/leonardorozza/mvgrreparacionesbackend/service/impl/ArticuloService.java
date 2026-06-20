package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.config.tenant.TenantService;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.BadRequestException;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.ResourceNotFoundException;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Articulo;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PlanFeature;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.ArticuloRepository;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.inventario.AjusteStockRequestDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.inventario.ArticuloRequestDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.inventario.ArticuloResponseDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class ArticuloService {

    private final ArticuloRepository articuloRepository;
    private final TenantService tenantService;
    private final PlanFeatureService planFeatureService;

    public ArticuloResponseDTO crear(ArticuloRequestDTO request) {
        planFeatureService.requerir(PlanFeature.INVENTARIO);
        Articulo articulo = Articulo.builder()
                .taller(tenantService.currentTallerRef())
                .nombre(request.getNombre())
                .descripcion(request.getDescripcion())
                .sku(request.getSku())
                .precio(request.getPrecio() != null ? request.getPrecio() : BigDecimal.ZERO)
                .costo(request.getCosto())
                .stock(request.getStock() != null ? request.getStock() : 0)
                .stockMinimo(request.getStockMinimo() != null ? request.getStockMinimo() : 0)
                .activo(true)
                .build();
        return toDTO(articuloRepository.save(articulo));
    }

    public ArticuloResponseDTO actualizar(Long id, ArticuloRequestDTO request) {
        planFeatureService.requerir(PlanFeature.INVENTARIO);
        Articulo a = obtenerEntidad(id);
        a.setNombre(request.getNombre());
        a.setDescripcion(request.getDescripcion());
        a.setSku(request.getSku());
        a.setPrecio(request.getPrecio() != null ? request.getPrecio() : BigDecimal.ZERO);
        a.setCosto(request.getCosto());
        if (request.getStockMinimo() != null) {
            a.setStockMinimo(request.getStockMinimo());
        }
        // El stock NO se cambia acá: se ajusta con /ajuste para dejar trazabilidad.
        return toDTO(articuloRepository.save(a));
    }

    @Transactional(readOnly = true)
    public ArticuloResponseDTO obtener(Long id) {
        planFeatureService.requerir(PlanFeature.INVENTARIO);
        return toDTO(obtenerEntidad(id));
    }

    @Transactional(readOnly = true)
    public Page<ArticuloResponseDTO> listar(String q, Pageable pageable) {
        planFeatureService.requerir(PlanFeature.INVENTARIO);
        return articuloRepository.search(tenantService.currentTallerId(), q, pageable).map(this::toDTO);
    }

    @Transactional(readOnly = true)
    public List<ArticuloResponseDTO> stockBajo() {
        planFeatureService.requerir(PlanFeature.INVENTARIO);
        return articuloRepository.findStockBajo(tenantService.currentTallerId())
                .stream().map(this::toDTO).toList();
    }

    public ArticuloResponseDTO ajustarStock(Long id, AjusteStockRequestDTO request) {
        planFeatureService.requerir(PlanFeature.INVENTARIO);
        Articulo a = obtenerEntidad(id);
        int nuevo = a.getStock() + request.getDelta();
        if (nuevo < 0) {
            throw new BadRequestException("El ajuste deja el stock en negativo (stock actual: " + a.getStock() + ").");
        }
        a.setStock(nuevo);
        log.info("Ajuste de stock articulo {}: delta={} -> {} ({})", id, request.getDelta(), nuevo, request.getMotivo());
        return toDTO(articuloRepository.save(a));
    }

    public void eliminar(Long id) {
        planFeatureService.requerir(PlanFeature.INVENTARIO);
        Articulo a = obtenerEntidad(id);
        articuloRepository.delete(a);
    }

    private Articulo obtenerEntidad(Long id) {
        return articuloRepository.findByIdAndTallerId(id, tenantService.currentTallerId())
                .orElseThrow(() -> new ResourceNotFoundException("Artículo no encontrado con ID: " + id));
    }

    private ArticuloResponseDTO toDTO(Articulo a) {
        return new ArticuloResponseDTO(
                a.getId(), a.getNombre(), a.getDescripcion(), a.getSku(),
                a.getPrecio(), a.getCosto(), a.getStock(), a.getStockMinimo(),
                a.isActivo(), a.getStock() <= a.getStockMinimo());
    }
}
