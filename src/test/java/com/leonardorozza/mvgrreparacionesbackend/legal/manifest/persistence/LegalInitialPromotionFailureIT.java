package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialReadiness;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.ApplyHarness;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.CommitAcknowledgementLostDataSource;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.EditorialLockHolder;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.Harness;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.applyHarness;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.assertEditorialConfirmed;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.assertEditorialKnownFailure;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.assertEditorialUnknown;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.cleanLegalState;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.copyRelease;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.editorialSequenceStates;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.editorialTableCounts;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.harness;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.holdEditorialLock;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.migrate;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.pooledDataSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers
class LegalInitialPromotionFailureIT {

    private static final String APPLY_APPLICATION_NAME =
            "ordenfix-legal-initial-promotion-failure-it";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_initial_promotion_failure")
            .withUsername("ordenfix")
            .withPassword("ordenfix");

    private static HikariDataSource pool;
    private static DataSource lockDataSource;
    private static JdbcTemplate observer;
    private static Harness importer;
    private static ApplyHarness production;

    @TempDir
    private Path temporaryDirectory;

    @BeforeAll
    static void migrateAndAssemble() {
        migrate(POSTGRES);
        pool = pooledDataSource(POSTGRES, APPLY_APPLICATION_NAME);
        lockDataSource = LegalManifestPersistenceITSupport.directDataSource(
                POSTGRES,
                "ordenfix-legal-initial-promotion-lock-holder");
        observer = new JdbcTemplate(LegalManifestPersistenceITSupport.directDataSource(
                POSTGRES,
                "ordenfix-legal-initial-promotion-failure-observer"));
        importer = harness(pool, LegalDatabaseBudgets.production());
        production = applyHarness(pool, LegalDatabaseBudgets.production());
    }

    @AfterAll
    static void closePool() {
        if (pool != null) {
            pool.close();
        }
    }

    @BeforeEach
    void cleanDatabase() {
        cleanLegalState(observer);
    }

    @Test
    void editorialLockTimeoutIsKnownNotPersistedAndRetryApplies() throws Exception {
        ImportedRelease target = importedDraft("initial-promotion-lock-timeout-v1");
        ApplyHarness reduced = applyHarness(
                pool,
                new LegalDatabaseBudgets(5, 2, 1, 1));
        Map<String, Long> rowsBeforeAttempt = editorialTableCounts(observer);
        Map<String, LegalManifestPersistenceITSupport.SequenceState> sequencesBeforeAttempt =
                editorialSequenceStates(observer);

        try (EditorialLockHolder ignored = holdEditorialLock(lockDataSource)) {
            LegalEditorialApplyResult timedOut = reduced.service()
                    .applyPromote(target.release());

            assertEditorialKnownFailure(
                    timedOut,
                    LegalManifestStatus.ERROR,
                    LegalManifestIssueCode.CONCURRENT_OPERATION);
            assertThat(editorialTableCounts(observer)).isEqualTo(rowsBeforeAttempt);
            assertThat(editorialSequenceStates(observer)).isEqualTo(sequencesBeforeAttempt);
        }

        LegalEditorialApplyResult retry = production.service().applyPromote(target.release());
        assertEditorialConfirmed(retry, LegalEditorialApplyResult.Outcome.APPLIED);
    }

