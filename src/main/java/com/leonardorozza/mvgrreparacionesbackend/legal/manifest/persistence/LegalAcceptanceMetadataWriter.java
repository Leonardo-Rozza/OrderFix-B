package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalActorSnapshot;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceMetadataCodec.PreparedMetadata;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalIdempotencyCoordinator.Reservation;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Objects;

/** Inserts metadata inside the owning writer's transaction; never commits or completes its reservation. */
final class LegalAcceptanceMetadataWriter {
    private final JdbcTemplate jdbc;
    private final LegalAcceptanceMetadataCodec codec;
    private final LegalAcceptanceMetadataPolicy policy;

    LegalAcceptanceMetadataWriter(JdbcTemplate jdbc, LegalAcceptanceMetadataCodec codec,
                                  LegalAcceptanceMetadataPolicy policy) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        Objects.requireNonNull(jdbc.getDataSource(), "jdbc.dataSource");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    void persist(Reservation reservation, LegalActorSnapshot actor, PreparedMetadata prepared) {
        try {
            require(reservation != null && prepared != null && codec.owns(prepared));
            require(reservation.jdbc() == jdbc);
            reservation.requireNew();
            reservation.requireWriteActor(actor);
            reservation.readBudget();
            var lots = jdbc.query("""
                    SELECT l.user_id,l.taller_id,l.rol_wire,l.audiencia,l.revision_scheme,l.perfil,
                           l.required_set_revision,l.aceptado_en,statement_timestamp() AS observed_at,
                           public.legal_fila_es_transaccion_actual(l.xmin) AS current_lot,
                           EXISTS(SELECT 1 FROM public.legal_aceptaciones a WHERE a.lote_id=l.id) AS has_acts,
                           EXISTS(SELECT 1 FROM public.legal_aceptacion_metadatos m WHERE m.lote_id=l.id) AS has_metadata
                      FROM public.legal_aceptacion_lotes l WHERE l.id=? LIMIT 2 FOR SHARE
                    """, (row, index) -> {
                require(actor != null && row.getLong("user_id") == actor.userId()
                        && row.getLong("taller_id") == actor.tallerId()
                        && actor.role().name().equals(row.getString("rol_wire"))
                        && actor.audience().name().equals(row.getString("audiencia"))
                        && row.getBoolean("current_lot") && row.getBoolean("has_acts")
                        && !row.getBoolean("has_metadata")
                        && "AGGREGATE_V1".equals(row.getString("revision_scheme"))
                        && reservation.command().requiredSetRevision().equals(row.getString("required_set_revision")));
                String expectedProfile = reservation.command().operation() == LegalAcceptanceCommand.Operation.REGISTRATION
                        ? "REGISTRATION" : "AUTHENTICATED_PENDING";
                require(expectedProfile.equals(row.getString("perfil")));
                OffsetDateTime accepted = row.getObject("aceptado_en", OffsetDateTime.class);
                OffsetDateTime observed = row.getObject("observed_at", OffsetDateTime.class);
                require(accepted != null && observed != null && !accepted.isAfter(observed));
                Instant capturedAt = accepted.toInstant();
                require(capturedAt.getNano() % 1_000 == 0);
                Instant retainUntil = policy.expiresAt(capturedAt);
                require(retainUntil.isAfter(observed.toInstant()));
                return new Header(capturedAt, retainUntil);
            }, prepared.lotId());
            require(lots.size() == 1);
            Header expected = lots.getFirst();
            reservation.readBudget();
            var inserted = jdbc.query("""
                    INSERT INTO public.legal_aceptacion_metadatos(lote_id,capturado_en,retener_hasta)
                    VALUES (?,?,?) RETURNING capturado_en,retener_hasta
                    """, (row, index) -> new Header(
                    row.getObject("capturado_en", OffsetDateTime.class).toInstant(),
                    row.getObject("retener_hasta", OffsetDateTime.class).toInstant()),
                    prepared.lotId(), expected.capturedAt().atOffset(ZoneOffset.UTC), expected.retainUntil().atOffset(ZoneOffset.UTC));
            require(inserted.size() == 1 && inserted.getFirst().equals(expected));
            for (var field : prepared.fields()) {
                byte[] nonce = field.nonce(), ciphertext = field.ciphertext(), tag = field.tag();
                try {
                    reservation.readBudget();
                    // The restricted role cannot read ciphertext or its generated identity column.
                    int insertedFields = jdbc.update("""
                            INSERT INTO public.legal_aceptacion_metadatos_cifrados
                              (lote_id,tipo,key_version,nonce,ciphertext,tag,longitud_original)
                            VALUES (?,?,?,?,?,?,?)
                            """, prepared.lotId(), field.tipo().name(), field.keyVersion(), nonce,
                            ciphertext, tag, field.originalLength());
                    require(insertedFields == 1);
                } finally {
                    Arrays.fill(nonce, (byte) 0);
                    Arrays.fill(ciphertext, (byte) 0);
                    Arrays.fill(tag, (byte) 0);
                }
            }
            reservation.check();
        } catch (RuntimeException failure) {
            // Never retry a duplicate nonce or run recovery SQL in a failed PostgreSQL transaction.
            if (reservation != null) throw reservation.fail(failure);
            throw new LegalIdempotencyException(LegalIdempotencyException.Reason.UNAVAILABLE);
        }
    }

    private static void require(boolean condition) {
        if (!condition) throw new LegalIdempotencyException(LegalIdempotencyException.Reason.UNAVAILABLE);
    }

    private record Header(Instant capturedAt, Instant retainUntil) { }
}
