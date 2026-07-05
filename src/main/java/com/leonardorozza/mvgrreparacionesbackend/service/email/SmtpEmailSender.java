package com.leonardorozza.mvgrreparacionesbackend.service.email;

import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * Envío por SMTP (Resend). Con {@code mail.enabled=false} no envía nada: loguea el
 * contenido (útil en dev para copiar el link del email). Un fallo de envío se loguea
 * pero NUNCA rompe el flujo que lo disparó (registro, olvido de contraseña).
 */
@Service
@Slf4j
public class SmtpEmailSender implements EmailSender {

    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final boolean enabled;
    private final String from;

    public SmtpEmailSender(ObjectProvider<JavaMailSender> mailSenderProvider,
                           @Value("${mail.enabled:false}") boolean enabled,
                           @Value("${mail.from:}") String from) {
        this.mailSenderProvider = mailSenderProvider;
        this.enabled = enabled;
        this.from = from;
    }

    @Override
    public void enviar(String para, String asunto, String cuerpoHtml) {
        if (!enabled) {
            log.info("[mail desactivado] Para: {} | Asunto: {} | Cuerpo: {}", para, asunto, cuerpoHtml);
            return;
        }
        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            log.warn("mail.enabled=true pero no hay SMTP configurado (spring.mail.*). Email a {} no enviado.", para);
            return;
        }
        try {
            MimeMessage mensaje = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mensaje, "UTF-8");
            helper.setFrom(from);
            helper.setTo(para);
            helper.setSubject(asunto);
            helper.setText(cuerpoHtml, true);
            mailSender.send(mensaje);
            log.info("Email enviado a {}: {}", para, asunto);
        } catch (Exception e) {
            log.error("No se pudo enviar el email a {} ({}): {}", para, asunto, e.getMessage());
        }
    }
}
