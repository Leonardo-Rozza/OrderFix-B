package com.leonardorozza.mvgrreparacionesbackend.controller;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoReparacion;
import com.leonardorozza.mvgrreparacionesbackend.service.ReparacionService;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.reparacion.CambiarEstadoRequestDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.reparacion.GarantiaReclamoRequestDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.reparacion.IngresoRapidoRequestDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.reparacion.IngresoRapidoResponseDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.reparacion.ReparacionRequestDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.reparacion.ReparacionResponseDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.reparacion.WhatsappLinkDTO;
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
@RequestMapping("/api/reparaciones")
@RequiredArgsConstructor
@Tag(name = "Reparaciones", description = "Gestión de las reparaciones")
public class ReparacionController {

    private final ReparacionService reparacionService;

    // =====================================================
    // CREAR
    // =====================================================
    @Operation(summary = "Crear una reparación")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Reparación creada correctamente",
                    content = @Content(schema = @Schema(implementation = ReparacionResponseDTO.class))),
            @ApiResponse(responseCode = "404", description = "Equipo no encontrado"),
    })
    @PostMapping
    public ResponseEntity<ReparacionResponseDTO> crear(
            @Valid @RequestBody ReparacionRequestDTO request) {

        ReparacionResponseDTO response = reparacionService.crear(request);
        return ResponseEntity.status(201).body(response);
    }

    // =====================================================
    // CARGA RÁPIDA (cliente + equipo + reparación en un paso)
    // =====================================================
    @Operation(summary = "Carga rápida: crea cliente + equipo + reparación de una sola vez")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Reparación creada (con IDs de cliente y equipo)",
                    content = @Content(schema = @Schema(implementation = IngresoRapidoResponseDTO.class))),
            @ApiResponse(responseCode = "402", description = "Límite del plan alcanzado")
    })
    @PostMapping("/ingreso-rapido")
    public ResponseEntity<IngresoRapidoResponseDTO> ingresoRapido(
            @Valid @RequestBody IngresoRapidoRequestDTO request) {

        return ResponseEntity.status(201).body(reparacionService.crearIngresoRapido(request));
    }

    // =====================================================
    // RECLAMO EN GARANTÍA (reparación vinculada a la original)
    // =====================================================
    @Operation(summary = "Crear un reclamo en garantía de una reparación (no consume cupo ni arranca con precio)")
    @PostMapping("/{id}/garantia")
    public ResponseEntity<ReparacionResponseDTO> reclamoGarantia(
            @PathVariable Long id,
            @Valid @RequestBody GarantiaReclamoRequestDTO request) {

        return ResponseEntity.status(201).body(reparacionService.crearReclamoGarantia(id, request));
    }

    // =====================================================
    // ACTUALIZAR
    // =====================================================
    @Operation(summary = "Actualizar una reparación existente")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Reparación actualizada correctamente"),
            @ApiResponse(responseCode = "404", description = "Reparación o equipo no encontrado")
    })
    @PutMapping("/{id}")
    public ResponseEntity<ReparacionResponseDTO> actualizar(
            @PathVariable Long id,
            @Valid @RequestBody ReparacionRequestDTO request) {

        return ResponseEntity.ok(reparacionService.actualizar(id, request));
    }

    // =====================================================
    // CAMBIAR ESTADO
    // =====================================================
    @Operation(summary = "Cambiar el estado de una reparación")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Estado actualizado correctamente"),
            @ApiResponse(responseCode = "404", description = "Reparación no encontrada")
    })
    @PatchMapping("/{id}/estado")
    public ResponseEntity<ReparacionResponseDTO> cambiarEstado(
            @PathVariable Long id,
            @Valid @RequestBody CambiarEstadoRequestDTO body) {

        return ResponseEntity.ok(reparacionService.cambiarEstado(id, body.getEstado()));
    }

    // =====================================================
    // OBTENER POR ID
    // =====================================================
    @Operation(summary = "Obtener una reparación por ID")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Reparación encontrada"),
            @ApiResponse(responseCode = "404", description = "No encontrada")
    })
    @GetMapping("/{id}")
    public ResponseEntity<ReparacionResponseDTO> obtenerPorId(@PathVariable Long id) {

        return ResponseEntity.ok(reparacionService.obtenerPorId(id));
    }

    // =====================================================
    // LISTAR TODAS
    // =====================================================
    @Operation(summary = "Listar reparaciones (paginado). Filtros: ?q=texto y ?estado=EN_PROCESO")
    @ApiResponse(responseCode = "200", description = "Página de reparaciones")
    @GetMapping
    public ResponseEntity<Page<ReparacionResponseDTO>> listar(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) EstadoReparacion estado,
            @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable pageable) {

        return ResponseEntity.ok(reparacionService.listar(q, estado, pageable));
    }

    // =====================================================
    // LISTAR POR EQUIPO
    // =====================================================
    @Operation(summary = "Listar reparaciones por equipo")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lista obtenida"),
            @ApiResponse(responseCode = "404", description = "Equipo no encontrado")
    })
    @GetMapping("/equipo/{equipoId}")
    public ResponseEntity<List<ReparacionResponseDTO>> listarPorEquipo(@PathVariable Long equipoId) {

        return ResponseEntity.ok(reparacionService.listarPorEquipo(equipoId));
    }

    // =====================================================
    // LISTAR POR ESTADO
    // =====================================================
    @Operation(summary = "Listar reparaciones por estado")
    @ApiResponse(responseCode = "200", description = "Lista obtenida")
    @GetMapping("/estado")
    public ResponseEntity<List<ReparacionResponseDTO>> listarPorEstado(
            @RequestParam EstadoReparacion estado) {

        return ResponseEntity.ok(reparacionService.listarPorEstado(estado));
    }

    // =====================================================
    // LINK WHATSAPP (avisar al cliente)
    // =====================================================
    @Operation(summary = "Generar link de WhatsApp para avisar al cliente (incluye link de seguimiento)")
    @GetMapping("/{id}/whatsapp")
    public ResponseEntity<WhatsappLinkDTO> whatsapp(@PathVariable Long id) {
        return ResponseEntity.ok(reparacionService.linkWhatsapp(id));
    }

    // =====================================================
    // ELIMINAR
    // =====================================================
    @Operation(summary = "Eliminar una reparación")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Reparación eliminada correctamente"),
            @ApiResponse(responseCode = "404", description = "Reparación no encontrada")
    })
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> eliminar(@PathVariable Long id) {

        reparacionService.eliminar(id);
        return ResponseEntity.noContent().build();
    }
}
