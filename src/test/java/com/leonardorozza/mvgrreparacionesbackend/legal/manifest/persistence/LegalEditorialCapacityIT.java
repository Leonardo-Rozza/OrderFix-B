package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialReadiness;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialPlanLimits;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalEditorialPlanV1;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalEditorialPlanV1.DocumentRef;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalEditorialPlanV1.DocumentReplacementBatch;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalEditorialITFixture.MaximumEditorialFixture;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalEditorialITFixture.ReplaceFixture;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalJdbcMetricsSupport.Category;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalJdbcMetricsSupport.Snapshot;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.ApplyHarness;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.Harness;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.PlannerHarness;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.cleanLegalState;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.directDataSource;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.migrate;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.restrictedApplyHarness;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.restrictedHarness;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.restrictedPlannerHarness;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.withReplicaRole;
import static org.assertj.core.api.Assertions.assertThat;

/** PostgreSQL capacity accreditation for the maximum realizable editorial projection. */
@Testcontainers
class LegalEditorialCapacityIT {

    private static final String IMPORT_ROLE = "ordenfix_legal_import_capacity_editorial_it";
    private static final String IMPORT_PASSWORD =
            "import-capacity-editorial-password-must-not-leak";
    private static final String EDITORIAL_ROLE =
            "ordenfix_legal_editorial_capacity_it";
    private static final String EDITORIAL_PASSWORD =
            "editorial-capacity-password-must-not-leak";
    private static final String OWNER_APPLICATION_NAME =
            "ordenfix-legal-capacity-owner";
    private static final Duration ARTIFICIAL_DELAY = Duration.ofMillis(5);
    private static final Duration MAX_OPERATION = Duration.ofSeconds(70);
    private static final Duration MAX_STATEMENT = Duration.ofSeconds(30);
    private static final int MAXIMUM_EDITORIAL_DOCUMENTS = 87;
    private static final int MAXIMUM_REQUIREMENTS = 256;
    private static final int MAXIMUM_SCOPES = 16;
    private static final int MAXIMUM_DOCUMENT_SLOTS = 88;
    private static final int MAXIMUM_REQUIREMENT_DOCUMENT_REFERENCES = 2_642;
    private static final UUID ONE_TO_ONE_BATCH_ID = UUID.fromString(
            "347eea8f-2737-30e3-b7b5-fd1c71a3cf11");
    private static final UUID SPLIT_BATCH_ID = UUID.fromString(
            "966c7a60-74a1-37c1-8a25-a2150dfe1c31");
    private static final UUID MERGE_BATCH_ID = UUID.fromString(
            "bea4a307-6469-3751-8846-fe56f4368d3f");
    private static final LegalEditorialPlanResult.DeltaCounts EXPECTED_DELTA =
            new LegalEditorialPlanResult.DeltaCounts(
                    7, 8, 18, 83, 83, 5, 5, 16, 16, 3);
    /*
     * This capacity fixture remains target V27. V29 compatibility adds exactly two fixed SELECTs
     * and two returned rows per schema preflight: closed migration history and absence of the V29
     * delta. Each successful readiness/plan/apply operation enters one gate. Graph, DML, sentinel,
     * per-SQL repetition and time budgets are unchanged; explicit assertions below prohibit N+1.
     */
    private static final MetricCaps READINESS_METRICS = new MetricCaps(
            54, 1, 0, 0, 3, 50, 0, 11_638, 2);
    private static final MetricCaps PLAN_METRICS = new MetricCaps(
            98, 1, 0, 0, 3, 94, 0, 36_044, 4);
    private static final MetricCaps APPLY_METRICS = new MetricCaps(
            186, 1, 17, 5, 4, 159, 0, 68_650, 6);
    private static final long MAXIMUM_SENTINEL_ROWS_READ = 11_686;
    private static final String COMPATIBILITY_HISTORY_SQL =
            "SELECT history.version, history.type, history.script, history.checksum, history.success";
    private static final String COMPATIBILITY_ABSENCE_SQL =
            "SELECT EXISTS ( SELECT 1 FROM pg_catalog.pg_class rel JOIN pg_catalog.pg_namespace ns";

