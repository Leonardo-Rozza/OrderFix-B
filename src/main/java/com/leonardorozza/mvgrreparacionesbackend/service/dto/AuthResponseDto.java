package com.leonardorozza.mvgrreparacionesbackend.service.dto;

public record AuthResponseDto(
        String token,
        String type,
        String email
) {}