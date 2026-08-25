package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.TransactionTimedOutException;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

/** Maps JDBC/transaction failures to safe, stable manifest issues without reading messages. */
final class LegalDatabaseFailureMapper {

    private static final String DATABASE_LOCATION = "database";
    private static final String SCHEMA_LOCATION = "database/schema";

    LegalManifestIssue map(Throwable failure) {
        Objects.requireNonNull(failure, "failure");

        FailureEvidence evidence = inspect(failure);
        LegalManifestIssueCode operationalCode = evidence.operationalCode();
        if (operationalCode != null) {
            return issue(
                    operationalCode,
                    operationalCode == LegalManifestIssueCode.DB_SCHEMA_INCOMPATIBLE
                            ? SCHEMA_LOCATION
                            : DATABASE_LOCATION);
        }
        if (evidence.operationalIssue() != null) {
            return evidence.operationalIssue();
        }
        if (evidence.blockedIssue() != null) {
            return evidence.blockedIssue();
        }
        if (evidence.constraint()) {
            return issue(LegalManifestIssueCode.DB_CONSTRAINT, DATABASE_LOCATION);
        }
        return issue(LegalManifestIssueCode.DB_OPERATION_FAILED, DATABASE_LOCATION);
    }

    private static FailureEvidence inspect(Throwable root) {
        FailureEvidence evidence = new FailureEvidence();
        Deque<Throwable> pending = new ArrayDeque<>();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        pending.add(root);

        while (!pending.isEmpty()) {
            Throwable current = pending.removeFirst();
            if (current == null || !visited.add(current)) {
                continue;
            }

            if (current instanceof LegalDryRunOperationalException operational
                    && evidence.operationalIssue() == null) {
                evidence.setOperationalIssue(operational.issue());
            } else if (current instanceof LegalDryRunBlockedException blocked
                    && evidence.blockedIssue() == null) {
                evidence.setBlockedIssue(blocked.issue());
            }

            if (current instanceof SQLException sqlException) {
                evidence.acceptSqlState(sqlException.getSQLState());
                add(pending, sqlException.getNextException());
            }
            if (current instanceof CannotGetJdbcConnectionException
                    || current instanceof CannotCreateTransactionException) {
                evidence.acceptOperational(LegalManifestIssueCode.DB_CONNECTION);
            }
            if (current instanceof QueryTimeoutException
                    || current instanceof TransactionTimedOutException) {
                evidence.acceptOperational(LegalManifestIssueCode.DB_STATEMENT_TIMEOUT);
            }
            if (current instanceof TransactionSystemException transactionFailure) {
                add(pending, transactionFailure.getApplicationException());
            }

            add(pending, current.getCause());
            for (Throwable suppressed : current.getSuppressed()) {
                add(pending, suppressed);
            }
        }
        return evidence;
    }

    private static void add(Deque<Throwable> pending, Throwable candidate) {
        if (candidate != null) {
            pending.addLast(candidate);
        }
    }

    private static LegalManifestIssue issue(
            LegalManifestIssueCode code,
            String location) {
        return LegalManifestIssue.at(code, location);
    }

    private static final class FailureEvidence {

        private LegalManifestIssueCode operationalCode;
        private LegalManifestIssue operationalIssue;
        private LegalManifestIssue blockedIssue;
        private boolean constraint;

        void acceptSqlState(String sqlState) {
            if (sqlState == null || sqlState.length() != 5) {
                return;
            }
            if ("55P03".equals(sqlState)) {
                acceptOperational(LegalManifestIssueCode.DB_LOCK_TIMEOUT);
            } else if ("57014".equals(sqlState)) {
                acceptOperational(LegalManifestIssueCode.DB_STATEMENT_TIMEOUT);
            } else if ("25001".equals(sqlState)) {
                acceptOperational(LegalManifestIssueCode.DB_ISOLATION);
            } else if (sqlState.startsWith("08")) {
                acceptOperational(LegalManifestIssueCode.DB_CONNECTION);
            } else if (sqlState.startsWith("40")) {
                acceptOperational(LegalManifestIssueCode.DB_CONCURRENCY);
            } else if ("42P01".equals(sqlState)
                    || "42703".equals(sqlState)
                    || "42883".equals(sqlState)
                    || "3F000".equals(sqlState)) {
                acceptOperational(LegalManifestIssueCode.DB_SCHEMA_INCOMPATIBLE);
            } else if (sqlState.startsWith("23")
                    || "22001".equals(sqlState)
                    || "P0001".equals(sqlState)) {
                constraint = true;
            }
        }

        void acceptOperational(LegalManifestIssueCode candidate) {
            if (operationalCode == null
                    || priority(candidate) < priority(operationalCode)) {
                operationalCode = candidate;
            }
        }

        LegalManifestIssueCode operationalCode() {
            return operationalCode;
        }

        LegalManifestIssue operationalIssue() {
            return operationalIssue;
        }

        void setOperationalIssue(LegalManifestIssue issue) {
            this.operationalIssue = issue;
        }

        LegalManifestIssue blockedIssue() {
            return blockedIssue;
        }

        void setBlockedIssue(LegalManifestIssue issue) {
            this.blockedIssue = issue;
        }

        boolean constraint() {
            return constraint;
        }

        private static int priority(LegalManifestIssueCode code) {
            return switch (code) {
                case DB_CONNECTION -> 0;
                case DB_LOCK_TIMEOUT -> 1;
                case DB_STATEMENT_TIMEOUT -> 2;
                case DB_ISOLATION -> 3;
                case DB_CONCURRENCY -> 4;
                case DB_SCHEMA_INCOMPATIBLE -> 5;
                default -> 6;
            };
        }
    }
}
