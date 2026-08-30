package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialStateFingerprintCalculator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetRevisionCalculator;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.cleanLegalState;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.copyRelease;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.editorialSequenceStates;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.editorialTableCounts;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.harness;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class LegalEditorialPrivilegeVerifierIT {

    private static final String EDITORIAL_ROLE = "ordenfix_legal_editorial_it";
    private static final String EDITORIAL_PASSWORD = "legal-editorial-test-only";
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("ordenfix_legal_editorial_privileges")
                    .withUsername("ordenfix")
                    .withPassword("ordenfix");

    @TempDir
    private static Path temporaryDirectory;

    private static JdbcTemplate owner;
    private static JdbcTemplate restricted;
    private static LegalEditorialPrivilegeVerifier verifier;
    private static LegalEditorialSchemaVerifier schemaVerifier;
    private static LegalEditorialApplyService applyService;
    private static LegalManifestPersistenceITSupport.Harness importer;
    private static ValidatedRelease golden;

    @BeforeAll
    static void migrateAndProvisionRestrictedRole() throws Exception {
        POSTGRES.start();
        Flyway.configure()
                .dataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        owner = jdbc(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
        LegalRestrictedEditorialRoleFixture.Credentials credentials =
                new LegalRestrictedEditorialRoleFixture(
                        owner,
                        POSTGRES.getJdbcUrl(),
                        EDITORIAL_ROLE,
                        EDITORIAL_PASSWORD,
                        POSTGRES.getDriverClassName())
                        .provisionAndVerify();
        restricted = jdbc(
                credentials.jdbcUrl(),
                credentials.username(),
                credentials.password());
        verifier = new LegalEditorialPrivilegeVerifier(
                restricted,
                EDITORIAL_ROLE,
                LegalV27EditorialInventory.DEFAULT_SCHEMA);
        schemaVerifier = new LegalEditorialSchemaVerifier(
                restricted,
                LegalV27EditorialInventory.DEFAULT_SCHEMA);
        applyService = applyService(restricted, schemaVerifier, verifier);
        DataSource ownerDataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
        importer = harness(ownerDataSource, LegalDatabaseBudgets.production());
        golden = copyRelease(
                temporaryDirectory,
                LegalEditorialPrivilegeVerifierIT.class,
                "editorial-role-v1",
                (manifestPath, manifest) -> manifest.withArray("documents")
                        .forEach(document -> ((ObjectNode) document)
                                .put("effectiveAt", "2020-01-01T00:00:00Z")));
    }

    @AfterAll
    static void stopContainer() {
        POSTGRES.stop();
    }

    @BeforeEach
    void cleanAndRecheckRole() {
        cleanLegalState(owner);
        verifier.verify();
    }

    @Test
    void exactStandaloneRolePassesTheEffectivePrivilegeMatrix() {
        schemaVerifier.verify();
        verifier.verify();
        Long largeObjectsBefore = owner.queryForObject(
                "SELECT count(*) FROM pg_catalog.pg_largeobject_metadata",
                Long.class);

        assertThat(restricted.queryForObject("""
                SELECT checksum
                  FROM flyway_schema_history
                 WHERE version = '27'
                """, Integer.class))
                .isEqualTo(LegalV27EditorialInventory.FLYWAY_CHECKSUM);
        assertSqlState(() -> restricted.update("""
                UPDATE flyway_schema_history
                   SET checksum = checksum
                 WHERE version = '27'
                """), "42501");
        assertSqlState(() -> restricted.queryForObject(
                "SELECT pg_catalog.lo_creat(0)::bigint",
                Long.class), "42501");
        assertSqlState(() -> restricted.queryForObject(
                "SELECT pg_catalog.lo_create(0)::bigint",
                Long.class), "42501");
        assertSqlState(() -> restricted.queryForObject(
                "SELECT pg_catalog.lo_from_bytea(0, ''::bytea)::bigint",
                Long.class), "42501");
        assertThat(owner.queryForObject(
                "SELECT count(*) FROM pg_catalog.pg_largeobject_metadata",
                Long.class)).isEqualTo(largeObjectsBefore);
    }

    @Test
    void exactOfflineRolePromotesAndReplaysWithoutAdvancingSequences() {
        LegalManifestImportResult imported = importer.importService().importManifest(golden);
        assertThat(imported.status()).isEqualTo(LegalManifestStatus.PASS);

        LegalEditorialApplyResult applied = applyService.applyPromote(golden);
        Map<String, LegalManifestPersistenceITSupport.SequenceState> afterApply =
                editorialSequenceStates(owner);
        LegalEditorialApplyResult replay = applyService.applyPromote(golden);

        assertThat(applied.status())
                .as("issues=%s", applied.issues())
                .isEqualTo(LegalManifestStatus.PASS);
        assertThat(applied.outcome()).isEqualTo(LegalEditorialApplyResult.Outcome.APPLIED);
        assertThat(applied.persisted()).isTrue();
        assertThat(replay.status()).isEqualTo(LegalManifestStatus.PASS);
        assertThat(replay.outcome())
                .isEqualTo(LegalEditorialApplyResult.Outcome.ALREADY_APPLIED);
        assertThat(replay.receipt()).isEqualTo(applied.receipt());
        assertThat(editorialSequenceStates(owner)).isEqualTo(afterApply);
    }

    @Test
    void roleCapabilitiesMembershipAndDatabaseOrSchemaExpansionFailClosed() {
        assertPrivilegeDrift(
                "ALTER ROLE " + quoteIdentifier(EDITORIAL_ROLE) + " SUPERUSER",
                "ALTER ROLE " + quoteIdentifier(EDITORIAL_ROLE) + " NOSUPERUSER");

        owner.execute("CREATE ROLE ordenfix_legal_editorial_broad_it NOLOGIN");
        try {
            assertPrivilegeDrift(
                    "GRANT ordenfix_legal_editorial_broad_it TO "
                            + quoteIdentifier(EDITORIAL_ROLE),
                    "REVOKE ordenfix_legal_editorial_broad_it FROM "
                            + quoteIdentifier(EDITORIAL_ROLE));
        } finally {
            owner.execute("DROP ROLE ordenfix_legal_editorial_broad_it");
        }

        String database = owner.queryForObject(
                "SELECT pg_catalog.current_database()",
                String.class);
        assertPrivilegeDrift(
                "GRANT TEMPORARY ON DATABASE " + quoteIdentifier(database)
                        + " TO " + quoteIdentifier(EDITORIAL_ROLE),
                "REVOKE TEMPORARY ON DATABASE " + quoteIdentifier(database)
                        + " FROM " + quoteIdentifier(EDITORIAL_ROLE));
        assertPrivilegeDrift(
                "GRANT CREATE ON SCHEMA public TO " + quoteIdentifier(EDITORIAL_ROLE),
                "REVOKE CREATE ON SCHEMA public FROM "
                        + quoteIdentifier(EDITORIAL_ROLE));

        String disabledDatabase = "ordenfix_legal_editorial_disabled_it";
        owner.execute("CREATE DATABASE " + quoteIdentifier(disabledDatabase));
        try {
            owner.execute("ALTER DATABASE " + quoteIdentifier(disabledDatabase)
                    + " OWNER TO " + quoteIdentifier(EDITORIAL_ROLE));
            owner.execute("ALTER DATABASE " + quoteIdentifier(disabledDatabase)
                    + " ALLOW_CONNECTIONS false");
            assertThatThrownBy(verifier::verify)
                    .isInstanceOfSatisfying(
                            LegalEditorialOperationalException.class,
                            failure -> assertThat(failure.issue().code())
                                    .isEqualTo(LegalManifestIssueCode.ROLE_PRIVILEGE_DRIFT));
        } finally {
            owner.execute("ALTER DATABASE " + quoteIdentifier(disabledDatabase)
                    + " OWNER TO " + quoteIdentifier(POSTGRES.getUsername()));
            owner.execute("DROP DATABASE " + quoteIdentifier(disabledDatabase));
        }
        verifier.verify();
    }

    @Test
    void extraRelationColumnSequenceOrGrantOptionFailsClosed() {
        assertPrivilegeDrift(
                "GRANT INSERT ON legal_publicaciones TO "
                        + quoteIdentifier(EDITORIAL_ROLE),
                "REVOKE INSERT ON legal_publicaciones FROM "
                        + quoteIdentifier(EDITORIAL_ROLE));
        assertPrivilegeDrift(
                "GRANT UPDATE (titulo) ON legal_documento_versiones TO "
                        + quoteIdentifier(EDITORIAL_ROLE),
                "REVOKE UPDATE (titulo) ON legal_documento_versiones FROM "
                        + quoteIdentifier(EDITORIAL_ROLE));
        assertPrivilegeDrift(
                "GRANT SELECT ON SEQUENCE legal_documento_transiciones_id_seq TO "
                        + quoteIdentifier(EDITORIAL_ROLE),
                "REVOKE SELECT ON SEQUENCE legal_documento_transiciones_id_seq FROM "
                        + quoteIdentifier(EDITORIAL_ROLE));
        assertPrivilegeDrift(
                "GRANT DELETE ON legal_documento_vigentes TO "
                        + quoteIdentifier(EDITORIAL_ROLE) + " WITH GRANT OPTION",
                "REVOKE GRANT OPTION FOR DELETE ON legal_documento_vigentes FROM "
                        + quoteIdentifier(EDITORIAL_ROLE));
    }

    @Test
    void publicOrDirectExecuteOutsideTheAllowlistFailsClosed() {
        String deniedFunction = "legal_publicacion_insert_guard()";
        assertPrivilegeDrift(
                "GRANT EXECUTE ON FUNCTION " + deniedFunction + " TO "
                        + quoteIdentifier(EDITORIAL_ROLE),
                "REVOKE EXECUTE ON FUNCTION " + deniedFunction + " FROM "
                        + quoteIdentifier(EDITORIAL_ROLE));

        String allowedFunction = "legal_documento_transicion_before_insert()";
        assertPrivilegeDrift(
                "GRANT EXECUTE ON FUNCTION " + allowedFunction + " TO PUBLIC",
                "REVOKE EXECUTE ON FUNCTION " + allowedFunction + " FROM PUBLIC");
    }

    @Test
    void largeObjectOrServerParameterCapabilitiesFailClosed() {
        Long objectId = owner.queryForObject(
                "SELECT pg_catalog.lo_create(0)::bigint",
                Long.class);
        assertThat(objectId).isNotNull();
        try {
            assertPrivilegeDrift(
                    "GRANT SELECT ON LARGE OBJECT " + objectId + " TO "
                            + quoteIdentifier(EDITORIAL_ROLE),
                    "REVOKE SELECT ON LARGE OBJECT " + objectId + " FROM "
                            + quoteIdentifier(EDITORIAL_ROLE));
        } finally {
            owner.queryForObject(
                    "SELECT pg_catalog.lo_unlink(?::oid)",
                    Integer.class,
                    objectId);
        }

        assertPrivilegeDrift(
                "GRANT SET ON PARAMETER session_replication_role TO "
                        + quoteIdentifier(EDITORIAL_ROLE),
                "REVOKE SET ON PARAMETER session_replication_role FROM "
                        + quoteIdentifier(EDITORIAL_ROLE));

        assertPrivilegeDrift(
                "GRANT EXECUTE ON FUNCTION pg_catalog.lo_create(oid) TO PUBLIC",
                "REVOKE EXECUTE ON FUNCTION pg_catalog.lo_create(oid) FROM PUBLIC");

        String database = owner.queryForObject(
                "SELECT pg_catalog.current_database()",
                String.class);
        LegalManifestImportResult imported = importer.importService().importManifest(golden);
        assertThat(imported.status()).isEqualTo(LegalManifestStatus.PASS);
        Map<String, Long> rowsBeforeAttempt = editorialTableCounts(owner);
        Map<String, LegalManifestPersistenceITSupport.SequenceState> sequencesBeforeAttempt =
                editorialSequenceStates(owner);
        owner.execute("ALTER ROLE " + quoteIdentifier(EDITORIAL_ROLE)
                + " IN DATABASE " + quoteIdentifier(database)
                + " SET session_replication_role TO replica");
        try {
            LegalEditorialApplyResult result = applyService.applyPromote(golden);
            assertThat(result.status()).isEqualTo(LegalManifestStatus.ERROR);
            assertThat(result.persisted()).isFalse();
            assertThat(result.outcome()).isEqualTo(LegalEditorialApplyResult.Outcome.ERROR);
            assertThat(result.issues()).singleElement().satisfies(issue ->
                    assertThat(issue.code())
                            .isEqualTo(LegalManifestIssueCode.ROLE_PRIVILEGE_DRIFT));
            assertThat(editorialTableCounts(owner)).isEqualTo(rowsBeforeAttempt);
            assertThat(editorialSequenceStates(owner)).isEqualTo(sequencesBeforeAttempt);
        } finally {
            owner.execute("ALTER ROLE " + quoteIdentifier(EDITORIAL_ROLE)
                    + " IN DATABASE " + quoteIdentifier(database)
                    + " SET session_replication_role TO origin");
        }
        verifier.verify();
        assertPrivilegeDrift(
                "ALTER ROLE " + quoteIdentifier(EDITORIAL_ROLE)
                        + " IN DATABASE " + quoteIdentifier(database)
                        + " SET lo_compat_privileges TO on",
                "ALTER ROLE " + quoteIdentifier(EDITORIAL_ROLE)
                        + " IN DATABASE " + quoteIdentifier(database)
                        + " SET lo_compat_privileges TO off");
    }

    @Test
    void technicalLockColumnsRemainGuardedAndApplicationTablesStayDenied() {
        LegalManifestImportResult imported = importer.importService().importManifest(golden);
        assertThat(imported.status()).isEqualTo(LegalManifestStatus.PASS);

        for (String table : List.of(
                "legal_publicaciones",
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
        assertSqlState(() -> restricted.update("DELETE FROM legal_publicaciones"), "42501");
        assertSqlState(() -> restricted.update(
                "INSERT INTO legal_aceptacion_lotes DEFAULT VALUES"), "42501");
    }

    private static LegalEditorialApplyService applyService(
            JdbcTemplate jdbc,
            LegalEditorialSchemaVerifier schema,
            LegalEditorialPrivilegeVerifier privileges) {
        DataSource dataSource = Objects.requireNonNull(jdbc.getDataSource(), "dataSource");
        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
        manager.setRollbackOnCommitFailure(false);
        TransactionTemplate transaction = new TransactionTemplate(manager);
        transaction.setName("legal-editorial-restricted-role-it");
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(
                LegalDatabaseBudgets.production().transactionTimeoutSeconds());
        transaction.setReadOnly(false);

        LegalRequiredSetRevisionCalculator revisionCalculator =
                new LegalRequiredSetRevisionCalculator();
        LegalManifestOriginGraphVerifier origin =
                new LegalManifestOriginGraphVerifier(jdbc, revisionCalculator);
        LegalEditorialReadinessCore readiness = new LegalEditorialReadinessCore(
                jdbc,
                origin,
                revisionCalculator,
                new LegalEditorialStateFingerprintCalculator());
        LegalEditorialPlannerCore planner = new LegalEditorialPlannerCore(
                jdbc,
                readiness,
                origin);
        LegalManifestDatabaseGate gate = new LegalManifestDatabaseGate(
                transaction,
                jdbc,
                LegalDatabaseBudgets.production(),
                List.of(schema, privileges));
        return new LegalEditorialApplyService(
                gate,
                jdbc,
                planner,
                new LegalInitialPromotionCore(jdbc),
                new LegalEditorialPostStateVerifier(jdbc),
                readiness,
                new LegalEditorialFailureMapper(),
                schema,
                privileges);
    }

    private static void assertPrivilegeDrift(String mutation, String restoration) {
        owner.execute(mutation);
        try {
            assertThatThrownBy(verifier::verify)
                    .isInstanceOfSatisfying(
                            LegalEditorialOperationalException.class,
                            failure -> assertThat(failure.issue().code())
                                    .isEqualTo(LegalManifestIssueCode.ROLE_PRIVILEGE_DRIFT));
        } finally {
            owner.execute(restoration);
        }
        verifier.verify();
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

    private static JdbcTemplate jdbc(String url, String username, String password) {
        return new JdbcTemplate(new DriverManagerDataSource(url, username, password));
    }

    private static String quoteIdentifier(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }
}
