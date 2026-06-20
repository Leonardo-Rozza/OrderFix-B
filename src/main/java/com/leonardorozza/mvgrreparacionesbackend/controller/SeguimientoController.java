package com.leonardorozza.mvgrreparacionesbackend.controller;

import com.leonardorozza.mvgrreparacionesbackend.service.dto.SeguimientoPublicoDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.SeguimientoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/seguimiento")
@RequiredArgsConstructor
@Tag(name = "Seguimiento", description = "Consulta pública del estado de una reparación (sin login)")
public class SeguimientoController {

    private final SeguimientoService seguimientoService;

    @Operation(summary = "Consultar estado por código de seguimiento (público)")
    @GetMapping("/{codigo}")
    public ResponseEntity<SeguimientoPublicoDTO> consultar(@PathVariable String codigo) {
        return ResponseEntity.ok(seguimientoService.consultar(codigo));
    }
}
