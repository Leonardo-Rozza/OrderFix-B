package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalActorSnapshot;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceSelection.ExistingAcceptance;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalIdempotencyCoordinatorITSupport.Harness;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalIdempotencyCoordinatorITSupport.LotWritten;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PerfilAgregadoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.HexFormat;
import java.util.UUID;

import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalIdempotencyCoordinatorITSupport.inTransaction;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalIdempotencyCoordinatorITSupport.key;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalIdempotencyCoordinatorITSupport.newLot;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalIdempotencyCoordinatorITSupport.writerBoundary;
import static org.assertj.core.api.Assertions.assertThat;

/** Only this disposable fixture can mutate historical sources; the reader uses the restricted V29 role. */
final class LegalAcceptanceEvidenceITSupport {
    private LegalAcceptanceEvidenceITSupport() { }

    /** I1's historical source reads extend only this disposable actor role, never the G2 fixture. */
    static void grantHistoricalSourceReads(LegalIdempotencyCoordinatorITSupport fixture) {
        fixture.database.requireEphemeral();
        for (String table : List.of("legal_publicaciones", "legal_publicacion_requisitos",
                "legal_publicacion_documentos", "legal_documento_contextos",
                "legal_requisito_transiciones", "legal_documento_transiciones")) {
            fixture.owner.execute("GRANT SELECT ON public." + table + " TO "
                    + LegalIdempotencyCoordinatorITSupport.ACCEPTOR);
        }
    }

    static LotWritten commitEvidence(Harness harness, LegalAcceptanceCommand command) {
        return inTransaction(harness, (status, remaining) -> {
            var lot = newLot(harness.jdbc(), command.actor(), PerfilAgregadoLegal.AUTHENTICATED_PENDING, command.acceptances());
            harness.jdbc().execute("SET CONSTRAINTS ALL IMMEDIATE");
            return lot;
        });
    }

    static List<ExistingAcceptance> read(Harness harness, LegalAcceptanceCommand command) {
        return inTransaction(harness, (status, remaining) -> {
            var reservation = harness.coordinator().reserve(command, key(), remaining);
            writerBoundary(harness.jdbc(), command.actor());
            harness.metrics().reset();
            return new LegalAcceptanceEvidenceReader(harness.jdbc()).read(reservation, command.actor());
        });
    }

    static void noEvidenceDmlOrPrivateMetadataRead(Harness harness) {
        var metrics = harness.metrics().snapshot();
        assertThat(metrics.executions(LegalJdbcMetricsSupport.Category.DML)).isZero();
        assertThat(metrics.bySql().keySet()).noneSatisfy(sql -> assertThat(sql).contains("FROM public.legal_aceptacion_metadatos"));
        assertThat(metrics.bySql().keySet()).noneSatisfy(sql -> assertThat(sql).contains("legal_requisito_conjuntos_actuales"));
    }

    static void corrupt(LegalIdempotencyCoordinatorITSupport fixture, String sql, Object... args) {
        fixture.database.requireEphemeral();
        LegalManifestPersistenceITSupport.withReplicaRole(fixture.owner, () -> assertThat(fixture.owner.update(sql, args)).isPositive());
    }

    static LegalEditorialITFixture.ImportedRelease seedMany(LegalIdempotencyCoordinatorITSupport fixture, Path directory, Class<?> anchor, int count) throws Exception {
        fixture.database.requireEphemeral();
        fixture.owner.execute("TRUNCATE legal_requisito_agregados, legal_publicaciones, legal_documento_reemplazo_lotes, talleres RESTART IDENTITY CASCADE");
        return seedCatalog(fixture.owner, directory, anchor, count);
    }

    private static LegalEditorialITFixture.ImportedRelease seedCatalog(JdbcTemplate owner, Path directory,
                                                                       Class<?> anchor, int count) throws Exception {
        assertThat(owner.queryForObject("SELECT current_database()", String.class)).startsWith("ordenfix_legal_v29_");
        var release = LegalManifestPersistenceITSupport.copyRelease(directory, anchor, "evidence-source", (path, manifest) -> {
            manifest.withArray("documents").forEach(document -> ((ObjectNode) document).put("effectiveAt", "2020-01-01T00:00:00-03:00"));
            for (int index = 1; index <= count; index++) {
                var requirement = manifest.withArray("requirements").addObject();
                requirement.put("key", "evidence-use-" + index); requirement.put("version", "1.0.0");
                requirement.put("context", "USO_CONTINUADO"); requirement.putArray("roles").add("ADMIN_TITULAR").add("USER");
                requirement.put("actType", "ACEPTACION"); String statement = "Confirmo el requisito histórico original " + index + ".";
                requirement.put("statement", statement); requirement.put("statementSha256", sha256(statement));
                requirement.putArray("documents").add("terminos"); requirement.put("required", true); requirement.put("requiresReacceptance", true);
            }
        });
        UUID publication = LegalV28AggregateITSupport.importRelease(owner.getDataSource(), release);
        LegalManifestPersistenceITSupport.promoteToReady(owner, publication);
        return new LegalEditorialITFixture.ImportedRelease(release, publication);
    }

