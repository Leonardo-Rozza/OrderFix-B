package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.CanonicalTextValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalActorSnapshot;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalDocumentSummary;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestLimits;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceHistoryPage.Acceptance;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceHistoryPage.Document;
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
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Read-only accreditation of one page of original evidence, independent of current pointers. */
final class LegalAcceptanceHistoryReader {
    private static final int BATCH_SIZE = 32;
    private static final int MAX_STATEMENT_BYTES = 4_000;
    private static final int MAX_STATEMENT_CODE_POINTS = 1_000;
    private static final int MAX_DOCUMENTS = LegalAcceptanceHistoryPage.MAX_PAGE_SIZE
            * LegalManifestLimits.MAX_DOCUMENTS_PER_REQUIREMENT;
    private static final long MAX_SOURCE_BYTES = 128L * 1024 * 1024;

    private static final String MEMBERSHIP_SQL = """
            CASE b.revision_scheme
              WHEN 'SCOPE_V1' THEN b.perfil IS NULL AND b.agregado_id IS NULL AND EXISTS (
                SELECT 1 FROM public.legal_requisito_conjuntos c
                JOIN public.legal_requisito_conjunto_miembros m ON m.conjunto_id = c.id
                JOIN public.legal_publicaciones p ON p.id = c.publicacion_id
                WHERE c.locale = line.locale AND c.contexto = line.contexto AND c.audiencia = b.audiencia
                  AND c.required_set_revision = b.required_set_revision AND c.creado_en <= observation.observed_at
                  AND m.publicacion_id = c.publicacion_id AND m.requisito_version_id = v.id
                  AND m.requisito_linea_id = line.id AND p.estado_construccion = 'SELLADO'
                  AND p.locale = line.locale AND p.sellado_en <= observation.observed_at
                  AND p.importado_en <= observation.observed_at)
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
                  AND p.estado_construccion = 'SELLADO' AND p.locale = line.locale
                  AND p.sellado_en <= observation.observed_at AND p.importado_en <= observation.observed_at)
              ELSE false END
            """;

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final CanonicalTextValidator textValidator = new CanonicalTextValidator();

    LegalAcceptanceHistoryReader(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.dataSource = Objects.requireNonNull(jdbc.getDataSource(), "jdbc.dataSource");
    }

    boolean usesJdbc(JdbcTemplate candidate) {
        return jdbc == candidate;
    }

    /** The caller holds both shared editorial and actor gates, including the final commit. */
    LegalAcceptanceHistoryPage read(LegalActorSnapshot actor, ContextoLegal context, int page, int size,
                                   LegalEditorialTimeBoundary boundary, LegalPrivateRequirementsDeadline deadline) {
        return readInTransaction(actor, context, page, size, boundary, deadline, false);
    }

    /** An already-authorized internal export sees all committed evidence in one MVCC snapshot. */
    LegalAcceptanceHistoryPage readExportSnapshot(LegalActorSnapshot actor, int page, int size,
            LegalEditorialTimeBoundary boundary, LegalPrivateRequirementsDeadline deadline) {
        return readInTransaction(actor, null, page, size, boundary, deadline, true);
    }

