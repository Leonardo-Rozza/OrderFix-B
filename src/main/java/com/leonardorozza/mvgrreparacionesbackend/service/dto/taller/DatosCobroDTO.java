package com.leonardorozza.mvgrreparacionesbackend.service.dto.taller;

public record DatosCobroDTO(
        String alias,
        String titular,
        String entidad,
        boolean mostrarEnResumen,
        boolean qrDisponible,
        String qrVersion
) {
}
