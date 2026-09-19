package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalV37OperationalDeletionSchema;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.WorkshopOperationalDeletionService.Category;

/** Fresh observation of V37's eight categories; never proof of complete account erasure. */
@Service
public final class WorkshopOperationalDeletionProgress {
    // Same photo conditions as V37. Alias t is the permanent workshop anchor in each query.
    static final String PHOTOS_PENDING_FOR_TALLER = """
            (EXISTS (SELECT 1 FROM public.reparacion_fotos f WHERE f.taller_id=t.id)
             OR EXISTS (SELECT 1 FROM public.reparacion_fotos_privadas f
               LEFT JOIN public.reparacion_foto_eliminaciones d ON d.foto_id=f.id
              WHERE f.taller_id=t.id AND (f.estado<>'ELIMINADA' OR d.foto_id IS NULL
                OR d.resultado<>'IDENTIDAD_ELIMINADA' OR d.confirmada_en IS NULL
                OR d.asset_id IS NULL OR d.asset_version IS NULL
                OR (d.user_id,d.taller_id,d.object_key) IS DISTINCT FROM (f.user_id,f.taller_id,f.object_key))))
            """;
    private static final String SQL = """
            SELECT t.id,h.referencia,h.generacion,h.confirmado_en,h.reversible_hasta,h.eliminacion_prevista_en,
                   clock_timestamp() AS observed_at,
                   (SELECT count(*) FROM public.presupuesto_items WHERE taller_id=t.id) AS items,
                   (SELECT count(*) FROM public.presupuestos WHERE taller_id=t.id) AS presupuestos,
                   (SELECT count(*) FROM public.cobros WHERE taller_id=t.id) AS cobros,
                   (SELECT count(*) FROM public.repuestos WHERE taller_id=t.id) AS repuestos,
                   (SELECT count(*) FROM public.reparaciones WHERE taller_id=t.id) AS reparaciones,
                   (SELECT count(*) FROM public.equipos WHERE taller_id=t.id) AS equipos,
                   (SELECT count(*) FROM public.clientes WHERE taller_id=t.id) AS clientes,
                   (SELECT count(*) FROM public.articulos WHERE taller_id=t.id) AS articulos,
                   %s AS photos_pending
              FROM public.talleres t JOIN public.cuenta_cierres h
                ON h.referencia=t.cierre_referencia AND h.taller_id=t.id
             WHERE t.id=? AND t.activo AND t.cierre_estado='RESTRINGIDO' AND t.cierre_referencia=?
               AND t.cierre_version=h.generacion AND h.estado='RESTRINGIDO' AND h.restaurado_en IS NULL
               AND h.politica='ordenfix-cierre/1' AND h.generacion>0
               AND EXISTS (SELECT 1 FROM public.users u WHERE u.id=h.titular_id AND u.taller_id=t.id AND u.role='ADMIN')
               AND h.confirmado_en=t.cierre_confirmado_en AND h.reversible_hasta=t.cierre_reversible_hasta
               AND h.eliminacion_prevista_en=t.cierre_eliminacion_prevista_en
               AND isfinite(h.confirmado_en) AND isfinite(h.reversible_hasta) AND isfinite(h.eliminacion_prevista_en)
               AND h.reversible_hasta=h.confirmado_en+INTERVAL '168 hours'
               AND h.eliminacion_prevista_en=h.reversible_hasta+INTERVAL '720 hours'
             FOR SHARE OF t,h
            """.formatted(PHOTOS_PENDING_FOR_TALLER);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public WorkshopOperationalDeletionProgress(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = Objects.requireNonNull(jdbc);
        transaction = new TransactionTemplate(Objects.requireNonNull(manager));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(10);
    }

    /** Internal read only in effect; writable transaction permits locks on the permanent anchor. */
    public Snapshot read(long tallerId, UUID closureReference) {
        if (tallerId <= 0 || closureReference == null) throw new Rejected(Rejected.Code.INVALID_TARGET);
        try {
            return Objects.requireNonNull(transaction.execute(status -> {
                jdbc.execute("SET LOCAL statement_timeout='5s'");
                jdbc.execute("SET LOCAL lock_timeout='2s'");
                if (!Boolean.TRUE.equals(jdbc.queryForObject(
                        "SELECT pg_catalog.pg_try_advisory_xact_lock_shared(pg_catalog.hashtextextended(?,0))",
                        Boolean.class, WorkshopClosureGate.LOCK_PREFIX + tallerId)))
                    throw new Rejected(Rejected.Code.UNAVAILABLE);
                LegalV37OperationalDeletionSchema.require(jdbc);
                var rows = jdbc.query(SQL, (row, index) -> {
                    Instant confirmed = row.getObject("confirmado_en", OffsetDateTime.class).toInstant();
                    Instant reversible = row.getObject("reversible_hasta", OffsetDateTime.class).toInstant();
                    Instant expected = row.getObject("eliminacion_prevista_en", OffsetDateTime.class).toInstant();
                    new WorkshopClosurePolicy.Schedule(confirmed, reversible, expected);
                    Instant observed = row.getObject("observed_at", OffsetDateTime.class).toInstant();
                    if (observed.isBefore(confirmed)) throw new Rejected(Rejected.Code.INVALID_REFERENCE);
                    var remaining = new EnumMap<Category, Long>(Category.class);
                    for (Category category : Category.values()) {
                        long count = row.getLong(category.name().toLowerCase(java.util.Locale.ROOT));
                        if (row.wasNull()) throw new Rejected(Rejected.Code.UNAVAILABLE);
                        remaining.put(category, count);
                    }
                    boolean photos = row.getBoolean("photos_pending");
                    if (row.wasNull()) throw new Rejected(Rejected.Code.UNAVAILABLE);
                    return new Snapshot(row.getLong("id"), row.getObject("referencia", UUID.class),
                            row.getLong("generacion"), observed, reversible, remaining, photos);
                }, tallerId, closureReference);
                if (rows.size() != 1) throw new Rejected(Rejected.Code.INVALID_REFERENCE);
                return rows.getFirst();
            }));
        } catch (Rejected failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new Rejected(Rejected.Code.UNAVAILABLE);
        }
    }

    /** Counts concern only the eight V37 categories; identity, QR and retained evidence are excluded. */
    public record Snapshot(long tallerId, UUID closureReference, long generation, Instant observedAt,
                           Instant reversibleUntil, Map<Category, Long> remaining, boolean photosPending) {
        public Snapshot {
            if (tallerId <= 0 || closureReference == null || generation <= 0
                    || observedAt == null || reversibleUntil == null) throw new IllegalArgumentException("Invalid deletion observation");
            remaining = Map.copyOf(Objects.requireNonNull(remaining));
            if (remaining.size() != Category.values().length
                    || remaining.values().stream().anyMatch(count -> count < 0))
                throw new IllegalArgumentException("Incomplete deletion observation");
        }
        public boolean hasRows() { return remaining.values().stream().anyMatch(count -> count > 0); }
        public boolean graceExpired() { return !observedAt.isBefore(reversibleUntil); }
        public Category firstPending() {
            for (Category category : Category.values()) if (remaining.get(category) > 0) return category;
            return null;
        }
        @Override public String toString() { return "OperationalDeletionSnapshot[redacted]"; }
    }

    public static final class Rejected extends RuntimeException {
        public enum Code { INVALID_TARGET, INVALID_REFERENCE, UNAVAILABLE }
        private final Code code;
        private Rejected(Code code) { super("No se pudo observar el avance del borrado operativo."); this.code = code; }
        public Code code() { return code; }
    }
}
