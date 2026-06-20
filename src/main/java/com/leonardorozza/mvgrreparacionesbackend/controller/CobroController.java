package com.leonardorozza.mvgrreparacionesbackend.controller;

import com.leonardorozza.mvgrreparacionesbackend.service.dto.cobro.CobroRequestDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.cobro.CobroResponseDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.cobro.CobrosReparacionDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.cobro.ReciboDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.CobroService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reparaciones/{reparacionId}")
@RequiredArgsConstructor
@Tag(name = "Cobros", description = "Cobros y recibo de una reparación")
public class CobroController {

    private final CobroService cobroService;

    @Operation(summary = "Registrar un cobro (pago) sobre la reparación")
    @PostMapping("/cobros")
    public ResponseEntity<CobroResponseDTO> registrar(
            @PathVariable Long reparacionId, @Valid @RequestBody CobroRequestDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(cobroService.registrar(reparacionId, request));
    }

    @Operation(summary = "Listar cobros + resumen (total / cobrado / saldo)")
    @GetMapping("/cobros")
    public ResponseEntity<CobrosReparacionDTO> listar(@PathVariable Long reparacionId) {
        return ResponseEntity.ok(cobroService.listarPorReparacion(reparacionId));
    }

    @Operation(summary = "Anular un cobro (solo ADMIN)")
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/cobros/{cobroId}")
    public ResponseEntity<Void> eliminar(@PathVariable Long reparacionId, @PathVariable Long cobroId) {
        cobroService.eliminar(cobroId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Datos del recibo de la reparación (para imprimir/compartir)")
    @GetMapping("/recibo")
    public ResponseEntity<ReciboDTO> recibo(@PathVariable Long reparacionId) {
        return ResponseEntity.ok(cobroService.recibo(reparacionId));
    }
}
