package com.leonardorozza.mvgrreparacionesbackend.controller;

import com.leonardorozza.mvgrreparacionesbackend.service.dto.SuscripcionResponseDto;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.SuscripcionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/suscripcion")
@RequiredArgsConstructor
@Tag(name = "Suscripción", description = "Plan y consumo del taller")
public class SuscripcionController {

    private final SuscripcionService suscripcionService;

    @Operation(summary = "Obtener el plan y consumo del taller actual")
    @GetMapping
    public ResponseEntity<SuscripcionResponseDto> miSuscripcion() {
        return ResponseEntity.ok(suscripcionService.miSuscripcion());
    }
}
