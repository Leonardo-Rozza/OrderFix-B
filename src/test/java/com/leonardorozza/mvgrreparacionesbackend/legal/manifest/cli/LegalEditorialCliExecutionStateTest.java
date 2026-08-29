package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli.LegalEditorialArguments.Command;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialPlanValidator.ValidatedEditorialPlan;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalEditorialApplyResult;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalEditorialPlanResult;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalEditorialReadinessResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LegalEditorialCliExecutionStateTest {

    @Test
    void confirmedApplySurvivesContextCloseAndSerializationFailure() {
        ValidatedRelease release = mock(ValidatedRelease.class);
        LegalEditorialApplyResult result = mock(LegalEditorialApplyResult.class);
        when(result.persisted()).thenReturn(Boolean.TRUE);
        LegalEditorialCliExecutionState state = fullyOpened(Command.APPLY_PROMOTE, release);

        state.resultReceived(result);
        state.contextClosed();
        state.reportSerializationStarted();
        state.reportSerializationFailed();

        LegalEditorialCliExecutionState.Snapshot snapshot = state.snapshot();
        assertThat(snapshot.phase())
                .isEqualTo(LegalEditorialCliExecutionState.Phase.REPORT_SERIALIZATION_FAILED);
        assertThat(snapshot.release()).containsSame(release);
        assertThat(snapshot.applyResult()).containsSame(result);
        assertThat(snapshot.persisted()).isTrue();
        assertThat(snapshot.readinessResult()).isEmpty();
        assertThat(snapshot.planResult()).isEmpty();
    }

    @Test
    void applyWithoutTerminalResultBecomesUnknownAfterInvocationStarts() {
        ValidatedRelease release = mock(ValidatedRelease.class);
        LegalEditorialCliExecutionState state = fullyOpened(Command.APPLY_PROMOTE, release);

        assertThat(state.snapshot().persisted()).isNull();
        state.contextClosed();
        state.reportSerializationStarted();
        state.reportSerializationFailed();

        assertThat(state.snapshot().persisted()).isNull();
        assertThat(state.snapshot().applyResult()).isEmpty();
    }

    @Test
    void applyBeforeInvocationAndAllReadOnlyFailuresRemainKnownNotPersisted() {
        ValidatedRelease release = mock(ValidatedRelease.class);
        LegalEditorialCliExecutionState apply =
                LegalEditorialCliExecutionState.recognized(Command.APPLY_PROMOTE);
        apply.releaseValidated(release);
        apply.contextOpened();
        LegalEditorialCliExecutionState readiness =
                fullyOpened(Command.READINESS, release);
        LegalEditorialCliExecutionState plan =
                fullyOpened(Command.PLAN_PROMOTE, release);

        assertThat(apply.snapshot().persisted()).isFalse();
        assertThat(readiness.snapshot().persisted()).isFalse();
        assertThat(plan.snapshot().persisted()).isFalse();
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

    @Test
    void readOnlyTerminalResultsRemainTypedAndNotPersisted() {
        ValidatedRelease release = mock(ValidatedRelease.class);
        LegalEditorialReadinessResult readinessResult =
                mock(LegalEditorialReadinessResult.class);
        LegalEditorialPlanResult planResult = mock(LegalEditorialPlanResult.class);
        LegalEditorialCliExecutionState readiness = fullyOpened(Command.READINESS, release);
        LegalEditorialCliExecutionState plan = fullyOpened(Command.PLAN_PROMOTE, release);

        readiness.resultReceived(readinessResult);
        plan.resultReceived(planResult);

        assertThat(readiness.snapshot().readinessResult()).containsSame(readinessResult);
        assertThat(readiness.snapshot().persisted()).isFalse();
        assertThat(plan.snapshot().planResult()).containsSame(planResult);
        assertThat(plan.snapshot().persisted()).isFalse();
    }

    @Test
    void confirmedReplacePlanSurvivesContextCloseAndSerializationFailure() {
        ValidatedRelease release = mock(ValidatedRelease.class);
        ValidatedEditorialPlan editorialPlan = mock(ValidatedEditorialPlan.class);
        LegalEditorialPlanResult result = mock(LegalEditorialPlanResult.class);
        LegalEditorialCliExecutionState state =
                LegalEditorialCliExecutionState.recognized(Command.PLAN_REPLACE);
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

    @Test
    void replaceCannotOpenContextBeforePlanConfirmationOrConfirmAnotherCommand() {
        ValidatedRelease release = mock(ValidatedRelease.class);
        ValidatedEditorialPlan editorialPlan = mock(ValidatedEditorialPlan.class);
        LegalEditorialCliExecutionState replace =
                LegalEditorialCliExecutionState.recognized(Command.PLAN_REPLACE);
        replace.releaseValidated(release);

        assertThatThrownBy(replace::contextOpened).isInstanceOf(IllegalStateException.class);
        replace.editorialPlanConfirmed(editorialPlan);
        assertThatThrownBy(() -> replace.editorialPlanConfirmed(editorialPlan))
                .isInstanceOf(IllegalStateException.class);

        LegalEditorialCliExecutionState promote =
                LegalEditorialCliExecutionState.recognized(Command.PLAN_PROMOTE);
        promote.releaseValidated(release);
        assertThatThrownBy(() -> promote.editorialPlanConfirmed(editorialPlan))
                .isInstanceOf(IllegalStateException.class);
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
        LegalEditorialCliExecutionState state =
                LegalEditorialCliExecutionState.recognized(Command.READINESS);

        assertThatThrownBy(() -> state.releaseValidated(null))
                .isInstanceOf(NullPointerException.class);
        assertThat(state.snapshot().phase())
                .isEqualTo(LegalEditorialCliExecutionState.Phase.COMMAND_RECOGNIZED);
    }

    private static LegalEditorialCliExecutionState fullyOpened(
            Command command,
            ValidatedRelease release) {
        LegalEditorialCliExecutionState state =
                LegalEditorialCliExecutionState.recognized(command);
        state.releaseValidated(release);
        state.contextOpened();
        state.operationInvocationStarted();
        return state;
    }
}
