package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

/** Applies only expiration already recorded in V27/V29. No keyring, new retention policy or HTTP entry. */
public final class LegalAcceptanceRetentionService {
    static final int TARGETS_PER_CATEGORY = 10;
    private static final String TUPLE_KEY_SQL = """
            SELECT pg_catalog.hashtextextended(pg_catalog.jsonb_build_array(
              'ordenfix:legal-idempotencia:tupla:v29',?::text,?::text,?::text,?::text)::text,0)
            """;
    private final JdbcTemplate jdbc;
    private final LegalAcceptanceMaintenanceBoundary boundary;

    public LegalAcceptanceRetentionService(JdbcTemplate jdbc, LegalAcceptanceMaintenanceBoundary boundary) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.boundary = Objects.requireNonNull(boundary);
        if (!boundary.usesJdbc(jdbc)) throw new LegalAcceptanceMaintenanceException(
                LegalAcceptanceMaintenanceException.Code.INVALID_BOUNDARY);
    }

    /** At most ten headers and ten parents from each ledger; busy targets remain for a later invocation. */
    public Batch runNext() { return boundary.execute(this::sweep); }

    private Batch sweep(LegalPrivateRequirementsDeadline deadline) {
        Selection selected = select(deadline);
        List<Target> targets = selected.targets();
        if (targets.isEmpty()) return new Batch(0, 0, 0, 0, 0, 0, false);
        Set<Long> admitted = new HashSet<>();
        for (long taller : targets.stream().map(Target::taller).distinct().sorted().toList()) {
            deadline.check();
            if (Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT pg_catalog.pg_try_advisory_xact_lock_shared(pg_catalog.hashtextextended(?,0))",
                    Boolean.class, "ordenfix:closure:" + taller))) {
                // Maintenance remains valid for inactive/restricted workshops, including after grace.
                if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM public.talleres WHERE id=?)", Boolean.class, taller)))
                    throw unavailable();
                admitted.add(taller);
            }
        }
        Set<Long> tupleLocks = new HashSet<>();
        for (long lock : targets.stream().filter(target -> admitted.contains(target.taller()) && target.tuple() != null)
                .map(Target::physicalKey).distinct().sorted(Long::compareUnsigned).toList()) {
            deadline.check();
            if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_catalog.pg_try_advisory_xact_lock(?::bigint)", Boolean.class, lock)))
                tupleLocks.add(lock);
        }
        List<Target> ready = targets.stream().filter(target -> admitted.contains(target.taller())
                && (target.tuple() == null || tupleLocks.contains(target.physicalKey()))).toList();
        long skipped = targets.size() - ready.size();
        if (ready.isEmpty()) return new Batch(0, 0, 0, 0, 0, skipped, true);
        deadline.check();
        // Historical canonical snapshots remain readable after REPLACE/RETIRE; no current-aggregate check.
        if (!Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT pg_catalog.pg_try_advisory_xact_lock_shared(pg_catalog.hashtextextended(?,0))",
                Boolean.class, LegalManifestDatabaseGate.EDITORIAL_LOCK_NAME)))
            return new Batch(0, 0, 0, 0, 0, targets.size(), true);
        long headers = 0, fields = 0, withActs = 0, withoutActs = 0, references = 0;
        for (Target target : ready) {
            deadline.check();
            if (target.kind() == Kind.METADATA) {
                int changed = purgeMetadata(target, deadline);
                if (changed == 0) skipped++;
                else { headers++; fields += changed; }
            } else {
                int changed = purgeResult(target, deadline);
                if (changed < 0) skipped++;
                else if (target.kind() == Kind.WITH_ACTS) withActs++;
                else { withoutActs++; references += changed; }
            }
        }
        deadline.check();
        jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
        deadline.check();
        return new Batch(headers, fields, withActs, withoutActs, references, skipped, selected.more() || skipped > 0);
    }

    private Selection select(LegalPrivateRequirementsDeadline deadline) {
        deadline.check();
        List<Target> targets = new ArrayList<>();
        List<Target> metadata = jdbc.query("""
                SELECT m.lote_id,l.taller_id FROM public.legal_aceptacion_metadatos m
                JOIN public.legal_aceptacion_lotes l ON l.id=m.lote_id
                WHERE m.purgado_en IS NULL AND m.retener_hasta<=transaction_timestamp()
                ORDER BY m.retener_hasta,m.lote_id LIMIT 11
                """, (row, index) -> new Target(Kind.METADATA, row.getObject(1, UUID.class), row.getLong(2), 0, null, 0));
        boolean more = addBounded(targets, metadata);
        for (Kind kind : List.of(Kind.WITH_ACTS, Kind.WITHOUT_ACTS)) {
            deadline.check();
            List<Target> rows = jdbc.query("SELECT id,taller_id,user_id,operacion,route_template,scope_hmac,idempotency_key_hmac FROM public."
                    + kind.table + " WHERE expires_at<=transaction_timestamp() ORDER BY expires_at,id LIMIT 11", (row, index) -> {
                Tuple tuple = new Tuple(row.getString("operacion"), row.getString("route_template"), row.getString("scope_hmac"), row.getString("idempotency_key_hmac"));
                return new Target(kind, kind == Kind.WITH_ACTS ? row.getLong("id") : row.getObject("id", UUID.class),
                        row.getLong("taller_id"), row.getLong("user_id"), tuple, 0);
            });
            more |= rows.size() > TARGETS_PER_CATEGORY;
            for (Target target : rows.stream().limit(TARGETS_PER_CATEGORY).toList()) {
                deadline.check(); Tuple tuple = target.tuple();
                Long physical = jdbc.queryForObject(TUPLE_KEY_SQL, Long.class, tuple.operation(), tuple.route(), tuple.scope(), tuple.key());
                if (physical == null) throw unavailable();
                targets.add(new Target(kind, target.id(), target.taller(), target.user(), tuple, physical));
            }
        }
        return new Selection(List.copyOf(targets), more);
    }

    private static boolean addBounded(List<Target> targets, List<Target> candidates) {
        targets.addAll(candidates.stream().limit(TARGETS_PER_CATEGORY).toList());
        return candidates.size() > TARGETS_PER_CATEGORY;
    }

    private int purgeMetadata(Target target, LegalPrivateRequirementsDeadline deadline) {
        var headers = jdbc.queryForList("""
                SELECT m.lote_id FROM public.legal_aceptacion_metadatos m
                JOIN public.legal_aceptacion_lotes l ON l.id=m.lote_id
                JOIN public.talleres t ON t.id=l.taller_id
                WHERE m.lote_id=? AND l.taller_id=? AND m.purgado_en IS NULL
                  AND m.retener_hasta<=transaction_timestamp() FOR UPDATE OF m SKIP LOCKED
                """, UUID.class, target.id(), target.taller());
        if (headers.isEmpty()) return 0;
        if (headers.size() != 1) throw unavailable();
        deadline.check();
        var fields = jdbc.query("SELECT tipo,tombstone_en IS NULL AS active FROM public.legal_aceptacion_metadatos_cifrados WHERE lote_id=? LIMIT 3",
                (row, index) -> new Field(row.getString("tipo"), row.getBoolean("active")), target.id());
        if (fields.isEmpty() || fields.size() > 2 || fields.stream().anyMatch(field -> !field.active())
                || fields.stream().filter(field -> "IP".equals(field.type())).count() != 1
                || fields.stream().filter(field -> "USER_AGENT".equals(field.type())).count() != fields.size() - 1)
            throw unavailable();
        int changed = jdbc.update("""
                UPDATE public.legal_aceptacion_metadatos_cifrados
                   SET ciphertext=NULL,tag=NULL,longitud_original=NULL,tombstone_en=transaction_timestamp()
                 WHERE lote_id=? AND tombstone_en IS NULL
                """, target.id());
        if (changed != fields.size()) throw unavailable();
        deadline.check();
        if (jdbc.update("""
                UPDATE public.legal_aceptacion_metadatos SET purgado_en=transaction_timestamp()
                 WHERE lote_id=? AND purgado_en IS NULL AND retener_hasta<=transaction_timestamp()
                """, target.id()) != 1) throw unavailable();
        return changed;
    }

    /** Returns deleted reference count, or -1 when the candidate disappeared or is row-locked. */
    private int purgeResult(Target target, LegalPrivateRequirementsDeadline deadline) {
        Tuple tuple = target.tuple();
        String shape = target.kind() == Kind.WITH_ACTS ? "0 AS referencia_count,'NEW' AS resultado" : "referencia_count,resultado";
        var rows = jdbc.query("SELECT " + shape + " FROM public." + target.kind().table
                + " WHERE id=? AND taller_id=? AND user_id=? AND operacion=? AND route_template=?"
                + " AND scope_hmac=? AND idempotency_key_hmac=? AND expires_at<=transaction_timestamp() FOR UPDATE SKIP LOCKED",
                (row, index) -> new Shape(row.getInt("referencia_count"), row.getString("resultado")),
                target.id(), target.taller(), target.user(), tuple.operation(), tuple.route(), tuple.scope(), tuple.key());
        if (rows.isEmpty()) return -1;
        if (rows.size() != 1) throw unavailable();
        deadline.check();
        int removedReferences = 0;
        if (target.kind() == Kind.WITHOUT_ACTS) {
            Shape shapeValue = rows.getFirst();
            if (!("EMPTY".equals(shapeValue.result()) && shapeValue.references() == 0)
                    && !("DEDUP".equals(shapeValue.result()) && shapeValue.references() >= 1 && shapeValue.references() <= 2048)) throw unavailable();
            var references = jdbc.query("""
                    SELECT taller_id,user_id FROM public.legal_idempotencia_sin_actos_referencias
                     WHERE resultado_id=? LIMIT 2049
                    """, (row, index) -> new Membership(row.getLong(1), row.getLong(2)), target.id());
            if (references.size() != shapeValue.references() || references.stream().anyMatch(
                    row -> row.taller() != target.taller() || row.user() != target.user())) throw unavailable();
            deadline.check();
            removedReferences = jdbc.update("DELETE FROM public.legal_idempotencia_sin_actos_referencias WHERE resultado_id=? AND taller_id=? AND user_id=?",
                    target.id(), target.taller(), target.user());
            if (removedReferences != references.size()) throw unavailable();
        }
        deadline.check();
        if (jdbc.update("DELETE FROM public." + target.kind().table + " WHERE id=? AND taller_id=? AND user_id=? AND expires_at<=transaction_timestamp()",
                target.id(), target.taller(), target.user()) != 1) throw unavailable();
        return removedReferences;
    }

    /** Counters are returned only after the isolated boundary confirms commit and connection release. */
    public record Batch(long metadataHeaders, long metadataFields, long resultsWithActs, long resultsWithoutActs,
                        long references, long skipped, boolean pending) {
        public Batch {
            if (metadataHeaders < 0 || metadataHeaders > TARGETS_PER_CATEGORY || metadataFields < metadataHeaders
                    || metadataFields > metadataHeaders * 2 || resultsWithActs < 0 || resultsWithActs > TARGETS_PER_CATEGORY
                    || resultsWithoutActs < 0 || resultsWithoutActs > TARGETS_PER_CATEGORY || references < 0
                    || references > resultsWithoutActs * 2048 || skipped < 0
                    || metadataHeaders + resultsWithActs + resultsWithoutActs + skipped > TARGETS_PER_CATEGORY * 3)
                throw new IllegalArgumentException("Resultado de mantenimiento inválido.");
        }
        @Override public String toString() { return "LegalAcceptanceRetentionService.Batch[redacted]"; }
    }
    private enum Kind {
        METADATA("legal_aceptacion_metadatos"), WITH_ACTS("legal_idempotencia_resultados"), WITHOUT_ACTS("legal_idempotencia_sin_actos");
        private final String table;
        Kind(String table) { this.table = table; }
    }
    private record Tuple(String operation, String route, String scope, String key) {
        private Tuple { if (operation == null || route == null || scope == null || key == null) throw unavailable(); }
        @Override public String toString() { return "Tuple[redacted]"; }
    }
    private record Target(Kind kind, Object id, long taller, long user, Tuple tuple, long physicalKey) {
        private Target { if (kind == null || id == null || taller <= 0 || (kind != Kind.METADATA && (tuple == null || user <= 0))) throw unavailable(); }
        @Override public String toString() { return "Target[redacted]"; }
    }
    private record Selection(List<Target> targets, boolean more) { }
    private record Field(String type, boolean active) { }
    private record Shape(int references, String result) { }
    private record Membership(long taller, long user) { }
    private static LegalAcceptanceMaintenanceException unavailable() {
        return new LegalAcceptanceMaintenanceException(LegalAcceptanceMaintenanceException.Code.UNAVAILABLE);
    }
}
