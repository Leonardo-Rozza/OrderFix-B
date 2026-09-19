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
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/** Explicit internal reconciliation only; never sends a cancellation or notification. */
@Service
public final class WorkshopClosureRenewalReconciler {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ObjectProvider<ClosureRenewalPort> renewal;
    private final TransactionTemplate transaction;

    public WorkshopClosureRenewalReconciler(JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock,
            ObjectProvider<ClosureRenewalPort> renewal) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.clock = Objects.requireNonNull(clock);
        this.renewal = Objects.requireNonNull(renewal);
        transaction = new TransactionTemplate(Objects.requireNonNull(manager));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(5);
    }

    /** IDs scope trusted internal work; they are not HTTP authorization. Historical closures remain targets. */
    public Result reconcile(long tallerId, UUID effectId) {
        if (tallerId <= 0 || effectId == null) throw new IllegalArgumentException("Invalid closure effect reference");
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Closure reconciliation requires no caller transaction");
        try {
            Instant startedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
            Snapshot snapshot = transaction.execute(status -> {
                admit(tallerId);
                return read(tallerId, effectId, false);
            });
            if (snapshot == null) return Result.NOT_ELIGIBLE;
            if ("CONFIRMADO".equals(snapshot.state())) return Result.REUSED;
            if (!"INCIERTO".equals(snapshot.state())) return Result.NOT_ELIGIBLE;
            ClosureRenewalPort port = renewal.getIfAvailable();
            if (port == null) return Result.PORT_UNAVAILABLE;
            if (!fresh(startedAt)) return Result.STALE;

            ClosureRenewalPort.Observation observation;
            try {
                observation = port.inspect(new ClosureRenewalPort.Target(effectId, snapshot.linkId(),
                        snapshot.externalReference(), snapshot.externalId()));
            } catch (RuntimeException unavailable) {
                return Result.UNRESOLVED;
            }
            if (observation == null || observation.state() != ClosureRenewalPort.State.CANCELED
                    || !snapshot.externalReference().equals(observation.externalReference())
                    || !snapshot.externalId().equals(observation.externalId())) return Result.UNRESOLVED;
            if (!fresh(startedAt)) return Result.STALE;
            return Objects.requireNonNull(transaction.execute(status -> confirm(tallerId, effectId, snapshot, startedAt)));
        } catch (SnapshotExpired ignored) {
            // The confirmation transaction rolled back, including its receipt.
            return Result.STALE;
        } catch (WorkshopClosureBusyException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            // Provider identifiers, SQL and nested causes must not escape this internal boundary.
            throw new Rejected();
        }
    }

    private Result confirm(long tallerId, UUID effectId, Snapshot snapshot, Instant startedAt) {
        admit(tallerId);
        // The V34 late-observation trigger locks link -> effect. Keep that order, before checking time.
        var links = jdbc.queryForList("SELECT id FROM public.subscription_provider_links WHERE id=? FOR SHARE",
                Long.class, snapshot.linkId());
        if (links.size() != 1) return Result.STALE;
        Snapshot current = read(tallerId, effectId, true);
        if (!snapshot.equals(current) || !fresh(startedAt)) return Result.STALE;
        int changed = jdbc.update("""
                UPDATE public.cuenta_cierre_efectos SET estado='CONFIRMADO',confirmed_at=?
                 WHERE efecto_id=? AND taller_id=? AND estado='INCIERTO' AND xmin::text=?
                """, Timestamp.from(clock.instant()), effectId, tallerId, snapshot.effectRevision());
        if (changed != 1) throw new Rejected();
        if (!fresh(startedAt)) throw new SnapshotExpired();
        return Result.CONFIRMED;
    }

    private Snapshot read(long tallerId, UUID effectId, boolean lockEffect) {
        var snapshots = jdbc.query("""
                SELECT e.estado,e.link_id,e.expected_external_reference,e.expected_external_id,
                       e.xmin::text AS effect_revision,l.xmin::text AS link_revision
                  FROM public.cuenta_cierre_efectos e
                  JOIN public.subscription_provider_links l ON l.id=e.link_id
                    AND l.provider='MERCADO_PAGO' AND l.external_reference=e.expected_external_reference
                    AND l.external_subscription_id=e.expected_external_id
                  JOIN public.suscripciones s ON s.id=l.suscripcion_id AND s.taller_id=e.taller_id
                  JOIN public.cuenta_cierre_operaciones o ON o.operacion_id=e.operacion_id
                    AND o.taller_id=e.taller_id AND o.user_id=e.usuario_id
                    AND o.cierre_referencia=e.cierre_referencia AND o.proposito='CERRAR'
                  JOIN public.cuenta_cierres h ON h.referencia=e.cierre_referencia
                    AND h.taller_id=e.taller_id AND h.titular_id=e.usuario_id
                  JOIN public.users u ON u.id=e.usuario_id AND u.taller_id=e.taller_id AND u.role='ADMIN'
                 WHERE e.taller_id=? AND e.efecto_id=? AND e.tipo='CANCELAR_RENOVACION'
                   AND e.expected_external_id IS NOT NULL
                """ + (lockEffect ? " FOR UPDATE OF e" : ""), (row, index) -> new Snapshot(
                row.getString("estado"), row.getLong("link_id"), row.getString("expected_external_reference"),
                row.getString("expected_external_id"), row.getString("effect_revision"), row.getString("link_revision")),
                tallerId, effectId);
        return snapshots.size() == 1 ? snapshots.getFirst() : null;
    }

    private void admit(long tallerId) {
        jdbc.queryForObject("SELECT pg_catalog.set_config('lock_timeout','2s',true)", String.class);
        jdbc.queryForObject("SELECT pg_catalog.set_config('statement_timeout','3s',true)", String.class);
        // Reconciliation also applies after grace/restoration: do not require an operational workshop.
        if (!Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT pg_catalog.pg_try_advisory_xact_lock_shared(pg_catalog.hashtextextended(?,0))",
                Boolean.class, WorkshopClosureGate.LOCK_PREFIX + tallerId))) throw new WorkshopClosureBusyException();
    }

    private boolean fresh(Instant startedAt) {
        Instant now = clock.instant();
        return !now.isBefore(startedAt) && now.isBefore(startedAt.plusSeconds(120));
    }

    public enum Result { CONFIRMED, REUSED, UNRESOLVED, NOT_ELIGIBLE, STALE, PORT_UNAVAILABLE }

    public static final class Rejected extends RuntimeException {
        private Rejected() { super("Closure renewal reconciliation unavailable"); }
    }

    private static final class SnapshotExpired extends RuntimeException {}

    private record Snapshot(String state, long linkId, String externalReference, String externalId,
            String effectRevision, String linkRevision) {
        @Override public String toString() { return "ClosureReconciliationSnapshot[redacted]"; }
    }
}
