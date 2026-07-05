package com.leonardorozza.mvgrreparacionesbackend.service.dto.cuenta;

import jakarta.validation.constraints.NotBlank;

public record VerificarEmailRequestDto(
        @NotBlank String token
) {}
