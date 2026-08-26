package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

/** Maps import failures to safe v2 issues without inferring commit certainty from exception types. */
final class LegalImportFailureMapper {

    private static final String COMMIT_LOCATION = "database/commit";

    private final LegalDatabaseFailureMapper databaseFailureMapper;

    LegalImportFailureMapper() {
        this(new LegalDatabaseFailureMapper());
    }

    LegalImportFailureMapper(LegalDatabaseFailureMapper databaseFailureMapper) {
        this.databaseFailureMapper = Objects.requireNonNull(
                databaseFailureMapper,
                "databaseFailureMapper");
    }

    LegalManifestIssue map(Throwable failure, boolean commitOutcomeUnknown) {
        Objects.requireNonNull(failure, "failure");
        if (commitOutcomeUnknown) {
            return LegalManifestIssue.at(
                    LegalManifestIssueCode.IMPORT_DB_COMMIT_UNKNOWN,
                    COMMIT_LOCATION);
        }

        TypedEvidence typed = inspectTyped(failure);
        if (typed.operationalIssue() != null) {
            return typed.operationalIssue();
        }
        if (typed.blockedIssue() != null) {
            return typed.blockedIssue();
        }

        LegalManifestIssue databaseIssue = databaseFailureMapper.map(failure);
        return LegalManifestIssue.at(
                importCode(databaseIssue.code()),
                databaseIssue.location());
    }

    private static TypedEvidence inspectTyped(Throwable root) {
        LegalManifestIssue operational = null;
        LegalManifestIssue blocked = null;
        Deque<Throwable> pending = new ArrayDeque<>();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        pending.add(root);

        while (!pending.isEmpty()) {
            Throwable current = pending.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            if (current instanceof LegalImportOperationalException exception
                    && operational == null) {
                operational = exception.issue();
            } else if (current instanceof LegalImportBlockedException exception
                    && blocked == null) {
                blocked = exception.issue();
            }
            add(pending, current.getCause());
            for (Throwable suppressed : current.getSuppressed()) {
                add(pending, suppressed);
            }
        }
        return new TypedEvidence(operational, blocked);
    }

    private static void add(Deque<Throwable> pending, Throwable candidate) {
        if (candidate != null) {
            pending.addLast(candidate);
        }
    }

    private static LegalManifestIssueCode importCode(LegalManifestIssueCode databaseCode) {
        return switch (databaseCode) {
            case DB_PERSISTED_CONFLICT -> LegalManifestIssueCode.IMPORT_DB_PERSISTED_CONFLICT;
            case DB_CONSTRAINT -> LegalManifestIssueCode.IMPORT_DB_CONSTRAINT;
            case DB_ISOLATION -> LegalManifestIssueCode.IMPORT_DB_ISOLATION;
            case DB_LOCK_TIMEOUT -> LegalManifestIssueCode.IMPORT_DB_LOCK_TIMEOUT;
            case DB_STATEMENT_TIMEOUT -> LegalManifestIssueCode.IMPORT_DB_STATEMENT_TIMEOUT;
            case DB_CONNECTION -> LegalManifestIssueCode.IMPORT_DB_CONNECTION;
            case DB_CONCURRENCY -> LegalManifestIssueCode.IMPORT_DB_CONCURRENCY;
            case DB_SCHEMA_INCOMPATIBLE ->
                    LegalManifestIssueCode.IMPORT_DB_SCHEMA_INCOMPATIBLE;
            case DB_OPERATION_FAILED -> LegalManifestIssueCode.IMPORT_DB_OPERATION_FAILED;
            default -> LegalManifestIssueCode.IMPORT_DB_OPERATION_FAILED;
        };
    }

    private record TypedEvidence(
            LegalManifestIssue operationalIssue,
            LegalManifestIssue blockedIssue
    ) { }
}
