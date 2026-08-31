package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegalEditorialTransactionStateTest {

    @AfterEach
    void clearTransactionSynchronization() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void registersTheFacadeAndStartsWithoutPlanEvidence() {
        LegalEditorialTransactionState<String> state =
                new LegalEditorialTransactionState<>();
        activateTransactionSynchronization();

        state.callbackStarted();

        assertThat(TransactionSynchronizationManager.getSynchronizations())
                .containsExactly(state);
        LegalEditorialTransactionState.Snapshot<String> snapshot = state.snapshot();
        assertThat(snapshot.callbackStarted()).isTrue();
        assertThat(snapshot.planConstructed()).isFalse();
        assertThat(snapshot.receiptDelivered()).isFalse();
        assertThat(snapshot.commitBoundaryEntered()).isFalse();
        assertThat(snapshot.completion())
                .isEqualTo(LegalEditorialTransactionState.Completion.NONE);
        assertThat(snapshot.persistence())
                .isEqualTo(LegalEditorialTransactionState.Persistence.NOT_PERSISTED);
        assertThat(snapshot.receipt()).isEmpty();
        assertThat(LegalEditorialTransactionState.Completion.values()).containsExactly(
                LegalEditorialTransactionState.Completion.NONE,
                LegalEditorialTransactionState.Completion.COMMITTED,
                LegalEditorialTransactionState.Completion.ROLLED_BACK,
                LegalEditorialTransactionState.Completion.UNKNOWN);
        assertThat(LegalEditorialTransactionState.Persistence.values()).containsExactly(
                LegalEditorialTransactionState.Persistence.PERSISTED,
                LegalEditorialTransactionState.Persistence.NOT_PERSISTED,
                LegalEditorialTransactionState.Persistence.UNKNOWN);
        assertThatThrownBy(state::callbackStarted)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesToStartOutsideAnActualSynchronizedTransaction() {
        LegalEditorialTransactionState<String> withoutSynchronization =
                new LegalEditorialTransactionState<>();

        assertThatThrownBy(withoutSynchronization::callbackStarted)
                .isInstanceOf(IllegalStateException.class);
        assertThat(withoutSynchronization.snapshot().callbackStarted()).isFalse();

        TransactionSynchronizationManager.initSynchronization();
        LegalEditorialTransactionState<String> withoutTransaction =
                new LegalEditorialTransactionState<>();

        assertThatThrownBy(withoutTransaction::callbackStarted)
                .isInstanceOf(IllegalStateException.class);
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    }

    @Test
    void planMarkerIsSingleUseAndMustPrecedeTheReceipt() {
        LegalEditorialTransactionState<String> state =
                new LegalEditorialTransactionState<>();

        assertThatThrownBy(state::planConstructed)
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> state.receiptDelivered("too early"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(state.snapshot().planConstructed()).isFalse();
        assertThat(state.snapshot().receiptDelivered()).isFalse();

        activateAndStart(state);
        assertThatThrownBy(() -> state.receiptDelivered("still too early"))
                .isInstanceOf(IllegalStateException.class);

        state.planConstructed();

        assertThat(state.snapshot().planConstructed()).isTrue();
        assertThatThrownBy(state::planConstructed)
                .isInstanceOf(IllegalStateException.class);
        state.receiptDelivered("receipt");
        assertThat(state.snapshot().receiptDelivered()).isTrue();
    }

    @Test
    void orderingViolationsNeverAdvanceValidEvidence() {
        LegalEditorialTransactionState<String> state =
                stateWithTentativeReceipt("receipt");

        assertThatThrownBy(state::planConstructed)
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> state.receiptDelivered("replacement"))
                .isInstanceOf(IllegalStateException.class);
        state.beforeCommit(false);
        LegalEditorialTransactionState.Snapshot<String> boundary = state.snapshot();
        assertThatThrownBy(state::planConstructed)
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> state.receiptDelivered("late"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(state.snapshot()).isEqualTo(boundary);

        TransactionSynchronizationManager.clear();
        LegalEditorialTransactionState<String> terminal =
                new LegalEditorialTransactionState<>();
        activateAndStart(terminal);
        terminal.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        LegalEditorialTransactionState.Snapshot<String> rolledBack = terminal.snapshot();

        assertThatThrownBy(terminal::planConstructed)
                .isInstanceOf(IllegalStateException.class);
        assertThat(terminal.snapshot()).isEqualTo(rolledBack);
    }

    @Test
    void tentativeReceiptIsRedactedUntilCommitBecomesAuthoritative() {
        Object receipt = new Object();
        LegalEditorialTransactionState<Object> state =
                stateWithTentativeReceipt(receipt);

        assertThat(state.snapshot().persistence())
                .isEqualTo(LegalEditorialTransactionState.Persistence.NOT_PERSISTED);
        assertThat(state.snapshot().receipt()).isEmpty();

        state.beforeCommit(false);
        assertThat(state.snapshot().persistence())
                .isEqualTo(LegalEditorialTransactionState.Persistence.UNKNOWN);
        assertThat(state.snapshot().receipt()).isEmpty();

        state.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);

        assertThat(state.snapshot().completion())
                .isEqualTo(LegalEditorialTransactionState.Completion.COMMITTED);
        assertThat(state.snapshot().persistence())
                .isEqualTo(LegalEditorialTransactionState.Persistence.PERSISTED);
        assertThat(state.snapshot().receipt()).contains(receipt);
    }

    @ParameterizedTest
    @MethodSource("terminalCompletions")
    void mapsTerminalCompletionAndRedactsEveryNonCommit(
            int springStatus,
            LegalEditorialTransactionState.Completion expectedCompletion,
            LegalEditorialTransactionState.Persistence expectedPersistence,
            boolean receiptVisible) {
        Object receipt = new Object();
        LegalEditorialTransactionState<Object> state =
                stateAtCommitBoundary(receipt);

        state.afterCompletion(springStatus);

        LegalEditorialTransactionState.Snapshot<Object> snapshot = state.snapshot();
        assertThat(snapshot.callbackStarted()).isTrue();
        assertThat(snapshot.planConstructed()).isTrue();
        assertThat(snapshot.receiptDelivered()).isTrue();
        assertThat(snapshot.commitBoundaryEntered()).isTrue();
        assertThat(snapshot.completion()).isEqualTo(expectedCompletion);
        assertThat(snapshot.persistence()).isEqualTo(expectedPersistence);
        if (receiptVisible) {
            assertThat(snapshot.receipt()).contains(receipt);
        } else {
            assertThat(snapshot.receipt()).isEmpty();
        }
    }

    @Test
    void planMarkerAloneSeparatesReconciliationEligibility() {
        LegalEditorialTransactionState<String> withoutPlan =
                new LegalEditorialTransactionState<>();
        activateAndStart(withoutPlan);
        withoutPlan.afterCompletion(TransactionSynchronization.STATUS_UNKNOWN);

        LegalEditorialTransactionState.Snapshot<String> noPlan = withoutPlan.snapshot();
        assertThat(noPlan.planConstructed()).isFalse();
        assertThat(noPlan.receiptDelivered()).isFalse();
        assertThat(noPlan.persistence())
                .isEqualTo(LegalEditorialTransactionState.Persistence.UNKNOWN);
        assertThat(noPlan.receipt()).isEmpty();

        TransactionSynchronizationManager.clear();
        LegalEditorialTransactionState<String> withPlan =
                new LegalEditorialTransactionState<>();
        activateAndStart(withPlan);
        withPlan.planConstructed();
        withPlan.afterCompletion(TransactionSynchronization.STATUS_UNKNOWN);

        LegalEditorialTransactionState.Snapshot<String> planned = withPlan.snapshot();
        assertThat(planned.planConstructed()).isTrue();
        assertThat(planned.receiptDelivered()).isFalse();
        assertThat(planned.persistence())
                .isEqualTo(LegalEditorialTransactionState.Persistence.UNKNOWN);
        assertThat(planned.receipt()).isEmpty();
    }

    @Test
    void planConstructedWithoutReceiptAndConfirmedRollbackIsKnownNotPersisted() {
        LegalEditorialTransactionState<String> state =
                new LegalEditorialTransactionState<>();
        activateAndStart(state);
        state.planConstructed();

        state.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        LegalEditorialTransactionState.Snapshot<String> snapshot = state.snapshot();
        assertThat(snapshot.planConstructed()).isTrue();
        assertThat(snapshot.receiptDelivered()).isFalse();
        assertThat(snapshot.completion())
                .isEqualTo(LegalEditorialTransactionState.Completion.ROLLED_BACK);
        assertThat(snapshot.persistence())
                .isEqualTo(LegalEditorialTransactionState.Persistence.NOT_PERSISTED);
        assertThat(snapshot.receipt()).isEmpty();
    }

    @Test
    void normalReturnConfirmsOnlyThePlannedDeliveredReceipt() {
        LegalEditorialTransactionState<String> state =
                stateWithTentativeReceipt("receipt");

        state.transactionReturnedNormally();

        assertThat(state.snapshot().completion())
                .isEqualTo(LegalEditorialTransactionState.Completion.COMMITTED);
        assertThat(state.snapshot().persistence())
                .isEqualTo(LegalEditorialTransactionState.Persistence.PERSISTED);
        assertThat(state.snapshot().receipt()).contains("receipt");
    }

    @Test
    void facadeCarriesOnlyABooleanPlanMarker() {
        String executionPlanType = LegalEditorialExecutionPlan.class.getName();
        Stream<String> fieldTypes = Stream.concat(
                Arrays.stream(LegalEditorialTransactionState.class.getDeclaredFields())
                        .map(Field::getGenericType)
                        .map(type -> type.getTypeName()),
                Arrays.stream(LegalEditorialTransactionState.Snapshot.class.getDeclaredFields())
                        .map(Field::getGenericType)
                        .map(type -> type.getTypeName()));
        Stream<String> methodTypes = Arrays.stream(
                        LegalEditorialTransactionState.class.getDeclaredMethods())
                .flatMap(LegalEditorialTransactionStateTest::methodTypeNames);

        assertThat(Stream.concat(fieldTypes, methodTypes))
                .noneMatch(type -> type.contains(executionPlanType));
    }

    private static Stream<Arguments> terminalCompletions() {
        return Stream.of(
                Arguments.of(
                        TransactionSynchronization.STATUS_COMMITTED,
                        LegalEditorialTransactionState.Completion.COMMITTED,
                        LegalEditorialTransactionState.Persistence.PERSISTED,
                        true),
                Arguments.of(
                        TransactionSynchronization.STATUS_ROLLED_BACK,
                        LegalEditorialTransactionState.Completion.ROLLED_BACK,
                        LegalEditorialTransactionState.Persistence.NOT_PERSISTED,
                        false),
                Arguments.of(
                        TransactionSynchronization.STATUS_UNKNOWN,
                        LegalEditorialTransactionState.Completion.UNKNOWN,
                        LegalEditorialTransactionState.Persistence.UNKNOWN,
                        false));
    }

    private static Stream<String> methodTypeNames(Method method) {
        return Stream.concat(
                Stream.of(method.getGenericReturnType().getTypeName()),
                Arrays.stream(method.getGenericParameterTypes()).map(type -> type.getTypeName()));
    }

    private static <R> LegalEditorialTransactionState<R> stateAtCommitBoundary(R receipt) {
        LegalEditorialTransactionState<R> state = stateWithTentativeReceipt(receipt);
        state.beforeCommit(false);
        return state;
    }

    private static <R> LegalEditorialTransactionState<R> stateWithTentativeReceipt(R receipt) {
        LegalEditorialTransactionState<R> state = new LegalEditorialTransactionState<>();
        activateAndStart(state);
        state.planConstructed();
        state.receiptDelivered(receipt);
        return state;
    }

    private static void activateAndStart(LegalEditorialTransactionState<?> state) {
        activateTransactionSynchronization();
        state.callbackStarted();
    }

    private static void activateTransactionSynchronization() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }
}
