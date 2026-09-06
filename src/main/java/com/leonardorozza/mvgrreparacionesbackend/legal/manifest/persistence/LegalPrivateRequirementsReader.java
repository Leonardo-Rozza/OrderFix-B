package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.CanonicalTextValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalDocumentSummary;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestLimits;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalActorSnapshot;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalApplicableScopeSet;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAuthenticatedRequirements;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAuthenticatedRequirements.Membership;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAuthenticatedRequirements.Scope;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAuthenticatedRequirements.Snapshot;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequirementLineage;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequirementLineage.Acceptance;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequirementLineage.DocumentLine;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequirementLineage.DocumentReference;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequirementLineage.DocumentVersion;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequirementLineage.EvidenceDocument;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequirementLineage.RequirementLine;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequirementLineage.RequirementVersion;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequirementSatisfactionEvaluator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateProvenance.ScopeOrigin;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection.DocumentProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection.RequirementProjection;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoVersionLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PerfilAgregadoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoActoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.util.EnumSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Complete content accreditation inside the caller's preflighted, shared mutable transaction. */
final class LegalPrivateRequirementsReader {

    private static final int BATCH_SIZE = 32;
    private static final int MAX_STATEMENT_CODE_POINTS = 1_000;
    private static final int MAX_STATEMENT_BYTES = 4 * MAX_STATEMENT_CODE_POINTS;
    private static final String HEADER_SQL = """
            SELECT a.id AS aggregate_id, a.perfil, a.locale AS aggregate_locale,
                   a.audiencia AS aggregate_audience, a.revision_scheme,
                   a.required_set_revision AS aggregate_revision, a.provenance_fingerprint,
                   a.scope_count, a.creado_en AS aggregate_created,
                   s.scope_ordinal, s.contexto AS scope_context, s.locale AS scope_locale,
                   s.audiencia AS scope_audience, s.conjunto_id AS scope_set,
                   s.publicacion_id AS scope_publication, s.required_set_revision AS scope_revision,
                   c.id AS set_id, c.publicacion_id, c.locale, c.contexto, c.audiencia,
                   c.required_set_revision, c.creado_en,
                   p.conjunto_id AS current_set, p.publicacion_id AS current_publication,
                   p.actualizado_en, publication.id AS publication_id,
                   publication.locale AS publication_locale, publication.estado_construccion,
                   publication.importado_en, publication.sellado_en,
                   (SELECT count(*) FROM (SELECT 1 FROM public.legal_publicacion_documentos membership
                                           WHERE membership.publicacion_id = c.publicacion_id LIMIT 129) bounded)
                       AS publication_document_count,
                   (SELECT count(*) FROM (SELECT 1 FROM public.legal_publicacion_requisitos membership
                                           WHERE membership.publicacion_id = c.publicacion_id LIMIT 257) bounded)
                       AS publication_requirement_count
              FROM public.legal_requisito_agregados a
              LEFT JOIN public.legal_requisito_agregado_scopes s ON s.agregado_id = a.id
              LEFT JOIN public.legal_requisito_conjuntos c ON c.id = s.conjunto_id
              LEFT JOIN public.legal_requisito_conjuntos_actuales p
                ON p.locale = c.locale AND p.contexto = c.contexto AND p.audiencia = c.audiencia
              LEFT JOIN public.legal_publicaciones publication ON publication.id = c.publicacion_id
             WHERE a.id = ? AND s.scope_ordinal = ?
            """;
    private static final String MEMBERS_SQL = """
            SELECT m.publicacion_id, m.requisito_version_id, m.requisito_linea_id, m.manifest_ordinal,
                   v.id AS version_id, v.requisito_linea_id AS version_line, v.estado,
                   v.estado_cambiado_en, v.afirmacion_sha256, v.requerido, v.lineage_ordinal,
                   pg_catalog.octet_length(pg_catalog.convert_to(v.afirmacion, 'UTF8')) AS statement_octets,
                   pg_catalog.char_length(v.afirmacion) AS statement_characters,
                   line.id AS line_id, line.clave, line.locale, line.contexto, line.tipo_acto,
                   publication.id AS membership_id, publication.manifest_ordinal AS publication_ordinal,
                   EXISTS (SELECT 1 FROM public.legal_requisito_audiencias audience
                            WHERE audience.requisito_linea_id = line.id
                              AND audience.audiencia = ?) AS applicable_audience
              FROM public.legal_requisito_conjunto_miembros m
              LEFT JOIN public.legal_requisito_versiones v ON v.id = m.requisito_version_id
              LEFT JOIN public.legal_requisito_lineas line ON line.id = v.requisito_linea_id
              LEFT JOIN public.legal_publicacion_requisitos publication
                ON publication.publicacion_id = ? AND publication.requisito_version_id = m.requisito_version_id
             WHERE m.conjunto_id = ?
             ORDER BY m.manifest_ordinal
            """;
    // Unknown versions/lines cannot silently disappear through the applicability filter.
    private static final String PUBLICATION_MEMBERS_SQL = """
            SELECT membership.requisito_version_id, membership.manifest_ordinal,
                   version.id AS version_id, line.id AS line_id
              FROM public.legal_publicacion_requisitos membership
              LEFT JOIN public.legal_requisito_versiones version ON version.id = membership.requisito_version_id
              LEFT JOIN public.legal_requisito_lineas line ON line.id = version.requisito_linea_id
             WHERE membership.publicacion_id = ?
               AND (version.id IS NULL OR line.id IS NULL
                    OR (line.locale = 'es-AR' AND line.contexto = ?
                        AND EXISTS (SELECT 1 FROM public.legal_requisito_audiencias audience
                                     WHERE audience.requisito_linea_id = line.id
                                       AND audience.audiencia = ?)))
             ORDER BY membership.manifest_ordinal
            """;
    private static final String DOCUMENT_METADATA_SQL = """
            SELECT needed.id AS requested_id, v.id, v.documento_linea_id, v.version, v.titulo,
                   v.sha256, v.vigente_desde, v.estado, v.estado_cambiado_en,
                   pg_catalog.octet_length(pg_catalog.convert_to(v.contenido_markdown, 'UTF8')) AS markdown_octets,
                   line.id AS line_id, line.tipo, line.locale,
                   context.id AS context_id, membership.id AS membership_id, membership.manifest_ordinal,
                   current.documento_version_id AS current_version,
                   current.documento_linea_id AS current_line, current.publicacion_id AS current_publication,
                   current.estado_documento AS current_state
              FROM (VALUES %s) needed(id)
              LEFT JOIN public.legal_documento_versiones v ON v.id = needed.id
              LEFT JOIN public.legal_documento_lineas line ON line.id = v.documento_linea_id
              LEFT JOIN public.legal_documento_contextos context
                ON context.documento_version_id = v.id AND context.contexto = ?
              LEFT JOIN public.legal_publicacion_documentos membership
                ON membership.publicacion_id = ? AND membership.documento_version_id = v.id
              LEFT JOIN public.legal_documento_vigentes current
                ON current.tipo = line.tipo AND current.locale = line.locale AND current.contexto = ?
             ORDER BY needed.id
            """;

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final CanonicalTextValidator textValidator = new CanonicalTextValidator();

    LegalPrivateRequirementsReader(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.dataSource = Objects.requireNonNull(jdbc.getDataSource(), "jdbc.dataSource");
    }

    boolean usesJdbc(JdbcTemplate candidate) {
        return jdbc == candidate;
    }

