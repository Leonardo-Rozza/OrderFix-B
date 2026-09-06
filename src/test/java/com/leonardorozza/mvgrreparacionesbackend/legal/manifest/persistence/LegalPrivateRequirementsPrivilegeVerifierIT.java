package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
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
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/** Exact PostgreSQL capabilities; store/configuration behavior has a separate context IT. */
@Execution(ExecutionMode.SAME_THREAD)
class LegalPrivateRequirementsPrivilegeVerifierIT {

    private static final String ROLE = "ordenfix_private_requirements_privilege_it";
    private static final String PASSWORD = "private-requirements-privilege-test-only";
    private static final String SCHEMA = "public";
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_private_requirements_privileges")
            .withUsername("ordenfix").withPassword("ordenfix");
    @TempDir
    static Path directory;
    private static JdbcTemplate owner;
    private static JdbcTemplate restricted;
    private static LegalPrivateRequirementsPrivilegeVerifier verifier;

    @BeforeAll
    static void migrateSeedAndProvision() throws Exception {
        POSTGRES.start();
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
        owner = jdbc(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        LegalPrivateRequirementsITSupport.seedCatalog(owner, directory,
                LegalPrivateRequirementsPrivilegeVerifierIT.class);
        var credentials = LegalPrivateRequirementsITSupport.provision(owner, ROLE, PASSWORD);
        assertThat(credentials.toString()).doesNotContain(PASSWORD, POSTGRES.getJdbcUrl(), ROLE);
        restricted = jdbc(credentials.jdbcUrl(), credentials.username(), credentials.password());
        verifier = new LegalPrivateRequirementsPrivilegeVerifier(restricted, ROLE, SCHEMA);
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @Test
    void exactConsumerAccreditsV29AndReads22TablesPlusSevenActorColumns() {
        new LegalV29AcceptanceSchemaVerifier(restricted, SCHEMA).verify();
        verifier.verify();
        assertThat(restricted.queryForObject(
                "SELECT pg_catalog.current_setting('server_version_num')::integer / 10000", Integer.class))
                .isEqualTo(16);
        assertThat(verifier.expectedRole()).isEqualTo(ROLE);
        assertThat(verifier.usesJdbc(restricted)).isTrue();
        assertThat(verifier.usesJdbc(owner)).isFalse();
        assertThat(restricted.queryForList("""
                SELECT relation.relname FROM pg_catalog.pg_class relation
                JOIN pg_catalog.pg_namespace namespace ON namespace.oid = relation.relnamespace
                 WHERE namespace.nspname = 'public' AND relation.relkind IN ('r', 'p', 'v', 'm', 'f')
                   AND pg_catalog.has_table_privilege(relation.oid, 'SELECT')
                 ORDER BY relation.relname
                """, String.class)).containsExactlyInAnyOrder(
                "flyway_schema_history", "legal_publicaciones", "legal_publicacion_requisitos",
                "legal_publicacion_documentos", "legal_requisito_conjuntos_actuales",
                "legal_requisito_conjuntos", "legal_requisito_conjunto_miembros", "legal_requisito_lineas",
                "legal_requisito_audiencias", "legal_requisito_versiones", "legal_requisito_documentos",
                "legal_documento_lineas", "legal_documento_versiones", "legal_documento_contextos",
                "legal_documento_vigentes", "legal_requisito_agregados", "legal_requisito_agregado_scopes",
                "legal_aceptacion_lotes", "legal_aceptaciones", "legal_aceptacion_documentos",
                "legal_requisito_transiciones", "legal_documento_transiciones");
        for (String table : LegalPrivateRequirementsPrivilegeVerifier.READ_TABLES) {
            assertThat(restricted.queryForObject("SELECT count(*) FROM public." + quoteIdentifier(table),
                    Long.class)).isNotNull();
        }
        assertThat(restricted.queryForList("""
                SELECT relation.relname FROM pg_catalog.pg_class relation
                JOIN pg_catalog.pg_namespace namespace ON namespace.oid = relation.relnamespace
                 WHERE namespace.nspname = 'public' AND relation.relkind IN ('r', 'p')
                   AND pg_catalog.has_table_privilege(relation.oid, 'INSERT')
                 ORDER BY relation.relname
                """, String.class)).containsExactly("legal_requisito_agregado_scopes", "legal_requisito_agregados");
        assertThat(restricted.queryForList("""
                SELECT relation.relname || '.' || attribute.attname FROM pg_catalog.pg_class relation
                JOIN pg_catalog.pg_namespace namespace ON namespace.oid = relation.relnamespace
                JOIN pg_catalog.pg_attribute attribute ON attribute.attrelid = relation.oid
                 WHERE namespace.nspname = 'public' AND relation.relkind IN ('r', 'p')
                   AND attribute.attnum > 0 AND NOT attribute.attisdropped
                   AND pg_catalog.has_column_privilege(relation.oid, attribute.attnum, 'UPDATE')
                 ORDER BY 1
                """, String.class)).containsExactly("legal_requisito_agregados.id",
                "legal_requisito_conjuntos_actuales.conjunto_id", "talleres.id", "users.id");
        assertThat(restricted.queryForList("""
                SELECT relation.relname || '.' || attribute.attname FROM pg_catalog.pg_class relation
                JOIN pg_catalog.pg_namespace namespace ON namespace.oid = relation.relnamespace
                JOIN pg_catalog.pg_attribute attribute ON attribute.attrelid = relation.oid
                 WHERE namespace.nspname = 'public' AND relation.relname IN ('users', 'talleres')
                   AND attribute.attnum > 0 AND NOT attribute.attisdropped
                   AND pg_catalog.has_column_privilege(relation.oid, attribute.attnum, 'SELECT')
                 ORDER BY 1
                """, String.class)).containsExactly("talleres.activo", "talleres.id", "users.active",
                "users.id", "users.role", "users.taller_id", "users.token_version");
    }

    @Test
    void onlySevenAggregateFunctionsAreExecutableIncludingNoV29WriterFunction() {
        assertThat(restricted.queryForList("""
                SELECT function.proname || '(' || pg_catalog.oidvectortypes(function.proargtypes) || ')'
                  FROM pg_catalog.pg_proc function
                  JOIN pg_catalog.pg_namespace namespace ON namespace.oid = function.pronamespace
                 WHERE namespace.nspname = 'public'
                   AND pg_catalog.has_function_privilege(function.oid, 'EXECUTE')
                 ORDER BY 1
                """, String.class)).containsExactlyInAnyOrder(
                "legal_rechazar_update_delete()", "legal_exigir_read_committed()",
                "legal_exigir_lock_editorial_v28()", "legal_requisito_agregado_insert_guard()",
                "legal_requisito_agregado_scope_insert_guard()", "legal_validar_requisito_agregado(uuid)",
                "legal_requisito_agregado_constraint_guard()");
        assertSqlState(() -> restricted.execute(
                "SELECT public.legal_validar_requisito_agregado_actual(NULL::uuid)"), "42501");
        assertSqlState(() -> restricted.execute(
                "SELECT public.legal_validar_conjuntos_actuales()"), "42501");
        assertThat(restricted.queryForObject("""
                SELECT pg_catalog.has_function_privilege(
                    'pg_catalog.pg_advisory_xact_lock_shared(bigint)', 'EXECUTE')
                """, Boolean.class)).isTrue();
    }

    @Test
    void pointerForShareIsAllowedButItsNoOpAndActualUpdatesStillReachTheFrozenGuard() throws Exception {
        UUID current = restricted.queryForObject("""
                SELECT conjunto_id FROM public.legal_requisito_conjuntos_actuales
                 WHERE locale = 'es-AR' AND contexto = 'REGISTRO' AND audiencia = 'ADMIN_TITULAR'
                """, UUID.class);
        assertThat(current).isNotNull();
        try (Connection connection = restricted.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try {
                try (PreparedStatement lock = connection.prepareStatement("""
                        SELECT pg_catalog.pg_advisory_xact_lock_shared(pg_catalog.hashtextextended(?, 0))
                        """)) {
                    lock.setString(1, LegalManifestDatabaseGate.EDITORIAL_LOCK_NAME);
                    lock.execute();
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT conjunto_id FROM public.legal_requisito_conjuntos_actuales
                         WHERE locale = 'es-AR' AND contexto = 'REGISTRO' AND audiencia = 'ADMIN_TITULAR'
                         FOR SHARE
                        """); ResultSet row = statement.executeQuery()) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getObject(1, UUID.class)).isEqualTo(current);
                    assertThat(row.next()).isFalse();
                }
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE public.legal_requisito_conjuntos_actuales SET conjunto_id = conjunto_id
                         WHERE locale = 'es-AR' AND contexto = 'REGISTRO' AND audiencia = 'ADMIN_TITULAR'
                        """)) {
                    assertSqlState(update::executeUpdate, "23514");
                }
            } finally {
                connection.rollback();
            }
        }
        assertSqlState(() -> restricted.update("""
                UPDATE public.legal_requisito_conjuntos_actuales SET conjunto_id = ?
                 WHERE locale = 'es-AR' AND contexto = 'REGISTRO' AND audiencia = 'ADMIN_TITULAR'
                """, UUID.randomUUID()), "23514");
        assertThat(restricted.queryForObject("""
                SELECT conjunto_id FROM public.legal_requisito_conjuntos_actuales
                 WHERE locale = 'es-AR' AND contexto = 'REGISTRO' AND audiencia = 'ADMIN_TITULAR'
                """, UUID.class)).isEqualTo(current);
        verifier.verify();
    }

    @Test
    void postgresDeniesPersonalDataEvidenceExtraDmlRowLocksSequencesDdlAndSessionLocks() {
        for (String table : List.of("users", "talleres", "suscripciones",
                "legal_aceptacion_metadatos", "legal_aceptacion_metadatos_cifrados",
                "legal_idempotencia_resultados", "legal_idempotencia_sin_actos",
                "legal_idempotencia_sin_actos_referencias", "legal_documento_reemplazo_lotes")) {
            assertSqlState(() -> restricted.queryForList(
                    "SELECT * FROM public." + quoteIdentifier(table) + " WHERE false"), "42501");
        }
        assertSqlState(() -> restricted.update("INSERT INTO public.legal_aceptacion_lotes DEFAULT VALUES"), "42501");
        assertSqlState(() -> restricted.update("INSERT INTO public.legal_documento_versiones DEFAULT VALUES"), "42501");
        assertSqlState(() -> restricted.update("UPDATE public.legal_documento_versiones SET titulo = titulo"), "42501");
        assertSqlState(() -> restricted.update("UPDATE public.legal_requisito_agregados SET scope_count = scope_count"), "42501");
        assertSqlState(() -> restricted.update("DELETE FROM public.legal_requisito_agregado_scopes"), "42501");
        assertSqlState(() -> restricted.execute("TRUNCATE public.legal_requisito_agregados"), "42501");
        assertSqlState(() -> restricted.queryForList("SELECT id FROM public.legal_documento_versiones FOR SHARE"), "42501");
        assertSqlState(() -> restricted.queryForObject(
                "SELECT pg_catalog.nextval('public.legal_documento_contextos_id_seq')", Long.class), "42501");
        assertSqlState(() -> restricted.execute("CREATE TABLE public.requirements_denied (id integer)"), "42501");
        assertSqlState(() -> restricted.execute("CREATE TEMP TABLE requirements_denied (id integer)"), "42501");
        assertSqlState(() -> restricted.queryForObject("SELECT pg_catalog.pg_try_advisory_lock(1::bigint)",
                Boolean.class), "42501");
        assertSqlState(() -> restricted.queryForObject("SELECT pg_catalog.lo_create(0)::bigint", Long.class), "42501");
    }

    @ParameterizedTest
    @ValueSource(strings = {"flyway_schema_history", "legal_publicaciones", "legal_publicacion_requisitos",
            "legal_publicacion_documentos", "legal_requisito_conjuntos_actuales", "legal_requisito_conjuntos",
            "legal_requisito_conjunto_miembros", "legal_requisito_lineas", "legal_requisito_audiencias",
            "legal_requisito_versiones", "legal_requisito_documentos", "legal_documento_lineas",
            "legal_documento_versiones", "legal_documento_contextos", "legal_documento_vigentes",
            "legal_requisito_agregados", "legal_requisito_agregado_scopes",
            "legal_aceptacion_lotes", "legal_aceptaciones", "legal_aceptacion_documentos",
            "legal_requisito_transiciones", "legal_documento_transiciones"})
    void missingAnyRequiredSelectFailsClosed(String table) {
        assertPrivilegeDrift("REVOKE SELECT ON TABLE public." + quoteIdentifier(table) + " FROM " + role(),
                "GRANT SELECT ON TABLE public." + quoteIdentifier(table) + " TO " + role());
    }

    @ParameterizedTest
    @ValueSource(strings = {"legal_rechazar_update_delete()", "legal_exigir_read_committed()",
            "legal_exigir_lock_editorial_v28()", "legal_requisito_agregado_insert_guard()",
            "legal_requisito_agregado_scope_insert_guard()", "legal_validar_requisito_agregado(uuid)",
            "legal_requisito_agregado_constraint_guard()"})
    void missingAnyRequiredExecuteFailsClosed(String signature) {
        assertPrivilegeDrift("REVOKE EXECUTE ON FUNCTION public." + signature + " FROM " + role(),
                "GRANT EXECUTE ON FUNCTION public." + signature + " TO " + role());
    }

    @Test
    void missingInsertOrTheExactPointerUpdateColumnFailsClosed() {
        for (String table : List.of("legal_requisito_agregados", "legal_requisito_agregado_scopes")) {
            assertPrivilegeDrift("REVOKE INSERT ON TABLE public." + table + " FROM " + role(),
                    "GRANT INSERT ON TABLE public." + table + " TO " + role());
        }
        assertPrivilegeDrift("REVOKE UPDATE (conjunto_id) ON public.legal_requisito_conjuntos_actuales FROM " + role(),
                "GRANT UPDATE (conjunto_id) ON public.legal_requisito_conjuntos_actuales TO " + role());
    }

    @ParameterizedTest
    @ValueSource(strings = {"users", "talleres", "legal_aceptacion_metadatos", "legal_aceptacion_metadatos_cifrados",
            "legal_idempotencia_resultados", "legal_idempotencia_sin_actos",
            "legal_idempotencia_sin_actos_referencias", "legal_documento_reemplazo_lotes"})
    void unapprovedReadsFailClosed(String table) {
        String mutation = "GRANT SELECT ON TABLE public." + quoteIdentifier(table) + " TO " + role();
        String revoke = "REVOKE SELECT ON TABLE public." + quoteIdentifier(table) + " FROM " + role();
        // PostgreSQL also revokes the grantee's column SELECT grants when table SELECT is revoked.
        // Restore the exact nominal actor columns after this intentional table-level drift.
        switch (table) {
            case "users" -> assertPrivilegeDrift(mutation, revoke,
                    "GRANT SELECT (id, taller_id, role, active, token_version) ON public.users TO " + role());
            case "talleres" -> assertPrivilegeDrift(mutation, revoke,
                    "GRANT SELECT (id, activo) ON public.talleres TO " + role());
            default -> assertPrivilegeDrift(mutation, revoke);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER"})
    void documentMutationCapabilitiesFailClosed(String privilege) {
        assertPrivilegeDrift("GRANT " + privilege + " ON public.legal_documento_versiones TO " + role(),
                "REVOKE " + privilege + " ON public.legal_documento_versiones FROM " + role());
    }

    @Test
    void columnGrantsAndGrantOptionsCannotBypassTheExactBoundary() {
        assertPrivilegeDrift("GRANT SELECT (password) ON public.users TO " + role(),
                "REVOKE SELECT (password) ON public.users FROM " + role());
        assertPrivilegeDrift("GRANT UPDATE (role) ON public.users TO " + role(),
                "REVOKE UPDATE (role) ON public.users FROM " + role());
        assertPrivilegeDrift("GRANT INSERT (titulo) ON public.legal_documento_versiones TO " + role(),
                "REVOKE INSERT (titulo) ON public.legal_documento_versiones FROM " + role());
        assertPrivilegeDrift("GRANT UPDATE (scope_count) ON public.legal_requisito_agregados TO " + role(),
                "REVOKE UPDATE (scope_count) ON public.legal_requisito_agregados FROM " + role());
        assertPrivilegeDrift("GRANT UPDATE (publicacion_id) ON public.legal_requisito_conjuntos_actuales TO " + role(),
                "REVOKE UPDATE (publicacion_id) ON public.legal_requisito_conjuntos_actuales FROM " + role());
        assertPrivilegeDrift("GRANT SELECT ON public.legal_documento_versiones TO " + role() + " WITH GRANT OPTION",
                "REVOKE GRANT OPTION FOR SELECT ON public.legal_documento_versiones FROM " + role());
        assertPrivilegeDrift("GRANT INSERT ON public.legal_requisito_agregados TO " + role() + " WITH GRANT OPTION",
                "REVOKE GRANT OPTION FOR INSERT ON public.legal_requisito_agregados FROM " + role());
        assertPrivilegeDrift("GRANT UPDATE (conjunto_id) ON public.legal_requisito_conjuntos_actuales TO "
                        + role() + " WITH GRANT OPTION",
                "REVOKE GRANT OPTION FOR UPDATE (conjunto_id) ON public.legal_requisito_conjuntos_actuales FROM " + role());
    }

    @Test
    void publicPrivilegesFailClosedEvenWhenTheyDuplicateAnAllowedDirectGrant() {
        assertPrivilegeDrift("GRANT SELECT ON public.legal_documento_lineas TO PUBLIC",
                "REVOKE SELECT ON public.legal_documento_lineas FROM PUBLIC");
        assertPrivilegeDrift("GRANT SELECT ON public.users TO PUBLIC", "REVOKE SELECT ON public.users FROM PUBLIC");
        assertPrivilegeDrift("GRANT INSERT ON public.legal_requisito_agregados TO PUBLIC",
                "REVOKE INSERT ON public.legal_requisito_agregados FROM PUBLIC");
        assertPrivilegeDrift("GRANT UPDATE (conjunto_id) ON public.legal_requisito_conjuntos_actuales TO PUBLIC",
                "REVOKE UPDATE (conjunto_id) ON public.legal_requisito_conjuntos_actuales FROM PUBLIC");
        assertPrivilegeDrift("GRANT EXECUTE ON FUNCTION public.legal_exigir_read_committed() TO PUBLIC",
                "REVOKE EXECUTE ON FUNCTION public.legal_exigir_read_committed() FROM PUBLIC");
        assertPrivilegeDrift("GRANT EXECUTE ON FUNCTION public.legal_validar_requisito_agregado_actual(uuid) TO PUBLIC",
                "REVOKE EXECUTE ON FUNCTION public.legal_validar_requisito_agregado_actual(uuid) FROM PUBLIC");
    }

    @Test
    void extraDirectFunctionAndExecuteGrantOptionsFailClosed() {
        assertPrivilegeDrift("GRANT EXECUTE ON FUNCTION public.legal_validar_requisito_agregado_actual(uuid) TO " + role(),
                "REVOKE EXECUTE ON FUNCTION public.legal_validar_requisito_agregado_actual(uuid) FROM " + role());
        assertPrivilegeDrift("GRANT EXECUTE ON FUNCTION public.legal_exigir_read_committed() TO " + role()
                        + " WITH GRANT OPTION",
                "REVOKE GRANT OPTION FOR EXECUTE ON FUNCTION public.legal_exigir_read_committed() FROM " + role());
    }

    @Test
    void elevatedAttributesAndReceivedOrDelegatedMembershipFailClosed() {
        for (String attribute : List.of("INHERIT", "SUPERUSER", "CREATEDB", "CREATEROLE", "REPLICATION", "BYPASSRLS")) {
            assertPrivilegeDrift("ALTER ROLE " + role() + " " + attribute,
                    "ALTER ROLE " + role() + " NO" + attribute);
        }
        String group = "ordenfix_requirements_membership_it";
        owner.execute("CREATE ROLE " + quoteIdentifier(group) + " NOLOGIN NOINHERIT");
        try {
            assertPrivilegeDrift("GRANT " + quoteIdentifier(group) + " TO " + role(),
                    "REVOKE " + quoteIdentifier(group) + " FROM " + role());
            assertPrivilegeDrift("GRANT " + role() + " TO " + quoteIdentifier(group),
                    "REVOKE " + role() + " FROM " + quoteIdentifier(group));
        } finally {
            owner.execute("DROP ROLE " + quoteIdentifier(group));
        }
    }

    @Test
    void tableOwnershipFailsClosedAndRestorationPreservesItsSelectGrant() {
        String originalOwner = owner.queryForObject("""
                SELECT pg_catalog.pg_get_userbyid(relowner) FROM pg_catalog.pg_class
                 WHERE oid = 'public.legal_documento_lineas'::pg_catalog.regclass
                """, String.class);
        assertThat(originalOwner).isNotNull();
        owner.execute("ALTER TABLE public.legal_documento_lineas OWNER TO " + role());
        try {
            assertPrivilegeFailure(verifier);
        } finally {
            owner.execute("ALTER TABLE public.legal_documento_lineas OWNER TO " + quoteIdentifier(originalOwner));
            owner.execute("GRANT SELECT ON public.legal_documento_lineas TO " + role());
        }
        verifier.verify();
        new LegalV29AcceptanceSchemaVerifier(restricted, SCHEMA).verify();
    }

    @Test
    void ownershipOfADisabledDatabaseStillFailsClosed() {
        String database = "ordenfix_requirements_disabled_it";
        owner.execute("CREATE DATABASE " + quoteIdentifier(database));
        try {
            owner.execute("ALTER DATABASE " + quoteIdentifier(database) + " OWNER TO " + role());
            owner.execute("ALTER DATABASE " + quoteIdentifier(database) + " ALLOW_CONNECTIONS false");
            assertPrivilegeFailure(verifier);
        } finally {
            owner.execute("ALTER DATABASE " + quoteIdentifier(database) + " OWNER TO " + quoteIdentifier(POSTGRES.getUsername()));
            owner.execute("DROP DATABASE " + quoteIdentifier(database));
        }
        verifier.verify();
    }

    @Test
    void databaseSchemaSequenceAndParameterCapabilitiesFailClosed() {
        String database = quoteIdentifier(POSTGRES.getDatabaseName());
        assertPrivilegeDrift("GRANT CONNECT ON DATABASE " + database + " TO PUBLIC",
                "REVOKE CONNECT ON DATABASE " + database + " FROM PUBLIC");
        assertPrivilegeDrift("GRANT TEMPORARY ON DATABASE " + database + " TO " + role(),
                "REVOKE TEMPORARY ON DATABASE " + database + " FROM " + role());
        assertPrivilegeDrift("GRANT CREATE ON SCHEMA public TO " + role(), "REVOKE CREATE ON SCHEMA public FROM " + role());
        assertPrivilegeDrift("GRANT USAGE ON SEQUENCE public.legal_documento_contextos_id_seq TO " + role(),
                "REVOKE USAGE ON SEQUENCE public.legal_documento_contextos_id_seq FROM " + role());
        assertPrivilegeDrift("GRANT SET ON PARAMETER session_replication_role TO " + role(),
                "REVOKE SET ON PARAMETER session_replication_role FROM " + role());
    }

    @Test
    void systemAclBackdoorsLargeObjectsAndSessionAdvisoryCapabilitiesFailClosed() {
        assertPrivilegeDrift("GRANT CREATE ON SCHEMA information_schema TO " + role(),
                "REVOKE CREATE ON SCHEMA information_schema FROM " + role());
        assertPrivilegeDrift("GRANT UPDATE (tgenabled) ON pg_catalog.pg_trigger TO " + role(),
                "REVOKE UPDATE (tgenabled) ON pg_catalog.pg_trigger FROM " + role());
        assertPrivilegeDrift("GRANT SELECT ON pg_catalog.pg_authid TO PUBLIC",
                "REVOKE SELECT ON pg_catalog.pg_authid FROM PUBLIC");
        assertPrivilegeDrift("GRANT EXECUTE ON FUNCTION pg_catalog.pg_read_file(text) TO PUBLIC",
                "REVOKE EXECUTE ON FUNCTION pg_catalog.pg_read_file(text) FROM PUBLIC");
        assertPrivilegeDrift("CREATE FUNCTION pg_catalog.ordenfix_requirements_backdoor() RETURNS void "
                        + "LANGUAGE sql SECURITY DEFINER AS 'SELECT NULL::void'",
                "DROP FUNCTION pg_catalog.ordenfix_requirements_backdoor()");
        assertPrivilegeDrift("GRANT EXECUTE ON FUNCTION pg_catalog.pg_advisory_lock(bigint) TO " + role(),
                "REVOKE EXECUTE ON FUNCTION pg_catalog.pg_advisory_lock(bigint) FROM " + role());
        assertPrivilegeDrift("GRANT EXECUTE ON FUNCTION pg_catalog.lo_create(oid) TO PUBLIC",
                "REVOKE EXECUTE ON FUNCTION pg_catalog.lo_create(oid) FROM PUBLIC");
    }

    @Test
    void currentAndSessionIdentityBothHaveToMatchTheExpectedCredential() throws Exception {
        assertPrivilegeFailure(new LegalPrivateRequirementsPrivilegeVerifier(owner, ROLE, SCHEMA));
        assertPrivilegeFailure(new LegalPrivateRequirementsPrivilegeVerifier(restricted, "unexpected_requirements_role", SCHEMA));
        try (Connection connection = owner.getDataSource().getConnection()) {
            try (var statement = connection.createStatement()) {
                statement.execute("SET ROLE " + role());
            }
            JdbcTemplate assumed = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            assertThat(assumed.queryForObject("SELECT CURRENT_USER", String.class)).isEqualTo(ROLE);
            assertThat(assumed.queryForObject("SELECT SESSION_USER", String.class)).isEqualTo(POSTGRES.getUsername());
            assertPrivilegeFailure(new LegalPrivateRequirementsPrivilegeVerifier(assumed, ROLE, SCHEMA));
        }
        verifier.verify();
    }

    @Test
    void unexpectedSearchPathOrSchemaFailsClosed() {
        assertPrivilegeFailure(new LegalPrivateRequirementsPrivilegeVerifier(restricted, ROLE, "unexpected_schema"));
        String database = quoteIdentifier(POSTGRES.getDatabaseName());
        owner.execute("ALTER ROLE " + role() + " IN DATABASE " + database
                + " SET search_path TO information_schema, public, pg_temp");
        try {
            assertPrivilegeFailure(new LegalPrivateRequirementsPrivilegeVerifier(
                    jdbc(POSTGRES.getJdbcUrl(), ROLE, PASSWORD), ROLE, SCHEMA));
        } finally {
            owner.execute("ALTER ROLE " + role() + " IN DATABASE " + database
                    + " SET search_path TO pg_catalog, public, pg_temp");
        }
        verifier.verify();
    }

    @Test
    void fixtureRefusesAnExistingRoleBeforeChangingAnyGrants() {
        assertThatThrownBy(() -> LegalPrivateRequirementsITSupport.provision(owner, ROLE, PASSWORD)).isInstanceOf(IllegalStateException.class);
        verifier.verify();
    }

    @Test
    void fixtureRefusesAnUnrelatedDatabaseBeforeCreatingTheRole() {
        String database = "ordenfix_requirements_unsafe_fixture_it";
        String uncreatedRole = "ordenfix_requirements_unsafe_role_it";
        owner.execute("CREATE DATABASE " + quoteIdentifier(database));
        try {
            String unsafeUrl = POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + database);
            JdbcTemplate unsafeOwner = jdbc(unsafeUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
            assertThatThrownBy(() -> LegalPrivateRequirementsITSupport.provision(unsafeOwner, uncreatedRole, PASSWORD))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(owner.queryForObject("SELECT count(*) FROM pg_catalog.pg_roles WHERE rolname = ?",
                    Long.class, uncreatedRole)).isZero();
        } finally {
            owner.execute("DROP DATABASE " + quoteIdentifier(database));
        }
        verifier.verify();
    }

    @Test
    void missingActorReadsOrAnyLockOnlyColumnFailsClosed() {
        for (String column : List.of("users.id", "users.taller_id", "users.role", "users.active",
                "users.token_version", "talleres.id", "talleres.activo")) {
            String[] part = column.split("\\.");
            assertPrivilegeDrift("REVOKE SELECT (" + part[1] + ") ON public." + part[0] + " FROM " + role(),
                    "GRANT SELECT (" + part[1] + ") ON public." + part[0] + " TO " + role());
        }
        for (String column : List.of("users.id", "talleres.id", "legal_requisito_agregados.id",
                "legal_requisito_conjuntos_actuales.conjunto_id")) {
            String[] part = column.split("\\.");
            assertPrivilegeDrift("REVOKE UPDATE (" + part[1] + ") ON public." + part[0] + " FROM " + role(),
                    "GRANT UPDATE (" + part[1] + ") ON public." + part[0] + " TO " + role());
        }
    }

    @Test
    void accountShareLocksCommitButIdentityStatementsFailForRealNoOpAndZeroRows() throws Exception {
        var actor = LegalPrivateRequirementsITSupport.seedActor(owner, "USER");
        try (Connection connection = restricted.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            for (String table : List.of("users", "talleres")) {
                long id = "users".equals(table) ? actor.userId() : actor.workshopId();
                try (var statement = connection.prepareStatement(
                        "SELECT id FROM public." + table + " WHERE id = ? FOR SHARE")) {
                    statement.setLong(1, id);
                    try (var row = statement.executeQuery()) {
                        assertThat(row.next()).isTrue();
                        assertThat(row.getLong(1)).isEqualTo(id);
                    }
                }
            }
            connection.commit();
        }
        for (String table : List.of("users", "talleres")) {
            long id = "users".equals(table) ? actor.userId() : actor.workshopId();
            assertSqlState(() -> restricted.update("UPDATE public." + table + " SET id = id WHERE id = ?", id), "23514");
            assertSqlState(() -> restricted.update("UPDATE public." + table + " SET id = id + 1000000 WHERE id = ?", id), "23514");
            assertSqlState(() -> restricted.update("UPDATE public." + table + " SET id = id WHERE false"), "23514");
            assertThat(restricted.queryForObject("SELECT id FROM public." + table + " WHERE id = ?", Long.class, id))
                    .isEqualTo(id);
        }
        assertSqlState(() -> restricted.execute("SELECT public.legal_rechazar_update_identidad_cuenta_v29()"), "42501");
        verifier.verify();
    }

    @Test
    void evidenceDmlAndEveryIdempotentCapabilityFailClosed() {
        for (String table : List.of("legal_aceptacion_lotes", "legal_aceptaciones", "legal_aceptacion_documentos")) {
            for (String privilege : List.of("INSERT", "UPDATE", "DELETE")) {
                assertPrivilegeDrift("GRANT " + privilege + " ON public." + table + " TO " + role(),
                        "REVOKE " + privilege + " ON public." + table + " FROM " + role());
            }
        }
        for (String table : List.of("legal_idempotencia_resultados", "legal_idempotencia_sin_actos",
                "legal_idempotencia_sin_actos_referencias")) {
            assertPrivilegeDrift("GRANT SELECT (user_id) ON public." + table + " TO " + role(),
                    "REVOKE SELECT (user_id) ON public." + table + " FROM " + role());
            assertPrivilegeDrift("GRANT INSERT ON public." + table + " TO " + role(),
                    "REVOKE INSERT ON public." + table + " FROM " + role());
        }
        assertPrivilegeDrift("GRANT EXECUTE ON FUNCTION public.legal_validar_idempotencia_sin_actos_v29(uuid) TO " + role(),
                "REVOKE EXECUTE ON FUNCTION public.legal_validar_idempotencia_sin_actos_v29(uuid) FROM " + role());
    }

    private static void assertPrivilegeDrift(String mutation, String... restorations) {
        owner.execute(mutation);
        try {
            assertPrivilegeFailure(verifier);
        } finally {
            for (String restoration : restorations) owner.execute(restoration);
        }
        verifier.verify();
    }

    private static void assertPrivilegeFailure(LegalPrivateRequirementsPrivilegeVerifier candidate) {
        assertThatThrownBy(candidate::verify).isInstanceOfSatisfying(LegalEditorialOperationalException.class,
                failure -> assertThat(failure.issue().code()).isEqualTo(LegalManifestIssueCode.ROLE_PRIVILEGE_DRIFT));
    }

    private static void assertSqlState(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation, String state) {
        Throwable current = catchThrowable(operation);
        assertThat(current).isNotNull();
        while (current != null && !(current instanceof SQLException)) {
            current = current.getCause();
        }
        assertThat(current).isInstanceOf(SQLException.class);
        assertThat(((SQLException) current).getSQLState()).isEqualTo(state);
    }

    private static JdbcTemplate jdbc(String url, String username, String password) {
        return new JdbcTemplate(new DriverManagerDataSource(url, username, password));
    }

    private static String role() {
        return quoteIdentifier(ROLE);
    }

    private static String quoteIdentifier(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }
}
