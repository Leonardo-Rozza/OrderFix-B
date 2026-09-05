package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalApplicableScopeResolver;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalApplicabilityPolicy;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PerfilAgregadoLegal;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

/** Bounded aggregate work on the production composition and a real restricted PostgreSQL role. */
class LegalRequiredSetAggregateCapacityIT {

    private static final String ROLE = "ordenfix_legal_aggregate_capacity_it";
    private static final String PASSWORD = "legal-aggregate-capacity-test-only";
    private static final String POINTER_SQL = "WITH requested(contexto, scope_ordinal)";
    private static final String INSERT_HEADER_SQL = "INSERT INTO legal_requisito_agregados";
    private static final String INSERT_MEMBERS_SQL = "INSERT INTO legal_requisito_agregado_scopes";
    private static final String SELECT_HEADERS_SQL = "FROM legal_requisito_agregados";
    private static final String SELECT_MEMBERS_SQL = "FROM legal_requisito_agregado_scopes";
    private static final List<ContextoLegal> ALL_CONTEXTS = List.of(ContextoLegal.values());
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("ordenfix_legal_aggregate_capacity")
                    .withUsername("ordenfix")
                    .withPassword("ordenfix");

    @TempDir
    private static Path temporaryDirectory;

    private static DataSource ownerDataSource;
    private static DataSource restrictedDataSource;
    private static JdbcTemplate owner;

    @BeforeAll
    static void migrateAndProvisionRestrictedRole() {
        POSTGRES.start();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load().migrate();
        ownerDataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        owner = new JdbcTemplate(ownerDataSource);
        restrictedDataSource = LegalV28AggregateITSupport.dataSource(
                new LegalRestrictedAggregateRoleFixture(
                        owner, POSTGRES.getJdbcUrl(), ROLE, PASSWORD,
                        POSTGRES.getDriverClassName()).provisionAndVerify());
    }

    @BeforeEach
    void seedAllEightAccreditableContexts() throws Exception {
        assertThat(ALL_CONTEXTS).hasSize(8);
        // V28 headers have no FK to the publication: include them explicitly in fixture cleanup.
        owner.execute("""
                TRUNCATE TABLE legal_requisito_agregados, legal_publicaciones,
                               legal_documento_reemplazo_lotes
                RESTART IDENTITY CASCADE
                """);
        UUID publicationId = LegalV28AggregateITSupport.importRelease(ownerDataSource,
                LegalManifestPersistenceITSupport.copyRelease(
                        temporaryDirectory, LegalRequiredSetAggregateCapacityIT.class,
                        "aggregate-capacity-" + UUID.randomUUID(), (path, manifest) -> {
                            manifest.withArray("documents").forEach(document -> {
                                ObjectNode entry = (ObjectNode) document;
                                entry.put("effectiveAt", "2020-01-01T00:00:00-03:00");
                                if (entry.path("key").asText().equals("terminos")) {
                                    entry.putArray("contexts");
                                    ALL_CONTEXTS.forEach(value ->
                                            entry.withArray("contexts").add(value.name()));
                                }
                            });
                            for (ContextoLegal legalContext : ALL_CONTEXTS) {
                                ObjectNode requirement = manifest.withArray("requirements").addObject();
                                requirement.put("key", "capacity-" + legalContext.name().toLowerCase(
                                        java.util.Locale.ROOT));
                                requirement.put("version", "1.0.0");
                                requirement.put("context", legalContext.name());
                                // REGISTRO remains ADMIN-only and mandatory, as required by V27.
                                requirement.putArray("roles").add(AudienciaLegal.ADMIN_TITULAR.name());
                                requirement.put("actType", "ACEPTACION");
                                String statement = "Confirmo el requisito de capacidad para "
                                        + legalContext.name() + ".";
                                requirement.put("statement", statement);
                                requirement.put("statementSha256", HexFormat.of().formatHex(
                                        MessageDigest.getInstance("SHA-256")
                                                .digest(statement.getBytes(StandardCharsets.UTF_8))));
                                requirement.putArray("documents").add("terminos");
                                requirement.put("required", true);
                                requirement.put("requiresReacceptance", true);
                            }
                        }));
        LegalManifestPersistenceITSupport.promoteToReady(owner, publicationId);
        assertThat(owner.queryForObject("""
                SELECT count(*) FROM legal_requisito_conjuntos_actuales
                 WHERE locale = 'es-AR' AND audiencia = 'ADMIN_TITULAR'
                """, Integer.class)).isEqualTo(8);
    }

