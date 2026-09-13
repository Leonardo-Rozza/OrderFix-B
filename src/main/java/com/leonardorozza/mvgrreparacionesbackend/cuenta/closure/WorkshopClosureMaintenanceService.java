package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

/**
 * Explicit internal maintenance after grace. Only existing temporary-data expiration rules apply.
 * No scheduler, provider call, business erasure, policy exception or terminal closure is enabled.
 */
@Service
public final class WorkshopClosureMaintenanceService {
    public static final int BATCH_SIZE = 25;
    private final JdbcTemplate jdbc;
    private final WorkshopClosureGate gate;
    private final TransactionTemplate transaction;

    public WorkshopClosureMaintenanceService(JdbcTemplate jdbc, PlatformTransactionManager manager,
                                             WorkshopClosureGate gate) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.gate = Objects.requireNonNull(gate);
        transaction = new TransactionTemplate(Objects.requireNonNull(manager));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(10);
    }

    /** IDs are an internal maintenance target, never a substitute for HTTP authorization. */
    public Batch cleanExpired(long tallerId, UUID closureReference) {
        if (tallerId <= 0 || closureReference == null) throw rejected(Rejected.Code.INVALID_TARGET);
        try {
            return transaction.execute(status -> {
                jdbc.execute("SET LOCAL statement_timeout='5s'");
                gate.lockExclusive(tallerId); // First lock; excludes restore and newly admitted writers.
                long generation = requireExpiredClosure(tallerId, closureReference);
                OffsetDateTime now = jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
                // AuthToken uses LocalDateTime.now() in the JVM's zone, not PostgreSQL's session zone.
                LocalDateTime authNow = LocalDateTime.ofInstant(Objects.requireNonNull(now).toInstant(), ZoneId.systemDefault());
                int auth = jdbc.update("""
                        WITH batch AS MATERIALIZED (
                          SELECT id FROM public.auth_tokens WHERE taller_id=? AND expira_en<=?
                          ORDER BY expira_en,id LIMIT 25 FOR UPDATE SKIP LOCKED)
                        DELETE FROM public.auth_tokens t USING batch b WHERE t.id=b.id AND t.taller_id=?
                        """, tallerId, authNow, tallerId);
                int exportProofs = jdbc.update("""
                        WITH batch AS MATERIALIZED (
                          SELECT token_hash FROM public.cuenta_reautenticaciones WHERE taller_id=? AND expira_en<=?
                          ORDER BY expira_en,token_hash LIMIT 25 FOR UPDATE SKIP LOCKED)
                        DELETE FROM public.cuenta_reautenticaciones t USING batch b
                          WHERE t.token_hash=b.token_hash AND t.taller_id=?
                        """, tallerId, now, tallerId);
                int closureProofs = jdbc.update("""
                        WITH batch AS MATERIALIZED (
                          SELECT token_hash FROM public.cuenta_cierre_confirmaciones
                          WHERE taller_id=? AND expira_en<=? AND usada_en IS NULL
                          ORDER BY expira_en,token_hash LIMIT 25 FOR UPDATE SKIP LOCKED)
                        DELETE FROM public.cuenta_cierre_confirmaciones t USING batch b
                          WHERE t.token_hash=b.token_hash AND t.taller_id=?
                        """, tallerId, now, tallerId);
                int exports = jdbc.update("""
                        WITH batch AS MATERIALIZED (
                          SELECT id FROM public.cuenta_exportaciones WHERE taller_id=?
                            AND estado IN ('QUEUED','RUNNING','READY') AND expira_en<=?
                          ORDER BY expira_en,id LIMIT 25 FOR UPDATE SKIP LOCKED)
                        UPDATE public.cuenta_exportaciones t SET estado='EXPIRED',snapshot_cipher=NULL,
                          archive_cipher=NULL,foto_ids='{}',lease_id=NULL,lease_hasta=NULL,actualizada_en=?
                        FROM batch b WHERE t.id=b.id AND t.taller_id=?
                        """, tallerId, now, now, tallerId);
                int exportMetadata = jdbc.update("""
                        WITH batch AS MATERIALIZED (
                          SELECT id FROM public.cuenta_exportaciones WHERE taller_id=?
                            AND estado IN ('FAILED','EXPIRED','REVOKED')
                            AND snapshot_cipher IS NULL AND archive_cipher IS NULL AND cardinality(foto_ids)=0
                            AND lease_id IS NULL AND lease_hasta IS NULL AND actualizada_en<?::timestamptz-INTERVAL '7 days'
                          ORDER BY actualizada_en,id LIMIT 25 FOR UPDATE SKIP LOCKED)
                        DELETE FROM public.cuenta_exportaciones t USING batch b WHERE t.id=b.id AND t.taller_id=?
                        """, tallerId, now, tallerId);
                for (int changed : new int[]{auth, exportProofs, closureProofs, exports, exportMetadata}) {
                    if (changed < 0 || changed > BATCH_SIZE) throw rejected(Rejected.Code.UNAVAILABLE);
                }
                boolean pending = hasEligible(tallerId, now, authNow);
                if (requireExpiredClosure(tallerId, closureReference) != generation)
                    throw rejected(Rejected.Code.INVALID_TARGET);
                return new Batch(closureReference, generation, auth, exportProofs, closureProofs,
                        exports, exportMetadata, pending);
            });
        } catch (Rejected failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw rejected(Rejected.Code.UNAVAILABLE);
        }
    }

    private long requireExpiredClosure(long tallerId, UUID reference) {
        var generations = jdbc.queryForList("""
                SELECT t.cierre_version FROM public.talleres t JOIN public.cuenta_cierres h
                  ON h.referencia=t.cierre_referencia AND h.taller_id=t.id
                WHERE t.id=? AND t.cierre_referencia=? AND t.cierre_estado='RESTRINGIDO'
                  AND h.estado='RESTRINGIDO' AND h.generacion=t.cierre_version
                  AND h.politica='ordenfix-cierre/1' AND h.confirmado_en=t.cierre_confirmado_en
                  AND h.reversible_hasta=t.cierre_reversible_hasta
                  AND h.eliminacion_prevista_en=t.cierre_eliminacion_prevista_en
                  AND t.cierre_reversible_hasta<=clock_timestamp()
                """, Long.class, tallerId, reference);
        if (generations.size() != 1) throw rejected(Rejected.Code.INVALID_TARGET);
        return generations.getFirst();
    }

    private boolean hasEligible(long tallerId, OffsetDateTime now, LocalDateTime authNow) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM public.auth_tokens WHERE taller_id=? AND expira_en<=?)
                    OR EXISTS(SELECT 1 FROM public.cuenta_reautenticaciones WHERE taller_id=? AND expira_en<=?)
                    OR EXISTS(SELECT 1 FROM public.cuenta_cierre_confirmaciones
                        WHERE taller_id=? AND expira_en<=? AND usada_en IS NULL)
                    OR EXISTS(SELECT 1 FROM public.cuenta_exportaciones WHERE taller_id=? AND
                        ((estado IN ('QUEUED','RUNNING','READY') AND expira_en<=?)
                         OR (estado IN ('FAILED','EXPIRED','REVOKED') AND snapshot_cipher IS NULL
                            AND archive_cipher IS NULL AND cardinality(foto_ids)=0 AND lease_id IS NULL
                            AND lease_hasta IS NULL AND actualizada_en<?::timestamptz-INTERVAL '7 days')))
                """, Boolean.class, tallerId, authNow, tallerId, now, tallerId, now, tallerId, now, now));
    }

    /** Counts committed local mutations only. Empty/pending=false never means the workshop was erased. */
    public record Batch(UUID closureReference, long generation, int expiredAuthTokens, int expiredExportProofs,
                        int expiredUnusedClosureProofs, int expiredArchives, int purgedExportMetadata,
                        boolean moreEligibleAtObservation) {
        @Override public String toString() { return "ClosureMaintenanceBatch[redacted]"; }
    }

    public static final class Rejected extends RuntimeException {
        public enum Code { INVALID_TARGET, UNAVAILABLE }
        private final Code code;
        private Rejected(Code code) { super("No se pudo completar el mantenimiento del cierre."); this.code = code; }
        public Code code() { return code; }
    }
    private static Rejected rejected(Rejected.Code code) { return new Rejected(code); }
}
