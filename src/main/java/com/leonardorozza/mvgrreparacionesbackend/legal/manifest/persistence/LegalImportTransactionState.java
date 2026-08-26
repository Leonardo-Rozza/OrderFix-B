package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;
import java.util.Optional;

/**
 * Tracks the monotonic transaction evidence for one legal import attempt.
 *
 * <p>The receipt remains private until Spring confirms {@link Completion#COMMITTED}. A commit
 * boundary without completion, or any {@link TransactionSynchronization#STATUS_UNKNOWN}, is
 * deliberately indeterminate and cannot be downgraded to a known rollback.</p>
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

    private boolean callbackStarted;
    private boolean synchronizationRegistered;
    private R deliveredReceipt;
    private boolean commitBoundaryEntered;
    private Completion completion = Completion.NONE;

    /** Registers this per-attempt tracker at the first line of the protected callback. */
    synchronized void callbackStarted() {
        if (callbackStarted) {
            throw new IllegalStateException("El callback de importación ya fue iniciado");
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "La importación requiere una transacción sincronizada activa");
        }
        TransactionSynchronizationManager.registerSynchronization(this);
        synchronizationRegistered = true;
        callbackStarted = true;
    }

    /** Stores immutable receipt evidence immediately before the protected callback returns it. */
    synchronized void receiptDelivered(R receipt) {
        Objects.requireNonNull(receipt, "receipt");
        requireActiveCallback();
        if (deliveredReceipt != null) {
            throw new IllegalStateException("La importación ya entregó un receipt");
        }
        if (commitBoundaryEntered || completion != Completion.NONE) {
            throw new IllegalStateException(
                    "El receipt no puede cambiar después de iniciar la finalización");
        }
        deliveredReceipt = receipt;
    }

    /**
     * Marks entry into Spring's commit phase, not proof that the database committed.
     *
     * <p>Spring invokes this callback only on the commit path. Failing before a receipt is
     * available aborts the commit and lets the transaction manager roll the attempt back.</p>
     */
    @Override
    public synchronized void beforeCommit(boolean readOnly) {
        requireActiveCallback();
        if (deliveredReceipt == null) {
            throw new IllegalStateException(
                    "No se puede intentar el commit sin un receipt de importación");
        }
        if (commitBoundaryEntered || completion != Completion.NONE) {
            throw new IllegalStateException("La fase de commit ya fue iniciada");
        }
        commitBoundaryEntered = true;
    }

    /**
     * Records that {@code TransactionTemplate.execute(...)} returned normally to its caller.
     *
     * <p>A normal return is independent confirmation that the transaction manager completed its
     * commit path. It may fill in a missing completion callback, but it never overrides explicit
     * rollback or indeterminate evidence.</p>
     */
    synchronized void transactionReturnedNormally() {
        requireActiveCallback();
        if (deliveredReceipt == null) {
            throw new IllegalStateException(
                    "La transacción no puede confirmar una importación sin receipt");
        }
        switch (completion) {
            case NONE -> completion = Completion.COMMITTED;
            case COMMITTED -> {
                // Idempotent confirmation from the transaction manager return boundary.
            }
            case ROLLED_BACK, UNKNOWN -> throw new IllegalStateException(
                    "El retorno normal contradice la finalización transaccional observada");
            default -> throw new IllegalStateException("Estado transaccional desconocido");
        }
    }

    /** Records Spring's terminal evidence without ever throwing from the completion callback. */
    @Override
    public synchronized void afterCompletion(int status) {
        if (completion != Completion.NONE) {
            return;
        }
        completion = switch (status) {
            case STATUS_COMMITTED -> validCommittedSequence()
                    ? Completion.COMMITTED
                    : Completion.UNKNOWN;
            case STATUS_ROLLED_BACK -> Completion.ROLLED_BACK;
            case STATUS_UNKNOWN -> Completion.UNKNOWN;
            default -> Completion.UNKNOWN;
        };
    }

    synchronized Snapshot<R> snapshot() {
        Persistence persistence;
        Optional<R> safeReceipt = Optional.empty();
        switch (completion) {
            case COMMITTED -> {
                persistence = Persistence.PERSISTED;
                safeReceipt = Optional.of(deliveredReceipt);
            }
            case ROLLED_BACK -> persistence = Persistence.NOT_PERSISTED;
            case UNKNOWN -> persistence = Persistence.UNKNOWN;
            case NONE -> persistence = commitBoundaryEntered
                    ? Persistence.UNKNOWN
                    : Persistence.NOT_PERSISTED;
            default -> throw new IllegalStateException("Estado transaccional desconocido");
        }
        return new Snapshot<>(
                callbackStarted,
                deliveredReceipt != null,
                commitBoundaryEntered,
                completion,
                persistence,
                safeReceipt);
    }

    private void requireActiveCallback() {
        if (!callbackStarted || !synchronizationRegistered) {
            throw new IllegalStateException(
                    "El callback de importación no registró su sincronización");
        }
    }

    private boolean validCommittedSequence() {
        return callbackStarted
                && synchronizationRegistered
                && deliveredReceipt != null
                && commitBoundaryEntered;
    }

    /** Immutable, redacted view safe to pass to the result mapper outside the transaction. */
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
