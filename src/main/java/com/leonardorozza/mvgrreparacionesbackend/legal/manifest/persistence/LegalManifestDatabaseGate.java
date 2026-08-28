package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;

/** Coordinates the shared transaction, preflights and editorial lock around legal graph access. */
final class LegalManifestDatabaseGate {

    static final String EDITORIAL_LOCK_NAME = "ordenfix:legal-publicaciones:sello:v1";

    private final TransactionTemplate transactionTemplate;
    private final JdbcTemplate jdbc;
    private final LegalDatabaseBudgets budgets;
    private final List<LegalDatabasePreflight> preflights;

    LegalManifestDatabaseGate(
            TransactionTemplate transactionTemplate,
            JdbcTemplate jdbc,
            LegalDatabaseBudgets budgets,
            List<LegalDatabasePreflight> preflights) {
        this.transactionTemplate = Objects.requireNonNull(
                transactionTemplate,
                "transactionTemplate");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.budgets = Objects.requireNonNull(budgets, "budgets");
        Objects.requireNonNull(preflights, "preflights");
        preflights.forEach(preflight -> Objects.requireNonNull(preflight, "preflight"));
        this.preflights = List.copyOf(preflights);
    }

    <T> T execute(TransactionCallback<T> protectedCallback) {
        Objects.requireNonNull(protectedCallback, "protectedCallback");
        return transactionTemplate.execute(status -> {
            enterProtectedGraph();
            return protectedCallback.doInTransaction(status);
        });
    }

    /**
     * Executes one commit-sensitive editorial mutation in an accredited writable transaction.
     *
     * <p>The boundary is checked before opening PostgreSQL. Once inside, the effective isolation
     * and read-only mode are accredited before preflights, advisory lock or graph access. This
     * keeps apply operations separate from the legacy import entry point while sharing the exact
     * timeout and cooperative-lock protocol.</p>
     */
    <T> T executeMutable(TransactionCallback<T> protectedCallback) {
        Objects.requireNonNull(protectedCallback, "protectedCallback");
        requireCommitOutcomeSafe();
        return transactionTemplate.execute(status -> {
            setLocalTimeout("statement_timeout", budgets.statementTimeoutSeconds());
            requireEffectiveMutableTransaction();
            enterProtectedGraphAfterStatementBudget();
            return protectedCallback.doInTransaction(status);
        });
    }

    /**
     * Executes a non-mutating editorial observation in an accredited read-only transaction.
     *
     * <p>The template declaration and the effective PostgreSQL transaction mode are both checked
     * before any preflight or graph read. The protected callback then uses the same preflights,
     * budgets and cooperative advisory lock as import and dry-run.</p>
     */
    <T> T executeReadOnly(TransactionCallback<T> protectedCallback) {
        Objects.requireNonNull(protectedCallback, "protectedCallback");
        requireReadOnlyBoundary();
        return transactionTemplate.execute(status -> {
            setLocalTimeout("statement_timeout", budgets.statementTimeoutSeconds());
            requireEffectiveReadOnlyTransaction();
            enterProtectedGraphAfterStatementBudget();
            return protectedCallback.doInTransaction(status);
        });
    }

    /**
     * Fails closed unless commit SQLExceptions remain transaction failures with an UNKNOWN
     * completion. {@code JdbcTransactionManager} translates those exceptions to
     * {@code DataAccessException}; Spring can then attempt a rollback and report a false
     * ROLLED_BACK outcome after the server already committed.
     */
    void requireCommitOutcomeSafe() {
        Object transactionManager = transactionTemplate.getTransactionManager();
        if (transactionManager == null
                || transactionManager.getClass() != DataSourceTransactionManager.class
                || ((DataSourceTransactionManager) transactionManager)
                        .isRollbackOnCommitFailure()
                || ((DataSourceTransactionManager) transactionManager).getDataSource()
                        != jdbc.getDataSource()
                || transactionTemplate.getPropagationBehavior()
                        != TransactionDefinition.PROPAGATION_REQUIRES_NEW
                || transactionTemplate.getIsolationLevel()
                        != TransactionDefinition.ISOLATION_READ_COMMITTED
                || transactionTemplate.getTimeout() != budgets.transactionTimeoutSeconds()
                || transactionTemplate.isReadOnly()) {
            throw new IllegalArgumentException(
                    "El importador legal requiere una frontera de commit acreditable");
        }
    }

