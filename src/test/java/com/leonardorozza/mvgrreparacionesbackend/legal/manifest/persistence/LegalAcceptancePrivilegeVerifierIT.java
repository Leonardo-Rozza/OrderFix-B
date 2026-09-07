package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/** Exact effective ACL and real PostgreSQL denials for the authenticated writer only. */
@Execution(ExecutionMode.SAME_THREAD)
class LegalAcceptancePrivilegeVerifierIT {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_acceptance_privileges").withUsername("ordenfix").withPassword("ordenfix");
    private static final String ROLE = "ordenfix_acceptance_privilege_it";
    private static final String PASSWORD = "acceptance-privilege-fixture";
    @TempDir static Path directory;
    private static JdbcTemplate owner;
    private static JdbcTemplate restricted;
    private static LegalAcceptancePrivilegeVerifier verifier;

    @BeforeAll static void start() throws Exception {
        POSTGRES.start();
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
        owner = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        LegalRestrictedAcceptanceRoleFixture.seedCatalog(owner, directory, LegalAcceptancePrivilegeVerifierIT.class);
        var credentials = LegalRestrictedAcceptanceRoleFixture.provision(owner, ROLE, PASSWORD);
        restricted = new JdbcTemplate(new DriverManagerDataSource(credentials.jdbcUrl(), credentials.username(), credentials.password()));
        verifier = new LegalAcceptancePrivilegeVerifier(restricted, ROLE, "public");
        assertThat(credentials.toString()).doesNotContain(PASSWORD, POSTGRES.getJdbcUrl());
    }

    @AfterAll static void stop() { POSTGRES.stop(); }

    @Test void exactPrivilegesAccreditV29WithoutSequencesOrBusinessWrites() {
        new LegalV29AcceptanceSchemaVerifier(restricted, "public").verify();
        verifier.verify();
        assertThat(restricted.queryForList("""
                SELECT relname FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
                 WHERE n.nspname='public' AND relkind IN ('r','p') AND pg_catalog.has_table_privilege(c.oid,'SELECT')
                """, String.class)).containsExactlyInAnyOrder(
                "flyway_schema_history", "legal_publicaciones", "legal_publicacion_requisitos", "legal_publicacion_documentos",
                "legal_requisito_conjuntos_actuales", "legal_requisito_conjuntos", "legal_requisito_conjunto_miembros",
                "legal_requisito_lineas", "legal_requisito_audiencias", "legal_requisito_versiones", "legal_requisito_documentos",
                "legal_documento_lineas", "legal_documento_versiones", "legal_documento_contextos", "legal_documento_vigentes",
                "legal_requisito_agregados", "legal_requisito_agregado_scopes", "legal_aceptacion_lotes", "legal_aceptaciones",
                "legal_aceptacion_documentos", "legal_requisito_transiciones", "legal_documento_transiciones",
                "legal_aceptacion_metadatos", "legal_idempotencia_resultados", "legal_idempotencia_sin_actos",
                "legal_idempotencia_sin_actos_referencias");
        assertThat(restricted.queryForList("""
                SELECT relname FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
                 WHERE n.nspname='public' AND relkind IN ('r','p') AND pg_catalog.has_table_privilege(c.oid,'INSERT')
                """, String.class)).containsExactlyInAnyOrder("legal_requisito_agregados", "legal_requisito_agregado_scopes",
                "legal_aceptacion_lotes", "legal_aceptaciones", "legal_aceptacion_documentos", "legal_aceptacion_metadatos",
                "legal_aceptacion_metadatos_cifrados", "legal_idempotencia_resultados", "legal_idempotencia_sin_actos",
                "legal_idempotencia_sin_actos_referencias");
        assertThat(restricted.queryForList("""
                SELECT relname||'.'||attname FROM pg_catalog.pg_class c
                JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
                JOIN pg_catalog.pg_attribute a ON a.attrelid=c.oid
                 WHERE n.nspname='public' AND attnum>0 AND NOT attisdropped AND relkind IN ('r','p')
                   AND pg_catalog.has_column_privilege(c.oid, a.attnum, 'UPDATE') ORDER BY 1
                """, String.class)).containsExactly("legal_aceptacion_lotes.id", "legal_aceptacion_metadatos.lote_id",
                "legal_aceptaciones.id", "legal_idempotencia_sin_actos.id", "legal_requisito_agregados.id",
                "legal_requisito_conjuntos_actuales.conjunto_id", "talleres.id", "users.id");
        assertThat(restricted.queryForList("""
                SELECT attname FROM pg_catalog.pg_attribute
                 WHERE attrelid='public.legal_aceptacion_metadatos_cifrados'::regclass AND attnum>0 AND NOT attisdropped
                   AND pg_catalog.has_column_privilege(attrelid, attnum, 'SELECT') ORDER BY 1
                """, String.class)).containsExactly("lote_id", "tipo", "tombstone_en");
        assertThat(restricted.queryForObject("""
                SELECT count(*) FROM pg_catalog.pg_class WHERE relkind='S'
                  AND (pg_catalog.has_sequence_privilege(oid,'USAGE') OR pg_catalog.has_sequence_privilege(oid,'SELECT')
                       OR pg_catalog.has_sequence_privilege(oid,'UPDATE'))
                """, Integer.class)).isZero();
        assertThat(restricted.queryForObject("SELECT current_user = session_user AND current_user = ?", Boolean.class, ROLE)).isTrue();
    }

