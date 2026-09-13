package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/** Real PostgreSQL 16 ACL accreditation and denial, confined to a dedicated disposable cluster. */
@Execution(ExecutionMode.SAME_THREAD)
class LegalAcceptanceMaintenancePrivilegeVerifierIT {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_maintenance_privileges")
            .withUsername("ordenfix").withPassword("ordenfix");
    private static final String ROLE = "ordenfix_maintenance_privilege_it";
    private static final String PASSWORD = "maintenance-privilege-fixture";
    private static JdbcTemplate owner;
    private static JdbcTemplate restricted;
    private static LegalAcceptanceMaintenancePrivilegeVerifier verifier;

    @BeforeAll static void start() {
        POSTGRES.start();
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
        owner = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        var credentials = LegalRestrictedMaintenanceRoleFixture.provision(owner, ROLE, PASSWORD);
        restricted = new JdbcTemplate(new DriverManagerDataSource(credentials.jdbcUrl(), credentials.username(), credentials.password()));
        verifier = new LegalAcceptanceMaintenancePrivilegeVerifier(restricted, ROLE, "public");
        assertThat(credentials.toString()).doesNotContain(PASSWORD, ROLE, POSTGRES.getJdbcUrl());
    }

    @AfterAll static void stop() { POSTGRES.stop(); }

    @Test void exactCapabilitiesAccreditCurrentSchemaWithoutAccountOrEncryptedPayloadReads() {
        new LegalV29AcceptanceSchemaVerifier(restricted, "public").verify();
        verifier.verify();
        assertThat(tablesWith("SELECT")).containsExactlyInAnyOrder(
                "flyway_schema_history", "legal_requisito_lineas", "legal_requisito_audiencias",
                "legal_requisito_versiones", "legal_requisito_documentos", "legal_documento_lineas",
                "legal_documento_versiones", "legal_aceptaciones", "legal_aceptacion_documentos",
                "legal_aceptacion_metadatos", "legal_idempotencia_sin_actos");
        assertThat(tablesWith("INSERT")).isEmpty();
        assertThat(tablesWith("UPDATE")).isEmpty();
        assertThat(tablesWith("DELETE")).containsExactlyInAnyOrder(
                "legal_idempotencia_resultados", "legal_idempotencia_sin_actos", "legal_idempotencia_sin_actos_referencias");
        assertThat(restricted.queryForList("""
                SELECT relname||'.'||attname FROM pg_catalog.pg_class c
                JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
                JOIN pg_catalog.pg_attribute a ON a.attrelid=c.oid
                 WHERE n.nspname='public' AND attnum>0 AND NOT attisdropped AND relkind IN ('r','p')
                   AND pg_catalog.has_column_privilege(c.oid,a.attnum,'UPDATE') ORDER BY 1
                """, String.class)).containsExactly(
                "legal_aceptacion_metadatos.purgado_en", "legal_aceptacion_metadatos_cifrados.ciphertext",
                "legal_aceptacion_metadatos_cifrados.longitud_original", "legal_aceptacion_metadatos_cifrados.tag",
                "legal_aceptacion_metadatos_cifrados.tombstone_en", "legal_idempotencia_resultados.id",
                "legal_idempotencia_sin_actos.id");
        assertThat(restricted.queryForList("""
                SELECT attname FROM pg_catalog.pg_attribute
                 WHERE attrelid='public.legal_aceptacion_metadatos_cifrados'::regclass AND attnum>0 AND NOT attisdropped
                   AND pg_catalog.has_column_privilege(attrelid,attnum,'SELECT') ORDER BY 1
                """, String.class)).containsExactly("lote_id", "tipo", "tombstone_en");
        assertThat(restricted.queryForObject("""
                SELECT count(*) FROM pg_catalog.pg_class WHERE relkind='S'
                  AND (pg_catalog.has_sequence_privilege(oid,'USAGE') OR pg_catalog.has_sequence_privilege(oid,'SELECT')
                       OR pg_catalog.has_sequence_privilege(oid,'UPDATE'))
                """, Integer.class)).isZero();
        assertThat(restricted.queryForObject("SELECT current_user=session_user AND current_user=?", Boolean.class, ROLE)).isTrue();
        assertThat(verifier.usesJdbc(restricted)).isTrue();
        assertThat(verifier.usesJdbc(owner)).isFalse();
        assertThat(verifier.expectedSchema()).isEqualTo("public");
    }

