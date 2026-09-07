package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;

/** Real restricted writes; only the physical COMMIT/cleanup acknowledgement is fault-injected. */
class LegalAcceptanceCommitIT {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_acceptance_commit").withUsername("ordenfix").withPassword("ordenfix");
    private static final long EXPIRED = Duration.ofSeconds(15).toNanos();
    private static LegalAcceptanceServiceITSupport fixture;
    @TempDir Path directory;
    private final AtomicLong clock = new AtomicLong();

    @BeforeAll static void start() { POSTGRES.start(); fixture = new LegalAcceptanceServiceITSupport(POSTGRES); }
    @AfterAll static void stop() { POSTGRES.stop(); }
    @BeforeEach void reset() throws Exception { fixture.reset(directory, getClass()); clock.set(0); }

    @ParameterizedTest @EnumSource(value = Fault.class, names = {"BEFORE_COMMIT", "BEFORE_COMMIT_DEADLINE"})
    void failureBeforeCommitRollsBackAllEvidenceMetadataAndTheResult(Fault fault) throws Exception {
        var actor = fixture.actor(); var command = fixture.command(actor);
        var before = fixture.durableRows(); var observed = new Attempt(fault);
        try (var harness = fixture.harness(source -> instrument(source, observed), clock::get)) {
            Throwable failure = catchThrowable(() -> harness.service().accept(fixture.principal(actor), key(),
                    command.requiredSetRevision(), command.acceptances(), LegalAcceptanceServiceITSupport.metadata()));
            var typed = failure(failure, LegalAcceptanceFailure.Completion.ROLLED_BACK,
                    LegalAcceptanceFailure.Persistence.NOT_PERSISTED);
            assertThat(typed.confirmedReceipt()).isEmpty();
            assertThat(observed.commitCalls).isZero(); assertThat(observed.rollbackCalls).isEqualTo(1);
            assertThat(observed.borrows).isEqualTo(1);
            assertThat(fixture.durableRows()).isEqualTo(before);
        }
        assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
    }