    @ParameterizedTest @ValueSource(strings = {"legal_aceptacion_lotes", "legal_aceptaciones", "legal_aceptacion_documentos",
            "legal_aceptacion_metadatos", "legal_aceptacion_metadatos_cifrados", "legal_idempotencia_resultados",
            "legal_idempotencia_sin_actos", "legal_idempotencia_sin_actos_referencias"})
    void eachAdditionalInsertIsMandatory(String table) {
        drift("REVOKE INSERT ON " + table + " FROM " + ROLE, "GRANT INSERT ON " + table + " TO " + ROLE);
    }

    @ParameterizedTest @ValueSource(strings = {"legal_aceptacion_metadatos", "legal_idempotencia_resultados",
            "legal_idempotencia_sin_actos", "legal_idempotencia_sin_actos_referencias"})
    void eachResultAndHeaderReadIsMandatory(String table) {
        drift("REVOKE SELECT ON " + table + " FROM " + ROLE, "GRANT SELECT ON " + table + " TO " + ROLE);
    }

    @ParameterizedTest @ValueSource(strings = {"lote_id", "tipo", "tombstone_en"})
    void onlyRequiredMetadataColumnsAreMandatory(String column) {
        drift("REVOKE SELECT (" + column + ") ON legal_aceptacion_metadatos_cifrados FROM " + ROLE,
                "GRANT SELECT (" + column + ") ON legal_aceptacion_metadatos_cifrados TO " + ROLE);
    }

    @ParameterizedTest @ValueSource(strings = {"ciphertext", "tag", "nonce", "key_version", "id", "longitud_original"})
    void encryptedPayloadReadsAreDeniedAndEvenOneColumnFailsPreflight(String column) {
        sqlState(() -> restricted.queryForList("SELECT " + column + " FROM legal_aceptacion_metadatos_cifrados"), "42501");
        drift("GRANT SELECT (" + column + ") ON legal_aceptacion_metadatos_cifrados TO " + ROLE,
                "REVOKE SELECT (" + column + ") ON legal_aceptacion_metadatos_cifrados FROM " + ROLE);
    }

