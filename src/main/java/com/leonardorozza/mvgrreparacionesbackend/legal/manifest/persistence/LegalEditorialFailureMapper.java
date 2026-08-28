package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
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

/** Maps editorial JDBC failures to safe reason codes without reading dynamic messages. */
final class LegalEditorialFailureMapper {

    static final String OBSERVATION_LOCATION = "database/observation";
    static final String CONCURRENCY_LOCATION = "database/concurrency";
    static final String SCHEMA_LOCATION = "database/schema";
    static final String PRIVILEGES_LOCATION = "database/privileges";

    LegalManifestIssue map(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        FailureEvidence evidence = inspect(failure);
        if (evidence.operationalIssue() != null) {
            return evidence.operationalIssue();
        }
        if (evidence.observationFailed()) {
            return issue(
                    LegalManifestIssueCode.EDITORIAL_OBSERVATION_FAILED,
                    OBSERVATION_LOCATION);
        }
        if (evidence.concurrentOperation()) {
            return issue(
                    LegalManifestIssueCode.CONCURRENT_OPERATION,
                    CONCURRENCY_LOCATION);
        }
        if (evidence.privilegeDrift()) {
            return issue(
                    LegalManifestIssueCode.ROLE_PRIVILEGE_DRIFT,
                    PRIVILEGES_LOCATION);
        }
        if (evidence.schemaDrift()) {
            return issue(LegalManifestIssueCode.SCHEMA_DRIFT, SCHEMA_LOCATION);
        }
        if (evidence.blockedIssue() != null) {
            return evidence.blockedIssue();
        }
        return issue(
                LegalManifestIssueCode.EDITORIAL_OBSERVATION_FAILED,
                OBSERVATION_LOCATION);
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
            if (current instanceof LegalEditorialOperationalException operational
                    && evidence.operationalIssue() == null) {
                evidence.setOperationalIssue(operational.issue());
            } else if (current instanceof LegalEditorialBlockedException blocked
                    && evidence.blockedIssue() == null) {
                evidence.setBlockedIssue(blocked.issue());
            }

            if (current instanceof SQLException sqlException) {
                evidence.acceptSqlState(sqlException.getSQLState());
                add(pending, sqlException.getNextException());
            }
            if (current instanceof CannotGetJdbcConnectionException
                    || current instanceof CannotCreateTransactionException
                    || current instanceof QueryTimeoutException
                    || current instanceof TransactionTimedOutException) {
                evidence.setObservationFailed();
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

        private LegalManifestIssue operationalIssue;
        private LegalManifestIssue blockedIssue;
        private boolean observationFailed;
        private boolean concurrentOperation;
        private boolean privilegeDrift;
        private boolean schemaDrift;

        void acceptSqlState(String sqlState) {
            if (sqlState == null || sqlState.length() != 5) {
                return;
            }
            if (sqlState.startsWith("08")
                    || "57014".equals(sqlState)
                    || "25001".equals(sqlState)) {
                observationFailed = true;
            } else if ("55P03".equals(sqlState) || sqlState.startsWith("40")) {
                concurrentOperation = true;
            } else if ("42501".equals(sqlState)) {
                privilegeDrift = true;
            } else if ("42P01".equals(sqlState)
                    || "42703".equals(sqlState)
                    || "42883".equals(sqlState)
                    || "3F000".equals(sqlState)) {
                schemaDrift = true;
            }
        }

        LegalManifestIssue operationalIssue() {
            return operationalIssue;
        }

        void setOperationalIssue(LegalManifestIssue issue) {
            operationalIssue = issue;
        }

        LegalManifestIssue blockedIssue() {
            return blockedIssue;
        }

        void setBlockedIssue(LegalManifestIssue issue) {
            blockedIssue = issue;
        }

        boolean observationFailed() {
            return observationFailed;
        }

        void setObservationFailed() {
            observationFailed = true;
        }

        boolean concurrentOperation() {
            return concurrentOperation;
        }

        boolean privilegeDrift() {
            return privilegeDrift;
        }

        boolean schemaDrift() {
            return schemaDrift;
        }
    }
}
