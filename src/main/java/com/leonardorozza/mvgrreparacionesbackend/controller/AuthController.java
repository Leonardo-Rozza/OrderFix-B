package com.leonardorozza.mvgrreparacionesbackend.controller;


import com.leonardorozza.mvgrreparacionesbackend.service.dto.AuthResponseDto;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.LoginRequestDto;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.RegisterRequestDto;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.cuenta.OlvidePasswordRequestDto;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.cuenta.ResetPasswordRequestDto;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.cuenta.VerificarEmailRequestDto;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.AuthService;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.CuentaService;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.RegistroService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "Login", description = "Inicio de sesion")
public class AuthController {

    private final AuthService authService;
    private final RegistroService registroService;
    private final CuentaService cuentaService;

    @PostMapping("/login")
    public ResponseEntity<AuthResponseDto> login(@Valid @RequestBody LoginRequestDto request) {
        return ResponseEntity.ok(authService.login(request.email(), request.password()));
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponseDto> register(@Valid @RequestBody RegisterRequestDto request) {
        return ResponseEntity.status(201).body(registroService.registrar(request));
    }

    // ---------- Olvido / reset de contraseña ----------

    @Operation(summary = "Pedir link de reset de contraseña por email (siempre 200: no revela si el email existe)")
    @PostMapping("/password/olvide")
    public ResponseEntity<Map<String, String>> olvidePassword(@Valid @RequestBody OlvidePasswordRequestDto request) {
        cuentaService.olvidePassword(request.email());
        return ResponseEntity.ok(Map.of(
                "message", "Si el email está registrado, te enviamos un link para restablecer la contraseña."));
    }

    @Operation(summary = "Restablecer la contraseña con el token del email (un solo uso, vence en 1 h)")
    @PostMapping("/password/reset")
    public ResponseEntity<Map<String, String>> resetPassword(@Valid @RequestBody ResetPasswordRequestDto request) {
        cuentaService.resetPassword(request.token(), request.nuevaPassword());
        return ResponseEntity.ok(Map.of("message", "Contraseña actualizada. Ya podés iniciar sesión."));
    }

    // ---------- Verificación de email ----------

    @Operation(summary = "Confirmar el email con el token del email de bienvenida")
    @PostMapping("/verificar-email")
    public ResponseEntity<Map<String, String>> verificarEmail(@Valid @RequestBody VerificarEmailRequestDto request) {
        cuentaService.verificarEmail(request.token());
        return ResponseEntity.ok(Map.of("message", "Email confirmado. ¡Gracias!"));
    }

    @Operation(summary = "Reenviar el email de verificación (siempre 200: no revela si el email existe)")
    @PostMapping("/verificar-email/reenviar")
    public ResponseEntity<Map<String, String>> reenviarVerificacion(@Valid @RequestBody OlvidePasswordRequestDto request) {
        cuentaService.reenviarVerificacion(request.email());
        return ResponseEntity.ok(Map.of(
                "message", "Si el email está registrado y sin confirmar, te reenviamos el link de verificación."));
    }
}
