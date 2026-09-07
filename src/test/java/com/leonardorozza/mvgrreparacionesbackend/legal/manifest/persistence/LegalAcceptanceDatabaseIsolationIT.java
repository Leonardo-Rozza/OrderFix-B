package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommandValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalActorSnapshot;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

/** Real configuration/transactions and G2 primitives; service acceptance semantics belong to I3. */
@Execution(ExecutionMode.SAME_THREAD)
class LegalAcceptanceDatabaseIsolationIT {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_acceptance_isolation").withUsername("ordenfix").withPassword("ordenfix");
    private static final String ROLE = "ordenfix_acceptance_isolation_it";
    private static final String PASSWORD = "acceptance-isolation-fixture";
    @TempDir static Path directory;
    private static JdbcTemplate owner;
    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static LegalPrivateRequirementsDataSource bounded;
    private static LegalAcceptanceTransactionBoundary boundary;
    private static LegalIdempotencyCoordinator coordinator;
    private static LegalIdempotencyResultStore store;
    private static LegalActorSnapshot actor;
    private static LegalRequiredSetAggregateReceipt aggregate;
    private static LegalAcceptanceCommand command;

    @BeforeAll static void start() throws Exception {
        POSTGRES.start();
        var ownerDataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        owner = new JdbcTemplate(ownerDataSource);
        Flyway.configure().dataSource(ownerDataSource).locations("classpath:db/migration").load().migrate();
        LegalRestrictedAcceptanceRoleFixture.seedCatalog(owner, directory, LegalAcceptanceDatabaseIsolationIT.class);
        var fixtureActor = LegalAcceptanceProtocolFeasibilityITSupport.insertActor(owner);
        actor = new LegalActorSnapshot(fixtureActor.userId(), fixtureActor.workshopId(), UserRole.USER, 0, true, true);
        var setup = new TransactionTemplate(new DataSourceTransactionManager(ownerDataSource));
        setup.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        aggregate = setup.execute(status -> LegalV29AcceptanceITSupport.aggregate(owner, fixtureActor));
        command = LegalAcceptanceCommandValidator.authenticated(actor, aggregate.requiredSetRevision(), List.of());
        LegalRestrictedAcceptanceRoleFixture.provision(owner, ROLE, PASSWORD);
        Map<String, Object> properties = LegalAcceptanceDatabaseConfigurationTest.properties();
        properties.put(LegalAcceptanceDatabaseConfiguration.PROPERTY_PREFIX + "jdbc-url", POSTGRES.getJdbcUrl());
        properties.put(LegalAcceptanceDatabaseConfiguration.PROPERTY_PREFIX + "username", ROLE);
        properties.put(LegalAcceptanceDatabaseConfiguration.PROPERTY_PREFIX + "password", PASSWORD);
        context = LegalAcceptanceDatabaseConfigurationTest.context(properties);
        context.refresh();
        jdbc = context.getBean(JdbcTemplate.class);
        bounded = context.getBean(LegalPrivateRequirementsDataSource.class);
        boundary = context.getBean(LegalAcceptanceTransactionBoundary.class);
        coordinator = context.getBean(LegalIdempotencyCoordinator.class);
        store = context.getBean(LegalIdempotencyResultStore.class);
    }

    @AfterAll static void stop() {
        if (context != null) context.close();
        POSTGRES.stop();
    }

    @Test void callbackHasRestrictedPhysicalTransactionAndNoAutomaticEditorialLock() {
        var state = new LegalTransactionCompletionState<String>();
        assertThat(boundary.execute(state, (status, deadline) -> {
            assertThat(status.isNewTransaction()).isTrue();
            assertThat(jdbc.queryForMap("""
                    SELECT current_user::text AS current_role, session_user::text AS session_role,
                           current_setting('transaction_isolation') AS isolation,
                           current_setting('transaction_read_only') AS read_only
                    """)).containsEntry("current_role", ROLE).containsEntry("session_role", ROLE)
                    .containsEntry("isolation", "read committed").containsEntry("read_only", "off");
            assertThat(advisoryCount()).isZero();
            return "accredited";
        })).isEqualTo("accredited");
        assertThat(state.snapshot().completion()).isEqualTo(LegalTransactionCompletionState.Completion.COMMITTED);
        assertClean();
    }

