package com.leonardorozza.mvgrreparacionesbackend.service.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Alta de un taller nuevo (onboarding) junto con su usuario administrador.
 */
public record RegisterRequestDto(
        @NotBlank @Size(max = 120) String nombreTaller,
        @Size(max = 20) String telefonoTaller,
        @NotBlank @Size(max = 50) String nombreAdmin,
        @NotBlank @Email String email,
        @NotBlank @Size(min = 6, max = 100) String password
) {}
