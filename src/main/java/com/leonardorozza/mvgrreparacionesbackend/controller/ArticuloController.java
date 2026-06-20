package com.leonardorozza.mvgrreparacionesbackend.controller;

import com.leonardorozza.mvgrreparacionesbackend.service.dto.inventario.AjusteStockRequestDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.inventario.ArticuloRequestDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.inventario.ArticuloResponseDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.ArticuloService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/inventario")
@RequiredArgsConstructor
@Tag(name = "Inventario", description = "Catálogo de artículos con stock")
public class ArticuloController {

    private final ArticuloService articuloService;

    @Operation(summary = "Crear un artículo")
    @PostMapping
    public ResponseEntity<ArticuloResponseDTO> crear(@Valid @RequestBody ArticuloRequestDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(articuloService.crear(request));
    }

    @Operation(summary = "Actualizar un artículo (el stock se cambia con /ajuste)")
    @PutMapping("/{id}")
    public ResponseEntity<ArticuloResponseDTO> actualizar(
            @PathVariable Long id, @Valid @RequestBody ArticuloRequestDTO request) {
        return ResponseEntity.ok(articuloService.actualizar(id, request));
    }

    @Operation(summary = "Obtener un artículo por ID")
    @GetMapping("/{id}")
    public ResponseEntity<ArticuloResponseDTO> obtener(@PathVariable Long id) {
        return ResponseEntity.ok(articuloService.obtener(id));
    }

    @Operation(summary = "Listar artículos (paginado). Búsqueda por nombre/SKU con ?q=")
    @GetMapping
    public ResponseEntity<Page<ArticuloResponseDTO>> listar(
            @RequestParam(required = false) String q,
            @PageableDefault(size = 20, sort = "nombre", direction = Sort.Direction.ASC) Pageable pageable) {
        return ResponseEntity.ok(articuloService.listar(q, pageable));
    }

    @Operation(summary = "Listar artículos con stock bajo (stock <= mínimo)")
    @GetMapping("/stock-bajo")
    public ResponseEntity<List<ArticuloResponseDTO>> stockBajo() {
        return ResponseEntity.ok(articuloService.stockBajo());
    }

    @Operation(summary = "Ajustar stock (delta + o -, con motivo)")
    @PostMapping("/{id}/ajuste")
    public ResponseEntity<ArticuloResponseDTO> ajustar(
            @PathVariable Long id, @Valid @RequestBody AjusteStockRequestDTO request) {
        return ResponseEntity.ok(articuloService.ajustarStock(id, request));
    }

    @Operation(summary = "Eliminar un artículo (solo ADMIN)")
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> eliminar(@PathVariable Long id) {
        articuloService.eliminar(id);
        return ResponseEntity.noContent().build();
    }
}
