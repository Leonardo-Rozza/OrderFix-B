package com.leonardorozza.mvgrreparacionesbackend.controller;

import com.leonardorozza.mvgrreparacionesbackend.service.dto.cobro.CajaResumenDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.CobroService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/caja")
@RequiredArgsConstructor
@Tag(name = "Caja", description = "Resumen de cobros por período")
public class CajaController {

    private final CobroService cobroService;

    @Operation(summary = "Resumen de caja (por defecto, hoy). Filtros: ?desde=YYYY-MM-DD&hasta=YYYY-MM-DD")
    @GetMapping
    public ResponseEntity<CajaResumenDTO> caja(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta) {
        return ResponseEntity.ok(cobroService.caja(desde, hasta));
    }
}
