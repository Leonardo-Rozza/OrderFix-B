package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialReadiness;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.ApplyHarness;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.Harness;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.PublicationGraphCounts;
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

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.applyHarness;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.assertEditorialConfirmed;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.assertEditorialKnownFailure;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.cleanLegalState;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.copyRelease;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.editorialSequenceStates;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.editorialTableCounts;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.harness;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.migrate;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.pooledDataSource;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.publicationGraphCounts;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.withReplicaRole;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class LegalInitialPromotionIT {

    private static final String APPLY_APPLICATION_NAME =
            "ordenfix-legal-initial-promotion-it";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_initial_promotion")
            .withUsername("ordenfix")
            .withPassword("ordenfix");

    private static HikariDataSource pool;
    private static JdbcTemplate observer;
    private static Harness importer;
    private static ApplyHarness apply;

    @TempDir
    private Path temporaryDirectory;

    @BeforeAll
    static void migrateAndAssemble() {
        migrate(POSTGRES);
        pool = pooledDataSource(POSTGRES, APPLY_APPLICATION_NAME);
        observer = new JdbcTemplate(LegalManifestPersistenceITSupport.directDataSource(
                POSTGRES,
                "ordenfix-legal-initial-promotion-observer"));
        importer = harness(pool, LegalDatabaseBudgets.production());
        apply = applyHarness(pool, LegalDatabaseBudgets.production());
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
    void freshPromotionCommitsTheExactReadyGraphAndDatabaseReceipt() throws Exception {
        ImportedRelease target = importedDraft("initial-promotion-fresh-v1");

        LegalEditorialApplyResult result = apply.service().applyPromote(target.release());

        assertEditorialConfirmed(result, LegalEditorialApplyResult.Outcome.APPLIED);
        LegalEditorialApplyReceipt receipt = result.receipt().orElseThrow();
        int expectedSlots = target.release().plan().documents().stream()
                .mapToInt(document -> document.declaration().contexts().size())
                .sum();
        assertThat(receipt.operationType())
                .isEqualTo(LegalEditorialApplyReceipt.OperationType.PROMOTE);
        assertThat(receipt.targetPublicationUuid()).isEqualTo(target.publicationId());
        assertThat(receipt.readinessAfter()).isEqualTo(LegalEditorialReadiness.READY);
        assertThat(receipt.documentVersions()).isEqualTo(target.release().documentCount());
        assertThat(receipt.requirementVersions()).isEqualTo(target.release().requirementCount());
        assertThat(receipt.documentTransitions())
                .isEqualTo(target.release().documentCount() * 2);
        assertThat(receipt.requirementTransitions())
                .isEqualTo(target.release().requirementCount() * 2);
        assertThat(receipt.documentSlots()).isEqualTo(expectedSlots);
        assertThat(receipt.requiredSetPointers()).isEqualTo(target.release().scopeCount());
        assertThat(receipt.replacementBatches()).isZero();

        assertExactTargetState(target, receipt);
        LegalEditorialReadinessResult readiness = apply.readinessCore()
                .evaluate(target.release(), receipt.appliedAt());
        assertThat(readiness.readiness()).isEqualTo(LegalEditorialReadiness.READY);
        assertThat(readiness.issues()).isEmpty();
        assertThat(readiness.observation().orElseThrow().publicationUuid())
                .contains(target.publicationId());
    }

    @Test
    void aTargetMayPromoteVersionsIntroducedByAnotherInactiveSealedRelease()
            throws Exception {
        ImportedRelease origin = importedDraft("initial-promotion-origin-v1");
        ImportedRelease target = importedDraft("initial-promotion-reused-membership-v1");

        PublicationGraphCounts targetCounts = publicationGraphCounts(
                observer,
                target.publicationId());
        assertThat(targetCounts.newDocumentVersions()).isZero();
        assertThat(targetCounts.reusedDocumentVersions())
                .isEqualTo(target.release().documentCount());
        assertThat(targetCounts.newRequirementVersions()).isZero();
        assertThat(targetCounts.reusedRequirementVersions())
                .isEqualTo(target.release().requirementCount());

        LegalEditorialApplyResult result = apply.service().applyPromote(target.release());

        assertEditorialConfirmed(result, LegalEditorialApplyResult.Outcome.APPLIED);
        assertThat(result.targetPublicationUuid()).contains(target.publicationId());
        assertThat(result.targetPublicationUuid().orElseThrow())
                .isNotEqualTo(origin.publicationId());
        assertExactTargetState(target, result.receipt().orElseThrow());
    }

    @Test
    void exactReplayReturnsAlreadyAppliedWithoutRowsOrSequenceAdvances()
            throws Exception {
        ImportedRelease target = importedDraft("initial-promotion-replay-v1");
        LegalEditorialApplyResult first = apply.service().applyPromote(target.release());
        assertEditorialConfirmed(first, LegalEditorialApplyResult.Outcome.APPLIED);
        Map<String, Long> rowsBeforeReplay = editorialTableCounts(observer);
        Map<String, LegalManifestPersistenceITSupport.SequenceState> sequencesBeforeReplay =
                editorialSequenceStates(observer);

        LegalEditorialApplyResult replay = apply.service().applyPromote(target.release());

        assertEditorialConfirmed(replay, LegalEditorialApplyResult.Outcome.ALREADY_APPLIED);
        assertThat(replay.receipt()).isEqualTo(first.receipt());
        assertThat(editorialTableCounts(observer)).isEqualTo(rowsBeforeReplay);
        assertThat(editorialSequenceStates(observer)).isEqualTo(sequencesBeforeReplay);
    }

    @Test
    void aDifferentTargetAfterTheFirstPromotionIsBlockedWithoutMutation()
            throws Exception {
        ImportedRelease first = importedDraft("initial-promotion-current-v1");
        ImportedRelease second = importedDraft("initial-promotion-other-target-v1");
        assertEditorialConfirmed(
                apply.service().applyPromote(first.release()),
                LegalEditorialApplyResult.Outcome.APPLIED);
        Map<String, Long> rowsBeforeAttempt = editorialTableCounts(observer);
        Map<String, LegalManifestPersistenceITSupport.SequenceState> sequencesBeforeAttempt =
                editorialSequenceStates(observer);

        LegalEditorialApplyResult blocked = apply.service().applyPromote(second.release());

        assertEditorialKnownFailure(
                blocked,
                LegalManifestStatus.BLOCKED,
                LegalManifestIssueCode.INITIAL_PROJECTION_ALREADY_EXISTS);
        assertThat(editorialTableCounts(observer)).isEqualTo(rowsBeforeAttempt);
        assertThat(editorialSequenceStates(observer)).isEqualTo(sequencesBeforeAttempt);
    }

    @Test
    void aPartialProjectionIsBlockedAndNeverHealed() throws Exception {
        ImportedRelease target = importedDraft("initial-promotion-partial-v1");
        assertEditorialConfirmed(
                apply.service().applyPromote(target.release()),
                LegalEditorialApplyResult.Outcome.APPLIED);
        withReplicaRole(observer, () -> assertThat(observer.update("""
                DELETE FROM legal_documento_vigentes
                 WHERE (tipo, locale, contexto) = (
                       SELECT tipo, locale, contexto
                         FROM legal_documento_vigentes
                        WHERE publicacion_id = ?
                        ORDER BY tipo, locale, contexto
                        LIMIT 1
                 )
                """, target.publicationId())).isEqualTo(1));
        Map<String, Long> rowsAfterCorruption = editorialTableCounts(observer);
        Map<String, LegalManifestPersistenceITSupport.SequenceState> sequencesAfterCorruption =
                editorialSequenceStates(observer);

        LegalEditorialApplyResult blocked = apply.service().applyPromote(target.release());

        assertEditorialKnownFailure(
                blocked,
                LegalManifestStatus.BLOCKED,
                LegalManifestIssueCode.INITIAL_PROJECTION_ALREADY_EXISTS);
        assertThat(editorialTableCounts(observer)).isEqualTo(rowsAfterCorruption);
        assertThat(editorialSequenceStates(observer)).isEqualTo(sequencesAfterCorruption);
    }

    @Test
    void historicalPromotionWithoutCurrentProjectionsCannotReusePromote()
            throws Exception {
        ImportedRelease target = importedDraft("initial-promotion-history-v1");
        assertEditorialConfirmed(
                apply.service().applyPromote(target.release()),
                LegalEditorialApplyResult.Outcome.APPLIED);
        withReplicaRole(observer, () -> {
            observer.update("DELETE FROM legal_documento_vigentes");
            observer.update("DELETE FROM legal_requisito_conjuntos_actuales");
        });
        Map<String, Long> rowsWithoutProjections = editorialTableCounts(observer);
        Map<String, LegalManifestPersistenceITSupport.SequenceState> sequencesWithoutProjections =
                editorialSequenceStates(observer);

        LegalEditorialApplyResult blocked = apply.service().applyPromote(target.release());

        assertEditorialKnownFailure(
                blocked,
                LegalManifestStatus.BLOCKED,
                LegalManifestIssueCode.INITIAL_PROJECTION_ALREADY_EXISTS);
        assertThat(editorialTableCounts(observer)).isEqualTo(rowsWithoutProjections);
        assertThat(editorialSequenceStates(observer)).isEqualTo(sequencesWithoutProjections);
        assertThat(observer.queryForObject(
                "SELECT count(*) FROM legal_documento_vigentes",
                Long.class)).isZero();
        assertThat(observer.queryForObject(
                "SELECT count(*) FROM legal_requisito_conjuntos_actuales",
                Long.class)).isZero();
    }

    @Test
    void aFutureEffectiveDateBlocksBeforeAnyEditorialMutation() throws Exception {
        ImportedRelease target = importedRelease(
                "initial-promotion-future-v1",
                "2099-01-01T00:00:00-03:00");
        Map<String, Long> rowsBeforeAttempt = editorialTableCounts(observer);
        Map<String, LegalManifestPersistenceITSupport.SequenceState> sequencesBeforeAttempt =
                editorialSequenceStates(observer);

        LegalEditorialApplyResult blocked = apply.service().applyPromote(target.release());

        assertEditorialKnownFailure(
                blocked,
                LegalManifestStatus.BLOCKED,
                LegalManifestIssueCode.EFFECTIVE_DATE_NOT_REACHED);
        assertThat(editorialTableCounts(observer)).isEqualTo(rowsBeforeAttempt);
        assertThat(editorialSequenceStates(observer)).isEqualTo(sequencesBeforeAttempt);
    }

    private ImportedRelease importedDraft(String externalId) throws Exception {
        return importedRelease(externalId, "2026-01-01T00:00:00-03:00");
    }

    private ImportedRelease importedRelease(String externalId, String effectiveAt)
            throws Exception {
        ValidatedRelease release = copyRelease(
                temporaryDirectory,
                LegalInitialPromotionIT.class,
                externalId,
                (manifestPath, manifest) -> manifest.withArray("documents")
                        .forEach(document -> ((ObjectNode) document).put(
                                "effectiveAt",
                                effectiveAt)));
        UUID publicationId = importer.importService()
                .importManifest(release)
                .receipt()
                .orElseThrow()
                .publicationUuid();
        return new ImportedRelease(release, publicationId);
    }

    private static void assertExactTargetState(
            ImportedRelease target,
            LegalEditorialApplyReceipt receipt) {
        assertThat(targetVersionCount("legal_publicacion_documentos", target.publicationId()))
                .isEqualTo(target.release().documentCount());
        assertThat(targetVersionCount("legal_publicacion_requisitos", target.publicationId()))
                .isEqualTo(target.release().requirementCount());
        assertThat(invalidDocumentTransitionVersions(target.publicationId())).isZero();
        assertThat(invalidRequirementTransitionVersions(target.publicationId())).isZero();
        OffsetDateTime occurredAt = observer.queryForObject("""
                SELECT min(occurred_at)
                  FROM (
                        SELECT dt.ocurrido_en AS occurred_at
                          FROM legal_documento_transiciones dt
                          JOIN legal_publicacion_documentos pd
                            ON pd.documento_version_id = dt.documento_version_id
                         WHERE pd.publicacion_id = ?
                        UNION ALL
                        SELECT rt.ocurrido_en
                          FROM legal_requisito_transiciones rt
                          JOIN legal_publicacion_requisitos pr
                            ON pr.requisito_version_id = rt.requisito_version_id
                         WHERE pr.publicacion_id = ?
                        UNION ALL
                        SELECT actualizado_en
                          FROM legal_requisito_conjuntos_actuales
                         WHERE publicacion_id = ?
                  ) timestamps
                """, OffsetDateTime.class,
                target.publicationId(),
                target.publicationId(),
                target.publicationId());
        Integer timestampCount = observer.queryForObject("""
                SELECT count(DISTINCT occurred_at)::integer
                  FROM (
                        SELECT dt.ocurrido_en AS occurred_at
                          FROM legal_documento_transiciones dt
                          JOIN legal_publicacion_documentos pd
                            ON pd.documento_version_id = dt.documento_version_id
                         WHERE pd.publicacion_id = ?
                        UNION ALL
                        SELECT rt.ocurrido_en
                          FROM legal_requisito_transiciones rt
                          JOIN legal_publicacion_requisitos pr
                            ON pr.requisito_version_id = rt.requisito_version_id
                         WHERE pr.publicacion_id = ?
                        UNION ALL
                        SELECT actualizado_en
                          FROM legal_requisito_conjuntos_actuales
                         WHERE publicacion_id = ?
                  ) timestamps
                """, Integer.class,
                target.publicationId(),
                target.publicationId(),
                target.publicationId());
        assertThat(timestampCount).isEqualTo(1);
        assertThat(Objects.requireNonNull(occurredAt).toInstant()).isEqualTo(receipt.appliedAt());
    }

    private static int targetVersionCount(String membershipTable, UUID publicationId) {
        String versionTable;
        String versionColumn;
        if ("legal_publicacion_documentos".equals(membershipTable)) {
            versionTable = "legal_documento_versiones";
            versionColumn = "documento_version_id";
        } else if ("legal_publicacion_requisitos".equals(membershipTable)) {
            versionTable = "legal_requisito_versiones";
            versionColumn = "requisito_version_id";
        } else {
            throw new IllegalArgumentException("Membresía legal de test no permitida");
        }
        Integer count = observer.queryForObject(
                "SELECT count(*)::integer FROM " + membershipTable + " membership"
                        + " JOIN " + versionTable + " version"
                        + " ON version.id = membership." + versionColumn
                        + " WHERE membership.publicacion_id = ?"
                        + " AND version.estado = 'VIGENTE'",
                Integer.class,
                publicationId);
        return Objects.requireNonNull(count, "target version count");
    }

    private static int invalidDocumentTransitionVersions(UUID publicationId) {
        Integer count = observer.queryForObject("""
                SELECT count(*)::integer
                  FROM (
                        SELECT pd.documento_version_id
                          FROM legal_publicacion_documentos pd
                          LEFT JOIN legal_documento_transiciones dt
                            ON dt.documento_version_id = pd.documento_version_id
                         WHERE pd.publicacion_id = ?
                         GROUP BY pd.documento_version_id
                        HAVING count(dt.id) <> 2
                            OR count(*) FILTER (
                                   WHERE dt.estado_anterior = 'BORRADOR'
                                     AND dt.estado_nuevo = 'PUBLICADA') <> 1
                            OR count(*) FILTER (
                                   WHERE dt.estado_anterior = 'PUBLICADA'
                                     AND dt.estado_nuevo = 'VIGENTE') <> 1
                  ) invalid
                """, Integer.class, publicationId);
        return Objects.requireNonNull(count, "invalid document transition count");
    }

    private static int invalidRequirementTransitionVersions(UUID publicationId) {
        Integer count = observer.queryForObject("""
                SELECT count(*)::integer
                  FROM (
                        SELECT pr.requisito_version_id
                          FROM legal_publicacion_requisitos pr
                          LEFT JOIN legal_requisito_transiciones rt
                            ON rt.requisito_version_id = pr.requisito_version_id
                         WHERE pr.publicacion_id = ?
                         GROUP BY pr.requisito_version_id
                        HAVING count(rt.id) <> 2
                            OR count(*) FILTER (
                                   WHERE rt.estado_anterior = 'BORRADOR'
                                     AND rt.estado_nuevo = 'PUBLICADA') <> 1
                            OR count(*) FILTER (
                                   WHERE rt.estado_anterior = 'PUBLICADA'
                                     AND rt.estado_nuevo = 'VIGENTE') <> 1
                  ) invalid
                """, Integer.class, publicationId);
        return Objects.requireNonNull(count, "invalid requirement transition count");
    }

    private record ImportedRelease(ValidatedRelease release, UUID publicationId) { }
}
