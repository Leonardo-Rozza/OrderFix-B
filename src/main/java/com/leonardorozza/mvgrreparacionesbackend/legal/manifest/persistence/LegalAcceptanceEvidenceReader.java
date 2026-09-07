package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.CanonicalTextValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateProjection.ScopeRevision;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateProvenance;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateProvenance.ScopeOrigin;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateRevisionCalculator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateProvenanceCalculator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceSelection.ExistingAcceptance;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalIdempotencyCoordinator.Reservation;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EsquemaRevisionLegal;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalActorSnapshot;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalDocumentSummary;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestLimits;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Bounded, SELECT-only accreditation of requested historical evidence, independent of current pointers or request content. */
final class LegalAcceptanceEvidenceReader {
    private static final int BATCH_SIZE = 32;
    private static final int MAX_STATEMENT_BYTES = 4_000;
    private static final int MAX_STATEMENT_CODE_POINTS = 1_000;
    private static final int MAX_ACTS = 2_048;
    private static final int MAX_DOCUMENTS = MAX_ACTS * LegalManifestLimits.MAX_DOCUMENTS_PER_REQUIREMENT;
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

    LegalAcceptanceEvidenceReader(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.dataSource = Objects.requireNonNull(jdbc.getDataSource(), "jdbc.dataSource");
    }

    boolean usesJdbc(JdbcTemplate candidate) {
        return jdbc == candidate;
    }

    /** Caller has reserved this MISS and holds the editorial gate and exclusive actor lock. */
    List<ExistingAcceptance> read(Reservation reservation, LegalActorSnapshot actor) {
        try {
            require(reservation != null && usesJdbc(reservation.jdbc()));
            reservation.requireNew();
            reservation.requireWriteActor(actor);
            require(reservation.command().operation() == LegalAcceptanceCommand.Operation.AUTHENTICATED_ACCEPTANCE);
            requireTransaction();
            Set<UUID> requested = new LinkedHashSet<>();
            for (var item : reservation.command().acceptances()) requested.add(item.requisitoVersionId());
            require(requested.size() <= MAX_ACTS);
            if (requested.isEmpty()) return List.of();
            return Objects.requireNonNull(jdbc.execute((ConnectionCallback<List<ExistingAcceptance>>) connection -> {
                requireBoundConnection(connection);
                LegalEditorialTimeBoundary boundary = query(connection,
                        "SELECT transaction_timestamp() AS transaction_at, statement_timestamp() AS observed_at",
                        List.of(), 1, row -> new LegalEditorialTimeBoundary(timestamp(row, "transaction_at"),
                                timestamp(row, "observed_at")), reservation).getFirst();
                SourceBudget budget = new SourceBudget();
                Map<UUID, ExpectedAggregate> aggregates = new LinkedHashMap<>();
                Map<UUID, EvidenceHeader> headers = readHeaders(connection, actor, List.copyOf(requested),
                        aggregates, boundary, budget, reservation);
                if (headers.isEmpty()) return List.of();
                Map<UUID, AggregateData> accreditedAggregates = accreditAggregates(connection, reservation, aggregates, boundary);
                Map<UUID, SourceHeader> requirementSources = new LinkedHashMap<>();
                for (EvidenceHeader header : headers.values()) {
                    require(requirementSources.putIfAbsent(header.requirementId(), header.source()) == null);
                }
                Map<UUID, DocumentSource> documentSources = new LinkedHashMap<>();
                Map<UUID, List<Document>> documents = readDocuments(connection, actor, headers,
                        documentSources, boundary, budget, reservation);
                require(documentSources.size() <= MAX_DOCUMENTS);
                Map<UUID, Instant> requirementActivations = accreditTransitions(connection, true,
                        List.copyOf(requirementSources.values()), boundary, reservation);
                Map<UUID, Instant> activations = accreditTransitions(connection, false,
                        documentSources.values().stream().map(DocumentSource::header).toList(), boundary, reservation);
                for (DocumentSource source : documentSources.values()) {
                    require(!activations.get(source.header().id()).isBefore(source.effectiveAt()));
                }
                for (EvidenceHeader header : headers.values()) {
                    accreditEvidenceDate(header, header.source(), requirementActivations.get(header.requirementId()), null);
                    if (header.aggregateId() != null) {
                        require(!header.acceptedAt().isBefore(accreditedAggregates.get(header.aggregateId()).createdAt()));
                    }
                    for (Document document : documents.get(header.id())) {
                        DocumentSource source = documentSources.get(document.documentVersionId());
                        accreditEvidenceDate(header, source.header(), activations.get(source.header().id()), source.effectiveAt());
                    }
                }
                // Accredit every bounded header before fetching the distinct historical source TEXT.
                validateDocumentSources(connection, documentSources, reservation);
                validateStatements(connection, actor, headers, reservation);
                List<ExistingAcceptance> result = new ArrayList<>(headers.size());
                for (EvidenceHeader header : headers.values()) {
                    reservation.check();
                    result.add(new ExistingAcceptance(header.id(), actor.userId(), actor.tallerId(),
                            header.requirementId(), header.actType(), header.source().digest(),
                            documents.get(header.id()).stream().map(document -> new LegalAcceptanceCommand.Document(
                                    document.documentVersionId(), document.sha256())).toList()));
                }
                reservation.check();
                return List.copyOf(result);
            }));
        } catch (RuntimeException failure) {
            if (reservation != null) throw reservation.fail(failure);
            throw new LegalIdempotencyException(LegalIdempotencyException.Reason.UNAVAILABLE);
        }
    }