    @AfterAll
    static void stopContainer() {
        POSTGRES.stop();
    }

    @Test
    void oneAndEightScopesUseConstantStatementsOnePointerReadAndOneMemberBatch() {
        Measurement single = measureCreatedAndReused(1);
        Measurement maximum = measureCreatedAndReused(8);

        assertConstantWork(single.created(), maximum.created(), 14);
        assertConstantWork(single.reused(), maximum.reused(), 14);
        assertThat(maximum.receipt().provenance().scopes())
                .extracting(scope -> scope.context()).containsExactlyElementsOf(ALL_CONTEXTS);
        assertThat(owner.queryForObject("SELECT count(*) FROM legal_requisito_agregados", Long.class))
                .isEqualTo(2);
        assertThat(owner.queryForObject("SELECT count(*) FROM legal_requisito_agregado_scopes", Long.class))
                .isEqualTo(9);
    }

    @Test
    void ninthReturnedPointerFailsBeforeDmlAndAnExplicitRetryCanCreateTheCompleteAggregate() {
        try (RuntimeFixture runtime = runtime(8)) {
            AtomicBoolean injectNinth = new AtomicBoolean(true);
            AtomicInteger rowsPresentedToStore = new AtomicInteger();
            JdbcTemplate delegate = new JdbcTemplate(runtime.metrics().dataSource());
            doAnswer(invocation -> {
                Object[] arguments = invocation.getRawArguments();
                String sql = (String) arguments[0];
                @SuppressWarnings("unchecked")
                RowMapper<Object> mapper = (RowMapper<Object>) arguments[1];
                List<Object> rows = delegate.query(sql, mapper, (Object[]) arguments[2]);
                if (LegalJdbcMetricsSupport.normalizeSql(sql).startsWith(POINTER_SQL)
                        && injectNinth.get()) {
                    assertThat(rows).hasSize(8);
                    ArrayList<Object> sentinel = new ArrayList<>(rows);
                    sentinel.add(rows.getFirst());
                    rowsPresentedToStore.set(sentinel.size());
                    return sentinel;
                }
                return rows;
            }).when(runtime.jdbc()).query(anyString(), any(RowMapper.class), any(Object[].class));

            assertThatThrownBy(runtime::materialize)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no contiene todos los scopes acreditados");

            LegalJdbcMetricsSupport.Snapshot failed = runtime.metrics().snapshot();
            assertThat(rowsPresentedToStore).hasValue(9);
            assertPointerRead(failed, 8);
            assertThat(failed.executions(LegalJdbcMetricsSupport.Category.DML)).isZero();
            assertThat(failed.executionsContaining(SELECT_HEADERS_SQL)).isZero();
            assertThat(failed.commits()).isZero();
            assertThat(failed.rollbacks()).isEqualTo(1);
            assertThat(runtime.batchRows()).isEmpty();
            assertThat(owner.queryForObject("SELECT count(*) FROM legal_requisito_agregados", Long.class))
                    .isZero();
            assertThat(owner.queryForObject("SELECT count(*) FROM legal_requisito_agregado_scopes", Long.class))
                    .isZero();

            // No ninth ContextoLegal exists and V27 uniqueness prevents nine valid pointers.
            // This is a JDBC-result fault, not persisted corruption or a weakened SQL guard.
            // One failed pointer query proves there is no internal automatic retry.
            injectNinth.set(false);
            runtime.metrics().reset();
            assertThat(runtime.materialize().outcome())
                    .isEqualTo(LegalRequiredSetAggregateReceipt.Outcome.CREATED);
            assertOperationBudget(runtime.metrics().snapshot(), 8, true);
            assertThat(runtime.batchRows()).containsExactly(8);
        }
    }

