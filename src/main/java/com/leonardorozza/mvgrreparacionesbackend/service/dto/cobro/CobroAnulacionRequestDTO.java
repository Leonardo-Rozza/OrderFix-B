package com.leonardorozza.mvgrreparacionesbackend.service.dto.cobro;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CobroAnulacionRequestDTO(
        @NotBlank
        @Size(max = 255)
        String motivo
) {
    public CobroAnulacionRequestDTO {
        motivo = motivo == null ? null : motivo.trim();
    }
}
