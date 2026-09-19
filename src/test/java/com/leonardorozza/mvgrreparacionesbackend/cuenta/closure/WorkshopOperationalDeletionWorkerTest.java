package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;

import static com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.WorkshopOperationalDeletionProgress.Snapshot;
import static com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.WorkshopOperationalDeletionService.*;
import static com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.WorkshopOperationalDeletionWorker.State.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkshopOperationalDeletionWorkerTest {
    private static final long TALLER = 41;
    private static final UUID CLOSURE = UUID.fromString("605c2f39-d1ed-40c4-a8a9-a6adf27d4329");
    private static final Instant NOW = Instant.parse("2026-09-19T18:00:00Z");
    @Mock WorkshopOperationalDeletionProgress progress;
    @Mock WorkshopOperationalDeletionService deletion;

    @AfterEach void clearTransaction() { TransactionSynchronizationManager.clear(); }

    @Test void disabledWorkerDoesNotReadOrMutate() {
        assertRejected(() -> new WorkshopOperationalDeletionWorker(progress, deletion, false, 4).run(TALLER, CLOSURE),
                WorkshopOperationalDeletionWorker.Rejected.Code.DISABLED);
        verifyNoInteractions(progress, deletion);
    }

    @ParameterizedTest @ValueSource(ints = {0, -1, 9, Integer.MAX_VALUE})
    void budgetMustRemainBounded(int budget) {
        assertThatIllegalArgumentException().isThrownBy(() -> worker(budget));
        verifyNoInteractions(progress, deletion);
    }

    @Test void invalidScopeAndOuterTransactionCannotBeginWork() {
        var worker = worker(4);
        assertRejected(() -> worker.run(0, CLOSURE), WorkshopOperationalDeletionWorker.Rejected.Code.INVALID_TARGET);
        assertRejected(() -> worker.run(TALLER, null), WorkshopOperationalDeletionWorker.Rejected.Code.INVALID_TARGET);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertRejected(() -> worker.run(TALLER, CLOSURE), WorkshopOperationalDeletionWorker.Rejected.Code.CALLER_TRANSACTION);
        verifyNoInteractions(progress, deletion);
    }

    @Test void initialReadFailureIsSanitizedAndDoesNotMutate() {
        when(progress.read(TALLER, CLOSURE)).thenThrow(new IllegalStateException("private SQL and identifiers"));
        assertRejected(() -> worker(4).run(TALLER, CLOSURE), WorkshopOperationalDeletionWorker.Rejected.Code.UNAVAILABLE);
        verifyNoInteractions(deletion);
    }

    @Test void foreignObservationCannotAuthorizeWork() {
        Snapshot own = snapshot(1, false);
        when(progress.read(TALLER, CLOSURE)).thenReturn(new Snapshot(TALLER + 1, CLOSURE, 1,
                NOW, own.reversibleUntil(), own.remaining(), false));
        assertRejected(() -> worker(4).run(TALLER, CLOSURE), WorkshopOperationalDeletionWorker.Rejected.Code.UNAVAILABLE);
        verifyNoInteractions(deletion);
    }

    @Test void graceIsCheckedBeforeAnyDeletion() {
        Snapshot current = snapshot(1, false);
        when(progress.read(TALLER, CLOSURE)).thenReturn(new Snapshot(TALLER, CLOSURE, 1,
                NOW, NOW.plusSeconds(1), current.remaining(), false));
        var result = worker(4).run(TALLER, CLOSURE);
        assertThat(result.state()).isEqualTo(WAITING_GRACE);
        assertThat(result.attempts()).isZero();
        verifyNoInteractions(deletion);
    }

    @Test void pendingPhotosBlockEvenWhenOperationalTablesAreEmpty() {
        when(progress.read(TALLER, CLOSURE)).thenReturn(snapshot(0, true));
        var result = worker(4).run(TALLER, CLOSURE);
        assertThat(result.state()).isEqualTo(PHOTOS_PENDING);
        assertThat(result.attempts()).isZero();
        verifyNoInteractions(deletion);
    }

    @Test void emptyScopeIsObservedWithoutInventingADeletionReceipt() {
        when(progress.read(TALLER, CLOSURE)).thenReturn(snapshot(0, false));
        var result = worker(4).run(TALLER, CLOSURE);
        assertThat(result.state()).isEqualTo(NO_PENDING_ROWS);
        assertThat(result.attempts()).isZero();
        verifyNoInteractions(deletion);
    }

    @Test void followsDependencyOrderAndUsesOneFreshIdentityPerAttempt() {
        var remaining = emptyCounts();
        for (Category category : Category.values()) remaining.put(category, 1L);
        var observations = new java.util.ArrayList<Snapshot>();
        observations.add(snapshot(remaining, false));
        for (Category category : Category.values()) {
            remaining.put(category, 0L);
            observations.add(snapshot(remaining, false));
        }
        when(progress.read(TALLER, CLOSURE)).thenReturn(observations.getFirst(), observations.subList(1, observations.size()).toArray(Snapshot[]::new));
        when(deletion.deleteBatch(eq(TALLER), eq(CLOSURE), any(UUID.class), any(Category.class))).thenAnswer(call ->
                new Batch(Status.DELETED, call.getArgument(3), 1, false, call.getArgument(2)));
        var result = worker(8).run(TALLER, CLOSURE);
        assertThat(result.state()).isEqualTo(NO_PENDING_ROWS);
        assertThat(result.attempts()).isEqualTo(8);
        ArgumentCaptor<Category> categories = ArgumentCaptor.forClass(Category.class);
        ArgumentCaptor<UUID> identities = ArgumentCaptor.forClass(UUID.class);
        verify(deletion, times(8)).deleteBatch(eq(TALLER), eq(CLOSURE), identities.capture(), categories.capture());
        assertThat(categories.getAllValues()).containsExactly(Category.values());
        assertThat(identities.getAllValues()).doesNotHaveDuplicates();
        verify(progress, times(9)).read(TALLER, CLOSURE);
    }

    @Test void budgetExhaustionIsRemainingWorkAndNotABlock() {
        when(progress.read(TALLER, CLOSURE)).thenReturn(snapshot(51, false), snapshot(26, false), snapshot(1, false));
        successfulArticleBatches();
        var result = worker(2).run(TALLER, CLOSURE);
        assertThat(result.state()).isEqualTo(WORK_REMAINS);
        assertThat(result.attempts()).isEqualTo(2);
        assertThat(result.observed().remaining().get(Category.ARTICULOS)).isEqualTo(1);
        verify(deletion, times(2)).deleteBatch(eq(TALLER), eq(CLOSURE), any(), eq(Category.ARTICULOS));
    }

    @Test void emptyBatchWithFreshRemainingRowsStopsAtDependencies() {
        when(progress.read(TALLER, CLOSURE)).thenReturn(snapshot(1, false));
        when(deletion.deleteBatch(eq(TALLER), eq(CLOSURE), any(), eq(Category.ARTICULOS)))
                .thenReturn(new Batch(Status.EMPTY, Category.ARTICULOS, 0, true, null));
        var result = worker(8).run(TALLER, CLOSURE);
        assertThat(result.state()).isEqualTo(DEPENDENCIES_PENDING);
        assertThat(result.attempts()).isEqualTo(1);
        verify(deletion).deleteBatch(eq(TALLER), eq(CLOSURE), any(), eq(Category.ARTICULOS));
    }

    @Test void concurrentCompletionOverridesHistoricalRemainingOnEmpty() {
        when(progress.read(TALLER, CLOSURE)).thenReturn(snapshot(1, false), snapshot(0, false));
        when(deletion.deleteBatch(eq(TALLER), eq(CLOSURE), any(), eq(Category.ARTICULOS)))
                .thenReturn(new Batch(Status.EMPTY, Category.ARTICULOS, 0, true, null));
        assertThat(worker(8).run(TALLER, CLOSURE).state()).isEqualTo(NO_PENDING_ROWS);
        verify(deletion).deleteBatch(eq(TALLER), eq(CLOSURE), any(), eq(Category.ARTICULOS));
    }

    @Test void reusedRemainingCannotPreventFreshCompletion() {
        when(progress.read(TALLER, CLOSURE)).thenReturn(snapshot(1, false), snapshot(0, false));
        when(deletion.deleteBatch(eq(TALLER), eq(CLOSURE), any(), eq(Category.ARTICULOS)))
                .thenAnswer(call -> new Batch(Status.REUSED, Category.ARTICULOS, 25, true, call.getArgument(2)));
        assertThat(worker(1).run(TALLER, CLOSURE).state()).isEqualTo(NO_PENDING_ROWS);
    }

    @Test void reusedResponseConsumesBudgetAndCannotInventCompletion() {
        when(progress.read(TALLER, CLOSURE)).thenReturn(snapshot(1, false));
        when(deletion.deleteBatch(eq(TALLER), eq(CLOSURE), any(), eq(Category.ARTICULOS)))
                .thenAnswer(call -> new Batch(Status.REUSED, Category.ARTICULOS, 25, false, call.getArgument(2)));
        var result = worker(1).run(TALLER, CLOSURE);
        assertThat(result.state()).isEqualTo(WORK_REMAINS);
        assertThat(result.attempts()).isEqualTo(1);
    }

    @Test void lostCommitResponseReconcilesFromRowsWithoutAnotherMutation() {
        when(progress.read(TALLER, CLOSURE)).thenReturn(snapshot(1, false), snapshot(0, false));
        when(deletion.deleteBatch(eq(TALLER), eq(CLOSURE), any(), eq(Category.ARTICULOS)))
                .thenThrow(new IllegalStateException("lost commit response"));
        var result = worker(8).run(TALLER, CLOSURE);
        assertThat(result.state()).isEqualTo(NO_PENDING_ROWS);
        assertThat(result.attempts()).isEqualTo(1);
        verify(deletion).deleteBatch(eq(TALLER), eq(CLOSURE), any(), eq(Category.ARTICULOS));
    }

    @Test void unknownFailureWithRowsRemainingStopsAndDefersRetry() {
        when(progress.read(TALLER, CLOSURE)).thenReturn(snapshot(1, false));
        when(deletion.deleteBatch(eq(TALLER), eq(CLOSURE), any(), eq(Category.ARTICULOS)))
                .thenThrow(new IllegalStateException("private database cause"));
        var result = worker(8).run(TALLER, CLOSURE);
        assertThat(result.state()).isEqualTo(RETRY_LATER);
        assertThat(result.attempts()).isEqualTo(1);
        assertThat(result.toString()).doesNotContain("private", CLOSURE.toString());
        verify(deletion).deleteBatch(eq(TALLER), eq(CLOSURE), any(), eq(Category.ARTICULOS));
    }

    @Test void unavailableFinalReadKeepsLastObservationWithoutClaimingCompletion() {
        when(progress.read(TALLER, CLOSURE)).thenReturn(snapshot(1, false)).thenThrow(new IllegalStateException("unavailable"));
        successfulArticleBatches();
        var result = worker(8).run(TALLER, CLOSURE);
        assertThat(result.state()).isEqualTo(RETRY_LATER);
        assertThat(result.observed().hasRows()).isTrue();
        verify(deletion).deleteBatch(eq(TALLER), eq(CLOSURE), any(), eq(Category.ARTICULOS));
    }

    @Test void uncertainMutationAndUnavailableReadDoNotLoop() {
        when(progress.read(TALLER, CLOSURE)).thenReturn(snapshot(1, false)).thenThrow(new IllegalStateException("unavailable"));
        when(deletion.deleteBatch(eq(TALLER), eq(CLOSURE), any(), eq(Category.ARTICULOS)))
                .thenThrow(new IllegalStateException("private SQL"));
        var result = worker(8).run(TALLER, CLOSURE);
        assertThat(result.state()).isEqualTo(RETRY_LATER);
        assertThat(result.attempts()).isEqualTo(1);
        verify(progress, times(2)).read(TALLER, CLOSURE);
        verify(deletion).deleteBatch(eq(TALLER), eq(CLOSURE), any(), eq(Category.ARTICULOS));
    }

    @Test void newPhotoBlockStopsBeforeNextBatch() {
        when(progress.read(TALLER, CLOSURE)).thenReturn(snapshot(26, false), snapshot(1, true));
        successfulArticleBatches();
        var result = worker(8).run(TALLER, CLOSURE);
        assertThat(result.state()).isEqualTo(PHOTOS_PENDING);
        assertThat(result.attempts()).isEqualTo(1);
        verify(deletion).deleteBatch(eq(TALLER), eq(CLOSURE), any(), eq(Category.ARTICULOS));
    }

    private void successfulArticleBatches() {
        when(deletion.deleteBatch(eq(TALLER), eq(CLOSURE), any(), eq(Category.ARTICULOS)))
                .thenAnswer(call -> new Batch(Status.DELETED, Category.ARTICULOS, 25, true, call.getArgument(2)));
    }
    private WorkshopOperationalDeletionWorker worker(int budget) { return new WorkshopOperationalDeletionWorker(progress, deletion, true, budget); }
    private static EnumMap<Category, Long> emptyCounts() {
        var counts = new EnumMap<Category, Long>(Category.class);
        for (Category category : Category.values()) counts.put(category, 0L);
        return counts;
    }
    private static Snapshot snapshot(long articles, boolean photos) {
        var counts = emptyCounts(); counts.put(Category.ARTICULOS, articles); return snapshot(counts, photos);
    }
    private static Snapshot snapshot(EnumMap<Category, Long> counts, boolean photos) {
        return new Snapshot(TALLER, CLOSURE, 1, NOW, NOW.minusSeconds(1), counts, photos);
    }
    private static void assertRejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, WorkshopOperationalDeletionWorker.Rejected.Code code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(WorkshopOperationalDeletionWorker.Rejected.class,
                failure -> assertThat(failure.code()).isEqualTo(code)).hasNoCause();
    }
}
