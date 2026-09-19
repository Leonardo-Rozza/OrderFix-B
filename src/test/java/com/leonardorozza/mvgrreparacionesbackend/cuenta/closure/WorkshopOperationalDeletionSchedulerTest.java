package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.WorkshopOperationalDeletionCandidates.*;
import static com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.WorkshopOperationalDeletionScheduler.*;
import static com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.WorkshopOperationalDeletionWorker.State.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkshopOperationalDeletionSchedulerTest {
    @Test void blockedAndFailedWorkshopsDoNotStarveLaterCandidatesAndRotationWraps() {
        var fixture = new Fixture();
        var first = candidate(1); var second = candidate(2); var third = candidate(3);
        when(fixture.candidates.next(0)).thenReturn(page(true, first, second));
        when(fixture.candidates.next(2)).thenReturn(page(false, third));
        when(fixture.worker.run(1, first.closureReference())).thenReturn(result(PHOTOS_PENDING));
        when(fixture.worker.run(2, second.closureReference())).thenThrow(new IllegalStateException("private-error"));
        when(fixture.worker.run(3, third.closureReference())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return result(NO_PENDING_ROWS);
        });
        fixture.scheduler.tick();
        assertThat(fixture.scheduler.lastRun()).isEqualTo(new RunSnapshot(Outcome.COMPLETED, 2, 2, 1, Map.of(PHOTOS_PENDING, 1)));
        fixture.scheduler.tick();
        assertThat(fixture.scheduler.lastRun()).isEqualTo(new RunSnapshot(Outcome.COMPLETED, 1, 1, 0, Map.of(NO_PENDING_ROWS, 1)));
        fixture.scheduler.tick();
        var order = inOrder(fixture.candidates, fixture.worker);
        order.verify(fixture.candidates).next(0);
        order.verify(fixture.worker).run(1, first.closureReference());
        order.verify(fixture.worker).run(2, second.closureReference());
        order.verify(fixture.candidates).next(2);
        order.verify(fixture.worker).run(3, third.closureReference());
        order.verify(fixture.candidates).next(0);
        order.verify(fixture.worker).run(1, first.closureReference());
        order.verify(fixture.worker).run(2, second.closureReference());
        order.verifyNoMoreInteractions();
    }

    @Test void discoveryFailureKeepsItsCursorAndTheNextPassRecovers() {
        var fixture = new Fixture();
        var first = candidate(7); var second = candidate(8); var last = candidate(9);
        when(fixture.candidates.next(0)).thenReturn(page(true, first, second));
        when(fixture.worker.run(anyLong(), any())).thenReturn(result(DEPENDENCIES_PENDING));
        when(fixture.candidates.next(8)).thenThrow(new IllegalStateException("private-discovery"))
                .thenReturn(page(false, last));
        fixture.scheduler.tick();
        fixture.scheduler.tick();
        assertThat(fixture.scheduler.lastRun()).isEqualTo(new RunSnapshot(Outcome.DISCOVERY_FAILED, 0, 0, 0, Map.of()));
        verify(fixture.worker, times(2)).run(anyLong(), any());
        fixture.scheduler.tick();
        verify(fixture.candidates, times(2)).next(8);
        verify(fixture.worker).run(9, last.closureReference());
    }

    @Test void emptyFinalPageResetsCursorWithoutClaimingAccountCompletion() {
        var fixture = new Fixture();
        when(fixture.candidates.next(0)).thenReturn(page(true, candidate(3), candidate(4)));
        when(fixture.worker.run(anyLong(), any())).thenReturn(result(WORK_REMAINS));
        when(fixture.candidates.next(4)).thenReturn(page(false));
        fixture.scheduler.tick();
        fixture.scheduler.tick();
        assertThat(fixture.scheduler.lastRun()).isEqualTo(new RunSnapshot(Outcome.COMPLETED, 0, 0, 0, Map.of()));
        fixture.scheduler.tick();
        verify(fixture.candidates, times(2)).next(0);
        verify(fixture.worker, times(4)).run(anyLong(), any());
    }

    @Test void noOverlappingPassPerInstanceAndFailureAlwaysReleasesThePermit() throws Exception {
        var fixture = new Fixture();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(fixture.candidates.next(0)).thenAnswer(call -> {
            entered.countDown();
            if (!release.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
            throw new IllegalStateException("private-discovery");
        }).thenReturn(page(false));
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(fixture.scheduler::tick);
            try {
                assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
                fixture.scheduler.tick();
                verify(fixture.candidates).next(0);
                assertThat(fixture.scheduler.lastRun().outcome()).isEqualTo(Outcome.IDLE);
            } finally { release.countDown(); }
            first.get(3, TimeUnit.SECONDS);
        }
        fixture.scheduler.tick();
        assertThat(fixture.scheduler.lastRun().outcome()).isEqualTo(Outcome.COMPLETED);
        verify(fixture.candidates, times(2)).next(0);
        verifyNoInteractions(fixture.worker);
    }

    @Test void allWorkerStatesRemainDistinctInTheObservation() {
        for (var state : WorkshopOperationalDeletionWorker.State.values()) {
            var fixture = new Fixture();
            when(fixture.candidates.next(0)).thenReturn(page(false, candidate(1)));
            when(fixture.worker.run(anyLong(), any())).thenReturn(result(state));
            fixture.scheduler.tick();
            assertThat(fixture.scheduler.lastRun().states()).containsExactlyEntriesOf(Map.of(state, 1));
            assertThatThrownBy(() -> fixture.scheduler.lastRun().states().clear()).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Test void untrustedExceptionsNeverEnterLogsOrTheObservation() {
        var fixture = new Fixture();
        var target = candidate(8123456789L);
        when(fixture.candidates.next(0)).thenReturn(page(false, target));
        String secret = "private-SQL-password-" + target.closureReference();
        when(fixture.worker.run(anyLong(), any())).thenThrow(new IllegalStateException(secret));
        var logger = (Logger) LoggerFactory.getLogger(WorkshopOperationalDeletionScheduler.class);
        var appender = new ListAppender<ILoggingEvent>(); appender.start(); logger.addAppender(appender);
        try {
            fixture.scheduler.tick();
            assertThat(appender.list).hasSize(1).allSatisfy(event -> {
                assertThat(event.getThrowableProxy()).isNull();
                assertThat(event.getFormattedMessage()).doesNotContain(secret, target.closureReference().toString(), "8123456789");
            });
            assertThat(fixture.scheduler.lastRun().toString()).doesNotContain(secret, target.closureReference().toString(), "8123456789");
        } finally { logger.detachAppender(appender); appender.stop(); }
    }

    @Test void scheduleIsFixedAndNeitherConstructionNorInspectionStartsWork() throws Exception {
        var fixture = new Fixture();
        assertThat(fixture.scheduler.lastRun()).isEqualTo(new RunSnapshot(Outcome.IDLE, 0, 0, 0, Map.of()));
        var schedule = WorkshopOperationalDeletionScheduler.class.getMethod("tick").getAnnotation(Scheduled.class);
        assertThat(schedule.fixedDelay()).isEqualTo(60_000);
        assertThat(schedule.initialDelay()).isEqualTo(60_000);
        verifyNoInteractions(fixture.candidates, fixture.worker);
    }

    private static Candidate candidate(long id) { return new Candidate(id, UUID.randomUUID()); }
    private static Page page(boolean more, Candidate... candidates) { return new Page(List.of(candidates), more); }
    private static WorkshopOperationalDeletionWorker.Result result(WorkshopOperationalDeletionWorker.State state) {
        return mock(WorkshopOperationalDeletionWorker.Result.class, call ->
                call.getMethod().getName().equals("state") ? state : RETURNS_DEFAULTS.answer(call));
    }
    private static final class Fixture {
        final WorkshopOperationalDeletionCandidates candidates = mock(WorkshopOperationalDeletionCandidates.class);
        final WorkshopOperationalDeletionWorker worker = mock(WorkshopOperationalDeletionWorker.class);
        final WorkshopOperationalDeletionScheduler scheduler = new WorkshopOperationalDeletionScheduler(candidates, worker);
    }
}
