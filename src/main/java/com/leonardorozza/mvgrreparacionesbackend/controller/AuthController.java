package com.leonardorozza.mvgrreparacionesbackend.controller;


import com.leonardorozza.mvgrreparacionesbackend.service.dto.AuthResponseDto;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.LoginRequestDto;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.RegisterRequestDto;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.AuthService;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.RegistroService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "Login", description = "Inicio de sesion")
public class AuthController {

    private final AuthService authService;
    private final RegistroService registroService;

    @PostMapping("/login")
    public ResponseEntity<AuthResponseDto> login(@Valid @RequestBody LoginRequestDto request) {

        String token = authService.login(request.email(), request.password());

        return ResponseEntity.ok(
                new AuthResponseDto(
                        token,
                        "Bearer",
                        request.email()
                )
        );
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponseDto> register(@Valid @RequestBody RegisterRequestDto request) {
        return ResponseEntity.status(201).body(registroService.registrar(request));
    }
}

