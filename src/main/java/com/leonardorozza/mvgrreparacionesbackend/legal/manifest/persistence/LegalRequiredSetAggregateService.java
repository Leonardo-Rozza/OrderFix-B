package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalApplicableScopeResolver;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalApplicableScopeSet;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PerfilAgregadoLegal;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Objects;

/** Internal entry point for commit-confirmed materialization in the isolated V28 context. */
final class LegalRequiredSetAggregateService {

    private final LegalApplicableScopeResolver resolver;
    private final LegalManifestDatabaseGate databaseGate;
    private final LegalRequiredSetAggregateStore store;

    LegalRequiredSetAggregateService(
            LegalApplicableScopeResolver resolver,
            JdbcTemplate jdbc,
            LegalManifestDatabaseGate databaseGate,
            LegalRequiredSetAggregateStore store,
            LegalV28AggregateSchemaVerifier schemaVerifier,
            LegalV28AggregatePrivilegeVerifier privilegeVerifier) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.databaseGate = Objects.requireNonNull(databaseGate, "databaseGate");
        this.store = Objects.requireNonNull(store, "store");
        Objects.requireNonNull(jdbc, "jdbc");
        Objects.requireNonNull(schemaVerifier, "schemaVerifier");
        Objects.requireNonNull(privilegeVerifier, "privilegeVerifier");
        databaseGate.requireCommitOutcomeSafe();
        databaseGate.requireExactAggregatePreflights(jdbc, schemaVerifier, privilegeVerifier);
        if (!databaseGate.usesJdbc(jdbc) || !store.usesJdbc(jdbc)) {
            throw new IllegalArgumentException(
                    "El servicio agregado requiere una única sesión JDBC compartida");
        }
    }

    LegalRequiredSetAggregateReceipt materialize(
            PerfilAgregadoLegal profile,
            LocaleLegal locale,
            AudienciaLegal audience) {
        LegalApplicableScopeSet scopes = resolver.resolve(profile, locale, audience);
        return Objects.requireNonNull(databaseGate.executeMutableShared((status, boundary) ->
                Objects.requireNonNull(store.materialize(scopes, boundary), "aggregate receipt")),
                "committed aggregate receipt");
    }
}
