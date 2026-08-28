package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.ConfinedEditorialPlanReader.EditorialPlanSource;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialPlanParser.ParsedEditorialPlan;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalEditorialPlanV1;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalEditorialPlanV1.DocumentRef;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalEditorialPlanV1.DocumentReplacementBatch;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalEditorialPlanV1.DocumentRetirement;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalEditorialPlanV1.DocumentScopedRef;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalEditorialPlanV1.OperationType;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalEditorialPlanV1.RequirementRef;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalEditorialPlanV1.RequirementReplacement;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalEditorialPlanV1.RequirementRetirement;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalEditorialPlanV1.RequirementScopedRef;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/** Single public entry point that emits the opaque, normalized editorial-plan accreditation. */
public final class LegalEditorialPlanValidator {

    private static final String PLAN_LOCATION = ConfinedEditorialPlanReader.PLAN_FILENAME;
    private static final Comparator<UUID> UUID_TEXT_ORDER = Comparator.comparing(UUID::toString);

    private final ConfinedEditorialPlanReader planReader;
    private final LegalEditorialPlanParser planParser;

    public LegalEditorialPlanValidator() {
        this(new ConfinedEditorialPlanReader(), new LegalEditorialPlanParser());
    }

    LegalEditorialPlanValidator(
            ConfinedEditorialPlanReader planReader,
            LegalEditorialPlanParser planParser) {
        this.planReader = Objects.requireNonNull(planReader, "planReader");
        this.planParser = Objects.requireNonNull(planParser, "planParser");
    }

    /** Validates path, bytes, schema and all operation/cross-collection invariants. */
    public LegalManifestValidation<ValidatedEditorialPlan> validate(Path planPath) {
        try {
            LegalManifestValidation<EditorialPlanSource> sourceResult = planReader.read(planPath);
            if (!sourceResult.passed()) {
                return sourceResult.asFailure();
            }
            LegalManifestValidation<ParsedEditorialPlan> parsedResult = planParser.parse(
                    sourceResult.value().orElseThrow().bytes());
            if (!parsedResult.passed()) {
                return parsedResult.asFailure();
            }
            return validateParsed(parsedResult.value().orElseThrow());
        } catch (RuntimeException | LinkageError exception) {
            return failure(LegalManifestIssueCode.EDITORIAL_PLAN_VALIDATION_ERROR, PLAN_LOCATION);
        }
    }

    private static LegalManifestValidation<ValidatedEditorialPlan> validateParsed(
            ParsedEditorialPlan parsed) {
        LegalEditorialPlanV1 plan = parsed.plan();
        List<LegalManifestIssue> issues = new ArrayList<>();

        if (plan.schemaVersion() != 1) {
            issues.add(issue(
                    LegalManifestIssueCode.EDITORIAL_PLAN_SCHEMA_INVALID,
                    "schemaVersion"));
        }

        validateOperationMatrix(plan, issues);
        validateReasons(plan, issues);
        validateDocumentMapping(plan, issues);
        validateRequirementMapping(plan, issues);

        if (!issues.isEmpty()) {
            return LegalManifestValidation.failure(issues);
        }
        return LegalManifestValidation.pass(new ValidatedEditorialPlan(
                normalize(plan),
                parsed.canonicalJson(),
                parsed.editorialPlanSha256()));
    }

