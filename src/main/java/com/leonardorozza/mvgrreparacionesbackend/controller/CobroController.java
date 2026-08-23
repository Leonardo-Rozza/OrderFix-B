package com.leonardorozza.mvgrreparacionesbackend.controller;

import com.leonardorozza.mvgrreparacionesbackend.service.dto.cobro.CobroRequestDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.cobro.CobroResponseDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.cobro.CobrosReparacionDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.cobro.CobroAnulacionRequestDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.cobro.ResumenDigitalOrdenDTO;
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
@Tag(name = "Cobros", description = "Cobros y resumen digital de una reparación")
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

    @Operation(summary = "Anular un cobro con motivo y auditoría (solo ADMIN)")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/cobros/{cobroId}/anulacion")
    public ResponseEntity<CobroResponseDTO> anular(
            @PathVariable Long reparacionId,
            @PathVariable Long cobroId,
            @Valid @RequestBody CobroAnulacionRequestDTO request) {
        return ResponseEntity.ok(cobroService.anular(reparacionId, cobroId, request));
    }

    @Deprecated
    @Operation(summary = "Alias legado para anular un cobro", deprecated = true)
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/cobros/{cobroId}")
    public ResponseEntity<Void> anularLegado(
            @PathVariable Long reparacionId, @PathVariable Long cobroId) {
        cobroService.anularLegado(reparacionId, cobroId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Resumen digital de la orden")
    @GetMapping("/resumen-digital")
    public ResponseEntity<ResumenDigitalOrdenDTO> resumenDigital(@PathVariable Long reparacionId) {
        return ResponseEntity.ok(cobroService.resumenDigital(reparacionId));
    }

    @Deprecated(since = "2026-08-23", forRemoval = false)
    @Operation(summary = "Alias legado del resumen digital de la orden", deprecated = true)
    @GetMapping("/recibo")
    public ResponseEntity<ResumenDigitalOrdenDTO> recibo(@PathVariable Long reparacionId) {
        return ResponseEntity.ok(cobroService.resumenDigital(reparacionId));
    }
}
