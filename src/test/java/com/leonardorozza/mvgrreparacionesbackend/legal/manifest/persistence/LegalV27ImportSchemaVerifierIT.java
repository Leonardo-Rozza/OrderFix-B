package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegalV27ImportSchemaVerifierIT {

    private static PostgreSQLContainer postgres;
    private static String jdbcUrl;
    private static String username;
    private static String password;

    private static LegalV27ImportSchemaVerifier verifier;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        jdbcUrl = System.getProperty(
                "ordenfix.test.schema.postgresql.url",
                System.getProperty("ordenfix.test.postgresql.url"));
        username = System.getProperty("ordenfix.test.postgresql.username", "ordenfix");
        password = System.getProperty("ordenfix.test.postgresql.password", "");
        if (jdbcUrl == null) {
            postgres = new PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("ordenfix_legal_schema_verifier")
                    .withUsername("ordenfix")
                    .withPassword("ordenfix");
            postgres.start();
            jdbcUrl = postgres.getJdbcUrl();
            username = postgres.getUsername();
            password = postgres.getPassword();
        }
        Flyway.configure()
                .dataSource(jdbcUrl, username, password)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                jdbcUrl,
                username,
                password);
        jdbc = new JdbcTemplate(dataSource);
        verifier = new LegalV27ImportSchemaVerifier(
                jdbc,
                LegalV27ImportInventory.DEFAULT_SCHEMA);
    }

    @AfterAll
    static void stopContainer() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void cleanPostgreSql16CatalogMatchesTheFrozenV27Inventory() {
        assertThat(verifier.snapshot().catalog())
                .isEqualTo(LegalV27ImportInventory.EXPECTED_CATALOG);
        verifier.verify();
    }

    @Test
    void frozenInventoryIsIndependentFromTheSafeSchemaName() {
        String schema = "legal_v27_custom_it";
        jdbc.execute("DROP SCHEMA IF EXISTS " + quoteIdentifier(schema) + " CASCADE");
        try {
            Flyway.configure()
                    .dataSource(jdbcUrl, username, password)
                    .locations("classpath:db/migration")
                    .schemas(schema)
                    .defaultSchema(schema)
                    .createSchemas(true)
                    .load()
                    .migrate();
            DriverManagerDataSource dataSource = new DriverManagerDataSource(
                    jdbcUrlForSchema(schema),
                    username,
                    password);
            LegalV27ImportSchemaVerifier customVerifier =
                    new LegalV27ImportSchemaVerifier(
                            new JdbcTemplate(dataSource),
                            schema);

            assertThat(customVerifier.snapshot().catalog())
                    .isEqualTo(LegalV27ImportInventory.EXPECTED_CATALOG);
            customVerifier.verify();
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS " + quoteIdentifier(schema) + " CASCADE");
        }
    }

    @Test
    void tablePropertyDriftFailsClosedBeforeGraphAccess() {
        assertDrift(
                "ALTER TABLE legal_publicaciones ENABLE ROW LEVEL SECURITY",
                "ALTER TABLE legal_publicaciones DISABLE ROW LEVEL SECURITY");
    }

    @Test
    void nonexistentEntryCannotHideInsideTheConfiguredSearchPath() {
        String database = jdbc.queryForObject(
                "SELECT pg_catalog.current_database()",
                String.class);
        assertDrift(
                "ALTER ROLE " + quoteIdentifier(username)
                        + " IN DATABASE " + quoteIdentifier(database)
                        + " SET search_path TO pg_catalog, legal_missing_schema, public, pg_temp",
                "ALTER ROLE " + quoteIdentifier(username)
                        + " IN DATABASE " + quoteIdentifier(database)
                        + " RESET search_path");
    }

    @Test
    void columnDriftFailsClosedBeforeGraphAccess() {
        assertDrift(
                "ALTER TABLE legal_publicaciones ALTER COLUMN email_legal DROP NOT NULL",
                "ALTER TABLE legal_publicaciones ALTER COLUMN email_legal SET NOT NULL");
    }

    @Test
    void identitySequenceDriftFailsClosedBeforeGraphAccess() {
        assertDrift(
                "ALTER SEQUENCE legal_documento_contextos_id_seq CACHE 2",
                "ALTER SEQUENCE legal_documento_contextos_id_seq CACHE 1");
    }

    @Test
    void flywayChecksumDriftFailsClosedBeforeGraphAccess() {
        assertDrift(
                "UPDATE flyway_schema_history SET checksum = checksum + 1 WHERE version = '27'",
                "UPDATE flyway_schema_history SET checksum = "
                        + LegalV27ImportInventory.FLYWAY_CHECKSUM
                        + " WHERE version = '27'");
    }

    @Test
    void reachableFunctionDriftFailsClosedBeforeGraphAccess() {
        assertDrift(
                "ALTER FUNCTION legal_validar_publicacion_sellada(uuid) SECURITY DEFINER",
                "ALTER FUNCTION legal_validar_publicacion_sellada(uuid) SECURITY INVOKER");
    }

    @Test
    void triggerDriftFailsClosedBeforeGraphAccess() {
        assertDrift(
                "ALTER TABLE legal_publicaciones DISABLE TRIGGER "
                        + "trg_legal_publicacion_insert_guard",
                "ALTER TABLE legal_publicaciones ENABLE TRIGGER "
                        + "trg_legal_publicacion_insert_guard");
    }

    @Test
    void triggerFunctionCannotBeRedirectedToAHomonymInAnotherSchema() {
        jdbc.execute("CREATE SCHEMA legal_v27_spoof");
        jdbc.execute("""
                CREATE FUNCTION legal_v27_spoof.legal_publicacion_insert_guard()
                RETURNS TRIGGER
                LANGUAGE plpgsql
                AS $$ BEGIN RETURN NEW; END; $$
                """);
        try {
            assertDrift(
                    """
                    CREATE OR REPLACE TRIGGER trg_legal_publicacion_insert_guard
                        BEFORE INSERT ON legal_publicaciones
                        FOR EACH ROW EXECUTE FUNCTION
                            legal_v27_spoof.legal_publicacion_insert_guard()
                    """,
                    """
                    CREATE OR REPLACE TRIGGER trg_legal_publicacion_insert_guard
                        BEFORE INSERT ON legal_publicaciones
                        FOR EACH ROW EXECUTE FUNCTION legal_publicacion_insert_guard()
                    """);
        } finally {
            jdbc.execute("DROP SCHEMA legal_v27_spoof CASCADE");
        }
    }

    @Test
    void disabledInternalForeignKeyTriggerFailsClosedBeforeGraphAccess() {
        String trigger = jdbc.queryForObject("""
                SELECT t.tgname
                  FROM pg_catalog.pg_trigger t
                  JOIN pg_catalog.pg_constraint c ON c.oid = t.tgconstraint
                  JOIN pg_catalog.pg_class r ON r.oid = t.tgrelid
                  JOIN pg_catalog.pg_namespace n ON n.oid = r.relnamespace
                 WHERE n.nspname = 'public'
                   AND r.relname = 'legal_documento_lineas'
                   AND c.conname = 'fk_legal_documento_lineas_publicacion'
                   AND t.tgisinternal
                 ORDER BY t.tgtype, t.tgname
                 LIMIT 1
                """, String.class);
        assertThat(trigger).isNotNull();
        assertDrift(
                "ALTER TABLE legal_documento_lineas DISABLE TRIGGER "
                        + quoteIdentifier(trigger),
                "ALTER TABLE legal_documento_lineas ENABLE TRIGGER "
                        + quoteIdentifier(trigger));
    }

    @Test
    void constraintDriftFailsClosedBeforeGraphAccess() {
        assertDrift(
                "ALTER TABLE legal_documento_lineas ALTER CONSTRAINT "
                        + "fk_legal_documento_lineas_publicacion "
                        + "DEFERRABLE INITIALLY IMMEDIATE",
                "ALTER TABLE legal_documento_lineas ALTER CONSTRAINT "
                        + "fk_legal_documento_lineas_publicacion NOT DEFERRABLE");
    }

    private static void assertDrift(String mutation, String restoration) {
        jdbc.execute(mutation);
        try {
            assertThatThrownBy(verifier::verify)
                    .isInstanceOfSatisfying(
                            LegalImportOperationalException.class,
                            failure -> assertThat(failure.issue().code())
                                    .isEqualTo(LegalManifestIssueCode
                                            .IMPORT_DB_SCHEMA_INCOMPATIBLE));
        } finally {
            jdbc.execute(restoration);
        }
        verifier.verify();
    }

    private static String quoteIdentifier(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    private static String jdbcUrlForSchema(String schema) {
        String separator = jdbcUrl.contains("?") ? "&" : "?";
        return jdbcUrl + separator + "currentSchema=" + schema;
    }
}
