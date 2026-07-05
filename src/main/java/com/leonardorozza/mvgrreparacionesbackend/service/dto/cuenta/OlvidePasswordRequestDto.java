package com.leonardorozza.mvgrreparacionesbackend.service.dto.cuenta;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record OlvidePasswordRequestDto(
        @NotBlank @Email String email
) {}
