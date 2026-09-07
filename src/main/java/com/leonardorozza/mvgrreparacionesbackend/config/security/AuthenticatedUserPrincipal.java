package com.leonardorozza.mvgrreparacionesbackend.config.security;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.User;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

/**
 * Identidad autenticada construida desde el usuario persistido en cada request.
 * Conserva los datos server-side necesarios para no confiar en claims de tenant o revocación.
 */
public final class AuthenticatedUserPrincipal
        extends org.springframework.security.core.userdetails.User {

    private final Long userId;
    private final Long tallerId;
    private final long tokenVersion;
    private final boolean emailVerificado;

    public AuthenticatedUserPrincipal(User user) {
        super(
                user.getEmail(),
                user.getPassword(),
                Boolean.TRUE.equals(user.getActive())
                        && user.getTaller() != null
                        && user.getTaller().getId() != null
                        && user.getTaller().getId() > 0
                        && Boolean.TRUE.equals(user.getTaller().getActivo()),
                true,
                true,
                true,
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()))
        );
        this.userId = user.getId();
        this.tallerId = user.getTaller() != null ? user.getTaller().getId() : null;
        this.tokenVersion = user.getTokenVersion();
        this.emailVerificado = Boolean.TRUE.equals(user.getEmailVerificado());
    }

    public Long getUserId() {
        return userId;
    }

    public Long getTallerId() {
        return tallerId;
    }

    public long getTokenVersion() {
        return tokenVersion;
    }

    public boolean isEmailVerificado() {
        return emailVerificado;
    }
}