    @Test void everyRequiredReadDeleteUpdateAndHelperIsIndependentlyAccredited() {
        for (String table : LegalAcceptanceMaintenancePrivilegeVerifier.READ_TABLES)
            drift("REVOKE SELECT ON " + table + " FROM " + ROLE, "GRANT SELECT ON " + table + " TO " + ROLE);
        for (String table : LegalAcceptanceMaintenancePrivilegeVerifier.DELETE_TABLES)
            drift("REVOKE DELETE ON " + table + " FROM " + ROLE, "GRANT DELETE ON " + table + " TO " + ROLE);
        LegalAcceptanceMaintenancePrivilegeVerifier.SELECT_COLUMNS.forEach((table, columns) -> columns.forEach(column ->
                drift("REVOKE SELECT (" + column + ") ON " + table + " FROM " + ROLE,
                        "GRANT SELECT (" + column + ") ON " + table + " TO " + ROLE)));
        LegalAcceptanceMaintenancePrivilegeVerifier.UPDATE_COLUMNS.forEach((table, columns) -> columns.forEach(column ->
                drift("REVOKE UPDATE (" + column + ") ON " + table + " FROM " + ROLE,
                        "GRANT UPDATE (" + column + ") ON " + table + " TO " + ROLE)));
        for (String function : LegalAcceptanceMaintenancePrivilegeVerifier.PRIVILEGED_FUNCTIONS)
            drift("REVOKE EXECUTE ON FUNCTION " + function + " FROM " + ROLE,
                    "GRANT EXECUTE ON FUNCTION " + function + " TO " + ROLE);
    }

    @ParameterizedTest @ValueSource(strings = {"ciphertext", "tag", "nonce", "key_version", "id", "longitud_original"})
    void encryptedPayloadColumnCannotBeReadEvenWhenTheRoleMayNullIt(String column) {
        sqlState(() -> restricted.queryForList("SELECT " + column + " FROM legal_aceptacion_metadatos_cifrados"), "42501");
        drift("GRANT SELECT (" + column + ") ON legal_aceptacion_metadatos_cifrados TO " + ROLE,
                "REVOKE SELECT (" + column + ") ON legal_aceptacion_metadatos_cifrados FROM " + ROLE);
    }

    @Test void requestsBusinessWritesAndEvidenceDeletionAreUnavailableAtDatabaseLevel() {
        for (String table : List.of("users", "talleres", "suscripciones", "legal_requisito_agregados",
                "legal_aceptaciones", "legal_aceptacion_lotes", "legal_aceptacion_metadatos",
                "legal_aceptacion_metadatos_cifrados", "legal_idempotencia_resultados", "legal_idempotencia_sin_actos")) {
            sqlState(() -> restricted.update("INSERT INTO " + table + " DEFAULT VALUES"), "42501");
            drift("GRANT INSERT ON " + table + " TO " + ROLE, "REVOKE INSERT ON " + table + " FROM " + ROLE);
        }
        for (String table : List.of("legal_aceptaciones", "legal_aceptacion_lotes", "legal_aceptacion_documentos",
                "legal_aceptacion_metadatos", "legal_aceptacion_metadatos_cifrados")) {
            sqlState(() -> restricted.update("DELETE FROM " + table), "42501");
            drift("GRANT DELETE ON " + table + " TO " + ROLE, "REVOKE DELETE ON " + table + " FROM " + ROLE);
        }
        sqlState(() -> restricted.queryForList("SELECT email,password FROM users"), "42501");
        sqlState(() -> restricted.queryForList("SELECT archive_cipher FROM cuenta_exportaciones"), "42501");
        sqlState(() -> restricted.execute("UPDATE legal_aceptacion_metadatos SET retener_hasta=transaction_timestamp()"), "42501");
        sqlState(() -> restricted.execute("UPDATE legal_aceptaciones SET afirmacion=afirmacion"), "42501");
        sqlState(() -> restricted.execute("TRUNCATE legal_idempotencia_resultados"), "42501");
    }

    @Test void publicGrantOptionsAndUnrelatedFunctionsCannotExpandTheRole() {
        drift("GRANT SELECT (email) ON users TO " + ROLE, "REVOKE SELECT (email) ON users FROM " + ROLE);
        drift("GRANT SELECT ON legal_aceptacion_metadatos_cifrados TO PUBLIC",
                "REVOKE SELECT ON legal_aceptacion_metadatos_cifrados FROM PUBLIC");
        drift("GRANT DELETE ON legal_idempotencia_resultados TO " + ROLE + " WITH GRANT OPTION",
                "REVOKE GRANT OPTION FOR DELETE ON legal_idempotencia_resultados FROM " + ROLE);
        drift("GRANT UPDATE (purgado_en) ON legal_aceptacion_metadatos TO " + ROLE + " WITH GRANT OPTION",
                "REVOKE GRANT OPTION FOR UPDATE (purgado_en) ON legal_aceptacion_metadatos FROM " + ROLE);
        for (String signature : List.of("legal_aceptacion_insert_guard()", "legal_validar_conjuntos_actuales()",
                "cuenta_cierre_estado_v33(bigint)")) {
            drift("GRANT EXECUTE ON FUNCTION " + signature + " TO " + ROLE,
                    "REVOKE EXECUTE ON FUNCTION " + signature + " FROM " + ROLE);
        }
        sqlState(() -> restricted.execute("SELECT cuenta_cierre_estado_v33(1)"), "42501");
    }