    private static void validateOperationMatrix(
            LegalEditorialPlanV1 plan,
            List<LegalManifestIssue> issues) {
        if (plan.operationType() == OperationType.REPLACE) {
            if (plan.expectedCurrentPublicationId().equals(plan.targetPublicationId())) {
                issues.add(issue(
                        LegalManifestIssueCode.REPLACEMENT_MAPPING_INVALID,
                        "targetPublicationId"));
            }
            if (plan.expectedReadinessAfter() != LegalEditorialReadiness.READY) {
                issues.add(issue(
                        LegalManifestIssueCode.EXPECTED_READINESS_MISMATCH,
                        "expectedReadinessAfter"));
            }
            if (plan.acknowledgeFailClosedGap()) {
                issues.add(issue(
                        LegalManifestIssueCode.FAIL_CLOSED_GAP_NOT_ACKNOWLEDGED,
                        "acknowledgeFailClosedGap"));
            }
            if (allChangeCollectionsEmpty(plan)) {
                issues.add(issue(
                        LegalManifestIssueCode.REPLACEMENT_MAPPING_INVALID,
                        PLAN_LOCATION));
            }
            return;
        }

        if (!plan.expectedCurrentPublicationId().equals(plan.targetPublicationId())
                || !plan.expectedCurrentManifestSha256().equals(plan.targetManifestSha256())) {
            issues.add(issue(
                    LegalManifestIssueCode.REPLACEMENT_MAPPING_INVALID,
                    "targetPublicationId"));
        }
        if (plan.expectedReadinessAfter() != LegalEditorialReadiness.NOT_READY) {
            issues.add(issue(
                    LegalManifestIssueCode.EXPECTED_READINESS_MISMATCH,
                    "expectedReadinessAfter"));
        }
        if (!plan.acknowledgeFailClosedGap()) {
            issues.add(issue(
                    LegalManifestIssueCode.FAIL_CLOSED_GAP_NOT_ACKNOWLEDGED,
                    "acknowledgeFailClosedGap"));
        }
        if (!retireOnlyCollections(plan)) {
            issues.add(issue(
                    LegalManifestIssueCode.REPLACEMENT_MAPPING_INVALID,
                    PLAN_LOCATION));
        }
        if (plan.documentRetirements().isEmpty() && plan.requirementRetirements().isEmpty()) {
            issues.add(issue(
                    LegalManifestIssueCode.REPLACEMENT_MAPPING_INVALID,
                    PLAN_LOCATION));
        }
    }

    private static boolean allChangeCollectionsEmpty(LegalEditorialPlanV1 plan) {
        return plan.documentAdditions().isEmpty()
                && plan.documentReuses().isEmpty()
                && plan.documentReplacementBatches().isEmpty()
                && plan.documentRetirements().isEmpty()
                && plan.requirementAdditions().isEmpty()
                && plan.requirementReuses().isEmpty()
                && plan.requirementReplacements().isEmpty()
                && plan.requirementRetirements().isEmpty();
    }

    private static boolean retireOnlyCollections(LegalEditorialPlanV1 plan) {
        return plan.documentAdditions().isEmpty()
                && plan.documentReuses().isEmpty()
                && plan.documentReplacementBatches().isEmpty()
                && plan.requirementAdditions().isEmpty()
                && plan.requirementReuses().isEmpty()
                && plan.requirementReplacements().isEmpty();
    }

    private static void validateReasons(
            LegalEditorialPlanV1 plan,
            List<LegalManifestIssue> issues) {
        for (int index = 0; index < plan.documentRetirements().size(); index++) {
            validateReason(
                    plan.documentRetirements().get(index).reason(),
                    "documentRetirements/" + index + "/reason",
                    issues);
        }
        for (int index = 0; index < plan.requirementRetirements().size(); index++) {
            validateReason(
                    plan.requirementRetirements().get(index).reason(),
                    "requirementRetirements/" + index + "/reason",
                    issues);
        }
    }

    private static void validateReason(
            String reason,
            String location,
            List<LegalManifestIssue> issues) {
        boolean valid = !reason.isEmpty()
                && reason.indexOf('\u0000') < 0
                && reason.codePointCount(0, reason.length())
                    <= LegalEditorialPlanLimits.MAX_REASON_CODE_POINTS
                && !isBoundaryWhitespace(reason.codePointAt(0))
                && !isBoundaryWhitespace(reason.codePointBefore(reason.length()));
        if (!valid) {
            issues.add(issue(LegalManifestIssueCode.RETIREMENT_REASON_REQUIRED, location));
        }
    }

