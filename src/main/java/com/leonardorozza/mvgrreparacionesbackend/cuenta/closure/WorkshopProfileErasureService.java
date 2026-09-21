package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalV38ProfileErasureSchema;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/** Removes local account profiles only. Retained identifiers and evidence are not anonymous or erased. */
@Service
public final class WorkshopProfileErasureService {
    public static final String ENABLED_PROPERTY = "ordenfix.cuenta.cierre.profile-erasure-enabled";
    public static final int MAX_USERS = 1_000;
    private final JdbcTemplate jdbc;
    private final boolean enabled;
    private final TransactionTemplate transaction;

    public WorkshopProfileErasureService(JdbcTemplate jdbc, PlatformTransactionManager manager,
            @Value("${ordenfix.cuenta.cierre.profile-erasure-enabled:false}") boolean enabled) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.enabled = enabled;
        transaction = new TransactionTemplate(Objects.requireNonNull(manager));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(10);
    }

    /** Trusted internal entry only. Identifiers are not HTTP authorization or an instruction to run on real accounts. */
    public Receipt suppress(long tallerId, UUID closureReference, UUID operationId) {
        if (tallerId <= 0 || closureReference == null || operationId == null) throw rejected(Rejected.Code.INVALID_TARGET);
        if (!enabled) throw rejected(Rejected.Code.DISABLED);
        try {
            return Objects.requireNonNull(transaction.execute(status -> {
                jdbc.execute("SET LOCAL statement_timeout='5s'");
                new WorkshopClosureGate(jdbc).lockExclusive(tallerId);
                LegalV38ProfileErasureSchema.require(jdbc);
                Receipt receipt = jdbc.queryForObject("""
                        WITH result AS MATERIALIZED (
                          SELECT public.cuenta_cierre_suprimir_perfil_v38(?,?,?) AS outcome)
                        SELECT outcome->>'status' AS status,(outcome->>'receiptId')::uuid AS receipt_id,
                               (outcome->>'users')::integer AS users,(outcome->>'qrRemoved')::boolean AS qr_removed,
                               (outcome->>'suppressedAt')::timestamptz AS suppressed_at FROM result
                        """, (row, index) -> {
                    int users = row.getInt("users");
                    if (row.wasNull()) throw rejected(Rejected.Code.UNAVAILABLE);
                    boolean qr = row.getBoolean("qr_removed");
                    if (row.wasNull()) throw rejected(Rejected.Code.UNAVAILABLE);
                    var when = row.getObject("suppressed_at", OffsetDateTime.class);
                    return new Receipt(Status.valueOf(row.getString("status")), row.getObject("receipt_id", UUID.class),
                            users, qr, when == null ? null : when.toInstant());
                }, operationId, tallerId, closureReference);
                if (receipt == null || (receipt.status() == Status.SUPPRESSED && !operationId.equals(receipt.receiptId())))
                    throw rejected(Rejected.Code.UNAVAILABLE);
                return receipt;
            }));
        } catch (Rejected failure) { throw failure; }
        catch (RuntimeException failure) { throw rejected(Rejected.Code.UNAVAILABLE); }
    }

    public enum Status { SUPPRESSED, REUSED }
    public record Receipt(Status status, UUID receiptId, int users, boolean qrRemoved, Instant suppressedAt) {
        public Receipt {
            if (status == null || receiptId == null || users < 1 || users > MAX_USERS || suppressedAt == null)
                throw new IllegalArgumentException("Invalid profile erasure receipt");
        }
        @Override public String toString() { return "ProfileErasureReceipt[redacted]"; }
    }
    private static Rejected rejected(Rejected.Code code) { return new Rejected(code); }
    public static final class Rejected extends RuntimeException {
        public enum Code { INVALID_TARGET, DISABLED, UNAVAILABLE }
        private final Code code;
        private Rejected(Code code) { super("No se pudo completar la supresión del perfil de cuenta."); this.code = code; }
        public Code code() { return code; }
    }
}