    @Test void fullNoActsReadIsRequiredByTheFrozenXminCompletenessHelper() {
        restricted.queryForList("SELECT xmin FROM legal_idempotencia_sin_actos");
        owner.execute("REVOKE SELECT ON legal_idempotencia_sin_actos FROM " + ROLE);
        try {
            owner.execute("GRANT SELECT(id,resultado,referencia_count,agregado_observado_id) ON legal_idempotencia_sin_actos TO " + ROLE);
            sqlState(() -> restricted.queryForList("SELECT xmin FROM legal_idempotencia_sin_actos"), "42501");
            assertThatThrownBy(verifier::verify).isInstanceOf(LegalEditorialOperationalException.class);
        } finally {
            owner.execute("REVOKE SELECT(id,resultado,referencia_count,agregado_observado_id) ON legal_idempotencia_sin_actos FROM " + ROLE);
            owner.execute("GRANT SELECT ON legal_idempotencia_sin_actos TO " + ROLE);
        }
        verifier.verify();
    }

    @Test void sessionLocksLargeObjectsAndDdlAreNotAvailable() {
        sqlState(() -> restricted.execute("SELECT pg_catalog.pg_advisory_lock(789::bigint)"), "42501");
        sqlState(() -> restricted.execute("SELECT pg_catalog.lo_create(0)"), "42501");
        sqlState(() -> restricted.execute("CREATE TABLE public.unauthorized(id integer)"), "42501");
        sqlState(() -> restricted.execute("CREATE TEMP TABLE unauthorized(id integer)"), "42501");
        drift("GRANT EXECUTE ON FUNCTION pg_catalog.lo_create(oid) TO " + ROLE,
                "REVOKE EXECUTE ON FUNCTION pg_catalog.lo_create(oid) FROM " + ROLE);
        drift("GRANT EXECUTE ON FUNCTION pg_catalog.pg_try_advisory_lock(bigint) TO " + ROLE,
                "REVOKE EXECUTE ON FUNCTION pg_catalog.pg_try_advisory_lock(bigint) FROM " + ROLE);
    }

    @Test void membershipOwnershipSessionSettingsAndSystemPrivilegesFailClosed() {
        drift("ALTER ROLE " + ROLE + " INHERIT", "ALTER ROLE " + ROLE + " NOINHERIT");
        drift("GRANT pg_read_all_data TO " + ROLE, "REVOKE pg_read_all_data FROM " + ROLE);
        drift("GRANT USAGE ON SEQUENCE users_id_seq TO " + ROLE, "REVOKE USAGE ON SEQUENCE users_id_seq FROM " + ROLE);
        drift("GRANT SET ON PARAMETER session_replication_role TO " + ROLE,
                "REVOKE SET ON PARAMETER session_replication_role FROM " + ROLE);
        drift("GRANT CREATE ON SCHEMA public TO " + ROLE, "REVOKE CREATE ON SCHEMA public FROM " + ROLE);
        drift("ALTER ROLE " + ROLE + " IN DATABASE " + POSTGRES.getDatabaseName() + " SET search_path=public",
                "ALTER ROLE " + ROLE + " IN DATABASE " + POSTGRES.getDatabaseName() + " SET search_path=pg_catalog,public,pg_temp");
        owner.execute("CREATE SCHEMA maintenance_owned AUTHORIZATION " + ROLE);
        try { assertThatThrownBy(verifier::verify).isInstanceOf(LegalEditorialOperationalException.class); }
        finally { owner.execute("DROP SCHEMA maintenance_owned"); }
        verifier.verify();
        assertThatThrownBy(() -> new LegalAcceptanceMaintenancePrivilegeVerifier(restricted, "foreign", "public").verify())
                .isInstanceOf(LegalEditorialOperationalException.class);
    }

    @Test void roleFixtureRefusesReuseBeforeChangingGrants() {
        assertThatThrownBy(() -> LegalRestrictedMaintenanceRoleFixture.provision(owner, ROLE, "other-fixture"))
                .isInstanceOf(IllegalStateException.class);
        verifier.verify();
    }

    private static List<String> tablesWith(String privilege) {
        return restricted.queryForList("""
                SELECT relname FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
                 WHERE n.nspname='public' AND relkind IN ('r','p') AND pg_catalog.has_table_privilege(c.oid,?)
                """, String.class, privilege);
    }

    private static void drift(String mutation, String restoration) {
        owner.execute(mutation);
        try { assertThatThrownBy(verifier::verify).isInstanceOf(LegalEditorialOperationalException.class); }
        finally { owner.execute(restoration); }
        verifier.verify();
    }

    private static void sqlState(org.assertj.core.api.ThrowableAssert.ThrowingCallable work, String expected) {
        Throwable failure = catchThrowable(work);
        assertThat(failure).isNotNull();
        while (failure != null && !(failure instanceof SQLException)) failure = failure.getCause();
        assertThat(failure).isInstanceOf(SQLException.class);
        assertThat(((SQLException) failure).getSQLState()).isEqualTo(expected);
    }
}
