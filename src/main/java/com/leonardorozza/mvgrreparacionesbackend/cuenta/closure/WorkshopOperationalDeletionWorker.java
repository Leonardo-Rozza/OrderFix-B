package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;
import java.util.UUID;

import static com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.WorkshopOperationalDeletionProgress.Snapshot;
import static com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.WorkshopOperationalDeletionService.Batch;
import static com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.WorkshopOperationalDeletionService.Status;

/** Resumes from durable rows/receipts, without a second job ledger or an outer transaction. */
@Service
public final class WorkshopOperationalDeletionWorker {
    public static final int MAX_BATCHES = 8;
    private final WorkshopOperationalDeletionProgress progress;
    private final WorkshopOperationalDeletionService deletion;
    private final boolean enabled;
    private final int maxBatches;

    public WorkshopOperationalDeletionWorker(WorkshopOperationalDeletionProgress progress,
            WorkshopOperationalDeletionService deletion,
            @Value("${ordenfix.cuenta.cierre.operational-worker-enabled:false}") boolean enabled,
            @Value("${ordenfix.cuenta.cierre.operational-worker-max-batches:4}") int maxBatches) {
        this.progress = Objects.requireNonNull(progress);
        this.deletion = Objects.requireNonNull(deletion);
        this.enabled = enabled;
        if (maxBatches < 1 || maxBatches > MAX_BATCHES) throw new IllegalArgumentException("Invalid deletion batch budget");
        this.maxBatches = maxBatches;
    }

    /** Trusted internal scope. A restart reconstructs work; no response counts are accumulated. */
    public Result run(long tallerId, UUID closureReference) {
        if (tallerId <= 0 || closureReference == null) throw new Rejected(Rejected.Code.INVALID_TARGET);
        if (!enabled) throw new Rejected(Rejected.Code.DISABLED);
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new Rejected(Rejected.Code.CALLER_TRANSACTION);
        Snapshot current = observe(tallerId, closureReference);
        for (int attempts = 0; ; attempts++) {
            State observedState = settledState(current);
            if (observedState != null) return new Result(observedState, attempts, current);
            if (attempts == maxBatches) return new Result(State.WORK_REMAINS, attempts, current);
            var category = Objects.requireNonNull(current.firstPending());
            Batch batch;
            try {
                batch = Objects.requireNonNull(deletion.deleteBatch(tallerId, closureReference, UUID.randomUUID(), category));
            } catch (RuntimeException unavailable) {
                // A commit may have succeeded before its reply was lost. Reobserve once, never
                // repeat a mutation in this invocation or infer a permanent cause from SQL failure.
                try { current = observe(tallerId, closureReference); }
                catch (Rejected observationUnavailable) { return new Result(State.RETRY_LATER, attempts + 1, current); }
                State freshState = settledState(current);
                return new Result(freshState == null ? State.RETRY_LATER : freshState, attempts + 1, current);
            }
            try { current = observe(tallerId, closureReference); }
            catch (Rejected observationUnavailable) { return new Result(State.RETRY_LATER, attempts + 1, current); }
            State freshState = settledState(current);
            if (freshState != null) return new Result(freshState, attempts + 1, current);
            if (batch.status() == Status.EMPTY && current.remaining().get(category) > 0)
                return new Result(State.DEPENDENCIES_PENDING, attempts + 1, current);
            // REUSED.remaining is historical. Only the next fresh snapshot drives continuation.
        }
    }

    private Snapshot observe(long tallerId, UUID reference) {
        try {
            Snapshot snapshot = Objects.requireNonNull(progress.read(tallerId, reference));
            if (snapshot.tallerId() != tallerId || !snapshot.closureReference().equals(reference))
                throw new Rejected(Rejected.Code.UNAVAILABLE);
            return snapshot;
        } catch (RuntimeException unavailable) {
            throw new Rejected(Rejected.Code.UNAVAILABLE);
        }
    }

    private static State settledState(Snapshot snapshot) {
        if (!snapshot.graceExpired()) return State.WAITING_GRACE;
        if (snapshot.photosPending()) return State.PHOTOS_PENDING;
        if (!snapshot.hasRows()) return State.NO_PENDING_ROWS;
        return null;
    }

    public enum State { NO_PENDING_ROWS, WORK_REMAINS, PHOTOS_PENDING, DEPENDENCIES_PENDING, WAITING_GRACE, RETRY_LATER }

    /** observed is the last successful snapshot, which can precede an uncertain final attempt. */
    public record Result(State state, int attempts, Snapshot observed) {
        public Result {
            Objects.requireNonNull(state); Objects.requireNonNull(observed);
            if (attempts < 0 || attempts > MAX_BATCHES
                    || (state == State.NO_PENDING_ROWS && (observed.hasRows() || observed.photosPending() || !observed.graceExpired()))
                    || (state == State.WAITING_GRACE && observed.graceExpired())
                    || (state == State.PHOTOS_PENDING && !observed.photosPending())
                    || ((state == State.WORK_REMAINS || state == State.DEPENDENCIES_PENDING) && (!observed.hasRows() || attempts == 0)))
                throw new IllegalArgumentException("Invalid deletion worker result");
        }
        @Override public String toString() { return "OperationalDeletionWorkerResult[redacted]"; }
    }

    public static final class Rejected extends RuntimeException {
        public enum Code { INVALID_TARGET, DISABLED, CALLER_TRANSACTION, UNAVAILABLE }
        private final Code code;
        private Rejected(Code code) { super("No se pudo continuar el borrado operativo."); this.code = code; }
        public Code code() { return code; }
    }
}
