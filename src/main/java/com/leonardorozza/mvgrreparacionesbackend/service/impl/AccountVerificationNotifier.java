package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.service.email.EmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.util.Objects;
import java.util.Optional;

/** Best-effort delivery after the independent token transaction has returned through its proxy. */
@Service
public class AccountVerificationNotifier {
    private static final Logger LOG = LoggerFactory.getLogger(AccountVerificationNotifier.class);
    private final AccountVerificationTokenIssuer issuer;
    private final EmailSender emailSender;
    private final String publicUrl;

    public AccountVerificationNotifier(AccountVerificationTokenIssuer issuer, EmailSender emailSender,
            @Value("${app.public-url:http://localhost:5173}") String publicUrl) {
        this.issuer = Objects.requireNonNull(issuer, "issuer");
        this.emailSender = Objects.requireNonNull(emailSender, "emailSender");
        this.publicUrl = Objects.requireNonNull(publicUrl, "publicUrl");
    }

    /** No enclosing transaction and no retry: an uncertain token commit must never trigger a link. */
    public void notifyVerification(Long userId, Long tallerId) {
        final Optional<AccountVerificationTokenIssuer.Delivery> delivery;
        try {
            delivery = Objects.requireNonNull(issuer.issue(userId, tallerId), "verification result");
        } catch (RuntimeException issuanceFailure) {
            LOG.warn("Verificación de email omitida: TOKEN_ISSUANCE_FAILED.");
            return;
        }
        if (delivery.isEmpty()) return;
        try {
            var value = delivery.get();
            String link = HtmlUtils.htmlEscape(publicUrl + "/verificar-email?token=" + value.rawToken());
            String name = HtmlUtils.htmlEscape(value.displayName());
            emailSender.enviar(value.recipient(), "Confirmá tu email de OrdenFix",
                    """
                    <p>Hola %s, ¡bienvenido a OrdenFix!</p>
                    <p>Confirmá tu email haciendo clic en el link (vence en %d horas):</p>
                    <p><a href="%s">%s</a></p>
                    """.formatted(name, value.validityHours(), link, link));
        } catch (RuntimeException deliveryFailure) {
            LOG.warn("Verificación de email omitida: DELIVERY_FAILED.");
        }
    }
}
