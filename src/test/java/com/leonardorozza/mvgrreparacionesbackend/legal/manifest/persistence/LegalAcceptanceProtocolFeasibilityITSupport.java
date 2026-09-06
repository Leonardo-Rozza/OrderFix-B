package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalApplicableScopeResolver;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateProvenanceCalculator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateRevisionCalculator;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PerfilAgregadoLegal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test-only V28 protocol fixture. No production role or acceptance service is provisioned.
 * Nominal grants characterize the invoker guards; this is not an actor/tenant authorization API.
 * Synthetic password/HMAC/ciphertext, fixed dates and omitted application audit callbacks mean
 * the JDBC registration fixture proves atomic SQL feasibility, not complete writer parity.
 */
final class LegalAcceptanceProtocolFeasibilityITSupport {

    static final String DATABASE = "ordenfix_legal_acceptance_protocol_15a";
    static final String SELECT_ACTOR_ROLE = "ordenfix_legal_15a_select_actor";
    static final String UNSAFE_ACTOR_ROLE = "ordenfix_legal_15a_unsafe_actor";
    static final String ACCEPTOR_ROLE = "ordenfix_legal_15a_acceptor";
    static final String REGISTRATION_ROLE = "ordenfix_legal_15a_registration";
    private static final String PASSWORD = "ephemeral-legal-15a-only";
    private static final String GUARD = "ordenfix_15a_fixture_reject_identity_update";

    private static final List<String> READ_TABLES = List.of(
            "users", "talleres",
            "legal_requisito_conjuntos_actuales", "legal_requisito_conjuntos",
            "legal_requisito_conjunto_miembros", "legal_requisito_agregados",
            "legal_requisito_agregado_scopes", "legal_requisito_lineas",
            "legal_requisito_versiones", "legal_requisito_audiencias",
            "legal_requisito_documentos", "legal_documento_lineas", "legal_documento_versiones",
            "legal_aceptacion_lotes", "legal_aceptaciones", "legal_aceptacion_documentos",
            "legal_aceptacion_metadatos", "legal_aceptacion_metadatos_cifrados",
            "legal_idempotencia_resultados");
    private static final List<String> INSERT_TABLES = List.of(
            "legal_requisito_agregados", "legal_requisito_agregado_scopes",
            "legal_aceptacion_lotes", "legal_aceptaciones", "legal_aceptacion_documentos",
            "legal_aceptacion_metadatos", "legal_aceptacion_metadatos_cifrados",
            "legal_idempotencia_resultados");
    private static final List<String> IDENTITY_SEQUENCES = List.of(
            "legal_aceptacion_documentos_id_seq", "legal_aceptacion_metadatos_cifrados_id_seq",
            "legal_idempotencia_resultados_id_seq", "talleres_id_seq",
            "suscripciones_id_seq", "users_id_seq");
    private static final Map<String, String> LOCK_COLUMNS = Map.of(
            "legal_requisito_conjuntos_actuales", "conjunto_id",
            "legal_requisito_agregados", "id",
            "legal_aceptacion_lotes", "id",
            "legal_aceptaciones", "id",
            "legal_aceptacion_metadatos", "lote_id");
    private static final List<String> FUNCTIONS = List.of(
            "legal_rechazar_update_delete()", "legal_exigir_read_committed()",
            "legal_fila_es_transaccion_actual(xid)", "legal_exigir_lock_editorial_v28()",
            "legal_requisito_agregado_insert_guard()", "legal_requisito_agregado_scope_insert_guard()",
            "legal_validar_requisito_agregado(uuid)", "legal_requisito_agregado_constraint_guard()",
            "legal_validar_requisito_agregado_actual(uuid)", "legal_aceptacion_lote_insert_guard()",
            "legal_aceptacion_insert_guard()", "legal_aceptacion_documento_insert_guard()",
            "legal_metadata_header_insert_guard()", "legal_metadata_cifrada_insert_guard()",
            "legal_validar_aceptacion(uuid)", "legal_validar_lote_aceptacion(uuid)",
            "legal_aceptacion_constraint_guard()", "legal_aceptacion_agregado_constraint_guard()",
            "legal_idempotencia_insert_guard()");
    static final List<String> DURABLE_TABLES = List.of(
            "talleres", "suscripciones", "users", "legal_requisito_agregados",
            "legal_requisito_agregado_scopes", "legal_aceptacion_lotes", "legal_aceptaciones",
            "legal_aceptacion_documentos", "legal_aceptacion_metadatos",
            "legal_aceptacion_metadatos_cifrados", "legal_idempotencia_resultados");

