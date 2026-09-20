package com.leonardorozza.mvgrreparacionesbackend.cuenta.recovery;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

import static com.leonardorozza.mvgrreparacionesbackend.cuenta.recovery.RecoveryCheckpoint.*;

/** Maintenance-only reader: no Spring component, HTTP entry, migration, writes or provider calls. */
public final class RecoverySnapshotReader {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private static final Map<Surface, String> QUERIES = queries();

    public RecoverySnapshotReader(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = Objects.requireNonNull(jdbc);
        transaction = new TransactionTemplate(Objects.requireNonNull(manager));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setReadOnly(true);
        transaction.setTimeout(30);
    }

    public Snapshot capture(UUID environmentId, UUID checkpointId) {
        if (environmentId == null || checkpointId == null) throw new Rejected(Rejected.Code.INVALID_TARGET);
        try {
            return Objects.requireNonNull(transaction.execute(status -> {
                jdbc.execute("SET TRANSACTION READ ONLY");
                jdbc.execute("SET LOCAL row_security=off");
                jdbc.execute("SET LOCAL search_path=pg_catalog, public, pg_temp");
                jdbc.execute("SET LOCAL statement_timeout='5s'");
                jdbc.execute("SET LOCAL lock_timeout='2s'");
                jdbc.execute("SET LOCAL TIME ZONE 'UTC'");
                jdbc.execute("SET LOCAL DateStyle='ISO, YMD'");
                jdbc.execute("SET LOCAL IntervalStyle='postgres'");
                jdbc.execute("SET LOCAL bytea_output='hex'");
                jdbc.execute("SET LOCAL extra_float_digits=3");
                // First query pins the PostgreSQL RR snapshot. Wall time is informative, not a freshness proof.
                var observed = Objects.requireNonNull(jdbc.queryForObject("SELECT statement_timestamp()",
                        OffsetDateTime.class)).toInstant();
                RecoveryReadSchemaPreflight.require(jdbc);
                var ids = jdbc.queryForList("SELECT id FROM public.talleres ORDER BY id LIMIT " + (MAX_WORKSHOPS + 1), Long.class);
                if (ids.size() > MAX_WORKSHOPS) throw new Rejected(Rejected.Code.CAPACITY_EXCEEDED);
                var collected = new TreeMap<Long, EnumMap<Surface, Digest>>();
                ids.forEach(id -> collected.put(id, new EnumMap<>(Surface.class)));
                for (Surface surface : Surface.values()) {
                    var rows = jdbc.query(QUERIES.get(surface), (row, index) -> new Row(
                            row.getObject(1, Long.class), row.getString(2)));
                    if (rows.size() > MAX_ROWS_PER_SURFACE) throw new Rejected(Rejected.Code.CAPACITY_EXCEEDED);
                    Map<Long, List<String>> grouped = new TreeMap<>();
                    for (Row row : rows) {
                        if (row.workshop() == null || !collected.containsKey(row.workshop())
                                || row.digest() == null || !row.digest().matches("[0-9a-f]{64}"))
                            throw new Rejected(Rejected.Code.UNAVAILABLE);
                        grouped.computeIfAbsent(row.workshop(), key -> new ArrayList<>()).add(row.digest());
                    }
                    collected.forEach((id, surfaces) -> surfaces.put(surface, digest(surface, grouped.getOrDefault(id, List.of()))));
                }
                return new Snapshot(environmentId, checkpointId, observed, collected.entrySet().stream()
                        .map(entry -> new Workshop(entry.getKey(), entry.getValue())).toList());
            }));
        } catch (Rejected rejected) { throw rejected;
        } catch (RuntimeException failure) { throw new Rejected(Rejected.Code.UNAVAILABLE); }
    }

    private static Digest digest(Surface surface, List<String> rows) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(("ordenfix-recovery/1:" + surface.name() + ":" + rows.size() + ":")
                    .getBytes(StandardCharsets.US_ASCII));
            // Sorting logical row hashes preserves duplicates and excludes physical xmin/ctid/OID identities.
            rows.stream().sorted().forEach(row -> digest.update(HexFormat.of().parseHex(row)));
            return new Digest(rows.size(), HexFormat.of().formatHex(digest.digest()));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(); }
    }

    private static Map<Surface, String> queries() {
        var queries = new EnumMap<Surface, String>(Surface.class);
        queries.put(Surface.WORKSHOP, query("""
                SELECT id AS taller_id, id, activo, cierre_estado, cierre_version, cierre_referencia,
                       cierre_confirmado_en, cierre_reversible_hasta, cierre_eliminacion_prevista_en FROM public.talleres
                """));
        queries.put(Surface.USERS, query("""
                SELECT taller_id, id, role, active, email_verificado, token_version FROM public.users
                """));
        queries.put(Surface.CLOSURES, query("SELECT * FROM public.cuenta_cierres"));
        queries.put(Surface.OPERATIONS, query("""
                SELECT taller_id, operacion_id, user_id, proposito, cierre_referencia, cierre_version,
                       estado_resultante, politica, confirmado_en, reversible_hasta, eliminacion_prevista_en,
                       registrada_en FROM public.cuenta_cierre_operaciones
                """));
        queries.put(Surface.DELETION_BATCHES, query("SELECT * FROM public.cuenta_borrado_lotes"));
        queries.put(Surface.ITEMS, query("SELECT * FROM public.presupuesto_items"));
        queries.put(Surface.PRESUPUESTOS, query("SELECT * FROM public.presupuestos"));
        queries.put(Surface.COBROS, query("SELECT * FROM public.cobros"));
        queries.put(Surface.REPUESTOS, query("SELECT * FROM public.repuestos"));
        queries.put(Surface.REPARACIONES, query("SELECT * FROM public.reparaciones"));
        queries.put(Surface.EQUIPOS, query("SELECT * FROM public.equipos"));
        queries.put(Surface.CLIENTES, query("SELECT * FROM public.clientes"));
        queries.put(Surface.ARTICULOS, query("SELECT * FROM public.articulos"));
        return Map.copyOf(queries);
    }

    /** Only fixed source SQL above reaches this builder. Raw business rows never leave PostgreSQL. */
    private static String query(String source) {
        return "SELECT r.taller_id, encode(sha256(convert_to(to_jsonb(r)::text,'UTF8')),'hex') FROM ("
                + source + ") r LIMIT " + (MAX_ROWS_PER_SURFACE + 1);
    }
    private record Row(Long workshop, String digest) { }

    public static final class Rejected extends RuntimeException {
        public enum Code { INVALID_TARGET, CAPACITY_EXCEEDED, UNAVAILABLE }
        private final Code code;
        private Rejected(Code code) { super("No se pudo capturar el checkpoint de recuperación."); this.code = code; }
        public Code code() { return code; }
    }
}
