package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.exceptions.BadRequestException;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.AuthToken;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.User;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoAuthToken;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.AuthTokenRepository;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.UserRepository;
import com.leonardorozza.mvgrreparacionesbackend.service.email.EmailSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Flujos de cuenta por email: olvido/reset de contraseña y verificación de email.
 * Los tokens viajan en un link al frontend, son de un solo uso y se guardan hasheados.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CuentaService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final AuthTokenRepository authTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailSender emailSender;

    @Value("${app.public-url:http://localhost:5173}")
    private String publicUrl;

    @Value("${auth.token.reset-horas:1}")
    private int resetHoras;

    @Value("${auth.token.verificacion-horas:48}")
    private int verificacionHoras;

    // ---------- Olvido / reset de contraseña ----------

    /**
     * Siempre termina "bien" hacia afuera: no revela si el email existe
     * (evita enumerar cuentas). Si existe y está activo, manda el link.
     */
    @Transactional
    public void olvidePassword(String email) {
        userRepository.findByEmail(email)
                .filter(u -> Boolean.TRUE.equals(u.getActive()))
                .ifPresentOrElse(user -> {
                    String token = emitirToken(user, TipoAuthToken.RESET_PASSWORD, resetHoras);
                    String link = publicUrl + "/reset-password?token=" + token;
                    emailSender.enviar(email, "Restablecer tu contraseña de OrdenFix",
                            """
                            <p>Hola %s,</p>
                            <p>Pediste restablecer tu contraseña. Hacé clic en el link (vence en %d hora/s):</p>
                            <p><a href="%s">%s</a></p>
                            <p>Si no fuiste vos, ignorá este email: tu contraseña sigue igual.</p>
                            """.formatted(user.getUsername(), resetHoras, link, link));
                }, () -> log.info("Olvido de contraseña para email no registrado o inactivo (no se revela)"));
    }

    @Transactional
    public void resetPassword(String token, String nuevaPassword) {
        AuthToken authToken = tokenUsable(token, TipoAuthToken.RESET_PASSWORD);
        User user = authToken.getUser();
        user.setPassword(passwordEncoder.encode(nuevaPassword));
        userRepository.save(user);
        authToken.setUsadoEn(LocalDateTime.now());
        authTokenRepository.save(authToken);
        log.info("Contraseña restablecida para el usuario {}", user.getId());
    }

    // ---------- Verificación de email ----------

    /** Manda el email de bienvenida + verificación (lo llama el registro). */
    @Transactional
    public void enviarVerificacion(User user) {
        String token = emitirToken(user, TipoAuthToken.VERIFICACION_EMAIL, verificacionHoras);
        String link = publicUrl + "/verificar-email?token=" + token;
        emailSender.enviar(user.getEmail(), "Confirmá tu email de OrdenFix",
                """
                <p>Hola %s, ¡bienvenido a OrdenFix!</p>
                <p>Confirmá tu email haciendo clic en el link (vence en %d horas):</p>
                <p><a href="%s">%s</a></p>
                """.formatted(user.getUsername(), verificacionHoras, link, link));
    }

    @Transactional
    public void verificarEmail(String token) {
        AuthToken authToken = tokenUsable(token, TipoAuthToken.VERIFICACION_EMAIL);
        User user = authToken.getUser();
        user.setEmailVerificado(true);
        userRepository.save(user);
        authToken.setUsadoEn(LocalDateTime.now());
        authTokenRepository.save(authToken);
        log.info("Email verificado para el usuario {}", user.getId());
    }

    /** Reenvía el email de verificación. Como el olvido: no revela si el email existe. */
    @Transactional
    public void reenviarVerificacion(String email) {
        userRepository.findByEmail(email)
                .filter(u -> Boolean.TRUE.equals(u.getActive()))
                .filter(u -> !Boolean.TRUE.equals(u.getEmailVerificado()))
                .ifPresent(this::enviarVerificacion);
    }

    // ---------- Tokens ----------

    /** Genera un token nuevo (invalidando los previos del mismo tipo) y devuelve el valor crudo para el link. */
    private String emitirToken(User user, TipoAuthToken tipo, int horasValidez) {
        authTokenRepository.deleteByUserIdAndTipo(user.getId(), tipo);
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        authTokenRepository.save(AuthToken.builder()
                .user(user)
                .tipo(tipo)
                .tokenHash(sha256(token))
                .expiraEn(LocalDateTime.now().plusHours(horasValidez))
                .build());
        return token;
    }

    private AuthToken tokenUsable(String token, TipoAuthToken tipo) {
        return authTokenRepository.findByTokenHashAndTipo(sha256(token), tipo)
                .filter(AuthToken::isUsable)
                .orElseThrow(() -> new BadRequestException("El link no es válido o ya venció. Pedí uno nuevo."));
    }

    private static String sha256(String valor) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(valor.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }
}
