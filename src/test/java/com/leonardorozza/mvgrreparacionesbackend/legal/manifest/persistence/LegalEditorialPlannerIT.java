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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class LegalEditorialPlannerIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant FIXED_OBSERVATION = Instant.parse(
            "2026-08-28T15:00:00.123456Z");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_editorial_planner")
            .withUsername("ordenfix")
            .withPassword("ordenfix");

    private static LegalManifestPersistenceITSupport.Harness importHarness;
    private static LegalManifestPersistenceITSupport.PlannerHarness plannerHarness;
    private static JdbcTemplate jdbc;

    @TempDir
    private Path temporaryDirectory;

    @BeforeAll
    static void migrateAndAssemble() {
        LegalManifestPersistenceITSupport.migrate(POSTGRES);
        var dataSource = LegalManifestPersistenceITSupport.directDataSource(
                POSTGRES,
                "legal-editorial-planner-it");
        importHarness = LegalManifestPersistenceITSupport.harness(
                dataSource,
                LegalDatabaseBudgets.production());
        plannerHarness = LegalManifestPersistenceITSupport.plannerHarness(
                dataSource,
                LegalDatabaseBudgets.production());
        jdbc = importHarness.jdbc();
    }

    @BeforeEach
    void cleanLegalState() {
        LegalManifestPersistenceITSupport.cleanLegalState(jdbc);
    }

    @Test
    void freshDraftPromotionIsApplicableAndRequiresACompleteChange() throws Exception {
        ImportedRelease target = importedDraft("planner-promote-fresh-v1");

        LegalEditorialPlanResult result = readOnly(() -> plannerHarness.plannerService()
                .planPromote(target.release()));

        assertThat(result.status()).isEqualTo(LegalManifestStatus.PASS);
        assertThat(result.outcome()).isEqualTo(LegalEditorialPlanResult.Outcome.APPLICABLE);
        assertThat(result.persisted()).isFalse();
        assertThat(result.expectedReadinessAfter()).contains(LegalEditorialReadiness.READY);
        assertThat(result.changeRequired()).contains(true);
        assertThat(result.targetPublicationUuid()).contains(target.publicationId());
        assertThat(result.deltaCounts()).get().satisfies(delta -> {
            assertThat(delta.isZero()).isFalse();
            assertThat(delta.directDocumentTransitions())
                    .isEqualTo(target.release().documentCount() * 2);
            assertThat(delta.directRequirementTransitions())
                    .isEqualTo(target.release().requirementCount() * 2);
        });
        assertThat(result.executionPlan()).get().satisfies(plan ->
                assertThat(plan.expectedAppliedAt()).isEqualTo(plan.observedAt()));
        assertThat(result.issues()).isEmpty();
    }

    @Test
    void exactPromotedReplayIsApplicableWithoutChangesOrDelta() throws Exception {
        ImportedRelease target = readyRelease("planner-promote-replay-v1");

        LegalEditorialPlanResult result = readOnly(() -> plannerHarness.plannerService()
                .planPromote(target.release()));

        assertThat(result.status()).isEqualTo(LegalManifestStatus.PASS);
        assertThat(result.outcome()).isEqualTo(LegalEditorialPlanResult.Outcome.APPLICABLE);
        assertThat(result.changeRequired()).contains(false);
        assertThat(result.deltaCounts()).get().satisfies(delta ->
                assertThat(delta.isZero()).isTrue());
        assertThat(result.executionPlan()).get().satisfies(plan -> {
            assertThat(plan.expectedAppliedAt()).isBefore(plan.observedAt());
            assertThat(plan.expectedPostState().preexistingDocumentTransitions()).isEmpty();
            assertThat(plan.expectedPostState().preexistingRequirementTransitions()).isEmpty();
        });
        assertThat(result.issues()).isEmpty();
    }

    @Test
    void historicalPromotionCannotBeReusedAfterCurrentProjectionsWereRemoved()
            throws Exception {
        ImportedRelease target = readyRelease("planner-promote-history-v1");
        withReplicaRole(() -> {
            jdbc.update("DELETE FROM legal_documento_vigentes");
            jdbc.update("DELETE FROM legal_requisito_conjuntos_actuales");
        });

        LegalEditorialPlanResult result = readOnly(() -> plannerHarness.plannerService()
                .planPromote(target.release()));

        assertBlocked(result, LegalManifestIssueCode.INITIAL_PROJECTION_ALREADY_EXISTS);
    }

    @Test
    void aPartialPromotedProjectionIsBlockedInsteadOfBeingCompleted()
            throws Exception {
        ImportedRelease target = readyRelease("planner-promote-partial-v1");
        withReplicaRole(() -> jdbc.update("""
                DELETE FROM legal_documento_vigentes
                 WHERE (tipo, locale, contexto) = (
                     SELECT tipo, locale, contexto
                       FROM legal_documento_vigentes
                      ORDER BY tipo, locale, contexto
                      LIMIT 1
                 )
                """));

        LegalEditorialPlanResult result = readOnly(() -> plannerHarness.plannerService()
                .planPromote(target.release()));

        assertBlocked(result, LegalManifestIssueCode.INITIAL_PROJECTION_ALREADY_EXISTS);
    }

    @Test
    void anUnreadableTargetClosureIsAnErrorWithoutPlannerWrites() throws Exception {
        ImportedRelease target = importedDraft("planner-promote-overflow-v1");
        insertOverflowDocumentVersions(target.publicationId());

        LegalEditorialPlanResult result = readOnly(() -> plannerHarness.plannerService()
                .planPromote(target.release()));

        assertThat(result.status()).isEqualTo(LegalManifestStatus.ERROR);
        assertThat(result.outcome()).isEqualTo(LegalEditorialPlanResult.Outcome.ERROR);
        assertThat(result.changeRequired()).isEmpty();
        assertThat(result.deltaCounts()).isEmpty();
        assertThat(result.issues())
                .extracting(LegalManifestIssue::code)
                .containsExactly(LegalManifestIssueCode.EDITORIAL_OBSERVATION_FAILED);
    }

    @Test
    void anExplicitDocumentRetirementIsApplicableAndDeletesDependentPointers()
            throws Exception {
        ImportedRelease current = readyRelease("planner-retire-valid-v1");
        DocumentVersion retired = firstDocument(current.publicationId());
        ValidatedEditorialPlan plan = retirementPlan(current, retired, true);

        LegalEditorialPlanResult result = readOnly(() -> plannerHarness.plannerService()
                .planRetire(current.release(), plan));

        assertThat(result.status()).isEqualTo(LegalManifestStatus.PASS);
        assertThat(result.outcome()).isEqualTo(LegalEditorialPlanResult.Outcome.APPLICABLE);
        assertThat(result.expectedReadinessAfter())
                .contains(LegalEditorialReadiness.NOT_READY);
        assertThat(result.changeRequired()).contains(true);
        assertThat(result.deltaCounts()).get().satisfies(delta -> {
            assertThat(delta.directDocumentTransitions()).isEqualTo(1);
            assertThat(delta.directDocumentSlotDeletes())
                    .isEqualTo(retired.contexts().size());
            assertThat(delta.requiredSetPointerDeletes()).isPositive();
        });
        assertThat(result.executionPlan()).get().satisfies(execution -> {
            assertThat(execution.expectedAppliedAt()).isEqualTo(execution.observedAt());
            assertThat(execution.expectedPostState().preexistingDocumentTransitions())
                    .hasSize(2);
        });
        assertThat(result.issues()).isEmpty();
    }

    @Test
    void aWrongSourceFingerprintBlocksAnOtherwiseValidRetirement()
            throws Exception {
        ImportedRelease current = readyRelease("planner-retire-fingerprint-v1");
        ValidatedEditorialPlan plan = retirementPlan(
                current,
                firstDocument(current.publicationId()),
                false);

        LegalEditorialPlanResult result = readOnly(() -> plannerHarness.plannerService()
                .planRetire(current.release(), plan));

        assertBlocked(result, LegalManifestIssueCode.SOURCE_FINGERPRINT_MISMATCH);
    }

    @Test
    void replacementSupportsAdditionReuseReplacementAndRetirementInOnePlan()
            throws Exception {
        ImportedRelease source = readyRelease("planner-replace-source-v1");
        ImportedRelease target = importedReplacementTarget("planner-replace-target-v1");
        ValidatedEditorialPlan plan = replacementPlan(source, target);

        LegalEditorialPlanResult result = readOnly(() -> plannerHarness.plannerService()
                .planReplace(target.release(), plan));

        assertThat(result.status()).isEqualTo(LegalManifestStatus.PASS);
        assertThat(result.outcome()).isEqualTo(LegalEditorialPlanResult.Outcome.APPLICABLE);
        assertThat(result.expectedReadinessAfter()).contains(LegalEditorialReadiness.READY);
        assertThat(result.changeRequired()).contains(true);
        assertThat(result.targetPublicationUuid()).contains(target.publicationId());
        assertThat(result.deltaCounts()).get().satisfies(delta -> {
            assertThat(delta.replacementBatches()).isEqualTo(1);
            assertThat(delta.triggerDerivedDocumentTransitions()).isEqualTo(2);
            assertThat(delta.directDocumentTransitions()).isPositive();
            assertThat(delta.directRequirementTransitions()).isPositive();
            assertThat(delta.directDocumentSlotDeletes()).isPositive();
            assertThat(delta.directDocumentSlotInserts()).isPositive();
            assertThat(delta.requiredSetPointerDeletes()).isPositive();
            assertThat(delta.requiredSetPointerInserts()).isPositive();
        });
        assertThat(result.executionPlan()).get().satisfies(execution -> {
            assertThat(execution.expectedAppliedAt()).isEqualTo(execution.observedAt());
            assertThat(execution.expectedPostState().preexistingDocumentTransitions())
                    .isNotEmpty();
            assertThat(execution.expectedPostState().preexistingRequirementTransitions())
                    .isNotEmpty();
        });
        assertThat(result.issues()).isEmpty();
    }

    private ImportedRelease importedDraft(String externalId) throws Exception {
        ValidatedRelease release = pastEffectiveRelease(externalId);
        UUID publicationId = importHarness.importService()
                .importManifest(release)
                .receipt()
                .orElseThrow()
                .publicationUuid();
        return new ImportedRelease(release, publicationId);
    }

    private ImportedRelease readyRelease(String externalId) throws Exception {
        ImportedRelease imported = importedDraft(externalId);
        LegalManifestPersistenceITSupport.promoteToReady(jdbc, imported.publicationId());
        return imported;
    }

    private ImportedRelease importedReplacementTarget(String externalId) throws Exception {
        ValidatedRelease release = LegalManifestPersistenceITSupport.copyRelease(
                temporaryDirectory,
                LegalEditorialPlannerIT.class,
                externalId,
                (manifestPath, manifest) -> {
                    manifest.withArray("documents").forEach(document ->
                            ((ObjectNode) document).put(
                                    "effectiveAt",
                                    "2026-01-01T00:00:00-03:00"));
                    document(manifest, "terminos").put("version", "replace-v2");

                    String previousClosureKey = "cierre-cuenta";
                    String addedClosureKey = "cierre-cuenta-nueva-linea";
                    String addedClosureSource = addedClosureKey + ".md";
                    String addedClosureMarkdown = """
                            # cierre-cuenta-nueva-linea

                            Versión alternativa revisada para OrdenFix.
                            """;
                    Files.writeString(
                            manifestPath.getParent().resolve(addedClosureSource),
                            addedClosureMarkdown,
                            StandardCharsets.UTF_8);
                    ObjectNode closure = document(manifest, previousClosureKey);
                    closure.put("key", addedClosureKey);
                    closure.put("source", addedClosureSource);
                    closure.put("sha256", sha256(addedClosureMarkdown));

                    manifest.withArray("requirements").forEach(requirementNode -> {
                        ObjectNode requirement = (ObjectNode) requirementNode;
                        ArrayNode references = requirement.withArray("documents");
                        boolean changed = false;
                        for (int index = 0; index < references.size(); index++) {
                            String key = references.get(index).textValue();
                            if (previousClosureKey.equals(key)) {
                                references.set(index, JSON.getNodeFactory().textNode(
                                        addedClosureKey));
                                changed = true;
                            }
                            if ("terminos".equals(key)) {
                                changed = true;
                            }
                        }
                        if (changed) {
                            requirement.put("version", "replace-v2");
                        }
                    });
                });
        UUID publicationId = importHarness.importService()
                .importManifest(release)
                .receipt()
                .orElseThrow()
                .publicationUuid();
        return new ImportedRelease(release, publicationId);
    }

    private ValidatedRelease pastEffectiveRelease(String externalId) throws Exception {
        return LegalManifestPersistenceITSupport.copyRelease(
                temporaryDirectory,
                LegalEditorialPlannerIT.class,
                externalId,
                (manifestPath, manifest) -> manifest.withArray("documents")
                        .forEach(document -> ((ObjectNode) document).put(
                                "effectiveAt",
                                "2026-01-01T00:00:00-03:00")));
    }

    private ValidatedEditorialPlan retirementPlan(
            ImportedRelease current,
            DocumentVersion retired,
            boolean exactFingerprint) throws Exception {
        String externalId = current.release().plan().manifest().publicationId();
        String fingerprint = exactFingerprint
                ? plannerHarness.readinessCore()
                        .observeState(externalId, FIXED_OBSERVATION)
                        .editorialStateFingerprint()
                : "sha256:" + "0".repeat(64);
        UUID operationId = stableUuid("retire:" + externalId);
        ObjectNode plan = emptyPlan(
                operationId,
                "RETIRE",
                externalId,
                current.release().plan().manifestSha256(),
                fingerprint,
                externalId,
                current.release().plan().manifestSha256(),
                "NOT_READY",
                true);
        ObjectNode retirement = plan.withArray("documentRetirements").addObject();
        retirement.put("documentVersionId", retired.id().toString());
        retirement.put("sha256", retired.sha256());
        retired.contexts().forEach(retirement.putArray("contexts")::add);
        retirement.put("reason", "Retiro editorial explícito de prueba");
        return validatePlan(plan, operationId);
    }

    private ValidatedEditorialPlan replacementPlan(
            ImportedRelease source,
            ImportedRelease target) throws Exception {
        List<DocumentVersion> sourceDocuments = documents(source.publicationId());
        List<DocumentVersion> targetDocuments = documents(target.publicationId());
        List<RequirementVersion> sourceRequirements = requirements(source.publicationId());
        List<RequirementVersion> targetRequirements = requirements(target.publicationId());
        Map<UUID, DocumentVersion> sourceDocumentsById = sourceDocuments.stream()
                .collect(Collectors.toMap(DocumentVersion::id, value -> value));
        Map<UUID, DocumentVersion> targetDocumentsById = targetDocuments.stream()
                .collect(Collectors.toMap(DocumentVersion::id, value -> value));
        Map<UUID, DocumentVersion> sourceDocumentsByLine = sourceDocuments.stream()
                .collect(Collectors.toMap(DocumentVersion::lineId, value -> value));
        Map<UUID, DocumentVersion> targetDocumentsByLine = targetDocuments.stream()
                .collect(Collectors.toMap(DocumentVersion::lineId, value -> value));
        Map<UUID, RequirementVersion> sourceRequirementsById = sourceRequirements.stream()
                .collect(Collectors.toMap(RequirementVersion::id, value -> value));
        Map<UUID, RequirementVersion> targetRequirementsById = targetRequirements.stream()
                .collect(Collectors.toMap(RequirementVersion::id, value -> value));
        Map<UUID, RequirementVersion> sourceRequirementsByLine = sourceRequirements.stream()
                .collect(Collectors.toMap(RequirementVersion::lineId, value -> value));
        Map<UUID, RequirementVersion> targetRequirementsByLine = targetRequirements.stream()
                .collect(Collectors.toMap(RequirementVersion::lineId, value -> value));

        String sourceExternalId = source.release().plan().manifest().publicationId();
        String sourceFingerprint = plannerHarness.readinessCore()
                .observeState(sourceExternalId, FIXED_OBSERVATION)
                .editorialStateFingerprint();
        UUID operationId = stableUuid("replace:"
                + sourceExternalId + ":"
                + target.release().plan().manifest().publicationId());
        ObjectNode plan = emptyPlan(
                operationId,
                "REPLACE",
                sourceExternalId,
                source.release().plan().manifestSha256(),
                sourceFingerprint,
                target.release().plan().manifest().publicationId(),
                target.release().plan().manifestSha256(),
                "READY",
                false);

        for (DocumentVersion document : targetDocuments) {
            if (sourceDocumentsById.containsKey(document.id())) {
                addDocumentScoped(plan.withArray("documentReuses"), document);
            } else if (!sourceDocumentsByLine.containsKey(document.lineId())) {
                addDocumentScoped(plan.withArray("documentAdditions"), document);
            } else {
                DocumentVersion predecessor = sourceDocumentsByLine.get(document.lineId());
                ObjectNode batch = plan.withArray("documentReplacementBatches").addObject();
                batch.put("replacementBatchId", stableUuid(
                        "batch:" + predecessor.id() + ":" + document.id()).toString());
                document.contexts().forEach(batch.putArray("contexts")::add);
                addDocumentRef(batch.putArray("predecessors"), predecessor);
                addDocumentRef(batch.putArray("successors"), document);
            }
        }
        for (DocumentVersion document : sourceDocuments) {
            if (!targetDocumentsById.containsKey(document.id())
                    && !targetDocumentsByLine.containsKey(document.lineId())) {
                ObjectNode retirement = plan.withArray("documentRetirements").addObject();
                retirement.put("documentVersionId", document.id().toString());
                retirement.put("sha256", document.sha256());
                document.contexts().forEach(retirement.putArray("contexts")::add);
                retirement.put("reason", "Retiro por reemplazo integral de prueba");
            }
        }

        for (RequirementVersion requirement : targetRequirements) {
            if (sourceRequirementsById.containsKey(requirement.id())) {
                addRequirementScoped(plan.withArray("requirementReuses"), requirement);
            } else if (!sourceRequirementsByLine.containsKey(requirement.lineId())) {
                addRequirementScoped(plan.withArray("requirementAdditions"), requirement);
            } else {
                RequirementVersion predecessor = sourceRequirementsByLine.get(
                        requirement.lineId());
                ObjectNode replacement = plan.withArray("requirementReplacements")
                        .addObject();
                addRequirementRef(replacement.putObject("predecessor"), predecessor);
                addRequirementRef(replacement.putObject("successor"), requirement);
                replacement.put("context", requirement.context());
                requirement.audiences().forEach(replacement.putArray("audiences")::add);
            }
        }
        for (RequirementVersion requirement : sourceRequirements) {
            if (!targetRequirementsById.containsKey(requirement.id())
                    && !targetRequirementsByLine.containsKey(requirement.lineId())) {
                ObjectNode retirement = plan.withArray("requirementRetirements").addObject();
                retirement.put("requirementVersionId", requirement.id().toString());
                retirement.put("statementSha256", requirement.sha256());
                retirement.put("context", requirement.context());
                requirement.audiences().forEach(retirement.putArray("audiences")::add);
                retirement.put("reason", "Retiro de requisito por reemplazo de prueba");
            }
        }

        assertThat(plan.withArray("documentAdditions")).hasSize(1);
        assertThat(plan.withArray("documentReuses")).isNotEmpty();
        assertThat(plan.withArray("documentReplacementBatches")).hasSize(1);
        assertThat(plan.withArray("documentRetirements")).hasSize(1);
        return validatePlan(plan, operationId);
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

    private static ObjectNode emptyPlan(
            UUID operationId,
            String operationType,
            String expectedCurrentPublicationId,
            String expectedCurrentManifestSha256,
            String expectedEditorialStateFingerprint,
            String targetPublicationId,
            String targetManifestSha256,
            String expectedReadinessAfter,
            boolean acknowledgeFailClosedGap) {
        ObjectNode plan = JSON.createObjectNode();
        plan.put("schemaVersion", 1);
        plan.put("operationId", operationId.toString());
        plan.put("operationType", operationType);
        plan.put("expectedCurrentPublicationId", expectedCurrentPublicationId);
        plan.put("expectedCurrentManifestSha256", expectedCurrentManifestSha256);
        plan.put("expectedEditorialStateFingerprint", expectedEditorialStateFingerprint);
        plan.put("targetPublicationId", targetPublicationId);
        plan.put("targetManifestSha256", targetManifestSha256);
        plan.putArray("documentAdditions");
        plan.putArray("documentReuses");
        plan.putArray("documentReplacementBatches");
        plan.putArray("documentRetirements");
        plan.putArray("requirementAdditions");
        plan.putArray("requirementReuses");
        plan.putArray("requirementReplacements");
        plan.putArray("requirementRetirements");
        plan.put("expectedReadinessAfter", expectedReadinessAfter);
        plan.put("acknowledgeFailClosedGap", acknowledgeFailClosedGap);
        return plan;
    }

    private ValidatedEditorialPlan validatePlan(ObjectNode plan, UUID operationId)
            throws Exception {
        Path directory = temporaryDirectory.toRealPath()
                .resolve("editorial-plan-" + operationId);
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

    private static DocumentVersion firstDocument(UUID publicationId) {
        return documents(publicationId).getFirst();
    }

    private static List<DocumentVersion> documents(UUID publicationId) {
        List<DocumentVersionBase> rows = jdbc.query("""
                SELECT dv.id, dv.documento_linea_id, dv.sha256
                  FROM legal_publicacion_documentos pd
                  JOIN legal_documento_versiones dv
                    ON dv.id = pd.documento_version_id
                 WHERE pd.publicacion_id = ?
                 ORDER BY pd.manifest_ordinal
                """, (resultSet, rowNumber) -> new DocumentVersionBase(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("documento_linea_id", UUID.class),
                        resultSet.getString("sha256")), publicationId);
        return rows.stream().map(row -> new DocumentVersion(
                row.id(),
                row.lineId(),
                row.sha256(),
                jdbc.queryForList("""
                        SELECT contexto
                          FROM legal_documento_contextos
                         WHERE documento_version_id = ?
                         ORDER BY contexto
                        """, String.class, row.id()))).toList();
    }

    private static List<RequirementVersion> requirements(UUID publicationId) {
        List<RequirementVersionBase> rows = jdbc.query("""
                SELECT rv.id, rv.requisito_linea_id, rv.afirmacion_sha256,
                       rl.contexto
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
                        resultSet.getString("contexto")), publicationId);
        return rows.stream().map(row -> new RequirementVersion(
                row.id(),
                row.lineId(),
                row.sha256(),
                row.context(),
                jdbc.queryForList("""
                        SELECT audiencia
                          FROM legal_requisito_audiencias
                         WHERE requisito_linea_id = ?
                         ORDER BY audiencia
                        """, String.class, row.lineId()))).toList();
    }

    private static ObjectNode document(ObjectNode manifest, String key) {
        return (ObjectNode) java.util.stream.StreamSupport.stream(
                        manifest.withArray("documents").spliterator(),
                        false)
                .filter(candidate -> key.equals(candidate.path("key").textValue()))
                .findFirst()
                .orElseThrow();
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static LegalEditorialPlanResult readOnly(
            Supplier<LegalEditorialPlanResult> operation) {
        Map<String, Long> rowsBefore =
                LegalManifestPersistenceITSupport.editorialTableCounts(jdbc);
        Map<String, LegalManifestPersistenceITSupport.SequenceState> sequencesBefore =
                LegalManifestPersistenceITSupport.editorialSequenceStates(jdbc);
        assertThat(rowsBefore).hasSize(19);
        assertThat(sequencesBefore).hasSize(10);

        LegalEditorialPlanResult result = operation.get();

        assertThat(LegalManifestPersistenceITSupport.editorialTableCounts(jdbc))
                .isEqualTo(rowsBefore);
        assertThat(LegalManifestPersistenceITSupport.editorialSequenceStates(jdbc))
                .isEqualTo(sequencesBefore);
        return result;
    }

    private static void assertBlocked(
            LegalEditorialPlanResult result,
            LegalManifestIssueCode issueCode) {
        assertThat(result.status()).isEqualTo(LegalManifestStatus.BLOCKED);
        assertThat(result.outcome()).isEqualTo(LegalEditorialPlanResult.Outcome.BLOCKED);
        assertThat(result.persisted()).isFalse();
        assertThat(result.changeRequired()).isEmpty();
        assertThat(result.deltaCounts()).isEmpty();
        assertThat(result.issues())
                .extracting(LegalManifestIssue::code)
                .contains(issueCode);
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
                     vigente_desde, requires_reacceptance)
                SELECT md5('planner-overflow-' || generated.ordinal::text)::uuid,
                       source.documento_linea_id, source.publicacion_intro_id,
                       'overflow-' || generated.ordinal::text,
                       100000 + generated.ordinal,
                       source.titulo, source.contenido_markdown, source.sha256,
                       source.vigente_desde, source.requires_reacceptance
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

    private static UUID stableUuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private record ImportedRelease(ValidatedRelease release, UUID publicationId) { }

    private record DocumentVersionBase(UUID id, UUID lineId, String sha256) { }

    private record DocumentVersion(
            UUID id,
            UUID lineId,
            String sha256,
            List<String> contexts) { }

    private record RequirementVersionBase(
            UUID id,
            UUID lineId,
            String sha256,
            String context) { }

    private record RequirementVersion(
            UUID id,
            UUID lineId,
            String sha256,
            String context,
            List<String> audiences) { }
}
