package com.leonardorozza.mvgrreparacionesbackend.support;

import com.leonardorozza.mvgrreparacionesbackend.service.email.EmailSender;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Map;
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

    @Override
    public void enviar(String para, String asunto, String cuerpoHtml) {
        ultimoCuerpoPorEmail.put(para, cuerpoHtml);
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