    /*
     * Legal graph reads use exact valid/sentinel cardinalities. PostgreSQL catalog reads are a
     * separate snapshot of the controlled PG16/V27 environment: they are inventoried and capped,
     * but are not presented as semantic expected+1 relations.
     */
    private static final List<SqlReadCap> SQL_READ_CAPS = List.of(
            readCap("origin-documents", 87, 88,
                    "SELECT pd.manifest_ordinal", "dl.id AS document_line_id"),
            readCap("origin-document-contexts", 88, 89,
                    "SELECT pd.manifest_ordinal, dc.documento_version_id"),
            readCap("origin-requirements", 256, 257,
                    "SELECT pr.manifest_ordinal", "rl.id AS requirement_line_id"),
            readCap("origin-requirement-audiences", 512, 513,
                    "SELECT pr.manifest_ordinal, ra.requisito_linea_id"),
            readCap("origin-requirement-documents", 2_642, 2_643,
                    "SELECT pr.manifest_ordinal AS requirement_ordinal"),
            readCap("publication-scopes", 16, 17,
                    "SELECT id, locale, contexto, audiencia, required_set_revision",
                    "FROM legal_requisito_conjuntos"),
            readCap("origin-scope-members", 512, 513,
                    "SELECT c.id AS scope_id", "JOIN legal_requisito_conjunto_miembros m"),
            readCap("target-documents", 87, 88,
                    "SELECT pd.manifest_ordinal, dv.id AS version_id"),
            readCap("target-requirements", 256, 257,
                    "SELECT pr.manifest_ordinal, rv.id AS version_id"),
            readCap("active-slots-fingerprint", 88,
                    "estado_documento, count(*) OVER () AS total_count",
                    "FROM legal_documento_vigentes"),
            readCap("active-pointers-fingerprint", 16,
                    "a.actualizado_en, c.required_set_revision, count(*) OVER () AS total_count"),
            readCap("active-members-fingerprint", 512, 515,
                    "SELECT a.locale, a.contexto, a.audiencia, m.conjunto_id",
                    "m.manifest_ordinal, m.requisito_version_id",
                    "count(*) OVER () AS total_count"),
            readCap("active-requirement-documents-fingerprint", 5_284, 5_317,
                    "SELECT a.locale, a.contexto, a.audiencia, m.conjunto_id",
                    "rd.documento_ordinal, rd.documento_version_id",
                    "count(*) OVER () AS total_count"),
            readCap("relevant-document-versions", 92,
                    "WITH RECURSIVE batch_members",
                    "SELECT dv.id, dv.documento_linea_id, dv.publicacion_intro_id, dv.lineage_ordinal",
                    "JOIN relevant ON"),
            readCap("relevant-requirement-versions", 262,
                    "SELECT rv.id, rv.requisito_linea_id, rv.publicacion_intro_id, rv.lineage_ordinal",
                    "count(*) OVER () AS total_count"),
            readCap("relevant-batches", 3,
                    "WITH RECURSIVE batch_members", "SELECT lot.id", "JOIN related_batches"),
            readCap("relevant-batch-predecessors", 4,
                    "WITH RECURSIVE batch_members", "SELECT previous.lote_id"),
            readCap("relevant-batch-successors", 4,
                    "WITH RECURSIVE batch_members", "SELECT successor.lote_id"),
            readCap("publication-document-membership", 87, 90,
                    "SELECT documento_version_id FROM legal_publicacion_documentos"),
            readCap("publication-requirement-membership", 256, 259,
                    "SELECT requisito_version_id FROM legal_publicacion_requisitos"),
            readCap("active-slots", 88,
                    "SELECT tipo, locale, contexto, documento_version_id, documento_linea_id, publicacion_id FROM legal_documento_vigentes"),
            readCap("active-pointers", 16,
                    "SELECT a.locale, a.contexto, a.audiencia, a.conjunto_id",
                    "c.required_set_revision FROM legal_requisito_conjuntos_actuales"),
            readCap("active-member-identities", 512, 515,
                    "SELECT a.conjunto_id, m.requisito_version_id"),
            readCap("active-document-identities", 5_284, 5_317,
                    "SELECT a.conjunto_id, rd.documento_version_id"),
            readCap("document-evidence", 92,
                    "SELECT dv.id, dv.documento_linea_id, dv.publicacion_intro_id, dv.sha256"),
            readCap("document-context-evidence", 94,
                    "SELECT documento_version_id, contexto FROM legal_documento_contextos"),
            readCap("requirement-evidence", 262,
                    "SELECT rv.id, rv.requisito_linea_id, rv.publicacion_intro_id, rv.afirmacion_sha256",
                    "JOIN legal_requisito_lineas rl"),
            readCap("requirement-audience-evidence", 512,
                    "SELECT requisito_linea_id, audiencia FROM legal_requisito_audiencias"),
            readCap("target-scope-evidence", 16,
                    "SELECT id, publicacion_id, locale, contexto, audiencia, required_set_revision",
                    "FROM legal_requisito_conjuntos"),
            readCap("target-member-evidence", 512, 513,
                    "SELECT c.id AS conjunto_id, m.requisito_version_id"),
            readCap("target-document-evidence", 5_284, 5_317,
                    "SELECT c.id AS conjunto_id, rd.documento_version_id"),
            readCap("document-transition-history", 189,
                    "SELECT id, documento_version_id, estado_anterior, estado_nuevo",
                    "WHERE documento_version_id IN"),
            readCap("requirement-transition-history", 530,
                    "SELECT id, requisito_version_id, estado_anterior, estado_nuevo",
                    "WHERE requisito_version_id IN"),
            readCap("related-batch-identities", 3,
                    "SELECT DISTINCT lote_id", "miembros WHERE documento_version_id IN"),
            readCap("batch-headers", 3,
                    "SELECT id, creado_en, sellado_en FROM legal_documento_reemplazo_lotes"),
            readCap("batch-predecessors", 4,
                    "SELECT lote_id, documento_version_id FROM legal_documento_reemplazo_anteriores",
                    "WHERE lote_id IN"),
            readCap("batch-successors", 4,
                    "SELECT lote_id, documento_version_id, publicacion_id FROM legal_documento_reemplazo_sucesoras",
                    "WHERE lote_id IN"),
            readCap("batch-transition-history", 8,
                    "SELECT id, documento_version_id, estado_anterior, estado_nuevo",
                    "WHERE reemplazo_lote_id IN"),
            readCap("locked-publications", 2,
                    "SELECT id, publication_external_id, manifest_sha256, estado_construccion",
                    "WHERE id IN", "FOR UPDATE"),
            readCap("locked-document-lines", 91,
                    "SELECT id FROM legal_documento_lineas", "FOR UPDATE"),
            readCap("locked-requirement-lines", 256,
                    "SELECT id FROM legal_requisito_lineas", "FOR UPDATE"),
            readCap("writer-document-versions", 92,
                    "SELECT id, documento_linea_id AS line_id", "FROM legal_documento_versiones"),
            readCap("writer-requirement-versions", 262,
                    "SELECT id, requisito_linea_id AS line_id", "FROM legal_requisito_versiones"),
            readCap("active-pointer-identities", 16,
                    "SELECT locale, contexto, audiencia, conjunto_id, publicacion_id",
                    "FROM legal_requisito_conjuntos_actuales"),
            readCap("batch-lock-identities", 3,
                    "SELECT id FROM legal_documento_reemplazo_lotes WHERE id IN"),
            readCap("role-membership-summary", 1,
                    "FROM pg_catalog.pg_roles candidate",
                    "FROM pg_catalog.pg_auth_members membership"),
            readCap("parameter-acls", 0,
                    "FROM pg_catalog.pg_parameter_acl acl"),
            readCap("document-transition-count", 1,
                    "SELECT count(*)::integer FROM legal_documento_transiciones",
                    "WHERE documento_version_id IN"),
            readCap("requirement-transition-count", 1,
                    "SELECT count(*)::integer FROM legal_requisito_transiciones",
                    "WHERE requisito_version_id IN"),
            readCap("publication-by-external-id", 2,
                    "FROM legal_publicaciones WHERE publication_external_id = ? LIMIT 2"),
            readCap("publication-by-id", 2,
                    "manifest_canonico", "FROM legal_publicaciones WHERE id = ? LIMIT 2"),
            readCap("catalog-large-objects", 0,
                    "FROM pg_catalog.pg_largeobject_metadata lom"),
            readCap("catalog-namespaces", 1,
                    "pg_catalog.has_schema_privilege", "FROM pg_catalog.pg_namespace n"),
            readCap("database-ownership-summary", 1,
                    "SELECT pg_catalog.count(*) > 0",
                    "FROM pg_catalog.pg_database WHERE datdba = ?::oid"),
            readCap("schema-session-context", 1,
                    "pg_catalog.current_schema() AS current_schema",
                    "pg_catalog.pg_my_temp_schema() AS temp_schema"),
            readCap("large-object-session-settings", 1,
                    "current_setting('session_replication_role')",
                    "current_setting('lo_compat_privileges')"),
            readCap("transaction-isolation-setting", 1,
                    "SELECT pg_catalog.current_setting('transaction_isolation')"),
            readCap("transaction-read-only-setting", 1,
                    "SELECT pg_catalog.current_setting('transaction_read_only')"),
            readCap("current-role-identity", 1,
                    "SESSION_USER AS session_name", "WHERE r.rolname = CURRENT_USER"),
            readCap("statement-timestamp", 1, "SELECT statement_timestamp()"),
            readCap("transaction-timestamp", 1, "SELECT transaction_timestamp()"),
            readCap("flyway-history-version", 1,
                    "SELECT version, type, script, checksum, success",
                    "\"flyway_schema_history\" WHERE version = ?"),
            readCap("compatibility-flyway-history", 1,
                    COMPATIBILITY_HISTORY_SQL,
                    "history.version IN (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    "WHERE first_v27.version = ?"),
            readCap("compatibility-v29-absence", 1,
                    COMPATIBILITY_ABSENCE_SQL,
                    "rel.relname IN ('legal_idempotencia_sin_actos', 'legal_idempotencia_sin_actos_referencias')",
                    "FROM pg_catalog.pg_trigger trg"),
            readCap("catalog-databases", 3, "FROM pg_catalog.pg_database d"),
            readCap("catalog-columns", 388, "pg_catalog.has_column_privilege"),
            readCap("catalog-sequences", 27, "pg_catalog.has_sequence_privilege"),
            readCap("catalog-tables", 43, "pg_catalog.has_table_privilege"),
            readCap("catalog-functions", 47,
                    "pg_catalog.has_function_privilege", "n.nspname <> 'information_schema'"),
            readCap("catalog-large-object-functions", 5,
                    "pg_catalog.has_function_privilege", "n.nspname = 'pg_catalog'"),
            readCap("catalog-function-definitions", 34, "pg_catalog.pg_get_function_result"),
            readCap("schema-columns", 130,
                    "pg_catalog.jsonb_build_array( c.relname, a.attnum"),
            readCap("schema-relations", 19,
                    "pg_catalog.jsonb_build_array( c.relname, c.relkind"),
            readCap("schema-constraints", 130,
                    "pg_catalog.jsonb_build_array( r.relname, c.conname"),
            readCap("schema-triggers", 58,
                    "pg_catalog.jsonb_build_array( r.relname, t.tgname"),
            readCap("schema-identity-sequences", 10,
                    "pg_catalog.jsonb_build_array( seq.relname, tbl.relname"));

    /*
     * These reads do not need their own LIMIT: their input IDs come from an already bounded read,
     * or their key space is closed by V27 uniqueness plus the frozen legal enums. Any other legal
     * multirrow read must retain an explicit LIMIT placeholder.
     */
    private static final Set<String> BOUNDED_WITHOUT_OWN_LIMIT_READS = Set.of(
            "document-context-evidence",
            "requirement-audience-evidence",
            "publication-document-membership",
            "publication-requirement-membership",
            "active-slots",
            "target-scope-evidence",
            "batch-headers",
            "batch-lock-identities",
            "locked-publications",
            "locked-document-lines",
            "locked-requirement-lines",
            "writer-document-versions",
            "writer-requirement-versions",
            "active-pointer-identities");

    /* Exact last numeric bind delivered to PostgreSQL for every parameterized LIMIT. */
    private static final Map<String, Long> SQL_LIMIT_BINDINGS = Map.ofEntries(
            Map.entry("origin-documents", 88L),
            Map.entry("origin-document-contexts", 89L),
            Map.entry("origin-requirements", 257L),
            Map.entry("origin-requirement-audiences", 513L),
            Map.entry("origin-requirement-documents", 2_643L),
            Map.entry("publication-scopes", 17L),
            Map.entry("origin-scope-members", 513L),
            Map.entry("target-documents", 88L),
            Map.entry("target-requirements", 257L),
            Map.entry("active-slots-fingerprint", 89L),
            Map.entry("active-pointers-fingerprint", 17L),
            Map.entry("active-members-fingerprint", 4_097L),
            Map.entry("active-requirement-documents-fingerprint", 65_537L),
            Map.entry("relevant-document-versions", 8_193L),
            Map.entry("relevant-requirement-versions", 16_385L),
            Map.entry("relevant-batches", 8_193L),
            Map.entry("relevant-batch-predecessors", 16_385L),
            Map.entry("relevant-batch-successors", 16_385L),
            Map.entry("publication-document-membership", 1_025L),
            Map.entry("publication-requirement-membership", 2_049L),
            Map.entry("active-slots", 1_025L),
            Map.entry("active-pointers", 65L),
            Map.entry("active-member-identities", 16_385L),
            Map.entry("active-document-identities", 32_769L),
            Map.entry("document-evidence", 1_025L),
            Map.entry("requirement-evidence", 2_049L),
            Map.entry("target-scope-evidence", 65L),
            Map.entry("target-member-evidence", 16_385L),
            Map.entry("target-document-evidence", 32_769L),
            Map.entry("document-transition-history", 8_193L),
            Map.entry("requirement-transition-history", 8_193L),
            Map.entry("related-batch-identities", 513L),
            Map.entry("batch-predecessors", 8_193L),
            Map.entry("batch-successors", 8_193L),
            Map.entry("batch-transition-history", 8_193L));
    private static final Map<String, String> SQL_LITERAL_LIMITS = Map.of(
            "publication-by-external-id", " LIMIT 2",
            "publication-by-id", " LIMIT 2",
            "compatibility-flyway-history", " LIMIT 13");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_editorial_capacity")
            .withUsername("ordenfix")
            .withPassword("ordenfix");

    private static JdbcTemplate observer;
    private static Harness importer;
    private static PlannerHarness setupPlanner;
    private static ApplyHarness setupApply;
    private static PlannerHarness measuredPlanner;
    private static ApplyHarness measuredApply;
    private static LegalJdbcMetricsSupport jdbcMetrics;
    private static LegalRestrictedImportRoleFixture importRoleFixture;
    private static LegalRestrictedEditorialRoleFixture editorialRoleFixture;

    @TempDir
    private Path temporaryDirectory;

    private LegalEditorialITFixture fixture;

    @BeforeAll
    static void migrateProvisionAndAssemble() {
        migrate(POSTGRES);
        DataSource ownerDataSource = directDataSource(POSTGRES, OWNER_APPLICATION_NAME);
        observer = new JdbcTemplate(ownerDataSource);

        importRoleFixture = new LegalRestrictedImportRoleFixture(
                observer,
                POSTGRES.getJdbcUrl(),
                IMPORT_ROLE,
                IMPORT_PASSWORD,
                POSTGRES.getDriverClassName());
        LegalRestrictedImportRoleFixture.Credentials importCredentials =
                importRoleFixture.provisionAndVerify();
        editorialRoleFixture = new LegalRestrictedEditorialRoleFixture(
                observer,
                POSTGRES.getJdbcUrl(),
                EDITORIAL_ROLE,
                EDITORIAL_PASSWORD,
                POSTGRES.getDriverClassName());
        LegalRestrictedEditorialRoleFixture.Credentials editorialCredentials =
                editorialRoleFixture.provisionAndVerify();

        importer = restrictedHarness(
                credentialDataSource(
                        importCredentials.jdbcUrl(),
                        importCredentials.username(),
                        importCredentials.password(),
                        importCredentials.driverClassName()),
                LegalDatabaseBudgets.production(),
                IMPORT_ROLE);
        DataSource setupEditorialDataSource = credentialDataSource(
                editorialCredentials.jdbcUrl(),
                editorialCredentials.username(),
                editorialCredentials.password(),
                editorialCredentials.driverClassName());
        setupPlanner = restrictedPlannerHarness(
                setupEditorialDataSource,
                LegalDatabaseBudgets.production(),
                EDITORIAL_ROLE);
        setupApply = restrictedApplyHarness(
                setupEditorialDataSource,
                LegalDatabaseBudgets.production(),
                EDITORIAL_ROLE);

        DataSource measuredDelegate = credentialDataSource(
                editorialCredentials.jdbcUrl(),
                editorialCredentials.username(),
                editorialCredentials.password(),
                editorialCredentials.driverClassName());
        jdbcMetrics = LegalJdbcMetricsSupport.instrument(measuredDelegate, ARTIFICIAL_DELAY);
        measuredPlanner = restrictedPlannerHarness(
                jdbcMetrics.dataSource(),
                LegalDatabaseBudgets.production(),
                EDITORIAL_ROLE);
        measuredApply = restrictedApplyHarness(
                jdbcMetrics.dataSource(),
                LegalDatabaseBudgets.production(),
                EDITORIAL_ROLE);

        assertThat(observer.queryForObject(
                "SELECT current_setting('server_version_num')::integer / 10000",
                Integer.class)).isEqualTo(16);
        assertThat(LegalDatabaseBudgets.production().transactionTimeoutSeconds())
                .isEqualTo(75);
        assertThat(LegalDatabaseBudgets.production().statementTimeoutSeconds())
                .isEqualTo(30);
    }

    @BeforeEach
    void cleanRecheckAndBuildFixture() {
        cleanLegalState(observer);
        importRoleFixture.verify();
        editorialRoleFixture.verify();
        jdbcMetrics.reset();
        fixture = new LegalEditorialITFixture(
                temporaryDirectory,
                LegalEditorialCapacityIT.class,
                observer,
                importer,
                setupApply);
    }

    @Test
    void maximumCompositeMeasuresReadinessPlanApplyAndBoundedSentinels() throws Exception {
        MaximumEditorialFixture maximum = fixture.maximumEditorialFixture(
                "capacity-editorial-source-v1",
                "capacity-editorial-target-v1",
                "capacity-editorial-replace");
        assertMaximumFixture(maximum);

        LegalEditorialReadinessResult sourceReadiness =
                setupPlanner.readinessService().evaluate(maximum.source().release());
        assertThat(sourceReadiness.readiness()).isEqualTo(LegalEditorialReadiness.READY);
        LegalEditorialPlanResult exactPlan = setupPlanner.plannerService().planReplace(
                maximum.target().release(),
                maximum.replacement().plan());
        assertApplicable(exactPlan, maximum.target().publicationId());

        Measurement<LegalEditorialReadinessResult> readiness = measure(() ->
                measuredPlanner.readinessService().evaluate(maximum.target().release()));
        assertThat(readiness.result().readiness()).isEqualTo(LegalEditorialReadiness.NOT_READY);
        assertThat(readiness.result().status()).isEqualTo(LegalManifestStatus.BLOCKED);
        assertReadOnlyMeasurement("readiness", readiness, READINESS_METRICS);

        Measurement<LegalEditorialPlanResult> plan = measure(() ->
                measuredPlanner.plannerService().planReplace(
                        maximum.target().release(),
                        maximum.replacement().plan()));
        assertApplicable(plan.result(), maximum.target().publicationId());
        assertThat(plan.result().deltaCounts()).contains(EXPECTED_DELTA);
        assertReadOnlyMeasurement("plan", plan, PLAN_METRICS);

        Measurement<LegalEditorialApplyResult> apply = measure(() ->
                measuredApply.service().applyReplace(
                        maximum.target().release(),
                        maximum.replacement().plan()));
        assertApplied(apply.result(), maximum);
        assertMutableMeasurement("apply", apply, APPLY_METRICS);
        assertSqlReadInventory(
                Set.of(),
                readiness.metrics(),
                plan.metrics(),
                apply.metrics());

        assertStructurallyBoundedRelations(maximum);
        assertDocumentRelationSentinel(maximum);
        assertRequirementRelationSentinel(maximum);
        assertScopeMemberRelationSentinel(maximum);
    }

    private static void assertMaximumFixture(MaximumEditorialFixture maximum) {
        assertThat(maximum.source().release().documentCount())
                .isEqualTo(MAXIMUM_EDITORIAL_DOCUMENTS);
        assertThat(maximum.target().release().documentCount())
                .isEqualTo(MAXIMUM_EDITORIAL_DOCUMENTS);
        assertThat(maximum.source().release().requirementCount())
                .isEqualTo(MAXIMUM_REQUIREMENTS);
        assertThat(maximum.target().release().requirementCount())
                .isEqualTo(MAXIMUM_REQUIREMENTS);
        assertThat(maximum.source().release().scopeCount()).isEqualTo(MAXIMUM_SCOPES);
        assertThat(maximum.target().release().scopeCount()).isEqualTo(MAXIMUM_SCOPES);
        assertThat(maximum.documentSlots()).isEqualTo(MAXIMUM_DOCUMENT_SLOTS);
        assertMaximumLocale(maximum);
        assertThat(maximum.source().release().plan().manifest().requirements())
                .extracting(requirement -> requirement.documents().size())
                .contains(11)
                .allSatisfy(references -> assertThat(references).isBetween(1, 11));
        assertThat(maximum.target().release().plan().manifest().requirements())
                .extracting(requirement -> requirement.documents().size())
                .contains(11)
                .allSatisfy(references -> assertThat(references).isBetween(1, 11));
        assertThat(maximum.source().release().plan().manifest().requirements().stream()
                .mapToInt(requirement -> requirement.documents().size())
                .sum()).isEqualTo(MAXIMUM_REQUIREMENT_DOCUMENT_REFERENCES);
        assertThat(maximum.target().release().plan().manifest().requirements().stream()
                .mapToInt(requirement -> requirement.documents().size())
                .sum()).isEqualTo(MAXIMUM_REQUIREMENT_DOCUMENT_REFERENCES);

        LegalEditorialPlanV1 plan = maximum.replacement().plan().plan();
        assertThat(plan.documentReuses()).hasSize(82);
        assertThat(plan.documentAdditions()).hasSize(1);
        assertThat(plan.documentRetirements()).hasSize(1);
        assertThat(plan.documentReplacementBatches())
                .extracting(batch -> batch.predecessors().size()
                        + "->" + batch.successors().size())
                .containsExactlyInAnyOrder("1->1", "1->2", "2->1");
        assertThat(plan.requirementReuses()).hasSize(250);
        assertThat(plan.requirementReplacements()).hasSize(6);
        assertThat(plan.requirementAdditions()).isEmpty();
        assertThat(plan.requirementRetirements()).isEmpty();
        assertThat(maximum.replacement().predecessorDocumentIds()).hasSize(4);
        assertThat(maximum.replacement().successorDocumentIds()).hasSize(4);
        assertThat(maximum.replacement().predecessorRequirementIds()).hasSize(6);
        assertThat(maximum.replacement().successorRequirementIds()).hasSize(6);
        assertThat(maximum.replacement().replacementBatchIds()).hasSize(3);
        assertExactBatches(plan.documentReplacementBatches());
    }

    private static void assertMaximumLocale(MaximumEditorialFixture maximum) {
        assertThat(LocaleLegal.values()).containsExactly(LocaleLegal.ES_AR);
        assertThat(LocaleLegal.ES_AR.getCodigo()).isEqualTo("es-AR");
        for (LegalEditorialITFixture.ImportedRelease release
                : List.of(maximum.source(), maximum.target())) {
            assertThat(release.release().plan().manifest().locale()).isEqualTo(LocaleLegal.ES_AR);
            assertThat(release.release().plan().manifest().documents())
                    .extracting(document -> document.locale())
                    .containsOnly(LocaleLegal.ES_AR);
            assertThat(release.release().plan().scopes())
                    .extracting(scope -> scope.locale())
                    .containsOnly(LocaleLegal.ES_AR);
        }
    }

    private static void assertExactBatches(List<DocumentReplacementBatch> batches) {
        Set<ContextoLegal> firstTwoContexts = Set.of(
                ContextoLegal.REGISTRO,
                ContextoLegal.PRIMER_INGRESO_EMPLEADO);
        assertThat(batches.stream().map(LegalEditorialCapacityIT::batchIdentity).toList())
                .containsExactlyInAnyOrder(
                        new BatchIdentity(
                                ONE_TO_ONE_BATCH_ID,
                                Set.of(ContextoLegal.REGISTRO),
                                Set.of(documentIdentity(
                                        "capacity-replace-one",
                                        "1.0.0",
                                        TipoDocumentoLegal.ACUERDO_TRATAMIENTO_DATOS,
                                        Set.of(ContextoLegal.REGISTRO))),
                                Set.of(documentIdentity(
                                        "capacity-replace-one",
                                        "2.0.0",
                                        TipoDocumentoLegal.ACUERDO_TRATAMIENTO_DATOS,
                                        Set.of(ContextoLegal.REGISTRO)))),
                        new BatchIdentity(
                                SPLIT_BATCH_ID,
                                firstTwoContexts,
                                Set.of(documentIdentity(
                                        "capacity-split-source",
                                        "1.0.0",
                                        TipoDocumentoLegal.TERMINOS_SERVICIO,
                                        firstTwoContexts)),
                                Set.of(
                                        documentIdentity(
                                                "capacity-split-target-a",
                                                "1.0.0",
                                                TipoDocumentoLegal.TERMINOS_SERVICIO,
                                                Set.of(ContextoLegal.REGISTRO)),
                                        documentIdentity(
                                                "capacity-split-target-b",
                                                "1.0.0",
                                                TipoDocumentoLegal.TERMINOS_SERVICIO,
                                                Set.of(ContextoLegal.PRIMER_INGRESO_EMPLEADO)))),
                        new BatchIdentity(
                                MERGE_BATCH_ID,
                                firstTwoContexts,
                                Set.of(
                                        documentIdentity(
                                                "capacity-merge-source-a",
                                                "1.0.0",
                                                TipoDocumentoLegal.POLITICA_PRIVACIDAD,
                                                Set.of(ContextoLegal.REGISTRO)),
                                        documentIdentity(
                                                "capacity-merge-source-b",
                                                "1.0.0",
                                                TipoDocumentoLegal.POLITICA_PRIVACIDAD,
                                                Set.of(ContextoLegal.PRIMER_INGRESO_EMPLEADO))),
                                Set.of(documentIdentity(
                                        "capacity-merge-target",
                                        "1.0.0",
                                        TipoDocumentoLegal.POLITICA_PRIVACIDAD,
                                        firstTwoContexts))));
    }

    private static BatchIdentity batchIdentity(DocumentReplacementBatch batch) {
        return new BatchIdentity(
                batch.replacementBatchId(),
                Set.copyOf(batch.contexts()),
                batch.predecessors().stream()
                        .map(LegalEditorialCapacityIT::persistedDocumentIdentity)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                batch.successors().stream()
                        .map(LegalEditorialCapacityIT::persistedDocumentIdentity)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()));
    }

