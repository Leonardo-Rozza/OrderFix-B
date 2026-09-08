package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.AuthToken;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoAuthToken;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.AuthTokenRepository;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** Persists only a verification token; its proxied return is the boundary for later delivery. */
@Service
public class AccountVerificationTokenIssuer {
    private final UserRepository users;
    private final AuthTokenRepository tokens;
    private final int verificationHours;
    private final SecureRandom random;
    private final Supplier<LocalDateTime> now;

    @Autowired
    public AccountVerificationTokenIssuer(UserRepository users, AuthTokenRepository tokens,
            @Value("${auth.token.verificacion-horas:48}") int verificationHours) {
        this(users, tokens, verificationHours, new SecureRandom(), LocalDateTime::now);
    }

    AccountVerificationTokenIssuer(UserRepository users, AuthTokenRepository tokens, int verificationHours,
                                  SecureRandom random, Supplier<LocalDateTime> now) {
        this.users = Objects.requireNonNull(users, "users");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.verificationHours = verificationHours;
        this.random = Objects.requireNonNull(random, "random");
        this.now = Objects.requireNonNull(now, "now");
    }

    /** The caller supplies committed IDs, never an entity from a registration transaction. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public Optional<Delivery> issue(Long userId, Long tallerId) {
        if (userId == null || userId <= 0 || tallerId == null || tallerId <= 0) return Optional.empty();
        var user = users.findSessionByIdAndTallerId(userId, tallerId).orElse(null);
        if (user == null || !Objects.equals(userId, user.getId()) || user.getTaller() == null
                || !Objects.equals(tallerId, user.getTaller().getId())
                || !Boolean.TRUE.equals(user.getActive()) || !Boolean.TRUE.equals(user.getTaller().getActivo())
                || Boolean.TRUE.equals(user.getEmailVerificado())
                || user.getEmail() == null || user.getEmail().isBlank()) return Optional.empty();

        // Preserve LocalDateTime.now() semantics; the application's UTC Clock serves other flows.
        LocalDateTime expiresAt = Objects.requireNonNull(now.get(), "verification time").plusHours(verificationHours);
        byte[] bytes = new byte[32];
        final String rawToken;
        try {
            random.nextBytes(bytes);
            rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
        String hash = sha256(rawToken);
        var delivery = new Delivery(user.getEmail(), user.getUsername(), rawToken, verificationHours);
        tokens.deleteByUserIdAndTipo(userId, TipoAuthToken.VERIFICACION_EMAIL);
        tokens.save(AuthToken.builder().user(user).tipo(TipoAuthToken.VERIFICACION_EMAIL)
                .tokenHash(hash).expiraEn(expiresAt).build());
        return Optional.of(delivery);
    }

    private static String sha256(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        byte[] hash = null;
        try {
            hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 no disponible", impossible);
        } finally {
            Arrays.fill(bytes, (byte) 0);
            if (hash != null) Arrays.fill(hash, (byte) 0);
        }
    }

    /** Short-lived delivery data: no managed entity, database identity or persisted hash. */
    public record Delivery(String recipient, String displayName, String rawToken, int validityHours) {
        public Delivery {
            Objects.requireNonNull(recipient, "recipient");
            Objects.requireNonNull(displayName, "displayName");
            Objects.requireNonNull(rawToken, "rawToken");
        }
        @Override public String toString() { return "Delivery[redacted]"; }
    }
}