    private Map<UUID, EvidenceHeader> readHeaders(Connection connection, LegalActorSnapshot actor,
            List<UUID> requested, Map<UUID, ExpectedAggregate> aggregates, LegalEditorialTimeBoundary boundary,
            SourceBudget budget, Reservation deadline) throws SQLException {
        Map<UUID, EvidenceHeader> result = new LinkedHashMap<>();
        for (List<UUID> batch : batches(requested)) {
            String sql = """
                SELECT needed.id AS requested_id, a.id, a.lote_id, a.user_id, a.taller_id, a.requisito_version_id,
                       a.contexto, a.tipo_acto, a.afirmacion_sha256,
                       pg_catalog.octet_length(a.afirmacion) AS snapshot_octets,
                       pg_catalog.char_length(a.afirmacion) AS snapshot_characters,
                       b.id AS batch_id, b.user_id AS batch_user, b.taller_id AS batch_workshop,
                       b.rol_wire, b.audiencia, b.aceptado_en, b.required_set_revision, b.revision_scheme, b.perfil, b.agregado_id,
                       v.id AS source_id, v.requisito_linea_id AS source_line, v.lineage_ordinal,
                       v.version AS source_version, v.estado AS source_state, v.estado_cambiado_en AS source_changed,
                       v.afirmacion_sha256 AS source_digest,
                       pg_catalog.octet_length(v.afirmacion) AS source_octets,
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
                         AND CASE WHEN pg_catalog.octet_length(a.afirmacion) BETWEEN 1 AND 4000
                                   AND pg_catalog.char_length(a.afirmacion) BETWEEN 1 AND 1000
                                   AND pg_catalog.octet_length(v.afirmacion) BETWEEN 1 AND 4000
                                   AND pg_catalog.char_length(v.afirmacion) BETWEEN 1 AND 1000
                              THEN pg_catalog.convert_to(a.afirmacion, 'UTF8') = pg_catalog.convert_to(v.afirmacion, 'UTF8')
                              ELSE false END
                         AND a.afirmacion_sha256 = v.afirmacion_sha256
                         AND a.requerido = v.requerido) AS canonical_snapshot,
                       %s AS source_membership, %s AS own_acceptance, %s AS own_lot
                  FROM (VALUES %s) needed(id)
                  CROSS JOIN (SELECT ?::timestamptz AS observed_at) observation
                  LEFT JOIN public.legal_aceptaciones a ON a.requisito_version_id = needed.id AND a.user_id = ? AND a.taller_id = ?
                  LEFT JOIN public.legal_aceptacion_lotes b ON b.id = a.lote_id
                  LEFT JOIN public.legal_requisito_versiones v ON v.id = a.requisito_version_id
                  LEFT JOIN public.legal_requisito_lineas line ON line.id = v.requisito_linea_id
                  LEFT JOIN public.legal_publicaciones publication ON publication.id = v.publicacion_intro_id
                  LEFT JOIN public.legal_publicacion_requisitos membership
                    ON membership.publicacion_id = v.publicacion_intro_id AND membership.requisito_version_id = v.id
                 ORDER BY needed.id
                """.formatted(MEMBERSHIP_SQL, ownRow("a"), ownRow("b"), values(batch.size()));
            List<Object> arguments = new ArrayList<>(batch);
            arguments.add(boundary.observedAt().atOffset(ZoneOffset.UTC));
            arguments.add(actor.userId()); arguments.add(actor.tallerId());
            Set<UUID> seen = new HashSet<>();
            query(connection, sql, arguments, batch.size(), row -> {
                UUID requestedId = uuid(row, "requested_id");
                require(batch.contains(requestedId) && seen.add(requestedId));
                // Raw actor-scoped absence is normal; missing joined sources on an existing act are not.
                if (row.getObject("id") == null) return requestedId;
                UUID id = uuid(row, "id");
                require(!bool(row, "own_acceptance") && !bool(row, "own_lot")
                        && number(row, "user_id") == actor.userId() && number(row, "taller_id") == actor.tallerId()
                        && number(row, "batch_user") == actor.userId() && number(row, "batch_workshop") == actor.tallerId()
                        && uuid(row, "lote_id").equals(uuid(row, "batch_id"))
                        && bool(row, "canonical_snapshot") && bool(row, "source_membership") && bool(row, "applicable_audience"));
                UserRole originalRole = UserRole.valueOf(row.getString("rol_wire"));
                require(originalRole.toAudienciaLegal().name().equals(row.getString("audiencia")));
                String revision = revision(row.getString("required_set_revision"));
                if ("AGGREGATE_V1".equals(row.getString("revision_scheme"))) {
                    PerfilAgregadoLegal profile = PerfilAgregadoLegal.valueOf(row.getString("perfil"));
                    ExpectedAggregate aggregate = new ExpectedAggregate(uuid(row, "agregado_id"), profile,
                            originalRole.toAudienciaLegal(), revision);
                    ExpectedAggregate previous = aggregates.putIfAbsent(aggregate.id(), aggregate);
                    require(previous == null || previous.equals(aggregate));
                } else require("SCOPE_V1".equals(row.getString("revision_scheme"))
                        && row.getObject("perfil") == null && row.getObject("agregado_id") == null);
                SourceHeader source = sourceHeader(row, true, boundary);
                require(requestedId.equals(source.id()) && uuid(row, "requisito_version_id").equals(source.id())
                        && source.digest().equals(row.getString("afirmacion_sha256"))
                        && number(row, "snapshot_octets") == source.octets()
                        && number(row, "snapshot_characters") == number(row, "source_characters"));
                budget.add(source.octets());
                int documentCount = integer(row, "document_count");
                require(documentCount > 0 && documentCount <= LegalManifestLimits.MAX_DOCUMENTS_PER_REQUIREMENT);
                EvidenceHeader header = new EvidenceHeader(id, source.id(), ContextoLegal.valueOf(row.getString("contexto")),
                        TipoActoLegal.valueOf(row.getString("tipo_acto")), source,
                        documentCount, past(row, "aceptado_en", boundary), row.getObject("agregado_id", UUID.class));
                require(result.putIfAbsent(id, header) == null);
                return requestedId;
            }, deadline);
            require(seen.equals(new HashSet<>(batch)));
        }
        return result;
    }

