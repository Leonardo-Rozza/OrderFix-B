package com.leonardorozza.mvgrreparacionesbackend.service.dto.reparacion;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.MomentoFoto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Foto del equipo. {@code momento} es opcional al enviar (default INGRESO).
 */
public record FotoDTO(
        @NotBlank @Size(max = 500) String url,
        MomentoFoto momento
) {
}
