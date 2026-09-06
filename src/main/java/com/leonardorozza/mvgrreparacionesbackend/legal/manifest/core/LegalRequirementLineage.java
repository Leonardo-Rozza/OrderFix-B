package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoVersionLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoActoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable, indexed canonical metadata for the requirement and document lines of one observation.
 *
 * <p>Every supplied row and reference is checked, including drafts and versions beyond a current
 * target. Increasing ordinals may contain gaps: an absent integer does not establish that a
 * published version was omitted. Persistence must independently accredit interval completeness,
 * publication transitions, canonical source digests and actor ownership under its database gate.
 * This value neither proves that database observation nor authenticates a caller.</p>
 *
 * <p>No historical Markdown is carried here. A digest is validated as canonical metadata and against
 * its referenced version, not recomputed from text that this catalog does not receive.</p>
 */
public final class LegalRequirementLineage {

    public static final int MAX_INTERVAL_VERSIONS = 4_096;
    public static final int MAX_OBSERVATION_ROWS = 65_536;

    private static final int MAX_KEY_LENGTH = 100;
    private static final int SHA256_LENGTH = 64;

    private final List<RequirementLine> requirementLines;
    private final List<DocumentLine> documentLines;
    private final Map<UUID, RequirementLine> requirementLinesByVersion;
    private final Map<UUID, RequirementVersion> requirementVersions;
    private final Map<UUID, DocumentLine> documentLinesByVersion;
    private final Map<UUID, DocumentVersion> documentVersions;
    private final int rowCount;

    public LegalRequirementLineage(
            List<RequirementLine> requirementLines,
            List<DocumentLine> documentLines) {
        this.requirementLines = copyBounded(requirementLines, MAX_OBSERVATION_ROWS,
                "requirementLines");
        this.documentLines = copyBounded(documentLines, MAX_OBSERVATION_ROWS, "documentLines");

        Map<UUID, RequirementLine> indexedRequirementLines = new HashMap<>();
        Map<UUID, RequirementVersion> indexedRequirementVersions = new HashMap<>();
        Map<UUID, DocumentLine> indexedDocumentLines = new HashMap<>();
        Map<UUID, DocumentVersion> indexedDocumentVersions = new HashMap<>();
        Set<UUID> requirementLineIds = new HashSet<>();
        Set<String> requirementKeys = new HashSet<>();
        Set<UUID> documentLineIds = new HashSet<>();
        Set<String> documentKeys = new HashSet<>();
        int rows = 0;

        for (DocumentLine line : this.documentLines) {
            if (!documentLineIds.add(line.lineId()) || !documentKeys.add(line.key())) {
                throw invalid("La identidad de una línea documental está duplicada");
            }
            for (DocumentVersion version : line.versions()) {
                rows = addRows(rows, 1);
                if (indexedDocumentVersions.putIfAbsent(version.versionId(), version) != null) {
                    throw invalid("La identidad de una versión documental está duplicada");
                }
                indexedDocumentLines.put(version.versionId(), line);
            }
        }

        for (RequirementLine line : this.requirementLines) {
            if (!requirementLineIds.add(line.lineId()) || !requirementKeys.add(line.key())) {
                throw invalid("La identidad de una línea de requisito está duplicada");
            }
            for (RequirementVersion version : line.versions()) {
                rows = addRows(rows, 1 + version.documents().size());
                if (indexedRequirementVersions.putIfAbsent(version.versionId(), version) != null) {
                    throw invalid("La identidad de una versión de requisito está duplicada");
                }
                indexedRequirementLines.put(version.versionId(), line);
                for (DocumentReference reference : version.documents()) {
                    DocumentLine documentLine = indexedDocumentLines.get(reference.versionId());
                    DocumentVersion documentVersion = indexedDocumentVersions.get(reference.versionId());
                    if (documentLine == null || documentVersion == null
                            || !documentLine.key().equals(reference.key())
                            || documentLine.locale() != line.locale()
                            || !documentVersion.sha256().equals(reference.sha256())) {
                        throw invalid("La referencia documental no coincide con su línea y versión");
                    }
                    if (version.state() != EstadoVersionLegal.BORRADOR
                            && documentVersion.state() == EstadoVersionLegal.BORRADOR) {
                        throw invalid("Un requisito publicado referencia un documento borrador");
                    }
                }
            }
        }

        this.requirementLinesByVersion = Map.copyOf(indexedRequirementLines);
        this.requirementVersions = Map.copyOf(indexedRequirementVersions);
        this.documentLinesByVersion = Map.copyOf(indexedDocumentLines);
        this.documentVersions = Map.copyOf(indexedDocumentVersions);
        this.rowCount = rows;
    }