    private final JdbcTemplate owner;
    private final String jdbcUrl;

    LegalAcceptanceProtocolFeasibilityITSupport(DataSource owner, String jdbcUrl) {
        this.owner = new JdbcTemplate(Objects.requireNonNull(owner, "owner"));
        this.jdbcUrl = Objects.requireNonNull(jdbcUrl, "jdbcUrl");
    }

    void provisionRoles() {
        requireFixtureDatabase();
        owner.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
        owner.execute("REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA public FROM PUBLIC");
        owner.execute("REVOKE TEMPORARY ON DATABASE " + DATABASE + " FROM PUBLIC");
        for (String role : List.of(SELECT_ACTOR_ROLE, UNSAFE_ACTOR_ROLE,
                ACCEPTOR_ROLE, REGISTRATION_ROLE)) {
            assertThat(owner.queryForObject(
                    "SELECT count(*) FROM pg_catalog.pg_roles WHERE rolname = ?", Long.class, role))
                    .as("fixture roles must not preexist").isZero();
            owner.execute("CREATE ROLE " + role
                    + " LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS"
                    + " PASSWORD '" + PASSWORD + "'");
            owner.execute("GRANT CONNECT ON DATABASE " + DATABASE + " TO " + role);
            owner.execute("GRANT USAGE ON SCHEMA public TO " + role);
            owner.execute("ALTER ROLE " + role + " IN DATABASE " + DATABASE
                    + " SET search_path TO pg_catalog, public, pg_temp");
        }
        owner.execute("GRANT SELECT ON public.users, public.talleres TO " + UNSAFE_ACTOR_ROLE);
        grantActorRowLocks(UNSAFE_ACTOR_ROLE);
        for (String role : List.of(SELECT_ACTOR_ROLE, ACCEPTOR_ROLE, REGISTRATION_ROLE)) {
            owner.execute("GRANT SELECT ON " + qualified(READ_TABLES) + " TO " + role);
            owner.execute("GRANT INSERT ON " + qualified(INSERT_TABLES) + " TO " + role);
            LOCK_COLUMNS.forEach((table, column) -> owner.execute(
                    "GRANT UPDATE (" + column + ") ON public." + table + " TO " + role));
            owner.execute("GRANT EXECUTE ON FUNCTION " + qualified(FUNCTIONS) + " TO " + role);
        }
        grantActorRowLocks(ACCEPTOR_ROLE);
        grantActorRowLocks(REGISTRATION_ROLE);
        owner.execute("GRANT INSERT (nombre, email_contacto, telefono, activo) ON public.talleres TO "
                + REGISTRATION_ROLE);
        owner.execute("GRANT INSERT (taller_id, plan, estado, fecha_inicio, fecha_fin_trial)"
                + " ON public.suscripciones TO " + REGISTRATION_ROLE);
        owner.execute("GRANT INSERT (username, password, email, role, taller_id, active, email_verificado)"
                + " ON public.users TO " + REGISTRATION_ROLE);
        for (String role : List.of(SELECT_ACTOR_ROLE, UNSAFE_ACTOR_ROLE,
                ACCEPTOR_ROLE, REGISTRATION_ROLE)) {
            assertThat(owner.queryForObject("""
                    SELECT NOT (rolsuper OR rolinherit OR rolcreaterole OR rolcreatedb
                                OR rolreplication OR rolbypassrls)
                      FROM pg_catalog.pg_roles WHERE rolname = ?
                    """, Boolean.class, role)).isTrue();
            assertThat(owner.queryForObject("""
                    SELECT count(*) FROM pg_catalog.pg_class relation
                      JOIN pg_catalog.pg_roles role ON role.oid = relation.relowner
                     WHERE role.rolname = ?
                    """, Long.class, role)).isZero();
            // All six IDs are GENERATED BY DEFAULT AS IDENTITY. Inserts must succeed through
            // table/column grants alone; no direct nextval capability is given to these roles.
            for (String sequence : IDENTITY_SEQUENCES) {
                assertThat(owner.queryForObject(
                        "SELECT pg_catalog.has_sequence_privilege(?, ?, 'USAGE')",
                        Boolean.class, role, "public." + sequence))
                        .as("role %s must not have sequence USAGE on %s", role, sequence)
                        .isFalse();
            }
        }
    }

