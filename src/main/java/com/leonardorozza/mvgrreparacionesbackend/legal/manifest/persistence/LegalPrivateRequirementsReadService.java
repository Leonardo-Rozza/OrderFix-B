package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalActorSnapshot;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalApplicableScopeResolver;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalApplicableScopeSet;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAuthenticatedRequirements;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PerfilAgregadoLegal;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Objects;

/** Internal account observation; delivery follows commit, resource release and the final deadline check. */
public final class LegalPrivateRequirementsReadService {
    private final JdbcTemplate jdbc;
    private final LegalPrivateRequirementsDataSource dataSource;
    private final LegalManifestDatabaseGate gate;
    private final LegalApplicableScopeResolver resolver;
    private final LegalRequiredSetAggregateStore store;
    private final LegalPrivateRequirementsReader reader;
    private final LegalActorSnapshotReader actorReader;

    LegalPrivateRequirementsReadService(
            JdbcTemplate jdbc,
            LegalPrivateRequirementsDataSource dataSource,
            LegalManifestDatabaseGate gate,
            LegalApplicableScopeResolver resolver,
            LegalRequiredSetAggregateStore store,
            LegalPrivateRequirementsReader reader,
            LegalActorSnapshotReader actorReader,
            LegalV29AcceptanceSchemaVerifier schema,
            LegalPrivateRequirementsPrivilegeVerifier privileges) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.store = Objects.requireNonNull(store, "store");
        this.reader = Objects.requireNonNull(reader, "reader");
        this.actorReader = Objects.requireNonNull(actorReader, "actorReader");
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(privileges, "privileges");
        gate.requireExactPrivateRequirementsBoundary(jdbc, schema, privileges);
        if (jdbc.getDataSource() != dataSource || !store.usesJdbc(jdbc)
                || !reader.usesJdbc(jdbc) || !actorReader.usesJdbc(jdbc)) {
            throw new IllegalArgumentException("Los requisitos privados requieren una única frontera JDBC acotada");
        }
    }

    /** Caller supplies the existing server principal, never a browser-selected actor or scope. */
    public LegalAuthenticatedRequirements read(AuthenticatedUserPrincipal principal) {
        try {
            return dataSource.withinDeadline(deadline -> Objects.requireNonNull(
                    gate.executeMutableShared((status, boundary) -> {
                        LegalActorSnapshot actor = actorReader.read(principal, boundary, deadline);
                        // Waiting for the actor may expose an acceptance committed after the gate clock.
                        LegalEditorialTimeBoundary observation = new LegalEditorialTimeBoundary(
                                boundary.transactionAt(), Objects.requireNonNull(jdbc.queryForObject(
                                "SELECT statement_timestamp()", java.time.OffsetDateTime.class)).toInstant());
                        LegalApplicableScopeSet scopes = Objects.requireNonNull(resolver.resolve(
                                PerfilAgregadoLegal.AUTHENTICATED_PENDING, LocaleLegal.ES_AR, actor.audience()),
                                "private scopes");
                        deadline.check();
                        LegalRequiredSetAggregateReceipt receipt = Objects.requireNonNull(
                                store.materialize(scopes, observation), "aggregate receipt");
                        deadline.check();
                        LegalAuthenticatedRequirements result = Objects.requireNonNull(
                                reader.read(actor, scopes, receipt, observation, deadline), "private requirements");
                        deadline.check();
                        return result;
                    }), "committed private requirements"));
        } catch (LegalActorSnapshotException | LegalPrivateRequirementsReadException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            // SQLSTATE and uncertain commit outcomes remain available only through the internal cause.
            throw new LegalPrivateRequirementsReadException(failure);
        }
    }
}