    boolean usesJdbc(JdbcTemplate candidate) {
        return jdbc == candidate;
    }

    void requireExactImportPreflights(
            JdbcTemplate candidate,
            LegalDatabasePreflight schema,
            LegalDatabasePreflight privileges) {
        if (jdbc != candidate
                || preflights.size() != 2
                || preflights.get(0) != schema
                || preflights.get(1) != privileges
                || !schema.usesJdbc(candidate)
                || !privileges.usesJdbc(candidate)) {
            throw new IllegalArgumentException(
                    "El importador legal requiere preflights acreditados y ordenados");
        }
    }

    void requireExactReadinessPreflights(
            JdbcTemplate candidate,
            LegalDatabasePreflight schema) {
        if (jdbc != candidate
                || preflights.size() != 1
                || preflights.getFirst() != schema
                || !schema.usesJdbc(candidate)) {
            throw new IllegalArgumentException(
                    "El readiness legal requiere el preflight de schema acreditado");
        }
    }

    private void requireReadOnlyBoundary() {
        Object transactionManager = transactionTemplate.getTransactionManager();
        if (transactionManager == null
                || transactionManager.getClass() != DataSourceTransactionManager.class
                || ((DataSourceTransactionManager) transactionManager).getDataSource()
                        != jdbc.getDataSource()
                || transactionTemplate.getPropagationBehavior()
                        != TransactionDefinition.PROPAGATION_REQUIRES_NEW
                || transactionTemplate.getIsolationLevel()
                        != TransactionDefinition.ISOLATION_READ_COMMITTED
                || transactionTemplate.getTimeout() != budgets.transactionTimeoutSeconds()
                || !transactionTemplate.isReadOnly()) {
            throw new IllegalArgumentException(
                    "El readiness legal requiere una frontera read-only acreditable");
        }
    }

    private void requireEffectiveReadOnlyTransaction() {
        String isolation = jdbc.queryForObject(
                "SELECT pg_catalog.current_setting('transaction_isolation')",
                String.class);
        String readOnly = jdbc.queryForObject(
                "SELECT pg_catalog.current_setting('transaction_read_only')",
                String.class);
        if (!"read committed".equals(isolation) || !"on".equals(readOnly)) {
            throw new IllegalArgumentException(
                    "PostgreSQL no confirmó la frontera read-only del readiness legal");
        }
    }

    private void requireEffectiveMutableTransaction() {
        String isolation = jdbc.queryForObject(
                "SELECT pg_catalog.current_setting('transaction_isolation')",
                String.class);
        String readOnly = jdbc.queryForObject(
                "SELECT pg_catalog.current_setting('transaction_read_only')",
                String.class);
        if (!"read committed".equals(isolation) || !"off".equals(readOnly)) {
            throw new IllegalArgumentException(
                    "PostgreSQL no confirmó la frontera mutable editorial");
        }
    }

    private void enterProtectedGraph() {
        setLocalTimeout("statement_timeout", budgets.statementTimeoutSeconds());
        enterProtectedGraphAfterStatementBudget();
    }

    private void enterProtectedGraphAfterStatementBudget() {
        preflights.forEach(LegalDatabasePreflight::verify);
        setLocalTimeout("lock_timeout", budgets.editorialLockTimeoutSeconds());
        jdbc.queryForList("""
                SELECT pg_catalog.pg_advisory_xact_lock(
                    pg_catalog.hashtextextended(?, 0)
                )
                """, EDITORIAL_LOCK_NAME);
        setLocalTimeout("lock_timeout", budgets.graphLockTimeoutSeconds());
    }

    private void setLocalTimeout(String setting, int seconds) {
        if (!"statement_timeout".equals(setting) && !"lock_timeout".equals(setting)) {
            throw new IllegalArgumentException("Timeout PostgreSQL no permitido");
        }
        jdbc.execute("SET LOCAL " + setting + " TO '" + seconds + "s'");
    }
}
