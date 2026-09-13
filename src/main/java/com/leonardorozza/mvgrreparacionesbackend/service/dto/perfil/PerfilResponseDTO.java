package com.leonardorozza.mvgrreparacionesbackend.service.dto.perfil;

import com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.WorkshopClosureAccess;

public record PerfilResponseDTO(
        PerfilUsuarioDTO usuario,
        PerfilTallerDTO taller,
        WorkshopClosureAccess.Mode accesoTaller
) {
}