    private LegalAcceptanceHistoryPage readInTransaction(LegalActorSnapshot actor, ContextoLegal context, int page,
            int size, LegalEditorialTimeBoundary boundary, LegalPrivateRequirementsDeadline deadline, boolean exportSnapshot) {
        long offset = LegalAcceptanceHistoryPage.pageOffset(page, size);
        Objects.requireNonNull(actor, "actor").requireEnabled();
        Objects.requireNonNull(boundary, "boundary");
        Objects.requireNonNull(deadline, "deadline").check();
        requireTransaction(exportSnapshot);
        try {
            return Objects.requireNonNull(jdbc.execute((ConnectionCallback<LegalAcceptanceHistoryPage>) connection -> {
                requireBoundConnection(connection, exportSnapshot);
                long total = readCount(connection, actor, context, deadline);
                long pages = LegalAcceptanceHistoryPage.pagesFor(total, size);
                if (offset >= total) return new LegalAcceptanceHistoryPage(List.of(), page, size, total, pages);
                SourceBudget budget = new SourceBudget();
                Map<UUID, EvidenceHeader> headers = readHeaders(connection, actor, context, offset, size,
                        boundary, budget, deadline);
                require(headers.size() == Math.min(size, total - offset));
                Map<UUID, SourceHeader> requirementSources = new LinkedHashMap<>();
                for (EvidenceHeader header : headers.values()) {
                    require(requirementSources.putIfAbsent(header.requirementId(), header.source()) == null);
                }
                Map<UUID, DocumentSource> documentSources = new LinkedHashMap<>();
                Map<UUID, List<Document>> documents = readDocuments(connection, actor, headers,
                        documentSources, boundary, budget, deadline);
                require(documentSources.size() <= MAX_DOCUMENTS);
                accreditTransitions(connection, true, List.copyOf(requirementSources.values()), boundary, deadline);
                Map<UUID, Instant> documentActivations = accreditTransitions(connection, false,
                        documentSources.values().stream().map(DocumentSource::header).toList(), boundary, deadline);
                for (DocumentSource source : documentSources.values()) {
                    require(!documentActivations.get(source.header().id()).isBefore(source.effectiveAt()));
                }
                // All page headers and the distinct-source byte budget are accredited before any TEXT fetch.
                validateDocumentSources(connection, documentSources, deadline);
                Map<UUID, String> statements = readStatements(connection, actor, headers, deadline);
                List<Acceptance> result = new ArrayList<>();
                for (EvidenceHeader header : headers.values()) {
                    deadline.check();
                    result.add(new Acceptance(header.id(), header.requirementId(), header.context(), header.actType(),
                            statements.get(header.id()), header.source().digest(), documents.get(header.id()), header.acceptedAt()));
                }
                deadline.check();
                return new LegalAcceptanceHistoryPage(result, page, size, total, pages);
            }));
        } catch (LegalAcceptanceHistoryReadException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new LegalAcceptanceHistoryReadException(failure);
        }
    }

    private long readCount(Connection connection, LegalActorSnapshot actor, ContextoLegal context,
                           LegalPrivateRequirementsDeadline deadline) throws SQLException {
        // Count raw actor snapshots. A missing or inconsistent joined source must not hide an act.
        String sql = "SELECT count(*) AS total_elements FROM public.legal_aceptaciones WHERE user_id = ? AND taller_id = ?"
                + (context == null ? "" : " AND contexto = ?");
        List<Long> rows = query(connection, sql, actorArguments(actor, context), 1, row -> {
            long count = number(row, "total_elements");
            require(count >= 0 && count <= LegalAcceptanceHistoryPage.MAX_SAFE_COUNT);
            return count;
        }, deadline);
        require(rows.size() == 1);
        return rows.getFirst();
    }

