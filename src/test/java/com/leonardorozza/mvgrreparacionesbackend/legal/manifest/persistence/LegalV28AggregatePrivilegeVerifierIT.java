package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class LegalV28AggregatePrivilegeVerifierIT {

    private static final String ROLE = "ordenfix_legal_aggregate_it";
    private static final String PASSWORD = "legal-aggregate-test-only";
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("ordenfix_legal_aggregate_privileges")
                    .withUsername("ordenfix")
                    .withPassword("ordenfix");

    private static JdbcTemplate owner;
    private static JdbcTemplate restricted;
    private static LegalV28AggregatePrivilegeVerifier verifier;

    @BeforeAll
    static void migrateAndProvisionRestrictedRole() {
        POSTGRES.start();
        Flyway.configure()
                .dataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        owner = jdbc(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
        LegalRestrictedAggregateRoleFixture.Credentials credentials =
                new LegalRestrictedAggregateRoleFixture(
                        owner,
                        POSTGRES.getJdbcUrl(),
                        ROLE,
                        PASSWORD,
                        POSTGRES.getDriverClassName())
                        .provisionAndVerify();
        restricted = jdbc(
                credentials.jdbcUrl(),
                credentials.username(),
                credentials.password());
        verifier = new LegalV28AggregatePrivilegeVerifier(restricted, ROLE, "public");
    }

    @AfterAll
    static void stopContainer() {
        POSTGRES.stop();
    }

    @Test
    void exactRolePassesAndCannotAcquireSessionLocksOrMutateAcceptance() {
        verifier.verify();

        assertThat(restricted.queryForObject(
                "SELECT pg_catalog.has_function_privilege("
                        + "'pg_catalog.pg_advisory_xact_lock_shared(bigint)', 'EXECUTE')",
                Boolean.class)).isTrue();
        assertSqlState(() -> restricted.queryForObject(
                "SELECT pg_catalog.pg_try_advisory_lock(1::bigint)", Boolean.class), "42501");
        assertSqlState(() -> restricted.update(
                "INSERT INTO legal_aceptacion_lotes DEFAULT VALUES"), "42501");
        assertSqlState(() -> restricted.update(
                "DELETE FROM legal_aceptaciones"), "42501");
        assertSqlState(() -> restricted.update(
                "UPDATE legal_requisito_agregados SET scope_count = scope_count"), "42501");
        assertSqlState(() -> restricted.update(
                "DELETE FROM legal_requisito_agregado_scopes"), "42501");
    }

    @Test
    void extraRelationColumnAndAcceptanceDmlFailClosed() {
        assertPrivilegeDrift(
                "GRANT SELECT ON legal_aceptaciones TO " + quoteIdentifier(ROLE),
                "REVOKE SELECT ON legal_aceptaciones FROM " + quoteIdentifier(ROLE));
        assertPrivilegeDrift(
                "GRANT UPDATE (scope_count) ON legal_requisito_agregados TO "
                        + quoteIdentifier(ROLE),
                "REVOKE UPDATE (scope_count) ON legal_requisito_agregados FROM "
                        + quoteIdentifier(ROLE));
        assertPrivilegeDrift(
                "GRANT INSERT ON legal_aceptacion_lotes TO " + quoteIdentifier(ROLE),
                "REVOKE INSERT ON legal_aceptacion_lotes FROM " + quoteIdentifier(ROLE));
        assertPrivilegeDrift(
                "GRANT INSERT ON legal_requisito_agregados TO PUBLIC",
                "REVOKE INSERT ON legal_requisito_agregados FROM PUBLIC");
        assertPrivilegeDrift(
                "GRANT UPDATE (conjunto_id) ON legal_requisito_conjuntos_actuales TO PUBLIC",
                "REVOKE UPDATE (conjunto_id) ON legal_requisito_conjuntos_actuales FROM PUBLIC");
    }

    @Test
    void sessionAdvisoryAndPublicDependencyExecuteFailClosed() {
        assertPrivilegeDrift(
                "GRANT EXECUTE ON FUNCTION pg_catalog.pg_advisory_lock(bigint) TO "
                        + quoteIdentifier(ROLE),
                "REVOKE EXECUTE ON FUNCTION pg_catalog.pg_advisory_lock(bigint) FROM "
                        + quoteIdentifier(ROLE));
        assertPrivilegeDrift(
                "GRANT EXECUTE ON FUNCTION legal_validar_requisito_agregado_actual(uuid) "
                        + "TO PUBLIC",
                "REVOKE EXECUTE ON FUNCTION legal_validar_requisito_agregado_actual(uuid) "
                        + "FROM PUBLIC");
        assertPrivilegeDrift(
                "GRANT EXECUTE ON FUNCTION legal_exigir_read_committed() TO PUBLIC",
                "REVOKE EXECUTE ON FUNCTION legal_exigir_read_committed() FROM PUBLIC");
    }

    @Test
    void roleCapabilityAndParameterSettingFailClosed() {
        String database = owner.queryForObject(
                "SELECT pg_catalog.current_database()",
                String.class);
        assertPrivilegeDrift(
                "GRANT CONNECT ON DATABASE " + quoteIdentifier(database) + " TO PUBLIC",
                "REVOKE CONNECT ON DATABASE " + quoteIdentifier(database) + " FROM PUBLIC");
        assertPrivilegeDrift(
                "ALTER ROLE " + quoteIdentifier(ROLE) + " INHERIT",
                "ALTER ROLE " + quoteIdentifier(ROLE) + " NOINHERIT");
        assertPrivilegeDrift(
                "GRANT SET ON PARAMETER session_replication_role TO "
                        + quoteIdentifier(ROLE),
                "REVOKE SET ON PARAMETER session_replication_role FROM "
                        + quoteIdentifier(ROLE));
    }

    @Test
    void systemSchemaCreateAndResidualShadowFailClosed() {
        assertPrivilegeDrift(
                "GRANT CREATE ON SCHEMA information_schema TO "
                        + quoteIdentifier(ROLE),
                "REVOKE CREATE ON SCHEMA information_schema FROM "
                        + quoteIdentifier(ROLE));
        assertPrivilegeDrift(
                "GRANT UPDATE (tgenabled) ON pg_catalog.pg_trigger TO "
                        + quoteIdentifier(ROLE),
                "REVOKE UPDATE (tgenabled) ON pg_catalog.pg_trigger FROM "
                        + quoteIdentifier(ROLE));
        assertPrivilegeDrift(
                "GRANT SELECT ON pg_catalog.pg_authid TO PUBLIC",
                "REVOKE SELECT ON pg_catalog.pg_authid FROM PUBLIC");
        assertPrivilegeDrift(
                "GRANT SELECT ON information_schema.transforms TO PUBLIC",
                "REVOKE SELECT ON information_schema.transforms FROM PUBLIC");
        assertPrivilegeDrift(
                "GRANT EXECUTE ON FUNCTION pg_catalog.pg_read_file(text) TO PUBLIC",
                "REVOKE EXECUTE ON FUNCTION pg_catalog.pg_read_file(text) FROM PUBLIC");
        assertPrivilegeDrift(
                "CREATE FUNCTION pg_catalog.ordenfix_v28_backdoor() RETURNS void "
                        + "LANGUAGE sql SECURITY DEFINER AS 'SELECT NULL::void'",
                "DROP FUNCTION pg_catalog.ordenfix_v28_backdoor()");

        String shadow = "pg_catalog.legal_exigir_lock_editorial_v28()";
        owner.execute("GRANT CREATE ON SCHEMA pg_catalog TO " + quoteIdentifier(ROLE));
        try {
            restricted.execute("CREATE FUNCTION " + shadow
                    + " RETURNS void LANGUAGE sql AS 'SELECT NULL::void'");
        } finally {
            owner.execute("REVOKE CREATE ON SCHEMA pg_catalog FROM "
                    + quoteIdentifier(ROLE));
        }
        try {
            assertPrivilegeFailure(verifier);
        } finally {
            owner.execute("DROP FUNCTION " + shadow);
        }
        verifier.verify();
    }

    @Test
    void deviatedSearchPathFailsClosed() {
        String database = owner.queryForObject(
                "SELECT pg_catalog.current_database()",
                String.class);
        owner.execute("ALTER ROLE " + quoteIdentifier(ROLE)
                + " IN DATABASE " + quoteIdentifier(database)
                + " SET search_path TO information_schema, public, pg_temp");
        try {
            assertPrivilegeFailure(new LegalV28AggregatePrivilegeVerifier(
                    jdbc(POSTGRES.getJdbcUrl(), ROLE, PASSWORD),
                    ROLE,
                    "public"));
        } finally {
            owner.execute("ALTER ROLE " + quoteIdentifier(ROLE)
                    + " IN DATABASE " + quoteIdentifier(database)
                    + " SET search_path TO pg_catalog, public, pg_temp");
        }
        verifier.verify();
    }

    private static void assertPrivilegeDrift(String mutation, String restoration) {
        owner.execute(mutation);
        try {
            assertPrivilegeFailure(verifier);
        } finally {
            owner.execute(restoration);
        }
        verifier.verify();
    }

    private static void assertPrivilegeFailure(
            LegalV28AggregatePrivilegeVerifier candidate) {
        assertThatThrownBy(candidate::verify)
                .isInstanceOfSatisfying(
                        LegalEditorialOperationalException.class,
                        failure -> assertThat(failure.issue().code())
                                .isEqualTo(LegalManifestIssueCode.ROLE_PRIVILEGE_DRIFT));
    }

    private static void assertSqlState(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable operation,
            String expected) {
        Throwable current = catchThrowable(operation);
        assertThat(current).isNotNull();
        while (current != null && !(current instanceof SQLException)) {
            current = current.getCause();
        }
        assertThat(current).isInstanceOf(SQLException.class);
        assertThat(((SQLException) current).getSQLState()).isEqualTo(expected);
    }

    private static JdbcTemplate jdbc(String url, String username, String password) {
        return new JdbcTemplate(new DriverManagerDataSource(url, username, password));
    }

    private static String quoteIdentifier(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }
}
