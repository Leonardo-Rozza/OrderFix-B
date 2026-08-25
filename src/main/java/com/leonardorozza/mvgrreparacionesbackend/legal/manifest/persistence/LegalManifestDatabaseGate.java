package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
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
            setLocalTimeout("statement_timeout", budgets.statementTimeoutSeconds());
            preflights.forEach(LegalDatabasePreflight::verify);
            setLocalTimeout("lock_timeout", budgets.editorialLockTimeoutSeconds());
            jdbc.queryForList("""
                    SELECT pg_catalog.pg_advisory_xact_lock(
                        pg_catalog.hashtextextended(?, 0)
                    )
                    """, EDITORIAL_LOCK_NAME);
            setLocalTimeout("lock_timeout", budgets.graphLockTimeoutSeconds());
            return protectedCallback.doInTransaction(status);
        });
    }

    private void setLocalTimeout(String setting, int seconds) {
        if (!"statement_timeout".equals(setting) && !"lock_timeout".equals(setting)) {
            throw new IllegalArgumentException("Timeout PostgreSQL no permitido");
        }
        jdbc.execute("SET LOCAL " + setting + " TO '" + seconds + "s'");
    }
}
