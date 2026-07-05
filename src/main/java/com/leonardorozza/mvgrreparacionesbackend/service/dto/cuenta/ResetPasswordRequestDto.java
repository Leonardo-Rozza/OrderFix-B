package com.leonardorozza.mvgrreparacionesbackend.service.dto.cuenta;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequestDto(
        @NotBlank String token,
        @NotBlank @Size(min = 6, max = 100) String nuevaPassword
) {}
