package com.leonardorozza.mvgrreparacionesbackend.service.dto.perfil;

public record PerfilResponseDTO(
        PerfilUsuarioDTO usuario,
        PerfilTallerDTO taller
) {
}
