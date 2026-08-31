package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.ConfinedEditorialPlanReader;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialReadiness;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialPlanValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialPlanValidator.ValidatedEditorialPlan;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidation;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.ApplyHarness;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.Harness;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.ReleaseMutation;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.SequenceState;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.copyRelease;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.editorialSequenceStates;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.editorialTableCounts;

/**
 * Small, test-only source of deterministic releases, editorial plans and owner snapshots.
 *
 * <p>The fixture deliberately receives already assembled services. It neither provisions roles
 * nor owns connections, pools or executors, so each integration test remains responsible for its
 * own lifecycle and concurrency orchestration.</p>
 */
final class LegalEditorialITFixture {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String EFFECTIVE_AT = "2026-01-01T00:00:00-03:00";
    private static final String DOCUMENT_RETIREMENT_REASON =
            "Retiro por reemplazo integral del fixture editorial";
    private static final String REQUIREMENT_RETIREMENT_REASON =
            "Retiro de requisito por reemplazo del fixture editorial";

    private final Path temporaryDirectory;
    private final Class<?> resourceAnchor;
    private final JdbcTemplate owner;
    private final Harness importer;
    private final ApplyHarness ownerApply;

    LegalEditorialITFixture(
            Path temporaryDirectory,
            Class<?> resourceAnchor,
            JdbcTemplate owner,
            Harness importer,
            ApplyHarness ownerApply) {
        this.temporaryDirectory = Objects.requireNonNull(
                temporaryDirectory,
                "temporaryDirectory");
        this.resourceAnchor = Objects.requireNonNull(resourceAnchor, "resourceAnchor");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.importer = Objects.requireNonNull(importer, "importer");
        this.ownerApply = Objects.requireNonNull(ownerApply, "ownerApply");
        if (!Files.isDirectory(temporaryDirectory)) {
            throw new IllegalArgumentException("El directorio temporal del fixture no existe");
        }
    }

    /** Copies and validates one past-effective release without importing it. */
    ValidatedRelease draftRelease(String externalId) throws Exception {
        return draftRelease(externalId, ReleaseMutation.NONE);
    }

    /** Copies, mutates and validates one past-effective release without importing it. */
    ValidatedRelease draftRelease(
            String externalId,
            ReleaseMutation mutation) throws Exception {
        String confinedId = requireConfinedReleaseId(externalId);
        Objects.requireNonNull(mutation, "mutation");
        return copyRelease(
                temporaryDirectory,
                resourceAnchor,
                confinedId,
                (manifestPath, manifest) -> {
                    for (JsonNode candidate : manifest.withArray("documents")) {
                        ((ObjectNode) candidate).put("effectiveAt", EFFECTIVE_AT);
                    }
                    mutation.apply(manifestPath, manifest);
                });
    }

    /** Imports a fresh draft prepared from the golden release. */
    ImportedRelease importedDraft(String externalId) throws Exception {
        return importRelease(draftRelease(externalId));
    }

    /** Imports a fresh draft after applying one caller-owned manifest mutation. */
    ImportedRelease importedDraft(
            String externalId,
            ReleaseMutation mutation) throws Exception {
        return importRelease(draftRelease(externalId, mutation));
    }

    /**
     * Imports a new version of one existing document line and all requirements that reference it.
     * This is the bounded target used by overlapping-predecessor REPLACE races.
     */
    ImportedRelease importedDocumentRevision(
            String externalId,
            String documentKey,
            String versionSeed) throws Exception {
        String requiredKey = requireVisibleToken(documentKey, "documentKey");
        String requiredVersion = requireVisibleToken(versionSeed, "versionSeed");
        return importedDraft(externalId, (manifestPath, manifest) -> {
            ObjectNode revised = document(manifest, requiredKey);
            revised.put("version", requiredVersion);
            for (JsonNode candidate : manifest.withArray("requirements")) {
                ObjectNode requirement = (ObjectNode) candidate;
                if (containsText(requirement.withArray("documents"), requiredKey)) {
                    requirement.put("version", requiredVersion);
                }
            }
        });
    }

