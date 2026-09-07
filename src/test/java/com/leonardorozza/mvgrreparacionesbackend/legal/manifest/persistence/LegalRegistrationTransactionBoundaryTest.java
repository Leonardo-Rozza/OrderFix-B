package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LegalRegistrationTransactionBoundaryTest {
    @ParameterizedTest @ValueSource(strings = {"propagation", "isolation", "readonly", "timeout", "rollback", "manager", "jdbc", "schema", "privileges"})
    void mutableGraphDriftIsRejectedBeforeBorrowOrSql(String drift) throws Exception {
        try (var fixture = new Fixture()) {
            switch (drift) {
                case "propagation" -> fixture.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
                case "isolation" -> fixture.tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
                case "readonly" -> fixture.tx.setReadOnly(true);
                case "timeout" -> fixture.tx.setTimeout(15);
                case "rollback" -> fixture.manager.setRollbackOnCommitFailure(true);
                case "manager" -> fixture.tx.setTransactionManager(new DataSourceTransactionManager(mock(DataSource.class)));
                case "jdbc" -> when(fixture.jdbc.getDataSource()).thenReturn(mock(DataSource.class));
                case "schema" -> when(fixture.schema.usesJdbc(fixture.jdbc)).thenReturn(false);
                case "privileges" -> when(fixture.privileges.expectedSchema()).thenReturn("foreign");
                default -> throw new AssertionError();
            }
            assertThatThrownBy(() -> fixture.boundary.execute(new LegalTransactionCompletionState<>(),
                    (status, deadline) -> "unexpected")).isInstanceOf(IllegalArgumentException.class);
            verify(fixture.pool, never()).getConnection();
            verify(fixture.schema, never()).verify();
            verify(fixture.privileges, never()).verify();
        }
    }

    @Test
    void historicalFifteenSecondDataSourceCannotMasqueradeAsRegistration() {
        try (var historical = new LegalPrivateRequirementsDataSource(mock(DataSource.class), Duration.ofSeconds(15))) {
            var jdbc = new JdbcTemplate(historical);
            var manager = new DataSourceTransactionManager(historical);
            manager.setRollbackOnCommitFailure(false);
            var tx = new TransactionTemplate(manager);
            tx.setTimeout(25);
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            var schema = mock(LegalRegistrationSchemaVerifier.class);
            var privileges = mock(LegalRegistrationPrivilegeVerifier.class);
            when(schema.usesJdbc(jdbc)).thenReturn(true);
            when(schema.expectedSchema()).thenReturn("public");
            when(privileges.usesJdbc(jdbc)).thenReturn(true);
            when(privileges.expectedSchema()).thenReturn("public");
            assertThatThrownBy(() -> new LegalRegistrationTransactionBoundary(jdbc, historical, tx,
                    new LegalDatabaseBudgets(25, 5, 1, 1), schema, privileges))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void successRegistersBeforePreflightAndDoesNotAcquireEditorialLock() throws Exception {
        try (var fixture = new Fixture()) {
            var state = new LegalTransactionCompletionState<String>();
            doAnswer(call -> {
                assertThat(state.snapshot().callbackStarted()).isTrue();
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                return null;
            }).when(fixture.schema).verify();
            assertThat(fixture.boundary.execute(state, (status, deadline) -> {
                assertThat(status.isNewTransaction()).isTrue();
                assertThat(state.snapshot().receipt()).isEmpty();
                return "receipt";
            })).isEqualTo("receipt");
            assertThat(state.snapshot().completion()).isEqualTo(LegalTransactionCompletionState.Completion.COMMITTED);
            assertThat(state.snapshot().receipt()).contains("receipt");
            verify(fixture.connection).commit();
            verify(fixture.connection, never()).rollback();
            verify(fixture.jdbc, never()).queryForList(anyString(), any(Object[].class));
            assertClean();
        }
    }

    @Test
    void preflightFailureIsRegisteredRolledBackAndNeverInvokesWork() throws Exception {
        try (var fixture = new Fixture()) {
            RuntimeException failure = new IllegalStateException("preflight-fixture");
            doThrow(failure).when(fixture.privileges).verify();
            var state = new LegalTransactionCompletionState<String>();
            AtomicBoolean invoked = new AtomicBoolean();
            assertThatThrownBy(() -> fixture.boundary.execute(state, (status, deadline) -> {
                invoked.set(true); return "unexpected";
            })).isSameAs(failure);
            assertThat(invoked).isFalse();
            assertThat(state.snapshot().completion()).isEqualTo(LegalTransactionCompletionState.Completion.ROLLED_BACK);
            assertThat(state.snapshot().receipt()).isEmpty();
            verify(fixture.connection).rollback();
            verify(fixture.connection, never()).commit();
            assertClean();
        }
    }

    @Test
    void nullReceiptCannotCommit() throws Exception {
        try (var fixture = new Fixture()) {
            var state = new LegalTransactionCompletionState<String>();
            assertThatThrownBy(() -> fixture.boundary.execute(state, (status, deadline) -> null))
                    .isInstanceOf(NullPointerException.class);
            assertThat(state.snapshot().completion()).isEqualTo(LegalTransactionCompletionState.Completion.ROLLED_BACK);
            verify(fixture.connection, never()).commit();
            assertClean();
        }
    }

    @Test
    void rollbackOnlyCannotReturnATentativeReceiptAsSuccess() throws Exception {
        try (var fixture = new Fixture()) {
            var state = new LegalTransactionCompletionState<String>();
            assertThatThrownBy(() -> fixture.boundary.execute(state, (status, deadline) -> {
                status.setRollbackOnly();
                return "must-not-escape";
            })).isInstanceOf(IllegalStateException.class);
            assertThat(state.snapshot().completion()).isEqualTo(LegalTransactionCompletionState.Completion.ROLLED_BACK);
            assertThat(state.snapshot().receipt()).isEmpty();
            verify(fixture.connection, never()).commit();
            verify(fixture.connection).rollback();
            assertClean();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"isolation", "read_only"})
    void cachedJdbcFlagsCannotHideAnIncompatiblePostgresMode(String changed) throws Exception {
        try (var fixture = new Fixture()) {
            when(fixture.jdbc.queryForMap(anyString())).thenReturn(java.util.Map.of(
                    "isolation", changed.equals("isolation") ? "repeatable read" : "read committed",
                    "read_only", changed.equals("read_only") ? "on" : "off"));
            var state = new LegalTransactionCompletionState<String>();
            assertThatThrownBy(() -> fixture.boundary.execute(state, (status, deadline) -> "unexpected"))
                    .isInstanceOf(IllegalStateException.class);
            verify(fixture.schema, never()).verify();
            verify(fixture.privileges, never()).verify();
            assertThat(state.snapshot().completion()).isEqualTo(LegalTransactionCompletionState.Completion.ROLLED_BACK);
            assertClean();
        }
    }

    @Test
    void commitSQLExceptionRemainsUnknownAndNeverAttemptsACompensatingRollback() throws Exception {
        try (var fixture = new Fixture()) {
            SQLException lost = new SQLException("synthetic acknowledgement loss", "08006");
            doThrow(lost).when(fixture.connection).commit();
            var state = new LegalTransactionCompletionState<String>();
            assertThatThrownBy(() -> fixture.boundary.execute(state, (status, deadline) -> "tentative"))
                    .isInstanceOf(TransactionSystemException.class).hasCause(lost);
            assertThat(state.snapshot().completion()).isEqualTo(LegalTransactionCompletionState.Completion.UNKNOWN);
            assertThat(state.snapshot().receipt()).isEmpty();
            verify(fixture.connection, never()).rollback();
            assertClean();
        }
    }

    @Test
    void expiredAfterSuccessfulCommitRejectsDeliveryButRetainsCommittedEvidence() throws Exception {
        try (var fixture = new Fixture()) {
            doAnswer(call -> { fixture.clock.set(Duration.ofSeconds(31).toNanos()); return null; })
                    .when(fixture.connection).commit();
            var state = new LegalTransactionCompletionState<String>();
            assertThatThrownBy(() -> fixture.boundary.execute(state, (status, deadline) -> "committed"))
                    .isInstanceOf(LegalPrivateRequirementsReadException.class);
            assertThat(state.snapshot().completion()).isEqualTo(LegalTransactionCompletionState.Completion.COMMITTED);
            assertThat(state.snapshot().receipt()).contains("committed");
            verify(fixture.connection, never()).rollback();
            assertClean();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"foreign", "replay", "authenticated"})
    void editorialEntryRejectsInvalidReservationBeforeLock(String kind) throws Exception {
        try (var fixture = new Fixture()) {
            var reservation = mock(LegalIdempotencyCoordinator.Reservation.class);
            when(reservation.jdbc()).thenReturn(kind.equals("foreign") ? mock(JdbcTemplate.class) : fixture.jdbc);
            if (kind.equals("replay")) {
                doThrow(new LegalIdempotencyException(LegalIdempotencyException.Reason.UNAVAILABLE))
                        .when(reservation).requireNew();
            }
            if (kind.equals("authenticated")) {
                var command = mock(com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand.class);
                when(command.operation()).thenReturn(
                        com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand.Operation.AUTHENTICATED_ACCEPTANCE);
                when(reservation.command()).thenReturn(command);
            }
            when(reservation.fail(any(RuntimeException.class))).thenAnswer(call ->
                    new LegalIdempotencyException(LegalIdempotencyException.Reason.UNAVAILABLE, call.getArgument(0)));
            var state = new LegalTransactionCompletionState<String>();
            assertThatThrownBy(() -> fixture.boundary.execute(state, (status, deadline) -> {
                fixture.boundary.enterEditorialShared(reservation, deadline);
                return "must-not-escape";
            })).isInstanceOf(LegalIdempotencyException.class);
            verify(reservation).fail(any(RuntimeException.class));
            verify(fixture.jdbc, never()).queryForList(anyString(), any(Object[].class));
            verify(fixture.connection).rollback();
            verify(fixture.connection, never()).commit();
            assertThat(state.snapshot().completion()).isEqualTo(LegalTransactionCompletionState.Completion.ROLLED_BACK);
            assertClean();
        }
    }

    private static void assertClean() {
        assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
    }

    private static final class Fixture implements AutoCloseable {
        final DataSource pool = mock(DataSource.class);
        final Connection connection = mock(Connection.class);
        final AtomicLong clock = new AtomicLong();
        final LegalPrivateRequirementsDataSource bounded;
        final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        final DataSourceTransactionManager manager;
        final TransactionTemplate tx;
        final LegalRegistrationSchemaVerifier schema = mock(LegalRegistrationSchemaVerifier.class);
        final LegalRegistrationPrivilegeVerifier privileges = mock(LegalRegistrationPrivilegeVerifier.class);
        final LegalRegistrationTransactionBoundary boundary;

        Fixture() throws Exception {
            AtomicBoolean autoCommit = new AtomicBoolean(true);
            when(pool.getConnection()).thenReturn(connection);
            when(connection.getAutoCommit()).thenAnswer(call -> autoCommit.get());
            doAnswer(call -> { autoCommit.set(call.getArgument(0)); return null; })
                    .when(connection).setAutoCommit(anyBoolean());
            when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
            bounded = LegalPrivateRequirementsDataSource.registration(pool, clock::get);
            when(jdbc.getDataSource()).thenReturn(bounded);
            when(jdbc.queryForMap(anyString())).thenReturn(java.util.Map.of("isolation", "read committed", "read_only", "off"));
            manager = new DataSourceTransactionManager(bounded);
            manager.setRollbackOnCommitFailure(false);
            tx = new TransactionTemplate(manager);
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            tx.setTimeout(25);
            when(schema.usesJdbc(jdbc)).thenReturn(true);
            when(schema.expectedSchema()).thenReturn("public");
            when(privileges.usesJdbc(jdbc)).thenReturn(true);
            when(privileges.expectedSchema()).thenReturn("public");
            boundary = new LegalRegistrationTransactionBoundary(jdbc, bounded, tx,
                    new LegalDatabaseBudgets(25, 5, 1, 1), schema, privileges);
        }

        @Override public void close() { bounded.close(); }
    }
}
