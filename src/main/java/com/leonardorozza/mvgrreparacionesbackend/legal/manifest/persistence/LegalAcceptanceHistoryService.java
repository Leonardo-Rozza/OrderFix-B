package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalActorSnapshot;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.Objects;

/** Own historical evidence under the shared account boundary; never materializes current requirements. */
public final class LegalAcceptanceHistoryService {
    private final JdbcTemplate jdbc;
    private final LegalPrivateRequirementsDataSource dataSource;
    private final LegalManifestDatabaseGate gate;
    private final LegalActorSnapshotReader actorReader;
    private final LegalAcceptanceHistoryReader reader;

    LegalAcceptanceHistoryService(JdbcTemplate jdbc, LegalPrivateRequirementsDataSource dataSource,
            LegalManifestDatabaseGate gate, LegalActorSnapshotReader actorReader, LegalAcceptanceHistoryReader reader,
            LegalV29AcceptanceSchemaVerifier schema, LegalPrivateRequirementsPrivilegeVerifier privileges) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.actorReader = Objects.requireNonNull(actorReader, "actorReader");
        this.reader = Objects.requireNonNull(reader, "reader");
        gate.requireExactPrivateRequirementsBoundary(jdbc, Objects.requireNonNull(schema, "schema"),
                Objects.requireNonNull(privileges, "privileges"));
        if (jdbc.getDataSource() != dataSource || !actorReader.usesJdbc(jdbc) || !reader.usesJdbc(jdbc)) {
            throw new IllegalArgumentException("El historial privado requiere una única frontera JDBC acotada");
        }
    }

    public LegalAcceptanceHistoryPage read(AuthenticatedUserPrincipal principal, ContextoLegal context,
                                           int page, int size) {
        // HTTP validates these inputs too; direct internal callers cannot bypass pagination bounds.
        LegalAcceptanceHistoryPage.pageOffset(page, size);
        try {
            return dataSource.withinDeadline(deadline -> Objects.requireNonNull(
                    gate.executeMutableShared((status, boundary) -> {
                        LegalActorSnapshot actor = actorReader.read(principal, boundary, deadline);
                        // A writer may commit while this reader waits for the actor advisory lock.
                        var observation = new LegalEditorialTimeBoundary(boundary.transactionAt(),
                                Objects.requireNonNull(jdbc.queryForObject("SELECT statement_timestamp()",
                                        OffsetDateTime.class)).toInstant());
                        deadline.check();
                        LegalAcceptanceHistoryPage result = Objects.requireNonNull(
                                reader.read(actor, context, page, size, observation, deadline), "history page");
                        deadline.check();
                        return result;
                    }), "committed history page"));
        } catch (LegalActorSnapshotException | LegalAcceptanceHistoryReadException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new LegalAcceptanceHistoryReadException(failure);
        }
    }
}