    private static DocumentIdentity persistedDocumentIdentity(DocumentRef reference) {
        PersistedDocumentBase persisted = observer.queryForObject("""
                SELECT dl.clave, dv.version, dl.tipo, dl.locale, dv.sha256
                  FROM legal_documento_versiones dv
                  JOIN legal_documento_lineas dl ON dl.id = dv.documento_linea_id
                 WHERE dv.id = ?
                """, (resultSet, rowNumber) -> new PersistedDocumentBase(
                resultSet.getString("clave"),
                resultSet.getString("version"),
                resultSet.getString("tipo"),
                resultSet.getString("locale"),
                resultSet.getString("sha256")), reference.documentVersionId());
        assertThat(reference.sha256())
                .as("SHA declarado por el lote para %s", reference.documentVersionId())
                .isEqualTo(persisted.sha256());
        Set<ContextoLegal> contexts = observer.queryForList("""
                        SELECT contexto
                          FROM legal_documento_contextos
                         WHERE documento_version_id = ?
                         ORDER BY contexto
                        """, String.class, reference.documentVersionId()).stream()
                .map(ContextoLegal::valueOf)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        LocaleLegal locale = LocaleLegal.fromCodigo(persisted.locale());
        assertThat(locale).isEqualTo(LocaleLegal.ES_AR);
        return new DocumentIdentity(
                persisted.key(),
                persisted.version(),
                TipoDocumentoLegal.valueOf(persisted.type()),
                locale,
                contexts);
    }

