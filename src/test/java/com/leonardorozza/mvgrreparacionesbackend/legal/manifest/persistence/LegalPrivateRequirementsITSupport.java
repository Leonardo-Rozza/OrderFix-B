package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Provisions the exact private-requirements consumer in an ephemeral test database.
 *
 * <p>PUBLIC revocations belong only to the dedicated PostgreSQL test container. This fixture is
 * not provisioning for a shared environment; its existing consumers require a separate inventory.</p>
 */
final class LegalPrivateRequirementsITSupport {

    private static final String SAFE_DATABASE_PREFIX = "ordenfix_legal_private_requirements_";
    private static final String DEFAULT_SCHEMA = "public";

    private final JdbcTemplate owner;
    private final Credentials credentials;

    private LegalPrivateRequirementsITSupport(
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
            return new LegalPrivateRequirementsITSupport(owner, connection.getMetaData().getURL(), role,
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
                LegalPrivateRequirementsPrivilegeVerifier.LARGE_OBJECT_CREATION_FUNCTIONS);
        revokeExecuteFromPublicAndRole(
                "pg_catalog",
                LegalPrivateRequirementsPrivilegeVerifier.SESSION_ADVISORY_LOCK_FUNCTIONS);
        revokeExecuteFromPublicAndRole(
                DEFAULT_SCHEMA,
                LegalPrivateRequirementsPrivilegeVerifier.PRIVILEGED_FUNCTIONS);

        owner.execute("GRANT SELECT ON TABLE "
                + qualifiedNames(LegalPrivateRequirementsPrivilegeVerifier.READ_TABLES)
                + " TO " + quoteIdentifier(credentials.username()));
        LegalPrivateRequirementsPrivilegeVerifier.SELECT_COLUMNS.forEach((table, columns) ->
                owner.execute("GRANT SELECT ("
                        + String.join(", ", columns.stream().sorted()
                                .map(LegalPrivateRequirementsITSupport::quoteIdentifier).toList())
                        + ") ON TABLE public." + quoteIdentifier(table)
                        + " TO " + quoteIdentifier(credentials.username())));
        owner.execute("GRANT INSERT ON TABLE "
                + qualifiedNames(LegalPrivateRequirementsPrivilegeVerifier.INSERT_TABLES)
                + " TO " + quoteIdentifier(credentials.username()));
        LegalPrivateRequirementsPrivilegeVerifier.UPDATE_COLUMNS.forEach((table, columns) ->
                owner.execute("GRANT UPDATE ("
                        + String.join(", ", columns.stream()
                                .sorted()
                                .map(LegalPrivateRequirementsITSupport::quoteIdentifier)
                                .toList())
                        + ") ON TABLE public." + quoteIdentifier(table)
                        + " TO " + quoteIdentifier(credentials.username())));
        owner.execute("GRANT EXECUTE ON FUNCTION "
                + qualifiedFunctions(
                        DEFAULT_SCHEMA,
                        LegalPrivateRequirementsPrivilegeVerifier.PRIVILEGED_FUNCTIONS)
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
        new LegalPrivateRequirementsPrivilegeVerifier(
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
                    "El fixture de requisitos privados sólo puede mutar una base efímera dedicada");
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
            throw new IllegalStateException("El rol de requisitos privados efímero ya existe");
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

    static void seedCatalog(JdbcTemplate owner, java.nio.file.Path directory, Class<?> source) throws Exception {
        requireSafeEphemeralDatabase(owner);
        var release = LegalManifestPersistenceITSupport.copyRelease(directory, source, "private-requirements",
                (path, manifest) -> {
                    manifest.withArray("documents").forEach(document ->
                            ((com.fasterxml.jackson.databind.node.ObjectNode) document)
                                    .put("effectiveAt", "2020-01-01T00:00:00-03:00"));
                    for (int index = 1; index <= 2; index++) {
                        var requirement = manifest.withArray("requirements").addObject();
                        requirement.put("key", "private-continued-use-" + index);
                        requirement.put("version", "1.0.0");
                        requirement.put("context", "USO_CONTINUADO");
                        requirement.putArray("roles").add("ADMIN_TITULAR").add("USER");
                        requirement.put("actType", "ACEPTACION");
                        String statement = "Confirmo el requisito privado de uso continuado " + index + ".";
                        requirement.put("statement", statement);
                        requirement.put("statementSha256", sha256(statement));
                        requirement.putArray("documents").add("terminos");
                        requirement.put("required", true);
                        requirement.put("requiresReacceptance", true);
                    }
                });
        var publication = LegalV28AggregateITSupport.importRelease(owner.getDataSource(), release);
        LegalManifestPersistenceITSupport.promoteToReady(owner, publication);
    }

    static Actor seedActor(JdbcTemplate owner, String role) {
        requireSafeEphemeralDatabase(owner);
        if (!java.util.Set.of("ADMIN", "USER").contains(role)) throw new IllegalArgumentException("Rol inválido");
        String identity = java.util.UUID.randomUUID().toString();
        long workshop = Objects.requireNonNull(owner.queryForObject(
                "INSERT INTO talleres (nombre) VALUES (?) RETURNING id", Long.class,
                "Private requirements fixture " + identity));
        long user = Objects.requireNonNull(owner.queryForObject("""
                INSERT INTO users (username, password, email, role, taller_id)
                VALUES (?, 'fixture-hash', ?, ?, ?) RETURNING id
                """, Long.class, "fixture-" + identity, identity + "@ordenfix.test", role, workshop));
        return new Actor(user, workshop, role, "ADMIN".equals(role) ? "ADMIN_TITULAR" : "USER");
    }

    static java.util.Map<String, Long> counts(JdbcTemplate owner) {
        requireSafeEphemeralDatabase(owner);
        var counts = new java.util.LinkedHashMap<String, Long>();
        for (String table : java.util.List.of("legal_requisito_agregados", "legal_requisito_agregado_scopes",
                "legal_aceptacion_lotes", "legal_aceptaciones", "legal_aceptacion_documentos",
                "legal_aceptacion_metadatos", "legal_aceptacion_metadatos_cifrados",
                "legal_idempotencia_resultados", "legal_idempotencia_sin_actos",
                "legal_idempotencia_sin_actos_referencias")) {
            counts.put(table, owner.queryForObject("SELECT count(*) FROM public." + quoteIdentifier(table), Long.class));
        }
        return java.util.Map.copyOf(counts);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    record Actor(long userId, long workshopId, String role, String audience) { }

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