    @Test
    void aNotReadyPostconditionRollsBackTheCompletePromotion() throws Exception {
        ImportedRelease target = importedDraft("initial-promotion-postcondition-v1");
        Map<String, Long> rowsBeforeAttempt = editorialTableCounts(observer);
        LegalInitialPromotionCore sabotagedCore = mock(LegalInitialPromotionCore.class);
        when(sabotagedCore.usesJdbc(production.jdbc())).thenReturn(true);
        when(sabotagedCore.apply(any())).thenAnswer(invocation -> {
            LegalEditorialApplyReceipt receipt = production.promotionCore()
                    .apply(invocation.getArgument(0));
            int deleted = production.jdbc().update("""
                    DELETE FROM legal_requisito_conjuntos_actuales
                     WHERE contexto = 'CIERRE_CUENTA'
                       AND audiencia = 'ADMIN_TITULAR'
                       AND publicacion_id = ?
                    """, target.publicationId());
            assertThat(deleted).isEqualTo(1);
            return receipt;
        });
        LegalEditorialApplyService sabotagedService = new LegalEditorialApplyService(
                production.gate(),
                production.jdbc(),
                production.plannerCore(),
                sabotagedCore,
                production.readinessCore(),
                new LegalEditorialFailureMapper(),
                production.schemaVerifier(),
                production.privilegeVerifier());

        LegalEditorialApplyResult result = sabotagedService.applyPromote(target.release());

        assertEditorialKnownFailure(
                result,
                LegalManifestStatus.ERROR,
                LegalManifestIssueCode.POSTCONDITION_NOT_READY);
        assertThat(editorialTableCounts(observer)).isEqualTo(rowsBeforeAttempt);
        assertDraftWithoutEditorialResidue(target.publicationId());
    }

    @Test
    void aDeferredConstraintFailureRollsBackEveryEditorialWrite() throws Exception {
        ImportedRelease target = importedDraft("initial-promotion-constraint-v1");
        Map<String, Long> rowsBeforeAttempt = editorialTableCounts(observer);
        LegalEditorialPlannerCore sabotagedPlanner = mock(LegalEditorialPlannerCore.class);
        when(sabotagedPlanner.usesJdbc(production.jdbc())).thenReturn(true);
        when(sabotagedPlanner.planPromote(any(), any())).thenAnswer(invocation -> {
            LegalEditorialPlanResult planned = production.plannerCore().planPromote(
                    invocation.getArgument(0),
                    invocation.getArgument(1));
            return LegalEditorialPlanResult.applicable(
                    withoutOneMultiContextSlot(planned.executionPlan().orElseThrow()));
        });
        LegalEditorialApplyService sabotagedService = new LegalEditorialApplyService(
                production.gate(),
                production.jdbc(),
                sabotagedPlanner,
                production.promotionCore(),
                production.readinessCore(),
                new LegalEditorialFailureMapper(),
                production.schemaVerifier(),
                production.privilegeVerifier());

        LegalEditorialApplyResult result = sabotagedService.applyPromote(target.release());

        assertEditorialKnownFailure(
                result,
                LegalManifestStatus.ERROR,
                LegalManifestIssueCode.EDITORIAL_OBSERVATION_FAILED);
        assertThat(editorialTableCounts(observer)).isEqualTo(rowsBeforeAttempt);
        assertDraftWithoutEditorialResidue(target.publicationId());
    }

    @Test
    void commitAcknowledgementLossReturnsUnknownAndExactRetryReconciles()
            throws Exception {
        ImportedRelease target = importedDraft("initial-promotion-commit-unknown-v1");
        CommitAcknowledgementLostDataSource ambiguousDataSource =
                new CommitAcknowledgementLostDataSource(pool);
        ApplyHarness ambiguous = applyHarness(
                ambiguousDataSource,
                LegalDatabaseBudgets.production());

        LegalEditorialApplyResult firstAttempt = ambiguous.service()
                .applyPromote(target.release());

        assertEditorialUnknown(firstAttempt);
        assertThat(ambiguousDataSource.armed()).isFalse();
        OffsetDateTime appliedAt = observer.queryForObject("""
                SELECT max(dt.ocurrido_en)
                  FROM legal_documento_transiciones dt
                  JOIN legal_publicacion_documentos pd
                    ON pd.documento_version_id = dt.documento_version_id
                 WHERE pd.publicacion_id = ?
                """, OffsetDateTime.class, target.publicationId());
        assertThat(appliedAt).isNotNull();
        LegalEditorialReadinessResult readiness = production.readinessCore()
                .evaluate(target.release(), Objects.requireNonNull(appliedAt).toInstant());
        assertThat(readiness.readiness()).isEqualTo(LegalEditorialReadiness.READY);
        Map<String, Long> rowsBeforeReplay = editorialTableCounts(observer);
        Map<String, LegalManifestPersistenceITSupport.SequenceState> sequencesBeforeReplay =
                editorialSequenceStates(observer);

        LegalEditorialApplyResult replay = ambiguous.service()
                .applyPromote(target.release());

        assertEditorialConfirmed(replay, LegalEditorialApplyResult.Outcome.ALREADY_APPLIED);
        assertThat(replay.receipt().orElseThrow().appliedAt())
                .isEqualTo(Objects.requireNonNull(appliedAt).toInstant());
        assertThat(editorialTableCounts(observer)).isEqualTo(rowsBeforeReplay);
        assertThat(editorialSequenceStates(observer)).isEqualTo(sequencesBeforeReplay);
    }

