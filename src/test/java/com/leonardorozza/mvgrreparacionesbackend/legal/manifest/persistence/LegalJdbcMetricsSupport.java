package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Test-only JDBC instrumentation used by the legal capacity gates.
 *
 * <p>The proxy measures logical driver executions, not individual rows in a batch. It deliberately
 * keeps transaction completion separate from statements so the configured latency models a JDBC
 * statement round trip without changing commit or rollback behaviour. A statement is counted only
 * after the artificial delay reaches the driver, and commit/rollback counters represent successful
 * full-transaction completions.</p>
 */
final class LegalJdbcMetricsSupport {

    private static final String UNKNOWN_SQL = "<unknown>";
    private static final String DYNAMIC_STATEMENT_SQL = "<statement>";

    private final Metrics metrics;
    private final DataSource dataSource;

    private LegalJdbcMetricsSupport(
            DataSource delegate,
            Duration delay,
            DelayStrategy delayStrategy) {
        Objects.requireNonNull(delegate, "delegate");
        Objects.requireNonNull(delay, "delay");
        if (delay.isNegative()) {
            throw new IllegalArgumentException("delay must not be negative");
        }
        metrics = new Metrics(delay, Objects.requireNonNull(delayStrategy, "delayStrategy"));
        dataSource = instrumentDataSource(delegate, metrics);
    }

    static LegalJdbcMetricsSupport instrument(DataSource delegate, Duration delay) {
        return new LegalJdbcMetricsSupport(delegate, delay, LegalJdbcMetricsSupport::sleep);
    }

    static LegalJdbcMetricsSupport instrument(
            DataSource delegate,
            Duration delay,
            DelayStrategy delayStrategy) {
        return new LegalJdbcMetricsSupport(delegate, delay, delayStrategy);
    }

    DataSource dataSource() {
        return dataSource;
    }

    void reset() {
        metrics.reset();
    }

    Snapshot snapshot() {
        return metrics.snapshot();
    }

    static String normalizeSql(String sql) {
        if (sql == null) {
            return UNKNOWN_SQL;
        }
        String normalized = sql.replaceAll("\\s+", " ").trim();
        return normalized.isEmpty() ? UNKNOWN_SQL : normalized;
    }