    private void grantActorRowLocks(String role) {
        owner.execute("GRANT UPDATE (id) ON public.users, public.talleres TO " + role);
    }

    /** Temporary additive hardening; original V27/V28 trigger functions are never changed. */
    void installIdentityGuard() {
        requireFixtureDatabase();
        assertThat(owner.queryForObject("""
                SELECT count(*) FROM pg_catalog.pg_proc
                 WHERE pronamespace = 'public'::regnamespace AND proname = ?
                """, Long.class, GUARD)).isZero();
        owner.execute("""
                CREATE FUNCTION public.%s() RETURNS trigger
                LANGUAGE plpgsql SECURITY INVOKER
                SET search_path TO pg_catalog, public, pg_temp
                AS $fixture$
                BEGIN
                    RAISE EXCEPTION '15A fixture: identity UPDATE is forbidden'
                        USING ERRCODE = '23514';
                END;
                $fixture$
                """.formatted(GUARD));
        owner.execute("REVOKE ALL ON FUNCTION public." + GUARD + "() FROM PUBLIC");
        for (String table : List.of("users", "talleres")) {
            owner.execute("CREATE TRIGGER ordenfix_15a_fixture_identity_update"
                    + " BEFORE UPDATE OF id ON public." + table
                    + " FOR EACH STATEMENT EXECUTE FUNCTION public." + GUARD + "()");
        }
    }

    void removeIdentityGuard() {
        requireFixtureDatabase();
        for (String table : List.of("users", "talleres")) {
            owner.execute("DROP TRIGGER IF EXISTS ordenfix_15a_fixture_identity_update ON public." + table);
        }
        owner.execute("DROP FUNCTION IF EXISTS public." + GUARD + "()");
    }

    private void requireFixtureDatabase() {
        assertThat(owner.queryForObject("SELECT current_database()", String.class)).isEqualTo(DATABASE);
        assertThat(owner.queryForObject("""
                SELECT version FROM public.flyway_schema_history
                 WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1
                """, String.class)).isEqualTo("28");
    }

    DataSource dataSource(String role) {
        if (!List.of(SELECT_ACTOR_ROLE, UNSAFE_ACTOR_ROLE, ACCEPTOR_ROLE, REGISTRATION_ROLE)
                .contains(role)) {
            throw new IllegalArgumentException("role is not owned by this fixture");
        }
        return new DriverManagerDataSource(jdbcUrl, role, PASSWORD);
    }

    Map<String, Long> counts() {
        Map<String, Long> result = new LinkedHashMap<>();
        DURABLE_TABLES.forEach(table -> result.put(table,
                owner.queryForObject("SELECT count(*) FROM public." + table, Long.class)));
        return Map.copyOf(result);
    }

