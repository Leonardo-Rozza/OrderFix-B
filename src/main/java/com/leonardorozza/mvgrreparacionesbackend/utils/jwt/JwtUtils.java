package com.leonardorozza.mvgrreparacionesbackend.utils.jwt;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Component
public class JwtUtils {

    private static final int MIN_SECRET_BYTES = 32;
    private static final long MAX_CLOCK_SKEW_SECONDS = 300;
    private static final String TALLER_ID_CLAIM = "tallerId";
    private static final String TOKEN_VERSION_CLAIM = "tokenVersion";

    private final long jwtExpirationMs;
    private final String issuer;
    private final String audience;
    private final Algorithm algorithm;
    private final JWTVerifier verifier;

    public JwtUtils(
            @Value("${security.jwt.secret}") String jwtSecret,
            @Value("${security.jwt.expiration}") long jwtExpirationMs,
            @Value("${security.jwt.issuer}") String issuer,
            @Value("${security.jwt.audience:ordenfix-api}") String audience,
            @Value("${security.jwt.clock-skew-seconds:30}") long clockSkewSeconds) {
        validateConfiguration(jwtSecret, jwtExpirationMs, issuer, audience, clockSkewSeconds);
        this.jwtExpirationMs = jwtExpirationMs;
        this.issuer = issuer.trim();
        this.audience = audience.trim();
        this.algorithm = Algorithm.HMAC256(jwtSecret);
        this.verifier = JWT.require(algorithm)
                .withIssuer(this.issuer)
                .withAudience(this.audience)
                .withClaimPresence("sub")
                .withClaimPresence("exp")
                .withClaimPresence("iat")
                .withClaimPresence("nbf")
                .withClaimPresence("jti")
                .withClaimPresence("role")
                .withClaimPresence(TALLER_ID_CLAIM)
                .withClaimPresence(TOKEN_VERSION_CLAIM)
                .acceptLeeway(clockSkewSeconds)
                .build();
    }

    /**
     * Genera un access token con el contrato completo. Cuando el principal proviene de la base,
     * verifica que el tenant recibido coincida y toma de allí la versión de revocación.
     */
    public String generateToken(UserDetails userDetails, Long tallerId) {
        long tokenVersion = 0L;
        if (userDetails instanceof AuthenticatedUserPrincipal principal) {
            if (!Objects.equals(principal.getTallerId(), tallerId)) {
                throw new IllegalStateException("El tenant autenticado no coincide con el solicitado");
            }
            tokenVersion = principal.getTokenVersion();
        }
        if (tallerId == null) {
            throw new IllegalStateException("El usuario autenticado no tiene tenant");
        }

        String role = userDetails.getAuthorities().stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("El usuario autenticado no tiene rol"))
                .getAuthority();
        Instant now = Instant.now();

        return JWT.create()
                .withIssuer(issuer)
                .withAudience(audience)
                .withSubject(userDetails.getUsername())
                .withClaim("role", role)
                .withClaim(TALLER_ID_CLAIM, tallerId)
                .withClaim(TOKEN_VERSION_CLAIM, tokenVersion)
                .withIssuedAt(now)
                .withNotBefore(now)
                .withJWTId(UUID.randomUUID().toString())
                .withExpiresAt(now.plusMillis(jwtExpirationMs))
                .sign(algorithm);
    }

    public DecodedJWT verifyToken(String token) {
        return verifier.verify(token);
    }

    public String extractUsername(String token) {
        return verifyToken(token).getSubject();
    }

    public Long extractTallerId(String token) {
        return verifyToken(token).getClaim(TALLER_ID_CLAIM).asLong();
    }

    /** Valida criptografía y, para el principal productivo, tenant y versión contra la base. */
    public boolean validateToken(String token, UserDetails userDetails) {
        try {
            DecodedJWT decoded = verifyToken(token);
            if (userDetails instanceof AuthenticatedUserPrincipal principal) {
                return validateToken(decoded, principal);
            }
            return decoded.getSubject().equals(userDetails.getUsername()) && userDetails.isEnabled();
        } catch (JWTVerificationException | IllegalArgumentException ex) {
            return false;
        }
    }

    public boolean validateToken(DecodedJWT decoded, AuthenticatedUserPrincipal principal) {
        Long claimedTallerId = decoded.getClaim(TALLER_ID_CLAIM).asLong();
        Long claimedTokenVersion = decoded.getClaim(TOKEN_VERSION_CLAIM).asLong();
        return principal.isEnabled()
                && decoded.getSubject().equals(principal.getUsername())
                && Objects.equals(claimedTallerId, principal.getTallerId())
                && claimedTokenVersion != null
                && claimedTokenVersion == principal.getTokenVersion();
    }

    private static void validateConfiguration(
            String jwtSecret,
            long jwtExpirationMs,
            String issuer,
            String audience,
            long clockSkewSeconds) {
        if (jwtSecret == null
                || jwtSecret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("security.jwt.secret debe tener al menos 32 bytes");
        }
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalStateException("security.jwt.issuer es obligatorio");
        }
        if (audience == null || audience.isBlank()) {
            throw new IllegalStateException("security.jwt.audience es obligatorio");
        }
        if (jwtExpirationMs <= 0) {
            throw new IllegalStateException("security.jwt.expiration debe ser positivo");
        }
        if (clockSkewSeconds < 0 || clockSkewSeconds > MAX_CLOCK_SKEW_SECONDS) {
            throw new IllegalStateException(
                    "security.jwt.clock-skew-seconds debe estar entre 0 y 300");
        }
    }
}