    /** The caller holds the editorial gate and the shared actor lock through commit. */
    LegalAuthenticatedRequirements read(LegalActorSnapshot actor, LegalApplicableScopeSet scopes,
                                       LegalRequiredSetAggregateReceipt receipt,
                                       LegalEditorialTimeBoundary boundary,
                                       LegalPrivateRequirementsDeadline deadline) {
        Objects.requireNonNull(deadline, "deadline").check();
        Objects.requireNonNull(boundary, "boundary");
        Objects.requireNonNull(actor, "actor").requireEnabled();
        Objects.requireNonNull(scopes, "scopes");
        var provenance = Objects.requireNonNull(receipt, "receipt").provenance();
        require(scopes.profile() == PerfilAgregadoLegal.AUTHENTICATED_PENDING
                && provenance.profile() == scopes.profile() && provenance.locale() == scopes.locale()
                && provenance.audience() == actor.audience() && scopes.audience() == actor.audience()
                && provenance.scopes().stream().map(ScopeOrigin::context).toList().equals(scopes.contexts()));
        requireTransaction();
        return Objects.requireNonNull(jdbc.execute((ConnectionCallback<LegalAuthenticatedRequirements>) connection -> {
            requireBoundConnection(connection);
            List<Scope> complete = new ArrayList<>();
            List<String> revisions = new ArrayList<>();
            List<RequirementMetadata> allRequirements = new ArrayList<>();
            for (ScopeOrigin origin : provenance.scopes()) {
                int scopeOrdinal = complete.size() + 1;
                revisions.add(readHeader(connection, receipt, origin, actor.audience(), scopeOrdinal, boundary, deadline));
                List<RequirementMetadata> requirements = readMembers(connection, origin, actor.audience(), boundary, deadline);
                allRequirements.addAll(requirements);
                comparePublicationMembers(connection, origin, actor.audience(), requirements, deadline);
                Map<UUID, List<UUID>> references = readReferences(connection, requirements, deadline);
                Set<UUID> documentIds = new LinkedHashSet<>();
                references.values().forEach(documentIds::addAll);
                require(documentIds.size() <= LegalManifestLimits.MAX_DOCUMENTS);
                Map<UUID, DocumentMetadata> metadata = readDocumentMetadata(
                        connection, List.copyOf(documentIds), origin, boundary, deadline);
                long expandedBytes = 0;
                for (List<UUID> linked : references.values()) {
                    for (UUID id : linked) {
                        expandedBytes = Math.addExact(expandedBytes, metadata.get(id).octets());
                        require(expandedBytes <= LegalManifestLimits.MAX_EXPANDED_SCOPE_MARKDOWN_BYTES);
                    }
                }
                // Sizes and the whole scope graph are accredited before fetching any legal TEXT.
                Map<UUID, String> statements = readStatements(connection, requirements, deadline);
                Map<UUID, DocumentProjection> documents = readDocuments(connection, metadata, deadline);
                List<RequirementProjection> projection = new ArrayList<>(requirements.size());
                List<Membership> memberships = new ArrayList<>(requirements.size());
                for (RequirementMetadata requirement : requirements) {
                    deadline.check();
                    projection.add(new RequirementProjection(requirement.id(), origin.context(),
                            requirement.actType(), statements.get(requirement.id()), requirement.sha256(),
                            references.get(requirement.id()).stream().map(documents::get).toList(),
                            requirement.required()));
                    memberships.add(new Membership(requirement.ordinal(), requirement.key(), requirement.id()));
                }
                complete.add(new Scope(scopeOrdinal,
                        new LegalRequiredSetProjection(origin.context(), scopes.locale(), projection), memberships));
            }
            Snapshot snapshot = new Snapshot(actor, scopes, complete);
            require(snapshot.requiredSetRevision().equals(receipt.requiredSetRevision())
                    && snapshot.scopeRevisions().stream().map(value -> value.requiredSetRevision()).toList().equals(revisions));
            deadline.check();
            HistoricalObservation history = readHistory(connection, actor, allRequirements, boundary, deadline);
            LegalAuthenticatedRequirements result = new LegalRequirementSatisfactionEvaluator().evaluate(
                    snapshot, history.lineage(), history.evidence());
            deadline.check();
            return result;
        }));
    }

    private String readHeader(Connection connection, LegalRequiredSetAggregateReceipt receipt,
                              ScopeOrigin origin, AudienciaLegal audience, int scopeOrdinal, LegalEditorialTimeBoundary boundary,
                              LegalPrivateRequirementsDeadline deadline) throws SQLException {
        List<String> rows = query(connection, HEADER_SQL, List.of(receipt.aggregateId(), scopeOrdinal), 1, row -> {
            require(receipt.aggregateId().equals(uuid(row, "aggregate_id"))
                    && "AUTHENTICATED_PENDING".equals(row.getString("perfil"))
                    && "es-AR".equals(row.getString("aggregate_locale"))
                    && audience.name().equals(row.getString("aggregate_audience"))
                    && "AGGREGATE_V1".equals(row.getString("revision_scheme"))
                    && receipt.requiredSetRevision().equals(row.getString("aggregate_revision"))
                    && receipt.provenanceFingerprint().equals(row.getString("provenance_fingerprint"))
                    && receipt.createdAt().equals(timestamp(row, "aggregate_created").toInstant())
                    && integer(row, "scope_count") == receipt.provenance().scopes().size() && integer(row, "scope_ordinal") == scopeOrdinal
                    && origin.context().name().equals(row.getString("scope_context"))
                    && "es-AR".equals(row.getString("scope_locale"))
                    && audience.name().equals(row.getString("scope_audience"))
                    && origin.requiredSetId().equals(uuid(row, "scope_set"))
                    && origin.publicationId().equals(uuid(row, "scope_publication"))
                    && origin.requiredSetId().equals(uuid(row, "set_id"))
                    && origin.publicationId().equals(uuid(row, "publicacion_id"))
                    && "es-AR".equals(row.getString("locale"))
                    && origin.context().name().equals(row.getString("contexto"))
                    && audience.name().equals(row.getString("audiencia"))
                    && origin.requiredSetId().equals(uuid(row, "current_set"))
                    && origin.publicationId().equals(uuid(row, "current_publication"))
                    && origin.publicationId().equals(uuid(row, "publication_id"))
                    && "es-AR".equals(row.getString("publication_locale"))
                    && "SELLADO".equals(row.getString("estado_construccion")));
            requirePast(row, "creado_en", boundary);
            requirePast(row, "actualizado_en", boundary);
            requirePast(row, "importado_en", boundary);
            requirePast(row, "sellado_en", boundary);
            long documentCount = number(row, "publication_document_count");
            long requirementCount = number(row, "publication_requirement_count");
            require(documentCount >= 1 && documentCount <= LegalManifestLimits.MAX_DOCUMENTS
                    && requirementCount >= 1 && requirementCount <= LegalManifestLimits.MAX_REQUIREMENTS);
            String revision = row.getString("required_set_revision");
            require(revision != null && revision.equals(row.getString("scope_revision")));
            return revision;
        }, deadline);
        require(rows.size() == 1);
        return rows.getFirst();
    }

