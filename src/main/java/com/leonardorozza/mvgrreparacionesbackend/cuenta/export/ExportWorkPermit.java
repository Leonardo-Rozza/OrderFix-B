package com.leonardorozza.mvgrreparacionesbackend.cuenta.export;

import org.springframework.stereotype.Component;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** One heavy export operation per JVM, including the lifetime of a synchronous HTTP response. */
@Component
public final class ExportWorkPermit {
    private final Semaphore capacity = new Semaphore(1);

    /** Never queues a request behind a slow client or an ongoing worker. */
    public Lease tryAcquire() {
        return capacity.tryAcquire() ? new Lease(capacity) : null;
    }

    public static final class Lease implements AutoCloseable {
        private final Semaphore capacity;
        private final AtomicBoolean closed = new AtomicBoolean();
        private Lease(Semaphore capacity) { this.capacity = capacity; }
        @Override public void close() {
            if (closed.compareAndSet(false, true)) capacity.release();
        }
    }
}