    private ImportedRelease importedDraft(String externalId) throws Exception {
        ValidatedRelease release = copyRelease(
                temporaryDirectory,
                LegalInitialPromotionFailureIT.class,
                externalId,
                (manifestPath, manifest) -> manifest.withArray("documents")
                        .forEach(document -> ((ObjectNode) document).put(
                                "effectiveAt",
                                "2026-01-01T00:00:00-03:00")));
        UUID publicationId = importer.importService()
                .importManifest(release)
                .receipt()
                .orElseThrow()
                .publicationUuid();
        return new ImportedRelease(release, publicationId);
    }

    private static void assertDraftWithoutEditorialResidue(UUID publicationId) {
        Integer nonDraftDocuments = observer.queryForObject("""
                SELECT count(*)::integer
                  FROM legal_publicacion_documentos pd
                  JOIN legal_documento_versiones dv
                    ON dv.id = pd.documento_version_id
                 WHERE pd.publicacion_id = ?
                   AND dv.estado <> 'BORRADOR'
                """, Integer.class, publicationId);
        Integer nonDraftRequirements = observer.queryForObject("""
                SELECT count(*)::integer
                  FROM legal_publicacion_requisitos pr
                  JOIN legal_requisito_versiones rv
                    ON rv.id = pr.requisito_version_id
                 WHERE pr.publicacion_id = ?
                   AND rv.estado <> 'BORRADOR'
                """, Integer.class, publicationId);
        Long transitions = observer.queryForObject("""
                SELECT (SELECT count(*) FROM legal_documento_transiciones)
                     + (SELECT count(*) FROM legal_requisito_transiciones)
                """, Long.class);
        Long projections = observer.queryForObject("""
                SELECT (SELECT count(*) FROM legal_documento_vigentes)
                     + (SELECT count(*) FROM legal_requisito_conjuntos_actuales)
                """, Long.class);
        assertThat(nonDraftDocuments).isZero();
        assertThat(nonDraftRequirements).isZero();
        assertThat(transitions).isZero();
        assertThat(projections).isZero();
    }

    private static LegalEditorialExecutionPlan withoutOneMultiContextSlot(
            LegalEditorialExecutionPlan original) {
        LegalEditorialExecutionPlan.ExpectedPostState expected = original.expectedPostState();
        LegalEditorialExecutionPlan.ExpectedDocumentSlot omitted = expected.documentSlots().stream()
                .filter(candidate -> expected.documentSlots().stream()
                        .filter(slot -> slot.documentLineId().equals(candidate.documentLineId()))
                        .count() > 1)
                .findFirst()
                .orElseThrow();
        List<LegalEditorialExecutionPlan.ExpectedDocumentSlot> remainingSlots =
                expected.documentSlots().stream()
                        .filter(slot -> !slot.equals(omitted))
                        .toList();
        LegalEditorialExecutionPlan.ExpectedPostState incompletePostState =
                new LegalEditorialExecutionPlan.ExpectedPostState(
                        expected.documentStates(),
                        expected.requirementStates(),
                        expected.documentTransitions(),
                        expected.requirementTransitions(),
                        expected.preexistingDocumentTransitions(),
                        expected.preexistingRequirementTransitions(),
                        remainingSlots,
                        expected.requiredSetPointers(),
                        expected.replacementBatches(),
                        expected.v27TriggerEffects());
        LegalEditorialExecutionPlan.MutationCommands commands = original.mutationCommands();
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

    private record ImportedRelease(ValidatedRelease release, UUID publicationId) { }
}
