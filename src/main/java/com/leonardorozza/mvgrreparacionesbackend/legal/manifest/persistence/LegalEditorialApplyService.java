package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialReadiness;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/** Transactional boundary for an atomic, idempotent first editorial promotion. */
public final class LegalEditorialApplyService {

    private static final String OBSERVATION_LOCATION = "database/observation";
    private static final String POSTCONDITION_LOCATION = "database/postcondition";

    private final LegalManifestDatabaseGate databaseGate;
    private final JdbcTemplate jdbc;
    private final LegalEditorialPlannerCore planner;
    private final LegalEditorialMutationWriter mutationWriter;
    private final LegalEditorialPostStateVerifier postStateVerifier;
    private final LegalEditorialReadinessCore readinessCore;
    private final LegalEditorialFailureMapper failureMapper;

    LegalEditorialApplyService(
            LegalManifestDatabaseGate databaseGate,
            JdbcTemplate jdbc,
            LegalEditorialPlannerCore planner,
            LegalEditorialMutationWriter mutationWriter,
            LegalEditorialPostStateVerifier postStateVerifier,
            LegalEditorialReadinessCore readinessCore,
            LegalEditorialFailureMapper failureMapper,
            LegalEditorialSchemaVerifier schemaVerifier,
            LegalEditorialPrivilegeVerifier privilegeVerifier) {
        this.databaseGate = Objects.requireNonNull(databaseGate, "databaseGate");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.planner = Objects.requireNonNull(planner, "planner");
        this.mutationWriter = Objects.requireNonNull(mutationWriter, "mutationWriter");
        this.postStateVerifier = Objects.requireNonNull(
                postStateVerifier,
                "postStateVerifier");
        this.readinessCore = Objects.requireNonNull(readinessCore, "readinessCore");
        this.failureMapper = Objects.requireNonNull(failureMapper, "failureMapper");
        this.databaseGate.requireExactEditorialPreflights(
                this.jdbc,
                Objects.requireNonNull(schemaVerifier, "schemaVerifier"),
                Objects.requireNonNull(privilegeVerifier, "privilegeVerifier"));
    }

    /** Applies or confirms the first promotion of one validator-issued sealed release. */
    public LegalEditorialApplyResult applyPromote(ValidatedRelease target) {
        Objects.requireNonNull(target, "target");
        LegalTransactionCompletionState<ConfirmedApply> transactionState =
                new LegalTransactionCompletionState<>();
        try {
            requireSharedJdbcSession();
            databaseGate.executeMutable(status -> {
                transactionState.callbackStarted();
                Instant observedAt = readTransactionTimestamp();
                LegalEditorialExecutionPlan plan = requirePromotePlan(requireApplicable(
                        planner.planPromote(target, observedAt)));

                ConfirmedApply confirmed = plan.changeRequired()
                        ? applyFresh(target, plan, observedAt)
                        : confirmReplay(plan);
                transactionState.receiptDelivered(confirmed);
                return confirmed;
            });
            transactionState.transactionReturnedNormally();
            return confirmedResult(transactionState.snapshot());
        } catch (RuntimeException | LinkageError failure) {
            LegalTransactionCompletionState.Snapshot<ConfirmedApply> snapshot =
                    transactionState.snapshot();
            return switch (snapshot.persistence()) {
                case PERSISTED -> confirmedResult(snapshot);
                case UNKNOWN -> LegalEditorialApplyResult.unknown();
                case NOT_PERSISTED -> mappedFailure(failure);
            };
        }
    }

    private ConfirmedApply applyFresh(
            ValidatedRelease target,
            LegalEditorialExecutionPlan plan,
            Instant observedAt) {
        mutationWriter.write(plan);
        LegalEditorialApplyReceipt receipt = postStateVerifier.verify(plan);
        jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
        LegalEditorialReadinessResult readiness = readinessCore.evaluate(target, observedAt);
        requireReadyPostcondition(readiness, receipt, plan);
        return new ConfirmedApply(
                LegalEditorialApplyResult.Outcome.APPLIED,
                receipt);
    }

    private ConfirmedApply confirmReplay(LegalEditorialExecutionPlan plan) {
        return new ConfirmedApply(
                LegalEditorialApplyResult.Outcome.ALREADY_APPLIED,
                postStateVerifier.verify(plan));
    }

    private static LegalEditorialExecutionPlan requireApplicable(
            LegalEditorialPlanResult result) {
        Objects.requireNonNull(result, "result");
        return switch (result.outcome()) {
            case APPLICABLE -> result.executionPlan().orElseThrow();
            case BLOCKED -> throw new LegalEditorialBlockedException(
                    result.issues().getFirst());
            case ERROR -> throw new LegalEditorialOperationalException(
                    result.issues().getFirst());
        };
    }

    private static LegalEditorialExecutionPlan requirePromotePlan(
            LegalEditorialExecutionPlan plan) {
        if (plan.operationType() != LegalEditorialExecutionPlan.OperationType.PROMOTE) {
            throw new LegalEditorialOperationalException(
                    LegalManifestIssueCode.EDITORIAL_OBSERVATION_FAILED,
                    OBSERVATION_LOCATION);
        }
        return plan;
    }

