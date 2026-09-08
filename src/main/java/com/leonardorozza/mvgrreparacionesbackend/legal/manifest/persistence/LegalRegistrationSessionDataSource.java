package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ConnectionBuilder;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.ShardingKeyBuilder;
import java.sql.Statement;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Inert, explicit routing for registration sessions. The caller owns both supplied pools and
 * finishes its transaction/resources inside the scope. No expired result is delivered; driver
 * cancellation and teardown are not a wall-clock SLA. Outside the scope the historical delegate
 * is returned literally, including after this router's protection has been shut down.
 */
public final class LegalRegistrationSessionDataSource implements DataSource, AutoCloseable {
    static final int BORROW_TIMEOUT_MILLIS = 1_000;
    private static final int STATEMENT_TIMEOUT_MILLIS = 5_000;
    private static final int NETWORK_TIMEOUT_MILLIS = 6_000;
    private final DataSource historical;
    private final DataSource dedicated;
    private final ThreadLocal<LegalRegistrationBudget> current = new ThreadLocal<>();
    private final Set<Lease> leases = ConcurrentHashMap.newKeySet();
    private final ScheduledThreadPoolExecutor watchdog = new ScheduledThreadPoolExecutor(2, task -> {
        Thread thread = new Thread(task, "legal-registration-session-deadline");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean closed;

    LegalRegistrationSessionDataSource(DataSource historical, DataSource dedicated) {
        this.historical = Objects.requireNonNull(historical, "historical");
        this.dedicated = Objects.requireNonNull(dedicated, "dedicated");
        if (historical == dedicated) {
            throw new IllegalArgumentException("La sesión de registro requiere un delegado dedicado");
        }
        watchdog.setRemoveOnCancelPolicy(true);
    }

    public <T> T withinRegistrationBudget(LegalRegistrationBudget owner, Function<LegalRegistrationBudget, T> work) {
        LegalRegistrationBudget previous = current.get();
        if (owner == null || work == null || previous != null && previous != owner) {
            throw new LegalRegistrationSessionUnavailableException();
        }
        current.set(owner);
        try {
            try {
                check(owner);
                requireOpen();
                T result = work.apply(owner);
                check(owner);
                requireOpen();
                return result;
            } catch (RuntimeException failure) {
                // This runs after the caller's transaction/cleanup also on exceptional delivery.
                // Preserve Error unchanged: neither it nor persistence is interpreted here.
                try {
                    check(owner);
                    requireOpen();
                } catch (RuntimeException boundaryFailure) {
                    suppress(boundaryFailure, failure);
                    throw boundaryFailure;
                }
                throw failure;
            }
        } finally {
            if (previous == null) current.remove();
            else current.set(previous);
        }
    }

    @Override public Connection getConnection() throws SQLException {
        LegalRegistrationBudget owner = current.get();
        if (owner == null) return historical.getConnection();
        requireOpen();
        if (remaining(owner) < BORROW_TIMEOUT_MILLIS) {
            throw new LegalRegistrationSessionUnavailableException();
        }
        Connection connection = dedicated.getConnection();
        Lease lease = null;
        try {
            check(owner);
            requireOpen();
            lease = new Lease(connection, owner);
            leases.add(lease);
            if (closed) {
                lease.expire();
                lease.release();
                throw new LegalRegistrationSessionUnavailableException();
            }
            return lease.proxy();
        } catch (SQLException | RuntimeException failure) {
            try {
                if (lease != null) lease.release();
                else {
                    try {
                        connection.close();
                    } catch (SQLException | RuntimeException cleanup) {
                        owner.recordCleanupFailure(cleanup);
                        throw cleanup;
                    }
                }
            } catch (SQLException | RuntimeException cleanup) {
                suppress(failure, cleanup);
            }
            throw failure;
        }
    }

    @Override public Connection getConnection(String username, String password) throws SQLException {
        if (current.get() != null) throw unsupported();
        return historical.getConnection(username, password);
    }

    @Override public PrintWriter getLogWriter() throws SQLException { return historical.getLogWriter(); }
    @Override public int getLoginTimeout() throws SQLException { return historical.getLoginTimeout(); }
    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { return historical.getParentLogger(); }
    @Override public void setLogWriter(PrintWriter writer) throws SQLException {
        if (current.get() != null) throw unsupported();
        historical.setLogWriter(writer);
    }
    @Override public void setLoginTimeout(int seconds) throws SQLException {
        if (current.get() != null) throw unsupported();
        historical.setLoginTimeout(seconds);
    }
    @Override public <T> T unwrap(Class<T> type) throws SQLException {
        if (current.get() != null) throw unsupported();
        return historical.unwrap(type);
    }
    @Override public boolean isWrapperFor(Class<?> type) throws SQLException {
        return current.get() == null && historical.isWrapperFor(type);
    }
    @Override public ConnectionBuilder createConnectionBuilder() throws SQLException {
        if (current.get() != null) throw unsupported();
        return historical.createConnectionBuilder();
    }
    @Override public ShardingKeyBuilder createShardingKeyBuilder() throws SQLException {
        if (current.get() != null) throw unsupported();
        return historical.createShardingKeyBuilder();
    }

    /** Stop only this protection; ownership of both delegates remains with their supplier. */
    @Override public void close() {
        closed = true;
        try {
            leases.forEach(lease -> {
                lease.expire();
                try { lease.release(); }
                catch (SQLException | RuntimeException ignored) {
                    // Release already recorded the original failure on this lease's owner.
                }
            });
        } finally {
            watchdog.shutdownNow();
        }
    }

    private void requireOpen() {
        if (closed) throw new LegalRegistrationSessionUnavailableException();
    }
    private static SQLFeatureNotSupportedException unsupported() {
        return new SQLFeatureNotSupportedException("La sesión de registro no permite sustituir su frontera JDBC");
    }
    private static int remaining(LegalRegistrationBudget owner) {
        try {
            return owner.remainingMillis();
        } catch (LegalRegistrationBudget.UnavailableException failure) {
            throw new LegalRegistrationSessionUnavailableException(failure.getCause());
        }
    }
    private static void check(LegalRegistrationBudget owner) { remaining(owner); }
    private static void suppress(Throwable primary, Throwable secondary) {
        if (primary != secondary) primary.addSuppressed(secondary);
    }

    private final class Lease {
        private final Connection connection;
        private final LegalRegistrationBudget owner;
        private final ScheduledFuture<?> expiration;
        private final Map<Statement, StatementHandle> statements = new IdentityHashMap<>();
        private final Map<ResultSet, RowsHandle> rows = new IdentityHashMap<>();
        private volatile Statement active;
        private boolean released;
        private boolean expired;
        private Connection exposed;

        private Lease(Connection connection, LegalRegistrationBudget owner) throws SQLException {
            this.connection = Objects.requireNonNull(connection, "connection");
            this.owner = owner;
            connection.setNetworkTimeout(Runnable::run, Math.min(NETWORK_TIMEOUT_MILLIS, remaining(owner)));
            expiration = watchdog.schedule(this::expire, remaining(owner), TimeUnit.MILLISECONDS);
        }

        private synchronized Connection proxy() {
            if (exposed != null) return exposed;
            exposed = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
                        String name = method.getName();
                        if (name.equals("close")) { release(); return null; }
                        if (name.equals("unwrap")) throw unsupported();
                        if (name.equals("isWrapperFor")) return false;
                        boolean control = connectionControl(name, args);
                        try {
                            synchronized (this) {
                                if (released) {
                                    if (name.equals("isClosed")) return true;
                                    throw new SQLException("La conexión de sesión está cerrada");
                                }
                            }
                            if (!control) beforeIo();
                            if (name.equals("commit")) {
                                // Only the delegate's unchecked failure is converted to a checked
                                // commit failure. A precheck above can still prevent COMMIT entirely.
                                try {
                                    return invoke(connection, method, args);
                                } catch (RuntimeException failure) {
                                    throw new SQLException("La confirmación de sesión no pudo acreditarse", failure);
                                }
                                // No post-COMMIT check here: JPA owns completion notification.
                            }
                            Object[] arguments = args;
                            if (name.equals("setNetworkTimeout")) {
                                arguments = args.clone();
                                int requested = (Integer) args[1];
                                int cap = Math.min(NETWORK_TIMEOUT_MILLIS, remaining(owner));
                                arguments[1] = requested == 0 ? cap : Math.min(requested, cap);
                            }
                            Object result = invoke(connection, method, arguments);
                            if (!control) check(owner);
                            if (result instanceof Statement statement) return statement(statement).proxy();
                            if (result instanceof DatabaseMetaData metadata) return metadata(metadata);
                            return result;
                        } catch (SQLException | RuntimeException failure) {
                            if (control) owner.recordCleanupFailure(failure);
                            throw failure;
                        }
                    });
            return exposed;
        }

        private Object metadata(DatabaseMetaData metadata) {
            return Proxy.newProxyInstance(DatabaseMetaData.class.getClassLoader(), new Class<?>[]{DatabaseMetaData.class},
                    (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
                        String name = method.getName();
                        if (name.equals("getConnection")) return proxy();
                        if (name.equals("unwrap")) throw unsupported();
                        if (name.equals("isWrapperFor")) return false;
                        beforeIo();
                        Object result = invoke(metadata, method, args);
                        check(owner);
                        if (result instanceof ResultSet resultSet) return rows(resultSet, resultSet.getStatement()).proxy();
                        return result;
                    });
        }

        private synchronized StatementHandle statement(Statement statement) {
            return statements.computeIfAbsent(statement, StatementHandle::new);
        }
        private synchronized RowsHandle rows(ResultSet resultSet, Statement statement) {
            return rows.computeIfAbsent(resultSet, key -> new RowsHandle(key, statement));
        }

        private void beforeIo() throws SQLException {
            check(owner);
            if (current.get() != owner) throw new LegalRegistrationSessionUnavailableException();
            synchronized (this) {
                if (released || expired) throw new LegalRegistrationSessionUnavailableException();
                connection.setNetworkTimeout(Runnable::run, Math.min(NETWORK_TIMEOUT_MILLIS, remaining(owner)));
            }
        }

        private void prepareExecution(Statement statement) throws SQLException {
            beforeIo();
            active = statement;
            int millis = Math.min(STATEMENT_TIMEOUT_MILLIS, remaining(owner));
            int seconds = Math.max(1, (millis + 999) / 1_000);
            statement.setQueryTimeout(seconds);
            if (!connection.getAutoCommit()) {
                try (Settings settings = new Settings(connection.createStatement())) {
                    settings.statement.setQueryTimeout(seconds);
                    settings.statement.execute("SET LOCAL statement_timeout TO '" + millis + "ms'");
                }
            }
            check(owner);
        }

        private synchronized void expire() {
            if (released) return;
            expired = true;
            if (active != null) {
                try { active.cancel(); }
                catch (SQLException | RuntimeException ignored) { /* Driver cancellation remains best effort. */ }
            }
            try { connection.abort(Runnable::run); }
            catch (SQLException | RuntimeException ignored) { /* The lease still has one owning close. */ }
        }

        private synchronized void release() throws SQLException {
            if (released) return;
            released = true;
            leases.remove(this);
            expiration.cancel(false);
            try {
                connection.close();
            } catch (SQLException | RuntimeException failure) {
                owner.recordCleanupFailure(failure);
                throw failure;
            }
        }

        private final class Settings implements AutoCloseable {
            private final Statement statement;
            private Settings(Statement statement) { this.statement = statement; }
            @Override public void close() throws SQLException {
                try { statement.close(); }
                catch (SQLException | RuntimeException failure) { owner.recordCleanupFailure(failure); throw failure; }
            }
        }

        private final class StatementHandle {
            private final Statement delegate;
            private Statement exposed;
            private boolean closed;
            private StatementHandle(Statement delegate) { this.delegate = delegate; }

            private synchronized Statement proxy() {
                if (exposed != null) return exposed;
                Class<?> type = delegate instanceof CallableStatement ? CallableStatement.class
                        : delegate instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
                exposed = (Statement) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
                    String name = method.getName();
                    if (name.equals("getConnection")) return Lease.this.proxy();
                    if (name.equals("unwrap")) throw unsupported();
                    if (name.equals("isWrapperFor")) return false;
                    if (name.equals("close")) { close(); return null; }
                    boolean control = statementControl(name, args);
                    try {
                        synchronized (this) {
                            if (closed) {
                                if (name.equals("isClosed")) return true;
                                throw new SQLException("La sentencia de sesión está cerrada");
                            }
                        }
                        if (!control) {
                            if (name.startsWith("execute")) prepareExecution(delegate);
                            else beforeIo();
                        }
                        Object result = invoke(delegate, method, args);
                        if (!control) check(owner);
                        if (result instanceof ResultSet resultSet) return rows(resultSet, delegate).proxy();
                        return result;
                    } catch (SQLException | RuntimeException failure) {
                        if (control) owner.recordCleanupFailure(failure);
                        throw failure;
                    }
                });
                return exposed;
            }

