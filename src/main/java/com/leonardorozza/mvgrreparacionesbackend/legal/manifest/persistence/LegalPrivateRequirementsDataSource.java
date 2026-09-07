package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import org.springframework.jdbc.datasource.AbstractDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Isolated pool boundary applying the remaining operation budget to JDBC execution and FETCH.
 * A watchdog cancels the current statement and aborts the leased connection on expiry. This is
 * not a wall-clock SLA: cancellation/driver teardown can finish after the deadline. No expired
 * observation is returned, including when commit/cleanup completed too late. A successful mutable
 * commit is first reported to Spring; only the outer operation checks expiry after that commit.
 * Release failures are retained by the shared operation deadline even if Spring absorbs them.
 */
final class LegalPrivateRequirementsDataSource extends AbstractDataSource implements AutoCloseable {

    static final int BORROW_TIMEOUT_MILLIS = 1_000;
    private static final int STATEMENT_TIMEOUT_MILLIS = 5_000;
    private final DataSource pool;
    private final Duration operationBudget;
    private final LongSupplier clock;
    private final int networkTimeoutMillis;
    private final boolean registrationBoundary;
    private final ThreadLocal<LegalPrivateRequirementsDeadline> current = new ThreadLocal<>();
    private final Set<Lease> leases = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    private final ScheduledThreadPoolExecutor watchdog = new ScheduledThreadPoolExecutor(2, task -> {
        Thread thread = new Thread(task, "legal-private-requirements-deadline");
        thread.setDaemon(true);
        return thread;
    });

    LegalPrivateRequirementsDataSource(DataSource pool, Duration operationBudget) {
        this(pool, operationBudget, System::nanoTime);
    }

    /** Package-private clock seam for deterministic phase/commit boundary tests. */
    LegalPrivateRequirementsDataSource(DataSource pool, Duration operationBudget, LongSupplier clock) {
        this(pool, operationBudget, clock, 0);
    }

    /** Optional bounded transport margin lets PostgreSQL deliver its SQL timeout before the socket closes. */
    LegalPrivateRequirementsDataSource(DataSource pool, Duration operationBudget, LongSupplier clock,
                                       int networkTimeoutGraceMillis) {
        if (networkTimeoutGraceMillis < 0 || networkTimeoutGraceMillis > 1_000) {
            throw new IllegalArgumentException("El margen de transporte legal debe estar entre 0 y 1000 ms");
        }
        this.pool = Objects.requireNonNull(pool, "pool");
        this.operationBudget = Objects.requireNonNull(operationBudget, "operationBudget");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.networkTimeoutMillis = STATEMENT_TIMEOUT_MILLIS + networkTimeoutGraceMillis;
        this.registrationBoundary = false;
        new LegalPrivateRequirementsDeadline(operationBudget, clock);
        watchdog.setRemoveOnCancelPolicy(true);
    }

    /** Fixed registration capability; callers cannot supply a different outer budget or margin. */
    static LegalPrivateRequirementsDataSource registration(DataSource pool) {
        return registration(pool, System::nanoTime);
    }

    static LegalPrivateRequirementsDataSource registration(DataSource pool, LongSupplier clock) {
        return new LegalPrivateRequirementsDataSource(pool, clock);
    }

    private LegalPrivateRequirementsDataSource(DataSource pool, LongSupplier clock) {
        this.pool = Objects.requireNonNull(pool, "pool");
        this.operationBudget = Duration.ofSeconds(30);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.networkTimeoutMillis = STATEMENT_TIMEOUT_MILLIS + 1_000;
        this.registrationBoundary = true;
        // Construction does not start the clock; the owning operation opens its one deadline.
        watchdog.setRemoveOnCancelPolicy(true);
    }

    boolean isRegistrationBoundary() { return registrationBoundary; }

    private LegalPrivateRequirementsDeadline newDeadline() {
        return registrationBoundary ? LegalPrivateRequirementsDeadline.registration(clock)
                : new LegalPrivateRequirementsDeadline(operationBudget, clock);
    }

