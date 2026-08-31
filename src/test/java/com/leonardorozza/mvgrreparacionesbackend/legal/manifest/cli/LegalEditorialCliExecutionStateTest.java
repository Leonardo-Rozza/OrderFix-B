package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli.LegalEditorialArguments.Command;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialPlanValidator.ValidatedEditorialPlan;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalEditorialPlanV1.OperationType;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalEditorialApplyResult;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalEditorialPlanResult;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalEditorialReadinessResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LegalEditorialCliExecutionStateTest {

    @ParameterizedTest
    @EnumSource(
            value = Command.class,
            names = {"APPLY_PROMOTE", "APPLY_REPLACE", "APPLY_RETIRE"})
    void confirmedApplySurvivesContextCloseAndSerializationFailure(Command command) {
        ValidatedRelease release = mock(ValidatedRelease.class);
        LegalEditorialApplyResult result = mock(LegalEditorialApplyResult.class);
        when(result.persisted()).thenReturn(Boolean.TRUE);
        LegalEditorialCliExecutionState state = fullyOpened(command, release);

        state.resultReceived(result);
        state.contextClosed();
        state.reportSerializationStarted();
        state.reportSerializationFailed();

        LegalEditorialCliExecutionState.Snapshot snapshot = state.snapshot();
        assertThat(snapshot.phase())
                .isEqualTo(LegalEditorialCliExecutionState.Phase.REPORT_SERIALIZATION_FAILED);
        assertThat(snapshot.release()).containsSame(release);
        assertThat(snapshot.editorialPlan().isPresent())
                .isEqualTo(command.requiresEditorialPlan());
        assertThat(snapshot.applyResult()).containsSame(result);
        assertThat(snapshot.persisted()).isTrue();
        assertThat(snapshot.readinessResult()).isEmpty();
        assertThat(snapshot.planResult()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(
            value = Command.class,
            names = {"APPLY_PROMOTE", "APPLY_REPLACE", "APPLY_RETIRE"})
    void applyWithoutTerminalResultBecomesUnknownAfterInvocationStarts(Command command) {
        ValidatedRelease release = mock(ValidatedRelease.class);
        LegalEditorialCliExecutionState state = fullyOpened(command, release);

        assertThat(state.snapshot().persisted()).isNull();
        state.contextClosed();
        state.reportSerializationStarted();
        state.reportSerializationFailed();

        assertThat(state.snapshot().persisted()).isNull();
        assertThat(state.snapshot().applyResult()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(
            value = Command.class,
            names = {"APPLY_PROMOTE", "APPLY_REPLACE", "APPLY_RETIRE"})
    void applyBeforeInvocationRemainsKnownNotPersisted(Command command) {
        ValidatedRelease release = mock(ValidatedRelease.class);
        LegalEditorialCliExecutionState apply =
                LegalEditorialCliExecutionState.recognized(command);
        apply.releaseValidated(release);
        if (command.requiresEditorialPlan()) {
            apply.editorialPlanConfirmed(editorialPlanFor(command));
        }
        apply.contextOpened();

        assertThat(apply.snapshot().persisted()).isFalse();
        assertThat(apply.snapshot().applyResult()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(
            value = Command.class,
            names = {"READINESS", "PLAN_PROMOTE", "PLAN_REPLACE", "PLAN_RETIRE"})
    void readOnlyInvocationWithoutTerminalResultRemainsKnownNotPersisted(Command command) {
        LegalEditorialCliExecutionState state = fullyOpened(
                command,
                mock(ValidatedRelease.class));

        assertThat(state.snapshot().persisted()).isFalse();
        assertThat(state.snapshot().applyResult()).isEmpty();
    }

    @Test
    void serializationFailureCannotFabricateAnApplyInvocation() {
        LegalEditorialCliExecutionState state =
                LegalEditorialCliExecutionState.recognized(Command.APPLY_PROMOTE);

        state.reportSerializationStarted();
        state.reportSerializationFailed();

        assertThat(state.snapshot().persisted()).isFalse();
        assertThat(state.snapshot().applyResult()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(
            value = Command.class,
            names = {"PLAN_PROMOTE", "PLAN_REPLACE", "PLAN_RETIRE"})
    void planTerminalResultsRemainTypedAndNotPersisted(Command command) {
        ValidatedRelease release = mock(ValidatedRelease.class);
        LegalEditorialPlanResult planResult = mock(LegalEditorialPlanResult.class);
        LegalEditorialCliExecutionState plan = fullyOpened(command, release);

        plan.resultReceived(planResult);

        assertThat(plan.snapshot().planResult()).containsSame(planResult);
        assertThat(plan.snapshot().persisted()).isFalse();
    }

    @Test
    void readinessTerminalResultRemainsTypedAndNotPersisted() {
        ValidatedRelease release = mock(ValidatedRelease.class);
        LegalEditorialReadinessResult result = mock(LegalEditorialReadinessResult.class);
        LegalEditorialCliExecutionState state = fullyOpened(Command.READINESS, release);

        state.resultReceived(result);

        assertThat(state.snapshot().readinessResult()).containsSame(result);
        assertThat(state.snapshot().persisted()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(
            value = Command.class,
            names = {"PLAN_REPLACE", "PLAN_RETIRE"})
    void confirmedExternalPlanSurvivesContextCloseAndSerializationFailure(Command command) {
        ValidatedRelease release = mock(ValidatedRelease.class);
        ValidatedEditorialPlan editorialPlan = editorialPlanFor(command);
        LegalEditorialPlanResult result = mock(LegalEditorialPlanResult.class);
        LegalEditorialCliExecutionState state =
                LegalEditorialCliExecutionState.recognized(command);
        state.releaseValidated(release);
        state.editorialPlanConfirmed(editorialPlan);
        state.contextOpened();
        state.operationInvocationStarted();
        state.resultReceived(result);
        state.contextClosed();
        state.reportSerializationStarted();
        state.reportSerializationFailed();

        LegalEditorialCliExecutionState.Snapshot snapshot = state.snapshot();
        assertThat(snapshot.editorialPlan()).containsSame(editorialPlan);
        assertThat(snapshot.planResult()).containsSame(result);
        assertThat(snapshot.persisted()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(
            value = Command.class,
            names = {"PLAN_REPLACE", "APPLY_REPLACE", "PLAN_RETIRE", "APPLY_RETIRE"})
    void externalPlanCommandsRequireMatchingConfirmationBeforeOpeningContext(Command command) {
        ValidatedRelease release = mock(ValidatedRelease.class);
        ValidatedEditorialPlan editorialPlan = editorialPlanFor(command);
        LegalEditorialCliExecutionState state =
                LegalEditorialCliExecutionState.recognized(command);
        state.releaseValidated(release);

        assertThatThrownBy(state::contextOpened).isInstanceOf(IllegalStateException.class);
        state.editorialPlanConfirmed(editorialPlan);
        assertThatThrownBy(() -> state.editorialPlanConfirmed(editorialPlan))
                .isInstanceOf(IllegalStateException.class);
        state.contextOpened();
    }

    @ParameterizedTest
    @EnumSource(
            value = Command.class,
            names = {"PLAN_REPLACE", "APPLY_REPLACE", "PLAN_RETIRE", "APPLY_RETIRE"})
    void rejectsCrossOperationPlanWithoutAdvancingEvidence(Command command) {
        ValidatedRelease release = mock(ValidatedRelease.class);
        OperationType expectedType = command.editorialPlanOperationType().orElseThrow();
        OperationType foreignType = expectedType == OperationType.REPLACE
                ? OperationType.RETIRE
                : OperationType.REPLACE;
        LegalEditorialCliExecutionState state =
                LegalEditorialCliExecutionState.recognized(command);
        state.releaseValidated(release);

        assertThatThrownBy(() -> state.editorialPlanConfirmed(editorialPlan(foreignType)))
                .isInstanceOf(IllegalStateException.class);

        LegalEditorialCliExecutionState.Snapshot rejected = state.snapshot();
        assertThat(rejected.phase())
                .isEqualTo(LegalEditorialCliExecutionState.Phase.RELEASE_VALIDATED);
        assertThat(rejected.editorialPlan()).isEmpty();
        assertThat(rejected.persisted()).isFalse();

        ValidatedEditorialPlan matchingPlan = editorialPlan(expectedType);
        state.editorialPlanConfirmed(matchingPlan);
        assertThat(state.snapshot().editorialPlan()).containsSame(matchingPlan);
    }

    @ParameterizedTest
    @EnumSource(
            value = Command.class,
            names = {"READINESS", "PLAN_PROMOTE", "APPLY_PROMOTE"})
    void commandsWithoutExternalPlanRejectPlanConfirmation(Command command) {
        ValidatedRelease release = mock(ValidatedRelease.class);
        LegalEditorialCliExecutionState state =
                LegalEditorialCliExecutionState.recognized(command);
        state.releaseValidated(release);

        assertThatThrownBy(() -> state.editorialPlanConfirmed(
                editorialPlan(OperationType.REPLACE)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(state.snapshot().phase())
                .isEqualTo(LegalEditorialCliExecutionState.Phase.RELEASE_VALIDATED);
        assertThat(state.snapshot().editorialPlan()).isEmpty();
    }

    @Test
    void stateRejectsWrongResultTypeDuplicateResultsAndBackwardTransitions() {
        ValidatedRelease release = mock(ValidatedRelease.class);
        LegalEditorialCliExecutionState state = fullyOpened(Command.APPLY_PROMOTE, release);

        assertThatThrownBy(() -> state.resultReceived(
                mock(LegalEditorialReadinessResult.class)))
                .isInstanceOf(IllegalStateException.class);
        LegalEditorialApplyResult result = mock(LegalEditorialApplyResult.class);
        state.resultReceived(result);
        assertThatThrownBy(() -> state.resultReceived(result))
                .isInstanceOf(IllegalStateException.class);
        state.contextClosed();
        assertThatThrownBy(state::contextOpened)
                .isInstanceOf(IllegalStateException.class);
        state.reportSerializationStarted();
        assertThatThrownBy(state::reportSerializationStarted)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void nullsNeverAdvanceTheMonotonicEvidence() {
        LegalEditorialCliExecutionState readiness =
                LegalEditorialCliExecutionState.recognized(Command.READINESS);

        assertThatThrownBy(() -> readiness.releaseValidated(null))
                .isInstanceOf(NullPointerException.class);
        assertThat(readiness.snapshot().phase())
                .isEqualTo(LegalEditorialCliExecutionState.Phase.COMMAND_RECOGNIZED);

        ValidatedRelease release = mock(ValidatedRelease.class);
        LegalEditorialCliExecutionState retire =
                LegalEditorialCliExecutionState.recognized(Command.APPLY_RETIRE);
        retire.releaseValidated(release);
        assertThatThrownBy(() -> retire.editorialPlanConfirmed(null))
                .isInstanceOf(NullPointerException.class);
        assertThat(retire.snapshot().phase())
                .isEqualTo(LegalEditorialCliExecutionState.Phase.RELEASE_VALIDATED);
        assertThat(retire.snapshot().editorialPlan()).isEmpty();

        ValidatedEditorialPlan typelessPlan = mock(ValidatedEditorialPlan.class);
        assertThatThrownBy(() -> retire.editorialPlanConfirmed(typelessPlan))
                .isInstanceOf(IllegalStateException.class);
        assertThat(retire.snapshot().phase())
                .isEqualTo(LegalEditorialCliExecutionState.Phase.RELEASE_VALIDATED);

        retire.editorialPlanConfirmed(editorialPlanFor(Command.APPLY_RETIRE));
        retire.contextOpened();
        retire.operationInvocationStarted();
        assertThatThrownBy(() -> retire.resultReceived((LegalEditorialApplyResult) null))
                .isInstanceOf(NullPointerException.class);
        assertThat(retire.snapshot().phase())
                .isEqualTo(LegalEditorialCliExecutionState.Phase.OPERATION_INVOCATION_STARTED);
        assertThat(retire.snapshot().persisted()).isNull();
    }

    private static LegalEditorialCliExecutionState fullyOpened(
            Command command,
            ValidatedRelease release) {
        LegalEditorialCliExecutionState state =
                LegalEditorialCliExecutionState.recognized(command);
        state.releaseValidated(release);
        if (command.requiresEditorialPlan()) {
            state.editorialPlanConfirmed(editorialPlanFor(command));
        }
        state.contextOpened();
        state.operationInvocationStarted();
        return state;
    }

    private static ValidatedEditorialPlan editorialPlanFor(Command command) {
        return editorialPlan(command.editorialPlanOperationType().orElseThrow());
    }

    private static ValidatedEditorialPlan editorialPlan(OperationType operationType) {
        ValidatedEditorialPlan plan = mock(ValidatedEditorialPlan.class);
        when(plan.operationType()).thenReturn(operationType);
        return plan;
    }
}
