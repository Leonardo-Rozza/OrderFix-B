package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidation;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LegalManifestImportServiceTest {

    private static final String GOLDEN_MANIFEST =
            "/legal/manifest/release-valid-v1/publication-manifest.json";

    private static ValidatedRelease goldenRelease;

    @BeforeAll
    static void validateGoldenRelease() throws URISyntaxException {
        Path manifest = Path.of(Objects.requireNonNull(
                LegalManifestImportServiceTest.class.getResource(GOLDEN_MANIFEST)).toURI());
        LegalManifestValidation<ValidatedRelease> validation =
                new LegalManifestValidator().validate(manifest);
        assertThat(validation.status()).isEqualTo(LegalManifestStatus.PASS);
        goldenRelease = validation.value().orElseThrow();
    }

    @AfterEach
    void clearSynchronization() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void importsANewReleaseAndConfirmsOnlyAfterTheTransactionReturns() {
        Fixture fixture = fixture();
        executeAndComplete(fixture.gate(), TransactionSynchronization.STATUS_COMMITTED, null);
        stubLookup(fixture.jdbc(), List.of());
        LegalManifestGraphReceipt receipt = receipt();
        when(fixture.writer().writeNew(goldenRelease)).thenReturn(receipt);

        LegalManifestImportResult result = fixture.service().importManifest(goldenRelease);

        assertConfirmed(result, LegalManifestImportResult.Outcome.IMPORTED, receipt);
        verify(fixture.gate()).requireCommitOutcomeSafe();
        verify(fixture.writer()).writeNew(goldenRelease);
        verify(fixture.replayVerifier(), never()).verify(any(), any());
    }

    @Test
    void exactSealedReplayNeverInvokesTheWriter() throws Exception {
        Fixture fixture = fixture();
        executeAndComplete(fixture.gate(), TransactionSynchronization.STATUS_COMMITTED, null);
        UUID publicationId = UUID.randomUUID();
        stubLookup(fixture.jdbc(), List.of(publication("SELLADO", publicationId)));
        LegalManifestGraphReceipt receipt = receipt(publicationId);
        when(fixture.replayVerifier().verify(goldenRelease, publicationId)).thenReturn(receipt);

        LegalManifestImportResult result = fixture.service().importManifest(goldenRelease);

        assertConfirmed(result, LegalManifestImportResult.Outcome.ALREADY_IMPORTED, receipt);
        verify(fixture.writer(), never()).writeNew(any());
        verify(fixture.replayVerifier()).verify(goldenRelease, publicationId);
    }

    @Test
    void openPublicationFailsClosedAndIsNeverRepaired() throws Exception {
        Fixture fixture = fixture();
        executeAndRollbackOnFailure(fixture.gate());
        stubLookup(fixture.jdbc(), List.of(publication("ABIERTO", UUID.randomUUID())));

        LegalManifestImportResult result = fixture.service().importManifest(goldenRelease);

        assertKnownFailure(
                result,
                LegalManifestStatus.ERROR,
                LegalManifestIssueCode.IMPORT_DB_PUBLICATION_OPEN);
        verify(fixture.writer(), never()).writeNew(any());
        verify(fixture.replayVerifier(), never()).verify(any(), any());
    }

    @Test
    void replayConflictRollsBackAndReturnsKnownFalse() throws Exception {
        Fixture fixture = fixture();
        executeAndRollbackOnFailure(fixture.gate());
        UUID publicationId = UUID.randomUUID();
        stubLookup(fixture.jdbc(), List.of(publication("SELLADO", publicationId)));
        when(fixture.replayVerifier().verify(goldenRelease, publicationId))
                .thenThrow(new LegalImportBlockedException(
                        LegalManifestIssueCode.IMPORT_DB_PERSISTED_CONFLICT,
                        "database/publication"));

        LegalManifestImportResult result = fixture.service().importManifest(goldenRelease);

        assertKnownFailure(
                result,
                LegalManifestStatus.BLOCKED,
                LegalManifestIssueCode.IMPORT_DB_PERSISTED_CONFLICT);
        verify(fixture.writer(), never()).writeNew(any());
    }

    @Test
    void writerFailureIsMappedOnlyAfterRollbackCompletion() {
        Fixture fixture = fixture();
        executeAndRollbackOnFailure(fixture.gate());
        stubLookup(fixture.jdbc(), List.of());
        when(fixture.writer().writeNew(goldenRelease))
                .thenThrow(new LegalDryRunBlockedException(
                        LegalManifestIssueCode.DB_CONSTRAINT,
                        "database/publication"));

        LegalManifestImportResult result = fixture.service().importManifest(goldenRelease);

        assertKnownFailure(
                result,
                LegalManifestStatus.BLOCKED,
                LegalManifestIssueCode.IMPORT_DB_CONSTRAINT);
    }

    @Test
    void exceptionAfterCommittedCompletionStillReturnsConfirmedSuccess() {
        Fixture fixture = fixture();
        RuntimeException afterCommitFailure = new IllegalStateException("afterCommit failure");
        executeAndComplete(
                fixture.gate(),
                TransactionSynchronization.STATUS_COMMITTED,
                afterCommitFailure);
        stubLookup(fixture.jdbc(), List.of());
        LegalManifestGraphReceipt receipt = receipt();
        when(fixture.writer().writeNew(goldenRelease)).thenReturn(receipt);

        LegalManifestImportResult result = fixture.service().importManifest(goldenRelease);

        assertConfirmed(result, LegalManifestImportResult.Outcome.IMPORTED, receipt);
    }

    @Test
    void unknownCompletionNeverLeaksTheTentativeReceiptOrClaimsRollback() {
        Fixture fixture = fixture();
        executeAndComplete(
                fixture.gate(),
                TransactionSynchronization.STATUS_UNKNOWN,
                new IllegalStateException("connection outcome unknown"));
        stubLookup(fixture.jdbc(), List.of());
        when(fixture.writer().writeNew(goldenRelease)).thenReturn(receipt());

        LegalManifestImportResult result = fixture.service().importManifest(goldenRelease);

        assertThat(result.status()).isEqualTo(LegalManifestStatus.ERROR);
        assertThat(result.persisted()).isNull();
        assertThat(result.outcome()).contains(LegalManifestImportResult.Outcome.UNKNOWN);
        assertThat(result.receipt()).isEmpty();
        assertThat(result.issues())
                .extracting(LegalManifestIssue::code)
                .containsExactly(LegalManifestIssueCode.IMPORT_DB_COMMIT_UNKNOWN);
    }

    @Test
    void unsafeTransactionManagerConfigurationFailsBeforeReadingOrWriting() {
        Fixture fixture = fixture();
        doThrow(new IllegalArgumentException("unsafe transaction manager"))
                .when(fixture.gate()).requireCommitOutcomeSafe();

        LegalManifestImportResult result = fixture.service().importManifest(goldenRelease);

        assertKnownFailure(
                result,
                LegalManifestStatus.ERROR,
                LegalManifestIssueCode.IMPORT_DB_OPERATION_FAILED);
        verify(fixture.gate(), never()).execute(any());
        verify(fixture.jdbc(), never()).query(anyString(), any(RowMapper.class), any());
        verify(fixture.writer(), never()).writeNew(any());
    }

    @Test
    void mismatchedJdbcParticipantsFailBeforeOpeningTheTransaction() {
        Fixture fixture = fixture();
        when(fixture.writer().usesJdbc(fixture.jdbc())).thenReturn(false);

        LegalManifestImportResult result = fixture.service().importManifest(goldenRelease);

        assertKnownFailure(
                result,
                LegalManifestStatus.ERROR,
                LegalManifestIssueCode.IMPORT_DB_OPERATION_FAILED);
        verify(fixture.gate(), never()).execute(any());
        verify(fixture.jdbc(), never()).query(anyString(), any(RowMapper.class), any());
    }

    private static Fixture fixture() {
        LegalManifestDatabaseGate gate = mock(LegalManifestDatabaseGate.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        LegalManifestGraphWriter writer = mock(LegalManifestGraphWriter.class);
        LegalManifestReplayVerifier replayVerifier = mock(LegalManifestReplayVerifier.class);
        when(gate.usesJdbc(jdbc)).thenReturn(true);
        when(writer.usesJdbc(jdbc)).thenReturn(true);
        when(replayVerifier.usesJdbc(jdbc)).thenReturn(true);
        LegalManifestImportService service = new LegalManifestImportService(
                gate,
                jdbc,
                writer,
                replayVerifier,
                new LegalImportFailureMapper());
        return new Fixture(gate, jdbc, writer, replayVerifier, service);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void executeAndComplete(
            LegalManifestDatabaseGate gate,
            int completion,
            RuntimeException terminalFailure) {
        when(gate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback callback = invocation.getArgument(0);
            beginSynchronizedTransaction();
            try {
                Object value = callback.doInTransaction(new SimpleTransactionStatus());
                List<TransactionSynchronization> synchronizations =
                        TransactionSynchronizationManager.getSynchronizations();
                synchronizations.forEach(sync -> sync.beforeCommit(false));
                synchronizations.forEach(sync -> sync.afterCompletion(completion));
                if (terminalFailure != null) {
                    throw terminalFailure;
                }
                return value;
            } finally {
                TransactionSynchronizationManager.clear();
            }
        });
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void executeAndRollbackOnFailure(LegalManifestDatabaseGate gate) {
        when(gate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback callback = invocation.getArgument(0);
            beginSynchronizedTransaction();
            try {
                return callback.doInTransaction(new SimpleTransactionStatus());
            } catch (RuntimeException failure) {
                TransactionSynchronizationManager.getSynchronizations()
                        .forEach(sync -> sync.afterCompletion(
                                TransactionSynchronization.STATUS_ROLLED_BACK));
                throw failure;
            } finally {
                TransactionSynchronizationManager.clear();
            }
        });
    }

    private static void beginSynchronizedTransaction() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    @SuppressWarnings("unchecked")
    private static void stubLookup(JdbcTemplate jdbc, List<PublicationStub> publications) {
        when(jdbc.query(
                anyString(),
                org.mockito.ArgumentMatchers.<RowMapper<Object>>any(),
                eq(goldenRelease.plan().manifest().publicationId())))
                .thenAnswer(invocation -> {
                    RowMapper<Object> mapper = invocation.getArgument(1);
                    java.util.ArrayList<Object> rows = new java.util.ArrayList<>();
                    for (int index = 0; index < publications.size(); index++) {
                        PublicationStub publication = publications.get(index);
                        ResultSet resultSet = mock(ResultSet.class);
                        when(resultSet.getObject("id", UUID.class))
                                .thenReturn(publication.id());
                        when(resultSet.getString("estado_construccion"))
                                .thenReturn(publication.state());
                        rows.add(mapper.mapRow(resultSet, index));
                    }
                    return List.copyOf(rows);
                });
    }

    private static PublicationStub publication(String state, UUID id) {
        return new PublicationStub(id, state);
    }

    private static void assertConfirmed(
            LegalManifestImportResult result,
            LegalManifestImportResult.Outcome outcome,
            LegalManifestGraphReceipt receipt) {
        assertThat(result.status()).isEqualTo(LegalManifestStatus.PASS);
        assertThat(result.persisted()).isTrue();
        assertThat(result.outcome()).contains(outcome);
        assertThat(result.receipt()).contains(new LegalManifestImportResult.Receipt(
                receipt.publicationUuid(),
                receipt.importedAt(),
                receipt.sealedAt()));
        assertThat(result.issues()).isEmpty();
    }

    private static void assertKnownFailure(
            LegalManifestImportResult result,
            LegalManifestStatus status,
            LegalManifestIssueCode code) {
        assertThat(result.status()).isEqualTo(status);
        assertThat(result.persisted()).isFalse();
        assertThat(result.outcome()).isEmpty();
        assertThat(result.receipt()).isEmpty();
        assertThat(result.issues())
                .extracting(LegalManifestIssue::code)
                .containsExactly(code);
    }

    private static LegalManifestGraphReceipt receipt() {
        return receipt(UUID.randomUUID());
    }

    private static LegalManifestGraphReceipt receipt(UUID publicationId) {
        Instant instant = Instant.parse("2026-08-25T18:00:00.123456Z");
        return new LegalManifestGraphReceipt(
                publicationId,
                instant,
                instant,
                11,
                6,
                8,
                11,
                11,
                0,
                6,
                6,
                0);
    }

    private record PublicationStub(UUID id, String state) { }

    private record Fixture(
            LegalManifestDatabaseGate gate,
            JdbcTemplate jdbc,
            LegalManifestGraphWriter writer,
            LegalManifestReplayVerifier replayVerifier,
            LegalManifestImportService service
    ) { }
}
