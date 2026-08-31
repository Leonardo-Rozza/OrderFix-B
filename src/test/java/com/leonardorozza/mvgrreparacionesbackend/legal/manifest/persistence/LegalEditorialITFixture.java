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
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
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
    private static final int MAXIMUM_EDITORIAL_DOCUMENTS = 87;
    private static final int MAXIMUM_EDITORIAL_REQUIREMENTS = 256;
    private static final int MAXIMUM_EDITORIAL_SLOTS = 88;
    private static final int REQUIREMENTS_PER_CONTEXT = 32;
    private static final String CAPACITY_ONE_TO_ONE_KEY = "capacity-replace-one";
    private static final String CAPACITY_SPLIT_SOURCE_KEY = "capacity-split-source";
    private static final String CAPACITY_SPLIT_TARGET_FIRST_KEY = "capacity-split-target-a";
    private static final String CAPACITY_SPLIT_TARGET_SECOND_KEY = "capacity-split-target-b";
    private static final String CAPACITY_MERGE_SOURCE_FIRST_KEY = "capacity-merge-source-a";
    private static final String CAPACITY_MERGE_SOURCE_SECOND_KEY = "capacity-merge-source-b";
    private static final String CAPACITY_MERGE_TARGET_KEY = "capacity-merge-target";
    private static final String CAPACITY_RETIREMENT_KEY = "capacity-retired";
    private static final String CAPACITY_ADDITION_KEY = "capacity-added";

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
        return readyRelease(externalId, ReleaseMutation.NONE);
    }

    /**
     * Builds the realizable editorial maximum through the production import and apply paths.
     * Source and target each contain 87 documents, 256 requirements, 16 scopes, 88 slots and the
     * 2,642 requirement-document references compatible with the declared reuse/replacement mix.
     */
    MaximumEditorialFixture maximumEditorialFixture(
            String sourceExternalId,
            String targetExternalId,
            String operationSeed) throws Exception {
        String sourceId = requireConfinedReleaseId(sourceExternalId);
        String targetId = requireConfinedReleaseId(targetExternalId);
        String requiredSeed = requireVisibleToken(operationSeed, "operationSeed");
        if (sourceId.equals(targetId)) {
            throw new IllegalArgumentException(
                    "El máximo editorial requiere publicaciones distintas");
        }

        ImportedRelease source = readyRelease(
                sourceId,
                (manifestPath, manifest) -> replaceWithMaximumEditorialManifest(
                        manifestPath,
                        manifest,
                        MaximumEditorialSide.SOURCE));
        ImportedRelease target = importedDraft(
                targetId,
                (manifestPath, manifest) -> replaceWithMaximumEditorialManifest(
                        manifestPath,
                        manifest,
                        MaximumEditorialSide.TARGET));

        ContextoLegal[] contexts = ContextoLegal.values();
        List<ReplacementBatchSpec> batches = List.of(
                new ReplacementBatchSpec(
                        stableUuid("capacity-batch:" + requiredSeed + ":one-to-one"),
                        List.of(CAPACITY_ONE_TO_ONE_KEY),
                        List.of(CAPACITY_ONE_TO_ONE_KEY),
                        List.of(contexts[0].name())),
                new ReplacementBatchSpec(
                        stableUuid("capacity-batch:" + requiredSeed + ":split"),
                        List.of(CAPACITY_SPLIT_SOURCE_KEY),
                        List.of(
                                CAPACITY_SPLIT_TARGET_FIRST_KEY,
                                CAPACITY_SPLIT_TARGET_SECOND_KEY),
                        List.of(contexts[0].name(), contexts[1].name())),
                new ReplacementBatchSpec(
                        stableUuid("capacity-batch:" + requiredSeed + ":merge"),
                        List.of(
                                CAPACITY_MERGE_SOURCE_FIRST_KEY,
                                CAPACITY_MERGE_SOURCE_SECOND_KEY),
                        List.of(CAPACITY_MERGE_TARGET_KEY),
                        List.of(contexts[0].name(), contexts[1].name())));
        ReplaceFixture replacement = replacementPlan(
                source,
                target,
                requiredSeed,
                batches);
        return new MaximumEditorialFixture(
                source,
                target,
                replacement,
                MAXIMUM_EDITORIAL_SLOTS);
    }

    private ImportedRelease readyRelease(
            String externalId,
            ReleaseMutation mutation) throws Exception {
        ImportedRelease imported = importedDraft(externalId, mutation);
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
        return replacementPlan(source, target, operationSeed, List.of());
    }

    /**
     * Builds a REPLACE plan while assigning the declared document members to explicit batches.
     * Explicit predecessors and successors are excluded from automatic additions, retirements and
     * one-to-one lineage matching.
     */
    ReplaceFixture replacementPlan(
            ImportedRelease source,
            ImportedRelease target,
            String operationSeed,
            List<ReplacementBatchSpec> batchSpecs) throws Exception {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        String requiredSeed = requireVisibleToken(operationSeed, "operationSeed");
        List<ReplacementBatchSpec> requiredBatchSpecs = List.copyOf(
                Objects.requireNonNull(batchSpecs, "batchSpecs"));
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
        Map<String, DocumentVersion> sourceDocumentsByKey = indexDocumentsByKey(
                sourceDocuments);
        Map<String, DocumentVersion> targetDocumentsByKey = indexDocumentsByKey(
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

        List<ResolvedReplacementBatch> explicitBatches = requiredBatchSpecs.stream()
                .map(spec -> resolveReplacementBatch(
                        spec,
                        sourceDocumentsByKey,
                        targetDocumentsByKey))
                .toList();
        requireDisjointReplacementBatches(explicitBatches);
        Set<UUID> explicitPredecessorIds = explicitBatches.stream()
                .flatMap(batch -> batch.predecessors().stream())
                .map(DocumentVersion::id)
                .collect(Collectors.toUnmodifiableSet());
        Set<UUID> explicitSuccessorIds = explicitBatches.stream()
                .flatMap(batch -> batch.successors().stream())
                .map(DocumentVersion::id)
                .collect(Collectors.toUnmodifiableSet());

        for (ResolvedReplacementBatch explicitBatch : explicitBatches) {
            ObjectNode batch = plan.withArray("documentReplacementBatches").addObject();
            batch.put("replacementBatchId", explicitBatch.batchId().toString());
            explicitBatch.contexts().forEach(batch.putArray("contexts")::add);
            explicitBatch.predecessors().forEach(document ->
                    addDocumentRef(batch.withArray("predecessors"), document));
            explicitBatch.successors().forEach(document ->
                    addDocumentRef(batch.withArray("successors"), document));
            explicitBatch.predecessors().forEach(document -> predecessors.add(document.id()));
            explicitBatch.successors().forEach(document -> successors.add(document.id()));
            batchIds.add(explicitBatch.batchId());
        }

        for (DocumentVersion document : targetDocuments) {
            if (explicitSuccessorIds.contains(document.id())) {
                continue;
            }
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
            if (!explicitPredecessorIds.contains(document.id())
                    && !targetDocumentsById.containsKey(document.id())
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

    private static void replaceWithMaximumEditorialManifest(
            Path manifestPath,
            ObjectNode manifest,
            MaximumEditorialSide side) throws Exception {
        Objects.requireNonNull(manifestPath, "manifestPath");
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(side, "side");

        ContextoLegal[] contexts = ContextoLegal.values();
        TipoDocumentoLegal[] types = TipoDocumentoLegal.values();
        LocaleLegal[] locales = LocaleLegal.values();
        if (contexts.length != 8 || types.length != 11
                || locales.length != 1 || locales[0] != LocaleLegal.ES_AR) {
            throw new IllegalStateException(
                    "El máximo editorial congelado requiere 8 contextos, 11 tipos y ES_AR");
        }

        Map<String, List<String>> documentKeysByContext = new LinkedHashMap<>();
        for (ContextoLegal context : contexts) {
            documentKeysByContext.put(context.name(), new ArrayList<>());
        }
        ArrayNode documents = manifest.putArray("documents");
        for (int typeIndex = 0; typeIndex < types.length; typeIndex++) {
            for (int contextIndex = 0; contextIndex < contexts.length; contextIndex++) {
                if (isMaximumEditorialChangeSlot(typeIndex, contextIndex)) {
                    continue;
                }
                String key = "capacity-doc-%02d-%02d".formatted(typeIndex, contextIndex);
                addCapacityDocument(
                        manifestPath,
                        documents,
                        documentKeysByContext,
                        key,
                        types[typeIndex].name(),
                        "1.0.0",
                        List.of(contexts[contextIndex].name()),
                        "reuse-" + typeIndex + "-" + contextIndex);
            }
        }

        if (side == MaximumEditorialSide.SOURCE) {
            addCapacityDocument(
                    manifestPath,
                    documents,
                    documentKeysByContext,
                    CAPACITY_ONE_TO_ONE_KEY,
                    types[2].name(),
                    "1.0.0",
                    List.of(contexts[0].name()),
                    "one-to-one-source");
            addCapacityDocument(
                    manifestPath,
                    documents,
                    documentKeysByContext,
                    CAPACITY_SPLIT_SOURCE_KEY,
                    types[0].name(),
                    "1.0.0",
                    List.of(contexts[0].name(), contexts[1].name()),
                    "split-source");
            addCapacityDocument(
                    manifestPath,
                    documents,
                    documentKeysByContext,
                    CAPACITY_MERGE_SOURCE_FIRST_KEY,
                    types[1].name(),
                    "1.0.0",
                    List.of(contexts[0].name()),
                    "merge-source-a");
            addCapacityDocument(
                    manifestPath,
                    documents,
                    documentKeysByContext,
                    CAPACITY_MERGE_SOURCE_SECOND_KEY,
                    types[1].name(),
                    "1.0.0",
                    List.of(contexts[1].name()),
                    "merge-source-b");
            addCapacityDocument(
                    manifestPath,
                    documents,
                    documentKeysByContext,
                    CAPACITY_RETIREMENT_KEY,
                    types[3].name(),
                    "1.0.0",
                    List.of(contexts[0].name()),
                    "retirement-source");
        } else {
            addCapacityDocument(
                    manifestPath,
                    documents,
                    documentKeysByContext,
                    CAPACITY_ONE_TO_ONE_KEY,
                    types[2].name(),
                    "2.0.0",
                    List.of(contexts[0].name()),
                    "one-to-one-target");
            addCapacityDocument(
                    manifestPath,
                    documents,
                    documentKeysByContext,
                    CAPACITY_SPLIT_TARGET_FIRST_KEY,
                    types[0].name(),
                    "1.0.0",
                    List.of(contexts[0].name()),
                    "split-target-a");
            addCapacityDocument(
                    manifestPath,
                    documents,
                    documentKeysByContext,
                    CAPACITY_SPLIT_TARGET_SECOND_KEY,
                    types[0].name(),
                    "1.0.0",
                    List.of(contexts[1].name()),
                    "split-target-b");
            addCapacityDocument(
                    manifestPath,
                    documents,
                    documentKeysByContext,
                    CAPACITY_MERGE_TARGET_KEY,
                    types[1].name(),
                    "1.0.0",
                    List.of(contexts[0].name(), contexts[1].name()),
                    "merge-target");
            addCapacityDocument(
                    manifestPath,
                    documents,
                    documentKeysByContext,
                    CAPACITY_ADDITION_KEY,
                    types[3].name(),
                    "1.0.0",
                    List.of(contexts[0].name()),
                    "addition-target");
        }

        if (documents.size() != MAXIMUM_EDITORIAL_DOCUMENTS) {
            throw new IllegalStateException(
                    "El fixture máximo no produjo 87 documentos: " + documents.size());
        }
        int slots = documentKeysByContext.values().stream().mapToInt(List::size).sum();
        if (slots != MAXIMUM_EDITORIAL_SLOTS
                || documentKeysByContext.values().stream().anyMatch(keys -> keys.size() != 11)) {
            throw new IllegalStateException(
                    "El fixture máximo no cubre exactamente los 88 slots editoriales");
        }

        ArrayNode requirements = manifest.putArray("requirements");
        for (int contextIndex = 0; contextIndex < contexts.length; contextIndex++) {
            String context = contexts[contextIndex].name();
            List<String> contextDocuments = List.copyOf(documentKeysByContext.get(context));
            List<String> stableDocuments = contextDocuments.stream()
                    .filter(key -> key.startsWith("capacity-doc-"))
                    .toList();
            if (stableDocuments.isEmpty()) {
                throw new IllegalStateException(
                        "El contexto no conserva documentos reusables: " + context);
            }
            for (int requirementIndex = 0;
                    requirementIndex < REQUIREMENTS_PER_CONTEXT;
                    requirementIndex++) {
                boolean replacement = contextIndex < 2 && requirementIndex < 3;
                String version = side == MaximumEditorialSide.TARGET && replacement
                        ? "2.0.0"
                        : "1.0.0";
                String key = "capacity-requirement-%02d-%02d"
                        .formatted(contextIndex, requirementIndex);
                String statement = "Confirmo el requisito editorial %02d-%02d versión %s."
                        .formatted(contextIndex, requirementIndex, version);
                ObjectNode requirement = requirements.addObject();
                requirement.put("key", key);
                requirement.put("version", version);
                requirement.put("context", context);
                ArrayNode roles = requirement.putArray("roles");
                for (AudienciaLegal audience : AudienciaLegal.values()) {
                    roles.add(audience.name());
                }
                requirement.put("actType", "ACEPTACION");
                requirement.put("statement", statement);
                requirement.put("statementSha256", sha256(statement));
                ArrayNode references = requirement.putArray("documents");
                if (replacement) {
                    contextDocuments.forEach(references::add);
                } else {
                    stableDocuments.forEach(references::add);
                }
                requirement.put("required", true);
                requirement.put("requiresReacceptance", replacement);
            }
        }
        if (requirements.size() != MAXIMUM_EDITORIAL_REQUIREMENTS) {
            throw new IllegalStateException(
                    "El fixture máximo no produjo 256 requisitos: " + requirements.size());
        }
    }

    private static boolean isMaximumEditorialChangeSlot(int typeIndex, int contextIndex) {
        return (typeIndex == 0 && contextIndex < 2)
                || (typeIndex == 1 && contextIndex < 2)
                || ((typeIndex == 2 || typeIndex == 3) && contextIndex == 0);
    }

    private static void addCapacityDocument(
            Path manifestPath,
            ArrayNode documents,
            Map<String, List<String>> documentKeysByContext,
            String key,
            String type,
            String version,
            List<String> contexts,
            String contentSeed) throws Exception {
        String source = key + ".md";
        String markdown = "# Documento " + key + "\n\n"
                + "Contenido editorial determinista " + contentSeed + ".\n";
        Files.writeString(
                manifestPath.getParent().resolve(source),
                markdown,
                StandardCharsets.UTF_8);
        ObjectNode document = documents.addObject();
        document.put("key", key);
        document.put("type", type);
        document.put("version", version);
        document.put("locale", LocaleLegal.ES_AR.getCodigo());
        document.put("source", source);
        document.put("sha256", sha256(markdown));
        document.put("effectiveAt", EFFECTIVE_AT);
        ArrayNode declaredContexts = document.putArray("contexts");
        for (String context : contexts) {
            declaredContexts.add(context);
            List<String> keys = documentKeysByContext.get(context);
            if (keys == null) {
                throw new IllegalArgumentException(
                        "Contexto desconocido en documento máximo: " + context);
            }
            keys.add(key);
        }
        document.put("requiresReacceptance", true);
    }

    private static ResolvedReplacementBatch resolveReplacementBatch(
            ReplacementBatchSpec spec,
            Map<String, DocumentVersion> sourceDocuments,
            Map<String, DocumentVersion> targetDocuments) {
        List<DocumentVersion> predecessors = spec.predecessorKeys().stream()
                .map(key -> requireDocument(sourceDocuments, key, "predecessor"))
                .toList();
        List<DocumentVersion> successors = spec.successorKeys().stream()
                .map(key -> requireDocument(targetDocuments, key, "successor"))
                .toList();
        if (predecessors.size() != 1 && successors.size() != 1) {
            throw new IllegalArgumentException(
                    "Cada lote explícito debe tener un único lado de cardinalidad uno");
        }
        DocumentVersion first = predecessors.getFirst();
        if (predecessors.stream().anyMatch(document ->
                        !first.type().equals(document.type())
                                || !first.locale().equals(document.locale()))
                || successors.stream().anyMatch(document ->
                        !first.type().equals(document.type())
                                || !first.locale().equals(document.locale()))) {
            throw new IllegalArgumentException(
                    "Los miembros de un lote explícito deben compartir tipo y locale");
        }
        Set<String> expectedContexts = Set.copyOf(spec.contexts());
        if (!contextUnion(predecessors).equals(expectedContexts)
                || !contextUnion(successors).equals(expectedContexts)) {
            throw new IllegalArgumentException(
                    "Los lados del lote explícito no cubren exactamente sus contextos");
        }
        return new ResolvedReplacementBatch(
                spec.batchId(),
                predecessors,
                successors,
                spec.contexts());
    }

    private static DocumentVersion requireDocument(
            Map<String, DocumentVersion> documents,
            String key,
            String side) {
        DocumentVersion document = documents.get(key);
        if (document == null) {
            throw new IllegalArgumentException(
                    "Documento " + side + " inexistente en lote explícito: " + key);
        }
        return document;
    }

    private static Set<String> contextUnion(List<DocumentVersion> documents) {
        LinkedHashSet<String> contexts = new LinkedHashSet<>();
        for (DocumentVersion document : documents) {
            for (String context : document.contexts()) {
                if (!contexts.add(context)) {
                    throw new IllegalArgumentException(
                            "Los documentos del mismo lado de un lote solapan contextos");
                }
            }
        }
        return Set.copyOf(contexts);
    }

    private static void requireDisjointReplacementBatches(
            List<ResolvedReplacementBatch> batches) {
        Set<UUID> batchIds = new HashSet<>();
        Set<UUID> documentIds = new HashSet<>();
        for (ResolvedReplacementBatch batch : batches) {
            if (!batchIds.add(batch.batchId())) {
                throw new IllegalArgumentException("UUID de lote explícito duplicado");
            }
            for (DocumentVersion predecessor : batch.predecessors()) {
                if (!documentIds.add(predecessor.id())) {
                    throw new IllegalArgumentException(
                            "Documento solapado entre lotes explícitos");
                }
            }
            for (DocumentVersion successor : batch.successors()) {
                if (!documentIds.add(successor.id())) {
                    throw new IllegalArgumentException(
                            "Documento solapado entre lotes explícitos");
                }
            }
        }
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

    private static Map<String, DocumentVersion> indexDocumentsByKey(
            List<DocumentVersion> values) {
        return values.stream().collect(Collectors.toMap(
                DocumentVersion::key,
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

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 no disponible en el fixture", impossible);
        }
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

    private static List<String> immutableVisibleTokens(List<String> values, String name) {
        List<String> required = List.copyOf(Objects.requireNonNull(values, name));
        required.forEach(value -> requireVisibleToken(value, name));
        return required;
    }

    record ImportedRelease(ValidatedRelease release, UUID publicationId) {

        ImportedRelease {
            Objects.requireNonNull(release, "release");
            Objects.requireNonNull(publicationId, "publicationId");
        }
    }

    record ReplacementBatchSpec(
            UUID batchId,
            List<String> predecessorKeys,
            List<String> successorKeys,
            List<String> contexts) {

        ReplacementBatchSpec {
            Objects.requireNonNull(batchId, "batchId");
            predecessorKeys = immutableVisibleTokens(predecessorKeys, "predecessorKeys");
            successorKeys = immutableVisibleTokens(successorKeys, "successorKeys");
            contexts = immutableVisibleTokens(contexts, "contexts");
            if (predecessorKeys.isEmpty() || successorKeys.isEmpty() || contexts.isEmpty()) {
                throw new IllegalArgumentException(
                        "Un lote explícito requiere ambos lados y al menos un contexto");
            }
            if (Set.copyOf(predecessorKeys).size() != predecessorKeys.size()
                    || Set.copyOf(successorKeys).size() != successorKeys.size()
                    || Set.copyOf(contexts).size() != contexts.size()) {
                throw new IllegalArgumentException(
                        "Un lote explícito no admite miembros ni contextos duplicados");
            }
        }
    }

    record MaximumEditorialFixture(
            ImportedRelease source,
            ImportedRelease target,
            ReplaceFixture replacement,
            int documentSlots) {

        MaximumEditorialFixture {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(replacement, "replacement");
            if (documentSlots != MAXIMUM_EDITORIAL_SLOTS) {
                throw new IllegalArgumentException(
                        "El máximo editorial debe exponer exactamente 88 slots");
            }
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
            List<String> contexts) { }

    private record ResolvedReplacementBatch(
            UUID batchId,
            List<DocumentVersion> predecessors,
            List<DocumentVersion> successors,
            List<String> contexts) {

        private ResolvedReplacementBatch {
            Objects.requireNonNull(batchId, "batchId");
            predecessors = List.copyOf(predecessors);
            successors = List.copyOf(successors);
            contexts = List.copyOf(contexts);
        }
    }

    private enum MaximumEditorialSide {
        SOURCE,
        TARGET
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
            List<String> audiences) { }
}
