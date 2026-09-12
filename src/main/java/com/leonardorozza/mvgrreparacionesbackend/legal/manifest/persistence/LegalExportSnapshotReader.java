package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalActorSnapshot;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Internal bridge: no HTTP, no second DataSource/transaction and no expanded employee-history access. */
public final class LegalExportSnapshotReader {
    public static final class CapacityExceededException extends RuntimeException {
        private CapacityExceededException() { super("El historial supera la capacidad de exportación."); }
    }
    private final LegalAcceptanceHistoryReader reader;
    private final LegalV29AcceptanceSchemaVerifier schema;
    public LegalExportSnapshotReader(JdbcTemplate jdbc) { reader = new LegalAcceptanceHistoryReader(jdbc); schema = new LegalV29AcceptanceSchemaVerifier(jdbc, "public"); }
    public List<LegalAcceptanceHistoryPage.Acceptance> read(LegalActorSnapshot actor, Instant observedAt) {
        actor.requireEnabled();
        if (actor.role() != UserRole.ADMIN) throw new LegalAcceptanceHistoryReadException();
        schema.verify();
        var deadline = new LegalPrivateRequirementsDeadline(Duration.ofSeconds(15));
        var boundary = new LegalEditorialTimeBoundary(observedAt, observedAt);
        var result = new ArrayList<LegalAcceptanceHistoryPage.Acceptance>();
        long expected = -1;
        for (int page = 0; ; page++) {
            var batch = reader.readExportSnapshot(actor, page, 100, boundary, deadline);
            if (batch.totalElements() > 5000) throw new CapacityExceededException();
            if (expected >= 0 && batch.totalElements() != expected) {
                throw new LegalAcceptanceHistoryReadException();
            }
            expected = batch.totalElements();
            result.addAll(batch.content());
            if (page + 1 >= batch.totalPages()) break;
        }
        if (result.size() != expected) throw new LegalAcceptanceHistoryReadException();
        return List.copyOf(result);
    }
}