    static JdbcTemplate jdbc(Connection connection) {
        return new JdbcTemplate(new SingleConnectionDataSource(connection, true));
    }

    static Connection transaction(DataSource dataSource) throws SQLException {
        Connection connection = dataSource.getConnection();
        try {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            JdbcTemplate jdbc = jdbc(connection);
            jdbc.execute("SET LOCAL statement_timeout = '10s'");
            jdbc.execute("SET LOCAL lock_timeout = '5s'");
            assertThat(jdbc.queryForObject("SHOW transaction_isolation", String.class))
                    .isEqualTo("read committed");
            return connection;
        } catch (RuntimeException | SQLException failure) {
            connection.close();
            throw failure;
        }
    }

    static LegalEditorialTimeBoundary sharedBoundary(JdbcTemplate jdbc) {
        jdbc.execute("""
                SELECT pg_catalog.pg_advisory_xact_lock_shared(
                    pg_catalog.hashtextextended('ordenfix:legal-publicaciones:sello:v1', 0))
                """);
        return jdbc.queryForObject("SELECT transaction_timestamp(), statement_timestamp()",
                (row, ignored) -> new LegalEditorialTimeBoundary(
                        row.getObject(1, OffsetDateTime.class).toInstant(),
                        row.getObject(2, OffsetDateTime.class).toInstant()));
    }

    static LegalRequiredSetAggregateReceipt materialize(
            JdbcTemplate jdbc, PerfilAgregadoLegal profile, AudienciaLegal audience) {
        LegalEditorialTimeBoundary boundary = sharedBoundary(jdbc);
        return new LegalRequiredSetAggregateStore(jdbc,
                new LegalRequiredSetAggregateRevisionCalculator(),
                new LegalRequiredSetAggregateProvenanceCalculator())
                .materialize(new LegalApplicableScopeResolver().resolve(
                        profile, LocaleLegal.ES_AR, audience), boundary);
    }

    static Actor insertActor(JdbcTemplate jdbc) {
        String identity = UUID.randomUUID().toString();
        long workshop = Objects.requireNonNull(jdbc.queryForObject(
                "INSERT INTO talleres (nombre) VALUES (?) RETURNING id", Long.class,
                "Fixture actor " + identity));
        long user = Objects.requireNonNull(jdbc.queryForObject("""
                INSERT INTO users (username, password, email, role, taller_id)
                VALUES (?, 'fixture-hash', ?, 'USER', ?) RETURNING id
                """, Long.class, "fixture-" + identity, identity + "@ordenfix.test", workshop));
        return new Actor(user, workshop, "USER", "USER");
    }

    static Lot insertLot(JdbcTemplate jdbc, Actor actor, LegalRequiredSetAggregateReceipt aggregate) {
        UUID lotId = UUID.randomUUID();
        OffsetDateTime acceptedAt = jdbc.queryForObject("""
                INSERT INTO legal_aceptacion_lotes
                    (id, user_id, taller_id, rol_wire, audiencia, required_set_revision,
                     aceptado_en, revision_scheme, perfil, agregado_id)
                VALUES (?, ?, ?, ?, ?, ?, '1970-01-01T00:00:00Z',
                        'AGGREGATE_V1', ?, ?) RETURNING aceptado_en
                """, OffsetDateTime.class, lotId, actor.userId(), actor.workshopId(),
                actor.role(), actor.audience(), aggregate.requiredSetRevision(),
                aggregate.provenance().profile().name(), aggregate.aggregateId());
        return new Lot(lotId, actor, aggregate, Objects.requireNonNull(acceptedAt));
    }

