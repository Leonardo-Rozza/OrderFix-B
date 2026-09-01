package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidation;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetRevisionCalculator;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class LegalImportPrivilegeVerifierIT {

    private static final String IMPORT_ROLE = "ordenfix_legal_import_it";
    private static final String IMPORT_PASSWORD = "legal-import-test-only";

    private static PostgreSQLContainer postgres;
    private static String jdbcUrl;
    private static String ownerUsername;
    private static String ownerPassword;
    private static JdbcTemplate owner;
    private static JdbcTemplate restricted;
    private static LegalImportPrivilegeVerifier verifier;
    private static LegalV27ImportSchemaVerifier schemaVerifier;
    private static LegalManifestImportService importService;
    private static ValidatedRelease goldenRelease;

    @BeforeAll
    static void migrateAndProvisionRestrictedRole() {
        jdbcUrl = System.getProperty(
                "ordenfix.test.privilege.postgresql.url",
                System.getProperty("ordenfix.test.postgresql.url"));
        ownerUsername = System.getProperty(
                "ordenfix.test.postgresql.username",
                "ordenfix");
        ownerPassword = System.getProperty(
                "ordenfix.test.postgresql.password",
                "");
        if (jdbcUrl == null) {
            postgres = new PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("ordenfix_legal_privilege_verifier")
                    .withUsername("ordenfix")
                    .withPassword("ordenfix");
            postgres.start();
            jdbcUrl = postgres.getJdbcUrl();
            ownerUsername = postgres.getUsername();
            ownerPassword = postgres.getPassword();
        }
        Flyway.configure()
                .dataSource(jdbcUrl, ownerUsername, ownerPassword)
                .locations("classpath:db/migration")
                .target("27")
                .load()
                .migrate();
        owner = jdbc(jdbcUrl, ownerUsername, ownerPassword);
        provisionRole();
        restricted = jdbc(jdbcUrl, IMPORT_ROLE, IMPORT_PASSWORD);
        verifier = new LegalImportPrivilegeVerifier(
                restricted,
                IMPORT_ROLE,
                LegalV27ImportInventory.DEFAULT_SCHEMA);
        importService = importService();
        goldenRelease = goldenRelease();
    }

    @AfterAll
    static void stopContainer() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @BeforeEach
    void cleanLegalState() {
        owner.execute("""
                TRUNCATE TABLE legal_publicaciones, legal_documento_reemplazo_lotes
                RESTART IDENTITY CASCADE
                """);
        verifier.verify();
    }

    @Test
    void exactStandaloneRolePassesTheEffectivePrivilegeMatrix() {
        verifier.verify();

        assertThat(restricted.queryForObject("""
                SELECT checksum
                  FROM flyway_schema_history
                 WHERE version = '27'
                """, Integer.class))
                .isEqualTo(LegalV27ImportInventory.FLYWAY_CHECKSUM);
        assertSqlState(() -> restricted.update("""
                UPDATE flyway_schema_history
                   SET checksum = checksum
                 WHERE version = '27'
                """), "42501");
    }

    @Test
    void exactRoleImportsAndReplaysWithoutBroaderApplicationPrivileges() {
        assertThat(schemaVerifier.snapshot().catalog())
                .isEqualTo(LegalV27ImportInventory.EXPECTED_CATALOG);
        schemaVerifier.verify();
        LegalManifestImportResult imported = importService.importManifest(goldenRelease);
        LegalManifestImportResult replay = importService.importManifest(goldenRelease);

        assertThat(imported.status())
                .as("issues=%s", imported.issues())
                .isEqualTo(LegalManifestStatus.PASS);
        assertThat(imported.outcome())
                .contains(LegalManifestImportResult.Outcome.IMPORTED);
        assertThat(imported.persisted()).isTrue();
        assertThat(replay.status()).isEqualTo(LegalManifestStatus.PASS);
        assertThat(replay.outcome())
                .contains(LegalManifestImportResult.Outcome.ALREADY_IMPORTED);
        assertThat(replay.receipt()).isEqualTo(imported.receipt());
    }

    @Test
    void specialRoleCapabilityOrMembershipFailsClosed() {
        assertPrivilegeDrift(
                "ALTER ROLE " + quoteIdentifier(IMPORT_ROLE) + " SUPERUSER",
                "ALTER ROLE " + quoteIdentifier(IMPORT_ROLE) + " NOSUPERUSER");
        assertPrivilegeDrift(
                "ALTER ROLE " + quoteIdentifier(IMPORT_ROLE) + " BYPASSRLS",
                "ALTER ROLE " + quoteIdentifier(IMPORT_ROLE) + " NOBYPASSRLS");

        owner.execute("CREATE ROLE ordenfix_legal_broad_it NOLOGIN");
        try {
            assertPrivilegeDrift(
                    "GRANT ordenfix_legal_broad_it TO " + quoteIdentifier(IMPORT_ROLE),
                    "REVOKE ordenfix_legal_broad_it FROM "
                            + quoteIdentifier(IMPORT_ROLE));
        } finally {
            owner.execute("DROP ROLE ordenfix_legal_broad_it");
        }

        owner.execute("CREATE ROLE ordenfix_legal_delegate_it NOLOGIN");
        try {
            assertPrivilegeDrift(
                    "GRANT " + quoteIdentifier(IMPORT_ROLE)
                            + " TO ordenfix_legal_delegate_it",
                    "REVOKE " + quoteIdentifier(IMPORT_ROLE)
                            + " FROM ordenfix_legal_delegate_it");
        } finally {
            owner.execute("DROP ROLE ordenfix_legal_delegate_it");
        }
    }

    @Test
    void ownershipDatabaseOrSchemaCreateCapabilitiesFailClosed() {
        String database = owner.queryForObject(
                "SELECT pg_catalog.current_database()",
                String.class);
        String otherDatabase = owner.queryForObject("""
                SELECT datname
                  FROM pg_catalog.pg_database
                 WHERE datallowconn
                   AND datname <> pg_catalog.current_database()
                 ORDER BY datname
                 LIMIT 1
                """, String.class);
        assertPrivilegeDrift(
                "GRANT CONNECT ON DATABASE " + quoteIdentifier(otherDatabase)
                        + " TO " + quoteIdentifier(IMPORT_ROLE),
                "REVOKE CONNECT ON DATABASE " + quoteIdentifier(otherDatabase)
                        + " FROM " + quoteIdentifier(IMPORT_ROLE));
        assertPrivilegeDrift(
                "GRANT TEMPORARY ON DATABASE " + quoteIdentifier(database)
                        + " TO " + quoteIdentifier(IMPORT_ROLE),
                "REVOKE TEMPORARY ON DATABASE " + quoteIdentifier(database)
                        + " FROM " + quoteIdentifier(IMPORT_ROLE));
        assertPrivilegeDrift(
                "GRANT CREATE ON SCHEMA public TO " + quoteIdentifier(IMPORT_ROLE),
                "REVOKE CREATE ON SCHEMA public FROM " + quoteIdentifier(IMPORT_ROLE));
        assertPrivilegeDrift(
                "CREATE SCHEMA zz_legal_import_owned AUTHORIZATION "
                        + quoteIdentifier(IMPORT_ROLE),
                "DROP SCHEMA zz_legal_import_owned");
    }

    @Test
    void extraTableColumnSequenceOrGrantOptionFailsClosed() {
        assertPrivilegeDrift(
                "GRANT INSERT ON legal_documento_transiciones TO "
                        + quoteIdentifier(IMPORT_ROLE),
                "REVOKE INSERT ON legal_documento_transiciones FROM "
                        + quoteIdentifier(IMPORT_ROLE));
        assertPrivilegeDrift(
                "GRANT UPDATE (titulo) ON legal_documento_versiones TO "
                        + quoteIdentifier(IMPORT_ROLE),
                "REVOKE UPDATE (titulo) ON legal_documento_versiones FROM "
                        + quoteIdentifier(IMPORT_ROLE));
        assertPrivilegeDrift(
                "GRANT SELECT ON SEQUENCE legal_documento_contextos_id_seq TO "
                        + quoteIdentifier(IMPORT_ROLE),
                "REVOKE SELECT ON SEQUENCE legal_documento_contextos_id_seq FROM "
                        + quoteIdentifier(IMPORT_ROLE));
        assertPrivilegeDrift(
                "GRANT SELECT ON legal_publicaciones TO "
                        + quoteIdentifier(IMPORT_ROLE) + " WITH GRANT OPTION",
                "REVOKE GRANT OPTION FOR SELECT ON legal_publicaciones FROM "
                        + quoteIdentifier(IMPORT_ROLE));
        assertPrivilegeDrift(
                "GRANT SELECT (id) ON legal_publicaciones TO "
                        + quoteIdentifier(IMPORT_ROLE) + " WITH GRANT OPTION",
                "REVOKE GRANT OPTION FOR SELECT (id) ON legal_publicaciones FROM "
                        + quoteIdentifier(IMPORT_ROLE));
    }

    @Test
    void publicOrDirectExecuteOutsideTheAllowlistFailsClosed() {
        String deniedFunction = "legal_documento_transicion_before_insert()";
        assertPrivilegeDrift(
                "GRANT EXECUTE ON FUNCTION " + deniedFunction + " TO PUBLIC",
                "REVOKE EXECUTE ON FUNCTION " + deniedFunction + " FROM PUBLIC");
        assertPrivilegeDrift(
                "GRANT EXECUTE ON FUNCTION " + deniedFunction + " TO "
                        + quoteIdentifier(IMPORT_ROLE),
                "REVOKE EXECUTE ON FUNCTION " + deniedFunction + " FROM "
                        + quoteIdentifier(IMPORT_ROLE));
        String allowedFunction = "legal_publicacion_insert_guard()";
        assertPrivilegeDrift(
                "GRANT EXECUTE ON FUNCTION " + allowedFunction + " TO PUBLIC",
                "REVOKE EXECUTE ON FUNCTION " + allowedFunction + " FROM PUBLIC");
    }

    @Test
    void largeObjectOwnershipOrPrivilegeFailsClosed() {
        Long objectId = owner.queryForObject(
                "SELECT pg_catalog.lo_create(0)::bigint",
                Long.class);
        assertThat(objectId).isNotNull();
        try {
            assertPrivilegeDrift(
                    "GRANT SELECT ON LARGE OBJECT " + objectId + " TO "
                            + quoteIdentifier(IMPORT_ROLE),
                    "REVOKE SELECT ON LARGE OBJECT " + objectId + " FROM "
                            + quoteIdentifier(IMPORT_ROLE));
            assertPrivilegeDrift(
                    "ALTER LARGE OBJECT " + objectId + " OWNER TO "
                            + quoteIdentifier(IMPORT_ROLE),
                    "ALTER LARGE OBJECT " + objectId + " OWNER TO "
                            + quoteIdentifier(ownerUsername));
        } finally {
            owner.queryForObject(
                    "SELECT pg_catalog.lo_unlink(?::oid)",
                    Integer.class,
                    objectId);
        }
    }

    @Test
    void explicitServerParameterCapabilityFailsClosed() {
        assertPrivilegeDrift(
                "GRANT SET ON PARAMETER session_replication_role TO "
                        + quoteIdentifier(IMPORT_ROLE),
                "REVOKE SET ON PARAMETER session_replication_role FROM "
                        + quoteIdentifier(IMPORT_ROLE));
    }

    @Test
    void technicalRowLockColumnsCannotBeUsedForDirectNoOpUpdates() {
        LegalManifestImportResult imported = importService.importManifest(goldenRelease);
        assertThat(imported.status())
                .as("issues=%s", imported.issues())
                .isEqualTo(LegalManifestStatus.PASS);

        for (String table : List.of(
                "legal_documento_lineas",
                "legal_documento_versiones",
                "legal_requisito_lineas",
                "legal_requisito_versiones")) {
            UUID id = restricted.queryForObject(
                    "SELECT id FROM " + table + " LIMIT 1",
                    UUID.class);
            assertSqlState(() -> restricted.update(
                    "UPDATE " + table + " SET id = id WHERE id = ?",
                    id), "23514");
        }
        assertSqlState(() -> restricted.update("""
                UPDATE legal_documento_versiones
                   SET titulo = titulo
                """), "42501");
        assertSqlState(() -> restricted.update("""
                DELETE FROM legal_publicaciones
                """), "42501");
        assertSqlState(() -> restricted.update(
                "INSERT INTO legal_documento_transiciones DEFAULT VALUES"),
                "42501");
        assertSqlState(() -> restricted.update(
                "INSERT INTO legal_documento_vigentes DEFAULT VALUES"),
                "42501");
        assertSqlState(() -> restricted.update(
                "INSERT INTO legal_aceptacion_lotes DEFAULT VALUES"),
                "42501");
    }

    private static void assertSqlState(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable operation,
            String expected) {
        Throwable failure = catchThrowable(operation);
        assertThat(failure).isNotNull();
        Throwable current = failure;
        while (current != null && !(current instanceof SQLException)) {
            current = current.getCause();
        }
        assertThat(current).isInstanceOf(SQLException.class);
        assertThat(((SQLException) current).getSQLState()).isEqualTo(expected);
    }

    private static void assertPrivilegeDrift(String mutation, String restoration) {
        owner.execute(mutation);
        try {
            assertThatThrownBy(verifier::verify)
                    .isInstanceOfSatisfying(
                            LegalImportOperationalException.class,
                            failure -> assertThat(failure.issue().code())
                                    .isEqualTo(LegalManifestIssueCode
                                            .IMPORT_DB_PRIVILEGES_INCOMPATIBLE));
        } finally {
            owner.execute(restoration);
        }
        verifier.verify();
    }

    private static LegalManifestImportService importService() {
        DataSourceTransactionManager manager = new DataSourceTransactionManager(
                restricted.getDataSource());
        manager.setRollbackOnCommitFailure(false);
        TransactionTemplate transaction = new TransactionTemplate(manager);
        transaction.setName("legal-import-privilege-it");
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(LegalDatabaseBudgets.production().transactionTimeoutSeconds());
        transaction.setReadOnly(false);
        schemaVerifier = new LegalV27ImportSchemaVerifier(restricted, "public");
        LegalRequiredSetRevisionCalculator calculator =
                new LegalRequiredSetRevisionCalculator();
        LegalManifestDatabaseGate gate = new LegalManifestDatabaseGate(
                transaction,
                restricted,
                LegalDatabaseBudgets.production(),
                List.of(schemaVerifier, verifier));
        return new LegalManifestImportService(
                gate,
                restricted,
                new LegalManifestGraphWriter(restricted, calculator),
                new LegalManifestReplayVerifier(restricted, calculator),
                new LegalImportFailureMapper(),
                schemaVerifier,
                verifier);
    }

    private static ValidatedRelease goldenRelease() {
        try {
            Path manifest = Path.of(Objects.requireNonNull(
                    LegalImportPrivilegeVerifierIT.class.getResource(
                            "/legal/manifest/release-valid-v1/"
                                    + "publication-manifest.json")).toURI());
            LegalManifestValidation<ValidatedRelease> validation =
                    new LegalManifestValidator().validate(manifest);
            assertThat(validation.status()).isEqualTo(LegalManifestStatus.PASS);
            return validation.value().orElseThrow();
        } catch (URISyntaxException failure) {
            throw new AssertionError("Fixture legal inválido", failure);
        }
    }

    private static void provisionRole() {
        String database = owner.queryForObject(
                "SELECT pg_catalog.current_database()",
                String.class);
        Boolean roleExists = owner.queryForObject("""
                SELECT pg_catalog.count(*) = 1
                  FROM pg_catalog.pg_roles
                 WHERE rolname = ?
                """, Boolean.class, IMPORT_ROLE);
        if (Boolean.TRUE.equals(roleExists)) {
            owner.execute("DROP OWNED BY " + quoteIdentifier(IMPORT_ROLE));
            owner.execute("DROP ROLE " + quoteIdentifier(IMPORT_ROLE));
        }
        owner.execute("CREATE ROLE " + quoteIdentifier(IMPORT_ROLE) + " LOGIN NOINHERIT "
                + "NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS "
                + "PASSWORD '" + IMPORT_PASSWORD + "'");

        owner.queryForList("""
                SELECT datname
                  FROM pg_catalog.pg_database
                 WHERE datallowconn
                """, String.class).forEach(databaseName ->
                owner.execute("REVOKE CONNECT, TEMPORARY ON DATABASE "
                        + quoteIdentifier(databaseName) + " FROM PUBLIC"));
        owner.execute("REVOKE ALL ON DATABASE " + quoteIdentifier(database)
                + " FROM " + quoteIdentifier(IMPORT_ROLE));
        owner.execute("GRANT CONNECT ON DATABASE " + quoteIdentifier(database)
                + " TO " + quoteIdentifier(IMPORT_ROLE));
        owner.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
        owner.execute("REVOKE ALL ON SCHEMA public FROM " + quoteIdentifier(IMPORT_ROLE));
        owner.execute("GRANT USAGE ON SCHEMA public TO " + quoteIdentifier(IMPORT_ROLE));
        owner.execute("REVOKE ALL ON ALL TABLES IN SCHEMA public FROM "
                + quoteIdentifier(IMPORT_ROLE));
        owner.execute("REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM "
                + quoteIdentifier(IMPORT_ROLE));
        owner.execute("REVOKE ALL ON ALL FUNCTIONS IN SCHEMA public FROM "
                + quoteIdentifier(IMPORT_ROLE));
        owner.execute("REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA public FROM PUBLIC");

        owner.execute("GRANT SELECT ON TABLE public.flyway_schema_history TO "
                + quoteIdentifier(IMPORT_ROLE));
        owner.execute("GRANT SELECT, INSERT ON TABLE "
                + qualifiedNames(LegalV27ImportInventory.IMPORT_TABLES)
                + " TO " + quoteIdentifier(IMPORT_ROLE));
        LegalV27ImportInventory.UPDATE_COLUMNS.forEach((table, columns) ->
                owner.execute("GRANT UPDATE ("
                        + String.join(", ", columns.stream()
                                .sorted()
                                .map(LegalImportPrivilegeVerifierIT::quoteIdentifier)
                                .toList())
                        + ") ON TABLE public." + quoteIdentifier(table)
                        + " TO " + quoteIdentifier(IMPORT_ROLE)));
        owner.execute("GRANT USAGE ON SEQUENCE "
                + qualifiedNames(LegalV27ImportInventory.IDENTITY_SEQUENCES.keySet())
                + " TO " + quoteIdentifier(IMPORT_ROLE));
        owner.execute("GRANT EXECUTE ON FUNCTION "
                + qualifiedFunctions(LegalV27ImportInventory.IMPORT_FUNCTIONS.keySet())
                + " TO " + quoteIdentifier(IMPORT_ROLE));
        owner.execute("ALTER ROLE " + quoteIdentifier(IMPORT_ROLE)
                + " IN DATABASE " + quoteIdentifier(database)
                + " SET search_path TO pg_catalog, public, pg_temp");
    }

    private static JdbcTemplate jdbc(String url, String username, String password) {
        return new JdbcTemplate(new DriverManagerDataSource(url, username, password));
    }

    private static String qualifiedNames(Iterable<String> names) {
        List<String> qualified = new ArrayList<>();
        names.forEach(name -> qualified.add("public." + quoteIdentifier(name)));
        return String.join(", ", qualified);
    }

    private static String qualifiedFunctions(Iterable<String> signatures) {
        List<String> qualified = new ArrayList<>();
        signatures.forEach(signature -> qualified.add("public." + signature));
        return String.join(", ", qualified);
    }

    private static String quoteIdentifier(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }
}
