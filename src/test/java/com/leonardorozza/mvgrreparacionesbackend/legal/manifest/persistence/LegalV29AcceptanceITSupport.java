package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PerfilAgregadoLegal;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceProtocolFeasibilityITSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Disposable SQL capability fixture. Reuses the frozen 15A row writers, never its roles or guards.
 * Synthetic password/HMAC/ciphertext prove SQL constraints, not application writer parity.
 */
final class LegalV29AcceptanceITSupport {
    static final String PARENT = "legal_idempotencia_sin_actos";
    static final String REFERENCES = "legal_idempotencia_sin_actos_referencias";
    static final String ROUTE = "/api/aceptaciones-legales";
    static final List<String> SEQUENCES = List.of("talleres_id_seq", "suscripciones_id_seq", "users_id_seq",
            "legal_aceptacion_documentos_id_seq", "legal_aceptacion_metadatos_cifrados_id_seq",
            "legal_idempotencia_resultados_id_seq");
    static final List<String> TABLES = java.util.stream.Stream.concat(DURABLE_TABLES.stream(),
            java.util.stream.Stream.of(PARENT, REFERENCES)).toList();

    final PostgreSQLContainer postgres;
    final DataSource dataSource;
    final JdbcTemplate owner;

    LegalV29AcceptanceITSupport(PostgreSQLContainer postgres) {
        this.postgres = postgres;
        dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        owner = new JdbcTemplate(dataSource);
    }

    void migrate(String target) {
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target(target).load().migrate();
    }