    static List<Act> insertActs(JdbcTemplate jdbc, Lot lot) {
        List<UUID> requirementIds = jdbc.queryForList("""
                SELECT member.requisito_version_id
                  FROM legal_requisito_agregado_scopes scope
                  JOIN legal_requisito_conjunto_miembros member ON member.conjunto_id = scope.conjunto_id
                 WHERE scope.agregado_id = ?
                 ORDER BY scope.scope_ordinal, member.manifest_ordinal
                """, UUID.class, lot.aggregate().aggregateId());
        assertThat(requirementIds).isNotEmpty();
        List<Act> acts = new ArrayList<>();
        for (UUID requirement : requirementIds) {
            UUID acceptance = UUID.randomUUID();
            assertThat(jdbc.update("""
                    INSERT INTO legal_aceptaciones
                        (id, lote_id, user_id, taller_id, requisito_version_id,
                         requisito_clave, requisito_version, contexto, tipo_acto,
                         afirmacion, afirmacion_sha256, requerido)
                    SELECT ?, ?, ?, ?, version.id, line.clave, version.version,
                           line.contexto, line.tipo_acto, version.afirmacion,
                           version.afirmacion_sha256, version.requerido
                      FROM legal_requisito_versiones version
                      JOIN legal_requisito_lineas line ON line.id = version.requisito_linea_id
                     WHERE version.id = ?
                    """, acceptance, lot.id(), lot.actor().userId(), lot.actor().workshopId(), requirement))
                    .isOne();
            acts.add(new Act(acceptance, requirement));
        }
        return List.copyOf(acts);
    }

    static int insertDocuments(JdbcTemplate jdbc, List<Act> acts) {
        int count = 0;
        for (Act act : acts) {
            int written = jdbc.update("""
                    INSERT INTO legal_aceptacion_documentos
                        (aceptacion_id, documento_ordinal, documento_version_id,
                         documento_clave, tipo, version, titulo, sha256)
                    SELECT ?, document.documento_ordinal, version.id, line.clave, line.tipo,
                           version.version, version.titulo, version.sha256
                      FROM legal_requisito_documentos document
                      JOIN legal_documento_versiones version ON version.id = document.documento_version_id
                      JOIN legal_documento_lineas line ON line.id = version.documento_linea_id
                     WHERE document.requisito_version_id = ?
                    """, act.id(), act.requirementId());
            assertThat(written).isPositive();
            count += written;
        }
        return count;
    }

    static void insertMetadata(JdbcTemplate jdbc, Lot lot) {
        assertThat(jdbc.update("""
                INSERT INTO legal_aceptacion_metadatos (lote_id, capturado_en, retener_hasta)
                VALUES (?, statement_timestamp(), statement_timestamp() + INTERVAL '30 days')
                """, lot.id())).isOne();
        // Opaque synthetic bytes characterize SQL shape, not a production encryption service.
        byte[] nonce = new byte[12];
        new java.security.SecureRandom().nextBytes(nonce);
        assertThat(jdbc.update("""
                INSERT INTO legal_aceptacion_metadatos_cifrados
                    (lote_id, tipo, key_version, nonce, ciphertext, tag, longitud_original)
                VALUES (?, 'IP', 1, ?, ?, ?, 9)
                """, lot.id(), nonce, new byte[9], new byte[16])).isOne();
    }

    static long insertLedger(JdbcTemplate jdbc, Lot lot, String operation) {
        // Synthetic HMAC-shaped values characterize SQL constraints, not HMAC/keyring behavior.
        String hex = UUID.randomUUID().toString().replace("-", "");
        return Objects.requireNonNull(jdbc.queryForObject("""
                INSERT INTO legal_idempotencia_resultados
                    (operacion, route_template, scope_hmac, idempotency_key_hmac,
                     fingerprint_hmac, hmac_key_version, user_id, taller_id, lote_id,
                     completed_at, expires_at)
                VALUES (?, ?, ?, ?, ?, 1, ?, ?, ?, '1970-01-01T00:00:00Z',
                        statement_timestamp() + INTERVAL '25 hours') RETURNING id
                """, Long.class, operation,
                operation.equals("REGISTRO") ? "/api/auth/register" : "/api/aceptaciones-legales",
                "a".repeat(64), hex + hex, "b".repeat(64),
                lot.actor().userId(), lot.actor().workshopId(), lot.id()));
    }

