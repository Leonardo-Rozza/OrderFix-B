package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalV37OperationalDeletionSchema;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.UUID;

/** Internal, opt-in operational erasure. It never represents complete account deletion. */
@Service
public final class WorkshopOperationalDeletionService {
    public static final String ENABLED_PROPERTY = "ordenfix.cuenta.cierre.operational-deletion-enabled";
    public static final int BATCH_SIZE = 25;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final boolean enabled;

    public WorkshopOperationalDeletionService(JdbcTemplate jdbc, PlatformTransactionManager manager,
            @Value("${ordenfix.cuenta.cierre.operational-deletion-enabled:false}") boolean enabled) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.enabled = enabled;
        transaction = new TransactionTemplate(Objects.requireNonNull(manager));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(10);
    }

    /** Trusted internal orchestration only: these identifiers do not authorize an HTTP caller. */
    public Batch deleteBatch(long tallerId, UUID closureReference, UUID batchId, Category category) {
        if (tallerId <= 0 || closureReference == null || batchId == null || category == null)
            throw new Rejected(Rejected.Code.INVALID_TARGET);
        if (!enabled) throw new Rejected(Rejected.Code.DISABLED);
        try {
            return Objects.requireNonNull(transaction.execute(status -> {
                jdbc.execute("SET LOCAL statement_timeout='5s'");
                new WorkshopClosureGate(jdbc).lockExclusive(tallerId);
                LegalV37OperationalDeletionSchema.require(jdbc);
                Batch result = jdbc.queryForObject("""
                        WITH result AS MATERIALIZED (
                          SELECT public.cuenta_cierre_borrar_lote_v37(?,?,?,?) AS outcome)
                        SELECT outcome->>'status' AS status,outcome->>'category' AS category,
                               (outcome->>'deleted')::integer AS deleted,
                               (outcome->>'remaining')::boolean AS remaining,
                               (outcome->>'receiptId')::uuid AS receipt_id
                          FROM result
                        """, (row, index) -> {
                    int deleted = row.getInt("deleted");
                    if (row.wasNull()) throw new Rejected(Rejected.Code.UNAVAILABLE);
                    boolean remaining = row.getBoolean("remaining");
                    if (row.wasNull()) throw new Rejected(Rejected.Code.UNAVAILABLE);
                    return new Batch(Status.valueOf(row.getString("status")),
                            Category.valueOf(row.getString("category")), deleted, remaining,
                            row.getObject("receipt_id", UUID.class));
                }, batchId, tallerId, closureReference, category.name());
                if (result == null || result.category() != category
                        || (result.status() != Status.EMPTY && !batchId.equals(result.receiptId())))
                    throw new Rejected(Rejected.Code.UNAVAILABLE);
                return result;
            }));
        } catch (Rejected failure) {
            throw failure;
        } catch (RuntimeException failure) {
            // No SQL, identifiers, row content or nested provider/database causes cross this boundary.
            throw new Rejected(Rejected.Code.UNAVAILABLE);
        }
    }

    /** Dependency order for a trusted caller; each invocation commits at most 25 selected rows. */
    public enum Category { ITEMS, PRESUPUESTOS, COBROS, REPUESTOS, REPARACIONES, EQUIPOS, CLIENTES, ARTICULOS }
    public enum Status { DELETED, REUSED, EMPTY }

    /** remaining concerns this category at the original observation, never complete erasure. */
    public record Batch(Status status, Category category, int deleted, boolean remaining, UUID receiptId) {
        public Batch {
            Objects.requireNonNull(status);
            Objects.requireNonNull(category);
            if (deleted < 0 || deleted > BATCH_SIZE
                    || (status == Status.EMPTY ? deleted != 0 || receiptId != null : deleted == 0 || receiptId == null))
                throw new IllegalArgumentException("Invalid operational deletion result");
        }
        @Override public String toString() { return "OperationalDeletionBatch[redacted]"; }
    }

    public static final class Rejected extends RuntimeException {
        public enum Code { INVALID_TARGET, DISABLED, UNAVAILABLE }
        private final Code code;
        private Rejected(Code code) { super("No se pudo completar el borrado operativo del cierre."); this.code = code; }
        public Code code() { return code; }
    }
}
