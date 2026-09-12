package com.leonardorozza.mvgrreparacionesbackend.cuenta.reauth;

import com.auth0.jwt.interfaces.DecodedJWT;
import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.BadRequestException;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.UnauthorizedException;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.User;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.UserRepository;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.UserSecurityStateLock;
import com.leonardorozza.mvgrreparacionesbackend.utils.jwt.JwtUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.HexFormat;
import java.util.Objects;

/** Internal credentials for export operations; never issues or extends an access session. */
@Service
public class ExportReauthenticationService {
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final int MAX_ACCESS_TOKEN_LENGTH = 8192;
    private final UserRepository users;
    private final UserSecurityStateLock securityState;
    private final PasswordEncoder passwords;
    private final JwtUtils jwt;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final SecureRandom random;

    @Autowired
    public ExportReauthenticationService(UserRepository users, UserSecurityStateLock securityState,
            PasswordEncoder passwords, JwtUtils jwt, JdbcTemplate jdbc, Clock clock) {
        this(users, securityState, passwords, jwt, jdbc, clock, new SecureRandom());
    }

    ExportReauthenticationService(UserRepository users, UserSecurityStateLock securityState,
            PasswordEncoder passwords, JwtUtils jwt, JdbcTemplate jdbc, Clock clock, SecureRandom random) {
        this.users = Objects.requireNonNull(users);
        this.securityState = Objects.requireNonNull(securityState);
        this.passwords = Objects.requireNonNull(passwords);
        this.jwt = Objects.requireNonNull(jwt);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.clock = Objects.requireNonNull(clock);
        this.random = Objects.requireNonNull(random);
    }

