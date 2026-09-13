package com.leonardorozza.mvgrreparacionesbackend.config.security;

import com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.WorkshopClosureAccess;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.User;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.time.Instant;
import java.util.List;

/**
 * Identity rebuilt from the persisted user/workshop on each request. Restricted access remains
 * authenticated, but admission and sensitive services must enforce its narrower operations.
 */
public final class AuthenticatedUserPrincipal
        extends org.springframework.security.core.userdetails.User {

    private final Long userId;
    private final Long tallerId;
    private final long tokenVersion;
    private final boolean emailVerificado;
    private final WorkshopClosureAccess.Mode workshopAccessMode;

    public AuthenticatedUserPrincipal(User user) {
        this(user, Instant.now());
    }

    public AuthenticatedUserPrincipal(User user, Instant observedAt) {
        this(user, WorkshopClosureAccess.mode(user, observedAt));
    }

    private AuthenticatedUserPrincipal(User user, WorkshopClosureAccess.Mode mode) {
        super(user.getEmail(), user.getPassword(), mode != WorkshopClosureAccess.Mode.DENIED,
                true, true, true, user.getRole() == null ? List.of()
                        : List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));
        this.userId = user.getId();
        this.tallerId = user.getTaller() != null ? user.getTaller().getId() : null;
        this.tokenVersion = user.getTokenVersion();
        this.emailVerificado = Boolean.TRUE.equals(user.getEmailVerificado());
        this.workshopAccessMode = mode;
    }

    public Long getUserId() { return userId; }
    public Long getTallerId() { return tallerId; }
    public long getTokenVersion() { return tokenVersion; }
    public boolean isEmailVerificado() { return emailVerificado; }
    public WorkshopClosureAccess.Mode getWorkshopAccessMode() { return workshopAccessMode; }
    public boolean isWorkshopRestricted() { return workshopAccessMode == WorkshopClosureAccess.Mode.RESTRICTED; }
}