    private Map<UUID, EvidenceHeader> readHeaders(Connection connection, LegalActorSnapshot actor, ContextoLegal context,
            long offset, int size, LegalEditorialTimeBoundary boundary, SourceBudget budget,
            LegalPrivateRequirementsDeadline deadline) throws SQLException {
        String sql = """
                WITH selected AS MATERIALIZED (
                  SELECT a.id, b.aceptado_en
                    FROM public.legal_aceptaciones a
                    LEFT JOIN public.legal_aceptacion_lotes b ON b.id = a.lote_id
                   WHERE a.user_id = ? AND a.taller_id = ? %s
                   ORDER BY b.aceptado_en DESC NULLS FIRST, a.id DESC LIMIT ? OFFSET ?
                )
                SELECT a.id, a.lote_id, a.user_id, a.taller_id, a.requisito_version_id,
                       a.contexto, a.tipo_acto, a.afirmacion_sha256,
                       pg_catalog.octet_length(pg_catalog.convert_to(a.afirmacion, 'UTF8')) AS snapshot_octets,
                       pg_catalog.char_length(a.afirmacion) AS snapshot_characters,
                       b.id AS batch_id, b.user_id AS batch_user, b.taller_id AS batch_workshop,
                       b.rol_wire, b.audiencia, b.aceptado_en, b.required_set_revision, b.revision_scheme, b.perfil,
                       v.id AS source_id, v.requisito_linea_id AS source_line, v.lineage_ordinal,
                       v.version AS source_version, v.estado AS source_state, v.estado_cambiado_en AS source_changed,
                       v.afirmacion_sha256 AS source_digest,
                       pg_catalog.octet_length(pg_catalog.convert_to(v.afirmacion, 'UTF8')) AS source_octets,
                       pg_catalog.char_length(v.afirmacion) AS source_characters,
                       line.id AS line_id, line.clave AS source_key, line.locale,
                       publication.id AS publication_id, publication.estado_construccion,
                       publication.locale AS publication_locale, publication.importado_en, publication.sellado_en,
                       membership.id AS membership_id,
                       (SELECT count(*) FROM (SELECT 1 FROM public.legal_requisito_documentos r
                         WHERE r.requisito_version_id = v.id LIMIT 17) bounded) AS document_count,
                       EXISTS (SELECT 1 FROM public.legal_requisito_audiencias audience
                                WHERE audience.requisito_linea_id = line.id AND audience.audiencia = b.audiencia)
                         AS applicable_audience,
                       (pg_catalog.convert_to(a.requisito_clave, 'UTF8') = pg_catalog.convert_to(line.clave, 'UTF8')
                         AND pg_catalog.convert_to(a.requisito_version, 'UTF8') = pg_catalog.convert_to(v.version, 'UTF8')
                         AND a.contexto = line.contexto AND a.tipo_acto = line.tipo_acto
                         AND pg_catalog.convert_to(a.afirmacion, 'UTF8') = pg_catalog.convert_to(v.afirmacion, 'UTF8')
                         AND a.afirmacion_sha256 = v.afirmacion_sha256
                         AND a.requerido = v.requerido) AS canonical_snapshot,
                       %s AS source_membership
                  FROM selected
                  CROSS JOIN (SELECT ?::timestamptz AS observed_at) observation
                  LEFT JOIN public.legal_aceptaciones a ON a.id = selected.id
                  LEFT JOIN public.legal_aceptacion_lotes b ON b.id = a.lote_id
                  LEFT JOIN public.legal_requisito_versiones v ON v.id = a.requisito_version_id
                  LEFT JOIN public.legal_requisito_lineas line ON line.id = v.requisito_linea_id
                  LEFT JOIN public.legal_publicaciones publication ON publication.id = v.publicacion_intro_id
                  LEFT JOIN public.legal_publicacion_requisitos membership
                    ON membership.publicacion_id = v.publicacion_intro_id AND membership.requisito_version_id = v.id
                 ORDER BY selected.aceptado_en DESC NULLS FIRST, selected.id DESC
                """.formatted(context == null ? "" : "AND a.contexto = ?", MEMBERSHIP_SQL);
        List<Object> arguments = actorArguments(actor, context);
        arguments.add(size);
        arguments.add(offset);
        arguments.add(boundary.observedAt().atOffset(ZoneOffset.UTC));
        Map<UUID, EvidenceHeader> result = new LinkedHashMap<>();
        query(connection, sql, arguments, size, row -> {
            UUID id = uuid(row, "id");
            require(number(row, "user_id") == actor.userId() && number(row, "taller_id") == actor.tallerId()
                    && number(row, "batch_user") == actor.userId() && number(row, "batch_workshop") == actor.tallerId()
                    && uuid(row, "lote_id").equals(uuid(row, "batch_id"))
                    && bool(row, "canonical_snapshot") && bool(row, "source_membership") && bool(row, "applicable_audience"));
            UserRole originalRole = UserRole.valueOf(row.getString("rol_wire"));
            require(originalRole.toAudienciaLegal().name().equals(row.getString("audiencia")));
            require(row.getString("required_set_revision").matches("sha256:[0-9a-f]{64}"));
            if ("AGGREGATE_V1".equals(row.getString("revision_scheme"))) {
                PerfilAgregadoLegal.valueOf(row.getString("perfil"));
            } else require("SCOPE_V1".equals(row.getString("revision_scheme")) && row.getObject("perfil") == null);
            SourceHeader source = sourceHeader(row, true, boundary);
            require(uuid(row, "requisito_version_id").equals(source.id())
                    && source.digest().equals(row.getString("afirmacion_sha256"))
                    && number(row, "snapshot_octets") == source.octets()
                    && number(row, "snapshot_characters") == number(row, "source_characters"));
            budget.add(source.octets());
            int documentCount = integer(row, "document_count");
            require(documentCount > 0 && documentCount <= LegalManifestLimits.MAX_DOCUMENTS_PER_REQUIREMENT);
            ContextoLegal originalContext = ContextoLegal.valueOf(row.getString("contexto"));
            require(context == null || context == originalContext);
            EvidenceHeader header = new EvidenceHeader(id, source.id(), originalContext,
                    TipoActoLegal.valueOf(row.getString("tipo_acto")), source,
                    documentCount, past(row, "aceptado_en", boundary));
            require(result.putIfAbsent(id, header) == null);
            return id;
        }, deadline);
        return result;
    }

