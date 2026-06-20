package com.leonardorozza.mvgrreparacionesbackend.service.dto.usuario;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import lombok.Data;

/**
 * Actualización parcial de un empleado: cambiar rol y/o activar/desactivar.
 * Los campos en null no se modifican.
 */
@Data
public class ActualizarUsuarioRequestDTO {

    private UserRole role;

    private Boolean active;
}
