package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestLimits;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection.DocumentProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection.RequirementProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetRevisionCalculator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalManifestV1;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalManifestV1.DocumentEntry;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalManifestV1.RequirementEntry;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalManifestV1.ReviewRecord;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalManifestV1.ReviewStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalPublicationPlan;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalPublicationPlan.DocumentPlan;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalPublicationPlan.RequirementPlan;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalPublicationPlan.ScopePlan;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Writes and seals one new V27 legal graph inside the caller-owned transaction. */
final class LegalManifestGraphWriter {

    private static final String DATABASE_LOCATION = "database/legal-manifest";

    private final JdbcTemplate jdbc;
    private final LegalRequiredSetRevisionCalculator requiredSetRevisionCalculator;

    LegalManifestGraphWriter(
            JdbcTemplate jdbc,
            LegalRequiredSetRevisionCalculator requiredSetRevisionCalculator) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.requiredSetRevisionCalculator = Objects.requireNonNull(
                requiredSetRevisionCalculator,
                "requiredSetRevisionCalculator");
    }

    LegalManifestGraphReceipt writeNew(ValidatedRelease release) {
        Objects.requireNonNull(release, "release");
        LegalPublicationPlan plan = release.plan();
        requireAccreditedLimits(plan);
        OffsetDateTime transactionTime = Objects.requireNonNull(
                jdbc.queryForObject("SELECT transaction_timestamp()", OffsetDateTime.class),
                "transaction timestamp");

        Map<String, DocumentLineRow> discoveredDocumentLines =
                readDocumentLines(plan.documents(), false);
        Map<String, RequirementLineRow> discoveredRequirementLines =
                readRequirementLines(plan.manifest().requirements(), false);
        ExistingVersionLocks existingVersionLocks = lockExistingVersions(
                plan,
                discoveredDocumentLines,
                discoveredRequirementLines);

        Map<String, DocumentLineRow> documentLines =
                readDocumentLines(plan.documents(), true);
        Map<String, RequirementLineRow> requirementLines =
                readRequirementLines(plan.manifest().requirements(), true);
        Map<String, ResolvedDocument> documents = resolveDocuments(
                plan,
                documentLines,
                existingVersionLocks.documentVersionIds());
        List<ResolvedRequirement> requirements =
                resolveRequirements(
                        plan,
                        documents,
                        requirementLines,
                        existingVersionLocks.requirementVersionIds());

        TreeSet<UUID> externalPublications = new TreeSet<>();
        documents.values().forEach(document -> document.collectExternalPublications(
                externalPublications));
        requirements.forEach(requirement -> requirement.collectExternalPublications(
                externalPublications));
        lockSealedExternalPublications(externalPublications);
        verifyStableHistoricalChildren(documents, requirements);

        UUID publicationId = UUID.randomUUID();
        insertPublication(publicationId, plan, transactionTime);
        insertDocuments(publicationId, plan, documents, transactionTime);
        insertRequirements(publicationId, plan, requirements, transactionTime);
        insertScopes(publicationId, plan, requirements, transactionTime);

        int sealed = jdbc.update("""
                UPDATE legal_publicaciones
                   SET estado_construccion = 'SELLADO', sellado_en = ?
                 WHERE id = ? AND estado_construccion = 'ABIERTO'
                """, transactionTime, publicationId);
        if (sealed != 1) {
            throw blocked(DATABASE_LOCATION);
        }
        jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
        jdbc.queryForList(
                "SELECT legal_validar_publicacion_sellada(?)",
                publicationId);

        int newDocumentLines = (int) documents.values().stream()
                .filter(ResolvedDocument::newLine)
                .count();
        int newDocumentVersions = (int) documents.values().stream()
                .filter(ResolvedDocument::newVersion)
                .count();
        int newRequirementLines = (int) requirements.stream()
                .filter(ResolvedRequirement::newLine)
                .count();
        int newRequirementVersions = (int) requirements.stream()
                .filter(ResolvedRequirement::newVersion)
                .count();
        verifyNewVersionsRemainDraft(
                publicationId,
                newDocumentVersions,
                newRequirementVersions);
        PublicationSealRow publication = readSealedPublication(publicationId);
        return new LegalManifestGraphReceipt(
                publicationId,
                publication.importedAt().toInstant(),
                publication.sealedAt().toInstant(),
                plan.documentCount(),
                plan.requirementCount(),
                plan.scopeCount(),
                newDocumentLines,
                newDocumentVersions,
                plan.documentCount() - newDocumentVersions,
                newRequirementLines,
                newRequirementVersions,
                plan.requirementCount() - newRequirementVersions);
    }

    boolean usesJdbc(JdbcTemplate candidate) {
        return jdbc == candidate;
    }

    private PublicationSealRow readSealedPublication(UUID publicationId) {
        PublicationSealRow publication = querySingle("""
                SELECT estado_construccion, importado_en, sellado_en
                  FROM legal_publicaciones
                 WHERE id = ?
                 FOR SHARE
                """, (rs, rowNumber) -> new PublicationSealRow(
                        rs.getString("estado_construccion"),
                        rs.getObject("importado_en", OffsetDateTime.class),
                        rs.getObject("sellado_en", OffsetDateTime.class)),
                publicationId)
                .orElseThrow(() -> blocked("database/publication"));
        if (!"SELLADO".equals(publication.state())
                || publication.importedAt() == null
                || publication.sealedAt() == null) {
            throw blocked("database/publication");
        }
        return publication;
    }

    private Map<String, DocumentLineRow> readDocumentLines(
            List<DocumentPlan> documents,
            boolean lock) {
        TreeSet<String> keys = new TreeSet<>();
        documents.forEach(document -> keys.add(document.declaration().key()));
        Map<String, DocumentLineRow> rows = new HashMap<>();
        for (String key : keys) {
            querySingle("""
                    SELECT id, clave, tipo, locale, publicacion_intro_id
                      FROM legal_documento_lineas
                     WHERE clave = ?
                    """ + (lock ? " FOR UPDATE" : ""),
                    LegalManifestGraphWriter::mapDocumentLine,
                    key)
                    .ifPresent(row -> rows.put(key, row));
        }
        return Map.copyOf(rows);
    }

    private Map<String, RequirementLineRow> readRequirementLines(
            List<RequirementEntry> requirements,
            boolean lock) {
        TreeSet<String> keys = new TreeSet<>();
        requirements.forEach(requirement -> keys.add(requirement.key()));
        Map<String, RequirementLineRow> rows = new HashMap<>();
        for (String key : keys) {
            querySingle("""
                    SELECT id, clave, locale, contexto, tipo_acto, publicacion_intro_id
                      FROM legal_requisito_lineas
                     WHERE clave = ?
                    """ + (lock ? " FOR UPDATE" : ""),
                    LegalManifestGraphWriter::mapRequirementLine,
                    key)
                    .ifPresent(row -> rows.put(key, row));
        }
        return Map.copyOf(rows);
    }

    /**
     * Acquires the same non-mutating row lock later requested by foreign keys before any line is
     * locked. NOWAIT turns an in-flight version transition into an operational lock result instead
     * of allowing the transition's version-to-line order to form a cycle with this dry-run.
     */
    private ExistingVersionLocks lockExistingVersions(
            LegalPublicationPlan plan,
            Map<String, DocumentLineRow> documentLines,
            Map<String, RequirementLineRow> requirementLines) {
        List<VersionLock> candidates = new ArrayList<>();
        for (DocumentPlan document : plan.documents()) {
            DocumentLineRow line = documentLines.get(document.declaration().key());
            if (line == null) {
                continue;
            }
            querySingle("""
                    SELECT id
                      FROM legal_documento_versiones
                     WHERE documento_linea_id = ? AND version = ?
                    """, (rs, rowNumber) -> rs.getObject(1, UUID.class),
                    line.id(), document.declaration().version())
                    .ifPresent(id -> candidates.add(new VersionLock(VersionKind.DOCUMENT, id)));
        }
        for (RequirementEntry requirement : plan.manifest().requirements()) {
            RequirementLineRow line = requirementLines.get(requirement.key());
            if (line == null) {
                continue;
            }
            querySingle("""
                    SELECT id
                      FROM legal_requisito_versiones
                     WHERE requisito_linea_id = ? AND version = ?
                    """, (rs, rowNumber) -> rs.getObject(1, UUID.class),
                    line.id(), requirement.version())
                    .ifPresent(id -> candidates.add(new VersionLock(VersionKind.REQUIREMENT, id)));
        }

        candidates.sort(java.util.Comparator
                .comparing(VersionLock::id)
                .thenComparing(lock -> lock.kind().name()));
        Set<UUID> documentVersionIds = new java.util.HashSet<>();
        Set<UUID> requirementVersionIds = new java.util.HashSet<>();
        for (VersionLock candidate : candidates) {
            String table = candidate.kind() == VersionKind.DOCUMENT
                    ? "legal_documento_versiones"
                    : "legal_requisito_versiones";
            Optional<UUID> locked = querySingle(
                    "SELECT id FROM " + table + " WHERE id = ? FOR KEY SHARE NOWAIT",
                    (rs, rowNumber) -> rs.getObject(1, UUID.class),
                    candidate.id());
            if (locked.isEmpty()) {
                throw concurrentGraphChange();
            }
            if (candidate.kind() == VersionKind.DOCUMENT) {
                documentVersionIds.add(candidate.id());
            } else {
                requirementVersionIds.add(candidate.id());
            }
        }
        return new ExistingVersionLocks(
                Set.copyOf(documentVersionIds),
                Set.copyOf(requirementVersionIds));
    }

    private Map<String, ResolvedDocument> resolveDocuments(
            LegalPublicationPlan plan,
            Map<String, DocumentLineRow> existingLines,
            Set<UUID> lockedVersionIds) {
        Map<String, ResolvedDocument> resolved = new LinkedHashMap<>();
        for (DocumentPlan document : plan.documents()) {
            DocumentEntry declaration = document.declaration();
            String location = "database/documents/" + document.manifestOrdinal();
            requirePostgresTimestampPrecision(declaration.effectiveAt(), location);
            DocumentLineRow line = existingLines.get(declaration.key());
            if (line == null) {
                resolved.put(declaration.key(), new ResolvedDocument(
                        document,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null,
                        null,
                        1,
                        true,
                        true));
                continue;
            }
            if (!line.type().equals(declaration.type().name())
                    || !line.locale().equals(declaration.locale().getCodigo())) {
                throw blocked(location);
            }

            Optional<DocumentVersionRow> existingVersion = querySingle("""
                    SELECT id, publicacion_intro_id, lineage_ordinal, titulo,
                           contenido_markdown, sha256, vigente_desde, requires_reacceptance
                      FROM legal_documento_versiones
                     WHERE documento_linea_id = ? AND version = ?
                    """, LegalManifestGraphWriter::mapDocumentVersion,
                    line.id(), declaration.version());

            if (existingVersion.isPresent()) {
                DocumentVersionRow version = existingVersion.orElseThrow();
                requirePrelockedVersion(version.id(), lockedVersionIds);
                if (!version.title().equals(document.title())
                        || !version.markdown().equals(document.markdown())
                        || !version.sha256().equals(declaration.sha256())
                        || !sameInstant(version.effectiveAt(), declaration.effectiveAt())
                        || version.requiresReacceptance() != declaration.requiresReacceptance()) {
                    throw blocked(location);
                }
                resolved.put(declaration.key(), new ResolvedDocument(
                        document,
                        line.id(),
                        version.id(),
                        line.introPublicationId(),
                        version.introPublicationId(),
                        version.lineageOrdinal(),
                        false,
                        false));
            } else {
                resolved.put(declaration.key(), new ResolvedDocument(
                        document,
                        line.id(),
                        UUID.randomUUID(),
                        line.introPublicationId(),
                        null,
                        nextDocumentLineageOrdinal(line.id(), location),
                        false,
                        true));
            }
        }
        return Map.copyOf(resolved);
    }

    private List<ResolvedRequirement> resolveRequirements(
            LegalPublicationPlan plan,
            Map<String, ResolvedDocument> documents,
            Map<String, RequirementLineRow> existingLines,
            Set<UUID> lockedVersionIds) {
        List<ResolvedRequirement> resolved = new ArrayList<>(plan.requirementCount());
        List<RequirementEntry> declarations = plan.manifest().requirements();
        for (int index = 0; index < declarations.size(); index++) {
            RequirementEntry declaration = declarations.get(index);
            String location = "database/requirements/" + index;
            List<ResolvedDocument> referencedDocuments = declaration.documents().stream()
                    .map(documents::get)
                    .map(document -> Objects.requireNonNull(document, "resolved document"))
                    .toList();
            RequirementLineRow line = existingLines.get(declaration.key());
            if (line == null) {
                resolved.add(new ResolvedRequirement(
                        index,
                        declaration,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null,
                        null,
                        1,
                        true,
                        true,
                        referencedDocuments));
                continue;
            }

            if (!line.locale().equals(plan.manifest().locale().getCodigo())
                    || !line.context().equals(declaration.context().name())
                    || !line.actType().equals(declaration.actType().name())) {
                throw blocked(location);
            }

            Optional<RequirementVersionRow> existingVersion = querySingle("""
                    SELECT id, publicacion_intro_id, lineage_ordinal, afirmacion,
                           afirmacion_sha256, requerido, requires_reacceptance
                      FROM legal_requisito_versiones
                     WHERE requisito_linea_id = ? AND version = ?
                    """, LegalManifestGraphWriter::mapRequirementVersion,
                    line.id(), declaration.version());
            if (existingVersion.isPresent()) {
                RequirementVersionRow version = existingVersion.orElseThrow();
                requirePrelockedVersion(version.id(), lockedVersionIds);
                if (!version.statement().equals(declaration.statement())
                        || !version.statementSha256().equals(declaration.statementSha256())
                        || version.required() != declaration.required()
                        || version.requiresReacceptance() != declaration.requiresReacceptance()) {
                    throw blocked(location);
                }
                resolved.add(new ResolvedRequirement(
                        index,
                        declaration,
                        line.id(),
                        version.id(),
                        line.introPublicationId(),
                        version.introPublicationId(),
                        version.lineageOrdinal(),
                        false,
                        false,
                        referencedDocuments));
            } else {
                resolved.add(new ResolvedRequirement(
                        index,
                        declaration,
                        line.id(),
                        UUID.randomUUID(),
                        line.introPublicationId(),
                        null,
                        nextRequirementLineageOrdinal(line.id(), location),
                        false,
                        true,
                        referencedDocuments));
            }
        }
        return List.copyOf(resolved);
    }

    private void lockSealedExternalPublications(Set<UUID> publicationIds) {
        for (UUID publicationId : publicationIds) {
            Optional<String> state = querySingle("""
                    SELECT estado_construccion
                      FROM legal_publicaciones
                     WHERE id = ?
                     FOR SHARE
                    """, (rs, rowNumber) -> rs.getString(1), publicationId);
            if (state.isEmpty() || !"SELLADO".equals(state.orElseThrow())) {
                throw blocked("database/dependencies");
            }
        }
    }

    /**
     * Reads append-only child sets only after every introductory publication is locked SELLADO.
     * This prevents accepting a set that changes between comparison and the external seal.
     */
    private void verifyStableHistoricalChildren(
            Map<String, ResolvedDocument> documents,
            List<ResolvedRequirement> requirements) {
        for (ResolvedDocument document : documents.values().stream()
                .sorted(java.util.Comparator.comparingInt(
                        candidate -> candidate.plan().manifestOrdinal()))
                .toList()) {
            if (document.newVersion()) {
                continue;
            }
            String location = "database/documents/" + document.plan().manifestOrdinal();
            Set<String> contexts = Set.copyOf(jdbc.queryForList("""
                    SELECT contexto
                      FROM legal_documento_contextos
                     WHERE documento_version_id = ?
                    """, String.class, document.versionId()));
            if (!contexts.equals(enumNameSet(document.plan().declaration().contexts()))) {
                throw blocked(location);
            }
        }

        for (ResolvedRequirement requirement : requirements) {
            String location = "database/requirements/" + requirement.manifestOrdinal();
            if (!requirement.newLine()) {
                Set<String> audiences = Set.copyOf(jdbc.queryForList("""
                        SELECT audiencia
                          FROM legal_requisito_audiencias
                         WHERE requisito_linea_id = ?
                        """, String.class, requirement.lineId()));
                if (!audiences.equals(enumNameSet(requirement.declaration().roles()))) {
                    throw blocked(location);
                }
            }
            if (!requirement.newVersion()) {
                List<RequirementDocumentRow> relations = jdbc.query("""
                        SELECT documento_ordinal, documento_version_id
                          FROM legal_requisito_documentos
                         WHERE requisito_version_id = ?
                         ORDER BY documento_ordinal
                        """, LegalManifestGraphWriter::mapRequirementDocument,
                        requirement.versionId());
                boolean relationMatches = relations.size() == requirement.documents().size();
                for (int index = 0; relationMatches && index < relations.size(); index++) {
                    RequirementDocumentRow relation = relations.get(index);
                    relationMatches = relation.ordinal() == index + 1
                            && relation.documentVersionId().equals(
                                    requirement.documents().get(index).versionId());
                }
                if (!relationMatches) {
                    throw blocked(location);
                }
            }
        }
    }

    private int nextDocumentLineageOrdinal(UUID lineId, String location) {
        Long next = jdbc.queryForObject("""
                SELECT COALESCE(MAX(lineage_ordinal)::bigint, 0) + 1
                  FROM legal_documento_versiones
                 WHERE documento_linea_id = ?
                """, Long.class, lineId);
        return requireDatabaseOrdinal(next, location);
    }

    private int nextRequirementLineageOrdinal(UUID lineId, String location) {
        Long next = jdbc.queryForObject("""
                SELECT COALESCE(MAX(lineage_ordinal)::bigint, 0) + 1
                  FROM legal_requisito_versiones
                 WHERE requisito_linea_id = ?
                """, Long.class, lineId);
        return requireDatabaseOrdinal(next, location);
    }

    private static int requireDatabaseOrdinal(Long candidate, String location) {
        if (candidate == null || candidate < 1 || candidate > Integer.MAX_VALUE) {
            throw blocked(location);
        }
        return candidate.intValue();
    }

    private void insertPublication(
            UUID publicationId,
            LegalPublicationPlan plan,
            OffsetDateTime transactionTime) {
        LegalManifestV1 manifest = plan.manifest();
        LegalManifestV1.PublisherSnapshot publisher = manifest.publisherSnapshot();
        LegalManifestV1.Contacts contacts = publisher.contacts();
        ReviewRecord legalReview = manifest.review().legal();
        ReviewRecord accountingReview = manifest.review().accounting();
        requirePostgresTimestampPrecision(legalReview.reviewedAt(), "database/publication");
        requirePostgresTimestampPrecision(accountingReview.reviewedAt(), "database/publication");
        jdbc.update("""
                INSERT INTO legal_publicaciones
                    (id, publication_external_id, schema_version, locale,
                     manifest_sha256, manifest_canonico, razon_social, cuit,
                     domicilio_legal, jurisdiccion, horario_atencion,
                     email_legal, email_privacidad, email_soporte,
                     revision_legal_estado, revision_legal_referencia, revision_legal_en,
                     revision_contable_estado, revision_contable_referencia,
                     revision_contable_en, estado_construccion, importado_en, sellado_en)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        'ABIERTO', ?, NULL)
                """,
                publicationId,
                manifest.publicationId(),
                manifest.schemaVersion(),
                manifest.locale().getCodigo(),
                plan.manifestSha256(),
                plan.canonicalJson(),
                publisher.legalName(),
                publisher.taxId().replace("-", ""),
                publisher.legalAddress(),
                publisher.jurisdiction(),
                publisher.businessHours(),
                contacts.legalEmail(),
                contacts.privacyEmail(),
                contacts.supportEmail(),
                databaseReviewStatus(legalReview.status()),
                legalReview.reference(),
                legalReview.reviewedAt(),
                databaseReviewStatus(accountingReview.status()),
                accountingReview.reference(),
                accountingReview.reviewedAt(),
                transactionTime);
    }

    private void insertDocuments(
            UUID publicationId,
            LegalPublicationPlan plan,
            Map<String, ResolvedDocument> documents,
            OffsetDateTime transactionTime) {
        // Missing keys cannot be gap-locked by PostgreSQL. A stable key order prevents two
        // overlapping dry-runs from taking inverse unique-index waits while inserting them.
        List<DocumentPlan> insertionOrder = plan.documents().stream()
                .sorted(Comparator.comparing(document -> document.declaration().key()))
                .toList();
        for (DocumentPlan documentPlan : insertionOrder) {
            ResolvedDocument document = documents.get(documentPlan.declaration().key());
            DocumentEntry declaration = documentPlan.declaration();
            if (document.newLine()) {
                jdbc.update("""
                        INSERT INTO legal_documento_lineas
                            (id, clave, tipo, locale, publicacion_intro_id, creado_en)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """, document.lineId(), declaration.key(), declaration.type().name(),
                        declaration.locale().getCodigo(), publicationId, transactionTime);
            }
            if (document.newVersion()) {
                jdbc.update("""
                        INSERT INTO legal_documento_versiones
                            (id, documento_linea_id, publicacion_intro_id, version,
                             lineage_ordinal, titulo, contenido_markdown, sha256,
                             vigente_desde, requires_reacceptance)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, document.versionId(), document.lineId(), publicationId,
                        declaration.version(), document.lineageOrdinal(), documentPlan.title(),
                        documentPlan.markdown(), declaration.sha256(), declaration.effectiveAt(),
                        declaration.requiresReacceptance());
                for (var context : declaration.contexts()) {
                    jdbc.update("""
                            INSERT INTO legal_documento_contextos
                                (documento_version_id, contexto)
                            VALUES (?, ?)
                            """, document.versionId(), context.name());
                }
            }
            jdbc.update("""
                    INSERT INTO legal_publicacion_documentos
                        (publicacion_id, documento_version_id, manifest_ordinal)
                    VALUES (?, ?, ?)
                    """, publicationId, document.versionId(), documentPlan.manifestOrdinal() + 1);
        }
    }

    private void insertRequirements(
            UUID publicationId,
            LegalPublicationPlan plan,
            List<ResolvedRequirement> requirements,
            OffsetDateTime transactionTime) {
        // Apply the same unique-index lock order to requirement identities.
        List<ResolvedRequirement> insertionOrder = requirements.stream()
                .sorted(Comparator.comparing(requirement -> requirement.declaration().key()))
                .toList();
        for (ResolvedRequirement requirement : insertionOrder) {
            RequirementEntry declaration = requirement.declaration();
            if (requirement.newLine()) {
                jdbc.update("""
                        INSERT INTO legal_requisito_lineas
                            (id, clave, locale, contexto, tipo_acto,
                             publicacion_intro_id, creado_en)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """, requirement.lineId(), declaration.key(),
                        plan.manifest().locale().getCodigo(), declaration.context().name(),
                        declaration.actType().name(), publicationId, transactionTime);
                for (var audience : declaration.roles()) {
                    jdbc.update("""
                            INSERT INTO legal_requisito_audiencias
                                (requisito_linea_id, audiencia)
                            VALUES (?, ?)
                            """, requirement.lineId(), audience.name());
                }
            }
            if (requirement.newVersion()) {
                jdbc.update("""
                        INSERT INTO legal_requisito_versiones
                            (id, requisito_linea_id, publicacion_intro_id, version,
                             lineage_ordinal, afirmacion, afirmacion_sha256, requerido,
                             requires_reacceptance)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, requirement.versionId(), requirement.lineId(), publicationId,
                        declaration.version(), requirement.lineageOrdinal(), declaration.statement(),
                        declaration.statementSha256(), declaration.required(),
                        declaration.requiresReacceptance());
                for (int index = 0; index < requirement.documents().size(); index++) {
                    jdbc.update("""
                            INSERT INTO legal_requisito_documentos
                                (requisito_version_id, documento_version_id, documento_ordinal)
                            VALUES (?, ?, ?)
                            """, requirement.versionId(),
                            requirement.documents().get(index).versionId(), index + 1);
                }
            }
            jdbc.update("""
                    INSERT INTO legal_publicacion_requisitos
                        (publicacion_id, requisito_version_id, manifest_ordinal)
                    VALUES (?, ?, ?)
                    """, publicationId, requirement.versionId(), requirement.manifestOrdinal() + 1);
        }
    }

    private void insertScopes(
            UUID publicationId,
            LegalPublicationPlan plan,
            List<ResolvedRequirement> requirements,
            OffsetDateTime transactionTime) {
        Map<Integer, ResolvedRequirement> byOrdinal = new HashMap<>();
        requirements.forEach(requirement ->
                byOrdinal.put(requirement.manifestOrdinal(), requirement));
        for (ScopePlan scope : plan.scopes()) {
            UUID snapshotId = UUID.randomUUID();
            String requiredSetRevision = requiredSetRevisionCalculator.calculate(
                    projectScope(scope, byOrdinal));
            jdbc.update("""
                    INSERT INTO legal_requisito_conjuntos
                        (id, publicacion_id, locale, contexto, audiencia,
                         required_set_revision, creado_en)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, snapshotId, publicationId, scope.locale().getCodigo(),
                    scope.context().name(), scope.audience().name(),
                    requiredSetRevision, transactionTime);
            for (RequirementPlan member : scope.requirements()) {
                ResolvedRequirement requirement = Objects.requireNonNull(
                        byOrdinal.get(member.manifestOrdinal()), "resolved scope requirement");
                jdbc.update("""
                        INSERT INTO legal_requisito_conjunto_miembros
                            (conjunto_id, publicacion_id, requisito_version_id,
                             requisito_linea_id, manifest_ordinal)
                        VALUES (?, ?, ?, ?, ?)
                        """, snapshotId, publicationId, requirement.versionId(),
                        requirement.lineId(), member.manifestOrdinal() + 1);
            }
        }
    }

    private void verifyNewVersionsRemainDraft(
            UUID publicationId,
            int expectedDocumentVersions,
            int expectedRequirementVersions) {
        Boolean documentsRemainDraft = jdbc.queryForObject("""
                SELECT count(*) = ?
                   AND count(*) FILTER (
                       WHERE estado = 'BORRADOR'
                         AND estado_cambiado_en IS NULL
                         AND ultimo_motivo IS NULL
                   ) = ?
                  FROM legal_documento_versiones
                 WHERE publicacion_intro_id = ?
                """, Boolean.class,
                expectedDocumentVersions,
                expectedDocumentVersions,
                publicationId);
        Boolean requirementsRemainDraft = jdbc.queryForObject("""
                SELECT count(*) = ?
                   AND count(*) FILTER (
                       WHERE estado = 'BORRADOR'
                         AND estado_cambiado_en IS NULL
                         AND ultimo_motivo IS NULL
                   ) = ?
                  FROM legal_requisito_versiones
                 WHERE publicacion_intro_id = ?
                """, Boolean.class,
                expectedRequirementVersions,
                expectedRequirementVersions,
                publicationId);
        if (!Boolean.TRUE.equals(documentsRemainDraft)
                || !Boolean.TRUE.equals(requirementsRemainDraft)) {
            throw new LegalDryRunBlockedException(LegalManifestIssue.at(
                    LegalManifestIssueCode.DB_CONSTRAINT,
                    "database/publication"));
        }
    }

    private static LegalRequiredSetProjection projectScope(
            ScopePlan scope,
            Map<Integer, ResolvedRequirement> requirementsByOrdinal) {
        List<RequirementProjection> requirements = scope.requirements().stream()
                .sorted(Comparator.comparingInt(RequirementPlan::manifestOrdinal))
                .map(member -> Objects.requireNonNull(
                        requirementsByOrdinal.get(member.manifestOrdinal()),
                        "resolved scope requirement"))
                .map(LegalManifestGraphWriter::projectRequirement)
                .toList();
        return new LegalRequiredSetProjection(scope.context(), scope.locale(), requirements);
    }

    private static RequirementProjection projectRequirement(
            ResolvedRequirement requirement) {
        RequirementEntry declaration = requirement.declaration();
        List<DocumentProjection> documents = requirement.documents().stream()
                .map(LegalManifestGraphWriter::projectDocument)
                .toList();
        return new RequirementProjection(
                requirement.versionId(),
                declaration.context(),
                declaration.actType(),
                declaration.statement(),
                declaration.statementSha256(),
                documents,
                declaration.required());
    }

    private static DocumentProjection projectDocument(ResolvedDocument document) {
        DocumentPlan plan = document.plan();
        DocumentEntry declaration = plan.declaration();
        return new DocumentProjection(
                document.versionId(),
                declaration.type(),
                declaration.version(),
                plan.title(),
                plan.markdown(),
                declaration.sha256(),
                declaration.effectiveAt(),
                declaration.locale());
    }

    private static String databaseReviewStatus(ReviewStatus status) {
        return switch (status) {
            case PENDING -> "PENDIENTE";
            case APPROVED -> "APROBADA";
        };
    }

    private static boolean sameInstant(OffsetDateTime left, OffsetDateTime right) {
        return left.toInstant().equals(right.toInstant());
    }

    private static Set<String> enumNameSet(List<? extends Enum<?>> values) {
        return values.stream()
                .map(Enum::name)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static void requirePostgresTimestampPrecision(
            OffsetDateTime timestamp,
            String location) {
        if (timestamp != null && timestamp.getNano() % 1_000 != 0) {
            throw new LegalDryRunBlockedException(LegalManifestIssue.at(
                    LegalManifestIssueCode.DB_CONSTRAINT,
                    location));
        }
    }

    private static void requireAccreditedLimits(LegalPublicationPlan plan) {
        if (plan.documents().size() > LegalManifestLimits.MAX_DOCUMENTS
                || plan.manifest().requirements().size() > LegalManifestLimits.MAX_REQUIREMENTS
                || plan.manifest().requirements().stream().anyMatch(requirement ->
                        requirement.documents().size()
                                > LegalManifestLimits.MAX_DOCUMENTS_PER_REQUIREMENT)) {
            throw new LegalDryRunBlockedException(LegalManifestIssue.at(
                    LegalManifestIssueCode.DB_CONSTRAINT,
                    DATABASE_LOCATION));
        }
    }

    private <T> Optional<T> querySingle(String sql, RowMapper<T> mapper, Object... arguments) {
        List<T> rows = jdbc.query(sql, mapper, arguments);
        if (rows.size() > 1) {
            throw new IllegalStateException("La consulta de identidad legal no fue única");
        }
        return rows.stream().findFirst();
    }

    private static DocumentLineRow mapDocumentLine(ResultSet rs, int rowNumber)
            throws SQLException {
        return new DocumentLineRow(
                rs.getObject("id", UUID.class),
                rs.getString("clave"),
                rs.getString("tipo"),
                rs.getString("locale"),
                rs.getObject("publicacion_intro_id", UUID.class));
    }

    private static DocumentVersionRow mapDocumentVersion(ResultSet rs, int rowNumber)
            throws SQLException {
        return new DocumentVersionRow(
                rs.getObject("id", UUID.class),
                rs.getObject("publicacion_intro_id", UUID.class),
                rs.getInt("lineage_ordinal"),
                rs.getString("titulo"),
                rs.getString("contenido_markdown"),
                rs.getString("sha256"),
                rs.getObject("vigente_desde", OffsetDateTime.class),
                rs.getBoolean("requires_reacceptance"));
    }

    private static RequirementLineRow mapRequirementLine(ResultSet rs, int rowNumber)
            throws SQLException {
        return new RequirementLineRow(
                rs.getObject("id", UUID.class),
                rs.getString("clave"),
                rs.getString("locale"),
                rs.getString("contexto"),
                rs.getString("tipo_acto"),
                rs.getObject("publicacion_intro_id", UUID.class));
    }

    private static RequirementVersionRow mapRequirementVersion(ResultSet rs, int rowNumber)
            throws SQLException {
        return new RequirementVersionRow(
                rs.getObject("id", UUID.class),
                rs.getObject("publicacion_intro_id", UUID.class),
                rs.getInt("lineage_ordinal"),
                rs.getString("afirmacion"),
                rs.getString("afirmacion_sha256"),
                rs.getBoolean("requerido"),
                rs.getBoolean("requires_reacceptance"));
    }

    private static RequirementDocumentRow mapRequirementDocument(ResultSet rs, int rowNumber)
            throws SQLException {
        return new RequirementDocumentRow(
                rs.getInt("documento_ordinal"),
                rs.getObject("documento_version_id", UUID.class));
    }

    private static LegalDryRunBlockedException blocked(String location) {
        return new LegalDryRunBlockedException(LegalManifestIssue.at(
                LegalManifestIssueCode.DB_PERSISTED_CONFLICT,
                location));
    }

    private static void requirePrelockedVersion(UUID versionId, Set<UUID> lockedVersionIds) {
        if (!lockedVersionIds.contains(versionId)) {
            throw concurrentGraphChange();
        }
    }

    private static LegalDryRunOperationalException concurrentGraphChange() {
        return new LegalDryRunOperationalException(
                LegalManifestIssueCode.DB_CONCURRENCY,
                "database");
    }

    private record DocumentLineRow(
            UUID id,
            String key,
            String type,
            String locale,
            UUID introPublicationId
    ) { }

    private record DocumentVersionRow(
            UUID id,
            UUID introPublicationId,
            int lineageOrdinal,
            String title,
            String markdown,
            String sha256,
            OffsetDateTime effectiveAt,
            boolean requiresReacceptance
    ) { }

    private record RequirementLineRow(
            UUID id,
            String key,
            String locale,
            String context,
            String actType,
            UUID introPublicationId
    ) { }

    private record RequirementVersionRow(
            UUID id,
            UUID introPublicationId,
            int lineageOrdinal,
            String statement,
            String statementSha256,
            boolean required,
            boolean requiresReacceptance
    ) { }

    private record RequirementDocumentRow(int ordinal, UUID documentVersionId) { }

    private record PublicationSealRow(
            String state,
            OffsetDateTime importedAt,
            OffsetDateTime sealedAt
    ) { }

    private enum VersionKind {
        DOCUMENT,
        REQUIREMENT
    }

    private record VersionLock(VersionKind kind, UUID id) { }

    private record ExistingVersionLocks(
            Set<UUID> documentVersionIds,
            Set<UUID> requirementVersionIds
    ) { }

    private record ResolvedDocument(
            DocumentPlan plan,
            UUID lineId,
            UUID versionId,
            UUID lineIntroPublicationId,
            UUID versionIntroPublicationId,
            int lineageOrdinal,
            boolean newLine,
            boolean newVersion
    ) {
        void collectExternalPublications(Set<UUID> target) {
            if (lineIntroPublicationId != null) {
                target.add(lineIntroPublicationId);
            }
            if (versionIntroPublicationId != null) {
                target.add(versionIntroPublicationId);
            }
        }
    }

    private record ResolvedRequirement(
            int manifestOrdinal,
            RequirementEntry declaration,
            UUID lineId,
            UUID versionId,
            UUID lineIntroPublicationId,
            UUID versionIntroPublicationId,
            int lineageOrdinal,
            boolean newLine,
            boolean newVersion,
            List<ResolvedDocument> documents
    ) {
        ResolvedRequirement {
            documents = List.copyOf(documents);
        }

        void collectExternalPublications(Set<UUID> target) {
            if (lineIntroPublicationId != null) {
                target.add(lineIntroPublicationId);
            }
            if (versionIntroPublicationId != null) {
                target.add(versionIntroPublicationId);
            }
        }
    }
}
