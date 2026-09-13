package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Internal observation, never a deletion command or evidence of remote/backup erasure.
 * Reads counts only in one RR snapshot. Writable solely because PostgreSQL requires it for
 * the anchor's FOR SHARE lock, which rejects a snapshot older than a closure transition.
 */
@Service
public final class WorkshopClosureDeletionInventory {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public WorkshopClosureDeletionInventory(JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.clock = Objects.requireNonNull(clock);
        transaction = new TransactionTemplate(Objects.requireNonNull(manager));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setReadOnly(false);
        transaction.setTimeout(10);
    }

    /** No HTTP authorization: callers must be trusted internal orchestration. Safe after grace expires. */
    public Report inspect(long tallerId, UUID closureReference) {
        if (tallerId <= 0 || closureReference == null) throw new Rejected(Rejected.Code.INVALID_REFERENCE);
        try {
            return Objects.requireNonNull(transaction.execute(status -> inspectSnapshot(tallerId, closureReference)));
        } catch (Rejected | WorkshopClosureBusyException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            // Do not expose SQL, causes, object/provider identifiers or partially observed results.
            throw new Rejected(Rejected.Code.UNAVAILABLE);
        }
    }

    private Report inspectSnapshot(long tallerId, UUID reference) {
        jdbc.queryForObject("SELECT pg_catalog.set_config('lock_timeout','2s',true)", String.class);
        jdbc.queryForObject("SELECT pg_catalog.set_config('statement_timeout','5s',true)", String.class);
        // Same admission key as every V33 writer/transition, before any row/table data read.
        // requireAccountAccess is intentionally unsuitable here: inventory is also needed after grace.
        if (!Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT pg_catalog.pg_try_advisory_xact_lock_shared(pg_catalog.hashtextextended(?,0))",
                Boolean.class, WorkshopClosureGate.LOCK_PREFIX + tallerId))) throw new WorkshopClosureBusyException();
        var anchors = jdbc.query("""
                SELECT t.cierre_version,t.cierre_confirmado_en,t.cierre_reversible_hasta,
                       t.cierre_eliminacion_prevista_en,
                       (t.cierre_estado='RESTRINGIDO' AND t.cierre_referencia=? AND EXISTS (
                         SELECT 1 FROM public.cuenta_cierres h WHERE h.referencia=t.cierre_referencia
                           AND h.taller_id=t.id AND h.generacion=t.cierre_version AND h.estado='RESTRINGIDO'
                           AND h.politica='ordenfix-cierre/1' AND h.restaurado_en IS NULL
                           AND h.confirmado_en=t.cierre_confirmado_en
                           AND h.reversible_hasta=t.cierre_reversible_hasta
                           AND h.eliminacion_prevista_en=t.cierre_eliminacion_prevista_en
                           AND EXISTS (SELECT 1 FROM public.users u WHERE u.id=h.titular_id
                                       AND u.taller_id=t.id AND u.role='ADMIN'))) AS coherent
                FROM public.talleres t WHERE t.id=? FOR SHARE OF t
                """, (row, index) -> {
            if (!row.getBoolean("coherent") || row.getLong("cierre_version") <= 0)
                throw new Rejected(Rejected.Code.INVALID_REFERENCE);
            var schedule = new WorkshopClosurePolicy.Schedule(
                    row.getObject("cierre_confirmado_en", OffsetDateTime.class).toInstant(),
                    row.getObject("cierre_reversible_hasta", OffsetDateTime.class).toInstant(),
                    row.getObject("cierre_eliminacion_prevista_en", OffsetDateTime.class).toInstant());
            return new Anchor(row.getLong("cierre_version"), schedule);
        }, reference, tallerId);
        if (anchors.size() != 1) throw new Rejected(Rejected.Code.INVALID_REFERENCE);
        Anchor anchor = anchors.getFirst();
        Instant observedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (observedAt.isBefore(anchor.schedule().confirmedAt())) throw new Rejected(Rejected.Code.INVALID_REFERENCE);
        EnumMap<Metric, Long> counts = new EnumMap<>(Metric.class);
        Object[] parameters = Arrays.stream(Metric.values()).map(metric -> (Object) tallerId).toArray();
        jdbc.query(COUNTS_SQL, row -> {
            Metric metric = Metric.valueOf(row.getString(1));
            if (counts.put(metric, row.getLong(2)) != null) throw new Rejected(Rejected.Code.UNAVAILABLE);
        }, parameters);
        if (counts.size() != Metric.values().length) throw new Rejected(Rejected.Code.UNAVAILABLE);
        boolean globalPaymentEvents = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM public.payment_events)", Boolean.class));
        EnumMap<Category, CategoryObservation> categories = new EnumMap<>(Category.class);
        for (Category category : Category.values()) {
            EnumMap<Metric, Long> selected = new EnumMap<>(Metric.class);
            counts.forEach((metric, count) -> { if (metric.category == category) selected.put(metric, count); });
            boolean present = selected.values().stream().anyMatch(count -> count > 0);
            EnumSet<ReviewReason> reasons = EnumSet.of(ReviewReason.RETENTION_POLICY_REQUIRED);
            switch (category) {
                case IDENTITY, LEGAL_EVIDENCE, CLOSURE_HISTORY -> reasons.add(ReviewReason.IMMUTABLE_CONTRACT_REVIEW_REQUIRED);
                case PRIVATE_PHOTOS -> { if (present) reasons.add(ReviewReason.REMOTE_PHOTO_EVIDENCE_REQUIRED); }
                case LEGACY_PHOTOS -> { if (present) reasons.add(ReviewReason.LEGACY_REMOTE_OWNERSHIP_UNPROVEN); }
                case TEMPORARY_ACCESS_AND_EXPORTS -> { if (present) reasons.add(ReviewReason.TRANSIENT_CLEANUP_NOT_ATTESTED); }
                case SUBSCRIPTIONS_AND_EFFECTS -> {
                    reasons.add(ReviewReason.EXTERNAL_PROVIDER_RETENTION_UNRESOLVED);
                    if (counts.get(Metric.UNCONFIRMED_RENEWAL_EFFECTS) > 0 || counts.get(Metric.UNSETTLED_PROVIDER_LINKS) > 0)
                        reasons.add(ReviewReason.RENEWAL_COORDINATION_UNRESOLVED);
                    if (counts.get(Metric.UNCERTAIN_EFFECTS) > 0 || counts.get(Metric.EXPIRED_EFFECT_LEASES) > 0
                            || counts.get(Metric.CANCELLATIONS_WITHOUT_REMOTE_ID) > 0 || counts.get(Metric.EXHAUSTED_EFFECTS) > 0)
                        reasons.add(ReviewReason.EFFECT_RECONCILIATION_REQUIRED);
                }
                default -> { }
            }
            categories.put(category, new CategoryObservation(present ? Observation.LOCAL_ROWS_PRESENT : Observation.NO_LOCAL_ROWS,
                    selected, reasons));
        }
        EnumSet<ReviewReason> overall = EnumSet.of(ReviewReason.RETENTION_POLICY_REQUIRED,
                ReviewReason.BACKUP_RESTORE_EVIDENCE_REQUIRED);
        if (!anchor.schedule().graceExpiredAt(observedAt)) overall.add(ReviewReason.RESTORATION_WINDOW_ACTIVE);
        return new Report(tallerId, reference, anchor.generation(), observedAt, categories,
                new GlobalReview(globalPaymentEvents, ReviewReason.PAYMENT_EVENT_OWNERSHIP_UNRESOLVED), overall);
    }

    public enum Category { OPERATIONAL, IDENTITY, LEGAL_EVIDENCE, PRIVATE_PHOTOS, LEGACY_PHOTOS,
        TEMPORARY_ACCESS_AND_EXPORTS, SUBSCRIPTIONS_AND_EFFECTS, CLOSURE_HISTORY }
    /** NO_LOCAL_ROWS is a database observation only; neither value certifies deletion. */
    public enum Observation { LOCAL_ROWS_PRESENT, NO_LOCAL_ROWS }
    public enum ReviewReason { RETENTION_POLICY_REQUIRED, IMMUTABLE_CONTRACT_REVIEW_REQUIRED,
        REMOTE_PHOTO_EVIDENCE_REQUIRED, LEGACY_REMOTE_OWNERSHIP_UNPROVEN, TRANSIENT_CLEANUP_NOT_ATTESTED,
        EXTERNAL_PROVIDER_RETENTION_UNRESOLVED, RENEWAL_COORDINATION_UNRESOLVED, EFFECT_RECONCILIATION_REQUIRED,
        PAYMENT_EVENT_OWNERSHIP_UNRESOLVED, BACKUP_RESTORE_EVIDENCE_REQUIRED, RESTORATION_WINDOW_ACTIVE }

    /** Metrics include subsets (e.g. active users); values must not be summed as distinct records. */
    public enum Metric {
        CUSTOMERS(Category.OPERATIONAL, "clientes WHERE taller_id=?"),
        EQUIPMENT(Category.OPERATIONAL, "equipos WHERE taller_id=?"),
        REPAIRS(Category.OPERATIONAL, "reparaciones WHERE taller_id=?"),
        PARTS(Category.OPERATIONAL, "repuestos WHERE taller_id=?"),
        ARTICLES(Category.OPERATIONAL, "articulos WHERE taller_id=?"),
        BUDGETS(Category.OPERATIONAL, "presupuestos WHERE taller_id=?"),
        BUDGET_ITEMS(Category.OPERATIONAL, "presupuesto_items WHERE taller_id=?"),
        MANUAL_COLLECTION_RECORDS(Category.OPERATIONAL, "cobros WHERE taller_id=?"),
        WORKSHOP_QR(Category.OPERATIONAL, "taller_qr_cobro WHERE taller_id=?"),
        WORKSHOP(Category.IDENTITY, "talleres WHERE id=?"),
        USERS(Category.IDENTITY, "users WHERE taller_id=?"),
        ACTIVE_USERS(Category.IDENTITY, "users WHERE taller_id=? AND active"),
        ACCEPTANCE_BATCHES(Category.LEGAL_EVIDENCE, "legal_aceptacion_lotes WHERE taller_id=?"),
        ACCEPTANCES(Category.LEGAL_EVIDENCE, "legal_aceptaciones WHERE taller_id=?"),
        ACCEPTANCE_DOCUMENTS(Category.LEGAL_EVIDENCE, "legal_aceptacion_documentos d JOIN public.legal_aceptaciones a ON a.id=d.aceptacion_id WHERE a.taller_id=?"),
        ACCEPTANCE_METADATA(Category.LEGAL_EVIDENCE, "legal_aceptacion_metadatos m JOIN public.legal_aceptacion_lotes l ON l.id=m.lote_id WHERE l.taller_id=?"),
        ENCRYPTED_METADATA_ROWS(Category.LEGAL_EVIDENCE, "legal_aceptacion_metadatos_cifrados m JOIN public.legal_aceptacion_lotes l ON l.id=m.lote_id WHERE l.taller_id=?"),
        LEGAL_IDEMPOTENCY(Category.LEGAL_EVIDENCE, "legal_idempotencia_resultados WHERE taller_id=?"),
        LEGAL_NO_ACT_RESULTS(Category.LEGAL_EVIDENCE, "legal_idempotencia_sin_actos WHERE taller_id=?"),
        LEGAL_NO_ACT_REFERENCES(Category.LEGAL_EVIDENCE, "legal_idempotencia_sin_actos_referencias WHERE taller_id=?"),
        PRIVATE_PHOTO_ROWS(Category.PRIVATE_PHOTOS, "reparacion_fotos_privadas WHERE taller_id=?"),
        PRIVATE_PHOTOS_NOT_MARKED_DELETED(Category.PRIVATE_PHOTOS, "reparacion_fotos_privadas WHERE taller_id=? AND estado<>'ELIMINADA'"),
        PRIVATE_PHOTOS_PENDING_CLEANUP(Category.PRIVATE_PHOTOS, "reparacion_fotos_privadas WHERE taller_id=? AND estado='LIMPIEZA_PENDIENTE'"),
        PRIVATE_PHOTOS_WITH_LIVE_LEASE(Category.PRIVATE_PHOTOS, "reparacion_fotos_privadas WHERE taller_id=? AND lease_hasta>CURRENT_TIMESTAMP"),
        PRIVATE_PHOTOS_WITH_ASSET_ID(Category.PRIVATE_PHOTOS, "reparacion_fotos_privadas WHERE taller_id=? AND asset_id IS NOT NULL"),
        PHOTO_ATTESTATIONS(Category.PRIVATE_PHOTOS, "reparacion_foto_atestaciones WHERE taller_id=?"),
        LEGACY_PHOTO_ROWS(Category.LEGACY_PHOTOS, "reparacion_fotos WHERE taller_id=?"),
        EXPORT_JOBS(Category.TEMPORARY_ACCESS_AND_EXPORTS, "cuenta_exportaciones WHERE taller_id=?"),
        NONTERMINAL_EXPORT_JOBS(Category.TEMPORARY_ACCESS_AND_EXPORTS, "cuenta_exportaciones WHERE taller_id=? AND estado IN ('QUEUED','RUNNING','READY')"),
        AUTH_TOKENS(Category.TEMPORARY_ACCESS_AND_EXPORTS, "auth_tokens WHERE taller_id=?"),
        EXPORT_REAUTHENTICATIONS(Category.TEMPORARY_ACCESS_AND_EXPORTS, "cuenta_reautenticaciones WHERE taller_id=?"),
        UNUSED_CLOSURE_CONFIRMATIONS(Category.TEMPORARY_ACCESS_AND_EXPORTS, "cuenta_cierre_confirmaciones WHERE taller_id=? AND usada_en IS NULL"),
        SUBSCRIPTIONS(Category.SUBSCRIPTIONS_AND_EFFECTS, "suscripciones WHERE taller_id=?"),
        PROVIDER_LINKS(Category.SUBSCRIPTIONS_AND_EFFECTS, "subscription_provider_links l JOIN public.suscripciones s ON s.id=l.suscripcion_id WHERE s.taller_id=?"),
        UNSETTLED_PROVIDER_LINKS(Category.SUBSCRIPTIONS_AND_EFFECTS, "subscription_provider_links l JOIN public.suscripciones s ON s.id=l.suscripcion_id WHERE s.taller_id=? AND lower(btrim(coalesce(l.status,''))) NOT IN ('canceled','cancelled')"),
        SUBSCRIPTION_PAYMENTS(Category.SUBSCRIPTIONS_AND_EFFECTS, "subscription_payments p JOIN public.suscripciones s ON s.id=p.suscripcion_id WHERE s.taller_id=?"),
        CLOSURE_EFFECTS(Category.SUBSCRIPTIONS_AND_EFFECTS, "cuenta_cierre_efectos WHERE taller_id=?"),
        UNCONFIRMED_RENEWAL_EFFECTS(Category.SUBSCRIPTIONS_AND_EFFECTS, "cuenta_cierre_efectos WHERE taller_id=? AND tipo IN ('CANCELAR_RENOVACION','REVISAR_RENOVACION') AND estado<>'CONFIRMADO'"),
        UNCERTAIN_EFFECTS(Category.SUBSCRIPTIONS_AND_EFFECTS, "cuenta_cierre_efectos WHERE taller_id=? AND estado='INCIERTO'"),
        EXPIRED_EFFECT_LEASES(Category.SUBSCRIPTIONS_AND_EFFECTS, "cuenta_cierre_efectos WHERE taller_id=? AND estado='EN_CURSO' AND lease_until<=CURRENT_TIMESTAMP"),
        CANCELLATIONS_WITHOUT_REMOTE_ID(Category.SUBSCRIPTIONS_AND_EFFECTS, "cuenta_cierre_efectos WHERE taller_id=? AND tipo='CANCELAR_RENOVACION' AND expected_external_id IS NULL"),
        EXHAUSTED_EFFECTS(Category.SUBSCRIPTIONS_AND_EFFECTS, "cuenta_cierre_efectos WHERE taller_id=? AND intentos>=10 AND estado<>'CONFIRMADO'"),
        CLOSURE_HISTORY_ROWS(Category.CLOSURE_HISTORY, "cuenta_cierres WHERE taller_id=?"),
        CLOSURE_OPERATIONS(Category.CLOSURE_HISTORY, "cuenta_cierre_operaciones WHERE taller_id=?"),
        USED_CLOSURE_CONFIRMATIONS(Category.CLOSURE_HISTORY, "cuenta_cierre_confirmaciones WHERE taller_id=? AND usada_en IS NOT NULL");
        private final Category category;
        private final String from;
        Metric(Category category, String from) { this.category = category; this.from = from; }
    }
    private static final String COUNTS_SQL = Arrays.stream(Metric.values())
            .map(metric -> "SELECT '" + metric.name() + "' AS metric,count(*) AS rows FROM public." + metric.from)
            .collect(Collectors.joining(" UNION ALL "));

    public record CategoryObservation(Observation observation, Map<Metric, Long> counts, Set<ReviewReason> reviewReasons) {
        public CategoryObservation {
            Objects.requireNonNull(observation);
            counts = Map.copyOf(counts); reviewReasons = Set.copyOf(reviewReasons);
            if (counts.isEmpty() || counts.values().stream().anyMatch(count -> count < 0)
                    || (observation == Observation.NO_LOCAL_ROWS) != counts.values().stream().allMatch(count -> count == 0))
                throw new IllegalArgumentException("Observación de inventario inválida.");
        }
        @Override public String toString() { return "CategoryObservation[redacted]"; }
    }
    /** Global scope: presence is not attributed to the requested workshop, even if identifiers match. */
    public record GlobalReview(boolean paymentEventsPresent, ReviewReason reviewReason) {
        public GlobalReview {
            if (reviewReason != ReviewReason.PAYMENT_EVENT_OWNERSHIP_UNRESOLVED)
                throw new IllegalArgumentException("Revisión global inválida.");
        }
        @Override public String toString() { return "GlobalReview[redacted]"; }
    }
    public record Report(long tallerId, UUID closureReference, long generation, Instant observedAt,
                         Map<Category, CategoryObservation> categories, GlobalReview globalReview,
                         Set<ReviewReason> reviewReasons) {
        public Report {
            if (tallerId <= 0 || generation <= 0 || closureReference == null || observedAt == null)
                throw new IllegalArgumentException("Inventario de cierre inválido.");
            categories = Map.copyOf(categories); reviewReasons = Set.copyOf(reviewReasons);
            Objects.requireNonNull(globalReview);
            if (categories.size() != Category.values().length) throw new IllegalArgumentException("Inventario incompleto.");
        }
        public long count(Metric metric) { return categories.get(metric.category).counts().get(metric); }
        @Override public String toString() { return "WorkshopClosureDeletionInventory.Report[redacted]"; }
    }
    private record Anchor(long generation, WorkshopClosurePolicy.Schedule schedule) { }
    public static final class Rejected extends RuntimeException {
        public enum Code { INVALID_REFERENCE, UNAVAILABLE }
        private final Code code;
        public Rejected(Code code) { super("No se pudo observar el inventario del cierre."); this.code = Objects.requireNonNull(code); }
        public Code code() { return code; }
    }
}
