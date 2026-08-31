package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialPlanValidator.ValidatedEditorialPlan;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;

/** SELECT-only classifier for one editorial transaction with an ambiguous completion. */
final class LegalEditorialCommitReconciler {

    private final LegalManifestDatabaseGate databaseGate;
    private final JdbcTemplate jdbc;
    private final LegalEditorialPlannerCore planner;
    private final LegalEditorialPostStateVerifier postStateVerifier;

    LegalEditorialCommitReconciler(
            LegalManifestDatabaseGate databaseGate,
            JdbcTemplate jdbc,
            LegalEditorialPlannerCore planner,
            LegalEditorialPostStateVerifier postStateVerifier,
            LegalEditorialSchemaVerifier schemaVerifier,
            LegalEditorialPrivilegeVerifier privilegeVerifier) {
        this.databaseGate = Objects.requireNonNull(databaseGate, "databaseGate");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.planner = Objects.requireNonNull(planner, "planner");
        this.postStateVerifier = Objects.requireNonNull(
                postStateVerifier,
                "postStateVerifier");
        this.databaseGate.requireExactEditorialReconciliationBoundary(
                this.jdbc,
                Objects.requireNonNull(schemaVerifier, "schemaVerifier"),
                Objects.requireNonNull(privilegeVerifier, "privilegeVerifier"));
        requireSharedJdbcGraph();
    }

    Result reconcilePromote(ValidatedRelease target, boolean planConstructed) {
        Objects.requireNonNull(target, "target");
        return reconcile(
                planConstructed,
                LegalEditorialExecutionPlan.OperationType.PROMOTE,
                observedAt -> planner.planPromote(target, observedAt));
    }

    Result reconcileReplace(
            ValidatedRelease target,
            ValidatedEditorialPlan editorialPlan,
            boolean planConstructed) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(editorialPlan, "editorialPlan");
        return reconcile(
                planConstructed,
                LegalEditorialExecutionPlan.OperationType.REPLACE,
                observedAt -> planner.planReplace(target, editorialPlan, observedAt));
    }

    Result reconcileRetire(
            ValidatedRelease current,
            ValidatedEditorialPlan editorialPlan,
            boolean planConstructed) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(editorialPlan, "editorialPlan");
        return reconcile(
                planConstructed,
                LegalEditorialExecutionPlan.OperationType.RETIRE,
                observedAt -> planner.planRetire(current, editorialPlan, observedAt));
    }

    boolean usesJdbc(JdbcTemplate candidate) {
        return jdbc == candidate
                && databaseGate.usesJdbc(candidate)
                && planner.usesJdbc(candidate)
                && postStateVerifier.usesJdbc(candidate);
    }

    private Result reconcile(
            boolean planConstructed,
            LegalEditorialExecutionPlan.OperationType expectedOperation,
            ReplanOperation operation) {
        if (!planConstructed || previousTransactionStillBound()) {
            return Result.unknown();
        }
        try {
            requireSharedJdbcGraph();
            Result observed = databaseGate.executeEditorialReconciliation(status -> {
                Instant observedAt = readTransactionTimestamp();
                return classify(
                        expectedOperation,
                        operation.plan(observedAt));
            });
            return observed == null ? Result.unknown() : observed;
        } catch (RuntimeException | LinkageError inconclusiveFailure) {
            return Result.unknown();
        }
    }

    private Result classify(
            LegalEditorialExecutionPlan.OperationType expectedOperation,
            LegalEditorialPlanResult replanned) {
        if (replanned == null
                || replanned.outcome() != LegalEditorialPlanResult.Outcome.APPLICABLE) {
            return Result.unknown();
        }
        Optional<LegalEditorialExecutionPlan> optionalPlan = replanned.executionPlan();
        if (optionalPlan.isEmpty()) {
            return Result.unknown();
        }
        LegalEditorialExecutionPlan plan = optionalPlan.orElseThrow();
        if (plan.operationType() != expectedOperation) {
            return Result.unknown();
        }
        if (plan.changeRequired()) {
            return Result.sourceExact();
        }
        LegalEditorialApplyReceipt receipt = postStateVerifier.verify(plan);
        return receipt == null ? Result.unknown() : Result.postExact(receipt);
    }

    private Instant readTransactionTimestamp() {
        OffsetDateTime timestamp = jdbc.queryForObject(
                "SELECT transaction_timestamp()",
                OffsetDateTime.class);
        return Objects.requireNonNull(timestamp, "transaction_timestamp").toInstant();
    }

    private boolean previousTransactionStillBound() {
        DataSource dataSource = jdbc.getDataSource();
        return dataSource == null
                || TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.hasResource(dataSource);
    }

    private void requireSharedJdbcGraph() {
        if (!usesJdbc(jdbc)) {
            throw new IllegalArgumentException(
                    "La reconciliación editorial requiere una única sesión JDBC acreditada");
        }
    }

    enum Outcome {
        POST_EXACT,
        SOURCE_EXACT,
        UNKNOWN
    }

    record Result(Outcome outcome, Optional<LegalEditorialApplyReceipt> receipt) {

        Result {
            outcome = Objects.requireNonNull(outcome, "outcome");
            receipt = Objects.requireNonNull(receipt, "receipt");
            boolean valid = switch (outcome) {
                case POST_EXACT -> receipt.isPresent();
                case SOURCE_EXACT, UNKNOWN -> receipt.isEmpty();
            };
            if (!valid) {
                throw new IllegalArgumentException(
                        "La evidencia reconciliada no admite esa combinación");
            }
        }

        private static Result postExact(LegalEditorialApplyReceipt receipt) {
            return new Result(
                    Outcome.POST_EXACT,
                    Optional.of(Objects.requireNonNull(receipt, "receipt")));
        }

        private static Result sourceExact() {
            return new Result(Outcome.SOURCE_EXACT, Optional.empty());
        }

        private static Result unknown() {
            return new Result(Outcome.UNKNOWN, Optional.empty());
        }
    }

    @FunctionalInterface
    private interface ReplanOperation {
        LegalEditorialPlanResult plan(Instant observedAt);
    }
}