    private List<RequirementMetadata> readMembers(Connection connection, ScopeOrigin origin, AudienciaLegal audience,
                                                  LegalEditorialTimeBoundary boundary,
                                                  LegalPrivateRequirementsDeadline deadline) throws SQLException {
        List<RequirementMetadata> rows = query(connection, MEMBERS_SQL,
                List.of(audience.name(), origin.publicationId(), origin.requiredSetId()), LegalManifestLimits.MAX_REQUIREMENTS, row -> {
                    UUID id = uuid(row, "requisito_version_id");
                    UUID line = uuid(row, "requisito_linea_id");
                    int ordinal = integer(row, "manifest_ordinal");
                    require(origin.publicationId().equals(uuid(row, "publicacion_id"))
                            && id.equals(uuid(row, "version_id")) && line.equals(uuid(row, "version_line"))
                            && line.equals(uuid(row, "line_id")) && "VIGENTE".equals(row.getString("estado"))
                            && "es-AR".equals(row.getString("locale"))
                            && origin.context().name().equals(row.getString("contexto"))
                            && bool(row, "applicable_audience") && row.getObject("membership_id") != null
                            && ordinal >= 1 && ordinal <= LegalManifestLimits.MAX_REQUIREMENTS
                            && ordinal == integer(row, "publication_ordinal"));
                    requirePast(row, "estado_cambiado_en", boundary);
                    int characters = integer(row, "statement_characters");
                    long octets = number(row, "statement_octets");
                    require(characters >= 1 && characters <= MAX_STATEMENT_CODE_POINTS
                            && octets >= 1 && octets <= MAX_STATEMENT_BYTES);
                    return new RequirementMetadata(id, line, row.getString("clave"), ordinal, positiveInteger(row, "lineage_ordinal"),
                            TipoActoLegal.valueOf(row.getString("tipo_acto")),
                            row.getString("afirmacion_sha256"), bool(row, "requerido"), octets);
                }, deadline);
        require(!rows.isEmpty());
        Set<UUID> ids = new HashSet<>();
        Set<UUID> lines = new HashSet<>();
        int previous = 0;
        for (RequirementMetadata row : rows) {
            require(ids.add(row.id()) && lines.add(row.line()) && row.ordinal() > previous);
            previous = row.ordinal();
        }
        return rows;
    }

    private void comparePublicationMembers(Connection connection, ScopeOrigin origin, AudienciaLegal audience,
                                            List<RequirementMetadata> members,
                                            LegalPrivateRequirementsDeadline deadline) throws SQLException {
        Map<UUID, RequirementMetadata> expected = new LinkedHashMap<>();
        members.forEach(member -> expected.put(member.id(), member));
        List<UUID> rows = query(connection, PUBLICATION_MEMBERS_SQL, List.of(origin.publicationId(), origin.context().name(), audience.name()),
                LegalManifestLimits.MAX_REQUIREMENTS, row -> {
                    UUID id = uuid(row, "requisito_version_id");
                    RequirementMetadata member = expected.get(id);
                    require(member != null && id.equals(uuid(row, "version_id"))
                            && member.line().equals(uuid(row, "line_id"))
                            && member.ordinal() == integer(row, "manifest_ordinal"));
                    return id;
                }, deadline);
        require(rows.size() == members.size() && new HashSet<>(rows).equals(expected.keySet()));
    }

    private Map<UUID, List<UUID>> readReferences(Connection connection, List<RequirementMetadata> requirements,
                                                LegalPrivateRequirementsDeadline deadline) throws SQLException {
        Map<UUID, List<UUID>> result = new LinkedHashMap<>();
        requirements.forEach(requirement -> result.put(requirement.id(), new ArrayList<>()));
        List<UUID> ids = List.copyOf(result.keySet());
        for (List<UUID> batch : batches(ids)) {
        String sql = """
                SELECT needed.id AS requirement_id, reference.documento_version_id, reference.documento_ordinal
                  FROM (VALUES %s) needed(id)
                  LEFT JOIN public.legal_requisito_documentos reference ON reference.requisito_version_id = needed.id
                 ORDER BY needed.id, reference.documento_ordinal
                """.formatted(values(batch.size()));
        query(connection, sql, batch, Math.multiplyExact(batch.size(), LegalManifestLimits.MAX_DOCUMENTS_PER_REQUIREMENT),
                row -> {
                    List<UUID> documents = result.get(uuid(row, "requirement_id"));
                    UUID document = uuid(row, "documento_version_id");
                    require(documents != null && documents.size() < LegalManifestLimits.MAX_DOCUMENTS_PER_REQUIREMENT
                            && integer(row, "documento_ordinal") == documents.size() + 1
                            && !documents.contains(document));
                    documents.add(document);
                    return document;
                }, deadline);
        }
        result.values().forEach(documents -> require(!documents.isEmpty()));
        return result;
    }

    private Map<UUID, DocumentMetadata> readDocumentMetadata(Connection connection, List<UUID> ids,
                                                            ScopeOrigin origin,
                                                            LegalEditorialTimeBoundary boundary,
                                                            LegalPrivateRequirementsDeadline deadline) throws SQLException {
        Map<UUID, DocumentMetadata> result = new LinkedHashMap<>();
        for (List<UUID> batch : batches(ids)) {
            List<Object> args = new ArrayList<>(batch);
            args.add(origin.context().name());
            args.add(origin.publicationId());
            args.add(origin.context().name());
            List<DocumentMetadata> rows = query(connection, DOCUMENT_METADATA_SQL.formatted(values(batch.size())),
                    args, batch.size(), row -> {
                        UUID id = uuid(row, "id");
                        UUID line = uuid(row, "documento_linea_id");
                        require(id.equals(uuid(row, "requested_id")) && line.equals(uuid(row, "line_id"))
                                && "VIGENTE".equals(row.getString("estado"))
                                && "es-AR".equals(row.getString("locale"))
                                && row.getObject("context_id") != null && row.getObject("membership_id") != null
                                && integer(row, "manifest_ordinal") >= 1
                                && integer(row, "manifest_ordinal") <= LegalManifestLimits.MAX_DOCUMENTS
                                && id.equals(uuid(row, "current_version")) && line.equals(uuid(row, "current_line"))
                                && origin.publicationId().equals(uuid(row, "current_publication"))
                                && "VIGENTE".equals(row.getString("current_state")));
                        requirePast(row, "estado_cambiado_en", boundary);
                        OffsetDateTime effectiveAt = requirePast(row, "vigente_desde", boundary);
                        long octets = number(row, "markdown_octets");
                        require(octets >= 1 && octets <= LegalManifestLimits.MAX_MARKDOWN_BYTES);
                        return new DocumentMetadata(id, TipoDocumentoLegal.valueOf(row.getString("tipo")),
                                row.getString("version"), row.getString("titulo"), row.getString("sha256"),
                                effectiveAt, octets);
                    }, deadline);
            require(rows.size() == batch.size());
            for (DocumentMetadata row : rows) {
                require(batch.contains(row.id()) && result.putIfAbsent(row.id(), row) == null);
            }
        }
        require(result.keySet().equals(new HashSet<>(ids)));
        return result;
    }

    private Map<UUID, String> readStatements(Connection connection, List<RequirementMetadata> requirements,
                                             LegalPrivateRequirementsDeadline deadline) throws SQLException {
        Map<UUID, RequirementMetadata> metadata = new LinkedHashMap<>();
        requirements.forEach(requirement -> metadata.put(requirement.id(), requirement));
        Map<UUID, String> result = new LinkedHashMap<>();
        for (List<UUID> batch : batches(List.copyOf(metadata.keySet()))) {
            String sql = """
                    SELECT needed.id, pg_catalog.octet_length(pg_catalog.convert_to(v.afirmacion, 'UTF8')) AS octets,
                           CASE WHEN pg_catalog.octet_length(pg_catalog.convert_to(v.afirmacion, 'UTF8')) BETWEEN 1 AND %d
                                 AND pg_catalog.char_length(v.afirmacion) BETWEEN 1 AND %d
                                THEN pg_catalog.convert_to(v.afirmacion, 'UTF8') ELSE NULL END AS text_utf8
                      FROM (VALUES %s) needed(id)
                      LEFT JOIN public.legal_requisito_versiones v ON v.id = needed.id
                     ORDER BY needed.id
                    """.formatted(MAX_STATEMENT_BYTES, MAX_STATEMENT_CODE_POINTS, values(batch.size()));
            List<UUID> rows = query(connection, sql, batch, batch.size(), row -> {
                UUID id = uuid(row, "id");
                RequirementMetadata expected = metadata.get(id);
                require(expected != null && number(row, "octets") == expected.octets());
                String text = validatedText(row, expected.octets(), expected.sha256(), deadline);
                require(result.putIfAbsent(id, text) == null);
                return id;
            }, deadline);
            require(rows.size() == batch.size() && new HashSet<>(rows).equals(new HashSet<>(batch)));
        }
        return result;
    }

