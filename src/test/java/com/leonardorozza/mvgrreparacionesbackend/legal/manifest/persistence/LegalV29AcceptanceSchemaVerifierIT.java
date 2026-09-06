package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Fresh V29 catalog accreditation; each corruption is isolated by PostgreSQL rollback. */
class LegalV29AcceptanceSchemaVerifierIT {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_v29_schema").withUsername("ordenfix").withPassword("ordenfix");
    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static LegalV29AcceptanceSchemaVerifier verifier;

    @BeforeAll
    static void migrate() {
        POSTGRES.start();
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        verifier = new LegalV29AcceptanceSchemaVerifier(jdbc, "public");
    }

    @AfterAll
    static void stop() { POSTGRES.stop(); }

    @Test
    void cleanCatalogAndAllExistingConsumersAccreditTheSameV29() {
        var snapshot = verifier.snapshot();
        assertThat(snapshot.catalog()).as("fresh canonical V29 snapshot: %s", snapshot)
                .isEqualTo(LegalV29AcceptanceInventory.EXPECTED_CATALOG);
        verifier.verify();
        new LegalV28AggregateSchemaVerifier(jdbc, "public").verify();
        new LegalEditorialSchemaVerifier(jdbc, "public").verify();
        new LegalV27ImportSchemaVerifier(jdbc, "public").verify();
        new LegalV27SchemaVerifier(jdbc, "public").verify();
    }

