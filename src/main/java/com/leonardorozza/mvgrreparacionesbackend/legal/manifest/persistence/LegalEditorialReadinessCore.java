package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialStateFingerprintCalculator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialStateProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialStateProjection.CurrentPublication;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialStateProjection.DocumentReference;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialStateProjection.DocumentSlot;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialStateProjection.DocumentVersionState;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialStateProjection.ReplacementBatch;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialStateProjection.ReplacementSuccessor;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialStateProjection.RequiredSetMember;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialStateProjection.RequiredSetPointer;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialStateProjection.RequirementVersionState;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestLimits;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection.DocumentProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection.RequirementProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetRevisionCalculator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalManifestV1.DocumentEntry;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalManifestV1.RequirementEntry;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalManifestV1.ReviewStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalPublicationPlan;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalPublicationPlan.DocumentPlan;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalPublicationPlan.ScopePlan;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoConstruccionLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoVersionLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoActoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
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

/**
 * Evaluates the complete V27 editorial projection on the caller-owned JDBC session.
 *
 * <p>This core never opens a transaction, acquires a lock or reads a clock. The caller must hold
 * the shared editorial advisory lock and must supply the single PostgreSQL transaction timestamp
 * used by the whole observation.</p>
 */
final class LegalEditorialReadinessCore {

    static final int MAX_RELEVANT_DOCUMENT_VERSIONS = 8_192;
    static final int MAX_RELEVANT_REQUIREMENT_VERSIONS = 16_384;
    static final int MAX_REPLACEMENT_BATCHES = 8_192;

    private static final int MAX_DOCUMENT_SLOTS =
            TipoDocumentoLegal.values().length
                    * LocaleLegal.values().length
                    * ContextoLegal.values().length;
    private static final int MAX_REQUIRED_SET_POINTERS =
            LocaleLegal.values().length
                    * ContextoLegal.values().length
                    * AudienciaLegal.values().length;
    private static final int MAX_POINTER_MEMBERS = Math.multiplyExact(
            MAX_REQUIRED_SET_POINTERS,
            LegalManifestLimits.MAX_REQUIREMENTS);
    private static final int MAX_POINTER_DOCUMENT_REFERENCES = Math.multiplyExact(
            MAX_POINTER_MEMBERS,
            LegalManifestLimits.MAX_DOCUMENTS_PER_REQUIREMENT);
    private static final int MAX_REPLACEMENT_MEMBERS = Math.multiplyExact(
            MAX_RELEVANT_DOCUMENT_VERSIONS,
            2);

    private static final String PUBLICATION_LOCATION = "database/publication";
    private static final String STATE_LOCATION = "database/state";
    private static final String SCOPE_LOCATION = "database/scopes";
    private static final String REVISION_LOCATION = "database/revisions";
    private static final String OBSERVATION_LOCATION = "database/observation";

    private final JdbcTemplate jdbc;
    private final LegalManifestOriginGraphVerifier originGraphVerifier;
    private final LegalRequiredSetRevisionCalculator requiredSetRevisionCalculator;
    private final LegalEditorialStateFingerprintCalculator fingerprintCalculator;

