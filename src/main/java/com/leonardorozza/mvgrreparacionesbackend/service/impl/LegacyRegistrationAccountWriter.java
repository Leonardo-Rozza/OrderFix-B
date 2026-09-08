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
import com.leonardorozza.mvgrreparacionesbackend.service.dto.RegisterRequestDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;

/** Creates the historical account graph; its proxied return precedes notification and session issuance. */
@Service
public class LegacyRegistrationAccountWriter {
    private final TallerRepository tallerRepository;
    private final SuscripcionRepository suscripcionRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final int trialDias;

    public LegacyRegistrationAccountWriter(TallerRepository tallerRepository,
            SuscripcionRepository suscripcionRepository, UserRepository userRepository,
            PasswordEncoder passwordEncoder, Clock clock,
            @Value("${plan.trial-dias:14}") int trialDias) {
        this.tallerRepository = tallerRepository;
        this.suscripcionRepository = suscripcionRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.trialDias = trialDias;
    }

    /** No entity escapes the independent account transaction. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED,
            readOnly = false)
    public Identity create(RegisterRequestDto request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new BadRequestException("Ya existe una cuenta con ese email.");
        }

        Taller taller = tallerRepository.save(Taller.builder()
                .nombre(request.nombreTaller())
                .emailContacto(request.email())
                .telefono(request.telefonoTaller())
                .activo(true)
                .build());

        LocalDate hoy = LocalDate.now(clock);
        suscripcionRepository.save(Suscripcion.builder()
                .taller(taller)
                .plan(PlanType.FREE)
                .estado(EstadoSuscripcion.TRIAL)
                .fechaInicio(hoy)
                .fechaFinTrial(hoy.plusDays(trialDias))
                .build());

        User admin = userRepository.save(User.builder()
                .username(request.nombreAdmin())
                .password(passwordEncoder.encode(request.password()))
                .email(request.email())
                .role(UserRole.ADMIN)
                .active(true)
                .emailVerificado(false)
                .taller(taller)
                .build());

        return new Identity(admin.getId(), taller.getId());
    }

    /** Committed IDs for the caller after the Spring proxy has returned successfully. */
    public record Identity(long userId, long tallerId) {
        public Identity {
            if (userId <= 0 || tallerId <= 0) throw new IllegalArgumentException("La identidad de cuenta no es válida.");
        }
        @Override public String toString() { return "Identity[redacted]"; }
    }
}