    /** Two real sessions: the editorial transaction begins first, acceptance commits, then REPLACE commits. */
    static LotWritten replaceInEarlierTransaction(LegalIdempotencyCoordinatorITSupport fixture, Path directory,
            Class<?> anchor, LegalEditorialITFixture.ImportedRelease source, Harness harness,
            LegalAcceptanceCommand command) throws Exception {
        fixture.database.requireEphemeral();
        var budgets = LegalDatabaseBudgets.production();
        var apply = LegalManifestPersistenceITSupport.applyHarness(fixture.database.dataSource, budgets);
        var editorial = new LegalEditorialITFixture(directory, anchor, fixture.owner,
                LegalManifestPersistenceITSupport.harness(fixture.database.dataSource, budgets), apply);
        var sourceManifest = new ObjectMapper().readTree(directory.resolve("evidence-source/publication-manifest.json").toFile());
        var target = editorial.importedDraft("evidence-replacement", (path, manifest) -> {
            manifest.removeAll(); manifest.setAll((ObjectNode) sourceManifest.deepCopy()); manifest.put("publicationId", "evidence-replacement");
            for (var candidate : manifest.withArray("requirements")) {
                if (!"evidence-use-1".equals(candidate.path("key").asText())) continue;
                var requirement = (ObjectNode) candidate; String statement = "Acepto la nueva versión del requisito histórico.";
                requirement.put("version", "2.0.0"); requirement.put("statement", statement);
                requirement.put("statementSha256", sha256(statement)); requirement.put("requiresReacceptance", true);
            }
        });
        var plan = editorial.replacementPlan(source, target, "evidence-replace").plan();
        try (Connection connection = fixture.database.dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED); connection.setAutoCommit(false);
            var sameConnection = new SingleConnectionDataSource(connection, true);
            var jdbc = new JdbcTemplate(sameConnection);
            OffsetDateTime started = jdbc.queryForObject("SELECT transaction_timestamp()", OffsetDateTime.class);
            LotWritten written = commitEvidence(harness, command);
            OffsetDateTime accepted = fixture.owner.queryForObject("SELECT aceptado_en FROM legal_aceptacion_lotes WHERE id=?", OffsetDateTime.class, written.lotId());
            assertThat(accepted.toInstant()).isAfter(started.toInstant());
            var result = LegalManifestPersistenceITSupport.applyHarness(sameConnection, budgets).service().applyReplace(target.release(), plan);
            assertThat(result.persisted()).as("issues=%s", result.issues()).isTrue();
            OffsetDateTime changed = fixture.owner.queryForObject("""
                    SELECT v.estado_cambiado_en FROM legal_requisito_versiones v
                      JOIN legal_requisito_lineas l ON l.id=v.requisito_linea_id
                     WHERE l.clave='evidence-use-1' AND v.version='1.0.0' AND v.estado='REEMPLAZADA'
                    """, OffsetDateTime.class);
            assertThat(changed).isEqualTo(started);
            assertThat(changed.toInstant()).isBefore(accepted.toInstant());
            return written;
        }
    }

    /** Genuine V27 evidence, created with enabled guards before V28/V29 are applied. */
    static LegacySeed seedLegacy(PostgreSQLContainer postgres, Path directory, Class<?> anchor) throws Exception {
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var owner = new JdbcTemplate(source);
        org.flywaydb.core.Flyway.configure().dataSource(source).locations("classpath:db/migration").target("27").load().migrate();
        UUID lot = UUID.randomUUID();
        List<UUID> acceptanceIds = new ArrayList<>();
        LegalActorSnapshot actor;
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            OffsetDateTime started = jdbc.queryForObject("SELECT transaction_timestamp()", OffsetDateTime.class);
            seedCatalog(owner, directory, anchor, 2);
            var original = LegalAcceptanceProtocolFeasibilityITSupport.insertActor(owner);
            actor = new LegalActorSnapshot(original.userId(), original.workshopId(), UserRole.USER, 0, true, true);
            String revision = jdbc.queryForObject("""
                    SELECT c.required_set_revision FROM legal_requisito_conjuntos_actuales p
                      JOIN legal_requisito_conjuntos c ON c.id=p.conjunto_id
                     WHERE p.contexto='USO_CONTINUADO' AND p.locale='es-AR' AND p.audiencia='USER'
                    """, String.class);
            assertThat(jdbc.update("""
                    INSERT INTO legal_aceptacion_lotes(id,user_id,taller_id,rol_wire,audiencia,required_set_revision,aceptado_en)
                    VALUES(?,?,?,'USER','USER',?,statement_timestamp())
                    """, lot, actor.userId(), actor.tallerId(), revision)).isOne();
            List<UUID> requirements = jdbc.queryForList("""
                    SELECT m.requisito_version_id FROM legal_requisito_conjuntos_actuales p
                      JOIN legal_requisito_conjunto_miembros m ON m.conjunto_id=p.conjunto_id
                     WHERE p.contexto='USO_CONTINUADO' AND p.locale='es-AR' AND p.audiencia='USER'
                     ORDER BY m.manifest_ordinal
                    """, UUID.class);
            for (UUID requirement : requirements) {
                UUID acceptance = UUID.randomUUID(); acceptanceIds.add(acceptance);
                assertThat(jdbc.update("""
                        INSERT INTO legal_aceptaciones
                          (id,lote_id,user_id,taller_id,requisito_version_id,requisito_clave,requisito_version,contexto,tipo_acto,afirmacion,afirmacion_sha256,requerido)
                        SELECT ?,?,?,?,v.id,l.clave,v.version,l.contexto,l.tipo_acto,v.afirmacion,v.afirmacion_sha256,v.requerido
                          FROM legal_requisito_versiones v JOIN legal_requisito_lineas l ON l.id=v.requisito_linea_id WHERE v.id=?
                        """, acceptance, lot, actor.userId(), actor.tallerId(), requirement)).isOne();
                assertThat(jdbc.update("""
                        INSERT INTO legal_aceptacion_documentos
                          (aceptacion_id,documento_ordinal,documento_version_id,documento_clave,tipo,version,titulo,sha256)
                        SELECT ?,r.documento_ordinal,v.id,l.clave,l.tipo,v.version,v.titulo,v.sha256
                          FROM legal_requisito_documentos r JOIN legal_documento_versiones v ON v.id=r.documento_version_id
                          JOIN legal_documento_lineas l ON l.id=v.documento_linea_id WHERE r.requisito_version_id=?
                        """, acceptance, requirement)).isPositive();
            }
            assertThat(jdbc.update("""
                    INSERT INTO legal_aceptacion_metadatos(lote_id,capturado_en,retener_hasta)
                    VALUES(?,statement_timestamp(),statement_timestamp()+INTERVAL '30 days')
                    """, lot)).isOne();
            assertThat(jdbc.update("""
                    INSERT INTO legal_aceptacion_metadatos_cifrados(lote_id,tipo,key_version,nonce,ciphertext,tag,longitud_original)
                    VALUES(?,'IP',1,?,?,?,9)
                    """, lot, new byte[12], new byte[9], new byte[16])).isOne();
            assertThat(jdbc.queryForObject("SELECT aceptado_en FROM legal_aceptacion_lotes WHERE id=?", OffsetDateTime.class, lot))
                    .isEqualTo(started);
            assertThat(started.toInstant()).isBefore(jdbc.queryForObject("""
                    SELECT min(t.ocurrido_en) FROM legal_requisito_transiciones t
                      JOIN legal_requisito_versiones v ON v.id=t.requisito_version_id
                      JOIN legal_requisito_lineas l ON l.id=v.requisito_linea_id
                     WHERE l.contexto='USO_CONTINUADO' AND t.estado_nuevo='VIGENTE'
                    """, OffsetDateTime.class).toInstant());
            jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE"); connection.commit();
        }
        return new LegacySeed(actor, lot, List.copyOf(acceptanceIds));
    }

    record LegacySeed(LegalActorSnapshot actor, UUID lotId, List<UUID> acceptanceIds) { }

    private static String sha256(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException failure) { throw new AssertionError(failure); }
    }
}