    @ParameterizedTest @EnumSource(value = Fault.class, names = {"COMMIT_SQL_ACK", "COMMIT_RUNTIME_ACK"})
    void aLostCommitAcknowledgementRemainsUnknownAndDoesNotRetryOrRollBack(Fault fault) throws Exception {
        var actor = fixture.actor(); var command = fixture.command(actor); String key = key();
        var before = fixture.durableRows(); var observed = new Attempt(fault);
        try (var harness = fixture.harness(source -> instrument(source, observed), clock::get)) {
            Throwable rejected = catchThrowable(() -> harness.service().accept(fixture.principal(actor), key,
                    command.requiredSetRevision(), command.acceptances(), LegalAcceptanceServiceITSupport.metadata()));
            var typed = failure(rejected, LegalAcceptanceFailure.Completion.UNKNOWN, LegalAcceptanceFailure.Persistence.UNKNOWN);
            assertThat(typed.confirmedReceipt()).isEmpty();
            assertThat(observed.commitCalls).isEqualTo(1); assertThat(observed.rollbackCalls).isZero();
            assertThat(observed.borrows).isEqualTo(1);
            assertThat(fixture.durableRows()).isNotEqualTo(before);
        }
        var committed = fixture.durableRows();
        try (var recovered = fixture.harness()) {
            var replay = recovered.service().accept(fixture.principal(actor), key, command.requiredSetRevision(),
                    command.acceptances(), LegalAcceptanceServiceITSupport.metadata());
            assertThat(replay.replay()).isTrue(); assertThat(replay.kind()).isEqualTo(LegalAcceptanceReceipt.Kind.WITH_ACTS);
            assertThat(recovered.metrics().snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isZero();
            assertThat(fixture.durableRows()).isEqualTo(committed);
        }
        assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
    }

    @Test void failureInsideCommitBeforeServerExecutionIsStillUnknown() throws Exception {
        var actor = fixture.actor(); var command = fixture.command(actor);
        var before = fixture.durableRows(); var observed = new Attempt(Fault.COMMIT_BEFORE_SERVER);
        try (var harness = fixture.harness(source -> instrument(source, observed), clock::get)) {
            Throwable rejected = catchThrowable(() -> harness.service().accept(fixture.principal(actor), key(),
                    command.requiredSetRevision(), command.acceptances(), LegalAcceptanceServiceITSupport.metadata()));
            failure(rejected, LegalAcceptanceFailure.Completion.UNKNOWN, LegalAcceptanceFailure.Persistence.UNKNOWN);
            assertThat(observed.commitCalls).isEqualTo(1); assertThat(observed.rollbackCalls).isZero();
            assertThat(observed.borrows).isEqualTo(1);
            // Only this independent observer knows the connection closed without executing COMMIT.
            assertThat(fixture.durableRows()).isEqualTo(before);
        }
        assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
    }

    @ParameterizedTest @EnumSource(value = Fault.class, names = {
            "AFTER_COMMIT", "AFTER_COMMIT_DEADLINE", "CLOSE_SQL", "CLOSE_RUNTIME", "CLOSE_DEADLINE"})
    void aConfirmedCommitSurvivesDeliveryOrCleanupFailureWithoutAFalseRollback(Fault fault) throws Exception {
        var actor = fixture.actor(); var command = fixture.command(actor); String key = key();
        var observed = new Attempt(fault); LegalAcceptanceReceipt confirmed;
        try (var harness = fixture.harness(source -> instrument(source, observed), clock::get)) {
            Throwable rejected = catchThrowable(() -> harness.service().accept(fixture.principal(actor), key,
                    command.requiredSetRevision(), command.acceptances(), LegalAcceptanceServiceITSupport.metadata()));
            var typed = failure(rejected, LegalAcceptanceFailure.Completion.COMMITTED, LegalAcceptanceFailure.Persistence.PERSISTED);
            confirmed = typed.confirmedReceipt().orElseThrow();
            assertThat(confirmed.replay()).isFalse(); assertThat(confirmed.acceptanceIds()).isNotEmpty();
            assertThat(observed.commitCalls).isEqualTo(1); assertThat(observed.rollbackCalls).isZero();
            assertThat(observed.borrows).isEqualTo(1);
        }
        var committed = fixture.durableRows();
        try (var recovered = fixture.harness()) {
            var replay = recovered.service().accept(fixture.principal(actor), key, command.requiredSetRevision(),
                    command.acceptances(), LegalAcceptanceServiceITSupport.metadata());
            assertThat(replay.replay()).isTrue(); assertThat(replay.lotId()).isEqualTo(confirmed.lotId());
            assertThat(replay.acceptanceIds()).isEqualTo(confirmed.acceptanceIds());
            assertThat(recovered.metrics().snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isZero();
            assertThat(fixture.durableRows()).isEqualTo(committed);
        }
        assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
    }

    private static LegalAcceptanceFailure failure(Throwable rejected, LegalAcceptanceFailure.Completion completion,
                                                   LegalAcceptanceFailure.Persistence persistence) {
        assertThat(rejected).isInstanceOf(LegalAcceptanceFailure.class);
        var typed = (LegalAcceptanceFailure) rejected;
        assertThat(typed.reason()).isEqualTo(LegalAcceptanceFailure.Reason.UNAVAILABLE);
        assertThat(typed.completion()).isEqualTo(completion); assertThat(typed.persistence()).isEqualTo(persistence);
        return typed;
    }

    private DataSource instrument(DataSource source, Attempt observed) {
        return new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException { return connection(source.getConnection(), observed); }
            @Override public Connection getConnection(String user, String password) throws SQLException {
                throw new SQLFeatureNotSupportedException("Fixed test credential");
            }
        };
    }

    private Connection connection(Connection delegate, Attempt observed) {
        observed.borrows++; AtomicBoolean registered = new AtomicBoolean();
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                        case "equals" -> proxy == arguments[0]; case "hashCode" -> System.identityHashCode(proxy);
                        case "toString" -> "AcceptanceCommitObservation";
                        default -> throw new IllegalStateException("Unexpected Object method");
                    };
                    if (TransactionSynchronizationManager.isSynchronizationActive() && registered.compareAndSet(false, true)) {
                        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                            @Override public void beforeCommit(boolean readOnly) {
                                if (observed.fault == Fault.BEFORE_COMMIT) throw new IllegalStateException("Synthetic precommit failure");
                            }
                            @Override public void beforeCompletion() {
                                if (observed.fault == Fault.BEFORE_COMMIT_DEADLINE) clock.set(EXPIRED);
                            }
                            @Override public void afterCommit() {
                                if (observed.fault == Fault.AFTER_COMMIT) throw new IllegalStateException("Synthetic delivery failure");
                            }
                            @Override public void afterCompletion(int status) { observed.completions.add(status); }
                        });
                    }
                    if (method.getName().equals("commit")) {
                        observed.commitCalls++;
                        if (observed.fault == Fault.COMMIT_BEFORE_SERVER) {
                            delegate.close(); // Prevent cleanup's setAutoCommit(true) from confirming this transaction.
                            throw new SQLException("Synthetic connection loss before server COMMIT", "08006");
                        }
                        Object result = invoke(delegate, method, arguments);
                        if (observed.fault == Fault.COMMIT_SQL_ACK) throw new SQLException("Synthetic lost acknowledgement", "08006");
                        if (observed.fault == Fault.COMMIT_RUNTIME_ACK) throw new IllegalStateException("Synthetic unchecked acknowledgement failure");
                        if (observed.fault == Fault.AFTER_COMMIT_DEADLINE) clock.set(EXPIRED);
                        return result;
                    }
                    if (method.getName().equals("rollback") && method.getParameterCount() == 0) observed.rollbackCalls++;
                    Object result = invoke(delegate, method, arguments);
                    if (method.getName().equals("close")) {
                        if (observed.fault == Fault.CLOSE_SQL) throw new SQLException("Synthetic error after physical close", "08006");
                        if (observed.fault == Fault.CLOSE_RUNTIME) throw new IllegalStateException("Synthetic unchecked close error");
                        if (observed.fault == Fault.CLOSE_DEADLINE) clock.set(EXPIRED);
                    }
                    return result;
                });
    }

    private static Object invoke(Object target, Method method, Object[] arguments) throws Throwable {
        try { return method.invoke(target, arguments); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    private static String key() { return UUID.randomUUID().toString(); }
    private enum Fault { BEFORE_COMMIT, BEFORE_COMMIT_DEADLINE, COMMIT_BEFORE_SERVER, COMMIT_SQL_ACK,
        COMMIT_RUNTIME_ACK, AFTER_COMMIT, AFTER_COMMIT_DEADLINE, CLOSE_SQL, CLOSE_RUNTIME, CLOSE_DEADLINE }
    private static final class Attempt {
        final Fault fault; final List<Integer> completions = new ArrayList<>();
        int borrows; int commitCalls; int rollbackCalls;
        Attempt(Fault fault) { this.fault = fault; }
    }
}
