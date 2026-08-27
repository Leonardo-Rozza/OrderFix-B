package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidation;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestImportResult;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestImportResult.Outcome;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestImportResult.Receipt;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LegalManifestCliExecutionStateTest {

    private static final String GOLDEN_MANIFEST =
            "/legal/manifest/release-valid-v1/publication-manifest.json";
    private static final Receipt RECEIPT = new Receipt(
            UUID.fromString("7d2df0b2-2846-43ec-8e5b-53c80f49fe19"),
            Instant.parse("2026-08-25T18:00:00.123456Z"),
            Instant.parse("2026-08-25T18:00:01.654321Z"));

    private static ValidatedRelease goldenRelease;

    @BeforeAll
    static void validateGoldenRelease() throws URISyntaxException {
        Path manifest = Path.of(Objects.requireNonNull(
                LegalManifestCliExecutionStateTest.class.getResource(GOLDEN_MANIFEST)).toURI());
        LegalManifestValidation<ValidatedRelease> validation =
                new LegalManifestValidator().validate(manifest);
        assertThat(validation.status())
                .as("issues=%s", validation.issues())
                .isEqualTo(LegalManifestStatus.PASS);
        goldenRelease = validation.value().orElseThrow();
    }

    @Test
    void recognizedImportStartsWithKnownNotPersistedEvidence() {
        LegalManifestCliExecutionState state =
                LegalManifestCliExecutionState.recognizedImport();

        LegalManifestCliExecutionState.Snapshot snapshot = state.snapshot();

        assertThat(snapshot.phase())
                .isEqualTo(LegalManifestCliExecutionState.Phase.IMPORT_RECOGNIZED);
        assertThat(snapshot.release()).isEmpty();
        assertThat(snapshot.importResult()).isEmpty();
        assertThat(snapshot.persisted()).isFalse();
        assertThat(snapshot.outcome()).isEmpty();
        assertThat(snapshot.receipt()).isEmpty();
    }

    @Test
    void validationAndContextOpeningDoNotInventATransactionCallback() {
        LegalManifestCliExecutionState state = stateWithValidatedRelease();

        assertThat(state.snapshot().release()).containsSame(goldenRelease);
        assertThat(state.snapshot().persisted()).isFalse();

        state.contextOpened();

        LegalManifestCliExecutionState.Snapshot snapshot = state.snapshot();
        assertThat(snapshot.phase())
                .isEqualTo(LegalManifestCliExecutionState.Phase.CONTEXT_OPENED);
        assertThat(snapshot.release()).containsSame(goldenRelease);
        assertThat(snapshot.persisted()).isFalse();
        assertThat(snapshot.outcome()).isEmpty();
        assertThat(snapshot.receipt()).isEmpty();
    }

    @Test
    void startedTransactionCallbackWithoutAResultUsesTheConservativeUnknownFallback() {
        LegalManifestCliExecutionState state = stateWithOpenContext();

        state.importCallbackStarted();

        LegalManifestCliExecutionState.Snapshot snapshot = state.snapshot();
        assertThat(snapshot.phase())
                .isEqualTo(LegalManifestCliExecutionState.Phase.IMPORT_CALLBACK_STARTED);
        assertThat(snapshot.persisted()).isNull();
        assertThat(snapshot.outcome()).contains(Outcome.UNKNOWN);
        assertThat(snapshot.receipt()).isEmpty();
        assertThat(snapshot.importResult()).isEmpty();
    }

    @Test
    void returnedKnownFailureOverridesCallbackUncertainty() {
        LegalManifestImportResult result = importResult(
                Boolean.FALSE,
                Optional.empty(),
                Optional.empty());
        LegalManifestCliExecutionState state = stateWithStartedCallback();

        state.importResultReceived(result);

        LegalManifestCliExecutionState.Snapshot snapshot = state.snapshot();
        assertThat(snapshot.persisted()).isFalse();
        assertThat(snapshot.outcome()).isEmpty();
        assertThat(snapshot.receipt()).isEmpty();
        assertThat(snapshot.importResult()).containsSame(result);
    }

    @Test
    void returnedKnownFailureBeforeTheCallbackRemainsAuthoritative() {
        LegalManifestImportResult result = importResult(
                Boolean.FALSE,
                Optional.empty(),
                Optional.empty());
        LegalManifestCliExecutionState state = stateWithOpenContext();

        state.importResultReceived(result);

        LegalManifestCliExecutionState.Snapshot snapshot = state.snapshot();
        assertThat(snapshot.phase())
                .isEqualTo(LegalManifestCliExecutionState.Phase.IMPORT_RESULT_RECEIVED);
        assertThat(snapshot.persisted()).isFalse();
        assertThat(snapshot.outcome()).isEmpty();
        assertThat(snapshot.receipt()).isEmpty();
        assertThat(snapshot.importResult()).containsSame(result);
    }

    @Test
    void returnedUnknownResultRemainsTheAuthority() {
        LegalManifestImportResult result = importResult(
                null,
                Optional.of(Outcome.UNKNOWN),
                Optional.empty());
        LegalManifestCliExecutionState state = stateWithStartedCallback();

        state.importResultReceived(result);

        LegalManifestCliExecutionState.Snapshot snapshot = state.snapshot();
        assertThat(snapshot.persisted()).isNull();
        assertThat(snapshot.outcome()).contains(Outcome.UNKNOWN);
        assertThat(snapshot.receipt()).isEmpty();
        assertThat(snapshot.importResult()).containsSame(result);
    }

    @Test
    void confirmedReceiptSurvivesLaterCloseOrReportBoundaryFailures() {
        LegalManifestImportResult result = importResult(
                Boolean.TRUE,
                Optional.of(Outcome.IMPORTED),
                Optional.of(RECEIPT));
        LegalManifestCliExecutionState state = stateWithStartedCallback();

        state.importResultReceived(result);
        LegalManifestCliExecutionState.Snapshot beforeLaterFailure = state.snapshot();
        assertThatThrownBy(() -> {
            throw new RuntimeException("context-close-or-report-write");
        }).isInstanceOf(RuntimeException.class);

        LegalManifestCliExecutionState.Snapshot fallback = state.snapshot();
        assertThat(beforeLaterFailure.importResult()).containsSame(result);
        assertThat(fallback.phase())
                .isEqualTo(LegalManifestCliExecutionState.Phase.IMPORT_RESULT_RECEIVED);
        assertThat(fallback.persisted()).isTrue();
        assertThat(fallback.outcome()).contains(Outcome.IMPORTED);
        assertThat(fallback.receipt()).contains(RECEIPT);
        assertThat(fallback.importResult()).containsSame(result);
    }

    @Test
    void transitionsAreStrictlyMonotonicAndRejectNullEvidence() {
        LegalManifestCliExecutionState state =
                LegalManifestCliExecutionState.recognizedImport();

        assertThatThrownBy(state::contextOpened)
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> state.releaseValidated(null))
                .isInstanceOf(NullPointerException.class);
        assertThat(state.snapshot().phase())
                .isEqualTo(LegalManifestCliExecutionState.Phase.IMPORT_RECOGNIZED);

        state.releaseValidated(goldenRelease);
        assertThatThrownBy(() -> state.releaseValidated(goldenRelease))
                .isInstanceOf(IllegalStateException.class);
        state.contextOpened();
        assertThatThrownBy(state::contextOpened)
                .isInstanceOf(IllegalStateException.class);
        state.importCallbackStarted();
        assertThatThrownBy(() -> state.importResultReceived(null))
                .isInstanceOf(NullPointerException.class);
        assertThat(state.snapshot().phase())
                .isEqualTo(LegalManifestCliExecutionState.Phase.IMPORT_CALLBACK_STARTED);
    }

    private static LegalManifestCliExecutionState stateWithValidatedRelease() {
        LegalManifestCliExecutionState state =
                LegalManifestCliExecutionState.recognizedImport();
        state.releaseValidated(goldenRelease);
        return state;
    }

    private static LegalManifestCliExecutionState stateWithOpenContext() {
        LegalManifestCliExecutionState state = stateWithValidatedRelease();
        state.contextOpened();
        return state;
    }

    private static LegalManifestCliExecutionState stateWithStartedCallback() {
        LegalManifestCliExecutionState state = stateWithOpenContext();
        state.importCallbackStarted();
        return state;
    }

    private static LegalManifestImportResult importResult(
            Boolean persisted,
            Optional<Outcome> outcome,
            Optional<Receipt> receipt) {
        LegalManifestImportResult result = mock(LegalManifestImportResult.class);
        when(result.persisted()).thenReturn(persisted);
        when(result.outcome()).thenReturn(outcome);
        when(result.receipt()).thenReturn(receipt);
        return result;
    }
}
