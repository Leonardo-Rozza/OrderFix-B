package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.zaxxer.hikari.HikariDataSource;

import java.util.Objects;
import java.util.function.Function;

/** Owns only the private pool and routing protection of one explicitly composed context. */
public final class LegalRegistrationSessionResources implements AutoCloseable {
    private final Function<HikariDataSource, HikariDataSource> poolFactory;
    private State state = State.NEW;
    private HikariDataSource historical;
    private HikariDataSource pool;
    private LegalRegistrationSessionDataSource router;
    private Throwable failure;

    LegalRegistrationSessionResources() {
        this(LegalRegistrationSessionPoolFactory::create);
    }

    /** Construction seam; the application configuration always uses the nominal factory. */
    LegalRegistrationSessionResources(Function<HikariDataSource, HikariDataSource> poolFactory) {
        this.poolFactory = Objects.requireNonNull(poolFactory, "poolFactory");
    }

    synchronized LegalRegistrationSessionDataSource install(HikariDataSource original) {
        Objects.requireNonNull(original, "historical");
        if ((state == State.PENDING || state == State.READY) && historical == original) return router;
        if (state != State.NEW) throw unavailable();
        HikariDataSource created = null;
        try {
            created = Objects.requireNonNull(poolFactory.apply(original), "dedicated");
            if (created == original) throw new IllegalArgumentException("La sesión de registro requiere un pool dedicado");
            var protection = new LegalRegistrationSessionDataSource(original, created);
            historical = original;
            pool = created;
            router = protection;
            state = State.PENDING;
            return protection;
        } catch (RuntimeException | Error primary) {
            failure = primary;
            state = State.FAILED;
            if (created != null && created != original) {
                try { created.close(); }
                catch (RuntimeException | Error cleanup) { suppress(primary, cleanup); }
            }
            throw primary;
        }
    }

    synchronized void recordFailure(RuntimeException primary) {
        Objects.requireNonNull(primary, "failure");
        if (failure == null) failure = primary;
        else if (failure != primary && primary.getCause() != failure) suppress(failure, primary);
        if (state != State.CLOSED) state = State.FAILED;
    }

    /** Called only by the mandatory non-lazy gate, after the original bean became disposable. */
    synchronized void markReady() {
        if (state == State.READY) return;
        if (state != State.PENDING) throw unavailable();
        state = State.READY;
    }

    public <T> T withinRegistrationBudget(LegalRegistrationBudget owner, Function<LegalRegistrationBudget, T> work) {
        return readyRouter().withinRegistrationBudget(owner, work);
    }

    private synchronized LegalRegistrationSessionDataSource readyRouter() {
        if (state != State.READY) throw unavailable();
        return router;
    }

    private LegalRegistrationSessionUnavailableException unavailable() {
        return new LegalRegistrationSessionUnavailableException(failure);
    }

    @Override public synchronized void close() {
        if (state == State.CLOSED) return;
        state = State.CLOSED;
        Throwable primary = null;
        try {
            if (router != null) router.close();
        } catch (RuntimeException | Error cleanup) {
            primary = cleanup;
        }
        try {
            if (pool != null) pool.close();
        } catch (RuntimeException | Error cleanup) {
            if (primary == null) primary = cleanup;
            else suppress(primary, cleanup);
        }
        if (primary instanceof RuntimeException runtime) throw runtime;
        if (primary instanceof Error error) throw error;
    }

    private static void suppress(Throwable primary, Throwable secondary) {
        if (primary != secondary) primary.addSuppressed(secondary);
    }

    @Override public String toString() { return "LegalRegistrationSessionResources[redacted]"; }

    private enum State { NEW, PENDING, READY, FAILED, CLOSED }
}
