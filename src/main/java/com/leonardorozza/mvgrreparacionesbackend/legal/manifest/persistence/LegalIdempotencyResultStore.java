package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.*;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand.Acceptance;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateProjection.ScopeRevision;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateProvenance.ScopeOrigin;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalIdempotencyCoordinator.Reservation;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.*;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;

/** Technical results only; callers own evidence, authorization, the transaction and final commit. */
final class LegalIdempotencyResultStore {
    private static final int BATCH = 32;
    private static final int MAX_ACTS = 2_048;
    private static final int MAX_DOCUMENTS = 16;
    private static final Duration MIN_TTL = Duration.ofHours(24);
    static final String FORWARD_XID_DISTANCE_SQL =
            "mod(row_xid - mod(top_xid, 4294967296) + 4294967296, 4294967296)";
    private final JdbcTemplate jdbc;

    LegalIdempotencyResultStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        Objects.requireNonNull(jdbc.getDataSource(), "jdbc.dataSource");
    }

    boolean usesJdbc(JdbcTemplate candidate) { return jdbc == candidate; }

    Optional<StoredResult> lookup(Reservation reservation) {
        try {
            verify(reservation);
            Header found = null;
            for (LegalIdempotencyFingerprint candidate : reservation.candidates()) {
                List<Object> args = new ArrayList<>();
                for (int index = 0; index < 2; index++) addTuple(args, candidate);
                List<Header> rows = select(reservation, """
                        SELECT 'WITH_ACTS' AS source, p.id AS ledger_id, NULL::uuid AS supplemental_id,
                               p.operacion, p.route_template, p.scope_hmac, p.idempotency_key_hmac,
                               p.fingerprint_hmac, p.hmac_key_version, p.user_id, p.taller_id, p.lote_id,
                               'WITH_ACTS' AS resultado, p.completed_at, p.expires_at,
                               NULL::varchar AS rol_wire, NULL::varchar AS audiencia, NULL::varchar AS submitted_revision,
                               NULL::uuid AS agregado_observado_id, NULL::varchar AS perfil,
                               NULL::varchar AS revision_scheme, NULL::varchar AS observed_revision,
                               NULL::integer AS referencia_count,
                               %s AS own_row
                          FROM public.legal_idempotencia_resultados p
                         WHERE p.operacion=? AND p.route_template=? AND p.scope_hmac=? AND p.idempotency_key_hmac=?
                        UNION ALL
                        SELECT 'WITHOUT_ACTS', NULL::bigint, p.id, p.operacion, p.route_template, p.scope_hmac,
                               p.idempotency_key_hmac, p.fingerprint_hmac, p.hmac_key_version,
                               p.user_id, p.taller_id, NULL::uuid, p.resultado, p.completed_at, p.expires_at,
                               p.rol_wire, p.audiencia, p.submitted_revision, p.agregado_observado_id, p.perfil,
                               p.revision_scheme, p.observed_revision, p.referencia_count,
                               %s
                          FROM public.legal_idempotencia_sin_actos p
                         WHERE p.operacion=? AND p.route_template=? AND p.scope_hmac=? AND p.idempotency_key_hmac=?
                        """.formatted(ownRow("p"), ownRow("p")), args, 2, row -> header(row, candidate));
                for (Header result : rows) {
                    require(found == null);
                    found = result;
                }
            }
            if (found == null) return Optional.empty();
            reservation.lockReplayActor(found.userId(), found.tallerId());
            if (!found.candidate().matchesFingerprint(found.fingerprint())) {
                throw new LegalIdempotencyException(LegalIdempotencyException.Reason.KEY_REUSED);
            }
            Instant observedAt = observation(reservation);
            require(!found.completedAt().isAfter(observedAt));
            Map<UUID, ExpectedAggregate> aggregates = new LinkedHashMap<>();
            List<UUID> actIds;
            if (found.source() == Source.WITH_ACTS) {
                Lot lot = readLot(reservation, found.lotId(), found.userId(), found.tallerId(), observedAt);
                require(!lot.ownRow() && found.completedAt().equals(lot.acceptedAt())
                        && lot.revision().equals(reservation.command().requiredSetRevision()));
                checkLotOperation(lot, reservation.command());
                addAggregate(aggregates, lot.aggregate());
                Map<UUID, Act> acts = accreditCommand(reservation, found.userId(), found.tallerId(), null, false,
                        observedAt, aggregates);
                require(!acts.isEmpty());
                checkLotMembers(reservation, found.lotId(), found.userId(), found.tallerId(), acts);
                actIds = ids(acts);
            } else {
                require(found.candidate().operation() == TipoOperacionIdempotenteLegal.ACEPTACION_LEGAL
                        || found.candidate().operation() == TipoOperacionIdempotenteLegal.ATESTACION_FOTOS);
                validateWithoutShape(found, reservation.command());
                addAggregate(aggregates, found.aggregate());
                List<UUID> references = readReferences(reservation, found);
                Map<UUID, Act> acts = accreditCommand(reservation, found.userId(), found.tallerId(), null, false,
                        observedAt, aggregates);
                actIds = ids(acts);
                require(references.equals(actIds));
            }
            accreditAggregates(reservation, aggregates, observedAt);
            reservation.check();
            return Optional.of(new StoredResult(found.source(), found.ledgerId(), found.supplementalId(),
                    found.userId(), found.tallerId(), found.lotId(), found.result(), found.completedAt(), found.expiresAt(), actIds));
        } catch (RuntimeException failure) {
            throw reservation.fail(failure);
        }
    }

    void persistWithActs(Reservation reservation, LegalActorSnapshot actor, UUID lotId) {
        try {
            verify(reservation); reservation.requireNew(); reservation.requireWriteActor(actor);
            Instant observedAt = observation(reservation);
            Lot lot = readLot(reservation, lotId, actor.userId(), actor.tallerId(), observedAt);
            require(lot.topXid() && "AGGREGATE_V1".equals(lot.scheme())
                    && lot.revision().equals(reservation.command().requiredSetRevision())
                    && lot.role() == actor.role());
            checkLotOperation(lot, reservation.command());
            Map<UUID, ExpectedAggregate> aggregates = new LinkedHashMap<>();
            addAggregate(aggregates, lot.aggregate());
            Map<UUID, Act> acts = accreditCommand(reservation, actor.userId(), actor.tallerId(), lotId, true,
                    observedAt, aggregates);
            checkLotMembers(reservation, lotId, actor.userId(), actor.tallerId(), acts);
            accreditAggregates(reservation, aggregates, observedAt);
            Duration ttl = roundedTtl(reservation.keyring().resultTtl());
            Instant expectedExpiry = lot.acceptedAt().plus(ttl);
            List<Object> args = fingerprintArguments(reservation.activeFingerprint());
            args.add(actor.userId()); args.add(actor.tallerId());
            args.add(ttl.getSeconds()); args.add(ttl.getNano() / 1_000);
            args.add(lotId); args.add(actor.userId()); args.add(actor.tallerId());
            List<Completion> inserted = returning(reservation, """
                    INSERT INTO public.legal_idempotencia_resultados
                        (operacion,route_template,scope_hmac,idempotency_key_hmac,fingerprint_hmac,hmac_key_version,
                         user_id,taller_id,lote_id,completed_at,expires_at)
                    SELECT ?,?,?,?,?,?,?,?,l.id,l.aceptado_en,
                           l.aceptado_en + ?::bigint * INTERVAL '1 second' + ?::integer * INTERVAL '1 microsecond'
                      FROM public.legal_aceptacion_lotes l WHERE l.id=? AND l.user_id=? AND l.taller_id=?
                    RETURNING id,completed_at,expires_at
                    """, args, 1, row -> {
                require(number(row, "id") > 0);
                return new Completion(timestamp(row, "completed_at"), timestamp(row, "expires_at"));
            });
            require(inserted.size() == 1 && inserted.getFirst().completedAt().equals(lot.acceptedAt())
                    && inserted.getFirst().expiresAt().equals(expectedExpiry));
            reservation.check(); reservation.markWritten();
        } catch (RuntimeException failure) {
            throw reservation.fail(failure);
        }
    }

    void persistWithoutActs(Reservation reservation, LegalActorSnapshot actor,
            LegalRequiredSetAggregateReceipt aggregate, List<UUID> committedAcceptanceIds) {
        try {
            verify(reservation); reservation.requireNew(); reservation.requireWriteActor(actor);
            require(reservation.command().operation() == LegalAcceptanceCommand.Operation.AUTHENTICATED_ACCEPTANCE);
            Objects.requireNonNull(aggregate, "aggregate");
            List<UUID> expectedReferences = immutableIds(committedAcceptanceIds);
            boolean empty = reservation.command().acceptances().isEmpty();
            require(empty == expectedReferences.isEmpty());
            require(aggregate.provenance().profile() == PerfilAgregadoLegal.AUTHENTICATED_PENDING
                    && aggregate.provenance().locale() == LocaleLegal.ES_AR
                    && aggregate.provenance().audience() == actor.audience());
            if (empty) require(reservation.command().requiredSetRevision().equals(aggregate.requiredSetRevision()));
            Instant observedAt = observation(reservation);
            require(!aggregate.createdAt().isAfter(observedAt));
            Map<UUID, ExpectedAggregate> aggregates = new LinkedHashMap<>();
            addAggregate(aggregates, new ExpectedAggregate(aggregate.aggregateId(), aggregate.provenance().profile(),
                    actor.audience(), aggregate.requiredSetRevision()));
            Map<UUID, Act> acts = accreditCommand(reservation, actor.userId(), actor.tallerId(), null, false,
                    observedAt, aggregates);
            require(ids(acts).equals(expectedReferences));
            Map<UUID, AggregateData> accredited = accreditAggregates(reservation, aggregates, observedAt);
            AggregateData actual = accredited.get(aggregate.aggregateId());
            require(actual.provenance().equals(aggregate.provenance())
                    && actual.provenanceFingerprint().equals(aggregate.provenanceFingerprint())
                    && actual.createdAt().equals(aggregate.createdAt()));
            Duration ttl = roundedTtl(reservation.keyring().resultTtl());
            observedAt.plus(ttl); // Reject an unrepresentable expiration before issuing INSERT.
            UUID resultId = UUID.randomUUID();
            List<Object> args = new ArrayList<>(); args.add(resultId);
            args.addAll(fingerprintArguments(reservation.activeFingerprint()));
            args.add(actor.userId()); args.add(actor.tallerId()); args.add(actor.role().name()); args.add(actor.audience().name());
            args.add(empty ? "EMPTY" : "DEDUP"); args.add(reservation.command().requiredSetRevision());
            args.add(aggregate.aggregateId()); args.add(aggregate.requiredSetRevision()); args.add(expectedReferences.size());
            args.add(ttl.getSeconds()); args.add(ttl.getNano() / 1_000);
            List<Completion> inserted = returning(reservation, """
                    INSERT INTO public.legal_idempotencia_sin_actos
                        (id,operacion,route_template,scope_hmac,idempotency_key_hmac,fingerprint_hmac,hmac_key_version,
                         user_id,taller_id,rol_wire,audiencia,resultado,submitted_revision,agregado_observado_id,
                         perfil,revision_scheme,observed_revision,referencia_count,completed_at,expires_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,'AUTHENTICATED_PENDING','AGGREGATE_V1',?,?,statement_timestamp(),
                            statement_timestamp() + ?::bigint * INTERVAL '1 second' + ?::integer * INTERVAL '1 microsecond')
                    RETURNING id,completed_at,expires_at
                    """, args, 1, row -> {
                require(resultId.equals(uuid(row, "id")));
                return new Completion(timestamp(row, "completed_at"), timestamp(row, "expires_at"));
            });
            require(inserted.size() == 1 && inserted.getFirst().expiresAt().equals(inserted.getFirst().completedAt().plus(ttl)));
            for (List<UUID> batch : batches(expectedReferences)) {
                List<Object> references = new ArrayList<>();
                for (UUID acceptance : batch) {
                    references.add(resultId); references.add(acceptance); references.add(actor.userId()); references.add(actor.tallerId());
                }
                List<UUID> insertedReferences = returning(reservation,
                        "INSERT INTO public.legal_idempotencia_sin_actos_referencias"
                                + " (resultado_id,aceptacion_id,user_id,taller_id) VALUES "
                                + String.join(",", Collections.nCopies(batch.size(), "(?,?,?,?)")) + " RETURNING aceptacion_id",
                        references, batch.size(), row -> uuid(row, "aceptacion_id"));
                require(immutableIds(insertedReferences).equals(batch));
            }
            reservation.check(); reservation.markWritten();
        } catch (RuntimeException failure) {
            throw reservation.fail(failure);
        }
    }

    private void verify(Reservation reservation) {
        Objects.requireNonNull(reservation, "reservation");
        require(usesJdbc(reservation.jdbc())); reservation.verify();
    }

    private Header header(ResultSet row, LegalIdempotencyFingerprint candidate) throws SQLException {
        require(!bool(row, "own_row"));
        LegalIdempotencyFingerprint stored = new LegalIdempotencyFingerprint(integer(row, "hmac_key_version"),
                TipoOperacionIdempotenteLegal.valueOf(row.getString("operacion")), row.getString("route_template"),
                row.getString("scope_hmac"), row.getString("idempotency_key_hmac"), row.getString("fingerprint_hmac"));
        require(stored.keyVersion() == candidate.keyVersion() && stored.operation() == candidate.operation()
                && stored.routeTemplate().equals(candidate.routeTemplate()) && stored.scopeHmac().equals(candidate.scopeHmac())
                && stored.idempotencyKeyHmac().equals(candidate.idempotencyKeyHmac()));
        long user = number(row, "user_id"), taller = number(row, "taller_id");
        require(user > 0 && taller > 0);
        Source source = Source.valueOf(row.getString("source"));
        Long ledgerId = source == Source.WITH_ACTS ? number(row, "ledger_id") : null;
        UUID supplementalId = source == Source.WITHOUT_ACTS ? uuid(row, "supplemental_id") : null;
        UUID lotId = source == Source.WITH_ACTS ? uuid(row, "lote_id") : null;
        if (ledgerId != null) require(ledgerId > 0);
        Instant completed = timestamp(row, "completed_at"), expires = timestamp(row, "expires_at");
        require(!expires.isBefore(completed.plus(MIN_TTL)));
        UserRole role = source == Source.WITHOUT_ACTS ? UserRole.valueOf(row.getString("rol_wire")) : null;
        ExpectedAggregate aggregate = null;
        if (source == Source.WITHOUT_ACTS) {
            require(role.toAudienciaLegal().name().equals(row.getString("audiencia"))
                    && "AGGREGATE_V1".equals(row.getString("revision_scheme"))
                    && "AUTHENTICATED_PENDING".equals(row.getString("perfil")));
            aggregate = new ExpectedAggregate(uuid(row, "agregado_observado_id"), PerfilAgregadoLegal.AUTHENTICATED_PENDING,
                    role.toAudienciaLegal(), revision(row.getString("observed_revision")));
        }
        return new Header(source, ledgerId, supplementalId, candidate, stored.fingerprintHmac(), user, taller, lotId,
                row.getString("resultado"), completed, expires,
                source == Source.WITHOUT_ACTS ? revision(row.getString("submitted_revision")) : null,
                aggregate, source == Source.WITHOUT_ACTS ? integer(row, "referencia_count") : 0);
    }

    private void validateWithoutShape(Header header, LegalAcceptanceCommand command) {
        require(command.operation() == LegalAcceptanceCommand.Operation.AUTHENTICATED_ACCEPTANCE
                && header.submittedRevision().equals(command.requiredSetRevision()));
        if (command.acceptances().isEmpty()) {
            require("EMPTY".equals(header.result()) && header.referenceCount() == 0
                    && header.submittedRevision().equals(header.aggregate().revision()));
        } else require("DEDUP".equals(header.result()) && header.referenceCount() >= 1
                && header.referenceCount() <= MAX_ACTS && header.referenceCount() == command.acceptances().size());
    }

    private List<UUID> readReferences(Reservation reservation, Header header) {
        List<UUID> result = select(reservation, """
                SELECT aceptacion_id,user_id,taller_id FROM public.legal_idempotencia_sin_actos_referencias
                 WHERE resultado_id=? ORDER BY aceptacion_id
                """, List.of(header.supplementalId()), header.referenceCount(), row -> {
            require(number(row, "user_id") == header.userId() && number(row, "taller_id") == header.tallerId());
            return uuid(row, "aceptacion_id");
        });
        require(result.size() == header.referenceCount());
        return immutableIds(result);
    }

    private Lot readLot(Reservation reservation, UUID lotId, long userId, long tallerId, Instant observedAt) {
        List<Lot> result = select(reservation, """
                SELECT id,user_id,taller_id,rol_wire,audiencia,required_set_revision,revision_scheme,perfil,agregado_id,
                       aceptado_en,public.legal_fila_es_transaccion_actual(l.xmin) AS top_xid,%s AS own_row
                  FROM public.legal_aceptacion_lotes l WHERE id=?
                """.formatted(ownRow("l")), List.of(Objects.requireNonNull(lotId, "lotId")), 1, row -> lot(row, userId, tallerId, observedAt));
        require(result.size() == 1); return result.getFirst();
    }

    private Lot lot(ResultSet row, long userId, long tallerId, Instant observedAt) throws SQLException {
        require(number(row, "user_id") == userId && number(row, "taller_id") == tallerId);
        UserRole role = UserRole.valueOf(row.getString("rol_wire"));
        require(role.toAudienciaLegal().name().equals(row.getString("audiencia")));
        String scheme = row.getString("revision_scheme"), revision = revision(row.getString("required_set_revision"));
        ExpectedAggregate aggregate = null;
        if ("AGGREGATE_V1".equals(scheme)) {
            aggregate = new ExpectedAggregate(uuid(row, "agregado_id"), PerfilAgregadoLegal.valueOf(row.getString("perfil")),
                    role.toAudienciaLegal(), revision);
        } else require("SCOPE_V1".equals(scheme) && row.getObject("perfil") == null && row.getObject("agregado_id") == null);
        Instant acceptedAt = timestamp(row, "aceptado_en"); require(!acceptedAt.isAfter(observedAt));
        return new Lot(uuid(row, "id"), role, scheme, revision, aggregate, acceptedAt, bool(row, "own_row"), bool(row, "top_xid"));
    }

    private static void checkLotOperation(Lot lot, LegalAcceptanceCommand command) {
        if (command.operation() == LegalAcceptanceCommand.Operation.REGISTRATION) {
            require(lot.role() == UserRole.ADMIN && (lot.aggregate() == null
                    || lot.aggregate().profile() == PerfilAgregadoLegal.REGISTRATION));
        } else require(lot.aggregate() == null || lot.aggregate().profile() == PerfilAgregadoLegal.AUTHENTICATED_PENDING);
    }

    private Map<UUID, Act> accreditCommand(Reservation reservation, long userId, long tallerId, UUID newLot,
            boolean writing, Instant observedAt, Map<UUID, ExpectedAggregate> aggregates) {
        Map<UUID, Acceptance> submitted = new LinkedHashMap<>();
        for (Acceptance item : reservation.command().acceptances()) {
            require(item.confirmado() && submitted.putIfAbsent(item.requisitoVersionId(), item) == null
                    && !item.documentos().isEmpty() && item.documentos().size() <= MAX_DOCUMENTS);
            Set<UUID> documents = new HashSet<>();
            item.documentos().forEach(document -> require(documents.add(document.documentoVersionId())));
        }
        require(submitted.size() <= MAX_ACTS);
        Map<UUID, Act> result = new LinkedHashMap<>();
        for (List<UUID> batch : batches(List.copyOf(submitted.keySet()))) {
            List<Object> args = new ArrayList<>(batch); args.add(userId); args.add(tallerId);
            select(reservation, """
                    SELECT needed.id AS requested_requirement, a.id AS acceptance_id,a.user_id AS acceptance_user,
                           a.taller_id AS acceptance_workshop,a.requisito_version_id,a.tipo_acto,a.afirmacion_sha256,a.contexto,
                           %s AS own_acceptance,
                           b.id,b.user_id,b.taller_id,b.rol_wire,b.audiencia,b.required_set_revision,b.revision_scheme,
                           b.perfil,b.agregado_id,b.aceptado_en,public.legal_fila_es_transaccion_actual(b.xmin) AS top_xid,%s AS own_row,
                           (SELECT count(*) FROM (SELECT 1 FROM public.legal_requisito_documentos rd
                              WHERE rd.requisito_version_id=v.id LIMIT 17) bounded) AS source_document_count,
                           (pg_catalog.convert_to(a.requisito_clave,'UTF8')=pg_catalog.convert_to(line.clave,'UTF8')
                            AND pg_catalog.convert_to(a.requisito_version,'UTF8')=pg_catalog.convert_to(v.version,'UTF8')
                            AND a.contexto=line.contexto AND a.tipo_acto=line.tipo_acto AND a.requerido=v.requerido
                            AND CASE WHEN pg_catalog.octet_length(a.afirmacion) BETWEEN 1 AND 4000
                                       AND pg_catalog.char_length(a.afirmacion) BETWEEN 1 AND 1000
                                       AND pg_catalog.octet_length(v.afirmacion) BETWEEN 1 AND 4000
                                       AND pg_catalog.char_length(v.afirmacion) BETWEEN 1 AND 1000
                                 THEN pg_catalog.convert_to(a.afirmacion,'UTF8')=pg_catalog.convert_to(v.afirmacion,'UTF8')
                                      AND pg_catalog.encode(pg_catalog.sha256(pg_catalog.convert_to(a.afirmacion,'UTF8')),'hex')=a.afirmacion_sha256
                                 ELSE false END
                            AND a.afirmacion_sha256=v.afirmacion_sha256 AND line.locale='es-AR'
                            AND EXISTS(SELECT 1 FROM public.legal_requisito_audiencias ra
                                        WHERE ra.requisito_linea_id=line.id AND ra.audiencia=b.audiencia)) AS canonical_snapshot,
                           CASE b.revision_scheme
                             WHEN 'SCOPE_V1' THEN EXISTS(
                               SELECT 1 FROM public.legal_requisito_conjuntos c
                               JOIN public.legal_requisito_conjunto_miembros m ON m.conjunto_id=c.id
                               WHERE c.locale=line.locale AND c.contexto=line.contexto AND c.audiencia=b.audiencia
                                 AND c.required_set_revision=b.required_set_revision AND m.publicacion_id=c.publicacion_id
                                 AND m.requisito_version_id=v.id AND m.requisito_linea_id=line.id)
                             WHEN 'AGGREGATE_V1' THEN EXISTS(
                               SELECT 1 FROM public.legal_requisito_agregado_scopes s
                               JOIN public.legal_requisito_conjuntos c ON c.id=s.conjunto_id
                               JOIN public.legal_requisito_conjunto_miembros m ON m.conjunto_id=c.id
                               WHERE s.agregado_id=b.agregado_id AND s.contexto=line.contexto AND s.locale=line.locale
                                 AND s.audiencia=b.audiencia AND c.contexto=s.contexto AND c.locale=s.locale
                                 AND c.audiencia=s.audiencia AND c.publicacion_id=s.publicacion_id
                                 AND c.required_set_revision=s.required_set_revision AND m.publicacion_id=c.publicacion_id
                                 AND m.requisito_version_id=v.id AND m.requisito_linea_id=line.id)
                             ELSE false END AS source_membership
                      FROM (VALUES %s) needed(id)
                      LEFT JOIN public.legal_aceptaciones a ON a.requisito_version_id=needed.id AND a.user_id=? AND a.taller_id=?
                      LEFT JOIN public.legal_aceptacion_lotes b ON b.id=a.lote_id
                      LEFT JOIN public.legal_requisito_versiones v ON v.id=a.requisito_version_id
                      LEFT JOIN public.legal_requisito_lineas line ON line.id=v.requisito_linea_id
                     ORDER BY needed.id
                    """.formatted(ownRow("a"), ownRow("b"), values(batch.size())), args, batch.size(), row -> {
                UUID requirement = uuid(row, "requested_requirement");
                Acceptance expected = submitted.get(requirement);
                require(expected != null && requirement.equals(uuid(row, "requisito_version_id"))
                        && number(row, "acceptance_user") == userId && number(row, "acceptance_workshop") == tallerId
                        && expected.tipoActo().name().equals(row.getString("tipo_acto"))
                        && expected.afirmacionSha256().equals(row.getString("afirmacion_sha256"))
                        && bool(row, "canonical_snapshot") && bool(row, "source_membership")
                        && integer(row, "source_document_count") == expected.documentos().size());
                ContextoLegal context = ContextoLegal.valueOf(row.getString("contexto"));
                if (reservation.command().operation() == LegalAcceptanceCommand.Operation.REGISTRATION) require(context == ContextoLegal.REGISTRO);
                Lot lot = lot(row, userId, tallerId, observedAt);
                boolean ownAcceptance = bool(row, "own_acceptance");
                if (writing && lot.id().equals(newLot)) require(lot.ownRow());
                else require(!lot.ownRow() && !ownAcceptance);
                addAggregate(aggregates, lot.aggregate());
                Act act = new Act(uuid(row, "acceptance_id"), requirement, lot.id(), expected);
                require(result.putIfAbsent(requirement, act) == null); return act.id();
            });
        }
        require(result.size() == submitted.size());
        accreditDocuments(reservation, userId, tallerId, result);
        return result;
    }

    private void accreditDocuments(Reservation reservation, long userId, long tallerId, Map<UUID, Act> acts) {
        for (List<Act> batch : batches(List.copyOf(acts.values()))) {
            Map<UUID, Act> byId = new LinkedHashMap<>(); Map<UUID, List<UUID>> seen = new LinkedHashMap<>();
            batch.forEach(act -> { require(byId.putIfAbsent(act.id(), act) == null); seen.put(act.id(), new ArrayList<>()); });
            List<Object> args = new ArrayList<>(byId.keySet()); args.add(userId); args.add(tallerId);
            select(reservation, """
                    SELECT needed.id AS acceptance_id,a.id AS actor_acceptance,d.documento_ordinal,d.documento_version_id,d.sha256,
                           (pg_catalog.convert_to(d.documento_clave,'UTF8')=pg_catalog.convert_to(line.clave,'UTF8')
                            AND d.tipo=line.tipo AND pg_catalog.convert_to(d.version,'UTF8')=pg_catalog.convert_to(v.version,'UTF8')
                            AND pg_catalog.convert_to(d.titulo,'UTF8')=pg_catalog.convert_to(v.titulo,'UTF8') AND d.sha256=v.sha256
                            AND line.locale='es-AR' AND r.documento_version_id=d.documento_version_id
                            AND r.documento_ordinal=d.documento_ordinal) AS canonical_snapshot
                      FROM (VALUES %s) needed(id)
                      LEFT JOIN public.legal_aceptaciones a ON a.id=needed.id AND a.user_id=? AND a.taller_id=?
                      LEFT JOIN public.legal_aceptacion_documentos d ON d.aceptacion_id=a.id
                      LEFT JOIN public.legal_requisito_documentos r ON r.requisito_version_id=a.requisito_version_id
                           AND r.documento_ordinal=d.documento_ordinal
                      LEFT JOIN public.legal_documento_versiones v ON v.id=d.documento_version_id
                      LEFT JOIN public.legal_documento_lineas line ON line.id=v.documento_linea_id
                     ORDER BY needed.id,d.documento_ordinal
                    """.formatted(values(batch.size())), args, batch.size() * MAX_DOCUMENTS, row -> {
                UUID acceptance = uuid(row, "acceptance_id"), document = uuid(row, "documento_version_id");
                require(byId.containsKey(acceptance) && acceptance.equals(uuid(row, "actor_acceptance")) && bool(row, "canonical_snapshot"));
                List<UUID> documents = seen.get(acceptance);
                require(documents.size() < MAX_DOCUMENTS && integer(row, "documento_ordinal") == documents.size() + 1
                        && !documents.contains(document));
                require(byId.get(acceptance).submitted().documentos().stream().anyMatch(expected ->
                        expected.documentoVersionId().equals(document) && expected.sha256().equals(string(row, "sha256"))));
                documents.add(document); return acceptance;
            });
            batch.forEach(act -> require(seen.get(act.id()).size() == act.submitted().documentos().size()));
        }
    }

    private void checkLotMembers(Reservation reservation, UUID lotId, long userId, long tallerId, Map<UUID, Act> acts) {
        List<UUID> members = select(reservation,
                "SELECT id,user_id,taller_id FROM public.legal_aceptaciones WHERE lote_id=? ORDER BY id", List.of(lotId), MAX_ACTS, row -> {
                    require(number(row, "user_id") == userId && number(row, "taller_id") == tallerId); return uuid(row, "id");
                });
        require(!members.isEmpty());
        List<UUID> expected = immutableIds(acts.values().stream().filter(act -> act.lotId().equals(lotId)).map(Act::id).toList());
        require(members.equals(expected));
    }

    private Map<UUID, AggregateData> accreditAggregates(Reservation reservation,
            Map<UUID, ExpectedAggregate> expected, Instant observedAt) {
        Map<UUID, AggregateData> result = new LinkedHashMap<>();
        for (List<UUID> batch : batches(List.copyOf(expected.keySet()))) {
            Map<UUID, List<ScopeRevision>> revisions = new LinkedHashMap<>();
            Map<UUID, List<ScopeOrigin>> origins = new LinkedHashMap<>();
            Map<UUID, Integer> counts = new LinkedHashMap<>();
            Map<UUID, String> fingerprints = new LinkedHashMap<>();
            Map<UUID, Instant> dates = new LinkedHashMap<>();
            select(reservation, """
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
            });
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

    private static void addAggregate(Map<UUID, ExpectedAggregate> aggregates, ExpectedAggregate aggregate) {
        if (aggregate == null) return;
        ExpectedAggregate prior = aggregates.putIfAbsent(aggregate.id(), aggregate);
        require(prior == null || prior.equals(aggregate)); require(aggregates.size() <= MAX_ACTS + 1);
    }

    static Duration roundedTtl(Duration duration) {
        require(duration != null && duration.compareTo(MIN_TTL) >= 0); duration.toNanos();
        long microseconds = (duration.getNano() + 999L) / 1_000;
        return Duration.ofSeconds(duration.getSeconds()).plusNanos(microseconds * 1_000);
    }

    private Instant observation(Reservation reservation) {
        List<Instant> rows = select(reservation, "SELECT statement_timestamp() AS observed_at", List.of(), 1,
                row -> timestamp(row, "observed_at"));
        require(rows.size() == 1); return rows.getFirst();
    }

    private <T> List<T> select(Reservation reservation, String sql, List<?> arguments, int maximum, RowMapper<T> mapper) {
        return query(reservation, sql + " LIMIT " + (maximum + 1), arguments, maximum, mapper);
    }

    private <T> List<T> returning(Reservation reservation, String sql, List<?> arguments, int maximum, RowMapper<T> mapper) {
        return query(reservation, sql, arguments, maximum, mapper);
    }

    private <T> List<T> query(Reservation reservation, String sql, List<?> arguments, int maximum, RowMapper<T> mapper) {
        require(maximum >= 0 && maximum <= MAX_ACTS);
        reservation.readBudget();
        return Objects.requireNonNull(jdbc.execute((ConnectionCallback<List<T>>) connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                try {
                    statement.setFetchSize(BATCH);
                    for (int index = 0; index < arguments.size(); index++) statement.setObject(index + 1, arguments.get(index));
                    reservation.check();
                    try (ResultSet rows = statement.executeQuery()) {
                        List<T> values = new ArrayList<>();
                        while (true) {
                            reservation.check(); boolean more = rows.next(); reservation.check();
                            if (!more) return List.copyOf(values);
                            require(values.size() < maximum); values.add(Objects.requireNonNull(mapper.map(rows)));
                        }
                    }
                } catch (SQLException | RuntimeException failure) {
                    try { statement.cancel(); } catch (SQLException | RuntimeException ignored) { }
                    throw failure;
                }
            }
        }));
    }

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

    private static List<Object> fingerprintArguments(LegalIdempotencyFingerprint fingerprint) {
        List<Object> result = new ArrayList<>(); addTuple(result, fingerprint);
        result.add(fingerprint.fingerprintHmac()); result.add(fingerprint.keyVersion()); return result;
    }
    private static void addTuple(List<Object> arguments, LegalIdempotencyFingerprint fingerprint) {
        arguments.add(fingerprint.operation().name()); arguments.add(fingerprint.routeTemplate());
        arguments.add(fingerprint.scopeHmac()); arguments.add(fingerprint.idempotencyKeyHmac());
    }
    private static String values(int size) { require(size > 0 && size <= BATCH); return String.join(",", Collections.nCopies(size, "(?::uuid)")); }
    private static <T> List<List<T>> batches(List<T> values) {
        List<List<T>> result = new ArrayList<>();
        for (int index = 0; index < values.size(); index += BATCH) result.add(values.subList(index, Math.min(values.size(), index + BATCH)));
        return result;
    }
    private static List<UUID> ids(Map<UUID, Act> acts) { return immutableIds(acts.values().stream().map(Act::id).toList()); }
    private static List<UUID> immutableIds(List<UUID> supplied) {
        require(supplied != null && supplied.size() <= MAX_ACTS);
        List<UUID> ids = new ArrayList<>(supplied); Set<UUID> unique = new HashSet<>();
        for (UUID id : ids) require(id != null && unique.add(id));
        ids.sort(Comparator.comparing(UUID::toString)); return List.copyOf(ids);
    }
    private static String revision(String value) { require(value != null && value.matches("sha256:[0-9a-f]{64}")); return value; }
    private static UUID uuid(ResultSet row, String name) throws SQLException { UUID value = row.getObject(name, UUID.class); require(value != null); return value; }
    private static long number(ResultSet row, String name) throws SQLException { long value = row.getLong(name); require(!row.wasNull()); return value; }
    private static int integer(ResultSet row, String name) throws SQLException { return Math.toIntExact(number(row, name)); }
    private static boolean bool(ResultSet row, String name) throws SQLException { boolean value = row.getBoolean(name); require(!row.wasNull()); return value; }
    private static String string(ResultSet row, String name) { try { return row.getString(name); } catch (SQLException failure) { throw new IllegalStateException(failure); } }
    private static Instant timestamp(ResultSet row, String name) throws SQLException { OffsetDateTime value = row.getObject(name, OffsetDateTime.class); require(value != null); return value.toInstant(); }
    private static void require(boolean condition) { if (!condition) throw new LegalIdempotencyException(LegalIdempotencyException.Reason.UNAVAILABLE); }

    enum Source { WITH_ACTS, WITHOUT_ACTS }

    /** Durable identity only; its existence in this transaction is not a successful caller commit. */
    record StoredResult(Source source, Long ledgerId, UUID supplementalId, long userId, long tallerId, UUID lotId,
                        String result, Instant completedAt, Instant expiresAt, List<UUID> acceptanceIds) {
        StoredResult {
            require(source != null && userId > 0 && tallerId > 0);
            require(completedAt != null && expiresAt != null && completedAt.getNano() % 1_000 == 0 && expiresAt.getNano() % 1_000 == 0
                    && !expiresAt.isBefore(completedAt.plus(MIN_TTL)));
            acceptanceIds = immutableIds(acceptanceIds);
            if (source == Source.WITH_ACTS) require(ledgerId != null && ledgerId > 0 && supplementalId == null && lotId != null
                    && "WITH_ACTS".equals(result) && !acceptanceIds.isEmpty());
            else require(ledgerId == null && supplementalId != null && lotId == null
                    && (("EMPTY".equals(result) && acceptanceIds.isEmpty()) || ("DEDUP".equals(result) && !acceptanceIds.isEmpty())));
        }
        @Override public String toString() { return "StoredResult[source=" + source + ", result=" + result + "]"; }
    }

    private record Header(Source source, Long ledgerId, UUID supplementalId, LegalIdempotencyFingerprint candidate,
                          String fingerprint, long userId, long tallerId, UUID lotId, String result, Instant completedAt,
                          Instant expiresAt, String submittedRevision, ExpectedAggregate aggregate, int referenceCount) { }
    private record Lot(UUID id, UserRole role, String scheme, String revision, ExpectedAggregate aggregate, Instant acceptedAt, boolean ownRow, boolean topXid) { }
    private record ExpectedAggregate(UUID id, PerfilAgregadoLegal profile, AudienciaLegal audience, String revision) { }
    private record AggregateData(LegalRequiredSetAggregateProvenance provenance, String provenanceFingerprint, Instant createdAt) { }
    private record Act(UUID id, UUID requirementId, UUID lotId, Acceptance submitted) { }
    private record Completion(Instant completedAt, Instant expiresAt) { }
    @FunctionalInterface private interface RowMapper<T> { T map(ResultSet row) throws SQLException; }
}
