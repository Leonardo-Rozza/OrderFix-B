package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded opt-in dispatch of notices; never schedules renewal cancellation or data deletion. */
@Component
@ConditionalOnProperty(name = {"mail.enabled", "ordenfix.cuenta.cierre.notifications-enabled",
        "ordenfix.cuenta.cierre.notifications-scheduled"}, havingValue = "true")
public final class WorkshopClosureNotificationScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(WorkshopClosureNotificationScheduler.class);
    private final WorkshopClosureEffectWorker worker;
    private final AtomicBoolean running = new AtomicBoolean();

    public WorkshopClosureNotificationScheduler(WorkshopClosureEffectWorker worker) {
        this.worker = Objects.requireNonNull(worker);
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void tick() {
        if (!running.compareAndSet(false, true)) return;
        try {
            for (int i = 0; i < 2; i++) {
                if (!worker.runNextNotification()) break;
            }
        } catch (RuntimeException unavailable) {
            LOG.warn("Avisos de cierre: ronda interrumpida; revisar estado durable antes de reintentar.");
        } finally {
            running.set(false);
        }
    }
}
