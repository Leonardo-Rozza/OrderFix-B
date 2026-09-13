package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Taller;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.User;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalApplicableScopeResolver;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalApplicableScopeSet;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PerfilAgregadoLegal;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

/** Actual private configuration, exact V29 preflight and restricted actor/store transactions. */
class LegalPrivateRequirementsDatabaseContextIT {

    private static final String ROLE = "ordenfix_legal_private_requirements_context_it";
    private static final String PASSWORD = "legal-private-requirements-context-test-only";
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_private_requirements_context")
            .withUsername("ordenfix").withPassword("ordenfix");

    @TempDir
    private Path temporaryDirectory;
    private static DataSource ownerDataSource;
    private static JdbcTemplate owner;
    private AnnotationConfigApplicationContext context;
    private HikariDataSource pool;
    private LegalJdbcMetricsSupport metrics;
    private LegalPrivateRequirementsDataSource bounded;
    private LegalManifestDatabaseGate gate;
    private LegalRequiredSetAggregateStore store;
    private JdbcTemplate jdbc;
    private LegalActorSnapshotReader actorReader;
    private LegalPrivateRequirementsITSupport.Actor actor;
    private AuthenticatedUserPrincipal principal;

    @BeforeAll
    static void migrateAndProvisionOnlyTheEphemeralConsumer() {
        POSTGRES.start();
        LegalManifestPersistenceITSupport.migrateLatest(POSTGRES);
        ownerDataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        owner = new JdbcTemplate(ownerDataSource);
        LegalPrivateRequirementsITSupport.provision(owner, ROLE, PASSWORD);
    }