    private Map<UUID, List<Document>> readDocuments(Connection connection, LegalActorSnapshot actor,
            Map<UUID, EvidenceHeader> headers, Map<UUID, DocumentSource> sources, LegalEditorialTimeBoundary boundary,
            SourceBudget budget, LegalPrivateRequirementsDeadline deadline) throws SQLException {
        Map<UUID, List<Document>> result = new LinkedHashMap<>();
        headers.keySet().forEach(id -> result.put(id, new ArrayList<>()));
        for (List<UUID> batch : batches(List.copyOf(headers.keySet()))) {
            String sql = """
                    SELECT needed.id AS acceptance_id, a.id AS actor_acceptance,
                           d.documento_ordinal, d.documento_version_id, d.tipo, d.version, d.titulo, d.sha256,
                           v.id AS source_id, v.documento_linea_id AS source_line, v.lineage_ordinal,
                           v.version AS source_version, v.estado AS source_state, v.estado_cambiado_en AS source_changed,
                           v.sha256 AS source_digest, v.vigente_desde,
                           pg_catalog.octet_length(pg_catalog.convert_to(v.contenido_markdown, 'UTF8')) AS source_octets,
                           line.id AS line_id, line.clave AS source_key, line.locale,
                           publication.id AS publication_id, publication.estado_construccion,
                           publication.locale AS publication_locale, publication.importado_en, publication.sellado_en,
                           membership.id AS membership_id,
                           (pg_catalog.convert_to(d.documento_clave, 'UTF8') = pg_catalog.convert_to(line.clave, 'UTF8')
                             AND d.tipo = line.tipo
                             AND pg_catalog.convert_to(d.version, 'UTF8') = pg_catalog.convert_to(v.version, 'UTF8')
                             AND pg_catalog.convert_to(d.titulo, 'UTF8') = pg_catalog.convert_to(v.titulo, 'UTF8')
                             AND d.sha256 = v.sha256
                             AND r.documento_version_id = d.documento_version_id
                             AND r.documento_ordinal = d.documento_ordinal) AS canonical_snapshot,
                           EXISTS (SELECT 1 FROM public.legal_documento_contextos c
                                    WHERE c.documento_version_id = v.id AND c.contexto = a.contexto) AS source_context
                      FROM (VALUES %s) needed(id)
                      LEFT JOIN public.legal_aceptaciones a ON a.id = needed.id AND a.user_id = ? AND a.taller_id = ?
                      LEFT JOIN public.legal_aceptacion_documentos d ON d.aceptacion_id = a.id
                      LEFT JOIN public.legal_requisito_documentos r
                        ON r.requisito_version_id = a.requisito_version_id AND r.documento_ordinal = d.documento_ordinal
                      LEFT JOIN public.legal_documento_versiones v ON v.id = d.documento_version_id
                      LEFT JOIN public.legal_documento_lineas line ON line.id = v.documento_linea_id
                      LEFT JOIN public.legal_publicaciones publication ON publication.id = v.publicacion_intro_id
                      LEFT JOIN public.legal_publicacion_documentos membership
                        ON membership.publicacion_id = v.publicacion_intro_id AND membership.documento_version_id = v.id
                     ORDER BY needed.id, d.documento_ordinal
                    """.formatted(values(batch.size()));
            List<Object> arguments = new ArrayList<>(batch);
            arguments.add(actor.userId());
            arguments.add(actor.tallerId());
            query(connection, sql, arguments, batch.size() * LegalManifestLimits.MAX_DOCUMENTS_PER_REQUIREMENT, row -> {
                UUID acceptance = uuid(row, "acceptance_id");
                require(batch.contains(acceptance) && acceptance.equals(uuid(row, "actor_acceptance"))
                        && bool(row, "canonical_snapshot") && bool(row, "source_context"));
                List<Document> documents = result.get(acceptance);
                require(integer(row, "documento_ordinal") == documents.size() + 1
                        && documents.size() < headers.get(acceptance).documentCount());
                SourceHeader header = sourceHeader(row, false, boundary);
                require(header.id().equals(uuid(row, "documento_version_id")));
                Document document = new Document(header.id(), TipoDocumentoLegal.valueOf(row.getString("tipo")),
                        row.getString("version"), row.getString("titulo"), row.getString("sha256"));
                require(documents.stream().noneMatch(item -> item.documentVersionId().equals(document.documentVersionId())));
                Instant effectiveAt = past(row, "vigente_desde", boundary);
                new LegalDocumentSummary(header.id(), document.type(), document.version(), document.title(), header.digest(),
                        effectiveAt, header.state(), LocaleLegal.ES_AR);
                DocumentSource source = new DocumentSource(header, document.type(), document.title(), effectiveAt);
                DocumentSource existing = sources.putIfAbsent(header.id(), source);
                require(existing == null || existing.equals(source));
                if (existing == null) budget.add(header.octets());
                documents.add(document);
                return acceptance;
            }, deadline);
        }
        result.forEach((id, documents) -> require(documents.size() == headers.get(id).documentCount()));
        result.replaceAll((id, documents) -> List.copyOf(documents));
        return result;
    }

