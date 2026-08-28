package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialReadiness;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class LegalEditorialReadinessIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_editorial_readiness")
            .withUsername("ordenfix")
            .withPassword("ordenfix");

    private static LegalManifestPersistenceITSupport.Harness importHarness;
    private static LegalManifestPersistenceITSupport.ReadinessHarness readinessHarness;
    private static JdbcTemplate jdbc;

    @TempDir
    private Path temporaryDirectory;

    @BeforeAll
    static void migrateAndAssemble() {
        LegalManifestPersistenceITSupport.migrate(POSTGRES);
        var dataSource = LegalManifestPersistenceITSupport.directDataSource(
                POSTGRES,
                "legal-editorial-readiness-it");
        importHarness = LegalManifestPersistenceITSupport.harness(
                dataSource,
                LegalDatabaseBudgets.production());
        readinessHarness = LegalManifestPersistenceITSupport.readinessHarness(
                dataSource,
                LegalDatabaseBudgets.production());
        jdbc = importHarness.jdbc();
    }

    @BeforeEach
    void cleanLegalState() {
        LegalManifestPersistenceITSupport.cleanLegalState(jdbc);
    }

    @Test
    void missingTargetIsNotReadyWithACompleteEmptyObservation() throws Exception {
        ValidatedRelease release = pastEffectiveRelease("readiness-missing-v1");

        LegalEditorialReadinessResult result = readinessHarness.service().evaluate(release);

        assertThat(result.status()).isEqualTo(LegalManifestStatus.BLOCKED);
        assertThat(result.readiness()).isEqualTo(LegalEditorialReadiness.NOT_READY);
        assertThat(result.persisted()).isFalse();
        assertThat(result.observation()).isPresent();
        assertThat(result.observation().orElseThrow().publicationUuid()).isEmpty();
        assertThat(result.issues())
                .extracting(LegalManifestIssue::code)
                .containsExactly(LegalManifestIssueCode.PUBLICATION_NOT_SEALED);
    }

    @Test
    void sealedOriginGraphWithoutPromotionIsNotReadyAndNeverWrites() throws Exception {
        ValidatedRelease release = pastEffectiveRelease("readiness-draft-v1");
        LegalManifestImportResult imported = importHarness.importService()
                .importManifest(release);
        UUID publicationId = imported.receipt().orElseThrow().publicationUuid();
        Map<String, Long> rowsBefore =
                LegalManifestPersistenceITSupport.editorialTableCounts(jdbc);
        Map<String, LegalManifestPersistenceITSupport.SequenceState> sequencesBefore =
                LegalManifestPersistenceITSupport.editorialSequenceStates(jdbc);

        LegalEditorialReadinessResult result = readinessHarness.service().evaluate(release);

        assertThat(result.readiness()).isEqualTo(LegalEditorialReadiness.NOT_READY);
        assertThat(result.observation().orElseThrow().publicationUuid())
                .contains(publicationId);
        assertThat(result.issues())
                .extracting(LegalManifestIssue::code)
                .contains(
                        LegalManifestIssueCode.CURRENT_STATE_MISMATCH,
                        LegalManifestIssueCode.SCOPE_COVERAGE_INCOMPLETE);
        assertThat(LegalManifestPersistenceITSupport.editorialTableCounts(jdbc))
                .isEqualTo(rowsBefore);
        assertThat(LegalManifestPersistenceITSupport.editorialSequenceStates(jdbc))
                .isEqualTo(sequencesBefore);
    }

    @Test
    void exactPromotedProjectionIsReady() throws Exception {
        ReadyGraph graph = readyGraph("readiness-ready-v1");
        Map<String, Long> rowsBefore =
                LegalManifestPersistenceITSupport.editorialTableCounts(jdbc);
        Map<String, LegalManifestPersistenceITSupport.SequenceState> sequencesBefore =
                LegalManifestPersistenceITSupport.editorialSequenceStates(jdbc);

        LegalEditorialReadinessResult result = readinessHarness.service()
                .evaluate(graph.release());

        assertThat(result.status()).isEqualTo(LegalManifestStatus.PASS);
        assertThat(result.readiness()).isEqualTo(LegalEditorialReadiness.READY);
        assertThat(result.persisted()).isFalse();
        assertThat(result.issues()).isEmpty();
        var observation = result.observation().orElseThrow();
        assertThat(observation.publicationUuid()).contains(graph.publicationId());
        assertThat(observation.documentVersions()).isEqualTo(
                graph.release().documentCount());
        assertThat(observation.requirementVersions()).isEqualTo(
                graph.release().requirementCount());
        assertThat(observation.documentTransitions())
                .isEqualTo(graph.release().documentCount() * 2);
        assertThat(observation.requirementTransitions())
                .isEqualTo(graph.release().requirementCount() * 2);
        assertThat(observation.currentRequirementSets())
                .isEqualTo(graph.release().scopeCount());
        assertThat(rowsBefore).hasSize(19);
        assertThat(sequencesBefore).hasSize(10);
        assertThat(LegalManifestPersistenceITSupport.editorialTableCounts(jdbc))
                .isEqualTo(rowsBefore);
        assertThat(LegalManifestPersistenceITSupport.editorialSequenceStates(jdbc))
                .isEqualTo(sequencesBefore);
    }

    @Test
    void globalNonDraftHistoryChangesTheCompleteFingerprint() throws Exception {
        ReadyGraph graph = readyGraph("readiness-history-target-v1");
        LegalEditorialReadinessResult before = readinessHarness.service()
                .evaluate(graph.release());
        ValidatedRelease foreignRelease = pastEffectiveRelease(
                "readiness-history-foreign-v1");
        UUID foreignPublicationId = importHarness.importService()
                .importManifest(foreignRelease)
                .receipt()
                .orElseThrow()
                .publicationUuid();
        insertForeignNonDraftHistory(foreignPublicationId);

        LegalEditorialReadinessResult result = readinessHarness.service()
                .evaluate(graph.release());

        assertThat(result.readiness()).isEqualTo(LegalEditorialReadiness.READY);
        assertThat(result.issues()).isEmpty();
        var observationBefore = before.observation().orElseThrow();
        var observationAfter = result.observation().orElseThrow();
        assertThat(observationAfter.documentVersions())
                .isEqualTo(observationBefore.documentVersions() + 1);
        assertThat(observationAfter.requirementVersions())
                .isEqualTo(observationBefore.requirementVersions() + 1);
        assertThat(observationAfter.replacementLots())
                .isEqualTo(observationBefore.replacementLots() + 1);
        assertThat(observationAfter.editorialStateFingerprint())
                .isNotEqualTo(observationBefore.editorialStateFingerprint());
    }

    @Test
    void anOverflowFailsClosedWithoutAPartialFingerprintOrAnyWrite() throws Exception {
        ReadyGraph graph = readyGraph("readiness-overflow-v1");
        insertOverflowDocumentVersions(graph.publicationId());
        Map<String, Long> rowsBefore =
                LegalManifestPersistenceITSupport.editorialTableCounts(jdbc);
        Map<String, LegalManifestPersistenceITSupport.SequenceState> sequencesBefore =
                LegalManifestPersistenceITSupport.editorialSequenceStates(jdbc);

        LegalEditorialReadinessResult result = readinessHarness.service()
                .evaluate(graph.release());

        assertThat(result.status()).isEqualTo(LegalManifestStatus.ERROR);
        assertThat(result.readiness()).isEqualTo(LegalEditorialReadiness.ERROR);
        assertThat(result.persisted()).isFalse();
        assertThat(result.observation()).isEmpty();
        assertThat(result.issues())
                .extracting(LegalManifestIssue::code)
                .containsExactly(LegalManifestIssueCode.EDITORIAL_OBSERVATION_FAILED);
        assertThat(LegalManifestPersistenceITSupport.editorialTableCounts(jdbc))
                .isEqualTo(rowsBefore);
        assertThat(LegalManifestPersistenceITSupport.editorialSequenceStates(jdbc))
                .isEqualTo(sequencesBefore);
    }

    @Test
    void aForeignAdditionalSlotIsIndependentlyNotReadyAndIncludedInTheFingerprint()
            throws Exception {
        ReadyGraph graph = readyGraph("readiness-extra-slot-v1");
        LegalEditorialReadinessResult before = readinessHarness.service()
                .evaluate(graph.release());
        int slotsBefore = before.observation().orElseThrow().documentSlots();
        int pointersBefore = before.observation().orElseThrow().currentRequirementSets();
        String fingerprintBefore = before.observation().orElseThrow()
                .editorialStateFingerprint();
        insertAdditionalSlot(graph.publicationId());

        LegalEditorialReadinessResult result = readinessHarness.service()
                .evaluate(graph.release());

        assertThat(result.readiness()).isEqualTo(LegalEditorialReadiness.NOT_READY);
        assertThat(result.issues())
                .extracting(LegalManifestIssue::location)
                .contains("database/slots/additional");
        assertThat(result.observation().orElseThrow().documentSlots())
                .isEqualTo(slotsBefore + 1);
        assertThat(result.observation().orElseThrow().currentRequirementSets())
                .isEqualTo(pointersBefore);
        assertThat(result.observation().orElseThrow().editorialStateFingerprint())
                .isNotEqualTo(fingerprintBefore);
    }

    @Test
    void aForeignAdditionalPointerIsIndependentlyNotReadyAndIncludedInTheFingerprint()
            throws Exception {
        ReadyGraph graph = readyGraph("readiness-extra-pointer-v1");
        LegalEditorialReadinessResult before = readinessHarness.service()
                .evaluate(graph.release());
        int slotsBefore = before.observation().orElseThrow().documentSlots();
        int pointersBefore = before.observation().orElseThrow().currentRequirementSets();
        String fingerprintBefore = before.observation().orElseThrow()
                .editorialStateFingerprint();
        insertAdditionalPointer();

        LegalEditorialReadinessResult result = readinessHarness.service()
                .evaluate(graph.release());

        assertThat(result.readiness()).isEqualTo(LegalEditorialReadiness.NOT_READY);
        assertThat(result.issues())
                .extracting(LegalManifestIssue::location)
                .contains("database/pointers/additional");
        assertThat(result.observation().orElseThrow().documentSlots())
                .isEqualTo(slotsBefore);
        assertThat(result.observation().orElseThrow().currentRequirementSets())
                .isEqualTo(pointersBefore + 1);
        assertThat(result.observation().orElseThrow().editorialStateFingerprint())
                .isNotEqualTo(fingerprintBefore);
    }

    @Test
    void revisionDriftAndFutureEffectiveDateRemainTypedNotReadyFindings()
            throws Exception {
        ReadyGraph graph = readyGraph("readiness-typed-findings-v1");
        corruptRevisionAndEffectiveDate(graph.publicationId());

        LegalEditorialReadinessResult result = readinessHarness.service()
                .evaluate(graph.release());

        assertThat(result.readiness()).isEqualTo(LegalEditorialReadiness.NOT_READY);
        assertThat(result.observation()).isPresent();
        assertThat(result.issues())
                .extracting(LegalManifestIssue::code)
                .contains(
                        LegalManifestIssueCode.PUBLICATION_CONTENT_MISMATCH,
                        LegalManifestIssueCode.EFFECTIVE_DATE_NOT_REACHED,
                        LegalManifestIssueCode.REVISION_MISMATCH);
    }

    private ReadyGraph readyGraph(String externalId) throws Exception {
        ValidatedRelease release = pastEffectiveRelease(externalId);
        LegalManifestImportResult imported = importHarness.importService()
                .importManifest(release);
        UUID publicationId = imported.receipt().orElseThrow().publicationUuid();
        LegalManifestPersistenceITSupport.promoteToReady(jdbc, publicationId);
        return new ReadyGraph(release, publicationId);
    }

    private ValidatedRelease pastEffectiveRelease(String externalId) throws Exception {
        return LegalManifestPersistenceITSupport.copyRelease(
                temporaryDirectory,
                LegalEditorialReadinessIT.class,
                externalId,
                (manifestPath, manifest) -> manifest.withArray("documents")
                        .forEach(document -> ((ObjectNode) document).put(
                                "effectiveAt",
                                "2026-01-01T00:00:00-03:00")));
    }

    private static void insertAdditionalSlot(UUID publicationId) {
        Map<String, Object> version = jdbc.queryForMap("""
                SELECT dv.id AS version_id, dv.documento_linea_id AS line_id
                  FROM legal_publicacion_documentos pd
                  JOIN legal_documento_versiones dv
                    ON dv.id = pd.documento_version_id
                 WHERE pd.publicacion_id = ?
                 ORDER BY pd.manifest_ordinal
                 LIMIT 1
                """, publicationId);
        Set<String> occupied = new LinkedHashSet<>(jdbc.queryForList("""
                SELECT tipo || ':' || contexto
                  FROM legal_documento_vigentes
                 WHERE locale = 'es-AR'
                """, String.class));
        String type = null;
        String context = null;
        outer:
        for (TipoDocumentoLegal candidateType : TipoDocumentoLegal.values()) {
            for (ContextoLegal candidateContext : ContextoLegal.values()) {
                if (!occupied.contains(candidateType.name() + ':' + candidateContext.name())) {
                    type = candidateType.name();
                    context = candidateContext.name();
                    break outer;
                }
            }
        }
        assertThat(type).as("unused document slot type").isNotNull();
        String selectedType = java.util.Objects.requireNonNull(type);
        String selectedContext = java.util.Objects.requireNonNull(context);
        withReplicaRole(() -> jdbc.update("""
                    INSERT INTO legal_documento_vigentes
                        (tipo, locale, contexto, documento_version_id,
                         documento_linea_id, publicacion_id, estado_documento)
                    VALUES (?, 'es-AR', ?, ?, ?, ?, 'VIGENTE')
                    """, selectedType, selectedContext, version.get("version_id"),
                    version.get("line_id"), UUID.randomUUID()));
    }

    private static void insertAdditionalPointer() {
        Set<String> occupied = new LinkedHashSet<>(jdbc.queryForList("""
                SELECT locale || ':' || contexto || ':' || audiencia
                  FROM legal_requisito_conjuntos_actuales
                """, String.class));
        String locale = null;
        String context = null;
        String audience = null;
        outer:
        for (LocaleLegal candidateLocale : LocaleLegal.values()) {
            for (ContextoLegal candidateContext : ContextoLegal.values()) {
                for (AudienciaLegal candidateAudience : AudienciaLegal.values()) {
                    String key = candidateLocale.getCodigo() + ':'
                            + candidateContext.name() + ':'
                            + candidateAudience.name();
                    if (!occupied.contains(key)) {
                        locale = candidateLocale.getCodigo();
                        context = candidateContext.name();
                        audience = candidateAudience.name();
                        break outer;
                    }
                }
            }
        }
        String selectedLocale = java.util.Objects.requireNonNull(locale);
        String selectedContext = java.util.Objects.requireNonNull(context);
        String selectedAudience = java.util.Objects.requireNonNull(audience);
        UUID setId = UUID.randomUUID();
        UUID foreignPublicationId = UUID.randomUUID();
        withReplicaRole(() -> {
            jdbc.update("""
                    INSERT INTO legal_requisito_conjuntos
                        (id, publicacion_id, locale, contexto, audiencia,
                         required_set_revision, creado_en)
                    VALUES (?, ?, ?, ?, ?, ?, transaction_timestamp())
                    """, setId, foreignPublicationId, selectedLocale,
                    selectedContext, selectedAudience,
                    "sha256:" + "f".repeat(64));
            jdbc.update("""
                    INSERT INTO legal_requisito_conjuntos_actuales
                        (locale, contexto, audiencia, conjunto_id,
                         publicacion_id, actualizado_en)
                    VALUES (?, ?, ?, ?, ?, transaction_timestamp())
                    """, selectedLocale, selectedContext, selectedAudience,
                    setId, foreignPublicationId);
        });
    }

    private static void corruptRevisionAndEffectiveDate(UUID publicationId) {
        withReplicaRole(() -> {
            jdbc.update("""
                    UPDATE legal_requisito_conjuntos
                       SET required_set_revision = ?
                     WHERE id = (
                         SELECT id
                           FROM legal_requisito_conjuntos
                          WHERE publicacion_id = ?
                          ORDER BY locale, contexto, audiencia
                          LIMIT 1
                     )
                    """, "sha256:" + "0".repeat(64), publicationId);
            jdbc.update("""
                    UPDATE legal_documento_versiones
                       SET vigente_desde = '2035-01-01T00:00:00Z'
                     WHERE id = (
                         SELECT documento_version_id
                           FROM legal_publicacion_documentos
                          WHERE publicacion_id = ?
                          ORDER BY manifest_ordinal
                          LIMIT 1
                     )
                    """, publicationId);
        });
    }

    private static void insertForeignNonDraftHistory(UUID publicationId) {
        withReplicaRole(() -> {
            jdbc.update("""
                    INSERT INTO legal_documento_lineas
                        (id, clave, tipo, locale, publicacion_intro_id, creado_en)
                    VALUES (md5('readiness-foreign-document-line')::uuid,
                            'readiness-foreign-document-history',
                            'TERMINOS_SERVICIO', 'es-AR', ?, transaction_timestamp())
                    """, publicationId);
            jdbc.update("""
                    INSERT INTO legal_documento_versiones
                        (id, documento_linea_id, publicacion_intro_id, version,
                         lineage_ordinal, titulo, contenido_markdown, sha256,
                         vigente_desde, requires_reacceptance, estado,
                         estado_cambiado_en, ultimo_motivo, reemplazo_lote_id)
                    VALUES (md5('readiness-foreign-document-version')::uuid,
                            md5('readiness-foreign-document-line')::uuid, ?,
                            'history-v1', 1, 'Historia ajena', 'Historia ajena',
                            ?, transaction_timestamp(), false, 'PUBLICADA',
                            transaction_timestamp(), NULL, NULL)
                    """, publicationId, "e".repeat(64));
            jdbc.update("""
                    INSERT INTO legal_documento_reemplazo_lotes
                        (id, estado_construccion, creado_en, sellado_en)
                    VALUES (md5('readiness-foreign-replacement-batch')::uuid,
                            'SELLADO', transaction_timestamp(), transaction_timestamp())
                    """);
            jdbc.update("""
                    INSERT INTO legal_documento_reemplazo_anteriores
                        (id, lote_id, documento_version_id)
                    VALUES (9000001,
                            md5('readiness-foreign-replacement-batch')::uuid,
                            md5('readiness-foreign-document-version')::uuid)
                    """);
            jdbc.update("""
                    INSERT INTO legal_requisito_lineas
                        (id, clave, locale, contexto, tipo_acto,
                         publicacion_intro_id, creado_en)
                    VALUES (md5('readiness-foreign-requirement-line')::uuid,
                            'readiness-foreign-requirement-history', 'es-AR',
                            'USO_CONTINUADO', 'LECTURA', ?, transaction_timestamp())
                    """, publicationId);
            jdbc.update("""
                    INSERT INTO legal_requisito_versiones
                        (id, requisito_linea_id, publicacion_intro_id, version,
                         lineage_ordinal, afirmacion, afirmacion_sha256,
                         requerido, requires_reacceptance, estado,
                         estado_cambiado_en, ultimo_motivo)
                    VALUES (md5('readiness-foreign-requirement-version')::uuid,
                            md5('readiness-foreign-requirement-line')::uuid, ?,
                            'history-v1', 1, 'Historia ajena', ?, false, false,
                            'PUBLICADA', transaction_timestamp(), NULL)
                    """, publicationId, "d".repeat(64));
        });
    }

    private static void insertOverflowDocumentVersions(UUID publicationId) {
        withReplicaRole(() -> jdbc.update("""
                WITH source AS (
                    SELECT dv.documento_linea_id, dv.publicacion_intro_id,
                           dv.titulo, dv.contenido_markdown, dv.sha256,
                           dv.vigente_desde, dv.requires_reacceptance
                      FROM legal_documento_versiones dv
                     WHERE dv.publicacion_intro_id = ?
                     ORDER BY dv.id
                     LIMIT 1
                )
                INSERT INTO legal_documento_versiones
                    (id, documento_linea_id, publicacion_intro_id, version,
                     lineage_ordinal, titulo, contenido_markdown, sha256,
                     vigente_desde, requires_reacceptance, estado,
                     estado_cambiado_en, ultimo_motivo, reemplazo_lote_id)
                SELECT md5('readiness-overflow-' || generated.ordinal::text)::uuid,
                       source.documento_linea_id, source.publicacion_intro_id,
                       'overflow-' || generated.ordinal::text,
                       100000 + generated.ordinal,
                       source.titulo, source.contenido_markdown, source.sha256,
                       source.vigente_desde, source.requires_reacceptance,
                       'PUBLICADA', transaction_timestamp(), NULL, NULL
                  FROM source
                 CROSS JOIN generate_series(1, ?) AS generated(ordinal)
                """, publicationId,
                LegalEditorialReadinessCore.MAX_RELEVANT_DOCUMENT_VERSIONS + 1));
    }

    private static void withReplicaRole(Runnable mutation) {
        DataSourceTransactionManager manager = new DataSourceTransactionManager(
                java.util.Objects.requireNonNull(jdbc.getDataSource(), "dataSource"));
        TransactionTemplate transaction = new TransactionTemplate(manager);
        transaction.executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            mutation.run();
        });
    }

    private record ReadyGraph(ValidatedRelease release, UUID publicationId) { }
}