    private Map<UUID, DocumentProjection> readDocuments(Connection connection, Map<UUID, DocumentMetadata> metadata,
                                                       LegalPrivateRequirementsDeadline deadline) throws SQLException {
        Map<UUID, DocumentProjection> result = new LinkedHashMap<>();
        for (List<UUID> batch : batches(List.copyOf(metadata.keySet()))) {
            String sql = """
                    SELECT needed.id, pg_catalog.octet_length(pg_catalog.convert_to(v.contenido_markdown, 'UTF8')) AS octets,
                           CASE WHEN pg_catalog.octet_length(pg_catalog.convert_to(v.contenido_markdown, 'UTF8')) BETWEEN 1 AND %d
                                THEN pg_catalog.convert_to(v.contenido_markdown, 'UTF8') ELSE NULL END AS text_utf8
                      FROM (VALUES %s) needed(id)
                      LEFT JOIN public.legal_documento_versiones v ON v.id = needed.id
                     ORDER BY needed.id
                    """.formatted(LegalManifestLimits.MAX_MARKDOWN_BYTES, values(batch.size()));
            List<UUID> rows = query(connection, sql, batch, batch.size(), row -> {
                UUID id = uuid(row, "id");
                DocumentMetadata expected = metadata.get(id);
                require(expected != null && number(row, "octets") == expected.octets());
                String markdown = validatedText(row, expected.octets(), expected.sha256(), deadline);
                require(result.putIfAbsent(id, new DocumentProjection(id, expected.type(), expected.version(),
                        expected.title(), markdown, expected.sha256(), expected.effectiveAt(), LocaleLegal.ES_AR)) == null);
                return id;
            }, deadline);
            require(rows.size() == batch.size() && new HashSet<>(rows).equals(new HashSet<>(batch)));
        }
        return result;
    }

    private HistoricalObservation readHistory(Connection connection, LegalActorSnapshot actor,
                                               List<RequirementMetadata> current,
                                               LegalEditorialTimeBoundary boundary,
                                               LegalPrivateRequirementsDeadline deadline) throws SQLException {
        ObservationBudget budget = new ObservationBudget();
        Map<UUID, RequirementMetadata> targets = new LinkedHashMap<>();
        for (RequirementMetadata item : current) {
            require(targets.putIfAbsent(item.line(), item) == null);
        }
        Map<UUID, EvidenceHeader> evidence = readEvidenceHeaders(connection, actor, targets, budget, boundary, deadline);
        Map<UUID, Interval> requirementIntervals = new LinkedHashMap<>();
        for (RequirementMetadata item : current) {
            include(requirementIntervals, item.line(), item.lineageOrdinal());
        }
        for (EvidenceHeader item : evidence.values()) {
            include(requirementIntervals, item.line(), item.ordinal());
        }
        Map<UUID, HistoricalRequirement> requirements = readRequirementIntervals(
                connection, requirementIntervals, budget, boundary, deadline);
        Map<UUID, List<DocumentReference>> references = new LinkedHashMap<>();
        Map<UUID, Interval> documentIntervals = readHistoricalReferences(
                connection, requirements, references, budget, deadline);
        Map<UUID, HistoricalDocument> documents = readDocumentIntervals(
                connection, documentIntervals, budget, boundary, deadline);
        Map<UUID, Instant> activationTimes = readTransitions(connection, true,
                requirements.values().stream().map(HistoricalRequirement::header).toList(), boundary, deadline);
        Map<UUID, Instant> documentActivationTimes = readTransitions(connection, false,
                documents.values().stream().map(HistoricalDocument::header).toList(), boundary, deadline);
        // Frozen V27 forbids activating a document before its own effective date. This is
        // an intra-version invariant, independent of the legacy batch transaction timestamp.
        for (var activation : documentActivationTimes.entrySet()) {
            require(!activation.getValue().isBefore(documents.get(activation.getKey()).effectiveAt()));
        }
        // Historical bytes are checked once per distinct canonical version and discarded by batch.
        validateHistoricalSources(connection, true,
                requirements.values().stream().map(HistoricalRequirement::header).toList(), deadline);
        validateHistoricalSources(connection, false,
                documents.values().stream().map(HistoricalDocument::header).toList(), deadline);
        List<Acceptance> accepted = readEvidenceDocuments(connection, actor, evidence, requirements, references,
                documents, activationTimes, documentActivationTimes, budget, deadline);
        Map<UUID, List<RequirementVersion>> requirementVersions = new LinkedHashMap<>();
        for (HistoricalRequirement item : requirements.values()) {
            VersionHeader version = item.header();
            requirementVersions.computeIfAbsent(version.line(), ignored -> new ArrayList<>()).add(
                    new RequirementVersion(version.id(), version.ordinal(), version.state(), version.reacceptance(),
                            version.digest(), item.required(), references.get(version.id())));
        }
        List<RequirementLine> requirementLines = new ArrayList<>();
        for (var entry : requirementVersions.entrySet()) {
            HistoricalRequirement first = requirements.get(entry.getValue().getFirst().versionId());
            requirementLines.add(new RequirementLine(entry.getKey(), first.header().key(), first.context(),
                    LocaleLegal.ES_AR, first.actType(), first.audiences(), entry.getValue()));
        }
        Map<UUID, List<DocumentVersion>> documentVersions = new LinkedHashMap<>();
        for (HistoricalDocument item : documents.values()) {
            VersionHeader version = item.header();
            documentVersions.computeIfAbsent(version.line(), ignored -> new ArrayList<>()).add(
                    new DocumentVersion(version.id(), version.ordinal(), version.state(),
                            version.reacceptance(), version.digest()));
        }
        List<DocumentLine> documentLines = new ArrayList<>();
        for (var entry : documentVersions.entrySet()) {
            HistoricalDocument first = documents.get(entry.getValue().getFirst().versionId());
            documentLines.add(new DocumentLine(entry.getKey(), first.header().key(), LocaleLegal.ES_AR,
                    first.type(), entry.getValue()));
        }
        return new HistoricalObservation(new LegalRequirementLineage(requirementLines, documentLines), accepted);
    }