    static Category categoryOf(String sql) {
        String normalized = normalizeSql(sql).toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("pg_advisory_xact_lock_shared(")
                || normalized.contains("pg_advisory_xact_lock(")
                || normalized.contains("pg_try_advisory_xact_lock(")
                || normalized.contains("pg_advisory_lock(")
                || normalized.contains("pg_try_advisory_lock(")
                || normalized.contains("pg_advisory_unlock(")) {
            return Category.ADVISORY_LOCK;
        }
        if (normalized.contains(" for update")
                || normalized.contains(" for no key update")
                || normalized.contains(" for share")
                || normalized.contains(" for key share")) {
            return Category.ROW_LOCK;
        }
        if (normalized.equals("<commit>")
                || normalized.equals("<rollback>")
                || startsWithAny(normalized,
                        "begin", "commit", "rollback", "start transaction",
                        "set ", "savepoint", "release savepoint")) {
            return Category.TX_CONTROL;
        }
        if (startsWithAny(normalized,
                "insert ", "update ", "delete ", "merge ", "truncate ")) {
            return Category.DML;
        }
        if (startsWithAny(normalized, "select ", "with ", "values ", "show ")) {
            return Category.SELECT;
        }
        return Category.OTHER;
    }

    private static boolean startsWithAny(String value, String... prefixes) {
        for (String prefix : prefixes) {
            if (value.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static DataSource instrumentDataSource(DataSource delegate, Metrics metrics) {
        return (DataSource) Proxy.newProxyInstance(
                LegalJdbcMetricsSupport.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, arguments) -> {
                    Object objectMethod = objectMethod(proxy, method, arguments, "DataSource");
                    if (objectMethod != NotAnObjectMethod.INSTANCE) {
                        return objectMethod;
                    }
                    if (isUnwrap(method)) {
                        return unwrapProxy(proxy, arguments);
                    }
                    if (isWrapperFor(method)) {
                        return isProxyWrapper(proxy, arguments);
                    }
                    if ("getConnection".equals(method.getName())) {
                        Connection connection = (Connection) invoke(delegate, method, arguments);
                        return instrumentConnection(connection, metrics);
                    }
                    return invoke(delegate, method, arguments);
                });
    }

    private static Connection instrumentConnection(Connection delegate, Metrics metrics) {
        Objects.requireNonNull(delegate, "connection");
        return (Connection) Proxy.newProxyInstance(
                LegalJdbcMetricsSupport.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, arguments) -> {
                    Object objectMethod = objectMethod(proxy, method, arguments, "Connection");
                    if (objectMethod != NotAnObjectMethod.INSTANCE) {
                        return objectMethod;
                    }
                    if (isUnwrap(method)) {
                        return unwrapProxy(proxy, arguments);
                    }
                    if (isWrapperFor(method)) {
                        return isProxyWrapper(proxy, arguments);
                    }
                    if (isStatementFactory(method)) {
                        Statement statement = (Statement) invoke(delegate, method, arguments);
                        return instrumentStatement(
                                statement,
                                sqlFromFactory(arguments),
                                metrics,
                                (Connection) proxy);
                    }
                    if ("commit".equals(method.getName())) {
                        return metrics.completeTransaction(
                                "commit",
                                () -> invoke(delegate, method, arguments));
                    }
                    if ("rollback".equals(method.getName()) && method.getParameterCount() == 0) {
                        return metrics.completeTransaction(
                                "rollback",
                                () -> invoke(delegate, method, arguments));
                    }
                    return invoke(delegate, method, arguments);
                });
    }

    private static Statement instrumentStatement(
            Statement delegate,
            String preparedSql,
            Metrics metrics,
            Connection connectionProxy) {
        Objects.requireNonNull(delegate, "statement");
        Class<?> statementType = delegate instanceof CallableStatement
                ? CallableStatement.class
                : delegate instanceof PreparedStatement
                        ? PreparedStatement.class
                        : Statement.class;
        StatementInvocation handler = new StatementInvocation(
                delegate,
                preparedSql,
                metrics,
                connectionProxy);
        return (Statement) Proxy.newProxyInstance(
                LegalJdbcMetricsSupport.class.getClassLoader(),
                new Class<?>[]{statementType},
                handler::invoke);
    }

    private static ResultSet instrumentResultSet(
            ResultSet delegate,
            String sql,
            Metrics metrics,
            Statement statementProxy) {
        Objects.requireNonNull(delegate, "resultSet");
        AtomicLong resultSetRows = new AtomicLong();
        return (ResultSet) Proxy.newProxyInstance(
                LegalJdbcMetricsSupport.class.getClassLoader(),
                new Class<?>[]{ResultSet.class},
                (proxy, method, arguments) -> {
                    Object objectMethod = objectMethod(proxy, method, arguments, "ResultSet");
                    if (objectMethod != NotAnObjectMethod.INSTANCE) {
                        return objectMethod;
                    }
                    if (isUnwrap(method)) {
                        return unwrapProxy(proxy, arguments);
                    }
                    if (isWrapperFor(method)) {
                        return isProxyWrapper(proxy, arguments);
                    }
                    if ("getStatement".equals(method.getName())) {
                        return statementProxy;
                    }
                    Object result = invoke(delegate, method, arguments);
                    if ("next".equals(method.getName()) && Boolean.TRUE.equals(result)) {
                        metrics.rowRead(sql, resultSetRows.incrementAndGet());
                    }
                    return result;
                });
    }

    private static String sqlFromFactory(Object[] arguments) {
        if (arguments != null
                && arguments.length > 0
                && arguments[0] instanceof String sql) {
            return sql;
        }
        return DYNAMIC_STATEMENT_SQL;
    }

    private static boolean isStatementFactory(Method method) {
        return switch (method.getName()) {
            case "createStatement", "prepareStatement", "prepareCall" -> true;
            default -> false;
        };
    }

    private static boolean isExecution(Method method) {
        return switch (method.getName()) {
            case "execute", "executeQuery", "executeUpdate", "executeLargeUpdate",
                    "executeBatch", "executeLargeBatch" -> true;
            default -> false;
        };
    }

    private static boolean isUnwrap(Method method) {
        return "unwrap".equals(method.getName())
                && method.getParameterCount() == 1
                && method.getParameterTypes()[0] == Class.class;
    }

    private static boolean isWrapperFor(Method method) {
        return "isWrapperFor".equals(method.getName())
                && method.getParameterCount() == 1
                && method.getParameterTypes()[0] == Class.class;
    }

    private static Object unwrapProxy(Object proxy, Object[] arguments) throws SQLException {
        Class<?> requested = requestedInterface(arguments);
        if (requested.isInstance(proxy)) {
            return requested.cast(proxy);
        }
        throw new SQLException("Unwrap no instrumentado: " + requested.getName());
    }

    private static boolean isProxyWrapper(Object proxy, Object[] arguments) throws SQLException {
        return requestedInterface(arguments).isInstance(proxy);
    }

    private static Class<?> requestedInterface(Object[] arguments) throws SQLException {
        if (arguments == null
                || arguments.length != 1
                || !(arguments[0] instanceof Class<?> requested)) {
            throw new SQLException("Interfaz JDBC inválida");
        }
        return requested;
    }

    private static Object objectMethod(
            Object proxy,
            Method method,
            Object[] arguments,
            String label) {
        if (method.getDeclaringClass() != Object.class) {
            return NotAnObjectMethod.INSTANCE;
        }
        return switch (method.getName()) {
            case "equals" -> proxy == arguments[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "InstrumentedLegalJdbc" + label;
            default -> throw new IllegalStateException("Método Object inesperado: " + method);
        };
    }

    private static Object invoke(Object target, Method method, Object[] arguments) throws Throwable {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    private static void sleep(Duration delay) throws SQLException {
        if (delay.isZero()) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new SQLException("La medición de latencia JDBC fue interrumpida", interrupted);
        }
    }

    enum Category {
        ADVISORY_LOCK,
        DML,
        ROW_LOCK,
        TX_CONTROL,
        SELECT,
        OTHER
    }

    record SqlSnapshot(
            String sql,
            Category category,
            long executions,
            long failures,
            long rowsRead,
            long maximumRowsRead,
            long executionsWithTerminalNumericBinding,
            Set<Long> terminalNumericBindings,
            Duration totalDuration,
            Duration maximumDuration
    ) {
        SqlSnapshot {
            Objects.requireNonNull(sql, "sql");
            Objects.requireNonNull(category, "category");
            terminalNumericBindings = Set.copyOf(Objects.requireNonNull(
                    terminalNumericBindings,
                    "terminalNumericBindings"));
            Objects.requireNonNull(totalDuration, "totalDuration");
            Objects.requireNonNull(maximumDuration, "maximumDuration");
        }
    }

    record Snapshot(
            long roundTrips,
            long statementExecutions,
            long commits,
            long rollbacks,
            long rowsRead,
            Duration maximumStatementDuration,
            Duration maximumAdvisoryLockDuration,
            Map<Category, Long> byCategory,
            Map<String, SqlSnapshot> bySql
    ) {
        Snapshot {
            Objects.requireNonNull(maximumStatementDuration, "maximumStatementDuration");
            Objects.requireNonNull(maximumAdvisoryLockDuration, "maximumAdvisoryLockDuration");
            EnumMap<Category, Long> categoryCopy = new EnumMap<>(Category.class);
            categoryCopy.putAll(Objects.requireNonNull(byCategory, "byCategory"));
            byCategory = Collections.unmodifiableMap(categoryCopy);
            bySql = Collections.unmodifiableMap(new LinkedHashMap<>(
                    Objects.requireNonNull(bySql, "bySql")));
        }

        long executions(Category category) {
            return byCategory.getOrDefault(Objects.requireNonNull(category, "category"), 0L);
        }

        long executionsContaining(String... fragments) {
            return matching(fragments).values().stream()
                    .mapToLong(SqlSnapshot::executions)
                    .sum();
        }

        long rowsReadContaining(String... fragments) {
            return matching(fragments).values().stream()
                    .mapToLong(SqlSnapshot::rowsRead)
                    .sum();
        }

        long maximumRowsReadContaining(String... fragments) {
            return matching(fragments).values().stream()
                    .mapToLong(SqlSnapshot::maximumRowsRead)
                    .max()
                    .orElse(0L);
        }

        Map<String, SqlSnapshot> matching(String... fragments) {
            Objects.requireNonNull(fragments, "fragments");
            return bySql.entrySet().stream()
                    .filter(entry -> matchesAll(entry.getKey(), fragments))
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(
                            Map.Entry::getKey,
                            Map.Entry::getValue));
        }

        private static boolean matchesAll(String sql, String[] fragments) {
            for (String fragment : fragments) {
                if (!sql.contains(Objects.requireNonNull(fragment, "fragment"))) {
                    return false;
                }
            }
            return true;
        }
    }

    @FunctionalInterface
    interface DelayStrategy {
        void apply(Duration delay) throws SQLException;
    }

    @FunctionalInterface
    private interface JdbcInvocation {
        Object invoke() throws Throwable;
    }

    private enum NotAnObjectMethod {
        INSTANCE
    }

    private static final class StatementInvocation {

        private final Statement delegate;
        private final String preparedSql;
        private final Metrics metrics;
        private final Connection connectionProxy;
        private final List<String> batchSql = new CopyOnWriteArrayList<>();
        private final Map<Integer, Object> parameterBindings = new LinkedHashMap<>();
        private volatile String currentSql;

        private StatementInvocation(
                Statement delegate,
                String preparedSql,
                Metrics metrics,
                Connection connectionProxy) {
            this.delegate = delegate;
            this.preparedSql = preparedSql;
            this.metrics = metrics;
            this.connectionProxy = connectionProxy;
            currentSql = preparedSql;
        }

        private Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
            Object objectMethod = objectMethod(proxy, method, arguments, "Statement");
            if (objectMethod != NotAnObjectMethod.INSTANCE) {
                return objectMethod;
            }
            if (isUnwrap(method)) {
                return unwrapProxy(proxy, arguments);
            }
            if (isWrapperFor(method)) {
                return isProxyWrapper(proxy, arguments);
            }
            if ("getConnection".equals(method.getName())) {
                return connectionProxy;
            }
            String sql = sqlForInvocation(method, arguments);
            if (isExecution(method)) {
                currentSql = sql;
                boolean dynamicBatch = isDynamicBatchExecution(method);
                try {
                    Object result = metrics.executeStatement(
                            sql,
                            terminalNumericBinding(),
                            () -> LegalJdbcMetricsSupport.invoke(delegate, method, arguments));
                    return result instanceof ResultSet rows
                            ? instrumentResultSet(rows, sql, metrics, (Statement) proxy)
                            : result;
                } finally {
                    if (dynamicBatch) {
                        batchSql.clear();
                    }
                }
            }
            Object result = LegalJdbcMetricsSupport.invoke(delegate, method, arguments);
            if (isParameterBinding(method, arguments)) {
                int index = (Integer) arguments[0];
                Object value = "setNull".equals(method.getName()) ? null : arguments[1];
                parameterBindings.put(index, value);
            } else if ("clearParameters".equals(method.getName())) {
                parameterBindings.clear();
            } else if ("addBatch".equals(method.getName())
                    && arguments != null
                    && arguments.length == 1
                    && arguments[0] instanceof String addedSql) {
                batchSql.add(addedSql);
            } else if ("clearBatch".equals(method.getName())) {
                batchSql.clear();
            }
            if (result instanceof ResultSet rows) {
                return instrumentResultSet(rows, currentSql, metrics, (Statement) proxy);
            }
            return result;
        }

        private static boolean isParameterBinding(Method method, Object[] arguments) {
            return method.getName().startsWith("set")
                    && arguments != null
                    && arguments.length >= 2
                    && arguments[0] instanceof Integer;
        }

        private Long terminalNumericBinding() {
            return parameterBindings.entrySet().stream()
                    .max(Map.Entry.comparingByKey())
                    .map(Map.Entry::getValue)
                    .map(StatementInvocation::exactLong)
                    .orElse(null);
        }

        private static Long exactLong(Object value) {
            try {
                if (value instanceof Byte number) {
                    return number.longValue();
                }
                if (value instanceof Short number) {
                    return number.longValue();
                }
                if (value instanceof Integer number) {
                    return number.longValue();
                }
                if (value instanceof Long number) {
                    return number;
                }
                if (value instanceof BigInteger number) {
                    return number.longValueExact();
                }
                if (value instanceof BigDecimal number) {
                    return number.longValueExact();
                }
                return null;
            } catch (ArithmeticException notAnExactLong) {
                return null;
            }
        }

        private String sqlForInvocation(Method method, Object[] arguments) {
            if (arguments != null
                    && arguments.length > 0
                    && arguments[0] instanceof String sql) {
                return sql;
            }
            if (("executeBatch".equals(method.getName())
                    || "executeLargeBatch".equals(method.getName()))
                    && DYNAMIC_STATEMENT_SQL.equals(preparedSql)
                    && !batchSql.isEmpty()) {
                return String.join("; ", batchSql);
            }
            return preparedSql;
        }

        private boolean isDynamicBatchExecution(Method method) {
            return ("executeBatch".equals(method.getName())
                    || "executeLargeBatch".equals(method.getName()))
                    && DYNAMIC_STATEMENT_SQL.equals(preparedSql);
        }
    }

    private static final class Metrics {

        private final Duration delay;
        private final DelayStrategy delayStrategy;
        private final AtomicLong statementExecutions = new AtomicLong();
        private final AtomicLong commits = new AtomicLong();
        private final AtomicLong rollbacks = new AtomicLong();
        private final AtomicLong rowsRead = new AtomicLong();
        private final AtomicLong maximumStatementNanos = new AtomicLong();
        private final AtomicLong maximumAdvisoryNanos = new AtomicLong();
        private final EnumMap<Category, AtomicLong> byCategory = new EnumMap<>(Category.class);
        private final Map<String, MutableSqlMetrics> bySql = new ConcurrentHashMap<>();

        private Metrics(Duration delay, DelayStrategy delayStrategy) {
            this.delay = delay;
            this.delayStrategy = delayStrategy;
            for (Category category : Category.values()) {
                byCategory.put(category, new AtomicLong());
            }
        }

        private Object executeStatement(
                String sql,
                Long terminalNumericBinding,
                JdbcInvocation invocation) throws Throwable {
            String normalized = normalizeSql(sql);
            Category category = categoryOf(normalized);
            long started = System.nanoTime();
            delayStrategy.apply(delay);
            boolean failed = false;
            try {
                return invocation.invoke();
            } catch (Throwable failure) {
                failed = true;
                throw failure;
            } finally {
                long duration = elapsedSince(started);
                statementExecutions.incrementAndGet();
                byCategory.get(category).incrementAndGet();
                maximumStatementNanos.accumulateAndGet(duration, Math::max);
                if (category == Category.ADVISORY_LOCK) {
                    maximumAdvisoryNanos.accumulateAndGet(duration, Math::max);
                }
                sqlMetrics(normalized, category).execution(
                        duration,
                        failed,
                        terminalNumericBinding);
            }
        }

        private Object completeTransaction(String operation, JdbcInvocation invocation)
                throws Throwable {
            Object result = invocation.invoke();
            if ("commit".equals(operation)) {
                commits.incrementAndGet();
            } else {
                rollbacks.incrementAndGet();
            }
            return result;
        }

        private void rowRead(String sql, long resultSetRows) {
            String normalized = normalizeSql(sql);
            rowsRead.incrementAndGet();
            sqlMetrics(normalized, categoryOf(normalized)).rowRead(resultSetRows);
        }

        private MutableSqlMetrics sqlMetrics(String normalized, Category category) {
            return bySql.computeIfAbsent(
                    normalized,
                    ignored -> new MutableSqlMetrics(normalized, category));
        }

        private void reset() {
            statementExecutions.set(0L);
            commits.set(0L);
            rollbacks.set(0L);
            rowsRead.set(0L);
            maximumStatementNanos.set(0L);
            maximumAdvisoryNanos.set(0L);
            byCategory.values().forEach(value -> value.set(0L));
            bySql.clear();
        }

        private Snapshot snapshot() {
            EnumMap<Category, Long> categorySnapshot = new EnumMap<>(Category.class);
            byCategory.forEach((category, count) -> categorySnapshot.put(category, count.get()));
            Map<String, SqlSnapshot> sqlSnapshot = new TreeMap<>();
            bySql.forEach((sql, value) -> sqlSnapshot.put(sql, value.snapshot()));
            long statementCount = statementExecutions.get();
            long commitCount = commits.get();
            long rollbackCount = rollbacks.get();
            return new Snapshot(
                    statementCount,
                    statementCount,
                    commitCount,
                    rollbackCount,
                    rowsRead.get(),
                    Duration.ofNanos(maximumStatementNanos.get()),
                    Duration.ofNanos(maximumAdvisoryNanos.get()),
                    categorySnapshot,
                    sqlSnapshot);
        }

        private static long elapsedSince(long started) {
            return Math.max(0L, System.nanoTime() - started);
        }
    }

    private static final class MutableSqlMetrics {

        private final String sql;
        private final Category category;
        private final AtomicLong executions = new AtomicLong();
        private final AtomicLong failures = new AtomicLong();
        private final AtomicLong rowsRead = new AtomicLong();
        private final AtomicLong maximumRowsRead = new AtomicLong();
        private final AtomicLong executionsWithTerminalNumericBinding = new AtomicLong();
        private final AtomicLong totalNanos = new AtomicLong();
        private final AtomicLong maximumNanos = new AtomicLong();
        private final Set<Long> terminalNumericBindings = ConcurrentHashMap.newKeySet();

        private MutableSqlMetrics(String sql, Category category) {
            this.sql = sql;
            this.category = category;
        }

        private void execution(long duration, boolean failed, Long terminalNumericBinding) {
            executions.incrementAndGet();
            if (failed) {
                failures.incrementAndGet();
            }
            totalNanos.addAndGet(duration);
            maximumNanos.accumulateAndGet(duration, Math::max);
            if (terminalNumericBinding != null) {
                executionsWithTerminalNumericBinding.incrementAndGet();
                terminalNumericBindings.add(terminalNumericBinding);
            }
        }

        private void rowRead(long resultSetRows) {
            rowsRead.incrementAndGet();
            maximumRowsRead.accumulateAndGet(resultSetRows, Math::max);
        }

        private SqlSnapshot snapshot() {
            return new SqlSnapshot(
                    sql,
                    category,
                    executions.get(),
                    failures.get(),
                    rowsRead.get(),
                    maximumRowsRead.get(),
                    executionsWithTerminalNumericBinding.get(),
                    terminalNumericBindings,
                    Duration.ofNanos(totalNanos.get()),
                    Duration.ofNanos(maximumNanos.get()));
        }
    }
}