    private static DocumentIdentity documentIdentity(
            String key,
            String version,
            TipoDocumentoLegal type,
            Set<ContextoLegal> contexts) {
        return new DocumentIdentity(key, version, type, LocaleLegal.ES_AR, contexts);
    }

    private static void assertApplicable(
            LegalEditorialPlanResult result,
            UUID targetPublicationId) {
        assertThat(result.status()).isEqualTo(LegalManifestStatus.PASS);
        assertThat(result.persisted()).isFalse();
        assertThat(result.outcome()).isEqualTo(LegalEditorialPlanResult.Outcome.APPLICABLE);
        assertThat(result.targetPublicationUuid()).contains(targetPublicationId);
        assertThat(result.expectedReadinessAfter()).contains(LegalEditorialReadiness.READY);
        assertThat(result.changeRequired()).contains(true);
        assertThat(result.issues()).isEmpty();
        assertThat(result.deltaCounts()).get().satisfies(counts ->
                assertThat(counts).isEqualTo(EXPECTED_DELTA));
    }

    private static void assertApplied(
            LegalEditorialApplyResult result,
            MaximumEditorialFixture maximum) {
        assertThat(result.status()).isEqualTo(LegalManifestStatus.PASS);
        assertThat(result.persisted()).isTrue();
        assertThat(result.outcome()).isEqualTo(LegalEditorialApplyResult.Outcome.APPLIED);
        assertThat(result.operationType())
                .contains(LegalEditorialApplyReceipt.OperationType.REPLACE);
        assertThat(result.targetPublicationUuid()).contains(maximum.target().publicationId());
        assertThat(result.readinessAfter()).contains(LegalEditorialReadiness.READY);
        assertThat(result.issues()).isEmpty();
        assertThat(result.receipt()).get().satisfies(receipt -> {
            assertThat(receipt.documentVersions()).isEqualTo(MAXIMUM_EDITORIAL_DOCUMENTS);
            assertThat(receipt.requirementVersions()).isEqualTo(MAXIMUM_REQUIREMENTS);
            assertThat(receipt.documentSlots()).isEqualTo(MAXIMUM_DOCUMENT_SLOTS);
            assertThat(receipt.requiredSetPointers()).isEqualTo(MAXIMUM_SCOPES);
            assertThat(receipt.replacementBatches()).isEqualTo(3);
        });
    }

