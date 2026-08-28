package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialStateProjection.CurrentPublication;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialStateProjection.DocumentReference;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialStateProjection.DocumentSlot;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialStateProjection.DocumentVersionState;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialStateProjection.ReplacementBatch;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialStateProjection.ReplacementSuccessor;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialStateProjection.RequiredSetMember;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialStateProjection.RequiredSetPointer;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialStateProjection.RequirementVersionState;

import java.time.Instant;
import java.util.Objects;

/** Calculates the internal RFC 8785 fingerprint of one observed editorial state. */
public final class LegalEditorialStateFingerprintCalculator {

    private static final String FINGERPRINT_PREFIX = "sha256:";
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final Rfc8785Canonicalizer canonicalizer;

    public LegalEditorialStateFingerprintCalculator() {
        this(new Rfc8785Canonicalizer());
    }

    LegalEditorialStateFingerprintCalculator(Rfc8785Canonicalizer canonicalizer) {
        this.canonicalizer = Objects.requireNonNull(canonicalizer, "canonicalizer");
    }

    /** Returns {@code sha256:<64-hex>} over the exact normalized projection. */
    public String calculate(LegalEditorialStateProjection projection) {
        return FINGERPRINT_PREFIX + canonicalizer.canonicalize(
                Objects.requireNonNull(projection, "projection"));
    }

    /** Materializes canonical bytes only for golden/equivalence tests. */
    byte[] canonicalUtf8(LegalEditorialStateProjection projection) {
        return canonicalizer.canonicalUtf8(Objects.requireNonNull(projection, "projection"));
    }

    /** Readable pre-JCS projection used only by golden/equivalence tests. */
    String projectionJson(LegalEditorialStateProjection projection) {
        return project(Objects.requireNonNull(projection, "projection")).toString();
    }

    private static ObjectNode project(LegalEditorialStateProjection projection) {
        ObjectNode root = JSON.objectNode();
        root.put("fingerprintVersion", projection.fingerprintVersion());
        projection.currentPublication().ifPresentOrElse(
                publication -> projectCurrentPublication(root, publication),
                () -> root.putNull("currentPublication"));

        ArrayNode slots = root.putArray("documentSlots");
        projection.documentSlots().forEach(slot -> projectDocumentSlot(slots, slot));

        ArrayNode pointers = root.putArray("requiredSetPointers");
        projection.requiredSetPointers().forEach(
                pointer -> projectRequiredSetPointer(pointers, pointer));

        ArrayNode documentVersions = root.putArray("documentVersions");
        projection.documentVersions().forEach(
                version -> projectDocumentVersion(documentVersions, version));

        ArrayNode requirementVersions = root.putArray("requirementVersions");
        projection.requirementVersions().forEach(
                version -> projectRequirementVersion(requirementVersions, version));

        ArrayNode batches = root.putArray("replacementBatches");
        projection.replacementBatches().forEach(batch -> projectReplacementBatch(batches, batch));
        return root;
    }

    private static void projectCurrentPublication(
            ObjectNode root,
            CurrentPublication publication) {
        ObjectNode current = root.putObject("currentPublication");
        current.put("id", publication.id().toString());
        current.put("publicationExternalId", publication.publicationExternalId());
        current.put("manifestSha256", publication.manifestSha256());
        current.put("buildState", publication.buildState().name());
        putNullableInstant(current, "sealedAt", publication.sealedAt());
    }

    private static void projectDocumentSlot(ArrayNode target, DocumentSlot slot) {
        ObjectNode item = target.addObject();
        item.put("type", slot.type().name());
        item.put("locale", slot.locale().getCodigo());
        item.put("context", slot.context().name());
        item.put("documentVersionId", slot.documentVersionId().toString());
        item.put("documentLineId", slot.documentLineId().toString());
        item.put("publicationId", slot.publicationId().toString());
        item.put("documentState", slot.documentState().name());
    }

