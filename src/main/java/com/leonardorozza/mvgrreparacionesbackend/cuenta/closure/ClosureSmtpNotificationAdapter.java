package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure;

import com.leonardorozza.mvgrreparacionesbackend.service.email.WorkshopClosureEmailTemplate;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/** Leased closure notices only. SMTP acceptance is not delivery; an ambiguous send is never retried. */
@Service
@ConditionalOnProperty(name = {"mail.enabled", "ordenfix.cuenta.cierre.notifications-enabled"}, havingValue = "true")
public final class ClosureSmtpNotificationAdapter implements ClosureNotificationPort {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ObjectProvider<JavaMailSender> sender;
    private final String from;
    private final String publicUrl;
    private final TransactionTemplate read;

    public ClosureSmtpNotificationAdapter(JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock,
            ObjectProvider<JavaMailSender> sender, @Value("${mail.from:}") String from,
            @Value("${app.public-url:http://localhost:5173}") String publicUrl) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.clock = Objects.requireNonNull(clock);
        this.sender = Objects.requireNonNull(sender);
        this.from = from;
        this.publicUrl = publicUrl;
        read = new TransactionTemplate(manager);
        read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        read.setReadOnly(true);
        read.setTimeout(5);
    }

    @Override
    public Result send(Event event) {
        if (TransactionSynchronizationManager.isActualTransactionActive() || !valid(event)) return Result.UNCERTAIN;
        JavaMailSender transport;
        MimeMessage message;
        try {
            Target target = resolve(event);
            if (target == null || !recipientValid(target.email())) return Result.UNCERTAIN;
            transport = sender.getIfAvailable();
            if (transport == null) return Result.RETRYABLE;
            String accountUrl = accountUrl(publicUrl);
            var content = event.kind() == Kind.CLOSED
                    ? WorkshopClosureEmailTemplate.closed(target.confirmedAt(), target.reversibleUntil(), accountUrl)
                    : WorkshopClosureEmailTemplate.restored(target.recordedAt(), accountUrl);
            message = transport.createMimeMessage();
            var helper = new MimeMessageHelper(message, "UTF-8");
            helper.setFrom(singleAddress(from, true));
            helper.setTo(singleAddress(target.email(), false));
            helper.setSubject(content.subject());
            var alternatives = new MimeMultipart("alternative");
            var plain = new MimeBodyPart();
            plain.setText(content.plainText(), "UTF-8");
            alternatives.addBodyPart(plain);
            var html = new MimeBodyPart();
            html.setText(content.html(), "UTF-8", "html");
            alternatives.addBodyPart(html);
            message.setContent(alternatives);
            // Preparing MIME/provider setup may consume the lease or overlap an owner change.
            if (!valid(event) || !target.equals(resolve(event)) || !valid(event)) return Result.UNCERTAIN;
        } catch (Exception beforeSend) {
            // Nothing has called SMTP send yet. Do not expose provider details, addresses or SQL.
            return Result.RETRYABLE;
        }
        try {
            // No transaction or row/advisory lock is held during external I/O.
            transport.send(message);
            return Result.ACCEPTED;
        } catch (Exception possiblyAccepted) {
            // Even MailSendException can follow provider acceptance. There is no atomic SMTP/SQL ACK.
            return Result.UNCERTAIN;
        }
    }

    private boolean valid(Event event) {
        return event != null && event.operationKey() != null && event.closureReference() != null
                && event.tallerId() > 0 && event.userId() > 0 && event.kind() != null
                && event.occurredAt() != null && event.leaseToken() != null && event.leaseUntil() != null
                && clock.instant().isBefore(event.leaseUntil());
    }

    private Target resolve(Event event) {
        return read.execute(status -> {
            var rows = jdbc.query("""
                    SELECT u.email,h.confirmado_en,h.reversible_hasta,o.registrada_en
                      FROM public.cuenta_cierre_efectos e
                      JOIN public.cuenta_cierre_operaciones o ON o.operacion_id=e.operacion_id
                        AND o.taller_id=e.taller_id AND o.user_id=e.usuario_id AND o.cierre_referencia=e.cierre_referencia
                      JOIN public.cuenta_cierres h ON h.referencia=e.cierre_referencia
                        AND h.taller_id=e.taller_id AND h.titular_id=e.usuario_id
                        AND h.politica=o.politica AND h.confirmado_en=o.confirmado_en
                        AND h.reversible_hasta=o.reversible_hasta AND h.eliminacion_prevista_en=o.eliminacion_prevista_en
                      JOIN public.users u ON u.id=e.usuario_id AND u.taller_id=e.taller_id
                        AND u.role='ADMIN' AND u.active AND u.email_verificado
                      JOIN public.talleres t ON t.id=e.taller_id AND t.activo
                     WHERE e.efecto_id=? AND e.cierre_referencia=? AND e.taller_id=? AND e.usuario_id=?
                       AND e.tipo=? AND e.created_at=? AND e.created_at>=o.registrada_en
                       AND e.estado='EN_CURSO' AND e.lease_token=? AND e.lease_until=? AND e.lease_until>?
                       AND ((e.tipo='AVISO_CIERRE' AND o.proposito='CERRAR' AND o.estado_resultante='RESTRINGIDO'
                             AND o.operacion_id=h.referencia AND o.cierre_version=h.generacion)
                         OR (e.tipo='AVISO_RESTAURACION' AND o.proposito='RESTAURAR' AND o.estado_resultante='ABIERTO'
                             AND h.estado='RESTAURADO' AND h.restaurado_en IS NOT NULL AND o.cierre_version=h.generacion+1))
                    """, (rs, row) -> new Target(rs.getString(1), rs.getTimestamp(2).toInstant(),
                            rs.getTimestamp(3).toInstant(), rs.getTimestamp(4).toInstant()),
                    event.operationKey(), event.closureReference(), event.tallerId(), event.userId(),
                    event.kind() == Kind.CLOSED ? "AVISO_CIERRE" : "AVISO_RESTAURACION",
                    Timestamp.from(event.occurredAt()), event.leaseToken(), Timestamp.from(event.leaseUntil()),
                    Timestamp.from(clock.instant()));
            return rows.size() == 1 ? rows.getFirst() : null;
        });
    }

    private static boolean recipientValid(String value) {
        try { singleAddress(value, false); return true; }
        catch (Exception invalid) { return false; }
    }

    private static InternetAddress singleAddress(String value, boolean allowName) throws Exception {
        if (value == null || value.isBlank() || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid mail address");
        var addresses = InternetAddress.parse(value, true);
        if (addresses.length != 1 || addresses[0].isGroup()
                || (!allowName && (addresses[0].getPersonal() != null || !value.equals(addresses[0].getAddress()))))
            throw new IllegalArgumentException("Invalid mail address");
        addresses[0].validate();
        return addresses[0];
    }

    private static String accountUrl(String base) {
        URI uri = URI.create(Objects.requireNonNull(base));
        String host = uri.getHost();
        boolean loopback = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
        if (host == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !(uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath()))
                || !("https".equalsIgnoreCase(uri.getScheme()) || (loopback && "http".equalsIgnoreCase(uri.getScheme())))
                || uri.getPort() == 0 || uri.getPort() > 65535)
            throw new IllegalArgumentException("Invalid account origin");
        return base.replaceFirst("/$", "") + "/cuenta";
    }

    private record Target(String email, Instant confirmedAt, Instant reversibleUntil, Instant recordedAt) {
        @Override public String toString() { return "ClosureMailTarget[redacted]"; }
    }
}
