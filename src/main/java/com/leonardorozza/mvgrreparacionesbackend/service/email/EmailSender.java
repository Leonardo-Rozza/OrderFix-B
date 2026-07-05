package com.leonardorozza.mvgrreparacionesbackend.service.email;

/**
 * Envío de emails transaccionales (reset de contraseña, verificación de email).
 * La implementación real usa SMTP (Resend); en tests se reemplaza por una que graba.
 */
public interface EmailSender {

    void enviar(String para, String asunto, String cuerpoHtml);
}
