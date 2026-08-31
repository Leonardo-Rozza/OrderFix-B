package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Objects;

/** Read-only transactional boundary for one complete editorial readiness observation. */
public final class LegalEditorialReadinessService {

    private static final String OBSERVATION_LOCATION = "database/observation";

    private final LegalManifestDatabaseGate databaseGate;
    private final JdbcTemplate jdbc;
    private final LegalEditorialReadinessCore core;
    private final LegalEditorialFailureMapper failureMapper;

    LegalEditorialReadinessService(
            LegalManifestDatabaseGate databaseGate,
            JdbcTemplate jdbc,
            LegalEditorialReadinessCore core,
            LegalEditorialFailureMapper failureMapper,
            LegalEditorialSchemaVerifier schemaVerifier,
            LegalEditorialPrivilegeVerifier privilegeVerifier) {
        this.databaseGate = Objects.requireNonNull(databaseGate, "databaseGate");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.core = Objects.requireNonNull(core, "core");
        this.failureMapper = Objects.requireNonNull(failureMapper, "failureMapper");
        this.databaseGate.requireExactEditorialPreflights(
                this.jdbc,
                Objects.requireNonNull(schemaVerifier, "schemaVerifier"),
                Objects.requireNonNull(privilegeVerifier, "privilegeVerifier"));
    }

    /** Evaluates the validator-issued release without persisting or taking row locks. */
    public LegalEditorialReadinessResult evaluate(ValidatedRelease release) {
        Objects.requireNonNull(release, "release");
        try {
            requireSharedJdbcSession();
            return databaseGate.executeReadOnly((status, boundary) ->
                    core.evaluate(release, boundary.observedAt()));
        } catch (RuntimeException | LinkageError failure) {
            LegalManifestIssue mapped = failureMapper.map(failure);
            if (mapped.severity() != LegalManifestStatus.ERROR) {
                mapped = LegalManifestIssue.at(
                        LegalManifestIssueCode.EDITORIAL_OBSERVATION_FAILED,
                        OBSERVATION_LOCATION);
            }
            return LegalEditorialReadinessResult.error(List.of(mapped));
        }
    }

    private void requireSharedJdbcSession() {
        if (!databaseGate.usesJdbc(jdbc) || !core.usesJdbc(jdbc)) {
            throw new LegalEditorialOperationalException(
                    LegalManifestIssueCode.EDITORIAL_OBSERVATION_FAILED,
                    OBSERVATION_LOCATION);
        }
    }
}