    /** Imports and confirms a fresh PROMOTE, producing a source in exact READY state. */
    ImportedRelease readyRelease(String externalId) throws Exception {
        ImportedRelease imported = importedDraft(externalId);
        LegalEditorialApplyResult promoted = ownerApply.service().applyPromote(
                imported.release());
        if (promoted.status() != LegalManifestStatus.PASS
                || !Boolean.TRUE.equals(promoted.persisted())
                || promoted.outcome() != LegalEditorialApplyResult.Outcome.APPLIED
                || promoted.receipt().isEmpty()
                || promoted.targetPublicationUuid()
                        .filter(imported.publicationId()::equals)
                        .isEmpty()
                || promoted.operationType()
                        .filter(type -> type == LegalEditorialApplyReceipt.OperationType.PROMOTE)
                        .isEmpty()
                || promoted.readinessAfter()
                        .filter(readiness -> readiness == LegalEditorialReadiness.READY)
                        .isEmpty()) {
            throw new IllegalStateException(
                    "No se pudo preparar la fuente READY: status="
                            + promoted.status()
                            + " outcome=" + promoted.outcome()
                            + " issues=" + promoted.issues());
        }
        return imported;
    }

    /**
     * Builds and validates a complete REPLACE plan from the persisted source and target graphs.
     * The fingerprint is observed immediately before the artifact is materialized.
     */
    ReplaceFixture replacementPlan(
            ImportedRelease source,
            ImportedRelease target,
            String operationSeed) throws Exception {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        String requiredSeed = requireVisibleToken(operationSeed, "operationSeed");
        if (source.publicationId().equals(target.publicationId())) {
            throw new IllegalArgumentException("REPLACE requiere publicaciones distintas");
        }

        List<DocumentVersion> sourceDocuments = documents(source.publicationId()).stream()
                .filter(document -> "VIGENTE".equals(document.state()))
                .toList();
        List<DocumentVersion> targetDocuments = documents(target.publicationId());
        List<RequirementVersion> sourceRequirements = requirements(source.publicationId())
                .stream()
                .filter(requirement -> "VIGENTE".equals(requirement.state()))
                .toList();
        List<RequirementVersion> targetRequirements = requirements(target.publicationId());

        Map<UUID, DocumentVersion> sourceDocumentsById = indexById(sourceDocuments);
        Map<UUID, DocumentVersion> targetDocumentsById = indexById(targetDocuments);
        Map<UUID, DocumentVersion> sourceDocumentsByLine = indexDocumentsByLine(
                sourceDocuments);
        Map<UUID, DocumentVersion> targetDocumentsByLine = indexDocumentsByLine(
                targetDocuments);
        Map<UUID, RequirementVersion> sourceRequirementsById = indexRequirementsById(
                sourceRequirements);
        Map<UUID, RequirementVersion> targetRequirementsById = indexRequirementsById(
                targetRequirements);
        Map<UUID, RequirementVersion> sourceRequirementsByLine = indexRequirementsByLine(
                sourceRequirements);
        Map<UUID, RequirementVersion> targetRequirementsByLine = indexRequirementsByLine(
                targetRequirements);

        String sourceExternalId = source.release().plan().manifest().publicationId();
        String targetExternalId = target.release().plan().manifest().publicationId();
        String fingerprint = ownerApply.readinessCore()
                .observeState(sourceExternalId, databaseNow())
                .editorialStateFingerprint();
        UUID operationId = stableUuid(
                "replace:" + requiredSeed + ":" + sourceExternalId + ":" + targetExternalId);
        ObjectNode plan = emptyReplacementPlan(
                operationId,
                sourceExternalId,
                source.release().plan().manifestSha256(),
                fingerprint,
                targetExternalId,
                target.release().plan().manifestSha256());

        LinkedHashSet<UUID> predecessors = new LinkedHashSet<>();
        LinkedHashSet<UUID> successors = new LinkedHashSet<>();
        LinkedHashSet<UUID> requirementPredecessors = new LinkedHashSet<>();
        LinkedHashSet<UUID> requirementSuccessors = new LinkedHashSet<>();
        LinkedHashSet<UUID> batchIds = new LinkedHashSet<>();

        for (DocumentVersion document : targetDocuments) {
            if (sourceDocumentsById.containsKey(document.id())) {
                addDocumentScoped(plan.withArray("documentReuses"), document);
            } else if (!sourceDocumentsByLine.containsKey(document.lineId())) {
                addDocumentScoped(plan.withArray("documentAdditions"), document);
            } else {
                DocumentVersion predecessor = sourceDocumentsByLine.get(document.lineId());
                UUID batchId = stableUuid(
                        "batch:" + operationId + ":" + predecessor.id() + ":" + document.id());
                ObjectNode batch = plan.withArray("documentReplacementBatches").addObject();
                batch.put("replacementBatchId", batchId.toString());
                document.contexts().forEach(batch.putArray("contexts")::add);
                addDocumentRef(batch.putArray("predecessors"), predecessor);
                addDocumentRef(batch.putArray("successors"), document);
                predecessors.add(predecessor.id());
                successors.add(document.id());
                batchIds.add(batchId);
            }
        }
        for (DocumentVersion document : sourceDocuments) {
            if (!targetDocumentsById.containsKey(document.id())
                    && !targetDocumentsByLine.containsKey(document.lineId())) {
                ObjectNode retirement = plan.withArray("documentRetirements").addObject();
                retirement.put("documentVersionId", document.id().toString());
                retirement.put("sha256", document.sha256());
                document.contexts().forEach(retirement.putArray("contexts")::add);
                retirement.put("reason", DOCUMENT_RETIREMENT_REASON);
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
                requirementPredecessors.add(predecessor.id());
                requirementSuccessors.add(requirement.id());
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
                retirement.put("reason", REQUIREMENT_RETIREMENT_REASON);
            }
        }

        ValidatedEditorialPlan validated = validatePlan(plan, operationId);
        return new ReplaceFixture(
                source,
                target,
                validated,
                immutableSet(predecessors),
                immutableSet(successors),
                immutableSet(requirementPredecessors),
                immutableSet(requirementSuccessors),
                immutableSet(batchIds));
    }

