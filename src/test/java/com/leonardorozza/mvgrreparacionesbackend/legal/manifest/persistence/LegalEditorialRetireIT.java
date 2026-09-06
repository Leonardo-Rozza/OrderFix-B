package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.ConfinedEditorialPlanReader;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialPlanValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialPlanValidator.ValidatedEditorialPlan;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialReadiness;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidation;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateProjection.ScopeRevision;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateProvenance;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateProvenance.ScopeOrigin;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateProvenanceCalculator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateRevisionCalculator;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EsquemaRevisionLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PerfilAgregadoLegal;
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
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.editorialTableCounts;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.harness;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.migrateLatest;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.promoteToReady;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.restrictedApplyHarness;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.withReplicaRole;
import static org.assertj.core.api.Assertions.assertThat;

/** PostgreSQL accreditation for fresh fail-closed RETIRE applies. */
@Testcontainers
class LegalEditorialRetireIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String EDITORIAL_ROLE = "ordenfix_legal_editorial_retire_it";
    private static final String EDITORIAL_PASSWORD = "retire-it-only";
    private static final String DOCUMENT_REASON =
            "Retiro editorial documental explícito de prueba";
    private static final String REQUIREMENT_REASON =
            "Retiro editorial de requisito explícito de prueba";
    private static final String CONTINUED_USE_STATEMENT =
            "Confirmo el requisito continued-use de OrdenFix.";
    private static final String CONTINUED_USE_STATEMENT_SHA256 =
            "691f6e8465d629ca3e5f184539add016db43ee5be37011b4f5a1470b33ac780d";
    private static final Set<String> ACCEPTANCE_AND_IDEMPOTENCY_TABLES = Set.of(
            "legal_requisito_agregados",
            "legal_requisito_agregado_scopes",
            "legal_aceptacion_lotes",
            "legal_aceptaciones",
            "legal_aceptacion_documentos",
            "legal_aceptacion_metadatos",
            "legal_aceptacion_metadatos_cifrados",
            "legal_idempotencia_resultados");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_editorial_retire")
            .withUsername("ordenfix")
            .withPassword("ordenfix");

    private static JdbcTemplate owner;
    private static LegalManifestPersistenceITSupport.Harness importer;
    private static LegalManifestPersistenceITSupport.ApplyHarness apply;
    private static LegalEditorialPrivilegeVerifier restrictedPrivileges;

    @TempDir
    private Path temporaryDirectory;

    @BeforeAll
    static void migrateProvisionAndAssemble() {
        migrateLatest(POSTGRES);
        DataSource ownerDataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        owner = new JdbcTemplate(ownerDataSource);
        importer = harness(ownerDataSource, LegalDatabaseBudgets.production());

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
        owner.execute("""
                TRUNCATE TABLE
                    legal_idempotencia_resultados,
                    legal_aceptacion_metadatos_cifrados,
                    legal_aceptacion_metadatos,
                    legal_aceptacion_documentos,
                    legal_aceptaciones,
                    legal_aceptacion_lotes,
                    legal_requisito_agregado_scopes,
                    legal_requisito_agregados
                RESTART IDENTITY CASCADE
                """);
        cleanLegalState(owner);
        restrictedPrivileges.verify();
        assertRestrictedSession();
    }

    @Test
    void documentOnlyRetirementCommitsExactNotReadyStateUnderRestrictedRole()
            throws Exception {
        ImportedRelease current = readyRelease("retire-document-only-v1");
        DocumentVersion retired = document(
                current.publicationId(),
                "aviso-clientes-taller");
        assertThat(retired.contexts())
                .containsExactly("ATESTACION_CREDENCIALES", "ATESTACION_FOTOS");

        AppliedRetirement applied = applyFreshRetirement(
                current,
                List.of(retired),
                List.of(),
                "document-only",
                0);

        assertThat(applied.documentAffectedPointers()).hasSize(4);
        assertThat(applied.requirementAffectedPointers()).isEmpty();
        assertThat(applied.affectedPointers())
                .isEqualTo(applied.documentAffectedPointers());
        assertThat(applied.affectedSlotCount()).isEqualTo(2);
    }

    @Test
    void requirementOnlyRetirementCommitsExactNotReadyStateUnderRestrictedRole()
            throws Exception {
        ImportedRelease current = readyRelease("retire-requirement-only-v1");
        RequirementVersion retired = requirement(
                current.publicationId(),
                "customer-photo-attestation");
        assertThat(retired.audiences())
                .containsExactly("ADMIN_TITULAR", "USER");

        AppliedRetirement applied = applyFreshRetirement(
                current,
                List.of(),
                List.of(retired),
                "requirement-only",
                0);

        assertThat(applied.documentAffectedPointers()).isEmpty();
        assertThat(applied.requirementAffectedPointers()).hasSize(2);
        assertThat(applied.affectedPointers())
                .isEqualTo(applied.requirementAffectedPointers());
        assertThat(applied.affectedSlotCount()).isZero();
        assertThat(applied.after().documentSlots())
                .isEqualTo(applied.before().documentSlots());
    }

    @Test
    void mixedRetirementDeduplicatesOverlappingPointersUnderRestrictedRole()
            throws Exception {
        ImportedRelease current = readyRelease("retire-mixed-v1");
        DocumentVersion retiredDocument = document(
                current.publicationId(),
                "aviso-clientes-taller");
        RequirementVersion retiredRequirement = requirement(
                current.publicationId(),
                "customer-photo-attestation");

        AppliedRetirement applied = applyFreshRetirement(
                current,
                List.of(retiredDocument),
                List.of(retiredRequirement),
                "mixed-overlap",
                0);

        Set<PointerKey> overlap = new LinkedHashSet<>(
                applied.documentAffectedPointers());
        overlap.retainAll(applied.requirementAffectedPointers());
        assertThat(applied.documentAffectedPointers()).hasSize(4);
        assertThat(applied.requirementAffectedPointers()).hasSize(2);
        assertThat(overlap).hasSize(2);
        assertThat(applied.affectedPointers()).hasSize(4);
        assertThat(applied.affectedPointers().size())
                .isLessThan(applied.documentAffectedPointers().size()
                        + applied.requirementAffectedPointers().size());
        assertThat(applied.before().requiredSetPointers().size()
                - applied.after().requiredSetPointers().size())
                .isEqualTo(applied.affectedPointers().size());
        assertThat(applied.affectedSlotCount()).isEqualTo(2);
    }

    @Test
    void retirementPreservesOneRealHistoricalReplacementBatchUnderRestrictedRole()
            throws Exception {
        ImportedRelease source = readyRelease("retire-after-replace-source-v1");
        ImportedRelease current = replaceTermsUnderRestrictedRole(
                source,
                "retire-after-replace-target-v1");
        RequirementVersion retired = requirement(
                current.publicationId(),
                "account-closure");

        AppliedRetirement applied = applyFreshRetirement(
                current,
                List.of(),
                List.of(retired),
                "after-real-replace",
                1);

        assertThat(applied.documentAffectedPointers()).isEmpty();
        assertThat(applied.requirementAffectedPointers()).hasSize(1);
        assertThat(applied.affectedPointers()).hasSize(1);
        assertThat(applied.affectedSlotCount()).isZero();
        assertThat(applied.after().replacementHistoryRows())
                .isEqualTo(applied.before().replacementHistoryRows());
    }

    @Test
    void exactPostStateReplaysAcrossDifferentOperationAndPlanShaWithoutDml()
            throws Exception {
        ImportedRelease current = readyRelease("retire-replay-v1");
        DocumentVersion retiredDocument = document(
                current.publicationId(),
                "aviso-clientes-taller");
        RequirementVersion retiredRequirement = requirement(
                current.publicationId(),
                "customer-photo-attestation");
        ValidatedEditorialPlan firstPlan = retirementPlan(
                current,
                List.of(retiredDocument),
                List.of(retiredRequirement),
                "replay-operation-one");
        ValidatedEditorialPlan secondPlan = retirementPlan(
                current,
                List.of(retiredDocument),
                List.of(retiredRequirement),
                "replay-operation-two");
        assertThat(secondPlan.operationId()).isNotEqualTo(firstPlan.operationId());
        assertThat(secondPlan.editorialPlanSha256())
                .isNotEqualTo(firstPlan.editorialPlanSha256());

        LegalEditorialApplyResult first = apply.service().applyRetire(
                current.release(), firstPlan);
        assertEditorialConfirmed(first, LegalEditorialApplyResult.Outcome.APPLIED);
        Map<String, String> rowsAfterApply = editorialTableRows();
        Map<String, LegalManifestPersistenceITSupport.SequenceState> sequencesAfterApply =
                editorialSequenceStates(owner);

        LegalEditorialApplyResult exactReplay = apply.service().applyRetire(
                current.release(), firstPlan);
        assertEditorialConfirmed(
                exactReplay,
                LegalEditorialApplyResult.Outcome.ALREADY_APPLIED);
        assertThat(exactReplay.receipt()).isEqualTo(first.receipt());
        assertThat(exactReplay.appliedAt()).isEqualTo(first.appliedAt());
        assertThat(editorialTableRows()).isEqualTo(rowsAfterApply);
        assertThat(editorialSequenceStates(owner)).isEqualTo(sequencesAfterApply);

        LegalEditorialApplyResult foreignIdentityReplay = apply.service().applyRetire(
                current.release(), secondPlan);

        assertEditorialConfirmed(
                foreignIdentityReplay,
                LegalEditorialApplyResult.Outcome.ALREADY_APPLIED);
        assertThat(foreignIdentityReplay.receipt()).isEqualTo(first.receipt());
        assertThat(foreignIdentityReplay.appliedAt()).isEqualTo(first.appliedAt());
        assertThat(editorialTableRows()).isEqualTo(rowsAfterApply);
        assertThat(editorialSequenceStates(owner)).isEqualTo(sequencesAfterApply);
    }

    @Test
    void aDifferentPredictedPostStateBlocksBeforeDmlAfterRetirement()
            throws Exception {
        ImportedRelease current = readyRelease("retire-terminal-alternative-v1");
        DocumentVersion retiredDocument = document(
                current.publicationId(),
                "aviso-clientes-taller");
        RequirementVersion retiredRequirement = requirement(
                current.publicationId(),
                "customer-photo-attestation");
        RequirementVersion additionalRetirement = requirement(
                current.publicationId(),
                "account-closure");
        ValidatedEditorialPlan appliedPlan = retirementPlan(
                current,
                List.of(retiredDocument),
                List.of(retiredRequirement),
                "terminal-applied");
        ValidatedEditorialPlan alternativePlan = retirementPlan(
                current,
                List.of(retiredDocument),
                List.of(retiredRequirement, additionalRetirement),
                "terminal-alternative");

        LegalEditorialApplyResult first = apply.service().applyRetire(
                current.release(), appliedPlan);
        assertEditorialConfirmed(first, LegalEditorialApplyResult.Outcome.APPLIED);
        Map<String, String> rowsBeforeAlternative = editorialTableRows();
        Map<String, LegalManifestPersistenceITSupport.SequenceState>
                sequencesBeforeAlternative = editorialSequenceStates(owner);

        LegalEditorialApplyResult blocked = apply.service().applyRetire(
                current.release(), alternativePlan);

        assertEditorialKnownFailure(
                blocked,
                LegalManifestStatus.BLOCKED,
                LegalManifestIssueCode.SOURCE_FINGERPRINT_MISMATCH);
        assertThat(editorialTableRows()).isEqualTo(rowsBeforeAlternative);
        assertThat(editorialSequenceStates(owner)).isEqualTo(sequencesBeforeAlternative);
    }

    @Test
    void wrongFingerprintBlocksReadyStateWithoutRowsOrSequenceAdvances()
            throws Exception {
        ImportedRelease current = readyRelease("retire-wrong-fingerprint-v1");
        DocumentVersion retired = document(
                current.publicationId(),
                "aviso-clientes-taller");
        ValidatedEditorialPlan plan = retirementPlan(
                current,
                List.of(retired),
                List.of(),
                "wrong-fingerprint",
                "sha256:" + "0".repeat(64));
        assertThat(apply.readinessCore().evaluate(
                current.release(),
                databaseNow(apply.jdbc())).readiness())
                .isEqualTo(LegalEditorialReadiness.READY);
        Map<String, String> rowsBefore = editorialTableRows();
        Map<String, LegalManifestPersistenceITSupport.SequenceState> sequencesBefore =
                editorialSequenceStates(owner);
        assertThat(rowsBefore).hasSize(19);
        assertThat(sequencesBefore).hasSize(10);

        LegalEditorialApplyResult blocked = apply.service().applyRetire(
                current.release(), plan);

        assertSourceFingerprintBlocked(blocked);
        assertThat(editorialTableRows()).isEqualTo(rowsBefore);
        assertThat(editorialSequenceStates(owner)).isEqualTo(sequencesBefore);
    }

    @ParameterizedTest(name = "corrupcion de postestado RETIRE {0}")
    @EnumSource(RetirementPostStateCorruption.class)
    void everyExtraOrMissingPostStateCorruptionBlocksWithoutHealing(
            RetirementPostStateCorruption corruption) throws Exception {
        String seed = corruption.name().toLowerCase(java.util.Locale.ROOT);
        ImportedRelease source = readyRelease(
                "ret-cor-" + seed + "-v1");
        ImportedRelease current = replaceTermsUnderRestrictedRole(
                source,
                "ret-cor-" + seed + "-v2");
        UUID offScopeBatchMember = corruption == RetirementPostStateCorruption.BATCH_MEMBER_EXTRA
                ? offScopeDraftTermsDocument("ret-cor-" + seed + "-aux-v1")
                : null;
        DocumentVersion retiredDocument = document(
                current.publicationId(),
                "aviso-clientes-taller");
        RequirementVersion retiredRequirement = requirement(
                current.publicationId(),
                "customer-photo-attestation");
        AppliedRetirement applied = applyFreshRetirement(
                current,
                List.of(retiredDocument),
                List.of(retiredRequirement),
                "corruption-" + seed,
                1);
        Map<String, String> rowsBeforeCorruption = editorialTableRows();
        Map<String, LegalManifestPersistenceITSupport.SequenceState>
                sequencesBeforeCorruption = editorialSequenceStates(owner);
        assertThat(rowsBeforeCorruption).hasSize(19);
        assertThat(sequencesBeforeCorruption).hasSize(10);

        seedRetirementCorruption(corruption, current, applied, offScopeBatchMember);

        Map<String, String> corruptedRows = editorialTableRows();
        Map<String, LegalManifestPersistenceITSupport.SequenceState> corruptedSequences =
                editorialSequenceStates(owner);
        assertThat(corruptedRows).isNotEqualTo(rowsBeforeCorruption);
        assertThat(corruptedRows).hasSize(19);
        assertThat(corruptedSequences).hasSize(10);
        assertThat(corruptedSequences).isEqualTo(sequencesBeforeCorruption);

        LegalEditorialApplyResult blocked = apply.service().applyRetire(
                current.release(), applied.plan());

        assertSourceFingerprintBlocked(blocked);
        assertThat(editorialTableRows()).isEqualTo(corruptedRows);
        assertThat(editorialSequenceStates(owner)).isEqualTo(corruptedSequences);
        assertRestrictedSession();
        restrictedPrivileges.verify();
    }

    @Test
    void survivingPointerUpdatedAtDriftIsAnExplicitReplayKnowledgeBoundary()
            throws Exception {
        ImportedRelease current = readyRelease("retire-pointer-updated-at-boundary-v1");
        RequirementVersion retired = requirement(
                current.publicationId(),
                "account-closure");
        AppliedRetirement applied = applyFreshRetirement(
                current,
                List.of(),
                List.of(retired),
                "pointer-updated-at-boundary",
                0);
        RequiredSetPointer survivor = applied.after().requiredSetPointers().values().stream()
                .findFirst()
                .orElseThrow();
        Instant driftedAt = survivor.updatedAt().plusSeconds(1);
        Map<String, String> rowsBeforeDrift = editorialTableRows();
        Map<String, LegalManifestPersistenceITSupport.SequenceState> sequencesBeforeDrift =
                editorialSequenceStates(owner);

        withReplicaRole(owner, () -> assertThat(owner.update("""
                UPDATE legal_requisito_conjuntos_actuales
                   SET actualizado_en = ?
                 WHERE locale = ?
                   AND contexto = ?
                   AND audiencia = ?
                """,
                OffsetDateTime.ofInstant(driftedAt, java.time.ZoneOffset.UTC),
                survivor.key().locale(),
                survivor.key().context(),
                survivor.key().audience())).isOne());

        Map<PointerKey, RequiredSetPointer> expectedPointers = new LinkedHashMap<>(
                applied.after().requiredSetPointers());
        expectedPointers.put(survivor.key(), new RequiredSetPointer(
                survivor.key(),
                survivor.requiredSetId(),
                survivor.publicationId(),
                survivor.revision(),
                driftedAt,
                survivor.memberRequirementIds(),
                survivor.referencedDocumentIds()));
        assertThat(requiredSetPointers()).isEqualTo(expectedPointers);
        Map<String, String> driftedRows = editorialTableRows();
        Map<String, LegalManifestPersistenceITSupport.SequenceState> driftedSequences =
                editorialSequenceStates(owner);
        assertThat(rowsChangedBetween(rowsBeforeDrift, driftedRows))
                .containsExactly("legal_requisito_conjuntos_actuales");
        assertThat(driftedSequences).isEqualTo(sequencesBeforeDrift);

        LegalEditorialApplyResult replay = apply.service().applyRetire(
                current.release(), applied.plan());

        assertEditorialConfirmed(
                replay,
                LegalEditorialApplyResult.Outcome.ALREADY_APPLIED);
        assertThat(replay.receipt()).contains(applied.receipt());
        assertThat(replay.appliedAt()).contains(applied.receipt().appliedAt());
        assertThat(requiredSetPointers()).isEqualTo(expectedPointers);
        assertThat(editorialTableRows()).isEqualTo(driftedRows);
        assertThat(editorialSequenceStates(owner)).isEqualTo(driftedSequences);
    }

    @Test
    void failureAfterLastMixedRetirementBatchRollsBackAllRowsExactly()
            throws Exception {
        ImportedRelease source = readyRelease("retire-last-dml-rollback-source-v1");
        ImportedRelease current = replaceTermsUnderRestrictedRole(
                source,
                "retire-last-dml-rollback-v2");
        DocumentVersion retiredDocument = document(
                current.publicationId(),
                "aviso-clientes-taller");
        RequirementVersion retiredRequirement = requirement(
                current.publicationId(),
                "customer-photo-attestation");
        ValidatedEditorialPlan plan = retirementPlan(
                current,
                List.of(retiredDocument),
                List.of(retiredRequirement),
                "last-dml-rollback");
        Map<String, String> rowsBeforeAttempt = editorialTableRows();
        assertThat(rowsBeforeAttempt).hasSize(19);
        rowsBeforeAttempt.forEach((table, rows) ->
                assertThat(rows).as(table).isNotEqualTo("[]"));
        Map<String, String> replacementBeforeAttempt = replacementHistoryRows();
        replacementBeforeAttempt.forEach((table, rows) ->
                assertThat(rows).as(table).isNotEqualTo("[]"));
        Map<String, LegalManifestPersistenceITSupport.SequenceState> sequencesBeforeAttempt =
                editorialSequenceStates(owner);
        FailAfterLastRetirementBatchJdbcTemplate injectedJdbc =
                new FailAfterLastRetirementBatchJdbcTemplate(apply.dataSource());
        LegalEditorialSchemaVerifier schemaVerifier = new LegalEditorialSchemaVerifier(
                injectedJdbc,
                LegalV27EditorialInventory.DEFAULT_SCHEMA);
        LegalEditorialPrivilegeVerifier privilegeVerifier =
                new LegalEditorialPrivilegeVerifier(
                        injectedJdbc,
                        EDITORIAL_ROLE,
                        LegalV27EditorialInventory.DEFAULT_SCHEMA);
        LegalManifestPersistenceITSupport.ApplyHarness injected = applyHarness(
                injectedJdbc,
                LegalDatabaseBudgets.production(),
                schemaVerifier,
                privilegeVerifier);
        assertThat(injected.jdbc().queryForObject("SELECT current_user", String.class))
                .isEqualTo(EDITORIAL_ROLE);

        LegalEditorialApplyResult result = injected.service().applyRetire(
                current.release(), plan);

        assertEditorialKnownFailure(
                result,
                LegalManifestStatus.ERROR,
                LegalManifestIssueCode.EDITORIAL_OBSERVATION_FAILED);
        assertThat(injectedJdbc.fired()).isTrue();
        assertThat(injectedJdbc.dmlOrder()).containsExactly(
                "required-set-pointer:delete",
                "document-slot:delete",
                "requirement-transition:insert",
                "document-transition:insert");
        assertThat(editorialTableRows()).isEqualTo(rowsBeforeAttempt);
        assertThat(replacementHistoryRows()).isEqualTo(replacementBeforeAttempt);
        assertExactSequenceDelta(
                sequencesBeforeAttempt,
                editorialSequenceStates(owner),
                1,
                1);
        assertThat(apply.readinessCore().evaluate(
                current.release(),
                databaseNow(apply.jdbc())).readiness())
                .isEqualTo(LegalEditorialReadiness.READY);
    }

    @Test
    void restrictedCommitAcknowledgementLossReconcilesAndRetryRemainsSelectOnly()
            throws Exception {
        ImportedRelease current = readyRelease("retire-commit-unknown-v1");
        DocumentVersion retiredDocument = document(
                current.publicationId(),
                "aviso-clientes-taller");
        RequirementVersion retiredRequirement = requirement(
                current.publicationId(),
                "customer-photo-attestation");
        ValidatedEditorialPlan plan = retirementPlan(
                current,
                List.of(retiredDocument),
                List.of(retiredRequirement),
                "commit-unknown");
        var ambiguousDataSource =
                new LegalManifestPersistenceITSupport.CommitAcknowledgementLostDataSource(
                        apply.dataSource());
        LegalManifestPersistenceITSupport.ApplyHarness ambiguous = restrictedApplyHarness(
                ambiguousDataSource,
                LegalDatabaseBudgets.production(),
                EDITORIAL_ROLE);
        assertThat(ambiguous.jdbc().queryForObject("SELECT current_user", String.class))
                .isEqualTo(EDITORIAL_ROLE);

        LegalEditorialApplyResult firstAttempt = ambiguous.service().applyRetire(
                current.release(), plan);

        assertEditorialConfirmed(
                firstAttempt,
                LegalEditorialApplyResult.Outcome.ALREADY_APPLIED);
        assertThat(firstAttempt.operationType())
                .contains(LegalEditorialApplyReceipt.OperationType.RETIRE);
        assertThat(firstAttempt.targetPublicationUuid()).contains(current.publicationId());
        assertThat(firstAttempt.readinessAfter())
                .contains(LegalEditorialReadiness.NOT_READY);
        assertThat(firstAttempt.omittedIssueCount()).isZero();
        assertThat(ambiguousDataSource.armed()).isFalse();
        assertThat(apply.readinessCore().evaluate(
                current.release(),
                databaseNow(apply.jdbc())).readiness())
                .isEqualTo(LegalEditorialReadiness.NOT_READY);
        Instant committedAppliedAt = documentHistories(List.of(retiredDocument))
                .get(retiredDocument.id())
                .getLast()
                .occurredAt();
        assertThat(firstAttempt.appliedAt()).contains(committedAppliedAt);
        assertThat(firstAttempt.receipt()).get()
                .extracting(LegalEditorialApplyReceipt::appliedAt)
                .isEqualTo(committedAppliedAt);
        Map<String, String> rowsBeforeReplay = editorialTableRows();
        Map<String, LegalManifestPersistenceITSupport.SequenceState> sequencesBeforeReplay =
                editorialSequenceStates(owner);

        LegalEditorialApplyResult replay = ambiguous.service().applyRetire(
                current.release(), plan);

        assertEditorialConfirmed(
                replay,
                LegalEditorialApplyResult.Outcome.ALREADY_APPLIED);
        assertThat(replay.operationType())
                .contains(LegalEditorialApplyReceipt.OperationType.RETIRE);
        assertThat(replay.targetPublicationUuid()).contains(current.publicationId());
        assertThat(replay.readinessAfter()).contains(LegalEditorialReadiness.NOT_READY);
        assertThat(replay.appliedAt()).contains(committedAppliedAt);
        assertThat(replay.receipt()).get()
                .extracting(LegalEditorialApplyReceipt::appliedAt)
                .isEqualTo(committedAppliedAt);
        assertThat(editorialTableRows()).isEqualTo(rowsBeforeReplay);
        assertThat(editorialSequenceStates(owner)).isEqualTo(sequencesBeforeReplay);
    }

    private AppliedRetirement applyFreshRetirement(
            ImportedRelease current,
            List<DocumentVersion> retiredDocuments,
            List<RequirementVersion> retiredRequirements,
            String operationSeed,
            int expectedReplacementBatches) throws Exception {
        assertRestrictedSession();
        seedAcceptanceAggregate(current);
        assertThat(apply.readinessCore().evaluate(
                current.release(),
                databaseNow(apply.jdbc())).readiness())
                .isEqualTo(LegalEditorialReadiness.READY);

        List<DocumentVersion> allDocuments = documents(current.publicationId());
        List<RequirementVersion> allRequirements = requirements(current.publicationId());
        assertThat(allDocuments).hasSize(11);
        assertThat(allRequirements).hasSize(7);

        Set<UUID> retiredDocumentIds = retiredDocuments.stream()
                .map(DocumentVersion::id)
                .collect(Collectors.toUnmodifiableSet());
        Set<UUID> retiredRequirementIds = retiredRequirements.stream()
                .map(RequirementVersion::id)
                .collect(Collectors.toUnmodifiableSet());
        assertThat(retiredDocumentIds).hasSize(retiredDocuments.size());
        assertThat(retiredRequirementIds).hasSize(retiredRequirements.size());
        assertThat(allDocuments)
                .extracting(DocumentVersion::id)
                .containsAll(retiredDocumentIds);
        assertThat(allRequirements)
                .extracting(RequirementVersion::id)
                .containsAll(retiredRequirementIds);

        DatabaseSnapshot before = databaseSnapshot(allDocuments, allRequirements);
        assertThat(before.documentSlots()).hasSize(21);
        assertThat(before.requiredSetPointers()).hasSize(9);
        assertThat(before.unrelatedRows().keySet())
                .containsAll(ACCEPTANCE_AND_IDEMPOTENCY_TABLES);
        assertAcceptanceAggregate(before.acceptanceRows());
        assertReplacementHistory(before, expectedReplacementBatches);

        Set<PointerKey> documentAffectedPointers = affectedPointerKeys(
                before.requiredSetPointers(),
                retiredDocumentIds,
                Set.of());
        Set<PointerKey> requirementAffectedPointers = affectedPointerKeys(
                before.requiredSetPointers(),
                Set.of(),
                retiredRequirementIds);
        Set<PointerKey> affectedPointers = new LinkedHashSet<>(
                documentAffectedPointers);
        affectedPointers.addAll(requirementAffectedPointers);
        affectedPointers = Set.copyOf(affectedPointers);
        long affectedSlotCount = before.documentSlots().values().stream()
                .filter(slot -> retiredDocumentIds.contains(slot.documentVersionId()))
                .count();
        assertThat(affectedSlotCount).isEqualTo(retiredDocuments.stream()
                .mapToLong(document -> document.contexts().size())
                .sum());

        ValidatedEditorialPlan plan = retirementPlan(
                current,
                retiredDocuments,
                retiredRequirements,
                operationSeed);
        Instant beforeApply = databaseNow(owner);
        LegalEditorialApplyResult result = apply.service().applyRetire(
                current.release(), plan);
        Instant afterApply = databaseNow(owner);

        assertEditorialConfirmed(result, LegalEditorialApplyResult.Outcome.APPLIED);
        LegalEditorialApplyReceipt receipt = result.receipt().orElseThrow();
        assertThat(receipt.operationType())
                .isEqualTo(LegalEditorialApplyReceipt.OperationType.RETIRE);
        assertThat(receipt.targetPublicationUuid()).isEqualTo(current.publicationId());
        assertThat(receipt.readinessAfter()).isEqualTo(LegalEditorialReadiness.NOT_READY);
        assertThat(result.appliedAt()).contains(receipt.appliedAt());
        assertThat(result.readinessAfter()).contains(LegalEditorialReadiness.NOT_READY);
        assertThat(receipt.appliedAt()).isAfterOrEqualTo(beforeApply);
        assertThat(receipt.appliedAt()).isBeforeOrEqualTo(afterApply);

        DatabaseSnapshot after = databaseSnapshot(allDocuments, allRequirements);
        assertVersionStatesAndHistories(
                before,
                after,
                retiredDocumentIds,
                retiredRequirementIds,
                receipt.appliedAt());
        assertExactProjectionDelta(
                before,
                after,
                retiredDocumentIds,
                affectedPointers);
        assertExactTableCountDelta(
                before.editorialTableCounts(),
                after.editorialTableCounts(),
                retiredDocumentIds.size(),
                retiredRequirementIds.size(),
                Math.toIntExact(affectedSlotCount),
                affectedPointers.size());
        assertExactSequenceDelta(
                before.editorialSequenceStates(),
                after.editorialSequenceStates(),
                retiredDocumentIds.size(),
                retiredRequirementIds.size());
        assertThat(after.immutableOriginRows()).isEqualTo(before.immutableOriginRows());
        assertThat(after.unrelatedRows()).isEqualTo(before.unrelatedRows());
        assertThat(after.acceptanceRows()).isEqualTo(before.acceptanceRows());
        assertThat(after.replacementHistoryRows())
                .isEqualTo(before.replacementHistoryRows());

        int expectedDocumentTransitions = before.documentHistories().values().stream()
                .mapToInt(List::size)
                .sum() + retiredDocumentIds.size();
        int expectedRequirementTransitions = before.requirementHistories().values().stream()
                .mapToInt(List::size)
                .sum() + retiredRequirementIds.size();
        assertThat(receipt.documentVersions()).isEqualTo(allDocuments.size());
        assertThat(receipt.requirementVersions()).isEqualTo(allRequirements.size());
        assertThat(receipt.documentTransitions()).isEqualTo(expectedDocumentTransitions);
        assertThat(receipt.requirementTransitions()).isEqualTo(expectedRequirementTransitions);
        assertThat(receipt.documentSlots()).isEqualTo(after.documentSlots().size());
        assertThat(receipt.requiredSetPointers())
                .isEqualTo(after.requiredSetPointers().size());
        assertThat(receipt.replacementBatches()).isEqualTo(expectedReplacementBatches);
        assertThat(owner.queryForObject(
                "SELECT count(*) FROM legal_documento_reemplazo_lotes",
                Long.class)).isEqualTo((long) expectedReplacementBatches);

        assertRestrictedSession();
        assertThat(apply.readinessCore().evaluate(
                current.release(),
                receipt.appliedAt()).readiness())
                .isEqualTo(LegalEditorialReadiness.NOT_READY);
        return new AppliedRetirement(
                before,
                after,
                receipt,
                plan,
                documentAffectedPointers,
                requirementAffectedPointers,
                affectedPointers,
                Math.toIntExact(affectedSlotCount));
    }

    private static void seedRetirementCorruption(
            RetirementPostStateCorruption corruption,
            ImportedRelease current,
            AppliedRetirement applied,
            UUID offScopeBatchMember) {
        HistoricalReplacementBatch batch = historicalReplacementBatch();
        withReplicaRole(owner, () -> {
            int affected = switch (corruption) {
                case SLOT_EXTRA -> insertDocumentSlot(removedDocumentSlot(applied));
                case SLOT_MISSING -> deleteDocumentSlot(survivingDocumentSlot(applied));
                case POINTER_EXTRA -> insertRequiredSetPointer(removedRequiredSetPointer(applied));
                case POINTER_MISSING -> deleteRequiredSetPointer(
                        survivingRequiredSetPointer(applied));
                case TRANSITION_EXTRA -> insertDuplicateRetirementTransition(
                        retirementTransition(applied));
                case TRANSITION_MISSING -> owner.update("""
                        DELETE FROM legal_documento_transiciones
                         WHERE id = ?
                        """, retirementTransition(applied).transition().id());
                case BATCH_MEMBER_EXTRA -> owner.update("""
                        INSERT INTO legal_documento_reemplazo_anteriores
                            (id, lote_id, documento_version_id)
                        VALUES (-8000002, ?, ?)
                        """,
                        batch.id(),
                        Objects.requireNonNull(offScopeBatchMember));
                case BATCH_MEMBER_MISSING -> owner.update("""
                        DELETE FROM legal_documento_reemplazo_anteriores
                         WHERE lote_id = ?
                           AND documento_version_id = ?
                        """, batch.id(), batch.predecessorDocumentVersionId());
                case BATCH_EXTRA -> insertRelatedExtraBatch(batch, current.publicationId());
                case BATCH_MISSING -> owner.update("""
                        DELETE FROM legal_documento_reemplazo_lotes
                         WHERE id = ?
                        """, batch.id());
            };
            assertThat(affected).isOne();
        });
    }

    private static int insertDocumentSlot(DocumentSlot slot) {
        return owner.update("""
                INSERT INTO legal_documento_vigentes
                    (tipo, locale, contexto, documento_version_id,
                     documento_linea_id, publicacion_id, estado_documento)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                slot.key().type(),
                slot.key().locale(),
                slot.key().context(),
                slot.documentVersionId(),
                slot.documentLineId(),
                slot.publicationId(),
                slot.state());
    }

    private static int deleteDocumentSlot(DocumentSlot slot) {
        return owner.update("""
                DELETE FROM legal_documento_vigentes
                 WHERE tipo = ?
                   AND locale = ?
                   AND contexto = ?
                """,
                slot.key().type(),
                slot.key().locale(),
                slot.key().context());
    }

    private static int insertRequiredSetPointer(RequiredSetPointer pointer) {
        return owner.update("""
                INSERT INTO legal_requisito_conjuntos_actuales
                    (locale, contexto, audiencia, conjunto_id,
                     publicacion_id, actualizado_en)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                pointer.key().locale(),
                pointer.key().context(),
                pointer.key().audience(),
                pointer.requiredSetId(),
                pointer.publicationId(),
                OffsetDateTime.ofInstant(
                        pointer.updatedAt(),
                        java.time.ZoneOffset.UTC));
    }

    private static int deleteRequiredSetPointer(RequiredSetPointer pointer) {
        return owner.update("""
                DELETE FROM legal_requisito_conjuntos_actuales
                 WHERE locale = ?
                   AND contexto = ?
                   AND audiencia = ?
                """,
                pointer.key().locale(),
                pointer.key().context(),
                pointer.key().audience());
    }

    private static int insertDuplicateRetirementTransition(
            RetirementTransition retirement) {
        DocumentTransition transition = retirement.transition();
        return owner.update("""
                INSERT INTO legal_documento_transiciones
                    (id, documento_version_id, estado_anterior, estado_nuevo,
                     motivo, reemplazo_lote_id, ocurrido_en)
                VALUES (-8000001, ?, ?, ?, ?, ?, ?)
                """,
                retirement.documentVersionId(),
                transition.previousState(),
                transition.newState(),
                transition.reason(),
                transition.replacementBatchId(),
                OffsetDateTime.ofInstant(
                        transition.occurredAt(),
                        java.time.ZoneOffset.UTC));
    }

    private static int insertRelatedExtraBatch(
            HistoricalReplacementBatch batch,
            UUID publicationId) {
        UUID extraBatchId = stableUuid("retire-corruption:extra-batch:" + publicationId);
        assertThat(owner.update("""
                INSERT INTO legal_documento_reemplazo_lotes
                    (id, estado_construccion, creado_en, sellado_en)
                SELECT ?, estado_construccion, creado_en, sellado_en
                  FROM legal_documento_reemplazo_lotes
                 WHERE id = ?
                """, extraBatchId, batch.id())).isOne();
        assertThat(owner.update("""
                INSERT INTO legal_documento_reemplazo_anteriores
                    (id, lote_id, documento_version_id)
                VALUES (-8000003, ?, ?)
                """, extraBatchId, batch.predecessorDocumentVersionId())).isOne();
        return owner.update("""
                INSERT INTO legal_documento_reemplazo_sucesoras
                    (id, lote_id, documento_version_id, publicacion_id)
                VALUES (-8000004, ?, ?, ?)
                """,
                extraBatchId,
                batch.successorDocumentVersionId(),
                publicationId);
    }

    private static HistoricalReplacementBatch historicalReplacementBatch() {
        List<UUID> batchIds = owner.queryForList("""
                SELECT id
                 FROM legal_documento_reemplazo_lotes
                 ORDER BY id
                """, UUID.class);
        assertThat(batchIds).hasSize(1);
        UUID batchId = batchIds.getFirst();
        List<UUID> predecessors = owner.queryForList("""
                SELECT documento_version_id
                  FROM legal_documento_reemplazo_anteriores
                 WHERE lote_id = ?
                 ORDER BY id
                """, UUID.class, batchId);
        List<UUID> successors = owner.queryForList("""
                SELECT documento_version_id
                  FROM legal_documento_reemplazo_sucesoras
                 WHERE lote_id = ?
                 ORDER BY id
                """, UUID.class, batchId);
        assertThat(predecessors).hasSize(1);
        assertThat(successors).hasSize(1);
        return new HistoricalReplacementBatch(
                batchId,
                predecessors.getFirst(),
                successors.getFirst());
    }

    private static DocumentSlot removedDocumentSlot(AppliedRetirement applied) {
        return applied.before().documentSlots().values().stream()
                .filter(slot -> !applied.after().documentSlots().containsKey(slot.key()))
                .findFirst()
                .orElseThrow();
    }

    private static DocumentSlot survivingDocumentSlot(AppliedRetirement applied) {
        return applied.after().documentSlots().values().stream()
                .findFirst()
                .orElseThrow();
    }

    private static RequiredSetPointer removedRequiredSetPointer(
            AppliedRetirement applied) {
        return applied.before().requiredSetPointers().values().stream()
                .filter(pointer -> !applied.after().requiredSetPointers()
                        .containsKey(pointer.key()))
                .findFirst()
                .orElseThrow();
    }

    private static RequiredSetPointer survivingRequiredSetPointer(
            AppliedRetirement applied) {
        return applied.after().requiredSetPointers().values().stream()
                .findFirst()
                .orElseThrow();
    }

    private static RetirementTransition retirementTransition(
            AppliedRetirement applied) {
        List<RetirementTransition> candidates = new ArrayList<>();
        applied.after().documentHistories().forEach((documentVersionId, afterHistory) -> {
            List<DocumentTransition> beforeHistory = applied.before()
                    .documentHistories()
                    .get(documentVersionId);
            if (afterHistory.size() == beforeHistory.size() + 1
                    && afterHistory.getLast().newState().equals("RETIRADA")) {
                candidates.add(new RetirementTransition(
                        documentVersionId,
                        afterHistory.getLast()));
            }
        });
        assertThat(candidates).hasSize(1);
        return candidates.getFirst();
    }

    private static void assertSourceFingerprintBlocked(
            LegalEditorialApplyResult result) {
        assertEditorialKnownFailure(
                result,
                LegalManifestStatus.BLOCKED,
                LegalManifestIssueCode.SOURCE_FINGERPRINT_MISMATCH);
        assertThat(result.issues()).singleElement().satisfies(issue ->
                assertThat(issue.location()).isEqualTo("database/source"));
    }

    private static List<String> rowsChangedBetween(
            Map<String, String> before,
            Map<String, String> after) {
        assertThat(after.keySet()).isEqualTo(before.keySet());
        return before.keySet().stream()
                .filter(table -> !Objects.equals(before.get(table), after.get(table)))
                .sorted()
                .toList();
    }

    private static void seedAcceptanceAggregate(ImportedRelease current) {
        String token = current.release().plan().manifest().publicationId();
        UUID lotId = stableUuid("acceptance-lot:" + token);
        UUID acceptanceId = stableUuid("acceptance-act:" + token);
        importer.transaction().executeWithoutResult(status -> {
            JdbcTemplate jdbc = importer.jdbc();
            jdbc.queryForList("""
                    SELECT pg_catalog.pg_advisory_xact_lock_shared(
                        pg_catalog.hashtextextended(?, 0)
                    )
                    """, LegalManifestDatabaseGate.EDITORIAL_LOCK_NAME);
            Long workshopId = jdbc.queryForObject("""
                    INSERT INTO talleres (nombre)
                    VALUES (?)
                    RETURNING id
                    """, Long.class, "Taller aceptación RETIRE " + token);
            Long userId = jdbc.queryForObject("""
                    INSERT INTO users (username, password, email, role, taller_id)
                    VALUES (?, 'hash-retire-it', ?, 'ADMIN', ?)
                    RETURNING id
                    """, Long.class,
                    "retire-" + token,
                    token + "@retire.ordenfix.test",
                    workshopId);
            UUID requirementId = Objects.requireNonNull(jdbc.queryForObject("""
                    SELECT rv.id
                      FROM legal_publicacion_requisitos pr
                      JOIN legal_requisito_versiones rv
                        ON rv.id = pr.requisito_version_id
                      JOIN legal_requisito_lineas rl
                        ON rl.id = rv.requisito_linea_id
                     WHERE pr.publicacion_id = ?
                       AND rl.clave = 'customer-photo-attestation'
                    """, UUID.class, current.publicationId()));
            PersistedAcceptanceAggregate aggregate = materializeAcceptanceAggregate(
                    jdbc,
                    current.publicationId(),
                    stableUuid("acceptance-aggregate:" + token),
                    List.of(
                            ContextoLegal.USO_CONTINUADO,
                            ContextoLegal.ATESTACION_FOTOS));

            assertThat(jdbc.update("""
                    INSERT INTO legal_aceptacion_lotes
                        (id, user_id, taller_id, rol_wire, audiencia,
                         required_set_revision, aceptado_en, revision_scheme,
                         perfil, agregado_id)
                    VALUES (?, ?, ?, 'ADMIN', 'ADMIN_TITULAR', ?,
                            transaction_timestamp(), 'AGGREGATE_V1',
                            'AUTHENTICATED_PENDING', ?)
                    """,
                    lotId,
                    userId,
                    workshopId,
                    aggregate.requiredSetRevision(),
                    aggregate.id())).isOne();
            assertThat(jdbc.update("""
                    INSERT INTO legal_aceptaciones
                        (id, lote_id, user_id, taller_id, requisito_version_id,
                         requisito_clave, requisito_version, contexto, tipo_acto,
                         afirmacion, afirmacion_sha256, requerido)
                    SELECT ?, ?, ?, ?, rv.id, rl.clave, rv.version, rl.contexto,
                           rl.tipo_acto, rv.afirmacion, rv.afirmacion_sha256,
                           rv.requerido
                      FROM legal_requisito_versiones rv
                      JOIN legal_requisito_lineas rl
                        ON rl.id = rv.requisito_linea_id
                     WHERE rv.id = ?
                    """, acceptanceId, lotId, userId, workshopId, requirementId)).isOne();
            assertThat(jdbc.update("""
                    INSERT INTO legal_aceptacion_documentos
                        (aceptacion_id, documento_ordinal, documento_version_id,
                         documento_clave, tipo, version, titulo, sha256)
                    SELECT ?, rd.documento_ordinal, dv.id, dl.clave, dl.tipo,
                           dv.version, dv.titulo, dv.sha256
                      FROM legal_requisito_documentos rd
                      JOIN legal_documento_versiones dv
                        ON dv.id = rd.documento_version_id
                      JOIN legal_documento_lineas dl
                        ON dl.id = dv.documento_linea_id
                     WHERE rd.requisito_version_id = ?
                     ORDER BY rd.documento_ordinal
                    """, acceptanceId, requirementId)).isEqualTo(2);
            assertThat(jdbc.update("""
                    INSERT INTO legal_aceptacion_metadatos
                        (lote_id, capturado_en, retener_hasta)
                    VALUES (?, transaction_timestamp(),
                            transaction_timestamp() + INTERVAL '30 days')
                    """, lotId)).isOne();
            assertThat(jdbc.update("""
                    INSERT INTO legal_aceptacion_metadatos_cifrados
                        (lote_id, tipo, key_version, nonce, ciphertext, tag,
                         longitud_original)
                    VALUES (?, 'IP', 1, ?, ?, ?, 9)
                    """, lotId, bytes(12, 11), bytes(9, 31), bytes(16, 51))).isOne();
            jdbc.queryForObject("""
                    SELECT pg_advisory_xact_lock(hashtextextended(jsonb_build_array(
                        'ordenfix:legal-idempotencia:tupla:v29', 'ACEPTACION_LEGAL',
                        '/api/legal/retire-it', ?::text, ?::text)::text, 0))
                    """, Object.class, "a".repeat(64), "b".repeat(64));
            assertThat(jdbc.update("""
                    INSERT INTO legal_idempotencia_resultados
                        (operacion, route_template, scope_hmac,
                         idempotency_key_hmac, fingerprint_hmac,
                         hmac_key_version, user_id, taller_id, lote_id,
                         completed_at, expires_at)
                    VALUES ('ACEPTACION_LEGAL', '/api/legal/retire-it', ?, ?, ?,
                            1, ?, ?, ?, transaction_timestamp(),
                            transaction_timestamp() + INTERVAL '30 days')
                    """,
                    "a".repeat(64),
                    "b".repeat(64),
                    "c".repeat(64),
                    userId,
                    workshopId,
                    lotId)).isOne();
            jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
        });
        assertAcceptanceAggregate(acceptanceRows());
    }

    private static PersistedAcceptanceAggregate materializeAcceptanceAggregate(
            JdbcTemplate jdbc,
            UUID publicationId,
            UUID aggregateId,
            List<ContextoLegal> expectedContexts) {
        List<AggregateScope> scopes = jdbc.query("""
                SELECT current_set.conjunto_id, current_set.publicacion_id,
                       current_set.contexto, required_set.required_set_revision
                  FROM legal_requisito_conjuntos_actuales current_set
                  JOIN legal_requisito_conjuntos required_set
                    ON required_set.id = current_set.conjunto_id
                   AND required_set.publicacion_id = current_set.publicacion_id
                   AND required_set.locale = current_set.locale
                   AND required_set.contexto = current_set.contexto
                   AND required_set.audiencia = current_set.audiencia
                 WHERE current_set.publicacion_id = ?
                   AND current_set.locale = 'es-AR'
                   AND current_set.audiencia = 'ADMIN_TITULAR'
                   AND current_set.contexto IN ('USO_CONTINUADO', 'ATESTACION_FOTOS')
                 ORDER BY CASE current_set.contexto
                     WHEN 'USO_CONTINUADO' THEN 1
                     WHEN 'ATESTACION_FOTOS' THEN 2
                 END
                   FOR SHARE OF current_set
                """, (resultSet, rowNumber) -> new AggregateScope(
                ContextoLegal.valueOf(resultSet.getString("contexto")),
                resultSet.getObject("conjunto_id", UUID.class),
                resultSet.getObject("publicacion_id", UUID.class),
                resultSet.getString("required_set_revision")), publicationId);
        assertThat(scopes)
                .extracting(AggregateScope::context)
                .containsExactlyElementsOf(expectedContexts);

        LegalRequiredSetAggregateProjection projection =
                new LegalRequiredSetAggregateProjection(
                        EsquemaRevisionLegal.AGGREGATE_V1,
                        LocaleLegal.ES_AR,
                        AudienciaLegal.ADMIN_TITULAR,
                        scopes.stream()
                                .map(scope -> new ScopeRevision(
                                        scope.context(),
                                        scope.requiredSetRevision()))
                                .toList());
        LegalRequiredSetAggregateProvenance provenance =
                new LegalRequiredSetAggregateProvenance(
                        PerfilAgregadoLegal.AUTHENTICATED_PENDING,
                        LocaleLegal.ES_AR,
                        AudienciaLegal.ADMIN_TITULAR,
                        scopes.stream()
                                .map(scope -> new ScopeOrigin(
                                        scope.context(),
                                        scope.requiredSetId(),
                                        scope.publicationId()))
                                .toList());
        String requiredSetRevision =
                new LegalRequiredSetAggregateRevisionCalculator().calculate(projection);
        String provenanceFingerprint =
                new LegalRequiredSetAggregateProvenanceCalculator().calculate(provenance);

        assertThat(jdbc.update("""
                INSERT INTO legal_requisito_agregados
                    (id, perfil, locale, audiencia, revision_scheme,
                     required_set_revision, provenance_fingerprint,
                     scope_count, creado_en)
                VALUES (?, 'AUTHENTICATED_PENDING', 'es-AR', 'ADMIN_TITULAR',
                        'AGGREGATE_V1', ?, ?, ?, transaction_timestamp())
                """,
                aggregateId,
                requiredSetRevision,
                provenanceFingerprint,
                scopes.size())).isOne();
        for (int index = 0; index < scopes.size(); index++) {
            AggregateScope scope = scopes.get(index);
            assertThat(jdbc.update("""
                    INSERT INTO legal_requisito_agregado_scopes
                        (agregado_id, scope_ordinal, contexto, conjunto_id,
                         publicacion_id, locale, audiencia, required_set_revision)
                    VALUES (?, ?, ?, ?, ?, 'es-AR', 'ADMIN_TITULAR', ?)
                    """,
                    aggregateId,
                    index + 1,
                    scope.context().name(),
                    scope.requiredSetId(),
                    scope.publicationId(),
                    scope.requiredSetRevision())).isOne();
        }
        return new PersistedAcceptanceAggregate(aggregateId, requiredSetRevision);
    }

    private static byte[] bytes(int length, int seed) {
        byte[] value = new byte[length];
        for (int index = 0; index < length; index++) {
            value[index] = (byte) (seed + index);
        }
        return value;
    }

    private static void assertVersionStatesAndHistories(
            DatabaseSnapshot before,
            DatabaseSnapshot after,
            Set<UUID> retiredDocumentIds,
            Set<UUID> retiredRequirementIds,
            Instant appliedAt) {
        for (Map.Entry<UUID, DocumentState> entry : before.documentStates().entrySet()) {
            UUID versionId = entry.getKey();
            if (!retiredDocumentIds.contains(versionId)) {
                assertThat(after.documentStates().get(versionId)).isEqualTo(entry.getValue());
                assertThat(after.documentHistories().get(versionId))
                        .isEqualTo(before.documentHistories().get(versionId));
                continue;
            }
            assertThat(after.documentStates().get(versionId)).isEqualTo(new DocumentState(
                    "RETIRADA",
                    appliedAt,
                    DOCUMENT_REASON,
                    null));
            List<DocumentTransition> historyBefore = before.documentHistories().get(versionId);
            List<DocumentTransition> historyAfter = after.documentHistories().get(versionId);
            assertThat(historyAfter).hasSize(historyBefore.size() + 1);
            assertThat(historyAfter.subList(0, historyBefore.size()))
                    .isEqualTo(historyBefore);
            assertThat(historyAfter.getLast()).satisfies(transition -> {
                assertThat(transition.previousState()).isEqualTo("VIGENTE");
                assertThat(transition.newState()).isEqualTo("RETIRADA");
                assertThat(transition.reason()).isEqualTo(DOCUMENT_REASON);
                assertThat(transition.replacementBatchId()).isNull();
                assertThat(transition.occurredAt()).isEqualTo(appliedAt);
            });
        }

        for (Map.Entry<UUID, RequirementState> entry :
                before.requirementStates().entrySet()) {
            UUID versionId = entry.getKey();
            if (!retiredRequirementIds.contains(versionId)) {
                assertThat(after.requirementStates().get(versionId))
                        .isEqualTo(entry.getValue());
                assertThat(after.requirementHistories().get(versionId))
                        .isEqualTo(before.requirementHistories().get(versionId));
                continue;
            }
            assertThat(after.requirementStates().get(versionId))
                    .isEqualTo(new RequirementState(
                            "RETIRADA",
                            appliedAt,
                            REQUIREMENT_REASON));
            List<RequirementTransition> historyBefore =
                    before.requirementHistories().get(versionId);
            List<RequirementTransition> historyAfter =
                    after.requirementHistories().get(versionId);
            assertThat(historyAfter).hasSize(historyBefore.size() + 1);
            assertThat(historyAfter.subList(0, historyBefore.size()))
                    .isEqualTo(historyBefore);
            assertThat(historyAfter.getLast()).satisfies(transition -> {
                assertThat(transition.previousState()).isEqualTo("VIGENTE");
                assertThat(transition.newState()).isEqualTo("RETIRADA");
                assertThat(transition.reason()).isEqualTo(REQUIREMENT_REASON);
                assertThat(transition.occurredAt()).isEqualTo(appliedAt);
            });
        }
    }

    private static void assertExactProjectionDelta(
            DatabaseSnapshot before,
            DatabaseSnapshot after,
            Set<UUID> retiredDocumentIds,
            Set<PointerKey> affectedPointers) {
        Map<DocumentSlotKey, DocumentSlot> expectedSlots = before.documentSlots()
                .entrySet()
                .stream()
                .filter(entry -> !retiredDocumentIds.contains(
                        entry.getValue().documentVersionId()))
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue));
        assertThat(after.documentSlots()).isEqualTo(expectedSlots);

        Map<PointerKey, RequiredSetPointer> expectedPointers =
                before.requiredSetPointers()
                        .entrySet()
                        .stream()
                        .filter(entry -> !affectedPointers.contains(entry.getKey()))
                        .collect(Collectors.toUnmodifiableMap(
                                Map.Entry::getKey,
                                Map.Entry::getValue));
        assertThat(after.requiredSetPointers()).isEqualTo(expectedPointers);
        assertThat(after.requiredSetPointers().keySet())
                .doesNotContainAnyElementsOf(affectedPointers);
        after.requiredSetPointers().forEach((key, pointer) ->
                assertThat(pointer.updatedAt())
                        .isEqualTo(before.requiredSetPointers().get(key).updatedAt()));
    }

    private static void assertExactTableCountDelta(
            Map<String, Long> before,
            Map<String, Long> after,
            int retiredDocuments,
            int retiredRequirements,
            int deletedSlots,
            int deletedPointers) {
        Map<String, Long> expected = new LinkedHashMap<>(before);
        adjustCount(expected, "legal_documento_transiciones", retiredDocuments);
        adjustCount(expected, "legal_requisito_transiciones", retiredRequirements);
        adjustCount(expected, "legal_documento_vigentes", -deletedSlots);
        adjustCount(expected, "legal_requisito_conjuntos_actuales", -deletedPointers);
        assertThat(after).isEqualTo(expected);
    }

    private static void adjustCount(
            Map<String, Long> counts,
            String table,
            int delta) {
        counts.compute(table, (ignored, current) ->
                Objects.requireNonNull(current, "table count") + delta);
    }

    private static void assertExactSequenceDelta(
            Map<String, LegalManifestPersistenceITSupport.SequenceState> before,
            Map<String, LegalManifestPersistenceITSupport.SequenceState> after,
            int retiredDocuments,
            int retiredRequirements) {
        Map<String, LegalManifestPersistenceITSupport.SequenceState> expected =
                new LinkedHashMap<>(before);
        advanceSequence(
                expected,
                "legal_documento_transiciones_id_seq",
                retiredDocuments);
        advanceSequence(
                expected,
                "legal_requisito_transiciones_id_seq",
                retiredRequirements);
        assertThat(after).isEqualTo(expected);
    }

    private static void advanceSequence(
            Map<String, LegalManifestPersistenceITSupport.SequenceState> sequences,
            String name,
            int delta) {
        if (delta == 0) {
            return;
        }
        LegalManifestPersistenceITSupport.SequenceState current =
                Objects.requireNonNull(sequences.get(name), "sequence state");
        assertThat(current.called()).isTrue();
        sequences.put(name, new LegalManifestPersistenceITSupport.SequenceState(
                current.lastValue() + delta,
                true));
    }

    private DatabaseSnapshot databaseSnapshot(
            List<DocumentVersion> documents,
            List<RequirementVersion> requirements) {
        return new DatabaseSnapshot(
                documentStates(documents),
                documentHistories(documents),
                requirementStates(requirements),
                requirementHistories(requirements),
                documentSlots(),
                requiredSetPointers(),
                editorialTableCounts(owner),
                editorialSequenceStates(owner),
                immutableOriginRows(),
                unrelatedRows(),
                acceptanceRows(),
                replacementHistoryRows());
    }

    private ValidatedEditorialPlan retirementPlan(
            ImportedRelease current,
            List<DocumentVersion> retiredDocuments,
            List<RequirementVersion> retiredRequirements,
            String operationSeed) throws Exception {
        String externalId = current.release().plan().manifest().publicationId();
        String fingerprint = apply.readinessCore()
                .observeState(externalId, databaseNow(apply.jdbc()))
                .editorialStateFingerprint();
        return retirementPlan(
                current,
                retiredDocuments,
                retiredRequirements,
                operationSeed,
                fingerprint);
    }

    private ValidatedEditorialPlan retirementPlan(
            ImportedRelease current,
            List<DocumentVersion> retiredDocuments,
            List<RequirementVersion> retiredRequirements,
            String operationSeed,
            String fingerprint) throws Exception {
        String externalId = current.release().plan().manifest().publicationId();
        UUID operationId = stableUuid("retire:" + operationSeed + ":" + externalId);
        ObjectNode plan = emptyPlan(
                operationId,
                externalId,
                current.release().plan().manifestSha256(),
                fingerprint);
        for (DocumentVersion retired : retiredDocuments) {
            ObjectNode retirement = plan.withArray("documentRetirements").addObject();
            retirement.put("documentVersionId", retired.id().toString());
            retirement.put("sha256", retired.sha256());
            retired.contexts().forEach(retirement.putArray("contexts")::add);
            retirement.put("reason", DOCUMENT_REASON);
        }
        for (RequirementVersion retired : retiredRequirements) {
            ObjectNode retirement = plan.withArray("requirementRetirements").addObject();
            retirement.put("requirementVersionId", retired.id().toString());
            retirement.put("statementSha256", retired.sha256());
            retirement.put("context", retired.context());
            retired.audiences().forEach(retirement.putArray("audiences")::add);
            retirement.put("reason", REQUIREMENT_REASON);
        }
        return validatePlan(plan, operationId);
    }

    private static ObjectNode emptyPlan(
            UUID operationId,
            String externalId,
            String manifestSha256,
            String fingerprint) {
        ObjectNode plan = JSON.createObjectNode();
        plan.put("schemaVersion", 1);
        plan.put("operationId", operationId.toString());
        plan.put("operationType", "RETIRE");
        plan.put("expectedCurrentPublicationId", externalId);
        plan.put("expectedCurrentManifestSha256", manifestSha256);
        plan.put("expectedEditorialStateFingerprint", fingerprint);
        plan.put("targetPublicationId", externalId);
        plan.put("targetManifestSha256", manifestSha256);
        plan.putArray("documentAdditions");
        plan.putArray("documentReuses");
        plan.putArray("documentReplacementBatches");
        plan.putArray("documentRetirements");
        plan.putArray("requirementAdditions");
        plan.putArray("requirementReuses");
        plan.putArray("requirementReplacements");
        plan.putArray("requirementRetirements");
        plan.put("expectedReadinessAfter", "NOT_READY");
        plan.put("acknowledgeFailClosedGap", true);
        return plan;
    }

    private ValidatedEditorialPlan validatePlan(ObjectNode plan, UUID operationId)
            throws Exception {
        Path directory = temporaryDirectory.toRealPath()
                .resolve("retire-plan-" + operationId);
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

    private ImportedRelease readyRelease(String externalId) throws Exception {
        ValidatedRelease release = copyRelease(
                temporaryDirectory,
                LegalEditorialRetireIT.class,
                externalId,
                (manifestPath, manifest) -> {
                    manifest.withArray("documents")
                            .forEach(document -> ((ObjectNode) document).put(
                                    "effectiveAt",
                                    "2026-01-01T00:00:00-03:00"));
                    // AUTHENTICATED_PENDING must carry a genuine USO_CONTINUADO scope.
                    // Add it before import/seal instead of fabricating SCOPE_V1 history.
                    appendContinuedUseRequirement(manifest);
                });
        UUID publicationId = importer.importService()
                .importManifest(release)
                .receipt()
                .orElseThrow()
                .publicationUuid();
        promoteToReady(owner, publicationId);
        return new ImportedRelease(release, publicationId);
    }

    private ImportedRelease replaceTermsUnderRestrictedRole(
            ImportedRelease source,
            String targetExternalId) throws Exception {
        return replaceTermsUnderRestrictedRole(
                source,
                targetExternalId,
                "replace-v2");
    }

    private ImportedRelease replaceTermsUnderRestrictedRole(
            ImportedRelease source,
            String targetExternalId,
            String replacementVersion) throws Exception {
        ImportedRelease target = importedTermsReplacementTarget(
                targetExternalId,
                replacementVersion);
        ValidatedEditorialPlan plan = oneToOneReplacementPlan(source, target);
        long expectedReplacementBatches = Math.addExact(
                rowCount("legal_documento_reemplazo_lotes"),
                1L);

        assertRestrictedSession();
        LegalEditorialApplyResult result = apply.service().applyReplace(
                target.release(),
                plan);

        assertEditorialConfirmed(result, LegalEditorialApplyResult.Outcome.APPLIED);
        LegalEditorialApplyReceipt receipt = result.receipt().orElseThrow();
        assertThat(receipt.operationType())
                .isEqualTo(LegalEditorialApplyReceipt.OperationType.REPLACE);
        assertThat(receipt.targetPublicationUuid()).isEqualTo(target.publicationId());
        assertThat(receipt.readinessAfter()).isEqualTo(LegalEditorialReadiness.READY);
        assertThat(receipt.replacementBatches()).isOne();
        assertThat(rowCount("legal_documento_reemplazo_lotes"))
                .isEqualTo(expectedReplacementBatches);
        assertThat(rowCount("legal_documento_reemplazo_anteriores"))
                .isEqualTo(expectedReplacementBatches);
        assertThat(rowCount("legal_documento_reemplazo_sucesoras"))
                .isEqualTo(expectedReplacementBatches);
        assertThat(apply.readinessCore().evaluate(
                target.release(),
                receipt.appliedAt()).readiness())
                .isEqualTo(LegalEditorialReadiness.READY);
        return target;
    }

    private ImportedRelease importedTermsReplacementTarget(
            String externalId,
            String replacementVersion) throws Exception {
        ValidatedRelease release = copyRelease(
                temporaryDirectory,
                LegalEditorialRetireIT.class,
                externalId,
                (manifestPath, manifest) -> {
                    manifest.withArray("documents").forEach(document ->
                            ((ObjectNode) document).put(
                                    "effectiveAt",
                                    "2026-01-01T00:00:00-03:00"));
                    appendContinuedUseRequirement(manifest);
                    manifestDocument(manifest, "terminos")
                            .put("version", replacementVersion);
                    manifest.withArray("requirements").forEach(candidate -> {
                        ObjectNode requirement = (ObjectNode) candidate;
                        boolean referencesTerms = java.util.stream.StreamSupport.stream(
                                        requirement.withArray("documents").spliterator(),
                                        false)
                                .anyMatch(reference ->
                                        "terminos".equals(reference.textValue()));
                        if (referencesTerms) {
                            requirement.put("version", replacementVersion);
                        }
                    });
                });
        UUID publicationId = importer.importService()
                .importManifest(release)
                .receipt()
                .orElseThrow()
                .publicationUuid();
        return new ImportedRelease(release, publicationId);
    }

    private UUID offScopeDraftTermsDocument(String externalId) throws Exception {
        ImportedRelease draft = importedTermsReplacementTarget(
                externalId,
                "draft-" + externalId);
        DocumentVersion terms = document(draft.publicationId(), "terminos");
        assertThat(owner.queryForObject("""
                SELECT estado
                  FROM legal_documento_versiones
                 WHERE id = ?
                """, String.class, terms.id())).isEqualTo("BORRADOR");
        assertThat(owner.queryForObject("""
                SELECT count(*)
                  FROM legal_documento_vigentes
                 WHERE documento_version_id = ?
                """, Long.class, terms.id())).isZero();
        return terms.id();
    }

    private ValidatedEditorialPlan oneToOneReplacementPlan(
            ImportedRelease source,
            ImportedRelease target) throws Exception {
        List<DocumentVersion> sourceDocuments = documents(source.publicationId());
        List<DocumentVersion> targetDocuments = documents(target.publicationId());
        List<RequirementVersion> sourceRequirements = requirements(source.publicationId());
        List<RequirementVersion> targetRequirements = requirements(target.publicationId());
        Map<UUID, DocumentVersion> sourceDocumentsById = sourceDocuments.stream()
                .collect(Collectors.toUnmodifiableMap(
                        DocumentVersion::id,
                        document -> document));
        Map<UUID, DocumentVersion> sourceDocumentsByLine = sourceDocuments.stream()
                .collect(Collectors.toUnmodifiableMap(
                        DocumentVersion::lineId,
                        document -> document));
        Map<UUID, RequirementVersion> sourceRequirementsById = sourceRequirements.stream()
                .collect(Collectors.toUnmodifiableMap(
                        RequirementVersion::id,
                        requirement -> requirement));
        Map<UUID, RequirementVersion> sourceRequirementsByLine = sourceRequirements.stream()
                .collect(Collectors.toUnmodifiableMap(
                        RequirementVersion::lineId,
                        requirement -> requirement));

        String sourceExternalId = source.release().plan().manifest().publicationId();
        String fingerprint = apply.readinessCore()
                .observeState(sourceExternalId, databaseNow(apply.jdbc()))
                .editorialStateFingerprint();
        UUID operationId = stableUuid("replace:terms:" + sourceExternalId + ":"
                + target.release().plan().manifest().publicationId());
        ObjectNode plan = emptyReplacePlan(
                operationId,
                source,
                target,
                fingerprint);

        for (DocumentVersion document : targetDocuments) {
            if (sourceDocumentsById.containsKey(document.id())) {
                addDocumentScoped(plan.withArray("documentReuses"), document);
                continue;
            }
            DocumentVersion predecessor = Objects.requireNonNull(
                    sourceDocumentsByLine.get(document.lineId()),
                    "one-to-one document predecessor");
            ObjectNode batch = plan.withArray("documentReplacementBatches").addObject();
            batch.put("replacementBatchId", stableUuid(
                    "batch:" + predecessor.id() + ":" + document.id()).toString());
            document.contexts().forEach(batch.putArray("contexts")::add);
            addDocumentRef(batch.putArray("predecessors"), predecessor);
            addDocumentRef(batch.putArray("successors"), document);
        }

        for (RequirementVersion requirement : targetRequirements) {
            if (sourceRequirementsById.containsKey(requirement.id())) {
                addRequirementScoped(plan.withArray("requirementReuses"), requirement);
                continue;
            }
            RequirementVersion predecessor = Objects.requireNonNull(
                    sourceRequirementsByLine.get(requirement.lineId()),
                    "one-to-one requirement predecessor");
            ObjectNode replacement = plan.withArray("requirementReplacements")
                    .addObject();
            addRequirementRef(replacement.putObject("predecessor"), predecessor);
            addRequirementRef(replacement.putObject("successor"), requirement);
            replacement.put("context", requirement.context());
            requirement.audiences().forEach(replacement.putArray("audiences")::add);
        }

        assertThat(plan.withArray("documentReuses")).hasSize(10);
        assertThat(plan.withArray("documentReplacementBatches")).hasSize(1);
        assertThat(plan.withArray("requirementReuses")).hasSize(5);
        assertThat(plan.withArray("requirementReplacements")).hasSize(2);
        assertThat(plan.withArray("documentAdditions")).isEmpty();
        assertThat(plan.withArray("documentRetirements")).isEmpty();
        assertThat(plan.withArray("requirementAdditions")).isEmpty();
        assertThat(plan.withArray("requirementRetirements")).isEmpty();
        return validatePlan(plan, operationId);
    }

    private static ObjectNode emptyReplacePlan(
            UUID operationId,
            ImportedRelease source,
            ImportedRelease target,
            String fingerprint) {
        ObjectNode plan = JSON.createObjectNode();
        plan.put("schemaVersion", 1);
        plan.put("operationId", operationId.toString());
        plan.put("operationType", "REPLACE");
        plan.put("expectedCurrentPublicationId",
                source.release().plan().manifest().publicationId());
        plan.put("expectedCurrentManifestSha256",
                source.release().plan().manifestSha256());
        plan.put("expectedEditorialStateFingerprint", fingerprint);
        plan.put("targetPublicationId",
                target.release().plan().manifest().publicationId());
        plan.put("targetManifestSha256", target.release().plan().manifestSha256());
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

    private static void addRequirementScoped(
            ArrayNode target,
            RequirementVersion requirement) {
        ObjectNode item = target.addObject();
        addRequirementRef(item, requirement);
        item.put("context", requirement.context());
        requirement.audiences().forEach(item.putArray("audiences")::add);
    }

    private static void addRequirementRef(
            ObjectNode target,
            RequirementVersion requirement) {
        target.put("requirementVersionId", requirement.id().toString());
        target.put("statementSha256", requirement.sha256());
    }

    private static ObjectNode manifestDocument(ObjectNode manifest, String key) {
        return (ObjectNode) java.util.stream.StreamSupport.stream(
                        manifest.withArray("documents").spliterator(),
                        false)
                .filter(candidate -> key.equals(candidate.path("key").textValue()))
                .findFirst()
                .orElseThrow();
    }

    private static void appendContinuedUseRequirement(ObjectNode manifest) {
        boolean alreadyPresent = java.util.stream.StreamSupport.stream(
                        manifest.withArray("requirements").spliterator(),
                        false)
                .anyMatch(candidate -> "continued-use".equals(
                        candidate.path("key").textValue()));
        if (alreadyPresent) {
            throw new IllegalStateException(
                    "El fixture RETIRE ya contiene el requisito USO_CONTINUADO");
        }
        ObjectNode requirement = manifest.withArray("requirements").addObject();
        requirement.put("key", "continued-use");
        requirement.put("version", "1.0.0");
        requirement.put("context", "USO_CONTINUADO");
        requirement.putArray("roles").add("ADMIN_TITULAR");
        requirement.put("actType", "ACEPTACION");
        requirement.put("statement", CONTINUED_USE_STATEMENT);
        requirement.put("statementSha256", CONTINUED_USE_STATEMENT_SHA256);
        requirement.putArray("documents").add("privacidad");
        requirement.put("required", true);
        requirement.put("requiresReacceptance", true);
    }

    private static DocumentVersion document(UUID publicationId, String key) {
        return documents(publicationId).stream()
                .filter(document -> key.equals(document.key()))
                .findFirst()
                .orElseThrow();
    }

    private static RequirementVersion requirement(UUID publicationId, String key) {
        return requirements(publicationId).stream()
                .filter(requirement -> key.equals(requirement.key()))
                .findFirst()
                .orElseThrow();
    }

    private static List<DocumentVersion> documents(UUID publicationId) {
        List<DocumentVersionBase> rows = owner.query("""
                SELECT dv.id, dv.documento_linea_id, dv.sha256, dl.clave
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
                        resultSet.getString("clave")), publicationId);
        return rows.stream().map(row -> new DocumentVersion(
                row.id(),
                row.lineId(),
                row.sha256(),
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
                       rl.clave, rl.contexto
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
                        resultSet.getString("clave"),
                        resultSet.getString("contexto")), publicationId);
        return rows.stream().map(row -> new RequirementVersion(
                row.id(),
                row.lineId(),
                row.sha256(),
                row.key(),
                row.context(),
                owner.queryForList("""
                        SELECT audiencia
                          FROM legal_requisito_audiencias
                         WHERE requisito_linea_id = ?
                         ORDER BY audiencia
                        """, String.class, row.lineId()))).toList();
    }

    private static Map<UUID, DocumentState> documentStates(
            List<DocumentVersion> documents) {
        Map<UUID, DocumentState> states = new LinkedHashMap<>();
        for (DocumentVersion document : documents) {
            DocumentState state = owner.queryForObject("""
                    SELECT estado, estado_cambiado_en, ultimo_motivo,
                           reemplazo_lote_id
                      FROM legal_documento_versiones
                     WHERE id = ?
                    """, (resultSet, rowNumber) -> new DocumentState(
                            resultSet.getString("estado"),
                            instant(resultSet.getObject(
                                    "estado_cambiado_en",
                                    OffsetDateTime.class)),
                            resultSet.getString("ultimo_motivo"),
                            resultSet.getObject("reemplazo_lote_id", UUID.class)),
                    document.id());
            states.put(document.id(), Objects.requireNonNull(state, "document state"));
        }
        return Map.copyOf(states);
    }

    private static Map<UUID, List<DocumentTransition>> documentHistories(
            List<DocumentVersion> documents) {
        Map<UUID, List<DocumentTransition>> histories = new LinkedHashMap<>();
        for (DocumentVersion document : documents) {
            histories.put(document.id(), List.copyOf(owner.query("""
                    SELECT id, estado_anterior, estado_nuevo, motivo,
                           reemplazo_lote_id, ocurrido_en
                      FROM legal_documento_transiciones
                     WHERE documento_version_id = ?
                     ORDER BY id
                    """, (resultSet, rowNumber) -> new DocumentTransition(
                            resultSet.getLong("id"),
                            resultSet.getString("estado_anterior"),
                            resultSet.getString("estado_nuevo"),
                            resultSet.getString("motivo"),
                            resultSet.getObject("reemplazo_lote_id", UUID.class),
                            resultSet.getObject(
                                    "ocurrido_en",
                                    OffsetDateTime.class).toInstant()),
                    document.id())));
        }
        return Map.copyOf(histories);
    }

    private static Map<UUID, RequirementState> requirementStates(
            List<RequirementVersion> requirements) {
        Map<UUID, RequirementState> states = new LinkedHashMap<>();
        for (RequirementVersion requirement : requirements) {
            RequirementState state = owner.queryForObject("""
                    SELECT estado, estado_cambiado_en, ultimo_motivo
                      FROM legal_requisito_versiones
                     WHERE id = ?
                    """, (resultSet, rowNumber) -> new RequirementState(
                            resultSet.getString("estado"),
                            instant(resultSet.getObject(
                                    "estado_cambiado_en",
                                    OffsetDateTime.class)),
                            resultSet.getString("ultimo_motivo")),
                    requirement.id());
            states.put(requirement.id(), Objects.requireNonNull(
                    state,
                    "requirement state"));
        }
        return Map.copyOf(states);
    }

    private static Map<UUID, List<RequirementTransition>> requirementHistories(
            List<RequirementVersion> requirements) {
        Map<UUID, List<RequirementTransition>> histories = new LinkedHashMap<>();
        for (RequirementVersion requirement : requirements) {
            histories.put(requirement.id(), List.copyOf(owner.query("""
                    SELECT id, estado_anterior, estado_nuevo, motivo, ocurrido_en
                      FROM legal_requisito_transiciones
                     WHERE requisito_version_id = ?
                     ORDER BY id
                    """, (resultSet, rowNumber) -> new RequirementTransition(
                            resultSet.getLong("id"),
                            resultSet.getString("estado_anterior"),
                            resultSet.getString("estado_nuevo"),
                            resultSet.getString("motivo"),
                            resultSet.getObject(
                                    "ocurrido_en",
                                    OffsetDateTime.class).toInstant()),
                    requirement.id())));
        }
        return Map.copyOf(histories);
    }

    private static Map<DocumentSlotKey, DocumentSlot> documentSlots() {
        Map<DocumentSlotKey, DocumentSlot> slots = new LinkedHashMap<>();
        owner.query("""
                SELECT tipo, locale, contexto, documento_version_id,
                       documento_linea_id, publicacion_id, estado_documento
                  FROM legal_documento_vigentes
                 ORDER BY tipo, locale, contexto
                """, resultSet -> {
                    DocumentSlotKey key = new DocumentSlotKey(
                            resultSet.getString("tipo"),
                            resultSet.getString("locale"),
                            resultSet.getString("contexto"));
                    DocumentSlot previous = slots.put(key, new DocumentSlot(
                            key,
                            resultSet.getObject("documento_version_id", UUID.class),
                            resultSet.getObject("documento_linea_id", UUID.class),
                            resultSet.getObject("publicacion_id", UUID.class),
                            resultSet.getString("estado_documento")));
                    assertThat(previous).isNull();
                });
        return Map.copyOf(slots);
    }

    private static Map<PointerKey, RequiredSetPointer> requiredSetPointers() {
        List<RequiredSetPointerBase> rows = owner.query("""
                SELECT a.locale, a.contexto, a.audiencia, a.conjunto_id,
                       a.publicacion_id, c.required_set_revision, a.actualizado_en
                  FROM legal_requisito_conjuntos_actuales a
                  JOIN legal_requisito_conjuntos c ON c.id = a.conjunto_id
                 ORDER BY a.locale, a.contexto, a.audiencia
                """, (resultSet, rowNumber) -> new RequiredSetPointerBase(
                        new PointerKey(
                                resultSet.getString("locale"),
                                resultSet.getString("contexto"),
                                resultSet.getString("audiencia")),
                        resultSet.getObject("conjunto_id", UUID.class),
                        resultSet.getObject("publicacion_id", UUID.class),
                        resultSet.getString("required_set_revision"),
                        resultSet.getObject(
                                "actualizado_en",
                                OffsetDateTime.class).toInstant()));
        Map<PointerKey, RequiredSetPointer> pointers = new LinkedHashMap<>();
        for (RequiredSetPointerBase row : rows) {
            Set<UUID> memberRequirementIds = Set.copyOf(owner.queryForList("""
                    SELECT requisito_version_id
                      FROM legal_requisito_conjunto_miembros
                     WHERE conjunto_id = ?
                     ORDER BY manifest_ordinal, id
                    """, UUID.class, row.requiredSetId()));
            Set<UUID> referencedDocumentIds = Set.copyOf(owner.queryForList("""
                    SELECT rd.documento_version_id
                      FROM legal_requisito_conjunto_miembros m
                      JOIN legal_requisito_documentos rd
                        ON rd.requisito_version_id = m.requisito_version_id
                     WHERE m.conjunto_id = ?
                     ORDER BY rd.documento_version_id,
                              m.manifest_ordinal, rd.documento_ordinal, rd.id
                    """, UUID.class, row.requiredSetId()));
            RequiredSetPointer previous = pointers.put(row.key(), new RequiredSetPointer(
                    row.key(),
                    row.requiredSetId(),
                    row.publicationId(),
                    row.revision(),
                    row.updatedAt(),
                    memberRequirementIds,
                    referencedDocumentIds));
            assertThat(previous).isNull();
        }
        return Map.copyOf(pointers);
    }

    private static Set<PointerKey> affectedPointerKeys(
            Map<PointerKey, RequiredSetPointer> pointers,
            Set<UUID> retiredDocumentIds,
            Set<UUID> retiredRequirementIds) {
        return pointers.values().stream()
                .filter(pointer -> !java.util.Collections.disjoint(
                                pointer.referencedDocumentIds(),
                                retiredDocumentIds)
                        || !java.util.Collections.disjoint(
                                pointer.memberRequirementIds(),
                                retiredRequirementIds))
                .map(RequiredSetPointer::key)
                .collect(Collectors.toUnmodifiableSet());
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

    private static Map<String, String> unrelatedRows() {
        List<String> tables = owner.queryForList("""
                SELECT table_name
                  FROM information_schema.tables
                 WHERE table_schema = 'public'
                   AND table_type = 'BASE TABLE'
                 ORDER BY table_name
                """, String.class).stream()
                .filter(table -> !LegalV27EditorialInventory.EDITORIAL_TABLES.contains(table))
                .toList();
        return tableRows(tables);
    }

    private static Map<String, String> acceptanceRows() {
        return tableRows(new TreeSet<>(ACCEPTANCE_AND_IDEMPOTENCY_TABLES));
    }

    private static void assertAcceptanceAggregate(Map<String, String> snapshot) {
        assertThat(snapshot).hasSize(ACCEPTANCE_AND_IDEMPOTENCY_TABLES.size());
        assertThat(rowCount("legal_requisito_agregados")).isOne();
        assertThat(rowCount("legal_requisito_agregado_scopes")).isEqualTo(2L);
        assertThat(rowCount("legal_aceptacion_lotes")).isOne();
        assertThat(rowCount("legal_aceptaciones")).isOne();
        assertThat(rowCount("legal_aceptacion_documentos")).isEqualTo(2L);
        assertThat(rowCount("legal_aceptacion_metadatos")).isOne();
        assertThat(rowCount("legal_aceptacion_metadatos_cifrados")).isOne();
        assertThat(rowCount("legal_idempotencia_resultados")).isOne();
        snapshot.values().forEach(rows -> assertThat(rows).isNotEqualTo("[]"));
    }

    private static Map<String, String> replacementHistoryRows() {
        Map<String, String> rows = new LinkedHashMap<>(tableRows(List.of(
                "legal_documento_reemplazo_lotes",
                "legal_documento_reemplazo_anteriores",
                "legal_documento_reemplazo_sucesoras")));
        rows.put("replacement-linked-document-versions", jsonRows("""
                WITH linked_versions AS (
                    SELECT documento_version_id
                      FROM legal_documento_reemplazo_anteriores
                    UNION
                    SELECT documento_version_id
                      FROM legal_documento_reemplazo_sucesoras
                )
                SELECT version.*
                  FROM legal_documento_versiones version
                  JOIN linked_versions linked ON linked.documento_version_id = version.id
                """));
        rows.put("replacement-linked-document-transitions", jsonRows("""
                WITH linked_versions AS (
                    SELECT documento_version_id
                      FROM legal_documento_reemplazo_anteriores
                    UNION
                    SELECT documento_version_id
                      FROM legal_documento_reemplazo_sucesoras
                )
                SELECT transition.*
                  FROM legal_documento_transiciones transition
                  JOIN linked_versions linked
                    ON linked.documento_version_id = transition.documento_version_id
                """));
        return Map.copyOf(rows);
    }

    private static Map<String, String> editorialTableRows() {
        Map<String, String> rows = tableRows(
                new TreeSet<>(LegalV27EditorialInventory.EDITORIAL_TABLES));
        assertThat(rows).hasSize(19);
        return rows;
    }

    private static void assertReplacementHistory(
            DatabaseSnapshot snapshot,
            int expectedBatches) {
        assertThat(rowCount("legal_documento_reemplazo_lotes"))
                .isEqualTo((long) expectedBatches);
        assertThat(rowCount("legal_documento_reemplazo_anteriores"))
                .isEqualTo((long) expectedBatches);
        assertThat(rowCount("legal_documento_reemplazo_sucesoras"))
                .isEqualTo((long) expectedBatches);
        if (expectedBatches == 0) {
            assertThat(snapshot.replacementHistoryRows().values())
                    .containsOnly("[]");
            return;
        }
        assertThat(expectedBatches).isOne();
        assertThat(rowCount("legal_documento_versiones version JOIN ("
                + "SELECT documento_version_id FROM legal_documento_reemplazo_anteriores "
                + "UNION SELECT documento_version_id "
                + "FROM legal_documento_reemplazo_sucesoras) linked "
                + "ON linked.documento_version_id = version.id")).isEqualTo(2L);
        assertThat(rowCount("legal_documento_transiciones transition JOIN ("
                + "SELECT documento_version_id FROM legal_documento_reemplazo_anteriores "
                + "UNION SELECT documento_version_id "
                + "FROM legal_documento_reemplazo_sucesoras) linked "
                + "ON linked.documento_version_id = transition.documento_version_id"))
                .isEqualTo(5L);
        snapshot.replacementHistoryRows().values()
                .forEach(rows -> assertThat(rows).isNotEqualTo("[]"));
    }

    private static long rowCount(String tableExpression) {
        return Objects.requireNonNull(owner.queryForObject(
                "SELECT count(*) FROM " + tableExpression,
                Long.class));
    }

    private static Map<String, String> tableRows(Iterable<String> tables) {
        Map<String, String> rows = new LinkedHashMap<>();
        for (String table : tables) {
            rows.put(table, jsonRows("SELECT * FROM " + quoteIdentifier(table)));
        }
        return Map.copyOf(rows);
    }

    private static String jsonRows(String selectSql) {
        return owner.queryForObject(
                "SELECT COALESCE(jsonb_agg(to_jsonb(snapshot) "
                        + "ORDER BY to_jsonb(snapshot)::text), '[]'::jsonb)::text "
                        + "FROM (" + selectSql + ") snapshot",
                String.class);
    }

    private static Instant databaseNow(JdbcTemplate jdbc) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT transaction_timestamp()",
                OffsetDateTime.class)).toInstant();
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static void assertRestrictedSession() {
        assertThat(apply.jdbc().queryForObject("SELECT current_user", String.class))
                .isEqualTo(EDITORIAL_ROLE);
    }

    private static UUID stableUuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String quoteIdentifier(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    private static final class FailAfterLastRetirementBatchJdbcTemplate
            extends JdbcTemplate {

        private final List<String> dmlOrder = new ArrayList<>();
        private final AtomicBoolean fired = new AtomicBoolean();

        private FailAfterLastRetirementBatchJdbcTemplate(DataSource dataSource) {
            super(Objects.requireNonNull(dataSource, "dataSource"));
        }

        @Override
        public int[] batchUpdate(String sql, List<Object[]> batchArgs) {
            String signature = retirementDmlSignature(sql);
            int[] counts = super.batchUpdate(sql, batchArgs);
            dmlOrder.add(signature);
            if ("document-transition:insert".equals(signature)
                    && fired.compareAndSet(false, true)) {
                throw new IllegalStateException(
                        "fallo inyectado después del último batch DML RETIRE");
            }
            return counts;
        }

        private static String retirementDmlSignature(String sql) {
            String normalized = sql.toLowerCase(java.util.Locale.ROOT)
                    .replaceAll("\\s+", " ")
                    .trim();
            if (normalized.startsWith(
                    "delete from legal_requisito_conjuntos_actuales")) {
                return "required-set-pointer:delete";
            }
            if (normalized.startsWith("delete from legal_documento_vigentes")) {
                return "document-slot:delete";
            }
            if (normalized.startsWith(
                    "insert into legal_requisito_transiciones")) {
                return "requirement-transition:insert";
            }
            if (normalized.startsWith(
                    "insert into legal_documento_transiciones")) {
                return "document-transition:insert";
            }
            throw new AssertionError("DML RETIRE inesperado: " + normalized);
        }

        private List<String> dmlOrder() {
            return List.copyOf(dmlOrder);
        }

        private boolean fired() {
            return fired.get();
        }
    }

    private record ImportedRelease(ValidatedRelease release, UUID publicationId) { }

    private record AggregateScope(
            ContextoLegal context,
            UUID requiredSetId,
            UUID publicationId,
            String requiredSetRevision) { }

    private record PersistedAcceptanceAggregate(
            UUID id,
            String requiredSetRevision) { }

    private record DocumentVersionBase(
            UUID id,
            UUID lineId,
            String sha256,
            String key) { }

    private record DocumentVersion(
            UUID id,
            UUID lineId,
            String sha256,
            String key,
            List<String> contexts) { }

    private record RequirementVersionBase(
            UUID id,
            UUID lineId,
            String sha256,
            String key,
            String context) { }

    private record RequirementVersion(
            UUID id,
            UUID lineId,
            String sha256,
            String key,
            String context,
            List<String> audiences) { }

    private record DocumentState(
            String state,
            Instant changedAt,
            String reason,
            UUID replacementBatchId) { }

    private record RequirementState(
            String state,
            Instant changedAt,
            String reason) { }

    private record DocumentTransition(
            long id,
            String previousState,
            String newState,
            String reason,
            UUID replacementBatchId,
            Instant occurredAt) { }

    private record RequirementTransition(
            long id,
            String previousState,
            String newState,
            String reason,
            Instant occurredAt) { }

    private record DocumentSlotKey(String type, String locale, String context) { }

    private record DocumentSlot(
            DocumentSlotKey key,
            UUID documentVersionId,
            UUID documentLineId,
            UUID publicationId,
            String state) { }

    private record PointerKey(String locale, String context, String audience) { }

    private record RequiredSetPointerBase(
            PointerKey key,
            UUID requiredSetId,
            UUID publicationId,
            String revision,
            Instant updatedAt) { }

    private record RequiredSetPointer(
            PointerKey key,
            UUID requiredSetId,
            UUID publicationId,
            String revision,
            Instant updatedAt,
            Set<UUID> memberRequirementIds,
            Set<UUID> referencedDocumentIds) { }

    private record DatabaseSnapshot(
            Map<UUID, DocumentState> documentStates,
            Map<UUID, List<DocumentTransition>> documentHistories,
            Map<UUID, RequirementState> requirementStates,
            Map<UUID, List<RequirementTransition>> requirementHistories,
            Map<DocumentSlotKey, DocumentSlot> documentSlots,
            Map<PointerKey, RequiredSetPointer> requiredSetPointers,
            Map<String, Long> editorialTableCounts,
            Map<String, LegalManifestPersistenceITSupport.SequenceState> editorialSequenceStates,
            Map<String, String> immutableOriginRows,
            Map<String, String> unrelatedRows,
            Map<String, String> acceptanceRows,
            Map<String, String> replacementHistoryRows) { }

    private enum RetirementPostStateCorruption {
        SLOT_EXTRA,
        SLOT_MISSING,
        POINTER_EXTRA,
        POINTER_MISSING,
        TRANSITION_EXTRA,
        TRANSITION_MISSING,
        BATCH_MEMBER_EXTRA,
        BATCH_MEMBER_MISSING,
        BATCH_EXTRA,
        BATCH_MISSING
    }

    private record HistoricalReplacementBatch(
            UUID id,
            UUID predecessorDocumentVersionId,
            UUID successorDocumentVersionId) { }

    private record RetirementTransition(
            UUID documentVersionId,
            DocumentTransition transition) { }

    private record AppliedRetirement(
            DatabaseSnapshot before,
            DatabaseSnapshot after,
            LegalEditorialApplyReceipt receipt,
            ValidatedEditorialPlan plan,
            Set<PointerKey> documentAffectedPointers,
            Set<PointerKey> requirementAffectedPointers,
            Set<PointerKey> affectedPointers,
            int affectedSlotCount) { }
}