    static Written writeCompleteAcceptance(JdbcTemplate jdbc, Actor actor) {
        var aggregate = materialize(jdbc, PerfilAgregadoLegal.AUTHENTICATED_PENDING, AudienciaLegal.USER);
        Lot lot = insertLot(jdbc, actor, aggregate);
        List<Act> acts = insertActs(jdbc, lot);
        int documents = insertDocuments(jdbc, acts);
        insertMetadata(jdbc, lot);
        long ledger = insertLedger(jdbc, lot, "ACEPTACION_LEGAL");
        return new Written(lot, acts, documents, ledger);
    }

    static Written writeRegistration(JdbcTemplate jdbc, Consumer<Stage> checkpoint) {
        String identity = UUID.randomUUID().toString();
        String email = identity + "@ordenfix.test";
        long workshop = Objects.requireNonNull(jdbc.queryForObject("""
                INSERT INTO talleres (nombre, email_contacto, telefono, activo)
                VALUES (?, ?, '1100000000', true) RETURNING id
                """, Long.class, "Fixture alta " + identity, email));
        checkpoint.accept(Stage.WORKSHOP);
        LocalDate today = LocalDate.of(2026, 9, 6);
        assertThat(jdbc.update("""
                INSERT INTO suscripciones (taller_id, plan, estado, fecha_inicio, fecha_fin_trial)
                VALUES (?, 'FREE', 'TRIAL', ?, ?)
                """, workshop, today, today.plusDays(14))).isOne();
        checkpoint.accept(Stage.SUBSCRIPTION);
        long user = Objects.requireNonNull(jdbc.queryForObject("""
                INSERT INTO users
                    (username, password, email, role, taller_id, active, email_verificado)
                VALUES (?, 'fixture-hash', ?, 'ADMIN', ?, true, false) RETURNING id
                """, Long.class, "fixture-" + identity, email, workshop));
        checkpoint.accept(Stage.USER);
        Actor actor = new Actor(user, workshop, "ADMIN", "ADMIN_TITULAR");
        var aggregate = materialize(jdbc, PerfilAgregadoLegal.REGISTRATION, AudienciaLegal.ADMIN_TITULAR);
        assertThat(aggregate.outcome()).isEqualTo(LegalRequiredSetAggregateReceipt.Outcome.CREATED);
        checkpoint.accept(Stage.AGGREGATE);
        Lot lot = insertLot(jdbc, actor, aggregate);
        checkpoint.accept(Stage.LOT);
        List<Act> acts = insertActs(jdbc, lot);
        checkpoint.accept(Stage.ACTS);
        int documents = insertDocuments(jdbc, acts);
        checkpoint.accept(Stage.DOCUMENTS);
        insertMetadata(jdbc, lot);
        checkpoint.accept(Stage.METADATA);
        long ledger = insertLedger(jdbc, lot, "REGISTRO");
        checkpoint.accept(Stage.LEDGER);
        jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
        checkpoint.accept(Stage.CONSTRAINTS);
        return new Written(lot, acts, documents, ledger);
    }

    private static String qualified(List<String> names) {
        return String.join(", ", names.stream().map(name -> "public." + name).toList());
    }

    enum Stage { WORKSHOP, SUBSCRIPTION, USER, AGGREGATE, LOT, ACTS, DOCUMENTS, METADATA, LEDGER, CONSTRAINTS }
    record Actor(long userId, long workshopId, String role, String audience) { }
    record Lot(UUID id, Actor actor, LegalRequiredSetAggregateReceipt aggregate, OffsetDateTime acceptedAt) { }
    record Act(UUID id, UUID requirementId) { }
    record Written(Lot lot, List<Act> acts, int documents, long ledgerId) { }
}
