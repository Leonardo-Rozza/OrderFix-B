package com.leonardorozza.mvgrreparacionesbackend;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.leonardorozza.mvgrreparacionesbackend.utils.jwt.JwtUtils;
import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Taller;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
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

    @ParameterizedTest @EnumSource(UserRole.class)
    void elPrincipalActualConservaExactamenteLosClaimsYElRolWireSinAgregarAudienciaLegal(UserRole role) {
        var persisted = persistedUser(); persisted.setRole(role); persisted.setTokenVersion(37L);
        var principal = new AuthenticatedUserPrincipal(persisted);
        DecodedJWT token = jwtUtils.verifyToken(jwtUtils.generateToken(principal, 42L));
        assertThat(token.getClaims()).containsOnlyKeys("iss", "aud", "sub", "role", "tallerId", "tokenVersion",
                "iat", "nbf", "jti", "exp");
        assertThat(token.getAlgorithm()).isEqualTo("HS256");
        assertThat(token.getAudience()).containsExactly(AUDIENCE);
        assertThat(token.getSubject()).isEqualTo(persisted.getEmail());
        assertThat(token.getClaim("role").asString()).isEqualTo("ROLE_" + role.name());
        assertThat(token.getClaim("tokenVersion").asLong()).isEqualTo(37L);
        assertThat(token.getClaim("tallerId").asLong()).isEqualTo(42L);
        assertThat(jwtUtils.validateToken(token, principal)).isTrue();
    }

    @ParameterizedTest @ValueSource(strings = {"usuario-inactivo", "usuario-null", "taller-inactivo", "taller-null-activo",
            "sin-taller", "sin-id-taller", "id-taller-cero", "id-taller-negativo"})
    void tokenExistenteNoAutenticaCuandoLaCuentaActualNoEstaHabilitada(String estado) {
        var persisted = persistedUser();
        String token = jwtUtils.generateToken(new AuthenticatedUserPrincipal(persisted), 42L);
        switch (estado) {
            case "usuario-inactivo" -> persisted.setActive(false);
            case "usuario-null" -> persisted.setActive(null);
            case "taller-inactivo" -> persisted.getTaller().setActivo(false);
            case "taller-null-activo" -> persisted.getTaller().setActivo(null);
            case "sin-taller" -> persisted.setTaller(null);
            case "sin-id-taller" -> persisted.getTaller().setId(null);
            case "id-taller-cero" -> persisted.getTaller().setId(0L);
            case "id-taller-negativo" -> persisted.getTaller().setId(-1L);
            default -> throw new AssertionError(estado);
        }
        var current = new AuthenticatedUserPrincipal(persisted);
        assertThat(current.isEnabled()).isFalse();
        assertThat(jwtUtils.validateToken(token, current)).isFalse();
        assertThat(jwtUtils.validateToken(jwtUtils.verifyToken(token), current)).isFalse();
    }

    @ParameterizedTest @ValueSource(strings = {"token-version", "taller", "email"})
    void unaIdentidadActualDiferenteInvalidaElTokenAunqueSuFirmaSeaCorrecta(String cambio) {
        var persisted = persistedUser();
        String token = jwtUtils.generateToken(new AuthenticatedUserPrincipal(persisted), 42L);
        switch (cambio) {
            case "token-version" -> persisted.setTokenVersion(persisted.getTokenVersion() + 1);
            case "taller" -> persisted.getTaller().setId(43L);
            case "email" -> persisted.setEmail("changed@example.invalid");
            default -> throw new AssertionError(cambio);
        }
        var current = new AuthenticatedUserPrincipal(persisted);
        assertThat(current.isEnabled()).isTrue();
        assertThat(jwtUtils.validateToken(token, current)).isFalse();
        assertThat(jwtUtils.validateToken(jwtUtils.verifyToken(token), current)).isFalse();
    }

    @Test void laPasswordActualRevocaElTokenPrevioYLaSiguienteEmisionUsaNuevaVersionYJti() {
        var persisted = persistedUser();
        String oldToken = jwtUtils.generateToken(new AuthenticatedUserPrincipal(persisted), 42L);
        persisted.cambiarPassword("synthetic-updated-hash");
        var current = new AuthenticatedUserPrincipal(persisted);
        String newToken = jwtUtils.generateToken(current, 42L);
        assertThat(jwtUtils.validateToken(oldToken, current)).isFalse();
        assertThat(jwtUtils.validateToken(newToken, current)).isTrue();
        assertThat(jwtUtils.verifyToken(newToken).getClaim("tokenVersion").asLong()).isEqualTo(6L);
        assertThat(jwtUtils.verifyToken(newToken).getId()).isNotEqualTo(jwtUtils.verifyToken(oldToken).getId());
    }

    @Test void laEmisionNoPuedeCambiarElTenantDelPrincipalTipado() {
        var principal = new AuthenticatedUserPrincipal(persistedUser());
        assertThatThrownBy(() -> jwtUtils.generateToken(principal, 43L)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> jwtUtils.generateToken(principal, null)).isInstanceOf(IllegalStateException.class);
    }

    @Test void elEmailSinVerificarSigueSiendoUnaCondicionSuave() {
        var persisted = persistedUser(); persisted.setEmailVerificado(false);
        var principal = new AuthenticatedUserPrincipal(persisted);
        String token = jwtUtils.generateToken(principal, 42L);
        assertThat(principal.isEnabled()).isTrue(); assertThat(principal.isEmailVerificado()).isFalse();
        assertThat(jwtUtils.validateToken(token, principal)).isTrue();
    }

    private static com.leonardorozza.mvgrreparacionesbackend.persistence.entity.User persistedUser() {
        return com.leonardorozza.mvgrreparacionesbackend.persistence.entity.User.builder().id(17L)
                .email("current@example.invalid").username("Actor").password("synthetic-current-hash")
                .active(true).role(UserRole.ADMIN).tokenVersion(5L).emailVerificado(true)
                .taller(Taller.builder().id(42L).nombre("Taller").activo(true).build()).build();
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
