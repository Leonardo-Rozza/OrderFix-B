package com.leonardorozza.mvgrreparacionesbackend.controller;

import com.leonardorozza.mvgrreparacionesbackend.service.dto.presupuesto.PresupuestoRequestDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.presupuesto.PresupuestoResponseDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.PresupuestoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/reparaciones/{reparacionId}/presupuestos")
@RequiredArgsConstructor
@Tag(name = "Presupuestos", description = "Presupuestos de una reparación")
public class PresupuestoController {

    private final PresupuestoService presupuestoService;

    @Operation(summary = "Crear un presupuesto (queda PENDIENTE de aprobación)")
    @PostMapping
    public ResponseEntity<PresupuestoResponseDTO> crear(
            @PathVariable Long reparacionId,
            @Valid @RequestBody PresupuestoRequestDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(presupuestoService.crear(reparacionId, request));
    }

    @Operation(summary = "Listar los presupuestos de la reparación (más nuevo primero)")
    @GetMapping
    public ResponseEntity<List<PresupuestoResponseDTO>> listar(@PathVariable Long reparacionId) {
        return ResponseEntity.ok(presupuestoService.listarPorReparacion(reparacionId));
    }

    @Operation(summary = "El taller aprueba el presupuesto (ej: el cliente lo acepta en persona)")
    @PostMapping("/{presupuestoId}/aprobar")
    public ResponseEntity<PresupuestoResponseDTO> aprobar(
            @PathVariable Long reparacionId, @PathVariable Long presupuestoId) {
        return ResponseEntity.ok(presupuestoService.responder(reparacionId, presupuestoId, true));
    }

    @Operation(summary = "El taller rechaza el presupuesto")
    @PostMapping("/{presupuestoId}/rechazar")
    public ResponseEntity<PresupuestoResponseDTO> rechazar(
            @PathVariable Long reparacionId, @PathVariable Long presupuestoId) {
        return ResponseEntity.ok(presupuestoService.responder(reparacionId, presupuestoId, false));
    }

    @Operation(summary = "Re-presupuestar: clona un presupuesto (vencido) con validez nueva; "
            + "opcionalmente con precios/ítems nuevos en el body")
    @PostMapping("/{presupuestoId}/represupuestar")
    public ResponseEntity<PresupuestoResponseDTO> represupuestar(
            @PathVariable Long reparacionId, @PathVariable Long presupuestoId,
            @RequestBody(required = false) @Valid PresupuestoRequestDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(presupuestoService.represupuestar(reparacionId, presupuestoId, request));
    }
}
