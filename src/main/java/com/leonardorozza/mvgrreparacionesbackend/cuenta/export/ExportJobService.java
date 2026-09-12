package com.leonardorozza.mvgrreparacionesbackend.cuenta.export;

import com.leonardorozza.mvgrreparacionesbackend.cuenta.reauth.ExportReauthenticationService;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.reauth.ExportReauthenticationService.ExportSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static com.leonardorozza.mvgrreparacionesbackend.cuenta.export.ExportPackageException.Code.*;
import static com.leonardorozza.mvgrreparacionesbackend.cuenta.reauth.ExportReauthenticationPurpose.*;

/** Internal account workflow. No controller. Ciphertext, leases and proof consumption commit in PostgreSQL. */
public final class ExportJobService {
    private static final int MAX_LIVE_JOBS=4;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ExportReauthenticationService reauthentication;
    private final WorkshopExportSnapshotService snapshots;
    private final ExportArtifactCodec codec;
    private final ExportPhotoReader photos;

    public record Status(UUID id,String state,Instant expiresAt,boolean reused) { }
    public ExportJobService(JdbcTemplate jdbc,PlatformTransactionManager manager,ExportReauthenticationService reauthentication,
            WorkshopExportSnapshotService snapshots,ExportArtifactCodec codec,ExportPhotoReader photos) {
        this.jdbc=Objects.requireNonNull(jdbc); this.reauthentication=Objects.requireNonNull(reauthentication);
        this.snapshots=Objects.requireNonNull(snapshots); this.codec=Objects.requireNonNull(codec); this.photos=Objects.requireNonNull(photos);
        tx=new TransactionTemplate(manager); tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED); tx.setTimeout(15);
    }
    public Status request(String accessToken,String proof,UUID requestKey) {
        if(requestKey==null) throw new ExportPackageException(INVALID_PACKAGE);
        return transaction(()-> {
            ExportSession actor=reauthentication.authorize(accessToken);
            cleanupLocked();
            String key=ExportFile.digest(requestKey.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var previous=jdbc.query(SELECT+" WHERE user_id=? AND taller_id=? AND request_hash=?",ExportJobService::row,
                    actor.userId(),actor.tallerId(),key);
            if(!previous.isEmpty()) {
                Job job=previous.getFirst();
                if(!job.sessionHash().equals(actor.sessionHash()) || job.version()!=actor.tokenVersion()) throw new ExportPackageException(ACCESS_DENIED);
                return status(job,true);
            }
            if(jdbc.queryForObject("SELECT count(*) FROM public.cuenta_exportaciones WHERE estado IN ('QUEUED','RUNNING','READY')",Long.class)>=MAX_LIVE_JOBS
                    || jdbc.queryForObject("SELECT count(*) FROM public.cuenta_exportaciones WHERE taller_id=? AND estado IN ('QUEUED','RUNNING','READY')",Long.class,actor.tallerId())>0) {
                throw new ExportPackageException(CAPACITY_EXCEEDED);
            }
            reauthentication.consume(accessToken,proof,EXPORTAR);
            UUID id=UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO public.cuenta_exportaciones(id,user_id,taller_id,token_version,session_hash,request_hash,estado,creada_en,actualizada_en,expira_en)
                    VALUES (?,?,?,?,?,?,'QUEUED',statement_timestamp(),statement_timestamp(),statement_timestamp()+INTERVAL '24 hours')
                    """,id,actor.userId(),actor.tallerId(),actor.tokenVersion(),actor.sessionHash(),key);
            return status(find(id),false);
        });
    }
    public Status status(String accessToken,UUID id) {
        return transaction(()-> {
            ExportSession actor=reauthentication.authorize(accessToken);
            cleanupLocked(); Job job=find(id); requireOwner(job,actor); return status(job,false);
        });
    }
    /** D must expose this only through its bounded HTTP download flow; every call consumes a fresh download proof. */
    public byte[] authorizedArchive(String accessToken,String proof,UUID id) {
        return transaction(()-> {
            ExportSession actor=reauthentication.authorize(accessToken);
            cleanupLocked(); Job job=find(id); requireOwner(job,actor);
            if(!job.state().equals("READY")) throw new ExportPackageException(INVALID_PACKAGE);
            requireLive(job,true);
            byte[] encrypted=jdbc.queryForObject("SELECT archive_cipher FROM public.cuenta_exportaciones WHERE id=?",byte[].class,id);
            byte[] result=codec.decryptArchive(context(job),encrypted);
            requireLive(job,true);
            reauthentication.consume(accessToken,proof,DESCARGAR_EXPORTACION);
            return result;
        });
    }
    /** A bounded scheduler pass; the database singleton claim also serializes different application instances. */
    public synchronized boolean runNext() {
        Job job=transaction(()-> {
            cleanupLocked();
            if(jdbc.queryForObject("SELECT count(*) FROM public.cuenta_exportaciones WHERE estado='RUNNING'",Long.class)>0) return null;
            var rows=jdbc.query(SELECT+" WHERE estado='QUEUED' ORDER BY creada_en,id LIMIT 1 FOR UPDATE",ExportJobService::row);
            if(rows.isEmpty()) return null;
            UUID id=rows.getFirst().id(),lease=UUID.randomUUID();
            jdbc.update("""
                    UPDATE public.cuenta_exportaciones SET estado='RUNNING',lease_id=?,lease_hasta=clock_timestamp()+INTERVAL '5 minutes',
                        intentos=intentos+1,actualizada_en=clock_timestamp() WHERE id=?
                    """,lease,id);
            return find(id);
        });
        if(job==null) return false;
        long started=System.nanoTime();
        try {
            checkpoint(job,started);
            byte[] stored=transaction(()-> {
                requireLease(job); return jdbc.queryForObject("SELECT snapshot_cipher FROM public.cuenta_exportaciones WHERE id=?",byte[].class,job.id());
            });
            ExportSnapshot snapshot;
            if(stored==null) {
                snapshot=snapshots.capture(job.actor(),job.workshop(),job.version());
                if(snapshot.pendingPhotos().size()>256) throw new ExportPackageException(CAPACITY_EXCEEDED);
                byte[] encrypted=codec.encryptSnapshot(context(job),snapshot);
                transaction(()-> {
                    requireLease(job); requireLive(job,true);
                    UUID[] ids=snapshot.pendingPhotos().stream().map(ExportSnapshot.PendingPhoto::id).toArray(UUID[]::new);
                    validatePhotos(job,ids);
                    Instant until=photoExpiry(job,ids);
                    if(!until.isAfter(now())) throw new ExportPackageException(ACCESS_DENIED);
                    int changed=jdbc.update("""
                            UPDATE public.cuenta_exportaciones SET snapshot_cipher=?,capturada_en=?,foto_ids=?,expira_en=LEAST(expira_en,?),
                                actualizada_en=clock_timestamp() WHERE id=? AND lease_id=? AND snapshot_cipher IS NULL
                            """,encrypted,snapshot.observedAt().atOffset(ZoneOffset.UTC),ids,until.atOffset(ZoneOffset.UTC),job.id(),job.lease());
                    if(changed!=1) throw new ExportPackageException(UNAVAILABLE);
                    return true;
                });
            } else snapshot=codec.decryptSnapshot(context(job),stored);
            checkpoint(job,started);
            var files=photos.read(snapshot,job.version(),()->checkpoint(job,started));
            byte[] archive=codec.archive(context(job),snapshot,files);
            checkpoint(job,started);
            transaction(()-> {
                requireLease(job); requireLive(find(job.id()),true);
                int changed=jdbc.update("""
                        UPDATE public.cuenta_exportaciones SET estado='READY',archive_cipher=?,snapshot_cipher=NULL,
                            lease_id=NULL,lease_hasta=NULL,fallo=NULL,actualizada_en=clock_timestamp()
                        WHERE id=? AND lease_id=? AND estado='RUNNING'
                        """,archive,job.id(),job.lease());
                if(changed!=1) throw new ExportPackageException(UNAVAILABLE);
                return true;
            });
        } catch(RuntimeException failure) {
            fail(job,failure instanceof ExportPackageException typed?typed.code():UNAVAILABLE);
        }
        return true;
    }
    public int cleanup() { return transaction(this::cleanupLocked); }

    private void checkpoint(Job job,long started) {
        if(Thread.currentThread().isInterrupted() || System.nanoTime()-started>Duration.ofMinutes(3).toNanos()) throw new ExportPackageException(CAPACITY_EXCEEDED);
        transaction(()-> {requireLease(job); requireLive(find(job.id()),false); return true;});
    }
    private void fail(Job original,ExportPackageException.Code code) {
        transaction(()-> {
            var matches=jdbc.query(SELECT+" WHERE id=? AND lease_id=? AND estado='RUNNING'",ExportJobService::row,original.id(),original.lease());
            if(matches.isEmpty()) return false; // A stale worker cannot publish, retry, or erase a successor.
            Job current=matches.getFirst();
            String state=!current.expiresAt().isAfter(now())?"EXPIRED":code==ACCESS_DENIED?"REVOKED":code==UNAVAILABLE && current.attempts()<3?"QUEUED":"FAILED";
            boolean retry=state.equals("QUEUED");
            jdbc.update("""
                    UPDATE public.cuenta_exportaciones SET estado=?,fallo=?,lease_id=NULL,lease_hasta=NULL,archive_cipher=NULL,
                        snapshot_cipher=CASE WHEN ? THEN snapshot_cipher ELSE NULL END,
                        foto_ids=CASE WHEN ? THEN foto_ids ELSE '{}'::uuid[] END,actualizada_en=clock_timestamp()
                    WHERE id=? AND lease_id=?
                    """,state,code.name(),retry,retry,current.id(),original.lease());
            return true;
        });
    }
    private int cleanupLocked() {
        int changed=jdbc.update("""
                UPDATE public.cuenta_exportaciones j SET estado=CASE WHEN expira_en<=clock_timestamp() THEN 'EXPIRED' ELSE 'REVOKED' END,
                    snapshot_cipher=NULL,archive_cipher=NULL,foto_ids='{}',lease_id=NULL,lease_hasta=NULL,actualizada_en=clock_timestamp()
                WHERE estado IN ('QUEUED','RUNNING','READY') AND (expira_en<=clock_timestamp() OR NOT (
                """+VALID_ACTOR+" AND "+VALID_PHOTOS+"))");
        changed+=jdbc.update("""
                UPDATE public.cuenta_exportaciones SET estado=CASE WHEN intentos<3 THEN 'QUEUED' ELSE 'FAILED' END,
                    snapshot_cipher=CASE WHEN intentos<3 THEN snapshot_cipher ELSE NULL END,
                    foto_ids=CASE WHEN intentos<3 THEN foto_ids ELSE '{}'::uuid[] END,
                    lease_id=NULL,lease_hasta=NULL,fallo='UNAVAILABLE',actualizada_en=clock_timestamp()
                WHERE estado='RUNNING' AND lease_hasta<=clock_timestamp()
                """);
        changed+=jdbc.update("""
                DELETE FROM public.cuenta_exportaciones WHERE id IN (
                    SELECT id FROM public.cuenta_exportaciones WHERE estado IN ('FAILED','EXPIRED','REVOKED')
                      AND actualizada_en<clock_timestamp()-INTERVAL '7 days' ORDER BY actualizada_en,id LIMIT 100)
                """);
        jdbc.update("""
                DELETE FROM public.cuenta_reautenticaciones WHERE token_hash IN (
                    SELECT token_hash FROM public.cuenta_reautenticaciones WHERE expira_en<=clock_timestamp() ORDER BY expira_en LIMIT 1000)
                """);
        return changed;
    }
    private void requireLease(Job expected) {
        Long live=jdbc.queryForObject("SELECT count(*) FROM public.cuenta_exportaciones WHERE id=? AND lease_id=? AND estado='RUNNING' AND lease_hasta>clock_timestamp()",Long.class,expected.id(),expected.lease());
        if(live!=1) throw new ExportPackageException(UNAVAILABLE);
    }
    private void requireLive(Job job,boolean lockActor) {
        if(lockActor) {
            jdbc.queryForList("SELECT id FROM public.talleres WHERE id=? FOR SHARE",job.workshop());
            jdbc.queryForList("SELECT id FROM public.users WHERE id=? AND taller_id=? FOR SHARE",job.actor(),job.workshop());
            jdbc.queryForList("SELECT u.id FROM public.users u WHERE u.id IN (SELECT p.user_id FROM public.reparacion_fotos_privadas p JOIN public.cuenta_exportaciones j ON p.id=ANY(j.foto_ids) WHERE j.id=?) ORDER BY u.id FOR SHARE",job.id());
            jdbc.queryForList("SELECT r.id FROM public.reparaciones r WHERE r.id IN (SELECT p.reparacion_id FROM public.reparacion_fotos_privadas p JOIN public.cuenta_exportaciones j ON p.id=ANY(j.foto_ids) WHERE j.id=?) ORDER BY r.id FOR SHARE",job.id());
            jdbc.queryForList("SELECT p.id FROM public.reparacion_fotos_privadas p JOIN public.cuenta_exportaciones j ON p.id=ANY(j.foto_ids) WHERE j.id=? ORDER BY p.id FOR SHARE OF p",job.id());
        }
        Boolean valid=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM public.cuenta_exportaciones j WHERE j.id=? AND j.expira_en>clock_timestamp() AND "+VALID_ACTOR+" AND "+VALID_PHOTOS+")",Boolean.class,job.id());
        if(!Boolean.TRUE.equals(valid)) throw new ExportPackageException(ACCESS_DENIED);
    }
    private void validatePhotos(Job job,UUID[] ids) {
        Long count=jdbc.queryForObject("SELECT count(*) FROM public.reparacion_fotos_privadas p WHERE p.id=ANY(?) AND p.taller_id=? AND "+PHOTO_ALLOWED,Long.class,ids,job.workshop());
        if(count!=ids.length) throw new ExportPackageException(ACCESS_DENIED);
    }
    private Instant photoExpiry(Job job,UUID[] ids) {
        var value=jdbc.queryForObject("SELECT LEAST(?::timestamptz,min(retener_hasta)) FROM public.reparacion_fotos_privadas WHERE id=ANY(?)",OffsetDateTime.class,
                job.expiresAt().atOffset(ZoneOffset.UTC),ids);
        return value.toInstant();
    }
    private Job find(UUID id) {
        var jobs=jdbc.query(SELECT+" WHERE id=?",ExportJobService::row,id);
        if(jobs.size()!=1) throw new ExportPackageException(ACCESS_DENIED);
        return jobs.getFirst();
    }
    private static void requireOwner(Job job,ExportSession actor) {
        if(job.actor()!=actor.userId() || job.workshop()!=actor.tallerId() || job.version()!=actor.tokenVersion()) throw new ExportPackageException(ACCESS_DENIED);
    }
    private <T>T transaction(Supplier<T> work) {
        try { return tx.execute(status-> {
            jdbc.execute("SET LOCAL statement_timeout='15s'"); jdbc.execute("SET LOCAL lock_timeout='5s'");
            jdbc.queryForList("SELECT pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('ordenfix:export-jobs:v1',0))");
            return work.get();
        }); } catch(ExportPackageException | com.leonardorozza.mvgrreparacionesbackend.exceptions.BadRequestException
                | com.leonardorozza.mvgrreparacionesbackend.exceptions.UnauthorizedException
                | org.springframework.security.access.AccessDeniedException rejected) { throw rejected; }
        catch(RuntimeException failure) { throw new ExportPackageException(UNAVAILABLE); }
    }
    private Instant now() { return jdbc.queryForObject("SELECT clock_timestamp()",OffsetDateTime.class).toInstant(); }
    private static Status status(Job job,boolean reused) { return new Status(job.id(),job.state(),job.expiresAt(),reused); }
    private static ExportArtifactCodec.Context context(Job job) { return new ExportArtifactCodec.Context(job.id(),job.workshop(),job.actor()); }
    private record Job(UUID id,long actor,long workshop,long version,String sessionHash,String state,Instant expiresAt,UUID lease,int attempts) { }
    private static Job row(ResultSet r,int index)throws SQLException {
        return new Job(r.getObject("id",UUID.class),r.getLong("user_id"),r.getLong("taller_id"),r.getLong("token_version"),r.getString("session_hash"),r.getString("estado"),
                r.getObject("expira_en",OffsetDateTime.class).toInstant(),r.getObject("lease_id",UUID.class),r.getInt("intentos"));
    }
    private static final String SELECT="SELECT id,user_id,taller_id,token_version,session_hash,estado,expira_en,lease_id,intentos FROM public.cuenta_exportaciones";
    private static final String VALID_ACTOR="EXISTS(SELECT 1 FROM public.users u JOIN public.talleres t ON t.id=u.taller_id WHERE u.id=j.user_id AND u.taller_id=j.taller_id AND u.role='ADMIN' AND u.active AND u.email_verificado AND t.activo AND u.token_version=j.token_version)";
    private static final String PHOTO_ALLOWED="p.estado='ASOCIADA' AND p.retener_hasta>clock_timestamp() AND EXISTS(SELECT 1 FROM public.reparaciones r WHERE r.id=p.reparacion_id AND r.id=p.reparacion_original_id AND r.taller_id=p.taller_id) AND EXISTS(SELECT 1 FROM public.users u WHERE u.id=p.user_id AND u.taller_id=p.taller_id)";
    private static final String VALID_PHOTOS="NOT EXISTS(SELECT 1 FROM unnest(j.foto_ids) expected(id) WHERE NOT EXISTS(SELECT 1 FROM public.reparacion_fotos_privadas p WHERE p.id=expected.id AND p.taller_id=j.taller_id AND "+PHOTO_ALLOWED+"))";
}