    void seedCatalog(Path directory, Class<?> source) throws Exception {
        var release = LegalManifestPersistenceITSupport.copyRelease(directory, source, "v29-acceptance",
                (path, manifest) -> {
                    manifest.withArray("documents").forEach(document ->
                            ((com.fasterxml.jackson.databind.node.ObjectNode) document)
                                    .put("effectiveAt", "2020-01-01T00:00:00-03:00"));
                    for (int i = 1; i <= 2; i++) {
                        var requirement = manifest.withArray("requirements").addObject();
                        requirement.put("key", "v29-continued-use-" + i);
                        requirement.put("version", "1.0.0");
                        requirement.put("context", "USO_CONTINUADO");
                        requirement.putArray("roles").add("ADMIN_TITULAR").add("USER");
                        requirement.put("actType", "ACEPTACION");
                        String statement = "Confirmo el requisito V29 de uso continuado " + i + ".";
                        requirement.put("statement", statement);
                        try {
                            requirement.put("statementSha256", java.util.HexFormat.of().formatHex(
                                    java.security.MessageDigest.getInstance("SHA-256")
                                            .digest(statement.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
                        } catch (java.security.NoSuchAlgorithmException impossible) {
                            throw new IllegalStateException(impossible);
                        }
                        requirement.putArray("documents").add("terminos");
                        requirement.put("required", true);
                        requirement.put("requiresReacceptance", true);
                    }
                });
        UUID publication = LegalV28AggregateITSupport.importRelease(dataSource, release);
        LegalManifestPersistenceITSupport.promoteToReady(owner, publication);
    }

    Map<String, Long> counts() {
        Map<String, Long> result = new LinkedHashMap<>();
        for (String table : TABLES) result.put(table, owner.queryForObject("SELECT count(*) FROM " + table, Long.class));
        return Map.copyOf(result);
    }

    Map<String, List<String>> durableRows() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String table : TABLES) result.put(table, owner.queryForList(
                "SELECT to_jsonb(t)::text || '|xmin=' || xmin::text FROM " + table + " t ORDER BY 1", String.class));
        return Map.copyOf(result);
    }

    static Tuple tuple() { return new Tuple("ACEPTACION_LEGAL", ROUTE, hex(), hex()); }
    static String hex() { return UUID.randomUUID().toString().replace("-", "").repeat(2); }
    static String revision() { return "sha256:" + hex(); }

    static long lockKey(JdbcTemplate jdbc, Tuple tuple) {
        return Objects.requireNonNull(jdbc.queryForObject("""
                SELECT pg_catalog.hashtextextended(pg_catalog.jsonb_build_array(
                  'ordenfix:legal-idempotencia:tupla:v29', ?::text, ?::text, ?::text, ?::text)::text, 0)
                """, Long.class, tuple.operation(), tuple.route(), tuple.scope(), tuple.key()));
    }

    static void lock(JdbcTemplate jdbc, Tuple tuple) {
        jdbc.queryForList("SELECT pg_catalog.pg_advisory_xact_lock(?)", lockKey(jdbc, tuple));
    }

    static LegalRequiredSetAggregateReceipt aggregate(JdbcTemplate jdbc, Actor actor) {
        return materialize(jdbc, PerfilAgregadoLegal.AUTHENTICATED_PENDING,
                AudienciaLegal.valueOf(actor.audience()));
    }

    static Written acceptance(JdbcTemplate jdbc, Actor actor, Tuple tuple) {
        lock(jdbc, tuple);
        return acceptanceWithLock(jdbc, actor, tuple);
    }

    static Written acceptanceWithLock(JdbcTemplate jdbc, Actor actor, Tuple tuple) {
        var aggregate = aggregate(jdbc, actor);
        var lot = insertLot(jdbc, actor, aggregate);
        List<Act> acts = insertActs(jdbc, lot);
        int documents = insertDocuments(jdbc, acts);
        insertMetadata(jdbc, lot);
        long ledger = ledger(jdbc, lot, tuple);
        return new Written(lot, acts, documents, ledger);
    }

    static Written singleActAcceptance(JdbcTemplate jdbc, Actor actor, Tuple tuple, int ordinal) {
        lock(jdbc, tuple);
        var aggregate = aggregate(jdbc, actor);
        var lot = insertLot(jdbc, actor, aggregate);
        UUID requirement = jdbc.queryForList("""
                SELECT member.requisito_version_id FROM legal_requisito_agregado_scopes scope
                JOIN legal_requisito_conjunto_miembros member ON member.conjunto_id = scope.conjunto_id
                WHERE scope.agregado_id = ? ORDER BY scope.scope_ordinal, member.manifest_ordinal
                """, UUID.class, aggregate.aggregateId()).get(ordinal);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO legal_aceptaciones
                (id,lote_id,user_id,taller_id,requisito_version_id,requisito_clave,requisito_version,contexto,tipo_acto,afirmacion,afirmacion_sha256,requerido)
                SELECT ?,?,?,?,v.id,l.clave,v.version,l.contexto,l.tipo_acto,v.afirmacion,v.afirmacion_sha256,v.requerido
                FROM legal_requisito_versiones v JOIN legal_requisito_lineas l ON l.id=v.requisito_linea_id WHERE v.id=?
                """, id, lot.id(), actor.userId(), actor.workshopId(), requirement);
        List<Act> acts = List.of(new Act(id, requirement));
        int documents = insertDocuments(jdbc, acts);
        insertMetadata(jdbc, lot);
        return new Written(lot, acts, documents, ledger(jdbc, lot, tuple));
    }

    static long ledger(JdbcTemplate jdbc, Lot lot, Tuple tuple) {
        return Objects.requireNonNull(jdbc.queryForObject("""
                INSERT INTO legal_idempotencia_resultados
                  (operacion, route_template, scope_hmac, idempotency_key_hmac, fingerprint_hmac,
                   hmac_key_version, user_id, taller_id, lote_id, completed_at, expires_at)
                VALUES (?, ?, ?, ?, ?, 1, ?, ?, ?, statement_timestamp(),
                        statement_timestamp() + INTERVAL '25 hours') RETURNING id
                """, Long.class, tuple.operation(), tuple.route(), tuple.scope(), tuple.key(), hex(),
                lot.actor().userId(), lot.actor().workshopId(), lot.id()));
    }

    static UUID result(JdbcTemplate jdbc, Actor actor, LegalRequiredSetAggregateReceipt aggregate,
                       Tuple tuple, String kind, String submitted, int references) {
        return result(jdbc, actor, aggregate, tuple, kind, submitted, references, 1);
    }

    static UUID result(JdbcTemplate jdbc, Actor actor, LegalRequiredSetAggregateReceipt aggregate,
                       Tuple tuple, String kind, String submitted, int references, int keyVersion) {
        UUID id = UUID.randomUUID();
        assertThat(jdbc.update("""
                INSERT INTO legal_idempotencia_sin_actos
                  (id, operacion, route_template, scope_hmac, idempotency_key_hmac, fingerprint_hmac,
                   hmac_key_version, user_id, taller_id, rol_wire, audiencia, resultado, submitted_revision,
                   agregado_observado_id, perfil, revision_scheme, observed_revision, referencia_count,
                   completed_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'AUTHENTICATED_PENDING',
                        'AGGREGATE_V1', ?, ?, '1970-01-01T00:00:00Z', statement_timestamp() + INTERVAL '25 hours')
                """, id, tuple.operation(), tuple.route(), tuple.scope(), tuple.key(), hex(), keyVersion,
                actor.userId(), actor.workshopId(), actor.role(), actor.audience(), kind, submitted,
                aggregate.aggregateId(), aggregate.requiredSetRevision(), references)).isOne();
        return id;
    }

    static void reference(JdbcTemplate jdbc, UUID result, UUID act, Actor actor) {
        assertThat(jdbc.update("""
                INSERT INTO legal_idempotencia_sin_actos_referencias
                  (resultado_id, aceptacion_id, user_id, taller_id) VALUES (?, ?, ?, ?)
                """, result, act, actor.userId(), actor.workshopId())).isOne();
    }

    Written committedAcceptance(Actor actor) throws Exception {
        try (Connection connection = transaction(dataSource)) {
            Written result = acceptance(jdbc(connection), actor, tuple());
            connection.commit();
            return result;
        }
    }

    UUID committedEmpty(Actor actor, Tuple tuple) throws Exception {
        try (Connection connection = transaction(dataSource)) {
            JdbcTemplate jdbc = jdbc(connection);
            lock(jdbc, tuple);
            var aggregate = aggregate(jdbc, actor);
            UUID id = result(jdbc, actor, aggregate, tuple, "EMPTY", aggregate.requiredSetRevision(), 0);
            connection.commit();
            return id;
        }
    }

    UUID committedDedup(Actor actor, Tuple tuple, Written evidence) throws Exception {
        try (Connection connection = transaction(dataSource)) {
            JdbcTemplate jdbc = jdbc(connection);
            lock(jdbc, tuple);
            var aggregate = aggregate(jdbc, actor);
            UUID id = result(jdbc, actor, aggregate, tuple, "DEDUP", revision(), evidence.acts().size());
            evidence.acts().forEach(act -> reference(jdbc, id, act.id(), actor));
            connection.commit();
            return id;
        }
    }

    /** Emulates elapsed retention only in this disposable database; never used for upgrade history. */
    void expire(String table, Object id) throws Exception {
        requireEphemeral();
        if (!List.of(PARENT, "legal_idempotencia_resultados").contains(table)) throw new IllegalArgumentException();
        try (Connection connection = transaction(dataSource)) {
            JdbcTemplate jdbc = jdbc(connection);
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update("UPDATE " + table + " SET completed_at = statement_timestamp() - INTERVAL '50 hours', "
                    + "expires_at = statement_timestamp() - INTERVAL '25 hours' WHERE id = ?", id);
            connection.commit();
        }
    }

    void requireEphemeral() {
        assertThat(owner.queryForObject("SELECT current_database()", String.class)).startsWith("ordenfix_legal_v29_");
    }

    DataSource provision(String role, boolean registration, boolean maintenance) {
        requireEphemeral();
        if (!role.matches("ordenfix_v29_[a-z_]+")) throw new IllegalArgumentException("fixture role");
        assertThat(owner.queryForObject("SELECT count(*) FROM pg_roles WHERE rolname = ?", Integer.class, role)).isZero();
        owner.execute("CREATE ROLE " + role + " LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE"
                + " NOREPLICATION NOBYPASSRLS PASSWORD 'disposable-v29-capability' ");
        owner.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
        owner.execute("REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA public FROM PUBLIC");
        owner.execute("REVOKE TEMPORARY ON DATABASE " + postgres.getDatabaseName() + " FROM PUBLIC");
        owner.execute("GRANT CONNECT ON DATABASE " + postgres.getDatabaseName() + " TO " + role);
        owner.execute("GRANT USAGE ON SCHEMA public TO " + role);
        owner.execute("ALTER ROLE " + role + " SET search_path TO pg_catalog, public, pg_temp");
        for (String signature : LegalV28AggregatePrivilegeVerifier.SESSION_ADVISORY_LOCK_FUNCTIONS) {
            owner.execute("REVOKE EXECUTE ON FUNCTION pg_catalog." + signature + " FROM PUBLIC, " + role);
        }
        for (String signature : LegalV28AggregatePrivilegeVerifier.LARGE_OBJECT_CREATION_FUNCTIONS) {
            owner.execute("REVOKE EXECUTE ON FUNCTION pg_catalog." + signature + " FROM PUBLIC, " + role);
        }
        if (maintenance) {
            // Capability for result retention only; personal metadata tombstoning is covered in 15O.
            if (registration) throw new IllegalArgumentException("separate consumer roles");
            owner.execute("GRANT SELECT, DELETE ON " + PARENT + ", " + REFERENCES + ", legal_idempotencia_resultados TO " + role);
            owner.execute("GRANT UPDATE (id) ON " + PARENT + " TO " + role);
            for (String signature : List.of("legal_exigir_read_committed()", "legal_fila_es_transaccion_actual(xid)",
                    "legal_exigir_lock_idempotente_v29(character varying,character varying,character varying,character varying)",
                    "legal_idempotencia_tupla_guard_v29()", "legal_validar_idempotencia_sin_actos_v29(uuid)",
                    "legal_idempotencia_sin_actos_constraint_guard_v29()", "legal_idempotencia_sin_actos_mutation_guard_v29()",
                    "legal_idempotencia_sin_actos_ref_delete_guard_v29()", "legal_idempotencia_update_delete_guard()"))
                owner.execute("GRANT EXECUTE ON FUNCTION " + signature + " TO " + role);
            assertNoSequenceUsage(role);
            return new DriverManagerDataSource(postgres.getJdbcUrl(), role, "disposable-v29-capability");
        }
        owner.execute("GRANT SELECT (id, taller_id, role, active, token_version) ON users TO " + role);
        owner.execute("GRANT SELECT (id, activo) ON talleres TO " + role);
        for (String table : List.of("legal_requisito_conjuntos_actuales", "legal_requisito_conjuntos",
                "legal_requisito_conjunto_miembros", "legal_requisito_agregados", "legal_requisito_agregado_scopes",
                "legal_requisito_lineas", "legal_requisito_versiones", "legal_requisito_audiencias",
                "legal_requisito_documentos", "legal_documento_lineas", "legal_documento_versiones",
                "legal_aceptacion_lotes", "legal_aceptaciones", "legal_aceptacion_documentos",
                "legal_aceptacion_metadatos", "legal_idempotencia_resultados",
                PARENT, REFERENCES)) owner.execute("GRANT SELECT ON " + table + " TO " + role);
        owner.execute("GRANT SELECT (lote_id,tipo,tombstone_en) ON legal_aceptacion_metadatos_cifrados TO " + role);
        for (String table : List.of("legal_requisito_agregados", "legal_requisito_agregado_scopes",
                "legal_aceptacion_lotes", "legal_aceptaciones", "legal_aceptacion_documentos",
                "legal_aceptacion_metadatos", "legal_aceptacion_metadatos_cifrados", "legal_idempotencia_resultados",
                PARENT, REFERENCES)) owner.execute("GRANT INSERT ON " + table + " TO " + role);
        for (String table : List.of("users", "talleres", "legal_requisito_agregados", "legal_aceptacion_lotes",
                "legal_aceptaciones", PARENT)) owner.execute("GRANT UPDATE (id) ON " + table + " TO " + role);
        owner.execute("GRANT UPDATE (lote_id) ON legal_aceptacion_metadatos TO " + role);
        owner.execute("GRANT UPDATE (conjunto_id) ON legal_requisito_conjuntos_actuales TO " + role);
        for (String signature : List.of("legal_rechazar_update_delete()", "legal_exigir_read_committed()",
                "legal_fila_es_transaccion_actual(xid)", "legal_exigir_lock_editorial_v28()",
                "legal_requisito_agregado_insert_guard()", "legal_requisito_agregado_scope_insert_guard()",
                "legal_validar_requisito_agregado(uuid)", "legal_requisito_agregado_constraint_guard()",
                "legal_validar_requisito_agregado_actual(uuid)", "legal_aceptacion_lote_insert_guard()",
                "legal_aceptacion_insert_guard()", "legal_aceptacion_documento_insert_guard()",
                "legal_metadata_header_insert_guard()", "legal_metadata_cifrada_insert_guard()",
                "legal_validar_aceptacion(uuid)", "legal_validar_lote_aceptacion(uuid)",
                "legal_aceptacion_constraint_guard()", "legal_aceptacion_agregado_constraint_guard()",
                "legal_idempotencia_insert_guard()", "legal_idempotencia_update_delete_guard()",
                "legal_exigir_lock_idempotente_v29(character varying,character varying,character varying,character varying)",
                "legal_idempotencia_tupla_guard_v29()", "legal_idempotencia_sin_actos_insert_guard_v29()",
                "legal_idempotencia_sin_actos_ref_insert_guard_v29()", "legal_validar_idempotencia_sin_actos_v29(uuid)",
                "legal_idempotencia_sin_actos_constraint_guard_v29()", "legal_idempotencia_sin_actos_mutation_guard_v29()",
                "legal_idempotencia_sin_actos_ref_delete_guard_v29()", "legal_idempotencia_sin_actos_ref_update_guard_v29()",
                "legal_rechazar_update_identidad_cuenta_v29()")) {
            owner.execute("GRANT EXECUTE ON FUNCTION " + signature + " TO " + role);
        }
        if (registration) {
            owner.execute("GRANT INSERT (nombre,email_contacto,telefono,activo,created_at,updated_at) ON talleres TO " + role);
            owner.execute("GRANT INSERT (taller_id,plan,estado,fecha_inicio,fecha_fin_trial,created_at,updated_at) ON suscripciones TO " + role);
            owner.execute("GRANT INSERT (username,password,email,role,taller_id,active,email_verificado,token_version) ON users TO " + role);
        }
        assertNoSequenceUsage(role);
        return new DriverManagerDataSource(postgres.getJdbcUrl(), role, "disposable-v29-capability");
    }

    private void assertNoSequenceUsage(String role) {
        for (String sequence : SEQUENCES) assertThat(owner.queryForObject(
                "SELECT has_sequence_privilege(?, ?, 'USAGE')", Boolean.class, role, sequence)).isFalse();
    }

    static Written registration(JdbcTemplate jdbc, Tuple tuple) {
        lock(jdbc, tuple);
        String unique = UUID.randomUUID().toString();
        long workshop = Objects.requireNonNull(jdbc.queryForObject("""
                INSERT INTO talleres (nombre,email_contacto,telefono,activo,created_at,updated_at)
                VALUES (?, ?, '1100000000', true, localtimestamp, localtimestamp) RETURNING id
                """, Long.class, "V29 fixture " + unique, unique + "@ordenfix.test"));
        jdbc.update("""
                INSERT INTO suscripciones (taller_id,plan,estado,fecha_inicio,fecha_fin_trial,created_at,updated_at)
                VALUES (?, 'FREE','TRIAL', ?, ?, localtimestamp, localtimestamp)
                """, workshop, LocalDate.of(2026, 9, 6), LocalDate.of(2026, 9, 20));
        long user = Objects.requireNonNull(jdbc.queryForObject("""
                INSERT INTO users (username,password,email,role,taller_id,active,email_verificado,token_version)
                VALUES (?, 'synthetic-hash', ?, 'ADMIN', ?, true, false, 0) RETURNING id
                """, Long.class, "v29-" + unique, unique + "@ordenfix.test", workshop));
        Actor actor = new Actor(user, workshop, "ADMIN", "ADMIN_TITULAR");
        var aggregate = materialize(jdbc, PerfilAgregadoLegal.REGISTRATION, AudienciaLegal.ADMIN_TITULAR);
        var lot = insertLot(jdbc, actor, aggregate);
        var acts = insertActs(jdbc, lot);
        int documents = insertDocuments(jdbc, acts);
        insertMetadata(jdbc, lot);
        return new Written(lot, acts, documents, ledger(jdbc, lot, tuple));
    }

    static void sqlState(Throwable failure, String state) {
        assertThat(failure).isNotNull();
        Throwable current = failure;
        while (current != null && !(current instanceof SQLException)) current = current.getCause();
        assertThat(current).isInstanceOf(SQLException.class);
        assertThat(((SQLException) current).getSQLState()).isEqualTo(state);
    }

    record Tuple(String operation, String route, String scope, String key) { }
}
