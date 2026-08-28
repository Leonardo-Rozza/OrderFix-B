package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialReadiness;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LegalEditorialReadinessCoreTest {

    private static final Instant OBSERVED_AT = Instant.parse(
            "2026-08-28T15:00:00.123456Z");

    @Test
    void coreSourceContainsOnlyReadQueriesAndNeverOpensAGateOrReadsAClock()
            throws Exception {
        String coreSource = Files.readString(Path.of(
                "src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/"
                        + "persistence/LegalEditorialReadinessCore.java"));
        String originSource = Files.readString(Path.of(
                "src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/"
                        + "persistence/LegalManifestOriginGraphVerifier.java"));
        String normalized = (coreSource + originSource)
                .toLowerCase(java.util.Locale.ROOT);

        assertThat(normalized)
                .doesNotContain("for update")
                .doesNotContain("for share")
                .doesNotContain("legal_validar_publicacion_sellada")
                .doesNotContain("transaction_timestamp")
                .doesNotContain("new legalmanifestdatabasegate")
                .doesNotContain("insert into ")
                .doesNotContain("update legal_")
                .doesNotContain("delete from ");
        assertThat(normalized).contains("select ");
    }

    @Test
    void exposesOnePackagePrivateBundleFreeObservationPathSharedByReadiness()
            throws Exception {
        Method observeState = LegalEditorialReadinessCore.class.getDeclaredMethod(
                "observeState",
                String.class,
                Instant.class);
        String coreSource = Files.readString(Path.of(
                "src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/"
                        + "persistence/LegalEditorialReadinessCore.java"));

        assertThat(observeState.getReturnType())
                .isEqualTo(LegalEditorialReadinessObservation.class);
        assertThat(observeState.getModifiers()
                & (Modifier.PUBLIC | Modifier.PROTECTED | Modifier.PRIVATE))
                .isZero();
        assertThat(coreSource)
                .contains("EditorialStateSnapshot snapshot = readStateSnapshot(")
                .contains("return readStateSnapshot(")
                .containsOnlyOnce("new LegalEditorialReadinessObservation(");
    }

    @Test
    void serviceReadsOneCallerOwnedTransactionTimestampAndPassesItToCore() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        LegalManifestDatabaseGate gate = mock(LegalManifestDatabaseGate.class);
        LegalEditorialReadinessCore core = mock(LegalEditorialReadinessCore.class);
        LegalEditorialSchemaVerifier schemaVerifier = mock(
                LegalEditorialSchemaVerifier.class);
        LegalEditorialPrivilegeVerifier privilegeVerifier = mock(
                LegalEditorialPrivilegeVerifier.class);
        ValidatedRelease release = mock(ValidatedRelease.class);
        OffsetDateTime databaseTimestamp = OffsetDateTime.ofInstant(
                OBSERVED_AT,
                ZoneOffset.UTC);
        LegalEditorialReadinessResult expected = LegalEditorialReadinessResult.notReady(
                observation(Optional.empty()),
                java.util.List.of(com.leonardorozza.mvgrreparacionesbackend
                        .legal.manifest.core.LegalManifestIssue.at(
                                LegalManifestIssueCode.PUBLICATION_NOT_SEALED,
                                "database/publication")));
        when(gate.usesJdbc(jdbc)).thenReturn(true);
        when(core.usesJdbc(jdbc)).thenReturn(true);
        when(jdbc.queryForObject("SELECT transaction_timestamp()", OffsetDateTime.class))
                .thenReturn(databaseTimestamp);
        when(core.evaluate(release, OBSERVED_AT)).thenReturn(expected);
        when(gate.executeReadOnly(any())).thenAnswer(invocation -> {
            org.springframework.transaction.support.TransactionCallback<?> callback =
                    invocation.getArgument(0);
            return callback.doInTransaction(new SimpleTransactionStatus());
        });
        LegalEditorialReadinessService service = new LegalEditorialReadinessService(
                gate,
                jdbc,
                core,
                new LegalEditorialFailureMapper(),
                schemaVerifier,
                privilegeVerifier);

        LegalEditorialReadinessResult result = service.evaluate(release);

        assertThat(result).isSameAs(expected);
        verify(jdbc, times(1)).queryForObject(
                "SELECT transaction_timestamp()",
                OffsetDateTime.class);
        verify(core, times(1)).evaluate(release, OBSERVED_AT);
        verify(gate, times(1)).requireExactEditorialPreflights(
                jdbc,
                schemaVerifier,
                privilegeVerifier);
    }

    @Test
    void anUnreadableObservationReturnsErrorWithoutPartialEvidence() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        LegalManifestDatabaseGate gate = mock(LegalManifestDatabaseGate.class);
        LegalEditorialReadinessCore core = mock(LegalEditorialReadinessCore.class);
        LegalEditorialSchemaVerifier schemaVerifier = mock(
                LegalEditorialSchemaVerifier.class);
        LegalEditorialPrivilegeVerifier privilegeVerifier = mock(
                LegalEditorialPrivilegeVerifier.class);
        ValidatedRelease release = mock(ValidatedRelease.class);
        when(gate.usesJdbc(jdbc)).thenReturn(true);
        when(core.usesJdbc(jdbc)).thenReturn(true);
        when(gate.executeReadOnly(any())).thenThrow(
                new CannotGetJdbcConnectionException("offline"));
        LegalEditorialReadinessService service = new LegalEditorialReadinessService(
                gate,
                jdbc,
                core,
                new LegalEditorialFailureMapper(),
                schemaVerifier,
                privilegeVerifier);

        LegalEditorialReadinessResult result = service.evaluate(release);

        assertThat(result.readiness()).isEqualTo(LegalEditorialReadiness.ERROR);
        assertThat(result.observation()).isEmpty();
        assertThat(result.issues())
                .extracting(issue -> issue.code())
                .containsExactly(LegalManifestIssueCode.EDITORIAL_OBSERVATION_FAILED);
    }

    @Test
    void serviceHasNoAssemblyPathThatCanSkipTheExactEditorialPreflights() {
        assertThat(LegalEditorialReadinessService.class.getDeclaredConstructors())
                .singleElement()
                .satisfies(constructor -> assertThat(constructor.getParameterCount())
                        .isEqualTo(6));
    }

    @Test
    void freezesFiniteBudgetsForPotentiallyUnboundedEditorialHistory() {
        assertThat(LegalEditorialReadinessCore.MAX_RELEVANT_DOCUMENT_VERSIONS)
                .isEqualTo(8_192);
        assertThat(LegalEditorialReadinessCore.MAX_RELEVANT_REQUIREMENT_VERSIONS)
                .isEqualTo(16_384);
        assertThat(LegalEditorialReadinessCore.MAX_REPLACEMENT_BATCHES)
                .isEqualTo(8_192);
    }

    private static LegalEditorialReadinessObservation observation(
            Optional<UUID> publicationId) {
        return new LegalEditorialReadinessObservation(
                publicationId,
                OBSERVED_AT,
                "sha256:" + "a".repeat(64),
                0, 0, 0, 0, 0, 0, 0);
    }
}
