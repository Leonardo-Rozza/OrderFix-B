package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Optional rotation over bounded pages. The cursor and observation are neither durable progress nor receipts. */
@Component
@ConditionalOnProperty(prefix = "ordenfix.cuenta.cierre",
        name = {"operational-deletion-enabled", "operational-worker-enabled"}, havingValue = "true")
public final class WorkshopOperationalDeletionScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(WorkshopOperationalDeletionScheduler.class);
    private final WorkshopOperationalDeletionCandidates candidates;
    private final WorkshopOperationalDeletionWorker worker;
    private final AtomicBoolean running = new AtomicBoolean();
    private long cursor;
    private volatile RunSnapshot lastRun = new RunSnapshot(Outcome.IDLE, 0, 0, 0, Map.of());

    public WorkshopOperationalDeletionScheduler(WorkshopOperationalDeletionCandidates candidates,
                                               WorkshopOperationalDeletionWorker worker) {
        this.candidates = Objects.requireNonNull(candidates);
        this.worker = Objects.requireNonNull(worker);
    }

    /** Observes only the last completed scheduler pass in this process, never account deletion. */
    public RunSnapshot lastRun() { return lastRun; }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void tick() {
        if (!running.compareAndSet(false, true)) return;
        try {
            WorkshopOperationalDeletionCandidates.Page page;
            try {
                page = Objects.requireNonNull(candidates.next(cursor));
                // Defensive validation also preserves rotation if an implementation is replaced.
                if (!page.candidates().isEmpty() && page.candidates().getFirst().tallerId() <= cursor)
                    throw new IllegalStateException("Invalid candidate rotation");
            } catch (RuntimeException failure) {
                lastRun = new RunSnapshot(Outcome.DISCOVERY_FAILED, 0, 0, 0, Map.of());
                LOG.warn("Borrado operativo: estado={}", Outcome.DISCOVERY_FAILED);
                return; // Failed discovery never consumes or resets the cursor.
            }
            var states = new EnumMap<WorkshopOperationalDeletionWorker.State, Integer>(WorkshopOperationalDeletionWorker.State.class);
            int attempted = 0;
            int failed = 0;
            for (var candidate : page.candidates()) {
                attempted++;
                try {
                    var result = Objects.requireNonNull(worker.run(candidate.tallerId(), candidate.closureReference()));
                    states.merge(Objects.requireNonNull(result.state()), 1, Integer::sum);
                } catch (RuntimeException failure) {
                    failed++;
                } finally {
                    // Blocked or failed workshops cannot starve later candidates.
                    cursor = candidate.tallerId();
                }
            }
            if (!page.hasMore()) cursor = 0;
            lastRun = new RunSnapshot(Outcome.COMPLETED, page.candidates().size(), attempted, failed, states);
            if (failed > 0) LOG.warn("Borrado operativo observado: candidatos={} intentos={} fallos={} estados={}",
                    lastRun.discovered(), lastRun.attempted(), lastRun.failed(), lastRun.states());
            else LOG.info("Borrado operativo observado: candidatos={} intentos={} fallos={} estados={}",
                    lastRun.discovered(), lastRun.attempted(), lastRun.failed(), lastRun.states());
        } finally {
            running.set(false);
        }
    }

    public enum Outcome { IDLE, COMPLETED, DISCOVERY_FAILED }

    /** COMPLETED means this pass finished; NO_PENDING_ROWS is a local observation only. */
    public record RunSnapshot(Outcome outcome, int discovered, int attempted, int failed,
                              Map<WorkshopOperationalDeletionWorker.State, Integer> states) {
        public RunSnapshot {
            Objects.requireNonNull(outcome);
            states = Map.copyOf(states);
            if (discovered < 0 || discovered > WorkshopOperationalDeletionCandidates.PAGE_SIZE
                    || attempted != discovered || failed < 0 || failed > attempted
                    || states.values().stream().anyMatch(count -> count < 1 || count > attempted)
                    || states.values().stream().mapToInt(Integer::intValue).sum() != attempted - failed
                    || (outcome != Outcome.COMPLETED && discovered != 0))
                throw new IllegalArgumentException("Invalid operational deletion observation");
        }
    }
}
