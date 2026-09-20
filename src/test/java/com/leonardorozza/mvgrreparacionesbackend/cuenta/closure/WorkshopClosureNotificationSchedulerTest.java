package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

class WorkshopClosureNotificationSchedulerTest {
    @Test void eachPassDispatchesAtMostTwoNotificationsAndNeverGenericWork() {
        var worker = mock(WorkshopClosureEffectWorker.class);
        when(worker.runNextNotification()).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return true;
        });
        var scheduler = new WorkshopClosureNotificationScheduler(worker);
        scheduler.tick();
        verify(worker, times(2)).runNextNotification();
        verify(worker, never()).runNext();
        verifyNoMoreInteractions(worker);
    }

    @Test void anEmptyQueueStopsAtTheFirstObservation() {
        var worker = mock(WorkshopClosureEffectWorker.class);
        when(worker.runNextNotification()).thenReturn(false);
        new WorkshopClosureNotificationScheduler(worker).tick();
        verify(worker).runNextNotification();
        verifyNoMoreInteractions(worker);
    }

    @Test void oneNotificationThenAnEmptyQueueDoesNotTriggerAnotherClaim() {
        var worker = mock(WorkshopClosureEffectWorker.class);
        when(worker.runNextNotification()).thenReturn(true, false, true);
        new WorkshopClosureNotificationScheduler(worker).tick();
        verify(worker, times(2)).runNextNotification();
        verifyNoMoreInteractions(worker);
    }

    @Test void failureStopsThePassAndTheNextPassCanRun() {
        var worker = mock(WorkshopClosureEffectWorker.class);
        when(worker.runNextNotification()).thenThrow(new IllegalStateException("private-dispatch-failure"))
                .thenReturn(false);
        var scheduler = new WorkshopClosureNotificationScheduler(worker);
        assertThatCode(scheduler::tick).doesNotThrowAnyException();
        verify(worker).runNextNotification();
        scheduler.tick();
        verify(worker, times(2)).runNextNotification();
        verifyNoMoreInteractions(worker);
    }

    @Test void completingTheBoundedPassReleasesThePermit() {
        var worker = mock(WorkshopClosureEffectWorker.class);
        when(worker.runNextNotification()).thenReturn(true);
        var scheduler = new WorkshopClosureNotificationScheduler(worker);
        scheduler.tick();
        scheduler.tick();
        verify(worker, times(4)).runNextNotification();
        verifyNoMoreInteractions(worker);
    }

    @Test void overlappingPassesCannotDispatchAndFailureReleasesThePermit() throws Exception {
        var worker = mock(WorkshopClosureEffectWorker.class);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(worker.runNextNotification()).thenAnswer(call -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("synthetic timeout");
            throw new IllegalStateException("synthetic dispatch failure");
        }).thenReturn(false);
        var scheduler = new WorkshopClosureNotificationScheduler(worker);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var running = executor.submit(scheduler::tick);
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                scheduler.tick();
                verify(worker).runNextNotification();
            } finally {
                release.countDown();
            }
            running.get(5, TimeUnit.SECONDS);
        }
        scheduler.tick();
        verify(worker, times(2)).runNextNotification();
        verify(worker, never()).runNext();
        verifyNoMoreInteractions(worker);
    }

    @Test void failuresProduceOnlyTheSameSanitizedWarningWithoutThrowableOrArguments() {
        var worker = mock(WorkshopClosureEffectWorker.class);
        String sensitive = "private@example.invalid / token-private / SELECT user_id / 8123456789";
        when(worker.runNextNotification())
                .thenThrow(new IllegalStateException(sensitive, new IllegalArgumentException("smtp-private-secret")))
                .thenThrow(new IllegalArgumentException("another-private-message"));
        var scheduler = new WorkshopClosureNotificationScheduler(worker);
        var logger = (Logger) LoggerFactory.getLogger(WorkshopClosureNotificationScheduler.class);
        Level previous = logger.getLevel();
        var appender = new ListAppender<ILoggingEvent>();
        appender.setContext(logger.getLoggerContext());
        appender.start();
        logger.setLevel(Level.WARN);
        logger.addAppender(appender);
        try {
            assertThatCode(scheduler::tick).doesNotThrowAnyException();
            assertThatCode(scheduler::tick).doesNotThrowAnyException();
            assertThat(appender.list).hasSize(2).allSatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getThrowableProxy()).isNull();
                assertThat(event.getArgumentArray()).isNullOrEmpty();
                assertThat(event.getFormattedMessage()).isNotBlank().doesNotContain(
                        sensitive, "private@example.invalid", "token-private", "SELECT user_id", "8123456789",
                        "smtp-private-secret", "another-private-message", "IllegalStateException", "IllegalArgumentException");
            });
            assertThat(appender.list.get(0).getFormattedMessage()).isEqualTo(appender.list.get(1).getFormattedMessage());
            verify(worker, times(2)).runNextNotification();
            verifyNoMoreInteractions(worker);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(previous);
        }
    }

    @Test void constructionDoesNotDispatchAndTheScheduleHasAnInitialDelay() throws Exception {
        var worker = mock(WorkshopClosureEffectWorker.class);
        new WorkshopClosureNotificationScheduler(worker);
        var schedule = WorkshopClosureNotificationScheduler.class.getMethod("tick").getAnnotation(Scheduled.class);
        assertThat(schedule).isNotNull();
        assertThat(schedule.fixedDelay()).isEqualTo(60_000);
        assertThat(schedule.initialDelay()).isEqualTo(60_000);
        verifyNoInteractions(worker);
    }
}