    LegalEditorialReadinessCore(
            JdbcTemplate jdbc,
            LegalManifestOriginGraphVerifier originGraphVerifier,
            LegalRequiredSetRevisionCalculator requiredSetRevisionCalculator,
            LegalEditorialStateFingerprintCalculator fingerprintCalculator) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.originGraphVerifier = Objects.requireNonNull(
                originGraphVerifier,
                "originGraphVerifier");
        this.requiredSetRevisionCalculator = Objects.requireNonNull(
                requiredSetRevisionCalculator,
                "requiredSetRevisionCalculator");
        this.fingerprintCalculator = Objects.requireNonNull(
                fingerprintCalculator,
                "fingerprintCalculator");
    }

    LegalEditorialReadinessResult evaluate(ValidatedRelease release, Instant observedAt) {
        Objects.requireNonNull(release, "release");
        Instant requiredObservedAt = requirePostgresInstant(observedAt);
        LegalPublicationPlan plan = release.plan();
        List<LegalManifestIssue> findings = new ArrayList<>();

        Optional<PublicationRow> publication = readPublication(
                plan.manifest().publicationId());
        UUID publicationId = publication.map(PublicationRow::id).orElse(null);
        if (publication.isEmpty()) {
            findings.add(issue(
                    LegalManifestIssueCode.PUBLICATION_NOT_SEALED,
                    PUBLICATION_LOCATION));
        } else {
            verifyOrigin(release, publicationId, findings);
        }
        verifyApprovedMarkers(plan, findings);

        List<TargetDocumentRow> targetDocuments = publicationId == null
                ? List.of()
                : readTargetDocuments(publicationId, plan.documentCount());
        List<TargetRequirementRow> targetRequirements = publicationId == null
                ? List.of()
                : readTargetRequirements(publicationId, plan.requirementCount());
        List<TargetScopeRow> targetScopes = publicationId == null
                ? List.of()
                : readTargetScopes(publicationId, plan.scopeCount());
        EditorialStateSnapshot snapshot = readStateSnapshot(
                publication,
                requiredObservedAt);

        if (publicationId != null) {
            validateTargetCardinality(
                    plan,
                    targetDocuments,
                    targetRequirements,
                    targetScopes,
                    findings);
        }
        validateTargetStates(
                publicationId,
                targetDocuments,
                targetRequirements,
                snapshot.documentVersions(),
                snapshot.requirementVersions(),
                requiredObservedAt,
                findings);
        ExpectedProjection expected = expectedProjection(
                plan,
                publicationId,
                targetDocuments,
                targetRequirements,
                targetScopes,
                findings);
        validateSlots(expected, snapshot.slots(), findings);
        validatePointers(
                expected,
                snapshot.pointers(),
                snapshot.pointerMembers(),
                targetRequirements,
                findings);

        return findings.isEmpty()
                ? LegalEditorialReadinessResult.ready(snapshot.observation())
                : LegalEditorialReadinessResult.notReady(
                        snapshot.observation(),
                        findings);
    }

    LegalEditorialReadinessObservation observeState(
            String publicationExternalId,
            Instant observedAt) {
        Objects.requireNonNull(publicationExternalId, "publicationExternalId");
        Instant requiredObservedAt = requirePostgresInstant(observedAt);
        return readStateSnapshot(
                readPublication(publicationExternalId),
                requiredObservedAt).observation();
    }

    boolean usesJdbc(JdbcTemplate candidate) {
        return jdbc == candidate && originGraphVerifier.usesJdbc(candidate);
    }

    private void verifyOrigin(
            ValidatedRelease release,
            UUID publicationId,
            List<LegalManifestIssue> findings) {
        try {
            originGraphVerifier.verify(release, publicationId);
        } catch (LegalEditorialBlockedException mismatch) {
            findings.add(mismatch.issue());
        }
    }

    private static void verifyApprovedMarkers(
            LegalPublicationPlan plan,
            List<LegalManifestIssue> findings) {
        if (plan.manifest().review().legal().status() != ReviewStatus.APPROVED
                || plan.manifest().review().accounting().status() != ReviewStatus.APPROVED) {
            findings.add(issue(
                    LegalManifestIssueCode.REVISION_MISMATCH,
                    "manifest/review"));
        }
    }

    private Optional<PublicationRow> readPublication(String externalId) {
        List<PublicationRow> rows = jdbc.query("""
                SELECT id, publication_external_id, manifest_sha256,
                       estado_construccion, sellado_en
                  FROM legal_publicaciones
                 WHERE publication_external_id = ?
                 LIMIT 2
                """, LegalEditorialReadinessCore::mapPublication, externalId);
        if (rows.size() > 1) {
            throw observationFailed();
        }
        return rows.stream().findFirst();
    }

    private EditorialStateSnapshot readStateSnapshot(
            Optional<PublicationRow> publication,
            Instant observedAt) {
        UUID publicationId = publication.map(PublicationRow::id).orElse(null);
        List<SlotRow> slots = boundedQuery(
                """
                SELECT tipo, locale, contexto, documento_version_id,
                       documento_linea_id, publicacion_id, estado_documento,
                       count(*) OVER () AS total_count
                  FROM legal_documento_vigentes
                 ORDER BY tipo, locale, contexto
                 LIMIT ?
                """,
                LegalEditorialReadinessCore::mapSlot,
                MAX_DOCUMENT_SLOTS);
        List<PointerRow> pointers = boundedQuery(
                """
                SELECT a.locale, a.contexto, a.audiencia, a.conjunto_id,
                       a.publicacion_id, a.actualizado_en, c.required_set_revision,
                       count(*) OVER () AS total_count
                  FROM legal_requisito_conjuntos_actuales a
                  JOIN legal_requisito_conjuntos c ON c.id = a.conjunto_id
                 ORDER BY a.locale, a.contexto, a.audiencia
                 LIMIT ?
                """,
                LegalEditorialReadinessCore::mapPointer,
                MAX_REQUIRED_SET_POINTERS);
        List<PointerMemberRow> pointerMembers = boundedQuery(
                """
                SELECT a.locale, a.contexto, a.audiencia, m.conjunto_id,
                       m.manifest_ordinal, m.requisito_version_id,
                       m.requisito_linea_id,
                       count(*) OVER () AS total_count
                  FROM legal_requisito_conjuntos_actuales a
                  JOIN legal_requisito_conjunto_miembros m
                    ON m.conjunto_id = a.conjunto_id
                 ORDER BY a.locale, a.contexto, a.audiencia,
                          m.manifest_ordinal, m.id
                 LIMIT ?
                """,
                LegalEditorialReadinessCore::mapPointerMember,
                MAX_POINTER_MEMBERS);
        List<PointerDocumentRow> pointerDocuments = boundedQuery(
                """
                SELECT a.locale, a.contexto, a.audiencia, m.conjunto_id,
                       m.requisito_version_id, rd.documento_ordinal,
                       rd.documento_version_id,
                       count(*) OVER () AS total_count
                  FROM legal_requisito_conjuntos_actuales a
                  JOIN legal_requisito_conjunto_miembros m
                    ON m.conjunto_id = a.conjunto_id
                  JOIN legal_requisito_documentos rd
                    ON rd.requisito_version_id = m.requisito_version_id
                 ORDER BY a.locale, a.contexto, a.audiencia,
                          m.manifest_ordinal, rd.documento_ordinal, rd.id
                 LIMIT ?
                """,
                LegalEditorialReadinessCore::mapPointerDocument,
                MAX_POINTER_DOCUMENT_REFERENCES);
        List<DocumentVersionRow> documentVersions =
                readRelevantDocumentVersions(publicationId);
        List<RequirementVersionRow> requirementVersions =
                readRelevantRequirementVersions(publicationId);
        List<ReplacementBatchRow> replacementBatches =
                readRelevantReplacementBatches(publicationId);
        List<ReplacementPredecessorRow> replacementPredecessors =
                readReplacementPredecessors(publicationId);
        List<ReplacementSuccessorRow> replacementSuccessors =
                readReplacementSuccessors(publicationId);

        LegalEditorialStateProjection projection = buildFingerprintProjection(
                publication,
                slots,
                pointers,
                pointerMembers,
                pointerDocuments,
                documentVersions,
                requirementVersions,
                replacementBatches,
                replacementPredecessors,
                replacementSuccessors);
        LegalEditorialReadinessObservation observation =
                new LegalEditorialReadinessObservation(
                        publication.map(PublicationRow::id),
                        observedAt,
                        fingerprintCalculator.calculate(projection),
                        documentVersions.size(),
                        requirementVersions.size(),
                        countDocumentTransitions(documentVersions),
                        countRequirementTransitions(requirementVersions),
                        slots.size(),
                        pointers.size(),
                        replacementBatches.size());
        return new EditorialStateSnapshot(
                publication,
                observedAt,
                slots,
                pointers,
                pointerMembers,
                pointerDocuments,
                documentVersions,
                requirementVersions,
                replacementBatches,
                replacementPredecessors,
                replacementSuccessors,
                observation);
    }

    private List<TargetDocumentRow> readTargetDocuments(UUID publicationId, int expected) {
        List<TargetDocumentRow> rows = jdbc.query("""
                SELECT pd.manifest_ordinal, dv.id AS version_id,
                       dv.documento_linea_id AS line_id,
                       dv.publicacion_intro_id, dv.lineage_ordinal, dv.sha256,
                       dv.vigente_desde, dv.estado, dv.estado_cambiado_en,
                       dv.ultimo_motivo, dv.reemplazo_lote_id,
                       dl.tipo, dl.locale
                  FROM legal_publicacion_documentos pd
                  JOIN legal_documento_versiones dv
                    ON dv.id = pd.documento_version_id
                  JOIN legal_documento_lineas dl
                    ON dl.id = dv.documento_linea_id
                 WHERE pd.publicacion_id = ?
                 ORDER BY pd.manifest_ordinal
                 LIMIT ?
                """, LegalEditorialReadinessCore::mapTargetDocument,
                publicationId,
                expectedPlusOne(expected));
        return List.copyOf(rows);
    }

    private List<TargetRequirementRow> readTargetRequirements(
            UUID publicationId,
            int expected) {
        List<TargetRequirementRow> rows = jdbc.query("""
                SELECT pr.manifest_ordinal, rv.id AS version_id,
                       rv.requisito_linea_id AS line_id,
                       rv.publicacion_intro_id, rv.lineage_ordinal,
                       rv.afirmacion_sha256, rv.requerido, rv.estado,
                       rv.estado_cambiado_en, rv.ultimo_motivo
                  FROM legal_publicacion_requisitos pr
                  JOIN legal_requisito_versiones rv
                    ON rv.id = pr.requisito_version_id
                 WHERE pr.publicacion_id = ?
                 ORDER BY pr.manifest_ordinal
                 LIMIT ?
                """, LegalEditorialReadinessCore::mapTargetRequirement,
                publicationId,
                expectedPlusOne(expected));
        return List.copyOf(rows);
    }

    private List<TargetScopeRow> readTargetScopes(UUID publicationId, int expected) {
        List<TargetScopeRow> rows = jdbc.query("""
                SELECT id, locale, contexto, audiencia, required_set_revision
                  FROM legal_requisito_conjuntos
                 WHERE publicacion_id = ?
                 ORDER BY locale, contexto, audiencia
                 LIMIT ?
                """, LegalEditorialReadinessCore::mapTargetScope,
                publicationId,
                expectedPlusOne(expected));
        return List.copyOf(rows);
    }

    private List<DocumentVersionRow> readRelevantDocumentVersions(UUID publicationId) {
        String targetPredicate = publicationId == null
                ? "FALSE"
                : "pd.publicacion_id = ?";
        String introducedPredicate = publicationId == null
                ? "FALSE"
                : "dv.publicacion_intro_id = ?";
        List<Object> arguments = new ArrayList<>();
        if (publicationId != null) {
            arguments.add(publicationId);
            arguments.add(publicationId);
        }
        arguments.add(expectedPlusOne(MAX_RELEVANT_DOCUMENT_VERSIONS));
        String sql = """
                WITH RECURSIVE
                batch_members AS (
                    SELECT lote_id, documento_version_id
                      FROM legal_documento_reemplazo_anteriores
                    UNION ALL
                    SELECT lote_id, documento_version_id
                      FROM legal_documento_reemplazo_sucesoras
                    UNION ALL
                    SELECT reemplazo_lote_id, id
                      FROM legal_documento_versiones
                     WHERE reemplazo_lote_id IS NOT NULL
                ),
                seeds(id) AS (
                    SELECT dv.id
                      FROM legal_documento_versiones dv
                     WHERE dv.estado <> 'BORRADOR'
                        OR %s
                        OR EXISTS (
                            SELECT 1
                              FROM legal_publicacion_documentos pd
                              JOIN legal_documento_versiones target
                                ON target.id = pd.documento_version_id
                             WHERE %s
                               AND target.documento_linea_id = dv.documento_linea_id
                               AND (dv.estado <> 'BORRADOR' OR dv.id = target.id)
                        )
                        OR EXISTS (
                            SELECT 1
                              FROM legal_documento_vigentes slot
                             WHERE slot.documento_linea_id = dv.documento_linea_id
                               AND (dv.estado <> 'BORRADOR'
                                    OR dv.id = slot.documento_version_id)
                        )
                ),
                relevant(id) AS (
                    SELECT id FROM seeds
                    UNION
                    SELECT neighbor.documento_version_id
                      FROM relevant current_version
                      JOIN batch_members current_member
                        ON current_member.documento_version_id = current_version.id
                      JOIN batch_members neighbor
                        ON neighbor.lote_id = current_member.lote_id
                )
                SELECT dv.id, dv.documento_linea_id, dv.publicacion_intro_id,
                       dv.lineage_ordinal, dv.sha256, dv.estado,
                       dv.estado_cambiado_en, dv.ultimo_motivo,
                       dv.reemplazo_lote_id,
                       count(*) OVER () AS total_count
                  FROM legal_documento_versiones dv
                  JOIN relevant ON relevant.id = dv.id
                 ORDER BY dv.id
                 LIMIT ?
                """.formatted(introducedPredicate, targetPredicate);
        return boundedQuery(
                sql,
                LegalEditorialReadinessCore::mapDocumentVersion,
                MAX_RELEVANT_DOCUMENT_VERSIONS,
                arguments.toArray());
    }

    private List<RequirementVersionRow> readRelevantRequirementVersions(UUID publicationId) {
        String targetMembership = publicationId == null
                ? "FALSE"
                : "pr.publicacion_id = ?";
        String introduced = publicationId == null
                ? "FALSE"
                : "rv.publicacion_intro_id = ?";
        List<Object> arguments = new ArrayList<>();
        if (publicationId != null) {
            arguments.add(publicationId);
            arguments.add(publicationId);
        }
        arguments.add(expectedPlusOne(MAX_RELEVANT_REQUIREMENT_VERSIONS));
        String sql = """
                SELECT rv.id, rv.requisito_linea_id, rv.publicacion_intro_id,
                       rv.lineage_ordinal, rv.afirmacion_sha256, rv.estado,
                       rv.estado_cambiado_en, rv.ultimo_motivo,
                       count(*) OVER () AS total_count
                  FROM legal_requisito_versiones rv
                 WHERE rv.estado <> 'BORRADOR'
                    OR %s
                    OR EXISTS (
                        SELECT 1
                          FROM legal_publicacion_requisitos pr
                          JOIN legal_requisito_versiones target
                            ON target.id = pr.requisito_version_id
                         WHERE %s
                           AND target.requisito_linea_id = rv.requisito_linea_id
                           AND (rv.estado <> 'BORRADOR' OR rv.id = target.id)
                    )
                    OR EXISTS (
                        SELECT 1
                          FROM legal_requisito_conjuntos_actuales current_set
                          JOIN legal_requisito_conjunto_miembros member
                            ON member.conjunto_id = current_set.conjunto_id
                          JOIN legal_requisito_versiones current_version
                            ON current_version.id = member.requisito_version_id
                         WHERE current_version.requisito_linea_id = rv.requisito_linea_id
                           AND (rv.estado <> 'BORRADOR'
                                OR rv.id = current_version.id)
                    )
                 ORDER BY rv.id
                 LIMIT ?
                """.formatted(introduced, targetMembership);
        return boundedQuery(
                sql,
                LegalEditorialReadinessCore::mapRequirementVersion,
                MAX_RELEVANT_REQUIREMENT_VERSIONS,
                arguments.toArray());
    }

    private List<ReplacementBatchRow> readRelevantReplacementBatches(UUID publicationId) {
        return boundedQuery(
                replacementBatchQuery("""
                        SELECT lot.id, lot.estado_construccion, lot.creado_en,
                               lot.sellado_en, count(*) OVER () AS total_count
                          FROM legal_documento_reemplazo_lotes lot
                          JOIN related_batches related ON related.id = lot.id
                         ORDER BY lot.id
                         LIMIT ?
                        """, publicationId),
                LegalEditorialReadinessCore::mapReplacementBatch,
                MAX_REPLACEMENT_BATCHES,
                replacementArguments(publicationId, MAX_REPLACEMENT_BATCHES));
    }

    private List<ReplacementPredecessorRow> readReplacementPredecessors(UUID publicationId) {
        return boundedQuery(
                replacementBatchQuery("""
                        SELECT previous.lote_id, previous.documento_version_id,
                               count(*) OVER () AS total_count
                          FROM legal_documento_reemplazo_anteriores previous
                          JOIN related_batches related ON related.id = previous.lote_id
                         ORDER BY previous.lote_id, previous.documento_version_id
                         LIMIT ?
                        """, publicationId),
                LegalEditorialReadinessCore::mapReplacementPredecessor,
                MAX_REPLACEMENT_MEMBERS,
                replacementArguments(publicationId, MAX_REPLACEMENT_MEMBERS));
    }

    private List<ReplacementSuccessorRow> readReplacementSuccessors(UUID publicationId) {
        return boundedQuery(
                replacementBatchQuery("""
                        SELECT successor.lote_id, successor.documento_version_id,
                               successor.publicacion_id,
                               count(*) OVER () AS total_count
                          FROM legal_documento_reemplazo_sucesoras successor
                          JOIN related_batches related ON related.id = successor.lote_id
                         ORDER BY successor.lote_id, successor.documento_version_id
                         LIMIT ?
                        """, publicationId),
                LegalEditorialReadinessCore::mapReplacementSuccessor,
                MAX_REPLACEMENT_MEMBERS,
                replacementArguments(publicationId, MAX_REPLACEMENT_MEMBERS));
    }

    private static String replacementBatchQuery(String selection, UUID publicationId) {
        String introduced = publicationId == null
                ? "FALSE"
                : "dv.publicacion_intro_id = ?";
        String targetMembership = publicationId == null
                ? "FALSE"
                : "pd.publicacion_id = ?";
        return """
                WITH RECURSIVE
                batch_members AS (
                    SELECT lote_id, documento_version_id
                      FROM legal_documento_reemplazo_anteriores
                    UNION ALL
                    SELECT lote_id, documento_version_id
                      FROM legal_documento_reemplazo_sucesoras
                    UNION ALL
                    SELECT reemplazo_lote_id, id
                      FROM legal_documento_versiones
                     WHERE reemplazo_lote_id IS NOT NULL
                ),
                seeds(id) AS (
                    SELECT dv.id
                      FROM legal_documento_versiones dv
                     WHERE dv.estado <> 'BORRADOR'
                        OR %s
                        OR EXISTS (
                            SELECT 1
                              FROM legal_publicacion_documentos pd
                              JOIN legal_documento_versiones target
                                ON target.id = pd.documento_version_id
                             WHERE %s
                               AND target.documento_linea_id = dv.documento_linea_id
                               AND (dv.estado <> 'BORRADOR' OR dv.id = target.id)
                        )
                        OR EXISTS (
                            SELECT 1
                              FROM legal_documento_vigentes slot
                             WHERE slot.documento_linea_id = dv.documento_linea_id
                               AND (dv.estado <> 'BORRADOR'
                                    OR dv.id = slot.documento_version_id)
                        )
                ),
                relevant(id) AS (
                    SELECT id FROM seeds
                    UNION
                    SELECT neighbor.documento_version_id
                      FROM relevant current_version
                      JOIN batch_members current_member
                        ON current_member.documento_version_id = current_version.id
                      JOIN batch_members neighbor
                        ON neighbor.lote_id = current_member.lote_id
                ),
                related_batches(id) AS (
                    SELECT DISTINCT member.lote_id
                      FROM batch_members member
                      JOIN relevant ON relevant.id = member.documento_version_id
                )
                %s
                """.formatted(introduced, targetMembership, selection);
    }

    private static Object[] replacementArguments(UUID publicationId, int maximum) {
        List<Object> arguments = new ArrayList<>();
        if (publicationId != null) {
            arguments.add(publicationId);
            arguments.add(publicationId);
        }
        arguments.add(expectedPlusOne(maximum));
        return arguments.toArray();
    }

    private static void validateTargetCardinality(
            LegalPublicationPlan plan,
            List<TargetDocumentRow> documents,
            List<TargetRequirementRow> requirements,
            List<TargetScopeRow> scopes,
            List<LegalManifestIssue> findings) {
        if (documents.size() != plan.documentCount()
                || requirements.size() != plan.requirementCount()
                || scopes.size() != plan.scopeCount()) {
            findings.add(issue(
                    LegalManifestIssueCode.PUBLICATION_CONTENT_MISMATCH,
                    PUBLICATION_LOCATION));
        }
    }

    private static void validateTargetStates(
            UUID publicationId,
            List<TargetDocumentRow> targetDocuments,
            List<TargetRequirementRow> targetRequirements,
            List<DocumentVersionRow> documentVersions,
            List<RequirementVersionRow> requirementVersions,
            Instant observedAt,
            List<LegalManifestIssue> findings) {
        Set<UUID> targetDocumentIds = mapIds(targetDocuments, TargetDocumentRow::versionId);
        Set<UUID> targetRequirementIds = mapIds(
                targetRequirements,
                TargetRequirementRow::versionId);
        for (TargetDocumentRow document : targetDocuments) {
            if (!"VIGENTE".equals(document.state())) {
                findings.add(issue(
                        LegalManifestIssueCode.CURRENT_STATE_MISMATCH,
                        "database/documents/" + document.manifestOrdinal()));
            }
            if (document.effectiveAt() == null
                    || document.effectiveAt().toInstant().isAfter(observedAt)) {
                findings.add(issue(
                        LegalManifestIssueCode.EFFECTIVE_DATE_NOT_REACHED,
                        "database/documents/" + document.manifestOrdinal()));
            }
        }
        for (TargetRequirementRow requirement : targetRequirements) {
            if (!"VIGENTE".equals(requirement.state())) {
                findings.add(issue(
                        LegalManifestIssueCode.CURRENT_STATE_MISMATCH,
                        "database/requirements/" + requirement.manifestOrdinal()));
            }
        }
        if (publicationId == null) {
            return;
        }
        for (DocumentVersionRow version : documentVersions) {
            if (publicationId.equals(version.introductionPublicationId())
                    && !targetDocumentIds.contains(version.id())
                    && !"BORRADOR".equals(version.state())) {
                findings.add(issue(
                        LegalManifestIssueCode.CURRENT_STATE_MISMATCH,
                        "database/documents/additional"));
            }
        }
        for (RequirementVersionRow version : requirementVersions) {
            if (publicationId.equals(version.introductionPublicationId())
                    && !targetRequirementIds.contains(version.id())
                    && !"BORRADOR".equals(version.state())) {
                findings.add(issue(
                        LegalManifestIssueCode.CURRENT_STATE_MISMATCH,
                        "database/requirements/additional"));
            }
        }
    }

    private ExpectedProjection expectedProjection(
            LegalPublicationPlan plan,
            UUID publicationId,
            List<TargetDocumentRow> documents,
            List<TargetRequirementRow> requirements,
            List<TargetScopeRow> scopes,
            List<LegalManifestIssue> findings) {
        Map<Integer, TargetDocumentRow> documentsByOrdinal = uniqueByOrdinal(
                documents,
                TargetDocumentRow::manifestOrdinal);
        Map<Integer, TargetRequirementRow> requirementsByOrdinal = uniqueByOrdinal(
                requirements,
                TargetRequirementRow::manifestOrdinal);
        Map<ScopeKey, TargetScopeRow> scopesByKey = uniqueByKey(
                scopes,
                TargetScopeRow::key);

        Map<SlotKey, ExpectedSlot> expectedSlots = new LinkedHashMap<>();
        for (int index = 0; index < plan.documents().size(); index++) {
            DocumentPlan document = plan.documents().get(index);
            TargetDocumentRow row = documentsByOrdinal.get(index + 1);
            if (row == null || publicationId == null) {
                continue;
            }
            for (ContextoLegal context : document.declaration().contexts()) {
                SlotKey key = new SlotKey(
                        document.declaration().type().name(),
                        document.declaration().locale().getCodigo(),
                        context.name());
                ExpectedSlot previous = expectedSlots.put(key, new ExpectedSlot(
                        row.versionId(),
                        row.lineId(),
                        publicationId));
                if (previous != null) {
                    findings.add(issue(
                            LegalManifestIssueCode.SCOPE_COVERAGE_INCOMPLETE,
                            SCOPE_LOCATION));
                }
            }
        }

        Map<ScopeKey, ExpectedPointer> expectedPointers = new LinkedHashMap<>();
        for (ScopePlan scope : plan.scopes()) {
            ScopeKey key = ScopeKey.of(scope);
            TargetScopeRow actualScope = scopesByKey.get(key);
            if (actualScope == null || publicationId == null) {
                continue;
            }
            List<RequirementProjection> projectedRequirements = new ArrayList<>();
            boolean complete = true;
            for (LegalPublicationPlan.RequirementPlan member : scope.requirements()) {
                int ordinal = member.manifestOrdinal() + 1;
                TargetRequirementRow requirement = requirementsByOrdinal.get(ordinal);
                if (requirement == null) {
                    complete = false;
                    break;
                }
                RequirementEntry declaration = member.declaration();
                List<DocumentProjection> projectedDocuments = new ArrayList<>();
                for (String documentKey : declaration.documents()) {
                    Optional<DocumentPlan> expectedDocument = plan.documentByKey(documentKey);
                    if (expectedDocument.isEmpty()) {
                        complete = false;
                        break;
                    }
                    DocumentPlan documentPlan = expectedDocument.orElseThrow();
                    TargetDocumentRow document = documentsByOrdinal.get(
                            documentPlan.manifestOrdinal() + 1);
                    if (document == null) {
                        complete = false;
                        break;
                    }
                    DocumentEntry documentDeclaration = documentPlan.declaration();
                    projectedDocuments.add(new DocumentProjection(
                            document.versionId(),
                            documentDeclaration.type(),
                            documentDeclaration.version(),
                            documentPlan.title(),
                            documentPlan.markdown(),
                            documentDeclaration.sha256(),
                            documentDeclaration.effectiveAt(),
                            documentDeclaration.locale()));
                }
                if (!complete) {
                    break;
                }
                projectedRequirements.add(new RequirementProjection(
                        requirement.versionId(),
                        declaration.context(),
                        declaration.actType(),
                        declaration.statement(),
                        declaration.statementSha256(),
                        projectedDocuments,
                        declaration.required()));
            }
            if (!complete) {
                continue;
            }
            String revision = requiredSetRevisionCalculator.calculate(
                    new LegalRequiredSetProjection(
                            scope.context(),
                            scope.locale(),
                            projectedRequirements));
            if (!revision.equals(actualScope.revision())) {
                findings.add(issue(
                        LegalManifestIssueCode.REVISION_MISMATCH,
                        REVISION_LOCATION + "/" + scope.context().name()
                                + "/" + scope.audience().name()));
            }
            expectedPointers.put(key, new ExpectedPointer(
                    actualScope.id(),
                    publicationId,
                    revision));
        }
        return new ExpectedProjection(
                Map.copyOf(expectedSlots),
                Map.copyOf(expectedPointers));
    }

    private static void validateSlots(
            ExpectedProjection expected,
            List<SlotRow> actualRows,
            List<LegalManifestIssue> findings) {
        Map<SlotKey, SlotRow> actual = uniqueByKey(actualRows, SlotRow::key);
        if (!actual.keySet().containsAll(expected.slots().keySet())) {
            findings.add(issue(
                    LegalManifestIssueCode.SCOPE_COVERAGE_INCOMPLETE,
                    "database/slots/missing"));
        }
        if (!expected.slots().keySet().containsAll(actual.keySet())) {
            findings.add(issue(
                    LegalManifestIssueCode.CURRENT_STATE_MISMATCH,
                    "database/slots/additional"));
        }
        for (Map.Entry<SlotKey, ExpectedSlot> entry : expected.slots().entrySet()) {
            SlotRow actualSlot = actual.get(entry.getKey());
            ExpectedSlot expectedSlot = entry.getValue();
            if (actualSlot != null
                    && (!expectedSlot.versionId().equals(actualSlot.versionId())
                    || !expectedSlot.lineId().equals(actualSlot.lineId())
                    || !expectedSlot.publicationId().equals(actualSlot.publicationId())
                    || !"VIGENTE".equals(actualSlot.documentState()))) {
                findings.add(issue(
                        LegalManifestIssueCode.CURRENT_STATE_MISMATCH,
                        "database/slots/" + entry.getKey().context()));
            }
        }
    }

    private static void validatePointers(
            ExpectedProjection expected,
            List<PointerRow> pointers,
            List<PointerMemberRow> members,
            List<TargetRequirementRow> targetRequirements,
            List<LegalManifestIssue> findings) {
        Map<ScopeKey, PointerRow> actual = uniqueByKey(pointers, PointerRow::key);
        if (!actual.keySet().containsAll(expected.pointers().keySet())) {
            findings.add(issue(
                    LegalManifestIssueCode.SCOPE_COVERAGE_INCOMPLETE,
                    "database/pointers/missing"));
        }
        if (!expected.pointers().keySet().containsAll(actual.keySet())) {
            findings.add(issue(
                    LegalManifestIssueCode.CURRENT_STATE_MISMATCH,
                    "database/pointers/additional"));
        }
        for (Map.Entry<ScopeKey, ExpectedPointer> entry : expected.pointers().entrySet()) {
            PointerRow pointer = actual.get(entry.getKey());
            ExpectedPointer expectedPointer = entry.getValue();
            if (pointer == null) {
                continue;
            }
            if (!expectedPointer.scopeId().equals(pointer.scopeId())
                    || !expectedPointer.publicationId().equals(pointer.publicationId())) {
                findings.add(issue(
                        LegalManifestIssueCode.CURRENT_STATE_MISMATCH,
                        "database/pointers/" + entry.getKey().context()
                                + "/" + entry.getKey().audience()));
            }
            if (!expectedPointer.revision().equals(pointer.revision())) {
                findings.add(issue(
                        LegalManifestIssueCode.REVISION_MISMATCH,
                        REVISION_LOCATION + "/" + entry.getKey().context()
                                + "/" + entry.getKey().audience()));
            }
        }

        Map<UUID, Boolean> requiredByVersion = new HashMap<>();
        targetRequirements.forEach(requirement -> requiredByVersion.put(
                requirement.versionId(),
                requirement.required()));
        for (PointerRow pointer : pointers) {
            if (!"REGISTRO".equals(pointer.context())) {
                continue;
            }
            boolean hasRequired = members.stream()
                    .filter(member -> member.scopeId().equals(pointer.scopeId()))
                    .map(PointerMemberRow::requirementVersionId)
                    .anyMatch(versionId -> Boolean.TRUE.equals(
                            requiredByVersion.get(versionId)));
            if (!hasRequired) {
                findings.add(issue(
                        LegalManifestIssueCode.SCOPE_COVERAGE_INCOMPLETE,
                        "database/scopes/REGISTRO/" + pointer.audience()));
            }
        }
    }

    private static LegalEditorialStateProjection buildFingerprintProjection(
            Optional<PublicationRow> publication,
            List<SlotRow> slots,
            List<PointerRow> pointers,
            List<PointerMemberRow> members,
            List<PointerDocumentRow> documents,
            List<DocumentVersionRow> documentVersions,
            List<RequirementVersionRow> requirementVersions,
            List<ReplacementBatchRow> batches,
            List<ReplacementPredecessorRow> predecessors,
            List<ReplacementSuccessorRow> successors) {
        Optional<CurrentPublication> currentPublication = publication.map(row ->
                new CurrentPublication(
                        row.id(),
                        row.externalId(),
                        row.manifestSha256(),
                        EstadoConstruccionLegal.valueOf(row.constructionState()),
                        instant(row.sealedAt())));
        List<DocumentSlot> projectedSlots = slots.stream()
                .map(row -> new DocumentSlot(
                        TipoDocumentoLegal.valueOf(row.type()),
                        LocaleLegal.fromCodigo(row.locale()),
                        ContextoLegal.valueOf(row.context()),
                        row.versionId(),
                        row.lineId(),
                        row.publicationId(),
                        EstadoVersionLegal.valueOf(row.documentState())))
                .toList();

        Map<PointerMemberKey, List<PointerDocumentRow>> documentsByMember = groupBy(
                documents,
                row -> new PointerMemberKey(
                        row.scopeId(),
                        row.requirementVersionId()));
        Map<UUID, List<PointerMemberRow>> membersByScope = groupBy(
                members,
                PointerMemberRow::scopeId);
        List<RequiredSetPointer> projectedPointers = new ArrayList<>();
        for (PointerRow pointer : pointers) {
            List<RequiredSetMember> projectedMembers = new ArrayList<>();
            for (PointerMemberRow member : membersByScope.getOrDefault(
                    pointer.scopeId(),
                    List.of())) {
                List<DocumentReference> references = documentsByMember.getOrDefault(
                                new PointerMemberKey(
                                        pointer.scopeId(),
                                        member.requirementVersionId()),
                                List.of())
                        .stream()
                        .map(document -> new DocumentReference(
                                document.documentOrdinal(),
                                document.documentVersionId()))
                        .toList();
                projectedMembers.add(new RequiredSetMember(
                        member.manifestOrdinal(),
                        member.requirementVersionId(),
                        member.requirementLineId(),
                        references));
            }
            projectedPointers.add(new RequiredSetPointer(
                    LocaleLegal.fromCodigo(pointer.locale()),
                    ContextoLegal.valueOf(pointer.context()),
                    AudienciaLegal.valueOf(pointer.audience()),
                    pointer.scopeId(),
                    pointer.publicationId(),
                    pointer.revision(),
                    instantRequired(pointer.updatedAt()),
                    projectedMembers));
        }

        List<DocumentVersionState> projectedDocumentVersions = documentVersions.stream()
                .map(row -> new DocumentVersionState(
                        row.id(),
                        row.lineId(),
                        row.introductionPublicationId(),
                        row.lineageOrdinal(),
                        row.sha256(),
                        EstadoVersionLegal.valueOf(row.state()),
                        instant(row.stateChangedAt()),
                        row.lastReason(),
                        row.replacementBatchId()))
                .toList();
        List<RequirementVersionState> projectedRequirementVersions = requirementVersions.stream()
                .map(row -> new RequirementVersionState(
                        row.id(),
                        row.lineId(),
                        row.introductionPublicationId(),
                        row.lineageOrdinal(),
                        row.statementSha256(),
                        EstadoVersionLegal.valueOf(row.state()),
                        instant(row.stateChangedAt()),
                        row.lastReason()))
                .toList();

        Map<UUID, List<UUID>> predecessorsByBatch = groupValues(
                predecessors,
                ReplacementPredecessorRow::batchId,
                ReplacementPredecessorRow::documentVersionId);
        Map<UUID, List<ReplacementSuccessor>> successorsByBatch = groupValues(
                successors,
                ReplacementSuccessorRow::batchId,
                row -> new ReplacementSuccessor(
                        row.documentVersionId(),
                        row.publicationId()));
        List<ReplacementBatch> projectedBatches = batches.stream()
                .map(row -> new ReplacementBatch(
                        row.id(),
                        EstadoConstruccionLegal.valueOf(row.constructionState()),
                        instantRequired(row.createdAt()),
                        instant(row.sealedAt()),
                        predecessorsByBatch.getOrDefault(row.id(), List.of()),
                        successorsByBatch.getOrDefault(row.id(), List.of())))
                .toList();
        return new LegalEditorialStateProjection(
                LegalEditorialStateProjection.CURRENT_FINGERPRINT_VERSION,
                currentPublication,
                projectedSlots,
                projectedPointers,
                projectedDocumentVersions,
                projectedRequirementVersions,
                projectedBatches);
    }

    private int countDocumentTransitions(List<DocumentVersionRow> versions) {
        return countTransitions(
                "legal_documento_transiciones",
                "documento_version_id",
                versions.stream().map(DocumentVersionRow::id).toList());
    }

    private int countRequirementTransitions(List<RequirementVersionRow> versions) {
        return countTransitions(
                "legal_requisito_transiciones",
                "requisito_version_id",
                versions.stream().map(RequirementVersionRow::id).toList());
    }

    private int countTransitions(String table, String column, List<UUID> versionIds) {
        if (versionIds.isEmpty()) {
            return 0;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(
                versionIds.size(),
                "?"));
        Integer count = jdbc.queryForObject(
                "SELECT count(*)::integer FROM " + table
                        + " WHERE " + column + " IN (" + placeholders + ")",
                Integer.class,
                versionIds.toArray());
        return Objects.requireNonNull(count, "transition count");
    }

    private <T> List<T> boundedQuery(
            String sql,
            RowMapper<T> mapper,
            int maximum,
            Object... arguments) {
        List<CountedRow<T>> rows = jdbc.query(
                sql,
                (resultSet, rowNumber) -> new CountedRow<>(
                        resultSet.getLong("total_count"),
                        mapper.mapRow(resultSet, rowNumber)),
                appendLimit(arguments, maximum));
        if (rows.isEmpty()) {
            return List.of();
        }
        long total = rows.getFirst().total();
        if (total < 0
                || total > maximum
                || total != rows.size()
                || rows.stream().anyMatch(row -> row.total() != total)) {
            throw observationFailed();
        }
        return rows.stream().map(CountedRow::value).toList();
    }

    private static Object[] appendLimit(Object[] arguments, int maximum) {
        if (arguments.length > 0
                && arguments[arguments.length - 1] instanceof Integer) {
            return arguments;
        }
        Object[] withLimit = java.util.Arrays.copyOf(arguments, arguments.length + 1);
        withLimit[arguments.length] = expectedPlusOne(maximum);
        return withLimit;
    }

    private static int expectedPlusOne(int expected) {
        if (expected < 0) {
            throw new IllegalArgumentException("Cardinalidad editorial negativa");
        }
        return Math.addExact(expected, 1);
    }

    private static Instant requirePostgresInstant(Instant value) {
        Instant required = Objects.requireNonNull(value, "observedAt");
        if (required.getNano() % 1_000 != 0) {
            throw new IllegalArgumentException(
                    "observedAt supera la precisión de PostgreSQL");
        }
        return required;
    }

    private static LegalEditorialOperationalException observationFailed() {
        return new LegalEditorialOperationalException(
                LegalManifestIssueCode.EDITORIAL_OBSERVATION_FAILED,
                OBSERVATION_LOCATION);
    }

    private static LegalManifestIssue issue(
            LegalManifestIssueCode code,
            String location) {
        return LegalManifestIssue.at(code, location);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static Instant instantRequired(OffsetDateTime value) {
        return Objects.requireNonNull(value, "database timestamp").toInstant();
    }

    private static <T> Set<UUID> mapIds(
            Collection<T> values,
            Function<T, UUID> identifier) {
        Set<UUID> ids = new LinkedHashSet<>();
        values.forEach(value -> ids.add(identifier.apply(value)));
        return Set.copyOf(ids);
    }

    private static <T> Map<Integer, T> uniqueByOrdinal(
            Collection<T> values,
            java.util.function.ToIntFunction<T> ordinal) {
        Map<Integer, T> result = new LinkedHashMap<>();
        for (T value : values) {
            if (result.put(ordinal.applyAsInt(value), value) != null) {
                throw observationFailed();
            }
        }
        return Map.copyOf(result);
    }

    private static <T, K> Map<K, T> uniqueByKey(
            Collection<T> values,
            Function<T, K> key) {
        Map<K, T> result = new LinkedHashMap<>();
        for (T value : values) {
            if (result.put(key.apply(value), value) != null) {
                throw observationFailed();
            }
        }
        return Map.copyOf(result);
    }

    private static <T, K> Map<K, List<T>> groupBy(
            Collection<T> values,
            Function<T, K> key) {
        Map<K, List<T>> grouped = new LinkedHashMap<>();
        values.forEach(value -> grouped
                .computeIfAbsent(key.apply(value), ignored -> new ArrayList<>())
                .add(value));
        Map<K, List<T>> immutable = new LinkedHashMap<>();
        grouped.forEach((group, rows) -> immutable.put(group, List.copyOf(rows)));
        return Map.copyOf(immutable);
    }

    private static <T, K, V> Map<K, List<V>> groupValues(
            Collection<T> values,
            Function<T, K> key,
            Function<T, V> value) {
        Map<K, List<V>> grouped = new LinkedHashMap<>();
        values.forEach(row -> grouped
                .computeIfAbsent(key.apply(row), ignored -> new ArrayList<>())
                .add(value.apply(row)));
        Map<K, List<V>> immutable = new LinkedHashMap<>();
        grouped.forEach((group, rows) -> immutable.put(group, List.copyOf(rows)));
        return Map.copyOf(immutable);
    }

    private static PublicationRow mapPublication(ResultSet rs, int rowNumber)
            throws SQLException {
        return new PublicationRow(
                rs.getObject("id", UUID.class),
                rs.getString("publication_external_id"),
                rs.getString("manifest_sha256"),
                rs.getString("estado_construccion"),
                rs.getObject("sellado_en", OffsetDateTime.class));
    }

    private static TargetDocumentRow mapTargetDocument(ResultSet rs, int rowNumber)
            throws SQLException {
        return new TargetDocumentRow(
                rs.getInt("manifest_ordinal"),
                rs.getObject("version_id", UUID.class),
                rs.getObject("line_id", UUID.class),
                rs.getObject("publicacion_intro_id", UUID.class),
                rs.getInt("lineage_ordinal"),
                rs.getString("sha256"),
                rs.getObject("vigente_desde", OffsetDateTime.class),
                rs.getString("estado"),
                rs.getObject("estado_cambiado_en", OffsetDateTime.class),
                rs.getString("ultimo_motivo"),
                rs.getObject("reemplazo_lote_id", UUID.class),
                rs.getString("tipo"),
                rs.getString("locale"));
    }

    private static TargetRequirementRow mapTargetRequirement(ResultSet rs, int rowNumber)
            throws SQLException {
        return new TargetRequirementRow(
                rs.getInt("manifest_ordinal"),
                rs.getObject("version_id", UUID.class),
                rs.getObject("line_id", UUID.class),
                rs.getObject("publicacion_intro_id", UUID.class),
                rs.getInt("lineage_ordinal"),
                rs.getString("afirmacion_sha256"),
                rs.getBoolean("requerido"),
                rs.getString("estado"),
                rs.getObject("estado_cambiado_en", OffsetDateTime.class),
                rs.getString("ultimo_motivo"));
    }

    private static TargetScopeRow mapTargetScope(ResultSet rs, int rowNumber)
            throws SQLException {
        return new TargetScopeRow(
                rs.getObject("id", UUID.class),
                rs.getString("locale"),
                rs.getString("contexto"),
                rs.getString("audiencia"),
                rs.getString("required_set_revision"));
    }

    private static SlotRow mapSlot(ResultSet rs, int rowNumber) throws SQLException {
        return new SlotRow(
                rs.getString("tipo"),
                rs.getString("locale"),
                rs.getString("contexto"),
                rs.getObject("documento_version_id", UUID.class),
                rs.getObject("documento_linea_id", UUID.class),
                rs.getObject("publicacion_id", UUID.class),
                rs.getString("estado_documento"));
    }

    private static PointerRow mapPointer(ResultSet rs, int rowNumber) throws SQLException {
        return new PointerRow(
                rs.getString("locale"),
                rs.getString("contexto"),
                rs.getString("audiencia"),
                rs.getObject("conjunto_id", UUID.class),
                rs.getObject("publicacion_id", UUID.class),
                rs.getObject("actualizado_en", OffsetDateTime.class),
                rs.getString("required_set_revision"));
    }

    private static PointerMemberRow mapPointerMember(ResultSet rs, int rowNumber)
            throws SQLException {
        return new PointerMemberRow(
                rs.getString("locale"),
                rs.getString("contexto"),
                rs.getString("audiencia"),
                rs.getObject("conjunto_id", UUID.class),
                rs.getInt("manifest_ordinal"),
                rs.getObject("requisito_version_id", UUID.class),
                rs.getObject("requisito_linea_id", UUID.class));
    }

    private static PointerDocumentRow mapPointerDocument(ResultSet rs, int rowNumber)
            throws SQLException {
        return new PointerDocumentRow(
                rs.getString("locale"),
                rs.getString("contexto"),
                rs.getString("audiencia"),
                rs.getObject("conjunto_id", UUID.class),
                rs.getObject("requisito_version_id", UUID.class),
                rs.getInt("documento_ordinal"),
                rs.getObject("documento_version_id", UUID.class));
    }

    private static DocumentVersionRow mapDocumentVersion(ResultSet rs, int rowNumber)
            throws SQLException {
        return new DocumentVersionRow(
                rs.getObject("id", UUID.class),
                rs.getObject("documento_linea_id", UUID.class),
                rs.getObject("publicacion_intro_id", UUID.class),
                rs.getInt("lineage_ordinal"),
                rs.getString("sha256"),
                rs.getString("estado"),
                rs.getObject("estado_cambiado_en", OffsetDateTime.class),
                rs.getString("ultimo_motivo"),
                rs.getObject("reemplazo_lote_id", UUID.class));
    }

    private static RequirementVersionRow mapRequirementVersion(ResultSet rs, int rowNumber)
            throws SQLException {
        return new RequirementVersionRow(
                rs.getObject("id", UUID.class),
                rs.getObject("requisito_linea_id", UUID.class),
                rs.getObject("publicacion_intro_id", UUID.class),
                rs.getInt("lineage_ordinal"),
                rs.getString("afirmacion_sha256"),
                rs.getString("estado"),
                rs.getObject("estado_cambiado_en", OffsetDateTime.class),
                rs.getString("ultimo_motivo"));
    }

    private static ReplacementBatchRow mapReplacementBatch(ResultSet rs, int rowNumber)
            throws SQLException {
        return new ReplacementBatchRow(
                rs.getObject("id", UUID.class),
                rs.getString("estado_construccion"),
                rs.getObject("creado_en", OffsetDateTime.class),
                rs.getObject("sellado_en", OffsetDateTime.class));
    }

    private static ReplacementPredecessorRow mapReplacementPredecessor(
            ResultSet rs,
            int rowNumber) throws SQLException {
        return new ReplacementPredecessorRow(
                rs.getObject("lote_id", UUID.class),
                rs.getObject("documento_version_id", UUID.class));
    }

    private static ReplacementSuccessorRow mapReplacementSuccessor(
            ResultSet rs,
            int rowNumber) throws SQLException {
        return new ReplacementSuccessorRow(
                rs.getObject("lote_id", UUID.class),
                rs.getObject("documento_version_id", UUID.class),
                rs.getObject("publicacion_id", UUID.class));
    }

    private record CountedRow<T>(long total, T value) { }

    private record EditorialStateSnapshot(
            Optional<PublicationRow> publication,
            Instant observedAt,
            List<SlotRow> slots,
            List<PointerRow> pointers,
            List<PointerMemberRow> pointerMembers,
            List<PointerDocumentRow> pointerDocuments,
            List<DocumentVersionRow> documentVersions,
            List<RequirementVersionRow> requirementVersions,
            List<ReplacementBatchRow> replacementBatches,
            List<ReplacementPredecessorRow> replacementPredecessors,
            List<ReplacementSuccessorRow> replacementSuccessors,
            LegalEditorialReadinessObservation observation) { }

    private record PublicationRow(
            UUID id,
            String externalId,
            String manifestSha256,
            String constructionState,
            OffsetDateTime sealedAt) { }

    private record TargetDocumentRow(
            int manifestOrdinal,
            UUID versionId,
            UUID lineId,
            UUID introductionPublicationId,
            int lineageOrdinal,
            String sha256,
            OffsetDateTime effectiveAt,
            String state,
            OffsetDateTime stateChangedAt,
            String lastReason,
            UUID replacementBatchId,
            String type,
            String locale) { }

    private record TargetRequirementRow(
            int manifestOrdinal,
            UUID versionId,
            UUID lineId,
            UUID introductionPublicationId,
            int lineageOrdinal,
            String statementSha256,
            boolean required,
            String state,
            OffsetDateTime stateChangedAt,
            String lastReason) { }

    private record TargetScopeRow(
            UUID id,
            String locale,
            String context,
            String audience,
            String revision) {

        ScopeKey key() {
            return new ScopeKey(locale, context, audience);
        }
    }

    private record SlotRow(
            String type,
            String locale,
            String context,
            UUID versionId,
            UUID lineId,
            UUID publicationId,
            String documentState) {

        SlotKey key() {
            return new SlotKey(type, locale, context);
        }
    }

    private record PointerRow(
            String locale,
            String context,
            String audience,
            UUID scopeId,
            UUID publicationId,
            OffsetDateTime updatedAt,
            String revision) {

        ScopeKey key() {
            return new ScopeKey(locale, context, audience);
        }
    }

    private record PointerMemberRow(
            String locale,
            String context,
            String audience,
            UUID scopeId,
            int manifestOrdinal,
            UUID requirementVersionId,
            UUID requirementLineId) { }

    private record PointerDocumentRow(
            String locale,
            String context,
            String audience,
            UUID scopeId,
            UUID requirementVersionId,
            int documentOrdinal,
            UUID documentVersionId) { }

    private record PointerMemberKey(UUID scopeId, UUID requirementVersionId) { }

    private record DocumentVersionRow(
            UUID id,
            UUID lineId,
            UUID introductionPublicationId,
            int lineageOrdinal,
            String sha256,
            String state,
            OffsetDateTime stateChangedAt,
            String lastReason,
            UUID replacementBatchId) { }

    private record RequirementVersionRow(
            UUID id,
            UUID lineId,
            UUID introductionPublicationId,
            int lineageOrdinal,
            String statementSha256,
            String state,
            OffsetDateTime stateChangedAt,
            String lastReason) { }

    private record ReplacementBatchRow(
            UUID id,
            String constructionState,
            OffsetDateTime createdAt,
            OffsetDateTime sealedAt) { }

    private record ReplacementPredecessorRow(UUID batchId, UUID documentVersionId) { }

    private record ReplacementSuccessorRow(
            UUID batchId,
            UUID documentVersionId,
            UUID publicationId) { }

    private record SlotKey(String type, String locale, String context) { }

    private record ScopeKey(String locale, String context, String audience) {

        static ScopeKey of(ScopePlan scope) {
            return new ScopeKey(
                    scope.locale().getCodigo(),
                    scope.context().name(),
                    scope.audience().name());
        }
    }

    private record ExpectedSlot(UUID versionId, UUID lineId, UUID publicationId) { }

    private record ExpectedPointer(UUID scopeId, UUID publicationId, String revision) { }

    private record ExpectedProjection(
            Map<SlotKey, ExpectedSlot> slots,
            Map<ScopeKey, ExpectedPointer> pointers) { }
}