    @Test void deniedCapabilitiesCannotBeRecoveredThroughPublicColumnsFunctionsOrGrantOptions() {
        for (String table : List.of("users", "talleres", "suscripciones")) {
            sqlState(() -> restricted.update("INSERT INTO " + table + " DEFAULT VALUES"), "42501");
            drift("GRANT INSERT ON " + table + " TO " + ROLE, "REVOKE INSERT ON " + table + " FROM " + ROLE);
        }
        sqlState(() -> restricted.queryForList("SELECT email, password FROM users"), "42501");
        sqlState(() -> restricted.execute("DELETE FROM legal_idempotencia_resultados"), "42501");
        sqlState(() -> restricted.execute("UPDATE legal_aceptaciones SET afirmacion = afirmacion"), "42501");
        sqlState(() -> restricted.execute("SELECT pg_catalog.pg_advisory_lock(123::bigint)"), "42501");
        sqlState(() -> restricted.execute("CREATE TEMP TABLE unauthorized(id integer)"), "42501");
        drift("GRANT SELECT (email) ON users TO " + ROLE, "REVOKE SELECT (email) ON users FROM " + ROLE);
        drift("GRANT DELETE ON legal_idempotencia_resultados TO " + ROLE, "REVOKE DELETE ON legal_idempotencia_resultados FROM " + ROLE);
        drift("GRANT SELECT ON legal_aceptaciones TO PUBLIC", "REVOKE SELECT ON legal_aceptaciones FROM PUBLIC");
        drift("GRANT INSERT ON legal_aceptaciones TO " + ROLE + " WITH GRANT OPTION",
                "REVOKE GRANT OPTION FOR INSERT ON legal_aceptaciones FROM " + ROLE);
        drift("GRANT EXECUTE ON FUNCTION legal_validar_conjuntos_actuales() TO " + ROLE,
                "REVOKE EXECUTE ON FUNCTION legal_validar_conjuntos_actuales() FROM " + ROLE);
    }

    @Test void mandatoryFunctionClosureAndLockColumnsAreAccreditedIndividually() {
        for (String function : LegalAcceptancePrivilegeVerifier.PRIVILEGED_FUNCTIONS) {
            drift("REVOKE EXECUTE ON FUNCTION " + function + " FROM " + ROLE,
                    "GRANT EXECUTE ON FUNCTION " + function + " TO " + ROLE);
        }
        LegalAcceptancePrivilegeVerifier.UPDATE_COLUMNS.forEach((table, columns) -> columns.forEach(column ->
                drift("REVOKE UPDATE (" + column + ") ON " + table + " FROM " + ROLE,
                        "GRANT UPDATE (" + column + ") ON " + table + " TO " + ROLE)));
    }

    @Test void systemPrivilegesMembershipOwnershipAndSearchPathFailClosed() {
        drift("ALTER ROLE " + ROLE + " INHERIT", "ALTER ROLE " + ROLE + " NOINHERIT");
        drift("GRANT pg_read_all_data TO " + ROLE, "REVOKE pg_read_all_data FROM " + ROLE);
        drift("GRANT USAGE ON SEQUENCE users_id_seq TO " + ROLE, "REVOKE USAGE ON SEQUENCE users_id_seq FROM " + ROLE);
        drift("GRANT SET ON PARAMETER session_replication_role TO " + ROLE,
                "REVOKE SET ON PARAMETER session_replication_role FROM " + ROLE);
        drift("GRANT CREATE ON SCHEMA public TO " + ROLE, "REVOKE CREATE ON SCHEMA public FROM " + ROLE);
        drift("ALTER ROLE " + ROLE + " IN DATABASE " + POSTGRES.getDatabaseName() + " SET search_path=public",
                "ALTER ROLE " + ROLE + " IN DATABASE " + POSTGRES.getDatabaseName() + " SET search_path=pg_catalog,public,pg_temp");
        assertThatThrownBy(() -> new LegalAcceptancePrivilegeVerifier(restricted, "foreign", "public").verify())
                .isInstanceOf(LegalEditorialOperationalException.class);
    }

    @Test void roleFixtureRefusesReuseBeforeChangingGrants() {
        assertThatThrownBy(() -> LegalRestrictedAcceptanceRoleFixture.provision(owner, ROLE, "other-fixture"))
                .isInstanceOf(IllegalStateException.class);
        verifier.verify();
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
