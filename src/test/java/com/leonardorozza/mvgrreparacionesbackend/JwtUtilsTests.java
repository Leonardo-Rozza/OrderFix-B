package com.leonardorozza.mvgrreparacionesbackend;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.leonardorozza.mvgrreparacionesbackend.utils.jwt.JwtUtils;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtUtilsTests {

    private static final String SECRET = "test-secret-key-para-jwt-hmac256-0123456789";
    private static final String ISSUER = "ordenfix-test";
    private static final String AUDIENCE = "ordenfix-api-test";

    private final JwtUtils jwtUtils = new JwtUtils(SECRET, 3_600_000, ISSUER, AUDIENCE, 30);
    private final UserDetails user = User.withUsername("jwt@test.com")
            .password("irrelevante")
            .authorities("ROLE_ADMIN")
            .build();

    @Test
    void emiteContratoCompletoDeClaims() {
        DecodedJWT token = jwtUtils.verifyToken(jwtUtils.generateToken(user, 42L));

        assertThat(token.getIssuer()).isEqualTo(ISSUER);
        assertThat(token.getAudience()).contains(AUDIENCE);
        assertThat(token.getSubject()).isEqualTo("jwt@test.com");
        assertThat(token.getIssuedAt()).isNotNull();
        assertThat(token.getNotBefore()).isNotNull();
        assertThat(token.getExpiresAt()).isAfter(token.getIssuedAt());
        assertThat(token.getId()).isNotBlank();
        assertThat(token.getClaim("tallerId").asLong()).isEqualTo(42L);
        assertThat(token.getClaim("tokenVersion").asLong()).isZero();
    }

    @Test
    void rechazaAudienceIncorrectaAunqueLaFirmaSeaValida() {
        String token = tokenFirmadoPara("otra-api");

        assertThatThrownBy(() -> jwtUtils.verifyToken(token))
                .isInstanceOf(JWTVerificationException.class);
        assertThat(jwtUtils.validateToken(token, user)).isFalse();
    }

    @Test
    void rechazaSecretoMenorA256BitsAlConstruirElBean() {
        assertThatThrownBy(() -> new JwtUtils("demasiado-corto", 3_600_000, ISSUER, AUDIENCE, 30))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    private String tokenFirmadoPara(String audience) {
        Instant now = Instant.now();
        return JWT.create()
                .withIssuer(ISSUER)
                .withAudience(audience)
                .withSubject(user.getUsername())
                .withClaim("role", "ROLE_ADMIN")
                .withClaim("tallerId", 42L)
                .withClaim("tokenVersion", 0L)
                .withIssuedAt(now)
                .withNotBefore(now)
                .withJWTId(UUID.randomUUID().toString())
                .withExpiresAt(now.plusSeconds(600))
                .sign(Algorithm.HMAC256(SECRET));
    }
}
