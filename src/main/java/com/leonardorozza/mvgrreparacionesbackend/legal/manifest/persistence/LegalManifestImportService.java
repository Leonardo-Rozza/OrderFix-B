package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalImportTransactionState.Persistence;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestImportResult.Outcome;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Imports one accredited legal release or confirms its exact, immutable replay.
 *
 * <p>This service is deliberately not a Spring component. Only the isolated, accredited import
 * database context is allowed to construct it for production use.</p>
 */
public final class LegalManifestImportService {

    private static final String PUBLICATION_LOCATION = "database/publication";

    private final LegalManifestDatabaseGate databaseGate;
    private final JdbcTemplate jdbc;
    private final LegalManifestGraphWriter graphWriter;
    private final LegalManifestReplayVerifier replayVerifier;
    private final LegalImportFailureMapper failureMapper;

    LegalManifestImportService(
            LegalManifestDatabaseGate databaseGate,
            JdbcTemplate jdbc,
            LegalManifestGraphWriter graphWriter,
            LegalManifestReplayVerifier replayVerifier,
            LegalImportFailureMapper failureMapper) {
        this.databaseGate = Objects.requireNonNull(databaseGate, "databaseGate");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.graphWriter = Objects.requireNonNull(graphWriter, "graphWriter");
        this.replayVerifier = Objects.requireNonNull(replayVerifier, "replayVerifier");
        this.failureMapper = Objects.requireNonNull(failureMapper, "failureMapper");
    }

    LegalManifestImportService(
            LegalManifestDatabaseGate databaseGate,
            JdbcTemplate jdbc,
            LegalManifestGraphWriter graphWriter,
            LegalManifestReplayVerifier replayVerifier,
            LegalImportFailureMapper failureMapper,
            LegalV27ImportSchemaVerifier schemaVerifier,
            LegalImportPrivilegeVerifier privilegeVerifier) {
        this(databaseGate, jdbc, graphWriter, replayVerifier, failureMapper);
        databaseGate.requireExactImportPreflights(
                jdbc,
                Objects.requireNonNull(schemaVerifier, "schemaVerifier"),
                Objects.requireNonNull(privilegeVerifier, "privilegeVerifier"));
    }

    /** Executes one non-retrying import attempt for the opaque validator-issued release. */
    public LegalManifestImportResult importManifest(ValidatedRelease release) {
        Objects.requireNonNull(release, "release");
        LegalImportTransactionState<ImportEvidence> transactionState =
                new LegalImportTransactionState<>();
        try {
            databaseGate.requireCommitOutcomeSafe();
            requireSharedJdbcSession();
            databaseGate.execute(status -> {
                transactionState.callbackStarted();
                ImportEvidence evidence = resolveUnderEditorialLock(release);
                transactionState.receiptDelivered(evidence);
                return evidence;
            });
            transactionState.transactionReturnedNormally();
            return confirmedResult(transactionState.snapshot());
        } catch (RuntimeException failure) {
            LegalImportTransactionState.Snapshot<ImportEvidence> snapshot =
                    transactionState.snapshot();
            if (snapshot.persistence() == Persistence.PERSISTED) {
                return confirmedResult(snapshot);
            }
            boolean unknown = snapshot.persistence() == Persistence.UNKNOWN;
            var issue = failureMapper.map(failure, unknown);
            return unknown
                    ? LegalManifestImportResult.unknown()
                    : LegalManifestImportResult.failure(issue);
        }
    }

    private void requireSharedJdbcSession() {
        if (!databaseGate.usesJdbc(jdbc)
                || !graphWriter.usesJdbc(jdbc)
                || !replayVerifier.usesJdbc(jdbc)) {
            throw new IllegalArgumentException(
                    "El importador legal requiere una única sesión JDBC compartida");
        }
    }

    private ImportEvidence resolveUnderEditorialLock(ValidatedRelease release) {
        List<ExistingPublication> existing = jdbc.query("""
                SELECT id, estado_construccion
                  FROM legal_publicaciones
                 WHERE publication_external_id = ?
                 FOR UPDATE
                """, (resultSet, rowNumber) -> new ExistingPublication(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("estado_construccion")),
                release.plan().manifest().publicationId());
        if (existing.size() > 1) {
            throw operational(LegalManifestIssueCode.IMPORT_DB_OPERATION_FAILED);
        }
        if (existing.isEmpty()) {
            return new ImportEvidence(Outcome.IMPORTED, graphWriter.writeNew(release));
        }

        ExistingPublication publication = existing.getFirst();
        return switch (publication.state()) {
            case "SELLADO" -> new ImportEvidence(
                    Outcome.ALREADY_IMPORTED,
                    replayVerifier.verify(release, publication.id()));
            case "ABIERTO" -> throw operational(
                    LegalManifestIssueCode.IMPORT_DB_PUBLICATION_OPEN);
            default -> throw operational(LegalManifestIssueCode.IMPORT_DB_OPERATION_FAILED);
        };
    }

    private static LegalManifestImportResult confirmedResult(
            LegalImportTransactionState.Snapshot<ImportEvidence> snapshot) {
        ImportEvidence evidence = snapshot.receipt().orElseThrow(() ->
                new IllegalStateException("La importación confirmada no conservó su receipt"));
        return switch (evidence.outcome()) {
            case IMPORTED -> LegalManifestImportResult.imported(evidence.receipt());
            case ALREADY_IMPORTED ->
                    LegalManifestImportResult.alreadyImported(evidence.receipt());
            case UNKNOWN -> throw new IllegalStateException(
                    "UNKNOWN no puede existir dentro de un receipt confirmado");
        };
    }

    private static LegalImportOperationalException operational(
            LegalManifestIssueCode code) {
        return new LegalImportOperationalException(code, PUBLICATION_LOCATION);
    }

    private record ExistingPublication(UUID id, String state) {

        private ExistingPublication {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(state, "state");
        }
    }

    private record ImportEvidence(Outcome outcome, LegalManifestGraphReceipt receipt) {

        private ImportEvidence {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(receipt, "receipt");
            if (outcome == Outcome.UNKNOWN) {
                throw new IllegalArgumentException(
                        "UNKNOWN no puede entregarse como receipt transaccional");
            }
        }
    }
}
