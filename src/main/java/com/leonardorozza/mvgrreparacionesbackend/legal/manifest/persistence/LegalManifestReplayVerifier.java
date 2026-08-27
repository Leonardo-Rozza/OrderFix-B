package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
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
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalPublicationPlan.ScopePlan;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoActoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal;
import org.springframework.dao.DataIntegrityViolationException;
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
import java.util.UUID;

/** Reaccredits one already sealed publication without invoking the graph writer. */
final class LegalManifestReplayVerifier {

    private static final String PUBLICATION_LOCATION = "database/publication";

    private final JdbcTemplate jdbc;
    private final LegalRequiredSetRevisionCalculator requiredSetRevisionCalculator;

    LegalManifestReplayVerifier(
            JdbcTemplate jdbc,
            LegalRequiredSetRevisionCalculator requiredSetRevisionCalculator) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.requiredSetRevisionCalculator = Objects.requireNonNull(
                requiredSetRevisionCalculator,
                "requiredSetRevisionCalculator");
    }

    /**
     * Verifies the immutable origin graph and rebuilds the original import receipt.
     *
     * <p>The caller must invoke this method inside the shared database gate after resolving the
     * natural publication identity. Every statement issued here is a {@code SELECT}; database
     * failures deliberately escape so the surrounding transaction can roll back before mapping.
     */
    LegalManifestGraphReceipt verify(ValidatedRelease release, UUID publicationId) {
        Objects.requireNonNull(release, "release");
        Objects.requireNonNull(publicationId, "publicationId");
        LegalPublicationPlan plan = release.plan();

        PublicationRow publication = readPublication(publicationId);
        requireExactHeader(plan, publication);
        validateSealedPublication(publicationId);

        List<DocumentRow> documents = readDocuments(publicationId, plan.documentCount());
        Map<Integer, List<DocumentContextRow>> contexts = readDocumentContexts(
                publicationId,
                expectedDocumentContexts(plan));
        Map<String, DocumentRow> documentsByKey = verifyDocuments(
                plan,
                documents,
                contexts);

        List<RequirementRow> requirements = readRequirements(
                publicationId,
                plan.requirementCount());
        Map<Integer, List<RequirementAudienceRow>> audiences = readRequirementAudiences(
                publicationId,
                expectedRequirementAudiences(plan));
        Map<Integer, List<RequirementDocumentRow>> requirementDocuments =
                readRequirementDocuments(
                        publicationId,
                        expectedRequirementDocuments(plan));
        Map<Integer, RequirementRow> requirementsByOrdinal = verifyRequirements(
                plan,
                requirements,
                audiences,
                requirementDocuments,
                documentsByKey);

        List<ScopeRow> scopes = readScopes(publicationId, plan.scopeCount());
        Map<ScopeKey, List<ScopeMemberRow>> members = readScopeMembers(
                publicationId,
                expectedScopeMembers(plan));
        verifyScopes(
                plan,
                publicationId,
                scopes,
                members,
                requirementsByOrdinal,
                requirementDocuments,
                documentsByKey);

        int newDocumentLines = countDocumentIntroductions(
                documents,
                publicationId,
                true);
        int newDocumentVersions = countDocumentIntroductions(
                documents,
                publicationId,
                false);
        int newRequirementLines = countRequirementIntroductions(
                requirements,
                publicationId,
                true);
        int newRequirementVersions = countRequirementIntroductions(
                requirements,
                publicationId,
                false);
        if (newDocumentLines > newDocumentVersions
                || newRequirementLines > newRequirementVersions) {
            conflict(PUBLICATION_LOCATION);
        }

        return new LegalManifestGraphReceipt(
                publication.id(),
                publication.importedAt().toInstant(),
                publication.sealedAt().toInstant(),
                documents.size(),
                requirements.size(),
                scopes.size(),
                newDocumentLines,
                newDocumentVersions,
                documents.size() - newDocumentVersions,
                newRequirementLines,
                newRequirementVersions,
                requirements.size() - newRequirementVersions);
    }

    boolean usesJdbc(JdbcTemplate candidate) {
        return jdbc == candidate;
    }

    private void validateSealedPublication(UUID publicationId) {
        try {
            jdbc.queryForList(
                    "SELECT legal_validar_publicacion_sellada(?)",
                    publicationId);
        } catch (DataIntegrityViolationException failure) {
            throw new LegalImportBlockedException(
                    LegalManifestIssueCode.IMPORT_DB_PERSISTED_CONFLICT,
                    PUBLICATION_LOCATION,
                    failure);
        }
    }

    private PublicationRow readPublication(UUID publicationId) {
        return querySingle("""
                SELECT id, publication_external_id, schema_version, locale,
                       manifest_sha256, manifest_canonico, razon_social, cuit,
                       domicilio_legal, jurisdiccion, horario_atencion,
                       email_legal, email_privacidad, email_soporte,
                       revision_legal_estado, revision_legal_referencia, revision_legal_en,
                       revision_contable_estado, revision_contable_referencia,
                       revision_contable_en, estado_construccion, importado_en, sellado_en
                  FROM legal_publicaciones
                 WHERE id = ?
                 FOR UPDATE
                """, LegalManifestReplayVerifier::mapPublication, publicationId)
                .orElseThrow(() -> blocked(PUBLICATION_LOCATION));
    }

    private static void requireExactHeader(
            LegalPublicationPlan plan,
            PublicationRow actual) {
        LegalManifestV1 manifest = plan.manifest();
        LegalManifestV1.PublisherSnapshot publisher = manifest.publisherSnapshot();
        LegalManifestV1.Contacts contacts = publisher.contacts();
        ReviewRecord legalReview = manifest.review().legal();
        ReviewRecord accountingReview = manifest.review().accounting();
        boolean exact = "SELLADO".equals(actual.constructionState())
                && actual.publicationExternalId().equals(manifest.publicationId())
                && actual.schemaVersion() == manifest.schemaVersion()
                && actual.locale().equals(manifest.locale().getCodigo())
                && actual.manifestSha256().equals(plan.manifestSha256())
                && actual.canonicalManifest().equals(plan.canonicalJson())
                && actual.legalName().equals(publisher.legalName())
                && actual.taxId().equals(publisher.taxId().replace("-", ""))
                && actual.legalAddress().equals(publisher.legalAddress())
                && actual.jurisdiction().equals(publisher.jurisdiction())
                && actual.businessHours().equals(publisher.businessHours())
                && actual.legalEmail().equals(contacts.legalEmail())
                && actual.privacyEmail().equals(contacts.privacyEmail())
                && actual.supportEmail().equals(contacts.supportEmail())
                && exactReview(
                        actual.legalReviewStatus(),
                        actual.legalReviewReference(),
                        actual.legalReviewedAt(),
                        legalReview)
                && exactReview(
                        actual.accountingReviewStatus(),
                        actual.accountingReviewReference(),
                        actual.accountingReviewedAt(),
                        accountingReview)
                && validReceiptTimes(actual.importedAt(), actual.sealedAt());
        if (!exact) {
            conflict(PUBLICATION_LOCATION);
        }
    }

    private Map<String, DocumentRow> verifyDocuments(
            LegalPublicationPlan plan,
            List<DocumentRow> actual,
            Map<Integer, List<DocumentContextRow>> contexts) {
        if (actual.size() != plan.documents().size()
                || contexts.keySet().stream().anyMatch(ordinal ->
                        ordinal < 1 || ordinal > actual.size())) {
            conflict(PUBLICATION_LOCATION);
        }

        Map<String, DocumentRow> byKey = new LinkedHashMap<>();
        Map<UUID, String> versionKeys = new HashMap<>();
        for (int index = 0; index < plan.documents().size(); index++) {
            DocumentPlan expected = plan.documents().get(index);
            DocumentEntry declaration = expected.declaration();
            DocumentRow row = actual.get(index);
            String location = "database/documents/" + index;
            boolean exact = row.manifestOrdinal() == index + 1
                    && row.lineId() != null
                    && row.versionId() != null
                    && row.lineIntroPublicationId() != null
                    && row.versionIntroPublicationId() != null
                    && row.lineageOrdinal() > 0
                    && row.key().equals(declaration.key())
                    && row.type().equals(declaration.type().name())
                    && row.locale().equals(declaration.locale().getCodigo())
                    && row.version().equals(declaration.version())
                    && row.title().equals(expected.title())
                    && row.markdown().equals(expected.markdown())
                    && row.sha256().equals(declaration.sha256())
                    && sameInstant(row.effectiveAt(), declaration.effectiveAt())
                    && row.requiresReacceptance() == declaration.requiresReacceptance()
                    && exactDocumentContexts(
                            contexts.getOrDefault(index + 1, List.of()),
                            row.versionId(),
                            declaration);
            if (!exact
                    || byKey.put(declaration.key(), row) != null
                    || versionKeys.put(row.versionId(), declaration.key()) != null) {
                conflict(location);
            }
        }
        return Map.copyOf(byKey);
    }

    private Map<Integer, RequirementRow> verifyRequirements(
            LegalPublicationPlan plan,
            List<RequirementRow> actual,
            Map<Integer, List<RequirementAudienceRow>> audiences,
            Map<Integer, List<RequirementDocumentRow>> documents,
            Map<String, DocumentRow> documentsByKey) {
        List<RequirementEntry> expectedRequirements = plan.manifest().requirements();
        if (actual.size() != expectedRequirements.size()
                || audiences.keySet().stream().anyMatch(ordinal ->
                        ordinal < 1 || ordinal > actual.size())
                || documents.keySet().stream().anyMatch(ordinal ->
                        ordinal < 1 || ordinal > actual.size())) {
            conflict(PUBLICATION_LOCATION);
        }

        Map<Integer, RequirementRow> byOrdinal = new LinkedHashMap<>();
        Map<UUID, Integer> versionOrdinals = new HashMap<>();
        for (int index = 0; index < expectedRequirements.size(); index++) {
            RequirementEntry expected = expectedRequirements.get(index);
            RequirementRow row = actual.get(index);
            String location = "database/requirements/" + index;
            boolean exact = row.manifestOrdinal() == index + 1
                    && row.lineId() != null
                    && row.versionId() != null
                    && row.lineIntroPublicationId() != null
                    && row.versionIntroPublicationId() != null
                    && row.lineageOrdinal() > 0
                    && row.key().equals(expected.key())
                    && row.locale().equals(plan.manifest().locale().getCodigo())
                    && row.context().equals(expected.context().name())
                    && row.actType().equals(expected.actType().name())
                    && row.version().equals(expected.version())
                    && row.statement().equals(expected.statement())
                    && row.statementSha256().equals(expected.statementSha256())
                    && row.required() == expected.required()
                    && row.requiresReacceptance() == expected.requiresReacceptance()
                    && exactRequirementAudiences(
                            audiences.getOrDefault(index + 1, List.of()),
                            row.lineId(),
                            expected)
                    && exactRequirementDocuments(
                            documents.getOrDefault(index + 1, List.of()),
                            row.versionId(),
                            expected,
                            documentsByKey);
            if (!exact
                    || byOrdinal.put(index + 1, row) != null
                    || versionOrdinals.put(row.versionId(), index + 1) != null) {
                conflict(location);
            }
        }
        return Map.copyOf(byOrdinal);
    }

    private void verifyScopes(
            LegalPublicationPlan plan,
            UUID publicationId,
            List<ScopeRow> actualScopes,
            Map<ScopeKey, List<ScopeMemberRow>> members,
            Map<Integer, RequirementRow> requirements,
            Map<Integer, List<RequirementDocumentRow>> requirementDocuments,
            Map<String, DocumentRow> documentsByKey) {
        Map<ScopeKey, ScopePlan> expectedScopes = new LinkedHashMap<>();
        for (ScopePlan scope : plan.scopes()) {
            ScopeKey key = ScopeKey.of(scope);
            if (expectedScopes.put(key, scope) != null) {
                conflict(PUBLICATION_LOCATION);
            }
        }
        if (actualScopes.size() != expectedScopes.size()
                || !members.keySet().stream().allMatch(expectedScopes::containsKey)) {
            conflict(PUBLICATION_LOCATION);
        }

        Map<ScopeKey, ScopeRow> actualByKey = new LinkedHashMap<>();
        for (ScopeRow scope : actualScopes) {
            ScopeKey key = scope.key();
            if (scope.id() == null
                    || scope.revision() == null
                    || actualByKey.put(key, scope) != null) {
                conflict(PUBLICATION_LOCATION);
            }
        }
        if (!actualByKey.keySet().equals(expectedScopes.keySet())) {
            conflict(PUBLICATION_LOCATION);
        }

        for (Map.Entry<ScopeKey, ScopePlan> expectedEntry : expectedScopes.entrySet()) {
            ScopeKey key = expectedEntry.getKey();
            ScopePlan expected = expectedEntry.getValue();
            ScopeRow actual = actualByKey.get(key);
            String location = "database/scopes/"
                    + expected.context().name()
                    + "/"
                    + expected.audience().name();
            List<ScopeMemberRow> actualMembers = members.getOrDefault(key, List.of());
            List<LegalPublicationPlan.RequirementPlan> expectedMembers =
                    expected.requirements().stream()
                            .sorted(Comparator.comparingInt(
                                    LegalPublicationPlan.RequirementPlan::manifestOrdinal))
                            .toList();
            if (actualMembers.size() != expectedMembers.size()) {
                conflict(location);
            }

            List<RequirementProjection> projections = new ArrayList<>(actualMembers.size());
            for (int index = 0; index < expectedMembers.size(); index++) {
                int ordinal = expectedMembers.get(index).manifestOrdinal() + 1;
                RequirementRow requirement = requirements.get(ordinal);
                ScopeMemberRow member = actualMembers.get(index);
                if (requirement == null
                        || member.scopeId() == null
                        || !member.scopeId().equals(actual.id())
                        || !member.publicationId().equals(publicationId)
                        || member.manifestOrdinal() != ordinal
                        || !member.requirementVersionId().equals(requirement.versionId())
                        || !member.requirementLineId().equals(requirement.lineId())) {
                    conflict(location);
                }
                projections.add(projectRequirement(
                        requirement,
                        requirementDocuments.getOrDefault(ordinal, List.of()),
                        documentsByKey));
            }

            LegalRequiredSetProjection projection = new LegalRequiredSetProjection(
                    ContextoLegal.valueOf(actual.context()),
                    LocaleLegal.fromCodigo(actual.locale()),
                    projections);
            if (!actual.revision().equals(
                    requiredSetRevisionCalculator.calculate(projection))) {
                conflict(location);
            }
        }
    }

    private static RequirementProjection projectRequirement(
            RequirementRow requirement,
            List<RequirementDocumentRow> relations,
            Map<String, DocumentRow> documentsByKey) {
        List<DocumentProjection> documents = relations.stream()
                .map(relation -> Objects.requireNonNull(
                        documentsByKey.get(relation.documentKey()),
                        "persisted replay document"))
                .map(LegalManifestReplayVerifier::projectDocument)
                .toList();
        return new RequirementProjection(
                requirement.versionId(),
                ContextoLegal.valueOf(requirement.context()),
                TipoActoLegal.valueOf(requirement.actType()),
                requirement.statement(),
                requirement.statementSha256(),
                documents,
                requirement.required());
    }

    private static DocumentProjection projectDocument(DocumentRow document) {
        return new DocumentProjection(
                document.versionId(),
                TipoDocumentoLegal.valueOf(document.type()),
                document.version(),
                document.title(),
                document.markdown(),
                document.sha256(),
                document.effectiveAt(),
                LocaleLegal.fromCodigo(document.locale()));
    }

    private List<DocumentRow> readDocuments(UUID publicationId, int expectedRows) {
        return jdbc.query("""
                SELECT pd.manifest_ordinal,
                       dl.id AS document_line_id,
                       dl.publicacion_intro_id AS document_line_intro_id,
                       dl.clave, dl.tipo, dl.locale,
                       dv.id AS document_version_id,
                       dv.publicacion_intro_id AS document_version_intro_id,
                       dv.version, dv.lineage_ordinal, dv.titulo,
                       dv.contenido_markdown, dv.sha256, dv.vigente_desde,
                       dv.requires_reacceptance
                  FROM legal_publicacion_documentos pd
                  JOIN legal_documento_versiones dv
                    ON dv.id = pd.documento_version_id
                  JOIN legal_documento_lineas dl
                    ON dl.id = dv.documento_linea_id
                 WHERE pd.publicacion_id = ?
                 ORDER BY pd.manifest_ordinal
                 LIMIT ?
                """, LegalManifestReplayVerifier::mapDocument,
                publicationId,
                expectedPlusOne(expectedRows));
    }

    private Map<Integer, List<DocumentContextRow>> readDocumentContexts(
            UUID publicationId,
            int expectedRows) {
        List<DocumentContextRow> rows = jdbc.query("""
                SELECT pd.manifest_ordinal, dc.documento_version_id, dc.contexto
                  FROM legal_publicacion_documentos pd
                  JOIN legal_documento_contextos dc
                    ON dc.documento_version_id = pd.documento_version_id
                 WHERE pd.publicacion_id = ?
                 ORDER BY pd.manifest_ordinal, dc.contexto, dc.id
                 LIMIT ?
                """, (rs, rowNumber) -> new DocumentContextRow(
                rs.getInt("manifest_ordinal"),
                rs.getObject("documento_version_id", UUID.class),
                rs.getString("contexto")),
                publicationId,
                expectedPlusOne(expectedRows));
        return groupByOrdinal(rows, DocumentContextRow::manifestOrdinal);
    }

    private List<RequirementRow> readRequirements(UUID publicationId, int expectedRows) {
        return jdbc.query("""
                SELECT pr.manifest_ordinal,
                       rl.id AS requirement_line_id,
                       rl.publicacion_intro_id AS requirement_line_intro_id,
                       rl.clave, rl.locale, rl.contexto, rl.tipo_acto,
                       rv.id AS requirement_version_id,
                       rv.publicacion_intro_id AS requirement_version_intro_id,
                       rv.version, rv.lineage_ordinal, rv.afirmacion,
                       rv.afirmacion_sha256, rv.requerido, rv.requires_reacceptance
                  FROM legal_publicacion_requisitos pr
                  JOIN legal_requisito_versiones rv
                    ON rv.id = pr.requisito_version_id
                  JOIN legal_requisito_lineas rl
                    ON rl.id = rv.requisito_linea_id
                 WHERE pr.publicacion_id = ?
                 ORDER BY pr.manifest_ordinal
                 LIMIT ?
                """, LegalManifestReplayVerifier::mapRequirement,
                publicationId,
                expectedPlusOne(expectedRows));
    }

    private Map<Integer, List<RequirementAudienceRow>> readRequirementAudiences(
            UUID publicationId,
            int expectedRows) {
        List<RequirementAudienceRow> rows = jdbc.query("""
                SELECT pr.manifest_ordinal, ra.requisito_linea_id, ra.audiencia
                  FROM legal_publicacion_requisitos pr
                  JOIN legal_requisito_versiones rv
                    ON rv.id = pr.requisito_version_id
                  JOIN legal_requisito_audiencias ra
                    ON ra.requisito_linea_id = rv.requisito_linea_id
                 WHERE pr.publicacion_id = ?
                 ORDER BY pr.manifest_ordinal, ra.audiencia, ra.id
                 LIMIT ?
                """, (rs, rowNumber) -> new RequirementAudienceRow(
                rs.getInt("manifest_ordinal"),
                rs.getObject("requisito_linea_id", UUID.class),
                rs.getString("audiencia")),
                publicationId,
                expectedPlusOne(expectedRows));
        return groupByOrdinal(rows, RequirementAudienceRow::manifestOrdinal);
    }

    private Map<Integer, List<RequirementDocumentRow>> readRequirementDocuments(
            UUID publicationId,
            int expectedRows) {
        List<RequirementDocumentRow> rows = jdbc.query("""
                SELECT pr.manifest_ordinal AS requirement_ordinal,
                       rd.requisito_version_id,
                       rd.documento_ordinal,
                       rd.documento_version_id,
                       dl.clave AS document_key
                  FROM legal_publicacion_requisitos pr
                  JOIN legal_requisito_documentos rd
                    ON rd.requisito_version_id = pr.requisito_version_id
                  JOIN legal_documento_versiones dv
                    ON dv.id = rd.documento_version_id
                  JOIN legal_documento_lineas dl
                    ON dl.id = dv.documento_linea_id
                 WHERE pr.publicacion_id = ?
                 ORDER BY pr.manifest_ordinal, rd.documento_ordinal, rd.id
                 LIMIT ?
                """, (rs, rowNumber) -> new RequirementDocumentRow(
                rs.getInt("requirement_ordinal"),
                rs.getObject("requisito_version_id", UUID.class),
                rs.getInt("documento_ordinal"),
                rs.getObject("documento_version_id", UUID.class),
                rs.getString("document_key")),
                publicationId,
                expectedPlusOne(expectedRows));
        return groupByOrdinal(rows, RequirementDocumentRow::requirementOrdinal);
    }

    private List<ScopeRow> readScopes(UUID publicationId, int expectedRows) {
        return jdbc.query("""
                SELECT id, locale, contexto, audiencia, required_set_revision
                 FROM legal_requisito_conjuntos
                 WHERE publicacion_id = ?
                 ORDER BY locale, contexto, audiencia
                 LIMIT ?
                """, (rs, rowNumber) -> new ScopeRow(
                rs.getObject("id", UUID.class),
                rs.getString("locale"),
                rs.getString("contexto"),
                rs.getString("audiencia"),
                rs.getString("required_set_revision")),
                publicationId,
                expectedPlusOne(expectedRows));
    }

    private Map<ScopeKey, List<ScopeMemberRow>> readScopeMembers(
            UUID publicationId,
            int expectedRows) {
        List<ScopeMemberRow> rows = jdbc.query("""
                SELECT c.id AS scope_id, c.locale, c.contexto, c.audiencia,
                       m.publicacion_id, m.requisito_version_id,
                       m.requisito_linea_id, m.manifest_ordinal
                  FROM legal_requisito_conjuntos c
                  JOIN legal_requisito_conjunto_miembros m
                    ON m.conjunto_id = c.id
                 WHERE c.publicacion_id = ?
                 ORDER BY c.locale, c.contexto, c.audiencia,
                          m.manifest_ordinal, m.id
                 LIMIT ?
                """, (rs, rowNumber) -> new ScopeMemberRow(
                rs.getObject("scope_id", UUID.class),
                new ScopeKey(
                        rs.getString("locale"),
                        rs.getString("contexto"),
                        rs.getString("audiencia")),
                rs.getObject("publicacion_id", UUID.class),
                rs.getObject("requisito_version_id", UUID.class),
                rs.getObject("requisito_linea_id", UUID.class),
                rs.getInt("manifest_ordinal")),
                publicationId,
                expectedPlusOne(expectedRows));
        Map<ScopeKey, List<ScopeMemberRow>> grouped = new LinkedHashMap<>();
        for (ScopeMemberRow row : rows) {
            grouped.computeIfAbsent(row.key(), ignored -> new ArrayList<>()).add(row);
        }
        return immutableLists(grouped);
    }

    private static int expectedDocumentContexts(LegalPublicationPlan plan) {
        int expected = 0;
        for (DocumentPlan document : plan.documents()) {
            expected = Math.addExact(expected, document.declaration().contexts().size());
        }
        return expected;
    }

    private static int expectedRequirementAudiences(LegalPublicationPlan plan) {
        int expected = 0;
        for (RequirementEntry requirement : plan.manifest().requirements()) {
            expected = Math.addExact(expected, requirement.roles().size());
        }
        return expected;
    }

    private static int expectedRequirementDocuments(LegalPublicationPlan plan) {
        int expected = 0;
        for (RequirementEntry requirement : plan.manifest().requirements()) {
            expected = Math.addExact(expected, requirement.documents().size());
        }
        return expected;
    }

    private static int expectedScopeMembers(LegalPublicationPlan plan) {
        int expected = 0;
        for (ScopePlan scope : plan.scopes()) {
            expected = Math.addExact(expected, scope.requirements().size());
        }
        return expected;
    }

    private static int expectedPlusOne(int expectedRows) {
        if (expectedRows < 0) {
            throw new IllegalArgumentException("La cardinalidad esperada no puede ser negativa");
        }
        return Math.addExact(expectedRows, 1);
    }

    private static boolean exactDocumentContexts(
            List<DocumentContextRow> actual,
            UUID versionId,
            DocumentEntry expected) {
        List<String> expectedContexts = expected.contexts().stream()
                .map(Enum::name)
                .sorted()
                .toList();
        return actual.size() == expectedContexts.size()
                && actual.stream().allMatch(row -> versionId.equals(row.versionId()))
                && actual.stream().map(DocumentContextRow::context).toList()
                        .equals(expectedContexts);
    }

    private static boolean exactRequirementAudiences(
            List<RequirementAudienceRow> actual,
            UUID lineId,
            RequirementEntry expected) {
        List<String> expectedAudiences = expected.roles().stream()
                .map(Enum::name)
                .sorted()
                .toList();
        return actual.size() == expectedAudiences.size()
                && actual.stream().allMatch(row -> lineId.equals(row.lineId()))
                && actual.stream().map(RequirementAudienceRow::audience).toList()
                        .equals(expectedAudiences);
    }

    private static boolean exactRequirementDocuments(
            List<RequirementDocumentRow> actual,
            UUID requirementVersionId,
            RequirementEntry expected,
            Map<String, DocumentRow> documentsByKey) {
        if (actual.size() != expected.documents().size()) {
            return false;
        }
        for (int index = 0; index < actual.size(); index++) {
            RequirementDocumentRow row = actual.get(index);
            String expectedKey = expected.documents().get(index);
            DocumentRow expectedDocument = documentsByKey.get(expectedKey);
            if (expectedDocument == null
                    || row.documentOrdinal() != index + 1
                    || !row.requirementVersionId().equals(requirementVersionId)
                    || !row.documentKey().equals(expectedKey)
                    || !row.documentVersionId().equals(expectedDocument.versionId())) {
                return false;
            }
        }
        return true;
    }

    private static int countDocumentIntroductions(
            List<DocumentRow> rows,
            UUID publicationId,
            boolean lines) {
        return (int) rows.stream()
                .map(row -> lines
                        ? row.lineIntroPublicationId()
                        : row.versionIntroPublicationId())
                .filter(publicationId::equals)
                .count();
    }

    private static int countRequirementIntroductions(
            List<RequirementRow> rows,
            UUID publicationId,
            boolean lines) {
        return (int) rows.stream()
                .map(row -> lines
                        ? row.lineIntroPublicationId()
                        : row.versionIntroPublicationId())
                .filter(publicationId::equals)
                .count();
    }

    private static boolean exactReview(
            String actualStatus,
            String actualReference,
            OffsetDateTime actualTimestamp,
            ReviewRecord expected) {
        return databaseReviewStatus(expected.status()).equals(actualStatus)
                && Objects.equals(expected.reference(), actualReference)
                && sameInstant(actualTimestamp, expected.reviewedAt());
    }

    private static boolean validReceiptTimes(
            OffsetDateTime importedAt,
            OffsetDateTime sealedAt) {
        return importedAt != null
                && sealedAt != null
                && importedAt.getNano() % 1_000 == 0
                && sealedAt.getNano() % 1_000 == 0
                && !sealedAt.toInstant().isBefore(importedAt.toInstant());
    }

    private static boolean sameInstant(OffsetDateTime left, OffsetDateTime right) {
        return left == null ? right == null
                : right != null && left.toInstant().equals(right.toInstant());
    }

    private static String databaseReviewStatus(ReviewStatus status) {
        return switch (status) {
            case PENDING -> "PENDIENTE";
            case APPROVED -> "APROBADA";
        };
    }

    private <T> Optional<T> querySingle(
            String sql,
            RowMapper<T> mapper,
            Object... arguments) {
        List<T> rows = jdbc.query(sql, mapper, arguments);
        if (rows.size() > 1) {
            conflict(PUBLICATION_LOCATION);
        }
        return rows.stream().findFirst();
    }

    private static <T> Map<Integer, List<T>> groupByOrdinal(
            List<T> rows,
            java.util.function.ToIntFunction<T> ordinal) {
        Map<Integer, List<T>> grouped = new LinkedHashMap<>();
        for (T row : rows) {
            grouped.computeIfAbsent(ordinal.applyAsInt(row), ignored -> new ArrayList<>())
                    .add(row);
        }
        return immutableLists(grouped);
    }

    private static <K, V> Map<K, List<V>> immutableLists(Map<K, List<V>> mutable) {
        Map<K, List<V>> immutable = new LinkedHashMap<>();
        mutable.forEach((key, values) -> immutable.put(key, List.copyOf(values)));
        return Map.copyOf(immutable);
    }

    private static PublicationRow mapPublication(ResultSet rs, int rowNumber)
            throws SQLException {
        return new PublicationRow(
                rs.getObject("id", UUID.class),
                rs.getString("publication_external_id"),
                rs.getInt("schema_version"),
                rs.getString("locale"),
                rs.getString("manifest_sha256"),
                rs.getString("manifest_canonico"),
                rs.getString("razon_social"),
                rs.getString("cuit"),
                rs.getString("domicilio_legal"),
                rs.getString("jurisdiccion"),
                rs.getString("horario_atencion"),
                rs.getString("email_legal"),
                rs.getString("email_privacidad"),
                rs.getString("email_soporte"),
                rs.getString("revision_legal_estado"),
                rs.getString("revision_legal_referencia"),
                rs.getObject("revision_legal_en", OffsetDateTime.class),
                rs.getString("revision_contable_estado"),
                rs.getString("revision_contable_referencia"),
                rs.getObject("revision_contable_en", OffsetDateTime.class),
                rs.getString("estado_construccion"),
                rs.getObject("importado_en", OffsetDateTime.class),
                rs.getObject("sellado_en", OffsetDateTime.class));
    }

    private static DocumentRow mapDocument(ResultSet rs, int rowNumber)
            throws SQLException {
        return new DocumentRow(
                rs.getInt("manifest_ordinal"),
                rs.getObject("document_line_id", UUID.class),
                rs.getObject("document_line_intro_id", UUID.class),
                rs.getString("clave"),
                rs.getString("tipo"),
                rs.getString("locale"),
                rs.getObject("document_version_id", UUID.class),
                rs.getObject("document_version_intro_id", UUID.class),
                rs.getString("version"),
                rs.getInt("lineage_ordinal"),
                rs.getString("titulo"),
                rs.getString("contenido_markdown"),
                rs.getString("sha256"),
                rs.getObject("vigente_desde", OffsetDateTime.class),
                rs.getBoolean("requires_reacceptance"));
    }

    private static RequirementRow mapRequirement(ResultSet rs, int rowNumber)
            throws SQLException {
        return new RequirementRow(
                rs.getInt("manifest_ordinal"),
                rs.getObject("requirement_line_id", UUID.class),
                rs.getObject("requirement_line_intro_id", UUID.class),
                rs.getString("clave"),
                rs.getString("locale"),
                rs.getString("contexto"),
                rs.getString("tipo_acto"),
                rs.getObject("requirement_version_id", UUID.class),
                rs.getObject("requirement_version_intro_id", UUID.class),
                rs.getString("version"),
                rs.getInt("lineage_ordinal"),
                rs.getString("afirmacion"),
                rs.getString("afirmacion_sha256"),
                rs.getBoolean("requerido"),
                rs.getBoolean("requires_reacceptance"));
    }

    private static LegalImportBlockedException blocked(String location) {
        return new LegalImportBlockedException(
                LegalManifestIssueCode.IMPORT_DB_PERSISTED_CONFLICT,
                location);
    }

    private static void conflict(String location) {
        throw blocked(location);
    }

    private record PublicationRow(
            UUID id,
            String publicationExternalId,
            int schemaVersion,
            String locale,
            String manifestSha256,
            String canonicalManifest,
            String legalName,
            String taxId,
            String legalAddress,
            String jurisdiction,
            String businessHours,
            String legalEmail,
            String privacyEmail,
            String supportEmail,
            String legalReviewStatus,
            String legalReviewReference,
            OffsetDateTime legalReviewedAt,
            String accountingReviewStatus,
            String accountingReviewReference,
            OffsetDateTime accountingReviewedAt,
            String constructionState,
            OffsetDateTime importedAt,
            OffsetDateTime sealedAt
    ) { }

    private record DocumentRow(
            int manifestOrdinal,
            UUID lineId,
            UUID lineIntroPublicationId,
            String key,
            String type,
            String locale,
            UUID versionId,
            UUID versionIntroPublicationId,
            String version,
            int lineageOrdinal,
            String title,
            String markdown,
            String sha256,
            OffsetDateTime effectiveAt,
            boolean requiresReacceptance
    ) { }

    private record DocumentContextRow(
            int manifestOrdinal,
            UUID versionId,
            String context
    ) { }

    private record RequirementRow(
            int manifestOrdinal,
            UUID lineId,
            UUID lineIntroPublicationId,
            String key,
            String locale,
            String context,
            String actType,
            UUID versionId,
            UUID versionIntroPublicationId,
            String version,
            int lineageOrdinal,
            String statement,
            String statementSha256,
            boolean required,
            boolean requiresReacceptance
    ) { }

    private record RequirementAudienceRow(
            int manifestOrdinal,
            UUID lineId,
            String audience
    ) { }

    private record RequirementDocumentRow(
            int requirementOrdinal,
            UUID requirementVersionId,
            int documentOrdinal,
            UUID documentVersionId,
            String documentKey
    ) { }

    private record ScopeRow(
            UUID id,
            String locale,
            String context,
            String audience,
            String revision
    ) {
        ScopeKey key() {
            return new ScopeKey(locale, context, audience);
        }
    }

    private record ScopeMemberRow(
            UUID scopeId,
            ScopeKey key,
            UUID publicationId,
            UUID requirementVersionId,
            UUID requirementLineId,
            int manifestOrdinal
    ) { }

    private record ScopeKey(String locale, String context, String audience) {
        static ScopeKey of(ScopePlan scope) {
            return new ScopeKey(
                    scope.locale().getCodigo(),
                    scope.context().name(),
                    scope.audience().name());
        }
    }
}
