package com.leonardorozza.mvgrreparacionesbackend.controller;

import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.taller.DatosCobroDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.taller.DatosCobroRequestDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.DatosCobroTallerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

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

    @Operation(summary = "Obtener la imagen QR de cobro normalizada")
    @GetMapping(value = "/qr", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> obtenerQr(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        var qr = datosCobroTallerService.obtenerQr(principal);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .header(HttpHeaders.ETAG, "\"" + qr.sha256() + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(qr.png());
    }

    @Operation(summary = "Reemplazar la imagen QR de cobro (solo ADMIN)")
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping(value = "/qr", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DatosCobroDTO> actualizarQr(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @RequestPart("file") MultipartFile file) {
        return ResponseEntity.ok(datosCobroTallerService.actualizarQr(principal, file));
    }

    @Operation(summary = "Eliminar la imagen QR de cobro (solo ADMIN)")
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/qr")
    public ResponseEntity<DatosCobroDTO> eliminarQr(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return ResponseEntity.ok(datosCobroTallerService.eliminarQr(principal));
    }
}
