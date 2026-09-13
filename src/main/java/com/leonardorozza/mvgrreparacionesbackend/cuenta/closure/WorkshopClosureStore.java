package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Internal persistence boundary, not a user-facing closure command. The caller must first acquire
 * the exclusive workshop gate and, in cut C, verify/consume the specific confirmation in this transaction.
 * No independent transaction, HTTP route, provider call or deletion worker is created here.
 */
@Service
public class WorkshopClosureStore {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public WorkshopClosureStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc=Objects.requireNonNull(jdbc); this.clock=Objects.requireNonNull(clock);
    }
    public record Receipt(UUID reference,long tallerId,long titularId,long generation,String state,
            Instant confirmedAt,Instant reversibleUntil,Instant deletionExpectedBy,boolean reused) { }
    public static final class Rejected extends RuntimeException {
        public enum Code { INVALID_BOUNDARY, INVALID_ACTOR, CONFLICT, CAPACITY, UNAVAILABLE }
        private final Code code;
        Rejected(Code code) { super("No se pudo registrar la transición de cierre."); this.code=code; }
        public Code code() { return code; }
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public Receipt restrict(long tallerId,long titularId,UUID reference) {
        try {
            boundary(tallerId,reference);
            var workshop=workshop(tallerId);
            long oldOwnerVersion=owner(tallerId,titularId);
            var previous=read(reference);
            if (previous!=null) {
                if (previous.tallerId()!=tallerId || previous.titularId()!=titularId) throw rejected(Rejected.Code.CONFLICT);
                return reused(previous);
            }
            if (!"ABIERTO".equals(workshop.state())) throw rejected(Rejected.Code.CONFLICT);
            long generation=increment(workshop.generation());
            var schedule=WorkshopClosurePolicy.scheduleAt(clock.instant());
            revokeSessions(tallerId);
            // Only explicitly eligible READY is rebound. A later reset/revocation still invalidates it normally.
            var keptJobs=jdbc.query("""
                    UPDATE public.cuenta_exportaciones j SET token_version=?
                     WHERE j.taller_id=? AND j.user_id=? AND j.token_version=? AND j.estado='READY'
                       AND j.expira_en>clock_timestamp()
                       AND NOT EXISTS(SELECT 1 FROM unnest(j.foto_ids) expected(id) WHERE NOT EXISTS(
                         SELECT 1 FROM public.reparacion_fotos_privadas p
                          WHERE p.id=expected.id AND p.taller_id=j.taller_id AND p.estado='ASOCIADA'
                            AND p.retener_hasta>clock_timestamp()
                            AND EXISTS(SELECT 1 FROM public.reparaciones r WHERE r.id=p.reparacion_id
                              AND r.id=p.reparacion_original_id AND r.taller_id=p.taller_id)
                            AND EXISTS(SELECT 1 FROM public.users u WHERE u.id=p.user_id AND u.taller_id=p.taller_id)))
                    RETURNING j.id
                    """,(rs,n)->rs.getObject(1,UUID.class),increment(oldOwnerVersion),tallerId,titularId,oldOwnerVersion);
            revokeJobs(tallerId,keptJobs);
            jdbc.update("""
                    INSERT INTO public.cuenta_cierres(referencia,taller_id,titular_id,generacion,estado,politica,
                      confirmado_en,reversible_hasta,eliminacion_prevista_en)
                    VALUES(?,?,?,?,'RESTRINGIDO',?,?,?,?)
                    """,reference,tallerId,titularId,generation,WorkshopClosurePolicy.POLICY_VERSION,
                    utc(schedule.confirmedAt()),utc(schedule.reversibleUntil()),utc(schedule.deletionExpectedBy()));
            int changed=jdbc.update("""
                    UPDATE public.talleres SET cierre_estado='RESTRINGIDO',cierre_version=?,cierre_referencia=?,
                      cierre_confirmado_en=?,cierre_reversible_hasta=?,cierre_eliminacion_prevista_en=?
                     WHERE id=? AND cierre_estado='ABIERTO' AND cierre_version=?
                    """,generation,reference,utc(schedule.confirmedAt()),utc(schedule.reversibleUntil()),
                    utc(schedule.deletionExpectedBy()),tallerId,workshop.generation());
            if(changed!=1) throw rejected(Rejected.Code.CONFLICT);
            return Objects.requireNonNull(read(reference));
        } catch(Rejected rejected) { throw rejected; }
        // The workshop gate is local to one tenant; closure references are unique across all tenants.
        catch(DuplicateKeyException collision) { throw rejected(Rejected.Code.CONFLICT); }
        catch(RuntimeException failure) { throw rejected(Rejected.Code.UNAVAILABLE); }
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public Receipt restore(long tallerId,long titularId,UUID reference) {
        try {
            boundary(tallerId,reference);
            var workshop=workshop(tallerId);
            owner(tallerId,titularId);
            var previous=read(reference);
            if(previous==null || previous.tallerId()!=tallerId || previous.titularId()!=titularId)
                throw rejected(Rejected.Code.CONFLICT);
            if("RESTAURADO".equals(previous.state())) return reused(previous);
            Instant now=clock.instant().truncatedTo(ChronoUnit.MICROS);
            var schedule=new WorkshopClosurePolicy.Schedule(previous.confirmedAt(),previous.reversibleUntil(),previous.deletionExpectedBy());
            if(!"RESTRINGIDO".equals(workshop.state()) || !reference.equals(workshop.reference())
                    || !schedule.canRestoreAt(now)) throw rejected(Rejected.Code.CONFLICT);
            long generation=increment(workshop.generation());
            revokeSessions(tallerId);
            // Restoration does not carry an old sensitive artifact across another revocation epoch.
            revokeJobs(tallerId,List.of());
            jdbc.update("UPDATE public.cuenta_cierres SET estado='RESTAURADO',restaurado_en=? WHERE referencia=? AND estado='RESTRINGIDO'",utc(now),reference);
            jdbc.update("""
                    UPDATE public.talleres SET cierre_estado='ABIERTO',cierre_version=?,cierre_referencia=NULL,
                      cierre_confirmado_en=NULL,cierre_reversible_hasta=NULL,cierre_eliminacion_prevista_en=NULL
                     WHERE id=? AND cierre_referencia=? AND cierre_estado='RESTRINGIDO'
                    """,generation,tallerId,reference);
            return Objects.requireNonNull(read(reference));
        } catch(Rejected rejected) { throw rejected; }
        catch(RuntimeException failure) { throw rejected(Rejected.Code.UNAVAILABLE); }
    }

    private void boundary(long tallerId,UUID reference) {
        if(tallerId<=0 || reference==null || !TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !"read committed".equals(jdbc.queryForObject("SHOW transaction_isolation",String.class)))
            throw rejected(Rejected.Code.INVALID_BOUNDARY);
        Boolean exclusive=jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM pg_catalog.pg_locks l WHERE l.locktype='advisory'
                  AND l.pid=pg_catalog.pg_backend_pid() AND l.granted AND l.mode='ExclusiveLock' AND l.objsubid=1
                  AND l.classid=((pg_catalog.hashtextextended(?,0)>>32)&4294967295)::oid
                  AND l.objid=(pg_catalog.hashtextextended(?,0)&4294967295)::oid)
                """,Boolean.class,WorkshopClosureGate.LOCK_PREFIX+tallerId,WorkshopClosureGate.LOCK_PREFIX+tallerId);
        if(!Boolean.TRUE.equals(exclusive)) throw rejected(Rejected.Code.INVALID_BOUNDARY);
    }
    private Workshop workshop(long tallerId) {
        var rows=jdbc.query("SELECT cierre_estado,cierre_version,cierre_referencia FROM public.talleres WHERE id=? AND activo FOR UPDATE",
                (rs,n)->new Workshop(rs.getString(1),rs.getLong(2),rs.getObject(3,UUID.class)),tallerId);
        if(rows.size()!=1) throw rejected(Rejected.Code.INVALID_ACTOR);
        return rows.getFirst();
    }
    private long owner(long tallerId,long titularId) {
        var rows=jdbc.queryForList("SELECT token_version FROM public.users WHERE id=? AND taller_id=? AND role='ADMIN' AND active AND email_verificado FOR UPDATE",
                Long.class,titularId,tallerId);
        if(rows.size()!=1 || rows.getFirst()<0) throw rejected(Rejected.Code.INVALID_ACTOR);
        return rows.getFirst();
    }
    private void revokeSessions(long tallerId) {
        var versions=jdbc.queryForList("SELECT token_version FROM public.users WHERE taller_id=? ORDER BY id FOR UPDATE",Long.class,tallerId);
        if(versions.isEmpty() || versions.stream().anyMatch(v->v==null || v<0 || v==Long.MAX_VALUE)) throw rejected(Rejected.Code.CAPACITY);
        jdbc.update("UPDATE public.users SET token_version=token_version+1 WHERE taller_id=?",tallerId);
        jdbc.update("DELETE FROM public.cuenta_reautenticaciones WHERE taller_id=?",tallerId);
    }
    private void revokeJobs(long tallerId,List<UUID> keptJobs) {
        jdbc.update("""
                UPDATE public.cuenta_exportaciones SET estado='REVOKED',snapshot_cipher=NULL,archive_cipher=NULL,
                  foto_ids='{}',lease_id=NULL,lease_hasta=NULL,actualizada_en=clock_timestamp()
                 WHERE taller_id=? AND estado IN ('QUEUED','RUNNING','READY')
                   AND NOT (id=ANY(?))
                """,tallerId,keptJobs.toArray(UUID[]::new));
    }
    private Receipt read(UUID reference) {
        var rows=jdbc.query("""
                SELECT referencia,taller_id,titular_id,generacion,estado,confirmado_en,reversible_hasta,eliminacion_prevista_en
                  FROM public.cuenta_cierres WHERE referencia=?
                """,(rs,n)->new Receipt(rs.getObject(1,UUID.class),rs.getLong(2),rs.getLong(3),rs.getLong(4),rs.getString(5),
                        rs.getObject(6,OffsetDateTime.class).toInstant(),rs.getObject(7,OffsetDateTime.class).toInstant(),
                        rs.getObject(8,OffsetDateTime.class).toInstant(),false),reference);
        return rows.isEmpty()?null:rows.getFirst();
    }
    private static Receipt reused(Receipt r) { return new Receipt(r.reference(),r.tallerId(),r.titularId(),r.generation(),r.state(),r.confirmedAt(),r.reversibleUntil(),r.deletionExpectedBy(),true); }
    private static OffsetDateTime utc(Instant instant) { return instant.atOffset(ZoneOffset.UTC); }
    private static long increment(long version) {
        if(version<0 || version==Long.MAX_VALUE) throw rejected(Rejected.Code.CAPACITY);
        return version+1;
    }
    private static Rejected rejected(Rejected.Code code) { return new Rejected(code); }
    private record Workshop(String state,long generation,UUID reference) { }
}
