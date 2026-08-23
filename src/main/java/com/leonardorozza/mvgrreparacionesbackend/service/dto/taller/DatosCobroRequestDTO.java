package com.leonardorozza.mvgrreparacionesbackend.service.dto.taller;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record DatosCobroRequestDTO(
        @Size(max = 120)
        String alias,

        @Size(max = 160)
        String titular,

        @Size(max = 120)
        String entidad,

        @NotNull
        Boolean mostrarEnResumen
) {
    public DatosCobroRequestDTO {
        alias = normalizar(alias);
        titular = normalizar(titular);
        entidad = normalizar(entidad);
    }

    private static String normalizar(String valor) {
        if (valor == null) {
            return null;
        }
        String normalizado = valor.trim();
        return normalizado.isEmpty() ? null : normalizado;
    }
}