    /** Captures exact rows, counts and identity-sequence state through the owner connection. */
    EditorialSnapshot snapshot() {
        Map<String, String> rows = new LinkedHashMap<>();
        for (String table : new TreeSet<>(LegalV27EditorialInventory.EDITORIAL_TABLES)) {
            rows.put(table, jsonRows("SELECT * FROM " + quoteIdentifier(table)));
        }
        return new EditorialSnapshot(
                rows,
                editorialTableCounts(owner),
                editorialSequenceStates(owner));
    }

    Instant databaseNow() {
        OffsetDateTime timestamp = owner.queryForObject(
                "SELECT transaction_timestamp()",
                OffsetDateTime.class);
        return Objects.requireNonNull(timestamp, "transaction_timestamp").toInstant();
    }

    private ImportedRelease importRelease(ValidatedRelease release) {
        LegalManifestImportResult result = importer.importService().importManifest(release);
        if (result.status() != LegalManifestStatus.PASS
                || !Boolean.TRUE.equals(result.persisted())
                || result.outcome().isEmpty()
                || result.outcome().orElseThrow() == LegalManifestImportResult.Outcome.UNKNOWN
                || result.receipt().isEmpty()) {
            throw new IllegalStateException(
                    "No se pudo preparar la publicación del fixture: status="
                            + result.status()
                            + " outcome=" + result.outcome()
                            + " issues=" + result.issues());
        }
        return new ImportedRelease(
                release,
                result.receipt().orElseThrow().publicationUuid());
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
        if (!validation.passed()) {
            throw new IllegalStateException(
                    "El plan editorial generado no es válido: " + validation.issues());
        }
        return validation.value().orElseThrow();
    }

