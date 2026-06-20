package com.leonardorozza.mvgrreparacionesbackend.service.dto.usuario;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;

public record UsuarioResponseDTO(
        Long id,
        String username,
        String email,
        UserRole role,
        boolean active
) {
}
