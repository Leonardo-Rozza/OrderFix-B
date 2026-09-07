package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalActorSnapshot;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequestMetadata;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceProtocolFeasibilityITSupport.Act;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceProtocolFeasibilityITSupport.Actor;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceProtocolFeasibilityITSupport.Lot;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalIdempotencyCoordinatorITSupport.Harness;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PerfilAgregadoLegal;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceProtocolFeasibilityITSupport.insertActs;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceProtocolFeasibilityITSupport.insertDocuments;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceProtocolFeasibilityITSupport.insertLot;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceProtocolFeasibilityITSupport.jdbc;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceProtocolFeasibilityITSupport.materialize;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceProtocolFeasibilityITSupport.transaction;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalIdempotencyCoordinatorITSupport.writerBoundary;
import static org.assertj.core.api.Assertions.assertThat;

/** Real canonical evidence without synthetic metadata; only disposable owner helpers inspect ciphertext. */
final class LegalAcceptanceMetadataITSupport {
    static final int AES_VERSION = 7;
    static final String AES_SECRET = Base64.getEncoder().encodeToString(
            "33333333333333333333333333333333".getBytes(StandardCharsets.US_ASCII));
    static final String IP = "198.51.100.17";
    static final Duration RETENTION = Duration.ofDays(30).plusNanos(999_999_501);
    static final Duration ROUNDED_RETENTION = Duration.ofDays(30).plusSeconds(1);

    private LegalAcceptanceMetadataITSupport() { }

    static LegalAcceptanceMetadataCodec codec() {
        assertThat(Base64.getDecoder().decode(AES_SECRET)).hasSize(32);
        return new LegalAcceptanceMetadataCodec(Map.of(AES_VERSION, AES_SECRET), AES_VERSION);
    }

    static LegalAcceptanceMetadataCodec codec(byte[]... nonces) {
        assertThat(Base64.getDecoder().decode(AES_SECRET)).hasSize(32);
        return new LegalAcceptanceMetadataCodec(Map.of(AES_VERSION, AES_SECRET), AES_VERSION,
                new ScriptedRandom(nonces));
    }

    static LegalAcceptanceMetadataWriter writer(Harness harness, LegalAcceptanceMetadataCodec codec) {
        return new LegalAcceptanceMetadataWriter(harness.jdbc(), codec, new LegalAcceptanceMetadataPolicy(RETENTION));
    }

    static LegalRequestMetadata capture(String userAgent) {
        return LegalRequestMetadata.of(LegalRequestMetadata.parseIpLiteral(IP), userAgent);
    }

    static Graph newGraph(Harness harness, LegalActorSnapshot actor, PerfilAgregadoLegal profile) {
        writerBoundary(harness.jdbc(), actor);
        var aggregate = materialize(harness.jdbc(), profile, actor.audience());
        Lot lot = insertLot(harness.jdbc(), original(actor), aggregate);
        List<Act> acts = insertActs(harness.jdbc(), lot);
        insertDocuments(harness.jdbc(), acts);
        return new Graph(lot, acts);
    }

    static Lot emptyLot(Harness harness, LegalActorSnapshot actor) {
        writerBoundary(harness.jdbc(), actor);
        return insertLot(harness.jdbc(), original(actor), materialize(harness.jdbc(),
                PerfilAgregadoLegal.AUTHENTICATED_PENDING, actor.audience()));
    }

    private static Actor original(LegalActorSnapshot actor) {
        return new Actor(actor.userId(), actor.tallerId(), actor.role().name(), actor.audience().name());
    }

    static Header header(JdbcTemplate owner, UUID lotId) {
        return owner.queryForObject("""
                SELECT capturado_en,retener_hasta,purgado_en
                  FROM legal_aceptacion_metadatos WHERE lote_id=?
                """, (row, index) -> new Header(row.getObject("capturado_en", OffsetDateTime.class).toInstant(),
                row.getObject("retener_hasta", OffsetDateTime.class).toInstant(),
                row.getObject("purgado_en", OffsetDateTime.class)), lotId);
    }

    static List<CipherRow> fields(JdbcTemplate owner, UUID lotId) {
        return owner.query("""
                SELECT tipo,key_version,nonce,ciphertext,tag,longitud_original,tombstone_en
                  FROM legal_aceptacion_metadatos_cifrados WHERE lote_id=? ORDER BY tipo
                """, (row, index) -> new CipherRow(row.getString("tipo"), row.getInt("key_version"),
                row.getBytes("nonce"), row.getBytes("ciphertext"), row.getBytes("tag"),
                row.getObject("longitud_original", Integer.class), row.getObject("tombstone_en", OffsetDateTime.class)), lotId);
    }

