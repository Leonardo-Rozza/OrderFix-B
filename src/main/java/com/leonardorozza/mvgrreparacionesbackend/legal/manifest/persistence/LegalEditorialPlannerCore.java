package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialPlanValidator.ValidatedEditorialPlan;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialReadiness;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalEditorialPlanV1;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalEditorialPlanV1.DocumentRef;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalEditorialPlanV1.DocumentReplacementBatch;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalEditorialPlanV1.DocumentRetirement;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalEditorialPlanV1.DocumentScopedRef;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalEditorialPlanV1.RequirementRef;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalEditorialPlanV1.RequirementReplacement;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalEditorialPlanV1.RequirementRetirement;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalEditorialPlanV1.RequirementScopedRef;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalManifestV1.DocumentEntry;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalManifestV1.ReviewStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalPublicationPlan;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoVersionLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** SELECT-only deterministic planner over the accredited V27 editorial graph. */
final class LegalEditorialPlannerCore {

    static final int MAX_ACTIVE_SLOTS = 1_024;
    static final int MAX_ACTIVE_POINTERS = 64;
    static final int MAX_ACTIVE_POINTER_MEMBERS = 16_384;
    static final int MAX_ACTIVE_POINTER_DOCUMENT_REFERENCES = 32_768;
    static final int MAX_RELEVANT_DOCUMENTS = 1_024;
    static final int MAX_RELEVANT_REQUIREMENTS = 2_048;
    static final int MAX_RELEVANT_TRANSITIONS = 8_192;
    static final int MAX_RELEVANT_BATCHES = 512;

    private static final String PUBLICATION_LOCATION = "database/publication";
    private static final String STATE_LOCATION = "database/state";
    private static final String MAPPING_LOCATION = "database/mapping";
    private static final String SOURCE_LOCATION = "database/source";

    private static final Comparator<UUID> UUID_TEXT_ORDER =
            Comparator.comparing(UUID::toString);
    private static final LegalEditorialReplaceScopeGuard REPLACE_SCOPE_GUARD =
            new LegalEditorialReplaceScopeGuard();

    private final JdbcTemplate jdbc;
    private final LegalEditorialReadinessCore readinessCore;
    private final LegalManifestOriginGraphVerifier originGraphVerifier;
    private final PlannerStateReader stateReader;

    LegalEditorialPlannerCore(
            JdbcTemplate jdbc,
            LegalEditorialReadinessCore readinessCore,
            LegalManifestOriginGraphVerifier originGraphVerifier) {
        this(jdbc, readinessCore, originGraphVerifier, new JdbcPlannerStateReader(jdbc));
    }