    private static <T> Measurement<T> measure(Supplier<T> operation) {
        jdbcMetrics.reset();
        long started = System.nanoTime();
        T result = Objects.requireNonNull(operation.get(), "capacity result");
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
        return new Measurement<>(result, elapsed, jdbcMetrics.snapshot());
    }

    private static void assertReadOnlyMeasurement(
            String operation,
            Measurement<?> measurement,
            MetricCaps expected) {
        assertCommonMeasurement(operation, measurement, expected);
        assertThat(measurement.metrics().executions(Category.DML)).isZero();
        assertThat(measurement.metrics().commits()).isEqualTo(1L);
        assertThat(measurement.metrics().rollbacks()).isZero();
    }

    private static void assertMutableMeasurement(
            String operation,
            Measurement<?> measurement,
            MetricCaps expected) {
        assertCommonMeasurement(operation, measurement, expected);
        assertThat(measurement.metrics().executions(Category.DML)).isPositive();
        assertThat(measurement.metrics().executions(Category.ROW_LOCK)).isPositive();
        assertThat(measurement.metrics().commits()).isEqualTo(1L);
        assertThat(measurement.metrics().rollbacks()).isZero();
    }

    private static void assertCommonMeasurement(
            String operation,
            Measurement<?> measurement,
            MetricCaps expected) {
        Snapshot metrics = measurement.metrics();
        assertConstantCompatibilityPreflight(metrics);
        assertThat(measurement.elapsed())
                .as("%s metrics=%s", operation, concise(metrics))
                .isLessThan(MAX_OPERATION)
                .isGreaterThanOrEqualTo(ARTIFICIAL_DELAY.multipliedBy(metrics.roundTrips()));
        assertThat(metrics.roundTrips()).isLessThanOrEqualTo(expected.roundTrips());
        assertThat(metrics.maximumStatementDuration()).isLessThan(MAX_STATEMENT);
        assertThat(metrics.maximumAdvisoryLockDuration())
                .isGreaterThanOrEqualTo(ARTIFICIAL_DELAY)
                .isLessThan(MAX_STATEMENT);
        assertThat(metrics.executions(Category.ADVISORY_LOCK))
                .isEqualTo(expected.advisoryLocks());
        assertThat(metrics.executions(Category.DML)).isLessThanOrEqualTo(expected.dml());
        assertThat(metrics.executions(Category.ROW_LOCK)).isLessThanOrEqualTo(expected.rowLocks());
        assertThat(metrics.executions(Category.TX_CONTROL)).isEqualTo(expected.txControl());
        assertThat(metrics.executions(Category.SELECT)).isLessThanOrEqualTo(expected.selects());
        assertThat(metrics.executions(Category.OTHER)).isEqualTo(expected.other());
        assertThat(metrics.rowsRead()).isLessThanOrEqualTo(expected.rowsRead());
        assertMaximumSqlExecutions(
                operation,
                metrics,
                expected.maximumSqlExecutions());
        assertThat(metrics.byCategory().values().stream().mapToLong(Long::longValue).sum())
                .isEqualTo(metrics.roundTrips());
        assertThat(metrics.bySql().values())
                .allSatisfy(sql -> assertThat(sql.failures()).isZero());
        System.out.println("LEGAL_EDITORIAL_CAPACITY " + operation
                + " elapsed=" + measurement.elapsed()
                + " " + concise(metrics));
    }