    private Map<UUID, List<Document>> readDocuments(Connection connection, LegalActorSnapshot actor,
            Map<UUID, EvidenceHeader> headers, Map<UUID, DocumentSource> sources, LegalEditorialTimeBoundary boundary,
            SourceBudget budget, Reservation deadline) throws SQLException {
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
            LegalEditorialTimeBoundary boundary, Reservation deadline) throws SQLException {
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

    private static void accreditEvidenceDate(EvidenceHeader evidence, SourceHeader source,
                                             Instant activatedAt, Instant effectiveAt) {
        // V27 stamped transaction_timestamp: a valid legacy transaction may start before publication.
        // V28 uses statement_timestamp after the shared gate, so its evidence has strict lower bounds.
        if (evidence.aggregateId() != null) {
            require(!evidence.acceptedAt().isBefore(source.sealedAt())
                    && !evidence.acceptedAt().isBefore(activatedAt)
                    && (effectiveAt == null || !evidence.acceptedAt().isBefore(effectiveAt)));
        }
        // Editorial transitions retain transaction_timestamp. A replacing transaction can start
        // before an accepting transaction commits, so changedAt is not an upper causal bound.
    }

    private void validateDocumentSources(Connection connection, Map<UUID, DocumentSource> sources,
                                         Reservation deadline) throws SQLException {
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

    private void validateStatements(Connection connection, LegalActorSnapshot actor,
            Map<UUID, EvidenceHeader> headers, Reservation deadline) throws SQLException {
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
                validatedText(row, source, deadline);
                return id;
            }, deadline);
            require(seen.equals(new HashSet<>(batch)));
        }
    }

    private String validatedText(ResultSet row, SourceHeader source, Reservation deadline) throws SQLException {
        byte[] bytes = row.getBytes("text_utf8");
        deadline.check();
        require(bytes != null && bytes.length == source.octets());
        var validation = textValidator.validate(bytes, source.digest(), "acceptance-evidence");
        deadline.check();
        require(validation.passed());
        return validation.value().orElseThrow().text();
    }

    /** The extra row is rejected before its mapper can hydrate any of its values. */
    private static <T> List<T> query(Connection connection, String sql, List<?> arguments, int maximum,
            RowMapper<T> mapper, Reservation deadline) throws SQLException {
        deadline.readBudget();
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
                try { statement.cancel(); } catch (SQLException | RuntimeException ignored) { }
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
        if (!condition) throw new LegalIdempotencyException(LegalIdempotencyException.Reason.UNAVAILABLE);
    }

    private Map<UUID, AggregateData> accreditAggregates(Connection connection, Reservation deadline,
            Map<UUID, ExpectedAggregate> expected, LegalEditorialTimeBoundary boundary) throws SQLException {
        Instant observedAt = boundary.observedAt();
        Map<UUID, AggregateData> result = new LinkedHashMap<>();
        for (List<UUID> batch : batches(List.copyOf(expected.keySet()))) {
            Map<UUID, List<ScopeRevision>> revisions = new LinkedHashMap<>();
            Map<UUID, List<ScopeOrigin>> origins = new LinkedHashMap<>();
            Map<UUID, Integer> counts = new LinkedHashMap<>();
            Map<UUID, String> fingerprints = new LinkedHashMap<>();
            Map<UUID, Instant> dates = new LinkedHashMap<>();
            query(connection, """
                    SELECT needed.id AS requested_id,g.id,g.perfil,g.locale,g.audiencia,g.revision_scheme,
                           g.required_set_revision,g.provenance_fingerprint,g.scope_count,g.creado_en,
                           s.scope_ordinal,s.contexto,s.locale AS scope_locale,s.audiencia AS scope_audience,
                           s.conjunto_id,s.publicacion_id,s.required_set_revision AS scope_revision,
                           c.id AS set_id,c.locale AS set_locale,c.contexto AS set_context,c.audiencia AS set_audience,
                           c.publicacion_id AS set_publication,c.required_set_revision AS set_revision,c.creado_en AS set_created
                      FROM (VALUES %s) needed(id)
                      LEFT JOIN public.legal_requisito_agregados g ON g.id=needed.id
                      LEFT JOIN public.legal_requisito_agregado_scopes s ON s.agregado_id=g.id
                      LEFT JOIN public.legal_requisito_conjuntos c ON c.id=s.conjunto_id
                     ORDER BY needed.id,s.scope_ordinal
                    """.formatted(values(batch.size())), batch, batch.size() * 8, row -> {
                UUID id = uuid(row, "requested_id"); ExpectedAggregate item = expected.get(id);
                require(item != null && id.equals(uuid(row, "id")) && item.profile().name().equals(row.getString("perfil"))
                        && item.audience().name().equals(row.getString("audiencia")) && "es-AR".equals(row.getString("locale"))
                        && "AGGREGATE_V1".equals(row.getString("revision_scheme"))
                        && item.revision().equals(row.getString("required_set_revision")));
                int scopeCount = integer(row, "scope_count"); require(scopeCount > 0 && scopeCount <= 8);
                Integer priorCount = counts.putIfAbsent(id, scopeCount); require(priorCount == null || priorCount == scopeCount);
                Instant created = timestamp(row, "creado_en"); require(!created.isAfter(observedAt));
                Instant priorDate = dates.putIfAbsent(id, created); require(priorDate == null || priorDate.equals(created));
                String fingerprint = revision(row.getString("provenance_fingerprint"));
                String priorFingerprint = fingerprints.putIfAbsent(id, fingerprint); require(priorFingerprint == null || priorFingerprint.equals(fingerprint));
                List<ScopeRevision> scopes = revisions.computeIfAbsent(id, ignored -> new ArrayList<>());
                List<ScopeOrigin> physical = origins.computeIfAbsent(id, ignored -> new ArrayList<>());
                ContextoLegal context = ContextoLegal.valueOf(row.getString("contexto"));
                require(scopes.size() < scopeCount && integer(row, "scope_ordinal") == scopes.size() + 1
                        && (scopes.isEmpty() || scopes.getLast().context().ordinal() < context.ordinal())
                        && "es-AR".equals(row.getString("scope_locale")) && "es-AR".equals(row.getString("set_locale"))
                        && item.audience().name().equals(row.getString("scope_audience"))
                        && item.audience().name().equals(row.getString("set_audience"))
                        && context.name().equals(row.getString("set_context"))
                        && uuid(row, "conjunto_id").equals(uuid(row, "set_id"))
                        && uuid(row, "publicacion_id").equals(uuid(row, "set_publication"))
                        && Objects.equals(row.getString("scope_revision"), row.getString("set_revision"))
                        && !timestamp(row, "set_created").isAfter(observedAt));
                scopes.add(new ScopeRevision(context, row.getString("scope_revision")));
                physical.add(new ScopeOrigin(context, uuid(row, "conjunto_id"), uuid(row, "publicacion_id")));
                return id;
            }, deadline);
            for (UUID id : batch) {
                ExpectedAggregate item = expected.get(id); List<ScopeRevision> scopes = revisions.get(id);
                require(scopes != null && scopes.size() == counts.get(id));
                if (item.profile() == PerfilAgregadoLegal.REGISTRATION) require(item.audience() == AudienciaLegal.ADMIN_TITULAR
                        && scopes.size() == 1 && scopes.getFirst().context() == ContextoLegal.REGISTRO);
                else require(scopes.stream().anyMatch(scope -> scope.context() == ContextoLegal.USO_CONTINUADO));
                var semantic = new LegalRequiredSetAggregateProjection(EsquemaRevisionLegal.AGGREGATE_V1, LocaleLegal.ES_AR, item.audience(), scopes);
                var provenance = new LegalRequiredSetAggregateProvenance(item.profile(), LocaleLegal.ES_AR, item.audience(), origins.get(id));
                require(new LegalRequiredSetAggregateRevisionCalculator().calculate(semantic).equals(item.revision())
                        && new LegalRequiredSetAggregateProvenanceCalculator().calculate(provenance).equals(fingerprints.get(id)));
                result.put(id, new AggregateData(provenance, fingerprints.get(id), dates.get(id)));
            }
        }
        return result;
    }

    private static Instant timestamp(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        require(value != null);
        return value.toInstant();
    }

    private static String revision(String value) {
        require(value != null && value.matches("sha256:[0-9a-f]{64}"));
        return value;
    }

    private static final String FORWARD_XID_DISTANCE_SQL =
            "mod(row_xid - mod(top_xid, 4294967296) + 4294967296, 4294967296)";

    /**
     * MVCC visibility also includes this transaction's released SAVEPOINT children. Their locks
     * have already disappeared, but pg_xact_status still reports them as in progress. Reconstruct
     * a child's full xid using the forward unsigned distance from the assigned top-level xid;
     * a distance of at least 2^31 is older and cannot be our child. Do not use age(): its anchor
     * can have been cached before our top-level xid was assigned. Ancient frozen xmin values
     * that collide with the forward interval are deliberately unavailable if status is ambiguous.
     */
    private static String ownRow(String alias) {
        return """
                (SELECT CASE
                   WHEN row_xid IS NULL OR row_xid=0 THEN true
                   WHEN row_xid IN (1,2) OR distance >= 2147483648 THEN false
                   ELSE pg_catalog.pg_xact_status((top_xid + distance)::text::xid8)
                        IS DISTINCT FROM 'committed'
                 END
                 FROM (SELECT pg_catalog.pg_current_xact_id()::text::numeric AS top_xid,
                              %s.xmin::text::numeric AS row_xid) origin
                 CROSS JOIN LATERAL (SELECT %s AS distance) relative_xid)
                """.formatted(alias, FORWARD_XID_DISTANCE_SQL);
    }

    private record ExpectedAggregate(UUID id, PerfilAgregadoLegal profile, AudienciaLegal audience, String revision) { }
    private record AggregateData(LegalRequiredSetAggregateProvenance provenance, String provenanceFingerprint, Instant createdAt) { }

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
                                  SourceHeader source, int documentCount, Instant acceptedAt, UUID aggregateId) { }
    private record DocumentSource(SourceHeader header, TipoDocumentoLegal type, String title, Instant effectiveAt) { }
    private record Transition(EstadoVersionLegal from, EstadoVersionLegal to, Instant at) { }
}