    private static boolean isBoundaryWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private static void validateDocumentMapping(
            LegalEditorialPlanV1 plan,
            List<LegalManifestIssue> issues) {
        Set<UUID> source = new HashSet<>();
        Set<UUID> target = new HashSet<>();
        Set<UUID> reuses = new HashSet<>();
        Set<UUID> batchIds = new HashSet<>();
        Map<UUID, String> digests = new HashMap<>();

        for (int index = 0; index < plan.documentAdditions().size(); index++) {
            DocumentScopedRef item = plan.documentAdditions().get(index);
            validateContexts(item.contexts(), "documentAdditions/" + index + "/contexts", issues);
            addVersion(target, digests, item.documentVersionId(), item.sha256(),
                    "documentAdditions/" + index, issues);
        }
        for (int index = 0; index < plan.documentReuses().size(); index++) {
            DocumentScopedRef item = plan.documentReuses().get(index);
            String location = "documentReuses/" + index;
            validateContexts(item.contexts(), location + "/contexts", issues);
            if (!reuses.add(item.documentVersionId())) {
                mappingIssue(location, issues);
            }
            addVersion(source, digests, item.documentVersionId(), item.sha256(), location, issues);
            addVersion(target, digests, item.documentVersionId(), item.sha256(), location, issues);
        }
        for (int index = 0; index < plan.documentRetirements().size(); index++) {
            DocumentRetirement item = plan.documentRetirements().get(index);
            String location = "documentRetirements/" + index;
            validateContexts(item.contexts(), location + "/contexts", issues);
            addVersion(source, digests, item.documentVersionId(), item.sha256(), location, issues);
        }
        if (plan.documentReplacementBatches().size()
                > LegalEditorialPlanLimits.MAX_REPLACEMENT_BATCHES) {
            mappingIssue("documentReplacementBatches", issues);
        }
        for (int batchIndex = 0;
                batchIndex < plan.documentReplacementBatches().size();
                batchIndex++) {
            DocumentReplacementBatch batch = plan.documentReplacementBatches().get(batchIndex);
            String location = "documentReplacementBatches/" + batchIndex;
            if (!batchIds.add(batch.replacementBatchId())) {
                mappingIssue(location + "/replacementBatchId", issues);
            }
            validateContexts(batch.contexts(), location + "/contexts", issues);
            if (batch.predecessors().isEmpty() || batch.successors().isEmpty()) {
                mappingIssue(location, issues);
            }
            Set<UUID> localPredecessors = new HashSet<>();
            for (int index = 0; index < batch.predecessors().size(); index++) {
                DocumentRef item = batch.predecessors().get(index);
                String itemLocation = location + "/predecessors/" + index;
                if (!localPredecessors.add(item.documentVersionId())) {
                    mappingIssue(itemLocation, issues);
                }
                addVersion(source, digests, item.documentVersionId(), item.sha256(),
                        itemLocation, issues);
            }
            Set<UUID> localSuccessors = new HashSet<>();
            for (int index = 0; index < batch.successors().size(); index++) {
                DocumentRef item = batch.successors().get(index);
                String itemLocation = location + "/successors/" + index;
                if (!localSuccessors.add(item.documentVersionId())) {
                    mappingIssue(itemLocation, issues);
                }
                if (localPredecessors.contains(item.documentVersionId())) {
                    mappingIssue(itemLocation, issues);
                }
                addVersion(target, digests, item.documentVersionId(), item.sha256(),
                        itemLocation, issues);
            }
        }

        validateCrossSideIntersection(source, target, reuses, "documents", issues);
        if (source.size() > LegalEditorialPlanLimits.MAX_DOCUMENTS
                || target.size() > LegalEditorialPlanLimits.MAX_DOCUMENTS) {
            mappingIssue("documents", issues);
        }
    }

    private static void validateRequirementMapping(
            LegalEditorialPlanV1 plan,
            List<LegalManifestIssue> issues) {
        Set<UUID> source = new HashSet<>();
        Set<UUID> target = new HashSet<>();
        Set<UUID> reuses = new HashSet<>();
        Map<UUID, String> digests = new HashMap<>();

        for (int index = 0; index < plan.requirementAdditions().size(); index++) {
            RequirementScopedRef item = plan.requirementAdditions().get(index);
            String location = "requirementAdditions/" + index;
            validateAudiences(item.audiences(), location + "/audiences", issues);
            addVersion(target, digests, item.requirementVersionId(), item.statementSha256(),
                    location, issues);
        }
        for (int index = 0; index < plan.requirementReuses().size(); index++) {
            RequirementScopedRef item = plan.requirementReuses().get(index);
            String location = "requirementReuses/" + index;
            validateAudiences(item.audiences(), location + "/audiences", issues);
            if (!reuses.add(item.requirementVersionId())) {
                mappingIssue(location, issues);
            }
            addVersion(source, digests, item.requirementVersionId(), item.statementSha256(),
                    location, issues);
            addVersion(target, digests, item.requirementVersionId(), item.statementSha256(),
                    location, issues);
        }
        for (int index = 0; index < plan.requirementRetirements().size(); index++) {
            RequirementRetirement item = plan.requirementRetirements().get(index);
            String location = "requirementRetirements/" + index;
            validateAudiences(item.audiences(), location + "/audiences", issues);
            addVersion(source, digests, item.requirementVersionId(), item.statementSha256(),
                    location, issues);
        }
        for (int index = 0; index < plan.requirementReplacements().size(); index++) {
            RequirementReplacement item = plan.requirementReplacements().get(index);
            String location = "requirementReplacements/" + index;
            validateAudiences(item.audiences(), location + "/audiences", issues);
            RequirementRef predecessor = item.predecessor();
            RequirementRef successor = item.successor();
            if (predecessor.requirementVersionId().equals(successor.requirementVersionId())) {
                mappingIssue(location, issues);
            }
            addVersion(source, digests,
                    predecessor.requirementVersionId(), predecessor.statementSha256(),
                    location + "/predecessor", issues);
            addVersion(target, digests,
                    successor.requirementVersionId(), successor.statementSha256(),
                    location + "/successor", issues);
        }

        validateCrossSideIntersection(source, target, reuses, "requirements", issues);
        if (source.size() > LegalEditorialPlanLimits.MAX_REQUIREMENTS
                || target.size() > LegalEditorialPlanLimits.MAX_REQUIREMENTS) {
            mappingIssue("requirements", issues);
        }
    }