    @Test
    void canonicalInventoryAlsoWorksInAnExplicitAlternativeSchema() {
        String schema = "legal_v29_alternative";
        jdbc.execute("CREATE SCHEMA " + schema);
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration").load().migrate();
            var alternative = new JdbcTemplate(new DriverManagerDataSource(
                    POSTGRES.getJdbcUrl() + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=" + schema,
                    POSTGRES.getUsername(), POSTGRES.getPassword()));
            var custom = new LegalV29AcceptanceSchemaVerifier(alternative, schema);
            assertThat(custom.snapshot().catalog()).isEqualTo(verifier.snapshot().catalog());
            custom.verify();
            new LegalV28AggregateSchemaVerifier(alternative, schema).verify();
            new LegalV27ImportSchemaVerifier(alternative, schema).verify();
        } finally {
            jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ALTER TABLE legal_idempotencia_sin_actos ENABLE ROW LEVEL SECURITY",
            "ALTER TABLE legal_idempotencia_sin_actos ALTER COLUMN referencia_count DROP NOT NULL",
            "ALTER TABLE legal_idempotencia_sin_actos DROP CONSTRAINT ck_legal_idem_sin_actos_shape",
            "ALTER TABLE legal_idempotencia_sin_actos_referencias ALTER CONSTRAINT fk_legal_idem_sin_actos_ref_padre DEFERRABLE INITIALLY DEFERRED",
            "ALTER INDEX idx_legal_idem_sin_actos_expira SET (fillfactor = 90)",
            "ALTER TABLE legal_idempotencia_sin_actos DISABLE TRIGGER USER",
            "ALTER TABLE legal_idempotencia_sin_actos_referencias DISABLE TRIGGER ALL",
            "ALTER TABLE legal_idempotencia_resultados DISABLE TRIGGER USER",
            "ALTER TABLE users DISABLE TRIGGER USER",
            "ALTER TABLE talleres DISABLE TRIGGER USER",
            "CREATE TABLE legal_v29_inheritance () INHERITS (legal_idempotencia_sin_actos)",
            "CREATE RULE legal_v29_rule AS ON INSERT TO legal_idempotencia_sin_actos DO INSTEAD NOTHING",
            "ALTER FUNCTION legal_exigir_lock_idempotente_v29(varchar,varchar,varchar,varchar) SECURITY DEFINER",
            "ALTER FUNCTION legal_exigir_lock_idempotente_v29(varchar,varchar,varchar,varchar) SET search_path TO public, pg_catalog",
            "ALTER FUNCTION legal_exigir_lock_idempotente_v29(varchar,varchar,varchar,varchar) STABLE",
            "CREATE OR REPLACE FUNCTION legal_rechazar_update_identidad_cuenta_v29() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NULL; END $$",
            "CREATE FUNCTION legal_rechazar_update_identidad_cuenta_v29(integer) RETURNS integer LANGUAGE sql AS 'SELECT $1'",
            "ALTER TABLE legal_publicaciones ENABLE ROW LEVEL SECURITY",
            "ALTER TABLE legal_requisito_agregados ENABLE ROW LEVEL SECURITY"
    })
    void shapeFunctionAndTopologyDriftFailClosedAcrossCurrentConsumers(String mutation) {
        assertDrift(mutation);
    }

    @ParameterizedTest
    @ValueSource(strings = {"27", "28", "29"})
    void everyRequiredMigrationChecksumAndPresenceRemainMandatory(String version) {
        assertDrift("UPDATE flyway_schema_history SET checksum = checksum + 1 WHERE version = '" + version + "'");
        assertDrift("DELETE FROM flyway_schema_history WHERE version = '" + version + "'");
        assertDrift("UPDATE flyway_schema_history SET success = false WHERE version = '" + version + "'");
        assertDrift("UPDATE flyway_schema_history SET script = 'unexpected.sql' WHERE version = '" + version + "'");
    }

    @Test
    void unknownMigrationAndReorderedHistoryAreNotAForwardCompatibilityShortcut() {
        assertDrift("""
                INSERT INTO flyway_schema_history
                    (installed_rank, version, description, type, script, checksum,
                     installed_by, installed_on, execution_time, success)
                SELECT max(installed_rank) + 1, '30', 'unknown', 'SQL', 'V30__unknown.sql', 1,
                       current_user, now(), 0, true FROM flyway_schema_history
                """);
        assertDrift("UPDATE flyway_schema_history SET installed_rank = 1000 WHERE version = '27'");
    }

    @Test
    void excessMigrationHistoryIsBoundedByOneRejectionSentinel() {
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.executeWithoutResult(status -> {
            jdbc.execute("""
                    INSERT INTO flyway_schema_history
                        (installed_rank, version, description, type, script, checksum,
                         installed_by, installed_on, execution_time, success)
                    SELECT previous.rank + candidate.ordinal, (29 + candidate.ordinal)::text,
                           'unknown', 'SQL', 'unexpected.sql', 1, current_user, now(), 0, true
                    FROM (SELECT max(installed_rank) AS rank FROM flyway_schema_history) previous
                    CROSS JOIN generate_series(1, 16) AS candidate(ordinal)
                    """);
            assertThat(verifier.snapshot().flyway()).hasSize(4);
            assertThatThrownBy(verifier::verify).isInstanceOf(LegalEditorialOperationalException.class);
            status.setRollbackOnly();
        });
        verifier.verify();
    }

    @Test
    void aPartialV29DeltaCannotMasqueradeAsV28() {
        assertDrift("DELETE FROM flyway_schema_history WHERE version = '29'");
    }

    @Test
    void untrustedSessionSearchPathIsRejected() {
        assertDrift("SET LOCAL search_path TO public, pg_catalog");
    }

    @Test
    void unsafeSchemaNamesFailBeforeSql() {
        for (String schema : List.of("", "Public", "public; SELECT 1")) {
            assertThatThrownBy(() -> new LegalV29AcceptanceSchemaVerifier(jdbc, schema))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static void assertDrift(String mutation) {
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.executeWithoutResult(status -> {
            jdbc.execute(mutation);
            assertThatThrownBy(verifier::verify)
                    .isInstanceOfSatisfying(LegalEditorialOperationalException.class,
                            error -> assertThat(error.issue().code()).isEqualTo(LegalManifestIssueCode.SCHEMA_DRIFT));
            assertThatThrownBy(() -> new LegalV28AggregateSchemaVerifier(jdbc, "public").verify())
                    .isInstanceOf(LegalEditorialOperationalException.class);
            assertThatThrownBy(() -> new LegalEditorialSchemaVerifier(jdbc, "public").verify())
                    .isInstanceOf(LegalEditorialOperationalException.class);
            assertThatThrownBy(() -> new LegalV27ImportSchemaVerifier(jdbc, "public").verify())
                    .isInstanceOfSatisfying(LegalImportOperationalException.class,
                            error -> assertThat(error.issue().code())
                                    .isEqualTo(LegalManifestIssueCode.IMPORT_DB_SCHEMA_INCOMPATIBLE));
            assertThatThrownBy(() -> new LegalV27SchemaVerifier(jdbc, "public").verify())
                    .isInstanceOfSatisfying(LegalDryRunOperationalException.class,
                            error -> assertThat(error.issue().code())
                                    .isEqualTo(LegalManifestIssueCode.DB_SCHEMA_INCOMPATIBLE));
            status.setRollbackOnly();
        });
        verifier.verify();
    }
}
