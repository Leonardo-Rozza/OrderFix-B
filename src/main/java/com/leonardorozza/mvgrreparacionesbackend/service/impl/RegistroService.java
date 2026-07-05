package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.exceptions.BadRequestException;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Suscripcion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Taller;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.User;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoSuscripcion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PlanType;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.SuscripcionRepository;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.TallerRepository;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.UserRepository;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.AuthResponseDto;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.RegisterRequestDto;
import com.leonardorozza.mvgrreparacionesbackend.utils.jwt.JwtUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * Onboarding: crea un taller nuevo con su usuario admin y una suscripción en TRIAL,
 * y devuelve un token para que el usuario quede logueado automáticamente.
 */
@Service
@RequiredArgsConstructor
public class RegistroService {

    private final TallerRepository tallerRepository;
    private final SuscripcionRepository suscripcionRepository;
    private final UserRepository userRepository;
    private final UserDetailsServiceImpl userDetailsService;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;
    private final CuentaService cuentaService;

    @Value("${plan.trial-dias:14}")
    private int trialDias;

    @Transactional
    public AuthResponseDto registrar(RegisterRequestDto request) {

        if (userRepository.existsByEmail(request.email())) {
            throw new BadRequestException("Ya existe una cuenta con ese email.");
        }

        // 1) Taller (tenant)
        Taller taller = tallerRepository.save(
                Taller.builder()
                        .nombre(request.nombreTaller())
                        .emailContacto(request.email())
                        .telefono(request.telefonoTaller())
                        .activo(true)
                        .build()
        );

        // 2) Suscripción FREE en TRIAL
        LocalDate hoy = LocalDate.now();
        suscripcionRepository.save(
                Suscripcion.builder()
                        .taller(taller)
                        .plan(PlanType.FREE)
                        .estado(EstadoSuscripcion.TRIAL)
                        .fechaInicio(hoy)
                        .fechaFinTrial(hoy.plusDays(trialDias))
                        .build()
        );

        // 3) Usuario administrador del taller
        User admin = userRepository.save(
                User.builder()
                        .username(request.nombreAdmin())
                        .password(passwordEncoder.encode(request.password()))
                        .email(request.email())
                        .role(UserRole.ADMIN)
                        .active(true)
                        .emailVerificado(false)
                        .taller(taller)
                        .build()
        );

        // 4) Email de bienvenida + verificación (si falla el envío, el registro sigue igual)
        cuentaService.enviarVerificacion(admin);

        // 5) Auto-login: emitimos el token con el taller recién creado
        UserDetails userDetails = userDetailsService.loadUserByUsername(request.email());
        String token = jwtUtils.generateToken(userDetails, taller.getId());

        return new AuthResponseDto(token, "Bearer", request.email(), false);
    }
}
