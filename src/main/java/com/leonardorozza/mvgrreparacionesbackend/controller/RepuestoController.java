package com.leonardorozza.mvgrreparacionesbackend.controller;

import com.leonardorozza.mvgrreparacionesbackend.service.RepuestoService;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.repuesto.RepuestoRequestDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.repuesto.RepuestoResponseDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/repuestos")
@RequiredArgsConstructor
@Tag(name = "Repuestos", description = "Gestión de los repuestos.")
public class RepuestoController {

    private final RepuestoService repuestoService;

    // =====================================================
    // CREAR
    // =====================================================
    @Operation(summary = "Crear un repuesto")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Repuesto creado correctamente",
                    content = @Content(schema = @Schema(implementation = RepuestoResponseDTO.class)))
    })
    @PostMapping
    public ResponseEntity<RepuestoResponseDTO> crear(
            @Valid @RequestBody RepuestoRequestDTO request) {

        return ResponseEntity.status(201).body(repuestoService.crear(request));
    }

    // =====================================================
    // ACTUALIZAR
    // =====================================================
    @Operation(summary = "Actualizar un repuesto existente")
    @PutMapping("/{id}")
    public ResponseEntity<RepuestoResponseDTO> actualizar(
            @PathVariable Long id,
            @Valid @RequestBody RepuestoRequestDTO request) {

        return ResponseEntity.ok(repuestoService.actualizar(id, request));
    }

    // =====================================================
    // OBTENER POR ID
    // =====================================================
    @Operation(summary = "Obtener un repuesto por ID")
    @GetMapping("/{id}")
    public ResponseEntity<RepuestoResponseDTO> obtenerPorId(@PathVariable Long id) {

        return ResponseEntity.ok(repuestoService.obtenerPorId(id));
    }

    // =====================================================
    // LISTAR TODOS
    // =====================================================
    @Operation(summary = "Listar repuestos (paginado). Búsqueda por nombre con ?q=")
    @GetMapping
    public ResponseEntity<Page<RepuestoResponseDTO>> listar(
            @RequestParam(required = false) String q,
            @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable pageable) {

        return ResponseEntity.ok(repuestoService.listar(q, pageable));
    }

    // =====================================================
    // LISTAR POR REPARACION
    // =====================================================
    @Operation(summary = "Listar repuestos por reparación")
    @GetMapping("/reparacion/{reparacionId}")
    public ResponseEntity<List<RepuestoResponseDTO>> listarPorReparacion(
            @PathVariable Long reparacionId) {

        return ResponseEntity.ok(repuestoService.listarPorReparacion(reparacionId));
    }

    // =====================================================
    // ELIMINAR
    // =====================================================
    @Operation(summary = "Eliminar un repuesto (solo ADMIN)")
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> eliminar(@PathVariable Long id) {

        repuestoService.eliminar(id);
        return ResponseEntity.noContent().build();
    }
}