    private static void assertDocumentRelationSentinel(MaximumEditorialFixture maximum) {
        ReplaceFixture replacement = maximum.replacement();
        List<UUID> extras = replacement.predecessorDocumentIds().stream().limit(3).toList();
        assertThat(extras).hasSize(3);
        UUID target = maximum.target().publicationId();
        extras.forEach(extra -> assertThat(observer.queryForObject("""
                        SELECT count(*)::integer
                          FROM legal_publicacion_documentos
                         WHERE publicacion_id = ? AND documento_version_id = ?
                        """, Integer.class, target, extra)).isZero());
        withReplicaRole(observer, () -> {
            for (int index = 0; index < extras.size(); index++) {
                assertThat(observer.update("""
                        INSERT INTO legal_publicacion_documentos
                            (publicacion_id, documento_version_id, manifest_ordinal)
                        VALUES (?, ?, ?)
                        """, target, extras.get(index),
                        MAXIMUM_EDITORIAL_DOCUMENTS + index + 1)).isOne();
            }
        });

        try {
            assertThat(countPublicationRelations("legal_publicacion_documentos", target))
                    .isEqualTo(MAXIMUM_EDITORIAL_DOCUMENTS + extras.size());
            Measurement<LegalEditorialReadinessResult> sentinel = measure(() ->
                    measuredPlanner.readinessService().evaluate(maximum.target().release()));
            assertSentinelBlocked(sentinel.result());
            assertSentinelMeasurement(
                    "documents-sentinel",
                    sentinel,
                    Set.of(
                            "origin-documents",
                            "origin-document-contexts",
                            "target-documents"));
            assertThat(sentinel.metrics().maximumRowsReadContaining(
                    "SELECT pd.manifest_ordinal",
                    "dl.id AS document_line_id"))
                    .isEqualTo(MAXIMUM_EDITORIAL_DOCUMENTS + 1L);
            assertThat(sentinel.metrics().maximumRowsReadContaining(
                    "SELECT pd.manifest_ordinal, dc.documento_version_id",
                    "JOIN legal_documento_contextos dc"))
                    .isEqualTo(MAXIMUM_DOCUMENT_SLOTS + 1L);
        } finally {
            withReplicaRole(observer, () -> extras.forEach(extra ->
                    assertThat(observer.update("""
                            DELETE FROM legal_publicacion_documentos
                             WHERE publicacion_id = ? AND documento_version_id = ?
                            """, target, extra)).isOne()));
        }
    }

    private static void assertRequirementRelationSentinel(MaximumEditorialFixture maximum) {
        ReplaceFixture replacement = maximum.replacement();
        List<UUID> extras = replacement.predecessorRequirementIds().stream().limit(3).toList();
        assertThat(extras).hasSize(3);
        UUID target = maximum.target().publicationId();
        extras.forEach(extra -> assertThat(observer.queryForObject("""
                        SELECT count(*)::integer
                          FROM legal_publicacion_requisitos
                         WHERE publicacion_id = ? AND requisito_version_id = ?
                        """, Integer.class, target, extra)).isZero());
        withReplicaRole(observer, () -> {
            for (int index = 0; index < extras.size(); index++) {
                assertThat(observer.update("""
                        INSERT INTO legal_publicacion_requisitos
                            (publicacion_id, requisito_version_id, manifest_ordinal)
                        VALUES (?, ?, ?)
                        """, target, extras.get(index),
                        MAXIMUM_REQUIREMENTS + index + 1)).isOne();
            }
        });

        try {
            assertThat(countPublicationRelations("legal_publicacion_requisitos", target))
                    .isEqualTo(MAXIMUM_REQUIREMENTS + extras.size());
            Measurement<LegalEditorialReadinessResult> sentinel = measure(() ->
                    measuredPlanner.readinessService().evaluate(maximum.target().release()));
            assertSentinelBlocked(sentinel.result());
            assertSentinelMeasurement(
                    "requirements-sentinel",
                    sentinel,
                    Set.of(
                            "origin-requirements",
                            "origin-requirement-audiences",
                            "origin-requirement-documents",
                            "target-requirements"));
            assertThat(sentinel.metrics().maximumRowsReadContaining(
                    "SELECT pr.manifest_ordinal",
                    "rl.id AS requirement_line_id"))
                    .isEqualTo(MAXIMUM_REQUIREMENTS + 1L);
            assertThat(sentinel.metrics().maximumRowsReadContaining(
                    "SELECT pr.manifest_ordinal, ra.requisito_linea_id"))
                    .isEqualTo(MAXIMUM_REQUIREMENTS * 2L + 1L);
            assertThat(sentinel.metrics().maximumRowsReadContaining(
                    "SELECT pr.manifest_ordinal AS requirement_ordinal"))
                    .isEqualTo(MAXIMUM_REQUIREMENT_DOCUMENT_REFERENCES + 1L);
        } finally {
            withReplicaRole(observer, () -> extras.forEach(extra ->
                    assertThat(observer.update("""
                            DELETE FROM legal_publicacion_requisitos
                             WHERE publicacion_id = ? AND requisito_version_id = ?
                            """, target, extra)).isOne()));
        }
    }

    private static void assertScopeMemberRelationSentinel(MaximumEditorialFixture maximum) {
        UUID target = maximum.target().publicationId();
        UUID scope = observer.queryForObject("""
                SELECT id
                  FROM legal_requisito_conjuntos
                 WHERE publicacion_id = ?
                   AND locale = 'es-AR'
                   AND contexto = 'REGISTRO'
                   AND audiencia = 'ADMIN_TITULAR'
                """, UUID.class, target);
        List<ScopeMemberCorruption> extras = observer.query("""
                SELECT pr.requisito_version_id, rv.requisito_linea_id,
                       pr.manifest_ordinal
                  FROM legal_publicacion_requisitos pr
                  JOIN legal_requisito_versiones rv
                    ON rv.id = pr.requisito_version_id
                  JOIN legal_requisito_lineas rl
                    ON rl.id = rv.requisito_linea_id
                 WHERE pr.publicacion_id = ?
                   AND rl.contexto = 'USO_CONTINUADO'
                 ORDER BY pr.manifest_ordinal
                 LIMIT 3
                """, (resultSet, rowNumber) -> new ScopeMemberCorruption(
                -10_001L - rowNumber,
                resultSet.getObject("requisito_version_id", UUID.class),
                resultSet.getObject("requisito_linea_id", UUID.class),
                resultSet.getInt("manifest_ordinal")), target);
        assertThat(extras).hasSize(3);
        withReplicaRole(observer, () -> extras.forEach(extra ->
                assertThat(observer.update("""
                        INSERT INTO legal_requisito_conjunto_miembros
                            (id, conjunto_id, publicacion_id, requisito_version_id,
                             requisito_linea_id, manifest_ordinal)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """, extra.id(), scope, target, extra.requirementVersionId(),
                        extra.requirementLineId(), extra.manifestOrdinal())).isOne()));

        try {
            assertThat(observer.queryForObject("""
                    SELECT count(*)::integer
                      FROM legal_requisito_conjunto_miembros
                     WHERE publicacion_id = ?
                    """, Integer.class, target)).isEqualTo(515);
            Measurement<LegalEditorialReadinessResult> sentinel = measure(() ->
                    measuredPlanner.readinessService().evaluate(maximum.target().release()));
            assertSentinelBlocked(sentinel.result());
            assertSentinelMeasurement(
                    "scope-members-sentinel",
                    sentinel,
                    Set.of(
                            "origin-scope-members",
                            "active-members-fingerprint",
                            "active-requirement-documents-fingerprint"));
            assertThat(sentinel.metrics().maximumRowsReadContaining(
                    "SELECT c.id AS scope_id",
                    "JOIN legal_requisito_conjunto_miembros m"))
                    .isEqualTo(MAXIMUM_REQUIREMENTS * 2L + 1L);
        } finally {
            withReplicaRole(observer, () -> extras.forEach(extra ->
                    assertThat(observer.update("""
                            DELETE FROM legal_requisito_conjunto_miembros WHERE id = ?
                            """, extra.id())).isOne()));
        }
    }

