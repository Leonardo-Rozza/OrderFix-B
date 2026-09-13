package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure;

import com.auth0.jwt.interfaces.DecodedJWT;
import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.reauth.ReauthenticationGrant;
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
import org.springframework.transaction.TransactionDefinition;
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
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Operation-bound internal grants. Never issues an access session or accepts a revoked JWT for replay. */
@Service
public class WorkshopClosureReauthenticationService {
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final int MAX_ACCESS_TOKEN_LENGTH = 8192;
    private final UserRepository users;
    private final UserSecurityStateLock security;
    private final PasswordEncoder passwords;
    private final JwtUtils jwt;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final WorkshopClosureGate gate;
    private final SecureRandom random;

    @Autowired
    public WorkshopClosureReauthenticationService(UserRepository users, UserSecurityStateLock security,
            PasswordEncoder passwords, JwtUtils jwt, JdbcTemplate jdbc, Clock clock, WorkshopClosureGate gate) {
        this(users, security, passwords, jwt, jdbc, clock, gate, new SecureRandom());
    }

    WorkshopClosureReauthenticationService(UserRepository users, UserSecurityStateLock security,
            PasswordEncoder passwords, JwtUtils jwt, JdbcTemplate jdbc, Clock clock,
            WorkshopClosureGate gate, SecureRandom random) {
        this.users = Objects.requireNonNull(users);
        this.security = Objects.requireNonNull(security);
        this.passwords = Objects.requireNonNull(passwords);
        this.jwt = Objects.requireNonNull(jwt);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.clock = Objects.requireNonNull(clock);
        this.gate = Objects.requireNonNull(gate);
        this.random = Objects.requireNonNull(random);
    }

    /** Cryptographic routing only: the coordinator must take its exclusive gate and then authorize again. */
    public long routeWorkshop(String accessToken) {
        return verified(accessToken).getClaim("tallerId").asLong();
    }