    private static SourceHeader sourceHeader(ResultSet row, boolean requirement,
                                              LegalEditorialTimeBoundary boundary) throws SQLException {
        UUID id = uuid(row, "source_id");
        UUID line = uuid(row, "source_line");
        require(line.equals(uuid(row, "line_id")) && "es-AR".equals(row.getString("locale"))
                && "es-AR".equals(row.getString("publication_locale"))
                && "SELLADO".equals(row.getString("estado_construccion"))
                && row.getObject("publication_id") != null && row.getObject("membership_id") != null);
        String key = row.getString("source_key");
        require(key != null && key.length() <= 100 && key.matches("[a-z0-9][a-z0-9._-]*"));
        String version = row.getString("source_version");
        require(version != null && !version.isBlank() && version.codePointCount(0, version.length()) <= 40);
        int ordinal = integer(row, "lineage_ordinal");
        require(ordinal > 0);
        String digest = row.getString("source_digest");
        require(digest != null && digest.matches("[0-9a-f]{64}"));
        EstadoVersionLegal state = EstadoVersionLegal.valueOf(row.getString("source_state"));
        require(state == EstadoVersionLegal.VIGENTE || state == EstadoVersionLegal.REEMPLAZADA
                || state == EstadoVersionLegal.RETIRADA);
        Instant imported = past(row, "importado_en", boundary);
        Instant sealed = past(row, "sellado_en", boundary);
        Instant changed = past(row, "source_changed", boundary);
        require(!sealed.isBefore(imported) && !changed.isBefore(imported));
        long octets = number(row, "source_octets");
        require(octets >= 1 && octets <= (requirement ? MAX_STATEMENT_BYTES : LegalManifestLimits.MAX_MARKDOWN_BYTES));
        if (requirement) {
            int characters = integer(row, "source_characters");
            require(characters >= 1 && characters <= MAX_STATEMENT_CODE_POINTS);
        }
        return new SourceHeader(id, line, key, version, ordinal, state, digest, octets, imported, sealed, changed);
    }

    private Map<UUID, Instant> accreditTransitions(Connection connection, boolean requirement, List<SourceHeader> sources,
            LegalEditorialTimeBoundary boundary, LegalPrivateRequirementsDeadline deadline) throws SQLException {
        Map<UUID, Instant> activations = new LinkedHashMap<>();
        String table = requirement ? "legal_requisito_transiciones" : "legal_documento_transiciones";
        String column = requirement ? "requisito_version_id" : "documento_version_id";
        for (List<SourceHeader> batch : batches(sources)) {
            Map<UUID, List<Transition>> transitions = new LinkedHashMap<>();
            batch.forEach(source -> transitions.put(source.id(), new ArrayList<>()));
            String sql = """
                    SELECT needed.id AS version_id, t.id AS transition_id, t.estado_anterior, t.estado_nuevo, t.ocurrido_en
                      FROM (VALUES %s) needed(id)
                      LEFT JOIN public.%s t ON t.%s = needed.id
                     ORDER BY needed.id, CASE t.estado_nuevo WHEN 'PUBLICADA' THEN 1 WHEN 'VIGENTE' THEN 2 ELSE 3 END
                    """.formatted(values(batch.size()), table, column);
            query(connection, sql, batch.stream().map(SourceHeader::id).toList(), batch.size() * 3, row -> {
                UUID id = uuid(row, "version_id");
                List<Transition> list = transitions.get(id);
                require(list != null && list.size() < 3 && row.getObject("transition_id") != null);
                list.add(new Transition(EstadoVersionLegal.valueOf(row.getString("estado_anterior")),
                        EstadoVersionLegal.valueOf(row.getString("estado_nuevo")), past(row, "ocurrido_en", boundary)));
                return id;
            }, deadline);
            for (SourceHeader source : batch) activations.put(source.id(), accreditTransitions(source, transitions.get(source.id())));
        }
        return activations;
    }

