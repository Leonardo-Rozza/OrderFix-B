package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalApplicableScopeResolver;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalApplicableScopeSet;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalPublicRegistrationRequirements;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PerfilAgregadoLegal;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Objects;

/** Complete registration requirements, accredited in the same transaction as their V28 aggregate. */
public final class LegalPublicRequirementsReadService {

    private final LegalPublicRequirementsDataSource dataSource;
    private final LegalManifestDatabaseGate gate;
    private final LegalApplicableScopeResolver resolver;
    private final LegalRequiredSetAggregateStore store;
    private final LegalPublicRequirementsReader reader;

    LegalPublicRequirementsReadService(
            JdbcTemplate jdbc,
            LegalPublicRequirementsDataSource dataSource,
            LegalManifestDatabaseGate gate,
            LegalApplicableScopeResolver resolver,
            LegalRequiredSetAggregateStore store,
            LegalPublicRequirementsReader reader,
            LegalV28AggregateSchemaVerifier schema,
            LegalPublicRequirementsPrivilegeVerifier privileges) {
        Objects.requireNonNull(jdbc, "jdbc");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.store = Objects.requireNonNull(store, "store");
        this.reader = Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(privileges, "privileges");
        gate.requireExactPublicRequirementsBoundary(jdbc, schema, privileges);
        if (jdbc.getDataSource() != dataSource || !store.usesJdbc(jdbc) || !reader.usesJdbc(jdbc)) {
            throw new IllegalArgumentException("Los requisitos públicos requieren una única frontera JDBC acotada");
        }
    }

    /** Returns a fully accredited observation only after commit, cleanup and the final deadline check. */
    public LegalPublicRegistrationRequirements readRegistration() {
        try {
            return dataSource.withinDeadline(deadline -> {
                LegalApplicableScopeSet scopes = Objects.requireNonNull(resolver.resolve(
                        PerfilAgregadoLegal.REGISTRATION, LocaleLegal.ES_AR, AudienciaLegal.ADMIN_TITULAR),
                        "registration scopes");
                deadline.check();
                return Objects.requireNonNull(gate.executeMutableShared((status, boundary) -> {
                    deadline.check();
                    LegalRequiredSetAggregateReceipt receipt = Objects.requireNonNull(
                            store.materialize(scopes, boundary), "aggregate receipt");
                    deadline.check();
                    LegalPublicRegistrationRequirements result = Objects.requireNonNull(
                            reader.read(receipt, boundary, deadline), "registration requirements");
                    deadline.check();
                    return result;
                }), "committed registration requirements");
            });
        } catch (LegalPublicRequirementsReadException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            // Preserve the internal cause, including UNKNOWN commit outcomes, without exposing it as a message.
            throw new LegalPublicRequirementsReadException(failure);
        }
    }
}
