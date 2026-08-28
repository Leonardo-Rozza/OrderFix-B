package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Provisions the exact temporary offline editorial account in an ephemeral test database.
 *
 * <p>The role still has the structural DML needed by the V27 SECURITY INVOKER call graph, so it
 * must never be reused by the web application or kept as a long-lived production credential.</p>
 */
public final class LegalRestrictedEditorialRoleFixture {

    private static final String SAFE_DATABASE_PREFIX = "ordenfix_legal_editorial_";
    private static final String DEFAULT_SCHEMA = "public";

    private final JdbcTemplate owner;
    private final Credentials credentials;

    public LegalRestrictedEditorialRoleFixture(
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

    /** Creates, grants and independently verifies the restricted account. */
    public Credentials provisionAndVerify() {
        String database = requireSafeEphemeralDatabase();
        dropExistingRole();
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
        owner.execute("REVOKE CREATE ON SCHEMA " + quoteIdentifier(DEFAULT_SCHEMA)
                + " FROM PUBLIC");
        owner.execute("REVOKE ALL ON SCHEMA " + quoteIdentifier(DEFAULT_SCHEMA)
                + " FROM " + quoteIdentifier(credentials.username()));
        owner.execute("GRANT USAGE ON SCHEMA " + quoteIdentifier(DEFAULT_SCHEMA)
                + " TO " + quoteIdentifier(credentials.username()));
        owner.execute("REVOKE ALL ON ALL TABLES IN SCHEMA "
                + quoteIdentifier(DEFAULT_SCHEMA) + " FROM "
                + quoteIdentifier(credentials.username()));
        owner.execute("REVOKE ALL ON ALL SEQUENCES IN SCHEMA "
                + quoteIdentifier(DEFAULT_SCHEMA) + " FROM "
                + quoteIdentifier(credentials.username()));
        owner.execute("REVOKE ALL ON ALL FUNCTIONS IN SCHEMA "
                + quoteIdentifier(DEFAULT_SCHEMA) + " FROM "
                + quoteIdentifier(credentials.username()));
        owner.execute("REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA "
                + quoteIdentifier(DEFAULT_SCHEMA) + " FROM PUBLIC");
        owner.execute("REVOKE EXECUTE ON FUNCTION "
                + qualifiedFunctions(
                        "pg_catalog",
                        LegalEditorialPrivilegeVerifier.LARGE_OBJECT_CREATION_FUNCTIONS)
                + " FROM PUBLIC");
        owner.execute("REVOKE EXECUTE ON FUNCTION "
                + qualifiedFunctions(
                        "pg_catalog",
                        LegalEditorialPrivilegeVerifier.LARGE_OBJECT_CREATION_FUNCTIONS)
                + " FROM " + quoteIdentifier(credentials.username()));

        owner.execute("GRANT SELECT ON TABLE public.flyway_schema_history TO "
                + quoteIdentifier(credentials.username()));
        owner.execute("GRANT SELECT ON TABLE "
                + qualifiedNames(LegalV27EditorialInventory.EDITORIAL_TABLES)
                + " TO " + quoteIdentifier(credentials.username()));
        owner.execute("GRANT INSERT ON TABLE "
                + qualifiedNames(LegalV27EditorialInventory.INSERT_TABLES)
                + " TO " + quoteIdentifier(credentials.username()));
        owner.execute("GRANT DELETE ON TABLE "
                + qualifiedNames(LegalV27EditorialInventory.DELETE_TABLES)
                + " TO " + quoteIdentifier(credentials.username()));
        LegalV27EditorialInventory.UPDATE_COLUMNS.forEach((table, columns) ->
                owner.execute("GRANT UPDATE ("
                        + String.join(", ", columns.stream()
                                .sorted()
                                .map(LegalRestrictedEditorialRoleFixture::quoteIdentifier)
                                .toList())
                        + ") ON TABLE public." + quoteIdentifier(table)
                        + " TO " + quoteIdentifier(credentials.username())));
        owner.execute("GRANT USAGE ON SEQUENCE "
                + qualifiedNames(LegalV27EditorialInventory.WRITABLE_SEQUENCES)
                + " TO " + quoteIdentifier(credentials.username()));
        owner.execute("GRANT EXECUTE ON FUNCTION "
                + qualifiedFunctions(LegalV27EditorialInventory.PRIVILEGED_FUNCTIONS)
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

    /** Rechecks catalog integrity and effective privileges from the restricted session. */
    public void verify() {
        JdbcTemplate restricted = new JdbcTemplate(new DriverManagerDataSource(
                credentials.jdbcUrl(),
                credentials.username(),
                credentials.password()));
        new LegalEditorialSchemaVerifier(restricted, DEFAULT_SCHEMA).verify();
        new LegalEditorialPrivilegeVerifier(
                restricted,
                credentials.username(),
                DEFAULT_SCHEMA).verify();
    }

    private String requireSafeEphemeralDatabase() {
        String database = owner.queryForObject(
                "SELECT pg_catalog.current_database()",
                String.class);
        if (database == null || !database.startsWith(SAFE_DATABASE_PREFIX)) {
            throw new IllegalStateException(
                    "El fixture editorial sólo puede mutar una base efímera dedicada");
        }
        return database;
    }

    private void dropExistingRole() {
        Boolean exists = owner.queryForObject("""
                SELECT pg_catalog.count(*) = 1
                  FROM pg_catalog.pg_roles
                 WHERE rolname = ?
                """, Boolean.class, credentials.username());
        if (Boolean.TRUE.equals(exists)) {
            owner.execute("DROP OWNED BY " + quoteIdentifier(credentials.username()));
            owner.execute("DROP ROLE " + quoteIdentifier(credentials.username()));
        }
    }

    private static String qualifiedNames(Iterable<String> names) {
        List<String> qualified = new ArrayList<>();
        names.forEach(name -> qualified.add("public." + quoteIdentifier(name)));
        qualified.sort(String::compareTo);
        return String.join(", ", qualified);
    }

    private static String qualifiedFunctions(Iterable<String> signatures) {
        return qualifiedFunctions(DEFAULT_SCHEMA, signatures);
    }

    private static String qualifiedFunctions(
            String schema,
            Iterable<String> signatures) {
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

    /** Restricted credentials whose string representation never exposes the secret. */
    public static final class Credentials {

        private final String jdbcUrl;
        private final String username;
        private final String password;
        private final String driverClassName;

        private Credentials(
                String jdbcUrl,
                String username,
                String password,
                String driverClassName) {
            this.jdbcUrl = jdbcUrl;
            this.username = username;
            this.password = password;
            this.driverClassName = driverClassName;
        }

        public String jdbcUrl() {
            return jdbcUrl;
        }

        public String username() {
            return username;
        }

        public String password() {
            return password;
        }

        public String driverClassName() {
            return driverClassName;
        }

        @Override
        public String toString() {
            return "Credentials[configured=true]";
        }
    }
}