    @BeforeEach
    void seedAndOpenTheActualConfiguration() throws Exception {
        assertThat(owner.queryForObject("SELECT current_database()", String.class))
                .startsWith("ordenfix_legal_private_requirements_");
        owner.execute("TRUNCATE legal_requisito_agregados, legal_publicaciones, "
                + "legal_documento_reemplazo_lotes RESTART IDENTITY CASCADE");
        LegalPrivateRequirementsITSupport.seedCatalog(owner, temporaryDirectory, getClass());
        actor = LegalPrivateRequirementsITSupport.seedActor(owner, "ADMIN");
        principal = new AuthenticatedUserPrincipal(User.builder().id(actor.userId())
                .taller(Taller.builder().id(actor.workshopId()).build())
                .role(UserRole.ADMIN).active(true).tokenVersion(0)
                .email("context@example.invalid").password("context-test-unused-hash").build());
        context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("requirements-it", Map.of(
                LegalPrivateRequirementsDatabaseConfiguration.ENABLED_PROPERTY, "true",
                LegalPrivateRequirementsDatabaseConfiguration.PROPERTY_PREFIX + "jdbc-url", POSTGRES.getJdbcUrl(),
                LegalPrivateRequirementsDatabaseConfiguration.PROPERTY_PREFIX + "username", ROLE,
                LegalPrivateRequirementsDatabaseConfiguration.PROPERTY_PREFIX + "password", PASSWORD)));
        context.getBeanFactory().addBeanPostProcessor(new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (name.equals("legalPrivateRequirementsPool")) {
                    pool = (HikariDataSource) bean;
                    metrics = LegalJdbcMetricsSupport.instrument(pool, Duration.ZERO);
                    HikariDataSource monitored = spy(pool);
                    try {
                        doAnswer(invocation -> metrics.dataSource().getConnection()).when(monitored).getConnection();
                    } catch (SQLException impossibleDuringStubbing) {
                        throw new IllegalStateException(impossibleDuringStubbing);
                    }
                    return monitored;
                }
                return bean;
            }
        });
        context.register(LegalPrivateRequirementsDatabaseConfiguration.class);
        context.refresh();
        bounded = context.getBean(LegalPrivateRequirementsDataSource.class);
        gate = context.getBean(LegalManifestDatabaseGate.class);
        store = context.getBean(LegalRequiredSetAggregateStore.class);
        jdbc = context.getBean(JdbcTemplate.class);
        actorReader = context.getBean(LegalActorSnapshotReader.class);
        metrics.reset();
    }

    @AfterEach
    void closeAllResources() {
        if (context != null) {
            context.close();
        }
        if (pool != null) {
            pool.close();
        }
    }

    @AfterAll
    static void stopContainer() {
        POSTGRES.stop();
    }

    @Test
    void dedicatedContextCommitsAndSequentialReuseHasNoDml() {
        assertThat(context.getBeansOfType(LegalDatabaseBoundaryMarker.class).values())
                .extracting(LegalDatabaseBoundaryMarker::kind)
                .containsExactly(LegalDatabaseBoundaryMarker.Kind.PRIVATE_REQUIREMENTS);
        assertThat(context.getBeansOfType(LegalRequiredSetAggregateService.class)).isEmpty();
        assertThat(context.getBeansOfType(LegalPublicDocumentReadService.class)).isEmpty();
        assertThat(context.getBeansOfType(LegalPublicRequirementsReadService.class)).isEmpty();
        assertThat(jdbc.getDataSource()).isSameAs(bounded).isNotSameAs(ownerDataSource);
        var manager = context.getBean(DataSourceTransactionManager.class);
        assertThat(manager.getClass()).isEqualTo(DataSourceTransactionManager.class);
        assertThat(manager.getDataSource()).isSameAs(bounded);
        assertThat(manager.isRollbackOnCommitFailure()).isFalse();

        AtomicInteger completed = new AtomicInteger(-1);
        LegalRequiredSetAggregateReceipt first = execute((status, boundary) -> {
            assertThat(jdbc.queryForObject("SELECT current_user || ':' || session_user", String.class))
                    .isEqualTo(ROLE + ":" + ROLE);
            assertThat(jdbc.queryForObject("SHOW transaction_isolation", String.class)).isEqualTo("read committed");
            assertThat(jdbc.queryForObject("SHOW transaction_read_only", String.class)).isEqualTo("off");
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int state) { completed.set(state); }
            });
            return store.materialize(scopes(), boundary);
        });
        assertThat(first.outcome()).isEqualTo(LegalRequiredSetAggregateReceipt.Outcome.CREATED);
        assertThat(completed.get()).isEqualTo(TransactionSynchronization.STATUS_COMMITTED);
        assertThat(metrics.snapshot().commits()).isEqualTo(1);
        assertThat(metrics.snapshot().rollbacks()).isZero();
        assertThat(metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isPositive();
        assertThat(metrics.snapshot().advisoryLockExecutions(LegalJdbcMetricsSupport.AdvisoryLockMode.SHARED))
                .isEqualTo(2);
        assertCounts(1, 1);

        metrics.reset();
        LegalRequiredSetAggregateReceipt reused = materialize();
        assertThat(reused.outcome()).isEqualTo(LegalRequiredSetAggregateReceipt.Outcome.REUSED);
        assertThat(reused.aggregateId()).isEqualTo(first.aggregateId());
        assertThat(reused.requiredSetRevision()).isEqualTo(first.requiredSetRevision());
        assertThat(reused.provenance()).isEqualTo(first.provenance());
        assertThat(metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isZero();
        assertThat(metrics.snapshot().commits()).isEqualTo(1);
        assertThat(metrics.snapshot().rollbacks()).isZero();
        assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
    }

    @Test
    void callerFailureAfterStoreRollsBackBothNewTablesAndAllowsANewAttempt() {
        IllegalStateException failure = new IllegalStateException("simulated consumer accreditation failure");
        assertThatThrownBy(() -> execute((status, boundary) -> {
            assertThat(store.materialize(scopes(), boundary).outcome())
                    .isEqualTo(LegalRequiredSetAggregateReceipt.Outcome.CREATED);
            throw failure;
        })).isSameAs(failure);
        assertCounts(0, 0);
        assertThat(metrics.snapshot().commits()).isZero();
        assertThat(metrics.snapshot().rollbacks()).isEqualTo(1);
        assertThat(metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isPositive();
        assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
        assertThat(materialize().outcome()).isEqualTo(LegalRequiredSetAggregateReceipt.Outcome.CREATED);
        assertCounts(1, 1);
    }

    @Test
    void callerFailureAfterReusePreservesTheCommittedAggregateWithoutDml() {
        LegalRequiredSetAggregateReceipt first = materialize();
        metrics.reset();
        assertThatThrownBy(() -> execute((status, boundary) -> {
            assertThat(store.materialize(scopes(), boundary).outcome())
                    .isEqualTo(LegalRequiredSetAggregateReceipt.Outcome.REUSED);
            throw new IllegalStateException("simulated reused content failure");
        })).isInstanceOf(IllegalStateException.class);
        assertCounts(1, 1);
        assertThat(metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isZero();
        assertThat(metrics.snapshot().commits()).isZero();
        assertThat(metrics.snapshot().rollbacks()).isEqualTo(1);
        assertThat(materialize().aggregateId()).isEqualTo(first.aggregateId());
    }

    @Test
    void requiresNewSuspendsTheOuterConnectionAndItsCommitSurvivesOuterRollback() {
        var outer = new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        AtomicReference<LegalRequiredSetAggregateReceipt> committed = new AtomicReference<>();
        bounded.withinDeadline(deadline -> {
            outer.executeWithoutResult(status -> {
                int outerPid = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
                committed.set(execute((inner, boundary) -> {
                    assertThat(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)).isNotEqualTo(outerPid);
                    assertThat(jdbc.queryForObject("SHOW transaction_isolation", String.class)).isEqualTo("read committed");
                    return store.materialize(scopes(), boundary);
                }));
                assertThat(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)).isEqualTo(outerPid);
                assertThat(jdbc.queryForObject("SHOW transaction_isolation", String.class)).isEqualTo("serializable");
                status.setRollbackOnly();
            });
            return "completed";
        });
        assertThat(committed.get().outcome()).isEqualTo(LegalRequiredSetAggregateReceipt.Outcome.CREATED);
        assertCounts(1, 1);
        assertThat(metrics.snapshot().commits()).isEqualTo(1);
        assertThat(metrics.snapshot().rollbacks()).isEqualTo(1);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
    }

    @Test
    void privilegeDriftStopsBeforeEditorialLockAndStoreAndRecoversAfterRestoration() {
        owner.execute("GRANT SELECT (password) ON users TO " + ROLE);
        try {
            assertThatThrownBy(this::materialize).isInstanceOfSatisfying(LegalEditorialOperationalException.class,
                    error -> assertThat(error.issue().code()).isEqualTo(LegalManifestIssueCode.ROLE_PRIVILEGE_DRIFT));
        } finally {
            owner.execute("REVOKE SELECT (password) ON users FROM " + ROLE);
        }
        assertCounts(0, 0);
        assertThat(metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isZero();
        assertThat(metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.ADVISORY_LOCK)).isZero();
        assertThat(metrics.snapshot().rollbacks()).isEqualTo(1);
        assertThat(materialize().outcome()).isEqualTo(LegalRequiredSetAggregateReceipt.Outcome.CREATED);
    }

    @Test
    void migrationEvidenceDriftFailsBeforePrivilegesLockOrStore() {
        Integer checksum = owner.queryForObject("SELECT checksum FROM flyway_schema_history WHERE version='29'",
                Integer.class);
        owner.update("UPDATE flyway_schema_history SET checksum=checksum+1 WHERE version='29'");
        try {
            assertThatThrownBy(this::materialize).isInstanceOf(LegalEditorialOperationalException.class);
        } finally {
            owner.update("UPDATE flyway_schema_history SET checksum=? WHERE version='29'", checksum);
        }
        assertCounts(0, 0);
        assertThat(metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isZero();
        assertThat(metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.ADVISORY_LOCK)).isZero();
        assertThat(metrics.snapshot().rollbacks()).isEqualTo(1);
        assertThat(materialize().outcome()).isEqualTo(LegalRequiredSetAggregateReceipt.Outcome.CREATED);
    }

    @Test
    void editorialLockTimeoutDoesNotReachStoreAndReleasesTheLease() throws SQLException {
        try (Connection blocker = ownerDataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (var lock = blocker.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                lock.setString(1, LegalManifestDatabaseGate.EDITORIAL_LOCK_NAME);
                lock.execute();
            }
            try {
                assertSqlState(this::materialize, "55P03");
            } finally {
                blocker.rollback();
            }
        }
        assertCounts(0, 0);
        assertThat(metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isZero();
        assertThat(metrics.snapshot().rollbacks()).isEqualTo(1);
        assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
        assertThat(materialize().outcome()).isEqualTo(LegalRequiredSetAggregateReceipt.Outcome.CREATED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"actor", "workshop", "user"})
    void actorAndAccountRowLocksHaveBoundedWaitsAndRollbackBeforeAggregateDml(String target) throws SQLException {
        try (Connection blocker = ownerDataSource.getConnection()) {
            blocker.setAutoCommit(false);
            String sql = switch (target) {
                case "actor" -> """
                        SELECT pg_advisory_xact_lock(hashtextextended(jsonb_build_array(
                            'ordenfix:legal-actor:v1', ?::bigint, ?::bigint)::text, 0))
                        """;
                case "workshop" -> "UPDATE talleres SET activo=false WHERE id=?";
                case "user" -> "UPDATE users SET active=false WHERE id=?";
                default -> throw new AssertionError(target);
            };
            try (var statement = blocker.prepareStatement(sql)) {
                statement.setLong(1, target.equals("user") ? actor.userId() : actor.workshopId());
                if (target.equals("actor")) { statement.setLong(2, actor.userId()); }
                statement.execute();
            }
            try {
                assertSqlState(this::materialize, "55P03");
            } finally {
                blocker.rollback();
            }
        }
        assertCounts(0, 0);
        assertThat(metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isZero();
        assertThat(metrics.snapshot().rollbacks()).isEqualTo(1);
        assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
        assertThat(materialize().outcome()).isEqualTo(LegalRequiredSetAggregateReceipt.Outcome.CREATED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"role", "active", "token-version", "workshop-active", "tenant"})
    void staleOrForeignServerPrincipalsAreRejectedAfterLocksAndBeforeStore(String changed) {
        switch (changed) {
            case "role" -> owner.update("UPDATE users SET role='USER' WHERE id=?", actor.userId());
            case "active" -> owner.update("UPDATE users SET active=false WHERE id=?", actor.userId());
            case "token-version" -> owner.update("UPDATE users SET token_version=1 WHERE id=?", actor.userId());
            case "workshop-active" -> owner.update("UPDATE talleres SET activo=false WHERE id=?", actor.workshopId());
            case "tenant" -> {
                var foreign = LegalPrivateRequirementsITSupport.seedActor(owner, "USER");
                // Only the presented identity claims another workshop; the persisted user never moves.
                principal = new AuthenticatedUserPrincipal(User.builder().id(actor.userId())
                        .taller(Taller.builder().id(foreign.workshopId()).build())
                        .role(UserRole.valueOf(actor.role())).active(true).tokenVersion(principal.getTokenVersion())
                        .email(principal.getUsername()).password(principal.getPassword()).build());
            }
            default -> throw new AssertionError(changed);
        }

        assertThatThrownBy(this::materialize).isInstanceOf(LegalActorSnapshotException.class)
                .hasMessage("La identidad legal no está disponible");

        assertCounts(0, 0);
        assertThat(metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isZero();
        assertThat(metrics.snapshot().commits()).isZero();
        assertThat(metrics.snapshot().rollbacks()).isEqualTo(1);
        assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
    }

    @Test
    void privateReaderHasNoAccountPasswordEvidenceWriteOrHttpSurface() {
        execute((status, boundary) -> {
            assertThat(jdbc.queryForObject("SELECT has_column_privilege(current_user, 'users', 'password', 'SELECT')",
                    Boolean.class)).isFalse();
            assertThat(jdbc.queryForObject("SELECT has_table_privilege(current_user, 'legal_aceptaciones', 'INSERT')",
                    Boolean.class)).isFalse();
            assertThat(jdbc.queryForObject("SELECT has_table_privilege(current_user, 'legal_aceptacion_metadatos', 'SELECT')",
                    Boolean.class)).isFalse();
            assertThat(jdbc.queryForObject("SELECT has_table_privilege(current_user, 'legal_idempotencia_sin_actos', 'SELECT')",
                    Boolean.class)).isFalse();
            return "accredited";
        });
        assertThat(context.getBeanNamesForAnnotation(org.springframework.stereotype.Controller.class)).isEmpty();
        assertThat(context.getBeansOfType(org.flywaydb.core.Flyway.class)).isEmpty();
        assertCounts(0, 0);
    }

    private LegalRequiredSetAggregateReceipt materialize() {
        return execute((status, boundary) -> store.materialize(scopes(), boundary));
    }

    private <T> T execute(LegalManifestDatabaseGate.EditorialTransactionCallback<T> operation) {
        return bounded.withinDeadline(deadline -> gate.executeMutableShared((status, boundary) -> {
            deadline.check();
            assertThat(actorReader.read(principal, boundary, deadline).userId()).isEqualTo(actor.userId());
            T result = operation.doInTransaction(status, boundary);
            deadline.check();
            return result;
        }));
    }

    private LegalApplicableScopeSet scopes() {
        return context.getBean(LegalApplicableScopeResolver.class).resolve(PerfilAgregadoLegal.AUTHENTICATED_PENDING,
                LocaleLegal.ES_AR, AudienciaLegal.ADMIN_TITULAR);
    }

    private static void assertCounts(int headers, int members) {
        assertThat(owner.queryForObject("SELECT count(*) FROM legal_requisito_agregados", Integer.class))
                .isEqualTo(headers);
        assertThat(owner.queryForObject("SELECT count(*) FROM legal_requisito_agregado_scopes", Integer.class))
                .isEqualTo(members);
    }

    private static void assertSqlState(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation, String state) {
        Throwable failure = catchThrowable(operation);
        assertThat(failure).isNotNull();
        while (failure != null && !(failure instanceof SQLException)) {
            failure = failure.getCause();
        }
        assertThat(failure).isInstanceOfSatisfying(SQLException.class,
                sql -> assertThat(sql.getSQLState()).isEqualTo(state));
    }
}
