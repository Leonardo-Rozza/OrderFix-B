package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PerfilAgregadoLegal;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.List;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

/** Exercises the internal entry point from the explicit production AGGREGATE context. */
class LegalRequiredSetAggregateServiceIT {

    private static final String ROLE = "ordenfix_legal_aggregate_service_it";
    private static final String PASSWORD = "legal-aggregate-service-test-only";
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("ordenfix_legal_aggregate_service")
                    .withUsername("ordenfix")
                    .withPassword("ordenfix");

    @TempDir
    private static Path temporaryDirectory;

    private static DataSource ownerDataSource;
    private static DataSource restrictedDataSource;
    private static JdbcTemplate owner;

    private AnnotationConfigApplicationContext context;
    private LegalJdbcMetricsSupport metrics;
    private JdbcTemplate jdbc;
    private LegalRequiredSetAggregateService service;
    private final AtomicReference<TransactionObservation> observed = new AtomicReference<>();

    @BeforeAll
    static void migrateAndProvisionRestrictedRole() {
        POSTGRES.start();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        ownerDataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        owner = new JdbcTemplate(ownerDataSource);
        restrictedDataSource = LegalV28AggregateITSupport.dataSource(
                new LegalRestrictedAggregateRoleFixture(
                        owner, POSTGRES.getJdbcUrl(), ROLE, PASSWORD,
                        POSTGRES.getDriverClassName()).provisionAndVerify());
    }

    @BeforeEach
    void seedAndLoadProductionComposition() throws Exception {
        owner.execute("""
                TRUNCATE TABLE legal_requisito_agregados, legal_publicaciones,
                               legal_documento_reemplazo_lotes
                RESTART IDENTITY CASCADE
                """);
        UUID publicationId = importEquivalentRelease();
        LegalManifestPersistenceITSupport.promoteToReady(owner, publicationId);
        metrics = LegalJdbcMetricsSupport.instrument(restrictedDataSource, Duration.ZERO);
        jdbc = spy(new JdbcTemplate(metrics.dataSource()));
        context = context(metrics.dataSource(), jdbc, ROLE);
        service = context.getBean(LegalRequiredSetAggregateService.class);
        decorateQueries(Fault.NONE, null);
        metrics.reset();
    }

    @AfterEach
    void closeContext() {
        if (context != null) {
            context.close();
        }
    }

    @AfterAll
    static void stopContainer() {
        POSTGRES.stop();
    }

    @Test
    void productionContextCreatesAndReusesWithNoDmlInOneRestrictedAggregateBoundary() {
        assertThat(context.getBeansOfType(LegalDatabaseBoundaryMarker.class).values())
                .extracting(LegalDatabaseBoundaryMarker::kind)
                .containsExactly(LegalDatabaseBoundaryMarker.Kind.AGGREGATE);
        assertThat(context.getBeansOfType(DataSource.class)).hasSize(1);
        assertThat(context.getBean(DataSource.class)).isSameAs(metrics.dataSource());
        assertThat(context.getBean(DataSource.class)).isNotSameAs(ownerDataSource);
        assertThat(context.getBean(DataSourceTransactionManager.class).getDataSource())
                .isSameAs(metrics.dataSource());
        assertThat(context.getBean(JdbcTemplate.class)).isSameAs(jdbc);
        TransactionTemplate transaction = context.getBean(TransactionTemplate.class);
        assertThat(transaction.getPropagationBehavior())
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThat(transaction.getIsolationLevel())
                .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);