    private static void projectRequiredSetPointer(
            ArrayNode target,
            RequiredSetPointer pointer) {
        ObjectNode item = target.addObject();
        item.put("locale", pointer.locale().getCodigo());
        item.put("context", pointer.context().name());
        item.put("audience", pointer.audience().name());
        item.put("requiredSetId", pointer.requiredSetId().toString());
        item.put("publicationId", pointer.publicationId().toString());
        item.put("requiredSetRevision", pointer.requiredSetRevision());
        item.put("updatedAt", utcInstant(pointer.updatedAt()));
        ArrayNode members = item.putArray("members");
        pointer.members().forEach(member -> projectRequiredSetMember(members, member));
    }

    private static void projectRequiredSetMember(
            ArrayNode target,
            RequiredSetMember member) {
        ObjectNode item = target.addObject();
        item.put("manifestOrdinal", member.manifestOrdinal());
        item.put("requirementVersionId", member.requirementVersionId().toString());
        item.put("requirementLineId", member.requirementLineId().toString());
        ArrayNode references = item.putArray("documentReferences");
        member.documentReferences().forEach(
                reference -> projectDocumentReference(references, reference));
    }

    private static void projectDocumentReference(
            ArrayNode target,
            DocumentReference reference) {
        ObjectNode item = target.addObject();
        item.put("documentOrdinal", reference.documentOrdinal());
        item.put("documentVersionId", reference.documentVersionId().toString());
    }

    private static void projectDocumentVersion(
            ArrayNode target,
            DocumentVersionState version) {
        ObjectNode item = target.addObject();
        item.put("documentVersionId", version.documentVersionId().toString());
        item.put("documentLineId", version.documentLineId().toString());
        item.put("introductionPublicationId", version.introductionPublicationId().toString());
        item.put("lineageOrdinal", version.lineageOrdinal());
        item.put("sha256", version.sha256());
        item.put("state", version.state().name());
        putNullableInstant(item, "stateChangedAt", version.stateChangedAt());
        putNullableString(item, "lastReason", version.lastReason());
        putNullableUuid(item, "replacementBatchId", version.replacementBatchId());
    }

    private static void projectRequirementVersion(
            ArrayNode target,
            RequirementVersionState version) {
        ObjectNode item = target.addObject();
        item.put("requirementVersionId", version.requirementVersionId().toString());
        item.put("requirementLineId", version.requirementLineId().toString());
        item.put("introductionPublicationId", version.introductionPublicationId().toString());
        item.put("lineageOrdinal", version.lineageOrdinal());
        item.put("statementSha256", version.statementSha256());
        item.put("state", version.state().name());
        putNullableInstant(item, "stateChangedAt", version.stateChangedAt());
        putNullableString(item, "lastReason", version.lastReason());
    }

    private static void projectReplacementBatch(
            ArrayNode target,
            ReplacementBatch batch) {
        ObjectNode item = target.addObject();
        item.put("id", batch.id().toString());
        item.put("buildState", batch.buildState().name());
        item.put("createdAt", utcInstant(batch.createdAt()));
        putNullableInstant(item, "sealedAt", batch.sealedAt());
        ArrayNode predecessors = item.putArray("predecessorDocumentVersionIds");
        batch.predecessorDocumentVersionIds().forEach(
                versionId -> predecessors.add(versionId.toString()));
        ArrayNode successors = item.putArray("successors");
        batch.successors().forEach(successor -> projectReplacementSuccessor(successors, successor));
    }

    private static void projectReplacementSuccessor(
            ArrayNode target,
            ReplacementSuccessor successor) {
        ObjectNode item = target.addObject();
        item.put("documentVersionId", successor.documentVersionId().toString());
        item.put("publicationId", successor.publicationId().toString());
    }

    private static void putNullableInstant(ObjectNode target, String field, Instant value) {
        if (value == null) {
            target.putNull(field);
        } else {
            target.put(field, utcInstant(value));
        }
    }

    private static void putNullableString(ObjectNode target, String field, String value) {
        if (value == null) {
            target.putNull(field);
        } else {
            target.put(field, value);
        }
    }

    private static void putNullableUuid(ObjectNode target, String field, java.util.UUID value) {
        if (value == null) {
            target.putNull(field);
        } else {
            target.put(field, value.toString());
        }
    }

    private static String utcInstant(Instant instant) {
        return LegalRequiredSetRevisionCalculator.utcInstant(instant);
    }
}