    private static void addVersion(
            Set<UUID> side,
            Map<UUID, String> digests,
            UUID id,
            String digest,
            String location,
            List<LegalManifestIssue> issues) {
        if (!side.add(id)) {
            mappingIssue(location, issues);
        }
        String previous = digests.putIfAbsent(id, digest);
        if (previous != null && !previous.equals(digest)) {
            mappingIssue(location, issues);
        }
    }

    private static void validateCrossSideIntersection(
            Set<UUID> source,
            Set<UUID> target,
            Set<UUID> reuses,
            String location,
            List<LegalManifestIssue> issues) {
        Set<UUID> intersection = new HashSet<>(source);
        intersection.retainAll(target);
        if (!intersection.equals(reuses)) {
            mappingIssue(location, issues);
        }
    }

    private static void validateContexts(
            List<ContextoLegal> contexts,
            String location,
            List<LegalManifestIssue> issues) {
        if (contexts.isEmpty()
                || contexts.size() > LegalEditorialPlanLimits.MAX_CONTEXTS
                || new HashSet<>(contexts).size() != contexts.size()) {
            mappingIssue(location, issues);
        }
    }

    private static void validateAudiences(
            List<AudienciaLegal> audiences,
            String location,
            List<LegalManifestIssue> issues) {
        if (audiences.isEmpty()
                || audiences.size() > LegalEditorialPlanLimits.MAX_AUDIENCES
                || new HashSet<>(audiences).size() != audiences.size()) {
            mappingIssue(location, issues);
        }
    }

    private static void mappingIssue(String location, List<LegalManifestIssue> issues) {
        issues.add(issue(LegalManifestIssueCode.REPLACEMENT_MAPPING_INVALID, location));
    }

    private static LegalEditorialPlanV1 normalize(LegalEditorialPlanV1 plan) {
        return new LegalEditorialPlanV1(
                plan.schemaVersion(),
                plan.operationId(),
                plan.operationType(),
                plan.expectedCurrentPublicationId(),
                plan.expectedCurrentManifestSha256(),
                plan.expectedEditorialStateFingerprint(),
                plan.targetPublicationId(),
                plan.targetManifestSha256(),
                sorted(plan.documentAdditions(),
                        item -> new DocumentScopedRef(
                                item.documentVersionId(),
                                item.sha256(),
                                sortedEnums(item.contexts())),
                        Comparator.comparing(DocumentScopedRef::documentVersionId, UUID_TEXT_ORDER)),
                sorted(plan.documentReuses(),
                        item -> new DocumentScopedRef(
                                item.documentVersionId(),
                                item.sha256(),
                                sortedEnums(item.contexts())),
                        Comparator.comparing(DocumentScopedRef::documentVersionId, UUID_TEXT_ORDER)),
                sorted(plan.documentReplacementBatches(),
                        LegalEditorialPlanValidator::normalizeBatch,
                        Comparator.comparing(LegalEditorialPlanValidator::minimumMemberUuid)
                                .thenComparing(
                                        DocumentReplacementBatch::replacementBatchId,
                                        UUID_TEXT_ORDER)),
                sorted(plan.documentRetirements(),
                        item -> new DocumentRetirement(
                                item.documentVersionId(),
                                item.sha256(),
                                sortedEnums(item.contexts()),
                                item.reason()),
                        Comparator.comparing(DocumentRetirement::documentVersionId, UUID_TEXT_ORDER)),
                sorted(plan.requirementAdditions(),
                        LegalEditorialPlanValidator::normalizeRequirementScopedRef,
                        Comparator.comparing(
                                RequirementScopedRef::requirementVersionId,
                                UUID_TEXT_ORDER)),
                sorted(plan.requirementReuses(),
                        LegalEditorialPlanValidator::normalizeRequirementScopedRef,
                        Comparator.comparing(
                                RequirementScopedRef::requirementVersionId,
                                UUID_TEXT_ORDER)),
                sorted(plan.requirementReplacements(),
                        item -> new RequirementReplacement(
                                item.predecessor(),
                                item.successor(),
                                item.context(),
                                sortedEnums(item.audiences())),
                        Comparator.comparing(
                                        (RequirementReplacement item) ->
                                                item.predecessor().requirementVersionId(),
                                        UUID_TEXT_ORDER)
                                .thenComparing(
                                        item -> item.successor().requirementVersionId(),
                                        UUID_TEXT_ORDER)),
                sorted(plan.requirementRetirements(),
                        item -> new RequirementRetirement(
                                item.requirementVersionId(),
                                item.statementSha256(),
                                item.context(),
                                sortedEnums(item.audiences()),
                                item.reason()),
                        Comparator.comparing(
                                RequirementRetirement::requirementVersionId,
                                UUID_TEXT_ORDER)),
                plan.expectedReadinessAfter(),
                plan.acknowledgeFailClosedGap());
    }

