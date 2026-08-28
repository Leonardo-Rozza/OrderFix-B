package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegalTransactionCompletionStateTest {

    @AfterEach
    void clearTransactionSynchronization() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void registersItselfExactlyOnceInsideAnActualSynchronizedTransaction() {
        LegalTransactionCompletionState<String> state = new LegalTransactionCompletionState<>();
        activateTransactionSynchronization();

        state.callbackStarted();

        assertThat(TransactionSynchronizationManager.getSynchronizations())
                .containsExactly(state);
        assertThat(state.snapshot().callbackStarted()).isTrue();
        assertThatThrownBy(state::callbackStarted).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesToStartOutsideAnActualSynchronizedTransaction() {
        LegalTransactionCompletionState<String> state = new LegalTransactionCompletionState<>();

        assertThatThrownBy(state::callbackStarted).isInstanceOf(IllegalStateException.class);

        TransactionSynchronizationManager.initSynchronization();
        assertThatThrownBy(state::callbackStarted).isInstanceOf(IllegalStateException.class);
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    }

    @Test
    void deliveredReceiptIsTentativeUntilCommitIsAuthoritative() {
        LegalTransactionCompletionState<String> state = new LegalTransactionCompletionState<>();
        activateAndStart(state);
        state.receiptDelivered("receipt");

        LegalTransactionCompletionState.Snapshot<String> beforeCommit = state.snapshot();
        assertThat(beforeCommit.persistence())
                .isEqualTo(LegalTransactionCompletionState.Persistence.NOT_PERSISTED);
        assertThat(beforeCommit.receipt()).isEmpty();

        state.beforeCommit(false);
        LegalTransactionCompletionState.Snapshot<String> atBoundary = state.snapshot();
        assertThat(atBoundary.persistence())
                .isEqualTo(LegalTransactionCompletionState.Persistence.UNKNOWN);
        assertThat(atBoundary.receipt()).isEmpty();

        state.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        LegalTransactionCompletionState.Snapshot<String> committed = state.snapshot();
        assertThat(committed.completion())
                .isEqualTo(LegalTransactionCompletionState.Completion.COMMITTED);
        assertThat(committed.persistence())
                .isEqualTo(LegalTransactionCompletionState.Persistence.PERSISTED);
        assertThat(committed.receipt()).contains("receipt");
    }

    @Test
    void rollbackIsKnownFalseAndUnknownAlwaysHidesReceipt() {
        LegalTransactionCompletionState<String> rolledBack = stateAtCommitBoundary("rollback");
        rolledBack.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertThat(rolledBack.snapshot().persistence())
                .isEqualTo(LegalTransactionCompletionState.Persistence.NOT_PERSISTED);
        assertThat(rolledBack.snapshot().receipt()).isEmpty();

        TransactionSynchronizationManager.clear();
        LegalTransactionCompletionState<String> unknown = stateAtCommitBoundary("unknown");
        unknown.afterCompletion(TransactionSynchronization.STATUS_UNKNOWN);
        assertThat(unknown.snapshot().persistence())
                .isEqualTo(LegalTransactionCompletionState.Persistence.UNKNOWN);
        assertThat(unknown.snapshot().receipt()).isEmpty();
    }

    @Test
    void committedStatusWithoutTheCompleteSequenceDegradesToUnknown() {
        LegalTransactionCompletionState<String> state = new LegalTransactionCompletionState<>();
        activateAndStart(state);
        state.receiptDelivered("tentative");

        state.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);

        assertThat(state.snapshot().completion())
                .isEqualTo(LegalTransactionCompletionState.Completion.UNKNOWN);
        assertThat(state.snapshot().persistence())
                .isEqualTo(LegalTransactionCompletionState.Persistence.UNKNOWN);
        assertThat(state.snapshot().receipt()).isEmpty();
    }

    @Test
    void normalTransactionReturnConfirmsOnlyAReceiptAndNeverOverridesTerminalEvidence() {
        LegalTransactionCompletionState<String> committed =
                new LegalTransactionCompletionState<>();
        activateAndStart(committed);
        committed.receiptDelivered("receipt");
        committed.transactionReturnedNormally();
        assertThat(committed.snapshot().persistence())
                .isEqualTo(LegalTransactionCompletionState.Persistence.PERSISTED);
        assertThat(committed.snapshot().receipt()).contains("receipt");

        TransactionSynchronizationManager.clear();
        LegalTransactionCompletionState<String> rolledBack = stateAtCommitBoundary("rollback");
        rolledBack.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertThatThrownBy(rolledBack::transactionReturnedNormally)
                .isInstanceOf(IllegalStateException.class);
        assertThat(rolledBack.snapshot().persistence())
                .isEqualTo(LegalTransactionCompletionState.Persistence.NOT_PERSISTED);
    }

    @Test
    void receiptAndCommitSequenceCannotBeMutatedOrRepeated() {
        LegalTransactionCompletionState<String> state = new LegalTransactionCompletionState<>();
        assertThatThrownBy(() -> state.receiptDelivered("early"))
                .isInstanceOf(IllegalStateException.class);

        activateAndStart(state);
        assertThatThrownBy(() -> state.receiptDelivered(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> state.beforeCommit(false))
                .isInstanceOf(IllegalStateException.class);

        state.receiptDelivered("receipt");
        assertThatThrownBy(() -> state.receiptDelivered("replacement"))
                .isInstanceOf(IllegalStateException.class);
        state.beforeCommit(false);
        assertThatThrownBy(() -> state.beforeCommit(false))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> state.receiptDelivered("late"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void wrapperCanRegisterItsOwnSynchronizationIdentity() {
        LegalTransactionCompletionState<String> state = new LegalTransactionCompletionState<>();
        TransactionSynchronization wrapper = new TransactionSynchronization() { };
        activateTransactionSynchronization();

        state.callbackStarted(wrapper);

        assertThat(TransactionSynchronizationManager.getSynchronizations())
                .containsExactly(wrapper);
        assertThat(state.snapshot().callbackStarted()).isTrue();
    }

    private static LegalTransactionCompletionState<String> stateAtCommitBoundary(String receipt) {
        LegalTransactionCompletionState<String> state = new LegalTransactionCompletionState<>();
        activateAndStart(state);
        state.receiptDelivered(receipt);
        state.beforeCommit(false);
        return state;
    }

    private static void activateAndStart(LegalTransactionCompletionState<?> state) {
        activateTransactionSynchronization();
        state.callbackStarted();
    }

    private static void activateTransactionSynchronization() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }
}
