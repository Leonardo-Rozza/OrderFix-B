package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import org.springframework.transaction.support.TransactionSynchronization;

import java.util.Objects;
import java.util.Optional;

/** Editorial facade over the neutral completion tracker with marker-only plan evidence. */
final class LegalEditorialTransactionState<R> implements TransactionSynchronization {

    enum Completion {
        NONE,
        COMMITTED,
        ROLLED_BACK,
        UNKNOWN
    }

    enum Persistence {
        PERSISTED,
        NOT_PERSISTED,
        UNKNOWN
    }

    private final LegalTransactionCompletionState<R> delegate =
            new LegalTransactionCompletionState<>();
    private boolean planConstructed;

    synchronized void callbackStarted() {
        delegate.callbackStarted(this);
    }

    synchronized void planConstructed() {
        LegalTransactionCompletionState.Snapshot<R> snapshot = delegate.snapshot();
        if (!snapshot.callbackStarted()
                || planConstructed
                || snapshot.receiptDelivered()
                || snapshot.commitBoundaryEntered()
                || snapshot.completion() != LegalTransactionCompletionState.Completion.NONE) {
            throw new IllegalStateException(
                    "El plan editorial debe registrarse una sola vez antes del receipt");
        }
        planConstructed = true;
    }

    synchronized void receiptDelivered(R receipt) {
        requirePlanConstructed();
        delegate.receiptDelivered(receipt);
    }

    @Override
    public synchronized void beforeCommit(boolean readOnly) {
        requirePlanConstructed();
        delegate.beforeCommit(readOnly);
    }

    synchronized void transactionReturnedNormally() {
        requirePlanConstructed();
        delegate.transactionReturnedNormally();
    }

    @Override
    public synchronized void afterCompletion(int status) {
        delegate.afterCompletion(status);
    }

    synchronized Snapshot<R> snapshot() {
        LegalTransactionCompletionState.Snapshot<R> snapshot = delegate.snapshot();
        return new Snapshot<>(
                snapshot.callbackStarted(),
                planConstructed,
                snapshot.receiptDelivered(),
                snapshot.commitBoundaryEntered(),
                Completion.valueOf(snapshot.completion().name()),
                Persistence.valueOf(snapshot.persistence().name()),
                snapshot.receipt());
    }

    private void requirePlanConstructed() {
        if (!planConstructed) {
            throw new IllegalStateException(
                    "La operación editorial requiere un plan construido");
        }
    }

    record Snapshot<R>(
            boolean callbackStarted,
            boolean planConstructed,
            boolean receiptDelivered,
            boolean commitBoundaryEntered,
            Completion completion,
            Persistence persistence,
            Optional<R> receipt
    ) {

        Snapshot {
            completion = Objects.requireNonNull(completion, "completion");
            persistence = Objects.requireNonNull(persistence, "persistence");
            receipt = Objects.requireNonNull(receipt, "receipt");
        }
    }
}
