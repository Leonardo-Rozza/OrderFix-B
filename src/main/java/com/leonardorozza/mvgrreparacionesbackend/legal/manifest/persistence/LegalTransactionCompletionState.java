package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;
import java.util.Optional;

/** Tracks monotonic commit evidence while keeping tentative receipts private. */
final class LegalTransactionCompletionState<R> implements TransactionSynchronization {

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

    synchronized void callbackStarted() {
        callbackStarted(this);
    }

    /** Registers a compatibility wrapper while this core retains all completion state. */
    synchronized void callbackStarted(TransactionSynchronization registrationTarget) {
        Objects.requireNonNull(registrationTarget, "registrationTarget");
        if (callbackStarted) {
            throw new IllegalStateException("El callback transaccional ya fue iniciado");
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "La operación requiere una transacción sincronizada activa");
        }
        TransactionSynchronizationManager.registerSynchronization(registrationTarget);
        synchronizationRegistered = true;
        callbackStarted = true;
    }

    synchronized void receiptDelivered(R receipt) {
        Objects.requireNonNull(receipt, "receipt");
        requireActiveCallback();
        if (deliveredReceipt != null) {
            throw new IllegalStateException("La transacción ya entregó un receipt");
        }
        if (commitBoundaryEntered || completion != Completion.NONE) {
            throw new IllegalStateException(
                    "El receipt no puede cambiar después de iniciar la finalización");
        }
        deliveredReceipt = receipt;
    }

    @Override
    public synchronized void beforeCommit(boolean readOnly) {
        requireActiveCallback();
        if (deliveredReceipt == null) {
            throw new IllegalStateException(
                    "No se puede intentar el commit sin un receipt");
        }
        if (commitBoundaryEntered || completion != Completion.NONE) {
            throw new IllegalStateException("La fase de commit ya fue iniciada");
        }
        commitBoundaryEntered = true;
    }

    synchronized void transactionReturnedNormally() {
        requireActiveCallback();
        if (deliveredReceipt == null) {
            throw new IllegalStateException(
                    "La transacción no puede confirmar una operación sin receipt");
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
                    "El callback no registró su sincronización transaccional");
        }
    }

    private boolean validCommittedSequence() {
        return callbackStarted
                && synchronizationRegistered
                && deliveredReceipt != null
                && commitBoundaryEntered;
    }

    record Snapshot<R>(
            boolean callbackStarted,
            boolean receiptDelivered,
            boolean commitBoundaryEntered,
            Completion completion,
            Persistence persistence,
            Optional<R> receipt
    ) {

        Snapshot {
            Objects.requireNonNull(completion, "completion");
            Objects.requireNonNull(persistence, "persistence");
            receipt = Objects.requireNonNull(receipt, "receipt");
        }
    }
}
