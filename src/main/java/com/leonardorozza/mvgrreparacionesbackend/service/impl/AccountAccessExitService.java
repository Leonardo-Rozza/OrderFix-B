package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.BadRequestException;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.UnauthorizedException;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.User;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/** Immediate deactivation of the authenticated employee; no workshop or operational data is deleted. */
@Service
@RequiredArgsConstructor
public class AccountAccessExitService {
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final UserSecurityStateLock securityState;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void deactivate(AuthenticatedUserPrincipal principal, String passwordActual) {
        if (principal == null || principal.getUserId() == null || principal.getTallerId() == null
                || principal.getUserId() <= 0 || principal.getTallerId() <= 0 || !principal.isEnabled()) throw staleSession();
        User user = users.findByIdAndTallerId(principal.getUserId(), principal.getTallerId())
                .orElseThrow(AccountAccessExitService::staleSession);
        securityState.refreshAndLock(user);
        if (!Objects.equals(user.getId(), principal.getUserId()) || user.getTaller() == null
                || !Objects.equals(user.getTaller().getId(), principal.getTallerId())
                || !Boolean.TRUE.equals(user.getActive()) || !Boolean.TRUE.equals(user.getTaller().getActivo())
                || !Objects.equals(user.getEmail(), principal.getUsername())
                || user.getTokenVersion() < 0 || user.getTokenVersion() != principal.getTokenVersion()) {
            throw staleSession();
        }
        if (user.getRole() != UserRole.USER || principal.getAuthorities().stream()
                .noneMatch(authority -> "ROLE_USER".equals(authority.getAuthority()))) {
            throw new AccessDeniedException("La baja de acceso individual corresponde a empleados.");
        }
        boolean matches = false;
        if (passwordActual != null && !passwordActual.isBlank() && passwordActual.length() <= 100) {
            try { matches = passwords.matches(passwordActual, user.getPassword()); }
            catch (IllegalArgumentException invalidCredential) { /* Invalid BCrypt input is not a server failure. */ }
        }
        if (!matches) {
            throw new BadRequestException("PASSWORD_ACTUAL_INVALIDA", "La contraseña actual no es correcta.");
        }
        // Compute before mutation; overflow leaves even this persistence-context snapshot unchanged.
        long nextVersion = Math.addExact(user.getTokenVersion(), 1L);
        user.setActive(false);
        user.setTokenVersion(nextVersion);
        users.save(user);
    }

    private static UnauthorizedException staleSession() {
        return new UnauthorizedException("La sesión ya no corresponde a un acceso activo.");
    }
}
