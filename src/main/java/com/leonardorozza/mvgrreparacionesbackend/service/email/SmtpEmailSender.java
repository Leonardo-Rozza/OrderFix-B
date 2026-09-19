package com.leonardorozza.mvgrreparacionesbackend.service.email;

import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * Envío por SMTP (Resend). Los logs nunca incluyen destinatario, asunto, cuerpos de texto o HTML,
 * tokens ni mensajes del proveedor. Un fallo de envío se registra sin datos sensibles,
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
        enviarMensaje(para, asunto, null, cuerpoHtml, false);
    }

    @Override
    public void enviar(String para, String asunto, String cuerpoTexto, String cuerpoHtml) {
        enviarMensaje(para, asunto, cuerpoTexto, cuerpoHtml, true);
    }

    private void enviarMensaje(String para, String asunto, String cuerpoTexto, String cuerpoHtml,
                               boolean alternatives) {
        if (!enabled) {
            log.info("Email transaccional omitido: mail.enabled=false.");
            return;
        }
        try {
            JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
            if (mailSender == null) {
                log.warn("Email transaccional omitido: SMTP no configurado.");
                return;
            }
            MimeMessage mensaje = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mensaje, "UTF-8");
            helper.setFrom(from);
            helper.setTo(para);
            helper.setSubject(asunto);
            if (alternatives) {
                // Exactly two alternatives, without attachment or related MIME containers.
                MimeMultipart content = new MimeMultipart("alternative");
                MimeBodyPart plain = new MimeBodyPart();
                plain.setText(cuerpoTexto, "UTF-8", "plain");
                content.addBodyPart(plain);
                MimeBodyPart html = new MimeBodyPart();
                html.setText(cuerpoHtml, "UTF-8", "html");
                content.addBodyPart(html);
                mensaje.setContent(content);
            } else {
                helper.setText(cuerpoHtml, true);
            }
            mailSender.send(mensaje);
            log.info("Email transaccional enviado.");
        } catch (Exception e) {
            log.error("No se pudo enviar el email transaccional ({}).", e.getClass().getSimpleName());
        }
    }
}