    private Map<UUID, EvidenceHeader> readEvidenceHeaders(Connection connection, LegalActorSnapshot actor,
            Map<UUID, RequirementMetadata> targets, ObservationBudget budget,
            LegalEditorialTimeBoundary boundary, LegalPrivateRequirementsDeadline deadline) throws SQLException {
        Map<UUID, EvidenceHeader> result = new LinkedHashMap<>();
        for (List<RequirementMetadata> batch : batches(List.copyOf(targets.values()))) {
            List<Object> arguments = new ArrayList<>();
            for (RequirementMetadata item : batch) {
                arguments.add(item.line());
                arguments.add(item.key());
            }
            arguments.add(boundary.observedAt().atOffset(java.time.ZoneOffset.UTC));
            arguments.add(actor.userId());
            arguments.add(actor.tallerId());
            String sql = """
                    SELECT a.id, a.user_id, a.taller_id, a.requisito_version_id, a.afirmacion_sha256,
                           v.requisito_linea_id, v.lineage_ordinal, b.rol_wire, b.audiencia, b.aceptado_en,
                           b.user_id AS batch_user, b.taller_id AS batch_workshop,
                           CASE b.revision_scheme
                             WHEN 'SCOPE_V1' THEN b.perfil IS NULL AND b.agregado_id IS NULL AND EXISTS (
                               SELECT 1 FROM public.legal_requisito_conjuntos c
                               JOIN public.legal_requisito_conjunto_miembros m ON m.conjunto_id = c.id
                               JOIN public.legal_publicaciones p ON p.id = c.publicacion_id
                               WHERE c.locale = line.locale AND c.contexto = line.contexto AND c.audiencia = b.audiencia
                                 AND c.required_set_revision = b.required_set_revision AND c.creado_en <= observation.observed_at
                                 AND m.publicacion_id = c.publicacion_id AND m.requisito_version_id = v.id
                                 AND m.requisito_linea_id = line.id AND p.estado_construccion = 'SELLADO'
                                 AND p.sellado_en <= observation.observed_at AND p.importado_en <= observation.observed_at)
                             WHEN 'AGGREGATE_V1' THEN EXISTS (
                               SELECT 1 FROM public.legal_requisito_agregados g
                               JOIN public.legal_requisito_agregado_scopes s ON s.agregado_id = g.id
                               JOIN public.legal_requisito_conjuntos c ON c.id = s.conjunto_id
                               JOIN public.legal_requisito_conjunto_miembros m ON m.conjunto_id = c.id
                               JOIN public.legal_publicaciones p ON p.id = c.publicacion_id
                               WHERE g.id = b.agregado_id AND g.perfil = b.perfil AND g.audiencia = b.audiencia
                                 AND g.revision_scheme = b.revision_scheme AND g.required_set_revision = b.required_set_revision
                                 AND g.creado_en <= observation.observed_at AND s.locale = g.locale AND s.audiencia = g.audiencia
                                 AND s.contexto = line.contexto AND s.locale = line.locale
                                 AND c.locale = s.locale AND c.contexto = s.contexto AND c.audiencia = s.audiencia
                                 AND c.publicacion_id = s.publicacion_id AND c.required_set_revision = s.required_set_revision
                                 AND c.creado_en <= observation.observed_at AND m.publicacion_id = c.publicacion_id
                                 AND m.requisito_version_id = v.id AND m.requisito_linea_id = line.id
                                 AND p.estado_construccion = 'SELLADO' AND p.sellado_en <= observation.observed_at AND p.importado_en <= observation.observed_at)
                             ELSE false END AS source_membership,
                           (a.requisito_clave = line.clave AND a.requisito_version = v.version
                             AND a.contexto = line.contexto AND a.tipo_acto = line.tipo_acto
                             AND a.afirmacion = v.afirmacion AND a.afirmacion_sha256 = v.afirmacion_sha256
                             AND a.requerido = v.requerido AND line.id = needed.line_id
                             AND line.clave = needed.key AND line.locale = 'es-AR') AS canonical_snapshot
                      FROM (VALUES %s) needed(line_id, key)
                      CROSS JOIN (SELECT ?::timestamptz AS observed_at) observation
                      JOIN public.legal_aceptaciones a ON a.user_id = ? AND a.taller_id = ?
                      LEFT JOIN public.legal_requisito_versiones v ON v.id = a.requisito_version_id
                      LEFT JOIN public.legal_requisito_lineas line ON line.id = v.requisito_linea_id
                      LEFT JOIN public.legal_aceptacion_lotes b ON b.id = a.lote_id
                     WHERE v.requisito_linea_id = needed.line_id OR a.requisito_clave = needed.key
                     ORDER BY a.id
                    """.formatted(String.join(", ", Collections.nCopies(batch.size(), "(?::uuid, ?::varchar)")));
            query(connection, sql, arguments, budget.remainingRows(), row -> {
                budget.addRows(1);
                UUID line = uuid(row, "requisito_linea_id");
                require(targets.containsKey(line) && bool(row, "canonical_snapshot") && bool(row, "source_membership")
                        && number(row, "user_id") == actor.userId() && number(row, "taller_id") == actor.tallerId()
                        && number(row, "batch_user") == actor.userId() && number(row, "batch_workshop") == actor.tallerId());
                UserRole role = UserRole.valueOf(row.getString("rol_wire"));
                require(role.toAudienciaLegal().name().equals(row.getString("audiencia")));
                EvidenceHeader header = new EvidenceHeader(uuid(row, "id"), uuid(row, "requisito_version_id"), line,
                        positiveInteger(row, "lineage_ordinal"), role, row.getString("afirmacion_sha256"),
                        requirePast(row, "aceptado_en", boundary).toInstant());
                require(result.putIfAbsent(header.id(), header) == null);
                return header.id();
            }, deadline);
        }
        return result;
    }

    private Map<UUID, HistoricalRequirement> readRequirementIntervals(Connection connection,
            Map<UUID, Interval> intervals, ObservationBudget budget, LegalEditorialTimeBoundary boundary,
            LegalPrivateRequirementsDeadline deadline) throws SQLException {
        Map<UUID, HistoricalRequirement> result = new LinkedHashMap<>();
        for (List<Interval> batch : batches(List.copyOf(intervals.values()))) {
            String sql = """
                    SELECT v.id, v.requisito_linea_id AS line_id, v.lineage_ordinal, v.estado,
                           v.estado_cambiado_en, v.requires_reacceptance, v.afirmacion_sha256 AS digest,
                           pg_catalog.octet_length(pg_catalog.convert_to(v.afirmacion, 'UTF8')) AS octets,
                           pg_catalog.char_length(v.afirmacion) AS characters, v.requerido,
                           line.clave, line.locale, line.contexto, line.tipo_acto,
                           EXISTS (SELECT 1 FROM public.legal_requisito_audiencias a
                                    WHERE a.requisito_linea_id = line.id AND a.audiencia = 'ADMIN_TITULAR') AS admin_audience,
                           EXISTS (SELECT 1 FROM public.legal_requisito_audiencias a
                                    WHERE a.requisito_linea_id = line.id AND a.audiencia = 'USER') AS user_audience,
                           publication.id AS publication_id, publication.estado_construccion,
                           publication.locale AS publication_locale, publication.importado_en, publication.sellado_en,
                           membership.id AS membership_id
                      FROM (VALUES %s) needed(line_id, from_ordinal, to_ordinal)
                      LEFT JOIN public.legal_requisito_versiones v ON v.requisito_linea_id = needed.line_id
                           AND v.lineage_ordinal BETWEEN needed.from_ordinal AND needed.to_ordinal
                      LEFT JOIN public.legal_requisito_lineas line ON line.id = v.requisito_linea_id
                      LEFT JOIN public.legal_publicaciones publication ON publication.id = v.publicacion_intro_id
                      LEFT JOIN public.legal_publicacion_requisitos membership
                        ON membership.publicacion_id = v.publicacion_intro_id AND membership.requisito_version_id = v.id
                     ORDER BY needed.line_id, v.lineage_ordinal
                    """.formatted(intervalValues(batch.size()));
            Map<UUID, Integer> counts = new LinkedHashMap<>();
            List<UUID> fetched = query(connection, sql, intervalArguments(batch), budget.remainingRows(), row -> {
                budget.addRows(1);
                VersionHeader header = historyHeader(row, true, budget, boundary);
                countInterval(counts, intervals.get(header.line()), header.ordinal());
                EnumSet<AudienciaLegal> audiences = EnumSet.noneOf(AudienciaLegal.class);
                if (bool(row, "admin_audience")) audiences.add(AudienciaLegal.ADMIN_TITULAR);
                if (bool(row, "user_audience")) audiences.add(AudienciaLegal.USER);
                require(!audiences.isEmpty());
                HistoricalRequirement item = new HistoricalRequirement(header,
                        ContextoLegal.valueOf(row.getString("contexto")), TipoActoLegal.valueOf(row.getString("tipo_acto")),
                        Set.copyOf(audiences), bool(row, "requerido"));
                require(result.putIfAbsent(header.id(), item) == null);
                return header.id();
            }, deadline);
            require(counts.size() == batch.size());
            accreditIntervalEndpoints(batch, fetched.stream().map(result::get).map(HistoricalRequirement::header).toList());
        }
        return result;
    }