    private static ObjectNode emptyReplacementPlan(
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

    private List<DocumentVersion> documents(UUID publicationId) {
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

    private List<RequirementVersion> requirements(UUID publicationId) {
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

    private String jsonRows(String selectSql) {
        return owner.queryForObject(
                "SELECT COALESCE(jsonb_agg(to_jsonb(snapshot) "
                        + "ORDER BY to_jsonb(snapshot)::text), '[]'::jsonb)::text "
                        + "FROM (" + selectSql + ") snapshot",
                String.class);
    }

    private String requireConfinedReleaseId(String externalId) {
        String required = requireVisibleToken(externalId, "externalId");
        Path root = temporaryDirectory.toAbsolutePath().normalize();
        Path candidate = root.resolve(required).normalize();
        if (!Objects.equals(candidate.getParent(), root)) {
            throw new IllegalArgumentException(
                    "externalId debe resolver a un único directorio temporal");
        }
        return required;
    }

    private static String requireVisibleToken(String value, String name) {
        String required = Objects.requireNonNull(value, name);
        if (required.isBlank()
                || !required.equals(required.trim())
                || required.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException(name + " no es un token visible válido");
        }
        return required;
    }

    private static ObjectNode document(ObjectNode manifest, String key) {
        for (JsonNode candidate : manifest.withArray("documents")) {
            if (key.equals(candidate.path("key").textValue())) {
                return (ObjectNode) candidate;
            }
        }
        throw new IllegalArgumentException("Documento de fixture inexistente: " + key);
    }

    private static boolean containsText(ArrayNode values, String expected) {
        for (JsonNode value : values) {
            if (expected.equals(value.textValue())) {
                return true;
            }
        }
        return false;
    }

    private static Map<UUID, DocumentVersion> indexById(List<DocumentVersion> values) {
        return values.stream().collect(Collectors.toMap(DocumentVersion::id, value -> value));
    }

    private static Map<UUID, DocumentVersion> indexDocumentsByLine(
            List<DocumentVersion> values) {
        return values.stream().collect(Collectors.toMap(
                DocumentVersion::lineId,
                value -> value));
    }

    private static Map<UUID, RequirementVersion> indexRequirementsById(
            List<RequirementVersion> values) {
        return values.stream().collect(Collectors.toMap(
                RequirementVersion::id,
                value -> value));
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

    private static UUID stableUuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String quoteIdentifier(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    private static <V> Map<String, V> immutableStringMap(Map<String, V> values) {
        LinkedHashMap<String, V> ordered = new LinkedHashMap<>();
        for (String key : new TreeSet<>(values.keySet())) {
            ordered.put(key, values.get(key));
        }
        return Collections.unmodifiableMap(ordered);
    }

    private static <T> Set<T> immutableSet(Set<T> values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }

    record ImportedRelease(ValidatedRelease release, UUID publicationId) {

        ImportedRelease {
            Objects.requireNonNull(release, "release");
            Objects.requireNonNull(publicationId, "publicationId");
        }
    }

    record ReplaceFixture(
            ImportedRelease source,
            ImportedRelease target,
            ValidatedEditorialPlan plan,
            Set<UUID> predecessorDocumentIds,
            Set<UUID> successorDocumentIds,
            Set<UUID> predecessorRequirementIds,
            Set<UUID> successorRequirementIds,
            Set<UUID> replacementBatchIds) {

        ReplaceFixture {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(plan, "plan");
            predecessorDocumentIds = immutableSet(Objects.requireNonNull(
                    predecessorDocumentIds,
                    "predecessorDocumentIds"));
            successorDocumentIds = immutableSet(Objects.requireNonNull(
                    successorDocumentIds,
                    "successorDocumentIds"));
            predecessorRequirementIds = immutableSet(Objects.requireNonNull(
                    predecessorRequirementIds,
                    "predecessorRequirementIds"));
            successorRequirementIds = immutableSet(Objects.requireNonNull(
                    successorRequirementIds,
                    "successorRequirementIds"));
            replacementBatchIds = immutableSet(Objects.requireNonNull(
                    replacementBatchIds,
                    "replacementBatchIds"));
        }
    }

    record EditorialSnapshot(
            Map<String, String> tableRows,
            Map<String, Long> tableCounts,
            Map<String, SequenceState> sequenceStates) {

        EditorialSnapshot {
            tableRows = immutableStringMap(Objects.requireNonNull(tableRows, "tableRows"));
            tableCounts = immutableStringMap(Objects.requireNonNull(tableCounts, "tableCounts"));
            sequenceStates = immutableStringMap(Objects.requireNonNull(
                    sequenceStates,
                    "sequenceStates"));
        }
    }

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
}