    public List<RequirementLine> requirementLines() {
        return requirementLines;
    }

    public List<DocumentLine> documentLines() {
        return documentLines;
    }

    /** Version rows plus every requirement-document reference; evidence rows are counted separately. */
    public int rowCount() {
        return rowCount;
    }

    RequirementLine requirementLine(UUID versionId) {
        return requireIndexed(requirementLinesByVersion, versionId, "línea de requisito");
    }

    RequirementVersion requirementVersion(UUID versionId) {
        return requireIndexed(requirementVersions, versionId, "versión de requisito");
    }

    DocumentLine documentLine(UUID versionId) {
        return requireIndexed(documentLinesByVersion, versionId, "línea documental");
    }

    DocumentVersion documentVersion(UUID versionId) {
        return requireIndexed(documentVersions, versionId, "versión documental");
    }

    /** A stable requirement identity; supplied versions retain their explicit increasing order. */
    public record RequirementLine(
            UUID lineId,
            String key,
            ContextoLegal context,
            LocaleLegal locale,
            TipoActoLegal actType,
            Set<AudienciaLegal> audiences,
            List<RequirementVersion> versions) {

        public RequirementLine {
            lineId = Objects.requireNonNull(lineId, "lineId");
            key = requireKey(key);
            context = Objects.requireNonNull(context, "context");
            locale = Objects.requireNonNull(locale, "locale");
            actType = Objects.requireNonNull(actType, "actType");
            audiences = Set.copyOf(Objects.requireNonNull(audiences, "audiences"));
            if (audiences.isEmpty()) {
                throw invalid("La línea de requisito no contiene audiencias");
            }
            versions = copyBounded(versions, MAX_OBSERVATION_ROWS, "versions");
            if (versions.isEmpty()) {
                throw invalid("La línea de requisito no contiene versiones");
            }
            int previousOrdinal = 0;
            Set<UUID> versionIds = new HashSet<>();
            for (RequirementVersion version : versions) {
                if (version.ordinal() <= previousOrdinal || !versionIds.add(version.versionId())) {
                    throw invalid("Las versiones del requisito no conservan identidad y orden únicos");
                }
                previousOrdinal = version.ordinal();
            }
        }
    }

    /** Metadata of a canonical requirement version, including its original document-reference order. */
    public record RequirementVersion(
            UUID versionId,
            int ordinal,
            EstadoVersionLegal state,
            boolean requiresReacceptance,
            String statementSha256,
            boolean required,
            List<DocumentReference> documents) {

        public RequirementVersion {
            versionId = Objects.requireNonNull(versionId, "versionId");
            requireOrdinal(ordinal);
            state = Objects.requireNonNull(state, "state");
            statementSha256 = requireSha256(statementSha256);
            documents = copyBounded(documents, LegalManifestLimits.MAX_DOCUMENTS_PER_REQUIREMENT,
                    "documents");
            if (state != EstadoVersionLegal.BORRADOR && documents.isEmpty()) {
                throw invalid("Un requisito publicado no contiene documentos");
            }
            Set<String> keys = new HashSet<>();
            Set<UUID> versionIds = new HashSet<>();
            for (DocumentReference document : documents) {
                if (!keys.add(document.key()) || !versionIds.add(document.versionId())) {
                    throw invalid("El requisito contiene referencias documentales duplicadas");
                }
            }
        }
    }

    /** Stable document identity, deliberately without a context filter on its historical line. */
    public record DocumentLine(
            UUID lineId,
            String key,
            LocaleLegal locale,
            TipoDocumentoLegal type,
            List<DocumentVersion> versions) {

        public DocumentLine {
            lineId = Objects.requireNonNull(lineId, "lineId");
            key = requireKey(key);
            locale = Objects.requireNonNull(locale, "locale");
            type = Objects.requireNonNull(type, "type");
            versions = copyBounded(versions, MAX_OBSERVATION_ROWS, "versions");
            if (versions.isEmpty()) {
                throw invalid("La línea documental no contiene versiones");
            }
            int previousOrdinal = 0;
            Set<UUID> versionIds = new HashSet<>();
            for (DocumentVersion version : versions) {
                if (version.ordinal() <= previousOrdinal || !versionIds.add(version.versionId())) {
                    throw invalid("Las versiones documentales no conservan identidad y orden únicos");
                }
                previousOrdinal = version.ordinal();
            }
        }
    }

