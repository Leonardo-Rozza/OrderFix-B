package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.ClosureRenewalAssessment.*;
import static org.assertj.core.api.Assertions.assertThat;

class ClosureRenewalAssessmentTest {
    private static final SubscriptionEvidence CLEAN_FREE = new SubscriptionEvidence(true, Plan.FREE, ProviderState.NONE, false, false);

    @Test
    void cleanFreeSnapshotOnlyReportsAbsenceOfLocalEvidence() {
        assertThat(assess(CLEAN_FREE, List.of())).isEqualTo(Result.NO_LOCAL_EVIDENCE);
    }

    @Test
    void absentOrMalformedEvidenceNeverBecomesAStatementThatBillingStopped() {
        assertThat(assess(null, List.of())).isEqualTo(Result.UNCERTAIN);
        assertThat(assess(new SubscriptionEvidence(false, Plan.UNKNOWN, ProviderState.NONE, false, false), List.of())).isEqualTo(Result.UNCERTAIN);
        assertThat(assess(new SubscriptionEvidence(true, null, ProviderState.NONE, false, false), List.of())).isEqualTo(Result.UNCERTAIN);
        assertThat(assess(new SubscriptionEvidence(true, Plan.UNKNOWN, ProviderState.NONE, false, false), List.of())).isEqualTo(Result.UNCERTAIN);
        assertThat(assess(new SubscriptionEvidence(true, Plan.FREE, null, false, false), List.of())).isEqualTo(Result.UNCERTAIN);
        assertThat(assess(CLEAN_FREE, null)).isEqualTo(Result.UNCERTAIN);
        assertThat(assess(CLEAN_FREE, Arrays.asList((LinkEvidence) null))).isEqualTo(Result.UNCERTAIN);
        assertThat(assess(CLEAN_FREE, List.of(new LinkEvidence(true, null, false, false)))).isEqualTo(Result.UNCERTAIN);
    }

    @ParameterizedTest
    @EnumSource(value = ProviderState.class, names = {"NONE", "AUTHORIZED", "PENDING", "PAUSED", "CANCELED"})
    void proWithoutAnyLinkRequiresReviewEvenWhenTheSubscriptionClaimsCancellation(ProviderState state) {
        var subscription = new SubscriptionEvidence(true, Plan.PRO, state, state != ProviderState.NONE, false);
        assertThat(assess(subscription, List.of())).isEqualTo(Result.UNCERTAIN);
    }

    @Test
    void canceledHistoryDoesNotExplainProWithoutCurrentCommercialStateButStillCountsForFree() {
        var history = List.of(new LinkEvidence(false, ProviderState.CANCELED, true, true));
        var unexplainedPro = new SubscriptionEvidence(true, Plan.PRO, ProviderState.NONE, false, false);

        assertThat(assess(unexplainedPro, history)).isEqualTo(Result.UNCERTAIN);
        assertThat(assess(CLEAN_FREE, history)).isEqualTo(Result.PROVIDER_COORDINATION_REQUIRED);
    }

    @ParameterizedTest
    @EnumSource(value = ProviderState.class, names = {"AUTHORIZED", "PENDING", "PAUSED"})
    void knownCurrentProviderStateRequiresCoordination(ProviderState state) {
        var subscription = new SubscriptionEvidence(true, Plan.PRO, state, true, true);
        assertThat(assess(subscription, List.of(new LinkEvidence(true, state, true, true))))
                .isEqualTo(Result.PROVIDER_COORDINATION_REQUIRED);
    }

    @ParameterizedTest
    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void locallyCanceledFreeSubscriptionNeverMeansTheProviderWasStopped(boolean externalId, boolean otherEvidence) {
        var canceled = new SubscriptionEvidence(true, Plan.FREE, ProviderState.CANCELED, externalId, otherEvidence);
        assertThat(assess(canceled, List.of())).isEqualTo(Result.PROVIDER_COORDINATION_REQUIRED);
    }

