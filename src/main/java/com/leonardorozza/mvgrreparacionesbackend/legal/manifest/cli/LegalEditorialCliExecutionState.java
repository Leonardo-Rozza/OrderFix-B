package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli.LegalEditorialArguments.Command;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialPlanValidator.ValidatedEditorialPlan;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalEditorialApplyResult;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalEditorialPlanResult;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalEditorialReadinessResult;

import java.util.Objects;
import java.util.Optional;

/**
 * Monotonic evidence retained across context-close and report-serialization failures.
 *
 * <p>A confirmed service result is terminal authority and is never replaced by a later boundary
 * failure. If apply invocation began but no result escaped, persistence remains unknown. Read-only
 * commands never manufacture persistence uncertainty.</p>
 */
final class LegalEditorialCliExecutionState {

    enum Phase {
        COMMAND_RECOGNIZED,
        RELEASE_VALIDATED,
        EDITORIAL_PLAN_CONFIRMED,
        CONTEXT_OPENED,
        OPERATION_INVOCATION_STARTED,
        RESULT_RECEIVED,
        CONTEXT_CLOSED,
        REPORT_SERIALIZATION_STARTED,
        REPORT_SERIALIZATION_FAILED
    }

    private final Command command;
    private Phase phase = Phase.COMMAND_RECOGNIZED;
    private ValidatedRelease release;
    private ValidatedEditorialPlan editorialPlan;
    private LegalEditorialReadinessResult readinessResult;
    private LegalEditorialPlanResult planResult;
    private LegalEditorialApplyResult applyResult;
    private boolean operationInvoked;

    private LegalEditorialCliExecutionState(Command command) {
        this.command = Objects.requireNonNull(command, "command");
    }

    static LegalEditorialCliExecutionState recognized(
            Command command) {
        return new LegalEditorialCliExecutionState(command);
    }

    synchronized void releaseValidated(ValidatedRelease validatedRelease) {
        requirePhase(Phase.COMMAND_RECOGNIZED);
        release = Objects.requireNonNull(validatedRelease, "release");
        phase = Phase.RELEASE_VALIDATED;
    }

    synchronized void contextOpened() {
        requirePhase(command.requiresEditorialPlan()
                ? Phase.EDITORIAL_PLAN_CONFIRMED
                : Phase.RELEASE_VALIDATED);
        phase = Phase.CONTEXT_OPENED;
    }

    synchronized void editorialPlanConfirmed(ValidatedEditorialPlan validatedPlan) {
        requireCommand(Command.PLAN_REPLACE);
        requirePhase(Phase.RELEASE_VALIDATED);
        editorialPlan = Objects.requireNonNull(validatedPlan, "editorialPlan");
        phase = Phase.EDITORIAL_PLAN_CONFIRMED;
    }

    synchronized void operationInvocationStarted() {
        requirePhase(Phase.CONTEXT_OPENED);
        operationInvoked = true;
        phase = Phase.OPERATION_INVOCATION_STARTED;
    }

    synchronized void resultReceived(LegalEditorialReadinessResult result) {
        requireCommand(Command.READINESS);
        requireInvocation();
        readinessResult = Objects.requireNonNull(result, "result");
        phase = Phase.RESULT_RECEIVED;
    }

    synchronized void resultReceived(LegalEditorialPlanResult result) {
        if (command != Command.PLAN_PROMOTE && command != Command.PLAN_REPLACE) {
            throw invalidTransition();
        }
        requireInvocation();
        planResult = Objects.requireNonNull(result, "result");
        phase = Phase.RESULT_RECEIVED;
    }

    synchronized void resultReceived(LegalEditorialApplyResult result) {
        requireCommand(Command.APPLY_PROMOTE);
        requireInvocation();
        applyResult = Objects.requireNonNull(result, "result");
        phase = Phase.RESULT_RECEIVED;
    }

    synchronized void contextClosed() {
        if (phase.ordinal() < Phase.CONTEXT_OPENED.ordinal()
                || phase.ordinal() >= Phase.CONTEXT_CLOSED.ordinal()) {
            throw invalidTransition();
        }
        phase = Phase.CONTEXT_CLOSED;
    }

    synchronized void reportSerializationStarted() {
        if (phase.ordinal() >= Phase.REPORT_SERIALIZATION_STARTED.ordinal()) {
            throw invalidTransition();
        }
        phase = Phase.REPORT_SERIALIZATION_STARTED;
    }

    synchronized void reportSerializationFailed() {
        requirePhase(Phase.REPORT_SERIALIZATION_STARTED);
        phase = Phase.REPORT_SERIALIZATION_FAILED;
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(
                command,
                phase,
                release,
                editorialPlan,
                readinessResult,
                planResult,
                applyResult,
                operationInvoked);
    }

    private void requireInvocation() {
        requirePhase(Phase.OPERATION_INVOCATION_STARTED);
    }

    private void requireCommand(Command expected) {
        if (command != expected) {
            throw invalidTransition();
        }
    }

    private void requirePhase(Phase expected) {
        if (phase != expected) {
            throw invalidTransition();
        }
    }

    private static IllegalStateException invalidTransition() {
        return new IllegalStateException(
                "Transición inválida del estado de ejecución editorial");
    }

    /** Immutable evidence used to select a truthful v3 fallback report. */
    static final class Snapshot {

        private final Command command;
        private final Phase phase;
        private final ValidatedRelease release;
        private final ValidatedEditorialPlan editorialPlan;
        private final LegalEditorialReadinessResult readinessResult;
        private final LegalEditorialPlanResult planResult;
        private final LegalEditorialApplyResult applyResult;
        private final boolean operationInvoked;

        private Snapshot(
                Command command,
                Phase phase,
                ValidatedRelease release,
                ValidatedEditorialPlan editorialPlan,
                LegalEditorialReadinessResult readinessResult,
                LegalEditorialPlanResult planResult,
                LegalEditorialApplyResult applyResult,
                boolean operationInvoked) {
            this.command = Objects.requireNonNull(command, "command");
            this.phase = Objects.requireNonNull(phase, "phase");
            this.release = release;
            this.editorialPlan = editorialPlan;
            this.readinessResult = readinessResult;
            this.planResult = planResult;
            this.applyResult = applyResult;
            this.operationInvoked = operationInvoked;
        }

        Command command() {
            return command;
        }

        Phase phase() {
            return phase;
        }

        Optional<ValidatedRelease> release() {
            return Optional.ofNullable(release);
        }

        Optional<ValidatedEditorialPlan> editorialPlan() {
            return Optional.ofNullable(editorialPlan);
        }

        Optional<LegalEditorialReadinessResult> readinessResult() {
            return Optional.ofNullable(readinessResult);
        }

        Optional<LegalEditorialPlanResult> planResult() {
            return Optional.ofNullable(planResult);
        }

        Optional<LegalEditorialApplyResult> applyResult() {
            return Optional.ofNullable(applyResult);
        }

        /** Returns true, false, or null when apply invocation has no terminal result. */
        Boolean persisted() {
            if (applyResult != null) {
                return applyResult.persisted();
            }
            if (readinessResult != null || planResult != null) {
                return Boolean.FALSE;
            }
            boolean applyMayHaveReachedItsTransaction = command.mutating() && operationInvoked;
            return applyMayHaveReachedItsTransaction ? null : Boolean.FALSE;
        }
    }
}
