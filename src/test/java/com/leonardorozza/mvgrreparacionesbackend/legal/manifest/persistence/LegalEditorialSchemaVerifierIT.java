package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Execution(ExecutionMode.SAME_THREAD)
class LegalEditorialSchemaVerifierIT {

    private static PostgreSQLContainer postgres;
    private static String jdbcUrl;
    private static String username;
    private static String password;

    private static LegalEditorialSchemaVerifier verifier;
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
                    .withDatabaseName("ordenfix_legal_editorial_schema_verifier")
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
                .target("27")
                .load()
                .migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                jdbcUrl,
                username,
                password);
        jdbc = new JdbcTemplate(dataSource);
        verifier = new LegalEditorialSchemaVerifier(
                jdbc,
                LegalV27EditorialInventory.DEFAULT_SCHEMA);
    }

    @AfterAll
    static void stopContainer() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void cleanPostgreSql16CatalogMatchesTheFrozenEditorialInventory() {
        LegalEditorialSchemaVerifier.CatalogSnapshot snapshot = verifier.snapshot();

        assertThat(snapshot.catalog())
                .isEqualTo(LegalV27EditorialInventory.EXPECTED_CATALOG);
        assertThat(snapshot.functions())
                .hasSize(LegalV27EditorialInventory.EDITORIAL_FUNCTIONS.size());
        assertThat(snapshot.functions().keySet())
                .containsExactlyInAnyOrderElementsOf(
                        LegalV27EditorialInventory.EDITORIAL_FUNCTIONS.keySet());
        verifier.verify();
    }

    @Test
    void editorialInventoryIsTheIndependentNineteenTableTenSequenceSurface() {
        assertThat(LegalV27EditorialInventory.EDITORIAL_TABLES)
                .hasSize(19)
                .containsAll(LegalV27ImportInventory.IMPORT_TABLES);
        assertThat(LegalV27EditorialInventory.IDENTITY_SEQUENCES)
                .hasSize(10)
                .containsAllEntriesOf(LegalV27ImportInventory.IDENTITY_SEQUENCES);
        assertThat(LegalV27EditorialInventory.EDITORIAL_FUNCTIONS)
                .hasSize(34);
        assertThat(LegalV27EditorialInventory.EDITORIAL_FUNCTIONS.keySet())
                .containsAll(LegalV27ImportInventory.IMPORT_FUNCTIONS.keySet());
        LegalV27ImportInventory.IMPORT_FUNCTIONS.forEach((signature, importSpec) ->
                assertThat(LegalV27EditorialInventory.EDITORIAL_FUNCTIONS.get(signature)
                        .sourceSha256()).isEqualTo(importSpec.sourceSha256()));

        assertThat(LegalV27ImportInventory.IMPORT_TABLES).containsExactly(
                "legal_publicaciones",
                "legal_documento_lineas",
                "legal_documento_versiones",
                "legal_documento_contextos",
                "legal_publicacion_documentos",
                "legal_requisito_lineas",
                "legal_requisito_audiencias",
                "legal_requisito_versiones",
                "legal_requisito_documentos",
                "legal_publicacion_requisitos",
                "legal_requisito_conjuntos",
                "legal_requisito_conjunto_miembros");
        assertThat(LegalV27ImportInventory.IDENTITY_SEQUENCES).hasSize(6);
        assertThat(LegalV27ImportInventory.EXPECTED_CATALOG)
                .isEqualTo(new LegalV27ImportInventory.CatalogFingerprint(
                        12,
                        "d3b0a50cb6cbdf0a0a8eab97bd10ae0e1f8e605ce009c6083ae77e1827257ad9",
                        93,
                        "71ce2628bcac127f8798bbd5098563c8bd3a43c8fc505bd7794dfaa726ae96a7",
                        97,
                        "359366a916255d91e4de547eb4476d236b307c984495490071f1b54920936475",
                        29,
                        "e85176c8a84aa7cbf52b5051b8a73981529e29955cf5a49127e20f7cbe3205e3",
                        6,
                        "308609421640e4120de7cf8621a605b541287c0808e9b44ca0f64f782df2874e"));
    }

    @Test
    void frozenInventoryIsIndependentFromTheSafeSchemaName() {
        String schema = "legal_v27_editorial_it";
        jdbc.execute("DROP SCHEMA IF EXISTS " + quoteIdentifier(schema) + " CASCADE");
        try {
            Flyway.configure()
                    .dataSource(jdbcUrl, username, password)
                    .locations("classpath:db/migration")
                    .target("27")
                    .schemas(schema)
                    .defaultSchema(schema)
                    .createSchemas(true)
                    .load()
                    .migrate();
            DriverManagerDataSource dataSource = new DriverManagerDataSource(
                    jdbcUrlForSchema(schema),
                    username,
                    password);
            LegalEditorialSchemaVerifier customVerifier =
                    new LegalEditorialSchemaVerifier(
                            new JdbcTemplate(dataSource),
                            schema);

            assertThat(customVerifier.snapshot().catalog())
                    .isEqualTo(LegalV27EditorialInventory.EXPECTED_CATALOG);
            customVerifier.verify();
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS " + quoteIdentifier(schema) + " CASCADE");
        }
    }

    @Test
    void tablePropertyDriftFailsClosedBeforeEditorialGraphAccess() {
        assertDrift(
                "ALTER TABLE legal_documento_reemplazo_lotes ENABLE ROW LEVEL SECURITY",
                "ALTER TABLE legal_documento_reemplazo_lotes DISABLE ROW LEVEL SECURITY");
    }

    @Test
    void newlyCoveredTransitionColumnDriftFailsClosed() {
        assertDrift(
                "ALTER TABLE legal_documento_transiciones "
                        + "ALTER COLUMN ocurrido_en DROP NOT NULL",
                "ALTER TABLE legal_documento_transiciones "
                        + "ALTER COLUMN ocurrido_en SET NOT NULL");
    }

    @Test
    void newlyCoveredCurrentProjectionConstraintDriftFailsClosed() {
        assertDrift(
                "ALTER TABLE legal_documento_vigentes ALTER CONSTRAINT "
                        + "fk_legal_documento_vigente_estado NOT DEFERRABLE",
                "ALTER TABLE legal_documento_vigentes ALTER CONSTRAINT "
                        + "fk_legal_documento_vigente_estado "
                        + "DEFERRABLE INITIALLY DEFERRED");
    }

    @Test
    void newlyCoveredIdentitySequenceDriftFailsClosed() {
        assertDrift(
                "ALTER SEQUENCE legal_documento_transiciones_id_seq CACHE 2",
                "ALTER SEQUENCE legal_documento_transiciones_id_seq CACHE 1");
    }

    @Test
    void newlyCoveredTriggerDriftFailsClosed() {
        assertDrift(
                "ALTER TABLE legal_requisito_transiciones DISABLE TRIGGER "
                        + "trg_legal_requisito_transicion_before",
                "ALTER TABLE legal_requisito_transiciones ENABLE TRIGGER "
                        + "trg_legal_requisito_transicion_before");
    }

    @Test
    void newlyCoveredFunctionDriftFailsClosed() {
        assertDrift(
                "ALTER FUNCTION legal_validar_slots_documentales() SECURITY DEFINER",
                "ALTER FUNCTION legal_validar_slots_documentales() SECURITY INVOKER");
    }

    @Test
    void replacementMembershipInternalForeignKeyTriggerDriftFailsClosed() {
        String trigger = jdbc.queryForObject("""
                SELECT t.tgname
                  FROM pg_catalog.pg_trigger t
                  JOIN pg_catalog.pg_constraint c ON c.oid = t.tgconstraint
                  JOIN pg_catalog.pg_class r ON r.oid = t.tgrelid
                  JOIN pg_catalog.pg_namespace n ON n.oid = r.relnamespace
                 WHERE n.nspname = 'public'
                   AND r.relname = 'legal_documento_reemplazo_sucesoras'
                   AND c.conname = 'fk_legal_doc_reemplazo_sucesora_membresia'
                   AND t.tgisinternal
                 ORDER BY t.tgtype, t.tgname
                 LIMIT 1
                """, String.class);
        assertThat(trigger).isNotNull();
        assertDrift(
                "ALTER TABLE legal_documento_reemplazo_sucesoras DISABLE TRIGGER "
                        + quoteIdentifier(trigger),
                "ALTER TABLE legal_documento_reemplazo_sucesoras ENABLE TRIGGER "
                        + quoteIdentifier(trigger));
    }

    @Test
    void triggerFunctionCannotBeRedirectedToAHomonymInAnotherSchema() {
        jdbc.execute("CREATE SCHEMA legal_editorial_spoof");
        jdbc.execute("""
                CREATE FUNCTION legal_editorial_spoof.legal_documento_slot_insert_guard()
                RETURNS TRIGGER
                LANGUAGE plpgsql
                AS $$ BEGIN RETURN NEW; END; $$
                """);
        try {
            assertDrift(
                    """
                    CREATE OR REPLACE TRIGGER trg_legal_documento_slot_insert
                        BEFORE INSERT ON legal_documento_vigentes
                        FOR EACH ROW EXECUTE FUNCTION
                            legal_editorial_spoof.legal_documento_slot_insert_guard()
                    """,
                    """
                    CREATE OR REPLACE TRIGGER trg_legal_documento_slot_insert
                        BEFORE INSERT ON legal_documento_vigentes
                        FOR EACH ROW EXECUTE FUNCTION legal_documento_slot_insert_guard()
                    """);
        } finally {
            jdbc.execute("DROP SCHEMA legal_editorial_spoof CASCADE");
        }
    }

    @Test
    void flywayChecksumDriftFailsClosed() {
        assertDrift(
                "UPDATE flyway_schema_history SET checksum = checksum + 1 WHERE version = '27'",
                "UPDATE flyway_schema_history SET checksum = "
                        + LegalV27EditorialInventory.FLYWAY_CHECKSUM
                        + " WHERE version = '27'");
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
    void nonEditorialAcceptanceDriftDoesNotExpandThisVerifier() {
        jdbc.execute("ALTER TABLE legal_aceptacion_lotes ENABLE ROW LEVEL SECURITY");
        try {
            verifier.verify();
        } finally {
            jdbc.execute("ALTER TABLE legal_aceptacion_lotes DISABLE ROW LEVEL SECURITY");
        }
        verifier.verify();
    }

    @Test
    void unsafeSchemaIdentifiersAreRejectedBeforeAnyCatalogRead() {
        assertThatThrownBy(() -> new LegalEditorialSchemaVerifier(
                jdbc,
                "public;drop schema public"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LegalEditorialSchemaVerifier(jdbc, ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertDrift(String mutation, String restoration) {
        jdbc.execute(mutation);
        try {
            assertThatThrownBy(verifier::verify)
                    .isInstanceOfSatisfying(
                            LegalEditorialOperationalException.class,
                            failure -> {
                                assertThat(failure.issue().code())
                                        .isEqualTo(LegalManifestIssueCode.SCHEMA_DRIFT);
                                assertThat(failure.issue().location())
                                        .isEqualTo(LegalEditorialSchemaVerifier.ISSUE_LOCATION);
                            });
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
