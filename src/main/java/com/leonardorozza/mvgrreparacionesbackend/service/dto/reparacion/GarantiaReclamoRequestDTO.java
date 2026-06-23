package com.leonardorozza.mvgrreparacionesbackend.service.dto.reparacion;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Reclamo en garantía: el cliente vuelve por (idealmente) el mismo problema.
 * Crea una reparación nueva vinculada a la original.
 */
@Data
public class GarantiaReclamoRequestDTO {

    @NotBlank
    @Size(max = 255)
    private String descripcionProblema;
}
