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
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.assertEditorialConfirmed;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.cleanLegalState;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.copyRelease;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.editorialSequenceStates;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.editorialTableCounts;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.harness;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.migrate;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.promoteToReady;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.restrictedApplyHarness;
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
    private static final Set<String> ACCEPTANCE_AND_IDEMPOTENCY_TABLES = Set.of(
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
        owner.execute("""
                TRUNCATE TABLE
                    legal_idempotencia_resultados,
                    legal_aceptacion_metadatos_cifrados,
                    legal_aceptacion_metadatos,
                    legal_aceptacion_documentos,
                    legal_aceptaciones,
                    legal_aceptacion_lotes
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
        assertThat(allRequirements).hasSize(6);

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
        assertThat(before.requiredSetPointers()).hasSize(8);
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
                documentAffectedPointers,
                requirementAffectedPointers,
                affectedPointers,
                Math.toIntExact(affectedSlotCount));
    }

    private static void seedAcceptanceAggregate(ImportedRelease current) {
        String token = current.release().plan().manifest().publicationId();
        UUID lotId = stableUuid("acceptance-lot:" + token);
        UUID acceptanceId = stableUuid("acceptance-act:" + token);
        importer.transaction().executeWithoutResult(status -> {
            JdbcTemplate jdbc = importer.jdbc();
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
            String revision = Objects.requireNonNull(jdbc.queryForObject("""
                    SELECT c.required_set_revision
                      FROM legal_requisito_conjuntos_actuales a
                      JOIN legal_requisito_conjuntos c ON c.id = a.conjunto_id
                     WHERE a.publicacion_id = ?
                       AND a.locale = 'es-AR'
                       AND a.contexto = 'ATESTACION_FOTOS'
                       AND a.audiencia = 'ADMIN_TITULAR'
                    """, String.class, current.publicationId()));

            assertThat(jdbc.update("""
                    INSERT INTO legal_aceptacion_lotes
                        (id, user_id, taller_id, rol_wire, audiencia,
                         required_set_revision, aceptado_en)
                    VALUES (?, ?, ?, 'ADMIN', 'ADMIN_TITULAR', ?,
                            transaction_timestamp())
                    """, lotId, userId, workshopId, revision)).isOne();
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
                (manifestPath, manifest) -> manifest.withArray("documents")
                        .forEach(document -> ((ObjectNode) document).put(
                                "effectiveAt",
                                "2026-01-01T00:00:00-03:00")));
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
        ImportedRelease target = importedTermsReplacementTarget(targetExternalId);
        ValidatedEditorialPlan plan = oneToOneReplacementPlan(source, target);

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
        assertThat(rowCount("legal_documento_reemplazo_lotes")).isOne();
        assertThat(rowCount("legal_documento_reemplazo_anteriores")).isOne();
        assertThat(rowCount("legal_documento_reemplazo_sucesoras")).isOne();
        assertThat(apply.readinessCore().evaluate(
                target.release(),
                receipt.appliedAt()).readiness())
                .isEqualTo(LegalEditorialReadiness.READY);
        return target;
    }

    private ImportedRelease importedTermsReplacementTarget(String externalId)
            throws Exception {
        ValidatedRelease release = copyRelease(
                temporaryDirectory,
                LegalEditorialRetireIT.class,
                externalId,
                (manifestPath, manifest) -> {
                    manifest.withArray("documents").forEach(document ->
                            ((ObjectNode) document).put(
                                    "effectiveAt",
                                    "2026-01-01T00:00:00-03:00"));
                    manifestDocument(manifest, "terminos")
                            .put("version", "replace-v2");
                    manifest.withArray("requirements").forEach(candidate -> {
                        ObjectNode requirement = (ObjectNode) candidate;
                        boolean referencesTerms = java.util.stream.StreamSupport.stream(
                                        requirement.withArray("documents").spliterator(),
                                        false)
                                .anyMatch(reference ->
                                        "terminos".equals(reference.textValue()));
                        if (referencesTerms) {
                            requirement.put("version", "replace-v2");
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
        assertThat(plan.withArray("requirementReuses")).hasSize(4);
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

    private record ImportedRelease(ValidatedRelease release, UUID publicationId) { }

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

    private record AppliedRetirement(
            DatabaseSnapshot before,
            DatabaseSnapshot after,
            LegalEditorialApplyReceipt receipt,
            Set<PointerKey> documentAffectedPointers,
            Set<PointerKey> requirementAffectedPointers,
            Set<PointerKey> affectedPointers,
            int affectedSlotCount) { }
}