    <T> T withinDeadline(Function<LegalPrivateRequirementsDeadline, T> operation) {
        LegalPrivateRequirementsDeadline previous = current.get();
        LegalPrivateRequirementsDeadline deadline = previous == null ? newDeadline() : previous;
        current.set(deadline);
        try {
            requireOpen();
            deadline.check();
            T result = operation.apply(deadline);
            deadline.check();
            requireOpen();
            return result;
        } finally {
            if (previous == null) {
                current.remove();
            } else {
                current.set(previous);
            }
        }
    }

    @Override
    public Connection getConnection() throws SQLException {
        requireOpen();
        LegalPrivateRequirementsDeadline deadline = current.get();
        if (deadline == null) {
            throw new SQLException("La conexión de requisitos requiere un presupuesto activo");
        }
        // Never start a pool borrow whose configured bound exceeds the remaining budget.
        if (deadline.remainingMillis() < BORROW_TIMEOUT_MILLIS) {
            throw new LegalPrivateRequirementsReadException();
        }
        Connection connection = pool.getConnection();
        try {
            deadline.check();
            requireOpen();
            Lease lease = new Lease(connection, deadline);
            leases.add(lease);
            if (closed) {
                lease.expire();
                lease.release();
                throw new LegalPrivateRequirementsReadException();
            }
            return lease.proxy();
        } catch (RuntimeException | SQLException failure) {
            try {
                connection.close();
            } catch (SQLException | RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        throw new SQLFeatureNotSupportedException("La credencial de requisitos es fija");
    }

    @Override
    public void close() {
        closed = true;
        leases.forEach(Lease::expire);
        watchdog.shutdownNow();
    }

    private void requireOpen() {
        if (closed) {
            throw new LegalPrivateRequirementsReadException();
        }
    }

    private final class Lease {
        private final Connection connection;
        private final LegalPrivateRequirementsDeadline deadline;
        private final ScheduledFuture<?> expiration;
        private volatile Statement active;
        private boolean released;
        private boolean expired;
        private Connection exposed;

        private Lease(Connection connection, LegalPrivateRequirementsDeadline deadline) throws SQLException {
            this.connection = connection;
            this.deadline = deadline;
            connection.setNetworkTimeout(Runnable::run,
                    Math.min(networkTimeoutMillis, deadline.remainingMillis()));
            expiration = watchdog.schedule(this::expire, deadline.remainingMillis(), TimeUnit.MILLISECONDS);
        }

        private synchronized Connection proxy() {
            if (exposed != null) {
                return exposed;
            }
            exposed = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return objectMethod(proxy, method, args);
                        }
                        String name = method.getName();
                        if (name.equals("close")) {
                            release();
                            return null;
                        }
                        synchronized (this) {
                            if (released) {
                                if (name.equals("isClosed")) {
                                    return true;
                                }
                                throw new SQLException("La conexión de requisitos está cerrada");
                            }
                        }
                        if (name.equals("unwrap")) {
                            throw new SQLException("La conexión de requisitos no expone su delegado");
                        }
                        if (name.equals("isWrapperFor")) {
                            return false;
                        }
                        // Rollback and Spring cleanup must still run after expiry/failure.
                        boolean cleanup = name.equals("rollback") || name.equals("setAutoCommit")
                                || name.equals("setReadOnly") || name.equals("setTransactionIsolation")
                                || name.equals("isClosed") || name.equals("getWarnings")
                                || name.equals("clearWarnings");
                        if (!cleanup) {
                            beforeIo();
                        }
                        if (name.equals("commit")) {
                            // The precheck above may fail before COMMIT and must remain a runtime
                            // failure so Spring can roll back. Once COMMIT is invoked, an unchecked
                            // driver failure has an uncertain outcome, just like SQLException.
                            try {
                                return invoke(connection, method, args);
                            } catch (RuntimeException commitFailure) {
                                throw new SQLException(
                                        "La confirmación de requisitos tiene resultado indeterminado",
                                        commitFailure);
                            }
                            // Do not check the deadline here after a successful delegate COMMIT.
                            // withinDeadline checks it after Spring has reported COMMITTED/cleanup.
                        }
                        Object value = invoke(connection, method, args);
                        if (value instanceof Statement statement) {
                            return statementProxy(statement);
                        }
                        if (value instanceof DatabaseMetaData metadata) {
                            return Proxy.newProxyInstance(DatabaseMetaData.class.getClassLoader(),
                                    new Class<?>[]{DatabaseMetaData.class}, (metadataProxy, operation, arguments) -> {
                                        if (operation.getDeclaringClass() == Object.class) {
                                            return objectMethod(metadataProxy, operation, arguments);
                                        }
                                        if (operation.getName().equals("getConnection")) {
                                            return proxy();
                                        }
                                        if (operation.getName().equals("unwrap")) {
                                            throw new SQLException("Los metadatos no exponen su delegado");
                                        }
                                        if (operation.getName().equals("isWrapperFor")) {
                                            return false;
                                        }
                                        beforeIo();
                                        Object metadataValue = invoke(metadata, operation, arguments);
                                        if (metadataValue instanceof ResultSet rows) {
                                            return resultSetProxy(rows, rows.getStatement());
                                        }
                                        return metadataValue;
                                    });
                        }
                        return value;
                    });
            return exposed;
        }