    private static void requireReadyPostcondition(
            LegalEditorialReadinessResult result,
            LegalEditorialApplyReceipt receipt,
            LegalEditorialExecutionPlan plan) {
        if (result.readiness() == LegalEditorialReadiness.ERROR) {
            throw new LegalEditorialOperationalException(result.issues().getFirst());
        }
        if (result.readiness() != LegalEditorialReadiness.READY) {
            throw postconditionFailure();
        }
        LegalEditorialReadinessObservation observation = result.observation().orElseThrow();
        boolean exact = observation.publicationUuid()
                        .filter(plan.target().publicationUuid()::equals)
                        .isPresent()
                && observation.observedAt().equals(plan.observedAt())
                && receipt.operationType() == receiptOperation(plan.operationType())
                && receipt.targetPublicationUuid().equals(plan.target().publicationUuid())
                && receipt.appliedAt().equals(plan.expectedAppliedAt())
                && receipt.readinessAfter() == plan.expectedReadinessAfter()
                && promoteReadinessCountsMatch(plan, observation, receipt);
        if (!exact) {
            throw postconditionFailure();
        }
    }

    private Instant readTransactionTimestamp() {
        OffsetDateTime timestamp = jdbc.queryForObject(
                "SELECT transaction_timestamp()",
                OffsetDateTime.class);
        return Objects.requireNonNull(timestamp, "transaction_timestamp").toInstant();
    }

    private void requireSharedJdbcSession() {
        if (!databaseGate.usesJdbc(jdbc)
                || !planner.usesJdbc(jdbc)
                || !mutationWriter.usesJdbc(jdbc)
                || !postStateVerifier.usesJdbc(jdbc)
                || !readinessCore.usesJdbc(jdbc)) {
            throw new LegalEditorialOperationalException(
                    LegalManifestIssueCode.EDITORIAL_OBSERVATION_FAILED,
                    OBSERVATION_LOCATION);
        }
    }

    private LegalEditorialApplyResult mappedFailure(Throwable failure) {
        LegalManifestIssue mapped = failureMapper.map(failure);
        if (mapped.severity() == LegalManifestStatus.BLOCKED) {
            try {
                return LegalEditorialApplyResult.blocked(List.of(mapped));
            } catch (IllegalArgumentException foreignBlockedIssue) {
                return observationError();
            }
        }
        if (mapped.severity() == LegalManifestStatus.ERROR) {
            try {
                return LegalEditorialApplyResult.error(List.of(mapped));
            } catch (IllegalArgumentException foreignOperationalIssue) {
                return observationError();
            }
        }
        return observationError();
    }

    private static LegalEditorialApplyResult confirmedResult(
            LegalTransactionCompletionState.Snapshot<ConfirmedApply> snapshot) {
        ConfirmedApply confirmed = snapshot.receipt().orElseThrow(() ->
                new IllegalStateException("El apply confirmado no conservó su receipt"));
        return switch (confirmed.outcome()) {
            case APPLIED -> LegalEditorialApplyResult.applied(confirmed.receipt());
            case ALREADY_APPLIED ->
                    LegalEditorialApplyResult.alreadyApplied(confirmed.receipt());
            case BLOCKED, ERROR, UNKNOWN -> throw new IllegalStateException(
                    "Un receipt transaccional sólo admite éxito confirmado");
        };
    }

    private static LegalEditorialApplyResult observationError() {
        return LegalEditorialApplyResult.error(List.of(LegalManifestIssue.at(
                LegalManifestIssueCode.EDITORIAL_OBSERVATION_FAILED,
                OBSERVATION_LOCATION)));
    }

    private static LegalEditorialOperationalException postconditionFailure() {
        return new LegalEditorialOperationalException(
                LegalManifestIssueCode.POSTCONDITION_NOT_READY,
                POSTCONDITION_LOCATION);
    }

    private static LegalEditorialApplyReceipt.OperationType receiptOperation(
            LegalEditorialExecutionPlan.OperationType operationType) {
        return switch (operationType) {
            case PROMOTE -> LegalEditorialApplyReceipt.OperationType.PROMOTE;
            case REPLACE -> LegalEditorialApplyReceipt.OperationType.REPLACE;
            case RETIRE -> LegalEditorialApplyReceipt.OperationType.RETIRE;
        };
    }

    private static boolean promoteReadinessCountsMatch(
            LegalEditorialExecutionPlan plan,
            LegalEditorialReadinessObservation observation,
            LegalEditorialApplyReceipt receipt) {
        return plan.operationType() != LegalEditorialExecutionPlan.OperationType.PROMOTE
                || (receipt.documentVersions() == observation.documentVersions()
                        && receipt.requirementVersions() == observation.requirementVersions()
                        && receipt.documentTransitions() == observation.documentTransitions()
                        && receipt.requirementTransitions() == observation.requirementTransitions()
                        && receipt.documentSlots() == observation.documentSlots()
                        && receipt.requiredSetPointers() == observation.currentRequirementSets()
                        && receipt.replacementBatches() == observation.replacementLots());
    }

    private record ConfirmedApply(
            LegalEditorialApplyResult.Outcome outcome,
            LegalEditorialApplyReceipt receipt
    ) {

        private ConfirmedApply {
            outcome = Objects.requireNonNull(outcome, "outcome");
            receipt = Objects.requireNonNull(receipt, "receipt");
            if (outcome != LegalEditorialApplyResult.Outcome.APPLIED
                    && outcome != LegalEditorialApplyResult.Outcome.ALREADY_APPLIED) {
                throw new IllegalArgumentException(
                        "Un receipt transaccional requiere un éxito confirmado");
            }
        }
    }
}
