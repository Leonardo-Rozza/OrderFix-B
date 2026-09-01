package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import org.erdtman.jcs.JsonCanonicalizer;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Adaptador acotado de JCS: acepta JSON externo sólo después de {@link StrictJsonReader} y las
 * proyecciones internas tipadas del conjunto requerido, sus agregados y el estado editorial. No
 * expone entradas de texto, bytes o árboles JSON genéricos.
 */
final class Rfc8785Canonicalizer {

    private static final char[] LOWER_HEXADECIMAL = "0123456789abcdef".toCharArray();

    public LegalManifestValidation<CanonicalJson> canonicalize(
            StrictJsonReader.StrictJsonDocument document) {
        return canonicalizeExternalDocument(
                document,
                StrictJsonReader.ExternalJsonProfile.MANIFEST);
    }

    LegalManifestValidation<CanonicalJson> canonicalizeEditorialPlan(
            StrictJsonReader.StrictJsonDocument document) {
        return canonicalizeExternalDocument(
                document,
                StrictJsonReader.ExternalJsonProfile.EDITORIAL_PLAN);
    }

    private static LegalManifestValidation<CanonicalJson> canonicalizeExternalDocument(
            StrictJsonReader.StrictJsonDocument document,
            StrictJsonReader.ExternalJsonProfile expectedProfile) {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(expectedProfile, "expectedProfile");
        if (document.profile() != expectedProfile) {
            throw new IllegalArgumentException(
                    "El documento JSON estricto no pertenece al perfil esperado");
        }

        try {
            return LegalManifestValidation.pass(canonicalizeText(document.text()));
        } catch (IOException exception) {
            return LegalManifestValidation.failure(LegalManifestIssue.at(
                    expectedProfile.rfc8785Invalid(),
                    document.safeLocation()));
        } catch (RuntimeException exception) {
            return LegalManifestValidation.failure(LegalManifestIssue.at(
                    expectedProfile.canonicalizationError(),
                    document.safeLocation()));
        }
    }