        private Object statementProxy(Statement statement) {
            Class<?> type = statement instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
            return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) {
                    return objectMethod(proxy, method, args);
                }
                String name = method.getName();
                if (name.equals("unwrap")) {
                    throw new SQLException("La sentencia de requisitos no expone su delegado");
                }
                if (name.equals("isWrapperFor")) {
                    return false;
                }
                if (name.equals("getConnection")) {
                    return proxy();
                }
                if (name.startsWith("execute")) {
                    beforeIo();
                    active = statement;
                    int remaining = Math.min(STATEMENT_TIMEOUT_MILLIS, deadline.remainingMillis());
                    statement.setQueryTimeout(Math.max(1, (remaining + 999) / 1_000));
                    // Millisecond PostgreSQL budget also covers cursor FETCH, unlike queryTimeout.
                    if (!connection.getAutoCommit()) {
                        try (Statement settings = connection.createStatement()) {
                            settings.setQueryTimeout(Math.max(1, (remaining + 999) / 1_000));
                            settings.execute("SET LOCAL statement_timeout TO '" + remaining + "ms'");
                        }
                    }
                }
                Object result = invoke(statement, method, args);
                if (name.startsWith("execute")) {
                    deadline.check();
                }
                if (result instanceof ResultSet rows) {
                    return resultSetProxy(rows, statement);
                }
                return result;
            });
        }

        private ResultSet resultSetProxy(ResultSet rows, Statement statement) {
            return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                    new Class<?>[]{ResultSet.class}, (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return objectMethod(proxy, method, args);
                        }
                        String name = method.getName();
                        if (name.equals("unwrap")) {
                            throw new SQLException("El cursor de requisitos no expone su delegado");
                        }
                        if (name.equals("isWrapperFor")) {
                            return false;
                        }
                        if (name.equals("getStatement")) {
                            return statement == null ? null : statementProxy(statement);
                        }
                        if (name.equals("next")) {
                            beforeIo();
                            active = statement;
                        }
                        Object result = invoke(rows, method, args);
                        if (name.equals("next")) {
                            deadline.check();
                        }
                        return result;
                    });
        }

        private void beforeIo() throws SQLException {
            deadline.check();
            synchronized (this) {
                if (released || expired) {
                    throw new LegalPrivateRequirementsReadException();
                }
                connection.setNetworkTimeout(Runnable::run,
                        Math.min(networkTimeoutMillis, deadline.remainingMillis()));
            }
        }

        private synchronized void expire() {
            if (released) {
                return;
            }
            expired = true;
            deadline.cancel(active);
            try {
                connection.abort(Runnable::run);
            } catch (SQLException | RuntimeException ignored) {
                // The driver/socket timeout remains bounded and the caller still closes the lease.
            }
        }

        private synchronized void release() throws SQLException {
            if (!released) {
                released = true;
                leases.remove(this);
                expiration.cancel(false);
                // Never let a late watchdog abort a connection already returned to the pool.
                try {
                    connection.close();
                } catch (SQLException | RuntimeException failure) {
                    deadline.recordCleanupFailure(failure);
                    throw failure;
                }
            }
        }
    }

    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "LegalPrivateRequirementsJdbc[restricted]";
            default -> throw new IllegalStateException("Método Object JDBC inesperado");
        };
    }

    private static Object invoke(Object target, Method method, Object[] arguments) throws Throwable {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException exception) {
            throw exception.getCause();
        }
    }
}
