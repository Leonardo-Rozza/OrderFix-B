package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalActorSnapshot;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestLimits;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequestMetadata;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateProvenanceCalculator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateRevisionCalculator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection.RequirementProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalIdempotencyCoordinator.Reservation;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EsquemaRevisionLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PerfilAgregadoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Creates a new account and canonical evidence inside its existing REGISTRATION reservation. */
final class LegalRegistrationWriter {
    private static final int BATCH = 32;
    private final JdbcTemplate jdbc;
    private final LegalAcceptanceMetadataCodec codec;
    private final LegalAcceptanceMetadataWriter metadataWriter;
    private final LegalIdempotencyResultStore results;
    private final LegalRequiredSetAggregateReplayVerifier aggregateVerifier;

    LegalRegistrationWriter(JdbcTemplate jdbc, LegalAcceptanceMetadataCodec codec,
                            LegalAcceptanceMetadataPolicy policy, LegalIdempotencyResultStore results) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.results = Objects.requireNonNull(results, "results");
        if (jdbc.getDataSource() == null || !results.usesJdbc(jdbc)) {
            throw new IllegalArgumentException("El registro requiere una única frontera JDBC");
        }
        metadataWriter = new LegalAcceptanceMetadataWriter(jdbc, codec, policy);
        aggregateVerifier = new LegalRequiredSetAggregateReplayVerifier(jdbc,
                new LegalRequiredSetAggregateRevisionCalculator(),
                new LegalRequiredSetAggregateProvenanceCalculator());
    }

    boolean usesJdbc(JdbcTemplate candidate) { return jdbc == candidate; }

    LegalRegistrationReceipt write(Reservation reservation, LegalRegistrationPreparation.Prepared prepared,
                                   LegalRequiredSetAggregateReceipt aggregate,
                                   LegalRegistrationSelection.Selection selection, LegalRequestMetadata metadata) {
        try {
            require(reservation != null && reservation.jdbc() == jdbc);
            reservation.requireNew();
            LegalAcceptanceCommand command = reservation.command();
            require(command != null && command.operation() == LegalAcceptanceCommand.Operation.REGISTRATION
                    && command.actor() == null && command.registration() != null);
            require(prepared != null && selection != null && aggregate != null && metadata != null);
            prepared.requireCommand(command);
            selection.requireCommand(command);
            require(selection.current() != null && !selection.requirements().isEmpty()
                    && selection.requirements().size() <= LegalManifestLimits.MAX_REQUIREMENTS
                    && command.requiredSetRevision().equals(selection.current().requiredSetRevision())
                    && command.requiredSetRevision().equals(aggregate.requiredSetRevision()));
            reservation.readBudget();
            jdbc.queryForList("SELECT public.legal_exigir_lock_editorial_v28()");
            verifyAggregate(reservation, aggregate, selection);

            UUID lotId = UUID.randomUUID();
            // No business row is created if capture, key configuration or encryption fails.
            var encrypted = codec.prepare(lotId, metadata);
            require(encrypted != null && codec.owns(encrypted));
            var registration = prepared.registration();
            reservation.readBudget();
            Long tallerId = jdbc.queryForObject("""
                    INSERT INTO public.talleres
                      (nombre,email_contacto,telefono,activo,created_at,updated_at)
                    VALUES (?,?,?,true,?,?) RETURNING id
                    """, Long.class, registration.nombreTaller(), registration.email(),
                    registration.telefonoTaller(), prepared.auditAt(), prepared.auditAt());
            require(tallerId != null && tallerId > 0);
            reservation.readBudget();
            int subscriptions = jdbc.update("""
                    INSERT INTO public.suscripciones
                      (taller_id,plan,estado,fecha_inicio,fecha_fin_trial,created_at,updated_at)
                    VALUES (?,'FREE','TRIAL',?,?,?,?)
                    """, tallerId, prepared.startDate(), prepared.trialEndDate(),
                    prepared.auditAt(), prepared.auditAt());
            require(subscriptions == 1);
            reservation.readBudget();
            Long userId = jdbc.queryForObject("""
                    INSERT INTO public.users
                      (username,password,email,role,taller_id,active,email_verificado,token_version)
                    VALUES (?,?,?,'ADMIN',?,true,false,0) RETURNING id
                    """, Long.class, registration.nombreAdmin(), prepared.encodedPassword(),
                    registration.email(), tallerId);
            require(userId != null && userId > 0);

            // IDs come exclusively from the three successful INSERTs above, never from caller input.
            var actor = new LegalActorSnapshot(userId, tallerId, UserRole.ADMIN, 0, true, true);
            reservation.readBudget();
            jdbc.queryForList("""
                    SELECT pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(
                      pg_catalog.jsonb_build_array('ordenfix:legal-actor:v1',?::bigint,?::bigint)::text,0))
                    """, tallerId, userId);
            reservation.requireWriteActor(actor);
            reservation.readBudget();
            OffsetDateTime acceptedAt = jdbc.queryForObject("""
                    INSERT INTO public.legal_aceptacion_lotes
                      (id,user_id,taller_id,rol_wire,audiencia,required_set_revision,aceptado_en,
                       revision_scheme,perfil,agregado_id)
                    VALUES (?,?,?,'ADMIN','ADMIN_TITULAR',?,statement_timestamp(),
                            'AGGREGATE_V1','REGISTRATION',?) RETURNING aceptado_en
                    """, OffsetDateTime.class, lotId, userId, tallerId,
                    aggregate.requiredSetRevision(), aggregate.aggregateId());
            require(acceptedAt != null);
            List<UUID> acts = insertActs(reservation, actor, lotId, selection.requirements());
            metadataWriter.persist(reservation, actor, encrypted);
            results.persistWithActs(reservation, actor, lotId);
            return new LegalRegistrationReceipt(userId, tallerId, false, lotId, acts);
        } catch (RuntimeException failure) {
            if (reservation != null) throw reservation.fail(failure);
            throw new LegalIdempotencyException(LegalIdempotencyException.Reason.UNAVAILABLE);
        }
    }

    private void verifyAggregate(Reservation reservation, LegalRequiredSetAggregateReceipt aggregate,
                                 LegalRegistrationSelection.Selection selection) {
        var provenance = aggregate.provenance();
        require(provenance.profile() == PerfilAgregadoLegal.REGISTRATION
                && provenance.locale() == LocaleLegal.ES_AR && provenance.audience() == AudienciaLegal.ADMIN_TITULAR
                && provenance.scopes().size() == 1 && provenance.scopes().getFirst().context() == ContextoLegal.REGISTRO);
        reservation.readBudget();
        var observations = jdbc.query("""
                SELECT transaction_timestamp() AS transaction_at, statement_timestamp() AS observed_at
                """, (row, index) -> new LegalEditorialTimeBoundary(
                timestamp(row, "transaction_at"), timestamp(row, "observed_at")));
        require(observations.size() == 1);
        var observation = observations.getFirst();
        require(!aggregate.createdAt().isAfter(observation.observedAt()));
        var projection = new LegalRequiredSetAggregateProjection(EsquemaRevisionLegal.AGGREGATE_V1,
                LocaleLegal.ES_AR, AudienciaLegal.ADMIN_TITULAR,
                List.of(new LegalRequiredSetAggregateProjection.ScopeRevision(
                        ContextoLegal.REGISTRO, selection.current().scopeRevision())));
        var expected = new LegalRequiredSetAggregateReplayVerifier.ExpectedAggregate(projection,
                aggregate.requiredSetRevision(), provenance, aggregate.provenanceFingerprint());
        reservation.readBudget();
        // The post-lock materialization has already happened. CREATED must belong to this transaction's
        // time interval; using this later observation as its lower bound would reject valid new rows.
        var verified = aggregateVerifier.verify(aggregate.aggregateId(), aggregate.outcome(), expected,
                new LegalEditorialTimeBoundary(observation.transactionAt(), observation.transactionAt()));
        require(verified.createdAt().equals(aggregate.createdAt()));
        var origin = provenance.scopes().getFirst();
        reservation.readBudget();
        var current = jdbc.query("""
                SELECT c.id,c.publicacion_id,c.locale,c.contexto,c.audiencia,c.required_set_revision,c.creado_en,
                       p.conjunto_id AS current_set,p.publicacion_id AS current_publication,p.actualizado_en,
                       publication.id AS publication_id,publication.locale AS publication_locale,
                       publication.estado_construccion,publication.importado_en,publication.sellado_en
                  FROM public.legal_requisito_conjuntos c
                  LEFT JOIN public.legal_requisito_conjuntos_actuales p
                    ON p.locale=c.locale AND p.contexto=c.contexto AND p.audiencia=c.audiencia
                  LEFT JOIN public.legal_publicaciones publication ON publication.id=c.publicacion_id
                 WHERE c.id=? LIMIT 2
                """, (row, index) -> origin.requiredSetId().equals(row.getObject("id", UUID.class))
                && origin.publicationId().equals(row.getObject("publicacion_id", UUID.class))
                && "es-AR".equals(row.getString("locale")) && "REGISTRO".equals(row.getString("contexto"))
                && "ADMIN_TITULAR".equals(row.getString("audiencia"))
                && selection.current().scopeRevision().equals(row.getString("required_set_revision"))
                && origin.requiredSetId().equals(row.getObject("current_set", UUID.class))
                && origin.publicationId().equals(row.getObject("current_publication", UUID.class))
                && origin.publicationId().equals(row.getObject("publication_id", UUID.class))
                && "es-AR".equals(row.getString("publication_locale"))
                && "SELLADO".equals(row.getString("estado_construccion"))
                && !timestamp(row, "creado_en").isAfter(observation.observedAt())
                && !timestamp(row, "actualizado_en").isAfter(observation.observedAt())
                && !timestamp(row, "importado_en").isAfter(observation.observedAt())
                && !timestamp(row, "sellado_en").isAfter(observation.observedAt()), origin.requiredSetId());
        require(current.size() == 1 && Boolean.TRUE.equals(current.getFirst()));
    }

    private List<UUID> insertActs(Reservation reservation, LegalActorSnapshot actor, UUID lotId,
                                  List<RequirementProjection> selected) {
        List<RequirementProjection> ordered = selected.stream()
                .sorted((left, right) -> left.versionId().toString().compareTo(right.versionId().toString())).toList();
        Map<UUID, UUID> expectedIds = new HashMap<>();
        List<UUID> written = new ArrayList<>();
        for (int offset = 0; offset < ordered.size(); offset += BATCH) {
            var batch = ordered.subList(offset, Math.min(ordered.size(), offset + BATCH));
            List<Object> arguments = new ArrayList<>();
            arguments.add(lotId); arguments.add(actor.userId()); arguments.add(actor.tallerId());
            for (var requirement : batch) {
                UUID id = UUID.randomUUID();
                require(expectedIds.putIfAbsent(requirement.versionId(), id) == null);
                arguments.add(requirement.versionId()); arguments.add(id);
                arguments.add(requirement.statementSha256()); arguments.add(requirement.actType().name());
            }
            reservation.readBudget();
            var inserted = jdbc.query("""
                    INSERT INTO public.legal_aceptaciones
                      (id,lote_id,user_id,taller_id,requisito_version_id,requisito_clave,requisito_version,
                       contexto,tipo_acto,afirmacion,afirmacion_sha256,requerido)
                    SELECT selected.act_id,?,?,?,v.id,line.clave,v.version,line.contexto,line.tipo_acto,
                           v.afirmacion,v.afirmacion_sha256,v.requerido
                      FROM (VALUES %s) selected(requirement_id,act_id,digest,act_type)
                      JOIN public.legal_requisito_versiones v ON v.id=selected.requirement_id
                      JOIN public.legal_requisito_lineas line ON line.id=v.requisito_linea_id
                     WHERE v.afirmacion_sha256=selected.digest AND line.tipo_acto=selected.act_type
                       AND line.contexto='REGISTRO'
                     ORDER BY v.id RETURNING id,requisito_version_id
                    """.formatted(String.join(",", Collections.nCopies(batch.size(), "(?::uuid,?::uuid,?::varchar,?::varchar)"))),
                    (row, index) -> {
                        UUID requirement = row.getObject("requisito_version_id", UUID.class);
                        UUID id = row.getObject("id", UUID.class);
                        require(id != null && id.equals(expectedIds.get(requirement)));
                        return id;
                    }, arguments.toArray());
            require(inserted.size() == batch.size());
            written.addAll(inserted);
            reservation.readBudget();
            int documents = jdbc.update("""
                    INSERT INTO public.legal_aceptacion_documentos
                      (aceptacion_id,documento_ordinal,documento_version_id,documento_clave,tipo,version,titulo,sha256)
                    SELECT a.id,d.documento_ordinal,v.id,line.clave,line.tipo,v.version,v.titulo,v.sha256
                      FROM (VALUES %s) selected(id)
                      JOIN public.legal_aceptaciones a ON a.id=selected.id
                      JOIN public.legal_requisito_documentos d ON d.requisito_version_id=a.requisito_version_id
                      JOIN public.legal_documento_versiones v ON v.id=d.documento_version_id
                      JOIN public.legal_documento_lineas line ON line.id=v.documento_linea_id
                     ORDER BY a.id,d.documento_ordinal
                    """.formatted(String.join(",", Collections.nCopies(inserted.size(), "(?::uuid)"))), inserted.toArray());
            require(documents == batch.stream().mapToInt(requirement -> requirement.documents().size()).sum());
        }
        return List.copyOf(written);
    }

    private static Instant timestamp(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        require(value != null && value.getNano() % 1_000 == 0);
        return value.toInstant();
    }

    private static void require(boolean condition) {
        if (!condition) throw new LegalIdempotencyException(LegalIdempotencyException.Reason.UNAVAILABLE);
    }
}