    @ParameterizedTest
    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void terminalHistoryCannotBeErasedByAnOtherwiseCleanFreePlan(boolean current, boolean externalId) {
        var old = new LinkEvidence(current, ProviderState.CANCELED, externalId, false);
        assertThat(assess(CLEAN_FREE, List.of(old))).isEqualTo(Result.PROVIDER_COORDINATION_REQUIRED);
    }

    @ParameterizedTest
    @EnumSource(value = ProviderState.class, names = {"NONE", "AUTHORIZED", "PENDING", "PAUSED", "CREATING", "RETRYABLE", "UNKNOWN"})
    void nonterminalHistoricalLinkRequiresReviewEvenIfAnotherLinkIsCurrentAndCanceled(ProviderState state) {
        var current = new LinkEvidence(true, ProviderState.CANCELED, true, true);
        var historic = new LinkEvidence(false, state, true, true);
        assertThat(assess(CLEAN_FREE, List.of(historic, current))).isEqualTo(Result.UNCERTAIN);
        assertThat(assess(CLEAN_FREE, List.of(current, historic))).isEqualTo(Result.UNCERTAIN);
    }

    @ParameterizedTest
    @EnumSource(value = ProviderState.class, names = {"CREATING", "RETRYABLE", "UNKNOWN"})
    void unresolvedOrFutureStateNeverDisappearsBecauseIdsAreStillEmpty(ProviderState state) {
        assertThat(assess(new SubscriptionEvidence(true, Plan.FREE, state, false, false), List.of()))
                .isEqualTo(Result.UNCERTAIN);
        assertThat(assess(CLEAN_FREE, List.of(new LinkEvidence(true, state, false, false))))
                .isEqualTo(Result.UNCERTAIN);
    }

    @ParameterizedTest
    @CsvSource({"true,false", "false,true", "true,true"})
    void absentStatusWithAnyProviderMetadataRequiresReview(boolean externalId, boolean otherEvidence) {
        assertThat(assess(new SubscriptionEvidence(true, Plan.FREE, ProviderState.NONE, externalId, otherEvidence), List.of()))
                .isEqualTo(Result.UNCERTAIN);
    }

    @ParameterizedTest
    @EnumSource(value = ProviderState.class, names = {"AUTHORIZED", "PENDING", "PAUSED"})
    void emptyExternalIdDoesNotSupportAKnownRemoteState(ProviderState state) {
        assertThat(assess(new SubscriptionEvidence(true, Plan.FREE, state, false, true), List.of()))
                .isEqualTo(Result.UNCERTAIN);
        assertThat(assess(CLEAN_FREE, List.of(new LinkEvidence(true, state, false, true))))
                .isEqualTo(Result.UNCERTAIN);
    }

    @Test
    void emptyCurrentLinkIsUncertainAndMultipleCurrentLinksRemainUncertainEvenIfCanceled() {
        assertThat(assess(CLEAN_FREE, List.of(new LinkEvidence(true, ProviderState.NONE, false, false))))
                .isEqualTo(Result.UNCERTAIN);
        var canceled = new LinkEvidence(true, ProviderState.CANCELED, true, false);
        assertThat(assess(CLEAN_FREE, List.of(canceled, canceled))).isEqualTo(Result.UNCERTAIN);
    }

    @Test
    void allHistoricalRowsAreConsideredUpToTheBoundAndOverflowFailsClosed() {
        var old = new LinkEvidence(false, ProviderState.CANCELED, true, true);
        assertThat(assess(CLEAN_FREE, Collections.nCopies(1_000, old))).isEqualTo(Result.PROVIDER_COORDINATION_REQUIRED);
        assertThat(assess(CLEAN_FREE, Collections.nCopies(1_001, old))).isEqualTo(Result.UNCERTAIN);
    }

    @Test
    void contractAndRecordRenderingHaveNoFieldsForRawProviderDataOrCredentials() {
        for (var type : List.of(SubscriptionEvidence.class, LinkEvidence.class)) {
            assertThat(type.getRecordComponents()).allSatisfy(component ->
                    assertThat(component.getType() == boolean.class || component.getType().isEnum()).isTrue());
        }
        assertThat(CLEAN_FREE.toString()).contains("FREE", "NONE").doesNotContain("http", "@", "token", "key");
    }
}
