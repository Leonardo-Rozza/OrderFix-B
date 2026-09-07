package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalActorSnapshot;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalIdempotencyFingerprint;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalIdempotencyException.Reason.*;

/**
 * Transaction participant, never an independent writer/service. The owning REQUIRES_NEW boundary
 * must accredit its own privileges/deadline and deliver a result only after commit and release.
 */
final class LegalIdempotencyCoordinator {
    private static final String PHYSICAL_KEY_SQL = """
            SELECT pg_catalog.hashtextextended(pg_catalog.jsonb_build_array(
              'ordenfix:legal-idempotencia:tupla:v29', ?::text, ?::text, ?::text, ?::text)::text, 0)
            """;
    private static final String ACTOR_KEY_SQL = """
            SELECT pg_catalog.hashtextextended(pg_catalog.jsonb_build_array(
              'ordenfix:legal-actor:v1', ?::bigint, ?::bigint)::text, 0)
            """;
    private static final String EFFECTIVE_TRANSACTION_SQL = """
            SELECT pg_catalog.current_setting('transaction_isolation') AS isolation,
                   pg_catalog.current_setting('transaction_read_only') AS read_only,
                   pg_catalog.pg_current_xact_id()::text AS xid
            """;

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final LegalV29AcceptanceSchemaVerifier schema;
    private final LegalIdempotencyKeyring keyring;
    private final LegalIdempotencyResultStore store;

