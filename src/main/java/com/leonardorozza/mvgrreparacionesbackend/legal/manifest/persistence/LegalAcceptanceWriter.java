package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalActorSnapshot;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequestMetadata;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection.RequirementProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceSelection.Selection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalIdempotencyCoordinator.Reservation;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PerfilAgregadoLegal;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Writes selected canonical evidence and its technical result in the reservation's single transaction. */
final class LegalAcceptanceWriter {
    private static final int BATCH = 32;
    private final JdbcTemplate jdbc;
    private final LegalAcceptanceMetadataCodec codec;
    private final LegalAcceptanceMetadataWriter metadataWriter;
    private final LegalIdempotencyResultStore results;

    LegalAcceptanceWriter(JdbcTemplate jdbc, LegalAcceptanceMetadataCodec codec,
                          LegalAcceptanceMetadataPolicy policy, LegalIdempotencyResultStore results) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.results = Objects.requireNonNull(results, "results");
        if (jdbc.getDataSource() == null || !results.usesJdbc(jdbc)) {
            throw new IllegalArgumentException("El escritor requiere una única frontera JDBC");
        }
        metadataWriter = new LegalAcceptanceMetadataWriter(jdbc, codec, policy);
    }

    boolean usesJdbc(JdbcTemplate candidate) { return jdbc == candidate; }

    LegalAcceptanceReceipt write(Reservation reservation, LegalActorSnapshot actor,
                                 LegalRequiredSetAggregateReceipt aggregate, Selection selection,
                                 LegalRequestMetadata metadata) {
        try {
            require(reservation != null && reservation.jdbc() == jdbc);
            reservation.requireNew();
            reservation.requireWriteActor(actor);
            require(reservation.command().operation() == LegalAcceptanceCommand.Operation.AUTHENTICATED_ACCEPTANCE);
            require(aggregate != null && selection != null
                    && aggregate.provenance().profile() == PerfilAgregadoLegal.AUTHENTICATED_PENDING
                    && aggregate.provenance().audience() == actor.audience());
            if (selection.kind() != LegalAcceptanceSelection.Kind.WITH_ACTS) {
                require(selection.newRequirements().isEmpty());
                results.persistWithoutActs(reservation, actor, aggregate, selection.existingAcceptanceIds());
                return new LegalAcceptanceReceipt(selection.kind() == LegalAcceptanceSelection.Kind.EMPTY
                        ? LegalAcceptanceReceipt.Kind.EMPTY : LegalAcceptanceReceipt.Kind.DEDUP,
                        false, null, selection.existingAcceptanceIds());
            }
            require(!selection.newRequirements().isEmpty() && selection.newRequirements().size() <= 2_048
                    && aggregate.requiredSetRevision().equals(reservation.command().requiredSetRevision()));
            UUID lotId = UUID.randomUUID();
            // Encrypt everything before the first evidence INSERT; nonce collisions still roll back at V27.
            var prepared = codec.prepare(lotId, metadata);
            reservation.readBudget();
            OffsetDateTime acceptedAt = jdbc.queryForObject("""
                    INSERT INTO public.legal_aceptacion_lotes
                      (id,user_id,taller_id,rol_wire,audiencia,required_set_revision,aceptado_en,
                       revision_scheme,perfil,agregado_id)
                    VALUES (?,?,?,?,?,?,statement_timestamp(),'AGGREGATE_V1','AUTHENTICATED_PENDING',?)
                    RETURNING aceptado_en
                    """, OffsetDateTime.class, lotId, actor.userId(), actor.tallerId(), actor.role().name(),
                    actor.audience().name(), aggregate.requiredSetRevision(), aggregate.aggregateId());
            require(acceptedAt != null);
            List<UUID> writtenIds = insertActs(reservation, actor, lotId, selection.newRequirements());
            metadataWriter.persist(reservation, actor, prepared);
            results.persistWithActs(reservation, actor, lotId);
            List<UUID> all = new ArrayList<>(selection.existingAcceptanceIds());
            all.addAll(writtenIds);
            return new LegalAcceptanceReceipt(LegalAcceptanceReceipt.Kind.WITH_ACTS, false, lotId, all);
        } catch (RuntimeException failure) {
            if (reservation != null) throw reservation.fail(failure);
            throw new LegalIdempotencyException(LegalIdempotencyException.Reason.UNAVAILABLE);
        }
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
            for (var requirement : batch) {
                UUID id = UUID.randomUUID();
                require(expectedIds.putIfAbsent(requirement.versionId(), id) == null);
                arguments.add(requirement.versionId()); arguments.add(id);
                arguments.add(requirement.statementSha256()); arguments.add(requirement.actType().name());
            }
            arguments.add(lotId); arguments.add(actor.userId()); arguments.add(actor.tallerId());
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
                     ORDER BY v.id
                    RETURNING id,requisito_version_id
                    """.formatted(String.join(",", Collections.nCopies(batch.size(), "(?::uuid,?::uuid,?::varchar,?::varchar)"))),
                    (row, index) -> {
                        UUID requirement = row.getObject("requisito_version_id", UUID.class);
                        UUID id = row.getObject("id", UUID.class);
                        require(id != null && id.equals(expectedIds.get(requirement)));
                        return id;
                    }, rotateArguments(arguments, batch.size() * 4));
            require(inserted.size() == batch.size());
            written.addAll(inserted);
            List<Object> docsArguments = new ArrayList<>(inserted);
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
                    """.formatted(String.join(",", Collections.nCopies(inserted.size(), "(?::uuid)"))), docsArguments.toArray());
            require(documents == batch.stream().mapToInt(requirement -> requirement.documents().size()).sum());
        }
        return List.copyOf(written);
    }

    /** The three SELECT placeholders precede the VALUES relation in JDBC parameter order. */
    private static Object[] rotateArguments(List<Object> arguments, int tupleArguments) {
        List<Object> ordered = new ArrayList<>(arguments.subList(tupleArguments, arguments.size()));
        ordered.addAll(arguments.subList(0, tupleArguments));
        return ordered.toArray();
    }

    private static void require(boolean condition) {
        if (!condition) throw new LegalIdempotencyException(LegalIdempotencyException.Reason.UNAVAILABLE);
    }
}
