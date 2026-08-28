package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import org.springframework.transaction.support.TransactionSynchronization;

import java.util.Objects;
import java.util.Optional;

/**
 * Import-compatible facade over the neutral transaction completion tracker.
 *
 * <p>The class, erased method signatures, nested enum order and snapshot API remain unchanged for
 * the import path. The facade registers itself as Spring synchronization so existing observers
 * also retain the same identity while all state-machine behavior is shared.</p>
 */
final class LegalImportTransactionState<R> implements TransactionSynchronization {

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

    synchronized void callbackStarted() {
        delegate.callbackStarted(this);
    }

    synchronized void receiptDelivered(R receipt) {
        delegate.receiptDelivered(receipt);
    }

    @Override
    public synchronized void beforeCommit(boolean readOnly) {
        delegate.beforeCommit(readOnly);
    }

    synchronized void transactionReturnedNormally() {
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
                snapshot.receiptDelivered(),
                snapshot.commitBoundaryEntered(),
                Completion.valueOf(snapshot.completion().name()),
                Persistence.valueOf(snapshot.persistence().name()),
                snapshot.receipt());
    }

    /** Immutable, redacted view safe to pass to the import result mapper. */
    static final class Snapshot<R> {

        private final boolean callbackStarted;
        private final boolean receiptDelivered;
        private final boolean commitBoundaryEntered;
        private final Completion completion;
        private final Persistence persistence;
        private final Optional<R> receipt;

        private Snapshot(
                boolean callbackStarted,
                boolean receiptDelivered,
                boolean commitBoundaryEntered,
                Completion completion,
                Persistence persistence,
                Optional<R> receipt) {
            this.callbackStarted = callbackStarted;
            this.receiptDelivered = receiptDelivered;
            this.commitBoundaryEntered = commitBoundaryEntered;
            this.completion = Objects.requireNonNull(completion, "completion");
            this.persistence = Objects.requireNonNull(persistence, "persistence");
            this.receipt = Objects.requireNonNull(receipt, "receipt");
        }

        boolean callbackStarted() {
            return callbackStarted;
        }

        boolean receiptDelivered() {
            return receiptDelivered;
        }

        boolean commitBoundaryEntered() {
            return commitBoundaryEntered;
        }

        Completion completion() {
            return completion;
        }

        Persistence persistence() {
            return persistence;
        }

        Optional<R> receipt() {
            return receipt;
        }
    }
}
