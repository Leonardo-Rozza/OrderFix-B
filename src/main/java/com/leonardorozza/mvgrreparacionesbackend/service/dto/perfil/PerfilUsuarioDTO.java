package com.leonardorozza.mvgrreparacionesbackend.service.dto.perfil;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;

public record PerfilUsuarioDTO(
        Long id,
        String username,
        String email,
        UserRole role,
        boolean emailVerificado
) {
}
