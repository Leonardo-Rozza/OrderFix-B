package com.leonardorozza.mvgrreparacionesbackend.service.dto.usuario;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * Actualización parcial de un empleado. El estado puede cambiarse; el rol es
 * inmutable y sólo se conserva temporalmente para detectar clientes antiguos.
 */
@Data
public class ActualizarUsuarioRequestDTO {

    /**
     * Compatibilidad temporal. Repetir el rol actual es un no-op; intentar
     * cambiarlo se rechaza.
     */
    @Schema(
            description = "Campo legacy: repetir el rol actual es un no-op; cambiarlo se rechaza.",
            deprecated = true)
    private UserRole role;

    private Boolean active;
}
