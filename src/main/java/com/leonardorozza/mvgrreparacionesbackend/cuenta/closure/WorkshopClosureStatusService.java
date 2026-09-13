package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure;

import com.leonardorozza.mvgrreparacionesbackend.exceptions.UnauthorizedException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/** Current account state and immutable receipt only. No DML, provider call, cleanup or deletion promise. */
@Service
public final class WorkshopClosureStatusService {
    private final JdbcTemplate jdbc;
    private final WorkshopClosureGate gate;
    private final WorkshopClosureReauthenticationService authorization;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public WorkshopClosureStatusService(JdbcTemplate jdbc, WorkshopClosureGate gate,
            WorkshopClosureReauthenticationService authorization, PlatformTransactionManager manager, Clock clock) {
        this.jdbc=Objects.requireNonNull(jdbc); this.gate=Objects.requireNonNull(gate);
        this.authorization=Objects.requireNonNull(authorization); this.clock=Objects.requireNonNull(clock);
        transaction=new TransactionTemplate(Objects.requireNonNull(manager));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        // Writable for the existing identity/anchor locks; this service never changes a row.
        transaction.setReadOnly(false); transaction.setTimeout(10);
    }

    public Status read(String accessToken) {
        try {
            long routed=authorization.routeWorkshop(accessToken);
            return Objects.requireNonNull(transaction.execute(ignored -> {
                jdbc.execute("SET LOCAL lock_timeout='2s'"); jdbc.execute("SET LOCAL statement_timeout='5s'");
                gate.requireAccountAccess(routed);
                var actor=authorization.authorize(accessToken);
                if (actor.tallerId()!=routed) throw unavailable();
                var anchors=jdbc.query("""
                        SELECT t.nombre,t.cierre_estado,t.cierre_version,t.cierre_referencia,
                               t.cierre_confirmado_en,t.cierre_reversible_hasta,t.cierre_eliminacion_prevista_en,
                               EXISTS(SELECT 1 FROM public.cuenta_cierres h WHERE h.referencia=t.cierre_referencia
                                 AND h.taller_id=t.id AND h.titular_id=? AND h.generacion=t.cierre_version
                                 AND h.estado='RESTRINGIDO' AND h.politica='ordenfix-cierre/1' AND h.restaurado_en IS NULL
                                 AND h.confirmado_en=t.cierre_confirmado_en AND h.reversible_hasta=t.cierre_reversible_hasta
                                 AND h.eliminacion_prevista_en=t.cierre_eliminacion_prevista_en) AS history_matches
                        FROM public.talleres t WHERE t.id=? AND t.activo FOR SHARE OF t
                        """, (row,index) -> new Anchor(row.getString(1),row.getString(2),row.getLong(3),row.getObject(4,UUID.class),
                        instant(row.getObject(5,OffsetDateTime.class)),instant(row.getObject(6,OffsetDateTime.class)),
                        instant(row.getObject(7,OffsetDateTime.class)),row.getBoolean(8)),actor.userId(),routed);
                if (anchors.size()!=1) throw unavailable();
                Anchor anchor=anchors.getFirst();
                Instant observed=clock.instant().truncatedTo(ChronoUnit.MICROS);
                validateAnchor(anchor,actor,observed);
                var operations=jdbc.query("""
                        SELECT o.operacion_id,o.cierre_referencia,o.proposito,o.cierre_version,o.estado_resultante,
                               o.confirmado_en,o.reversible_hasta,o.eliminacion_prevista_en,o.registrada_en,
                               h.generacion,h.estado,h.restaurado_en,
                               (h.taller_id=o.taller_id AND h.titular_id=o.user_id AND h.politica=o.politica
                                AND h.confirmado_en=o.confirmado_en AND h.reversible_hasta=o.reversible_hasta
                                AND h.eliminacion_prevista_en=o.eliminacion_prevista_en) AS history_matches
                          FROM public.cuenta_cierre_operaciones o LEFT JOIN public.cuenta_cierres h ON h.referencia=o.cierre_referencia
                         WHERE o.taller_id=? AND o.user_id=? ORDER BY o.cierre_version DESC LIMIT 1
                        """,(row,index)->new StoredOperation(new WorkshopClosureCommandService.Receipt(
                            row.getObject(1,UUID.class),row.getObject(2,UUID.class),WorkshopClosurePurpose.valueOf(row.getString(3)),
                            row.getLong(4),row.getString(5),instant(row.getObject(6,OffsetDateTime.class)),instant(row.getObject(7,OffsetDateTime.class)),
                            instant(row.getObject(8,OffsetDateTime.class)),instant(row.getObject(9,OffsetDateTime.class)),false),
                            row.getLong(10),row.getString(11),instant(row.getObject(12,OffsetDateTime.class)),row.getBoolean(13)),routed,actor.userId());
                WorkshopClosureCommandService.Receipt last=null;
                if (!operations.isEmpty()) { validateOperation(operations.getFirst(),anchor); last=operations.getFirst().receipt(); }
                // The gate excludes a closure transition; the refreshed actor and exact deadline remain authoritative.
                if (!actor.equals(authorization.authorize(accessToken))) throw unavailable();
                authorization.requireLive(actor);
                boolean open="ABIERTO".equals(anchor.state());
                return new Status(anchor.state(),anchor.name(),observed,anchor.reference(),anchor.confirmed(),anchor.reversible(),open,!open,last);
            }));
        } catch (UnauthorizedException | AccessDeniedException | WorkshopClosureBlockedException | WorkshopClosureBusyException | Unavailable rejected) {
            throw rejected;
        } catch (RuntimeException failure) { throw unavailable(); }
    }