            private synchronized void close() throws SQLException {
                if (closed) return;
                closed = true;
                try { delegate.close(); }
                catch (SQLException | RuntimeException failure) { owner.recordCleanupFailure(failure); throw failure; }
            }
        }

        private final class RowsHandle {
            private final ResultSet delegate;
            private final Statement statement;
            private ResultSet exposed;
            private boolean closed;
            private RowsHandle(ResultSet delegate, Statement statement) { this.delegate = delegate; this.statement = statement; }

            private synchronized ResultSet proxy() {
                if (exposed != null) return exposed;
                exposed = (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[]{ResultSet.class},
                        (proxy, method, args) -> {
                            if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
                            String name = method.getName();
                            if (name.equals("getStatement")) return statement == null ? null : statement(statement).proxy();
                            if (name.equals("unwrap")) throw unsupported();
                            if (name.equals("isWrapperFor")) return false;
                            if (name.equals("close")) { close(); return null; }
                            synchronized (this) {
                                if (closed) {
                                    if (name.equals("isClosed")) return true;
                                    throw new SQLException("El cursor de sesión está cerrado");
                                }
                            }
                            beforeIo();
                            if (name.equals("next")) active = statement;
                            Object result = invoke(delegate, method, args);
                            check(owner);
                            return result;
                        });
                return exposed;
            }

            private synchronized void close() throws SQLException {
                if (closed) return;
                closed = true;
                try { delegate.close(); }
                catch (SQLException | RuntimeException failure) { owner.recordCleanupFailure(failure); throw failure; }
            }
        }
    }

    private static boolean connectionControl(String name, Object[] args) {
        return switch (name) {
            case "rollback", "isClosed", "getWarnings", "clearWarnings" -> true;
            case "setAutoCommit" -> Boolean.TRUE.equals(args[0]);
            case "setReadOnly" -> Boolean.FALSE.equals(args[0]);
            case "setTransactionIsolation" -> args[0] instanceof Integer value
                    && (value == Connection.TRANSACTION_NONE || value == Connection.TRANSACTION_READ_UNCOMMITTED
                    || value == Connection.TRANSACTION_READ_COMMITTED || value == Connection.TRANSACTION_REPEATABLE_READ
                    || value == Connection.TRANSACTION_SERIALIZABLE);
            default -> false;
        };
    }

    private static boolean statementControl(String name, Object[] args) {
        return switch (name) {
            case "getMaxRows", "getQueryTimeout", "isClosed" -> true;
            case "setMaxRows", "setQueryTimeout" -> Integer.valueOf(0).equals(args[0]);
            default -> false;
        };
    }

    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "LegalRegistrationSessionJdbc[redacted]";
            default -> throw new IllegalStateException("Método Object JDBC inesperado");
        };
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
}