    /** Independently consumes the persisted split GCM ciphertext/tag using the documented AAD. */
    static String decrypt(UUID lotId, CipherRow row) throws Exception {
        byte[] key = Base64.getDecoder().decode(AES_SECRET);
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, row.nonce()));
            cipher.updateAAD(("ordenfix:legal-metadata:v1:" + lotId + ":" + row.type() + ":" + row.keyVersion())
                    .getBytes(StandardCharsets.US_ASCII));
            byte[] encoded = Arrays.copyOf(row.ciphertext(), row.ciphertext().length + row.tag().length);
            System.arraycopy(row.tag(), 0, encoded, row.ciphertext().length, row.tag().length);
            byte[] plaintext = cipher.doFinal(encoded);
            try { return new String(plaintext, StandardCharsets.UTF_8); }
            finally { Arrays.fill(plaintext, (byte) 0); }
        } finally { Arrays.fill(key, (byte) 0); }
    }

    /** Advances only this disposable fixture's dates, then exercises the real V27 tombstone transition. */
    static void ageAndPurge(LegalIdempotencyCoordinatorITSupport fixture, UUID lotId) throws Exception {
        fixture.database.requireEphemeral();
        try (var connection = transaction(fixture.database.dataSource)) {
            JdbcTemplate owner = jdbc(connection);
            OffsetDateTime captured = owner.queryForObject("SELECT statement_timestamp()-INTERVAL '60 days'", OffsetDateTime.class);
            owner.execute("SET LOCAL session_replication_role = replica");
            assertThat(owner.update("UPDATE legal_aceptacion_lotes SET aceptado_en=? WHERE id=?", captured, lotId)).isOne();
            assertThat(owner.update("UPDATE legal_aceptacion_metadatos SET capturado_en=?,retener_hasta=? WHERE lote_id=?",
                    captured, captured.plusDays(30), lotId)).isOne();
            owner.execute("SET LOCAL session_replication_role = origin");
            assertThat(owner.update("""
                    UPDATE legal_aceptacion_metadatos_cifrados
                       SET ciphertext=NULL,tag=NULL,longitud_original=NULL,tombstone_en=transaction_timestamp()
                     WHERE lote_id=?
                    """, lotId)).isPositive();
            assertThat(owner.update("UPDATE legal_aceptacion_metadatos SET purgado_en=transaction_timestamp() WHERE lote_id=?", lotId)).isOne();
            owner.execute("SET CONSTRAINTS ALL IMMEDIATE");
            connection.commit();
        }
    }

    static long metadataInserts(Harness harness) {
        return harness.metrics().snapshot().bySql().values().stream()
                .filter(sql -> sql.category() == LegalJdbcMetricsSupport.Category.DML)
                .filter(sql -> sql.sql().toLowerCase(Locale.ROOT).contains("legal_aceptacion_metadatos"))
                .mapToLong(LegalJdbcMetricsSupport.SqlSnapshot::executions).sum();
    }

    static long failedMetadataInserts(Harness harness) {
        return harness.metrics().snapshot().bySql().values().stream()
                .filter(sql -> sql.category() == LegalJdbcMetricsSupport.Category.DML)
                .filter(sql -> sql.sql().toLowerCase(Locale.ROOT).contains("legal_aceptacion_metadatos"))
                .mapToLong(LegalJdbcMetricsSupport.SqlSnapshot::failures).sum();
    }

    static void noDml(Harness harness) {
        assertThat(harness.metrics().snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isZero();
    }

    /** Observes the database clock crossing the rounded one-nanosecond policy, without guessing a sleep. */
    static void awaitMinimalRetentionElapsed(Harness harness, UUID lotId) {
        long deadline = System.nanoTime() + Duration.ofSeconds(4).toNanos();
        do {
            Boolean elapsed = harness.jdbc().queryForObject("""
                    SELECT statement_timestamp() > aceptado_en + INTERVAL '1 microsecond'
                      FROM legal_aceptacion_lotes WHERE id=?
                    """, Boolean.class, lotId);
            if (Boolean.TRUE.equals(elapsed)) return;
        } while (System.nanoTime() < deadline);
        throw new AssertionError("El reloj PostgreSQL no superó la retención mínima del lote");
    }

    static byte[] nonce(int marker) {
        byte[] nonce = new byte[12]; nonce[0] = (byte) marker; nonce[11] = (byte) (marker >>> 8); return nonce;
    }

    record Graph(Lot lot, List<Act> acts) { }
    record Header(Instant capturedAt, Instant retainUntil, OffsetDateTime purgedAt) { }
    record CipherRow(String type, int keyVersion, byte[] nonce, byte[] ciphertext, byte[] tag,
                     Integer originalLength, OffsetDateTime tombstoneAt) { }

    private static final class ScriptedRandom extends SecureRandom {
        private final List<byte[]> nonces;
        private int index;
        private ScriptedRandom(byte[][] nonces) {
            this.nonces = Arrays.stream(nonces).map(byte[]::clone).toList();
        }
        @Override public void nextBytes(byte[] bytes) {
            assertThat(bytes).hasSize(12); assertThat(index).isLessThan(nonces.size());
            byte[] selected = nonces.get(index++); assertThat(selected).hasSize(12);
            System.arraycopy(selected, 0, bytes, 0, bytes.length);
        }
    }
}