    private static void validateAnchor(Anchor anchor,WorkshopClosureReauthenticationService.Authority actor,Instant observed) {
        if (anchor.name()==null || anchor.name().isBlank() || anchor.name().length()>255 || anchor.version()!=actor.closureVersion()
                || !Objects.equals(anchor.reference(),actor.closureReference()) || !anchor.state().equals(actor.state())) throw unavailable();
        if ("ABIERTO".equals(anchor.state())) {
            if (anchor.reference()!=null || anchor.confirmed()!=null || anchor.reversible()!=null || anchor.deletion()!=null) throw unavailable();
        } else if ("RESTRINGIDO".equals(anchor.state())) {
            if (!anchor.historyMatches() || anchor.version()<=0 || anchor.reference()==null) throw unavailable();
            var schedule=new WorkshopClosurePolicy.Schedule(anchor.confirmed(),anchor.reversible(),anchor.deletion());
            if (!schedule.canRestoreAt(observed)) throw new WorkshopClosureBlockedException();
        } else throw unavailable();
    }
    private static void validateOperation(StoredOperation stored,Anchor anchor) {
        var receipt=stored.receipt();
        if (!stored.historyMatches() || receipt.generation()!=anchor.version() || !receipt.stateAtCommit().equals(anchor.state())
                || receipt.operationId()==null || receipt.reference()==null || receipt.completedAt()==null) throw unavailable();
        var schedule=new WorkshopClosurePolicy.Schedule(receipt.confirmedAt(),receipt.reversibleUntil(),receipt.deletionExpectedBy());
        if (receipt.completedAt().isBefore(schedule.confirmedAt()) || !receipt.completedAt().isBefore(schedule.reversibleUntil())) throw unavailable();
        if (receipt.purpose()==WorkshopClosurePurpose.CERRAR) {
            if (!"RESTRINGIDO".equals(stored.historyState()) || stored.historyVersion()!=receipt.generation()
                    || stored.restoredAt()!=null || !receipt.operationId().equals(receipt.reference())
                    || !receipt.reference().equals(anchor.reference()) || !receipt.confirmedAt().equals(anchor.confirmed())
                    || !receipt.reversibleUntil().equals(anchor.reversible())) throw unavailable();
        } else if (!"RESTAURADO".equals(stored.historyState()) || stored.historyVersion()!=receipt.generation()-1
                || stored.restoredAt()==null || receipt.completedAt().isBefore(stored.restoredAt())) throw unavailable();
    }
    public record Status(String state,String workshopName,Instant observedAt,UUID reference,Instant confirmedAt,
                         Instant reversibleUntil,boolean canRequest,boolean canRestore,WorkshopClosureCommandService.Receipt lastOperation) {
        @Override public String toString() { return "WorkshopClosureStatusService.Status[redacted]"; }
    }
    public static final class Unavailable extends RuntimeException {
        public Unavailable() { super("No se pudo comprobar el estado de cierre del taller."); }
    }
    private record Anchor(String name,String state,long version,UUID reference,Instant confirmed,Instant reversible,Instant deletion,boolean historyMatches) { }
    private record StoredOperation(WorkshopClosureCommandService.Receipt receipt,long historyVersion,String historyState,Instant restoredAt,boolean historyMatches) { }
    private static Instant instant(OffsetDateTime value) { return value==null?null:value.toInstant(); }
    private static Unavailable unavailable() { return new Unavailable(); }
}