    @Test
    void oversizedServerPolicyFailsBeforeAnyDatabaseWork() {
        try (RuntimeFixture runtime = runtime(9)) {
            assertThatThrownBy(runtime::materialize)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("entre 1 y 8 valores");
            assertThat(runtime.metrics().snapshot().statementExecutions()).isZero();
            assertThat(runtime.metrics().snapshot().commits()).isZero();
            assertThat(runtime.metrics().snapshot().rollbacks()).isZero();
            assertThat(runtime.batchRows()).isEmpty();
        }
    }

    private Measurement measureCreatedAndReused(int scopeCount) {
        try (RuntimeFixture runtime = runtime(scopeCount)) {
            LegalRequiredSetAggregateReceipt created = runtime.materialize();
            LegalJdbcMetricsSupport.Snapshot first = runtime.metrics().snapshot();
            assertThat(created.outcome()).isEqualTo(LegalRequiredSetAggregateReceipt.Outcome.CREATED);
            assertThat(created.provenance().scopes()).hasSize(scopeCount);
            assertOperationBudget(first, scopeCount, true);
            assertThat(runtime.batchRows()).containsExactly(scopeCount);
            assertThat(owner.queryForObject("""
                    SELECT count(*) FROM legal_requisito_agregado_scopes WHERE agregado_id = ?
                    """, Long.class, created.aggregateId())).isEqualTo(scopeCount);
            assertThat(owner.queryForList("""
                    SELECT scope_ordinal FROM legal_requisito_agregado_scopes
                     WHERE agregado_id = ? ORDER BY scope_ordinal
                    """, Integer.class, created.aggregateId()))
                    .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, scopeCount)
                            .boxed().toList());