    private static void assertStructurallyBoundedRelations(
            MaximumEditorialFixture maximum) {
        UUID target = maximum.target().publicationId();
        assertThat(ContextoLegal.values()).hasSize(8);
        assertThat(TipoDocumentoLegal.values()).hasSize(11);
        assertThat(AudienciaLegal.values()).hasSize(2);
        assertThat(maximum.target().release().plan().manifest().requirements())
                .allSatisfy(requirement -> assertThat(requirement.roles()).hasSize(2));
        assertThat(observer.queryForObject("""
                SELECT count(*)::integer
                  FROM legal_requisito_conjuntos
                 WHERE publicacion_id = ?
                """, Integer.class, target)).isEqualTo(MAXIMUM_SCOPES);
        assertThat(observer.queryForObject(
                "SELECT count(*)::integer FROM legal_documento_vigentes",
                Integer.class)).isEqualTo(MAXIMUM_DOCUMENT_SLOTS);
        assertThat(observer.queryForObject(
                "SELECT count(*)::integer FROM legal_requisito_conjuntos_actuales",
                Integer.class)).isEqualTo(MAXIMUM_SCOPES);
        assertThat(LegalEditorialPlanLimits.MAX_REPLACEMENT_BATCHES).isEqualTo(128);
        assertThat(LegalEditorialPlannerCore.MAX_RELEVANT_BATCHES).isEqualTo(512);
        assertThat(LegalEditorialPlannerCore.MAX_RELEVANT_BATCH_MEMBERS).isEqualTo(8_192);
        assertThat(LegalEditorialReadinessCore.MAX_REPLACEMENT_BATCHES).isEqualTo(8_192);
    }

    private static int countPublicationRelations(String table, UUID publicationId) {
        if (!Set.of("legal_publicacion_documentos", "legal_publicacion_requisitos")
                .contains(table)) {
            throw new IllegalArgumentException("Relación sentinel no permitida: " + table);
        }
        return Objects.requireNonNull(observer.queryForObject(
                "SELECT count(*)::integer FROM " + table + " WHERE publicacion_id = ?",
                Integer.class,
                publicationId));
    }

    private static void assertSentinelMeasurement(
            String operation,
            Measurement<?> sentinel,
            Set<String> relaxedFamilies) {
        Snapshot metrics = sentinel.metrics();
        assertConstantCompatibilityPreflight(metrics);
        assertThat(sentinel.elapsed())
                .as("%s metrics=%s", operation, concise(metrics))
                .isLessThan(MAX_OPERATION)
                .isGreaterThanOrEqualTo(ARTIFICIAL_DELAY.multipliedBy(metrics.roundTrips()));
        assertThat(metrics.roundTrips()).isLessThanOrEqualTo(READINESS_METRICS.roundTrips());
        assertThat(metrics.executions(Category.DML)).isZero();
        assertThat(metrics.executions(Category.ADVISORY_LOCK)).isEqualTo(1L);
        assertThat(metrics.executions(Category.ROW_LOCK)).isZero();
        assertThat(metrics.executions(Category.TX_CONTROL)).isEqualTo(3L);
        assertThat(metrics.executions(Category.SELECT))
                .isLessThanOrEqualTo(READINESS_METRICS.selects());
        assertThat(metrics.executions(Category.OTHER)).isZero();
        assertThat(metrics.rowsRead()).isLessThanOrEqualTo(MAXIMUM_SENTINEL_ROWS_READ);
        assertThat(metrics.byCategory().values().stream().mapToLong(Long::longValue).sum())
                .isEqualTo(metrics.roundTrips());
        assertMaximumSqlExecutions(
                operation,
                metrics,
                READINESS_METRICS.maximumSqlExecutions());
        assertThat(metrics.commits()).isEqualTo(1L);
        assertThat(metrics.rollbacks()).isZero();
        assertThat(metrics.maximumStatementDuration()).isLessThan(MAX_STATEMENT);
        assertThat(metrics.maximumAdvisoryLockDuration())
                .isGreaterThanOrEqualTo(ARTIFICIAL_DELAY)
                .isLessThan(MAX_STATEMENT);
        assertThat(metrics.bySql().values())
                .allSatisfy(sql -> assertThat(sql.failures()).isZero());
        assertSqlReadInventory(relaxedFamilies, metrics);
        System.out.println("LEGAL_EDITORIAL_CAPACITY " + operation
                + " elapsed=" + sentinel.elapsed() + " " + concise(metrics));
    }

    private static void assertConstantCompatibilityPreflight(Snapshot metrics) {
        for (String sql : List.of(COMPATIBILITY_HISTORY_SQL, COMPATIBILITY_ABSENCE_SQL)) {
            assertThat(metrics.matching(sql)).as("one fixed compatibility query: %s", sql).hasSize(1);
            assertThat(metrics.executionsContaining(sql)).isEqualTo(1L);
            assertThat(metrics.rowsReadContaining(sql)).isEqualTo(1L);
            assertThat(metrics.maximumRowsReadContaining(sql)).isEqualTo(1L);
        }
    }

