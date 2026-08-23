package com.leonardorozza.mvgrreparacionesbackend.controller;

import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.taller.DatosCobroDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.taller.DatosCobroRequestDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.DatosCobroTallerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/taller/datos-cobro")
@RequiredArgsConstructor
@Tag(name = "Datos de cobro", description = "Datos públicos de pago configurados por el taller")
public class DatosCobroTallerController {

    private final DatosCobroTallerService datosCobroTallerService;

    @Operation(summary = "Obtener los datos públicos de cobro del taller")
    @GetMapping
    public ResponseEntity<DatosCobroDTO> obtener(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return ResponseEntity.ok(datosCobroTallerService.obtener(principal));
    }

    @Operation(summary = "Reemplazar los datos públicos de cobro del taller (solo ADMIN)")
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping
    public ResponseEntity<DatosCobroDTO> actualizar(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @Valid @RequestBody DatosCobroRequestDTO request) {
        return ResponseEntity.ok(datosCobroTallerService.actualizar(principal, request));
    }
}
