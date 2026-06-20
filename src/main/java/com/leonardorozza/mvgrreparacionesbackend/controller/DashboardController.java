package com.leonardorozza.mvgrreparacionesbackend.controller;

import com.leonardorozza.mvgrreparacionesbackend.service.dto.DashboardResponseDto;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.DashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
@Tag(name = "Dashboard", description = "Métricas del taller")
public class DashboardController {

    private final DashboardService dashboardService;

    @Operation(summary = "Métricas del taller actual (conteos por estado, consumo, plan)")
    @GetMapping
    public ResponseEntity<DashboardResponseDto> obtener() {
        return ResponseEntity.ok(dashboardService.obtener());
    }
}
