package com.leonardorozza.mvgrreparacionesbackend;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.User;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.UserRepository;
import com.leonardorozza.mvgrreparacionesbackend.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class JwtSecurityIntegrationTests extends IntegrationTestBase {

    @Autowired
    private UserRepository userRepository;

    @Value("${security.jwt.secret}")
    private String secret;

    @Value("${security.jwt.issuer}")
    private String issuer;

    @Value("${security.jwt.audience}")
    private String audience;

    @Test
    void audienceIncorrectaNoAutenticaAunqueLaFirmaSeaValida() throws Exception {
        registrar("Taller Audience", "jwt-audience@test.com");
        User user = user("jwt-audience@test.com");
        String token = tokenFirmado(user, user.getTaller().getId(), "audience-ajena");

        authGet("/api/suscripcion", token).andExpect(status().isForbidden());
    }

    @Test
    void tenantClaimManipuladoNoAutenticaAlUsuario() throws Exception {
        registrar("Taller JWT A", "jwt-tenant-a@test.com");
        registrar("Taller JWT B", "jwt-tenant-b@test.com");
        User userA = user("jwt-tenant-a@test.com");
        Long tallerAjeno = user("jwt-tenant-b@test.com").getTaller().getId();
        String token = tokenFirmado(userA, tallerAjeno, audience);

        authGet("/api/suscripcion", token).andExpect(status().isForbidden());
    }

    @Test
    void tokenEmitidoAntesDelResetQuedaRevocado() throws Exception {
        String email = "jwt-reset@test.com";
        String tokenAnterior = registrar("Taller JWT Reset", email);

        mvc.perform(post("/api/auth/password/olvide")
                        .contentType(APPLICATION_JSON)
                        .content(json(Map.of("email", email))))
                .andExpect(status().isOk());
        String resetToken = emails.ultimoToken(email);
        mvc.perform(post("/api/auth/password/reset")
                        .contentType(APPLICATION_JSON)
                        .content(json(Map.of(
                                "token", resetToken,
                                "nuevaPassword", "nueva-segura-123"))))
                .andExpect(status().isOk());

        authGet("/api/suscripcion", tokenAnterior).andExpect(status().isForbidden());

        String tokenNuevo = login(email, "nueva-segura-123");
        authGet("/api/suscripcion", tokenNuevo).andExpect(status().isOk());
    }

    private User user(String email) {
        return userRepository.findByEmail(email).orElseThrow();
    }

    private String tokenFirmado(User user, Long tallerId, String tokenAudience) {
        Instant now = Instant.now();
        return JWT.create()
                .withIssuer(issuer)
                .withAudience(tokenAudience)
                .withSubject(user.getEmail())
                .withClaim("role", "ROLE_" + user.getRole().name())
                .withClaim("tallerId", tallerId)
                .withClaim("tokenVersion", user.getTokenVersion())
                .withIssuedAt(now)
                .withNotBefore(now)
                .withJWTId(UUID.randomUUID().toString())
                .withExpiresAt(now.plusSeconds(600))
                .sign(Algorithm.HMAC256(secret));
    }
}
