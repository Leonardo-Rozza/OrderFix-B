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

/** Frozen V29 catalog accreditation; each corruption is isolated by PostgreSQL rollback.
 * V30/V31/V32 compatibility is checked separately in each public-schema PostgreSQL instance. */
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
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("29").load().migrate();
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

    @ParameterizedTest
    @ValueSource(strings = {"30", "31", "32"})
    void photoAndReauthenticationMigrationsPreserveExistingLegalConsumers(String version) {
        try (var photos = new PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("ordenfix_legal_compat_schema")
                .withUsername("ordenfix").withPassword("ordenfix")) {
            photos.start();
            var photosSource = new DriverManagerDataSource(
                    photos.getJdbcUrl(), photos.getUsername(), photos.getPassword());
            var photosFlyway = Flyway.configure().dataSource(photosSource)
                    .locations("classpath:db/migration").target(version).load();
            photosFlyway.migrate();
            assertThat(photosFlyway.info().current().getVersion().toString()).isEqualTo(version);
            var photosJdbc = new JdbcTemplate(photosSource);
            var photosVerifier = new LegalV29AcceptanceSchemaVerifier(photosJdbc, "public");
            assertThat(photosVerifier.snapshot().catalog()).isEqualTo(LegalPrivatePhotoSchema.LEGAL_CATALOG);
            photosVerifier.verify();
            new LegalV28AggregateSchemaVerifier(photosJdbc, "public").verify();
            new LegalEditorialSchemaVerifier(photosJdbc, "public").verify();
            new LegalV27ImportSchemaVerifier(photosJdbc, "public").verify();
            new LegalV27SchemaVerifier(photosJdbc, "public").verify();
            if (!version.equals("30")) {
                for (String requiredVersion : version.equals("32") ? List.of("30", "31", "32") : List.of("30", "31")) {
                    for (String mutation : List.of(
                            "UPDATE flyway_schema_history SET checksum = checksum + 1 WHERE version = '%s'",
                            "UPDATE flyway_schema_history SET success = false WHERE version = '%s'",
                            "UPDATE flyway_schema_history SET script = 'unexpected.sql' WHERE version = '%s'")) {
                        assertDrift(mutation.formatted(requiredVersion), photosSource, photosJdbc, photosVerifier);
                    }
                }
                assertDrift("DELETE FROM flyway_schema_history WHERE version = '30'",
                        photosSource, photosJdbc, photosVerifier);
                // The legal preflight owns only the legal/photo boundary, not the independent account table.
                // The previous exact history plus an unrelated account table stays compatible; application startup
                // still validates and migrates the complete Flyway history before using reauthentication.
                var historyTransaction = new TransactionTemplate(new DataSourceTransactionManager(photosSource));
                historyTransaction.executeWithoutResult(status -> {
                    photosJdbc.update("DELETE FROM flyway_schema_history WHERE version = ?", version);
                    photosVerifier.verify();
                    new LegalV28AggregateSchemaVerifier(photosJdbc, "public").verify();
                    new LegalEditorialSchemaVerifier(photosJdbc, "public").verify();
                    new LegalV27ImportSchemaVerifier(photosJdbc, "public").verify();
                    new LegalV27SchemaVerifier(photosJdbc, "public").verify();
                    status.setRollbackOnly();
                });
                assertDrift("ALTER TABLE users ALTER COLUMN token_version DROP NOT NULL",
                        photosSource, photosJdbc, photosVerifier);
                var photoTransaction = new TransactionTemplate(new DataSourceTransactionManager(photosSource));
                photoTransaction.executeWithoutResult(status -> {
                    photosJdbc.execute("ALTER TABLE reparacion_fotos_privadas ENABLE ROW LEVEL SECURITY");
                    for (Runnable consumer : List.<Runnable>of(photosVerifier::verify,
                            () -> new LegalV28AggregateSchemaVerifier(photosJdbc, "public").verify(),
                            () -> new LegalEditorialSchemaVerifier(photosJdbc, "public").verify(),
                            () -> new LegalV27ImportSchemaVerifier(photosJdbc, "public").verify(),
                            () -> new LegalV27SchemaVerifier(photosJdbc, "public").verify())) {
                        assertThatThrownBy(consumer::run).isInstanceOf(IllegalStateException.class)
                                .hasMessage("Esquema de fotos privadas incompatible");
                    }
                    status.setRollbackOnly();
                });
                photosVerifier.verify();
                assertDrift("UPDATE flyway_schema_history SET installed_rank = 0 WHERE version = '31'",
                        photosSource, photosJdbc, photosVerifier);
                assertDrift("""
                        INSERT INTO flyway_schema_history
                            (installed_rank, version, description, type, script, checksum,
                             installed_by, installed_on, execution_time, success)
                        SELECT max(installed_rank) + 1, '33', 'unknown', 'SQL', 'V33__unknown.sql', 1,
                               current_user, now(), 0, true FROM flyway_schema_history
                        """, photosSource, photosJdbc, photosVerifier);
            }
        }
    }

    @Test
    void canonicalInventoryAlsoWorksInAnExplicitAlternativeSchema() {
        String schema = "legal_v29_alternative";
        jdbc.execute("CREATE SCHEMA " + schema);
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration").target("29").load().migrate();
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
            // Ten admitted versions (V27–V36), plus one bounded rejection sentinel.
            assertThat(verifier.snapshot().flyway()).hasSize(11);
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
        assertDrift(mutation, dataSource, jdbc, verifier);
    }

    private static void assertDrift(String mutation, DataSource source, JdbcTemplate selectedJdbc,
                                    LegalV29AcceptanceSchemaVerifier selectedVerifier) {
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.executeWithoutResult(status -> {
            selectedJdbc.execute(mutation);
            assertThatThrownBy(selectedVerifier::verify)
                    .isInstanceOfSatisfying(LegalEditorialOperationalException.class,
                            error -> assertThat(error.issue().code()).isEqualTo(LegalManifestIssueCode.SCHEMA_DRIFT));
            assertThatThrownBy(() -> new LegalV28AggregateSchemaVerifier(selectedJdbc, "public").verify())
                    .isInstanceOf(LegalEditorialOperationalException.class);
            assertThatThrownBy(() -> new LegalEditorialSchemaVerifier(selectedJdbc, "public").verify())
                    .isInstanceOf(LegalEditorialOperationalException.class);
            assertThatThrownBy(() -> new LegalV27ImportSchemaVerifier(selectedJdbc, "public").verify())
                    .isInstanceOfSatisfying(LegalImportOperationalException.class,
                            error -> assertThat(error.issue().code())
                                    .isEqualTo(LegalManifestIssueCode.IMPORT_DB_SCHEMA_INCOMPATIBLE));
            assertThatThrownBy(() -> new LegalV27SchemaVerifier(selectedJdbc, "public").verify())
                    .isInstanceOfSatisfying(LegalDryRunOperationalException.class,
                            error -> assertThat(error.issue().code())
                                    .isEqualTo(LegalManifestIssueCode.DB_SCHEMA_INCOMPATIBLE));
            status.setRollbackOnly();
        });
        selectedVerifier.verify();
    }
}