    LegalIdempotencyCoordinator(JdbcTemplate jdbc, LegalV29AcceptanceSchemaVerifier schema,
                               LegalIdempotencyKeyring keyring, LegalIdempotencyResultStore store) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.dataSource = Objects.requireNonNull(jdbc.getDataSource(), "jdbc.dataSource");
        this.schema = Objects.requireNonNull(schema, "schema");
        this.keyring = Objects.requireNonNull(keyring, "keyring");
        this.store = Objects.requireNonNull(store, "store");
        if (!schema.usesJdbc(jdbc) || !"public".equals(schema.expectedSchema()) || !store.usesJdbc(jdbc)) {
            throw new IllegalArgumentException("La coordinación idempotente requiere una frontera JDBC única");
        }
    }

    Reservation reserve(LegalAcceptanceCommand command, String key, IntSupplier remainingOperationMillis) {
        ConnectionHolder holder = null;
        try {
            holder = requireThreadBoundary(dataSource);
            Objects.requireNonNull(command, "command");
            Objects.requireNonNull(remainingOperationMillis, "remainingOperationMillis");
            operationRemaining(remainingOperationMillis);
            List<LegalIdempotencyFingerprint> candidates = keyring.candidates(command, key);
            Connection connection = Objects.requireNonNull(jdbc.execute((ConnectionCallback<Connection>) current -> {
                require(!current.getAutoCommit() && !current.isReadOnly()
                        && current.getTransactionIsolation() == Connection.TRANSACTION_READ_COMMITTED);
                Connection target = DataSourceUtils.getTargetConnection(current);
                require(DataSourceUtils.isConnectionTransactional(target, dataSource));
                return target;
            }));
            setTimeout(jdbc, "statement_timeout", Math.min(5_000, operationRemaining(remainingOperationMillis)));
            Map<String, Object> effective = jdbc.queryForMap(EFFECTIVE_TRANSACTION_SQL);
            require("read committed".equals(effective.get("isolation")) && "off".equals(effective.get("read_only")));
            String xid = Objects.toString(effective.get("xid"), "");
            require(xid.matches("[0-9]+"));
            schema.verify();
            operationRemaining(remainingOperationMillis);
            Reservation reservation = new Reservation(command, candidates, remainingOperationMillis,
                    holder, connection, xid);
            WaitBudget wait = new WaitBudget(System::nanoTime, remainingOperationMillis);
            List<Long> physical = new ArrayList<>();
            for (LegalIdempotencyFingerprint candidate : candidates) {
                wait.remainingMillis();
                Long lock = jdbc.queryForObject(PHYSICAL_KEY_SQL, Long.class, candidate.operation().name(),
                        candidate.routeTemplate(), candidate.scopeHmac(), candidate.idempotencyKeyHmac());
                require(lock != null);
                physical.add(lock);
                wait.remainingMillis();
            }
            for (long lock : orderedLocks(physical)) {
                setTimeout(jdbc, "lock_timeout", wait.remainingMillis());
                wait.remainingMillis();
                try {
                    jdbc.queryForList("SELECT pg_catalog.pg_advisory_xact_lock(?::bigint)", lock);
                } catch (RuntimeException failure) {
                    throw lockFailure(failure, wait);
                }
                wait.remainingMillis();
            }
            // No attempt to restore settings after a failed SQL statement in an aborted transaction.
            reservation.readBudget();
            reservation.replay = Objects.requireNonNull(store.lookup(reservation), "lookup");
            reservation.resolved = true;
            reservation.check();
            return reservation;
        } catch (RuntimeException failure) {
            if (holder != null) holder.setRollbackOnly();
            throw failure instanceof LegalIdempotencyException typed
                    ? typed : new LegalIdempotencyException(UNAVAILABLE, failure);
        }
    }

    static List<Long> orderedLocks(List<Long> physical) {
        if (physical == null || physical.isEmpty() || physical.size() > 8 || physical.stream().anyMatch(Objects::isNull)) {
            throw new LegalIdempotencyException(UNAVAILABLE);
        }
        return physical.stream().sorted(Long::compareUnsigned).distinct().toList();
    }

    static final class WaitBudget {
        private final LongSupplier clock;
        private final IntSupplier outer;
        private final long started;

        WaitBudget(LongSupplier clock, IntSupplier outer) {
            this.clock = Objects.requireNonNull(clock, "clock");
            this.outer = Objects.requireNonNull(outer, "outer");
            this.started = clock.getAsLong();
        }

        int remainingMillis() {
            int outerRemaining = operationRemaining(outer);
            long elapsed = clock.getAsLong() - started;
            if (elapsed < 0) throw new LegalIdempotencyException(UNAVAILABLE);
            long remaining = 5_000_000_000L - elapsed;
            if (remaining < 1_000_000L) throw new LegalIdempotencyException(IN_PROGRESS);
            return Math.min(Math.toIntExact(remaining / 1_000_000L), outerRemaining);
        }
    }

    /** Opaque capability tied to one holder, connection and xid; no raw key or password diagnostics. */
    final class Reservation {
        private final LegalAcceptanceCommand command;
        private final List<LegalIdempotencyFingerprint> candidates;
        private final IntSupplier remaining;
        private final ConnectionHolder holder;
        private final Connection connection;
        private final String xid;
        private Optional<LegalIdempotencyResultStore.StoredResult> replay = Optional.empty();
        private boolean resolved;
        private boolean written;

        private Reservation(LegalAcceptanceCommand command, List<LegalIdempotencyFingerprint> candidates,
                            IntSupplier remaining, ConnectionHolder holder, Connection connection, String xid) {
            this.command = command;
            this.candidates = List.copyOf(candidates);
            this.remaining = remaining;
            this.holder = holder;
            this.connection = connection;
            this.xid = xid;
        }

        JdbcTemplate jdbc() { check(); return jdbc; }
        List<LegalIdempotencyFingerprint> candidates() { return candidates; }
        LegalAcceptanceCommand command() { return command; }
        LegalIdempotencyKeyring keyring() { return keyring; }
        LegalIdempotencyFingerprint activeFingerprint() {
            return candidates.stream().filter(candidate -> candidate.keyVersion() == keyring.activeWriteVersion())
                    .findFirst().orElseThrow(() -> new LegalIdempotencyException(UNAVAILABLE));
        }

        Optional<LegalIdempotencyResultStore.StoredResult> replay() {
            check();
            require(resolved && !written);
            return replay;
        }

        void check() {
            operationRemaining(remaining);
            require(requireThreadBoundary(dataSource) == holder && !holder.isRollbackOnly()
                    && DataSourceUtils.getTargetConnection(holder.getConnection()) == connection);
        }

        void readBudget() {
            check();
            setTimeout(jdbc, "statement_timeout", Math.min(5_000, operationRemaining(remaining)));
            setTimeout(jdbc, "lock_timeout", Math.min(1_000, operationRemaining(remaining)));
        }

        void verify() {
            readBudget();
            Map<String, Object> effective = jdbc.queryForMap(EFFECTIVE_TRANSACTION_SQL);
            require(xid.equals(effective.get("xid")) && "read committed".equals(effective.get("isolation"))
                    && "off".equals(effective.get("read_only")));
            for (var candidate : candidates) {
                check();
                jdbc.queryForList("""
                        SELECT public.legal_exigir_lock_idempotente_v29(
                          ?::varchar, ?::varchar, ?::varchar, ?::varchar)
                        """, candidate.operation().name(), candidate.routeTemplate(), candidate.scopeHmac(),
                        candidate.idempotencyKeyHmac());
            }
            check();
        }

        void requireNew() {
            verify();
            require(resolved && replay.isEmpty() && !written);
        }

        void markWritten() {
            check();
            require(resolved && replay.isEmpty() && !written);
            written = true;
        }

        void lockReplayActor(long userId, long tallerId) {
            require(userId > 0 && tallerId > 0);
            LegalActorSnapshot expected = command.actor();
            if (expected != null) {
                require(expected.userId() == userId);
                if (expected.tallerId() != tallerId) throw new LegalIdempotencyException(INVALID_ACTOR);
            }
            readBudget();
            Long key = jdbc.queryForObject(ACTOR_KEY_SQL, Long.class, tallerId, userId);
            require(key != null);
            jdbc.queryForList("SELECT pg_catalog.pg_advisory_xact_lock_shared(?::bigint)", key);
            lockAndValidateRows(userId, tallerId, expected);
        }

        void requireWriteActor(LegalActorSnapshot actor) {
            require(actor != null && actor.active() && actor.workshopActive());
            if (command.actor() != null) {
                require(command.actor().equals(actor));
            } else {
                require(actor.role() == UserRole.ADMIN);
            }
            readBudget();
            Long key = jdbc.queryForObject(ACTOR_KEY_SQL, Long.class, actor.tallerId(), actor.userId());
            require(key != null);
            Boolean held = jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM pg_catalog.pg_locks l
                      WHERE l.locktype = 'advisory' AND l.pid = pg_catalog.pg_backend_pid()
                        AND l.database = (SELECT oid FROM pg_catalog.pg_database WHERE datname = pg_catalog.current_database())
                        AND l.classid = ((?::bigint >> 32) & 4294967295)::oid
                        AND l.objid = (?::bigint & 4294967295)::oid AND l.objsubid = 1
                        AND l.mode = 'ExclusiveLock' AND l.granted)
                    """, Boolean.class, key, key);
            require(Boolean.TRUE.equals(held));
            lockAndValidateRows(actor.userId(), actor.tallerId(), actor);
        }

        private void lockAndValidateRows(long userId, long tallerId, LegalActorSnapshot expected) {
            check();
            List<Map<String, Object>> workshops = jdbc.queryForList(
                    "SELECT id, activo FROM public.talleres WHERE id = ? LIMIT 2 FOR SHARE", tallerId);
            if (workshops.size() != 1 || !Boolean.TRUE.equals(workshops.getFirst().get("activo"))) invalidActor();
            check();
            List<Map<String, Object>> users = jdbc.queryForList("""
                    SELECT id, taller_id, role, active, token_version
                      FROM public.users WHERE id = ? LIMIT 2 FOR SHARE
                    """, userId);
            if (users.size() != 1) invalidActor();
            Map<String, Object> user = users.getFirst();
            if (!Objects.equals(user.get("taller_id"), tallerId) || !Boolean.TRUE.equals(user.get("active"))
                    || !("ADMIN".equals(user.get("role")) || "USER".equals(user.get("role")))
                    || !(user.get("token_version") instanceof Number token) || token.longValue() < 0) invalidActor();
            if (expected != null && (!expected.role().name().equals(user.get("role"))
                    || !Objects.equals(user.get("token_version"), expected.tokenVersion()))) invalidActor();
            check();
        }

        LegalIdempotencyException fail(RuntimeException failure) {
            // Mark this holder only; never poison a different transaction after token misuse.
            if (TransactionSynchronizationManager.getResource(dataSource) == holder) holder.setRollbackOnly();
            return failure instanceof LegalIdempotencyException typed
                    ? typed : new LegalIdempotencyException(UNAVAILABLE, failure);
        }

        @Override public String toString() { return "LegalIdempotencyReservation[redacted]"; }
    }

    private static ConnectionHolder requireThreadBoundary(DataSource source) {
        require(TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()
                && !TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                && Objects.equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),
                        Connection.TRANSACTION_READ_COMMITTED));
        Object resource = TransactionSynchronizationManager.getResource(source);
        require(resource instanceof ConnectionHolder);
        require(!((ConnectionHolder) resource).isRollbackOnly());
        return (ConnectionHolder) resource;
    }

    private static int operationRemaining(IntSupplier remaining) {
        int milliseconds = remaining.getAsInt();
        require(milliseconds > 0 && !Thread.currentThread().isInterrupted());
        return milliseconds;
    }

    private static void setTimeout(JdbcTemplate jdbc, String setting, int milliseconds) {
        require(milliseconds > 0);
        jdbc.queryForObject("SELECT pg_catalog.set_config(?, ?, true)", String.class,
                setting, milliseconds + "ms");
    }

    static LegalIdempotencyException lockFailure(RuntimeException failure, WaitBudget wait) {
        String state = null;
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql
                    && ("55P03".equals(sql.getSQLState()) || "57014".equals(sql.getSQLState()))) {
                state = sql.getSQLState();
                break;
            }
        }
        if (state == null) return new LegalIdempotencyException(UNAVAILABLE, failure);
        try {
            operationRemaining(wait.outer);
            if ("55P03".equals(state)) return new LegalIdempotencyException(IN_PROGRESS, failure);
            // Administrative cancellation must not masquerade as an idempotency wait timeout.
            wait.remainingMillis();
            return new LegalIdempotencyException(UNAVAILABLE, failure);
        } catch (LegalIdempotencyException exhausted) {
            return new LegalIdempotencyException(exhausted.reason(), failure);
        } catch (RuntimeException exhausted) {
            if (exhausted != failure) failure.addSuppressed(exhausted);
            return new LegalIdempotencyException(UNAVAILABLE, failure);
        }
    }

    private static void require(boolean condition) {
        if (!condition) throw new LegalIdempotencyException(UNAVAILABLE);
    }

    private static void invalidActor() { throw new LegalIdempotencyException(INVALID_ACTOR); }
}