    /**
     * A replacement checks the current password again and atomically invalidates the previous grant.
     * A new transaction uses READ_COMMITTED; an existing writable transaction retains its isolation.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReauthenticationGrant issue(String accessToken, String passwordActual,
            ExportReauthenticationPurpose purpose) {
        requireWritableTransaction();
        try {
            requirePurpose(purpose);
            LockedSession session = lockSession(accessToken);
            boolean matches = false;
            if (passwordActual != null && !passwordActual.isBlank() && passwordActual.length() <= 100) {
                try { matches = passwords.matches(passwordActual, session.user().getPassword()); }
                catch (IllegalArgumentException invalidPassword) { /* Reject malformed BCrypt input without exposing it. */ }
            }
            if (!matches) throw new BadRequestException("PASSWORD_ACTUAL_INVALIDA", "La contraseña actual no es correcta.");

            Instant createdAt = liveNow(session);
            Instant expiresAt = createdAt.plus(TTL);
            if (session.expiresAt().isBefore(expiresAt)) expiresAt = session.expiresAt();
            String token = newToken();
            String tokenHash = hash(token);
            // Preserve a repeated random value so the primary key rejects the collision without renewing it.
            jdbc.update("""
                    DELETE FROM public.cuenta_reautenticaciones
                     WHERE user_id = ? AND session_hash = ? AND proposito = ? AND token_hash <> ?
                    """, session.user().getId(), session.hash(), purpose.name(), tokenHash);
            int inserted = jdbc.update("""
                    INSERT INTO public.cuenta_reautenticaciones
                      (token_hash, user_id, taller_id, token_version, session_hash, proposito, creada_en, expira_en)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, tokenHash, session.user().getId(), session.user().getTaller().getId(),
                    session.user().getTokenVersion(), session.hash(), purpose.name(),
                    createdAt.atOffset(ZoneOffset.UTC), expiresAt.atOffset(ZoneOffset.UTC));
            if (inserted != 1) throw unavailable();
            if (!liveNow(session).isBefore(expiresAt)) throw invalidProof();
            return new ReauthenticationGrant(token, expiresAt);
        } catch (BadRequestException | UnauthorizedException | AccessDeniedException rejected) {
            throw rejected;
        } catch (RuntimeException failure) {
            // JWTs, password data and SQL driver diagnostics must not become exception causes.
            throw unavailable();
        }
    }

    /** Consumption and the future sensitive effect must commit or roll back in the caller's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void consume(String accessToken, String opaqueToken, ExportReauthenticationPurpose purpose) {
        requireWritableTransaction();
        try {
            requirePurpose(purpose);
            requireCanonicalToken(opaqueToken);
            LockedSession session = lockSession(accessToken);
            Instant consumedAt = liveNow(session);
            List<Instant> expirations = jdbc.query("""
                    UPDATE public.cuenta_reautenticaciones SET usada_en = ?
                     WHERE token_hash = ? AND user_id = ? AND taller_id = ? AND token_version = ?
                       AND session_hash = ? AND proposito = ? AND usada_en IS NULL AND expira_en > ?
                    RETURNING expira_en
                    """, (row, index) -> row.getObject("expira_en", OffsetDateTime.class).toInstant(),
                    consumedAt.atOffset(ZoneOffset.UTC), hash(opaqueToken), session.user().getId(),
                    session.user().getTaller().getId(), session.user().getTokenVersion(), session.hash(),
                    purpose.name(), consumedAt.atOffset(ZoneOffset.UTC));
            if (expirations.size() != 1) throw invalidProof();
            // The UPDATE may itself have waited for a database lock beyond the grant's lifetime.
            if (!liveNow(session).isBefore(expirations.getFirst())) throw invalidProof();
        } catch (BadRequestException | UnauthorizedException | AccessDeniedException rejected) {
            throw rejected;
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    private LockedSession lockSession(String accessToken) {
        if (accessToken == null || accessToken.isBlank() || accessToken.length() > MAX_ACCESS_TOKEN_LENGTH) {
            throw invalidSession();
        }
        DecodedJWT verified;
        try {
            verified = jwt.verifyToken(accessToken);
            if (verified.getSubject() == null || verified.getSubject().isBlank()
                    || verified.getExpiresAtAsInstant() == null) throw invalidSession();
        } catch (RuntimeException rejected) {
            throw invalidSession();
        }
        User user = users.findByEmail(verified.getSubject()).orElseThrow(ExportReauthenticationService::invalidSession);
        securityState.refreshAndLock(user);
        if (user.getId() == null || user.getId() <= 0 || user.getTaller() == null
                || user.getTaller().getId() == null || user.getTaller().getId() <= 0
                || !Boolean.TRUE.equals(user.getActive()) || !Boolean.TRUE.equals(user.getTaller().getActivo())
                || user.getRole() == null || user.getTokenVersion() < 0
                || user.getEmail() == null || user.getEmail().isBlank()
                || user.getPassword() == null || user.getPassword().isBlank()) throw invalidSession();
        try {
            if (!jwt.validateToken(verified, new AuthenticatedUserPrincipal(user))) throw invalidSession();
        } catch (RuntimeException rejected) {
            throw invalidSession();
        }
        if (user.getRole() != UserRole.ADMIN) throw new AccessDeniedException("La exportación corresponde al titular del taller.");
        if (!Boolean.TRUE.equals(user.getEmailVerificado())) throw new AccessDeniedException("Se requiere un email verificado para exportar.");
        LockedSession session = new LockedSession(user, hash(accessToken), verified.getExpiresAtAsInstant());
        liveNow(session);
        return session;
    }

    private Instant liveNow(LockedSession session) {
        Instant now = clock.instant();
        // Recheck after the user lock/password work; a JWT's verification leeway cannot extend this grant.
        if (!now.isBefore(session.expiresAt())) throw invalidSession();
        return now;
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        try {
            random.nextBytes(bytes);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static void requireCanonicalToken(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw invalidProof();
        byte[] bytes = null;
        try {
            bytes = Base64.getUrlDecoder().decode(token);
            if (bytes.length != 32 || !Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(token)) {
                throw invalidProof();
            }
        } catch (IllegalArgumentException malformed) {
            throw invalidProof();
        } finally {
            if (bytes != null) Arrays.fill(bytes, (byte) 0);
        }
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw unavailable();
        }
    }

    private static void requirePurpose(ExportReauthenticationPurpose purpose) {
        if (purpose == null) throw invalidProof();
    }

    private static void requireWritableTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("La reautenticación requiere una transacción de escritura.");
        }
    }

    private static BadRequestException invalidProof() {
        return new BadRequestException("REAUTENTICACION_INVALIDA", "La confirmación no es válida o ya venció. Volvé a confirmar tu contraseña.");
    }

    private static UnauthorizedException invalidSession() {
        return new UnauthorizedException("La sesión ya no corresponde a un acceso activo.");
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("No se pudo completar la reautenticación.");
    }

    private record LockedSession(User user, String hash, Instant expiresAt) { }
}
