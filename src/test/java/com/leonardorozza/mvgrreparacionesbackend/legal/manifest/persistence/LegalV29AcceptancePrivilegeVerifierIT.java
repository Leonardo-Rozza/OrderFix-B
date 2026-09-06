package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;

import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceProtocolFeasibilityITSupport.*;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalV29AcceptanceITSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** Tests concrete SQL grants; consumer-specific production privilege verifiers arrive in later cuts. */
@Testcontainers
class LegalV29AcceptancePrivilegeVerifierIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_v29_privileges").withUsername("ordenfix").withPassword("ordenfix");
    @TempDir static Path directory;
    static final String ACCEPTOR = "ordenfix_v29_acceptor";
    static final String REGISTRATION = "ordenfix_v29_registration";
    static LegalV29AcceptanceITSupport fixture;
    static DataSource acceptor;
    static DataSource registration;
    static DataSource resultMaintenance;

    @BeforeAll static void prepare() throws Exception {
        fixture = new LegalV29AcceptanceITSupport(POSTGRES);
        fixture.migrate("29");
        fixture.seedCatalog(directory, LegalV29AcceptancePrivilegeVerifierIT.class);
        acceptor = fixture.provision(ACCEPTOR, false, false);
        registration = fixture.provision(REGISTRATION, true, false);
        resultMaintenance = fixture.provision("ordenfix_v29_result_maintenance", false, true);
    }

    @Test void restrictedAcceptanceCommitsWholeInvokerGraphAndDedupEmptyWithoutSequenceUsage() throws Exception {
        Actor actor = insertActor(fixture.owner);
        Written evidence;
        try (Connection connection = transaction(acceptor)) {
            evidence = acceptance(jdbc(connection), actor, tuple());
            jdbc(connection).execute("SET CONSTRAINTS ALL IMMEDIATE");
            connection.commit();
        }
        assertComplete(evidence, false);
        for (String kind : List.of("DEDUP", "EMPTY")) {
            try (Connection connection = transaction(acceptor)) {
                JdbcTemplate jdbc = jdbc(connection); Tuple tuple = tuple(); lock(jdbc, tuple);
                var aggregate = aggregate(jdbc, actor);
                var id = result(jdbc, actor, aggregate, tuple, kind,
                        kind.equals("EMPTY") ? aggregate.requiredSetRevision() : revision(),
                        kind.equals("EMPTY") ? 0 : evidence.acts().size());
                if (kind.equals("DEDUP")) evidence.acts().forEach(act -> reference(jdbc, id, act.id(), actor));
                connection.commit();
            }
        }
        assertComplete(evidence, false);
        for (String sequence : SEQUENCES) assertThat(fixture.owner.queryForObject(
                "SELECT has_sequence_privilege(?, ?, 'USAGE')", Boolean.class, ACCEPTOR, sequence)).isFalse();
    }

    @Test void restrictedRegistrationCommitsBusinessAggregateEvidenceAndLedgerTogether() throws Exception {
        Written written;
        try (Connection connection = transaction(registration)) {
            written = LegalV29AcceptanceITSupport.registration(jdbc(connection),
                    new Tuple("REGISTRO", "/api/auth/register", hex(), hex()));
            jdbc(connection).execute("SET CONSTRAINTS ALL IMMEDIATE");
            connection.commit();
        }
        assertComplete(written, true);
        assertThat(fixture.owner.queryForObject("""
                SELECT u.role='ADMIN' AND u.active AND NOT u.email_verificado AND u.token_version=0
                  AND t.activo AND s.plan='FREE' AND s.estado='TRIAL'
                  AND s.fecha_fin_trial-s.fecha_inicio=14
                FROM users u JOIN talleres t ON t.id=u.taller_id
                  JOIN suscripciones s ON s.taller_id=t.id WHERE u.id=?
                """, Boolean.class, written.lot().actor().userId())).isTrue();
        for (String sequence : SEQUENCES) assertThat(fixture.owner.queryForObject(
                "SELECT has_sequence_privilege(?, ?, 'USAGE')", Boolean.class, REGISTRATION, sequence)).isFalse();
    }

    @Test void rollbackAfterFullRestrictedRegistrationLeavesNoBusinessOrLegalRows() throws Exception {
        var before = fixture.durableRows();
        try (Connection connection = transaction(registration)) {
            LegalV29AcceptanceITSupport.registration(jdbc(connection),
                    new Tuple("REGISTRO", "/api/auth/register", hex(), hex()));
            jdbc(connection).execute("SET CONSTRAINTS ALL IMMEDIATE");
            connection.rollback();
        }
        assertThat(fixture.durableRows()).isEqualTo(before);
    }

    @Test void identityLockGrantsCannotMutateIdsEvenForOwnerOrNoopZeroRowStatements() throws Exception {
        Actor actor = insertActor(fixture.owner);
        for (DataSource source : List.of(fixture.dataSource, acceptor, registration)) {
            for (String table : List.of("users", "talleres")) {
                long id = table.equals("users") ? actor.userId() : actor.workshopId();
                for (String sql : List.of("UPDATE " + table + " SET id=-id WHERE id=?",
                        "UPDATE " + table + " SET id=id WHERE id=?",
                        "UPDATE " + table + " SET id=id WHERE id=? AND false")) {
                    reject(source, jdbc -> jdbc.update(sql, id), "23514");
                }
                try (Connection connection = transaction(source)) {
                    assertThat(jdbc(connection).queryForObject("SELECT id FROM " + table + " WHERE id=? FOR SHARE", Long.class, id)).isEqualTo(id);
                    connection.rollback();
                }
            }
        }
        // Ordinary application updates remain valid for their existing owner credential.
        try (Connection connection = transaction(fixture.dataSource)) {
            assertThat(jdbc(connection).update("UPDATE talleres SET nombre=nombre WHERE id=?", actor.workshopId())).isOne();
            connection.rollback();
        }
    }

    @Test void missingTransitiveExecuteOrActorLockPrivilegeRollsBackTheCompleteGraph() throws Exception {
        Actor actor = insertActor(fixture.owner);
        fixture.owner.execute("REVOKE EXECUTE ON FUNCTION legal_validar_lote_aceptacion(uuid) FROM " + ACCEPTOR);
        try {
            reject(acceptor, jdbc -> acceptance(jdbc, actor, tuple()), "42501");
        } finally {
            fixture.owner.execute("GRANT EXECUTE ON FUNCTION legal_validar_lote_aceptacion(uuid) TO " + ACCEPTOR);
        }
        fixture.owner.execute("REVOKE UPDATE (id) ON users FROM " + ACCEPTOR);
        try {
            reject(acceptor, jdbc -> acceptance(jdbc, actor, tuple()), "42501");
        } finally {
            fixture.owner.execute("GRANT UPDATE (id) ON users TO " + ACCEPTOR);
        }
    }

    @Test void nominalRolesCannotAcquireSessionLocksReadSecretsOrChangeUnrelatedState() throws Exception {
        Actor actor = insertActor(fixture.owner);
        for (DataSource source : List.of(acceptor, registration)) {
            for (String sql : List.of("SELECT pg_catalog.pg_advisory_lock(1::bigint)",
                    "SELECT pg_catalog.lo_create(0)",
                    "SELECT password FROM users", "SELECT ciphertext FROM legal_aceptacion_metadatos_cifrados",
                    "UPDATE users SET role='ADMIN' WHERE id=" + actor.userId(),
                    "UPDATE users SET email='changed@ordenfix.test' WHERE id=" + actor.userId(),
                    "DELETE FROM legal_aceptaciones", "DELETE FROM legal_idempotencia_sin_actos",
                    "TRUNCATE legal_aceptaciones", "CREATE TABLE public.ordenfix_v29_forbidden(id integer)",
                    "CREATE TEMP TABLE ordenfix_v29_forbidden(id integer)")) {
                reject(source, jdbc -> jdbc.execute(sql), "42501");
            }
        }
        reject(acceptor, jdbc -> jdbc.update("INSERT INTO talleres(nombre) VALUES ('forbidden')"), "42501");
        for (String role : List.of(ACCEPTOR, REGISTRATION)) {
            assertThat(fixture.owner.queryForObject("""
                    SELECT NOT rolsuper AND NOT rolcreaterole AND NOT rolcreatedb AND NOT rolreplication
                      AND NOT rolbypassrls AND NOT rolinherit FROM pg_roles WHERE rolname=?
                    """, Boolean.class, role)).isTrue();
            assertThat(fixture.owner.queryForObject("SELECT count(*) FROM pg_auth_members WHERE member=?::regrole", Integer.class, role)).isZero();
            assertThat(fixture.owner.queryForObject("SELECT count(*) FROM pg_class WHERE relowner=?::regrole", Integer.class, role)).isZero();
            assertThat(fixture.owner.queryForObject("SELECT count(*) FROM pg_proc WHERE proowner=?::regrole", Integer.class, role)).isZero();
        }
    }

    @Test void separateResultMaintenanceCanPurgeExpiredGraphButCannotFabricateEvidenceOrBusiness() throws Exception {
        Actor actor = insertActor(fixture.owner);
        Written evidence = fixture.committedAcceptance(actor);
        Tuple tuple = tuple();
        var id = fixture.committedDedup(actor, tuple, evidence);
        fixture.expire(PARENT, id);
        var before = fixture.durableRows();
        try (Connection connection = transaction(resultMaintenance)) {
            JdbcTemplate jdbc = jdbc(connection); lock(jdbc, tuple);
            jdbc.update("DELETE FROM legal_idempotencia_sin_actos_referencias WHERE resultado_id=?", id);
            jdbc.update("DELETE FROM legal_idempotencia_sin_actos WHERE id=?", id);
            connection.commit();
        }
        for (String table : DURABLE_TABLES) assertThat(fixture.durableRows().get(table)).as(table).isEqualTo(before.get(table));
        for (String sql : List.of("INSERT INTO legal_idempotencia_sin_actos DEFAULT VALUES",
                "INSERT INTO legal_idempotencia_resultados DEFAULT VALUES", "INSERT INTO legal_aceptacion_lotes DEFAULT VALUES",
                "INSERT INTO legal_aceptaciones DEFAULT VALUES", "INSERT INTO talleres(nombre) VALUES ('forbidden')",
                "UPDATE users SET active=false", "SELECT ciphertext FROM legal_aceptacion_metadatos_cifrados",
                "SELECT pg_advisory_lock(1::bigint)", "SELECT pg_catalog.lo_create(0)")) reject(resultMaintenance, jdbc -> jdbc.execute(sql), "42501");
    }

    private static void assertComplete(Written evidence, boolean businessCreated) {
        var lot = evidence.lot();
        assertThat(fixture.owner.queryForObject("""
                SELECT count(*) FROM legal_aceptacion_lotes l JOIN legal_idempotencia_resultados r ON r.lote_id=l.id
                JOIN legal_aceptacion_metadatos m ON m.lote_id=l.id
                WHERE l.id=? AND l.xmin=r.xmin AND l.xmin=m.xmin
                  AND (SELECT count(*) FROM legal_aceptaciones a WHERE a.lote_id=l.id AND a.xmin=l.xmin)=?
                  AND (SELECT count(*) FROM legal_aceptacion_documentos d JOIN legal_aceptaciones a ON a.id=d.aceptacion_id
                       WHERE a.lote_id=l.id AND d.xmin=l.xmin)=?
                  AND (SELECT count(*) FROM legal_aceptacion_metadatos_cifrados c WHERE c.lote_id=l.id AND c.xmin=l.xmin)=1
                """, Long.class, lot.id(), evidence.acts().size(), evidence.documents())).isEqualTo(1L);
        if (businessCreated) assertThat(fixture.owner.queryForObject("""
                SELECT l.xmin=u.xmin AND l.xmin=t.xmin AND l.xmin=s.xmin
                FROM legal_aceptacion_lotes l JOIN users u ON u.id=l.user_id JOIN talleres t ON t.id=l.taller_id
                JOIN suscripciones s ON s.taller_id=t.id WHERE l.id=?
                """, Boolean.class, lot.id())).isTrue();
    }

    private static void reject(DataSource source, SqlAction action, String state) throws Exception {
        var before = fixture.durableRows();
        try (Connection connection = transaction(source)) {
            Throwable failure = catchThrowable(() -> { action.run(jdbc(connection)); connection.commit(); });
            sqlState(failure, state);
            connection.rollback();
        }
        assertThat(fixture.durableRows()).isEqualTo(before);
    }
    @FunctionalInterface interface SqlAction { void run(JdbcTemplate jdbc) throws Exception; }

    @Test void existingAggregateConsumerRejectsEverySupplementaryV29Capability() {
        try (var database = new PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("ordenfix_legal_aggregate_v29_boundary")
                .withUsername("ordenfix").withPassword("ordenfix")) {
            database.start();
            var source = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                    database.getJdbcUrl(), database.getUsername(), database.getPassword());
            org.flywaydb.core.Flyway.configure().dataSource(source)
                    .locations("classpath:db/migration").load().migrate();
            var owner = new JdbcTemplate(source);
            String role = "ordenfix_legal_aggregate_v29_legacy";
            var legacy = new LegalRestrictedAggregateRoleFixture(owner, database.getJdbcUrl(),
                    role, "disposable-legacy-v29", database.getDriverClassName());
            legacy.provisionAndVerify();
            for (String capability : List.of(
                    "SELECT ON legal_idempotencia_sin_actos",
                    "SELECT (aceptacion_id) ON legal_idempotencia_sin_actos_referencias",
                    "INSERT ON legal_idempotencia_sin_actos",
                    "EXECUTE ON FUNCTION legal_exigir_lock_idempotente_v29(varchar,varchar,varchar,varchar)")) {
                owner.execute("GRANT " + capability + " TO " + role);
                try {
                    org.assertj.core.api.Assertions.assertThatThrownBy(legacy::verify)
                            .isInstanceOfSatisfying(LegalEditorialOperationalException.class,
                                    error -> assertThat(error.issue().code()).isEqualTo(
                                            com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode.ROLE_PRIVILEGE_DRIFT));
                } finally {
                    owner.execute("REVOKE " + capability + " FROM " + role);
                }
                legacy.verify();
            }
        }
    }

}
