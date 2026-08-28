package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoConstruccionLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoVersionLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Immutable, deterministic projection of the observed V27 editorial state.
 *
 * <p>The caller is responsible for selecting the complete relevant state: every version owned by
 * the evaluated publication, every version referenced by a current projection, every related
 * replacement batch and every additional non-draft version that can interfere. An unrelated
 * sealed publication whose versions are all {@code BORRADOR} must not be supplied. This type never
 * filters rows silently because doing so would make two different caller observations hash as the
 * same state.</p>
 *
 * <p>The projection deliberately contains digests instead of legal content and has no operator,
 * path, credential or secret field.</p>
 */
public record LegalEditorialStateProjection(
        int fingerprintVersion,
        Optional<CurrentPublication> currentPublication,
        List<DocumentSlot> documentSlots,
        List<RequiredSetPointer> requiredSetPointers,
        List<DocumentVersionState> documentVersions,
        List<RequirementVersionState> requirementVersions,
        List<ReplacementBatch> replacementBatches
) {

    public static final int CURRENT_FINGERPRINT_VERSION = 1;

    private static final int SHA256_LENGTH = 64;
    private static final int REQUIRED_SET_REVISION_LENGTH = 71;
    private static final int MAX_PUBLICATION_EXTERNAL_ID_LENGTH = 120;
    private static final int MAX_REASON_LENGTH = 1_000;

    private static final Comparator<UUID> UUID_TEXT_ORDER =
            Comparator.comparing(UUID::toString);
    private static final Comparator<DocumentSlot> DOCUMENT_SLOT_ORDER = Comparator
            .comparing((DocumentSlot slot) -> slot.type().name())
            .thenComparing(slot -> slot.locale().getCodigo())
            .thenComparing(slot -> slot.context().name());
    private static final Comparator<RequiredSetPointer> REQUIRED_SET_POINTER_ORDER = Comparator
            .comparing((RequiredSetPointer pointer) -> pointer.locale().getCodigo())
            .thenComparing(pointer -> pointer.context().name())
            .thenComparing(pointer -> pointer.audience().name());
    private static final Comparator<DocumentVersionState> DOCUMENT_VERSION_ORDER = Comparator
            .comparing(DocumentVersionState::documentVersionId, UUID_TEXT_ORDER);
    private static final Comparator<RequirementVersionState> REQUIREMENT_VERSION_ORDER = Comparator
            .comparing(RequirementVersionState::requirementVersionId, UUID_TEXT_ORDER);
    private static final Comparator<ReplacementBatch> REPLACEMENT_BATCH_ORDER = Comparator
            .comparing(ReplacementBatch::id, UUID_TEXT_ORDER);

    public LegalEditorialStateProjection {
        if (fingerprintVersion != CURRENT_FINGERPRINT_VERSION) {
            throw new IllegalArgumentException("fingerprintVersion no soportada");
        }
        currentPublication = Objects.requireNonNull(currentPublication, "currentPublication");
        documentSlots = sortedCopy(documentSlots, DOCUMENT_SLOT_ORDER, "documentSlots");
        requiredSetPointers = sortedCopy(
                requiredSetPointers,
                REQUIRED_SET_POINTER_ORDER,
                "requiredSetPointers");
        documentVersions = sortedCopy(
                documentVersions,
                DOCUMENT_VERSION_ORDER,
                "documentVersions");
        requirementVersions = sortedCopy(
                requirementVersions,
                REQUIREMENT_VERSION_ORDER,
                "requirementVersions");
        replacementBatches = sortedCopy(
                replacementBatches,
                REPLACEMENT_BATCH_ORDER,
                "replacementBatches");

        rejectDuplicateKeys(
                documentSlots,
                slot -> new DocumentSlotKey(slot.type(), slot.locale(), slot.context()),
                "documentSlots contiene un scope duplicado");
        rejectDuplicateKeys(
                documentSlots,
                slot -> new DocumentVersionContextKey(
                        slot.documentVersionId(),
                        slot.context()),
                "documentSlots contiene una versión/contexto duplicada");
        rejectDuplicateKeys(
                requiredSetPointers,
                pointer -> new RequiredSetPointerKey(
                        pointer.locale(),
                        pointer.context(),
                        pointer.audience()),
                "requiredSetPointers contiene un scope duplicado");
        rejectDuplicateKeys(
                documentVersions,
                DocumentVersionState::documentVersionId,
                "documentVersions contiene un id duplicado");
        rejectDuplicateKeys(
                requirementVersions,
                RequirementVersionState::requirementVersionId,
                "requirementVersions contiene un id duplicado");
        rejectDuplicateKeys(
                replacementBatches,
                ReplacementBatch::id,
                "replacementBatches contiene un id duplicado");
    }

    /** The observed publication row. Empty is serialized as an explicit JSON {@code null}. */
    public record CurrentPublication(
            UUID id,
            String publicationExternalId,
            String manifestSha256,
            EstadoConstruccionLegal buildState,
            Instant sealedAt
    ) {

        public CurrentPublication {
            id = Objects.requireNonNull(id, "id");
            publicationExternalId = requirePublicationExternalId(publicationExternalId);
            manifestSha256 = requireSha256(manifestSha256, "manifestSha256");
            buildState = Objects.requireNonNull(buildState, "buildState");
            sealedAt = optionalPostgresInstant(sealedAt, "sealedAt");
            requireBuildStateMatrix(buildState, sealedAt, "currentPublication");
        }
    }

    /** One exact row from {@code legal_documento_vigentes}. */
    public record DocumentSlot(
            TipoDocumentoLegal type,
            LocaleLegal locale,
            ContextoLegal context,
            UUID documentVersionId,
            UUID documentLineId,
            UUID publicationId,
            EstadoVersionLegal documentState
    ) {

        public DocumentSlot {
            type = Objects.requireNonNull(type, "type");
            locale = Objects.requireNonNull(locale, "locale");
            context = Objects.requireNonNull(context, "context");
            documentVersionId = Objects.requireNonNull(
                    documentVersionId,
                    "documentVersionId");
            documentLineId = Objects.requireNonNull(documentLineId, "documentLineId");
            publicationId = Objects.requireNonNull(publicationId, "publicationId");
            documentState = Objects.requireNonNull(documentState, "documentState");
            if (documentState != EstadoVersionLegal.VIGENTE) {
                throw new IllegalArgumentException("documentState debe ser VIGENTE en un slot");
            }
        }
    }

    /** One current pointer and the immutable snapshot membership it references. */
    public record RequiredSetPointer(
            LocaleLegal locale,
            ContextoLegal context,
            AudienciaLegal audience,
            UUID requiredSetId,
            UUID publicationId,
            String requiredSetRevision,
            Instant updatedAt,
            List<RequiredSetMember> members
    ) {

        private static final Comparator<RequiredSetMember> MEMBER_ORDER = Comparator
                .comparingInt(RequiredSetMember::manifestOrdinal)
                .thenComparing(RequiredSetMember::requirementVersionId, UUID_TEXT_ORDER);

        public RequiredSetPointer {
            locale = Objects.requireNonNull(locale, "locale");
            context = Objects.requireNonNull(context, "context");
            audience = Objects.requireNonNull(audience, "audience");
            requiredSetId = Objects.requireNonNull(requiredSetId, "requiredSetId");
            publicationId = Objects.requireNonNull(publicationId, "publicationId");
            requiredSetRevision = requireRequiredSetRevision(requiredSetRevision);
            updatedAt = requirePostgresInstant(updatedAt, "updatedAt");
            members = sortedCopy(members, MEMBER_ORDER, "members");
            rejectDuplicateKeys(
                    members,
                    RequiredSetMember::manifestOrdinal,
                    "members contiene manifestOrdinal duplicado");
            rejectDuplicateKeys(
                    members,
                    RequiredSetMember::requirementLineId,
                    "members contiene requirementLineId duplicado");
            rejectDuplicateKeys(
                    members,
                    RequiredSetMember::requirementVersionId,
                    "members contiene requirementVersionId duplicado");
        }
    }

    /** One immutable member of a required-set snapshot. */
    public record RequiredSetMember(
            int manifestOrdinal,
            UUID requirementVersionId,
            UUID requirementLineId,
            List<DocumentReference> documentReferences
    ) {

        private static final Comparator<DocumentReference> DOCUMENT_REFERENCE_ORDER = Comparator
                .comparingInt(DocumentReference::documentOrdinal)
                .thenComparing(DocumentReference::documentVersionId, UUID_TEXT_ORDER);

        public RequiredSetMember {
            requirePositive(manifestOrdinal, "manifestOrdinal");
            requirementVersionId = Objects.requireNonNull(
                    requirementVersionId,
                    "requirementVersionId");
            requirementLineId = Objects.requireNonNull(
                    requirementLineId,
                    "requirementLineId");
            documentReferences = sortedCopy(
                    documentReferences,
                    DOCUMENT_REFERENCE_ORDER,
                    "documentReferences");
            rejectDuplicateKeys(
                    documentReferences,
                    DocumentReference::documentOrdinal,
                    "documentReferences contiene documentOrdinal duplicado");
            rejectDuplicateKeys(
                    documentReferences,
                    DocumentReference::documentVersionId,
                    "documentReferences contiene documentVersionId duplicado");
        }
    }

    /** One ordered requirement-to-document edge. */
    public record DocumentReference(int documentOrdinal, UUID documentVersionId) {

        public DocumentReference {
            requirePositive(documentOrdinal, "documentOrdinal");
            documentVersionId = Objects.requireNonNull(
                    documentVersionId,
                    "documentVersionId");
        }
    }

    /** Current lifecycle state and digest of one relevant document version. */
    public record DocumentVersionState(
            UUID documentVersionId,
            UUID documentLineId,
            UUID introductionPublicationId,
            int lineageOrdinal,
            String sha256,
            EstadoVersionLegal state,
            Instant stateChangedAt,
            String lastReason,
            UUID replacementBatchId
    ) {

        public DocumentVersionState {
            documentVersionId = Objects.requireNonNull(
                    documentVersionId,
                    "documentVersionId");
            documentLineId = Objects.requireNonNull(documentLineId, "documentLineId");
            introductionPublicationId = Objects.requireNonNull(
                    introductionPublicationId,
                    "introductionPublicationId");
            requirePositive(lineageOrdinal, "lineageOrdinal");
            sha256 = requireSha256(sha256, "sha256");
            state = Objects.requireNonNull(state, "state");
            stateChangedAt = optionalPostgresInstant(stateChangedAt, "stateChangedAt");
            lastReason = optionalReason(lastReason);
            requireDocumentStateMatrix(
                    state,
                    stateChangedAt,
                    lastReason,
                    replacementBatchId);
        }
    }

    /** Current lifecycle state and statement digest of one relevant requirement version. */
    public record RequirementVersionState(
            UUID requirementVersionId,
            UUID requirementLineId,
            UUID introductionPublicationId,
            int lineageOrdinal,
            String statementSha256,
            EstadoVersionLegal state,
            Instant stateChangedAt,
            String lastReason
    ) {

        public RequirementVersionState {
            requirementVersionId = Objects.requireNonNull(
                    requirementVersionId,
                    "requirementVersionId");
            requirementLineId = Objects.requireNonNull(
                    requirementLineId,
                    "requirementLineId");
            introductionPublicationId = Objects.requireNonNull(
                    introductionPublicationId,
                    "introductionPublicationId");
            requirePositive(lineageOrdinal, "lineageOrdinal");
            statementSha256 = requireSha256(statementSha256, "statementSha256");
            state = Objects.requireNonNull(state, "state");
            stateChangedAt = optionalPostgresInstant(stateChangedAt, "stateChangedAt");
            lastReason = optionalReason(lastReason);
            requireRequirementStateMatrix(state, stateChangedAt, lastReason);
        }
    }

    /** One related V27 replacement batch and both immutable membership sides. */
    public record ReplacementBatch(
            UUID id,
            EstadoConstruccionLegal buildState,
            Instant createdAt,
            Instant sealedAt,
            List<UUID> predecessorDocumentVersionIds,
            List<ReplacementSuccessor> successors
    ) {

        private static final Comparator<ReplacementSuccessor> SUCCESSOR_ORDER = Comparator
                .comparing(ReplacementSuccessor::documentVersionId, UUID_TEXT_ORDER)
                .thenComparing(ReplacementSuccessor::publicationId, UUID_TEXT_ORDER);

        public ReplacementBatch {
            id = Objects.requireNonNull(id, "id");
            buildState = Objects.requireNonNull(buildState, "buildState");
            createdAt = requirePostgresInstant(createdAt, "createdAt");
            sealedAt = optionalPostgresInstant(sealedAt, "sealedAt");
            requireBuildStateMatrix(buildState, sealedAt, "replacementBatch");
            predecessorDocumentVersionIds = sortedCopy(
                    predecessorDocumentVersionIds,
                    UUID_TEXT_ORDER,
                    "predecessorDocumentVersionIds");
            successors = sortedCopy(successors, SUCCESSOR_ORDER, "successors");
            rejectDuplicateKeys(
                    predecessorDocumentVersionIds,
                    Function.identity(),
                    "predecessorDocumentVersionIds contiene un id duplicado");
            rejectDuplicateKeys(
                    successors,
                    ReplacementSuccessor::documentVersionId,
                    "successors contiene documentVersionId duplicado");
        }
    }

    /** One successor membership from {@code legal_documento_reemplazo_sucesoras}. */
    public record ReplacementSuccessor(UUID documentVersionId, UUID publicationId) {

        public ReplacementSuccessor {
            documentVersionId = Objects.requireNonNull(
                    documentVersionId,
                    "documentVersionId");
            publicationId = Objects.requireNonNull(publicationId, "publicationId");
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
            case PUBLICADA, VIGENTE, REEMPLAZADA ->
                    stateChangedAt != null && lastReason == null;
            case RETIRADA -> stateChangedAt != null && lastReason != null;
        };
        if (!valid) {
            throw new IllegalArgumentException(
                    "metadata incompatible con el estado de requisito " + state);
        }
    }

    private static void requireBuildStateMatrix(
            EstadoConstruccionLegal buildState,
            Instant sealedAt,
            String field) {
        boolean valid = switch (buildState) {
            case ABIERTO -> sealedAt == null;
            case SELLADO -> sealedAt != null;
        };
        if (!valid) {
            throw new IllegalArgumentException(field + " no respeta la matriz ABIERTO/SELLADO");
        }
    }

    private static String requireRequiredSetRevision(String value) {
        String required = Objects.requireNonNull(value, "requiredSetRevision");
        if (required.length() != REQUIRED_SET_REVISION_LENGTH
                || !required.startsWith("sha256:")) {
            throw new IllegalArgumentException("requiredSetRevision no respeta sha256:<64-hex>");
        }
        requireLowerHex(required, "sha256:".length(), "requiredSetRevision");
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

    private static String requirePublicationExternalId(String value) {
        String required = Objects.requireNonNull(value, "publicationExternalId");
        if (required.codePointCount(0, required.length())
                > MAX_PUBLICATION_EXTERNAL_ID_LENGTH) {
            throw new IllegalArgumentException(
                    "publicationExternalId debe respetar el VARCHAR(120) de V27");
        }
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
        if (value.codePointCount(0, value.length()) > MAX_REASON_LENGTH
                || value.isEmpty()
                || value.codePoints().allMatch(codePoint -> codePoint == ' ')) {
            throw new IllegalArgumentException(
                    "lastReason debe respetar el VARCHAR(1000) y btrim de V27");
        }
        return value;
    }

    private static Instant requirePostgresInstant(Instant value, String field) {
        return Objects.requireNonNull(
                optionalPostgresInstant(value, field),
                field);
    }

    private static Instant optionalPostgresInstant(Instant value, String field) {
        if (value != null && value.getNano() % 1_000 != 0) {
            throw new IllegalArgumentException(field + " sólo admite precisión de microsegundos");
        }
        return value;
    }

    private static void requirePositive(int value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " debe ser positivo");
        }
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

    private record DocumentSlotKey(
            TipoDocumentoLegal type,
            LocaleLegal locale,
            ContextoLegal context) {
    }

    private record DocumentVersionContextKey(UUID versionId, ContextoLegal context) {
    }

    private record RequiredSetPointerKey(
            LocaleLegal locale,
            ContextoLegal context,
            AudienciaLegal audience) {
    }
}
