package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommandValidator;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real registration infrastructure and reservation only; the complete writer/service belong to L2/L3. */
@Execution(ExecutionMode.SAME_THREAD)
class LegalRegistrationDatabaseIsolationIT {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_registration_isolation").withUsername("ordenfix").withPassword("ordenfix");
    private static final String ROLE = "ordenfix_registration_isolation_it";
    private static final String PASSWORD = "registration-isolation-fixture";

    private static JdbcTemplate owner;
    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static LegalPrivateRequirementsDataSource bounded;
    private static LegalRegistrationTransactionBoundary boundary;
    private static LegalIdempotencyCoordinator coordinator;

    @BeforeAll static void start() {
        POSTGRES.start();
        var ownerDataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        owner = new JdbcTemplate(ownerDataSource);
        Flyway.configure().dataSource(ownerDataSource).locations("classpath:db/migration").load().migrate();
        var credentials = LegalRestrictedRegistrationRoleFixture.provision(owner, ROLE, PASSWORD);
        var properties = LegalRegistrationDatabaseConfigurationTest.properties();
        properties.put(LegalRegistrationDatabaseConfiguration.PROPERTY_PREFIX + "jdbc-url", credentials.jdbcUrl());
        properties.put(LegalRegistrationDatabaseConfiguration.PROPERTY_PREFIX + "username", credentials.username());
        properties.put(LegalRegistrationDatabaseConfiguration.PROPERTY_PREFIX + "password", credentials.password());
        context = LegalRegistrationDatabaseConfigurationTest.context(properties);
        context.refresh();
        jdbc = context.getBean(JdbcTemplate.class);
        bounded = context.getBean(LegalPrivateRequirementsDataSource.class);
        boundary = context.getBean(LegalRegistrationTransactionBoundary.class);
        var schema = new LegalV29AcceptanceSchemaVerifier(jdbc, "public");
        var results = new LegalIdempotencyResultStore(jdbc);
        coordinator = new LegalIdempotencyCoordinator(jdbc, schema,
                context.getBean(LegalAcceptanceKeyConfiguration.class).keyring(), results);
    }

    @AfterAll static void stop() {
        try {
            if (context != null) {
                HikariDataSource pool = context.getBean(HikariDataSource.class);
                context.close();
                assertThat(pool.isClosed()).isTrue();
            }
        } finally {
            POSTGRES.stop();
        }
    }

    @Test void callbackHasRestrictedMutableReadCommittedTransactionAndCommitsItsOwnInsert() {
        var state = new LegalTransactionCompletionState<Long>();
        long id = bounded.withinDeadline(outerDeadline -> {
            assertThat(outerDeadline.remainingMillis()).isBetween(29_000, 30_000);
            assertThat(context.getBean(TransactionTemplate.class).getTimeout()).isEqualTo(25);
            return boundary.execute(state, (status, deadline) -> {
                assertThat(deadline).isSameAs(outerDeadline);
                assertThat(status.isNewTransaction()).isTrue();
                assertRestrictedTransaction();
                ConnectionHolder holder = (ConnectionHolder) TransactionSynchronizationManager.getResource(bounded);
                assertThat(holder).isNotNull();
                assertThat(holder.hasTimeout()).isTrue();
                assertThat(holder.getTimeToLiveInMillis()).isBetween(1L, 25_000L);
                assertThat(advisoryCount()).isZero();
                long workshop = insertWorkshop();
                assertThat(workshopRows(workshop)).isZero();
                return workshop;
            });
        });
        assertThat(workshopRows(id)).isEqualTo(1L);
        assertThat(state.snapshot().completion()).isEqualTo(LegalTransactionCompletionState.Completion.COMMITTED);
        assertThat(state.snapshot().receipt()).contains(id);
        assertClean();
    }