    /** No actor supplied by a caller is accepted as authority. This must share the command transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Authority authorize(String accessToken) {
        requireWritableReadCommitted();
        try {
            return lockSession(accessToken).authority();
        } catch (UnauthorizedException | AccessDeniedException | WorkshopClosureBlockedException | WorkshopClosureBusyException rejected) {
            throw rejected;
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    /** Checks only the deadline of a previously authenticated snapshot, including after our own epoch change. */
    public void requireLive(Authority authority) {
        if (authority == null || authority.expiresAt() == null || !clock.instant().isBefore(authority.expiresAt())) {
            throw invalidSession();
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public ReauthenticationGrant issue(String accessToken, String passwordActual, WorkshopClosurePurpose purpose,
            UUID operationId, UUID closureReference) {
        requireWritableReadCommitted();
        try {
            LockedSession session = lockSession(accessToken);
            Authority authority = session.authority();
            requirePurpose(authority, purpose, operationId, closureReference);
            boolean matches = false;
            if (passwordActual != null && !passwordActual.isBlank() && passwordActual.length() <= 100) {
                try { matches = passwords.matches(passwordActual, session.user().getPassword()); }
                catch (IllegalArgumentException malformed) { /* Fail closed without retaining password diagnostics. */ }
            }
            if (!matches) throw new BadRequestException("PASSWORD_ACTUAL_INVALIDA", "La contraseña actual no es correcta.");
            requireLive(authority);
            Instant createdAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
            Instant expiresAt = createdAt.plus(TTL);
            if (authority.expiresAt().isBefore(expiresAt)) expiresAt = authority.expiresAt();
            String token = newToken();
            String tokenHash = hash(token);
            // Used grants remain as evidence. A repeated random value is rejected by the primary key.
            jdbc.update("""
                    DELETE FROM public.cuenta_cierre_confirmaciones
                     WHERE user_id=? AND session_hash=? AND proposito=? AND usada_en IS NULL AND token_hash<>?
                    """, authority.userId(), authority.sessionHash(), purpose.name(), tokenHash);
            int inserted = jdbc.update("""
                    INSERT INTO public.cuenta_cierre_confirmaciones
                      (token_hash,user_id,taller_id,token_version,session_hash,proposito,operacion_id,
                       cierre_referencia,cierre_version,creada_en,expira_en)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?)
                    """, tokenHash, authority.userId(), authority.tallerId(), authority.tokenVersion(),
                    authority.sessionHash(), purpose.name(), operationId, closureReference, authority.closureVersion(),
                    createdAt.atOffset(ZoneOffset.UTC), expiresAt.atOffset(ZoneOffset.UTC));
            if (inserted != 1) throw unavailable();
            requireLive(authority);
            if (!clock.instant().isBefore(expiresAt)) throw invalidProof();
            return new ReauthenticationGrant(token, expiresAt);
        } catch (BadRequestException | UnauthorizedException | AccessDeniedException
                | WorkshopClosureBlockedException | WorkshopClosureBusyException rejected) {
            throw rejected;
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    /** The grant, transition and durable effects commit or roll back together in the caller transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Authority consume(String accessToken, String opaqueToken, WorkshopClosurePurpose purpose,
            UUID operationId, UUID closureReference) {
        requireWritableReadCommitted();
        try {
            requireCanonicalToken(opaqueToken);
            Authority authority = lockSession(accessToken).authority();
            requirePurpose(authority, purpose, operationId, closureReference);
            requireLive(authority);
            Instant consumedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
            List<Instant> expirations = jdbc.query("""
                    UPDATE public.cuenta_cierre_confirmaciones SET usada_en=?
                     WHERE token_hash=? AND user_id=? AND taller_id=? AND token_version=? AND session_hash=?
                       AND proposito=? AND operacion_id=? AND cierre_referencia=? AND cierre_version=?
                       AND usada_en IS NULL AND creada_en<=? AND expira_en>?
                    RETURNING expira_en
                    """, (row, index) -> row.getObject("expira_en", OffsetDateTime.class).toInstant(),
                    consumedAt.atOffset(ZoneOffset.UTC), hash(opaqueToken), authority.userId(), authority.tallerId(),
                    authority.tokenVersion(), authority.sessionHash(), purpose.name(), operationId, closureReference,
                    authority.closureVersion(), consumedAt.atOffset(ZoneOffset.UTC), consumedAt.atOffset(ZoneOffset.UTC));
            if (expirations.size() != 1) throw invalidProof();
            // SQL itself can wait beyond the proof/JWT/grace deadline.
            requireLive(authority);
            Instant proofExpiry = expirations.getFirst();
            if (!clock.instant().isBefore(proofExpiry)) throw invalidProof();
            // The coordinator's final temporal check must also cover work performed after consumption.
            Instant deadline = proofExpiry.isBefore(authority.expiresAt()) ? proofExpiry : authority.expiresAt();
            return new Authority(authority.userId(), authority.tallerId(), authority.tokenVersion(),
                    authority.closureVersion(), authority.closureReference(), deadline, authority.sessionHash(), authority.state());
        } catch (BadRequestException | UnauthorizedException | AccessDeniedException
                | WorkshopClosureBlockedException | WorkshopClosureBusyException rejected) {
            throw rejected;
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    public record Authority(long userId, long tallerId, long tokenVersion, long closureVersion,
            UUID closureReference, Instant expiresAt, String sessionHash, String state) {
        @Override public String toString() { return "Authority[redacted]"; }
    }

    private LockedSession lockSession(String accessToken) {
        DecodedJWT verified = verified(accessToken);
        long routedWorkshop = verified.getClaim("tallerId").asLong();
        // Admission precedes user locks. The coordinator already owns the exclusive gate for transitions.
        gate.requireAccountAccess(routedWorkshop);
        User user = users.findByEmail(verified.getSubject()).orElseThrow(WorkshopClosureReauthenticationService::invalidSession);
        security.refreshAndLock(user);
        if (user.getId() == null || user.getId() <= 0 || user.getTaller() == null
                || user.getTaller().getId() == null || user.getTaller().getId() != routedWorkshop
                || !Boolean.TRUE.equals(user.getActive()) || !Boolean.TRUE.equals(user.getTaller().getActivo())
                || user.getRole() == null || user.getTokenVersion() < 0 || user.getTaller().getCierreVersion() < 0
                || user.getEmail() == null || user.getEmail().isBlank()
                || user.getPassword() == null || user.getPassword().isBlank()) throw invalidSession();
        Instant now = clock.instant();
        if (!jwt.validateToken(verified, new AuthenticatedUserPrincipal(user, now))) throw invalidSession();
        if (user.getRole() != UserRole.ADMIN) throw new AccessDeniedException("La operación corresponde al titular del taller.");
        if (!Boolean.TRUE.equals(user.getEmailVerificado())) throw new AccessDeniedException("Se requiere un email verificado.");
        var mode = WorkshopClosureAccess.mode(user, now);
        if (mode == WorkshopClosureAccess.Mode.DENIED) throw invalidSession();
        Instant expiresAt = verified.getExpiresAtAsInstant();
        if (mode == WorkshopClosureAccess.Mode.RESTRICTED) {
            Instant graceEnd = user.getTaller().getCierreReversibleHasta().toInstant();
            if (graceEnd.isBefore(expiresAt)) expiresAt = graceEnd;
        }
        Authority authority = new Authority(user.getId(), routedWorkshop, user.getTokenVersion(),
                user.getTaller().getCierreVersion(), user.getTaller().getCierreReferencia(), expiresAt,
                hash(accessToken), user.getTaller().getCierreEstado());
        requireLive(authority);
        return new LockedSession(user, authority);
    }

    private DecodedJWT verified(String accessToken) {
        if (accessToken == null || accessToken.isBlank() || accessToken.length() > MAX_ACCESS_TOKEN_LENGTH
                || accessToken.chars().anyMatch(Character::isWhitespace)) throw invalidSession();
        try {
            DecodedJWT verified = jwt.verifyToken(accessToken);
            Long workshop = verified.getClaim("tallerId").asLong();
            Long version = verified.getClaim("tokenVersion").asLong();
            if (verified.getSubject() == null || verified.getSubject().isBlank() || workshop == null || workshop <= 0
                    || version == null || version < 0 || verified.getExpiresAtAsInstant() == null
                    || !clock.instant().isBefore(verified.getExpiresAtAsInstant())) throw invalidSession();
            return verified;
        } catch (RuntimeException rejected) {
            throw invalidSession();
        }
    }

    private static void requirePurpose(Authority authority, WorkshopClosurePurpose purpose,
            UUID operationId, UUID closureReference) {
        if (purpose == null || operationId == null || closureReference == null) throw invalidProof();
        switch (purpose) {
            case CERRAR -> {
                if (!"ABIERTO".equals(authority.state())) throw new WorkshopClosureBlockedException();
                if (!operationId.equals(closureReference) || authority.closureReference() != null) throw invalidProof();
            }
            case RESTAURAR -> {
                if (!"RESTRINGIDO".equals(authority.state()) || !closureReference.equals(authority.closureReference())) {
                    throw invalidProof();
                }
            }
        }
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        try { random.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
        finally { Arrays.fill(bytes, (byte) 0); }
    }

    private static void requireCanonicalToken(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw invalidProof();
        byte[] bytes = null;
        try {
            bytes = Base64.getUrlDecoder().decode(token);
            if (bytes.length != 32 || !Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(token)) throw invalidProof();
        } catch (IllegalArgumentException malformed) { throw invalidProof(); }
        finally { if (bytes != null) Arrays.fill(bytes, (byte) 0); }
    }

    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException unavailable) { throw unavailable(); }
    }

    private static void requireWritableReadCommitted() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !Integer.valueOf(TransactionDefinition.ISOLATION_READ_COMMITTED)
                    .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) {
            throw new IllegalStateException("La confirmación requiere una transacción de escritura READ_COMMITTED.");
        }
    }

    private static UnauthorizedException invalidSession() {
        return new UnauthorizedException("La sesión ya no corresponde a un acceso activo.");
    }
    private static BadRequestException invalidProof() {
        return new BadRequestException("REAUTENTICACION_INVALIDA", "La confirmación no es válida o ya venció. Volvé a confirmar tu contraseña.");
    }
    private static IllegalStateException unavailable() {
        return new IllegalStateException("No se pudo completar la reautenticación.");
    }
    private record LockedSession(User user, Authority authority) { }
}
