package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class LegalPublicDocumentPrivilegeVerifierIT {

    private static final String ROLE = "ordenfix_legal_public_document_it";
    private static final String PASSWORD = "legal-public-document-test-only";
    private static final String SCHEMA = "public";
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("ordenfix_legal_public_document_privileges")
                    .withUsername("ordenfix")
                    .withPassword("ordenfix");

    private static JdbcTemplate owner;
    private static JdbcTemplate restricted;
    private static LegalPublicDocumentPrivilegeVerifier verifier;

    @BeforeAll
    static void migrateAndProvisionRestrictedRole() {
        POSTGRES.start();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        owner = jdbc(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        LegalRestrictedPublicDocumentRoleFixture.Credentials credentials =
                new LegalRestrictedPublicDocumentRoleFixture(
                        owner, POSTGRES.getJdbcUrl(), ROLE, PASSWORD, POSTGRES.getDriverClassName())
                        .provisionAndVerify();
        restricted = jdbc(credentials.jdbcUrl(), credentials.username(), credentials.password());
        verifier = new LegalPublicDocumentPrivilegeVerifier(restricted, ROLE, SCHEMA);
    }

    @AfterAll
    static void stopContainer() {
        POSTGRES.stop();
    }

    @Test
    void exactReaderAccreditsFrozenV27AndV28WithoutEvidenceOrMaterializerReads() {
        new LegalV28AggregateSchemaVerifier(restricted, SCHEMA).verify();
        verifier.verify();

        assertThat(restricted.queryForObject(
                "SELECT pg_catalog.current_setting('server_version_num')::integer / 10000",
                Integer.class)).isEqualTo(16);
        assertThat(verifier.expectedRole()).isEqualTo(ROLE);
        assertThat(verifier.usesJdbc(restricted)).isTrue();
        assertThat(verifier.usesJdbc(owner)).isFalse();
        assertThat(restricted.queryForList("""
                SELECT relation.relname
                  FROM pg_catalog.pg_class relation
                  JOIN pg_catalog.pg_namespace namespace
                    ON namespace.oid = relation.relnamespace
                 WHERE namespace.nspname = 'public'
                   AND relation.relkind IN ('r', 'p', 'v', 'm', 'f')
                   AND pg_catalog.has_table_privilege(relation.oid, 'SELECT')
                 ORDER BY relation.relname
                """, String.class)).containsExactly(
                "flyway_schema_history",
                "legal_documento_contextos",
                "legal_documento_lineas",
                "legal_documento_versiones");
        for (String table : List.of("legal_documento_lineas", "legal_documento_versiones",
                "legal_documento_contextos", "flyway_schema_history")) {
            assertThat(restricted.queryForObject(
                    "SELECT count(*) FROM public." + quoteIdentifier(table), Long.class))
                    .isNotNull();
        }
        for (String table : List.of("users", "legal_aceptaciones", "legal_aceptacion_metadatos",
                "legal_requisito_agregados", "legal_requisito_conjuntos_actuales",
                "legal_publicaciones")) {
            assertSqlState(() -> restricted.queryForList(
                    "SELECT * FROM public." + quoteIdentifier(table) + " WHERE false"), "42501");
        }
    }

    @Test
    void postgresDeniesDmlRowLocksSequencesDdlAndSessionLocks() {
        assertSqlState(() -> restricted.update(
                "INSERT INTO public.legal_documento_versiones DEFAULT VALUES"), "42501");
        assertSqlState(() -> restricted.update(
                "UPDATE public.legal_documento_versiones SET titulo = titulo"), "42501");
        assertSqlState(() -> restricted.update(
                "DELETE FROM public.legal_documento_contextos"), "42501");
        assertSqlState(() -> restricted.execute(
                "TRUNCATE public.legal_documento_contextos"), "42501");
        assertSqlState(() -> restricted.queryForList(
                "SELECT id FROM public.legal_documento_versiones FOR SHARE"), "42501");
        assertSqlState(() -> restricted.queryForObject(
                "SELECT pg_catalog.nextval('public.legal_documento_contextos_id_seq')",
                Long.class), "42501");
        assertSqlState(() -> restricted.execute(
                "CREATE TABLE public.ordenfix_public_document_denied (id integer)"), "42501");
        assertSqlState(() -> restricted.execute(
                "CREATE TEMP TABLE ordenfix_public_document_denied (id integer)"), "42501");
        assertSqlState(() -> restricted.queryForObject(
                "SELECT pg_catalog.pg_try_advisory_lock(1::bigint)", Boolean.class), "42501");
        assertSqlState(() -> restricted.queryForObject(
                "SELECT pg_catalog.lo_create(0)::bigint", Long.class), "42501");
        assertThat(restricted.queryForObject("""
                SELECT pg_catalog.has_function_privilege(
                    'pg_catalog.pg_advisory_xact_lock_shared(bigint)', 'EXECUTE')
                """, Boolean.class)).isTrue();
    }

    @Test
    void everyApplicationFunctionIncludingLegacyPublicDefaultsIsEffectivelyDenied() {
        assertThat(restricted.queryForObject("""
                SELECT count(*)
                  FROM pg_catalog.pg_proc function
                  JOIN pg_catalog.pg_namespace namespace
                    ON namespace.oid = function.pronamespace
                 WHERE namespace.nspname = 'public'
                   AND pg_catalog.has_function_privilege(function.oid, 'EXECUTE')
                """, Long.class)).isZero();
        assertSqlState(() -> restricted.execute(
                "SELECT public.legal_exigir_read_committed()"), "42501");
        assertSqlState(() -> restricted.execute(
                "SELECT public.legal_validar_requisito_agregado(NULL::uuid)"), "42501");
    }

    @ParameterizedTest
    @ValueSource(strings = {"legal_documento_lineas", "legal_documento_versiones",
            "legal_documento_contextos", "flyway_schema_history"})
    void missingRequiredReadFailsClosed(String table) {
        assertPrivilegeDrift(
                "REVOKE SELECT ON TABLE public." + quoteIdentifier(table) + " FROM " + quotedRole(),
                "GRANT SELECT ON TABLE public." + quoteIdentifier(table) + " TO " + quotedRole());
    }

    @ParameterizedTest
    @ValueSource(strings = {"users", "legal_aceptaciones", "legal_aceptacion_metadatos",
            "legal_requisito_agregados", "legal_requisito_conjuntos_actuales", "legal_publicaciones"})
    void readsOutsideTheDocumentArchiveFailClosed(String table) {
        assertPrivilegeDrift(
                "GRANT SELECT ON TABLE public." + quoteIdentifier(table) + " TO " + quotedRole(),
                "REVOKE SELECT ON TABLE public." + quoteIdentifier(table) + " FROM " + quotedRole());
    }

    @ParameterizedTest
    @ValueSource(strings = {"INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER"})
    void anyTableMutationCapabilityFailsClosed(String privilege) {
        assertPrivilegeDrift(
                "GRANT " + privilege + " ON TABLE public.legal_documento_versiones TO " + quotedRole(),
                "REVOKE " + privilege + " ON TABLE public.legal_documento_versiones FROM " + quotedRole());
    }

    @Test
    void columnPrivilegesAndSelectGrantOptionCannotBypassTheReadAllowlist() {
        assertPrivilegeDrift(
                "GRANT SELECT (id) ON TABLE public.users TO " + quotedRole(),
                "REVOKE SELECT (id) ON TABLE public.users FROM " + quotedRole());
        assertPrivilegeDrift(
                "GRANT UPDATE (titulo) ON TABLE public.legal_documento_versiones TO " + quotedRole(),
                "REVOKE UPDATE (titulo) ON TABLE public.legal_documento_versiones FROM " + quotedRole());
        assertPrivilegeDrift(
                "GRANT INSERT (titulo) ON TABLE public.legal_documento_versiones TO " + quotedRole(),
                "REVOKE INSERT (titulo) ON TABLE public.legal_documento_versiones FROM " + quotedRole());
        assertPrivilegeDrift(
                "GRANT SELECT ON TABLE public.legal_documento_versiones TO "
                        + quotedRole() + " WITH GRANT OPTION",
                "REVOKE GRANT OPTION FOR SELECT ON TABLE public.legal_documento_versiones FROM "
                        + quotedRole());
    }

    @Test
    void publicReadsWritesAndLegalExecuteFailClosedEvenWithoutDirectGrants() {
        assertPrivilegeDrift(
                "GRANT SELECT ON TABLE public.users TO PUBLIC",
                "REVOKE SELECT ON TABLE public.users FROM PUBLIC");
        assertPrivilegeDrift(
                "GRANT SELECT ON TABLE public.legal_documento_lineas TO PUBLIC",
                "REVOKE SELECT ON TABLE public.legal_documento_lineas FROM PUBLIC");
        assertPrivilegeDrift(
                "GRANT UPDATE (titulo) ON TABLE public.legal_documento_versiones TO PUBLIC",
                "REVOKE UPDATE (titulo) ON TABLE public.legal_documento_versiones FROM PUBLIC");
        assertPrivilegeDrift(
                "GRANT EXECUTE ON FUNCTION public.legal_exigir_read_committed() TO PUBLIC",
                "REVOKE EXECUTE ON FUNCTION public.legal_exigir_read_committed() FROM PUBLIC");
        assertPrivilegeDrift(
                "GRANT EXECUTE ON FUNCTION public.legal_validar_requisito_agregado(uuid) TO PUBLIC",
                "REVOKE EXECUTE ON FUNCTION public.legal_validar_requisito_agregado(uuid) FROM PUBLIC");
        assertPrivilegeDrift(
                "GRANT EXECUTE ON FUNCTION public.legal_exigir_read_committed() TO " + quotedRole(),
                "REVOKE EXECUTE ON FUNCTION public.legal_exigir_read_committed() FROM " + quotedRole());
    }

    @Test
    void elevatedAttributesAndReceivedOrDelegatedMembershipFailClosed() {
        assertPrivilegeDrift(
                "ALTER ROLE " + quotedRole() + " INHERIT",
                "ALTER ROLE " + quotedRole() + " NOINHERIT");
        assertPrivilegeDrift(
                "ALTER ROLE " + quotedRole() + " SUPERUSER",
                "ALTER ROLE " + quotedRole() + " NOSUPERUSER");
        assertPrivilegeDrift(
                "ALTER ROLE " + quotedRole() + " CREATEDB",
                "ALTER ROLE " + quotedRole() + " NOCREATEDB");
        assertPrivilegeDrift(
                "ALTER ROLE " + quotedRole() + " BYPASSRLS",
                "ALTER ROLE " + quotedRole() + " NOBYPASSRLS");
        String group = "ordenfix_public_document_membership_it";
        owner.execute("CREATE ROLE " + quoteIdentifier(group) + " NOLOGIN NOINHERIT");
        try {
            assertPrivilegeDrift(
                    "GRANT " + quoteIdentifier(group) + " TO " + quotedRole(),
                    "REVOKE " + quoteIdentifier(group) + " FROM " + quotedRole());
            assertPrivilegeDrift(
                    "GRANT " + quotedRole() + " TO " + quoteIdentifier(group),
                    "REVOKE " + quotedRole() + " FROM " + quoteIdentifier(group));
        } finally {
            owner.execute("DROP ROLE " + quoteIdentifier(group));
        }
    }

    @Test
    void tableOwnershipIsRejectedAndRestoredWithoutChangingTheReadContract() {
        String originalOwner = owner.queryForObject("""
                SELECT pg_catalog.pg_get_userbyid(relowner)
                  FROM pg_catalog.pg_class
                 WHERE oid = 'public.legal_documento_lineas'::pg_catalog.regclass
                """, String.class);
        assertThat(originalOwner).isNotNull();
        owner.execute("ALTER TABLE public.legal_documento_lineas OWNER TO " + quotedRole());
        try {
            assertPrivilegeFailure(verifier);
        } finally {
            owner.execute("ALTER TABLE public.legal_documento_lineas OWNER TO "
                    + quoteIdentifier(originalOwner));
            // Ownership reassignment can coalesce the old direct SELECT ACL with the owner ACL.
            owner.execute("GRANT SELECT ON TABLE public.legal_documento_lineas TO " + quotedRole());
        }
        verifier.verify();
        new LegalV28AggregateSchemaVerifier(restricted, SCHEMA).verify();
    }

    @Test
    void ownershipOfADisabledDatabaseStillFailsClosed() {
        String database = "ordenfix_public_document_disabled_it";
        owner.execute("CREATE DATABASE " + quoteIdentifier(database));
        try {
            owner.execute("ALTER DATABASE " + quoteIdentifier(database) + " OWNER TO " + quotedRole());
            owner.execute("ALTER DATABASE " + quoteIdentifier(database) + " ALLOW_CONNECTIONS false");
            assertPrivilegeFailure(verifier);
        } finally {
            owner.execute("ALTER DATABASE " + quoteIdentifier(database) + " OWNER TO "
                    + quoteIdentifier(POSTGRES.getUsername()));
            owner.execute("DROP DATABASE " + quoteIdentifier(database));
        }
        verifier.verify();
    }

    @Test
    void databaseSchemaSequenceAndParameterEscalationsFailClosed() {
        String database = quoteIdentifier(POSTGRES.getDatabaseName());
        assertPrivilegeDrift(
                "GRANT CONNECT ON DATABASE " + database + " TO PUBLIC",
                "REVOKE CONNECT ON DATABASE " + database + " FROM PUBLIC");
        assertPrivilegeDrift(
                "GRANT TEMPORARY ON DATABASE " + database + " TO " + quotedRole(),
                "REVOKE TEMPORARY ON DATABASE " + database + " FROM " + quotedRole());
        assertPrivilegeDrift(
                "GRANT CREATE ON SCHEMA public TO " + quotedRole(),
                "REVOKE CREATE ON SCHEMA public FROM " + quotedRole());
        assertPrivilegeDrift(
                "GRANT USAGE ON SEQUENCE public.legal_documento_contextos_id_seq TO " + quotedRole(),
                "REVOKE USAGE ON SEQUENCE public.legal_documento_contextos_id_seq FROM " + quotedRole());
        assertPrivilegeDrift(
                "GRANT SET ON PARAMETER session_replication_role TO " + quotedRole(),
                "REVOKE SET ON PARAMETER session_replication_role FROM " + quotedRole());
    }

    @Test
    void systemAclAndNewSecurityDefinerCapabilitiesFailClosed() {
        assertPrivilegeDrift(
                "GRANT CREATE ON SCHEMA information_schema TO " + quotedRole(),
                "REVOKE CREATE ON SCHEMA information_schema FROM " + quotedRole());
        assertPrivilegeDrift(
                "GRANT UPDATE (tgenabled) ON pg_catalog.pg_trigger TO " + quotedRole(),
                "REVOKE UPDATE (tgenabled) ON pg_catalog.pg_trigger FROM " + quotedRole());
        assertPrivilegeDrift(
                "GRANT SELECT ON pg_catalog.pg_authid TO PUBLIC",
                "REVOKE SELECT ON pg_catalog.pg_authid FROM PUBLIC");
        assertPrivilegeDrift(
                "GRANT EXECUTE ON FUNCTION pg_catalog.pg_read_file(text) TO PUBLIC",
                "REVOKE EXECUTE ON FUNCTION pg_catalog.pg_read_file(text) FROM PUBLIC");
        assertPrivilegeDrift(
                "CREATE FUNCTION pg_catalog.ordenfix_public_document_backdoor() RETURNS void "
                        + "LANGUAGE sql SECURITY DEFINER AS 'SELECT NULL::void'",
                "DROP FUNCTION pg_catalog.ordenfix_public_document_backdoor()");
        assertPrivilegeDrift(
                "GRANT EXECUTE ON FUNCTION pg_catalog.pg_advisory_lock(bigint) TO " + quotedRole(),
                "REVOKE EXECUTE ON FUNCTION pg_catalog.pg_advisory_lock(bigint) FROM " + quotedRole());
        assertPrivilegeDrift(
                "GRANT EXECUTE ON FUNCTION pg_catalog.lo_create(oid) TO PUBLIC",
                "REVOKE EXECUTE ON FUNCTION pg_catalog.lo_create(oid) FROM PUBLIC");
    }

    @Test
    void unexpectedIdentityAndSearchPathFailClosed() {
        assertPrivilegeFailure(new LegalPublicDocumentPrivilegeVerifier(owner, ROLE, SCHEMA));
        assertPrivilegeFailure(new LegalPublicDocumentPrivilegeVerifier(
                restricted, "ordenfix_unexpected_reader", SCHEMA));
        String database = quoteIdentifier(POSTGRES.getDatabaseName());
        owner.execute("ALTER ROLE " + quotedRole() + " IN DATABASE " + database
                + " SET search_path TO information_schema, public, pg_temp");
        try {
            assertPrivilegeFailure(new LegalPublicDocumentPrivilegeVerifier(
                    jdbc(POSTGRES.getJdbcUrl(), ROLE, PASSWORD), ROLE, SCHEMA));
        } finally {
            owner.execute("ALTER ROLE " + quotedRole() + " IN DATABASE " + database
                    + " SET search_path TO pg_catalog, public, pg_temp");
        }
        verifier.verify();
    }

    @Test
    void provisioningRefusesToReplaceAnExistingRole() {
        assertThatThrownBy(() -> new LegalRestrictedPublicDocumentRoleFixture(
                owner, POSTGRES.getJdbcUrl(), ROLE, PASSWORD, POSTGRES.getDriverClassName())
                .provisionAndVerify())
                .isInstanceOf(IllegalStateException.class);
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

    private static void assertPrivilegeFailure(LegalPublicDocumentPrivilegeVerifier candidate) {
        assertThatThrownBy(candidate::verify)
                .isInstanceOfSatisfying(LegalEditorialOperationalException.class,
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

    private static String quotedRole() {
        return quoteIdentifier(ROLE);
    }

    private static String quoteIdentifier(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }
}
