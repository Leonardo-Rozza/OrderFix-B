package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialReadiness;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoVersionLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Immutable database delta derived by the editorial planner.
 *
 * <p>The expected post-state is deliberately separate from the commands that would produce it.
 * In particular, effects materialized by the V27 replacement trigger are evidence, never direct
 * commands. A replay therefore keeps the complete expected post-state while exposing an empty
 * command set and zero delta counts.</p>
 */
record LegalEditorialExecutionPlan(
        OperationType operationType,
        Optional<SourceIdentity> source,
        PublicationIdentity target,
        Optional<UUID> operationId,
        Optional<String> planSha256,
        Instant observedAt,
        Instant expectedAppliedAt,
        LegalEditorialReadiness expectedReadinessAfter,
        boolean acknowledgeFailClosedGap,
        boolean changeRequired,
        ExpectedPostState expectedPostState,
        MutationCommands mutationCommands
) {

    private static final int SHA256_LENGTH = 64;
    private static final int FINGERPRINT_LENGTH = 71;
    private static final int MAX_PUBLICATION_EXTERNAL_ID_LENGTH = 120;
    private static final int MAX_REASON_LENGTH = 1_000;

    private static final Comparator<UUID> UUID_TEXT_ORDER =
            Comparator.comparing(UUID::toString);
    private static final Comparator<ExpectedDocumentState> DOCUMENT_STATE_ORDER = Comparator
            .comparing(ExpectedDocumentState::documentVersionId, UUID_TEXT_ORDER);
    private static final Comparator<ExpectedRequirementState> REQUIREMENT_STATE_ORDER = Comparator
            .comparing(ExpectedRequirementState::requirementVersionId, UUID_TEXT_ORDER);
    private static final Comparator<DocumentTransition> DOCUMENT_TRANSITION_ORDER = Comparator
            .comparing(DocumentTransition::documentVersionId, UUID_TEXT_ORDER)
            .thenComparingInt(transition -> transition.previousState().ordinal())
            .thenComparingInt(transition -> transition.newState().ordinal());
    private static final Comparator<RequirementTransition> REQUIREMENT_TRANSITION_ORDER = Comparator
            .comparing(RequirementTransition::requirementVersionId, UUID_TEXT_ORDER)
            .thenComparingInt(transition -> transition.previousState().ordinal())
            .thenComparingInt(transition -> transition.newState().ordinal());
    private static final Comparator<DocumentSlotKey> DOCUMENT_SLOT_KEY_ORDER = Comparator
            .comparing((DocumentSlotKey key) -> key.type().name())
            .thenComparing(key -> key.locale().getCodigo())
            .thenComparing(key -> key.context().name());
    private static final Comparator<ExpectedDocumentSlot> DOCUMENT_SLOT_ORDER = Comparator
            .comparing(ExpectedDocumentSlot::key, DOCUMENT_SLOT_KEY_ORDER);
    private static final Comparator<DocumentSlotDelete> DOCUMENT_SLOT_DELETE_ORDER = Comparator
            .comparing(DocumentSlotDelete::key, DOCUMENT_SLOT_KEY_ORDER);
    private static final Comparator<RequiredSetPointerKey> REQUIRED_SET_POINTER_KEY_ORDER =
            Comparator
                    .comparing((RequiredSetPointerKey key) -> key.locale().getCodigo())
                    .thenComparing(key -> key.context().name())
                    .thenComparing(key -> key.audience().name());
    private static final Comparator<ExpectedRequiredSetPointer> REQUIRED_SET_POINTER_ORDER =
            Comparator.comparing(
                    ExpectedRequiredSetPointer::key,
                    REQUIRED_SET_POINTER_KEY_ORDER);
    private static final Comparator<RequiredSetPointerDelete> REQUIRED_SET_POINTER_DELETE_ORDER =
            Comparator.comparing(
                    RequiredSetPointerDelete::key,
                    REQUIRED_SET_POINTER_KEY_ORDER);
    private static final Comparator<ReplacementBatch> HISTORICAL_REPLACEMENT_BATCH_ORDER =
            Comparator.comparing(ReplacementBatch::batchId, UUID_TEXT_ORDER);
    private static final Comparator<ReplacementBatch> CURRENT_REPLACEMENT_BATCH_ORDER = Comparator
            .comparing(
                    LegalEditorialExecutionPlan::minimumReplacementMemberId,
                    UUID_TEXT_ORDER)
            .thenComparing(ReplacementBatch::batchId, UUID_TEXT_ORDER);

    LegalEditorialExecutionPlan {
        operationType = Objects.requireNonNull(operationType, "operationType");
        source = Objects.requireNonNull(source, "source");
        target = Objects.requireNonNull(target, "target");
        operationId = Objects.requireNonNull(operationId, "operationId");
        planSha256 = Objects.requireNonNull(planSha256, "planSha256")
                .map(value -> requireSha256(value, "planSha256"));
        observedAt = requirePostgresInstant(observedAt, "observedAt");
        expectedAppliedAt = requirePostgresInstant(expectedAppliedAt, "expectedAppliedAt");
        if (expectedAppliedAt.isAfter(observedAt)) {
            throw new IllegalArgumentException(
                    "expectedAppliedAt no puede ser posterior a observedAt");
        }
        expectedReadinessAfter = Objects.requireNonNull(
                expectedReadinessAfter,
                "expectedReadinessAfter");
        expectedPostState = Objects.requireNonNull(expectedPostState, "expectedPostState");
        mutationCommands = Objects.requireNonNull(mutationCommands, "mutationCommands");

        requireOperationMatrix(
                operationType,
                source,
                target,
                operationId,
                planSha256,
                expectedReadinessAfter,
                acknowledgeFailClosedGap,
                expectedPostState,
                mutationCommands);
        requireExpectedStateConsistency(expectedPostState);
        requireExpectedDeltaTimeConsistency(
                operationType,
                expectedAppliedAt,
                expectedPostState);
        if (changeRequired) {
            if (!expectedAppliedAt.equals(observedAt)) {
                throw new IllegalArgumentException(
                        "Una mutación fresca requiere expectedAppliedAt igual a observedAt");
            }
            if (mutationCommands.isEmpty()) {
                throw new IllegalArgumentException(
                        "changeRequired=true requiere al menos un comando mutante");
            }
            requireCommandPlanConsistency(
                    operationType,
                    expectedPostState,
                    mutationCommands);
        } else if (!mutationCommands.isEmpty()) {
            throw new IllegalArgumentException(
                    "changeRequired=false requiere un conjunto de comandos vacío");
        }
    }

    DeltaCounts deltaCounts() {
        if (!changeRequired) {
            return DeltaCounts.ZERO;
        }
        V27TriggerEffects triggerEffects = expectedPostState.v27TriggerEffects();
        return new DeltaCounts(
                mutationCommands.documentTransitions().size(),
                triggerEffects.documentTransitions().size(),
                mutationCommands.requirementTransitions().size(),
                mutationCommands.documentSlotDeletes().size(),
                mutationCommands.documentSlotInserts().size(),
                triggerEffects.documentSlotDeletes().size(),
                triggerEffects.documentSlotInserts().size(),
                mutationCommands.requiredSetPointerDeletes().size(),
                mutationCommands.requiredSetPointerInserts().size(),
                mutationCommands.replacementBatchesToCreateAndSeal().size());
    }

    enum OperationType {
        PROMOTE,
        REPLACE,
        RETIRE
    }

    record PublicationIdentity(
            String publicationExternalId,
            UUID publicationUuid,
            String manifestSha256
    ) {

        PublicationIdentity {
            publicationExternalId = requirePublicationExternalId(publicationExternalId);
            publicationUuid = Objects.requireNonNull(publicationUuid, "publicationUuid");
            manifestSha256 = requireSha256(manifestSha256, "manifestSha256");
        }
    }

    record SourceIdentity(
            PublicationIdentity publication,
            String expectedEditorialStateFingerprint
    ) {

        SourceIdentity {
            publication = Objects.requireNonNull(publication, "publication");
            expectedEditorialStateFingerprint = requireFingerprint(
                    expectedEditorialStateFingerprint);
        }
    }

    record ExpectedPostState(
            List<ExpectedDocumentState> documentStates,
            List<ExpectedRequirementState> requirementStates,
            List<DocumentTransition> documentTransitions,
            List<RequirementTransition> requirementTransitions,
            List<DocumentTransition> preexistingDocumentTransitions,
            List<RequirementTransition> preexistingRequirementTransitions,
            List<ExpectedDocumentSlot> documentSlots,
            List<ExpectedRequiredSetPointer> requiredSetPointers,
            List<ReplacementBatch> preexistingReplacementBatches,
            List<ReplacementBatch> replacementBatches,
            V27TriggerEffects v27TriggerEffects
    ) {

        ExpectedPostState {
            documentStates = sortedCopy(
                    documentStates,
                    DOCUMENT_STATE_ORDER,
                    "documentStates");
            requirementStates = sortedCopy(
                    requirementStates,
                    REQUIREMENT_STATE_ORDER,
                    "requirementStates");
            documentTransitions = sortedCopy(
                    documentTransitions,
                    DOCUMENT_TRANSITION_ORDER,
                    "documentTransitions");
            requirementTransitions = sortedCopy(
                    requirementTransitions,
                    REQUIREMENT_TRANSITION_ORDER,
                    "requirementTransitions");
            preexistingDocumentTransitions = sortedCopy(
                    preexistingDocumentTransitions,
                    DOCUMENT_TRANSITION_ORDER,
                    "preexistingDocumentTransitions");
            preexistingRequirementTransitions = sortedCopy(
                    preexistingRequirementTransitions,
                    REQUIREMENT_TRANSITION_ORDER,
                    "preexistingRequirementTransitions");
            documentSlots = sortedCopy(
                    documentSlots,
                    DOCUMENT_SLOT_ORDER,
                    "documentSlots");
            requiredSetPointers = sortedCopy(
                    requiredSetPointers,
                    REQUIRED_SET_POINTER_ORDER,
                    "requiredSetPointers");
            preexistingReplacementBatches = sortedCopy(
                    preexistingReplacementBatches,
                    HISTORICAL_REPLACEMENT_BATCH_ORDER,
                    "preexistingReplacementBatches");
            replacementBatches = sortedCopy(
                    replacementBatches,
                    CURRENT_REPLACEMENT_BATCH_ORDER,
                    "replacementBatches");
            v27TriggerEffects = Objects.requireNonNull(
                    v27TriggerEffects,
                    "v27TriggerEffects");

            rejectDuplicateKeys(
                    documentStates,
                    ExpectedDocumentState::documentVersionId,
                    "documentStates contiene un UUID duplicado");
            rejectDuplicateKeys(
                    requirementStates,
                    ExpectedRequirementState::requirementVersionId,
                    "requirementStates contiene un UUID duplicado");
            rejectDuplicateKeys(
                    documentTransitions,
                    Function.identity(),
                    "documentTransitions contiene una transición duplicada");
            rejectDuplicateKeys(
                    requirementTransitions,
                    Function.identity(),
                    "requirementTransitions contiene una transición duplicada");
            rejectDuplicateKeys(
                    preexistingDocumentTransitions,
                    Function.identity(),
                    "preexistingDocumentTransitions contiene una transición duplicada");
            rejectDuplicateKeys(
                    preexistingRequirementTransitions,
                    Function.identity(),
                    "preexistingRequirementTransitions contiene una transición duplicada");
            rejectOverlap(
                    preexistingDocumentTransitions,
                    documentTransitions,
                    "Una transición documental no puede ser preexistente y parte del delta");
            rejectOverlap(
                    preexistingRequirementTransitions,
                    requirementTransitions,
                    "Una transición de requisito no puede ser preexistente y parte del delta");
            rejectDuplicateKeys(
                    documentSlots,
                    ExpectedDocumentSlot::key,
                    "documentSlots contiene una PK duplicada");
            rejectDuplicateKeys(
                    documentSlots,
                    slot -> new DocumentVersionContextKey(
                            slot.documentVersionId(),
                            slot.key().context()),
                    "documentSlots contiene una versión/contexto duplicada");
            rejectDuplicateKeys(
                    requiredSetPointers,
                    ExpectedRequiredSetPointer::key,
                    "requiredSetPointers contiene una PK duplicada");
            rejectDuplicateKeys(
                    preexistingReplacementBatches,
                    ReplacementBatch::batchId,
                    "preexistingReplacementBatches contiene un UUID duplicado");
            rejectDuplicateKeys(
                    replacementBatches,
                    ReplacementBatch::batchId,
                    "replacementBatches contiene un UUID duplicado");
            Set<UUID> preexistingBatchIds = preexistingReplacementBatches.stream()
                    .map(ReplacementBatch::batchId)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            if (replacementBatches.stream()
                    .map(ReplacementBatch::batchId)
                    .anyMatch(preexistingBatchIds::contains)) {
                throw new IllegalArgumentException(
                        "Un lote no puede ser preexistente y parte del delta");
            }
            rejectHistoricalReplacementRoleReuse(preexistingReplacementBatches);
            rejectOverlappingReplacementMembers(replacementBatches);
        }

        ExpectedPostState(
                List<ExpectedDocumentState> documentStates,
                List<ExpectedRequirementState> requirementStates,
                List<DocumentTransition> documentTransitions,
                List<RequirementTransition> requirementTransitions,
                List<DocumentTransition> preexistingDocumentTransitions,
                List<RequirementTransition> preexistingRequirementTransitions,
                List<ExpectedDocumentSlot> documentSlots,
                List<ExpectedRequiredSetPointer> requiredSetPointers,
                List<ReplacementBatch> replacementBatches,
                V27TriggerEffects v27TriggerEffects) {
            this(
                    documentStates,
                    requirementStates,
                    documentTransitions,
                    requirementTransitions,
                    preexistingDocumentTransitions,
                    preexistingRequirementTransitions,
                    documentSlots,
                    requiredSetPointers,
                    List.of(),
                    replacementBatches,
                    v27TriggerEffects);
        }

        static ExpectedPostState empty() {
            return new ExpectedPostState(
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    V27TriggerEffects.empty());
        }
    }

    record V27TriggerEffects(
            List<DocumentTransition> documentTransitions,
            List<DocumentSlotDelete> documentSlotDeletes,
            List<ExpectedDocumentSlot> documentSlotInserts
    ) {

        V27TriggerEffects {
            documentTransitions = sortedCopy(
                    documentTransitions,
                    DOCUMENT_TRANSITION_ORDER,
                    "v27.documentTransitions");
            documentSlotDeletes = sortedCopy(
                    documentSlotDeletes,
                    DOCUMENT_SLOT_DELETE_ORDER,
                    "v27.documentSlotDeletes");
            documentSlotInserts = sortedCopy(
                    documentSlotInserts,
                    DOCUMENT_SLOT_ORDER,
                    "v27.documentSlotInserts");
            rejectDuplicateKeys(
                    documentTransitions,
                    Function.identity(),
                    "v27.documentTransitions contiene una transición duplicada");
            rejectDuplicateKeys(
                    documentSlotDeletes,
                    DocumentSlotDelete::key,
                    "v27.documentSlotDeletes contiene una PK duplicada");
            rejectDuplicateKeys(
                    documentSlotInserts,
                    ExpectedDocumentSlot::key,
                    "v27.documentSlotInserts contiene una PK duplicada");
            for (DocumentTransition transition : documentTransitions) {
                if (transition.replacementBatchId() == null
                        || !isReplacementTriggerEdge(transition)) {
                    throw new IllegalArgumentException(
                            "V27 sólo deriva transiciones documentales ligadas a un lote");
                }
            }
        }

        static V27TriggerEffects empty() {
            return new V27TriggerEffects(List.of(), List.of(), List.of());
        }

        boolean isEmpty() {
            return documentTransitions.isEmpty()
                    && documentSlotDeletes.isEmpty()
                    && documentSlotInserts.isEmpty();
        }
    }

    record MutationCommands(
            List<DocumentTransition> documentTransitions,
            List<RequirementTransition> requirementTransitions,
            List<DocumentSlotDelete> documentSlotDeletes,
            List<ExpectedDocumentSlot> documentSlotInserts,
            List<RequiredSetPointerDelete> requiredSetPointerDeletes,
            List<ExpectedRequiredSetPointer> requiredSetPointerInserts,
            List<ReplacementBatch> replacementBatchesToCreateAndSeal
    ) {

        MutationCommands {
            documentTransitions = sortedCopy(
                    documentTransitions,
                    DOCUMENT_TRANSITION_ORDER,
                    "commands.documentTransitions");
            requirementTransitions = sortedCopy(
                    requirementTransitions,
                    REQUIREMENT_TRANSITION_ORDER,
                    "commands.requirementTransitions");
            documentSlotDeletes = sortedCopy(
                    documentSlotDeletes,
                    DOCUMENT_SLOT_DELETE_ORDER,
                    "commands.documentSlotDeletes");
            documentSlotInserts = sortedCopy(
                    documentSlotInserts,
                    DOCUMENT_SLOT_ORDER,
                    "commands.documentSlotInserts");
            requiredSetPointerDeletes = sortedCopy(
                    requiredSetPointerDeletes,
                    REQUIRED_SET_POINTER_DELETE_ORDER,
                    "commands.requiredSetPointerDeletes");
            requiredSetPointerInserts = sortedCopy(
                    requiredSetPointerInserts,
                    REQUIRED_SET_POINTER_ORDER,
                    "commands.requiredSetPointerInserts");
            replacementBatchesToCreateAndSeal = sortedCopy(
                    replacementBatchesToCreateAndSeal,
                    CURRENT_REPLACEMENT_BATCH_ORDER,
                    "commands.replacementBatchesToCreateAndSeal");

            rejectDuplicateKeys(
                    documentTransitions,
                    Function.identity(),
                    "commands.documentTransitions contiene una transición duplicada");
            rejectDuplicateKeys(
                    requirementTransitions,
                    Function.identity(),
                    "commands.requirementTransitions contiene una transición duplicada");
            rejectDuplicateKeys(
                    documentSlotDeletes,
                    DocumentSlotDelete::key,
                    "commands.documentSlotDeletes contiene una PK duplicada");
            rejectDuplicateKeys(
                    documentSlotInserts,
                    ExpectedDocumentSlot::key,
                    "commands.documentSlotInserts contiene una PK duplicada");
            rejectDuplicateKeys(
                    requiredSetPointerDeletes,
                    RequiredSetPointerDelete::key,
                    "commands.requiredSetPointerDeletes contiene una PK duplicada");
            rejectDuplicateKeys(
                    requiredSetPointerInserts,
                    ExpectedRequiredSetPointer::key,
                    "commands.requiredSetPointerInserts contiene una PK duplicada");
            rejectDuplicateKeys(
                    replacementBatchesToCreateAndSeal,
                    ReplacementBatch::batchId,
                    "commands.replacementBatches contiene un UUID duplicado");
            rejectOverlappingReplacementMembers(replacementBatchesToCreateAndSeal);

            for (DocumentTransition transition : documentTransitions) {
                if (transition.replacementBatchId() != null
                        || transition.newState() == EstadoVersionLegal.REEMPLAZADA) {
                    throw new IllegalArgumentException(
                            "Una transición derivada por V27 no puede ser un comando directo");
                }
            }
        }

        static MutationCommands empty() {
            return new MutationCommands(
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of());
        }

        boolean isEmpty() {
            return documentTransitions.isEmpty()
                    && requirementTransitions.isEmpty()
                    && documentSlotDeletes.isEmpty()
                    && documentSlotInserts.isEmpty()
                    && requiredSetPointerDeletes.isEmpty()
                    && requiredSetPointerInserts.isEmpty()
                    && replacementBatchesToCreateAndSeal.isEmpty();
        }
    }

    record ExpectedDocumentState(
            UUID documentVersionId,
            EstadoVersionLegal state,
            Instant stateChangedAt,
            String lastReason,
            UUID replacementBatchId
    ) {

        ExpectedDocumentState {
            documentVersionId = Objects.requireNonNull(
                    documentVersionId,
                    "documentVersionId");
            state = Objects.requireNonNull(state, "state");
            stateChangedAt = optionalPostgresInstant(stateChangedAt, "stateChangedAt");
            lastReason = optionalReason(lastReason);
            requireDocumentStateMatrix(state, stateChangedAt, lastReason, replacementBatchId);
        }
    }

    record ExpectedRequirementState(
            UUID requirementVersionId,
            EstadoVersionLegal state,
            Instant stateChangedAt,
            String lastReason
    ) {

        ExpectedRequirementState {
            requirementVersionId = Objects.requireNonNull(
                    requirementVersionId,
                    "requirementVersionId");
            state = Objects.requireNonNull(state, "state");
            stateChangedAt = optionalPostgresInstant(stateChangedAt, "stateChangedAt");
            lastReason = optionalReason(lastReason);
            requireRequirementStateMatrix(state, stateChangedAt, lastReason);
        }
    }

    record DocumentTransition(
            UUID documentVersionId,
            EstadoVersionLegal previousState,
            EstadoVersionLegal newState,
            String reason,
            UUID replacementBatchId,
            Instant occurredAt
    ) {

        DocumentTransition {
            documentVersionId = Objects.requireNonNull(
                    documentVersionId,
                    "documentVersionId");
            previousState = Objects.requireNonNull(previousState, "previousState");
            newState = Objects.requireNonNull(newState, "newState");
            reason = optionalReason(reason);
            occurredAt = requirePostgresInstant(occurredAt, "occurredAt");
            requireTransitionEdge(previousState, newState, "documentTransition");
            boolean validMetadata = switch (newState) {
                case PUBLICADA -> reason == null && replacementBatchId == null;
                case VIGENTE -> reason == null;
                case REEMPLAZADA -> reason == null && replacementBatchId != null;
                case RETIRADA -> reason != null && replacementBatchId == null;
                case BORRADOR -> false;
            };
            if (!validMetadata) {
                throw new IllegalArgumentException(
                        "metadata incompatible con la transición documental");
            }
        }
    }

    record RequirementTransition(
            UUID requirementVersionId,
            EstadoVersionLegal previousState,
            EstadoVersionLegal newState,
            String reason,
            Instant occurredAt
    ) {

        RequirementTransition {
            requirementVersionId = Objects.requireNonNull(
                    requirementVersionId,
                    "requirementVersionId");
            previousState = Objects.requireNonNull(previousState, "previousState");
            newState = Objects.requireNonNull(newState, "newState");
            reason = optionalReason(reason);
            occurredAt = requirePostgresInstant(occurredAt, "occurredAt");
            requireTransitionEdge(previousState, newState, "requirementTransition");
            boolean validMetadata = switch (newState) {
                case PUBLICADA, VIGENTE, REEMPLAZADA -> reason == null;
                case RETIRADA -> reason != null;
                case BORRADOR -> false;
            };
            if (!validMetadata) {
                throw new IllegalArgumentException(
                        "metadata incompatible con la transición de requisito");
            }
        }
    }

    record DocumentSlotKey(
            TipoDocumentoLegal type,
            LocaleLegal locale,
            ContextoLegal context
    ) {

        DocumentSlotKey {
            type = Objects.requireNonNull(type, "type");
            locale = Objects.requireNonNull(locale, "locale");
            context = Objects.requireNonNull(context, "context");
        }
    }

    record ExpectedDocumentSlot(
            DocumentSlotKey key,
            UUID documentVersionId,
            UUID documentLineId,
            UUID publicationId
    ) {

        ExpectedDocumentSlot {
            key = Objects.requireNonNull(key, "key");
            documentVersionId = Objects.requireNonNull(
                    documentVersionId,
                    "documentVersionId");
            documentLineId = Objects.requireNonNull(documentLineId, "documentLineId");
            publicationId = Objects.requireNonNull(publicationId, "publicationId");
        }
    }

    record DocumentSlotDelete(
            DocumentSlotKey key,
            UUID expectedDocumentVersionId
    ) {

        DocumentSlotDelete {
            key = Objects.requireNonNull(key, "key");
            expectedDocumentVersionId = Objects.requireNonNull(
                    expectedDocumentVersionId,
                    "expectedDocumentVersionId");
        }
    }

    record RequiredSetPointerKey(
            LocaleLegal locale,
            ContextoLegal context,
            AudienciaLegal audience
    ) {

        RequiredSetPointerKey {
            locale = Objects.requireNonNull(locale, "locale");
            context = Objects.requireNonNull(context, "context");
            audience = Objects.requireNonNull(audience, "audience");
        }
    }

    record ExpectedRequiredSetPointer(
            RequiredSetPointerKey key,
            UUID requiredSetId,
            UUID publicationId,
            String requiredSetRevision,
            Instant updatedAt,
            Optional<RequiredSetDependencies> dependenciesEvidence
    ) {

        ExpectedRequiredSetPointer {
            key = Objects.requireNonNull(key, "key");
            requiredSetId = Objects.requireNonNull(requiredSetId, "requiredSetId");
            publicationId = Objects.requireNonNull(publicationId, "publicationId");
            requiredSetRevision = requireFingerprintValue(
                    requiredSetRevision,
                    "requiredSetRevision");
            updatedAt = requirePostgresInstant(updatedAt, "updatedAt");
            dependenciesEvidence = Objects.requireNonNull(
                    dependenciesEvidence,
                    "dependenciesEvidence");
        }

        ExpectedRequiredSetPointer(
                RequiredSetPointerKey key,
                UUID requiredSetId,
                UUID publicationId,
                String requiredSetRevision,
                Instant updatedAt) {
            this(
                    key,
                    requiredSetId,
                    publicationId,
                    requiredSetRevision,
                    updatedAt,
                    Optional.empty());
        }
    }

    record RequiredSetPointerDelete(
            RequiredSetPointerKey key,
            UUID expectedRequiredSetId,
            Optional<RequiredSetDependencies> dependenciesEvidence
    ) {

        RequiredSetPointerDelete {
            key = Objects.requireNonNull(key, "key");
            expectedRequiredSetId = Objects.requireNonNull(
                    expectedRequiredSetId,
                    "expectedRequiredSetId");
            dependenciesEvidence = Objects.requireNonNull(
                    dependenciesEvidence,
                    "dependenciesEvidence");
        }

        RequiredSetPointerDelete(
                RequiredSetPointerKey key,
                UUID expectedRequiredSetId) {
            this(key, expectedRequiredSetId, Optional.empty());
        }
    }

    record RequiredSetDependencies(
            List<UUID> memberRequirementVersionIds,
            List<UUID> referencedDocumentVersionIds
    ) {

        RequiredSetDependencies {
            memberRequirementVersionIds = sortedDistinctCopy(
                    memberRequirementVersionIds,
                    UUID_TEXT_ORDER,
                    "memberRequirementVersionIds");
            referencedDocumentVersionIds = sortedDistinctCopy(
                    referencedDocumentVersionIds,
                    UUID_TEXT_ORDER,
                    "referencedDocumentVersionIds");
        }
    }

    record ReplacementBatch(
            UUID batchId,
            Instant createdAt,
            Instant sealedAt,
            List<UUID> predecessorDocumentVersionIds,
            List<ReplacementSuccessor> successors
    ) {

        private static final Comparator<ReplacementSuccessor> SUCCESSOR_ORDER = Comparator
                .comparing(ReplacementSuccessor::documentVersionId, UUID_TEXT_ORDER)
                .thenComparing(ReplacementSuccessor::publicationId, UUID_TEXT_ORDER);

        ReplacementBatch {
            batchId = Objects.requireNonNull(batchId, "batchId");
            createdAt = requirePostgresInstant(createdAt, "createdAt");
            sealedAt = requirePostgresInstant(sealedAt, "sealedAt");
            if (sealedAt.isBefore(createdAt)) {
                throw new IllegalArgumentException(
                        "El sello del lote no puede preceder a su creación");
            }
            predecessorDocumentVersionIds = sortedCopy(
                    predecessorDocumentVersionIds,
                    UUID_TEXT_ORDER,
                    "predecessorDocumentVersionIds");
            successors = sortedCopy(successors, SUCCESSOR_ORDER, "successors");
            if (predecessorDocumentVersionIds.isEmpty() || successors.isEmpty()) {
                throw new IllegalArgumentException(
                        "Un lote de reemplazo requiere predecesores y sucesoras");
            }
            rejectDuplicateKeys(
                    predecessorDocumentVersionIds,
                    Function.identity(),
                    "predecessorDocumentVersionIds contiene un UUID duplicado");
            rejectDuplicateKeys(
                    successors,
                    ReplacementSuccessor::documentVersionId,
                    "successors contiene un UUID documental duplicado");
            Set<UUID> predecessors = Set.copyOf(predecessorDocumentVersionIds);
            if (successors.stream().anyMatch(successor ->
                    predecessors.contains(successor.documentVersionId()))) {
                throw new IllegalArgumentException(
                        "Un lote de reemplazo no admite autociclos");
            }
        }
    }

    record ReplacementSuccessor(UUID documentVersionId, UUID publicationId) {

        ReplacementSuccessor {
            documentVersionId = Objects.requireNonNull(
                    documentVersionId,
                    "documentVersionId");
            publicationId = Objects.requireNonNull(publicationId, "publicationId");
        }
    }

    record DeltaCounts(
            int directDocumentTransitions,
            int triggerDerivedDocumentTransitions,
            int directRequirementTransitions,
            int directDocumentSlotDeletes,
            int directDocumentSlotInserts,
            int triggerDerivedDocumentSlotDeletes,
            int triggerDerivedDocumentSlotInserts,
            int requiredSetPointerDeletes,
            int requiredSetPointerInserts,
            int replacementBatches
    ) {

        static final DeltaCounts ZERO = new DeltaCounts(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

        DeltaCounts {
            if (directDocumentTransitions < 0
                    || triggerDerivedDocumentTransitions < 0
                    || directRequirementTransitions < 0
                    || directDocumentSlotDeletes < 0
                    || directDocumentSlotInserts < 0
                    || triggerDerivedDocumentSlotDeletes < 0
                    || triggerDerivedDocumentSlotInserts < 0
                    || requiredSetPointerDeletes < 0
                    || requiredSetPointerInserts < 0
                    || replacementBatches < 0) {
                throw new IllegalArgumentException("Los conteos del delta no pueden ser negativos");
            }
        }

        boolean isZero() {
            return equals(ZERO);
        }
    }

    private static void requireOperationMatrix(
            OperationType operationType,
            Optional<SourceIdentity> source,
            PublicationIdentity target,
            Optional<UUID> operationId,
            Optional<String> planSha256,
            LegalEditorialReadiness expectedReadinessAfter,
            boolean acknowledgeFailClosedGap,
            ExpectedPostState expectedPostState,
            MutationCommands mutationCommands) {
        if (expectedReadinessAfter == LegalEditorialReadiness.ERROR) {
            throw new IllegalArgumentException("ERROR nunca es un readiness posterior esperado");
        }
        switch (operationType) {
            case PROMOTE -> {
                if (source.isPresent()
                        || operationId.isPresent()
                        || planSha256.isPresent()
                        || expectedReadinessAfter != LegalEditorialReadiness.READY
                        || acknowledgeFailClosedGap
                        || !expectedPostState.preexistingDocumentTransitions().isEmpty()
                        || !expectedPostState.preexistingRequirementTransitions().isEmpty()
                        || !expectedPostState.preexistingReplacementBatches().isEmpty()
                        || !expectedPostState.replacementBatches().isEmpty()
                        || !expectedPostState.v27TriggerEffects().isEmpty()
                        || !mutationCommands.replacementBatchesToCreateAndSeal().isEmpty()
                        || !mutationCommands.documentSlotDeletes().isEmpty()
                        || !mutationCommands.requiredSetPointerDeletes().isEmpty()) {
                    throw invalidOperationMatrix();
                }
                requirePromotionPostState(expectedPostState);
            }
            case REPLACE -> {
                if (source.isEmpty()
                        || operationId.isEmpty()
                        || planSha256.isEmpty()
                        || expectedReadinessAfter != LegalEditorialReadiness.READY
                        || acknowledgeFailClosedGap
                        || samePublication(source.orElseThrow().publication(), target)) {
                    throw invalidOperationMatrix();
                }
                PublicationIdentity current = source.orElseThrow().publication();
                if (current.publicationExternalId().equals(target.publicationExternalId())
                        || current.publicationUuid().equals(target.publicationUuid())) {
                    throw invalidOperationMatrix();
                }
            }
            case RETIRE -> {
                boolean containsRetirement = expectedPostState.documentTransitions().stream()
                        .anyMatch(transition ->
                                transition.newState() == EstadoVersionLegal.RETIRADA)
                        || expectedPostState.requirementTransitions().stream()
                        .anyMatch(transition ->
                                transition.newState() == EstadoVersionLegal.RETIRADA);
                if (source.isEmpty()
                        || operationId.isEmpty()
                        || planSha256.isEmpty()
                        || expectedReadinessAfter != LegalEditorialReadiness.NOT_READY
                        || !acknowledgeFailClosedGap
                        || !samePublication(source.orElseThrow().publication(), target)
                        || !containsRetirement
                        || !expectedPostState.replacementBatches().isEmpty()
                        || !expectedPostState.v27TriggerEffects().isEmpty()
                        || !mutationCommands.documentSlotInserts().isEmpty()
                        || !mutationCommands.requiredSetPointerInserts().isEmpty()
                        || !mutationCommands.replacementBatchesToCreateAndSeal().isEmpty()
                        || expectedPostState.documentTransitions().stream().anyMatch(transition ->
                                transition.newState() != EstadoVersionLegal.RETIRADA)
                        || expectedPostState.requirementTransitions().stream().anyMatch(transition ->
                                transition.newState() != EstadoVersionLegal.RETIRADA)) {
                    throw invalidOperationMatrix();
                }
                requireRetirementPointerClassification(
                        expectedPostState,
                        mutationCommands);
            }
            default -> throw invalidOperationMatrix();
        }
    }

    private static void requirePromotionPostState(ExpectedPostState postState) {
        for (ExpectedDocumentState state : postState.documentStates()) {
            if (state.state() != EstadoVersionLegal.VIGENTE) {
                throw invalidOperationMatrix();
            }
            List<DocumentTransition> transitions = postState.documentTransitions().stream()
                    .filter(transition -> transition.documentVersionId()
                            .equals(state.documentVersionId()))
                    .toList();
            if (transitions.size() != 2
                    || transitions.get(0).previousState() != EstadoVersionLegal.BORRADOR
                    || transitions.get(0).newState() != EstadoVersionLegal.PUBLICADA
                    || transitions.get(1).previousState() != EstadoVersionLegal.PUBLICADA
                    || transitions.get(1).newState() != EstadoVersionLegal.VIGENTE) {
                throw invalidOperationMatrix();
            }
        }
        for (ExpectedRequirementState state : postState.requirementStates()) {
            if (state.state() != EstadoVersionLegal.VIGENTE) {
                throw invalidOperationMatrix();
            }
            List<RequirementTransition> transitions = postState.requirementTransitions().stream()
                    .filter(transition -> transition.requirementVersionId()
                            .equals(state.requirementVersionId()))
                    .toList();
            if (transitions.size() != 2
                    || transitions.get(0).previousState() != EstadoVersionLegal.BORRADOR
                    || transitions.get(0).newState() != EstadoVersionLegal.PUBLICADA
                    || transitions.get(1).previousState() != EstadoVersionLegal.PUBLICADA
                    || transitions.get(1).newState() != EstadoVersionLegal.VIGENTE) {
                throw invalidOperationMatrix();
            }
        }
    }

    private static void requireExpectedStateConsistency(ExpectedPostState postState) {
        requireDocumentStateConsistency(
                postState.documentStates(),
                completeDocumentHistory(postState));
        requireRequirementStateConsistency(
                postState.requirementStates(),
                completeRequirementHistory(postState));

        Set<DocumentTransition> allDocumentTransitions = Set.copyOf(
                postState.documentTransitions());
        for (DocumentTransition derived : postState.v27TriggerEffects().documentTransitions()) {
            if (!allDocumentTransitions.contains(derived)) {
                throw new IllegalArgumentException(
                        "Una transición derivada debe pertenecer al postestado esperado");
            }
        }

        Set<ExpectedDocumentSlot> finalSlots = Set.copyOf(postState.documentSlots());
        for (ExpectedDocumentSlot inserted : postState.v27TriggerEffects().documentSlotInserts()) {
            if (!finalSlots.contains(inserted)) {
                throw new IllegalArgumentException(
                        "Un slot derivado insertado debe pertenecer al postestado esperado");
            }
        }

        Set<UUID> batchIds = new HashSet<>();
        Set<UUID> predecessorIds = new HashSet<>();
        Set<ReplacementSuccessor> successors = new HashSet<>();
        Set<DocumentTransition> exactTriggerTransitions = new HashSet<>();
        for (ReplacementBatch batch : postState.replacementBatches()) {
            batchIds.add(batch.batchId());
            predecessorIds.addAll(batch.predecessorDocumentVersionIds());
            successors.addAll(batch.successors());
            for (UUID predecessorId : batch.predecessorDocumentVersionIds()) {
                exactTriggerTransitions.add(new DocumentTransition(
                        predecessorId,
                        EstadoVersionLegal.VIGENTE,
                        EstadoVersionLegal.REEMPLAZADA,
                        null,
                        batch.batchId(),
                        batch.sealedAt()));
            }
            for (ReplacementSuccessor successor : batch.successors()) {
                exactTriggerTransitions.add(new DocumentTransition(
                        successor.documentVersionId(),
                        EstadoVersionLegal.PUBLICADA,
                        EstadoVersionLegal.VIGENTE,
                        null,
                        batch.batchId(),
                        batch.sealedAt()));
            }
        }
        if (!exactTriggerTransitions.equals(Set.copyOf(
                postState.v27TriggerEffects().documentTransitions()))) {
            throw new IllegalArgumentException(
                    "Las transiciones derivadas no coinciden exactamente con los lotes V27");
        }
        for (DocumentTransition transition :
                postState.v27TriggerEffects().documentTransitions()) {
            if (!batchIds.contains(transition.replacementBatchId())) {
                throw new IllegalArgumentException(
                        "Una transición V27 referencia un lote ajeno al postestado");
            }
            if (transition.newState() == EstadoVersionLegal.REEMPLAZADA
                    && !predecessorIds.contains(transition.documentVersionId())) {
                throw new IllegalArgumentException(
                        "La predecesora derivada no pertenece al lote esperado");
            }
            if (transition.newState() == EstadoVersionLegal.VIGENTE
                    && successors.stream().noneMatch(successor -> successor.documentVersionId()
                            .equals(transition.documentVersionId()))) {
                throw new IllegalArgumentException(
                        "La sucesora derivada no pertenece al lote esperado");
            }
        }
        for (DocumentSlotDelete deleted :
                postState.v27TriggerEffects().documentSlotDeletes()) {
            if (!predecessorIds.contains(deleted.expectedDocumentVersionId())) {
                throw new IllegalArgumentException(
                        "El slot derivado eliminado no pertenece a una predecesora");
            }
        }
        for (ExpectedDocumentSlot inserted :
                postState.v27TriggerEffects().documentSlotInserts()) {
            boolean belongsToSuccessor = successors.stream().anyMatch(successor ->
                    successor.documentVersionId().equals(inserted.documentVersionId())
                            && successor.publicationId().equals(inserted.publicationId()));
            if (!belongsToSuccessor) {
                throw new IllegalArgumentException(
                        "El slot derivado insertado no pertenece a una sucesora");
            }
        }

        Map<UUID, ExpectedDocumentState> documentStateById = indexBy(
                postState.documentStates(),
                ExpectedDocumentState::documentVersionId);
        for (ExpectedDocumentSlot slot : postState.documentSlots()) {
            ExpectedDocumentState state = documentStateById.get(slot.documentVersionId());
            if (state == null || state.state() != EstadoVersionLegal.VIGENTE) {
                throw new IllegalArgumentException(
                        "Todo slot esperado requiere una versión documental VIGENTE");
            }
        }
    }

    private static void requireCommandPlanConsistency(
            OperationType operationType,
            ExpectedPostState postState,
            MutationCommands commands) {
        Set<DocumentTransition> derivedTransitions = Set.copyOf(
                postState.v27TriggerEffects().documentTransitions());
        List<DocumentTransition> expectedDirectTransitions = postState.documentTransitions().stream()
                .filter(transition -> !derivedTransitions.contains(transition))
                .toList();
        if (!commands.documentTransitions().equals(expectedDirectTransitions)
                || !commands.requirementTransitions().equals(postState.requirementTransitions())) {
            throw new IllegalArgumentException(
                    "Los comandos directos no particionan las transiciones esperadas");
        }

        if (operationType == OperationType.RETIRE) {
            requireRetirementProjectionCommands(postState, commands);
        } else {
            Set<ExpectedDocumentSlot> derivedSlotInserts = Set.copyOf(
                    postState.v27TriggerEffects().documentSlotInserts());
            List<ExpectedDocumentSlot> expectedDirectSlotInserts = postState.documentSlots().stream()
                    .filter(slot -> !derivedSlotInserts.contains(slot))
                    .toList();
            if (!commands.documentSlotInserts().equals(expectedDirectSlotInserts)
                    || !commands.requiredSetPointerInserts()
                            .equals(postState.requiredSetPointers())
                    || !commands.replacementBatchesToCreateAndSeal()
                            .equals(postState.replacementBatches())) {
                throw new IllegalArgumentException(
                        "Los comandos no producen el postestado esperado exacto");
            }
        }
        Set<DocumentSlotKey> triggerDeletedKeys = postState.v27TriggerEffects()
                .documentSlotDeletes().stream()
                .map(DocumentSlotDelete::key)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (commands.documentSlotDeletes().stream()
                .map(DocumentSlotDelete::key)
                .anyMatch(triggerDeletedKeys::contains)) {
            throw new IllegalArgumentException(
                    "Un slot derivado por V27 no puede eliminarse también de forma directa");
        }
    }

    private static void requireRetirementProjectionCommands(
            ExpectedPostState postState,
            MutationCommands commands) {
        if (!commands.documentSlotInserts().isEmpty()
                || !commands.requiredSetPointerInserts().isEmpty()
                || !commands.replacementBatchesToCreateAndSeal().isEmpty()) {
            throw new IllegalArgumentException(
                    "RETIRE no admite inserts de proyecciones ni lotes nuevos");
        }

        Set<DocumentSlotKey> finalSlotKeys = postState.documentSlots().stream()
                .map(ExpectedDocumentSlot::key)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (commands.documentSlotDeletes().stream()
                .map(DocumentSlotDelete::key)
                .anyMatch(finalSlotKeys::contains)) {
            throw new IllegalArgumentException(
                    "Un slot eliminado no puede permanecer en el postestado RETIRE");
        }

        Set<RequiredSetPointerKey> finalPointerKeys = postState.requiredSetPointers().stream()
                .map(ExpectedRequiredSetPointer::key)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (commands.requiredSetPointerDeletes().stream()
                .map(RequiredSetPointerDelete::key)
                .anyMatch(finalPointerKeys::contains)) {
            throw new IllegalArgumentException(
                    "Un puntero eliminado no puede permanecer en el postestado RETIRE");
        }

        Set<UUID> retiredDocumentIds = postState.documentTransitions().stream()
                .filter(transition -> transition.newState() == EstadoVersionLegal.RETIRADA)
                .map(DocumentTransition::documentVersionId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (commands.documentSlotDeletes().stream()
                .map(DocumentSlotDelete::expectedDocumentVersionId)
                .anyMatch(versionId -> !retiredDocumentIds.contains(versionId))) {
            throw new IllegalArgumentException(
                    "Todo slot eliminado por RETIRE debe pertenecer a un documento retirado");
        }
    }

    private static void requireRetirementPointerClassification(
            ExpectedPostState postState,
            MutationCommands commands) {
        Set<UUID> retiredDocumentIds = postState.documentTransitions().stream()
                .filter(transition -> transition.newState() == EstadoVersionLegal.RETIRADA)
                .map(DocumentTransition::documentVersionId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<UUID> retiredRequirementIds = postState.requirementTransitions().stream()
                .filter(transition -> transition.newState() == EstadoVersionLegal.RETIRADA)
                .map(RequirementTransition::requirementVersionId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        for (ExpectedRequiredSetPointer pointer : postState.requiredSetPointers()) {
            RequiredSetDependencies dependencies = pointer.dependenciesEvidence()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Todo puntero preservado por RETIRE requiere evidencia autoritativa"));
            if (referencesAny(dependencies, retiredDocumentIds, retiredRequirementIds)) {
                throw new IllegalArgumentException(
                        "Un puntero preservado no puede depender del retiro explícito");
            }
        }
        for (RequiredSetPointerDelete pointer : commands.requiredSetPointerDeletes()) {
            RequiredSetDependencies dependencies = pointer.dependenciesEvidence()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Todo puntero eliminado por RETIRE requiere evidencia autoritativa"));
            if (!referencesAny(dependencies, retiredDocumentIds, retiredRequirementIds)) {
                throw new IllegalArgumentException(
                        "Todo puntero eliminado debe corresponder al retiro explícito");
            }
        }
    }

    private static boolean referencesAny(
            RequiredSetDependencies dependencies,
            Set<UUID> retiredDocumentIds,
            Set<UUID> retiredRequirementIds) {
        return dependencies.memberRequirementVersionIds().stream()
                .anyMatch(retiredRequirementIds::contains)
                || dependencies.referencedDocumentVersionIds().stream()
                .anyMatch(retiredDocumentIds::contains);
    }

    private static void requireExpectedDeltaTimeConsistency(
            OperationType operationType,
            Instant expectedAppliedAt,
            ExpectedPostState postState) {
        requireAllAtExpectedAppliedAt(
                expectedAppliedAt,
                postState.documentTransitions().stream()
                        .map(DocumentTransition::occurredAt)
                        .toList(),
                "documentTransitions");
        requireAllAtExpectedAppliedAt(
                expectedAppliedAt,
                postState.requirementTransitions().stream()
                        .map(RequirementTransition::occurredAt)
                        .toList(),
                "requirementTransitions");
        if (operationType != OperationType.RETIRE) {
            requireAllAtExpectedAppliedAt(
                    expectedAppliedAt,
                    postState.requiredSetPointers().stream()
                            .map(ExpectedRequiredSetPointer::updatedAt)
                            .toList(),
                    "requiredSetPointers");
        }
        for (ReplacementBatch batch : postState.replacementBatches()) {
            if (!batch.createdAt().equals(expectedAppliedAt)
                    || !batch.sealedAt().equals(expectedAppliedAt)) {
                throw new IllegalArgumentException(
                        "Los lotes esperados deben usar expectedAppliedAt");
            }
        }
    }

    private static List<DocumentTransition> completeDocumentHistory(
            ExpectedPostState postState) {
        List<DocumentTransition> history = new ArrayList<>(
                postState.preexistingDocumentTransitions());
        history.addAll(postState.documentTransitions());
        history.sort(DOCUMENT_TRANSITION_ORDER);
        return List.copyOf(history);
    }

    private static List<RequirementTransition> completeRequirementHistory(
            ExpectedPostState postState) {
        List<RequirementTransition> history = new ArrayList<>(
                postState.preexistingRequirementTransitions());
        history.addAll(postState.requirementTransitions());
        history.sort(REQUIREMENT_TRANSITION_ORDER);
        return List.copyOf(history);
    }

    private static void requireDocumentStateConsistency(
            List<ExpectedDocumentState> states,
            List<DocumentTransition> transitions) {
        Map<UUID, ExpectedDocumentState> stateById = indexBy(
                states,
                ExpectedDocumentState::documentVersionId);
        Map<UUID, List<DocumentTransition>> transitionsById = new HashMap<>();
        for (DocumentTransition transition : transitions) {
            if (!stateById.containsKey(transition.documentVersionId())) {
                throw new IllegalArgumentException(
                        "Toda transición documental requiere un estado final esperado");
            }
            transitionsById.computeIfAbsent(
                    transition.documentVersionId(),
                    ignored -> new ArrayList<>()).add(transition);
        }
        for (ExpectedDocumentState state : states) {
            List<DocumentTransition> chain = transitionsById.getOrDefault(
                    state.documentVersionId(),
                    List.of());
            if (chain.isEmpty()) {
                if (state.state() != EstadoVersionLegal.BORRADOR) {
                    throw new IllegalArgumentException(
                            "Todo estado documental no borrador requiere su historia completa");
                }
            } else {
                requireDocumentTransitionChain(state, chain);
            }
        }
    }

    private static void requireRequirementStateConsistency(
            List<ExpectedRequirementState> states,
            List<RequirementTransition> transitions) {
        Map<UUID, ExpectedRequirementState> stateById = indexBy(
                states,
                ExpectedRequirementState::requirementVersionId);
        Map<UUID, List<RequirementTransition>> transitionsById = new HashMap<>();
        for (RequirementTransition transition : transitions) {
            if (!stateById.containsKey(transition.requirementVersionId())) {
                throw new IllegalArgumentException(
                        "Toda transición de requisito requiere un estado final esperado");
            }
            transitionsById.computeIfAbsent(
                    transition.requirementVersionId(),
                    ignored -> new ArrayList<>()).add(transition);
        }
        for (ExpectedRequirementState state : states) {
            List<RequirementTransition> chain = transitionsById.getOrDefault(
                    state.requirementVersionId(),
                    List.of());
            if (chain.isEmpty()) {
                if (state.state() != EstadoVersionLegal.BORRADOR) {
                    throw new IllegalArgumentException(
                            "Todo estado de requisito no borrador requiere su historia completa");
                }
            } else {
                requireRequirementTransitionChain(state, chain);
            }
        }
    }

    private static void requireDocumentTransitionChain(
            ExpectedDocumentState state,
            List<DocumentTransition> transitions) {
        List<DocumentTransition> chain = new ArrayList<>(transitions);
        chain.sort(DOCUMENT_TRANSITION_ORDER);
        if (chain.getFirst().previousState() != EstadoVersionLegal.BORRADOR) {
            throw new IllegalArgumentException(
                    "La historia documental no comienza en BORRADOR");
        }
        for (int index = 1; index < chain.size(); index++) {
            if (chain.get(index - 1).newState() != chain.get(index).previousState()) {
                throw new IllegalArgumentException(
                        "Las transiciones documentales no forman una cadena cerrada");
            }
        }
        DocumentTransition last = chain.getLast();
        if (last.newState() != state.state()
                || !last.occurredAt().equals(state.stateChangedAt())
                || !Objects.equals(last.reason(), state.lastReason())
                || !Objects.equals(last.replacementBatchId(), state.replacementBatchId())) {
            throw new IllegalArgumentException(
                    "La cadena documental no coincide con el estado final esperado");
        }
    }

    private static void requireRequirementTransitionChain(
            ExpectedRequirementState state,
            List<RequirementTransition> transitions) {
        List<RequirementTransition> chain = new ArrayList<>(transitions);
        chain.sort(REQUIREMENT_TRANSITION_ORDER);
        if (chain.getFirst().previousState() != EstadoVersionLegal.BORRADOR) {
            throw new IllegalArgumentException(
                    "La historia de requisito no comienza en BORRADOR");
        }
        for (int index = 1; index < chain.size(); index++) {
            if (chain.get(index - 1).newState() != chain.get(index).previousState()) {
                throw new IllegalArgumentException(
                        "Las transiciones de requisito no forman una cadena cerrada");
            }
        }
        RequirementTransition last = chain.getLast();
        if (last.newState() != state.state()
                || !last.occurredAt().equals(state.stateChangedAt())
                || !Objects.equals(last.reason(), state.lastReason())) {
            throw new IllegalArgumentException(
                    "La cadena de requisito no coincide con el estado final esperado");
        }
    }

    private static UUID minimumReplacementMemberId(ReplacementBatch batch) {
        UUID minimum = batch.predecessorDocumentVersionIds().getFirst();
        for (UUID predecessor : batch.predecessorDocumentVersionIds()) {
            if (UUID_TEXT_ORDER.compare(predecessor, minimum) < 0) {
                minimum = predecessor;
            }
        }
        for (ReplacementSuccessor successor : batch.successors()) {
            if (UUID_TEXT_ORDER.compare(successor.documentVersionId(), minimum) < 0) {
                minimum = successor.documentVersionId();
            }
        }
        return minimum;
    }

    private static void rejectOverlappingReplacementMembers(List<ReplacementBatch> batches) {
        Set<UUID> seen = new HashSet<>();
        for (ReplacementBatch batch : batches) {
            for (UUID predecessor : batch.predecessorDocumentVersionIds()) {
                if (!seen.add(predecessor)) {
                    throw new IllegalArgumentException(
                            "Una versión documental participa en más de un lote");
                }
            }
            for (ReplacementSuccessor successor : batch.successors()) {
                if (!seen.add(successor.documentVersionId())) {
                    throw new IllegalArgumentException(
                            "Una versión documental participa en más de un lote");
                }
            }
        }
    }

    private static void rejectHistoricalReplacementRoleReuse(
            List<ReplacementBatch> batches) {
        Set<UUID> predecessors = new HashSet<>();
        Set<UUID> successors = new HashSet<>();
        for (ReplacementBatch batch : batches) {
            for (UUID predecessor : batch.predecessorDocumentVersionIds()) {
                if (!predecessors.add(predecessor)) {
                    throw new IllegalArgumentException(
                            "Una predecesora histórica participa en más de un lote");
                }
            }
            for (ReplacementSuccessor successor : batch.successors()) {
                if (!successors.add(successor.documentVersionId())) {
                    throw new IllegalArgumentException(
                            "Una sucesora histórica participa en más de un lote");
                }
            }
        }
    }

    private static boolean isReplacementTriggerEdge(DocumentTransition transition) {
        return (transition.previousState() == EstadoVersionLegal.PUBLICADA
                && transition.newState() == EstadoVersionLegal.VIGENTE)
                || (transition.previousState() == EstadoVersionLegal.VIGENTE
                && transition.newState() == EstadoVersionLegal.REEMPLAZADA);
    }

    private static boolean samePublication(
            PublicationIdentity left,
            PublicationIdentity right) {
        return left.equals(right);
    }

    private static IllegalArgumentException invalidOperationMatrix() {
        return new IllegalArgumentException(
                "La combinación de operación editorial no es válida");
    }

    private static void requireTransitionEdge(
            EstadoVersionLegal previous,
            EstadoVersionLegal next,
            String field) {
        boolean valid = (previous == EstadoVersionLegal.BORRADOR
                && next == EstadoVersionLegal.PUBLICADA)
                || (previous == EstadoVersionLegal.PUBLICADA
                && next == EstadoVersionLegal.VIGENTE)
                || (previous == EstadoVersionLegal.VIGENTE
                && (next == EstadoVersionLegal.REEMPLAZADA
                || next == EstadoVersionLegal.RETIRADA));
        if (!valid) {
            throw new IllegalArgumentException(field + " no respeta una arista V27");
        }
    }

    private static void requireDocumentStateMatrix(
            EstadoVersionLegal state,
            Instant stateChangedAt,
            String lastReason,
            UUID replacementBatchId) {
        boolean valid = switch (state) {
            case BORRADOR -> stateChangedAt == null
                    && lastReason == null
                    && replacementBatchId == null;
            case PUBLICADA -> stateChangedAt != null
                    && lastReason == null
                    && replacementBatchId == null;
            case VIGENTE -> stateChangedAt != null && lastReason == null;
            case REEMPLAZADA -> stateChangedAt != null
                    && lastReason == null
                    && replacementBatchId != null;
            case RETIRADA -> stateChangedAt != null
                    && lastReason != null
                    && replacementBatchId == null;
        };
        if (!valid) {
            throw new IllegalArgumentException(
                    "metadata incompatible con el estado documental " + state);
        }
    }

    private static void requireRequirementStateMatrix(
            EstadoVersionLegal state,
            Instant stateChangedAt,
            String lastReason) {
        boolean valid = switch (state) {
            case BORRADOR -> stateChangedAt == null && lastReason == null;
            case PUBLICADA, VIGENTE, REEMPLAZADA -> stateChangedAt != null
                    && lastReason == null;
            case RETIRADA -> stateChangedAt != null && lastReason != null;
        };
        if (!valid) {
            throw new IllegalArgumentException(
                    "metadata incompatible con el estado de requisito " + state);
        }
    }

    private static void requireAllAtExpectedAppliedAt(
            Instant expectedAppliedAt,
            List<Instant> instants,
            String field) {
        if (instants.stream().anyMatch(value -> !value.equals(expectedAppliedAt))) {
            throw new IllegalArgumentException(
                    field + " no usa expectedAppliedAt");
        }
    }

    private static String requirePublicationExternalId(String value) {
        String required = Objects.requireNonNull(value, "publicationExternalId");
        if (required.isBlank()
                || !required.equals(required.strip())
                || required.codePointCount(0, required.length())
                > MAX_PUBLICATION_EXTERNAL_ID_LENGTH) {
            throw new IllegalArgumentException(
                    "publicationExternalId no respeta el identificador V27");
        }
        return required;
    }

    private static String requireSha256(String value, String field) {
        String required = Objects.requireNonNull(value, field);
        if (required.length() != SHA256_LENGTH) {
            throw new IllegalArgumentException(field + " no respeta 64-hex lowercase");
        }
        requireLowerHex(required, 0, field);
        return required;
    }

    private static String requireFingerprint(String value) {
        return requireFingerprintValue(value, "expectedEditorialStateFingerprint");
    }

    private static String requireFingerprintValue(String value, String field) {
        String required = Objects.requireNonNull(value, field);
        if (required.length() != FINGERPRINT_LENGTH || !required.startsWith("sha256:")) {
            throw new IllegalArgumentException(field + " no respeta sha256:<64-hex>");
        }
        requireLowerHex(required, "sha256:".length(), field);
        return required;
    }

    private static void requireLowerHex(String value, int start, String field) {
        for (int index = start; index < value.length(); index++) {
            char current = value.charAt(index);
            if (!((current >= '0' && current <= '9')
                    || (current >= 'a' && current <= 'f'))) {
                throw new IllegalArgumentException(field + " no respeta hexadecimal lowercase");
            }
        }
    }

    private static String optionalReason(String value) {
        if (value == null) {
            return null;
        }
        if (value.isBlank()
                || !value.equals(value.strip())
                || value.codePointCount(0, value.length()) > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException(
                    "reason debe respetar el VARCHAR(1000) y btrim de V27");
        }
        return value;
    }

    private static Instant requirePostgresInstant(Instant value, String field) {
        return Objects.requireNonNull(optionalPostgresInstant(value, field), field);
    }

    private static Instant optionalPostgresInstant(Instant value, String field) {
        if (value != null && value.getNano() % 1_000 != 0) {
            throw new IllegalArgumentException(field + " sólo admite precisión de microsegundos");
        }
        return value;
    }

    private static <T> List<T> sortedCopy(
            List<T> values,
            Comparator<? super T> comparator,
            String field) {
        List<T> copy = new ArrayList<>(Objects.requireNonNull(values, field));
        for (T value : copy) {
            Objects.requireNonNull(value, field + " contiene null");
        }
        copy.sort(comparator);
        return List.copyOf(copy);
    }

    private static <T> List<T> sortedDistinctCopy(
            List<T> values,
            Comparator<? super T> comparator,
            String field) {
        return sortedCopy(values, comparator, field).stream()
                .distinct()
                .toList();
    }

    private static <T, K> void rejectDuplicateKeys(
            List<T> values,
            Function<? super T, ? extends K> keyExtractor,
            String message) {
        Set<K> seen = new HashSet<>();
        for (T value : values) {
            if (!seen.add(keyExtractor.apply(value))) {
                throw new IllegalArgumentException(message);
            }
        }
    }

    private static <T> void rejectOverlap(
            List<T> left,
            List<T> right,
            String message) {
        Set<T> seen = new HashSet<>(left);
        if (right.stream().anyMatch(seen::contains)) {
            throw new IllegalArgumentException(message);
        }
    }

    private static <T, K> Map<K, T> indexBy(
            List<T> values,
            Function<? super T, ? extends K> keyExtractor) {
        Map<K, T> indexed = new HashMap<>();
        for (T value : values) {
            indexed.put(keyExtractor.apply(value), value);
        }
        return indexed;
    }

    private record DocumentVersionContextKey(UUID documentVersionId, ContextoLegal context) {
    }
}
