package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidation;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestDryRunService.DryRunResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LegalManifestDryRunServiceTest {

    private static final String GOLDEN_MANIFEST =
            "/legal/manifest/release-valid-v1/publication-manifest.json";

    private static ValidatedRelease goldenRelease;

    @BeforeAll
    static void validateGoldenRelease() throws URISyntaxException {
        Path manifest = Path.of(Objects.requireNonNull(
                LegalManifestDryRunServiceTest.class.getResource(GOLDEN_MANIFEST)).toURI());
        LegalManifestValidation<ValidatedRelease> validation =
                new LegalManifestValidator().validate(manifest);
        assertThat(validation.status()).isEqualTo(LegalManifestStatus.PASS);
        goldenRelease = validation.value().orElseThrow();
    }

    @Test
    void mapsTheInternalReceiptWithoutExposingItsIdentityOrTimestamps() {
        LegalManifestDatabaseGate gate = mock(LegalManifestDatabaseGate.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        LegalManifestGraphWriter writer = mock(LegalManifestGraphWriter.class);
        LegalDatabaseFailureMapper mapper = mock(LegalDatabaseFailureMapper.class);
        SimpleTransactionStatus status = new SimpleTransactionStatus();
        invokeCallback(gate, status);
        stubPublicationLookup(jdbc, List.of());
        LegalManifestGraphReceipt receipt = receipt();
        when(writer.writeNew(goldenRelease)).thenReturn(receipt);
        LegalManifestDryRunService service = new LegalManifestDryRunService(
                gate,
                jdbc,
                writer,
                mapper);

        LegalManifestValidation<DryRunResult> validation = service.dryRun(goldenRelease);

        assertThat(status.isRollbackOnly()).isTrue();
        assertThat(validation.status()).isEqualTo(LegalManifestStatus.PASS);
        assertThat(validation.value()).contains(new DryRunResult(
                11, 6, 8,
                3, 5, 6,
                2, 3, 3));
        verify(mapper, never()).map(any());
    }

    @Test
    void existingPublicationRemainsBlockedAndNeverInvokesTheWriter() {
        LegalManifestDatabaseGate gate = mock(LegalManifestDatabaseGate.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        LegalManifestGraphWriter writer = mock(LegalManifestGraphWriter.class);
        SimpleTransactionStatus status = new SimpleTransactionStatus();
        invokeCallback(gate, status);
        stubPublicationLookup(jdbc, List.of(UUID.randomUUID()));
        LegalManifestDryRunService service = new LegalManifestDryRunService(
                gate,
                jdbc,
                writer,
                new LegalDatabaseFailureMapper());

        LegalManifestValidation<DryRunResult> validation = service.dryRun(goldenRelease);

        assertThat(status.isRollbackOnly()).isTrue();
        assertThat(validation.status()).isEqualTo(LegalManifestStatus.BLOCKED);
        assertThat(validation.issues())
                .extracting(LegalManifestIssue::code)
                .containsExactly(LegalManifestIssueCode.DB_PERSISTED_CONFLICT);
        verify(writer, never()).writeNew(any());
    }

    @Test
    void writerFailureIsMappedOnlyAfterTheCallbackHasExitedRollbackOnly() {
        LegalManifestDatabaseGate gate = mock(LegalManifestDatabaseGate.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        LegalManifestGraphWriter writer = mock(LegalManifestGraphWriter.class);
        LegalDatabaseFailureMapper mapper = mock(LegalDatabaseFailureMapper.class);
        AtomicBoolean gateExited = new AtomicBoolean();
        when(gate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            SimpleTransactionStatus status = new SimpleTransactionStatus();
            try {
                return callback.doInTransaction(status);
            } catch (RuntimeException failure) {
                assertThat(status.isRollbackOnly()).isTrue();
                gateExited.set(true);
                throw failure;
            }
        });
        stubPublicationLookup(jdbc, List.of());
        QueryTimeoutException writerFailure = new QueryTimeoutException("writer timeout");
        when(writer.writeNew(goldenRelease)).thenThrow(writerFailure);
        when(mapper.map(writerFailure)).thenAnswer(invocation -> {
            assertThat(gateExited.get()).isTrue();
            return LegalManifestIssue.at(
                    LegalManifestIssueCode.DB_STATEMENT_TIMEOUT,
                    "database");
        });
        LegalManifestDryRunService service = new LegalManifestDryRunService(
                gate,
                jdbc,
                writer,
                mapper);

        LegalManifestValidation<DryRunResult> validation = service.dryRun(goldenRelease);

        assertThat(validation.status()).isEqualTo(LegalManifestStatus.ERROR);
        assertThat(validation.issues())
                .extracting(LegalManifestIssue::code)
                .containsExactly(LegalManifestIssueCode.DB_STATEMENT_TIMEOUT);
        verify(mapper).map(writerFailure);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void invokeCallback(
            LegalManifestDatabaseGate gate,
            SimpleTransactionStatus status) {
        when(gate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback callback = invocation.getArgument(0);
            return callback.doInTransaction(status);
        });
    }

    @SuppressWarnings("unchecked")
    private static void stubPublicationLookup(JdbcTemplate jdbc, List<UUID> result) {
        when(jdbc.query(
                anyString(),
                org.mockito.ArgumentMatchers.<RowMapper<UUID>>any(),
                eq(goldenRelease.plan().manifest().publicationId())))
                .thenReturn(result);
    }

    private static LegalManifestGraphReceipt receipt() {
        Instant instant = Instant.parse("2026-08-25T18:00:00.123456Z");
        return new LegalManifestGraphReceipt(
                UUID.randomUUID(),
                instant,
                instant,
                11,
                6,
                8,
                3,
                5,
                6,
                2,
                3,
                3);
    }
}
