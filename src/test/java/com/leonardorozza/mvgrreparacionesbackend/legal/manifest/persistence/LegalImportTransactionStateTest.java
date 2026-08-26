package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegalImportTransactionStateTest {

    @AfterEach
    void clearTransactionSynchronization() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void registersExactlyOnceWhenTheProtectedCallbackStarts() {
        LegalImportTransactionState<String> state = new LegalImportTransactionState<>();
        activateTransactionSynchronization();

        state.callbackStarted();

        assertThat(TransactionSynchronizationManager.getSynchronizations())
                .containsExactly(state);
        assertThat(state.snapshot().callbackStarted()).isTrue();
        assertThatThrownBy(state::callbackStarted)
                .isInstanceOf(IllegalStateException.class);
        assertThat(TransactionSynchronizationManager.getSynchronizations())
                .containsExactly(state);
    }

    @Test
    void refusesToStartWithoutAnActualSynchronizedTransaction() {
        LegalImportTransactionState<String> withoutSynchronization =
                new LegalImportTransactionState<>();

        assertThatThrownBy(withoutSynchronization::callbackStarted)
                .isInstanceOf(IllegalStateException.class);
        assertThat(withoutSynchronization.snapshot().callbackStarted()).isFalse();

        TransactionSynchronizationManager.clear();
        TransactionSynchronizationManager.initSynchronization();
        LegalImportTransactionState<String> withoutTransaction =
                new LegalImportTransactionState<>();

        assertThatThrownBy(withoutTransaction::callbackStarted)
                .isInstanceOf(IllegalStateException.class);
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    }

    @Test
    void requiresOneNonNullReceiptAfterTheCallbackStarted() {
        LegalImportTransactionState<String> state = new LegalImportTransactionState<>();

        assertThatThrownBy(() -> state.receiptDelivered("too early"))
                .isInstanceOf(IllegalStateException.class);

        activateAndStart(state);
        assertThatThrownBy(() -> state.receiptDelivered(null))
                .isInstanceOf(NullPointerException.class);

        state.receiptDelivered("receipt");

        assertThat(state.snapshot().receiptDelivered()).isTrue();
        assertThatThrownBy(() -> state.receiptDelivered("replacement"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void hidesDeliveredReceiptUntilCommitIsConfirmed() {
        LegalImportTransactionState<String> state = new LegalImportTransactionState<>();
        activateAndStart(state);
        state.receiptDelivered("receipt");

        LegalImportTransactionState.Snapshot<String> snapshot = state.snapshot();

        assertThat(snapshot.completion())
                .isEqualTo(LegalImportTransactionState.Completion.NONE);
        assertThat(snapshot.persistence())
                .isEqualTo(LegalImportTransactionState.Persistence.NOT_PERSISTED);
        assertThat(snapshot.receipt()).isEmpty();
    }

    @Test
    void beforeCommitRequiresAReceiptAndCreatesTheUnknownFallback() {
        LegalImportTransactionState<String> missingReceipt = new LegalImportTransactionState<>();
        activateAndStart(missingReceipt);

        assertThatThrownBy(() -> missingReceipt.beforeCommit(false))
                .isInstanceOf(IllegalStateException.class);
        assertThat(missingReceipt.snapshot().commitBoundaryEntered()).isFalse();

        TransactionSynchronizationManager.clear();
        LegalImportTransactionState<String> state = new LegalImportTransactionState<>();
        activateAndStart(state);
        state.receiptDelivered("receipt");

        state.beforeCommit(false);

        LegalImportTransactionState.Snapshot<String> snapshot = state.snapshot();
        assertThat(snapshot.commitBoundaryEntered()).isTrue();
        assertThat(snapshot.completion())
                .isEqualTo(LegalImportTransactionState.Completion.NONE);
        assertThat(snapshot.persistence())
                .isEqualTo(LegalImportTransactionState.Persistence.UNKNOWN);
        assertThat(snapshot.receipt()).isEmpty();
        assertThatThrownBy(() -> state.beforeCommit(false))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void committedCompletionConfirmsAndExposesOnlyTheDeliveredReceipt() {
        LegalImportTransactionState<String> state = stateAtCommitBoundary("receipt");

        state.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);

        LegalImportTransactionState.Snapshot<String> snapshot = state.snapshot();
        assertThat(snapshot.completion())
                .isEqualTo(LegalImportTransactionState.Completion.COMMITTED);
        assertThat(snapshot.persistence())
                .isEqualTo(LegalImportTransactionState.Persistence.PERSISTED);
        assertThat(snapshot.receipt()).contains("receipt");
    }

    @Test
    void normalTransactionReturnIsAuthoritativeWhenCompletionCallbackIsMissing() {
        LegalImportTransactionState<String> state = new LegalImportTransactionState<>();
        activateAndStart(state);
        state.receiptDelivered("receipt");

        state.transactionReturnedNormally();

        LegalImportTransactionState.Snapshot<String> snapshot = state.snapshot();
        assertThat(snapshot.completion())
                .isEqualTo(LegalImportTransactionState.Completion.COMMITTED);
        assertThat(snapshot.persistence())
                .isEqualTo(LegalImportTransactionState.Persistence.PERSISTED);
        assertThat(snapshot.receipt()).contains("receipt");

        state.transactionReturnedNormally();
        assertThat(state.snapshot().completion())
                .isEqualTo(LegalImportTransactionState.Completion.COMMITTED);
    }

    @Test
    void normalReturnRequiresAReceiptAndNeverOverwritesOtherTerminalEvidence() {
        LegalImportTransactionState<String> missingReceipt = new LegalImportTransactionState<>();
        activateAndStart(missingReceipt);

        assertThatThrownBy(missingReceipt::transactionReturnedNormally)
                .isInstanceOf(IllegalStateException.class);

        TransactionSynchronizationManager.clear();
        LegalImportTransactionState<String> rolledBack =
                stateAtCommitBoundary("rolled-back receipt");
        rolledBack.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        assertThatThrownBy(rolledBack::transactionReturnedNormally)
                .isInstanceOf(IllegalStateException.class);
        assertThat(rolledBack.snapshot().persistence())
                .isEqualTo(LegalImportTransactionState.Persistence.NOT_PERSISTED);
        assertThat(rolledBack.snapshot().receipt()).isEmpty();

        TransactionSynchronizationManager.clear();
        LegalImportTransactionState<String> unknown =
                stateAtCommitBoundary("unknown receipt");
        unknown.afterCompletion(TransactionSynchronization.STATUS_UNKNOWN);

        assertThatThrownBy(unknown::transactionReturnedNormally)
                .isInstanceOf(IllegalStateException.class);
        assertThat(unknown.snapshot().persistence())
                .isEqualTo(LegalImportTransactionState.Persistence.UNKNOWN);
        assertThat(unknown.snapshot().receipt()).isEmpty();
    }

    @Test
    void rolledBackCompletionIsKnownFalseAndNeverExposesTheReceipt() {
        LegalImportTransactionState<String> state = stateAtCommitBoundary("receipt");

        state.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        LegalImportTransactionState.Snapshot<String> snapshot = state.snapshot();
        assertThat(snapshot.completion())
                .isEqualTo(LegalImportTransactionState.Completion.ROLLED_BACK);
        assertThat(snapshot.persistence())
                .isEqualTo(LegalImportTransactionState.Persistence.NOT_PERSISTED);
        assertThat(snapshot.receipt()).isEmpty();
    }

    @Test
    void everyUnknownCompletionRemainsUnknownAndHidesAnyReceipt() {
        LegalImportTransactionState<String> beforeCommit = new LegalImportTransactionState<>();
        activateAndStart(beforeCommit);

        beforeCommit.afterCompletion(TransactionSynchronization.STATUS_UNKNOWN);

        assertThat(beforeCommit.snapshot().persistence())
                .isEqualTo(LegalImportTransactionState.Persistence.UNKNOWN);
        assertThat(beforeCommit.snapshot().receipt()).isEmpty();

        TransactionSynchronizationManager.clear();
        LegalImportTransactionState<String> afterCommitBoundary =
                stateAtCommitBoundary("receipt");

        afterCommitBoundary.afterCompletion(TransactionSynchronization.STATUS_UNKNOWN);

        assertThat(afterCommitBoundary.snapshot().persistence())
                .isEqualTo(LegalImportTransactionState.Persistence.UNKNOWN);
        assertThat(afterCommitBoundary.snapshot().receipt()).isEmpty();
    }

    @Test
    void invalidCommittedSequenceDegradesToUnknownInsteadOfInventingPersistence() {
        LegalImportTransactionState<String> state = new LegalImportTransactionState<>();
        activateAndStart(state);
        state.receiptDelivered("receipt");

        state.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);

        LegalImportTransactionState.Snapshot<String> snapshot = state.snapshot();
        assertThat(snapshot.completion())
                .isEqualTo(LegalImportTransactionState.Completion.UNKNOWN);
        assertThat(snapshot.persistence())
                .isEqualTo(LegalImportTransactionState.Persistence.UNKNOWN);
        assertThat(snapshot.receipt()).isEmpty();
    }

    @Test
    void terminalCompletionCannotBeOverwrittenByLaterCallbacks() {
        LegalImportTransactionState<String> state = stateAtCommitBoundary("receipt");
        state.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);

        state.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        state.afterCompletion(TransactionSynchronization.STATUS_UNKNOWN);

        LegalImportTransactionState.Snapshot<String> snapshot = state.snapshot();
        assertThat(snapshot.completion())
                .isEqualTo(LegalImportTransactionState.Completion.COMMITTED);
        assertThat(snapshot.persistence())
                .isEqualTo(LegalImportTransactionState.Persistence.PERSISTED);
        assertThat(snapshot.receipt()).contains("receipt");
        assertThatThrownBy(() -> state.receiptDelivered("late"))
                .isInstanceOf(IllegalStateException.class);
    }

    private static LegalImportTransactionState<String> stateAtCommitBoundary(String receipt) {
        LegalImportTransactionState<String> state = new LegalImportTransactionState<>();
        activateAndStart(state);
        state.receiptDelivered(receipt);
        state.beforeCommit(false);
        return state;
    }

    private static void activateAndStart(LegalImportTransactionState<?> state) {
        activateTransactionSynchronization();
        state.callbackStarted();
    }

    private static void activateTransactionSynchronization() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }
}