    private Map<UUID, Interval> readHistoricalReferences(Connection connection,
            Map<UUID, HistoricalRequirement> requirements, Map<UUID, List<DocumentReference>> references,
            ObservationBudget budget, LegalPrivateRequirementsDeadline deadline) throws SQLException {
        Map<UUID, Interval> intervals = new LinkedHashMap<>();
        requirements.keySet().forEach(id -> references.put(id, new ArrayList<>()));
        for (List<UUID> batch : batches(List.copyOf(requirements.keySet()))) {
            String sql = """
                    SELECT needed.id AS requirement_id, r.documento_version_id, r.documento_ordinal,
                           v.id AS document_id, v.documento_linea_id, v.lineage_ordinal, v.sha256, line.clave,
                           line.locale
                      FROM (VALUES %s) needed(id)
                      LEFT JOIN public.legal_requisito_documentos r ON r.requisito_version_id = needed.id
                      LEFT JOIN public.legal_documento_versiones v ON v.id = r.documento_version_id
                      LEFT JOIN public.legal_documento_lineas line ON line.id = v.documento_linea_id
                     ORDER BY needed.id, r.documento_ordinal
                    """.formatted(values(batch.size()));
            // An empty draft contributes one LEFT JOIN marker, never an invented reference row.
            Set<UUID> seen = new HashSet<>();
            query(connection, sql, batch, Math.multiplyExact(batch.size(), LegalManifestLimits.MAX_DOCUMENTS_PER_REQUIREMENT), row -> {
                UUID requirementId = uuid(row, "requirement_id");
                require(batch.contains(requirementId));
                seen.add(requirementId);
                List<DocumentReference> linked = references.get(requirementId);
                if (row.getObject("documento_version_id") == null) {
                    require(requirements.get(requirementId).header().state() == EstadoVersionLegal.BORRADOR
                            && linked.isEmpty());
                    return requirementId;
                }
                budget.addRows(1);
                UUID id = uuid(row, "documento_version_id");
                require(id.equals(uuid(row, "document_id")) && "es-AR".equals(row.getString("locale"))
                        && integer(row, "documento_ordinal") == linked.size() + 1
                        && linked.size() < LegalManifestLimits.MAX_DOCUMENTS_PER_REQUIREMENT);
                linked.add(new DocumentReference(row.getString("clave"), id, row.getString("sha256")));
                include(intervals, uuid(row, "documento_linea_id"), positiveInteger(row, "lineage_ordinal"));
                return requirementId;
            }, deadline);
            require(seen.equals(new HashSet<>(batch)));
        }
        references.replaceAll((id, linked) -> List.copyOf(linked));
        return intervals;
    }

    private Map<UUID, HistoricalDocument> readDocumentIntervals(Connection connection,
            Map<UUID, Interval> intervals, ObservationBudget budget, LegalEditorialTimeBoundary boundary,
            LegalPrivateRequirementsDeadline deadline) throws SQLException {
        Map<UUID, HistoricalDocument> result = new LinkedHashMap<>();
        for (List<Interval> batch : batches(List.copyOf(intervals.values()))) {
            String sql = """
                    SELECT v.id, v.documento_linea_id AS line_id, v.lineage_ordinal, v.estado,
                           v.estado_cambiado_en, v.requires_reacceptance, v.sha256 AS digest,
                           pg_catalog.octet_length(pg_catalog.convert_to(v.contenido_markdown, 'UTF8')) AS octets,
                           v.version, v.titulo, v.vigente_desde, line.clave, line.locale, line.tipo,
                           publication.id AS publication_id, publication.estado_construccion,
                           publication.locale AS publication_locale, publication.importado_en, publication.sellado_en,
                           membership.id AS membership_id
                      FROM (VALUES %s) needed(line_id, from_ordinal, to_ordinal)
                      LEFT JOIN public.legal_documento_versiones v ON v.documento_linea_id = needed.line_id
                           AND v.lineage_ordinal BETWEEN needed.from_ordinal AND needed.to_ordinal
                      LEFT JOIN public.legal_documento_lineas line ON line.id = v.documento_linea_id
                      LEFT JOIN public.legal_publicaciones publication ON publication.id = v.publicacion_intro_id
                      LEFT JOIN public.legal_publicacion_documentos membership
                        ON membership.publicacion_id = v.publicacion_intro_id AND membership.documento_version_id = v.id
                     ORDER BY needed.line_id, v.lineage_ordinal
                    """.formatted(intervalValues(batch.size()));
            Map<UUID, Integer> counts = new LinkedHashMap<>();
            List<UUID> fetched = query(connection, sql, intervalArguments(batch), budget.remainingRows(), row -> {
                budget.addRows(1);
                VersionHeader header = historyHeader(row, false, budget, boundary);
                countInterval(counts, intervals.get(header.line()), header.ordinal());
                HistoricalDocument item = new HistoricalDocument(header, TipoDocumentoLegal.valueOf(row.getString("tipo")),
                        row.getString("version"), row.getString("titulo"), timestamp(row, "vigente_desde").toInstant());
                // Reuse only the scalar metadata contract; history state is accredited independently below.
                new LegalDocumentSummary(header.id(), item.type(), item.version(), item.title(), header.digest(),
                        item.effectiveAt(), EstadoVersionLegal.VIGENTE, LocaleLegal.ES_AR);
                require(item.version().codePointCount(0, item.version().length()) <= 40);
                require(result.putIfAbsent(header.id(), item) == null);
                return header.id();
            }, deadline);
            require(counts.size() == batch.size());
            accreditIntervalEndpoints(batch, fetched.stream().map(result::get).map(HistoricalDocument::header).toList());
        }
        return result;
    }

    private static VersionHeader historyHeader(ResultSet row, boolean requirement, ObservationBudget budget,
            LegalEditorialTimeBoundary boundary) throws SQLException {
        UUID id = uuid(row, "id");
        UUID line = uuid(row, "line_id");
        require("es-AR".equals(row.getString("locale")) && row.getObject("publication_id") != null
                && "es-AR".equals(row.getString("publication_locale")));
        Instant imported = requirePast(row, "importado_en", boundary).toInstant();
        EstadoVersionLegal state = EstadoVersionLegal.valueOf(row.getString("estado"));
        Instant changed = null;
        if (state == EstadoVersionLegal.BORRADOR) {
            require(row.getObject("estado_cambiado_en") == null);
        } else {
            require("SELLADO".equals(row.getString("estado_construccion")) && row.getObject("membership_id") != null);
            requirePast(row, "sellado_en", boundary);
            changed = requirePast(row, "estado_cambiado_en", boundary).toInstant();
            require(!changed.isBefore(imported));
        }
        long octets = number(row, "octets");
        require(octets >= 1 && octets <= (requirement ? MAX_STATEMENT_BYTES : LegalManifestLimits.MAX_MARKDOWN_BYTES));
        if (requirement) {
            int characters = integer(row, "characters");
            require(characters >= 1 && characters <= MAX_STATEMENT_CODE_POINTS);
        }
        budget.addSourceBytes(octets);
        return new VersionHeader(id, line, row.getString("clave"), positiveInteger(row, "lineage_ordinal"), state,
                bool(row, "requires_reacceptance"), row.getString("digest"), octets, imported, changed);
    }

