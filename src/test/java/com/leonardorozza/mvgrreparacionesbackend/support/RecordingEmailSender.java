package com.leonardorozza.mvgrreparacionesbackend.support;

import com.leonardorozza.mvgrreparacionesbackend.service.email.EmailSender;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reemplaza el envío real de emails en los tests: guarda el último email por
 * destinatario para que los tests puedan extraer el token del link.
 */
@Component
@Primary
public class RecordingEmailSender implements EmailSender {

    private static final Pattern TOKEN = Pattern.compile("token=([A-Za-z0-9_-]+)");

    private final Map<String, String> ultimoCuerpoPorEmail = new ConcurrentHashMap<>();

    private volatile Path verificationMailbox;
    private volatile Path passwordResetMailbox;

    /** Opt-in browser harness capture. No production endpoint or external email delivery. */
    public void captureVerificationTokens(Path directory) {
        if (directory != null && !Files.isDirectory(directory)) throw new IllegalArgumentException("Mailbox directory does not exist");
        verificationMailbox = directory;
    }

    /** Captures the actual rendered reset email only in the opt-in local browser harness. */
    public void capturePasswordResetEmails(Path directory) {
        if (directory != null && !Files.isDirectory(directory)) throw new IllegalArgumentException("Mailbox directory does not exist");
        passwordResetMailbox = directory;
    }

    @Override
    public void enviar(String para, String asunto, String cuerpoHtml) {
        ultimoCuerpoPorEmail.put(para, cuerpoHtml);
        Path resetDirectory = passwordResetMailbox;
        if (resetDirectory != null && asunto.equals("Restablecer tu contraseña de OrdenFix")) {
            String filename = Base64.getUrlEncoder().withoutPadding().encodeToString(para.getBytes(StandardCharsets.UTF_8)) + ".html";
            try { Files.writeString(resetDirectory.resolve(filename), cuerpoHtml, StandardCharsets.UTF_8); }
            catch (IOException failure) { throw new IllegalStateException("Could not capture synthetic password reset email", failure); }
        }
        Path directory = verificationMailbox;
        if (directory != null && asunto.equals("Confirmá tu email de OrdenFix")) {
            Matcher match = TOKEN.matcher(cuerpoHtml);
            if (match.find()) {
                String filename = Base64.getUrlEncoder().withoutPadding().encodeToString(para.getBytes(StandardCharsets.UTF_8)) + ".txt";
                try { Files.writeString(directory.resolve(filename), match.group(1), StandardCharsets.UTF_8); }
                catch (IOException failure) { throw new IllegalStateException("Could not capture synthetic verification email", failure); }
            }
        }
    }

    public String ultimoCuerpo(String email) {
        return ultimoCuerpoPorEmail.get(email);
    }

    /** Extrae el token del link del último email enviado a ese destinatario (null si no hay). */
    public String ultimoToken(String email) {
        String cuerpo = ultimoCuerpoPorEmail.get(email);
        if (cuerpo == null) {
            return null;
        }
        Matcher m = TOKEN.matcher(cuerpo);
        return m.find() ? m.group(1) : null;
    }
}
