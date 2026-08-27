package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestImportResult;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestImportResult.Outcome;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestImportResult.Receipt;

import java.util.Objects;
import java.util.Optional;

/**
 * Retains monotonic, safe evidence across the import command boundary.
 *
 * <p>The CLI cannot observe the transaction callbacks owned by the import service. Consequently,
 * merely opening its isolated application context is still known not to have invoked the import.
 * Once the service reports that its protected transaction callback actually started, however, an
 * escaped failure without a returned result is treated conservatively as an indeterminate commit
 * outcome. A returned {@link LegalManifestImportResult} is the terminal authority and remains
 * available after context close or report-write failures.</p>
 */
final class LegalManifestCliExecutionState {

    enum Phase {
        IMPORT_RECOGNIZED,
        RELEASE_VALIDATED,
        CONTEXT_OPENED,
        IMPORT_CALLBACK_STARTED,
        IMPORT_RESULT_RECEIVED
    }

    private Phase phase = Phase.IMPORT_RECOGNIZED;
    private ValidatedRelease release;
    private LegalManifestImportResult importResult;

    private LegalManifestCliExecutionState() {
    }

    /** Creates the state as soon as the raw command is recognized as {@code import}. */
    static LegalManifestCliExecutionState recognizedImport() {
        return new LegalManifestCliExecutionState();
    }

    synchronized void releaseValidated(ValidatedRelease validatedRelease) {
        requirePhase(Phase.IMPORT_RECOGNIZED);
        release = Objects.requireNonNull(validatedRelease, "release");
        phase = Phase.RELEASE_VALIDATED;
    }

    /** Records that the isolated context was fully constructed and may now resolve the service. */
    synchronized void contextOpened() {
        requirePhase(Phase.RELEASE_VALIDATED);
        phase = Phase.CONTEXT_OPENED;
    }

    /** Marks the signal emitted from the first line of the protected transaction callback. */
    synchronized void importCallbackStarted() {
        requirePhase(Phase.CONTEXT_OPENED);
        phase = Phase.IMPORT_CALLBACK_STARTED;
    }

    /** Stores the service's terminal evidence without reinterpreting its persistence outcome. */
    synchronized void importResultReceived(LegalManifestImportResult result) {
        if (phase != Phase.CONTEXT_OPENED && phase != Phase.IMPORT_CALLBACK_STARTED) {
            throw new IllegalStateException(
                    "Transición inválida del estado de ejecución del importador legal");
        }
        importResult = Objects.requireNonNull(result, "result");
        phase = Phase.IMPORT_RESULT_RECEIVED;
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(phase, release, importResult);
    }

    private void requirePhase(Phase expected) {
        if (phase != expected) {
            throw new IllegalStateException(
                    "Transición inválida del estado de ejecución del importador legal");
        }
    }

    /** Immutable evidence used to select a truthful v2 report after any later boundary failure. */
    static final class Snapshot {

        private final Phase phase;
        private final ValidatedRelease release;
        private final LegalManifestImportResult importResult;

        private Snapshot(
                Phase phase,
                ValidatedRelease release,
                LegalManifestImportResult importResult) {
            this.phase = Objects.requireNonNull(phase, "phase");
            this.release = release;
            this.importResult = importResult;
        }

        Phase phase() {
            return phase;
        }

        Optional<ValidatedRelease> release() {
            return Optional.ofNullable(release);
        }

        Optional<LegalManifestImportResult> importResult() {
            return Optional.ofNullable(importResult);
        }

        /** Returns true, false, or null when the service invocation has an unknown outcome. */
        Boolean persisted() {
            if (importResult != null) {
                return importResult.persisted();
            }
            return phase == Phase.IMPORT_CALLBACK_STARTED ? null : Boolean.FALSE;
        }

        Optional<Outcome> outcome() {
            if (importResult != null) {
                return importResult.outcome();
            }
            return phase == Phase.IMPORT_CALLBACK_STARTED
                    ? Optional.of(Outcome.UNKNOWN)
                    : Optional.empty();
        }

        Optional<Receipt> receipt() {
            return importResult == null ? Optional.empty() : importResult.receipt();
        }
    }
}