    @Test void sameDataSourceOuterTransactionIsRestoredWithoutRestartingTheSharedDeadline() {
        var innerState = new LegalTransactionCompletionState<Long>();
        var outer = new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        outer.setReadOnly(true);
        outer.setTimeout(25);
        long id = bounded.withinDeadline(outerDeadline -> outer.execute(outerStatus -> {
            Object originalHolder = TransactionSynchronizationManager.getResource(bounded);
            int originalPid = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
            String originalXid = jdbc.queryForObject("SELECT pg_current_xact_id()::text", String.class);
            int remainingBeforeInner = outerDeadline.remainingMillis();
            long inserted = boundary.execute(innerState, (status, deadline) -> {
                assertThat(deadline).isSameAs(outerDeadline);
                assertThat(deadline.remainingMillis()).isLessThanOrEqualTo(remainingBeforeInner);
                assertThat(TransactionSynchronizationManager.getResource(bounded)).isNotSameAs(originalHolder);
                assertThat(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)).isNotEqualTo(originalPid);
                assertThat(jdbc.queryForObject("SELECT pg_current_xact_id()::text", String.class)).isNotEqualTo(originalXid);
                assertRestrictedTransaction();
                return insertWorkshop();
            });
            assertThat(TransactionSynchronizationManager.getResource(bounded)).isSameAs(originalHolder);
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
            assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                    .isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            assertThat(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)).isEqualTo(originalPid);
            assertThat(jdbc.queryForObject("SELECT pg_current_xact_id()::text", String.class)).isEqualTo(originalXid);
            assertThat(jdbc.queryForObject("SELECT current_setting('transaction_isolation')", String.class)).isEqualTo("repeatable read");
            assertThat(outerStatus.isRollbackOnly()).isFalse();
            assertThat(outerDeadline.remainingMillis()).isLessThanOrEqualTo(remainingBeforeInner);
            outerStatus.setRollbackOnly();
            return inserted;
        }));
        assertThat(workshopRows(id)).isEqualTo(1L);
        assertThat(innerState.snapshot().completion()).isEqualTo(LegalTransactionCompletionState.Completion.COMMITTED);
        assertThat(innerState.snapshot().receipt()).contains(id);
        assertClean();
    }

    @Test void anUnrelatedOwnerTransactionCannotSupplyTheRegistrationConnectionOrUndoItsCommit() {
        var outer = new TransactionTemplate(new DataSourceTransactionManager(owner.getDataSource()));
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        AtomicReference<Long> rolledBack = new AtomicReference<>();
        var innerState = new LegalTransactionCompletionState<Long>();
        Long committed = outer.execute(status -> {
            Object ownerHolder = TransactionSynchronizationManager.getResource(owner.getDataSource());
            int ownerPid = owner.queryForObject("SELECT pg_backend_pid()", Integer.class);
            rolledBack.set(owner.queryForObject("INSERT INTO talleres(nombre) VALUES (?) RETURNING id",
                    Long.class, "Outer " + UUID.randomUUID()));
            Long result = boundary.execute(innerState, (inner, deadline) -> {
                assertThat(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)).isNotEqualTo(ownerPid);
                assertThat(inner.isNewTransaction()).isTrue();
                assertRestrictedTransaction();
                return insertWorkshop();
            });
            assertThat(TransactionSynchronizationManager.getResource(owner.getDataSource())).isSameAs(ownerHolder);
            assertThat(owner.queryForObject("SELECT pg_backend_pid()", Integer.class)).isEqualTo(ownerPid);
            assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                    .isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            assertThat(status.isRollbackOnly()).isFalse();
            status.setRollbackOnly();
            return result;
        });
        assertThat(workshopRows(rolledBack.get())).isZero();
        assertThat(workshopRows(committed)).isEqualTo(1L);
        assertThat(innerState.snapshot().completion()).isEqualTo(LegalTransactionCompletionState.Completion.COMMITTED);
        assertClean();
    }

    @Test void callbackFailureRollsBackItsInsertAndReleasesTheRegistrationResources() {
        var state = new LegalTransactionCompletionState<Long>();
        AtomicReference<Long> inserted = new AtomicReference<>();
        IllegalStateException failure = new IllegalStateException("registration callback fixture");

        assertThatThrownBy(() -> boundary.execute(state, (status, deadline) -> {
            inserted.set(insertWorkshop());
            throw failure;
        })).isSameAs(failure);

        assertThat(inserted.get()).isNotNull();
        assertThat(workshopRows(inserted.get())).isZero();
        assertThat(state.snapshot().completion()).isEqualTo(LegalTransactionCompletionState.Completion.ROLLED_BACK);
        assertThat(state.snapshot().persistence()).isEqualTo(LegalTransactionCompletionState.Persistence.NOT_PERSISTED);
        assertThat(state.snapshot().receipt()).isEmpty();
        assertClean();
    }

    @Test void privilegeDriftStopsTheOperationBeforeTheConsumerCallback() {
        var state = new LegalTransactionCompletionState<String>();
        AtomicInteger calls = new AtomicInteger();
        boundary.execute(new LegalTransactionCompletionState<>(), (status, deadline) -> "baseline");
        owner.execute("REVOKE INSERT (email) ON users FROM " + ROLE);
        try {
            assertThatThrownBy(() -> boundary.execute(state, (status, deadline) -> {
                calls.incrementAndGet();
                return "unreachable";
            })).isInstanceOf(LegalEditorialOperationalException.class);
            assertThat(calls).hasValue(0);
            assertThat(state.snapshot().completion()).isEqualTo(LegalTransactionCompletionState.Completion.ROLLED_BACK);
            assertThat(state.snapshot().receipt()).isEmpty();
            assertClean();
        } finally {
            owner.execute("GRANT INSERT (email) ON users TO " + ROLE);
        }
        String restored = boundary.execute(new LegalTransactionCompletionState<>(), (status, deadline) -> "restored");
        assertThat(restored).isEqualTo("restored");
        assertClean();
    }

    @Test void registrationMissPrecedesTheExplicitSharedEditorialGateWithoutWritingALedger() {
        LegalAcceptanceCommand command = LegalAcceptanceCommandValidator.registration(
                new LegalAcceptanceCommand.Registration("Registration fixture", null, "Admin fixture",
                        "registration-boundary@test.invalid", "registration-input-fixture"),
                "sha256:" + "a".repeat(64), List.of());
        long before = ledgerRows();
        var state = new LegalTransactionCompletionState<String>();

        assertThat(boundary.execute(state, (status, deadline) -> {
            assertThat(advisoryCount()).isZero();
            var reservation = coordinator.reserve(command, UUID.randomUUID().toString(), deadline::remainingMillis);
            assertThat(reservation.command().operation()).isEqualTo(LegalAcceptanceCommand.Operation.REGISTRATION);
            assertThat(reservation.replay()).isEmpty();
            reservation.requireNew();
            assertThat(advisoryCount()).isPositive();
            assertThat(editorialLockModes()).isEmpty();
            var time = boundary.enterEditorialShared(reservation, deadline);
            assertThat(time.observedAt()).isAfterOrEqualTo(time.transactionAt());
            assertThat(editorialLockModes()).containsExactly("ShareLock");
            reservation.requireNew();
            return "reserved-and-gated";
        })).isEqualTo("reserved-and-gated");

        assertThat(ledgerRows()).isEqualTo(before);
        assertThat(state.snapshot().completion()).isEqualTo(LegalTransactionCompletionState.Completion.COMMITTED);
        assertClean();
    }

    private static void assertRestrictedTransaction() {
        assertThat(jdbc.queryForMap("""
                SELECT current_user::text AS current_role, session_user::text AS session_role,
                       current_setting('transaction_isolation') AS isolation,
                       current_setting('transaction_read_only') AS read_only
                """)).containsEntry("current_role", ROLE).containsEntry("session_role", ROLE)
                .containsEntry("isolation", "read committed").containsEntry("read_only", "off");
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
        assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    private static long insertWorkshop() {
        return Objects.requireNonNull(jdbc.queryForObject("INSERT INTO talleres(nombre,activo) VALUES (?,true) RETURNING id",
                Long.class, "Registration boundary " + UUID.randomUUID()));
    }

    private static long workshopRows(Long id) {
        return Objects.requireNonNull(owner.queryForObject("SELECT count(*) FROM talleres WHERE id=?", Long.class, id));
    }

    private static long ledgerRows() {
        return Objects.requireNonNull(owner.queryForObject("""
                SELECT (SELECT count(*) FROM legal_idempotencia_resultados)
                     + (SELECT count(*) FROM legal_idempotencia_sin_actos)
                """, Long.class));
    }

    private static int advisoryCount() {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT count(*) FROM pg_locks WHERE pid=pg_backend_pid() AND locktype='advisory'", Integer.class));
    }

    private static List<String> editorialLockModes() {
        return jdbc.queryForList("""
                SELECT mode FROM pg_locks WHERE pid=pg_backend_pid() AND locktype='advisory'
                  AND classid=((hashtextextended(?,0)>>32)&4294967295)::oid
                  AND objid=(hashtextextended(?,0)&4294967295)::oid AND objsubid=1 AND granted
                """, String.class, LegalManifestDatabaseGate.EDITORIAL_LOCK_NAME, LegalManifestDatabaseGate.EDITORIAL_LOCK_NAME);
    }

    private static void assertClean() {
        assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
        assertThat(context.getBean(HikariDataSource.class).getHikariPoolMXBean().getActiveConnections()).isZero();
    }
}
