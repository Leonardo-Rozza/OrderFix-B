package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Provisions the exact registration consumer in an ephemeral test database.
 *
 * <p>PUBLIC revocations belong only to the dedicated PostgreSQL test container. This fixture is
 * not provisioning for a shared environment; its existing consumers require a separate inventory.</p>
 */
final class LegalRestrictedRegistrationRoleFixture {

    private static final String SAFE_DATABASE_PREFIX = "ordenfix_legal_registration_";
    private static final String DEFAULT_SCHEMA = "public";

    private final JdbcTemplate owner;
    private final Credentials credentials;

    private LegalRestrictedRegistrationRoleFixture(
            JdbcTemplate owner,
            String jdbcUrl,
            String role,
            String password,
            String driverClassName) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.credentials = new Credentials(
                requireText(jdbcUrl, "jdbcUrl"),
                requireText(role, "role"),
                requireText(password, "password"),
                requireText(driverClassName, "driverClassName"));
    }

    static Credentials provision(JdbcTemplate owner, String role, String password) {
        try (var connection = Objects.requireNonNull(owner.getDataSource()).getConnection()) {
            return new LegalRestrictedRegistrationRoleFixture(owner, connection.getMetaData().getURL(), role,
                    password, "org.postgresql.Driver").provisionAndVerify();
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException("No se pudo abrir la base efímera del fixture", failure);
        }
    }

    /** Creates, grants and independently verifies the restricted account. */
    private Credentials provisionAndVerify() {
        String database = requireSafeEphemeralDatabase();
        requireAbsentRole();
        owner.execute("CREATE ROLE " + quoteIdentifier(credentials.username())
                + " LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE "
                + "NOREPLICATION NOBYPASSRLS PASSWORD "
                + quoteLiteral(credentials.password()));

        owner.queryForList("""
                SELECT datname
                  FROM pg_catalog.pg_database
                 WHERE datallowconn
                """, String.class).forEach(databaseName ->
                owner.execute("REVOKE CONNECT, TEMPORARY ON DATABASE "
                        + quoteIdentifier(databaseName) + " FROM PUBLIC"));
        owner.execute("REVOKE ALL ON DATABASE " + quoteIdentifier(database)
                + " FROM " + quoteIdentifier(credentials.username()));
        owner.execute("GRANT CONNECT ON DATABASE " + quoteIdentifier(database)
                + " TO " + quoteIdentifier(credentials.username()));
        owner.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
        owner.execute("REVOKE ALL ON SCHEMA public FROM "
                + quoteIdentifier(credentials.username()));
        owner.execute("GRANT USAGE ON SCHEMA public TO "
                + quoteIdentifier(credentials.username()));
        owner.execute("REVOKE ALL ON ALL TABLES IN SCHEMA public FROM "
                + quoteIdentifier(credentials.username()));
        owner.execute("REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM "
                + quoteIdentifier(credentials.username()));
        owner.execute("REVOKE ALL ON ALL FUNCTIONS IN SCHEMA public FROM "
                + quoteIdentifier(credentials.username()));
        owner.execute("REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA public FROM PUBLIC");

        revokeExecuteFromPublicAndRole(
                "pg_catalog",
                LegalRegistrationPrivilegeVerifier.LARGE_OBJECT_CREATION_FUNCTIONS);
        revokeExecuteFromPublicAndRole(
                "pg_catalog",
                LegalRegistrationPrivilegeVerifier.SESSION_ADVISORY_LOCK_FUNCTIONS);
        revokeExecuteFromPublicAndRole(
                DEFAULT_SCHEMA,
                LegalRegistrationPrivilegeVerifier.PRIVILEGED_FUNCTIONS);

        owner.execute("GRANT SELECT ON TABLE "
                + qualifiedNames(LegalRegistrationPrivilegeVerifier.READ_TABLES)
                + " TO " + quoteIdentifier(credentials.username()));
        LegalRegistrationPrivilegeVerifier.SELECT_COLUMNS.forEach((table, columns) ->
                owner.execute("GRANT SELECT ("
                        + String.join(", ", columns.stream().sorted()
                                .map(LegalRestrictedRegistrationRoleFixture::quoteIdentifier).toList())
                        + ") ON TABLE public." + quoteIdentifier(table)
                        + " TO " + quoteIdentifier(credentials.username())));
        owner.execute("GRANT INSERT ON TABLE "
                + qualifiedNames(LegalRegistrationPrivilegeVerifier.INSERT_TABLES)
                + " TO " + quoteIdentifier(credentials.username()));
        LegalRegistrationPrivilegeVerifier.INSERT_COLUMNS.forEach((table, columns) ->
                owner.execute("GRANT INSERT ("
                        + String.join(", ", columns.stream().sorted()
                                .map(LegalRestrictedRegistrationRoleFixture::quoteIdentifier).toList())
                        + ") ON TABLE public." + quoteIdentifier(table)
                        + " TO " + quoteIdentifier(credentials.username())));
        LegalRegistrationPrivilegeVerifier.UPDATE_COLUMNS.forEach((table, columns) ->
                owner.execute("GRANT UPDATE ("
                        + String.join(", ", columns.stream()
                                .sorted()
                                .map(LegalRestrictedRegistrationRoleFixture::quoteIdentifier)
                                .toList())
                        + ") ON TABLE public." + quoteIdentifier(table)
                        + " TO " + quoteIdentifier(credentials.username())));
        owner.execute("GRANT EXECUTE ON FUNCTION "
                + qualifiedFunctions(
                        DEFAULT_SCHEMA,
                        LegalRegistrationPrivilegeVerifier.PRIVILEGED_FUNCTIONS)
                + " TO " + quoteIdentifier(credentials.username()));
        owner.execute("ALTER ROLE " + quoteIdentifier(credentials.username())
                + " IN DATABASE " + quoteIdentifier(database)
                + " SET search_path TO pg_catalog, public, pg_temp");
        owner.execute("ALTER ROLE " + quoteIdentifier(credentials.username())
                + " IN DATABASE " + quoteIdentifier(database)
                + " SET session_replication_role TO origin");
        owner.execute("ALTER ROLE " + quoteIdentifier(credentials.username())
                + " IN DATABASE " + quoteIdentifier(database)
                + " SET lo_compat_privileges TO off");

        verify();
        return credentials;
    }

    private void verify() {
        JdbcTemplate restricted = new JdbcTemplate(new DriverManagerDataSource(
                credentials.jdbcUrl(), credentials.username(), credentials.password()));
        new LegalV29AcceptanceSchemaVerifier(restricted, DEFAULT_SCHEMA).verify();
        new LegalRegistrationPrivilegeVerifier(
                restricted, credentials.username(), DEFAULT_SCHEMA).verify();
    }

    private void revokeExecuteFromPublicAndRole(String schema, Iterable<String> signatures) {
        String functions = qualifiedFunctions(schema, signatures);
        owner.execute("REVOKE EXECUTE ON FUNCTION " + functions + " FROM PUBLIC");
        owner.execute("REVOKE EXECUTE ON FUNCTION " + functions + " FROM "
                + quoteIdentifier(credentials.username()));
    }

    private String requireSafeEphemeralDatabase() {
        return requireSafeEphemeralDatabase(owner);
    }

    static String requireSafeEphemeralDatabase(JdbcTemplate owner) {
        String database = owner.queryForObject(
                "SELECT pg_catalog.current_database()", String.class);
        if (database == null || !database.startsWith(SAFE_DATABASE_PREFIX)) {
            throw new IllegalStateException(
                    "El fixture de registro sólo puede mutar una base efímera dedicada");
        }
        return database;
    }

    private void requireAbsentRole() {
        Boolean exists = owner.queryForObject("""
                SELECT pg_catalog.count(*) > 0
                  FROM pg_catalog.pg_roles
                 WHERE rolname = ?
                """, Boolean.class, credentials.username());
        if (Boolean.TRUE.equals(exists)) {
            throw new IllegalStateException("El rol de registro efímero ya existe");
        }
    }

    private static String qualifiedNames(Iterable<String> names) {
        List<String> qualified = new ArrayList<>();
        names.forEach(name -> qualified.add("public." + quoteIdentifier(name)));
        qualified.sort(String::compareTo);
        return String.join(", ", qualified);
    }

    private static String qualifiedFunctions(String schema, Iterable<String> signatures) {
        List<String> qualified = new ArrayList<>();
        signatures.forEach(signature -> qualified.add(
                quoteIdentifier(schema) + "." + signature));
        qualified.sort(String::compareTo);
        return String.join(", ", qualified);
    }

    private static String quoteIdentifier(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    private static String quoteLiteral(String value) {
        return '\'' + value.replace("'", "''") + '\'';
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " no puede estar vacío");
        }
        return value;
    }

    public record Credentials(
            String jdbcUrl,
            String username,
            String password,
            String driverClassName) {

        public Credentials {
            requireText(jdbcUrl, "jdbcUrl");
            requireText(username, "username");
            requireText(password, "password");
            requireText(driverClassName, "driverClassName");
        }

        @Override
        public String toString() {
            return "Credentials[configured=true]";
        }
    }
}