    public record DocumentVersion(
            UUID versionId,
            int ordinal,
            EstadoVersionLegal state,
            boolean requiresReacceptance,
            String sha256) {

        public DocumentVersion {
            versionId = Objects.requireNonNull(versionId, "versionId");
            requireOrdinal(ordinal);
            state = Objects.requireNonNull(state, "state");
            sha256 = requireSha256(sha256);
        }
    }

    public record DocumentReference(String key, UUID versionId, String sha256) {

        public DocumentReference {
            key = requireKey(key);
            versionId = Objects.requireNonNull(versionId, "versionId");
            sha256 = requireSha256(sha256);
        }
    }

    public record EvidenceDocument(String key, UUID versionId, String sha256) {

        public EvidenceDocument {
            key = requireKey(key);
            versionId = Objects.requireNonNull(versionId, "versionId");
            sha256 = requireSha256(sha256);
        }
    }

    /** A supplied real act; actor ownership and canonical source equality are checked by the evaluator. */
    public record Acceptance(
            UUID acceptanceId,
            long userId,
            long tallerId,
            UserRole historicalRole,
            UUID requirementVersionId,
            String statementSha256,
            List<EvidenceDocument> documents,
            Instant acceptedAt) {

        public Acceptance {
            acceptanceId = Objects.requireNonNull(acceptanceId, "acceptanceId");
            if (userId <= 0 || tallerId <= 0) {
                throw invalid("La evidencia requiere identidades positivas de usuario y taller");
            }
            historicalRole = Objects.requireNonNull(historicalRole, "historicalRole");
            requirementVersionId = Objects.requireNonNull(requirementVersionId, "requirementVersionId");
            statementSha256 = requireSha256(statementSha256);
            documents = copyBounded(documents, LegalManifestLimits.MAX_DOCUMENTS_PER_REQUIREMENT,
                    "documents");
            Set<String> keys = new HashSet<>();
            Set<UUID> versionIds = new HashSet<>();
            for (EvidenceDocument document : documents) {
                if (!keys.add(document.key()) || !versionIds.add(document.versionId())) {
                    throw invalid("La evidencia contiene documentos duplicados");
                }
            }
            acceptedAt = Objects.requireNonNull(acceptedAt, "acceptedAt");
        }
    }

    /** The frozen publication-manifest schema admits exactly this ASCII key alphabet and length. */
    static String requireKey(String key) {
        String required = Objects.requireNonNull(key, "key");
        if (required.isEmpty() || required.length() > MAX_KEY_LENGTH
                || !isLowerAlphanumeric(required.charAt(0))) {
            throw invalid("La clave no respeta el contrato editorial");
        }
        for (int index = 1; index < required.length(); index++) {
            char value = required.charAt(index);
            if (!isLowerAlphanumeric(value) && value != '.' && value != '_' && value != '-') {
                throw invalid("La clave no respeta el contrato editorial");
            }
        }
        return required;
    }

    static String requireSha256(String sha256) {
        String required = Objects.requireNonNull(sha256, "sha256");
        if (required.length() != SHA256_LENGTH) {
            throw invalid("El digest no respeta SHA-256 hexadecimal minúsculo");
        }
        for (int index = 0; index < required.length(); index++) {
            char value = required.charAt(index);
            if (!((value >= '0' && value <= '9') || (value >= 'a' && value <= 'f'))) {
                throw invalid("El digest no respeta SHA-256 hexadecimal minúsculo");
            }
        }
        return required;
    }

    private static boolean isLowerAlphanumeric(char value) {
        return (value >= 'a' && value <= 'z') || (value >= '0' && value <= '9');
    }

    private static void requireOrdinal(int ordinal) {
        if (ordinal <= 0) {
            throw invalid("El ordinal de linaje debe ser positivo");
        }
    }

    private static <T> List<T> copyBounded(List<T> values, int maximum, String field) {
        List<T> required = Objects.requireNonNull(values, field);
        if (required.size() > maximum) {
            throw invalid("El catálogo de linaje excede su capacidad");
        }
        return List.copyOf(required);
    }

    private static int addRows(int count, int added) {
        if (added > MAX_OBSERVATION_ROWS - count) {
            throw invalid("El grafo de linaje excede la capacidad de una observación");
        }
        return count + added;
    }

    private static <T> T requireIndexed(Map<UUID, T> index, UUID versionId, String description) {
        T value = index.get(Objects.requireNonNull(versionId, "versionId"));
        if (value == null) {
            throw invalid("Falta una " + description + " en el catálogo de linaje");
        }
        return value;
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