    private static Instant accreditTransitions(SourceHeader source, List<Transition> transitions) {
        EstadoVersionLegal state = EstadoVersionLegal.BORRADOR;
        Instant previous = source.importedAt();
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
        require(state == source.state() && previous.equals(source.changedAt()) && activated != null);
        return activated;
    }

    private void validateDocumentSources(Connection connection, Map<UUID, DocumentSource> sources,
                                         LegalPrivateRequirementsDeadline deadline) throws SQLException {
        for (List<UUID> batch : batches(List.copyOf(sources.keySet()))) {
            String sql = """
                    SELECT needed.id, pg_catalog.octet_length(pg_catalog.convert_to(v.contenido_markdown, 'UTF8')) AS octets,
                           CASE WHEN pg_catalog.octet_length(pg_catalog.convert_to(v.contenido_markdown, 'UTF8')) BETWEEN 1 AND %d
                                THEN pg_catalog.convert_to(v.contenido_markdown, 'UTF8') ELSE NULL END AS text_utf8
                      FROM (VALUES %s) needed(id)
                      LEFT JOIN public.legal_documento_versiones v ON v.id = needed.id
                     ORDER BY needed.id
                    """.formatted(LegalManifestLimits.MAX_MARKDOWN_BYTES, values(batch.size()));
            Set<UUID> seen = new HashSet<>();
            query(connection, sql, batch, batch.size(), row -> {
                UUID id = uuid(row, "id");
                require(batch.contains(id) && seen.add(id));
                SourceHeader source = sources.get(id).header();
                require(number(row, "octets") == source.octets());
                validatedText(row, source, deadline);
                return id;
            }, deadline);
            require(seen.equals(new HashSet<>(batch)));
        }
    }

    private Map<UUID, String> readStatements(Connection connection, LegalActorSnapshot actor,
            Map<UUID, EvidenceHeader> headers, LegalPrivateRequirementsDeadline deadline) throws SQLException {
        Map<UUID, String> result = new LinkedHashMap<>();
        for (List<UUID> batch : batches(List.copyOf(headers.keySet()))) {
            String sql = """
                    SELECT needed.id, a.id AS actor_acceptance,
                           pg_catalog.octet_length(pg_catalog.convert_to(a.afirmacion, 'UTF8')) AS octets,
                           CASE WHEN pg_catalog.octet_length(pg_catalog.convert_to(a.afirmacion, 'UTF8')) BETWEEN 1 AND %d
                                THEN pg_catalog.convert_to(a.afirmacion, 'UTF8') ELSE NULL END AS text_utf8
                      FROM (VALUES %s) needed(id)
                      LEFT JOIN public.legal_aceptaciones a ON a.id = needed.id AND a.user_id = ? AND a.taller_id = ?
                     ORDER BY needed.id
                    """.formatted(MAX_STATEMENT_BYTES, values(batch.size()));
            List<Object> arguments = new ArrayList<>(batch);
            arguments.add(actor.userId());
            arguments.add(actor.tallerId());
            Set<UUID> seen = new HashSet<>();
            query(connection, sql, arguments, batch.size(), row -> {
                UUID id = uuid(row, "id");
                require(batch.contains(id) && seen.add(id) && id.equals(uuid(row, "actor_acceptance")));
                SourceHeader source = headers.get(id).source();
                require(number(row, "octets") == source.octets());
                // Header equality proved that the immutable snapshot is the exact canonical source bytes.
                result.put(id, validatedText(row, source, deadline));
                return id;
            }, deadline);
            require(seen.equals(new HashSet<>(batch)));
        }
        return result;
    }

