package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.User;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.UserRepository;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.AuthResponseDto;
import com.leonardorozza.mvgrreparacionesbackend.utils.jwt.JwtUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * Emite una sesión desde identidad durable y credenciales actuales. Login y la futura emisión
 * poscommit de registro usan esta misma frontera, sin recuperar la cuenta por un email histórico.
 */
@Service
@RequiredArgsConstructor
public class AccountSessionPolicy {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;

    /**
     * El llamador de registro debe haber confirmado el alta. La transacción propia evita reutilizar
     * entidades del llamador o leer sus cambios pendientes; no escribe ni envía email.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED,
            readOnly = true)
    public AuthResponseDto issueSession(Long userId, Long tallerId, String password) {
        if (userId == null || userId <= 0 || tallerId == null || tallerId <= 0
                || password == null || password.isBlank()) {
            throw rejected();
        }

        User user = userRepository.findSessionByIdAndTallerId(userId, tallerId)
                .orElseThrow(AccountSessionPolicy::rejected);
        if (!Objects.equals(userId, user.getId()) || user.getTaller() == null
                || !Objects.equals(tallerId, user.getTaller().getId())
                || !Boolean.TRUE.equals(user.getActive())
                || !Boolean.TRUE.equals(user.getTaller().getActivo())
                || user.getRole() == null || user.getTokenVersion() < 0
                || user.getEmail() == null || user.getEmail().isBlank()
                || user.getPassword() == null || user.getPassword().isBlank()) {
            throw rejected();
        }
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw rejected();
        }

        AuthenticatedUserPrincipal principal = new AuthenticatedUserPrincipal(user);
        String token = jwtUtils.generateToken(principal, principal.getTallerId());
        return new AuthResponseDto(token, "Bearer", principal.getUsername(), principal.isEmailVerificado());
    }

    private static BadCredentialsException rejected() {
        return new BadCredentialsException("Usuario o contraseña incorrectos");
    }
}
