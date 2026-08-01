package com.leonardorozza.mvgrreparacionesbackend.config;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Suscripcion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Taller;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.User;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoSuscripcion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PlanType;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.SuscripcionRepository;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.TallerRepository;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Component
@Slf4j
@RequiredArgsConstructor
public class DataLoader implements CommandLineRunner {

    private final UserRepository userRepository;
    private final TallerRepository tallerRepository;
    private final SuscripcionRepository suscripcionRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${admin.user}")
    private String username;

    @Value("${admin.password}")
    private String password;

    @Value("${admin.email:admin@mvgr.com}")
    private String email;

    @Override
    @Transactional
    public void run(String @NonNull ... args) {

        if (userRepository.count() > 0) {
            return;
        }

        // Taller "dueño" inicial (cuenta del propio negocio)
        Taller taller = tallerRepository.save(
                Taller.builder()
                        .nombre("MVGR Reparaciones")
                        .emailContacto(email)
                        .activo(true)
                        .build()
        );

        // Suscripción PRO/ACTIVA para la cuenta interna
        suscripcionRepository.save(
                Suscripcion.builder()
                        .taller(taller)
                        .plan(PlanType.PRO)
                        .estado(EstadoSuscripcion.ACTIVA)
                        .fechaInicio(LocalDate.now())
                        .build()
        );

        // Usuario admin del taller (login por email)
        userRepository.save(
                User.builder()
                        .username(username)
                        .password(passwordEncoder.encode(password))
                        .email(email)
                        .role(UserRole.ADMIN)
                        .active(true)
                        .emailVerificado(true)
                        .taller(taller)
                        .build()
        );

        log.info("=== Taller + admin inicial creados correctamente (login: {}) ===", email);
    }
}
