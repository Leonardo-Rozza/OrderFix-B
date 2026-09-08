package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalPublicRequirementsValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection.DocumentProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection.RequirementProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceValidationException.Motivo;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalRegistrationFailure.Reason;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoActoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class LegalRegistrationFailureTest {
    private static final LegalRegistrationReceipt FRESH = receipt(false, 11);
    private static final LegalRegistrationReceipt REPLAY = receipt(true, 11);

    @ParameterizedTest @EnumSource(LegalTransactionCompletionState.Completion.class)
    void freshAttemptPreservesItsDeliveryCompletionAndNeverPromotesTentativeIds(
            LegalTransactionCompletionState.Completion completion) {
        var state = state(completion, FRESH);
        var failure = new LegalRegistrationFailure(Reason.UNAVAILABLE, state, null, new IllegalStateException("fixture"));
        assertThat(failure.completion().name()).isEqualTo(completion.name());
        assertThat(failure.persistence().name()).isEqualTo(state.persistence().name());
        assertThat(failure.confirmedReceipt()).isEqualTo(state.receipt());
    }

    @ParameterizedTest @EnumSource(LegalTransactionCompletionState.Completion.class)
    void accreditedReplayRemainsPersistedEvenWhenItsDeliveryDidNotCommit(
            LegalTransactionCompletionState.Completion completion) {
        var failure = new LegalRegistrationFailure(Reason.UNAVAILABLE, state(completion, REPLAY), REPLAY,
                new IllegalStateException("delivery fixture"));
        assertThat(failure.completion().name()).isEqualTo(completion.name());
        assertThat(failure.persistence()).isEqualTo(LegalRegistrationFailure.Persistence.PERSISTED);
        assertThat(failure.confirmedReceipt()).containsSame(REPLAY);
        assertThat(failure.reason()).isEqualTo(Reason.UNAVAILABLE);
        assertThat(failure.validation()).isEmpty();
    }

    @Test void attemptedCommitWithoutCompletionOrReplayRemainsUnknown() {
        var state = new LegalTransactionCompletionState.Snapshot<LegalRegistrationReceipt>(true, true, true,
                LegalTransactionCompletionState.Completion.NONE, LegalTransactionCompletionState.Persistence.UNKNOWN, Optional.empty());
        var failure = new LegalRegistrationFailure(Reason.UNAVAILABLE, state, null, new IllegalStateException("commit fixture"));
        assertThat(failure.completion()).isEqualTo(LegalRegistrationFailure.Completion.NONE);
        assertThat(failure.persistence()).isEqualTo(LegalRegistrationFailure.Persistence.UNKNOWN);
        assertThat(failure.confirmedReceipt()).isEmpty();
    }

    @Test void replayEvidenceCannotBeInventedFromANewReceiptOrContradictConfirmedIdentity() {
        assertThatThrownBy(() -> new LegalRegistrationFailure(Reason.UNAVAILABLE,
                state(LegalTransactionCompletionState.Completion.ROLLED_BACK, FRESH), FRESH, new IllegalStateException()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LegalRegistrationFailure(Reason.UNAVAILABLE,
                state(LegalTransactionCompletionState.Completion.COMMITTED, receipt(true, 99)), REPLAY, new IllegalStateException()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LegalRegistrationFailure(Reason.UNAVAILABLE,
                state(LegalTransactionCompletionState.Completion.COMMITTED, FRESH), REPLAY, new IllegalStateException()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void expectedValidationRetainsItsPublicSnapshotOrReasonsWithoutExposingCauseDiagnostics() {
        var document = new DocumentProjection(new UUID(0, 1), TipoDocumentoLegal.TERMINOS_SERVICIO, "1.0.0", "Documento",
                "# Contenido\n", sha("# Contenido\n"), OffsetDateTime.parse("2020-01-01T00:00:00Z"), LocaleLegal.ES_AR);
        var requirement = new RequirementProjection(new UUID(0, 2), ContextoLegal.REGISTRO, TipoActoLegal.ACEPTACION,
                "Confirmo el registro.", sha("Confirmo el registro."), List.of(document), true);
        var current = new LegalPublicRequirementsValidator().validate(new LegalRequiredSetProjection(
                ContextoLegal.REGISTRO, LocaleLegal.ES_AR, List.of(requirement)));
        var stale = LegalRegistrationValidationException.stale("sha256:" + "a".repeat(64), current);
        var rejected = new LegalRegistrationFailure(Reason.STALE, state(LegalTransactionCompletionState.Completion.ROLLED_BACK, FRESH), null, stale);
        assertThat(rejected.validation()).containsSame(stale);
        assertThat(rejected.validation().orElseThrow().currentRequirements()).isSameAs(current);
        var invalid = LegalRegistrationValidationException.invalid(List.of(Motivo.REQUISITO_FALTANTE));
        assertThat(new LegalRegistrationFailure(Reason.INVALID, state(LegalTransactionCompletionState.Completion.ROLLED_BACK, FRESH), null, invalid)
                .validation()).containsSame(invalid);
        String privateDiagnostics = "private-password private@example.invalid 198.51.100.7 user-agent-fixture";
        var cause = new IllegalStateException(privateDiagnostics);
        var operational = new LegalRegistrationFailure(Reason.UNAVAILABLE,
                state(LegalTransactionCompletionState.Completion.UNKNOWN, FRESH), null, cause);
        assertThat(operational.getMessage() + operational.toString()).doesNotContain(privateDiagnostics,
                "private-password", "private@example.invalid", "198.51.100.7", "user-agent-fixture");
        assertThat(operational.getCause()).isSameAs(cause);
        assertThat(operational.validation()).isEmpty();
        assertThat(operational.confirmedReceipt()).isEmpty();
    }

    private static LegalTransactionCompletionState.Snapshot<LegalRegistrationReceipt> state(
            LegalTransactionCompletionState.Completion completion, LegalRegistrationReceipt committedReceipt) {
        var persistence = switch (completion) {
            case COMMITTED -> LegalTransactionCompletionState.Persistence.PERSISTED;
            case UNKNOWN -> LegalTransactionCompletionState.Persistence.UNKNOWN;
            case NONE, ROLLED_BACK -> LegalTransactionCompletionState.Persistence.NOT_PERSISTED;
        };
        return new LegalTransactionCompletionState.Snapshot<>(completion != LegalTransactionCompletionState.Completion.NONE,
                completion != LegalTransactionCompletionState.Completion.NONE,
                completion == LegalTransactionCompletionState.Completion.COMMITTED || completion == LegalTransactionCompletionState.Completion.UNKNOWN,
                completion, persistence, completion == LegalTransactionCompletionState.Completion.COMMITTED
                ? Optional.of(committedReceipt) : Optional.empty());
    }

    private static LegalRegistrationReceipt receipt(boolean replay, long user) {
        return new LegalRegistrationReceipt(user, 22, replay, new UUID(0, 30), List.of(new UUID(0, 40)));
    }

    private static String sha(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