    /**
     * Hashes the typed projection while emitting its RFC 8785 representation directly. The
     * projection contains only strings, booleans, arrays and fixed object keys, so no generic JSON
     * tree or full intermediate document is required.
     */
    String canonicalize(LegalRequiredSetProjection projection) {
        LegalRequiredSetProjection required = Objects.requireNonNull(projection, "projection");
        LegalRequiredSetRevisionCalculator.requireCanonicalizationCapacity(required);
        MessageDigest digest = sha256Digest();
        try (BufferedOutputStream output = new BufferedOutputStream(
                new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
            writeCanonicalProjection(required, output);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "No se pudo canonicalizar la proyección legal interna", exception);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Materializes canonical bytes only for golden/equivalence tests, never for persistence. */
    byte[] canonicalUtf8(LegalRequiredSetProjection projection) {
        LegalRequiredSetProjection required = Objects.requireNonNull(projection, "projection");
        LegalRequiredSetRevisionCalculator.requireCanonicalizationCapacity(required);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (BufferedOutputStream output = new BufferedOutputStream(bytes)) {
            writeCanonicalProjection(required, output);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "No se pudo materializar la proyección legal canónica", exception);
        }
        return bytes.toByteArray();
    }

    /** Hashes one normalized semantic aggregate without a generic JSON tree. */
    String canonicalize(LegalRequiredSetAggregateProjection projection) {
        LegalRequiredSetAggregateProjection required = Objects.requireNonNull(
                projection,
                "projection");
        MessageDigest digest = sha256Digest();
        try (BufferedOutputStream output = new BufferedOutputStream(
                new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
            writeCanonicalAggregateProjection(required, output);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "No se pudo canonicalizar la revisión legal agregada", exception);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Materializes semantic aggregate bytes only for golden/equivalence tests. */
    byte[] canonicalUtf8(LegalRequiredSetAggregateProjection projection) {
        LegalRequiredSetAggregateProjection required = Objects.requireNonNull(
                projection,
                "projection");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (BufferedOutputStream output = new BufferedOutputStream(bytes)) {
            writeCanonicalAggregateProjection(required, output);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "No se pudo materializar la revisión legal agregada", exception);
        }
        return bytes.toByteArray();
    }

    /** Hashes one normalized physical provenance without a generic JSON tree. */
    String canonicalize(LegalRequiredSetAggregateProvenance provenance) {
        LegalRequiredSetAggregateProvenance required = Objects.requireNonNull(
                provenance,
                "provenance");
        MessageDigest digest = sha256Digest();
        try (BufferedOutputStream output = new BufferedOutputStream(
                new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
            writeCanonicalAggregateProvenance(required, output);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "No se pudo canonicalizar la procedencia legal agregada", exception);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Materializes provenance bytes only for golden/equivalence tests. */
    byte[] canonicalUtf8(LegalRequiredSetAggregateProvenance provenance) {
        LegalRequiredSetAggregateProvenance required = Objects.requireNonNull(
                provenance,
                "provenance");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (BufferedOutputStream output = new BufferedOutputStream(bytes)) {
            writeCanonicalAggregateProvenance(required, output);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "No se pudo materializar la procedencia legal agregada", exception);
        }
        return bytes.toByteArray();
    }

    /** Hashes the normalized editorial-state projection without a generic JSON tree. */
    String canonicalize(LegalEditorialStateProjection projection) {
        LegalEditorialStateProjection required = Objects.requireNonNull(projection, "projection");
        MessageDigest digest = sha256Digest();
        try (BufferedOutputStream output = new BufferedOutputStream(
                new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
            writeCanonicalEditorialProjection(required, output);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "No se pudo canonicalizar el estado editorial interno", exception);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Materializes editorial canonical bytes only for golden/equivalence tests. */
    byte[] canonicalUtf8(LegalEditorialStateProjection projection) {
        LegalEditorialStateProjection required = Objects.requireNonNull(projection, "projection");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (BufferedOutputStream output = new BufferedOutputStream(bytes)) {
            writeCanonicalEditorialProjection(required, output);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "No se pudo materializar el estado editorial canónico", exception);
        }
        return bytes.toByteArray();
    }

    private static CanonicalJson canonicalizeText(String json) throws IOException {
        byte[] canonicalUtf8 = new JsonCanonicalizer(json).getEncodedUTF8();
        return new CanonicalJson(
                canonicalUtf8,
                HexFormat.of().formatHex(sha256Digest().digest(canonicalUtf8)));
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no está disponible", exception);
        }
    }

    private static void writeCanonicalProjection(
            LegalRequiredSetProjection projection,
            OutputStream output) throws IOException {
        writeAscii(output, "{\"contexto\":");
        writeJsonString(output, projection.context().name());
        writeAscii(output, ",\"locale\":");
        writeJsonString(output, projection.locale().getCodigo());
        writeAscii(output, ",\"requisitos\":[");
        boolean firstRequirement = true;
        for (LegalRequiredSetProjection.RequirementProjection requirement
                : projection.requirements()) {
            if (!firstRequirement) {
                output.write(',');
            }
            firstRequirement = false;
            writeCanonicalRequirement(requirement, output);
        }
        writeAscii(output, "]}");
    }

    private static void writeCanonicalRequirement(
            LegalRequiredSetProjection.RequirementProjection requirement,
            OutputStream output) throws IOException {
        writeAscii(output, "{\"afirmacion\":");
        writeJsonString(output, requirement.statement());
        writeAscii(output, ",\"afirmacionSha256\":");
        writeJsonString(output, requirement.statementSha256());
        writeAscii(output, ",\"contexto\":");
        writeJsonString(output, requirement.context().name());
        writeAscii(output, ",\"documentos\":[");
        boolean firstDocument = true;
        for (LegalRequiredSetProjection.DocumentProjection document
                : requirement.documents()) {
            if (!firstDocument) {
                output.write(',');
            }
            firstDocument = false;
            writeCanonicalDocument(document, output);
        }
        writeAscii(output, "],\"id\":");
        writeJsonString(output, requirement.versionId().toString());
        writeAscii(output, ",\"requerido\":");
        writeAscii(output, requirement.required() ? "true" : "false");
        writeAscii(output, ",\"tipoActo\":");
        writeJsonString(output, requirement.actType().name());
        output.write('}');
    }

    private static void writeCanonicalDocument(
            LegalRequiredSetProjection.DocumentProjection document,
            OutputStream output) throws IOException {
        writeAscii(output, "{\"contenidoMarkdown\":");
        writeJsonString(output, document.markdown());
        writeAscii(output, ",\"estado\":\"VIGENTE\",\"id\":");
        writeJsonString(output, document.versionId().toString());
        writeAscii(output, ",\"locale\":");
        writeJsonString(output, document.locale().getCodigo());
        writeAscii(output, ",\"sha256\":");
        writeJsonString(output, document.sha256());
        writeAscii(output, ",\"tipo\":");
        writeJsonString(output, document.type().name());
        writeAscii(output, ",\"titulo\":");
        writeJsonString(output, document.title());
        writeAscii(output, ",\"version\":");
        writeJsonString(output, document.version());
        writeAscii(output, ",\"vigenteDesde\":");
        writeJsonString(
                output,
                LegalRequiredSetRevisionCalculator.utcInstant(
                        document.effectiveAt().toInstant()));
        output.write('}');
    }

    private static void writeCanonicalAggregateProjection(
            LegalRequiredSetAggregateProjection projection,
            OutputStream output) throws IOException {
        writeAscii(output, "{\"audiencia\":");
        writeJsonString(output, projection.audience().name());
        writeAscii(output, ",\"locale\":");
        writeJsonString(output, projection.locale().getCodigo());
        writeAscii(output, ",\"revisionScheme\":");
        writeJsonString(output, projection.revisionScheme().name());
        writeAscii(output, ",\"scopes\":[");
        boolean first = true;
        for (LegalRequiredSetAggregateProjection.ScopeRevision scope : projection.scopes()) {
            if (!first) {
                output.write(',');
            }
            first = false;
            writeCanonicalAggregateScopeRevision(scope, output);
        }
        writeAscii(output, "]}");
    }

    private static void writeCanonicalAggregateScopeRevision(
            LegalRequiredSetAggregateProjection.ScopeRevision scope,
            OutputStream output) throws IOException {
        writeAscii(output, "{\"contexto\":");
        writeJsonString(output, scope.context().name());
        writeAscii(output, ",\"requiredSetRevision\":");
        writeJsonString(output, scope.requiredSetRevision());
        output.write('}');
    }

    private static void writeCanonicalAggregateProvenance(
            LegalRequiredSetAggregateProvenance provenance,
            OutputStream output) throws IOException {
        writeAscii(output, "{\"audiencia\":");
        writeJsonString(output, provenance.audience().name());
        writeAscii(output, ",\"locale\":");
        writeJsonString(output, provenance.locale().getCodigo());
        writeAscii(output, ",\"perfil\":");
        writeJsonString(output, provenance.profile().name());
        writeAscii(output, ",\"provenanceScheme\":");
        writeJsonString(output, LegalRequiredSetAggregateProvenance.PROVENANCE_SCHEME);
        writeAscii(output, ",\"scopes\":[");
        boolean first = true;
        for (LegalRequiredSetAggregateProvenance.ScopeOrigin scope : provenance.scopes()) {
            if (!first) {
                output.write(',');
            }
            first = false;
            writeCanonicalAggregateScopeOrigin(scope, output);
        }
        writeAscii(output, "]}");
    }

    private static void writeCanonicalAggregateScopeOrigin(
            LegalRequiredSetAggregateProvenance.ScopeOrigin scope,
            OutputStream output) throws IOException {
        writeAscii(output, "{\"conjuntoId\":");
        writeJsonString(output, scope.requiredSetId().toString());
        writeAscii(output, ",\"contexto\":");
        writeJsonString(output, scope.context().name());
        writeAscii(output, ",\"publicacionId\":");
        writeJsonString(output, scope.publicationId().toString());
        output.write('}');
    }

    private static void writeCanonicalEditorialProjection(
            LegalEditorialStateProjection projection,
            OutputStream output) throws IOException {
        writeAscii(output, "{\"currentPublication\":");
        if (projection.currentPublication().isEmpty()) {
            writeAscii(output, "null");
        } else {
            writeCanonicalCurrentPublication(
                    projection.currentPublication().orElseThrow(),
                    output);
        }
        writeAscii(output, ",\"documentSlots\":[");
        boolean first = true;
        for (LegalEditorialStateProjection.DocumentSlot slot : projection.documentSlots()) {
            if (!first) {
                output.write(',');
            }
            first = false;
            writeCanonicalDocumentSlot(slot, output);
        }
        writeAscii(output, "],\"documentVersions\":[");
        first = true;
        for (LegalEditorialStateProjection.DocumentVersionState version
                : projection.documentVersions()) {
            if (!first) {
                output.write(',');
            }
            first = false;
            writeCanonicalDocumentVersion(version, output);
        }
        writeAscii(output, "],\"fingerprintVersion\":");
        writeAscii(output, Integer.toString(projection.fingerprintVersion()));
        writeAscii(output, ",\"replacementBatches\":[");
        first = true;
        for (LegalEditorialStateProjection.ReplacementBatch batch
                : projection.replacementBatches()) {
            if (!first) {
                output.write(',');
            }
            first = false;
            writeCanonicalReplacementBatch(batch, output);
        }
        writeAscii(output, "],\"requiredSetPointers\":[");
        first = true;
        for (LegalEditorialStateProjection.RequiredSetPointer pointer
                : projection.requiredSetPointers()) {
            if (!first) {
                output.write(',');
            }
            first = false;
            writeCanonicalRequiredSetPointer(pointer, output);
        }
        writeAscii(output, "],\"requirementVersions\":[");
        first = true;
        for (LegalEditorialStateProjection.RequirementVersionState version
                : projection.requirementVersions()) {
            if (!first) {
                output.write(',');
            }
            first = false;
            writeCanonicalRequirementVersion(version, output);
        }
        writeAscii(output, "]}");
    }

    private static void writeCanonicalCurrentPublication(
            LegalEditorialStateProjection.CurrentPublication publication,
            OutputStream output) throws IOException {
        writeAscii(output, "{\"buildState\":");
        writeJsonString(output, publication.buildState().name());
        writeAscii(output, ",\"id\":");
        writeJsonString(output, publication.id().toString());
        writeAscii(output, ",\"manifestSha256\":");
        writeJsonString(output, publication.manifestSha256());
        writeAscii(output, ",\"publicationExternalId\":");
        writeJsonString(output, publication.publicationExternalId());
        writeAscii(output, ",\"sealedAt\":");
        writeNullableInstant(output, publication.sealedAt());
        output.write('}');
    }

    private static void writeCanonicalDocumentSlot(
            LegalEditorialStateProjection.DocumentSlot slot,
            OutputStream output) throws IOException {
        writeAscii(output, "{\"context\":");
        writeJsonString(output, slot.context().name());
        writeAscii(output, ",\"documentLineId\":");
        writeJsonString(output, slot.documentLineId().toString());
        writeAscii(output, ",\"documentState\":");
        writeJsonString(output, slot.documentState().name());
        writeAscii(output, ",\"documentVersionId\":");
        writeJsonString(output, slot.documentVersionId().toString());
        writeAscii(output, ",\"locale\":");
        writeJsonString(output, slot.locale().getCodigo());
        writeAscii(output, ",\"publicationId\":");
        writeJsonString(output, slot.publicationId().toString());
        writeAscii(output, ",\"type\":");
        writeJsonString(output, slot.type().name());
        output.write('}');
    }

    private static void writeCanonicalDocumentVersion(
            LegalEditorialStateProjection.DocumentVersionState version,
            OutputStream output) throws IOException {
        writeAscii(output, "{\"documentLineId\":");
        writeJsonString(output, version.documentLineId().toString());
        writeAscii(output, ",\"documentVersionId\":");
        writeJsonString(output, version.documentVersionId().toString());
        writeAscii(output, ",\"introductionPublicationId\":");
        writeJsonString(output, version.introductionPublicationId().toString());
        writeAscii(output, ",\"lastReason\":");
        writeNullableString(output, version.lastReason());
        writeAscii(output, ",\"lineageOrdinal\":");
        writeAscii(output, Integer.toString(version.lineageOrdinal()));
        writeAscii(output, ",\"replacementBatchId\":");
        writeNullableUuid(output, version.replacementBatchId());
        writeAscii(output, ",\"sha256\":");
        writeJsonString(output, version.sha256());
        writeAscii(output, ",\"state\":");
        writeJsonString(output, version.state().name());
        writeAscii(output, ",\"stateChangedAt\":");
        writeNullableInstant(output, version.stateChangedAt());
        output.write('}');
    }

    private static void writeCanonicalReplacementBatch(
            LegalEditorialStateProjection.ReplacementBatch batch,
            OutputStream output) throws IOException {
        writeAscii(output, "{\"buildState\":");
        writeJsonString(output, batch.buildState().name());
        writeAscii(output, ",\"createdAt\":");
        writeInstant(output, batch.createdAt());
        writeAscii(output, ",\"id\":");
        writeJsonString(output, batch.id().toString());
        writeAscii(output, ",\"predecessorDocumentVersionIds\":[");
        boolean first = true;
        for (java.util.UUID versionId : batch.predecessorDocumentVersionIds()) {
            if (!first) {
                output.write(',');
            }
            first = false;
            writeJsonString(output, versionId.toString());
        }
        writeAscii(output, "],\"sealedAt\":");
        writeNullableInstant(output, batch.sealedAt());
        writeAscii(output, ",\"successors\":[");
        first = true;
        for (LegalEditorialStateProjection.ReplacementSuccessor successor
                : batch.successors()) {
            if (!first) {
                output.write(',');
            }
            first = false;
            writeCanonicalReplacementSuccessor(successor, output);
        }
        writeAscii(output, "]}");
    }

    private static void writeCanonicalReplacementSuccessor(
            LegalEditorialStateProjection.ReplacementSuccessor successor,
            OutputStream output) throws IOException {
        writeAscii(output, "{\"documentVersionId\":");
        writeJsonString(output, successor.documentVersionId().toString());
        writeAscii(output, ",\"publicationId\":");
        writeJsonString(output, successor.publicationId().toString());
        output.write('}');
    }

    private static void writeCanonicalRequiredSetPointer(
            LegalEditorialStateProjection.RequiredSetPointer pointer,
            OutputStream output) throws IOException {
        writeAscii(output, "{\"audience\":");
        writeJsonString(output, pointer.audience().name());
        writeAscii(output, ",\"context\":");
        writeJsonString(output, pointer.context().name());
        writeAscii(output, ",\"locale\":");
        writeJsonString(output, pointer.locale().getCodigo());
        writeAscii(output, ",\"members\":[");
        boolean first = true;
        for (LegalEditorialStateProjection.RequiredSetMember member : pointer.members()) {
            if (!first) {
                output.write(',');
            }
            first = false;
            writeCanonicalRequiredSetMember(member, output);
        }
        writeAscii(output, "],\"publicationId\":");
        writeJsonString(output, pointer.publicationId().toString());
        writeAscii(output, ",\"requiredSetId\":");
        writeJsonString(output, pointer.requiredSetId().toString());
        writeAscii(output, ",\"requiredSetRevision\":");
        writeJsonString(output, pointer.requiredSetRevision());
        writeAscii(output, ",\"updatedAt\":");
        writeInstant(output, pointer.updatedAt());
        output.write('}');
    }

    private static void writeCanonicalRequiredSetMember(
            LegalEditorialStateProjection.RequiredSetMember member,
            OutputStream output) throws IOException {
        writeAscii(output, "{\"documentReferences\":[");
        boolean first = true;
        for (LegalEditorialStateProjection.DocumentReference reference
                : member.documentReferences()) {
            if (!first) {
                output.write(',');
            }
            first = false;
            writeCanonicalDocumentReference(reference, output);
        }
        writeAscii(output, "],\"manifestOrdinal\":");
        writeAscii(output, Integer.toString(member.manifestOrdinal()));
        writeAscii(output, ",\"requirementLineId\":");
        writeJsonString(output, member.requirementLineId().toString());
        writeAscii(output, ",\"requirementVersionId\":");
        writeJsonString(output, member.requirementVersionId().toString());
        output.write('}');
    }

    private static void writeCanonicalDocumentReference(
            LegalEditorialStateProjection.DocumentReference reference,
            OutputStream output) throws IOException {
        writeAscii(output, "{\"documentOrdinal\":");
        writeAscii(output, Integer.toString(reference.documentOrdinal()));
        writeAscii(output, ",\"documentVersionId\":");
        writeJsonString(output, reference.documentVersionId().toString());
        output.write('}');
    }

    private static void writeCanonicalRequirementVersion(
            LegalEditorialStateProjection.RequirementVersionState version,
            OutputStream output) throws IOException {
        writeAscii(output, "{\"introductionPublicationId\":");
        writeJsonString(output, version.introductionPublicationId().toString());
        writeAscii(output, ",\"lastReason\":");
        writeNullableString(output, version.lastReason());
        writeAscii(output, ",\"lineageOrdinal\":");
        writeAscii(output, Integer.toString(version.lineageOrdinal()));
        writeAscii(output, ",\"requirementLineId\":");
        writeJsonString(output, version.requirementLineId().toString());
        writeAscii(output, ",\"requirementVersionId\":");
        writeJsonString(output, version.requirementVersionId().toString());
        writeAscii(output, ",\"state\":");
        writeJsonString(output, version.state().name());
        writeAscii(output, ",\"stateChangedAt\":");
        writeNullableInstant(output, version.stateChangedAt());
        writeAscii(output, ",\"statementSha256\":");
        writeJsonString(output, version.statementSha256());
        output.write('}');
    }

    private static void writeNullableString(OutputStream output, String value) throws IOException {
        if (value == null) {
            writeAscii(output, "null");
        } else {
            writeJsonString(output, value);
        }
    }

    private static void writeNullableUuid(OutputStream output, java.util.UUID value)
            throws IOException {
        writeNullableString(output, value == null ? null : value.toString());
    }

    private static void writeInstant(OutputStream output, java.time.Instant value)
            throws IOException {
        writeJsonString(output, LegalRequiredSetRevisionCalculator.utcInstant(value));
    }

    private static void writeNullableInstant(OutputStream output, java.time.Instant value)
            throws IOException {
        if (value == null) {
            writeAscii(output, "null");
        } else {
            writeInstant(output, value);
        }
    }

    private static void writeJsonString(OutputStream output, String value) throws IOException {
        output.write('"');
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '"' -> writeAscii(output, "\\\"");
                case '\\' -> writeAscii(output, "\\\\");
                case '\b' -> writeAscii(output, "\\b");
                case '\t' -> writeAscii(output, "\\t");
                case '\n' -> writeAscii(output, "\\n");
                case '\f' -> writeAscii(output, "\\f");
                case '\r' -> writeAscii(output, "\\r");
                default -> {
                    if (current < 0x20) {
                        writeUnicodeControl(output, current);
                    } else if (Character.isHighSurrogate(current)) {
                        if (index + 1 >= value.length()
                                || !Character.isLowSurrogate(value.charAt(index + 1))) {
                            throw new IllegalArgumentException(
                                    "La proyección legal contiene Unicode inválido");
                        }
                        writeUtf8(output, Character.toCodePoint(
                                current,
                                value.charAt(++index)));
                    } else if (Character.isLowSurrogate(current)) {
                        throw new IllegalArgumentException(
                                "La proyección legal contiene Unicode inválido");
                    } else {
                        writeUtf8(output, current);
                    }
                }
            }
        }
        output.write('"');
    }

    private static void writeUnicodeControl(OutputStream output, char value) throws IOException {
        writeAscii(output, "\\u00");
        output.write(LOWER_HEXADECIMAL[(value >>> 4) & 0x0f]);
        output.write(LOWER_HEXADECIMAL[value & 0x0f]);
    }

    private static void writeUtf8(OutputStream output, int codePoint) throws IOException {
        if (codePoint <= 0x7f) {
            output.write(codePoint);
        } else if (codePoint <= 0x7ff) {
            output.write(0xc0 | (codePoint >>> 6));
            output.write(0x80 | (codePoint & 0x3f));
        } else if (codePoint <= 0xffff) {
            output.write(0xe0 | (codePoint >>> 12));
            output.write(0x80 | ((codePoint >>> 6) & 0x3f));
            output.write(0x80 | (codePoint & 0x3f));
        } else {
            output.write(0xf0 | (codePoint >>> 18));
            output.write(0x80 | ((codePoint >>> 12) & 0x3f));
            output.write(0x80 | ((codePoint >>> 6) & 0x3f));
            output.write(0x80 | (codePoint & 0x3f));
        }
    }

    private static void writeAscii(OutputStream output, String value) throws IOException {
        for (int index = 0; index < value.length(); index++) {
            output.write(value.charAt(index));
        }
    }

    /**
     * Bytes canónicos y su identidad SHA-256. Los bytes se copian al entrar y al salir.
     */
    public static final class CanonicalJson {

        private final byte[] utf8;
        private final String sha256;

        private CanonicalJson(byte[] utf8, String sha256) {
            this.utf8 = Objects.requireNonNull(utf8, "utf8").clone();
            this.sha256 = Objects.requireNonNull(sha256, "sha256");
        }

        public byte[] utf8() {
            return utf8.clone();
        }

        public String sha256() {
            return sha256;
        }

        public int sizeBytes() {
            return utf8.length;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof CanonicalJson canonicalJson)) {
                return false;
            }
            return sha256.equals(canonicalJson.sha256)
                    && Arrays.equals(utf8, canonicalJson.utf8);
        }

        @Override
        public int hashCode() {
            return 31 * sha256.hashCode() + Arrays.hashCode(utf8);
        }

        @Override
        public String toString() {
            return "CanonicalJson[sizeBytes=" + utf8.length + ", sha256=" + sha256 + ']';
        }
    }
}
