package com.leonardorozza.mvgrreparacionesbackend.service.dto.usuario;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Alta de un empleado del taller. Los empleados siempre se crean como USER.
 */
@Data
public class CrearUsuarioRequestDTO {

    @NotBlank
    @Size(max = 50)
    private String username;

    @NotBlank
    @Email
    @Size(max = 120)
    private String email;

    @NotBlank
    @Size(min = 6, max = 100)
    private String password;

    /**
     * Compatibilidad temporal con clientes anteriores. Puede omitirse o enviarse
     * como USER; ADMIN se rechaza porque el titular del taller es único.
     */
    @Schema(
            description = "Campo legacy: puede omitirse o enviarse como USER; ADMIN se rechaza.",
            allowableValues = "USER",
            deprecated = true)
    private UserRole role;
}