    private Map<UUID, Instant> readTransitions(Connection connection, boolean requirement, List<VersionHeader> versions,
            LegalEditorialTimeBoundary boundary, LegalPrivateRequirementsDeadline deadline) throws SQLException {
        Map<UUID, Instant> activated = new LinkedHashMap<>();
        String table = requirement ? "legal_requisito_transiciones" : "legal_documento_transiciones";
        String column = requirement ? "requisito_version_id" : "documento_version_id";
        for (List<VersionHeader> batch : batches(versions)) {
            Map<UUID, List<Transition>> transitions = new LinkedHashMap<>();
            batch.forEach(version -> transitions.put(version.id(), new ArrayList<>()));
            String sql = """
                    SELECT needed.id AS version_id, t.id AS transition_id, t.estado_anterior, t.estado_nuevo, t.ocurrido_en
                      FROM (VALUES %s) needed(id)
                      LEFT JOIN public.%s t ON t.%s = needed.id
                     ORDER BY needed.id, CASE t.estado_nuevo WHEN 'PUBLICADA' THEN 1 WHEN 'VIGENTE' THEN 2 ELSE 3 END
                    """.formatted(values(batch.size()), table, column);
            query(connection, sql, batch.stream().map(VersionHeader::id).toList(), batch.size() * 3, row -> {
                UUID id = uuid(row, "version_id");
                List<Transition> rows = transitions.get(id);
                require(rows != null && rows.size() < 3);
                if (row.getObject("transition_id") != null) {
                    rows.add(new Transition(EstadoVersionLegal.valueOf(row.getString("estado_anterior")),
                            EstadoVersionLegal.valueOf(row.getString("estado_nuevo")),
                            requirePast(row, "ocurrido_en", boundary).toInstant()));
                }
                return id;
            }, deadline);
            for (VersionHeader version : batch) {
                Instant activation = accreditTransitions(version, transitions.get(version.id()));
                if (activation != null) activated.put(version.id(), activation);
            }
        }
        return activated;
    }

    static Instant accreditTransitions(VersionHeader version, List<Transition> transitions) {
        EstadoVersionLegal state = EstadoVersionLegal.BORRADOR;
        Instant previous = version.importedAt();
        Instant activated = null;
        for (Transition transition : transitions) {
            boolean allowed = (state == EstadoVersionLegal.BORRADOR && transition.to() == EstadoVersionLegal.PUBLICADA)
                    || (state == EstadoVersionLegal.PUBLICADA && transition.to() == EstadoVersionLegal.VIGENTE)
                    || (state == EstadoVersionLegal.VIGENTE && (transition.to() == EstadoVersionLegal.REEMPLAZADA
                            || transition.to() == EstadoVersionLegal.RETIRADA));
            require(transition.from() == state && allowed && !transition.at().isBefore(previous));
            state = transition.to();
            previous = transition.at();
            if (state == EstadoVersionLegal.VIGENTE) activated = transition.at();
        }
        require(state == version.state() && (state == EstadoVersionLegal.BORRADOR
                ? version.changedAt() == null && transitions.isEmpty() : previous.equals(version.changedAt())));
        return activated;
    }

    private void validateHistoricalSources(Connection connection, boolean requirement, List<VersionHeader> versions,
            LegalPrivateRequirementsDeadline deadline) throws SQLException {
        String table = requirement ? "legal_requisito_versiones" : "legal_documento_versiones";
        String column = requirement ? "afirmacion" : "contenido_markdown";
        int maximum = requirement ? MAX_STATEMENT_BYTES : LegalManifestLimits.MAX_MARKDOWN_BYTES;
        for (List<VersionHeader> batch : batches(versions)) {
            Map<UUID, VersionHeader> expected = new LinkedHashMap<>();
            batch.forEach(version -> expected.put(version.id(), version));
            String sql = """
                    SELECT needed.id, pg_catalog.octet_length(pg_catalog.convert_to(v.%s, 'UTF8')) AS octets,
                           CASE WHEN pg_catalog.octet_length(pg_catalog.convert_to(v.%s, 'UTF8')) BETWEEN 1 AND %d
                                THEN pg_catalog.convert_to(v.%s, 'UTF8') ELSE NULL END AS text_utf8
                      FROM (VALUES %s) needed(id)
                      LEFT JOIN public.%s v ON v.id = needed.id
                     ORDER BY needed.id
                    """.formatted(column, column, maximum, column, values(batch.size()), table);
            Set<UUID> seen = new HashSet<>();
            query(connection, sql, List.copyOf(expected.keySet()), batch.size(), row -> {
                UUID id = uuid(row, "id");
                VersionHeader metadata = expected.get(id);
                require(metadata != null && seen.add(id) && metadata.octets() == number(row, "octets"));
                validatedText(row, metadata.octets(), metadata.digest(), deadline);
                return id;
            }, deadline);
            require(seen.equals(expected.keySet()));
        }
    }

    private List<Acceptance> readEvidenceDocuments(Connection connection, LegalActorSnapshot actor,
            Map<UUID, EvidenceHeader> evidence, Map<UUID, HistoricalRequirement> requirements,
            Map<UUID, List<DocumentReference>> references, Map<UUID, HistoricalDocument> canonicalDocuments,
            Map<UUID, Instant> activated, Map<UUID, Instant> documentActivated, ObservationBudget budget,
            LegalPrivateRequirementsDeadline deadline) throws SQLException {
        Map<UUID, List<EvidenceDocument>> documents = new LinkedHashMap<>();
        evidence.keySet().forEach(id -> documents.put(id, new ArrayList<>()));
        for (List<UUID> batch : batches(List.copyOf(evidence.keySet()))) {
            List<Object> args = new ArrayList<>(batch);
            args.add(actor.userId());
            args.add(actor.tallerId());
            String sql = """
                    SELECT needed.id AS acceptance_id, a.id AS actor_acceptance, d.documento_ordinal,
                           d.documento_version_id, d.documento_clave, d.tipo, d.version, d.titulo, d.sha256
                      FROM (VALUES %s) needed(id)
                      LEFT JOIN public.legal_aceptaciones a ON a.id = needed.id AND a.user_id = ? AND a.taller_id = ?
                      LEFT JOIN public.legal_aceptacion_documentos d ON d.aceptacion_id = a.id
                     ORDER BY needed.id, d.documento_ordinal
                    """.formatted(values(batch.size()));
            query(connection, sql, args, Math.min(budget.remainingRows(), batch.size() * LegalManifestLimits.MAX_DOCUMENTS_PER_REQUIREMENT), row -> {
                budget.addRows(1);
                UUID acceptance = uuid(row, "acceptance_id");
                require(acceptance.equals(uuid(row, "actor_acceptance")) && batch.contains(acceptance));
                List<EvidenceDocument> linked = documents.get(acceptance);
                UUID id = uuid(row, "documento_version_id");
                HistoricalDocument canonical = canonicalDocuments.get(id);
                require(canonical != null && integer(row, "documento_ordinal") == linked.size() + 1
                        && linked.size() < LegalManifestLimits.MAX_DOCUMENTS_PER_REQUIREMENT
                        && canonical.header().key().equals(row.getString("documento_clave"))
                        && canonical.type().name().equals(row.getString("tipo"))
                        && canonical.version().equals(row.getString("version"))
                        && canonical.title().equals(row.getString("titulo"))
                        && canonical.header().digest().equals(row.getString("sha256")));
                linked.add(new EvidenceDocument(row.getString("documento_clave"), id, row.getString("sha256")));
                return acceptance;
            }, deadline);
        }
        List<Acceptance> result = new ArrayList<>();
        // Legacy SCOPE_V1 stores transaction_timestamp(), which may precede an editorial lock wait.
        // Wall-clock order between a batch and its source/activation is not evidence of causality.
        // Sources, transitions and batch time are independently bounded by the observation above.
        for (EvidenceHeader item : evidence.values()) {
            HistoricalRequirement canonical = requirements.get(item.requirementId());
            require(canonical != null && canonical.header().line().equals(item.line())
                    && canonical.header().ordinal() == item.ordinal() && canonical.header().digest().equals(item.digest())
                    && activated.containsKey(item.requirementId())
                    && documents.get(item.id()).size() == references.get(item.requirementId()).size());
            for (EvidenceDocument document : documents.get(item.id())) {
                require(documentActivated.containsKey(document.versionId()));
            }
            result.add(new Acceptance(item.id(), actor.userId(), actor.tallerId(), item.role(), item.requirementId(),
                    item.digest(), documents.get(item.id()), item.acceptedAt()));
        }
        return List.copyOf(result);
    }

    private static void include(Map<UUID, Interval> intervals, UUID line, int ordinal) {
        require(ordinal > 0);
        Interval old = intervals.get(line);
        intervals.put(line, old == null ? new Interval(line, ordinal, ordinal)
                : new Interval(line, Math.min(old.from(), ordinal), Math.max(old.to(), ordinal)));
    }