    LegalEditorialPlannerCore(
            JdbcTemplate jdbc,
            LegalEditorialReadinessCore readinessCore,
            LegalManifestOriginGraphVerifier originGraphVerifier,
            PlannerStateReader stateReader) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.readinessCore = Objects.requireNonNull(readinessCore, "readinessCore");
        this.originGraphVerifier = Objects.requireNonNull(
                originGraphVerifier,
                "originGraphVerifier");
        this.stateReader = Objects.requireNonNull(stateReader, "stateReader");
        if (!readinessCore.usesJdbc(jdbc)
                || !originGraphVerifier.usesJdbc(jdbc)
                || !stateReader.usesJdbc(jdbc)) {
            throw new IllegalArgumentException(
                    "El planner editorial requiere una única sesión JDBC compartida");
        }
    }

    LegalEditorialPlanResult planPromote(ValidatedRelease target, Instant observedAt) {
        Objects.requireNonNull(target, "target");
        Instant timestamp = requirePostgresInstant(observedAt);
        TargetAccreditation accredited = accreditTarget(
                target,
                target.plan().manifest().publicationId(),
                target.plan().manifestSha256());
        PlannerSnapshot snapshot = stateReader.snapshot(
                accredited.publication().id(),
                accredited.publication().id(),
                Set.of(),
                Set.of(),
                Set.of());
        LegalEditorialReadinessResult readiness = readinessCore.evaluate(target, timestamp);
        if (readiness.readiness() == LegalEditorialReadiness.ERROR) {
            return LegalEditorialPlanResult.error(readiness.issues());
        }
        LegalEditorialReadinessObservation observation = readiness.observation().orElseThrow();

        if (readiness.readiness() == LegalEditorialReadiness.READY
                && exactPromotedPostState(snapshot, observation, accredited)) {
            return LegalEditorialPlanResult.applicable(promotionPlan(
                    snapshot,
                    accredited.publication(),
                    timestamp,
                    false));
        }
        if (containsFutureEffectiveDocument(snapshot, timestamp)) {
            return blocked(LegalManifestIssueCode.EFFECTIVE_DATE_NOT_REACHED, STATE_LOCATION);
        }
        if (exactInitialPromotionSource(snapshot, observation, accredited)) {
            return LegalEditorialPlanResult.applicable(promotionPlan(
                    snapshot,
                    accredited.publication(),
                    timestamp,
                    true));
        }
        if (observation.documentTransitions() != 0
                || observation.requirementTransitions() != 0
                || observation.documentSlots() != 0
                || observation.currentRequirementSets() != 0
                || observation.replacementLots() != 0) {
            return blocked(
                    LegalManifestIssueCode.INITIAL_PROJECTION_ALREADY_EXISTS,
                    STATE_LOCATION);
        }
        return blocked(LegalManifestIssueCode.CURRENT_STATE_MISMATCH, STATE_LOCATION);
    }

    LegalEditorialPlanResult planReplace(
            ValidatedRelease target,
            ValidatedEditorialPlan validatedPlan,
            Instant observedAt) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(validatedPlan, "validatedPlan");
        var scopedPlan = REPLACE_SCOPE_GUARD.validate(validatedPlan);
        if (scopedPlan.value().isEmpty()) {
            return LegalEditorialPlanResult.blocked(scopedPlan.issues());
        }
        Instant timestamp = requirePostgresInstant(observedAt);
        LegalEditorialPlanV1 plan = validatedPlan.plan();
        LegalEditorialPlanResult bindingFailure = requirePlanBinding(
                target,
                plan,
                LegalEditorialPlanV1.OperationType.REPLACE);
        if (bindingFailure != null) {
            return bindingFailure;
        }

        TargetAccreditation targetAccreditation = accreditTarget(
                target,
                plan.targetPublicationId(),
                plan.targetManifestSha256());
        Optional<PublicationEvidence> sourcePublication = stateReader.publication(
                plan.expectedCurrentPublicationId());
        if (sourcePublication.isEmpty()
                || !sourcePublication.orElseThrow().sealed()
                || !sourcePublication.orElseThrow().manifestSha256()
                        .equals(plan.expectedCurrentManifestSha256())) {
            return blocked(LegalManifestIssueCode.CURRENT_STATE_MISMATCH, SOURCE_LOCATION);
        }

        DeclaredIds declared = declaredIds(plan);
        PlannerSnapshot snapshot = stateReader.snapshot(
                targetAccreditation.publication().id(),
                sourcePublication.orElseThrow().id(),
                declared.documentIds(),
                declared.requirementIds(),
                declared.batchIds());

        MappingCheck mapping = validateReplacementMapping(
                snapshot,
                plan);
        if (!mapping.valid()) {
            return blocked(LegalManifestIssueCode.REPLACEMENT_MAPPING_INVALID, MAPPING_LOCATION);
        }

        LegalEditorialReadinessResult readiness = readinessCore.evaluate(target, timestamp);
        if (readiness.readiness() == LegalEditorialReadiness.ERROR) {
            return LegalEditorialPlanResult.error(readiness.issues());
        }
        if (readiness.readiness() == LegalEditorialReadiness.READY) {
            PlanAttempt post = replacementPlan(
                    snapshot,
                    validatedPlan,
                    sourcePublication.orElseThrow(),
                    targetAccreditation.publication(),
                    timestamp,
                    Phase.POST_STATE);
            if (post.plan().isPresent()) {
                return LegalEditorialPlanResult.applicable(post.plan().orElseThrow());
            }
        }

        LegalEditorialReadinessObservation sourceObservation = readinessCore.observeState(
                plan.expectedCurrentPublicationId(),
                timestamp);
        if (!sourceObservation.publicationUuid().equals(Optional.of(sourcePublication.orElseThrow().id()))
                || !sourceObservation.editorialStateFingerprint()
                        .equals(plan.expectedEditorialStateFingerprint())) {
            return blocked(LegalManifestIssueCode.SOURCE_FINGERPRINT_MISMATCH, SOURCE_LOCATION);
        }
        if (containsFutureEffectiveDocument(snapshot, declared.targetDocumentIds(), timestamp)) {
            return blocked(LegalManifestIssueCode.EFFECTIVE_DATE_NOT_REACHED, STATE_LOCATION);
        }

        PlanAttempt source = replacementPlan(
                snapshot,
                validatedPlan,
                sourcePublication.orElseThrow(),
                targetAccreditation.publication(),
                timestamp,
                Phase.SOURCE_STATE);
        if (source.plan().isPresent()) {
            return LegalEditorialPlanResult.applicable(source.plan().orElseThrow());
        }
        return blocked(source.failureCode(), STATE_LOCATION);
    }

    LegalEditorialPlanResult planRetire(
            ValidatedRelease current,
            ValidatedEditorialPlan validatedPlan,
            Instant observedAt) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(validatedPlan, "validatedPlan");
        Instant timestamp = requirePostgresInstant(observedAt);
        LegalEditorialPlanV1 plan = validatedPlan.plan();
        LegalEditorialPlanResult bindingFailure = requirePlanBinding(
                current,
                plan,
                LegalEditorialPlanV1.OperationType.RETIRE);
        if (bindingFailure != null) {
            return bindingFailure;
        }

        TargetAccreditation accredited = accreditTarget(
                current,
                plan.targetPublicationId(),
                plan.targetManifestSha256());
        DeclaredIds declared = declaredIds(plan);
        PlannerSnapshot snapshot = stateReader.snapshot(
                accredited.publication().id(),
                accredited.publication().id(),
                declared.documentIds(),
                declared.requirementIds(),
                Set.of());
        MappingCheck mapping = validateRetirementMapping(snapshot, plan);
        if (!mapping.valid()) {
            return blocked(LegalManifestIssueCode.REPLACEMENT_MAPPING_INVALID, MAPPING_LOCATION);
        }

        PlanAttempt post = retirementPlan(
                snapshot,
                validatedPlan,
                accredited.publication(),
                timestamp,
                Phase.POST_STATE);
        if (post.plan().isPresent()) {
            return LegalEditorialPlanResult.applicable(post.plan().orElseThrow());
        }

        LegalEditorialReadinessObservation sourceObservation = readinessCore.observeState(
                plan.expectedCurrentPublicationId(),
                timestamp);
        if (!sourceObservation.publicationUuid().equals(Optional.of(accredited.publication().id()))
                || !sourceObservation.editorialStateFingerprint()
                        .equals(plan.expectedEditorialStateFingerprint())) {
            return blocked(LegalManifestIssueCode.SOURCE_FINGERPRINT_MISMATCH, SOURCE_LOCATION);
        }
        PlanAttempt source = retirementPlan(
                snapshot,
                validatedPlan,
                accredited.publication(),
                timestamp,
                Phase.SOURCE_STATE);
        if (source.plan().isPresent()) {
            return LegalEditorialPlanResult.applicable(source.plan().orElseThrow());
        }
        return blocked(source.failureCode(), STATE_LOCATION);
    }

    boolean usesJdbc(JdbcTemplate candidate) {
        return jdbc == candidate
                && readinessCore.usesJdbc(candidate)
                && originGraphVerifier.usesJdbc(candidate)
                && stateReader.usesJdbc(candidate);
    }

    private TargetAccreditation accreditTarget(
            ValidatedRelease target,
            String expectedExternalId,
            String expectedManifestSha256) {
        LegalPublicationPlan release = target.plan();
        if (release.manifest().review().legal().status() != ReviewStatus.APPROVED
                || release.manifest().review().accounting().status() != ReviewStatus.APPROVED) {
            throw new LegalEditorialBlockedException(
                    LegalManifestIssueCode.REVISION_MISMATCH,
                    "manifest/review");
        }
        if (!release.manifest().publicationId().equals(expectedExternalId)
                || !release.manifestSha256().equals(expectedManifestSha256)) {
            throw new LegalEditorialBlockedException(
                    LegalManifestIssueCode.PUBLICATION_CONTENT_MISMATCH,
                    PUBLICATION_LOCATION);
        }
        PublicationEvidence publication = stateReader.publication(expectedExternalId)
                .orElseThrow(() -> new LegalEditorialBlockedException(
                        LegalManifestIssueCode.PUBLICATION_NOT_SEALED,
                        PUBLICATION_LOCATION));
        if (!publication.sealed()) {
            throw new LegalEditorialBlockedException(
                    LegalManifestIssueCode.PUBLICATION_NOT_SEALED,
                    PUBLICATION_LOCATION);
        }
        if (!publication.manifestSha256().equals(expectedManifestSha256)) {
            throw new LegalEditorialBlockedException(
                    LegalManifestIssueCode.PUBLICATION_CONTENT_MISMATCH,
                    PUBLICATION_LOCATION);
        }
        originGraphVerifier.verify(target, publication.id());
        return new TargetAccreditation(publication);
    }

    private static LegalEditorialPlanResult requirePlanBinding(
            ValidatedRelease release,
            LegalEditorialPlanV1 plan,
            LegalEditorialPlanV1.OperationType requiredType) {
        if (plan.operationType() != requiredType
                || !release.plan().manifest().publicationId()
                        .equals(plan.targetPublicationId())
                || !release.plan().manifestSha256().equals(plan.targetManifestSha256())) {
            return blocked(
                    LegalManifestIssueCode.REPLACEMENT_MAPPING_INVALID,
                    MAPPING_LOCATION);
        }
        if (requiredType == LegalEditorialPlanV1.OperationType.REPLACE
                && (plan.expectedReadinessAfter() != LegalEditorialReadiness.READY
                || plan.acknowledgeFailClosedGap())) {
            return blocked(
                    LegalManifestIssueCode.EXPECTED_READINESS_MISMATCH,
                    MAPPING_LOCATION);
        }
        if (requiredType == LegalEditorialPlanV1.OperationType.RETIRE
                && (plan.expectedReadinessAfter() != LegalEditorialReadiness.NOT_READY
                || !plan.acknowledgeFailClosedGap()
                || !plan.expectedCurrentPublicationId().equals(plan.targetPublicationId())
                || !plan.expectedCurrentManifestSha256().equals(plan.targetManifestSha256()))) {
            return blocked(
                    LegalManifestIssueCode.FAIL_CLOSED_GAP_NOT_ACKNOWLEDGED,
                    MAPPING_LOCATION);
        }
        return null;
    }

    private static boolean exactInitialPromotionSource(
            PlannerSnapshot snapshot,
            LegalEditorialReadinessObservation observation,
            TargetAccreditation accredited) {
        return observation.publicationUuid().equals(Optional.of(accredited.publication().id()))
                && observation.documentTransitions() == 0
                && observation.requirementTransitions() == 0
                && observation.documentSlots() == 0
                && observation.currentRequirementSets() == 0
                && observation.replacementLots() == 0
                && snapshot.activeSlots().isEmpty()
                && snapshot.activePointers().isEmpty()
                && snapshot.batches().isEmpty()
                && targetDocuments(snapshot).stream().allMatch(document ->
                        document.state() == EstadoVersionLegal.BORRADOR
                                && document.stateChangedAt() == null
                                && histories(snapshot.documentTransitions(), document.id()).isEmpty())
                && targetRequirements(snapshot).stream().allMatch(requirement ->
                        requirement.state() == EstadoVersionLegal.BORRADOR
                                && requirement.stateChangedAt() == null
                                && histories(snapshot.requirementTransitions(), requirement.id())
                                        .isEmpty());
    }

    private static boolean exactPromotedPostState(
            PlannerSnapshot snapshot,
            LegalEditorialReadinessObservation observation,
            TargetAccreditation accredited) {
        if (!observation.publicationUuid().equals(Optional.of(accredited.publication().id()))
                || observation.documentTransitions() != snapshot.targetDocumentIds().size() * 2
                || observation.requirementTransitions() != snapshot.targetRequirementIds().size() * 2
                || observation.replacementLots() != 0
                || !snapshot.batches().isEmpty()
                || !exactTargetProjections(snapshot, accredited.publication().id())) {
            return false;
        }
        for (DocumentEvidence document : targetDocuments(snapshot)) {
            if (!exactDocumentChain(
                    document,
                    histories(snapshot.documentTransitions(), document.id()),
                    EstadoVersionLegal.VIGENTE,
                    null,
                    null,
                    null)) {
                return false;
            }
        }
        for (RequirementEvidence requirement : targetRequirements(snapshot)) {
            if (!exactRequirementChain(
                    requirement,
                    histories(snapshot.requirementTransitions(), requirement.id()),
                    EstadoVersionLegal.VIGENTE,
                    null,
                    null)) {
                return false;
            }
        }
        return oneOperationTimestamp(snapshot, Set.copyOf(snapshot.targetDocumentIds()),
                Set.copyOf(snapshot.targetRequirementIds()), Set.of()).isPresent();
    }

    private static LegalEditorialExecutionPlan promotionPlan(
            PlannerSnapshot snapshot,
            PublicationEvidence target,
            Instant observedAt,
            boolean changeRequired) {
        Instant transitionAt = changeRequired
                ? observedAt
                : oneOperationTimestamp(
                        snapshot,
                        Set.copyOf(snapshot.targetDocumentIds()),
                        Set.copyOf(snapshot.targetRequirementIds()),
                        Set.of()).orElseThrow();
        List<LegalEditorialExecutionPlan.ExpectedDocumentState> documentStates =
                new ArrayList<>();
        List<LegalEditorialExecutionPlan.DocumentTransition> documentTransitions =
                new ArrayList<>();
        List<LegalEditorialExecutionPlan.ExpectedDocumentSlot> slots = new ArrayList<>();
        for (DocumentEvidence document : targetDocuments(snapshot)) {
            documentStates.add(new LegalEditorialExecutionPlan.ExpectedDocumentState(
                    document.id(),
                    EstadoVersionLegal.VIGENTE,
                    transitionAt,
                    null,
                    null));
            documentTransitions.add(documentTransition(
                    document.id(),
                    EstadoVersionLegal.BORRADOR,
                    EstadoVersionLegal.PUBLICADA,
                    null,
                    null,
                    transitionAt));
            documentTransitions.add(documentTransition(
                    document.id(),
                    EstadoVersionLegal.PUBLICADA,
                    EstadoVersionLegal.VIGENTE,
                    null,
                    null,
                    transitionAt));
            slots.addAll(expectedSlots(document, target.id()));
        }

        List<LegalEditorialExecutionPlan.ExpectedRequirementState> requirementStates =
                new ArrayList<>();
        List<LegalEditorialExecutionPlan.RequirementTransition> requirementTransitions =
                new ArrayList<>();
        for (RequirementEvidence requirement : targetRequirements(snapshot)) {
            requirementStates.add(new LegalEditorialExecutionPlan.ExpectedRequirementState(
                    requirement.id(),
                    EstadoVersionLegal.VIGENTE,
                    transitionAt,
                    null));
            requirementTransitions.add(requirementTransition(
                    requirement.id(),
                    EstadoVersionLegal.BORRADOR,
                    EstadoVersionLegal.PUBLICADA,
                    null,
                    transitionAt));
            requirementTransitions.add(requirementTransition(
                    requirement.id(),
                    EstadoVersionLegal.PUBLICADA,
                    EstadoVersionLegal.VIGENTE,
                    null,
                    transitionAt));
        }
        List<LegalEditorialExecutionPlan.ExpectedRequiredSetPointer> pointers =
                expectedPointers(snapshot.targetScopes(), transitionAt);
        LegalEditorialExecutionPlan.ExpectedPostState postState =
                new LegalEditorialExecutionPlan.ExpectedPostState(
                        documentStates,
                        requirementStates,
                        documentTransitions,
                        requirementTransitions,
                        List.of(),
                        List.of(),
                        slots,
                        pointers,
                        List.of(),
                        LegalEditorialExecutionPlan.V27TriggerEffects.empty());
        LegalEditorialExecutionPlan.MutationCommands commands = changeRequired
                ? new LegalEditorialExecutionPlan.MutationCommands(
                        documentTransitions,
                        requirementTransitions,
                        List.of(),
                        slots,
                        List.of(),
                        pointers,
                        List.of())
                : LegalEditorialExecutionPlan.MutationCommands.empty();
        return new LegalEditorialExecutionPlan(
                LegalEditorialExecutionPlan.OperationType.PROMOTE,
                Optional.empty(),
                identity(target),
                Optional.empty(),
                Optional.empty(),
                observedAt,
                transitionAt,
                LegalEditorialReadiness.READY,
                false,
                changeRequired,
                postState,
                commands);
    }

    private static MappingCheck validateReplacementMapping(
            PlannerSnapshot snapshot,
            LegalEditorialPlanV1 plan) {
        DeclaredIds ids = declaredIds(plan);
        if (!Set.copyOf(snapshot.targetDocumentIds()).equals(ids.targetDocumentIds())
                || !Set.copyOf(snapshot.targetRequirementIds())
                        .equals(ids.targetRequirementIds())) {
            return MappingCheck.INVALID;
        }
        for (DocumentScopedRef addition : plan.documentAdditions()) {
            if (!matchesTargetDocument(snapshot, addition)) {
                return MappingCheck.INVALID;
            }
        }
        for (DocumentScopedRef reuse : plan.documentReuses()) {
            if (!matchesTargetDocument(snapshot, reuse)) {
                return MappingCheck.INVALID;
            }
        }
        for (DocumentRetirement retirement : plan.documentRetirements()) {
            if (!matchesDocumentRetirement(snapshot, retirement)) {
                return MappingCheck.INVALID;
            }
        }
        for (DocumentReplacementBatch batch : plan.documentReplacementBatches()) {
            if (!matchesBatchMapping(snapshot, batch)) {
                return MappingCheck.INVALID;
            }
        }

        for (RequirementScopedRef addition : plan.requirementAdditions()) {
            if (!matchesTargetRequirement(snapshot, addition)) {
                return MappingCheck.INVALID;
            }
        }
        for (RequirementScopedRef reuse : plan.requirementReuses()) {
            if (!matchesTargetRequirement(snapshot, reuse)) {
                return MappingCheck.INVALID;
            }
        }
        for (RequirementReplacement replacement : plan.requirementReplacements()) {
            if (!matchesRequirementReplacement(snapshot, replacement)) {
                return MappingCheck.INVALID;
            }
        }
        for (RequirementRetirement retirement : plan.requirementRetirements()) {
            if (!matchesRequirementRetirement(snapshot, retirement)) {
                return MappingCheck.INVALID;
            }
        }
        return MappingCheck.VALID;
    }

    private static MappingCheck validateRetirementMapping(
            PlannerSnapshot snapshot,
            LegalEditorialPlanV1 plan) {
        for (DocumentRetirement retirement : plan.documentRetirements()) {
            DocumentEvidence document = snapshot.documents().get(retirement.documentVersionId());
            if (!snapshot.targetDocumentIds().contains(retirement.documentVersionId())
                    || document == null
                    || !document.sha256().equals(retirement.sha256())
                    || !Set.copyOf(document.contexts()).equals(Set.copyOf(retirement.contexts()))) {
                return MappingCheck.INVALID;
            }
        }
        for (RequirementRetirement retirement : plan.requirementRetirements()) {
            RequirementEvidence requirement = snapshot.requirements().get(
                    retirement.requirementVersionId());
            if (!snapshot.targetRequirementIds().contains(retirement.requirementVersionId())
                    || requirement == null
                    || !requirement.statementSha256().equals(retirement.statementSha256())
                    || requirement.context() != retirement.context()
                    || !Set.copyOf(requirement.audiences())
                            .equals(Set.copyOf(retirement.audiences()))) {
                return MappingCheck.INVALID;
            }
        }
        return MappingCheck.VALID;
    }

    private static PlanAttempt replacementPlan(
            PlannerSnapshot snapshot,
            ValidatedEditorialPlan validated,
            PublicationEvidence source,
            PublicationEvidence target,
            Instant observedAt,
            Phase phase) {
        LegalEditorialPlanV1 plan = validated.plan();
        if (!replacementStateMatches(snapshot, plan, source, target, phase)) {
            return PlanAttempt.failure(LegalManifestIssueCode.CURRENT_STATE_MISMATCH);
        }
        Instant operationAt = phase == Phase.SOURCE_STATE
                ? observedAt
                : inferReplacementTimestamp(snapshot, plan).orElse(null);
        if (operationAt == null) {
            return PlanAttempt.failure(LegalManifestIssueCode.CURRENT_STATE_MISMATCH);
        }

        List<LegalEditorialExecutionPlan.ExpectedDocumentState> documentStates =
                new ArrayList<>();
        List<LegalEditorialExecutionPlan.DocumentTransition> documentTransitions =
                new ArrayList<>();
        List<LegalEditorialExecutionPlan.DocumentTransition> derivedTransitions =
                new ArrayList<>();
        List<LegalEditorialExecutionPlan.ExpectedDocumentSlot> finalSlots = new ArrayList<>();
        List<LegalEditorialExecutionPlan.DocumentSlotDelete> directSlotDeletes =
                new ArrayList<>();
        List<LegalEditorialExecutionPlan.DocumentSlotDelete> derivedSlotDeletes =
                new ArrayList<>();
        List<LegalEditorialExecutionPlan.ExpectedDocumentSlot> derivedSlotInserts =
                new ArrayList<>();
        List<LegalEditorialExecutionPlan.ReplacementBatch> batches = new ArrayList<>();

        for (DocumentScopedRef addition : plan.documentAdditions()) {
            DocumentEvidence document = snapshot.documents().get(addition.documentVersionId());
            addDirectActivatedDocument(
                    document,
                    target.id(),
                    operationAt,
                    documentStates,
                    documentTransitions,
                    finalSlots);
        }
        for (DocumentScopedRef reuse : plan.documentReuses()) {
            DocumentEvidence document = snapshot.documents().get(reuse.documentVersionId());
            documentStates.add(expectedDocumentState(document));
            for (SlotEvidence active : slotsFor(snapshot, document.id())) {
                directSlotDeletes.add(new LegalEditorialExecutionPlan.DocumentSlotDelete(
                        active.key(),
                        document.id()));
            }
            finalSlots.addAll(expectedSlots(document, target.id()));
        }
        for (DocumentReplacementBatch declaredBatch : plan.documentReplacementBatches()) {
            LegalEditorialExecutionPlan.ReplacementBatch batch = replacementBatch(
                    snapshot,
                    declaredBatch,
                    target.id(),
                    operationAt,
                    phase);
            batches.add(batch);
            for (DocumentRef predecessorRef : declaredBatch.predecessors()) {
                DocumentEvidence predecessor = snapshot.documents().get(
                        predecessorRef.documentVersionId());
                documentStates.add(new LegalEditorialExecutionPlan.ExpectedDocumentState(
                        predecessor.id(),
                        EstadoVersionLegal.REEMPLAZADA,
                        operationAt,
                        null,
                        declaredBatch.replacementBatchId()));
                LegalEditorialExecutionPlan.DocumentTransition transition = documentTransition(
                        predecessor.id(),
                        EstadoVersionLegal.VIGENTE,
                        EstadoVersionLegal.REEMPLAZADA,
                        null,
                        declaredBatch.replacementBatchId(),
                        operationAt);
                documentTransitions.add(transition);
                derivedTransitions.add(transition);
                for (SlotEvidence active : slotsFor(snapshot, predecessor.id())) {
                    derivedSlotDeletes.add(new LegalEditorialExecutionPlan.DocumentSlotDelete(
                            active.key(),
                            predecessor.id()));
                }
            }
            for (DocumentRef successorRef : declaredBatch.successors()) {
                DocumentEvidence successor = snapshot.documents().get(
                        successorRef.documentVersionId());
                documentStates.add(new LegalEditorialExecutionPlan.ExpectedDocumentState(
                        successor.id(),
                        EstadoVersionLegal.VIGENTE,
                        operationAt,
                        null,
                        declaredBatch.replacementBatchId()));
                documentTransitions.add(documentTransition(
                        successor.id(),
                        EstadoVersionLegal.BORRADOR,
                        EstadoVersionLegal.PUBLICADA,
                        null,
                        null,
                        operationAt));
                LegalEditorialExecutionPlan.DocumentTransition derived = documentTransition(
                        successor.id(),
                        EstadoVersionLegal.PUBLICADA,
                        EstadoVersionLegal.VIGENTE,
                        null,
                        declaredBatch.replacementBatchId(),
                        operationAt);
                documentTransitions.add(derived);
                derivedTransitions.add(derived);
                List<LegalEditorialExecutionPlan.ExpectedDocumentSlot> successorSlots =
                        expectedSlots(successor, target.id());
                finalSlots.addAll(successorSlots);
                derivedSlotInserts.addAll(successorSlots);
            }
        }
        for (DocumentRetirement retirement : plan.documentRetirements()) {
            DocumentEvidence document = snapshot.documents().get(retirement.documentVersionId());
            documentStates.add(new LegalEditorialExecutionPlan.ExpectedDocumentState(
                    document.id(),
                    EstadoVersionLegal.RETIRADA,
                    operationAt,
                    retirement.reason(),
                    null));
            documentTransitions.add(documentTransition(
                    document.id(),
                    EstadoVersionLegal.VIGENTE,
                    EstadoVersionLegal.RETIRADA,
                    retirement.reason(),
                    null,
                    operationAt));
            for (SlotEvidence active : slotsFor(snapshot, document.id())) {
                directSlotDeletes.add(new LegalEditorialExecutionPlan.DocumentSlotDelete(
                        active.key(),
                        document.id()));
            }
        }

        List<LegalEditorialExecutionPlan.ExpectedRequirementState> requirementStates =
                new ArrayList<>();
        List<LegalEditorialExecutionPlan.RequirementTransition> requirementTransitions =
                new ArrayList<>();
        for (RequirementScopedRef addition : plan.requirementAdditions()) {
            addDirectActivatedRequirement(
                    snapshot.requirements().get(addition.requirementVersionId()),
                    operationAt,
                    requirementStates,
                    requirementTransitions);
        }
        for (RequirementScopedRef reuse : plan.requirementReuses()) {
            requirementStates.add(expectedRequirementState(
                    snapshot.requirements().get(reuse.requirementVersionId())));
        }
        for (RequirementReplacement replacement : plan.requirementReplacements()) {
            RequirementEvidence predecessor = snapshot.requirements().get(
                    replacement.predecessor().requirementVersionId());
            RequirementEvidence successor = snapshot.requirements().get(
                    replacement.successor().requirementVersionId());
            requirementStates.add(new LegalEditorialExecutionPlan.ExpectedRequirementState(
                    predecessor.id(),
                    EstadoVersionLegal.REEMPLAZADA,
                    operationAt,
                    null));
            requirementTransitions.add(requirementTransition(
                    predecessor.id(),
                    EstadoVersionLegal.VIGENTE,
                    EstadoVersionLegal.REEMPLAZADA,
                    null,
                    operationAt));
            addDirectActivatedRequirement(
                    successor,
                    operationAt,
                    requirementStates,
                    requirementTransitions);
        }
        for (RequirementRetirement retirement : plan.requirementRetirements()) {
            RequirementEvidence requirement = snapshot.requirements().get(
                    retirement.requirementVersionId());
            requirementStates.add(new LegalEditorialExecutionPlan.ExpectedRequirementState(
                    requirement.id(),
                    EstadoVersionLegal.RETIRADA,
                    operationAt,
                    retirement.reason()));
            requirementTransitions.add(requirementTransition(
                    requirement.id(),
                    EstadoVersionLegal.VIGENTE,
                    EstadoVersionLegal.RETIRADA,
                    retirement.reason(),
                    operationAt));
        }

        List<LegalEditorialExecutionPlan.ExpectedRequiredSetPointer> finalPointers =
                expectedPointers(snapshot.targetScopes(), operationAt);
        List<LegalEditorialExecutionPlan.RequiredSetPointerDelete> pointerDeletes =
                snapshot.activePointers().stream()
                        .map(pointer -> new LegalEditorialExecutionPlan.RequiredSetPointerDelete(
                                pointer.key(),
                                pointer.requiredSetId()))
                        .toList();
        Optional<PreexistingTransitionHistory> preexistingHistory =
                preexistingTransitionHistory(
                        snapshot,
                        documentStates,
                        requirementStates,
                        documentTransitions,
                        requirementTransitions,
                        phase);
        if (preexistingHistory.isEmpty()) {
            return PlanAttempt.failure(LegalManifestIssueCode.CURRENT_STATE_MISMATCH);
        }
        Optional<List<LegalEditorialExecutionPlan.ReplacementBatch>> preexistingBatches =
                preexistingReplacementBatches(
                        snapshot,
                        batches,
                        preexistingHistory.orElseThrow(),
                        phase);
        if (preexistingBatches.isEmpty()) {
            return PlanAttempt.failure(LegalManifestIssueCode.CURRENT_STATE_MISMATCH);
        }
        LegalEditorialExecutionPlan.ExpectedPostState postState =
                new LegalEditorialExecutionPlan.ExpectedPostState(
                        documentStates,
                        requirementStates,
                        documentTransitions,
                        requirementTransitions,
                        preexistingHistory.orElseThrow().documentTransitions(),
                        preexistingHistory.orElseThrow().requirementTransitions(),
                        finalSlots,
                        finalPointers,
                        preexistingBatches.orElseThrow(),
                        batches,
                        new LegalEditorialExecutionPlan.V27TriggerEffects(
                                derivedTransitions,
                                derivedSlotDeletes,
                                derivedSlotInserts));
        boolean changeRequired = phase == Phase.SOURCE_STATE;
        Set<LegalEditorialExecutionPlan.ExpectedDocumentSlot> derivedSlotSet =
                Set.copyOf(derivedSlotInserts);
        List<LegalEditorialExecutionPlan.ExpectedDocumentSlot> directSlotInserts =
                finalSlots.stream().filter(slot -> !derivedSlotSet.contains(slot)).toList();
        Set<LegalEditorialExecutionPlan.DocumentTransition> derivedTransitionSet =
                Set.copyOf(derivedTransitions);
        List<LegalEditorialExecutionPlan.DocumentTransition> directDocumentTransitions =
                documentTransitions.stream()
                        .filter(transition -> !derivedTransitionSet.contains(transition))
                        .toList();
        LegalEditorialExecutionPlan.MutationCommands commands = changeRequired
                ? new LegalEditorialExecutionPlan.MutationCommands(
                        directDocumentTransitions,
                        requirementTransitions,
                        directSlotDeletes,
                        directSlotInserts,
                        pointerDeletes,
                        finalPointers,
                        batches)
                : LegalEditorialExecutionPlan.MutationCommands.empty();
        return PlanAttempt.success(new LegalEditorialExecutionPlan(
                LegalEditorialExecutionPlan.OperationType.REPLACE,
                Optional.of(new LegalEditorialExecutionPlan.SourceIdentity(
                        identity(source),
                        plan.expectedEditorialStateFingerprint())),
                identity(target),
                Optional.of(validated.operationId()),
                Optional.of(validated.editorialPlanSha256()),
                observedAt,
                operationAt,
                LegalEditorialReadiness.READY,
                false,
                changeRequired,
                postState,
                commands));
    }

    private static PlanAttempt retirementPlan(
            PlannerSnapshot snapshot,
            ValidatedEditorialPlan validated,
            PublicationEvidence current,
            Instant observedAt,
            Phase phase) {
        LegalEditorialPlanV1 plan = validated.plan();
        if (!retirementStateMatches(snapshot, plan, phase)) {
            return PlanAttempt.failure(LegalManifestIssueCode.CURRENT_STATE_MISMATCH);
        }
        Instant operationAt = phase == Phase.SOURCE_STATE
                ? observedAt
                : inferRetirementTimestamp(snapshot, plan).orElse(null);
        if (operationAt == null) {
            return PlanAttempt.failure(LegalManifestIssueCode.CURRENT_STATE_MISMATCH);
        }

        List<LegalEditorialExecutionPlan.ExpectedDocumentState> documentStates =
                new ArrayList<>();
        List<LegalEditorialExecutionPlan.DocumentTransition> documentTransitions =
                new ArrayList<>();
        List<LegalEditorialExecutionPlan.DocumentSlotDelete> slotDeletes = new ArrayList<>();
        for (DocumentRetirement retirement : plan.documentRetirements()) {
            DocumentEvidence document = snapshot.documents().get(retirement.documentVersionId());
            documentStates.add(new LegalEditorialExecutionPlan.ExpectedDocumentState(
                    document.id(),
                    EstadoVersionLegal.RETIRADA,
                    operationAt,
                    retirement.reason(),
                    null));
            documentTransitions.add(documentTransition(
                    document.id(),
                    EstadoVersionLegal.VIGENTE,
                    EstadoVersionLegal.RETIRADA,
                    retirement.reason(),
                    null,
                    operationAt));
            if (phase == Phase.SOURCE_STATE) {
                for (SlotEvidence slot : slotsFor(snapshot, document.id())) {
                    slotDeletes.add(new LegalEditorialExecutionPlan.DocumentSlotDelete(
                            slot.key(),
                            document.id()));
                }
            } else {
                for (ContextoLegal context : document.contexts()) {
                    slotDeletes.add(new LegalEditorialExecutionPlan.DocumentSlotDelete(
                            new LegalEditorialExecutionPlan.DocumentSlotKey(
                                    document.type(),
                                    document.locale(),
                                    context),
                            document.id()));
                }
            }
        }

        List<LegalEditorialExecutionPlan.ExpectedRequirementState> requirementStates =
                new ArrayList<>();
        List<LegalEditorialExecutionPlan.RequirementTransition> requirementTransitions =
                new ArrayList<>();
        for (RequirementRetirement retirement : plan.requirementRetirements()) {
            RequirementEvidence requirement = snapshot.requirements().get(
                    retirement.requirementVersionId());
            requirementStates.add(new LegalEditorialExecutionPlan.ExpectedRequirementState(
                    requirement.id(),
                    EstadoVersionLegal.RETIRADA,
                    operationAt,
                    retirement.reason()));
            requirementTransitions.add(requirementTransition(
                    requirement.id(),
                    EstadoVersionLegal.VIGENTE,
                    EstadoVersionLegal.RETIRADA,
                    retirement.reason(),
                    operationAt));
        }
        Set<UUID> retiredDocumentIds = plan.documentRetirements().stream()
                .map(DocumentRetirement::documentVersionId)
                .collect(Collectors.toUnmodifiableSet());
        Set<UUID> retiredRequirementIds = plan.requirementRetirements().stream()
                .map(RequirementRetirement::requirementVersionId)
                .collect(Collectors.toUnmodifiableSet());
        List<LegalEditorialExecutionPlan.RequiredSetPointerDelete> pointerDeletes =
                phase == Phase.SOURCE_STATE
                        ? snapshot.activePointers().stream()
                                .filter(pointer -> pointerReferencesAny(
                                        pointer,
                                        retiredDocumentIds,
                                        retiredRequirementIds))
                                .map(pointer ->
                                        new LegalEditorialExecutionPlan.RequiredSetPointerDelete(
                                                pointer.key(),
                                                pointer.requiredSetId()))
                                .toList()
                        : List.of();
        Optional<PreexistingTransitionHistory> preexistingHistory =
                preexistingTransitionHistory(
                        snapshot,
                        documentStates,
                        requirementStates,
                        documentTransitions,
                        requirementTransitions,
                        phase);
        if (preexistingHistory.isEmpty()) {
            return PlanAttempt.failure(LegalManifestIssueCode.CURRENT_STATE_MISMATCH);
        }
        LegalEditorialExecutionPlan.ExpectedPostState postState =
                new LegalEditorialExecutionPlan.ExpectedPostState(
                        documentStates,
                        requirementStates,
                        documentTransitions,
                        requirementTransitions,
                        preexistingHistory.orElseThrow().documentTransitions(),
                        preexistingHistory.orElseThrow().requirementTransitions(),
                        List.of(),
                        List.of(),
                        List.of(),
                        LegalEditorialExecutionPlan.V27TriggerEffects.empty());
        boolean changeRequired = phase == Phase.SOURCE_STATE;
        LegalEditorialExecutionPlan.MutationCommands commands = changeRequired
                ? new LegalEditorialExecutionPlan.MutationCommands(
                        documentTransitions,
                        requirementTransitions,
                        slotDeletes,
                        List.of(),
                        pointerDeletes,
                        List.of(),
                        List.of())
                : LegalEditorialExecutionPlan.MutationCommands.empty();
        return PlanAttempt.success(new LegalEditorialExecutionPlan(
                LegalEditorialExecutionPlan.OperationType.RETIRE,
                Optional.of(new LegalEditorialExecutionPlan.SourceIdentity(
                        identity(current),
                        plan.expectedEditorialStateFingerprint())),
                identity(current),
                Optional.of(validated.operationId()),
                Optional.of(validated.editorialPlanSha256()),
                observedAt,
                operationAt,
                LegalEditorialReadiness.NOT_READY,
                true,
                changeRequired,
                postState,
                commands));
    }

    private static boolean replacementStateMatches(
            PlannerSnapshot snapshot,
            LegalEditorialPlanV1 plan,
            PublicationEvidence source,
            PublicationEvidence target,
            Phase phase) {
        DeclaredIds ids = declaredIds(plan);
        Set<UUID> expectedActiveDocuments = phase == Phase.SOURCE_STATE
                ? ids.sourceDocumentIds()
                : ids.targetDocumentIds();
        Set<UUID> expectedActiveRequirements = phase == Phase.SOURCE_STATE
                ? ids.sourceRequirementIds()
                : ids.targetRequirementIds();
        if (phase == Phase.SOURCE_STATE) {
            if (!sourceMembershipHasOnlyOperationalStates(snapshot)
                    || !currentSourceDocumentIds(snapshot).equals(expectedActiveDocuments)
                    || !currentSourceRequirementIds(snapshot).equals(expectedActiveRequirements)
                    || !activeDocumentContexts(snapshot).keySet()
                            .equals(expectedActiveDocuments)
                    || !expectedActiveRequirements.containsAll(
                            activeRequirementScopes(snapshot).keySet())
                    || !exactReplacementSourceProjections(
                            snapshot,
                            plan,
                            source.id())) {
                return false;
            }
        } else if (!activeDocumentContexts(snapshot).keySet().equals(expectedActiveDocuments)
                || !activeRequirementScopes(snapshot).keySet().equals(expectedActiveRequirements)
                || !exactTargetProjections(snapshot, target.id())) {
            return false;
        }

        for (DocumentScopedRef addition : plan.documentAdditions()) {
            if (!matchesDocumentPhase(snapshot, addition.documentVersionId(), null, phase)) {
                return false;
            }
        }
        for (DocumentScopedRef reuse : plan.documentReuses()) {
            if (!matchesReusableDocument(snapshot, reuse.documentVersionId())) {
                return false;
            }
        }
        for (DocumentReplacementBatch batch : plan.documentReplacementBatches()) {
            if (!matchesReplacementBatchState(snapshot, batch, target.id(), phase)) {
                return false;
            }
        }
        for (DocumentRetirement retirement : plan.documentRetirements()) {
            if (!matchesTerminalDocument(
                    snapshot,
                    retirement.documentVersionId(),
                    retirement.reason(),
                    phase)) {
                return false;
            }
        }
        for (RequirementScopedRef addition : plan.requirementAdditions()) {
            if (!matchesRequirementPhase(snapshot, addition.requirementVersionId(), phase)) {
                return false;
            }
        }
        for (RequirementScopedRef reuse : plan.requirementReuses()) {
            if (!matchesReusableRequirement(snapshot, reuse.requirementVersionId())) {
                return false;
            }
        }
        for (RequirementReplacement replacement : plan.requirementReplacements()) {
            if (!matchesRequirementReplacementState(snapshot, replacement, phase)) {
                return false;
            }
        }
        for (RequirementRetirement retirement : plan.requirementRetirements()) {
            if (!matchesTerminalRequirement(
                    snapshot,
                    retirement.requirementVersionId(),
                    retirement.reason(),
                    phase)) {
                return false;
            }
        }
        return phase != Phase.SOURCE_STATE || plan.documentReplacementBatches().stream()
                .noneMatch(batch -> snapshot.batches().containsKey(batch.replacementBatchId()));
    }

    private static boolean retirementStateMatches(
            PlannerSnapshot snapshot,
            LegalEditorialPlanV1 plan,
            Phase phase) {
        if (phase == Phase.SOURCE_STATE && !exactRetirementSourceProjections(snapshot, plan)) {
            return false;
        }
        for (DocumentRetirement retirement : plan.documentRetirements()) {
            DocumentEvidence document = snapshot.documents().get(retirement.documentVersionId());
            if (document == null) {
                return false;
            }
            if (phase == Phase.SOURCE_STATE) {
                if (!matchesReusableDocument(snapshot, document.id())
                        || !activeDocumentContexts(snapshot).getOrDefault(
                                document.id(), Set.of()).equals(Set.copyOf(retirement.contexts()))) {
                    return false;
                }
            } else if (!matchesTerminalDocument(
                    snapshot,
                    document.id(),
                    retirement.reason(),
                    Phase.POST_STATE)
                    || snapshot.activeSlots().stream().anyMatch(slot ->
                            slot.documentVersionId().equals(document.id()))
                    || snapshot.activePointers().stream().anyMatch(pointer ->
                            pointer.referencedDocumentVersionIds().contains(document.id()))) {
                return false;
            }
        }
        for (RequirementRetirement retirement : plan.requirementRetirements()) {
            RequirementEvidence requirement = snapshot.requirements().get(
                    retirement.requirementVersionId());
            if (requirement == null) {
                return false;
            }
            Set<RequirementScope> declaredScopes = retirement.audiences().stream()
                    .map(audience -> new RequirementScope(retirement.context(), audience))
                    .collect(Collectors.toUnmodifiableSet());
            if (phase == Phase.SOURCE_STATE) {
                if (!matchesReusableRequirement(snapshot, requirement.id())
                        || !activeRequirementScopes(snapshot).getOrDefault(
                                requirement.id(), Set.of()).equals(declaredScopes)) {
                    return false;
                }
            } else if (!matchesTerminalRequirement(
                    snapshot,
                    requirement.id(),
                    retirement.reason(),
                    Phase.POST_STATE)
                    || snapshot.activePointers().stream().anyMatch(pointer ->
                            pointer.memberVersionIds().contains(requirement.id()))) {
                return false;
            }
        }
        if (phase == Phase.POST_STATE) {
            Optional<Instant> operationAt = inferRetirementTimestamp(snapshot, plan);
            if (operationAt.isEmpty()) {
                return false;
            }
            Set<UUID> declaredDocuments = plan.documentRetirements().stream()
                    .map(DocumentRetirement::documentVersionId)
                    .collect(Collectors.toUnmodifiableSet());
            Set<UUID> declaredRequirements = plan.requirementRetirements().stream()
                    .map(RequirementRetirement::requirementVersionId)
                    .collect(Collectors.toUnmodifiableSet());
            boolean extraDocument = snapshot.documentTransitions().stream()
                    .anyMatch(transition -> transition.occurredAt().equals(operationAt.orElseThrow())
                            && transition.newState() == EstadoVersionLegal.RETIRADA
                            && !declaredDocuments.contains(transition.versionId()));
            boolean extraRequirement = snapshot.requirementTransitions().stream()
                    .anyMatch(transition -> transition.occurredAt().equals(operationAt.orElseThrow())
                            && transition.newState() == EstadoVersionLegal.RETIRADA
                            && !declaredRequirements.contains(transition.versionId()));
            return !extraDocument && !extraRequirement;
        }
        return true;
    }

    private static boolean matchesDocumentPhase(
            PlannerSnapshot snapshot,
            UUID versionId,
            UUID replacementBatchId,
            Phase phase) {
        DocumentEvidence document = snapshot.documents().get(versionId);
        if (document == null) {
            return false;
        }
        List<TransitionEvidence> transitions = histories(
                snapshot.documentTransitions(),
                versionId);
        if (phase == Phase.SOURCE_STATE) {
            return document.state() == EstadoVersionLegal.BORRADOR
                    && document.stateChangedAt() == null
                    && transitions.isEmpty();
        }
        return exactDocumentChain(
                document,
                transitions,
                EstadoVersionLegal.VIGENTE,
                null,
                replacementBatchId,
                replacementBatchId);
    }

    private static boolean matchesReusableDocument(
            PlannerSnapshot snapshot,
            UUID versionId) {
        DocumentEvidence document = snapshot.documents().get(versionId);
        return document != null && exactDocumentChain(
                document,
                histories(snapshot.documentTransitions(), versionId),
                EstadoVersionLegal.VIGENTE,
                null,
                document.replacementBatchId(),
                document.replacementBatchId());
    }

    private static boolean matchesTerminalDocument(
            PlannerSnapshot snapshot,
            UUID versionId,
            String reason,
            Phase phase) {
        if (phase == Phase.SOURCE_STATE) {
            return matchesReusableDocument(snapshot, versionId);
        }
        DocumentEvidence document = snapshot.documents().get(versionId);
        List<TransitionEvidence> history = document == null
                ? List.of()
                : histories(snapshot.documentTransitions(), versionId);
        UUID activationBatch = history.size() > 1
                ? history.get(1).replacementBatchId()
                : null;
        return document != null && exactDocumentChain(
                document,
                history,
                EstadoVersionLegal.RETIRADA,
                reason,
                null,
                activationBatch);
    }

    private static boolean matchesReplacementBatchState(
            PlannerSnapshot snapshot,
            DocumentReplacementBatch batch,
            UUID targetPublicationId,
            Phase phase) {
        if (phase == Phase.SOURCE_STATE) {
            for (DocumentRef predecessor : batch.predecessors()) {
                if (!matchesReusableDocument(snapshot, predecessor.documentVersionId())) {
                    return false;
                }
            }
            for (DocumentRef successor : batch.successors()) {
                if (!matchesDocumentPhase(
                        snapshot,
                        successor.documentVersionId(),
                        batch.replacementBatchId(),
                        Phase.SOURCE_STATE)) {
                    return false;
                }
            }
            return !snapshot.batches().containsKey(batch.replacementBatchId());
        }
        BatchEvidence actual = snapshot.batches().get(batch.replacementBatchId());
        if (actual == null
                || !actual.sealed()
                || !Set.copyOf(actual.predecessorIds()).equals(batch.predecessors().stream()
                        .map(DocumentRef::documentVersionId)
                        .collect(Collectors.toUnmodifiableSet()))
                || !Set.copyOf(actual.successors()).equals(batch.successors().stream()
                        .map(successor -> new LegalEditorialExecutionPlan.ReplacementSuccessor(
                                successor.documentVersionId(),
                                targetPublicationId))
                        .collect(Collectors.toUnmodifiableSet()))) {
            return false;
        }
        for (DocumentRef predecessor : batch.predecessors()) {
            DocumentEvidence document = snapshot.documents().get(predecessor.documentVersionId());
            List<TransitionEvidence> history = document == null
                    ? List.of()
                    : histories(snapshot.documentTransitions(), document.id());
            UUID activationBatch = history.size() > 1
                    ? history.get(1).replacementBatchId()
                    : null;
            if (document == null || !exactDocumentChain(
                    document,
                    history,
                    EstadoVersionLegal.REEMPLAZADA,
                    null,
                    batch.replacementBatchId(),
                    activationBatch)) {
                return false;
            }
        }
        for (DocumentRef successor : batch.successors()) {
            if (!matchesDocumentPhase(
                    snapshot,
                    successor.documentVersionId(),
                    batch.replacementBatchId(),
                    Phase.POST_STATE)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesRequirementPhase(
            PlannerSnapshot snapshot,
            UUID versionId,
            Phase phase) {
        RequirementEvidence requirement = snapshot.requirements().get(versionId);
        if (requirement == null) {
            return false;
        }
        List<TransitionEvidence> transitions = histories(
                snapshot.requirementTransitions(),
                versionId);
        if (phase == Phase.SOURCE_STATE) {
            return requirement.state() == EstadoVersionLegal.BORRADOR
                    && requirement.stateChangedAt() == null
                    && transitions.isEmpty();
        }
        return exactRequirementChain(
                requirement,
                transitions,
                EstadoVersionLegal.VIGENTE,
                null,
                null);
    }

    private static boolean matchesReusableRequirement(
            PlannerSnapshot snapshot,
            UUID versionId) {
        RequirementEvidence requirement = snapshot.requirements().get(versionId);
        return requirement != null && exactRequirementChain(
                requirement,
                histories(snapshot.requirementTransitions(), versionId),
                EstadoVersionLegal.VIGENTE,
                null,
                null);
    }

    private static boolean matchesTerminalRequirement(
            PlannerSnapshot snapshot,
            UUID versionId,
            String reason,
            Phase phase) {
        if (phase == Phase.SOURCE_STATE) {
            return matchesReusableRequirement(snapshot, versionId);
        }
        RequirementEvidence requirement = snapshot.requirements().get(versionId);
        return requirement != null && exactRequirementChain(
                requirement,
                histories(snapshot.requirementTransitions(), versionId),
                EstadoVersionLegal.RETIRADA,
                reason,
                null);
    }

    private static boolean matchesRequirementReplacementState(
            PlannerSnapshot snapshot,
            RequirementReplacement replacement,
            Phase phase) {
        if (phase == Phase.SOURCE_STATE) {
            return matchesReusableRequirement(
                    snapshot,
                    replacement.predecessor().requirementVersionId())
                    && matchesRequirementPhase(
                            snapshot,
                            replacement.successor().requirementVersionId(),
                            phase);
        }
        RequirementEvidence predecessor = snapshot.requirements().get(
                replacement.predecessor().requirementVersionId());
        return predecessor != null
                && exactRequirementChain(
                        predecessor,
                        histories(snapshot.requirementTransitions(), predecessor.id()),
                        EstadoVersionLegal.REEMPLAZADA,
                        null,
                        null)
                && matchesRequirementPhase(
                        snapshot,
                        replacement.successor().requirementVersionId(),
                        phase);
    }

    private static boolean exactDocumentChain(
            DocumentEvidence document,
            List<TransitionEvidence> transitions,
            EstadoVersionLegal expectedState,
            String expectedReason,
            UUID expectedStateBatch,
            UUID expectedActivationBatch) {
        int expectedSize = expectedState == EstadoVersionLegal.VIGENTE ? 2 : 3;
        if (document.state() != expectedState
                || !Objects.equals(document.lastReason(), expectedReason)
                || !Objects.equals(document.replacementBatchId(), expectedStateBatch)
                || document.stateChangedAt() == null
                || transitions.size() != expectedSize) {
            return false;
        }
        if (!edge(transitions.get(0), EstadoVersionLegal.BORRADOR,
                EstadoVersionLegal.PUBLICADA, null, null)
                || !edge(transitions.get(1), EstadoVersionLegal.PUBLICADA,
                        EstadoVersionLegal.VIGENTE, null, expectedActivationBatch)) {
            return false;
        }
        if (expectedSize == 3) {
            EstadoVersionLegal terminal = transitions.get(2).newState();
            if (terminal != expectedState
                    || transitions.get(2).previousState() != EstadoVersionLegal.VIGENTE
                    || !Objects.equals(transitions.get(2).reason(), expectedReason)
                    || !Objects.equals(transitions.get(2).replacementBatchId(), expectedStateBatch)) {
                return false;
            }
        }
        return document.stateChangedAt().equals(transitions.getLast().occurredAt());
    }

    private static boolean exactRequirementChain(
            RequirementEvidence requirement,
            List<TransitionEvidence> transitions,
            EstadoVersionLegal expectedState,
            String expectedReason,
            UUID ignoredBatch) {
        int expectedSize = expectedState == EstadoVersionLegal.VIGENTE ? 2 : 3;
        if (requirement.state() != expectedState
                || !Objects.equals(requirement.lastReason(), expectedReason)
                || requirement.stateChangedAt() == null
                || transitions.size() != expectedSize
                || !edge(transitions.get(0), EstadoVersionLegal.BORRADOR,
                        EstadoVersionLegal.PUBLICADA, null, null)
                || !edge(transitions.get(1), EstadoVersionLegal.PUBLICADA,
                        EstadoVersionLegal.VIGENTE, null, null)) {
            return false;
        }
        if (expectedSize == 3
                && (transitions.get(2).previousState() != EstadoVersionLegal.VIGENTE
                || transitions.get(2).newState() != expectedState
                || !Objects.equals(transitions.get(2).reason(), expectedReason)
                || transitions.get(2).replacementBatchId() != null)) {
            return false;
        }
        return requirement.stateChangedAt().equals(transitions.getLast().occurredAt());
    }

    private static boolean edge(
            TransitionEvidence transition,
            EstadoVersionLegal previous,
            EstadoVersionLegal next,
            String reason,
            UUID batchId) {
        return transition.previousState() == previous
                && transition.newState() == next
                && Objects.equals(transition.reason(), reason)
                && Objects.equals(transition.replacementBatchId(), batchId);
    }

    private static Optional<Instant> inferReplacementTimestamp(
            PlannerSnapshot snapshot,
            LegalEditorialPlanV1 plan) {
        List<Instant> evidence = new ArrayList<>();
        plan.documentAdditions().forEach(addition -> addAllTransitionTimes(
                evidence,
                histories(snapshot.documentTransitions(), addition.documentVersionId())));
        plan.documentReplacementBatches().forEach(batch -> {
            batch.predecessors().forEach(predecessor -> addLastTransitionTime(
                    evidence,
                    histories(snapshot.documentTransitions(), predecessor.documentVersionId())));
            batch.successors().forEach(successor -> addAllTransitionTimes(
                    evidence,
                    histories(snapshot.documentTransitions(), successor.documentVersionId())));
        });
        plan.documentRetirements().forEach(retirement -> addLastTransitionTime(
                evidence,
                histories(snapshot.documentTransitions(), retirement.documentVersionId())));
        plan.requirementAdditions().forEach(addition -> addAllTransitionTimes(
                evidence,
                histories(snapshot.requirementTransitions(), addition.requirementVersionId())));
        plan.requirementReplacements().forEach(replacement -> {
            addLastTransitionTime(
                    evidence,
                    histories(
                            snapshot.requirementTransitions(),
                            replacement.predecessor().requirementVersionId()));
            addAllTransitionTimes(
                    evidence,
                    histories(
                            snapshot.requirementTransitions(),
                            replacement.successor().requirementVersionId()));
        });
        plan.requirementRetirements().forEach(retirement -> addLastTransitionTime(
                evidence,
                histories(snapshot.requirementTransitions(), retirement.requirementVersionId())));
        for (UUID batchId : declaredIds(plan).batchIds()) {
            BatchEvidence batch = snapshot.batches().get(batchId);
            if (batch != null && batch.createdAt() != null && batch.sealedAt() != null) {
                evidence.add(batch.createdAt());
                evidence.add(batch.sealedAt());
            }
        }
        evidence.addAll(snapshot.activePointers().stream()
                .map(PointerEvidence::updatedAt)
                .toList());
        return singleTimestamp(evidence);
    }

    private static void addAllTransitionTimes(
            List<Instant> evidence,
            List<TransitionEvidence> transitions) {
        transitions.forEach(transition -> evidence.add(transition.occurredAt()));
    }

    private static void addLastTransitionTime(
            List<Instant> evidence,
            List<TransitionEvidence> transitions) {
        if (!transitions.isEmpty()) {
            evidence.add(transitions.getLast().occurredAt());
        }
    }

    private static Optional<Instant> inferRetirementTimestamp(
            PlannerSnapshot snapshot,
            LegalEditorialPlanV1 plan) {
        List<Instant> evidence = new ArrayList<>();
        for (DocumentRetirement retirement : plan.documentRetirements()) {
            List<TransitionEvidence> transitions = histories(
                    snapshot.documentTransitions(), retirement.documentVersionId());
            if (!transitions.isEmpty()) {
                evidence.add(transitions.getLast().occurredAt());
            }
        }
        for (RequirementRetirement retirement : plan.requirementRetirements()) {
            List<TransitionEvidence> transitions = histories(
                    snapshot.requirementTransitions(), retirement.requirementVersionId());
            if (!transitions.isEmpty()) {
                evidence.add(transitions.getLast().occurredAt());
            }
        }
        return singleTimestamp(evidence);
    }

    private static Optional<Instant> oneOperationTimestamp(
            PlannerSnapshot snapshot,
            Set<UUID> documentIds,
            Set<UUID> requirementIds,
            Set<UUID> batchIds) {
        List<Instant> evidence = new ArrayList<>();
        documentIds.forEach(id -> histories(snapshot.documentTransitions(), id)
                .forEach(transition -> evidence.add(transition.occurredAt())));
        requirementIds.forEach(id -> histories(snapshot.requirementTransitions(), id)
                .forEach(transition -> evidence.add(transition.occurredAt())));
        batchIds.forEach(id -> {
            BatchEvidence batch = snapshot.batches().get(id);
            if (batch != null && batch.sealedAt() != null) {
                evidence.add(batch.sealedAt());
            }
        });
        evidence.addAll(snapshot.activePointers().stream()
                .map(PointerEvidence::updatedAt)
                .toList());
        return singleTimestamp(evidence);
    }

    private static Optional<Instant> singleTimestamp(Collection<Instant> values) {
        Set<Instant> unique = new HashSet<>(values);
        return unique.size() == 1 ? Optional.of(unique.iterator().next()) : Optional.empty();
    }

    private static boolean exactTargetProjections(
            PlannerSnapshot snapshot,
            UUID targetPublicationId) {
        Map<LegalEditorialExecutionPlan.DocumentSlotKey,
                LegalEditorialExecutionPlan.ExpectedDocumentSlot> expectedSlots = new HashMap<>();
        for (DocumentEvidence document : targetDocuments(snapshot)) {
            for (LegalEditorialExecutionPlan.ExpectedDocumentSlot slot :
                    expectedSlots(document, targetPublicationId)) {
                if (expectedSlots.put(slot.key(), slot) != null) {
                    return false;
                }
            }
        }
        Map<LegalEditorialExecutionPlan.DocumentSlotKey, SlotEvidence> actualSlots =
                snapshot.activeSlots().stream().collect(Collectors.toMap(
                        SlotEvidence::key,
                        Function.identity(),
                        (left, right) -> left));
        if (!actualSlots.keySet().equals(expectedSlots.keySet())) {
            return false;
        }
        for (Map.Entry<LegalEditorialExecutionPlan.DocumentSlotKey,
                LegalEditorialExecutionPlan.ExpectedDocumentSlot> entry : expectedSlots.entrySet()) {
            SlotEvidence actual = actualSlots.get(entry.getKey());
            LegalEditorialExecutionPlan.ExpectedDocumentSlot expected = entry.getValue();
            if (!actual.documentVersionId().equals(expected.documentVersionId())
                    || !actual.documentLineId().equals(expected.documentLineId())
                    || !actual.publicationId().equals(expected.publicationId())) {
                return false;
            }
        }
        Map<LegalEditorialExecutionPlan.RequiredSetPointerKey, TargetScopeEvidence> scopes =
                snapshot.targetScopes().stream().collect(Collectors.toMap(
                        TargetScopeEvidence::key,
                        Function.identity()));
        Map<LegalEditorialExecutionPlan.RequiredSetPointerKey, PointerEvidence> pointers =
                snapshot.activePointers().stream().collect(Collectors.toMap(
                        PointerEvidence::key,
                        Function.identity()));
        if (!scopes.keySet().equals(pointers.keySet())) {
            return false;
        }
        for (Map.Entry<LegalEditorialExecutionPlan.RequiredSetPointerKey,
                TargetScopeEvidence> entry : scopes.entrySet()) {
            PointerEvidence pointer = pointers.get(entry.getKey());
            TargetScopeEvidence scope = entry.getValue();
            if (!pointer.requiredSetId().equals(scope.requiredSetId())
                    || !pointer.publicationId().equals(targetPublicationId)
                    || !pointer.revision().equals(scope.revision())) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsFutureEffectiveDocument(
            PlannerSnapshot snapshot,
            Instant observedAt) {
        return containsFutureEffectiveDocument(
                snapshot,
                Set.copyOf(snapshot.targetDocumentIds()),
                observedAt);
    }

    private static boolean containsFutureEffectiveDocument(
            PlannerSnapshot snapshot,
            Set<UUID> targetIds,
            Instant observedAt) {
        return targetIds.stream().map(snapshot.documents()::get)
                .filter(Objects::nonNull)
                .anyMatch(document -> document.effectiveAt().isAfter(observedAt));
    }

    private static void addDirectActivatedDocument(
            DocumentEvidence document,
            UUID targetPublicationId,
            Instant operationAt,
            List<LegalEditorialExecutionPlan.ExpectedDocumentState> states,
            List<LegalEditorialExecutionPlan.DocumentTransition> transitions,
            List<LegalEditorialExecutionPlan.ExpectedDocumentSlot> slots) {
        states.add(new LegalEditorialExecutionPlan.ExpectedDocumentState(
                document.id(), EstadoVersionLegal.VIGENTE, operationAt, null, null));
        transitions.add(documentTransition(
                document.id(), EstadoVersionLegal.BORRADOR,
                EstadoVersionLegal.PUBLICADA, null, null, operationAt));
        transitions.add(documentTransition(
                document.id(), EstadoVersionLegal.PUBLICADA,
                EstadoVersionLegal.VIGENTE, null, null, operationAt));
        slots.addAll(expectedSlots(document, targetPublicationId));
    }

    private static void addDirectActivatedRequirement(
            RequirementEvidence requirement,
            Instant operationAt,
            List<LegalEditorialExecutionPlan.ExpectedRequirementState> states,
            List<LegalEditorialExecutionPlan.RequirementTransition> transitions) {
        states.add(new LegalEditorialExecutionPlan.ExpectedRequirementState(
                requirement.id(), EstadoVersionLegal.VIGENTE, operationAt, null));
        transitions.add(requirementTransition(
                requirement.id(), EstadoVersionLegal.BORRADOR,
                EstadoVersionLegal.PUBLICADA, null, operationAt));
        transitions.add(requirementTransition(
                requirement.id(), EstadoVersionLegal.PUBLICADA,
                EstadoVersionLegal.VIGENTE, null, operationAt));
    }

    private static LegalEditorialExecutionPlan.ExpectedDocumentState expectedDocumentState(
            DocumentEvidence document) {
        return new LegalEditorialExecutionPlan.ExpectedDocumentState(
                document.id(),
                document.state(),
                document.stateChangedAt(),
                document.lastReason(),
                document.replacementBatchId());
    }

    private static LegalEditorialExecutionPlan.ExpectedRequirementState expectedRequirementState(
            RequirementEvidence requirement) {
        return new LegalEditorialExecutionPlan.ExpectedRequirementState(
                requirement.id(),
                requirement.state(),
                requirement.stateChangedAt(),
                requirement.lastReason());
    }

    private static LegalEditorialExecutionPlan.ReplacementBatch replacementBatch(
            PlannerSnapshot snapshot,
            DocumentReplacementBatch declared,
            UUID targetPublicationId,
            Instant operationAt,
            Phase phase) {
        Instant createdAt = operationAt;
        Instant sealedAt = operationAt;
        if (phase == Phase.POST_STATE) {
            BatchEvidence actual = snapshot.batches().get(declared.replacementBatchId());
            createdAt = actual.createdAt();
            sealedAt = actual.sealedAt();
        }
        return new LegalEditorialExecutionPlan.ReplacementBatch(
                declared.replacementBatchId(),
                createdAt,
                sealedAt,
                declared.predecessors().stream()
                        .map(DocumentRef::documentVersionId)
                        .toList(),
                declared.successors().stream()
                        .map(successor -> new LegalEditorialExecutionPlan.ReplacementSuccessor(
                                successor.documentVersionId(),
                                targetPublicationId))
                        .toList());
    }

    private static Optional<List<LegalEditorialExecutionPlan.ReplacementBatch>>
            preexistingReplacementBatches(
                    PlannerSnapshot snapshot,
                    List<LegalEditorialExecutionPlan.ReplacementBatch> currentBatches,
                    PreexistingTransitionHistory preexistingHistory,
                    Phase phase) {
        Set<UUID> currentBatchIds = currentBatches.stream()
                .map(LegalEditorialExecutionPlan.ReplacementBatch::batchId)
                .collect(Collectors.toUnmodifiableSet());
        Set<UUID> historicalBatchIds = preexistingHistory.documentTransitions().stream()
                .map(LegalEditorialExecutionPlan.DocumentTransition::replacementBatchId)
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
        if (historicalBatchIds.stream().anyMatch(currentBatchIds::contains)) {
            return Optional.empty();
        }
        Set<UUID> expectedActualBatchIds = new HashSet<>(historicalBatchIds);
        if (phase == Phase.POST_STATE) {
            expectedActualBatchIds.addAll(currentBatchIds);
        }
        if (!snapshot.batches().keySet().equals(expectedActualBatchIds)) {
            return Optional.empty();
        }
        List<LegalEditorialExecutionPlan.ReplacementBatch> historical = new ArrayList<>();
        for (BatchEvidence evidence : historicalBatchIds.stream()
                .map(snapshot.batches()::get)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(evidence -> evidence.id().toString()))
                .toList()) {
            if (!evidence.sealed()
                    || !historicalBatchMatchesTransitions(
                            evidence,
                            preexistingHistory.documentTransitions())) {
                return Optional.empty();
            }
            historical.add(new LegalEditorialExecutionPlan.ReplacementBatch(
                    evidence.id(),
                    evidence.createdAt(),
                    evidence.sealedAt(),
                    evidence.predecessorIds(),
                    evidence.successors()));
        }
        return Optional.of(List.copyOf(historical));
    }

    private static boolean historicalBatchMatchesTransitions(
            BatchEvidence batch,
            List<LegalEditorialExecutionPlan.DocumentTransition> transitions) {
        boolean referenced = false;
        Set<UUID> successorIds = batch.successors().stream()
                .map(LegalEditorialExecutionPlan.ReplacementSuccessor::documentVersionId)
                .collect(Collectors.toUnmodifiableSet());
        for (LegalEditorialExecutionPlan.DocumentTransition transition : transitions) {
            if (!batch.id().equals(transition.replacementBatchId())) {
                continue;
            }
            referenced = true;
            boolean membershipMatches =
                    transition.newState() == EstadoVersionLegal.VIGENTE
                            && successorIds.contains(transition.documentVersionId())
                    || transition.newState() == EstadoVersionLegal.REEMPLAZADA
                            && batch.predecessorIds().contains(transition.documentVersionId());
            if (!membershipMatches || !transition.occurredAt().equals(batch.sealedAt())) {
                return false;
            }
        }
        return referenced;
    }

    private static List<LegalEditorialExecutionPlan.ExpectedDocumentSlot> expectedSlots(
            DocumentEvidence document,
            UUID publicationId) {
        return document.contexts().stream()
                .map(context -> new LegalEditorialExecutionPlan.ExpectedDocumentSlot(
                        new LegalEditorialExecutionPlan.DocumentSlotKey(
                                document.type(), document.locale(), context),
                        document.id(),
                        document.lineId(),
                        publicationId))
                .toList();
    }

    private static List<LegalEditorialExecutionPlan.ExpectedRequiredSetPointer> expectedPointers(
            List<TargetScopeEvidence> scopes,
            Instant updatedAt) {
        return scopes.stream()
                .map(scope -> new LegalEditorialExecutionPlan.ExpectedRequiredSetPointer(
                        scope.key(),
                        scope.requiredSetId(),
                        scope.publicationId(),
                        scope.revision(),
                        updatedAt))
                .toList();
    }

    private static LegalEditorialExecutionPlan.DocumentTransition documentTransition(
            UUID id,
            EstadoVersionLegal previous,
            EstadoVersionLegal next,
            String reason,
            UUID batchId,
            Instant occurredAt) {
        return new LegalEditorialExecutionPlan.DocumentTransition(
                id, previous, next, reason, batchId, occurredAt);
    }

    private static LegalEditorialExecutionPlan.RequirementTransition requirementTransition(
            UUID id,
            EstadoVersionLegal previous,
            EstadoVersionLegal next,
            String reason,
            Instant occurredAt) {
        return new LegalEditorialExecutionPlan.RequirementTransition(
                id, previous, next, reason, occurredAt);
    }

    private static List<SlotEvidence> slotsFor(PlannerSnapshot snapshot, UUID versionId) {
        return snapshot.activeSlots().stream()
                .filter(slot -> slot.documentVersionId().equals(versionId))
                .toList();
    }

    private static Map<UUID, Set<ContextoLegal>> activeDocumentContexts(
            PlannerSnapshot snapshot) {
        Map<UUID, Set<ContextoLegal>> contexts = new HashMap<>();
        for (SlotEvidence slot : snapshot.activeSlots()) {
            contexts.computeIfAbsent(
                    slot.documentVersionId(),
                    ignored -> new LinkedHashSet<>()).add(slot.key().context());
        }
        return immutableSetMap(contexts);
    }

    private static Map<UUID, Set<RequirementScope>> activeRequirementScopes(
            PlannerSnapshot snapshot) {
        Map<UUID, Set<RequirementScope>> scopes = new HashMap<>();
        for (PointerEvidence pointer : snapshot.activePointers()) {
            RequirementScope scope = new RequirementScope(
                    pointer.key().context(), pointer.key().audience());
            for (UUID member : pointer.memberVersionIds()) {
                scopes.computeIfAbsent(member, ignored -> new LinkedHashSet<>()).add(scope);
            }
        }
        return immutableSetMap(scopes);
    }

    private static boolean exactReplacementSourceProjections(
            PlannerSnapshot snapshot,
            LegalEditorialPlanV1 plan,
            UUID sourcePublicationId) {
        Map<UUID, Set<ContextoLegal>> documents = activeDocumentContexts(snapshot);
        Set<UUID> declaredSourceRequirements = declaredIds(plan).sourceRequirementIds();
        if (snapshot.activeSlots().stream().anyMatch(slot ->
                !slot.publicationId().equals(sourcePublicationId))
                || snapshot.activePointers().stream().anyMatch(pointer ->
                        !pointer.publicationId().equals(sourcePublicationId)
                                || !declaredSourceRequirements.containsAll(
                                        pointer.memberVersionIds()))) {
            return false;
        }
        for (DocumentScopedRef reuse : plan.documentReuses()) {
            if (!matchesActiveDocument(documents, reuse)) {
                return false;
            }
        }
        for (DocumentRetirement retirement : plan.documentRetirements()) {
            if (!matchesActiveDocument(documents, retirement)) {
                return false;
            }
        }
        for (DocumentReplacementBatch batch : plan.documentReplacementBatches()) {
            for (DocumentRef predecessor : batch.predecessors()) {
                DocumentEvidence document = snapshot.documents().get(
                        predecessor.documentVersionId());
                if (document == null
                        || !documents.getOrDefault(document.id(), Set.of())
                                .equals(Set.copyOf(document.contexts()))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static Set<UUID> currentSourceDocumentIds(PlannerSnapshot snapshot) {
        return snapshot.sourceDocumentIds().stream()
                .filter(id -> {
                    DocumentEvidence document = snapshot.documents().get(id);
                    return document != null && document.state() == EstadoVersionLegal.VIGENTE;
                })
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Set<UUID> currentSourceRequirementIds(PlannerSnapshot snapshot) {
        return snapshot.sourceRequirementIds().stream()
                .filter(id -> {
                    RequirementEvidence requirement = snapshot.requirements().get(id);
                    return requirement != null && requirement.state() == EstadoVersionLegal.VIGENTE;
                })
                .collect(Collectors.toUnmodifiableSet());
    }

    private static boolean sourceMembershipHasOnlyOperationalStates(
            PlannerSnapshot snapshot) {
        return snapshot.sourceDocumentIds().stream().allMatch(id -> {
            DocumentEvidence document = snapshot.documents().get(id);
            return document != null && isOperationalSourceState(document.state());
        }) && snapshot.sourceRequirementIds().stream().allMatch(id -> {
            RequirementEvidence requirement = snapshot.requirements().get(id);
            return requirement != null && isOperationalSourceState(requirement.state());
        });
    }

    private static boolean isOperationalSourceState(EstadoVersionLegal state) {
        return state == EstadoVersionLegal.VIGENTE
                || state == EstadoVersionLegal.REEMPLAZADA
                || state == EstadoVersionLegal.RETIRADA;
    }

    private static boolean exactRetirementSourceProjections(
            PlannerSnapshot snapshot,
            LegalEditorialPlanV1 plan) {
        Map<UUID, Set<ContextoLegal>> documents = activeDocumentContexts(snapshot);
        Map<UUID, Set<RequirementScope>> requirements = activeRequirementScopes(snapshot);
        for (DocumentRetirement retirement : plan.documentRetirements()) {
            if (!matchesActiveDocument(documents, retirement)) {
                return false;
            }
        }
        for (RequirementRetirement retirement : plan.requirementRetirements()) {
            if (!matchesActiveRequirement(requirements, retirement)) {
                return false;
            }
        }
        return true;
    }

    private static boolean pointerReferencesAny(
            PointerEvidence pointer,
            Set<UUID> documentVersionIds,
            Set<UUID> requirementVersionIds) {
        return pointer.referencedDocumentVersionIds().stream().anyMatch(documentVersionIds::contains)
                || pointer.memberVersionIds().stream().anyMatch(requirementVersionIds::contains);
    }

    private static <K, V> Map<K, Set<V>> immutableSetMap(Map<K, Set<V>> source) {
        Map<K, Set<V>> copy = new HashMap<>();
        source.forEach((key, values) -> copy.put(key, Set.copyOf(values)));
        return Map.copyOf(copy);
    }

    private static boolean matchesTargetDocument(
            PlannerSnapshot snapshot,
            DocumentScopedRef declared) {
        DocumentEvidence actual = snapshot.documents().get(declared.documentVersionId());
        return actual != null
                && actual.sha256().equals(declared.sha256())
                && Set.copyOf(actual.contexts()).equals(Set.copyOf(declared.contexts()));
    }

    private static boolean matchesDocumentRetirement(
            PlannerSnapshot snapshot,
            DocumentRetirement declared) {
        DocumentEvidence actual = snapshot.documents().get(declared.documentVersionId());
        return actual != null
                && actual.sha256().equals(declared.sha256())
                && Set.copyOf(actual.contexts()).equals(Set.copyOf(declared.contexts()));
    }

    private static boolean matchesActiveDocument(
            Map<UUID, Set<ContextoLegal>> active,
            DocumentScopedRef declared) {
        return active.getOrDefault(declared.documentVersionId(), Set.of())
                .equals(Set.copyOf(declared.contexts()));
    }

    private static boolean matchesActiveDocument(
            Map<UUID, Set<ContextoLegal>> active,
            DocumentRetirement declared) {
        return active.getOrDefault(declared.documentVersionId(), Set.of())
                .equals(Set.copyOf(declared.contexts()));
    }

    private static boolean matchesBatchMapping(
            PlannerSnapshot snapshot,
            DocumentReplacementBatch batch) {
        Set<ContextoLegal> predecessorContexts = new HashSet<>();
        Set<ContextoLegal> successorContexts = new HashSet<>();
        TipoDocumentoLegal type = null;
        LocaleLegal locale = null;
        for (DocumentRef predecessor : batch.predecessors()) {
            DocumentEvidence document = snapshot.documents().get(predecessor.documentVersionId());
            if (document == null || !document.sha256().equals(predecessor.sha256())) {
                return false;
            }
            if (type == null) {
                type = document.type();
                locale = document.locale();
            } else if (type != document.type() || locale != document.locale()) {
                return false;
            }
            Set<ContextoLegal> contexts = Set.copyOf(document.contexts());
            if (contexts.stream().anyMatch(predecessorContexts::contains)) {
                return false;
            }
            predecessorContexts.addAll(contexts);
        }
        for (DocumentRef successor : batch.successors()) {
            DocumentEvidence document = snapshot.documents().get(successor.documentVersionId());
            if (document == null
                    || !document.sha256().equals(successor.sha256())
                    || type != document.type()
                    || locale != document.locale()) {
                return false;
            }
            Set<ContextoLegal> contexts = Set.copyOf(document.contexts());
            if (contexts.stream().anyMatch(successorContexts::contains)) {
                return false;
            }
            successorContexts.addAll(contexts);
        }
        return predecessorContexts.equals(Set.copyOf(batch.contexts()))
                && successorContexts.equals(Set.copyOf(batch.contexts()))
                && batch.successors().stream().allMatch(successor ->
                        snapshot.targetDocumentIds().contains(successor.documentVersionId()));
    }

    private static boolean matchesTargetRequirement(
            PlannerSnapshot snapshot,
            RequirementScopedRef declared) {
        RequirementEvidence actual = snapshot.requirements().get(declared.requirementVersionId());
        return actual != null
                && actual.statementSha256().equals(declared.statementSha256())
                && actual.context() == declared.context()
                && Set.copyOf(actual.audiences()).equals(Set.copyOf(declared.audiences()));
    }

    private static boolean matchesRequirementRetirement(
            PlannerSnapshot snapshot,
            RequirementRetirement declared) {
        RequirementEvidence actual = snapshot.requirements().get(declared.requirementVersionId());
        return actual != null
                && actual.statementSha256().equals(declared.statementSha256())
                && actual.context() == declared.context()
                && Set.copyOf(actual.audiences()).equals(Set.copyOf(declared.audiences()));
    }

    private static boolean matchesActiveRequirement(
            Map<UUID, Set<RequirementScope>> active,
            RequirementScopedRef declared) {
        Set<RequirementScope> expected = declared.audiences().stream()
                .map(audience -> new RequirementScope(declared.context(), audience))
                .collect(Collectors.toUnmodifiableSet());
        return active.getOrDefault(declared.requirementVersionId(), Set.of()).equals(expected);
    }

    private static boolean matchesActiveRequirement(
            Map<UUID, Set<RequirementScope>> active,
            RequirementRetirement declared) {
        Set<RequirementScope> expected = declared.audiences().stream()
                .map(audience -> new RequirementScope(declared.context(), audience))
                .collect(Collectors.toUnmodifiableSet());
        return active.getOrDefault(declared.requirementVersionId(), Set.of()).equals(expected);
    }

    private static boolean matchesRequirementReplacement(
            PlannerSnapshot snapshot,
            RequirementReplacement replacement) {
        RequirementEvidence predecessor = snapshot.requirements().get(
                replacement.predecessor().requirementVersionId());
        RequirementEvidence successor = snapshot.requirements().get(
                replacement.successor().requirementVersionId());
        return predecessor != null
                && successor != null
                && predecessor.statementSha256().equals(
                        replacement.predecessor().statementSha256())
                && successor.statementSha256().equals(
                        replacement.successor().statementSha256())
                && predecessor.lineId().equals(successor.lineId())
                && predecessor.context() == replacement.context()
                && successor.context() == replacement.context()
                && Set.copyOf(predecessor.audiences()).equals(Set.copyOf(replacement.audiences()))
                && Set.copyOf(successor.audiences()).equals(Set.copyOf(replacement.audiences()));
    }

    private static DeclaredIds declaredIds(LegalEditorialPlanV1 plan) {
        Set<UUID> sourceDocuments = new LinkedHashSet<>();
        Set<UUID> targetDocuments = new LinkedHashSet<>();
        Set<UUID> changedDocuments = new LinkedHashSet<>();
        Set<UUID> sourceRequirements = new LinkedHashSet<>();
        Set<UUID> targetRequirements = new LinkedHashSet<>();
        Set<UUID> changedRequirements = new LinkedHashSet<>();
        Set<UUID> batches = new LinkedHashSet<>();

        plan.documentAdditions().forEach(item -> {
            targetDocuments.add(item.documentVersionId());
            changedDocuments.add(item.documentVersionId());
        });
        plan.documentReuses().forEach(item -> {
            sourceDocuments.add(item.documentVersionId());
            targetDocuments.add(item.documentVersionId());
        });
        plan.documentRetirements().forEach(item -> {
            sourceDocuments.add(item.documentVersionId());
            changedDocuments.add(item.documentVersionId());
        });
        plan.documentReplacementBatches().forEach(batch -> {
            batches.add(batch.replacementBatchId());
            batch.predecessors().forEach(item -> {
                sourceDocuments.add(item.documentVersionId());
                changedDocuments.add(item.documentVersionId());
            });
            batch.successors().forEach(item -> {
                targetDocuments.add(item.documentVersionId());
                changedDocuments.add(item.documentVersionId());
            });
        });
        plan.requirementAdditions().forEach(item -> {
            targetRequirements.add(item.requirementVersionId());
            changedRequirements.add(item.requirementVersionId());
        });
        plan.requirementReuses().forEach(item -> {
            sourceRequirements.add(item.requirementVersionId());
            targetRequirements.add(item.requirementVersionId());
        });
        plan.requirementRetirements().forEach(item -> {
            sourceRequirements.add(item.requirementVersionId());
            changedRequirements.add(item.requirementVersionId());
        });
        plan.requirementReplacements().forEach(item -> {
            sourceRequirements.add(item.predecessor().requirementVersionId());
            targetRequirements.add(item.successor().requirementVersionId());
            changedRequirements.add(item.predecessor().requirementVersionId());
            changedRequirements.add(item.successor().requirementVersionId());
        });
        Set<UUID> documents = new LinkedHashSet<>(sourceDocuments);
        documents.addAll(targetDocuments);
        Set<UUID> requirements = new LinkedHashSet<>(sourceRequirements);
        requirements.addAll(targetRequirements);
        return new DeclaredIds(
                Set.copyOf(documents),
                Set.copyOf(requirements),
                Set.copyOf(batches),
                Set.copyOf(sourceDocuments),
                Set.copyOf(targetDocuments),
                Set.copyOf(changedDocuments),
                Set.copyOf(sourceRequirements),
                Set.copyOf(targetRequirements),
                Set.copyOf(changedRequirements));
    }

    private static List<DocumentEvidence> targetDocuments(PlannerSnapshot snapshot) {
        return snapshot.targetDocumentIds().stream()
                .map(snapshot.documents()::get)
                .filter(Objects::nonNull)
                .toList();
    }

    private static List<RequirementEvidence> targetRequirements(PlannerSnapshot snapshot) {
        return snapshot.targetRequirementIds().stream()
                .map(snapshot.requirements()::get)
                .filter(Objects::nonNull)
                .toList();
    }

    private static Optional<PreexistingTransitionHistory> preexistingTransitionHistory(
            PlannerSnapshot snapshot,
            List<LegalEditorialExecutionPlan.ExpectedDocumentState> documentStates,
            List<LegalEditorialExecutionPlan.ExpectedRequirementState> requirementStates,
            List<LegalEditorialExecutionPlan.DocumentTransition> documentDelta,
            List<LegalEditorialExecutionPlan.RequirementTransition> requirementDelta,
            Phase phase) {
        Set<UUID> documentIds = documentStates.stream()
                .map(LegalEditorialExecutionPlan.ExpectedDocumentState::documentVersionId)
                .collect(Collectors.toUnmodifiableSet());
        Set<UUID> requirementIds = requirementStates.stream()
                .map(LegalEditorialExecutionPlan.ExpectedRequirementState::requirementVersionId)
                .collect(Collectors.toUnmodifiableSet());
        List<LegalEditorialExecutionPlan.DocumentTransition> observedDocuments =
                snapshot.documentTransitions().stream()
                        .filter(transition -> documentIds.contains(transition.versionId()))
                        .map(LegalEditorialPlannerCore::documentTransition)
                        .toList();
        List<LegalEditorialExecutionPlan.RequirementTransition> observedRequirements =
                snapshot.requirementTransitions().stream()
                        .filter(transition -> requirementIds.contains(transition.versionId()))
                        .map(LegalEditorialPlannerCore::requirementTransition)
                        .toList();
        Optional<List<LegalEditorialExecutionPlan.DocumentTransition>> documents =
                subtractCutoverDelta(observedDocuments, documentDelta, phase);
        Optional<List<LegalEditorialExecutionPlan.RequirementTransition>> requirements =
                subtractCutoverDelta(observedRequirements, requirementDelta, phase);
        if (documents.isEmpty() || requirements.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new PreexistingTransitionHistory(
                documents.orElseThrow(),
                requirements.orElseThrow()));
    }

    private static LegalEditorialExecutionPlan.DocumentTransition documentTransition(
            DocumentTransitionEvidence evidence) {
        return documentTransition(
                evidence.versionId(),
                evidence.previousState(),
                evidence.newState(),
                evidence.reason(),
                evidence.replacementBatchId(),
                evidence.occurredAt());
    }

    private static LegalEditorialExecutionPlan.RequirementTransition requirementTransition(
            RequirementTransitionEvidence evidence) {
        return requirementTransition(
                evidence.versionId(),
                evidence.previousState(),
                evidence.newState(),
                evidence.reason(),
                evidence.occurredAt());
    }

    private static <T> Optional<List<T>> subtractCutoverDelta(
            List<T> observedHistory,
            List<T> cutoverDelta,
            Phase phase) {
        List<T> preexisting = new ArrayList<>(observedHistory);
        if (phase == Phase.SOURCE_STATE) {
            if (preexisting.stream().anyMatch(Set.copyOf(cutoverDelta)::contains)) {
                return Optional.empty();
            }
            return Optional.of(List.copyOf(preexisting));
        }
        for (T expectedTransition : cutoverDelta) {
            if (!preexisting.remove(expectedTransition)) {
                return Optional.empty();
            }
        }
        return Optional.of(List.copyOf(preexisting));
    }

    private static List<TransitionEvidence> histories(
            List<? extends TransitionEvidence> transitions,
            UUID versionId) {
        return transitions.stream()
                .filter(transition -> transition.versionId().equals(versionId))
                .sorted(Comparator.comparingLong(TransitionEvidence::id))
                .map(TransitionEvidence.class::cast)
                .toList();
    }

    private static LegalEditorialExecutionPlan.PublicationIdentity identity(
            PublicationEvidence publication) {
        return new LegalEditorialExecutionPlan.PublicationIdentity(
                publication.externalId(),
                publication.id(),
                publication.manifestSha256());
    }

    private static LegalEditorialPlanResult blocked(
            LegalManifestIssueCode code,
            String location) {
        return LegalEditorialPlanResult.blocked(List.of(LegalManifestIssue.at(code, location)));
    }

    private static Instant requirePostgresInstant(Instant value) {
        Instant required = Objects.requireNonNull(value, "observedAt");
        if (required.getNano() % 1_000 != 0) {
            throw new IllegalArgumentException(
                    "observedAt supera la precisión de PostgreSQL");
        }
        return required;
    }

    private enum Phase {
        SOURCE_STATE,
        POST_STATE
    }

    private record TargetAccreditation(PublicationEvidence publication) { }

    private record MappingCheck(boolean valid) {
        static final MappingCheck VALID = new MappingCheck(true);
        static final MappingCheck INVALID = new MappingCheck(false);
    }

    private record PreexistingTransitionHistory(
            List<LegalEditorialExecutionPlan.DocumentTransition> documentTransitions,
            List<LegalEditorialExecutionPlan.RequirementTransition> requirementTransitions) {

        private PreexistingTransitionHistory {
            documentTransitions = List.copyOf(documentTransitions);
            requirementTransitions = List.copyOf(requirementTransitions);
        }
    }

    private record PlanAttempt(
            Optional<LegalEditorialExecutionPlan> plan,
            LegalManifestIssueCode failureCode) {

        static PlanAttempt success(LegalEditorialExecutionPlan plan) {
            return new PlanAttempt(Optional.of(plan), null);
        }

        static PlanAttempt failure(LegalManifestIssueCode code) {
            return new PlanAttempt(Optional.empty(), Objects.requireNonNull(code, "code"));
        }
    }

    private record DeclaredIds(
            Set<UUID> documentIds,
            Set<UUID> requirementIds,
            Set<UUID> batchIds,
            Set<UUID> sourceDocumentIds,
            Set<UUID> targetDocumentIds,
            Set<UUID> changedDocumentIds,
            Set<UUID> sourceRequirementIds,
            Set<UUID> targetRequirementIds,
            Set<UUID> changedRequirementIds
    ) { }

    record PublicationEvidence(
            UUID id,
            String externalId,
            String manifestSha256,
            String constructionState,
            Instant sealedAt
    ) {
        PublicationEvidence {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(externalId, "externalId");
            Objects.requireNonNull(manifestSha256, "manifestSha256");
            Objects.requireNonNull(constructionState, "constructionState");
        }

        boolean sealed() {
            return "SELLADO".equals(constructionState) && sealedAt != null;
        }
    }

    record DocumentEvidence(
            UUID id,
            UUID lineId,
            UUID introductionPublicationId,
            String sha256,
            Instant effectiveAt,
            EstadoVersionLegal state,
            Instant stateChangedAt,
            String lastReason,
            UUID replacementBatchId,
            TipoDocumentoLegal type,
            LocaleLegal locale,
            List<ContextoLegal> contexts
    ) {
        DocumentEvidence {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(lineId, "lineId");
            Objects.requireNonNull(introductionPublicationId, "introductionPublicationId");
            Objects.requireNonNull(sha256, "sha256");
            Objects.requireNonNull(effectiveAt, "effectiveAt");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(locale, "locale");
            contexts = List.copyOf(Objects.requireNonNull(contexts, "contexts"));
        }
    }

    record RequirementEvidence(
            UUID id,
            UUID lineId,
            UUID introductionPublicationId,
            String statementSha256,
            EstadoVersionLegal state,
            Instant stateChangedAt,
            String lastReason,
            LocaleLegal locale,
            ContextoLegal context,
            List<AudienciaLegal> audiences
    ) {
        RequirementEvidence {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(lineId, "lineId");
            Objects.requireNonNull(introductionPublicationId, "introductionPublicationId");
            Objects.requireNonNull(statementSha256, "statementSha256");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(locale, "locale");
            Objects.requireNonNull(context, "context");
            audiences = List.copyOf(Objects.requireNonNull(audiences, "audiences"));
        }
    }

    interface TransitionEvidence {
        long id();
        UUID versionId();
        EstadoVersionLegal previousState();
        EstadoVersionLegal newState();
        String reason();
        UUID replacementBatchId();
        Instant occurredAt();
    }

    record DocumentTransitionEvidence(
            long id,
            UUID versionId,
            EstadoVersionLegal previousState,
            EstadoVersionLegal newState,
            String reason,
            UUID replacementBatchId,
            Instant occurredAt
    ) implements TransitionEvidence { }

    record RequirementTransitionEvidence(
            long id,
            UUID versionId,
            EstadoVersionLegal previousState,
            EstadoVersionLegal newState,
            String reason,
            UUID replacementBatchId,
            Instant occurredAt
    ) implements TransitionEvidence { }

    record SlotEvidence(
            LegalEditorialExecutionPlan.DocumentSlotKey key,
            UUID documentVersionId,
            UUID documentLineId,
            UUID publicationId
    ) { }

    record PointerEvidence(
            LegalEditorialExecutionPlan.RequiredSetPointerKey key,
            UUID requiredSetId,
            UUID publicationId,
            String revision,
            Instant updatedAt,
            List<UUID> memberVersionIds,
            List<UUID> referencedDocumentVersionIds
    ) {
        PointerEvidence {
            memberVersionIds = List.copyOf(memberVersionIds);
            referencedDocumentVersionIds = List.copyOf(referencedDocumentVersionIds);
        }
    }

    record TargetScopeEvidence(
            LegalEditorialExecutionPlan.RequiredSetPointerKey key,
            UUID requiredSetId,
            UUID publicationId,
            String revision
    ) { }

    record BatchEvidence(
            UUID id,
            Instant createdAt,
            Instant sealedAt,
            List<UUID> predecessorIds,
            List<LegalEditorialExecutionPlan.ReplacementSuccessor> successors
    ) {
        BatchEvidence {
            predecessorIds = List.copyOf(predecessorIds);
            successors = List.copyOf(successors);
        }

        boolean sealed() {
            return sealedAt != null;
        }
    }

    record PlannerSnapshot(
            List<UUID> targetDocumentIds,
            List<UUID> targetRequirementIds,
            List<UUID> sourceDocumentIds,
            List<UUID> sourceRequirementIds,
            Map<UUID, DocumentEvidence> documents,
            Map<UUID, RequirementEvidence> requirements,
            List<TargetScopeEvidence> targetScopes,
            List<SlotEvidence> activeSlots,
            List<PointerEvidence> activePointers,
            List<DocumentTransitionEvidence> documentTransitions,
            List<RequirementTransitionEvidence> requirementTransitions,
            Map<UUID, BatchEvidence> batches
    ) {
        PlannerSnapshot {
            targetDocumentIds = List.copyOf(targetDocumentIds);
            targetRequirementIds = List.copyOf(targetRequirementIds);
            sourceDocumentIds = List.copyOf(sourceDocumentIds);
            sourceRequirementIds = List.copyOf(sourceRequirementIds);
            documents = Map.copyOf(documents);
            requirements = Map.copyOf(requirements);
            targetScopes = List.copyOf(targetScopes);
            activeSlots = List.copyOf(activeSlots);
            activePointers = List.copyOf(activePointers);
            documentTransitions = List.copyOf(documentTransitions);
            requirementTransitions = List.copyOf(requirementTransitions);
            batches = Map.copyOf(batches);
        }
    }

    private record RequirementScope(ContextoLegal context, AudienciaLegal audience) { }

    interface PlannerStateReader {
        Optional<PublicationEvidence> publication(String externalId);

        PlannerSnapshot snapshot(
                UUID targetPublicationId,
                UUID sourcePublicationId,
                Set<UUID> declaredDocumentIds,
                Set<UUID> declaredRequirementIds,
                Set<UUID> declaredBatchIds);

        boolean usesJdbc(JdbcTemplate candidate);
    }

    /** Bounded SELECT-only materialization used by the pure classifier above. */
    static final class JdbcPlannerStateReader implements PlannerStateReader {

        private final JdbcTemplate jdbc;

        JdbcPlannerStateReader(JdbcTemplate jdbc) {
            this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        }

        @Override
        public Optional<PublicationEvidence> publication(String externalId) {
            List<PublicationEvidence> rows = jdbc.query("""
                    SELECT id, publication_external_id, manifest_sha256,
                           estado_construccion, sellado_en
                      FROM legal_publicaciones
                     WHERE publication_external_id = ?
                     LIMIT 2
                    """, (resultSet, rowNumber) -> new PublicationEvidence(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getString("publication_external_id"),
                    resultSet.getString("manifest_sha256"),
                    resultSet.getString("estado_construccion"),
                    instant(resultSet.getObject("sellado_en", OffsetDateTime.class))),
                    externalId);
            if (rows.size() > 1) {
                throw observationFailed();
            }
            return rows.stream().findFirst();
        }

        @Override
        public PlannerSnapshot snapshot(
                UUID targetPublicationId,
                UUID sourcePublicationId,
                Set<UUID> declaredDocumentIds,
                Set<UUID> declaredRequirementIds,
                Set<UUID> declaredBatchIds) {
            Objects.requireNonNull(targetPublicationId, "targetPublicationId");
            Objects.requireNonNull(sourcePublicationId, "sourcePublicationId");
            List<UUID> targetDocumentIds = readTargetDocumentIds(targetPublicationId);
            List<UUID> targetRequirementIds = readTargetRequirementIds(targetPublicationId);
            List<UUID> sourceDocumentIds = targetPublicationId.equals(sourcePublicationId)
                    ? targetDocumentIds
                    : readTargetDocumentIds(sourcePublicationId);
            List<UUID> sourceRequirementIds = targetPublicationId.equals(sourcePublicationId)
                    ? targetRequirementIds
                    : readTargetRequirementIds(sourcePublicationId);
            List<SlotEvidence> slots = readActiveSlots();
            List<PointerEvidence> pointers = readActivePointers();

            Set<UUID> documentIds = new LinkedHashSet<>(declaredDocumentIds);
            documentIds.addAll(targetDocumentIds);
            documentIds.addAll(sourceDocumentIds);
            slots.forEach(slot -> documentIds.add(slot.documentVersionId()));
            Set<UUID> requirementIds = new LinkedHashSet<>(declaredRequirementIds);
            requirementIds.addAll(targetRequirementIds);
            requirementIds.addAll(sourceRequirementIds);
            pointers.forEach(pointer -> requirementIds.addAll(pointer.memberVersionIds()));
            requireMaximum(documentIds.size(), MAX_RELEVANT_DOCUMENTS);
            requireMaximum(requirementIds.size(), MAX_RELEVANT_REQUIREMENTS);

            Map<UUID, DocumentEvidence> documents = readDocuments(documentIds);
            Map<UUID, RequirementEvidence> requirements = readRequirements(requirementIds);
            List<DocumentTransitionEvidence> documentTransitions =
                    readDocumentTransitions(documentIds);
            List<RequirementTransitionEvidence> requirementTransitions =
                    readRequirementTransitions(requirementIds);
            Map<UUID, BatchEvidence> batches = readBatches(documentIds, declaredBatchIds);
            return new PlannerSnapshot(
                    targetDocumentIds,
                    targetRequirementIds,
                    sourceDocumentIds,
                    sourceRequirementIds,
                    documents,
                    requirements,
                    readTargetScopes(targetPublicationId),
                    slots,
                    pointers,
                    documentTransitions,
                    requirementTransitions,
                    batches);
        }

        @Override
        public boolean usesJdbc(JdbcTemplate candidate) {
            return jdbc == candidate;
        }

        private List<UUID> readTargetDocumentIds(UUID publicationId) {
            return bounded(jdbc.query("""
                    SELECT documento_version_id
                      FROM legal_publicacion_documentos
                     WHERE publicacion_id = ?
                     ORDER BY manifest_ordinal
                     LIMIT ?
                    """, (resultSet, rowNumber) ->
                    resultSet.getObject("documento_version_id", UUID.class),
                    publicationId,
                    MAX_RELEVANT_DOCUMENTS + 1), MAX_RELEVANT_DOCUMENTS);
        }

        private List<UUID> readTargetRequirementIds(UUID publicationId) {
            return bounded(jdbc.query("""
                    SELECT requisito_version_id
                      FROM legal_publicacion_requisitos
                     WHERE publicacion_id = ?
                     ORDER BY manifest_ordinal
                     LIMIT ?
                    """, (resultSet, rowNumber) ->
                    resultSet.getObject("requisito_version_id", UUID.class),
                    publicationId,
                    MAX_RELEVANT_REQUIREMENTS + 1), MAX_RELEVANT_REQUIREMENTS);
        }

        private List<SlotEvidence> readActiveSlots() {
            return bounded(jdbc.query("""
                    SELECT tipo, locale, contexto, documento_version_id,
                           documento_linea_id, publicacion_id
                      FROM legal_documento_vigentes
                     ORDER BY tipo, locale, contexto
                     LIMIT ?
                    """, (resultSet, rowNumber) -> new SlotEvidence(
                    new LegalEditorialExecutionPlan.DocumentSlotKey(
                            TipoDocumentoLegal.valueOf(resultSet.getString("tipo")),
                            LocaleLegal.fromCodigo(resultSet.getString("locale")),
                            ContextoLegal.valueOf(resultSet.getString("contexto"))),
                    resultSet.getObject("documento_version_id", UUID.class),
                    resultSet.getObject("documento_linea_id", UUID.class),
                    resultSet.getObject("publicacion_id", UUID.class)),
                    MAX_ACTIVE_SLOTS + 1), MAX_ACTIVE_SLOTS);
        }

        private List<PointerEvidence> readActivePointers() {
            List<PointerBase> bases = bounded(jdbc.query("""
                    SELECT a.locale, a.contexto, a.audiencia, a.conjunto_id,
                           a.publicacion_id, a.actualizado_en,
                           c.required_set_revision
                      FROM legal_requisito_conjuntos_actuales a
                      JOIN legal_requisito_conjuntos c ON c.id = a.conjunto_id
                     ORDER BY a.locale, a.contexto, a.audiencia
                     LIMIT ?
                    """, (resultSet, rowNumber) -> new PointerBase(
                    new LegalEditorialExecutionPlan.RequiredSetPointerKey(
                            LocaleLegal.fromCodigo(resultSet.getString("locale")),
                            ContextoLegal.valueOf(resultSet.getString("contexto")),
                            AudienciaLegal.valueOf(resultSet.getString("audiencia"))),
                    resultSet.getObject("conjunto_id", UUID.class),
                    resultSet.getObject("publicacion_id", UUID.class),
                    resultSet.getString("required_set_revision"),
                    requiredInstant(resultSet.getObject(
                            "actualizado_en", OffsetDateTime.class))),
                    MAX_ACTIVE_POINTERS + 1), MAX_ACTIVE_POINTERS);
            List<PointerMember> members = bounded(jdbc.query("""
                    SELECT a.conjunto_id, m.requisito_version_id
                      FROM legal_requisito_conjuntos_actuales a
                      JOIN legal_requisito_conjunto_miembros m
                        ON m.conjunto_id = a.conjunto_id
                     ORDER BY a.locale, a.contexto, a.audiencia,
                              m.manifest_ordinal, m.id
                     LIMIT ?
                    """, (resultSet, rowNumber) -> new PointerMember(
                    resultSet.getObject("conjunto_id", UUID.class),
                    resultSet.getObject("requisito_version_id", UUID.class)),
                    MAX_ACTIVE_POINTER_MEMBERS + 1), MAX_ACTIVE_POINTER_MEMBERS);
            Map<UUID, List<UUID>> membersBySet = members.stream().collect(Collectors.groupingBy(
                    PointerMember::setId,
                    LinkedHashMap::new,
                    Collectors.mapping(PointerMember::versionId, Collectors.toList())));
            List<PointerDocumentReference> documentReferences = bounded(jdbc.query("""
                    SELECT a.conjunto_id, rd.documento_version_id
                      FROM legal_requisito_conjuntos_actuales a
                      JOIN legal_requisito_conjunto_miembros m
                        ON m.conjunto_id = a.conjunto_id
                      JOIN legal_requisito_documentos rd
                        ON rd.requisito_version_id = m.requisito_version_id
                     ORDER BY a.locale, a.contexto, a.audiencia,
                              rd.documento_version_id
                     LIMIT ?
                    """, (resultSet, rowNumber) -> new PointerDocumentReference(
                    resultSet.getObject("conjunto_id", UUID.class),
                    resultSet.getObject("documento_version_id", UUID.class)),
                    MAX_ACTIVE_POINTER_DOCUMENT_REFERENCES + 1),
                    MAX_ACTIVE_POINTER_DOCUMENT_REFERENCES);
            Map<UUID, List<UUID>> documentReferencesBySet = documentReferences.stream()
                    .collect(Collectors.groupingBy(
                            PointerDocumentReference::setId,
                            LinkedHashMap::new,
                            Collectors.mapping(
                                    PointerDocumentReference::documentVersionId,
                                    Collectors.toList())));
            return bases.stream().map(base -> new PointerEvidence(
                    base.key(),
                    base.setId(),
                    base.publicationId(),
                    base.revision(),
                    base.updatedAt(),
                    membersBySet.getOrDefault(base.setId(), List.of()),
                    documentReferencesBySet.getOrDefault(base.setId(), List.of()))).toList();
        }

        private Map<UUID, DocumentEvidence> readDocuments(Set<UUID> ids) {
            if (ids.isEmpty()) {
                return Map.of();
            }
            String placeholders = placeholders(ids.size());
            List<Object> arguments = new ArrayList<>(ids);
            arguments.add(MAX_RELEVANT_DOCUMENTS + 1);
            List<DocumentBase> bases = bounded(jdbc.query("""
                    SELECT dv.id, dv.documento_linea_id, dv.publicacion_intro_id,
                           dv.sha256, dv.vigente_desde, dv.estado,
                           dv.estado_cambiado_en, dv.ultimo_motivo,
                           dv.reemplazo_lote_id, dl.tipo, dl.locale
                      FROM legal_documento_versiones dv
                      JOIN legal_documento_lineas dl ON dl.id = dv.documento_linea_id
                     WHERE dv.id IN (%s)
                     ORDER BY dv.id
                     LIMIT ?
                    """.formatted(placeholders), (resultSet, rowNumber) -> new DocumentBase(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getObject("documento_linea_id", UUID.class),
                    resultSet.getObject("publicacion_intro_id", UUID.class),
                    resultSet.getString("sha256"),
                    requiredInstant(resultSet.getObject("vigente_desde", OffsetDateTime.class)),
                    EstadoVersionLegal.valueOf(resultSet.getString("estado")),
                    instant(resultSet.getObject("estado_cambiado_en", OffsetDateTime.class)),
                    resultSet.getString("ultimo_motivo"),
                    resultSet.getObject("reemplazo_lote_id", UUID.class),
                    TipoDocumentoLegal.valueOf(resultSet.getString("tipo")),
                    LocaleLegal.fromCodigo(resultSet.getString("locale"))),
                    arguments.toArray()), MAX_RELEVANT_DOCUMENTS);
            List<ContextRow> contexts = jdbc.query("""
                    SELECT documento_version_id, contexto
                      FROM legal_documento_contextos
                     WHERE documento_version_id IN (%s)
                     ORDER BY documento_version_id, contexto
                    """.formatted(placeholders), (resultSet, rowNumber) -> new ContextRow(
                    resultSet.getObject("documento_version_id", UUID.class),
                    ContextoLegal.valueOf(resultSet.getString("contexto"))),
                    new ArrayList<>(ids).toArray());
            Map<UUID, List<ContextoLegal>> contextsById = contexts.stream().collect(
                    Collectors.groupingBy(
                            ContextRow::versionId,
                            LinkedHashMap::new,
                            Collectors.mapping(ContextRow::context, Collectors.toList())));
            return bases.stream().map(base -> new DocumentEvidence(
                    base.id(), base.lineId(), base.introductionPublicationId(), base.sha256(),
                    base.effectiveAt(), base.state(), base.stateChangedAt(), base.lastReason(),
                    base.replacementBatchId(), base.type(), base.locale(),
                    contextsById.getOrDefault(base.id(), List.of())))
                    .collect(Collectors.toUnmodifiableMap(DocumentEvidence::id, Function.identity()));
        }

        private Map<UUID, RequirementEvidence> readRequirements(Set<UUID> ids) {
            if (ids.isEmpty()) {
                return Map.of();
            }
            String placeholders = placeholders(ids.size());
            List<Object> arguments = new ArrayList<>(ids);
            arguments.add(MAX_RELEVANT_REQUIREMENTS + 1);
            List<RequirementBase> bases = bounded(jdbc.query("""
                    SELECT rv.id, rv.requisito_linea_id, rv.publicacion_intro_id,
                           rv.afirmacion_sha256, rv.estado, rv.estado_cambiado_en,
                           rv.ultimo_motivo, rl.locale, rl.contexto
                      FROM legal_requisito_versiones rv
                      JOIN legal_requisito_lineas rl ON rl.id = rv.requisito_linea_id
                     WHERE rv.id IN (%s)
                     ORDER BY rv.id
                     LIMIT ?
                    """.formatted(placeholders), (resultSet, rowNumber) -> new RequirementBase(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getObject("requisito_linea_id", UUID.class),
                    resultSet.getObject("publicacion_intro_id", UUID.class),
                    resultSet.getString("afirmacion_sha256"),
                    EstadoVersionLegal.valueOf(resultSet.getString("estado")),
                    instant(resultSet.getObject("estado_cambiado_en", OffsetDateTime.class)),
                    resultSet.getString("ultimo_motivo"),
                    LocaleLegal.fromCodigo(resultSet.getString("locale")),
                    ContextoLegal.valueOf(resultSet.getString("contexto"))),
                    arguments.toArray()), MAX_RELEVANT_REQUIREMENTS);
            Set<UUID> lineIds = bases.stream().map(RequirementBase::lineId)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            Map<UUID, List<AudienciaLegal>> audiencesByLine = readAudiences(lineIds);
            return bases.stream().map(base -> new RequirementEvidence(
                    base.id(), base.lineId(), base.introductionPublicationId(),
                    base.statementSha256(), base.state(), base.stateChangedAt(),
                    base.lastReason(), base.locale(), base.context(),
                    audiencesByLine.getOrDefault(base.lineId(), List.of())))
                    .collect(Collectors.toUnmodifiableMap(
                            RequirementEvidence::id,
                            Function.identity()));
        }

        private Map<UUID, List<AudienciaLegal>> readAudiences(Set<UUID> lineIds) {
            if (lineIds.isEmpty()) {
                return Map.of();
            }
            List<AudienceRow> rows = jdbc.query("""
                    SELECT requisito_linea_id, audiencia
                      FROM legal_requisito_audiencias
                     WHERE requisito_linea_id IN (%s)
                     ORDER BY requisito_linea_id, audiencia
                    """.formatted(placeholders(lineIds.size())),
                    (resultSet, rowNumber) -> new AudienceRow(
                            resultSet.getObject("requisito_linea_id", UUID.class),
                            AudienciaLegal.valueOf(resultSet.getString("audiencia"))),
                    new ArrayList<>(lineIds).toArray());
            return rows.stream().collect(Collectors.groupingBy(
                    AudienceRow::lineId,
                    LinkedHashMap::new,
                    Collectors.mapping(AudienceRow::audience, Collectors.toList())));
        }

        private List<TargetScopeEvidence> readTargetScopes(UUID publicationId) {
            return bounded(jdbc.query("""
                    SELECT id, publicacion_id, locale, contexto, audiencia,
                           required_set_revision
                      FROM legal_requisito_conjuntos
                     WHERE publicacion_id = ?
                     ORDER BY locale, contexto, audiencia
                     LIMIT ?
                    """, (resultSet, rowNumber) -> new TargetScopeEvidence(
                    new LegalEditorialExecutionPlan.RequiredSetPointerKey(
                            LocaleLegal.fromCodigo(resultSet.getString("locale")),
                            ContextoLegal.valueOf(resultSet.getString("contexto")),
                            AudienciaLegal.valueOf(resultSet.getString("audiencia"))),
                    resultSet.getObject("id", UUID.class),
                    resultSet.getObject("publicacion_id", UUID.class),
                    resultSet.getString("required_set_revision")),
                    publicationId,
                    MAX_ACTIVE_POINTERS + 1), MAX_ACTIVE_POINTERS);
        }

        private List<DocumentTransitionEvidence> readDocumentTransitions(Set<UUID> ids) {
            if (ids.isEmpty()) {
                return List.of();
            }
            List<Object> arguments = new ArrayList<>(ids);
            arguments.add(MAX_RELEVANT_TRANSITIONS + 1);
            return bounded(jdbc.query("""
                    SELECT id, documento_version_id, estado_anterior, estado_nuevo,
                           motivo, reemplazo_lote_id, ocurrido_en
                      FROM legal_documento_transiciones
                     WHERE documento_version_id IN (%s)
                     ORDER BY documento_version_id, ocurrido_en, id
                     LIMIT ?
                    """.formatted(placeholders(ids.size())),
                    (resultSet, rowNumber) -> new DocumentTransitionEvidence(
                            resultSet.getLong("id"),
                            resultSet.getObject("documento_version_id", UUID.class),
                            EstadoVersionLegal.valueOf(resultSet.getString("estado_anterior")),
                            EstadoVersionLegal.valueOf(resultSet.getString("estado_nuevo")),
                            resultSet.getString("motivo"),
                            resultSet.getObject("reemplazo_lote_id", UUID.class),
                            requiredInstant(resultSet.getObject(
                                    "ocurrido_en", OffsetDateTime.class))),
                    arguments.toArray()), MAX_RELEVANT_TRANSITIONS);
        }

        private List<RequirementTransitionEvidence> readRequirementTransitions(Set<UUID> ids) {
            if (ids.isEmpty()) {
                return List.of();
            }
            List<Object> arguments = new ArrayList<>(ids);
            arguments.add(MAX_RELEVANT_TRANSITIONS + 1);
            return bounded(jdbc.query("""
                    SELECT id, requisito_version_id, estado_anterior, estado_nuevo,
                           motivo, ocurrido_en
                      FROM legal_requisito_transiciones
                     WHERE requisito_version_id IN (%s)
                     ORDER BY requisito_version_id, ocurrido_en, id
                     LIMIT ?
                    """.formatted(placeholders(ids.size())),
                    (resultSet, rowNumber) -> new RequirementTransitionEvidence(
                            resultSet.getLong("id"),
                            resultSet.getObject("requisito_version_id", UUID.class),
                            EstadoVersionLegal.valueOf(resultSet.getString("estado_anterior")),
                            EstadoVersionLegal.valueOf(resultSet.getString("estado_nuevo")),
                            resultSet.getString("motivo"),
                            null,
                            requiredInstant(resultSet.getObject(
                                    "ocurrido_en", OffsetDateTime.class))),
                    arguments.toArray()), MAX_RELEVANT_TRANSITIONS);
        }

        private Map<UUID, BatchEvidence> readBatches(
                Set<UUID> documentIds,
                Set<UUID> declaredBatchIds) {
            Set<UUID> batchIds = new LinkedHashSet<>(declaredBatchIds);
            if (!documentIds.isEmpty()) {
                List<UUID> related = bounded(jdbc.query("""
                        SELECT DISTINCT lote_id
                          FROM (
                                SELECT lote_id, documento_version_id
                                  FROM legal_documento_reemplazo_anteriores
                                UNION ALL
                                SELECT lote_id, documento_version_id
                                  FROM legal_documento_reemplazo_sucesoras
                          ) miembros
                         WHERE documento_version_id IN (%s)
                         ORDER BY lote_id
                         LIMIT ?
                        """.formatted(placeholders(documentIds.size())),
                        (resultSet, rowNumber) -> resultSet.getObject("lote_id", UUID.class),
                        append(new ArrayList<>(documentIds), MAX_RELEVANT_BATCHES + 1)),
                        MAX_RELEVANT_BATCHES);
                batchIds.addAll(related);
            }
            if (batchIds.isEmpty()) {
                return Map.of();
            }
            String placeholders = placeholders(batchIds.size());
            List<BatchBase> bases = jdbc.query("""
                    SELECT id, creado_en, sellado_en
                      FROM legal_documento_reemplazo_lotes
                     WHERE id IN (%s)
                     ORDER BY id
                    """.formatted(placeholders), (resultSet, rowNumber) -> new BatchBase(
                    resultSet.getObject("id", UUID.class),
                    requiredInstant(resultSet.getObject("creado_en", OffsetDateTime.class)),
                    instant(resultSet.getObject("sellado_en", OffsetDateTime.class))),
                    new ArrayList<>(batchIds).toArray());
            List<BatchPredecessor> predecessors = jdbc.query("""
                    SELECT lote_id, documento_version_id
                      FROM legal_documento_reemplazo_anteriores
                     WHERE lote_id IN (%s)
                     ORDER BY lote_id, documento_version_id
                    """.formatted(placeholders), (resultSet, rowNumber) -> new BatchPredecessor(
                    resultSet.getObject("lote_id", UUID.class),
                    resultSet.getObject("documento_version_id", UUID.class)),
                    new ArrayList<>(batchIds).toArray());
            List<BatchSuccessor> successors = jdbc.query("""
                    SELECT lote_id, documento_version_id, publicacion_id
                      FROM legal_documento_reemplazo_sucesoras
                     WHERE lote_id IN (%s)
                     ORDER BY lote_id, documento_version_id
                    """.formatted(placeholders), (resultSet, rowNumber) -> new BatchSuccessor(
                    resultSet.getObject("lote_id", UUID.class),
                    new LegalEditorialExecutionPlan.ReplacementSuccessor(
                            resultSet.getObject("documento_version_id", UUID.class),
                            resultSet.getObject("publicacion_id", UUID.class))),
                    new ArrayList<>(batchIds).toArray());
            Map<UUID, List<UUID>> predecessorsByBatch = predecessors.stream().collect(
                    Collectors.groupingBy(
                            BatchPredecessor::batchId,
                            LinkedHashMap::new,
                            Collectors.mapping(BatchPredecessor::versionId, Collectors.toList())));
            Map<UUID, List<LegalEditorialExecutionPlan.ReplacementSuccessor>> successorsByBatch =
                    successors.stream().collect(Collectors.groupingBy(
                            BatchSuccessor::batchId,
                            LinkedHashMap::new,
                            Collectors.mapping(BatchSuccessor::successor, Collectors.toList())));
            return bases.stream().map(base -> new BatchEvidence(
                    base.id(), base.createdAt(), base.sealedAt(),
                    predecessorsByBatch.getOrDefault(base.id(), List.of()),
                    successorsByBatch.getOrDefault(base.id(), List.of())))
                    .collect(Collectors.toUnmodifiableMap(BatchEvidence::id, Function.identity()));
        }

        private static String placeholders(int count) {
            if (count <= 0) {
                throw new IllegalArgumentException("Se requiere al menos un UUID acotado");
            }
            return String.join(",", java.util.Collections.nCopies(count, "?"));
        }

        private static Object[] append(List<Object> values, Object last) {
            values.add(last);
            return values.toArray();
        }

        private static <T> List<T> bounded(List<T> rows, int maximum) {
            if (rows.size() > maximum) {
                throw observationFailed();
            }
            return List.copyOf(rows);
        }

        private static void requireMaximum(int count, int maximum) {
            if (count > maximum) {
                throw observationFailed();
            }
        }

        private record PointerBase(
                LegalEditorialExecutionPlan.RequiredSetPointerKey key,
                UUID setId,
                UUID publicationId,
                String revision,
                Instant updatedAt) { }

        private record PointerMember(UUID setId, UUID versionId) { }

        private record PointerDocumentReference(UUID setId, UUID documentVersionId) { }

        private record DocumentBase(
                UUID id, UUID lineId, UUID introductionPublicationId, String sha256,
                Instant effectiveAt, EstadoVersionLegal state, Instant stateChangedAt,
                String lastReason, UUID replacementBatchId, TipoDocumentoLegal type,
                LocaleLegal locale) { }

        private record ContextRow(UUID versionId, ContextoLegal context) { }

        private record RequirementBase(
                UUID id, UUID lineId, UUID introductionPublicationId, String statementSha256,
                EstadoVersionLegal state, Instant stateChangedAt, String lastReason,
                LocaleLegal locale, ContextoLegal context) { }

        private record AudienceRow(UUID lineId, AudienciaLegal audience) { }

        private record BatchBase(UUID id, Instant createdAt, Instant sealedAt) { }

        private record BatchPredecessor(UUID batchId, UUID versionId) { }

        private record BatchSuccessor(
                UUID batchId,
                LegalEditorialExecutionPlan.ReplacementSuccessor successor) { }
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static Instant requiredInstant(OffsetDateTime value) {
        return Objects.requireNonNull(value, "database timestamp").toInstant();
    }

    private static LegalEditorialOperationalException observationFailed() {
        return new LegalEditorialOperationalException(
                LegalManifestIssueCode.EDITORIAL_OBSERVATION_FAILED,
                "database/observation");
    }
}
