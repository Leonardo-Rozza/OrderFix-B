package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialPlanValidator.ValidatedEditorialPlan;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidation;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalEditorialPlanV1;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalEditorialPlanV1.DocumentRef;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalEditorialPlanV1.DocumentReplacementBatch;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalEditorialPlanV1.OperationType;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.lang.reflect.Modifier;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LegalEditorialPlanServiceTest {

    private static final Instant OBSERVED_AT =
            Instant.parse("2026-08-28T18:00:00.123456Z");

    @Test
    void constructorIsTheOnlyAssemblyPathAndRequiresTheExactEditorialPreflights() {
        Harness harness = new Harness();

        new LegalEditorialPlanService(
                harness.gate,
                harness.jdbc,
                harness.planner,
                harness.replaceScopeGuard,
                harness.failureMapper,
                harness.schemaVerifier,
                harness.privilegeVerifier);

        assertThat(LegalEditorialPlanService.class.getDeclaredConstructors())
                .singleElement()
                .satisfies(constructor -> {
                    assertThat(constructor.getParameterCount()).isEqualTo(7);
                    assertThat(constructor.getModifiers() & Modifier.PUBLIC).isZero();
                });
        verify(harness.gate).requireExactEditorialPreflights(
                harness.jdbc,
                harness.schemaVerifier,
                harness.privilegeVerifier);
    }

    @Test
    void promoteReadsExactlyOnePostLockTimestampAndPassesItUnchangedToCore() {
        Harness harness = new Harness();
        ValidatedRelease release = mock(ValidatedRelease.class);
        LegalEditorialPlanResult expected = blockedResult();
        when(harness.planner.planPromote(release, OBSERVED_AT)).thenReturn(expected);

        LegalEditorialPlanResult result = harness.service().planPromote(release);

        assertThat(result).isSameAs(expected);
        verify(harness.gate, times(1)).executeReadOnly(any());
        verify(harness.jdbc, times(1)).queryForObject(
                "SELECT transaction_timestamp()",
                OffsetDateTime.class);
        verify(harness.planner, times(1)).planPromote(release, OBSERVED_AT);
    }

    @Test
    void replaceAndRetireUseTheSameCallerOwnedTimestampBoundary() {
        Harness replaceHarness = new Harness();
        Harness retireHarness = new Harness();
        ValidatedRelease release = mock(ValidatedRelease.class);
        ValidatedEditorialPlan token = mock(ValidatedEditorialPlan.class);
        LegalEditorialPlanResult expected = blockedResult();
        when(replaceHarness.planner.planReplace(release, token, OBSERVED_AT))
                .thenReturn(expected);
        when(retireHarness.planner.planRetire(release, token, OBSERVED_AT))
                .thenReturn(expected);

        assertThat(replaceHarness.service().planReplace(release, token)).isSameAs(expected);
        assertThat(retireHarness.service().planRetire(release, token)).isSameAs(expected);

        verify(replaceHarness.jdbc).queryForObject(
                "SELECT transaction_timestamp()",
                OffsetDateTime.class);
        verify(replaceHarness.replaceScopeGuard).validate(token);
        verify(retireHarness.jdbc).queryForObject(
                "SELECT transaction_timestamp()",
                OffsetDateTime.class);
        verify(replaceHarness.planner).planReplace(release, token, OBSERVED_AT);
        verify(retireHarness.planner).planRetire(release, token, OBSERVED_AT);
    }

    @Test
    void manyToManyReplaceIsBlockedByTheRealGuardBeforeReadOnlyGateOrTimestamp() {
        Harness harness = new Harness();
        ValidatedRelease release = mock(ValidatedRelease.class);
        ValidatedEditorialPlan token = manyToManyPlan();

        LegalEditorialPlanResult result = harness.service(
                new LegalEditorialReplaceScopeGuard()).planReplace(release, token);

        assertThat(result.status()).isEqualTo(LegalManifestStatus.BLOCKED);
        assertThat(result.executionPlan()).isEmpty();
        assertThat(result.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.code())
                    .isEqualTo(LegalManifestIssueCode.REPLACEMENT_MAPPING_INVALID);
            assertThat(issue.location()).isEqualTo("documentReplacementBatches");
        });
        verify(harness.gate, never()).usesJdbc(any());
        verify(harness.gate, never()).executeReadOnly(any());
        verify(harness.jdbc, never()).queryForObject(any(String.class), any(Class.class));
        verify(harness.planner, never()).usesJdbc(any());
        verify(harness.planner, never()).planReplace(any(), any(), any());
    }

    @Test
    void aForeignJdbcGraphFailsBeforeOpeningTheReadOnlyTransaction() {
        Harness harness = new Harness();
        ValidatedRelease release = mock(ValidatedRelease.class);
        when(harness.planner.usesJdbc(harness.jdbc)).thenReturn(false);

        LegalEditorialPlanResult result = harness.service().planPromote(release);

        assertObservationErrorWithoutEvidence(result);
        verify(harness.gate, never()).executeReadOnly(any());
        verify(harness.jdbc, never()).queryForObject(any(String.class), any(Class.class));
        verify(harness.planner, never()).planPromote(any(), any());
    }

    @Test
    void aTypedBlockerRemainsBlockedAndNeverBecomesAnOperationalError() {
        Harness harness = new Harness();
        ValidatedRelease release = mock(ValidatedRelease.class);
        doThrow(new LegalEditorialBlockedException(
                LegalManifestIssueCode.CURRENT_STATE_MISMATCH,
                "database/state"))
                .when(harness.gate).executeReadOnly(any());

        LegalEditorialPlanResult result = harness.service().planPromote(release);

        assertThat(result.status()).isEqualTo(LegalManifestStatus.BLOCKED);
        assertThat(result.executionPlan()).isEmpty();
        assertThat(result.issues())
                .extracting(LegalManifestIssue::code)
                .containsExactly(LegalManifestIssueCode.CURRENT_STATE_MISMATCH);
    }

    @Test
    void connectionFailureIsAlwaysErrorWithoutPlanEvidence() {
        Harness harness = new Harness();
        ValidatedRelease release = mock(ValidatedRelease.class);
        doThrow(new CannotGetJdbcConnectionException("offline"))
                .when(harness.gate).executeReadOnly(any());

        LegalEditorialPlanResult result = harness.service().planPromote(release);

        assertObservationErrorWithoutEvidence(result);
    }

    private static LegalEditorialPlanResult blockedResult() {
        return LegalEditorialPlanResult.blocked(List.of(LegalManifestIssue.at(
                LegalManifestIssueCode.CURRENT_STATE_MISMATCH,
                "database/state")));
    }

    private static ValidatedEditorialPlan manyToManyPlan() {
        DocumentReplacementBatch batch = new DocumentReplacementBatch(
                UUID.fromString("00000000-0000-0000-0000-000000000101"),
                List.of(ContextoLegal.USO_CONTINUADO),
                List.of(
                        documentRef("00000000-0000-0000-0000-000000000102", 'a'),
                        documentRef("00000000-0000-0000-0000-000000000103", 'b')),
                List.of(
                        documentRef("00000000-0000-0000-0000-000000000104", 'c'),
                        documentRef("00000000-0000-0000-0000-000000000105", 'd')));
        LegalEditorialPlanV1 model = mock(LegalEditorialPlanV1.class);
        when(model.documentReplacementBatches()).thenReturn(List.of(batch));
        ValidatedEditorialPlan token = mock(ValidatedEditorialPlan.class);
        when(token.plan()).thenReturn(model);
        when(token.operationType()).thenReturn(OperationType.REPLACE);
        return token;
    }

    private static DocumentRef documentRef(String id, char digestCharacter) {
        return new DocumentRef(
                UUID.fromString(id),
                String.valueOf(digestCharacter).repeat(64));
    }

    private static void assertObservationErrorWithoutEvidence(
            LegalEditorialPlanResult result) {
        assertThat(result.status()).isEqualTo(LegalManifestStatus.ERROR);
        assertThat(result.executionPlan()).isEmpty();
        assertThat(result.issues())
                .extracting(LegalManifestIssue::code)
                .containsExactly(LegalManifestIssueCode.EDITORIAL_OBSERVATION_FAILED);
    }

    private static final class Harness {
        private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        private final LegalManifestDatabaseGate gate = mock(LegalManifestDatabaseGate.class);
        private final LegalEditorialPlannerCore planner = mock(LegalEditorialPlannerCore.class);
        private final LegalEditorialReplaceScopeGuard replaceScopeGuard =
                mock(LegalEditorialReplaceScopeGuard.class);
        private final LegalEditorialFailureMapper failureMapper =
                new LegalEditorialFailureMapper();
        private final LegalEditorialSchemaVerifier schemaVerifier =
                mock(LegalEditorialSchemaVerifier.class);
        private final LegalEditorialPrivilegeVerifier privilegeVerifier =
                mock(LegalEditorialPrivilegeVerifier.class);

        private Harness() {
            when(gate.usesJdbc(jdbc)).thenReturn(true);
            when(planner.usesJdbc(jdbc)).thenReturn(true);
            when(replaceScopeGuard.validate(any())).thenAnswer(invocation ->
                    LegalManifestValidation.pass(invocation.getArgument(0)));
            when(jdbc.queryForObject(
                    "SELECT transaction_timestamp()",
                    OffsetDateTime.class)).thenReturn(OffsetDateTime.ofInstant(
                            OBSERVED_AT,
                            ZoneOffset.UTC));
            when(gate.executeReadOnly(any())).thenAnswer(invocation -> {
                org.springframework.transaction.support.TransactionCallback<?> callback =
                        invocation.getArgument(0);
                return callback.doInTransaction(new SimpleTransactionStatus());
            });
        }

        private LegalEditorialPlanService service() {
            return service(replaceScopeGuard);
        }

        private LegalEditorialPlanService service(
                LegalEditorialReplaceScopeGuard scopeGuard) {
            return new LegalEditorialPlanService(
                    gate,
                    jdbc,
                    planner,
                    scopeGuard,
                    failureMapper,
                    schemaVerifier,
                    privilegeVerifier);
        }
    }
}