    @Test void sameDataSourceOuterReadOnlyRepeatableReadIsSuspendedAndItsRollbackCannotUndoInnerResult() {
        long before = results();
        var innerState = new LegalTransactionCompletionState<String>();
        String key = UUID.randomUUID().toString();
        var outer = new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        outer.setReadOnly(true);
        outer.setTimeout(15);
        bounded.withinDeadline(outerDeadline -> outer.execute(outerStatus -> {
            Object holder = TransactionSynchronizationManager.getResource(bounded);
            int pid = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
            String xid = jdbc.queryForObject("SELECT pg_current_xact_id()::text", String.class);
            assertThat(boundary.execute(innerState, (status, deadline) -> {
                assertThat(TransactionSynchronizationManager.getResource(bounded)).isNotSameAs(holder);
                assertThat(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)).isNotEqualTo(pid);
                assertThat(jdbc.queryForObject("SELECT pg_current_xact_id()::text", String.class)).isNotEqualTo(xid);
                persistPrimitiveResult(key, deadline);
                assertThat(results()).isEqualTo(before); // Uncommitted rows are invisible on owner's connection.
                return "inner-confirmed";
            })).isEqualTo("inner-confirmed");
            assertThat(TransactionSynchronizationManager.getResource(bounded)).isSameAs(holder);
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
            assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                    .isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            assertThat(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)).isEqualTo(pid);
            assertThat(jdbc.queryForObject("SELECT pg_current_xact_id()::text", String.class)).isEqualTo(xid);
            assertThat(jdbc.queryForObject("SELECT current_setting('transaction_isolation')", String.class)).isEqualTo("repeatable read");
            outerStatus.setRollbackOnly();
            return "outer-rolled-back";
        }));
        assertThat(results()).isEqualTo(before + 1);
        assertThat(innerState.snapshot().completion()).isEqualTo(LegalTransactionCompletionState.Completion.COMMITTED);
        assertReplay(key);
        assertClean();
    }

    @Test void unrelatedOwnerTransactionIsRestoredAndCannotSupplyTheWriterConnection() {
        long before = results();
        var outer = new TransactionTemplate(new DataSourceTransactionManager(owner.getDataSource()));
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        AtomicReference<Long> rolledBackWorkshop = new AtomicReference<>();
        String key = UUID.randomUUID().toString();
        outer.execute(status -> {
            Object ownerHolder = TransactionSynchronizationManager.getResource(owner.getDataSource());
            rolledBackWorkshop.set(owner.queryForObject("INSERT INTO talleres(nombre) VALUES ('outer-fixture') RETURNING id", Long.class));
            int ownerPid = owner.queryForObject("SELECT pg_backend_pid()", Integer.class);
            boundary.execute(new LegalTransactionCompletionState<>(), (inner, deadline) -> {
                assertThat(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)).isNotEqualTo(ownerPid);
                assertThat(jdbc.queryForObject("SELECT current_user::text", String.class)).isEqualTo(ROLE);
                persistPrimitiveResult(key, deadline);
                return "writer-confirmed";
            });
            assertThat(TransactionSynchronizationManager.getResource(owner.getDataSource())).isSameAs(ownerHolder);
            status.setRollbackOnly();
            return null;
        });
        assertThat(owner.queryForObject("SELECT count(*) FROM talleres WHERE id=?", Integer.class, rolledBackWorkshop.get())).isZero();
        assertThat(results()).isEqualTo(before + 1);
        assertReplay(key);
        assertClean();
    }

    @Test void failureAfterRealInsertRollsBackResultAndReleasesEveryResource() {
        long before = results();
        var state = new LegalTransactionCompletionState<String>();
        assertThatThrownBy(() -> boundary.execute(state, (status, deadline) -> {
            persistPrimitiveResult(UUID.randomUUID().toString(), deadline);
            throw new IllegalStateException("after-result-fixture");
        })).isInstanceOf(IllegalStateException.class).hasMessage("after-result-fixture");
        assertThat(results()).isEqualTo(before);
        assertThat(state.snapshot().completion()).isEqualTo(LegalTransactionCompletionState.Completion.ROLLED_BACK);
        assertThat(state.snapshot().receipt()).isEmpty();
        assertClean();
    }

    @Test void replayCannotEnterEditorialGraphAndMarksOnlyItsTransactionForRollback() {
        String key = UUID.randomUUID().toString();
        boundary.execute(new LegalTransactionCompletionState<>(), (status, deadline) -> {
            persistPrimitiveResult(key, deadline); return "new";
        });
        long before = results();
        var state = new LegalTransactionCompletionState<String>();
        assertThatThrownBy(() -> boundary.execute(state, (status, deadline) -> {
            var reservation = coordinator.reserve(command, key, deadline::remainingMillis);
            assertThat(reservation.replay()).isPresent();
            assertThat(editorialHeld()).isFalse();
            try { boundary.enterEditorialShared(reservation, deadline); }
            catch (LegalIdempotencyException failure) {
                assertThat(status.isRollbackOnly()).isTrue();
                throw failure;
            }
            return "unexpected";
        })).isInstanceOf(LegalIdempotencyException.class);
        assertThat(results()).isEqualTo(before);
        assertThat(state.snapshot().completion()).isEqualTo(LegalTransactionCompletionState.Completion.ROLLED_BACK);
        assertClean();
    }

    private static void persistPrimitiveResult(String key, LegalPrivateRequirementsDeadline deadline) {
        var reservation = coordinator.reserve(command, key, deadline::remainingMillis);
        assertThat(reservation.replay()).isEmpty();
        assertThat(editorialHeld()).isFalse();
        var time = boundary.enterEditorialShared(reservation, deadline);
        assertThat(time.observedAt()).isAfterOrEqualTo(time.transactionAt());
        assertThat(editorialHeld()).isTrue();
        jdbc.queryForList("""
                SELECT pg_advisory_xact_lock(pg_catalog.hashtextextended(pg_catalog.jsonb_build_array(
                  'ordenfix:legal-actor:v1', ?::bigint, ?::bigint)::text, 0))
                """, actor.tallerId(), actor.userId());
        // Primitive result storage deliberately does not claim the I3 pending-requirements policy.
        store.persistWithoutActs(reservation, actor, aggregate, List.of());
    }

    private static void assertReplay(String key) {
        long before = results();
        boundary.execute(new LegalTransactionCompletionState<>(), (status, deadline) -> {
            var reservation = coordinator.reserve(command, key, deadline::remainingMillis);
            assertThat(reservation.replay()).isPresent();
            assertThat(editorialHeld()).isFalse();
            return "replayed";
        });
        assertThat(results()).isEqualTo(before);
    }

    private static long results() { return owner.queryForObject("SELECT count(*) FROM legal_idempotencia_sin_actos", Long.class); }
    private static int advisoryCount() {
        return jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE pid=pg_backend_pid() AND locktype='advisory'", Integer.class);
    }
    private static boolean editorialHeld() {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM pg_locks WHERE pid=pg_backend_pid() AND locktype='advisory'
                  AND classid=((hashtextextended(?,0)>>32)&4294967295)::oid
                  AND objid=(hashtextextended(?,0)&4294967295)::oid AND objsubid=1 AND granted)
                """, Boolean.class, LegalManifestDatabaseGate.EDITORIAL_LOCK_NAME, LegalManifestDatabaseGate.EDITORIAL_LOCK_NAME));
    }
    private static void assertClean() {
        assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
        assertThat(context.getBean(HikariDataSource.class).getHikariPoolMXBean().getActiveConnections()).isZero();
    }
}
