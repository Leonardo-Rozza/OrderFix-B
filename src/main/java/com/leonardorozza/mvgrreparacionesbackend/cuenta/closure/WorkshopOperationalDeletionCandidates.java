package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalV37OperationalDeletionSchema;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Bounded discovery only. A candidate never authorizes erasure or certifies closure completion. */
@Service
public final class WorkshopOperationalDeletionCandidates {
    public static final int PAGE_SIZE = 2;
    private static final String SQL = """
            SELECT t.id,t.cierre_referencia FROM public.talleres t
             WHERE t.id>? AND t.activo AND t.cierre_estado='RESTRINGIDO'
               AND t.cierre_referencia IS NOT NULL AND t.cierre_reversible_hasta<=clock_timestamp()
               AND (EXISTS(SELECT 1 FROM public.presupuesto_items p WHERE p.taller_id=t.id)
                 OR EXISTS(SELECT 1 FROM public.presupuestos p WHERE p.taller_id=t.id)
                 OR EXISTS(SELECT 1 FROM public.cobros p WHERE p.taller_id=t.id)
                 OR EXISTS(SELECT 1 FROM public.repuestos p WHERE p.taller_id=t.id)
                 OR EXISTS(SELECT 1 FROM public.reparaciones p WHERE p.taller_id=t.id)
                 OR EXISTS(SELECT 1 FROM public.equipos p WHERE p.taller_id=t.id)
                 OR EXISTS(SELECT 1 FROM public.clientes p WHERE p.taller_id=t.id)
                 OR EXISTS(SELECT 1 FROM public.articulos p WHERE p.taller_id=t.id)
                 OR (%s))
             ORDER BY t.id LIMIT 3
            """.formatted(WorkshopOperationalDeletionProgress.PHOTOS_PENDING_FOR_TALLER);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public WorkshopOperationalDeletionCandidates(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = Objects.requireNonNull(jdbc);
        transaction = new TransactionTemplate(Objects.requireNonNull(manager));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setReadOnly(true);
        transaction.setTimeout(5);
    }

    public Page next(long afterTallerId) {
        if (afterTallerId < 0) throw new Rejected(Rejected.Code.INVALID_CURSOR);
        try {
            return Objects.requireNonNull(transaction.execute(status -> {
                jdbc.execute("SET LOCAL statement_timeout='5s'");
                LegalV37OperationalDeletionSchema.require(jdbc);
                List<Candidate> rows = jdbc.query(SQL, (row, index) ->
                        new Candidate(row.getLong(1), row.getObject(2, UUID.class)), afterTallerId);
                if (rows.size() > PAGE_SIZE + 1) throw new Rejected(Rejected.Code.UNAVAILABLE);
                long previous = afterTallerId;
                for (Candidate candidate : rows) {
                    if (candidate.tallerId() <= previous) throw new Rejected(Rejected.Code.UNAVAILABLE);
                    previous = candidate.tallerId();
                }
                return new Page(rows.subList(0, Math.min(PAGE_SIZE, rows.size())), rows.size() > PAGE_SIZE);
            }));
        } catch (Rejected failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new Rejected(Rejected.Code.UNAVAILABLE);
        }
    }

    public record Candidate(long tallerId, UUID closureReference) {
        public Candidate {
            if (tallerId <= 0 || closureReference == null)
                throw new IllegalArgumentException("Invalid operational deletion candidate");
        }
        @Override public String toString() { return "OperationalDeletionCandidate[redacted]"; }
    }

    public record Page(List<Candidate> candidates, boolean hasMore) {
        public Page {
            candidates = List.copyOf(candidates);
            if (candidates.size() > PAGE_SIZE || (hasMore && candidates.size() != PAGE_SIZE))
                throw new IllegalArgumentException("Invalid operational deletion candidate page");
            long previous = 0;
            for (Candidate candidate : candidates) {
                if (candidate.tallerId() <= previous)
                    throw new IllegalArgumentException("Invalid operational deletion candidate page");
                previous = candidate.tallerId();
            }
        }
        @Override public String toString() { return "OperationalDeletionPage[redacted]"; }
    }

    public static final class Rejected extends RuntimeException {
        public enum Code { INVALID_CURSOR, UNAVAILABLE }
        private final Code code;
        private Rejected(Code code) {
            super("No se pudo consultar el trabajo operativo pendiente.");
            this.code = code;
        }
        public Code code() { return code; }
    }
}
