package com.leonardorozza.mvgrreparacionesbackend.service.dto.usuario;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Alta de un empleado del taller. Si no se envía rol, se crea como USER.
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

    /** Opcional: ADMIN o USER. Default USER. */
    private UserRole role;
}
