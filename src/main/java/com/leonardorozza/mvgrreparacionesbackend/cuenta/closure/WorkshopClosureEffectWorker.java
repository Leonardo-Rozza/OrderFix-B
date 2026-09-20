package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Internal effects runner. Missing ports never claim work; notification scheduling is opt-in. */
@Service
public final class WorkshopClosureEffectWorker {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ObjectProvider<ClosureRenewalPort> renewal;
    private final ObjectProvider<ClosureNotificationPort> notification;
    private final TransactionTemplate transaction;
    public WorkshopClosureEffectWorker(JdbcTemplate jdbc,PlatformTransactionManager manager,Clock clock,
            ObjectProvider<ClosureRenewalPort> renewal,ObjectProvider<ClosureNotificationPort> notification) {
        this.jdbc=Objects.requireNonNull(jdbc);this.clock=Objects.requireNonNull(clock);
        this.renewal=Objects.requireNonNull(renewal);this.notification=Objects.requireNonNull(notification);
        transaction=new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(5);
    }
    /** Missing ports never claim work or pretend to succeed. */
    public boolean runNext() {
        requireNoCallerTransaction();
        return run(renewal.getIfAvailable(),notification.getIfAvailable());
    }
    /** Does not resolve or invoke renewal providers, even if one is installed. */
    public boolean runNextNotification() {
        requireNoCallerTransaction();
        return run(null,notification.getIfAvailable());
    }
    private static void requireNoCallerTransaction() {
        if(TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Closure worker requires no caller transaction");
    }
    private boolean run(ClosureRenewalPort renewalPort,ClosureNotificationPort notificationPort) {
        if(renewalPort==null && notificationPort==null) return false;
        Claim claim=transaction.execute(status->claim(renewalPort!=null,notificationPort!=null));
        if(claim==null) return false;
        Outcome outcome;
        try { outcome=claim.cancellation()?renew(renewalPort,claim):notify(notificationPort,claim); }
        catch(RuntimeException unavailable) { outcome=claim.cancellation()?Outcome.RETRY:Outcome.UNCERTAIN; }
        Outcome decided=outcome;
        try { transaction.executeWithoutResult(status->acknowledge(claim,decided)); }
        catch(LeaseExpired ignored) { /* The transaction rolled back; a later inspection can resolve the effect. */ }
        return true;
    }
    private Claim claim(boolean renewals,boolean notifications) {
        Instant now=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        // A lost email ACK is ambiguous. Bound recovery work and do not blindly resend.
        jdbc.update("""
                UPDATE public.cuenta_cierre_efectos SET estado='INCIERTO',lease_token=NULL,lease_until=NULL
                 WHERE efecto_id IN(SELECT efecto_id FROM public.cuenta_cierre_efectos
                   WHERE estado='EN_CURSO' AND lease_until<=?
                     AND ((? AND tipo IN ('AVISO_CIERRE','AVISO_RESTAURACION'))
                        OR (? AND tipo='CANCELAR_RENOVACION' AND intentos>=10))
                   ORDER BY lease_until,efecto_id LIMIT 20 FOR UPDATE SKIP LOCKED)
                """,Timestamp.from(now),notifications,renewals);
        var rows=jdbc.query("""
                SELECT efecto_id,cierre_referencia,taller_id,usuario_id,tipo,link_id,expected_external_reference,
                       expected_external_id,intentos,created_at
                  FROM public.cuenta_cierre_efectos
                 WHERE intentos<10 AND available_at<=?
                   AND ((estado='PENDIENTE') OR (tipo='CANCELAR_RENOVACION' AND estado='EN_CURSO' AND lease_until<=?))
                   AND ((? AND tipo='CANCELAR_RENOVACION' AND expected_external_id IS NOT NULL)
                     OR (? AND tipo IN ('AVISO_CIERRE','AVISO_RESTAURACION')))
                 ORDER BY available_at,created_at,efecto_id LIMIT 1 FOR UPDATE SKIP LOCKED
                """,(rs,row)->new Claim(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getLong(3),rs.getLong(4),
                    rs.getString(5),rs.getLong(6),rs.getString(7),rs.getString(8),rs.getInt(9)+1,rs.getTimestamp(10).toInstant(),UUID.randomUUID(),now.plusSeconds(120)),
                Timestamp.from(now),Timestamp.from(now),renewals,notifications);
        if(rows.isEmpty()) return null;
        Claim claim=rows.getFirst();
        jdbc.update("""
                UPDATE public.cuenta_cierre_efectos SET estado='EN_CURSO',intentos=?,lease_token=?,lease_until=? WHERE efecto_id=?
                """,claim.attempt(),claim.lease(),Timestamp.from(claim.deadline()),claim.id());
        return claim;
    }
    private Outcome renew(ClosureRenewalPort port,Claim claim) {
        var target=new ClosureRenewalPort.Target(claim.id(),claim.linkId(),claim.reference(),claim.externalId());
        var observed=port.inspect(target);
        if(!matches(claim,observed)) return Outcome.UNCERTAIN;
        return switch(observed.state()) {
            case CANCELED -> Outcome.CONFIRMED;
            case RETRYABLE -> Outcome.RETRY;
            case UNCERTAIN -> Outcome.UNCERTAIN;
            case ACTIVE -> {
                if(!clock.instant().isBefore(claim.deadline())) yield Outcome.RETRY;
                var canceled=port.cancel(target);
                if(!matches(claim,canceled)) yield Outcome.UNCERTAIN;
                yield switch(canceled.state()) {case CANCELED->Outcome.CONFIRMED;case RETRYABLE->Outcome.RETRY;default->Outcome.UNCERTAIN;};
            }
        };
    }
    private static boolean matches(Claim claim,ClosureRenewalPort.Observation observed) {
        return observed!=null && observed.state()!=null && Objects.equals(claim.externalId(),observed.externalId())
                && Objects.equals(claim.reference(),observed.externalReference());
    }
    private Outcome notify(ClosureNotificationPort port,Claim claim) {
        if(!clock.instant().isBefore(claim.deadline())) return Outcome.UNCERTAIN;
        var kind="AVISO_CIERRE".equals(claim.type())?ClosureNotificationPort.Kind.CLOSED:ClosureNotificationPort.Kind.RESTORED;
        var result=port.send(new ClosureNotificationPort.Event(claim.id(),claim.closure(),claim.tallerId(),claim.userId(),kind,claim.createdAt(),claim.lease(),claim.deadline()));
        if(result==null) return Outcome.UNCERTAIN;
        return switch(result) {case ACCEPTED->Outcome.CONFIRMED;case RETRYABLE->Outcome.RETRY;case UNCERTAIN->Outcome.UNCERTAIN;};
    }
    private void acknowledge(Claim claim,Outcome outcome) {
        // Acquire the row first: a lock wait must never turn an expired lease into a valid receipt.
        var locked=jdbc.queryForList("SELECT efecto_id FROM public.cuenta_cierre_efectos WHERE efecto_id=? AND estado='EN_CURSO' AND lease_token=? FOR UPDATE",
                UUID.class,claim.id(),claim.lease());
        if(locked.isEmpty() || !clock.instant().isBefore(claim.deadline())) return;
        Instant now=clock.instant();
        String state=switch(outcome) {case CONFIRMED->"CONFIRMADO";case UNCERTAIN->"INCIERTO";
            case RETRY->claim.attempt()>=10?"INCIERTO":"PENDIENTE";};
        int changed=jdbc.update("""
                UPDATE public.cuenta_cierre_efectos SET estado=?,confirmed_at=?,lease_token=NULL,lease_until=NULL,available_at=?
                 WHERE efecto_id=? AND estado='EN_CURSO' AND lease_token=? AND lease_until>?
                   AND expected_external_reference IS NOT DISTINCT FROM ? AND expected_external_id IS NOT DISTINCT FROM ?
                """,state,"CONFIRMADO".equals(state)?Timestamp.from(now):null,
                Timestamp.from(now.plusSeconds(Math.min(3600,30L << Math.min(claim.attempt(),7)))),
                claim.id(),claim.lease(),Timestamp.from(now),claim.reference(),claim.externalId());
        if(changed==1 && !clock.instant().isBefore(claim.deadline())) throw new LeaseExpired();
    }
    private static final class LeaseExpired extends RuntimeException { LeaseExpired(){super("Closure effect lease expired");} }
    private enum Outcome { CONFIRMED, RETRY, UNCERTAIN }
    private record Claim(UUID id,UUID closure,long tallerId,long userId,String type,long linkId,String reference,
            String externalId,int attempt,Instant createdAt,UUID lease,Instant deadline) {
        boolean cancellation() { return "CANCELAR_RENOVACION".equals(type); }
        @Override public String toString() { return "ClosureEffectClaim[redacted]"; }
    }
}
