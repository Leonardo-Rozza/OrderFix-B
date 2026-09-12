package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.exceptions.BadRequestException;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.AuthToken;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.User;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoAuthToken;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.AuthTokenRepository;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.UserRepository;
import com.leonardorozza.mvgrreparacionesbackend.service.email.EmailSender;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.util.HtmlUtils;

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
    private final AccountVerificationNotifier verificationNotifier;
    private final UserSecurityStateLock securityState;

    @Value("${app.public-url:http://localhost:5173}")
    private String publicUrl;

    @Value("${auth.token.reset-horas:1}")
    private int resetHoras;

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
                    enviarResetDespuesDelCommit(email, user.getUsername(), token);
                }, () -> log.info("Olvido de contraseña para email no registrado o inactivo (no se revela)"));
    }

    private void enviarResetDespuesDelCommit(String recipient, String displayName, String token) {
        // Unlike verification, this token belongs to the caller's transaction.
        // Without its commit callback, no delivery can be accredited.
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            log.warn("Recuperación de contraseña omitida: COMMIT_CALLBACK_UNAVAILABLE.");
            return;
        }
        String link = HtmlUtils.htmlEscape(publicUrl + "/reset-password?token=" + token);
        String html = """
                <p>Hola %s,</p>
                <p>Pediste restablecer tu contraseña. Hacé clic en el link (vence en %d hora/s):</p>
                <p><a href="%s">%s</a></p>
                <p>Si no fuiste vos, ignorá este email: tu contraseña sigue igual.</p>
                """.formatted(HtmlUtils.htmlEscape(displayName), resetHoras, link, link);
        // Capture immutable delivery strings, never a managed User or AuthToken.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try {
                    emailSender.enviar(recipient, "Restablecer tu contraseña de OrdenFix", html);
                } catch (RuntimeException deliveryFailure) {
                    // The token is already committed; preserve the generic account response.
                    log.warn("Recuperación de contraseña omitida: DELIVERY_FAILED.");
                }
            }
        });
    }

    @Transactional
    public void resetPassword(String token, String nuevaPassword) {
        AuthToken authToken = tokenUsable(token, TipoAuthToken.RESET_PASSWORD);
        User user = lockUsableTokenOwner(authToken);
        user.cambiarPassword(passwordEncoder.encode(nuevaPassword));
        userRepository.save(user);
        authToken.setUsadoEn(LocalDateTime.now());
        authTokenRepository.save(authToken);
        log.info("Contraseña restablecida para el usuario {}", user.getId());
    }

    // ---------- Verificación de email ----------

    /** Schedules verification after the caller commits; the notifier persists in its own transaction. */
    public void enviarVerificacion(User user) {
        Long userId = user == null ? null : user.getId();
        Long tallerId = user == null || user.getTaller() == null ? null : user.getTaller().getId();
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            verificationNotifier.notifyVerification(userId, tallerId);
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // An active transaction without callbacks cannot accredit a post-commit delivery.
            log.warn("Verificación de email omitida: COMMIT_CALLBACK_UNAVAILABLE.");
            return;
        }
        // Capture only durable IDs, never a managed User or its persistence context.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                verificationNotifier.notifyVerification(userId, tallerId);
            }
        });
    }

    @Transactional
    public void verificarEmail(String token) {
        AuthToken authToken = tokenUsable(token, TipoAuthToken.VERIFICACION_EMAIL);
        User user = lockUsableTokenOwner(authToken);
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

    private User lockUsableTokenOwner(AuthToken token) {
        User user = token.getUser();
        securityState.refreshAndLock(user);
        // A second request may have consumed this token while we were waiting for the user lock.
        try { securityState.refreshToken(token); }
        catch (EntityNotFoundException expired) {
            throw new BadRequestException("El link no es válido o ya venció. Pedí uno nuevo.");
        }
        if (!token.isUsable() || !Boolean.TRUE.equals(user.getActive())
                || user.getTaller() == null || !Boolean.TRUE.equals(user.getTaller().getActivo())) {
            throw new BadRequestException("El link no es válido o ya venció. Pedí uno nuevo.");
        }
        return user;
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
