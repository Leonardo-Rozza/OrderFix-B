package com.leonardorozza.mvgrreparacionesbackend.controller;

import com.leonardorozza.mvgrreparacionesbackend.service.dto.SeguimientoPublicoDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.presupuesto.PresupuestoResponseDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.PresupuestoService;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.SeguimientoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/seguimiento")
@RequiredArgsConstructor
@Tag(name = "Seguimiento", description = "Consulta pública del estado de una reparación (sin login)")
public class SeguimientoController {

    private final SeguimientoService seguimientoService;
    private final PresupuestoService presupuestoService;

    @Operation(summary = "Consultar estado por código de seguimiento (público)")
    @GetMapping("/{codigo}")
    public ResponseEntity<SeguimientoPublicoDTO> consultar(@PathVariable String codigo) {
        return ResponseEntity.ok(seguimientoService.consultar(codigo));
    }

    @Operation(summary = "El cliente aprueba el presupuesto pendiente (público)")
    @PostMapping("/{codigo}/presupuesto/aprobar")
    public ResponseEntity<PresupuestoResponseDTO> aprobar(@PathVariable String codigo) {
        return ResponseEntity.ok(presupuestoService.responderPorCodigo(codigo, true));
    }

    @Operation(summary = "El cliente rechaza el presupuesto pendiente (público)")
    @PostMapping("/{codigo}/presupuesto/rechazar")
    public ResponseEntity<PresupuestoResponseDTO> rechazar(@PathVariable String codigo) {
        return ResponseEntity.ok(presupuestoService.responderPorCodigo(codigo, false));
    }
}