    private static DocumentReplacementBatch normalizeBatch(DocumentReplacementBatch batch) {
        return new DocumentReplacementBatch(
                batch.replacementBatchId(),
                sortedEnums(batch.contexts()),
                sorted(batch.predecessors(), Function.identity(),
                        Comparator.comparing(DocumentRef::documentVersionId, UUID_TEXT_ORDER)),
                sorted(batch.successors(), Function.identity(),
                        Comparator.comparing(DocumentRef::documentVersionId, UUID_TEXT_ORDER)));
    }

    private static String minimumMemberUuid(DocumentReplacementBatch batch) {
        return java.util.stream.Stream.concat(
                        batch.predecessors().stream(),
                        batch.successors().stream())
                .map(DocumentRef::documentVersionId)
                .map(UUID::toString)
                .min(String::compareTo)
                .orElseThrow();
    }

    private static RequirementScopedRef normalizeRequirementScopedRef(
            RequirementScopedRef item) {
        return new RequirementScopedRef(
                item.requirementVersionId(),
                item.statementSha256(),
                item.context(),
                sortedEnums(item.audiences()));
    }

    private static <T, R> List<R> sorted(
            Collection<T> values,
            Function<T, R> mapper,
            Comparator<R> comparator) {
        return values.stream().map(mapper).sorted(comparator).toList();
    }

    private static <E extends Enum<E>> List<E> sortedEnums(Collection<E> values) {
        return values.stream().sorted(Comparator.comparing(Enum::name)).toList();
    }

    private static LegalManifestIssue issue(
            LegalManifestIssueCode code,
            String location) {
        return LegalManifestIssue.at(code, location);
    }

    private static <T> LegalManifestValidation<T> failure(
            LegalManifestIssueCode code,
            String location) {
        return LegalManifestValidation.failure(issue(code, location));
    }

    /** Opaque token: raw artifact identity plus a deeply immutable, semantically sorted copy. */
    public static final class ValidatedEditorialPlan {

        private final LegalEditorialPlanV1 plan;
        private final String canonicalJson;
        private final String editorialPlanSha256;

        private ValidatedEditorialPlan(
                LegalEditorialPlanV1 plan,
                String canonicalJson,
                String editorialPlanSha256) {
            this.plan = Objects.requireNonNull(plan, "plan");
            this.canonicalJson = Objects.requireNonNull(canonicalJson, "canonicalJson");
            this.editorialPlanSha256 = Objects.requireNonNull(
                    editorialPlanSha256,
                    "editorialPlanSha256");
        }

        public LegalEditorialPlanV1 plan() {
            return plan;
        }

        public UUID operationId() {
            return plan.operationId();
        }

        public OperationType operationType() {
            return plan.operationType();
        }

        public String canonicalJson() {
            return canonicalJson;
        }

        public String editorialPlanSha256() {
            return editorialPlanSha256;
        }
    }
}
