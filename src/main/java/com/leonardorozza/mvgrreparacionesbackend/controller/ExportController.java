package com.leonardorozza.mvgrreparacionesbackend.controller;

import com.leonardorozza.mvgrreparacionesbackend.cuenta.export.http.ExportHttpRequests;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.export.http.ProtectedExcelExportService;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/export")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name="Exportación",description="Reportes operativos con confirmación de contraseña")
public class ExportController {
    private static final MediaType XLSX=MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private final ProtectedExcelExportService service;
    public ExportController(ProtectedExcelExportService service) { this.service=service; }

    @Operation(summary="Ruta retirada: confirmar contraseña y usar POST para descargar el reporte operativo")
    @GetMapping("/excel")
    public ResponseEntity<ApiError> retired(HttpServletRequest request) {
        ExportHttpRequests.require(request,ExportHttpRequests.Operation.RETIRED);
        return ResponseEntity.status(HttpStatus.GONE).header(HttpHeaders.CACHE_CONTROL,"private, no-store")
                .header("X-Content-Type-Options","nosniff")
                .body(new ApiError(LocalDateTime.now(),410,"Descarga actualizada","Confirmá tu contraseña para descargar el reporte operativo.",
                        request.getRequestURI(),"EXPORTACION_REAUTENTICACION_REQUERIDA",null));
    }

    @Operation(summary="Descargar reporte operativo XLSX con confirmación de contraseña de un solo uso")
    @PostMapping("/excel")
    public ResponseEntity<byte[]> excel(HttpServletRequest request) {
        ExportHttpRequests.require(request,ExportHttpRequests.Operation.DOWNLOAD); ExportHttpRequests.emptyBody(request);
        byte[] bytes=service.download(ExportHttpRequests.accessToken(request),ExportHttpRequests.proof(request));
        String name="ordenfix-export-"+LocalDate.now()+".xlsx";
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"private, no-store").header("X-Content-Type-Options","nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+name+"\"")
                .contentType(XLSX).contentLength(bytes.length).body(bytes);
    }
}
