package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalApplicableScopeResolver;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateProvenanceCalculator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateRevisionCalculator;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PerfilAgregadoLegal;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceProtocolFeasibilityITSupport.*;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalV29AcceptanceITSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

@Testcontainers
class LegalV29AcceptanceUpgradeIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_v29_upgrade").withUsername("ordenfix").withPassword("ordenfix");

    @Test void genuineScopeAndAggregateHistorySurviveUpgradeWithoutBackfillOrRewriting() throws Exception {
        var fixture = new LegalV29AcceptanceITSupport(POSTGRES);
        fixture.migrate("27");
        var legacy = new LegalV27AcceptanceHistoryFixture(fixture.dataSource, "public");
        var seed = legacy.seed();
        var originalLegacy = legacy.capture(seed);
        fixture.migrate("28");
        assertThat(legacy.capture(seed)).isEqualTo(originalLegacy);

        HistoricalAggregate history = insertV28History(fixture, seed);
        var before = legacyTables(fixture.owner);
        assertThat(fixture.owner.queryForList("SELECT revision_scheme FROM legal_aceptacion_lotes ORDER BY revision_scheme", String.class))
                .containsExactly("AGGREGATE_V1", "SCOPE_V1");
        assertThat(fixture.owner.queryForObject("SELECT count(*) FROM legal_idempotencia_resultados", Long.class)).isEqualTo(2L);
        fixture.migrate("29");

        assertThat(legacyTables(fixture.owner)).as("row bytes, IDs, timestamps and xmin across V28 to V29").isEqualTo(before);
        assertThat(legacy.capture(seed)).isEqualTo(originalLegacy);
        assertThat(fixture.owner.queryForObject("SELECT count(*) FROM legal_idempotencia_sin_actos", Long.class)).isZero();
        assertThat(fixture.owner.queryForObject("SELECT count(*) FROM legal_idempotencia_sin_actos_referencias", Long.class)).isZero();
        assertThat(fixture.owner.queryForObject("SELECT checksum FROM flyway_schema_history WHERE version='27' AND success", Integer.class))
                .isEqualTo(1_575_269_868);
        assertThat(fixture.owner.queryForObject("SELECT checksum FROM flyway_schema_history WHERE version='28' AND success", Integer.class))
                .isEqualTo(1_900_377_028);

        Tuple tuple = tuple();
        UUID result = UUID.randomUUID();
        try (Connection connection = transaction(fixture.dataSource)) {
            JdbcTemplate jdbc = jdbc(connection); lock(jdbc, tuple); sharedBoundary(jdbc);
            assertThat(jdbc.update("""
                    INSERT INTO legal_idempotencia_sin_actos
                    (id,operacion,route_template,scope_hmac,idempotency_key_hmac,fingerprint_hmac,hmac_key_version,
                     user_id,taller_id,rol_wire,audiencia,resultado,submitted_revision,agregado_observado_id,
                     perfil,revision_scheme,observed_revision,referencia_count,completed_at,expires_at)
                    SELECT ?,?,?,?,?,?,1,?,?,'ADMIN','ADMIN_TITULAR','DEDUP',?,id,perfil,revision_scheme,
                           required_set_revision,1,statement_timestamp(),statement_timestamp()+INTERVAL '25 hours'
                    FROM legal_requisito_agregados WHERE id = ?
                    """, result, tuple.operation(), tuple.route(), tuple.scope(), tuple.key(), hex(),
                    history.actor().userId(), history.actor().workshopId(), revision(), history.aggregate())).isOne();
            reference(jdbc, result, history.act(), history.actor());
            connection.commit();
        }
        assertThat(legacyTables(fixture.owner)).isEqualTo(before);
        try (Connection connection = transaction(fixture.dataSource)) {
            Throwable failure = catchThrowable(() -> jdbc(connection).update(
                    "DELETE FROM legal_idempotencia_sin_actos WHERE id = ?", result));
            sqlState(failure, "55000");
            connection.rollback();
        }
    }

    private static java.util.Map<String, List<String>> legacyTables(JdbcTemplate jdbc) {
        var rows = new java.util.LinkedHashMap<String, List<String>>();
        for (String table : DURABLE_TABLES) rows.put(table, jdbc.queryForList(
                "SELECT to_jsonb(t)::text || '|xmin=' || xmin::text FROM " + table + " t ORDER BY 1", String.class));
        return java.util.Map.copyOf(rows);
    }

    private static HistoricalAggregate insertV28History(LegalV29AcceptanceITSupport fixture,
            LegalV27AcceptanceHistoryFixture.SeededHistory seed) throws Exception {
        assertThat(fixture.owner.queryForObject("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1", String.class))
                .isEqualTo("28");
        try (Connection connection = transaction(fixture.dataSource)) {
            JdbcTemplate jdbc = jdbc(connection);
            var boundary = sharedBoundary(jdbc);
            // Historical multi-context fixture, with semantic revision and provenance calculated
            // from the three genuine V27 pointers. This does not expand the production policy.
            var scopeSet = new LegalApplicableScopeResolver((profile, audience) -> List.of(
                    ContextoLegal.USO_CONTINUADO, ContextoLegal.ATESTACION_FOTOS, ContextoLegal.CIERRE_CUENTA))
                    .resolve(PerfilAgregadoLegal.AUTHENTICATED_PENDING, LocaleLegal.ES_AR, AudienciaLegal.ADMIN_TITULAR);
            var receipt = new LegalRequiredSetAggregateStore(jdbc, new LegalRequiredSetAggregateRevisionCalculator(),
                    new LegalRequiredSetAggregateProvenanceCalculator()).materialize(scopeSet, boundary);
            UUID aggregate = receipt.aggregateId();
            String revision = receipt.requiredSetRevision();
            long workshop = Objects.requireNonNull(jdbc.queryForObject("INSERT INTO talleres(nombre) VALUES ('V29 historical aggregate') RETURNING id", Long.class));
            long user = Objects.requireNonNull(jdbc.queryForObject("""
                    INSERT INTO users(username,password,email,role,taller_id)
                    VALUES ('v29-history-admin','synthetic-hash','v29-history@ordenfix.test','ADMIN',?) RETURNING id
                    """, Long.class, workshop));
            Actor actor = new Actor(user, workshop, "ADMIN", "ADMIN_TITULAR");
            UUID lot = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO legal_aceptacion_lotes
                      (id,user_id,taller_id,rol_wire,audiencia,required_set_revision,aceptado_en,revision_scheme,perfil,agregado_id)
                    VALUES (?,?,?,'ADMIN','ADMIN_TITULAR',?,statement_timestamp(),'AGGREGATE_V1','AUTHENTICATED_PENDING',?)
                    """, lot, user, workshop, revision, aggregate);
            UUID act = UUID.randomUUID();
            UUID requirement = seed.requirementIds().get("USO_CONTINUADO");
            jdbc.update("""
                    INSERT INTO legal_aceptaciones
                    (id,lote_id,user_id,taller_id,requisito_version_id,requisito_clave,requisito_version,contexto,tipo_acto,afirmacion,afirmacion_sha256,requerido)
                    SELECT ?,?,?,?,v.id,l.clave,v.version,l.contexto,l.tipo_acto,v.afirmacion,v.afirmacion_sha256,v.requerido
                    FROM legal_requisito_versiones v JOIN legal_requisito_lineas l ON l.id=v.requisito_linea_id WHERE v.id=?
                    """, act, lot, user, workshop, requirement);
            insertDocuments(jdbc, List.of(new Act(act, requirement)));
            jdbc.update("INSERT INTO legal_aceptacion_metadatos(lote_id,capturado_en,retener_hasta) VALUES (?,statement_timestamp(),statement_timestamp()+INTERVAL '30 days')", lot);
            jdbc.update("""
                    INSERT INTO legal_aceptacion_metadatos_cifrados(lote_id,tipo,key_version,nonce,ciphertext,tag,longitud_original)
                    VALUES (?,'IP',1,?,?,?,9)
                    """, lot, new byte[12], new byte[9], new byte[16]);
            Tuple tuple = tuple();
            jdbc.update("""
                    INSERT INTO legal_idempotencia_resultados
                    (operacion,route_template,scope_hmac,idempotency_key_hmac,fingerprint_hmac,hmac_key_version,user_id,taller_id,lote_id,completed_at,expires_at)
                    VALUES (?,?,?,?,?,1,?,?,?,statement_timestamp(),statement_timestamp()+INTERVAL '25 hours')
                    """, tuple.operation(), tuple.route(), tuple.scope(), tuple.key(), hex(), user, workshop, lot);
            connection.commit();
            return new HistoricalAggregate(actor, aggregate, act);
        }
    }

    private record HistoricalAggregate(Actor actor, UUID aggregate, UUID act) { }
}