    private static void assertSqlReadInventory(
            Set<String> relaxedFamilies,
            Snapshot... snapshots) {
        Set<String> configuredFamilies = SQL_READ_CAPS.stream()
                .map(SqlReadCap::name)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        assertThat(configuredFamilies).hasSameSizeAs(SQL_READ_CAPS);
        assertThat(configuredFamilies).containsAll(relaxedFamilies);
        Set<String> observedFamilies = new LinkedHashSet<>();
        Set<String> observedParameterizedLimitFamilies = new LinkedHashSet<>();
        Set<String> observedLiteralLimitFamilies = new LinkedHashSet<>();
        Set<String> exercisedRelaxedFamilies = new LinkedHashSet<>();
        List<String> failures = new ArrayList<>();
        for (Snapshot snapshot : snapshots) {
            for (LegalJdbcMetricsSupport.SqlSnapshot sql : snapshot.bySql().values()) {
                if (sql.category() != Category.SELECT && sql.category() != Category.ROW_LOCK) {
                    continue;
                }
                List<SqlReadCap> matches = SQL_READ_CAPS.stream()
                        .filter(cap -> cap.matches(sql.sql()))
                        .toList();
                if (matches.size() != 1) {
                    failures.add("matches=" + matches.stream().map(SqlReadCap::name).toList()
                            + " rows=" + sql.maximumRowsRead() + " sql=" + sql.sql());
                    continue;
                }
                SqlReadCap cap = matches.getFirst();
                String upperSql = sql.sql().toUpperCase(java.util.Locale.ROOT);
                Long expectedLimit = SQL_LIMIT_BINDINGS.get(cap.name());
                String expectedLiteral = SQL_LITERAL_LIMITS.get(cap.name());
                boolean canonicalParameterizedLimit = upperSql.endsWith(" LIMIT ?");
                if (canonicalParameterizedLimit) {
                    observedParameterizedLimitFamilies.add(cap.name());
                    if (expectedLimit == null
                            || sql.executionsWithTerminalNumericBinding() != sql.executions()
                            || !sql.terminalNumericBindings().equals(Set.of(expectedLimit))) {
                        failures.add("binding LIMIT familia=" + cap.name()
                                + " expected=" + expectedLimit
                                + " ejecuciones=" + sql.executions()
                                + " ejecucionesConBind="
                                + sql.executionsWithTerminalNumericBinding()
                                + " valores=" + sql.terminalNumericBindings()
                                + " sql=" + sql.sql());
                    }
                } else if (upperSql.contains(" LIMIT ")) {
                    observedLiteralLimitFamilies.add(cap.name());
                    if (expectedLiteral == null || !upperSql.endsWith(expectedLiteral)) {
                        failures.add("LIMIT literal familia=" + cap.name()
                                + " expected=" + expectedLiteral + " sql=" + sql.sql());
                    }
                } else {
                    if (expectedLiteral != null) {
                        failures.add("falta LIMIT literal familia=" + cap.name()
                                + " expected=" + expectedLiteral + " sql=" + sql.sql());
                    }
                    if (expectedLimit != null
                            && !BOUNDED_WITHOUT_OWN_LIMIT_READS.contains(cap.name())) {
                        failures.add("falta LIMIT ? familia=" + cap.name()
                                + " expectedBind=" + expectedLimit + " sql=" + sql.sql());
                    }
                }
                if (cap.maximumRows() > 1L
                        && (upperSql.contains(" FROM LEGAL_")
                                || upperSql.contains(" JOIN LEGAL_"))
                        && !upperSql.contains(" LIMIT ")
                        && !BOUNDED_WITHOUT_OWN_LIMIT_READS.contains(cap.name())) {
                    failures.add("lectura legal sin LIMIT ni cota estructural familia="
                            + cap.name() + " rows=" + sql.maximumRowsRead()
                            + " sql=" + sql.sql());
                }
                long maximum = relaxedFamilies.contains(cap.name())
                        ? cap.sentinelMaximumRows()
                        : cap.maximumRows();
                if (sql.maximumRowsRead() > maximum) {
                    failures.add("cap=" + cap.name() + " expected<=" + maximum
                            + " actual=" + sql.maximumRowsRead() + " sql=" + sql.sql());
                }
                if (relaxedFamilies.contains(cap.name())
                        && sql.maximumRowsRead() > cap.maximumRows()) {
                    exercisedRelaxedFamilies.add(cap.name());
                }
                observedFamilies.add(cap.name());
            }
        }
        assertThat(failures)
                .as("inventario cerrado de lecturas SQL normalizadas")
                .isEmpty();
        assertThat(observedFamilies).containsAll(relaxedFamilies);
        assertThat(exercisedRelaxedFamilies).containsAll(relaxedFamilies);
        if (relaxedFamilies.isEmpty()) {
            assertThat(observedParameterizedLimitFamilies)
                    .containsAll(SQL_LIMIT_BINDINGS.keySet());
            assertThat(observedLiteralLimitFamilies).containsAll(SQL_LITERAL_LIMITS.keySet());
            assertThat(observedFamilies).contains(
                    "origin-documents",
                    "origin-document-contexts",
                    "origin-requirements",
                    "origin-requirement-audiences",
                    "origin-requirement-documents",
                    "publication-scopes",
                    "origin-scope-members",
                    "target-documents",
                    "target-requirements",
                    "active-slots-fingerprint",
                    "active-pointers-fingerprint",
                    "active-members-fingerprint",
                    "active-requirement-documents-fingerprint",
                    "relevant-document-versions",
                    "relevant-requirement-versions",
                    "publication-document-membership",
                    "publication-requirement-membership",
                    "document-transition-history",
                    "requirement-transition-history",
                    "catalog-columns",
                    "schema-constraints",
                    "compatibility-flyway-history",
                    "compatibility-v29-absence");
        }
    }

    private static SqlReadCap readCap(
            String name,
            long maximumRows,
            String... fragments) {
        return readCap(name, maximumRows, maximumRows, fragments);
    }

    private static SqlReadCap readCap(
            String name,
            long maximumRows,
            long sentinelMaximumRows,
            String... fragments) {
        return new SqlReadCap(
                name,
                maximumRows,
                sentinelMaximumRows,
                List.of(fragments));
    }

    private static void assertSentinelBlocked(LegalEditorialReadinessResult result) {
        assertThat(result.readiness()).isEqualTo(LegalEditorialReadiness.NOT_READY);
        assertThat(result.status()).isEqualTo(LegalManifestStatus.BLOCKED);
        assertThat(result.issues())
                .extracting(issue -> issue.code())
                .contains(LegalManifestIssueCode.PUBLICATION_CONTENT_MISMATCH);
    }

    private static String concise(Snapshot metrics) {
        return "roundTrips=" + metrics.roundTrips()
                + " categories=" + metrics.byCategory()
                + " rows=" + metrics.rowsRead()
                + " maxSqlExecutions=" + metrics.bySql().values().stream()
                        .mapToLong(LegalJdbcMetricsSupport.SqlSnapshot::executions)
                        .max()
                        .orElse(0L)
                + " commits=" + metrics.commits()
                + " rollbacks=" + metrics.rollbacks()
                + " maxStatement=" + metrics.maximumStatementDuration()
                + " maxAdvisory=" + metrics.maximumAdvisoryLockDuration();
    }

    private static List<String> topSqlExecutions(Snapshot metrics) {
        return metrics.bySql().values().stream()
                .sorted(java.util.Comparator
                        .comparingLong(LegalJdbcMetricsSupport.SqlSnapshot::executions)
                        .reversed()
                        .thenComparing(LegalJdbcMetricsSupport.SqlSnapshot::sql))
                .limit(5)
                .map(sql -> sql.category() + "=" + sql.executions() + " " + sql.sql())
                .toList();
    }

    private static void assertMaximumSqlExecutions(
            String operation,
            Snapshot metrics,
            long maximum) {
        assertThat(metrics.bySql().values().stream()
                .mapToLong(LegalJdbcMetricsSupport.SqlSnapshot::executions)
                .max()
                .orElse(0L))
                .as("%s máximo de ejecuciones por SQL normalizado; top=%s",
                        operation, topSqlExecutions(metrics))
                .isLessThanOrEqualTo(maximum);
    }

    private static DataSource credentialDataSource(
            String jdbcUrl,
            String username,
            String password,
            String driverClassName) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(jdbcUrl);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        dataSource.setDriverClassName(driverClassName);
        return dataSource;
    }

    private record PersistedDocumentBase(
            String key,
            String version,
            String type,
            String locale,
            String sha256) { }

    private record ScopeMemberCorruption(
            long id,
            UUID requirementVersionId,
            UUID requirementLineId,
            int manifestOrdinal) { }

    private record DocumentIdentity(
            String key,
            String version,
            TipoDocumentoLegal type,
            LocaleLegal locale,
            Set<ContextoLegal> contexts) {

        private DocumentIdentity {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(locale, "locale");
            contexts = Set.copyOf(Objects.requireNonNull(contexts, "contexts"));
        }
    }

    private record BatchIdentity(
            UUID id,
            Set<ContextoLegal> contexts,
            Set<DocumentIdentity> predecessors,
            Set<DocumentIdentity> successors) {

        private BatchIdentity {
            Objects.requireNonNull(id, "id");
            contexts = Set.copyOf(Objects.requireNonNull(contexts, "contexts"));
            predecessors = Set.copyOf(Objects.requireNonNull(predecessors, "predecessors"));
            successors = Set.copyOf(Objects.requireNonNull(successors, "successors"));
        }
    }

    private record Measurement<T>(T result, Duration elapsed, Snapshot metrics) {

        private Measurement {
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(elapsed, "elapsed");
            Objects.requireNonNull(metrics, "metrics");
        }
    }

    private record MetricCaps(
            long roundTrips,
            long advisoryLocks,
            long dml,
            long rowLocks,
            long txControl,
            long selects,
            long other,
            long rowsRead,
            long maximumSqlExecutions) { }

    private record SqlReadCap(
            String name,
            long maximumRows,
            long sentinelMaximumRows,
            List<String> fragments) {

        private SqlReadCap {
            Objects.requireNonNull(name, "name");
            fragments = List.copyOf(Objects.requireNonNull(fragments, "fragments"));
            if (name.isBlank()
                    || maximumRows < 0L
                    || sentinelMaximumRows < maximumRows
                    || fragments.isEmpty()) {
                throw new IllegalArgumentException("Cap SQL editorial inválido: " + name);
            }
        }

        private boolean matches(String sql) {
            return fragments.stream().allMatch(sql::contains);
        }
    }
}