            runtime.metrics().reset();
            runtime.batchRows().clear();
            LegalRequiredSetAggregateReceipt reused = runtime.materialize();
            LegalJdbcMetricsSupport.Snapshot second = runtime.metrics().snapshot();
            assertThat(reused).isEqualTo(new LegalRequiredSetAggregateReceipt(
                    LegalRequiredSetAggregateReceipt.Outcome.REUSED,
                    created.aggregateId(), created.requiredSetRevision(),
                    created.provenanceFingerprint(), created.provenance(), created.createdAt()));
            assertOperationBudget(second, scopeCount, false);
            assertThat(runtime.batchRows()).isEmpty();
            assertThat(first.statementExecutions() - second.statementExecutions()).isEqualTo(2);
            assertThat(first.rowsRead()).isEqualTo(second.rowsRead());
            System.out.printf("AGGREGATE_CAPACITY scopes=%d createdStatements=%d reusedStatements=%d "
                            + "createdRows=%d reusedRows=%d%n", scopeCount,
                    first.statementExecutions(), second.statementExecutions(),
                    first.rowsRead(), second.rowsRead());
            return new Measurement(created, first, second);
        }
    }

    private static void assertOperationBudget(
            LegalJdbcMetricsSupport.Snapshot snapshot, int scopes, boolean created) {
        assertPointerRead(snapshot, scopes);
        assertThat(snapshot.executionsContaining("pg_advisory_xact_lock_shared("))
                .isEqualTo(1);
        assertThat(snapshot.executionsContaining("pg_advisory_xact_lock("))
                .isZero();
        assertThat(snapshot.executions(LegalJdbcMetricsSupport.Category.ADVISORY_LOCK)).isEqualTo(1);
        assertThat(snapshot.advisoryLockExecutions(LegalJdbcMetricsSupport.AdvisoryLockMode.SHARED))
                .isEqualTo(1);
        assertThat(snapshot.advisoryLockExecutions(LegalJdbcMetricsSupport.AdvisoryLockMode.EXCLUSIVE))
                .isZero();
        assertThat(snapshot.executions(LegalJdbcMetricsSupport.Category.ROW_LOCK)).isEqualTo(1);
        assertThat(snapshot.executions(LegalJdbcMetricsSupport.Category.DML)).isEqualTo(created ? 2 : 0);
        assertThat(snapshot.executionsContaining(INSERT_HEADER_SQL)).isEqualTo(created ? 1 : 0);
        assertThat(snapshot.executionsContaining(INSERT_MEMBERS_SQL)).isEqualTo(created ? 1 : 0);
        assertThat(snapshot.executionsContaining(SELECT_HEADERS_SQL)).isEqualTo(2);
        assertThat(snapshot.rowsReadContaining(SELECT_HEADERS_SQL)).isEqualTo(created ? 1 : 2);
        assertThat(snapshot.executionsContaining(SELECT_MEMBERS_SQL)).isEqualTo(1);
        assertThat(snapshot.rowsReadContaining(SELECT_MEMBERS_SQL)).isEqualTo(scopes);
        assertThat(snapshot.matching(SELECT_MEMBERS_SQL).keySet()).allSatisfy(sql ->
                assertThat(sql).endsWith("ORDER BY scope_ordinal, contexto LIMIT 9"));
        assertThat(snapshot.executionsContaining("FROM legal_aceptacion")).isZero();
        assertThat(snapshot.commits()).isEqualTo(1);
        assertThat(snapshot.rollbacks()).isZero();
        assertThat(snapshot.bySql().values()).allSatisfy(sql -> assertThat(sql.failures()).isZero());

        // Six logical JDBC graph executions when created; four when reused, independently of N.
        // Trigger-internal SQL is not counted as a client/driver round trip by this instrument.
        assertThat(snapshot.bySql().values().stream().filter(sql -> isAggregateGraphSql(sql.sql()))
                .mapToLong(LegalJdbcMetricsSupport.SqlSnapshot::executions).sum())
                .isEqualTo(created ? 6 : 4);
        assertThat(snapshot.bySql().values().stream().filter(sql -> isAggregateGraphSql(sql.sql()))
                .mapToLong(LegalJdbcMetricsSupport.SqlSnapshot::rowsRead).sum())
                .isEqualTo(2L * scopes + 2);
    }

    private static void assertPointerRead(LegalJdbcMetricsSupport.Snapshot snapshot, int rows) {
        assertThat(snapshot.matching(POINTER_SQL)).hasSize(1);
        assertThat(snapshot.executionsContaining(POINTER_SQL)).isEqualTo(1);
        assertThat(snapshot.rowsReadContaining(POINTER_SQL)).isEqualTo(rows);
        assertThat(snapshot.maximumRowsReadContaining(POINTER_SQL)).isEqualTo(rows);
        assertThat(snapshot.matching(POINTER_SQL).keySet()).allSatisfy(sql -> assertThat(sql)
                .contains("JOIN legal_requisito_conjuntos_actuales actual")
                .endsWith("ORDER BY requested.scope_ordinal LIMIT 9 FOR SHARE OF actual"));
    }

    private static void assertConstantWork(
            LegalJdbcMetricsSupport.Snapshot single,
            LegalJdbcMetricsSupport.Snapshot maximum,
            long expectedAdditionalRows) {
        assertThat(maximum.statementExecutions()).isEqualTo(single.statementExecutions());
        assertThat(maximum.roundTrips()).isEqualTo(single.roundTrips());
        assertThat(maximum.byCategory()).isEqualTo(single.byCategory());
        assertThat(maximum.rowsRead() - single.rowsRead()).isEqualTo(expectedAdditionalRows);
        assertThat(nonAggregateMetrics(maximum)).isEqualTo(nonAggregateMetrics(single));
    }

    private static Map<String, List<Long>> nonAggregateMetrics(LegalJdbcMetricsSupport.Snapshot snapshot) {
        // Preflight catalogs are compared separately: their cardinalities do not scale with scopes.
        return snapshot.bySql().values().stream().filter(sql -> !isAggregateGraphSql(sql.sql()))
                .collect(Collectors.toMap(LegalJdbcMetricsSupport.SqlSnapshot::sql,
                        sql -> List.of(sql.executions(), sql.rowsRead(), sql.maximumRowsRead())));
    }

    private static boolean isAggregateGraphSql(String sql) {
        return sql.startsWith(POINTER_SQL) || sql.startsWith(INSERT_HEADER_SQL)
                || sql.startsWith(INSERT_MEMBERS_SQL) || sql.contains(SELECT_HEADERS_SQL)
                || sql.contains(SELECT_MEMBERS_SQL);
    }

    private static RuntimeFixture runtime(int scopeCount) {
        LegalJdbcMetricsSupport metrics = LegalJdbcMetricsSupport.instrument(
                restrictedDataSource, Duration.ZERO);
        JdbcTemplate jdbc = spy(new JdbcTemplate(metrics.dataSource()));
        List<Integer> batchRows = new ArrayList<>();
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            List<Object[]> rows = invocation.getArgument(1);
            assertThat(LegalJdbcMetricsSupport.normalizeSql(sql)).startsWith(INSERT_MEMBERS_SQL);
            assertThat(rows).allSatisfy(bindings -> assertThat(bindings).hasSize(8));
            batchRows.add(rows.size());
            return invocation.callRealMethod();
        }).when(jdbc).batchUpdate(anyString(), anyList());

        // Only the server policy is a fixture. Resolver, opaque scope value, gate, store and replay
        // are production code, and the service is obtained from the production Spring composition.
        LegalApplicabilityPolicy policy = (profile, audience) -> {
            if (profile != PerfilAgregadoLegal.AUTHENTICATED_PENDING
                    || audience != AudienciaLegal.ADMIN_TITULAR) {
                throw new IllegalArgumentException("Unsupported capacity fixture profile/audience");
            }
            if (scopeCount == 1) {
                return List.of(ContextoLegal.USO_CONTINUADO);
            }
            ArrayList<ContextoLegal> contexts = new ArrayList<>(ALL_CONTEXTS);
            if (scopeCount == 9) {
                contexts.add(ContextoLegal.USO_CONTINUADO);
            }
            return contexts;
        };
        LegalApplicableScopeResolver resolver = new LegalApplicableScopeResolver(policy);
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                "legal-aggregate-capacity-it", Map.of(
                        LegalRequiredSetAggregateDatabaseConfiguration.ENABLED_PROPERTY, "true",
                        "spring.datasource.username", ROLE)));
        context.registerBean(DataSource.class, metrics::dataSource);
        context.registerBean(JdbcTemplate.class, () -> jdbc);
        context.registerBean("capacityApplicableScopeResolver", LegalApplicableScopeResolver.class,
                () -> resolver, definition -> definition.setPrimary(true));
        context.register(LegalRequiredSetAggregateDatabaseConfiguration.class);
        context.refresh();
        assertThat(context.getBean(LegalApplicableScopeResolver.class)).isSameAs(resolver);
        assertThat(context.getBeansOfType(LegalDatabaseBoundaryMarker.class).values())
                .extracting(LegalDatabaseBoundaryMarker::kind)
                .containsExactly(LegalDatabaseBoundaryMarker.Kind.AGGREGATE);
        assertThat(context.getBean(DataSource.class)).isSameAs(metrics.dataSource());
        metrics.reset();
        return new RuntimeFixture(context, metrics, jdbc,
                context.getBean(LegalRequiredSetAggregateService.class), batchRows);
    }

    private record RuntimeFixture(
            AnnotationConfigApplicationContext context,
            LegalJdbcMetricsSupport metrics,
            JdbcTemplate jdbc,
            LegalRequiredSetAggregateService service,
            List<Integer> batchRows) implements AutoCloseable {

        LegalRequiredSetAggregateReceipt materialize() {
            return service.materialize(PerfilAgregadoLegal.AUTHENTICATED_PENDING,
                    LocaleLegal.ES_AR, AudienciaLegal.ADMIN_TITULAR);
        }

        @Override
        public void close() {
            context.close();
        }
    }

    private record Measurement(
            LegalRequiredSetAggregateReceipt receipt,
            LegalJdbcMetricsSupport.Snapshot created,
            LegalJdbcMetricsSupport.Snapshot reused) { }
}