    private static String intervalValues(int size) {
        require(size > 0 && size <= BATCH_SIZE);
        return String.join(", ", Collections.nCopies(size, "(?::uuid, ?::integer, ?::integer)"));
    }

    private static List<Object> intervalArguments(List<Interval> intervals) {
        List<Object> arguments = new ArrayList<>();
        for (Interval interval : intervals) {
            arguments.add(interval.line());
            arguments.add(interval.from());
            arguments.add(interval.to());
        }
        return arguments;
    }

    private static void countInterval(Map<UUID, Integer> counts, Interval interval, int ordinal) {
        require(interval != null && ordinal >= interval.from() && ordinal <= interval.to());
        int count = counts.merge(interval.line(), 1, Integer::sum);
        // The included base endpoint is outside the contractual (base, target] interval.
        require(count <= LegalRequirementLineage.MAX_INTERVAL_VERSIONS + 1);
    }

    private static void accreditIntervalEndpoints(List<Interval> intervals, List<VersionHeader> versions) {
        Map<UUID, Set<Integer>> endpoints = new LinkedHashMap<>();
        for (VersionHeader version : versions) {
            endpoints.computeIfAbsent(version.line(), ignored -> new HashSet<>()).add(version.ordinal());
        }
        for (Interval interval : intervals) {
            Set<Integer> present = endpoints.get(interval.line());
            require(present != null && present.contains(interval.from()) && present.contains(interval.to()));
        }
    }

    private static int positiveInteger(ResultSet row, String column) throws SQLException {
        int value = integer(row, column);
        require(value > 0);
        return value;
    }

    /** Distinct historical source versions, plus canonical links and actual evidence rows. */
    static final class ObservationBudget {
        static final long MAX_SOURCE_BYTES = 128L * 1024 * 1024;
        private int rows;
        private long sourceBytes;

        int remainingRows() { return LegalRequirementLineage.MAX_OBSERVATION_ROWS - rows; }

        void addRows(int count) {
            require(count >= 0 && count <= remainingRows());
            rows += count;
        }

        void addSourceBytes(long bytes) {
            require(bytes >= 0 && bytes <= MAX_SOURCE_BYTES - sourceBytes);
            sourceBytes += bytes;
        }
    }

    record VersionHeader(UUID id, UUID line, String key, int ordinal, EstadoVersionLegal state,
                         boolean reacceptance, String digest, long octets, Instant importedAt, Instant changedAt) { }
    record Transition(EstadoVersionLegal from, EstadoVersionLegal to, Instant at) { }
    private record Interval(UUID line, int from, int to) { }
    private record EvidenceHeader(UUID id, UUID requirementId, UUID line, int ordinal, UserRole role,
                                  String digest, Instant acceptedAt) { }
    private record HistoricalRequirement(VersionHeader header, ContextoLegal context, TipoActoLegal actType,
                                         Set<AudienciaLegal> audiences, boolean required) { }
    private record HistoricalDocument(VersionHeader header, TipoDocumentoLegal type, String version, String title,
                                      Instant effectiveAt) { }
    private record HistoricalObservation(LegalRequirementLineage lineage, List<Acceptance> evidence) { }

    private String validatedText(ResultSet row, long octets, String digest,
                                 LegalPrivateRequirementsDeadline deadline) throws SQLException {
        byte[] bytes = row.getBytes("text_utf8");
        deadline.check();
        require(bytes != null && bytes.length == octets);
        var validation = textValidator.validate(bytes, digest, "private-requirements");
        deadline.check();
        require(validation.passed());
        return validation.value().orElseThrow().text();
    }

    /** Every query has a maximum-plus-one sentinel; text queries also have a server-side byte gate. */
    static <T> List<T> query(Connection connection, String sql, List<?> arguments, int maximum,
                                      RowMapper<T> mapper, LegalPrivateRequirementsDeadline deadline) throws SQLException {
        deadline.check();
        require(maximum >= 0 && maximum <= LegalRequirementLineage.MAX_OBSERVATION_ROWS);
        try (PreparedStatement statement = connection.prepareStatement(sql + " LIMIT " + (maximum + 1),
                ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            try {
                statement.setFetchSize(BATCH_SIZE);
                for (int index = 0; index < arguments.size(); index++) {
                    statement.setObject(index + 1, arguments.get(index));
                }
                deadline.check();
                try (ResultSet rows = statement.executeQuery()) {
                    List<T> result = new ArrayList<>();
                    while (true) {
                        deadline.check();
                        boolean more = rows.next();
                        deadline.check();
                        if (!more) {
                            return result;
                        }
                        require(result.size() < maximum);
                        result.add(Objects.requireNonNull(mapper.map(rows)));
                        deadline.check();
                    }
                }
            } catch (SQLException | RuntimeException failure) {
                deadline.cancel(statement);
                throw failure;
            }
        }
    }

    private void requireTransaction() {
        require(TransactionSynchronizationManager.isActualTransactionActive()
                && !TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                && Objects.equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),
                        Connection.TRANSACTION_READ_COMMITTED)
                && TransactionSynchronizationManager.hasResource(dataSource));
    }

    private void requireBoundConnection(Connection connection) throws SQLException {
        require(!connection.getAutoCommit() && !connection.isReadOnly()
                && connection.getTransactionIsolation() == Connection.TRANSACTION_READ_COMMITTED
                && DataSourceUtils.isConnectionTransactional(DataSourceUtils.getTargetConnection(connection), dataSource));
    }

    private static OffsetDateTime requirePast(ResultSet row, String column, LegalEditorialTimeBoundary boundary)
            throws SQLException {
        OffsetDateTime value = timestamp(row, column);
        require(!value.toInstant().isAfter(boundary.observedAt()));
        return value;
    }

    private static OffsetDateTime timestamp(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        require(value != null);
        return value;
    }

    private static UUID uuid(ResultSet row, String column) throws SQLException {
        UUID value = row.getObject(column, UUID.class);
        require(value != null);
        return value;
    }

    private static long number(ResultSet row, String column) throws SQLException {
        long value = row.getLong(column);
        require(!row.wasNull());
        return value;
    }

    private static int integer(ResultSet row, String column) throws SQLException {
        return Math.toIntExact(number(row, column));
    }

    private static boolean bool(ResultSet row, String column) throws SQLException {
        boolean value = row.getBoolean(column);
        require(!row.wasNull());
        return value;
    }

    private static void require(boolean condition) {
        if (!condition) {
            throw new LegalPrivateRequirementsReadException();
        }
    }

    private static String values(int count) {
        require(count > 0 && count <= BATCH_SIZE);
        return String.join(", ", Collections.nCopies(count, "(?::uuid)"));
    }

    private static <T> List<List<T>> batches(List<T> ids) {
        List<List<T>> result = new ArrayList<>();
        for (int start = 0; start < ids.size(); start += BATCH_SIZE) {
            result.add(ids.subList(start, Math.min(ids.size(), start + BATCH_SIZE)));
        }
        return result;
    }

    @FunctionalInterface
    interface RowMapper<T> {
        T map(ResultSet row) throws SQLException;
    }

    private record RequirementMetadata(UUID id, UUID line, String key, int ordinal, int lineageOrdinal, TipoActoLegal actType,
                                       String sha256, boolean required, long octets) { }

    private record DocumentMetadata(UUID id, TipoDocumentoLegal type, String version, String title,
                                    String sha256, OffsetDateTime effectiveAt, long octets) {
        private DocumentMetadata {
            new LegalDocumentSummary(id, type, version, title, sha256, effectiveAt.toInstant(),
                    EstadoVersionLegal.VIGENTE, LocaleLegal.ES_AR);
            require(version.codePointCount(0, version.length()) <= 40);
        }
    }
}
