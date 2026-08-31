package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.ConfinedEditorialPlanReader;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialPlanValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialPlanValidator.ValidatedEditorialPlan;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialReadiness;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidation;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoVersionLegal;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.assertEditorialConfirmed;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.assertEditorialKnownFailure;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.assertEditorialUnknown;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.applyHarness;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.cleanLegalState;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.copyRelease;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.editorialSequenceStates;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.harness;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.migrate;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.promoteToReady;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.restrictedApplyHarness;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.withReplicaRole;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** PostgreSQL accreditation for the bounded editorial replacement cutover. */
@Testcontainers
class LegalEditorialReplaceIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String EDITORIAL_ROLE = "ordenfix_legal_editorial_replace_it";
    private static final String EDITORIAL_PASSWORD = "replace-it-only";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_editorial_replace")
            .withUsername("ordenfix")
            .withPassword("ordenfix");

    private static JdbcTemplate owner;
    private static LegalManifestPersistenceITSupport.Harness importer;
    private static LegalManifestPersistenceITSupport.ApplyHarness apply;
    private static LegalManifestPersistenceITSupport.ApplyHarness ownerApply;
    private static LegalEditorialPrivilegeVerifier restrictedPrivileges;

    @TempDir
    private Path temporaryDirectory;

    @BeforeAll
    static void migrateProvisionAndAssemble() {
        migrate(POSTGRES);
        DataSource ownerDataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        owner = new JdbcTemplate(ownerDataSource);
        importer = harness(ownerDataSource, LegalDatabaseBudgets.production());
        ownerApply = applyHarness(ownerDataSource, LegalDatabaseBudgets.production());

        LegalRestrictedEditorialRoleFixture.Credentials credentials =
                new LegalRestrictedEditorialRoleFixture(
                        owner,
                        POSTGRES.getJdbcUrl(),
                        EDITORIAL_ROLE,
                        EDITORIAL_PASSWORD,
                        POSTGRES.getDriverClassName())
                        .provisionAndVerify();
        DataSource restrictedDataSource = new DriverManagerDataSource(
                credentials.jdbcUrl(), credentials.username(), credentials.password());
        apply = restrictedApplyHarness(
                restrictedDataSource,
                LegalDatabaseBudgets.production(),
                EDITORIAL_ROLE);
        restrictedPrivileges = apply.privilegeVerifier();
    }

    @BeforeEach
    void cleanAndRecheckRestrictedRole() {
        cleanLegalState(owner);
        restrictedPrivileges.verify();
    }

    @Test
    void mixedOneToOneCutoverCommitsTheExactReadyGraphUnderTheRestrictedRole()
            throws Exception {
        ImportedRelease source = readyRelease("replace-mixed-source-v1");
        DocumentVersion recoveredGap = retireDocumentToGap(
                source.publicationId(),
                "aviso-clientes-taller");
        ImportedRelease target = importedMixedTarget("replace-mixed-target-v1");
        PlanFixture fixture = replacementPlan(source, target, "mixed-primary", null);
        Map<String, String> immutableBefore = immutableOriginRows();
        Map<String, String> externalBefore = externalAcceptanceRows();
        String gapHistoryBefore = versionHistory(recoveredGap.id());

        assertThat(apply.readinessCore().evaluate(target.release(), databaseNow()).readiness())
                .isEqualTo(LegalEditorialReadiness.NOT_READY);
        assertFullMixedClassification(fixture);

        LegalEditorialApplyResult result = apply.service().applyReplace(
                target.release(), fixture.plan());

        assertEditorialConfirmed(result, LegalEditorialApplyResult.Outcome.APPLIED);
        LegalEditorialApplyReceipt receipt = result.receipt().orElseThrow();
        assertThat(receipt.operationType())
                .isEqualTo(LegalEditorialApplyReceipt.OperationType.REPLACE);
        assertThat(receipt.targetPublicationUuid()).isEqualTo(target.publicationId());
        assertThat(receipt.readinessAfter()).isEqualTo(LegalEditorialReadiness.READY);
        assertThat(receipt.documentVersions()).isEqualTo(target.release().documentCount());
        assertThat(receipt.requirementVersions()).isEqualTo(target.release().requirementCount());
        assertThat(receipt.replacementBatches()).isOne();

        assertExactMixedStates(fixture, target.publicationId());
        assertThat(versionHistory(recoveredGap.id())).isEqualTo(gapHistoryBefore);
        assertThat(owner.queryForObject(
                "SELECT estado FROM legal_documento_versiones WHERE id = ?",
                String.class,
                recoveredGap.id())).isEqualTo("RETIRADA");
        assertThat(immutableOriginRows()).isEqualTo(immutableBefore);
        assertThat(externalAcceptanceRows()).isEqualTo(externalBefore);
        assertThat(apply.readinessCore()
                .evaluate(target.release(), receipt.appliedAt()).readiness())
                .isEqualTo(LegalEditorialReadiness.READY);
    }

    @Test
    void requirementOnlyCutoverUsesNoReplacementBatchAndStillBecomesReady()
            throws Exception {
        ImportedRelease source = readyRelease("replace-requirement-source-v1");
        ImportedRelease target = importedRequirementOnlyTarget(
                "replace-requirement-target-v1");
        PlanFixture fixture = replacementPlan(source, target, "requirement-only", null);

        assertThat(fixture.documentAdditions()).isEmpty();
        assertThat(fixture.documentReplacements()).isEmpty();
        assertThat(fixture.documentRetirements()).isEmpty();
        assertThat(fixture.documentReuses()).hasSize(target.release().documentCount());
        assertThat(fixture.requirementReplacements()).hasSize(1);
        assertThat(fixture.requirementReuses()).hasSize(5);
        assertThat(fixture.requirementAdditions()).isEmpty();
        assertThat(fixture.requirementRetirements()).isEmpty();

        LegalEditorialApplyResult result = apply.service().applyReplace(
                target.release(), fixture.plan());

        assertEditorialConfirmed(result, LegalEditorialApplyResult.Outcome.APPLIED);
        assertThat(result.receipt().orElseThrow().replacementBatches()).isZero();
        assertThat(owner.queryForObject(
                "SELECT count(*) FROM legal_documento_reemplazo_lotes", Long.class))
                .isZero();
        assertAllCurrentProjectionsBelongTo(target.publicationId());
        assertThat(apply.readinessCore().evaluate(
                target.release(), result.appliedAt().orElseThrow()).readiness())
                .isEqualTo(LegalEditorialReadiness.READY);
    }

    @Test
    void exactPostStateReplaysAcrossDifferentOperationAndPlanShaWithoutDml()
            throws Exception {
        ImportedRelease source = readyRelease("replace-replay-source-v1");
        retireDocumentToGap(source.publicationId(), "aviso-clientes-taller");
        ImportedRelease target = importedMixedTarget("replace-replay-target-v1");
        String sourceFingerprint = apply.readinessCore()
                .observeState(source.release().plan().manifest().publicationId(), databaseNow())
                .editorialStateFingerprint();
        PlanFixture firstPlan = replacementPlan(
                source, target, "replay-operation-one", sourceFingerprint);
        PlanFixture secondPlan = replacementPlan(
                source, target, "replay-operation-two", sourceFingerprint);
        assertThat(secondPlan.plan().operationId()).isNotEqualTo(firstPlan.plan().operationId());
        assertThat(secondPlan.plan().editorialPlanSha256())
                .isNotEqualTo(firstPlan.plan().editorialPlanSha256());

        LegalEditorialApplyResult first = apply.service().applyReplace(
                target.release(), firstPlan.plan());
        assertEditorialConfirmed(first, LegalEditorialApplyResult.Outcome.APPLIED);
        Map<String, String> rowsAfterApply = editorialTableRows();
        Map<String, LegalManifestPersistenceITSupport.SequenceState> sequencesAfterApply =
                editorialSequenceStates(owner);

        assertThat(databaseNow()).isAfter(first.appliedAt().orElseThrow());
        LegalEditorialApplyResult exactReplay = apply.service().applyReplace(
                target.release(), firstPlan.plan());
        LegalEditorialApplyResult foreignIdentityReplay = apply.service().applyReplace(
                target.release(), secondPlan.plan());

        assertEditorialConfirmed(
                exactReplay,
                LegalEditorialApplyResult.Outcome.ALREADY_APPLIED);
        assertEditorialConfirmed(
                foreignIdentityReplay,
                LegalEditorialApplyResult.Outcome.ALREADY_APPLIED);
        assertThat(exactReplay.receipt()).isEqualTo(first.receipt());
        assertThat(foreignIdentityReplay.receipt()).isEqualTo(first.receipt());
        assertThat(exactReplay.appliedAt()).isEqualTo(first.appliedAt());
        assertThat(foreignIdentityReplay.appliedAt()).isEqualTo(first.appliedAt());
        assertThat(editorialTableRows()).isEqualTo(rowsAfterApply);
        assertThat(editorialSequenceStates(owner)).isEqualTo(sequencesAfterApply);
    }

    @Test
    void wrongFingerprintBlocksWithoutRowsOrSequenceAdvances() throws Exception {
        ImportedRelease source = readyRelease("replace-fingerprint-source-v1");
        retireDocumentToGap(source.publicationId(), "aviso-clientes-taller");
        ImportedRelease target = importedMixedTarget("replace-fingerprint-target-v1");
        PlanFixture fixture = replacementPlan(
                source, target, "wrong-fingerprint", "sha256:" + "0".repeat(64));
        Map<String, String> rowsBefore = editorialTableRows();
        Map<String, LegalManifestPersistenceITSupport.SequenceState> sequencesBefore =
                editorialSequenceStates(owner);

        LegalEditorialApplyResult blocked = apply.service().applyReplace(
                target.release(), fixture.plan());

        assertEditorialKnownFailure(
                blocked,
                LegalManifestStatus.BLOCKED,
                LegalManifestIssueCode.SOURCE_FINGERPRINT_MISMATCH);
        assertThat(editorialTableRows()).isEqualTo(rowsBefore);
        assertThat(editorialSequenceStates(owner)).isEqualTo(sequencesBefore);
    }

    @Test
    void manyToManyMappingIsRejectedBeforeTheDatabaseGate()
            throws Exception {
        ImportedRelease irrelevantTarget = importedRequirementOnlyTarget(
                "replace-invalid-scope-target-v1");
        ValidatedEditorialPlan unsupported = manyToManyScopePlan();
        Map<String, String> rowsBefore = editorialTableRows();
        Map<String, LegalManifestPersistenceITSupport.SequenceState> sequencesBefore =
                editorialSequenceStates(owner);

        try (LegalManifestPersistenceITSupport.EditorialLockHolder ignored =
                     LegalManifestPersistenceITSupport.holdEditorialLock(
                             Objects.requireNonNull(owner.getDataSource()))) {
            LegalEditorialApplyResult blocked = apply.service().applyReplace(
                    irrelevantTarget.release(), unsupported);
            assertEditorialKnownFailure(
                    blocked,
                    LegalManifestStatus.BLOCKED,
                    LegalManifestIssueCode.REPLACEMENT_MAPPING_INVALID);
        }
        assertThat(editorialTableRows()).isEqualTo(rowsBefore);
        assertThat(editorialSequenceStates(owner)).isEqualTo(sequencesBefore);
    }

    @Test
    void aForeignRelatedBatchBlocksWithoutHealingOrSequenceAdvances()
            throws Exception {
        ImportedRelease source = readyRelease("replace-foreign-batch-source-v1");
        ImportedRelease target = importedRequirementOnlyTarget(
                "replace-foreign-batch-target-v1");
        UUID foreignBatchId = stableUuid("foreign-related-batch");
        UUID sourceDocumentId = documents(source.publicationId()).getFirst().id();
        OffsetDateTime timestamp = Objects.requireNonNull(owner.queryForObject(
                "SELECT transaction_timestamp()",
                OffsetDateTime.class));
        withReplicaRole(owner, () -> {
            owner.update("""
                    INSERT INTO legal_documento_reemplazo_lotes
                        (id, estado_construccion, creado_en, sellado_en)
                    VALUES (?, 'SELLADO', ?, ?)
                    """, foreignBatchId, timestamp, timestamp);
            owner.update("""
                    INSERT INTO legal_documento_reemplazo_anteriores
                        (lote_id, documento_version_id)
                    VALUES (?, ?)
                    """, foreignBatchId, sourceDocumentId);
        });
        PlanFixture fixture = replacementPlan(source, target, "foreign-batch", null);
        Map<String, String> rowsBefore = editorialTableRows();
        Map<String, LegalManifestPersistenceITSupport.SequenceState> sequencesBefore =
                editorialSequenceStates(owner);

        LegalEditorialApplyResult blocked = apply.service().applyReplace(
                target.release(), fixture.plan());

        assertEditorialKnownFailure(
                blocked,
                LegalManifestStatus.BLOCKED,
                LegalManifestIssueCode.CURRENT_STATE_MISMATCH);
        assertThat(editorialTableRows()).isEqualTo(rowsBefore);
        assertThat(editorialSequenceStates(owner)).isEqualTo(sequencesBefore);
    }

    @Test
    void aPartialPostStateBlocksWithoutRepairingTheCorruption()
            throws Exception {
        ImportedRelease source = readyRelease("replace-partial-source-v1");
        ImportedRelease target = importedRequirementOnlyTarget("replace-partial-target-v1");
        PlanFixture fixture = replacementPlan(source, target, "partial-post-state", null);
        LegalEditorialApplyResult applied = apply.service().applyReplace(
                target.release(), fixture.plan());
        assertEditorialConfirmed(applied, LegalEditorialApplyResult.Outcome.APPLIED);
        withReplicaRole(owner, () -> assertThat(owner.update("""
                DELETE FROM legal_requisito_conjuntos_actuales
                 WHERE conjunto_id = (
                       SELECT conjunto_id
                         FROM legal_requisito_conjuntos_actuales
                        ORDER BY locale, contexto, audiencia
                        LIMIT 1
                 )
                """)).isOne());
        Map<String, String> rowsBefore = editorialTableRows();
        Map<String, LegalManifestPersistenceITSupport.SequenceState> sequencesBefore =
                editorialSequenceStates(owner);

        LegalEditorialApplyResult blocked = apply.service().applyReplace(
                target.release(), fixture.plan());

        assertBlocked(blocked,
                LegalManifestIssueCode.SOURCE_FINGERPRINT_MISMATCH,
                LegalManifestIssueCode.CURRENT_STATE_MISMATCH);
        assertThat(editorialTableRows()).isEqualTo(rowsBefore);
        assertThat(editorialSequenceStates(owner)).isEqualTo(sequencesBefore);
    }

    @Test
    void commitAcknowledgementLossReconcilesImmediatelyAndRetryIsSelectOnly()
            throws Exception {
        ImportedRelease source = readyRelease("replace-commit-unknown-source-v1");
        ImportedRelease target = importedRequirementOnlyTarget(
                "replace-commit-unknown-target-v1");
        PlanFixture fixture = replacementPlan(source, target, "commit-unknown", null);
        var ambiguousDataSource =
                new LegalManifestPersistenceITSupport.CommitAcknowledgementLostDataSource(
                        Objects.requireNonNull(owner.getDataSource()));
        LegalManifestPersistenceITSupport.ApplyHarness ambiguous = applyHarness(
                ambiguousDataSource,
                LegalDatabaseBudgets.production());

        LegalEditorialApplyResult firstAttempt = ambiguous.service().applyReplace(
                target.release(), fixture.plan());

        assertEditorialConfirmed(
                firstAttempt,
                LegalEditorialApplyResult.Outcome.ALREADY_APPLIED);
        assertThat(firstAttempt.operationType())
                .contains(LegalEditorialApplyReceipt.OperationType.REPLACE);
        assertThat(firstAttempt.targetPublicationUuid()).contains(target.publicationId());
        assertThat(firstAttempt.readinessAfter()).contains(LegalEditorialReadiness.READY);
        Instant reconciledAppliedAt = firstAttempt.appliedAt().orElseThrow();
        assertThat(ambiguousDataSource.armed()).isFalse();
        assertThat(ownerApply.readinessCore().evaluate(
                target.release(), databaseNow()).readiness())
                .isEqualTo(LegalEditorialReadiness.READY);
        Map<String, String> rowsBeforeReplay = editorialTableRows();
        Map<String, LegalManifestPersistenceITSupport.SequenceState> sequencesBeforeReplay =
                editorialSequenceStates(owner);

        LegalEditorialApplyResult replay = ambiguous.service().applyReplace(
                target.release(), fixture.plan());

        assertEditorialConfirmed(replay, LegalEditorialApplyResult.Outcome.ALREADY_APPLIED);
        assertThat(replay.appliedAt()).contains(reconciledAppliedAt);
        assertThat(editorialTableRows()).isEqualTo(rowsBeforeReplay);
        assertThat(editorialSequenceStates(owner)).isEqualTo(sequencesBeforeReplay);
    }

    @ParameterizedTest(name = "rollback tardío después de {0}")
    @EnumSource(LateFailure.class)
    void everyLateFailureRollsBackTheCompleteReplacement(LateFailure failure)
            throws Exception {
        String seed = failure.name().toLowerCase(java.util.Locale.ROOT);
        ImportedRelease source = readyRelease("replace-rollback-" + seed + "-source-v1");
        retireDocumentToGap(source.publicationId(), "aviso-clientes-taller");
        ImportedRelease target = importedMixedTarget(
                "replace-rollback-" + seed + "-target-v1");
        PlanFixture fixture = replacementPlan(
                source,
                target,
                "rollback-" + seed,
                null);
        InjectedApply injected = injectedApply(failure, target, fixture.plan());
        Map<String, String> rowsBeforeAttempt = editorialTableRows();

        LegalEditorialApplyResult result = injected.service().applyReplace(
                target.release(), fixture.plan());

        assertEditorialKnownFailure(
                result,
                LegalManifestStatus.ERROR,
                failure.issueCode());
        injected.assertTriggered().run();
        assertThat(editorialTableRows()).isEqualTo(rowsBeforeAttempt);
        assertThat(ownerApply.readinessCore().evaluate(
                target.release(), databaseNow()).readiness())
                .isEqualTo(LegalEditorialReadiness.NOT_READY);
    }

    private InjectedApply injectedApply(
            LateFailure failure,
            ImportedRelease target,
            ValidatedEditorialPlan plan) {
        if (failure == LateFailure.SEAL || failure == LateFailure.POINTERS) {
            FailAfterJdbcTemplate jdbc = new FailAfterJdbcTemplate(
                    Objects.requireNonNull(owner.getDataSource()),
                    failure);
            LegalEditorialPrivilegeVerifier privileges = mock(
                    LegalEditorialPrivilegeVerifier.class);
            when(privileges.usesJdbc(jdbc)).thenReturn(true);
            LegalManifestPersistenceITSupport.ApplyHarness harness = applyHarness(
                    jdbc,
                    LegalDatabaseBudgets.production(),
                    new LegalEditorialSchemaVerifier(
                            jdbc,
                            LegalV27EditorialInventory.DEFAULT_SCHEMA),
                    privileges);
            return new InjectedApply(
                    harness.service(),
                    () -> assertThat(jdbc.fired()).isTrue());
        }

        if (failure == LateFailure.VERIFIER) {
            AtomicBoolean fired = new AtomicBoolean();
            LegalEditorialMutationWriter sabotagedWriter = mock(
                    LegalEditorialMutationWriter.class);
            when(sabotagedWriter.usesJdbc(ownerApply.jdbc())).thenReturn(true);
            doAnswer(invocation -> {
                LegalEditorialExecutionPlan execution = invocation.getArgument(0);
                ownerApply.replacementWriter().write(execution);
                LegalEditorialExecutionPlan.ExpectedRequiredSetPointer pointer =
                        execution.expectedPostState().requiredSetPointers().getFirst();
                int deleted = ownerApply.jdbc().update("""
                        DELETE FROM legal_requisito_conjuntos_actuales
                         WHERE locale = ? AND contexto = ? AND audiencia = ?
                           AND conjunto_id = ?
                        """,
                        pointer.key().locale().getCodigo(),
                        pointer.key().context().name(),
                        pointer.key().audience().name(),
                        pointer.requiredSetId());
                assertThat(deleted).isOne();
                fired.set(true);
                return null;
            }).when(sabotagedWriter).write(any());
            return new InjectedApply(
                    applyService(
                            ownerApply.plannerCore(),
                            sabotagedWriter,
                            ownerApply.readinessCore()),
                    () -> assertThat(fired.get()).isTrue());
        }

        if (failure == LateFailure.CONSTRAINTS) {
            AtomicBoolean fired = new AtomicBoolean();
            ConstraintObservingJdbcTemplate jdbc = new ConstraintObservingJdbcTemplate(
                    Objects.requireNonNull(owner.getDataSource()));
            LegalEditorialPrivilegeVerifier privileges = mock(
                    LegalEditorialPrivilegeVerifier.class);
            when(privileges.usesJdbc(jdbc)).thenReturn(true);
            LegalManifestPersistenceITSupport.ApplyHarness harness = applyHarness(
                    jdbc,
                    LegalDatabaseBudgets.production(),
                    new LegalEditorialSchemaVerifier(
                            jdbc,
                            LegalV27EditorialInventory.DEFAULT_SCHEMA),
                    privileges);
            LegalEditorialPlannerCore sabotagedPlanner = mock(
                    LegalEditorialPlannerCore.class);
            when(sabotagedPlanner.usesJdbc(jdbc)).thenReturn(true);
            when(sabotagedPlanner.planReplace(any(), eq(plan), any())).thenAnswer(invocation -> {
                LegalEditorialPlanResult planned = harness.plannerCore().planReplace(
                        invocation.getArgument(0),
                        invocation.getArgument(1),
                        invocation.getArgument(2));
                fired.set(true);
                return LegalEditorialPlanResult.applicable(
                        withoutOneDirectAdditionSlot(
                                planned.executionPlan().orElseThrow()));
            });
            return new InjectedApply(
                    applyService(
                            harness,
                            sabotagedPlanner,
                            harness.replacementWriter(),
                            harness.readinessCore()),
                    () -> {
                        assertThat(fired.get()).isTrue();
                        assertThat(jdbc.constraintsAttempted()).isTrue();
                    });
        }

        AtomicBoolean fired = new AtomicBoolean();
        LegalEditorialReadinessCore sabotagedReadiness = mock(
                LegalEditorialReadinessCore.class);
        when(sabotagedReadiness.usesJdbc(ownerApply.jdbc())).thenReturn(true);
        when(sabotagedReadiness.evaluate(eq(target.release()), any())).thenAnswer(invocation -> {
            LegalEditorialReadinessResult actual = ownerApply.readinessCore().evaluate(
                    invocation.getArgument(0),
                    invocation.getArgument(1));
            assertThat(actual.readiness()).isEqualTo(LegalEditorialReadiness.READY);
            fired.set(true);
            return LegalEditorialReadinessResult.notReady(
                    actual.observation().orElseThrow(),
                    List.of(LegalManifestIssue.at(
                            LegalManifestIssueCode.CURRENT_STATE_MISMATCH,
                            "database/state")));
        });
        return new InjectedApply(
                applyService(
                        ownerApply.plannerCore(),
                        ownerApply.replacementWriter(),
                        sabotagedReadiness),
                () -> assertThat(fired.get()).isTrue());
    }

    private static LegalEditorialApplyService applyService(
            LegalEditorialPlannerCore planner,
            LegalEditorialMutationWriter replacementWriter,
            LegalEditorialReadinessCore readiness) {
        return applyService(ownerApply, planner, replacementWriter, readiness);
    }

    private static LegalEditorialApplyService applyService(
            LegalManifestPersistenceITSupport.ApplyHarness harness,
            LegalEditorialPlannerCore planner,
            LegalEditorialMutationWriter replacementWriter,
            LegalEditorialReadinessCore readiness) {
        return new LegalEditorialApplyService(
                harness.gate(),
                harness.jdbc(),
                planner,
                harness.promotionCore(),
                replacementWriter,
                harness.retirementWriter(),
                harness.postStateVerifier(),
                readiness,
                harness.replaceScopeGuard(),
                harness.commitReconciler(),
                new LegalEditorialFailureMapper(),
                harness.schemaVerifier(),
                harness.privilegeVerifier());
    }

    private static LegalEditorialExecutionPlan withoutOneDirectAdditionSlot(
            LegalEditorialExecutionPlan original) {
        LegalEditorialExecutionPlan.MutationCommands commands = original.mutationCommands();
        Set<UUID> addedDocumentIds = commands.documentTransitions().stream()
                .filter(transition ->
                        transition.previousState() == EstadoVersionLegal.BORRADOR
                                && transition.newState() == EstadoVersionLegal.PUBLICADA)
                .map(LegalEditorialExecutionPlan.DocumentTransition::documentVersionId)
                .collect(Collectors.toUnmodifiableSet());
        LegalEditorialExecutionPlan.ExpectedDocumentSlot omitted =
                commands.documentSlotInserts().stream()
                        .filter(slot -> addedDocumentIds.contains(slot.documentVersionId()))
                        .findFirst()
                        .orElseThrow();
        LegalEditorialExecutionPlan.ExpectedPostState expected = original.expectedPostState();
        LegalEditorialExecutionPlan.ExpectedPostState incompletePostState =
                new LegalEditorialExecutionPlan.ExpectedPostState(
                        expected.documentStates(),
                        expected.requirementStates(),
                        expected.documentTransitions(),
                        expected.requirementTransitions(),
                        expected.preexistingDocumentTransitions(),
                        expected.preexistingRequirementTransitions(),
                        expected.documentSlots().stream()
                                .filter(slot -> !slot.equals(omitted))
                                .toList(),
                        expected.requiredSetPointers(),
                        expected.preexistingReplacementBatches(),
                        expected.replacementBatches(),
                        expected.v27TriggerEffects());
        LegalEditorialExecutionPlan.MutationCommands incompleteCommands =
                new LegalEditorialExecutionPlan.MutationCommands(
                        commands.documentTransitions(),
                        commands.requirementTransitions(),
                        commands.documentSlotDeletes(),
                        commands.documentSlotInserts().stream()
                                .filter(slot -> !slot.equals(omitted))
                                .toList(),
                        commands.requiredSetPointerDeletes(),
                        commands.requiredSetPointerInserts(),
                        commands.replacementBatchesToCreateAndSeal());
        return new LegalEditorialExecutionPlan(
                original.operationType(),
                original.source(),
                original.target(),
                original.operationId(),
                original.planSha256(),
                original.observedAt(),
                original.expectedAppliedAt(),
                original.expectedReadinessAfter(),
                original.acknowledgeFailClosedGap(),
                original.changeRequired(),
                incompletePostState,
                incompleteCommands);
    }

    private ImportedRelease readyRelease(String externalId) throws Exception {
        ImportedRelease imported = importedRelease(externalId, (path, manifest) -> { });
        promoteToReady(owner, imported.publicationId());
        return imported;
    }

    private ValidatedEditorialPlan manyToManyScopePlan() throws Exception {
        Path fixturePath = Path.of(Objects.requireNonNull(
                LegalEditorialReplaceIT.class.getResource(
                        "/legal/editorial/replace-valid-v1/editorial-plan.json"),
                "validated many-to-one fixture").toURI());
        ObjectNode manyToMany = (ObjectNode) JSON.readTree(Files.readAllBytes(fixturePath));
        UUID operation = stableUuid("unsupported-many-to-many");
        manyToMany.put("operationId", operation.toString());
        ObjectNode batch = (ObjectNode) manyToMany.withArray(
                "documentReplacementBatches").get(0);
        ObjectNode secondSuccessor = batch.withArray("successors").addObject();
        secondSuccessor.put(
                "documentVersionId",
                stableUuid("unsupported-many-to-many-successor").toString());
        secondSuccessor.put("sha256", "a".repeat(64));
        return validatePlan(manyToMany, operation);
    }

    private static DocumentVersion retireDocumentToGap(
            UUID publicationId,
            String documentKey) {
        DocumentVersion current = documents(publicationId).stream()
                .filter(document -> documentKey.equals(document.key()))
                .findFirst()
                .orElseThrow();
        ownerApply.transaction().executeWithoutResult(status -> {
            owner.queryForObject(
                    "SELECT pg_catalog.pg_advisory_xact_lock("
                            + "pg_catalog.hashtextextended(?, 0))",
                    Object.class,
                    LegalManifestDatabaseGate.EDITORIAL_LOCK_NAME);
            OffsetDateTime occurredAt = Objects.requireNonNull(owner.queryForObject(
                    "SELECT transaction_timestamp()",
                    OffsetDateTime.class));
            int deletedPointers = owner.update("""
                    DELETE FROM legal_requisito_conjuntos_actuales actual
                     WHERE EXISTS (
                           SELECT 1
                             FROM legal_requisito_conjunto_miembros miembro
                             JOIN legal_requisito_documentos documento
                               ON documento.requisito_version_id =
                                  miembro.requisito_version_id
                            WHERE miembro.conjunto_id = actual.conjunto_id
                              AND documento.documento_version_id = ?
                     )
                    """, current.id());
            int deletedSlots = owner.update("""
                    DELETE FROM legal_documento_vigentes
                     WHERE documento_version_id = ?
                    """, current.id());
            int transitioned = owner.update("""
                    INSERT INTO legal_documento_transiciones
                        (documento_version_id, estado_anterior, estado_nuevo,
                         motivo, reemplazo_lote_id, ocurrido_en)
                    VALUES (?, 'VIGENTE', 'RETIRADA',
                            'Hueco fail-closed recuperable de prueba', NULL, ?)
                    """, current.id(), occurredAt);
            assertThat(deletedPointers).isPositive();
            assertThat(deletedSlots).isEqualTo(current.contexts().size());
            assertThat(transitioned).isOne();
            owner.execute("SET CONSTRAINTS ALL IMMEDIATE");
        });
        return documents(publicationId).stream()
                .filter(document -> document.id().equals(current.id()))
                .findFirst()
                .orElseThrow();
    }

    private ImportedRelease importedMixedTarget(String externalId) throws Exception {
        return importedRelease(externalId, (manifestPath, manifest) -> {
            document(manifest, "terminos").put("version", "replace-v2");
            document(manifest, "aviso-clientes-taller").put("version", "replace-v2");

            String formerClosureKey = "cierre-cuenta";
            String addedClosureKey = "cierre-cuenta-nueva-linea";
            String addedClosureSource = addedClosureKey + ".md";
            String addedClosureMarkdown = """
                    # cierre-cuenta-nueva-linea

                    Version alternativa revisada para OrdenFix.
                    """;
            Files.writeString(
                    manifestPath.getParent().resolve(addedClosureSource),
                    addedClosureMarkdown,
                    StandardCharsets.UTF_8);
            ObjectNode closure = document(manifest, formerClosureKey);
            closure.put("key", addedClosureKey);
            closure.put("source", addedClosureSource);
            closure.put("sha256", sha256(addedClosureMarkdown));

            manifest.withArray("requirements").forEach(node -> {
                ObjectNode requirement = (ObjectNode) node;
                ArrayNode documents = requirement.withArray("documents");
                boolean changed = false;
                for (int index = 0; index < documents.size(); index++) {
                    String key = documents.get(index).textValue();
                    if (formerClosureKey.equals(key)) {
                        documents.set(index, JSON.getNodeFactory().textNode(addedClosureKey));
                        changed = true;
                    }
                    if ("terminos".equals(key) || "aviso-clientes-taller".equals(key)) {
                        changed = true;
                    }
                }
                if (changed) {
                    requirement.put("version", "replace-v2");
                }
            });

            ObjectNode renamed = requirement(
                    manifest, "customer-credential-attestation");
            String statement = "Confirmo el requisito customer-credential-attestation-v2 de OrdenFix.";
            renamed.put("key", "customer-credential-attestation-v2");
            renamed.put("version", "replace-v2");
            renamed.put("statement", statement);
            renamed.put("statementSha256", sha256(statement));
        });
    }

    private ImportedRelease importedRequirementOnlyTarget(String externalId)
            throws Exception {
        return importedRelease(externalId, (manifestPath, manifest) -> {
            ObjectNode changed = requirement(manifest, "admin-registration");
            String statement = "Confirmo la revision replace-v2 de admin-registration.";
            changed.put("version", "replace-v2");
            changed.put("statement", statement);
            changed.put("statementSha256", sha256(statement));
        });
    }

    private ImportedRelease importedRelease(
            String externalId,
            LegalManifestPersistenceITSupport.ReleaseMutation mutation) throws Exception {
        ValidatedRelease release = copyRelease(
                temporaryDirectory,
                LegalEditorialReplaceIT.class,
                externalId,
                (manifestPath, manifest) -> {
                    manifest.withArray("documents").forEach(document ->
                            ((ObjectNode) document).put(
                                    "effectiveAt", "2026-01-01T00:00:00-03:00"));
                    mutation.apply(manifestPath, manifest);
                });
        UUID publicationId = importer.importService()
                .importManifest(release)
                .receipt()
                .orElseThrow()
                .publicationUuid();
        return new ImportedRelease(release, publicationId);
    }

    private PlanFixture replacementPlan(
            ImportedRelease source,
            ImportedRelease target,
            String operationSeed,
            String fingerprintOverride) throws Exception {
        List<DocumentVersion> sourceDocuments = documents(source.publicationId());
        List<DocumentVersion> targetDocuments = documents(target.publicationId());
        List<RequirementVersion> sourceRequirements = requirements(source.publicationId());
        List<RequirementVersion> targetRequirements = requirements(target.publicationId());
        List<DocumentVersion> activeSourceDocuments = sourceDocuments.stream()
                .filter(document -> "VIGENTE".equals(document.state()))
                .toList();
        List<RequirementVersion> activeSourceRequirements = sourceRequirements.stream()
                .filter(requirement -> "VIGENTE".equals(requirement.state()))
                .toList();
        Map<UUID, DocumentVersion> sourceDocumentsById = indexDocumentsById(
                activeSourceDocuments);
        Map<UUID, DocumentVersion> targetDocumentsById = indexDocumentsById(targetDocuments);
        Map<UUID, DocumentVersion> sourceDocumentsByLine = indexDocumentsByLine(
                activeSourceDocuments);
        Map<UUID, DocumentVersion> targetDocumentsByLine = indexDocumentsByLine(targetDocuments);
        Map<UUID, RequirementVersion> sourceRequirementsById = indexRequirementsById(
                activeSourceRequirements);
        Map<UUID, RequirementVersion> targetRequirementsById = indexRequirementsById(
                targetRequirements);
        Map<UUID, RequirementVersion> sourceRequirementsByLine = indexRequirementsByLine(
                activeSourceRequirements);
        Map<UUID, RequirementVersion> targetRequirementsByLine = indexRequirementsByLine(
                targetRequirements);

        String sourceExternalId = source.release().plan().manifest().publicationId();
        String fingerprint = fingerprintOverride != null
                ? fingerprintOverride
                : apply.readinessCore()
                        .observeState(sourceExternalId, databaseNow())
                        .editorialStateFingerprint();
        UUID operationId = stableUuid("replace:" + operationSeed);
        ObjectNode plan = emptyPlan(
                operationId,
                sourceExternalId,
                source.release().plan().manifestSha256(),
                fingerprint,
                target.release().plan().manifest().publicationId(),
                target.release().plan().manifestSha256());

        java.util.LinkedHashSet<UUID> documentAdditions = new java.util.LinkedHashSet<>();
        java.util.LinkedHashSet<UUID> documentReuses = new java.util.LinkedHashSet<>();
        java.util.LinkedHashSet<UUID> documentReplacements = new java.util.LinkedHashSet<>();
        java.util.LinkedHashSet<UUID> documentPredecessors = new java.util.LinkedHashSet<>();
        java.util.LinkedHashSet<UUID> documentRetirements = new java.util.LinkedHashSet<>();
        java.util.LinkedHashSet<UUID> requirementAdditions = new java.util.LinkedHashSet<>();
        java.util.LinkedHashSet<UUID> requirementReuses = new java.util.LinkedHashSet<>();
        java.util.LinkedHashSet<UUID> requirementReplacements = new java.util.LinkedHashSet<>();
        java.util.LinkedHashSet<UUID> requirementPredecessors = new java.util.LinkedHashSet<>();
        java.util.LinkedHashSet<UUID> requirementRetirements = new java.util.LinkedHashSet<>();
        java.util.LinkedHashSet<UUID> batchIds = new java.util.LinkedHashSet<>();

        for (DocumentVersion document : targetDocuments) {
            if (sourceDocumentsById.containsKey(document.id())) {
                addDocumentScoped(plan.withArray("documentReuses"), document);
                documentReuses.add(document.id());
            } else if (!sourceDocumentsByLine.containsKey(document.lineId())) {
                addDocumentScoped(plan.withArray("documentAdditions"), document);
                documentAdditions.add(document.id());
            } else {
                DocumentVersion predecessor = sourceDocumentsByLine.get(document.lineId());
                UUID batchId = stableUuid(
                        "batch:" + predecessor.id() + ":" + document.id());
                ObjectNode batch = plan.withArray("documentReplacementBatches").addObject();
                batch.put("replacementBatchId", batchId.toString());
                document.contexts().forEach(batch.putArray("contexts")::add);
                addDocumentRef(batch.putArray("predecessors"), predecessor);
                addDocumentRef(batch.putArray("successors"), document);
                documentPredecessors.add(predecessor.id());
                documentReplacements.add(document.id());
                batchIds.add(batchId);
            }
        }
        for (DocumentVersion document : activeSourceDocuments) {
            if (!targetDocumentsById.containsKey(document.id())
                    && !targetDocumentsByLine.containsKey(document.lineId())) {
                ObjectNode retirement = plan.withArray("documentRetirements").addObject();
                retirement.put("documentVersionId", document.id().toString());
                retirement.put("sha256", document.sha256());
                document.contexts().forEach(retirement.putArray("contexts")::add);
                retirement.put("reason", "Retiro por reemplazo integral de prueba");
                documentRetirements.add(document.id());
            }
        }

        for (RequirementVersion requirement : targetRequirements) {
            if (sourceRequirementsById.containsKey(requirement.id())) {
                addRequirementScoped(plan.withArray("requirementReuses"), requirement);
                requirementReuses.add(requirement.id());
            } else if (!sourceRequirementsByLine.containsKey(requirement.lineId())) {
                addRequirementScoped(plan.withArray("requirementAdditions"), requirement);
                requirementAdditions.add(requirement.id());
            } else {
                RequirementVersion predecessor = sourceRequirementsByLine.get(
                        requirement.lineId());
                ObjectNode replacement = plan.withArray("requirementReplacements").addObject();
                addRequirementRef(replacement.putObject("predecessor"), predecessor);
                addRequirementRef(replacement.putObject("successor"), requirement);
                replacement.put("context", requirement.context());
                requirement.audiences().forEach(replacement.putArray("audiences")::add);
                requirementPredecessors.add(predecessor.id());
                requirementReplacements.add(requirement.id());
            }
        }
        for (RequirementVersion requirement : activeSourceRequirements) {
            if (!targetRequirementsById.containsKey(requirement.id())
                    && !targetRequirementsByLine.containsKey(requirement.lineId())) {
                ObjectNode retirement = plan.withArray("requirementRetirements").addObject();
                retirement.put("requirementVersionId", requirement.id().toString());
                retirement.put("statementSha256", requirement.sha256());
                retirement.put("context", requirement.context());
                requirement.audiences().forEach(retirement.putArray("audiences")::add);
                retirement.put("reason", "Retiro de requisito por reemplazo de prueba");
                requirementRetirements.add(requirement.id());
            }
        }

        return new PlanFixture(
                validatePlan(plan, operationId),
                Set.copyOf(documentAdditions),
                Set.copyOf(documentReuses),
                Set.copyOf(documentPredecessors),
                Set.copyOf(documentReplacements),
                Set.copyOf(documentRetirements),
                Set.copyOf(requirementAdditions),
                Set.copyOf(requirementReuses),
                Set.copyOf(requirementPredecessors),
                Set.copyOf(requirementReplacements),
                Set.copyOf(requirementRetirements),
                Set.copyOf(batchIds));
    }

    private ValidatedEditorialPlan validatePlan(ObjectNode plan, UUID operationId)
            throws Exception {
        Path directory = temporaryDirectory.toRealPath().resolve("replace-plan-" + operationId);
        Files.createDirectories(directory);
        Path path = directory.resolve(ConfinedEditorialPlanReader.PLAN_FILENAME);
        Files.write(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(plan));
        LegalManifestValidation<ValidatedEditorialPlan> validation =
                new LegalEditorialPlanValidator().validate(path);
        assertThat(validation.status())
                .as("issues=%s", validation.issues())
                .isEqualTo(LegalManifestStatus.PASS);
        return validation.value().orElseThrow();
    }

    private static ObjectNode emptyPlan(
            UUID operationId,
            String sourceExternalId,
            String sourceManifestSha,
            String fingerprint,
            String targetExternalId,
            String targetManifestSha) {
        ObjectNode plan = JSON.createObjectNode();
        plan.put("schemaVersion", 1);
        plan.put("operationId", operationId.toString());
        plan.put("operationType", "REPLACE");
        plan.put("expectedCurrentPublicationId", sourceExternalId);
        plan.put("expectedCurrentManifestSha256", sourceManifestSha);
        plan.put("expectedEditorialStateFingerprint", fingerprint);
        plan.put("targetPublicationId", targetExternalId);
        plan.put("targetManifestSha256", targetManifestSha);
        plan.putArray("documentAdditions");
        plan.putArray("documentReuses");
        plan.putArray("documentReplacementBatches");
        plan.putArray("documentRetirements");
        plan.putArray("requirementAdditions");
        plan.putArray("requirementReuses");
        plan.putArray("requirementReplacements");
        plan.putArray("requirementRetirements");
        plan.put("expectedReadinessAfter", "READY");
        plan.put("acknowledgeFailClosedGap", false);
        return plan;
    }

    private static void assertFullMixedClassification(PlanFixture fixture) {
        assertThat(fixture.documentAdditions()).hasSize(2);
        assertThat(fixture.documentReuses()).hasSize(8);
        assertThat(fixture.documentReplacements()).hasSize(1);
        assertThat(fixture.documentRetirements()).hasSize(1);
        assertThat(fixture.requirementAdditions()).hasSize(1);
        assertThat(fixture.requirementReuses()).hasSize(1);
        assertThat(fixture.requirementReplacements()).hasSize(4);
        assertThat(fixture.requirementRetirements()).hasSize(1);
        assertThat(fixture.batchIds()).hasSize(1);
    }

    private static void assertExactMixedStates(PlanFixture fixture, UUID targetPublicationId) {
        assertStates("legal_documento_versiones", fixture.documentAdditions(), "VIGENTE");
        assertStates("legal_documento_versiones", fixture.documentReuses(), "VIGENTE");
        assertStates("legal_documento_versiones", fixture.documentPredecessors(), "REEMPLAZADA");
        assertStates("legal_documento_versiones", fixture.documentReplacements(), "VIGENTE");
        assertStates("legal_documento_versiones", fixture.documentRetirements(), "RETIRADA");
        assertStates("legal_requisito_versiones", fixture.requirementAdditions(), "VIGENTE");
        assertStates("legal_requisito_versiones", fixture.requirementReuses(), "VIGENTE");
        assertStates(
                "legal_requisito_versiones",
                fixture.requirementPredecessors(),
                "REEMPLAZADA");
        assertStates("legal_requisito_versiones", fixture.requirementReplacements(), "VIGENTE");
        assertStates("legal_requisito_versiones", fixture.requirementRetirements(), "RETIRADA");
        assertAllCurrentProjectionsBelongTo(targetPublicationId);
        assertThat(owner.queryForList("""
                SELECT id
                  FROM legal_documento_reemplazo_lotes
                 WHERE estado_construccion = 'SELLADO'
                   AND sellado_en IS NOT NULL
                 ORDER BY id::text
                """, UUID.class)).containsExactlyInAnyOrderElementsOf(fixture.batchIds());
    }

    private static void assertStates(String table, Set<UUID> ids, String expected) {
        if (ids.isEmpty()) {
            return;
        }
        for (UUID id : ids) {
            assertThat(owner.queryForObject(
                    "SELECT estado FROM " + table + " WHERE id = ?",
                    String.class,
                    id)).isEqualTo(expected);
        }
    }

    private static void assertBlocked(
            LegalEditorialApplyResult result,
            LegalManifestIssueCode... allowedCodes) {
        assertThat(result.status()).isEqualTo(LegalManifestStatus.BLOCKED);
        assertThat(result.persisted()).isFalse();
        assertThat(result.outcome()).isEqualTo(LegalEditorialApplyResult.Outcome.BLOCKED);
        assertThat(result.receipt()).isEmpty();
        assertThat(result.issues()).singleElement().satisfies(issue ->
                assertThat(issue.code()).isIn((Object[]) allowedCodes));
    }

    private static void assertAllCurrentProjectionsBelongTo(UUID targetPublicationId) {
        assertThat(owner.queryForObject("""
                SELECT count(*)
                  FROM legal_documento_vigentes
                 WHERE publicacion_id <> ?
                """, Long.class, targetPublicationId)).isZero();
        assertThat(owner.queryForObject("""
                SELECT count(*)
                  FROM legal_requisito_conjuntos_actuales
                 WHERE publicacion_id <> ?
                """, Long.class, targetPublicationId)).isZero();
        assertThat(owner.queryForObject(
                "SELECT count(*) FROM legal_documento_vigentes",
                Long.class)).isEqualTo(21L);
        assertThat(owner.queryForObject(
                "SELECT count(*) FROM legal_requisito_conjuntos_actuales",
                Long.class)).isEqualTo(8L);
        assertThat(requirements(targetPublicationId))
                .extracting(RequirementVersion::audiences)
                .containsExactlyInAnyOrder(
                        List.of("ADMIN_TITULAR"),
                        List.of("USER"),
                        List.of("ADMIN_TITULAR"),
                        List.of("ADMIN_TITULAR", "USER"),
                        List.of("ADMIN_TITULAR", "USER"),
                        List.of("ADMIN_TITULAR"));
    }

    private static Map<String, String> editorialTableRows() {
        return tableRows(new TreeSet<>(LegalV27EditorialInventory.EDITORIAL_TABLES));
    }

    private static Map<String, String> externalAcceptanceRows() {
        return tableRows(Set.of(
                "legal_aceptacion_lotes",
                "legal_aceptaciones",
                "legal_aceptacion_documentos",
                "legal_aceptacion_metadatos",
                "legal_aceptacion_metadatos_cifrados",
                "legal_idempotencia_resultados"));
    }

    private static Map<String, String> immutableOriginRows() {
        Map<String, String> rows = new LinkedHashMap<>();
        for (String table : List.of(
                "legal_publicaciones",
                "legal_documento_lineas",
                "legal_documento_contextos",
                "legal_publicacion_documentos",
                "legal_requisito_lineas",
                "legal_requisito_audiencias",
                "legal_requisito_documentos",
                "legal_publicacion_requisitos",
                "legal_requisito_conjuntos",
                "legal_requisito_conjunto_miembros")) {
            rows.put(table, tableRows(Set.of(table)).get(table));
        }
        rows.put("legal_documento_versiones/origin", jsonRows("""
                SELECT id, documento_linea_id, publicacion_intro_id, version,
                       lineage_ordinal, titulo, contenido_markdown, sha256,
                       vigente_desde, requires_reacceptance
                  FROM legal_documento_versiones
                """));
        rows.put("legal_requisito_versiones/origin", jsonRows("""
                SELECT id, requisito_linea_id, publicacion_intro_id, version,
                       lineage_ordinal, afirmacion, afirmacion_sha256,
                       requerido, requires_reacceptance
                  FROM legal_requisito_versiones
                """));
        return Map.copyOf(rows);
    }

    private static Map<String, String> tableRows(Iterable<String> tables) {
        Map<String, String> rows = new LinkedHashMap<>();
        for (String table : tables) {
            rows.put(table, jsonRows("SELECT * FROM " + quoteIdentifier(table)));
        }
        return Map.copyOf(rows);
    }

    private static String versionHistory(UUID documentVersionId) {
        return jsonRows("""
                SELECT 'version' AS kind, to_jsonb(version)::text AS value
                  FROM legal_documento_versiones version
                 WHERE id = '%s'::uuid
                UNION ALL
                SELECT 'transition' AS kind, to_jsonb(transition)::text AS value
                  FROM legal_documento_transiciones transition
                 WHERE documento_version_id = '%s'::uuid
                """.formatted(documentVersionId, documentVersionId));
    }

    private static String jsonRows(String selectSql) {
        return owner.queryForObject(
                "SELECT COALESCE(jsonb_agg(to_jsonb(snapshot) "
                        + "ORDER BY to_jsonb(snapshot)::text), '[]'::jsonb)::text "
                        + "FROM (" + selectSql + ") snapshot",
                String.class);
    }

    private static Instant databaseNow() {
        return Objects.requireNonNull(owner.queryForObject(
                "SELECT transaction_timestamp()",
                OffsetDateTime.class)).toInstant();
    }

    private static List<DocumentVersion> documents(UUID publicationId) {
        List<DocumentVersionBase> rows = owner.query("""
                SELECT dv.id, dv.documento_linea_id, dv.sha256, dv.estado, dl.clave
                  FROM legal_publicacion_documentos pd
                  JOIN legal_documento_versiones dv
                    ON dv.id = pd.documento_version_id
                  JOIN legal_documento_lineas dl
                    ON dl.id = dv.documento_linea_id
                 WHERE pd.publicacion_id = ?
                 ORDER BY pd.manifest_ordinal
                """, (resultSet, rowNumber) -> new DocumentVersionBase(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("documento_linea_id", UUID.class),
                resultSet.getString("sha256"),
                resultSet.getString("estado"),
                resultSet.getString("clave")), publicationId);
        return rows.stream().map(row -> new DocumentVersion(
                row.id(),
                row.lineId(),
                row.sha256(),
                row.state(),
                row.key(),
                owner.queryForList("""
                        SELECT contexto
                          FROM legal_documento_contextos
                         WHERE documento_version_id = ?
                         ORDER BY contexto
                        """, String.class, row.id()))).toList();
    }

    private static List<RequirementVersion> requirements(UUID publicationId) {
        List<RequirementVersionBase> rows = owner.query("""
                SELECT rv.id, rv.requisito_linea_id, rv.afirmacion_sha256,
                       rv.estado, rl.contexto
                  FROM legal_publicacion_requisitos pr
                  JOIN legal_requisito_versiones rv
                    ON rv.id = pr.requisito_version_id
                  JOIN legal_requisito_lineas rl
                    ON rl.id = rv.requisito_linea_id
                 WHERE pr.publicacion_id = ?
                 ORDER BY pr.manifest_ordinal
                """, (resultSet, rowNumber) -> new RequirementVersionBase(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("requisito_linea_id", UUID.class),
                resultSet.getString("afirmacion_sha256"),
                resultSet.getString("estado"),
                resultSet.getString("contexto")), publicationId);
        return rows.stream().map(row -> new RequirementVersion(
                row.id(),
                row.lineId(),
                row.sha256(),
                row.state(),
                row.context(),
                owner.queryForList("""
                        SELECT audiencia
                          FROM legal_requisito_audiencias
                         WHERE requisito_linea_id = ?
                         ORDER BY audiencia
                        """, String.class, row.lineId()))).toList();
    }

    private static Map<UUID, DocumentVersion> indexDocumentsById(
            List<DocumentVersion> values) {
        return values.stream().collect(Collectors.toMap(DocumentVersion::id, value -> value));
    }

    private static Map<UUID, DocumentVersion> indexDocumentsByLine(
            List<DocumentVersion> values) {
        return values.stream().collect(Collectors.toMap(DocumentVersion::lineId, value -> value));
    }

    private static Map<UUID, RequirementVersion> indexRequirementsById(
            List<RequirementVersion> values) {
        return values.stream().collect(Collectors.toMap(RequirementVersion::id, value -> value));
    }

    private static Map<UUID, RequirementVersion> indexRequirementsByLine(
            List<RequirementVersion> values) {
        return values.stream().collect(Collectors.toMap(
                RequirementVersion::lineId,
                value -> value));
    }

    private static void addDocumentScoped(ArrayNode target, DocumentVersion document) {
        ObjectNode item = target.addObject();
        item.put("documentVersionId", document.id().toString());
        item.put("sha256", document.sha256());
        document.contexts().forEach(item.putArray("contexts")::add);
    }

    private static void addDocumentRef(ArrayNode target, DocumentVersion document) {
        ObjectNode item = target.addObject();
        item.put("documentVersionId", document.id().toString());
        item.put("sha256", document.sha256());
    }

    private static void addRequirementScoped(ArrayNode target, RequirementVersion requirement) {
        ObjectNode item = target.addObject();
        addRequirementRef(item, requirement);
        item.put("context", requirement.context());
        requirement.audiences().forEach(item.putArray("audiences")::add);
    }

    private static void addRequirementRef(ObjectNode target, RequirementVersion requirement) {
        target.put("requirementVersionId", requirement.id().toString());
        target.put("statementSha256", requirement.sha256());
    }

    private static ObjectNode document(ObjectNode manifest, String key) {
        return (ObjectNode) java.util.stream.StreamSupport.stream(
                        manifest.withArray("documents").spliterator(), false)
                .filter(candidate -> key.equals(candidate.path("key").textValue()))
                .findFirst()
                .orElseThrow();
    }

    private static ObjectNode requirement(ObjectNode manifest, String key) {
        return (ObjectNode) java.util.stream.StreamSupport.stream(
                        manifest.withArray("requirements").spliterator(), false)
                .filter(candidate -> key.equals(candidate.path("key").textValue()))
                .findFirst()
                .orElseThrow();
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static UUID stableUuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String quoteIdentifier(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    private enum LateFailure {
        SEAL(LegalManifestIssueCode.EDITORIAL_OBSERVATION_FAILED),
        POINTERS(LegalManifestIssueCode.EDITORIAL_OBSERVATION_FAILED),
        VERIFIER(LegalManifestIssueCode.POSTCONDITION_NOT_READY),
        CONSTRAINTS(LegalManifestIssueCode.EDITORIAL_OBSERVATION_FAILED),
        READINESS(LegalManifestIssueCode.POSTCONDITION_NOT_READY);

        private final LegalManifestIssueCode issueCode;

        LateFailure(LegalManifestIssueCode issueCode) {
            this.issueCode = issueCode;
        }

        LegalManifestIssueCode issueCode() {
            return issueCode;
        }
    }

    private static final class FailAfterJdbcTemplate extends JdbcTemplate {

        private final LateFailure failure;
        private final AtomicBoolean fired = new AtomicBoolean();

        private FailAfterJdbcTemplate(DataSource dataSource, LateFailure failure) {
            super(dataSource);
            this.failure = failure;
        }

        @Override
        public int update(String sql, Object... args) {
            int count = super.update(sql, args);
            if (failure == LateFailure.SEAL
                    && normalized(sql).contains(
                            "update legal_documento_reemplazo_lotes set estado_construccion")) {
                failOnce("fallo después del sello");
            }
            return count;
        }

        @Override
        public int[] batchUpdate(String sql, List<Object[]> batchArgs) {
            int[] counts = super.batchUpdate(sql, batchArgs);
            if (failure == LateFailure.POINTERS
                    && normalized(sql).contains(
                            "insert into legal_requisito_conjuntos_actuales")) {
                failOnce("fallo después de los punteros");
            }
            return counts;
        }

        boolean fired() {
            return fired.get();
        }

        private void failOnce(String message) {
            if (fired.compareAndSet(false, true)) {
                throw new IllegalStateException(message);
            }
        }

        private static String normalized(String sql) {
            return sql.toLowerCase(java.util.Locale.ROOT)
                    .replaceAll("\\s+", " ")
                    .trim();
        }
    }

    private static final class ConstraintObservingJdbcTemplate extends JdbcTemplate {

        private final AtomicBoolean constraintsAttempted = new AtomicBoolean();

        private ConstraintObservingJdbcTemplate(DataSource dataSource) {
            super(dataSource);
        }

        @Override
        public void execute(String sql) {
            if (normalized(sql).equals("set constraints all immediate")) {
                constraintsAttempted.set(true);
            }
            super.execute(sql);
        }

        boolean constraintsAttempted() {
            return constraintsAttempted.get();
        }

        private static String normalized(String sql) {
            return sql.toLowerCase(java.util.Locale.ROOT)
                    .replaceAll("\\s+", " ")
                    .trim();
        }
    }

    private record ImportedRelease(ValidatedRelease release, UUID publicationId) { }

    private record InjectedApply(
            LegalEditorialApplyService service,
            Runnable assertTriggered) { }

    private record DocumentVersionBase(
            UUID id,
            UUID lineId,
            String sha256,
            String state,
            String key) { }

    private record DocumentVersion(
            UUID id,
            UUID lineId,
            String sha256,
            String state,
            String key,
            List<String> contexts) { }

    private record RequirementVersionBase(
            UUID id,
            UUID lineId,
            String sha256,
            String state,
            String context) { }

    private record RequirementVersion(
            UUID id,
            UUID lineId,
            String sha256,
            String state,
            String context,
            List<String> audiences) { }

    private record PlanFixture(
            ValidatedEditorialPlan plan,
            Set<UUID> documentAdditions,
            Set<UUID> documentReuses,
            Set<UUID> documentPredecessors,
            Set<UUID> documentReplacements,
            Set<UUID> documentRetirements,
            Set<UUID> requirementAdditions,
            Set<UUID> requirementReuses,
            Set<UUID> requirementPredecessors,
            Set<UUID> requirementReplacements,
            Set<UUID> requirementRetirements,
            Set<UUID> batchIds
    ) { }
}
