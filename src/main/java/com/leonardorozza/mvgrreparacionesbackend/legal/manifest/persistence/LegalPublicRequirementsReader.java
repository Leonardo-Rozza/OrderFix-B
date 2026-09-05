package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.CanonicalTextValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalDocumentSummary;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestLimits;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalPublicRegistrationRequirements;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalPublicRequirementsValidator;
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
final class LegalPublicRequirementsReader {

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
             WHERE a.id = ?
            """;
    private static final String MEMBERS_SQL = """
            SELECT m.publicacion_id, m.requisito_version_id, m.requisito_linea_id, m.manifest_ordinal,
                   v.id AS version_id, v.requisito_linea_id AS version_line, v.estado,
                   v.estado_cambiado_en, v.afirmacion_sha256, v.requerido,
                   pg_catalog.octet_length(pg_catalog.convert_to(v.afirmacion, 'UTF8')) AS statement_octets,
                   pg_catalog.char_length(v.afirmacion) AS statement_characters,
                   line.id AS line_id, line.locale, line.contexto, line.tipo_acto,
                   publication.id AS membership_id, publication.manifest_ordinal AS publication_ordinal,
                   EXISTS (SELECT 1 FROM public.legal_requisito_audiencias audience
                            WHERE audience.requisito_linea_id = line.id
                              AND audience.audiencia = 'ADMIN_TITULAR') AS applicable_audience
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
                    OR (line.locale = 'es-AR' AND line.contexto = 'REGISTRO'
                        AND EXISTS (SELECT 1 FROM public.legal_requisito_audiencias audience
                                     WHERE audience.requisito_linea_id = line.id
                                       AND audience.audiencia = 'ADMIN_TITULAR')))
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
                ON context.documento_version_id = v.id AND context.contexto = 'REGISTRO'
              LEFT JOIN public.legal_publicacion_documentos membership
                ON membership.publicacion_id = ? AND membership.documento_version_id = v.id
              LEFT JOIN public.legal_documento_vigentes current
                ON current.tipo = line.tipo AND current.locale = line.locale AND current.contexto = 'REGISTRO'
             ORDER BY needed.id
            """;

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final CanonicalTextValidator textValidator = new CanonicalTextValidator();
    private final LegalPublicRequirementsValidator validator = new LegalPublicRequirementsValidator();

    LegalPublicRequirementsReader(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.dataSource = Objects.requireNonNull(jdbc.getDataSource(), "jdbc.dataSource");
    }

    boolean usesJdbc(JdbcTemplate candidate) {
        return jdbc == candidate;
    }

    LegalPublicRegistrationRequirements read(LegalRequiredSetAggregateReceipt receipt,
                                             LegalEditorialTimeBoundary boundary,
                                             LegalPublicRequirementsDeadline deadline) {
        Objects.requireNonNull(deadline, "deadline").check();
        Objects.requireNonNull(boundary, "boundary");
        ScopeOrigin origin = registrationOrigin(Objects.requireNonNull(receipt, "receipt"));
        requireTransaction();
        return Objects.requireNonNull(jdbc.execute((ConnectionCallback<LegalPublicRegistrationRequirements>) connection -> {
            requireBoundConnection(connection);
            String scopeRevision = readHeader(connection, receipt, origin, boundary, deadline);
            List<RequirementMetadata> requirements = readMembers(connection, origin, boundary, deadline);
            comparePublicationMembers(connection, origin, requirements, deadline);
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
            // No statement or Markdown TEXT has crossed JDBC before all graph and byte gates above.
            Map<UUID, String> statements = readStatements(connection, requirements, deadline);
            Map<UUID, DocumentProjection> documents = readDocuments(connection, metadata, deadline);
            List<RequirementProjection> projection = new ArrayList<>(requirements.size());
            for (RequirementMetadata requirement : requirements) {
                deadline.check();
                projection.add(new RequirementProjection(requirement.id(), ContextoLegal.REGISTRO,
                        requirement.actType(), statements.get(requirement.id()), requirement.sha256(),
                        references.get(requirement.id()).stream().map(documents::get).toList(),
                        requirement.required()));
            }
            deadline.check();
            LegalPublicRegistrationRequirements result = validator.validate(
                    new LegalRequiredSetProjection(ContextoLegal.REGISTRO, LocaleLegal.ES_AR, projection));
            deadline.check();
            require(result.scopeRevision().equals(scopeRevision)
                    && result.requiredSetRevision().equals(receipt.requiredSetRevision()));
            return result;
        }));
    }

    private static ScopeOrigin registrationOrigin(LegalRequiredSetAggregateReceipt receipt) {
        var provenance = receipt.provenance();
        require(provenance.profile() == PerfilAgregadoLegal.REGISTRATION
                && provenance.locale() == LocaleLegal.ES_AR
                && provenance.audience() == AudienciaLegal.ADMIN_TITULAR
                && provenance.scopes().size() == 1
                && provenance.scopes().getFirst().context() == ContextoLegal.REGISTRO);
        return provenance.scopes().getFirst();
    }

    private String readHeader(Connection connection, LegalRequiredSetAggregateReceipt receipt,
                              ScopeOrigin origin, LegalEditorialTimeBoundary boundary,
                              LegalPublicRequirementsDeadline deadline) throws SQLException {
        List<String> rows = query(connection, HEADER_SQL, List.of(receipt.aggregateId()), 1, row -> {
            require(receipt.aggregateId().equals(uuid(row, "aggregate_id"))
                    && "REGISTRATION".equals(row.getString("perfil"))
                    && "es-AR".equals(row.getString("aggregate_locale"))
                    && "ADMIN_TITULAR".equals(row.getString("aggregate_audience"))
                    && "AGGREGATE_V1".equals(row.getString("revision_scheme"))
                    && receipt.requiredSetRevision().equals(row.getString("aggregate_revision"))
                    && receipt.provenanceFingerprint().equals(row.getString("provenance_fingerprint"))
                    && receipt.createdAt().equals(timestamp(row, "aggregate_created").toInstant())
                    && integer(row, "scope_count") == 1 && integer(row, "scope_ordinal") == 1
                    && "REGISTRO".equals(row.getString("scope_context"))
                    && "es-AR".equals(row.getString("scope_locale"))
                    && "ADMIN_TITULAR".equals(row.getString("scope_audience"))
                    && origin.requiredSetId().equals(uuid(row, "scope_set"))
                    && origin.publicationId().equals(uuid(row, "scope_publication"))
                    && origin.requiredSetId().equals(uuid(row, "set_id"))
                    && origin.publicationId().equals(uuid(row, "publicacion_id"))
                    && "es-AR".equals(row.getString("locale"))
                    && "REGISTRO".equals(row.getString("contexto"))
                    && "ADMIN_TITULAR".equals(row.getString("audiencia"))
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

    private List<RequirementMetadata> readMembers(Connection connection, ScopeOrigin origin,
                                                  LegalEditorialTimeBoundary boundary,
                                                  LegalPublicRequirementsDeadline deadline) throws SQLException {
        List<RequirementMetadata> rows = query(connection, MEMBERS_SQL,
                List.of(origin.publicationId(), origin.requiredSetId()), LegalManifestLimits.MAX_REQUIREMENTS, row -> {
                    UUID id = uuid(row, "requisito_version_id");
                    UUID line = uuid(row, "requisito_linea_id");
                    int ordinal = integer(row, "manifest_ordinal");
                    require(origin.publicationId().equals(uuid(row, "publicacion_id"))
                            && id.equals(uuid(row, "version_id")) && line.equals(uuid(row, "version_line"))
                            && line.equals(uuid(row, "line_id")) && "VIGENTE".equals(row.getString("estado"))
                            && "es-AR".equals(row.getString("locale"))
                            && "REGISTRO".equals(row.getString("contexto"))
                            && bool(row, "applicable_audience") && row.getObject("membership_id") != null
                            && ordinal >= 1 && ordinal <= LegalManifestLimits.MAX_REQUIREMENTS
                            && ordinal == integer(row, "publication_ordinal"));
                    requirePast(row, "estado_cambiado_en", boundary);
                    int characters = integer(row, "statement_characters");
                    long octets = number(row, "statement_octets");
                    require(characters >= 1 && characters <= MAX_STATEMENT_CODE_POINTS
                            && octets >= 1 && octets <= MAX_STATEMENT_BYTES);
                    return new RequirementMetadata(id, line, ordinal,
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

    private void comparePublicationMembers(Connection connection, ScopeOrigin origin,
                                            List<RequirementMetadata> members,
                                            LegalPublicRequirementsDeadline deadline) throws SQLException {
        Map<UUID, RequirementMetadata> expected = new LinkedHashMap<>();
        members.forEach(member -> expected.put(member.id(), member));
        List<UUID> rows = query(connection, PUBLICATION_MEMBERS_SQL, List.of(origin.publicationId()),
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
                                                LegalPublicRequirementsDeadline deadline) throws SQLException {
        Map<UUID, List<UUID>> result = new LinkedHashMap<>();
        requirements.forEach(requirement -> result.put(requirement.id(), new ArrayList<>()));
        List<UUID> ids = List.copyOf(result.keySet());
        String sql = """
                SELECT needed.id AS requirement_id, reference.documento_version_id, reference.documento_ordinal
                  FROM (VALUES %s) needed(id)
                  LEFT JOIN public.legal_requisito_documentos reference ON reference.requisito_version_id = needed.id
                 ORDER BY needed.id, reference.documento_ordinal
                """.formatted(values(ids.size()));
        query(connection, sql, ids, Math.multiplyExact(ids.size(), LegalManifestLimits.MAX_DOCUMENTS_PER_REQUIREMENT),
                row -> {
                    List<UUID> documents = result.get(uuid(row, "requirement_id"));
                    UUID document = uuid(row, "documento_version_id");
                    require(documents != null && documents.size() < LegalManifestLimits.MAX_DOCUMENTS_PER_REQUIREMENT
                            && integer(row, "documento_ordinal") == documents.size() + 1
                            && !documents.contains(document));
                    documents.add(document);
                    return document;
                }, deadline);
        result.values().forEach(documents -> require(!documents.isEmpty()));
        return result;
    }

    private Map<UUID, DocumentMetadata> readDocumentMetadata(Connection connection, List<UUID> ids,
                                                            ScopeOrigin origin,
                                                            LegalEditorialTimeBoundary boundary,
                                                            LegalPublicRequirementsDeadline deadline) throws SQLException {
        Map<UUID, DocumentMetadata> result = new LinkedHashMap<>();
        for (List<UUID> batch : batches(ids)) {
            List<Object> args = new ArrayList<>(batch);
            args.add(origin.publicationId());
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
                                             LegalPublicRequirementsDeadline deadline) throws SQLException {
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
                                                       LegalPublicRequirementsDeadline deadline) throws SQLException {
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

    private String validatedText(ResultSet row, long octets, String digest,
                                 LegalPublicRequirementsDeadline deadline) throws SQLException {
        byte[] bytes = row.getBytes("text_utf8");
        deadline.check();
        require(bytes != null && bytes.length == octets);
        var validation = textValidator.validate(bytes, digest, "public-requirements");
        deadline.check();
        require(validation.passed());
        return validation.value().orElseThrow().text();
    }

    /** Every query has a maximum-plus-one sentinel; text queries also have a server-side byte gate. */
    private static <T> List<T> query(Connection connection, String sql, List<?> arguments, int maximum,
                                      RowMapper<T> mapper, LegalPublicRequirementsDeadline deadline) throws SQLException {
        deadline.check();
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
            throw new LegalPublicRequirementsReadException();
        }
    }

    private static String values(int count) {
        require(count > 0 && count <= LegalManifestLimits.MAX_REQUIREMENTS);
        return String.join(", ", Collections.nCopies(count, "(?::uuid)"));
    }

    private static List<List<UUID>> batches(List<UUID> ids) {
        List<List<UUID>> result = new ArrayList<>();
        for (int start = 0; start < ids.size(); start += BATCH_SIZE) {
            result.add(ids.subList(start, Math.min(ids.size(), start + BATCH_SIZE)));
        }
        return result;
    }

    @FunctionalInterface
    private interface RowMapper<T> {
        T map(ResultSet row) throws SQLException;
    }

    private record RequirementMetadata(UUID id, UUID line, int ordinal, TipoActoLegal actType,
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
