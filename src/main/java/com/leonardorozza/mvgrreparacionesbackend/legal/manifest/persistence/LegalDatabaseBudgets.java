package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

/** Fixed production time budgets for the shared legal database protocol. */
record LegalDatabaseBudgets(
        int transactionTimeoutSeconds,
        int statementTimeoutSeconds,
        int editorialLockTimeoutSeconds,
        int graphLockTimeoutSeconds
) {

    static final int PRODUCTION_TRANSACTION_TIMEOUT_SECONDS = 75;
    static final int PRODUCTION_STATEMENT_TIMEOUT_SECONDS = 30;
    static final int PRODUCTION_EDITORIAL_LOCK_TIMEOUT_SECONDS = 30;
    static final int PRODUCTION_GRAPH_LOCK_TIMEOUT_SECONDS = 5;

    LegalDatabaseBudgets {
        requireTestBounded(
                transactionTimeoutSeconds,
                PRODUCTION_TRANSACTION_TIMEOUT_SECONDS,
                "transactionTimeoutSeconds");
        requireTestBounded(
                statementTimeoutSeconds,
                PRODUCTION_STATEMENT_TIMEOUT_SECONDS,
                "statementTimeoutSeconds");
        requireTestBounded(
                editorialLockTimeoutSeconds,
                PRODUCTION_EDITORIAL_LOCK_TIMEOUT_SECONDS,
                "editorialLockTimeoutSeconds");
        requireTestBounded(
                graphLockTimeoutSeconds,
                PRODUCTION_GRAPH_LOCK_TIMEOUT_SECONDS,
                "graphLockTimeoutSeconds");
    }

    static LegalDatabaseBudgets production() {
        return new LegalDatabaseBudgets(
                PRODUCTION_TRANSACTION_TIMEOUT_SECONDS,
                PRODUCTION_STATEMENT_TIMEOUT_SECONDS,
                PRODUCTION_EDITORIAL_LOCK_TIMEOUT_SECONDS,
                PRODUCTION_GRAPH_LOCK_TIMEOUT_SECONDS);
    }

    private static void requireTestBounded(int candidate, int productionMaximum, String name) {
        if (candidate < 1 || candidate > productionMaximum) {
            throw new IllegalArgumentException(
                    name + " debe estar entre 1 y el presupuesto productivo");
        }
    }
}