        LegalRequiredSetAggregateReceipt created = materialize();
        LegalJdbcMetricsSupport.Snapshot first = metrics.snapshot();
        assertThat(created.outcome()).isEqualTo(LegalRequiredSetAggregateReceipt.Outcome.CREATED);
        assertThat(first.executions(LegalJdbcMetricsSupport.Category.DML)).isPositive();
        assertThat(first.commits()).isEqualTo(1);
        assertThat(first.rollbacks()).isZero();
        assertThat(first.executionsContaining("pg_advisory_xact_lock_shared("))
                .isEqualTo(1);
        assertThat(first.executionsContaining("FROM legal_requisito_agregado_scopes"))
                .isPositive();
        assertThat(observed.get().role()).isEqualTo(ROLE + ":" + ROLE);
        assertThat(observed.get().isolation()).isEqualTo("read committed");
        assertThat(observed.get().readOnly()).isEqualTo("off");
        assertThat(observed.get().active()).isTrue();

        metrics.reset();
        LegalRequiredSetAggregateReceipt reused = materialize();
        LegalJdbcMetricsSupport.Snapshot second = metrics.snapshot();
        assertThat(reused).isEqualTo(new LegalRequiredSetAggregateReceipt(
                LegalRequiredSetAggregateReceipt.Outcome.REUSED,
                created.aggregateId(), created.requiredSetRevision(),
                created.provenanceFingerprint(), created.provenance(), created.createdAt()));
        assertThat(second.executions(LegalJdbcMetricsSupport.Category.DML)).isZero();
        assertThat(second.commits()).isEqualTo(1);
        assertThat(second.rollbacks()).isZero();
        assertThat(second.executionsContaining("FROM legal_requisito_agregado_scopes"))
                .isPositive();
        assertCounts(1, 1);
    }

    @Test
    void requiresNewSuspendsTheOuterConnectionAndSurvivesItsRollback() {
        DataSourceTransactionManager manager = context.getBean(DataSourceTransactionManager.class);
        TransactionTemplate outer = new TransactionTemplate(manager);
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        AtomicReference<LegalRequiredSetAggregateReceipt> receipt = new AtomicReference<>();

        outer.executeWithoutResult(status -> {
            Integer outerPid = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
            receipt.set(materialize());
            assertThat(observed.get().pid()).isNotEqualTo(outerPid);
            assertThat(observed.get().isolation()).isEqualTo("read committed");
            assertThat(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class))
                    .isEqualTo(outerPid);
            assertThat(jdbc.queryForObject("SHOW transaction_isolation", String.class))
                    .isEqualTo("serializable");
            status.setRollbackOnly();
        });

        assertThat(receipt.get().outcome())
                .isEqualTo(LegalRequiredSetAggregateReceipt.Outcome.CREATED);
        assertCounts(1, 1);
        assertThat(metrics.snapshot().commits()).isEqualTo(1);
        assertThat(metrics.snapshot().rollbacks()).isEqualTo(1);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    @Test
    void equivalentPhysicalSnapshotCreatesNewProvenanceWhilePreservingTheToken() throws Exception {
        LegalRequiredSetAggregateReceipt previous = materialize();
        UUID publicationId = importEquivalentRelease();
        TransactionTemplate editorial = new TransactionTemplate(
                new DataSourceTransactionManager(ownerDataSource));
        editorial.executeWithoutResult(status -> {
            owner.queryForList("""
                    SELECT pg_catalog.pg_advisory_xact_lock(
                        pg_catalog.hashtextextended(?, 0))
                    """, LegalManifestDatabaseGate.EDITORIAL_LOCK_NAME);
            owner.update("DELETE FROM legal_requisito_conjuntos_actuales");
            owner.update("DELETE FROM legal_documento_vigentes");
            owner.update("""
                    INSERT INTO legal_documento_vigentes
                        (tipo, locale, contexto, documento_version_id,
                         documento_linea_id, publicacion_id, estado_documento)
                    SELECT linea.tipo, linea.locale, contexto.contexto, version.id,
                           version.documento_linea_id, publicacion.publicacion_id, 'VIGENTE'
                      FROM legal_publicacion_documentos publicacion
                      JOIN legal_documento_versiones version
                        ON version.id = publicacion.documento_version_id
                      JOIN legal_documento_lineas linea
                        ON linea.id = version.documento_linea_id
                      JOIN legal_documento_contextos contexto
                        ON contexto.documento_version_id = version.id
                     WHERE publicacion.publicacion_id = ?
                     ORDER BY linea.tipo, linea.locale, contexto.contexto
                    """, publicationId);
            assertThat(owner.update("""
                    INSERT INTO legal_requisito_conjuntos_actuales
                        (locale, contexto, audiencia, conjunto_id, publicacion_id, actualizado_en)
                    SELECT locale, contexto, audiencia, id, publicacion_id, statement_timestamp()
                      FROM legal_requisito_conjuntos
                     WHERE publicacion_id = ?
                    """, publicationId)).isPositive();
            owner.execute("SET CONSTRAINTS ALL IMMEDIATE");
        });

        LegalRequiredSetAggregateReceipt current = materialize();
        assertThat(current.outcome()).isEqualTo(LegalRequiredSetAggregateReceipt.Outcome.CREATED);
        assertThat(current.aggregateId()).isNotEqualTo(previous.aggregateId());
        assertThat(current.requiredSetRevision()).isEqualTo(previous.requiredSetRevision());
        assertThat(current.provenanceFingerprint()).isNotEqualTo(previous.provenanceFingerprint());
        assertThat(current.provenance().scopes()).allSatisfy(scope ->
                assertThat(scope.publicationId()).isEqualTo(publicationId));
        assertThat(previous.provenance().scopes()).noneMatch(scope ->
                scope.publicationId().equals(publicationId));
        assertCounts(2, 2);
    }

    @ParameterizedTest
    @EnumSource(value = Fault.class, names = {
            "MISSING_SNAPSHOT", "INVALID_SNAPSHOT_DIGEST", "INCOMPLETE_REPLAY",
            "REPLAY_DIGEST", "REPLAY_MEMBERSHIP"
    })
    void malformedSnapshotOrReplayFailsClosedAndRollsBack(Fault fault) {
        decorateQueries(fault, null);

        assertThatThrownBy(this::materialize).isInstanceOf(IllegalStateException.class);

        LegalJdbcMetricsSupport.Snapshot failed = metrics.snapshot();
        assertThat(failed.commits()).isZero();
        assertThat(failed.rollbacks()).isEqualTo(1);
        if (fault == Fault.MISSING_SNAPSHOT || fault == Fault.INVALID_SNAPSHOT_DIGEST) {
            assertThat(failed.executions(LegalJdbcMetricsSupport.Category.DML)).isZero();
        } else {
            assertThat(failed.executions(LegalJdbcMetricsSupport.Category.DML)).isPositive();
        }
        assertCounts(0, 0);
    }

    @Test
    void stalePhysicalSnapshotIsRejectedByPostgresAndRollsBack() throws Exception {
        UUID stalePublication = importEquivalentRelease();
        LegalV28AggregateITSupport.Pointer stale = LegalV28AggregateITSupport.publicationPointer(
                owner, stalePublication, ContextoLegal.USO_CONTINUADO, AudienciaLegal.USER);
        decorateQueries(Fault.STALE_SNAPSHOT, stale);

        Throwable failure = catchThrowable(this::materialize);

        LegalV28AggregateITSupport.assertSqlState(failure, "23514");
        assertThat(metrics.snapshot().commits()).isZero();
        assertThat(metrics.snapshot().rollbacks()).isEqualTo(1);
        assertThat(metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.DML))
                .isPositive();
        assertCounts(0, 0);
    }

    @Test
    void physicalIdCollisionRollsBackWithoutReusingAnUnrelatedAggregate() {
        LegalRequiredSetAggregateReceipt existing = materialize();
        decorateQueries(Fault.ID_COLLISION, existing.aggregateId());
        metrics.reset();

        Throwable failure = catchThrowable(() -> service.materialize(
                PerfilAgregadoLegal.AUTHENTICATED_PENDING,
                LocaleLegal.ES_AR, AudienciaLegal.ADMIN_TITULAR));

        LegalV28AggregateITSupport.assertSqlState(failure, "23505");
        assertThat(metrics.snapshot().commits()).isZero();
        assertThat(metrics.snapshot().rollbacks()).isEqualTo(1);
        assertCounts(1, 1);
    }

    @Test
    void productionPreflightRejectsOwnerCredentialsBeforeLockOrDml() {
        LegalJdbcMetricsSupport unsafe = LegalJdbcMetricsSupport.instrument(
                ownerDataSource, Duration.ZERO);
        try (AnnotationConfigApplicationContext ownerContext = context(
                unsafe.dataSource(), new JdbcTemplate(unsafe.dataSource()), POSTGRES.getUsername())) {
            LegalRequiredSetAggregateService unsafeService =
                    ownerContext.getBean(LegalRequiredSetAggregateService.class);
            unsafe.reset();

            assertThatThrownBy(() -> unsafeService.materialize(
                    PerfilAgregadoLegal.AUTHENTICATED_PENDING,
                    LocaleLegal.ES_AR, AudienciaLegal.USER)).isInstanceOf(RuntimeException.class);

            assertThat(unsafe.snapshot().executionsContaining("pg_advisory_xact_lock_shared("))
                    .isZero();
            assertThat(unsafe.snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isZero();
            assertThat(unsafe.snapshot().commits()).isZero();
            assertThat(unsafe.snapshot().rollbacks()).isEqualTo(1);
            assertCounts(0, 0);
        }
    }

    @Test
    void consumerSimulationKeepsTheTokenAcrossEvidenceChangesAndAnEmptyPendingList() {
        LegalV28AggregateITSupport.Pointer pointer = LegalV28AggregateITSupport.currentPointer(
                owner, ContextoLegal.USO_CONTINUADO, AudienciaLegal.USER);
        List<UUID> completeRequirements = owner.queryForList("""
                SELECT requisito_version_id
                  FROM legal_requisito_conjunto_miembros
                 WHERE conjunto_id = ?
                 ORDER BY manifest_ordinal
                """, UUID.class, pointer.requiredSetId());
        assertThat(completeRequirements).hasSize(2);
        UUID first = completeRequirements.get(0);
        UUID second = completeRequirements.get(1);
        // Consumer-only fixtures model additions, metadata changes, full coverage and removals.
        // They are not acceptance writes or a production pending-requirements endpoint.
        List<Map<UUID, String>> evidenceStates = List.of(
                Map.of(),
                Map.of(first, "proof-1"),
                Map.of(first, "proof-1-amended"),
                Map.of(first, "proof-1-amended", second, "proof-2"),
                Map.of(second, "proof-2"),
                Map.of());
        String stableToken = null;
        for (int index = 0; index < evidenceStates.size(); index++) {
            Map<UUID, String> evidence = evidenceStates.get(index);
            LegalRequiredSetAggregateReceipt receipt = materialize();
            PendingConsumerSimulation view = new PendingConsumerSimulation(
                    receipt.requiredSetRevision(), completeRequirements.stream()
                            .filter(requirement -> !evidence.containsKey(requirement)).toList());
            if (stableToken == null) {
                stableToken = view.requiredSetRevision();
            }
            assertThat(view.requiredSetRevision()).isNotBlank().isEqualTo(stableToken);
            assertThat(view.requisitos()).hasSize(completeRequirements.size() - evidence.size());
            if (index == 3) {
                assertThat(view.requisitos()).isEmpty();
            }
            if (index > 0) {
                assertThat(receipt.outcome()).isEqualTo(LegalRequiredSetAggregateReceipt.Outcome.REUSED);
            }
        }
        assertThat(metrics.snapshot().executionsContaining("FROM legal_aceptacion"))
                .isZero();
        assertCounts(1, 1);
    }

    @Test
    void configuredRuntimeCannotEditV27CreateDdlOrBypassGuards() {
        for (String forbidden : List.of(
                "UPDATE legal_requisito_conjuntos SET required_set_revision = required_set_revision WHERE false",
                "CREATE TABLE public.forbidden_aggregate_service_it (id integer)",
                "ALTER TABLE legal_requisito_agregados DISABLE TRIGGER USER",
                "SET session_replication_role = replica",
                "CREATE TABLE pg_catalog.forbidden_aggregate_service_it (id integer)")) {
            LegalV28AggregateITSupport.assertSqlState(
                    catchThrowable(() -> jdbc.execute(forbidden)), "42501");
        }
    }

    private LegalRequiredSetAggregateReceipt materialize() {
        return service.materialize(PerfilAgregadoLegal.AUTHENTICATED_PENDING,
                LocaleLegal.ES_AR, AudienciaLegal.USER);
    }

    private static UUID importEquivalentRelease() throws Exception {
        return LegalV28AggregateITSupport.importRelease(ownerDataSource,
                LegalManifestPersistenceITSupport.copyRelease(
                        temporaryDirectory, LegalRequiredSetAggregateServiceIT.class,
                        "aggregate-service-" + UUID.randomUUID(), (path, manifest) -> {
                            manifest.withArray("documents").forEach(document ->
                                    ((ObjectNode) document).put("effectiveAt", "2020-01-01T00:00:00-03:00"));
                            for (int index = 1; index <= 2; index++) {
                                ObjectNode requirement = manifest.withArray("requirements").addObject();
                                requirement.put("key", "continued-use-service-" + index);
                                requirement.put("version", "1.0.0");
                                requirement.put("context", ContextoLegal.USO_CONTINUADO.name());
                                requirement.putArray("roles")
                                        .add(AudienciaLegal.ADMIN_TITULAR.name()).add(AudienciaLegal.USER.name());
                                requirement.put("actType", "ACEPTACION");
                                String statement = "Confirmo el requisito de uso continuado " + index + ".";
                                requirement.put("statement", statement);
                                requirement.put("statementSha256", HexFormat.of().formatHex(
                                        MessageDigest.getInstance("SHA-256")
                                                .digest(statement.getBytes(StandardCharsets.UTF_8))));
                                requirement.putArray("documents").add("terminos");
                                requirement.put("required", true);
                                requirement.put("requiresReacceptance", true);
                            }
                        }));
    }

    private static AnnotationConfigApplicationContext context(
            DataSource dataSource, JdbcTemplate jdbcTemplate, String expectedRole) {
        AnnotationConfigApplicationContext result = new AnnotationConfigApplicationContext();
        result.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                "legal-aggregate-service-it", Map.of(
                        LegalRequiredSetAggregateDatabaseConfiguration.ENABLED_PROPERTY, "true",
                        "spring.datasource.username", expectedRole)));
        result.registerBean(DataSource.class, () -> dataSource);
        result.registerBean(JdbcTemplate.class, () -> jdbcTemplate);
        result.register(LegalRequiredSetAggregateDatabaseConfiguration.class);
        result.refresh();
        return result;
    }

    private static void assertCounts(long headers, long scopes) {
        assertThat(owner.queryForObject("SELECT count(*) FROM legal_requisito_agregados", Long.class))
                .isEqualTo(headers);
        assertThat(owner.queryForObject("SELECT count(*) FROM legal_requisito_agregado_scopes", Long.class))
                .isEqualTo(scopes);
    }

    /**
     * Only the selected query's returned values/binding are corrupted. The SQL still reaches
     * PostgreSQL through the same transaction-bound datasource; gate, store, replay, constraints
     * and commit/rollback all remain production code. No schema or persisted fixture is corrupted.
     */
    private void decorateQueries(Fault fault, Object payload) {
        JdbcTemplate delegate = new JdbcTemplate(metrics.dataSource());
        doAnswer(invocation -> {
            Object[] arguments = invocation.getRawArguments();
            String sql = (String) arguments[0];
            @SuppressWarnings("unchecked")
            RowMapper<Object> mapper = (RowMapper<Object>) arguments[1];
            Object[] bindings = (Object[]) arguments[2];
            String normalized = LegalJdbcMetricsSupport.normalizeSql(sql);
            boolean snapshot = normalized.startsWith("WITH requested(contexto, scope_ordinal)");
            boolean members = normalized.equals(LegalJdbcMetricsSupport.normalizeSql(
                    LegalRequiredSetAggregateReplayVerifier.MEMBERS_SQL));
            if (snapshot) {
                observed.set(delegate.queryForObject("""
                        SELECT current_user || ':' || session_user AS role,
                               current_setting('transaction_isolation') AS isolation,
                               current_setting('transaction_read_only') AS read_only,
                               pg_backend_pid() AS pid
                        """, (rows, row) -> new TransactionObservation(
                        rows.getString("role"), rows.getString("isolation"),
                        rows.getString("read_only"), rows.getInt("pid"),
                        TransactionSynchronizationManager.isActualTransactionActive())));
            }
            if (fault == Fault.ID_COLLISION
                    && normalized.startsWith("INSERT INTO legal_requisito_agregados")) {
                bindings = bindings.clone();
                bindings[0] = payload;
            }
            RowMapper<Object> selected = mapper;
            if ((snapshot && (fault == Fault.INVALID_SNAPSHOT_DIGEST || fault == Fault.STALE_SNAPSHOT))
                    || (members && (fault == Fault.REPLAY_DIGEST || fault == Fault.REPLAY_MEMBERSHIP))) {
                selected = (rows, rowNumber) -> mapper.mapRow(
                        corruptRow(rows, fault, payload), rowNumber);
            }
            List<Object> rows = delegate.query(sql, selected, bindings);
            if ((snapshot && fault == Fault.MISSING_SNAPSHOT)
                    || (members && fault == Fault.INCOMPLETE_REPLAY)) {
                return List.of();
            }
            return rows;
        }).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
    }

    private static ResultSet corruptRow(ResultSet row, Fault fault, Object payload) {
        return (ResultSet) Proxy.newProxyInstance(
                LegalRequiredSetAggregateServiceIT.class.getClassLoader(),
                new Class<?>[]{ResultSet.class}, (proxy, method, arguments) -> {
                    String column = arguments != null && arguments.length > 0
                            && arguments[0] instanceof String value ? value : "";
                    if (method.getName().equals("getString") && column.equals("required_set_revision")) {
                        if (fault == Fault.INVALID_SNAPSHOT_DIGEST) {
                            return "sha256:not-a-digest";
                        }
                        if (fault == Fault.REPLAY_DIGEST) {
                            return LegalV28AggregateITSupport.digest('0');
                        }
                    }
                    if (method.getName().equals("getObject")) {
                        if (fault == Fault.REPLAY_MEMBERSHIP && column.equals("agregado_id")) {
                            return UUID.randomUUID();
                        }
                        if (fault == Fault.STALE_SNAPSHOT) {
                            LegalV28AggregateITSupport.Pointer stale =
                                    (LegalV28AggregateITSupport.Pointer) payload;
                            if (column.equals("conjunto_id")) {
                                return stale.requiredSetId();
                            }
                            if (column.equals("publicacion_id")) {
                                return stale.publicationId();
                            }
                        }
                    }
                    try {
                        return method.invoke(row, arguments);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    private enum Fault {
        NONE, MISSING_SNAPSHOT, INVALID_SNAPSHOT_DIGEST, INCOMPLETE_REPLAY,
        REPLAY_DIGEST, REPLAY_MEMBERSHIP, STALE_SNAPSHOT, ID_COLLISION
    }

    private record TransactionObservation(
            String role, String isolation, String readOnly, int pid, boolean active) { }

    private record PendingConsumerSimulation(String requiredSetRevision, List<UUID> requisitos) { }
}
