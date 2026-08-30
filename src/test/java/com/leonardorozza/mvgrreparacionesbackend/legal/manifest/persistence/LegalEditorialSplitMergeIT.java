package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.ConfinedEditorialPlanReader;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialPlanValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialPlanValidator.ValidatedEditorialPlan;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialReadiness;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidation;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.assertEditorialConfirmed;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.cleanLegalState;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.copyRelease;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.harness;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.migrate;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.promoteToReady;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.restrictedApplyHarness;
import static org.assertj.core.api.Assertions.assertThat;

/** PostgreSQL accreditation for fresh split, merge and disjoint multibatch cutovers. */
@Testcontainers
class LegalEditorialSplitMergeIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Comparator<UUID> UUID_ORDER = Comparator.comparing(UUID::toString);
    private static final String EDITORIAL_ROLE = "ordenfix_legal_editorial_split_merge_it";
    private static final String EDITORIAL_PASSWORD = "split-merge-it-only";
    private static final UUID LOW_BATCH_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000001");
    private static final UUID HIGH_BATCH_ID = UUID.fromString(
            "ffffffff-ffff-ffff-ffff-ffffffffffff");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_editorial_split_merge")
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
        migrate(POSTGRES);
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
        cleanLegalState(owner);
        restrictedPrivileges.verify();
    }

    @Test
    void splitOneToTwoCommitsExactReadyGraphUnderRestrictedRole() throws Exception {
        ImportedRelease source = readyRelease(
                "split-source-v1",
                LegalManifestPersistenceITSupport.ReleaseMutation.NONE);
        ImportedRelease target = importedRelease("split-target-v1", (manifestPath, manifest) ->
                replaceDocument(
                        manifestPath,
                        manifest,
                        "aviso-clientes-taller",
                        List.of(
                                documentSpec(
                                        "aviso-clientes-fotos-v2",
                                        "split-v2",
                                        "ATESTACION_FOTOS"),
                                documentSpec(
                                        "aviso-clientes-credenciales-v2",
                                        "split-v2",
                                        "ATESTACION_CREDENCIALES")),
                        Map.of(
                                "ATESTACION_FOTOS", "aviso-clientes-fotos-v2",
                                "ATESTACION_CREDENCIALES",
                                "aviso-clientes-credenciales-v2"),
                        "split-v2"));
        BatchSpec split = new BatchSpec(
                stableUuid("batch:split-one-to-two"),
                List.of("aviso-clientes-taller"),
                List.of(
                        "aviso-clientes-fotos-v2",
                        "aviso-clientes-credenciales-v2"),
                List.of("ATESTACION_FOTOS", "ATESTACION_CREDENCIALES"));
        PlanFixture fixture = replacementPlan(
                source,
                target,
                "split-one-to-two",
                List.of(split));

        assertThat(fixture.batches()).singleElement().satisfies(batch -> {
            assertThat(batch.predecessors()).hasSize(1);
            assertThat(batch.successors()).hasSize(2);
        });
        assertFreshApply(
                source,
                target,
                fixture,
                new ExpectedReceipt(12, 6, 24, 12, 21, 8, 1));
    }

    @Test
    void mergeTwoToOneCommitsExactReadyGraphUnderRestrictedRole() throws Exception {
        ImportedRelease source = readyRelease("merge-source-v1", (manifestPath, manifest) ->
                replaceDocument(
                        manifestPath,
                        manifest,
                        "aviso-clientes-taller",
                        List.of(
                                documentSpec(
                                        "aviso-clientes-fotos-source",
                                        "merge-source-v2",
                                        "ATESTACION_FOTOS"),
                                documentSpec(
                                        "aviso-clientes-credenciales-source",
                                        "merge-source-v2",
                                        "ATESTACION_CREDENCIALES")),
                        Map.of(
                                "ATESTACION_FOTOS", "aviso-clientes-fotos-source",
                                "ATESTACION_CREDENCIALES",
                                "aviso-clientes-credenciales-source"),
                        "merge-source-v2"));
        ImportedRelease target = importedRelease("merge-target-v1", (manifestPath, manifest) ->
                replaceDocument(
                        manifestPath,
                        manifest,
                        "aviso-clientes-taller",
                        List.of(new DocumentSpec(
                                "aviso-clientes-unificado-v3",
                                "merge-target-v3",
                                "aviso-clientes-unificado-v3.md",
                                List.of(
                                        "ATESTACION_FOTOS",
                                        "ATESTACION_CREDENCIALES"))),
                        Map.of(
                                "ATESTACION_FOTOS", "aviso-clientes-unificado-v3",
                                "ATESTACION_CREDENCIALES", "aviso-clientes-unificado-v3"),
                        "merge-target-v3"));
        BatchSpec merge = new BatchSpec(
                stableUuid("batch:merge-two-to-one"),
                List.of(
                        "aviso-clientes-fotos-source",
                        "aviso-clientes-credenciales-source"),
                List.of("aviso-clientes-unificado-v3"),
                List.of("ATESTACION_FOTOS", "ATESTACION_CREDENCIALES"));
        PlanFixture fixture = replacementPlan(
                source,
                target,
                "merge-two-to-one",
                List.of(merge));

        assertThat(fixture.batches()).singleElement().satisfies(batch -> {
            assertThat(batch.predecessors()).hasSize(2);
            assertThat(batch.successors()).hasSize(1);
        });
        assertFreshApply(
                source,
                target,
                fixture,
                new ExpectedReceipt(11, 6, 22, 12, 21, 8, 1));
    }

    @Test
    void disjointSplitAndMergeCommitTogetherWithExactPostStateUnderRestrictedRole()
            throws Exception {
        ImportedRelease source = readyRelease("multibatch-source-v1", (manifestPath, manifest) ->
                replaceDocument(
                        manifestPath,
                        manifest,
                        "terminos",
                        List.of(
                                new DocumentSpec(
                                        "terminos-registro-uso-source",
                                        "multibatch-source-v2",
                                        "terminos-registro-uso-source.md",
                                        List.of("REGISTRO", "USO_CONTINUADO")),
                                documentSpec(
                                        "terminos-pro-source",
                                        "multibatch-source-v2",
                                        "CONTRATACION_PRO")),
                        Map.of(
                                "REGISTRO", "terminos-registro-uso-source",
                                "CONTRATACION_PRO", "terminos-pro-source"),
                        "multibatch-source-v2"));
        ImportedRelease target = importedRelease("multibatch-target-v1", (manifestPath, manifest) -> {
            replaceDocument(
                    manifestPath,
                    manifest,
                    "aviso-clientes-taller",
                    List.of(
                            documentSpec(
                                    "aviso-clientes-fotos-target",
                                    "multibatch-target-v3",
                                    "ATESTACION_FOTOS"),
                            documentSpec(
                                    "aviso-clientes-credenciales-target",
                                    "multibatch-target-v3",
                                    "ATESTACION_CREDENCIALES")),
                    Map.of(
                            "ATESTACION_FOTOS", "aviso-clientes-fotos-target",
                            "ATESTACION_CREDENCIALES",
                            "aviso-clientes-credenciales-target"),
                    "multibatch-target-v3");
            bumpRequirementsReferencing(
                    manifest,
                    Set.of("terminos"),
                    "multibatch-target-v3");
        });

        BatchKeys splitKeys = new BatchKeys(
                List.of("aviso-clientes-taller"),
                List.of(
                        "aviso-clientes-fotos-target",
                        "aviso-clientes-credenciales-target"));
        BatchKeys mergeKeys = new BatchKeys(
                List.of(
                        "terminos-registro-uso-source",
                        "terminos-pro-source"),
                List.of("terminos"));
        AdversarialBatchIds ids = adversarialBatchIds(
                source,
                target,
                splitKeys,
                mergeKeys);
        BatchSpec split = new BatchSpec(
                ids.splitBatchId(),
                splitKeys.predecessorKeys(),
                splitKeys.successorKeys(),
                List.of("ATESTACION_FOTOS", "ATESTACION_CREDENCIALES"));
        BatchSpec merge = new BatchSpec(
                ids.mergeBatchId(),
                mergeKeys.predecessorKeys(),
                mergeKeys.successorKeys(),
                List.of("REGISTRO", "USO_CONTINUADO", "CONTRATACION_PRO"));
        List<BatchSpec> batchIdOrder = List.of(split, merge).stream()
                .sorted(Comparator.comparing(batch -> batch.batchId().toString()))
                .toList();
        PlanFixture fixture = replacementPlan(
                source,
                target,
                "disjoint-multibatch",
                batchIdOrder);

        assertThat(Integer.signum(ids.splitMemberMinimum().compareTo(
                        ids.mergeMemberMinimum())))
                .isEqualTo(-Integer.signum(ids.splitBatchId().toString().compareTo(
                        ids.mergeBatchId().toString())));
        assertThat(fixture.batches())
                .extracting(batch -> minimumMember(batch).toString())
                .isSorted();
        assertThat(fixture.batches())
                .extracting(batch -> batch.predecessors().size()
                        + "->" + batch.successors().size())
                .containsExactlyInAnyOrder("1->2", "2->1");
        assertFreshApply(
                source,
                target,
                fixture,
                new ExpectedReceipt(12, 6, 24, 12, 21, 8, 2));
    }

    private void assertFreshApply(
            ImportedRelease source,
            ImportedRelease target,
            PlanFixture fixture,
            ExpectedReceipt expectedReceipt) {
        assertThat(owner.queryForObject(
                "SELECT count(*) FROM legal_documento_reemplazo_lotes",
                Long.class)).isZero();
        assertThat(apply.jdbc().queryForObject(
                "SELECT current_user", String.class)).isEqualTo(EDITORIAL_ROLE);
        assertThat(apply.readinessCore().evaluate(
                target.release(), databaseNow()).readiness())
                .isEqualTo(LegalEditorialReadiness.NOT_READY);
        assertSealedPublication(source.publicationId());
        assertSealedPublication(target.publicationId());
        Map<String, String> immutableBefore = immutableOriginRows();
        Map<String, String> externalBefore = externalAcceptanceRows();

        LegalEditorialApplyResult result = apply.service().applyReplace(
                target.release(), fixture.plan());

        assertEditorialConfirmed(result, LegalEditorialApplyResult.Outcome.APPLIED);
        LegalEditorialApplyReceipt receipt = result.receipt().orElseThrow();
        assertThat(result.appliedAt()).contains(receipt.appliedAt());
        assertReceiptAgainstSql(target, fixture, receipt, expectedReceipt);
        assertExactBatches(target, fixture, receipt.appliedAt());
        assertExactDocumentStatesHistoryAndSlots(target, fixture, receipt.appliedAt());
        assertExactRequirementStatesAndHistory(target, fixture, receipt.appliedAt());
        assertExactCurrentPointers(target, receipt.appliedAt());
        assertThat(immutableOriginRows()).isEqualTo(immutableBefore);
        assertThat(externalAcceptanceRows()).isEqualTo(externalBefore);
        assertThat(apply.readinessCore().evaluate(
                target.release(), receipt.appliedAt()).readiness())
                .isEqualTo(LegalEditorialReadiness.READY);
        assertSealedPublication(source.publicationId());
        assertSealedPublication(target.publicationId());
        assertThat(apply.jdbc().queryForObject(
                "SELECT current_user", String.class)).isEqualTo(EDITORIAL_ROLE);
        restrictedPrivileges.verify();
    }

    private static void assertReceiptAgainstSql(
            ImportedRelease target,
            PlanFixture fixture,
            LegalEditorialApplyReceipt receipt,
            ExpectedReceipt expected) {
        assertThat(receipt.operationType())
                .isEqualTo(LegalEditorialApplyReceipt.OperationType.REPLACE);
        assertThat(receipt.targetPublicationUuid()).isEqualTo(target.publicationId());
        assertThat(receipt.readinessAfter()).isEqualTo(LegalEditorialReadiness.READY);
        assertThat(receipt.documentVersions()).isEqualTo(target.release().documentCount());
        assertThat(receipt.requirementVersions()).isEqualTo(target.release().requirementCount());
        assertThat(receipt.replacementBatches()).isEqualTo(fixture.batches().size());
        assertThat(new ExpectedReceipt(
                receipt.documentVersions(),
                receipt.requirementVersions(),
                receipt.documentTransitions(),
                receipt.requirementTransitions(),
                receipt.documentSlots(),
                receipt.requiredSetPointers(),
                receipt.replacementBatches())).isEqualTo(expected);
        assertThat(receipt.documentVersions()).isEqualTo(intCount("""
                SELECT count(*)
                  FROM legal_publicacion_documentos
                 WHERE publicacion_id = ?
                """, target.publicationId()));
        assertThat(receipt.requirementVersions()).isEqualTo(intCount("""
                SELECT count(*)
                  FROM legal_publicacion_requisitos
                 WHERE publicacion_id = ?
                """, target.publicationId()));
        assertThat(receipt.documentTransitions()).isEqualTo(intCount("""
                SELECT count(*)
                  FROM legal_documento_transiciones transition
                 WHERE EXISTS (
                       SELECT 1
                         FROM legal_publicacion_documentos publication_document
                        WHERE publication_document.publicacion_id = ?
                          AND publication_document.documento_version_id =
                              transition.documento_version_id
                 )
                """, target.publicationId()));
        assertThat(receipt.requirementTransitions()).isEqualTo(intCount("""
                SELECT count(*)
                  FROM legal_requisito_transiciones transition
                 WHERE EXISTS (
                       SELECT 1
                         FROM legal_publicacion_requisitos publication_requirement
                        WHERE publication_requirement.publicacion_id = ?
                          AND publication_requirement.requisito_version_id =
                              transition.requisito_version_id
                 )
                """, target.publicationId()));
        assertThat(receipt.documentSlots()).isEqualTo(intCount("""
                SELECT count(*)
                  FROM legal_documento_vigentes
                 WHERE publicacion_id = ?
                """, target.publicationId()));
        assertThat(receipt.requiredSetPointers()).isEqualTo(intCount("""
                SELECT count(*)
                  FROM legal_requisito_conjuntos_actuales
                 WHERE publicacion_id = ?
                """, target.publicationId()));
        assertThat(receipt.replacementBatches()).isEqualTo(intCount("""
                SELECT count(DISTINCT transition.reemplazo_lote_id)
                  FROM legal_documento_transiciones transition
                 WHERE transition.reemplazo_lote_id IS NOT NULL
                   AND EXISTS (
                       SELECT 1
                         FROM legal_publicacion_documentos publication_document
                        WHERE publication_document.publicacion_id = ?
                          AND publication_document.documento_version_id =
                              transition.documento_version_id
                   )
                """, target.publicationId()));
        assertThat(receipt.documentSlots()).isEqualTo(21);
        assertThat(receipt.requiredSetPointers()).isEqualTo(8);
    }

    private static void assertExactBatches(
            ImportedRelease target,
            PlanFixture fixture,
            Instant appliedAt) {
        assertThat(owner.queryForList("""
                SELECT id
                  FROM legal_documento_reemplazo_lotes
                 ORDER BY id::text
                """, UUID.class))
                .containsExactlyElementsOf(sortedIds(fixture.batchIds()));
        for (ResolvedBatch batch : fixture.batches()) {
            BatchHeader header = owner.queryForObject("""
                    SELECT estado_construccion, creado_en, sellado_en
                      FROM legal_documento_reemplazo_lotes
                     WHERE id = ?
                    """, (resultSet, rowNumber) -> new BatchHeader(
                    resultSet.getString("estado_construccion"),
                    resultSet.getObject("creado_en", OffsetDateTime.class).toInstant(),
                    resultSet.getObject("sellado_en", OffsetDateTime.class).toInstant()),
                    batch.batchId());
            assertThat(header).isEqualTo(new BatchHeader(
                    "SELLADO", appliedAt, appliedAt));
            assertThat(owner.queryForList("""
                    SELECT documento_version_id
                      FROM legal_documento_reemplazo_anteriores
                     WHERE lote_id = ?
                     ORDER BY documento_version_id::text
                    """, UUID.class, batch.batchId()))
                    .containsExactlyElementsOf(sortedDocumentIds(batch.predecessors()));
            assertThat(owner.queryForList("""
                    SELECT documento_version_id
                      FROM legal_documento_reemplazo_sucesoras
                     WHERE lote_id = ?
                       AND publicacion_id = ?
                     ORDER BY documento_version_id::text
                    """, UUID.class, batch.batchId(), target.publicationId()))
                    .containsExactlyElementsOf(sortedDocumentIds(batch.successors()));
            assertThat(owner.queryForObject("""
                    SELECT count(*)
                      FROM legal_documento_reemplazo_sucesoras
                     WHERE lote_id = ?
                       AND publicacion_id <> ?
                    """, Long.class, batch.batchId(), target.publicationId())).isZero();
        }
    }

    private static void assertExactDocumentStatesHistoryAndSlots(
            ImportedRelease target,
            PlanFixture fixture,
            Instant appliedAt) {
        for (ResolvedBatch batch : fixture.batches()) {
            for (DocumentVersion predecessor : batch.predecessors()) {
                assertDocumentState(
                        predecessor.id(), "REEMPLAZADA", batch.batchId(), appliedAt);
                List<TransitionRow> history = documentHistory(predecessor.id());
                assertThat(history).hasSize(3);
                Instant promotedAt = history.getFirst().occurredAt();
                assertThat(history)
                        .containsExactly(
                                new TransitionRow(
                                        "BORRADOR", "PUBLICADA", null, promotedAt),
                                new TransitionRow(
                                        "PUBLICADA", "VIGENTE", null, promotedAt),
                                new TransitionRow(
                                        "VIGENTE", "REEMPLAZADA", batch.batchId(), appliedAt));
                assertThat(owner.queryForObject("""
                        SELECT count(*)
                          FROM legal_documento_vigentes
                         WHERE documento_version_id = ?
                        """, Long.class, predecessor.id())).isZero();
            }
            for (DocumentVersion successor : batch.successors()) {
                assertDocumentState(successor.id(), "VIGENTE", batch.batchId(), appliedAt);
                assertThat(documentHistory(successor.id())).containsExactly(
                        new TransitionRow(
                                "BORRADOR", "PUBLICADA", null, appliedAt),
                        new TransitionRow(
                                "PUBLICADA", "VIGENTE", batch.batchId(), appliedAt));
                List<DocumentSlot> slots = owner.query("""
                        SELECT tipo, locale, contexto, documento_version_id,
                               documento_linea_id, publicacion_id, estado_documento
                          FROM legal_documento_vigentes
                         WHERE documento_version_id = ?
                         ORDER BY contexto
                        """, (resultSet, rowNumber) -> new DocumentSlot(
                        resultSet.getString("tipo"),
                        resultSet.getString("locale"),
                        resultSet.getString("contexto"),
                        resultSet.getObject("documento_version_id", UUID.class),
                        resultSet.getObject("documento_linea_id", UUID.class),
                        resultSet.getObject("publicacion_id", UUID.class),
                        resultSet.getString("estado_documento")), successor.id());
                assertThat(slots).extracting(DocumentSlot::context)
                        .containsExactlyElementsOf(successor.contexts().stream().sorted().toList());
                assertThat(slots).allSatisfy(slot -> {
                    assertThat(slot.type()).isEqualTo(successor.type());
                    assertThat(slot.locale()).isEqualTo(successor.locale());
                    assertThat(slot.documentVersionId()).isEqualTo(successor.id());
                    assertThat(slot.documentLineId()).isEqualTo(successor.lineId());
                    assertThat(slot.publicationId()).isEqualTo(target.publicationId());
                    assertThat(slot.state()).isEqualTo("VIGENTE");
                });
            }
        }
        assertThat(owner.queryForObject(
                "SELECT count(*) FROM legal_documento_vigentes",
                Long.class)).isEqualTo(21L);
        assertThat(owner.queryForObject("""
                SELECT count(*)
                  FROM legal_documento_vigentes
                 WHERE publicacion_id <> ?
                """, Long.class, target.publicationId())).isZero();
        assertThat(owner.queryForObject("""
                SELECT count(*)
                  FROM legal_documento_vigentes active
                 WHERE NOT EXISTS (
                       SELECT 1
                         FROM legal_publicacion_documentos publication_document
                         JOIN legal_documento_versiones version
                           ON version.id = publication_document.documento_version_id
                         JOIN legal_documento_lineas line
                           ON line.id = version.documento_linea_id
                         JOIN legal_documento_contextos document_context
                           ON document_context.documento_version_id = version.id
                        WHERE publication_document.publicacion_id = ?
                          AND active.tipo = line.tipo
                          AND active.locale = line.locale
                          AND active.contexto = document_context.contexto
                          AND active.documento_version_id = version.id
                          AND active.documento_linea_id = line.id
                          AND active.publicacion_id = publication_document.publicacion_id
                 )
                """, Long.class, target.publicationId())).isZero();
        assertThat(owner.queryForObject("""
                SELECT count(*)
                  FROM legal_publicacion_documentos publication_document
                  JOIN legal_documento_versiones version
                    ON version.id = publication_document.documento_version_id
                 WHERE publication_document.publicacion_id = ?
                   AND version.estado <> 'VIGENTE'
                """, Long.class, target.publicationId())).isZero();
    }

    private static void assertExactRequirementStatesAndHistory(
            ImportedRelease target,
            PlanFixture fixture,
            Instant appliedAt) {
        for (UUID predecessorId : fixture.requirementPredecessors()) {
            RequirementState state = requirementState(predecessorId);
            assertThat(state.state()).isEqualTo("REEMPLAZADA");
            assertThat(state.changedAt()).isEqualTo(appliedAt);
            List<RequirementTransitionRow> history = requirementHistory(predecessorId);
            assertThat(history).hasSize(3);
            assertThat(history.get(0).previousState()).isEqualTo("BORRADOR");
            assertThat(history.get(0).newState()).isEqualTo("PUBLICADA");
            assertThat(history.get(1).previousState()).isEqualTo("PUBLICADA");
            assertThat(history.get(1).newState()).isEqualTo("VIGENTE");
            assertThat(history.get(2)).isEqualTo(new RequirementTransitionRow(
                    "VIGENTE", "REEMPLAZADA", appliedAt));
        }
        for (UUID successorId : fixture.requirementSuccessors()) {
            RequirementState state = requirementState(successorId);
            assertThat(state.state()).isEqualTo("VIGENTE");
            assertThat(state.changedAt()).isEqualTo(appliedAt);
            assertThat(requirementHistory(successorId)).containsExactly(
                    new RequirementTransitionRow("BORRADOR", "PUBLICADA", appliedAt),
                    new RequirementTransitionRow("PUBLICADA", "VIGENTE", appliedAt));
        }
        assertThat(owner.queryForObject("""
                SELECT count(*)
                  FROM legal_publicacion_requisitos publication_requirement
                  JOIN legal_requisito_versiones version
                    ON version.id = publication_requirement.requisito_version_id
                 WHERE publication_requirement.publicacion_id = ?
                   AND version.estado <> 'VIGENTE'
                """, Long.class, target.publicationId())).isZero();
    }

    private static void assertExactCurrentPointers(
            ImportedRelease target,
            Instant appliedAt) {
        List<CurrentPointer> pointers = owner.query("""
                SELECT locale, contexto, audiencia, publicacion_id, actualizado_en
                  FROM legal_requisito_conjuntos_actuales
                 ORDER BY locale, contexto, audiencia
                """, (resultSet, rowNumber) -> new CurrentPointer(
                resultSet.getString("locale"),
                resultSet.getString("contexto"),
                resultSet.getString("audiencia"),
                resultSet.getObject("publicacion_id", UUID.class),
                resultSet.getObject("actualizado_en", OffsetDateTime.class).toInstant()));
        assertThat(pointers).hasSize(8).allSatisfy(pointer -> {
            assertThat(pointer.locale()).isEqualTo("es-AR");
            assertThat(pointer.publicationId()).isEqualTo(target.publicationId());
            assertThat(pointer.updatedAt()).isEqualTo(appliedAt);
        });
        assertThat(owner.queryForObject("""
                SELECT count(*)
                  FROM legal_requisito_conjuntos_actuales active
                 WHERE NOT EXISTS (
                       SELECT 1
                         FROM legal_requisito_conjuntos required_set
                        WHERE required_set.publicacion_id = ?
                          AND required_set.id = active.conjunto_id
                          AND required_set.locale = active.locale
                          AND required_set.contexto = active.contexto
                          AND required_set.audiencia = active.audiencia
                 )
                """, Long.class, target.publicationId())).isZero();
    }

    private ImportedRelease readyRelease(
            String externalId,
            LegalManifestPersistenceITSupport.ReleaseMutation mutation) throws Exception {
        ImportedRelease imported = importedRelease(externalId, mutation);
        promoteToReady(owner, imported.publicationId());
        assertThat(apply.readinessCore().evaluate(
                imported.release(), databaseNow()).readiness())
                .isEqualTo(LegalEditorialReadiness.READY);
        return imported;
    }

    private ImportedRelease importedRelease(
            String externalId,
            LegalManifestPersistenceITSupport.ReleaseMutation mutation) throws Exception {
        ValidatedRelease release = copyRelease(
                temporaryDirectory,
                LegalEditorialSplitMergeIT.class,
                externalId,
                (manifestPath, manifest) -> {
                    manifest.withArray("documents").forEach(document ->
                            ((ObjectNode) document).put(
                                    "effectiveAt", "2026-01-01T00:00:00-03:00"));
                    mutation.apply(manifestPath, manifest);
                });
        LegalManifestImportResult result = importer.importService().importManifest(release);
        assertThat(result.status())
                .as("publication=%s issues=%s", externalId, result.issues())
                .isEqualTo(LegalManifestStatus.PASS);
        UUID publicationId = result.receipt().orElseThrow().publicationUuid();
        return new ImportedRelease(release, publicationId);
    }

    private PlanFixture replacementPlan(
            ImportedRelease source,
            ImportedRelease target,
            String operationSeed,
            List<BatchSpec> batchSpecs) throws Exception {
        List<DocumentVersion> sourceDocuments = documents(source.publicationId());
        List<DocumentVersion> activeSourceDocuments = sourceDocuments.stream()
                .filter(document -> "VIGENTE".equals(document.state()))
                .toList();
        List<DocumentVersion> targetDocuments = documents(target.publicationId());
        List<RequirementVersion> sourceRequirements = requirements(source.publicationId());
        List<RequirementVersion> activeSourceRequirements = sourceRequirements.stream()
                .filter(requirement -> "VIGENTE".equals(requirement.state()))
                .toList();
        List<RequirementVersion> targetRequirements = requirements(target.publicationId());

        Map<UUID, DocumentVersion> sourceDocumentsById = indexById(
                activeSourceDocuments, DocumentVersion::id);
        Map<UUID, DocumentVersion> targetDocumentsById = indexById(
                targetDocuments, DocumentVersion::id);
        Map<String, DocumentVersion> sourceDocumentsByKey = indexByKey(activeSourceDocuments);
        Map<String, DocumentVersion> targetDocumentsByKey = indexByKey(targetDocuments);
        Map<UUID, RequirementVersion> sourceRequirementsById = indexById(
                activeSourceRequirements, RequirementVersion::id);
        Map<UUID, RequirementVersion> targetRequirementsById = indexById(
                targetRequirements, RequirementVersion::id);
        Map<UUID, RequirementVersion> sourceRequirementsByLine = indexById(
                activeSourceRequirements, RequirementVersion::lineId);
        Map<UUID, RequirementVersion> targetRequirementsByLine = indexById(
                targetRequirements, RequirementVersion::lineId);

        List<ResolvedBatch> resolvedBatches = batchSpecs.stream()
                .map(spec -> resolveBatch(
                        spec,
                        sourceDocumentsByKey,
                        targetDocumentsByKey))
                .toList();
        requireDisjointBatches(resolvedBatches);
        Set<UUID> predecessorIds = resolvedBatches.stream()
                .flatMap(batch -> batch.predecessors().stream())
                .map(DocumentVersion::id)
                .collect(Collectors.toUnmodifiableSet());
        Set<UUID> successorIds = resolvedBatches.stream()
                .flatMap(batch -> batch.successors().stream())
                .map(DocumentVersion::id)
                .collect(Collectors.toUnmodifiableSet());

        String sourceExternalId = source.release().plan().manifest().publicationId();
        String fingerprint = apply.readinessCore()
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

        for (DocumentVersion document : targetDocuments) {
            if (sourceDocumentsById.containsKey(document.id())) {
                addDocumentScoped(plan.withArray("documentReuses"), document);
            } else if (!successorIds.contains(document.id())) {
                addDocumentScoped(plan.withArray("documentAdditions"), document);
            }
        }
        for (ResolvedBatch batch : resolvedBatches) {
            ObjectNode node = plan.withArray("documentReplacementBatches").addObject();
            node.put("replacementBatchId", batch.batchId().toString());
            batch.contexts().forEach(node.putArray("contexts")::add);
            batch.predecessors().forEach(predecessor ->
                    addDocumentRef(node.withArray("predecessors"), predecessor));
            batch.successors().forEach(successor ->
                    addDocumentRef(node.withArray("successors"), successor));
        }
        for (DocumentVersion document : activeSourceDocuments) {
            if (!targetDocumentsById.containsKey(document.id())
                    && !predecessorIds.contains(document.id())) {
                ObjectNode retirement = plan.withArray("documentRetirements").addObject();
                retirement.put("documentVersionId", document.id().toString());
                retirement.put("sha256", document.sha256());
                document.contexts().forEach(retirement.putArray("contexts")::add);
                retirement.put("reason", "Retiro documental de fixture split/merge");
            }
        }

        LinkedHashSet<UUID> requirementPredecessors = new LinkedHashSet<>();
        LinkedHashSet<UUID> requirementSuccessors = new LinkedHashSet<>();
        for (RequirementVersion requirement : targetRequirements) {
            if (sourceRequirementsById.containsKey(requirement.id())) {
                addRequirementScoped(plan.withArray("requirementReuses"), requirement);
            } else if (!sourceRequirementsByLine.containsKey(requirement.lineId())) {
                addRequirementScoped(plan.withArray("requirementAdditions"), requirement);
                requirementSuccessors.add(requirement.id());
            } else {
                RequirementVersion predecessor = sourceRequirementsByLine.get(
                        requirement.lineId());
                ObjectNode replacement = plan.withArray("requirementReplacements").addObject();
                addRequirementRef(replacement.putObject("predecessor"), predecessor);
                addRequirementRef(replacement.putObject("successor"), requirement);
                replacement.put("context", requirement.context());
                requirement.audiences().forEach(
                        replacement.putArray("audiences")::add);
                requirementPredecessors.add(predecessor.id());
                requirementSuccessors.add(requirement.id());
            }
        }
        for (RequirementVersion requirement : activeSourceRequirements) {
            if (!targetRequirementsById.containsKey(requirement.id())
                    && !targetRequirementsByLine.containsKey(requirement.lineId())) {
                ObjectNode retirement = plan.withArray("requirementRetirements").addObject();
                retirement.put("requirementVersionId", requirement.id().toString());
                retirement.put("statementSha256", requirement.sha256());
                retirement.put("context", requirement.context());
                requirement.audiences().forEach(
                        retirement.putArray("audiences")::add);
                retirement.put("reason", "Retiro de requisito de fixture split/merge");
            }
        }

        ValidatedEditorialPlan validated = validatePlan(plan, operationId);
        List<ResolvedBatch> canonicalBatches = validated.plan().documentReplacementBatches()
                .stream()
                .map(batch -> resolvedBatches.stream()
                        .filter(candidate -> candidate.batchId().equals(
                                batch.replacementBatchId()))
                        .findFirst()
                        .orElseThrow())
                .toList();
        return new PlanFixture(
                validated,
                canonicalBatches,
                Set.copyOf(requirementPredecessors),
                Set.copyOf(requirementSuccessors));
    }

    private ValidatedEditorialPlan validatePlan(ObjectNode plan, UUID operationId)
            throws Exception {
        Path directory = temporaryDirectory.toRealPath()
                .resolve("split-merge-plan-" + operationId);
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

    private static ResolvedBatch resolveBatch(
            BatchSpec spec,
            Map<String, DocumentVersion> sourceDocuments,
            Map<String, DocumentVersion> targetDocuments) {
        List<DocumentVersion> predecessors = spec.predecessorKeys().stream()
                .map(key -> requireDocument(sourceDocuments, key))
                .toList();
        List<DocumentVersion> successors = spec.successorKeys().stream()
                .map(key -> requireDocument(targetDocuments, key))
                .toList();
        assertThat(predecessors).isNotEmpty();
        assertThat(successors).isNotEmpty();
        assertThat(predecessors.size() == 1 || successors.size() == 1).isTrue();
        assertThat(predecessors).extracting(DocumentVersion::type)
                .containsOnly(predecessors.getFirst().type());
        assertThat(successors).extracting(DocumentVersion::type)
                .containsOnly(predecessors.getFirst().type());
        assertThat(predecessors).extracting(DocumentVersion::locale)
                .containsOnly(predecessors.getFirst().locale());
        assertThat(successors).extracting(DocumentVersion::locale)
                .containsOnly(predecessors.getFirst().locale());
        assertThat(contextUnion(predecessors)).containsExactlyInAnyOrderElementsOf(spec.contexts());
        assertThat(contextUnion(successors)).containsExactlyInAnyOrderElementsOf(spec.contexts());
        return new ResolvedBatch(
                spec.batchId(),
                sortedDocuments(predecessors),
                sortedDocuments(successors),
                List.copyOf(spec.contexts()));
    }

    private static void requireDisjointBatches(List<ResolvedBatch> batches) {
        LinkedHashSet<UUID> seen = new LinkedHashSet<>();
        for (ResolvedBatch batch : batches) {
            batch.predecessors().forEach(document ->
                    assertThat(seen.add(document.id())).isTrue());
            batch.successors().forEach(document ->
                    assertThat(seen.add(document.id())).isTrue());
        }
    }

    private static AdversarialBatchIds adversarialBatchIds(
            ImportedRelease source,
            ImportedRelease target,
            BatchKeys split,
            BatchKeys merge) {
        Map<String, DocumentVersion> sourceDocuments = indexByKey(
                documents(source.publicationId()));
        Map<String, DocumentVersion> targetDocuments = indexByKey(
                documents(target.publicationId()));
        String splitMinimum = minimumMember(
                split, sourceDocuments, targetDocuments).toString();
        String mergeMinimum = minimumMember(
                merge, sourceDocuments, targetDocuments).toString();
        boolean splitFirst = splitMinimum.compareTo(mergeMinimum) < 0;
        return new AdversarialBatchIds(
                splitFirst ? HIGH_BATCH_ID : LOW_BATCH_ID,
                splitFirst ? LOW_BATCH_ID : HIGH_BATCH_ID,
                splitMinimum,
                mergeMinimum);
    }

    private static UUID minimumMember(
            BatchKeys keys,
            Map<String, DocumentVersion> sourceDocuments,
            Map<String, DocumentVersion> targetDocuments) {
        return java.util.stream.Stream.concat(
                        keys.predecessorKeys().stream()
                                .map(key -> requireDocument(sourceDocuments, key).id()),
                        keys.successorKeys().stream()
                                .map(key -> requireDocument(targetDocuments, key).id()))
                .min(UUID_ORDER)
                .orElseThrow();
    }

    private static UUID minimumMember(ResolvedBatch batch) {
        return java.util.stream.Stream.concat(
                        batch.predecessors().stream().map(DocumentVersion::id),
                        batch.successors().stream().map(DocumentVersion::id))
                .min(UUID_ORDER)
                .orElseThrow();
    }

    private static void replaceDocument(
            Path manifestPath,
            ObjectNode manifest,
            String originalKey,
            List<DocumentSpec> replacements,
            Map<String, String> replacementByRequirementContext,
            String requirementVersion) throws Exception {
        ArrayNode documents = manifest.withArray("documents");
        int originalIndex = -1;
        ObjectNode template = null;
        for (int index = 0; index < documents.size(); index++) {
            if (originalKey.equals(documents.get(index).path("key").textValue())) {
                originalIndex = index;
                template = (ObjectNode) documents.get(index);
                break;
            }
        }
        if (template == null) {
            throw new IllegalArgumentException("Documento de fixture inexistente: " + originalKey);
        }
        documents.remove(originalIndex);
        for (int index = 0; index < replacements.size(); index++) {
            DocumentSpec replacement = replacements.get(index);
            String markdown = "# " + replacement.key() + "\n\n"
                    + "Contenido legal sellado de prueba para " + replacement.key() + ".\n";
            Files.writeString(
                    manifestPath.getParent().resolve(replacement.source()),
                    markdown,
                    StandardCharsets.UTF_8);
            ObjectNode node = template.deepCopy();
            node.put("key", replacement.key());
            node.put("version", replacement.version());
            node.put("source", replacement.source());
            node.put("sha256", sha256(markdown));
            ArrayNode contexts = node.putArray("contexts");
            replacement.contexts().forEach(contexts::add);
            documents.insert(originalIndex + index, node);
        }
        replaceRequirementReferences(
                manifest,
                Set.of(originalKey),
                replacementByRequirementContext,
                requirementVersion);
    }

    private static void replaceRequirementReferences(
            ObjectNode manifest,
            Set<String> originalKeys,
            Map<String, String> replacementByContext,
            String version) {
        manifest.withArray("requirements").forEach(candidate -> {
            ObjectNode requirement = (ObjectNode) candidate;
            String context = requirement.path("context").textValue();
            ArrayNode documents = requirement.withArray("documents");
            boolean changed = false;
            for (int index = 0; index < documents.size(); index++) {
                if (!originalKeys.contains(documents.get(index).textValue())) {
                    continue;
                }
                String replacement = replacementByContext.get(context);
                if (replacement == null) {
                    throw new IllegalArgumentException(
                            "El fixture no define sucesor para el contexto " + context);
                }
                documents.set(index, JSON.getNodeFactory().textNode(replacement));
                changed = true;
            }
            if (changed) {
                requirement.put("version", version);
            }
        });
    }

    private static void bumpRequirementsReferencing(
            ObjectNode manifest,
            Set<String> documentKeys,
            String version) {
        manifest.withArray("requirements").forEach(candidate -> {
            ObjectNode requirement = (ObjectNode) candidate;
            boolean references = java.util.stream.StreamSupport.stream(
                            requirement.withArray("documents").spliterator(), false)
                    .anyMatch(document -> documentKeys.contains(document.textValue()));
            if (references) {
                requirement.put("version", version);
            }
        });
    }

    private static DocumentSpec documentSpec(
            String key,
            String version,
            String context) {
        return new DocumentSpec(key, version, key + ".md", List.of(context));
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

    private static List<DocumentVersion> documents(UUID publicationId) {
        List<DocumentVersionBase> rows = owner.query("""
                SELECT dv.id, dv.documento_linea_id, dv.sha256, dv.estado,
                       dl.clave, dl.tipo, dl.locale
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
                resultSet.getString("clave"),
                resultSet.getString("tipo"),
                resultSet.getString("locale")), publicationId);
        return rows.stream().map(row -> new DocumentVersion(
                row.id(),
                row.lineId(),
                row.sha256(),
                row.state(),
                row.key(),
                row.type(),
                row.locale(),
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

    private static List<TransitionRow> documentHistory(UUID documentVersionId) {
        return owner.query("""
                SELECT estado_anterior, estado_nuevo, reemplazo_lote_id, ocurrido_en
                  FROM legal_documento_transiciones
                 WHERE documento_version_id = ?
                 ORDER BY id
                """, (resultSet, rowNumber) -> new TransitionRow(
                resultSet.getString("estado_anterior"),
                resultSet.getString("estado_nuevo"),
                resultSet.getObject("reemplazo_lote_id", UUID.class),
                resultSet.getObject("ocurrido_en", OffsetDateTime.class).toInstant()),
                documentVersionId);
    }

    private static List<RequirementTransitionRow> requirementHistory(
            UUID requirementVersionId) {
        return owner.query("""
                SELECT estado_anterior, estado_nuevo, ocurrido_en
                  FROM legal_requisito_transiciones
                 WHERE requisito_version_id = ?
                 ORDER BY id
                """, (resultSet, rowNumber) -> new RequirementTransitionRow(
                resultSet.getString("estado_anterior"),
                resultSet.getString("estado_nuevo"),
                resultSet.getObject("ocurrido_en", OffsetDateTime.class).toInstant()),
                requirementVersionId);
    }

    private static RequirementState requirementState(UUID requirementVersionId) {
        return owner.queryForObject("""
                SELECT estado, estado_cambiado_en
                  FROM legal_requisito_versiones
                 WHERE id = ?
                """, (resultSet, rowNumber) -> new RequirementState(
                resultSet.getString("estado"),
                resultSet.getObject("estado_cambiado_en", OffsetDateTime.class).toInstant()),
                requirementVersionId);
    }

    private static void assertDocumentState(
            UUID documentVersionId,
            String expectedState,
            UUID expectedBatchId,
            Instant expectedChangedAt) {
        DocumentState actual = owner.queryForObject("""
                SELECT estado, reemplazo_lote_id, estado_cambiado_en
                  FROM legal_documento_versiones
                 WHERE id = ?
                """, (resultSet, rowNumber) -> new DocumentState(
                resultSet.getString("estado"),
                resultSet.getObject("reemplazo_lote_id", UUID.class),
                resultSet.getObject("estado_cambiado_en", OffsetDateTime.class).toInstant()),
                documentVersionId);
        assertThat(actual).isEqualTo(new DocumentState(
                expectedState, expectedBatchId, expectedChangedAt));
    }

    private static int intCount(String sql, Object... arguments) {
        Long count = owner.queryForObject(sql, Long.class, arguments);
        return Math.toIntExact(Objects.requireNonNull(count, "count"));
    }

    private static Instant databaseNow() {
        return Objects.requireNonNull(owner.queryForObject(
                "SELECT transaction_timestamp()",
                OffsetDateTime.class)).toInstant();
    }

    private static void assertSealedPublication(UUID publicationId) {
        assertThat(owner.queryForObject(
                "SELECT estado_construccion FROM legal_publicaciones WHERE id = ?",
                String.class,
                publicationId)).isEqualTo("SELLADO");
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

    private static String jsonRows(String selectSql) {
        return owner.queryForObject(
                "SELECT COALESCE(jsonb_agg(to_jsonb(snapshot) "
                        + "ORDER BY to_jsonb(snapshot)::text), '[]'::jsonb)::text "
                        + "FROM (" + selectSql + ") snapshot",
                String.class);
    }

    private static <T> Map<UUID, T> indexById(
            List<T> values,
            Function<T, UUID> key) {
        return values.stream().collect(Collectors.toMap(key, Function.identity()));
    }

    private static Map<String, DocumentVersion> indexByKey(
            List<DocumentVersion> values) {
        return values.stream().collect(Collectors.toMap(
                DocumentVersion::key,
                Function.identity()));
    }

    private static DocumentVersion requireDocument(
            Map<String, DocumentVersion> documents,
            String key) {
        DocumentVersion document = documents.get(key);
        if (document == null) {
            throw new IllegalArgumentException("Documento persistido inexistente: " + key);
        }
        return document;
    }

    private static List<String> contextUnion(List<DocumentVersion> documents) {
        return documents.stream()
                .flatMap(document -> document.contexts().stream())
                .sorted()
                .toList();
    }

    private static List<DocumentVersion> sortedDocuments(
            List<DocumentVersion> documents) {
        return documents.stream()
                .sorted(Comparator.comparing(DocumentVersion::id, UUID_ORDER))
                .toList();
    }

    private static List<UUID> sortedDocumentIds(List<DocumentVersion> documents) {
        return documents.stream().map(DocumentVersion::id).sorted(UUID_ORDER).toList();
    }

    private static List<UUID> sortedIds(Set<UUID> ids) {
        return ids.stream().sorted(UUID_ORDER).toList();
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

    private record ImportedRelease(ValidatedRelease release, UUID publicationId) { }

    private record DocumentSpec(
            String key,
            String version,
            String source,
            List<String> contexts) {

        private DocumentSpec {
            contexts = List.copyOf(contexts);
        }
    }

    private record BatchSpec(
            UUID batchId,
            List<String> predecessorKeys,
            List<String> successorKeys,
            List<String> contexts) {

        private BatchSpec {
            predecessorKeys = List.copyOf(predecessorKeys);
            successorKeys = List.copyOf(successorKeys);
            contexts = List.copyOf(contexts);
        }
    }

    private record BatchKeys(
            List<String> predecessorKeys,
            List<String> successorKeys) {

        private BatchKeys {
            predecessorKeys = List.copyOf(predecessorKeys);
            successorKeys = List.copyOf(successorKeys);
        }
    }

    private record ResolvedBatch(
            UUID batchId,
            List<DocumentVersion> predecessors,
            List<DocumentVersion> successors,
            List<String> contexts) {

        private ResolvedBatch {
            predecessors = List.copyOf(predecessors);
            successors = List.copyOf(successors);
            contexts = List.copyOf(contexts);
        }
    }

    private record PlanFixture(
            ValidatedEditorialPlan plan,
            List<ResolvedBatch> batches,
            Set<UUID> requirementPredecessors,
            Set<UUID> requirementSuccessors) {

        private PlanFixture {
            batches = List.copyOf(batches);
            requirementPredecessors = Set.copyOf(requirementPredecessors);
            requirementSuccessors = Set.copyOf(requirementSuccessors);
        }

        Set<UUID> batchIds() {
            return batches.stream().map(ResolvedBatch::batchId)
                    .collect(Collectors.toUnmodifiableSet());
        }
    }

    private record AdversarialBatchIds(
            UUID splitBatchId,
            UUID mergeBatchId,
            String splitMemberMinimum,
            String mergeMemberMinimum) { }

    private record DocumentVersionBase(
            UUID id,
            UUID lineId,
            String sha256,
            String state,
            String key,
            String type,
            String locale) { }

    private record DocumentVersion(
            UUID id,
            UUID lineId,
            String sha256,
            String state,
            String key,
            String type,
            String locale,
            List<String> contexts) {

        private DocumentVersion {
            contexts = List.copyOf(contexts);
        }
    }

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
            List<String> audiences) {

        private RequirementVersion {
            audiences = List.copyOf(audiences);
        }
    }

    private record BatchHeader(
            String state,
            Instant createdAt,
            Instant sealedAt) { }

    private record ExpectedReceipt(
            int documentVersions,
            int requirementVersions,
            int documentTransitions,
            int requirementTransitions,
            int documentSlots,
            int requiredSetPointers,
            int replacementBatches) { }

    private record DocumentState(
            String state,
            UUID replacementBatchId,
            Instant changedAt) { }

    private record RequirementState(String state, Instant changedAt) { }

    private record TransitionRow(
            String previousState,
            String newState,
            UUID replacementBatchId,
            Instant occurredAt) { }

    private record RequirementTransitionRow(
            String previousState,
            String newState,
            Instant occurredAt) { }

    private record DocumentSlot(
            String type,
            String locale,
            String context,
            UUID documentVersionId,
            UUID documentLineId,
            UUID publicationId,
            String state) { }

    private record CurrentPointer(
            String locale,
            String context,
            String audience,
            UUID publicationId,
            Instant updatedAt) { }
}
