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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Execution(ExecutionMode.SAME_THREAD)
class LegalV28AggregateSchemaVerifierIT {

    private static PostgreSQLContainer postgres;
    private static String jdbcUrl;
    private static String username;
    private static String password;

    private static LegalV28AggregateSchemaVerifier verifier;
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
                    .withDatabaseName("ordenfix_legal_v28_schema_verifier")
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
                .target("28")
                .load()
                .migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                jdbcUrl,
                username,
                password));
        verifier = new LegalV28AggregateSchemaVerifier(
                jdbc,
                LegalV28AggregateInventory.DEFAULT_SCHEMA);
    }

    @AfterAll
    static void stopContainer() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void cleanPostgreSql16CatalogMatchesTheFrozenV28Inventory() {
        LegalV28AggregateSchemaVerifier.CatalogSnapshot snapshot = verifier.snapshot();

        assertThat(snapshot.catalog())
                .as("clean snapshot: %s", snapshot)
                .isEqualTo(LegalV28AggregateInventory.EXPECTED_CATALOG);
        assertThat(snapshot.functions()).hasSize(
                LegalV28AggregateInventory.SCHEMA_FUNCTIONS.size());
        assertThat(snapshot.functions().keySet()).containsExactlyInAnyOrderElementsOf(
                LegalV28AggregateInventory.SCHEMA_FUNCTIONS.keySet());
        assertThat(snapshot.flyway()).containsExactly(
                new LegalV28AggregateSchemaVerifier.FlywayState(
                        LegalV28AggregateInventory.FLYWAY_VERSION_V27,
                        LegalV28AggregateInventory.FLYWAY_TYPE,
                        LegalV28AggregateInventory.FLYWAY_SCRIPT_V27,
                        LegalV28AggregateInventory.FLYWAY_CHECKSUM_V27,
                        true,
                        false),
                new LegalV28AggregateSchemaVerifier.FlywayState(
                        LegalV28AggregateInventory.FLYWAY_VERSION_V28,
                        LegalV28AggregateInventory.FLYWAY_TYPE,
                        LegalV28AggregateInventory.FLYWAY_SCRIPT_V28,
                        LegalV28AggregateInventory.FLYWAY_CHECKSUM_V28,
                        true,
                        true));
        verifier.verify();
    }

    @Test
    void v28InventoryFreezesEightTablesTwentyFunctionsChecksumAndFingerprint() {
        assertThat(LegalV28AggregateInventory.CATALOG_TABLES).containsExactly(
                "legal_requisito_agregados",
                "legal_requisito_agregado_scopes",
                "legal_aceptacion_lotes",
                "legal_aceptaciones",
                "legal_aceptacion_documentos",
                "legal_aceptacion_metadatos",
                "legal_aceptacion_metadatos_cifrados",
                "legal_idempotencia_resultados");
        assertThat(LegalV28AggregateInventory.LEGAL_GRAPH_TABLES)
                .hasSize(29);
        assertThat(LegalV28AggregateInventory.LEGAL_GRAPH_TABLES.subList(0, 19))
                .containsExactlyElementsOf(
                        LegalV27EditorialInventory.EDITORIAL_TABLES);
        assertThat(LegalV28AggregateInventory.LEGAL_GRAPH_TABLES.subList(19, 27))
                .containsExactlyElementsOf(
                        LegalV28AggregateInventory.CATALOG_TABLES);
        assertThat(LegalV28AggregateInventory.LEGAL_GRAPH_TABLES.subList(27, 29))
                .containsExactlyElementsOf(
                        LegalV28AggregateInventory.RELATION_BOUNDARY_DEPENDENCIES);
        assertThat(LegalV28AggregateInventory.V28_FUNCTIONS)
                .hasSize(9)
                .containsOnlyKeys(
                        "legal_exigir_lock_editorial_v28()",
                        "legal_requisito_agregado_insert_guard()",
                        "legal_requisito_agregado_scope_insert_guard()",
                        "legal_validar_requisito_agregado(uuid)",
                        "legal_requisito_agregado_constraint_guard()",
                        "legal_validar_requisito_agregado_actual(uuid)",
                        "legal_aceptacion_lote_insert_guard()",
                        "legal_aceptacion_insert_guard()",
                        "legal_aceptacion_agregado_constraint_guard()");
        assertThat(LegalV28AggregateInventory.LEGACY_ACCEPTANCE_FUNCTIONS)
                .hasSize(11)
                .containsOnlyKeys(
                        "legal_fila_es_transaccion_actual(xid)",
                        "legal_aceptacion_documento_insert_guard()",
                        "legal_metadata_header_insert_guard()",
                        "legal_metadata_cifrada_insert_guard()",
                        "legal_validar_aceptacion(uuid)",
                        "legal_validar_lote_aceptacion(uuid)",
                        "legal_aceptacion_constraint_guard()",
                        "legal_metadata_cifrada_update_guard()",
                        "legal_metadata_header_update_guard()",
                        "legal_idempotencia_insert_guard()",
                        "legal_idempotencia_update_delete_guard()");
        assertThat(LegalV28AggregateInventory.SCHEMA_FUNCTIONS)
                .hasSize(20)
                .containsAllEntriesOf(LegalV28AggregateInventory.V28_FUNCTIONS)
                .containsAllEntriesOf(
                        LegalV28AggregateInventory.LEGACY_ACCEPTANCE_FUNCTIONS);
        assertThat(LegalV28AggregateInventory.FLYWAY_CHECKSUM_V28)
                .isEqualTo(1_900_377_028);
        assertThat(LegalV28AggregateInventory.EXPECTED_CATALOG)
                .isEqualTo(new LegalV28AggregateInventory.CatalogFingerprint(
                        8,
                        "a22262b2fda50aa3ed2a8e8bba5886d60b73c6e7ca10d25c24d1607641a401d5",
                        73,
                        "bef14c0ff3fcda14c50e559ad446ac88b3ae94831e09d6188b471ed09d564f0b",
                        75,
                        "dc7eb059a84c601924380310166df488a5e5ed562f5ff21dcdfd3bc6a682f522",
                        30,
                        "0420bf78aebffe7f2777dbf433b5b717f921d045511e647f8eb0d37fe8fdfde4",
                        27,
                        "5af75cf387b32f69a8b43726a2f9adeea91f4034cf3604891f0ed298736b33b6",
                        3,
                        "419f8ef41627f77b98aa9849cce4bcd0775a831eb4cd8402df499fc73702c846"));
    }

    @Test
    void frozenInventoryIsIndependentFromTheSafeSchemaName() {
        String schema = "legal_v28_aggregate_it";
        jdbc.execute("DROP SCHEMA IF EXISTS " + quoteIdentifier(schema) + " CASCADE");
        try {
            Flyway.configure()
                    .dataSource(jdbcUrl, username, password)
                    .locations("classpath:db/migration")
                    .target("28")
                    .schemas(schema)
                    .defaultSchema(schema)
                    .createSchemas(true)
                    .load()
                    .migrate();
            LegalV28AggregateSchemaVerifier customVerifier =
                    new LegalV28AggregateSchemaVerifier(
                            new JdbcTemplate(new DriverManagerDataSource(
                                    jdbcUrlForSchema(schema),
                                    username,
                                    password)),
                            schema);

            LegalV28AggregateSchemaVerifier.CatalogSnapshot snapshot =
                    customVerifier.snapshot();
            assertThat(snapshot.catalog())
                    .isEqualTo(LegalV28AggregateInventory.EXPECTED_CATALOG);
            assertThat(snapshot.functions().keySet())
                    .containsExactlyInAnyOrderElementsOf(
                            LegalV28AggregateInventory.SCHEMA_FUNCTIONS.keySet());
            assertThat(snapshot.flyway())
                    .isEqualTo(verifier.snapshot().flyway());
            customVerifier.verify();
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS " + quoteIdentifier(schema) + " CASCADE");
        }
    }

    @Test
    void v28TablePropertyDriftFailsClosedAndRestores() {
        assertDrift(
                "ALTER TABLE legal_requisito_agregados ENABLE ROW LEVEL SECURITY",
                "ALTER TABLE legal_requisito_agregados DISABLE ROW LEVEL SECURITY");
    }

    @Test
    void v27InheritanceDriftFailsClosedAndRestores() {
        assertDrift(
                "CREATE TABLE legal_v28_inheritance_drift () "
                        + "INHERITS (legal_requisito_conjuntos_actuales)",
                "DROP TABLE legal_v28_inheritance_drift");
    }

    @Test
    void v28RuleDriftFailsClosedAndRestores() {
        assertDrift(
                "CREATE RULE legal_v28_rule_drift AS ON INSERT "
                        + "TO legal_requisito_agregados DO INSTEAD NOTHING",
                "DROP RULE legal_v28_rule_drift ON legal_requisito_agregados");
    }

    @Test
    void v28ColumnDriftFailsClosedAndRestores() {
        assertDrift(
                "ALTER TABLE legal_requisito_agregados "
                        + "ALTER COLUMN scope_count DROP NOT NULL",
                "ALTER TABLE legal_requisito_agregados "
                        + "ALTER COLUMN scope_count SET NOT NULL");
    }

    @Test
    void v28ConstraintDriftFailsClosedAndRestores() {
        assertDrift(
                "ALTER TABLE legal_requisito_agregado_scopes ALTER CONSTRAINT "
                        + "fk_legal_requisito_agregado_scope_cabecera "
                        + "DEFERRABLE INITIALLY IMMEDIATE",
                "ALTER TABLE legal_requisito_agregado_scopes ALTER CONSTRAINT "
                        + "fk_legal_requisito_agregado_scope_cabecera NOT DEFERRABLE");
    }

    @Test
    void v28IndexDriftFailsClosedAndRestores() {
        assertDrift(
                "ALTER INDEX idx_legal_requisito_agregados_revision "
                        + "SET (fillfactor = 90)",
                "ALTER INDEX idx_legal_requisito_agregados_revision "
                        + "RESET (fillfactor)");
    }

    @Test
    void v28TriggerDriftFailsClosedAndRestores() {
        assertDrift(
                "ALTER TABLE legal_requisito_agregados DISABLE TRIGGER "
                        + "trg_legal_requisito_agregado_insert",
                "ALTER TABLE legal_requisito_agregados ENABLE TRIGGER "
                        + "trg_legal_requisito_agregado_insert");
    }

    @Test
    void v28FunctionDriftFailsClosedAndRestores() {
        assertDrift(
                "ALTER FUNCTION legal_exigir_lock_editorial_v28() SECURITY DEFINER",
                "ALTER FUNCTION legal_exigir_lock_editorial_v28() SECURITY INVOKER");
    }

    @Test
    void legacyAcceptanceFunctionSourceDriftFailsClosedAndRestores() {
        assertDrift(
                xminFunction("    SELECT TRUE"),
                xminFunction("    SELECT p_xmin::TEXT::NUMERIC =\n"
                        + "           mod(pg_current_xact_id()::TEXT::NUMERIC, "
                        + "4294967296::NUMERIC)"));
    }

    @Test
    void v28FlywayChecksumDriftFailsClosedAndRestores() {
        assertDrift(
                "UPDATE flyway_schema_history SET checksum = checksum + 1 "
                        + "WHERE version = '28'",
                "UPDATE flyway_schema_history SET checksum = "
                        + LegalV28AggregateInventory.FLYWAY_CHECKSUM_V28
                        + " WHERE version = '28'");
    }

    @Test
    void composedV27ImportDriftIsTranslatedToSchemaDriftAndRestores() {
        assertDrift(
                "ALTER TABLE legal_publicaciones ENABLE ROW LEVEL SECURITY",
                "ALTER TABLE legal_publicaciones DISABLE ROW LEVEL SECURITY");
    }

    @Test
    void revisionSchemeDefaultWasRetiredAfterHistoricalCompatibilityMigration() {
        Integer defaults = jdbc.queryForObject("""
                SELECT pg_catalog.count(*)::integer
                  FROM pg_catalog.pg_attrdef d
                  JOIN pg_catalog.pg_attribute a
                    ON a.attrelid = d.adrelid AND a.attnum = d.adnum
                  JOIN pg_catalog.pg_class c ON c.oid = a.attrelid
                  JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = ?
                   AND c.relname = 'legal_aceptacion_lotes'
                   AND a.attname = 'revision_scheme'
                """, Integer.class, LegalV28AggregateInventory.DEFAULT_SCHEMA);

        assertThat(defaults).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT a.attnotnull
                  FROM pg_catalog.pg_attribute a
                  JOIN pg_catalog.pg_class c ON c.oid = a.attrelid
                  JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = ?
                   AND c.relname = 'legal_aceptacion_lotes'
                   AND a.attname = 'revision_scheme'
                """, Boolean.class, LegalV28AggregateInventory.DEFAULT_SCHEMA))
                .isTrue();
        verifier.verify();
    }

    @Test
    void unsafeSchemaIdentifiersAreRejectedBeforeAnyCatalogRead() {
        for (String unsafe : List.of("", "Public", "public;drop schema public")) {
            assertThatThrownBy(() -> new LegalV28AggregateSchemaVerifier(jdbc, unsafe))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static void assertDrift(String mutation, String restoration) {
        try {
            jdbc.execute(mutation);
            assertThatThrownBy(verifier::verify)
                    .isInstanceOfSatisfying(
                            LegalEditorialOperationalException.class,
                            failure -> {
                                assertThat(failure.issue().code())
                                        .isEqualTo(LegalManifestIssueCode.SCHEMA_DRIFT);
                                assertThat(failure.issue().location())
                                        .isEqualTo(
                                                LegalV28AggregateSchemaVerifier
                                                        .ISSUE_LOCATION);
                            });
        } finally {
            jdbc.execute(restoration);
        }
        verifier.verify();
    }

    private static String quoteIdentifier(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    private static String xminFunction(String body) {
        return """
                CREATE OR REPLACE FUNCTION legal_fila_es_transaccion_actual(p_xmin XID)
                RETURNS BOOLEAN
                LANGUAGE sql
                VOLATILE
                SECURITY INVOKER
                SET search_path TO pg_catalog, public, pg_temp
                AS $$
                """ + body + "\n$$";
    }

    private static String jdbcUrlForSchema(String schema) {
        String separator = jdbcUrl.contains("?") ? "&" : "?";
        return jdbcUrl + separator + "currentSchema=" + schema;
    }
}
