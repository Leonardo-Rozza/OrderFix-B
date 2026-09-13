package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.User;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.UserRepository;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.AuthResponseDto;
import com.leonardorozza.mvgrreparacionesbackend.utils.jwt.JwtUtils;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.WorkshopClosureAccess;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.Clock;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Emite una sesión desde identidad durable y credenciales actuales. Login y la emisión poscommit
 * de registro reutilizan esta política, sin recuperar la cuenta por un email histórico.
 */
@Service
public class AccountSessionPolicy {

    private static final Runnable NO_CHECKPOINT = () -> { };
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;
    private final Clock clock;

    @Autowired
    public AccountSessionPolicy(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtUtils jwtUtils, Clock clock) {
        this.userRepository = Objects.requireNonNull(userRepository);
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder);
        this.jwtUtils = Objects.requireNonNull(jwtUtils);
        this.clock = Objects.requireNonNull(clock);
    }

    public AccountSessionPolicy(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtUtils jwtUtils) {
        this(userRepository, passwordEncoder, jwtUtils, Clock.systemUTC());
    }

    /**
     * El llamador de registro debe haber confirmado el alta. La transacción propia evita reutilizar
     * entidades del llamador o leer sus cambios pendientes; no escribe ni envía email.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED,
            readOnly = true)
    public AuthResponseDto issueSession(Long userId, Long tallerId, String password) {
        return issueInCurrentTransaction(userId, tallerId, password, NO_CHECKPOINT);
    }

    /** The bounded issuer owns the transaction and the check after its completion and cleanup. */
    AuthResponseDto issueInCurrentTransaction(Long userId, Long tallerId, String password, Runnable checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint").run();
        if (userId == null || userId <= 0 || tallerId == null || tallerId <= 0
                || password == null || password.isBlank()) {
            throw rejected();
        }

        User user = checked(checkpoint, () -> userRepository.findSessionByIdAndTallerId(userId, tallerId))
                .orElseThrow(AccountSessionPolicy::rejected);
        if (!Objects.equals(userId, user.getId()) || user.getTaller() == null
                || !Objects.equals(tallerId, user.getTaller().getId())
                || !Boolean.TRUE.equals(user.getActive())
                || WorkshopClosureAccess.mode(user, clock.instant()) == WorkshopClosureAccess.Mode.DENIED
                || user.getRole() == null || user.getTokenVersion() < 0
                || user.getEmail() == null || user.getEmail().isBlank()
                || user.getPassword() == null || user.getPassword().isBlank()) {
            throw rejected();
        }
        if (!checked(checkpoint, () -> passwordEncoder.matches(password, user.getPassword()))) {
            throw rejected();
        }

        // BCrypt and token generation must not carry a restricted login beyond the grace deadline.
        AuthenticatedUserPrincipal principal = new AuthenticatedUserPrincipal(user, clock.instant());
        if (!principal.isEnabled()) throw rejected();
        String token = checked(checkpoint, () -> jwtUtils.generateToken(principal, principal.getTallerId()));
        if (WorkshopClosureAccess.mode(user, clock.instant()) == WorkshopClosureAccess.Mode.DENIED) throw rejected();
        return new AuthResponseDto(token, "Bearer", principal.getUsername(), principal.isEmailVerificado());
    }

    private static <T> T checked(Runnable checkpoint, Supplier<T> operation) {
        checkpoint.run();
        final T result;
        try {
            result = operation.get();
        } catch (RuntimeException primary) {
            try {
                checkpoint.run();
            } catch (RuntimeException unavailable) {
                if (unavailable != primary) unavailable.addSuppressed(primary);
                throw unavailable;
            }
            throw primary;
        }
        // Keep this outside the operation's catch: a failed postcheck is not checked twice.
        checkpoint.run();
        return result;
    }

    private static BadCredentialsException rejected() {
        return new BadCredentialsException("Usuario o contraseña incorrectos");
    }
}
