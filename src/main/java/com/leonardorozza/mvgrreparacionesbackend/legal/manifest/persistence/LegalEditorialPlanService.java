package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialPlanValidator.ValidatedEditorialPlan;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/** Read-only transactional boundary for deterministic editorial planning. */
public final class LegalEditorialPlanService {

    private static final String OBSERVATION_LOCATION = "database/observation";

    private final LegalManifestDatabaseGate databaseGate;
    private final JdbcTemplate jdbc;
    private final LegalEditorialPlannerCore planner;
    private final LegalEditorialFailureMapper failureMapper;

    LegalEditorialPlanService(
            LegalManifestDatabaseGate databaseGate,
            JdbcTemplate jdbc,
            LegalEditorialPlannerCore planner,
            LegalEditorialFailureMapper failureMapper,
            LegalEditorialSchemaVerifier schemaVerifier,
            LegalEditorialPrivilegeVerifier privilegeVerifier) {
        this.databaseGate = Objects.requireNonNull(databaseGate, "databaseGate");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.planner = Objects.requireNonNull(planner, "planner");
        this.failureMapper = Objects.requireNonNull(failureMapper, "failureMapper");
        this.databaseGate.requireExactEditorialPreflights(
                this.jdbc,
                Objects.requireNonNull(schemaVerifier, "schemaVerifier"),
                Objects.requireNonNull(privilegeVerifier, "privilegeVerifier"));
    }

    /** Plans the first promotion of one accredited sealed release. */
    public LegalEditorialPlanResult planPromote(ValidatedRelease target) {
        Objects.requireNonNull(target, "target");
        return execute(observedAt -> planner.planPromote(target, observedAt));
    }

    /** Plans a complete replacement bound to one accredited immutable plan. */
    public LegalEditorialPlanResult planReplace(
            ValidatedRelease target,
            ValidatedEditorialPlan editorialPlan) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(editorialPlan, "editorialPlan");
        return execute(observedAt -> planner.planReplace(target, editorialPlan, observedAt));
    }

    /** Plans an explicit fail-closed retirement of the accredited current release. */
    public LegalEditorialPlanResult planRetire(
            ValidatedRelease current,
            ValidatedEditorialPlan editorialPlan) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(editorialPlan, "editorialPlan");
        return execute(observedAt -> planner.planRetire(current, editorialPlan, observedAt));
    }

    private LegalEditorialPlanResult execute(PlanOperation operation) {
        try {
            requireSharedJdbcSession();
            return databaseGate.executeReadOnly(status -> operation.plan(
                    readTransactionTimestamp()));
        } catch (RuntimeException | LinkageError failure) {
            return mappedFailure(failure);
        }
    }

    private Instant readTransactionTimestamp() {
        OffsetDateTime timestamp = jdbc.queryForObject(
                "SELECT transaction_timestamp()",
                OffsetDateTime.class);
        return Objects.requireNonNull(timestamp, "transaction_timestamp").toInstant();
    }

    private void requireSharedJdbcSession() {
        if (!databaseGate.usesJdbc(jdbc) || !planner.usesJdbc(jdbc)) {
            throw new LegalEditorialOperationalException(
                    LegalManifestIssueCode.EDITORIAL_OBSERVATION_FAILED,
                    OBSERVATION_LOCATION);
        }
    }

    private LegalEditorialPlanResult mappedFailure(Throwable failure) {
        LegalManifestIssue mapped = failureMapper.map(failure);
        if (mapped.severity() == LegalManifestStatus.BLOCKED) {
            try {
                return LegalEditorialPlanResult.blocked(List.of(mapped));
            } catch (IllegalArgumentException foreignBlockedIssue) {
                return observationError();
            }
        }
        if (mapped.severity() == LegalManifestStatus.ERROR) {
            try {
                return LegalEditorialPlanResult.error(List.of(mapped));
            } catch (IllegalArgumentException foreignOperationalIssue) {
                return observationError();
            }
        }
        return observationError();
    }

    private static LegalEditorialPlanResult observationError() {
        return LegalEditorialPlanResult.error(List.of(LegalManifestIssue.at(
                LegalManifestIssueCode.EDITORIAL_OBSERVATION_FAILED,
                OBSERVATION_LOCATION)));
    }

    @FunctionalInterface
    private interface PlanOperation {
        LegalEditorialPlanResult plan(Instant observedAt);
    }
}