    private String validatedText(ResultSet row, SourceHeader source, LegalPrivateRequirementsDeadline deadline) throws SQLException {
        byte[] bytes = row.getBytes("text_utf8");
        deadline.check();
        require(bytes != null && bytes.length == source.octets());
        var validation = textValidator.validate(bytes, source.digest(), "acceptance-history");
        deadline.check();
        require(validation.passed());
        return validation.value().orElseThrow().text();
    }

    /** The extra row is rejected before its mapper can hydrate any of its values. */
    private static <T> List<T> query(Connection connection, String sql, List<?> arguments, int maximum,
            RowMapper<T> mapper, LegalPrivateRequirementsDeadline deadline) throws SQLException {
        deadline.check();
        require(maximum >= 0 && maximum <= MAX_DOCUMENTS);
        try (PreparedStatement statement = connection.prepareStatement(sql + " LIMIT " + (maximum + 1),
                ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            try {
                statement.setFetchSize(BATCH_SIZE);
                for (int index = 0; index < arguments.size(); index++) statement.setObject(index + 1, arguments.get(index));
                deadline.check();
                try (ResultSet rows = statement.executeQuery()) {
                    List<T> result = new ArrayList<>();
                    while (true) {
                        deadline.check();
                        boolean more = rows.next();
                        deadline.check();
                        if (!more) return result;
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

    private void requireTransaction(boolean exportSnapshot) {
        require(TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isCurrentTransactionReadOnly() == exportSnapshot
                && Objects.equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),
                        (exportSnapshot ? Connection.TRANSACTION_REPEATABLE_READ : Connection.TRANSACTION_READ_COMMITTED))
                && TransactionSynchronizationManager.hasResource(dataSource));
    }

    private void requireBoundConnection(Connection connection, boolean exportSnapshot) throws SQLException {
        require(!connection.getAutoCommit() && connection.isReadOnly() == exportSnapshot
                && connection.getTransactionIsolation() == (exportSnapshot ? Connection.TRANSACTION_REPEATABLE_READ : Connection.TRANSACTION_READ_COMMITTED)
                && DataSourceUtils.isConnectionTransactional(DataSourceUtils.getTargetConnection(connection), dataSource));
    }

    private static List<Object> actorArguments(LegalActorSnapshot actor, ContextoLegal context) {
        List<Object> arguments = new ArrayList<>();
        arguments.add(actor.userId());
        arguments.add(actor.tallerId());
        if (context != null) arguments.add(context.name());
        return arguments;
    }

    private static String values(int count) {
        require(count > 0 && count <= BATCH_SIZE);
        return String.join(", ", Collections.nCopies(count, "(?::uuid)"));
    }

    private static <T> List<List<T>> batches(List<T> items) {
        List<List<T>> result = new ArrayList<>();
        for (int offset = 0; offset < items.size(); offset += BATCH_SIZE) {
            result.add(items.subList(offset, Math.min(items.size(), offset + BATCH_SIZE)));
        }
        return result;
    }

    private static Instant past(ResultSet row, String column, LegalEditorialTimeBoundary boundary) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        require(value != null && !value.toInstant().isAfter(boundary.observedAt()));
        return value.toInstant();
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
        if (!condition) throw new LegalAcceptanceHistoryReadException();
    }

    private static final class SourceBudget {
        private long bytes;

        void add(long added) {
            require(added >= 0 && added <= MAX_SOURCE_BYTES - bytes);
            bytes += added;
        }
    }

    @FunctionalInterface
    private interface RowMapper<T> { T map(ResultSet row) throws SQLException; }

    private record SourceHeader(UUID id, UUID line, String key, String version, int ordinal, EstadoVersionLegal state,
                                String digest, long octets, Instant importedAt, Instant sealedAt, Instant changedAt) { }
    private record EvidenceHeader(UUID id, UUID requirementId, ContextoLegal context, TipoActoLegal actType,
                                  SourceHeader source, int documentCount, Instant acceptedAt) { }
    private record DocumentSource(SourceHeader header, TipoDocumentoLegal type, String title, Instant effectiveAt) { }
    private record Transition(EstadoVersionLegal from, EstadoVersionLegal to, Instant at) { }
}
