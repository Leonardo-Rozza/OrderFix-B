package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceInput;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceInputException;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalActorSnapshot;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceServiceITSupport.Harness;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.AbstractDataSource;
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
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceServiceITSupport.key;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceServiceITSupport.metadata;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceServiceITSupport.principal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** Deferred input on the real restricted graph; physical completion faults stay local to this test. */
class LegalAcceptanceDeferredPayloadIT {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_acceptance_deferred").withUsername("ordenfix").withPassword("ordenfix");
    private static final long EXPIRED = Duration.ofSeconds(15).toNanos();
    private static LegalAcceptanceServiceITSupport fixture;
    @TempDir Path directory;
    private final AtomicLong clock = new AtomicLong();

    @BeforeAll static void start() { POSTGRES.start(); fixture = new LegalAcceptanceServiceITSupport(POSTGRES); }
    @AfterAll static void stop() { POSTGRES.stop(); }
    @BeforeEach void reset() throws Exception { fixture.reset(directory, getClass()); clock.set(0); }
    @AfterEach void noLeakedTransaction() { assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty(); }

    @ParameterizedTest @ValueSource(strings = {"null", "disabled"})
    void invalidPrincipalCannotInvokeTheInputReaderOrBorrowAConnection(String variant) {
        var actor = fixture.actor();
        var identity = variant.equals("null") ? null : principal(new LegalActorSnapshot(actor.userId(),
                actor.tallerId(), actor.role(), actor.tokenVersion(), false, true));
        var before = fixture.durableRows(); var calls = new AtomicInteger(); var probe = new Probe(Fault.NONE);
        try (var harness = fixture.harness(source -> instrument(source, probe), clock::get)) {
            Throwable thrown = catchThrowable(() -> harness.service().accept(identity, checkpoint -> {
                calls.incrementAndGet(); throw new AssertionError("Una identidad inválida no debe leer el payload");
            }));
            failure(thrown, LegalAcceptanceFailure.Reason.INVALID_ACTOR,
                    LegalAcceptanceFailure.Completion.NONE, LegalAcceptanceFailure.Persistence.NOT_PERSISTED);
            assertThat(calls).hasValue(0); assertThat(probe.borrows).isZero();
            assertNoDml(harness); assertThat(fixture.durableRows()).isEqualTo(before);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"user", "workshop", "token", "role", "tenant"})
    void persistedActorRejectionPrecedesEveryAttemptToConsumeThePayload(String changed) {
        var actor = fixture.actor(); var other = fixture.actor(); var identity = principal(actor);
        revoke(actor, other, changed);
        var before = fixture.durableRows(); var calls = new AtomicInteger();
        try (var harness = fixture.harness()) {
            Throwable thrown = catchThrowable(() -> harness.service().accept(identity, checkpoint -> {
                calls.incrementAndGet(); throw new AssertionError("La observación del actor debe preceder al payload");
            }));
            var rejected = failure(thrown, LegalAcceptanceFailure.Reason.INVALID_ACTOR,
                    LegalAcceptanceFailure.Completion.ROLLED_BACK, LegalAcceptanceFailure.Persistence.NOT_PERSISTED);
            assertThat(rejected.inputReason()).isEmpty(); assertThat(calls).hasValue(0);
            assertNoDml(harness); assertThat(harness.metrics().snapshot().rollbacks()).isEqualTo(1);
            assertThat(fixture.durableRows()).isEqualTo(before);
        }
    }

    @Test void readerRunsOnceInsideTheRestrictedTransactionBeforeIdempotencyAndAgainOnceForReplay() throws Exception {
        var actor = fixture.actor(); var command = fixture.command(actor); String requestKey = key();
        var calls = new AtomicInteger();
        try (var harness = fixture.harness()) {
            LegalAcceptanceInput.Reader reader = checkpoint -> {
                calls.incrementAndGet(); checkpoint.run();
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isTrue();
                assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
                assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                        .isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
                assertNoDml(harness);
                assertThat(harness.metrics().snapshot().executions(LegalJdbcMetricsSupport.Category.ADVISORY_LOCK)).isZero();
                var effective = harness.jdbc().queryForMap("""
                        SELECT current_user AS actor_role, current_setting('transaction_isolation') AS isolation,
                               current_setting('transaction_read_only') AS read_only
                        """);
                assertThat(effective).containsEntry("actor_role", LegalAcceptanceServiceITSupport.ROLE)
                        .containsEntry("isolation", "read committed").containsEntry("read_only", "off");
                var drafts = new ArrayList<>(command.acceptances());
                var detached = new LegalAcceptanceInput(requestKey, command.requiredSetRevision(), drafts, metadata());
                drafts.clear(); // Mutating the caller's draft cannot change the command selected for persistence.
                return detached;
            };
            var committed = harness.service().accept(principal(actor), reader);
            assertThat(calls).hasValue(1); assertThat(committed.replay()).isFalse();
            assertThat(committed.kind()).isEqualTo(LegalAcceptanceReceipt.Kind.WITH_ACTS);
            assertThat(committed.acceptanceIds()).hasSize(command.acceptances().size());
            var rows = fixture.durableRows(); harness.metrics().reset();
            var replay = harness.service().accept(principal(actor), reader);
            assertThat(calls).hasValue(2); assertThat(replay.replay()).isTrue();
            assertThat(replay.lotId()).isEqualTo(committed.lotId());
            assertThat(replay.acceptanceIds()).isEqualTo(committed.acceptanceIds());
            assertNoDml(harness); assertThat(fixture.durableRows()).isEqualTo(rows);
        }
    }

    @ParameterizedTest @EnumSource(LegalAcceptanceInputException.Reason.class)
    void typedInputRejectionSurvivesOnlyAnAccreditedRollback(LegalAcceptanceInputException.Reason reason) {
        var actor = fixture.actor(); var before = fixture.durableRows(); var calls = new AtomicInteger();
        try (var harness = fixture.harness()) {
            var rejected = failure(catchThrowable(() -> harness.service().accept(principal(actor), checkpoint -> {
                calls.incrementAndGet(); checkpoint.run(); throw new LegalAcceptanceInputException(reason);
            })), LegalAcceptanceFailure.Reason.INVALID_PAYLOAD, LegalAcceptanceFailure.Completion.ROLLED_BACK,
                    LegalAcceptanceFailure.Persistence.NOT_PERSISTED);
            assertThat(rejected.inputReason()).contains(reason); assertThat(rejected.validation()).isEmpty();
            assertThat(calls).hasValue(1); assertThat(harness.metrics().snapshot().rollbacks()).isEqualTo(1);
            assertNoDml(harness); assertThat(fixture.durableRows()).isEqualTo(before);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"reader", "result", "metadata", "unexpected", "domain-actor", "domain-validation"})
    void missingServerInputOrUnexpectedReaderFailureIsUnavailableWithoutBusinessWrites(String variant) throws Exception {
        var actor = fixture.actor(); var command = fixture.command(actor); var before = fixture.durableRows();
        var calls = new AtomicInteger();
        LegalAcceptanceInput.Reader reader = variant.equals("reader") ? null : checkpoint -> {
            calls.incrementAndGet();
            return switch (variant) {
                case "result" -> null;
                case "metadata" -> new LegalAcceptanceInput(key(), command.requiredSetRevision(), command.acceptances(), null);
                case "unexpected" -> throw new IllegalStateException("Synthetic unexpected parser failure");
                case "domain-actor" -> throw new LegalActorSnapshotException();
                case "domain-validation" -> throw LegalAcceptanceValidationException.invalid(
                        List.of(LegalAcceptanceValidationException.Motivo.CONFIRMACION_REQUERIDA));
                default -> throw new AssertionError(variant);
            };
        };
        try (var harness = fixture.harness()) {
            var rejected = failure(catchThrowable(() -> harness.service().accept(principal(actor), reader)),
                    LegalAcceptanceFailure.Reason.UNAVAILABLE, LegalAcceptanceFailure.Completion.ROLLED_BACK,
                    LegalAcceptanceFailure.Persistence.NOT_PERSISTED);
            assertThat(rejected.inputReason()).isEmpty(); assertThat(calls).hasValue(variant.equals("reader") ? 0 : 1);
            assertNoDml(harness); assertThat(fixture.durableRows()).isEqualTo(before);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"user", "workshop", "token", "role", "tenant"})
    void actorRevokedDuringTheReaderIsRecheckedUnderLocksBeforeAnyBusinessWrite(String changed) throws Exception {
        var actor = fixture.actor(); var other = fixture.actor(); var command = fixture.command(actor);
        var afterRevocation = new AtomicReference<Map<String, List<String>>>(); var calls = new AtomicInteger();
        try (var harness = fixture.harness()) {
            var rejected = failure(catchThrowable(() -> harness.service().accept(principal(actor), checkpoint -> {
                calls.incrementAndGet(); checkpoint.run(); revoke(actor, other, changed);
                afterRevocation.set(fixture.durableRows());
                return input(command, key());
            })), LegalAcceptanceFailure.Reason.INVALID_ACTOR, LegalAcceptanceFailure.Completion.ROLLED_BACK,
                    LegalAcceptanceFailure.Persistence.NOT_PERSISTED);
            assertThat(rejected.inputReason()).isEmpty(); assertThat(calls).hasValue(1);
            assertNoDml(harness); assertThat(fixture.durableRows()).isEqualTo(afterRevocation.get());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"returned", "checkpoint", "rejected"})
    void aReaderCannotTurnAnExpiredOperationIntoPersistenceOrAClientRejection(String end) throws Exception {
        var actor = fixture.actor(); var command = fixture.command(actor); var before = fixture.durableRows();
        var calls = new AtomicInteger();
        try (var harness = fixture.harness(source -> source, clock::get)) {
            var rejected = failure(catchThrowable(() -> harness.service().accept(principal(actor), checkpoint -> {
                calls.incrementAndGet(); checkpoint.run(); clock.set(EXPIRED);
                if (end.equals("checkpoint")) checkpoint.run();
                if (end.equals("rejected")) throw new LegalAcceptanceInputException(LegalAcceptanceInputException.Reason.INVALID_KEY);
                return input(command, key());
            })), LegalAcceptanceFailure.Reason.UNAVAILABLE, LegalAcceptanceFailure.Completion.ROLLED_BACK,
                    LegalAcceptanceFailure.Persistence.NOT_PERSISTED);
            assertThat(calls).hasValue(1); assertThat(rejected.inputReason()).isEmpty();
            assertNoDml(harness); assertThat(fixture.durableRows()).isEqualTo(before);
        }
    }

    @ParameterizedTest @EnumSource(value = Fault.class, names = {"CLOSE_ACK", "ROLLBACK_ACK"})
    void inputRejectionCannotHideCleanupFailureOrAnUncertainRollback(Fault fault) {
        var actor = fixture.actor(); var before = fixture.durableRows(); var probe = new Probe(fault);
        try (var harness = fixture.harness(source -> instrument(source, probe), clock::get)) {
            var completion = fault == Fault.ROLLBACK_ACK ? LegalAcceptanceFailure.Completion.UNKNOWN
                    : LegalAcceptanceFailure.Completion.ROLLED_BACK;
            var persistence = fault == Fault.ROLLBACK_ACK ? LegalAcceptanceFailure.Persistence.UNKNOWN
                    : LegalAcceptanceFailure.Persistence.NOT_PERSISTED;
            var rejected = failure(catchThrowable(() -> harness.service().accept(principal(actor), checkpoint -> {
                throw new LegalAcceptanceInputException(LegalAcceptanceInputException.Reason.REQUIRED_KEY);
            })), LegalAcceptanceFailure.Reason.UNAVAILABLE, completion, persistence);
            assertThat(rejected.inputReason()).isEmpty(); assertThat(probe.borrows).isEqualTo(1);
            assertThat(probe.commits).isZero(); assertThat(probe.rollbacks).isEqualTo(1);
            assertNoDml(harness); assertThat(fixture.durableRows()).isEqualTo(before);
        }
    }

    @Test void lostCommitAcknowledgementPreservesUncertaintyAndRecoversThroughOneDeferredReplay() throws Exception {
        var actor = fixture.actor(); var command = fixture.command(actor); String requestKey = key();
        var before = fixture.durableRows(); var probe = new Probe(Fault.COMMIT_ACK); var calls = new AtomicInteger();
        try (var harness = fixture.harness(source -> instrument(source, probe), clock::get)) {
            var rejected = failure(catchThrowable(() -> harness.service().accept(principal(actor), checkpoint -> {
                calls.incrementAndGet(); return input(command, requestKey);
            })), LegalAcceptanceFailure.Reason.UNAVAILABLE, LegalAcceptanceFailure.Completion.UNKNOWN,
                    LegalAcceptanceFailure.Persistence.UNKNOWN);
            assertThat(rejected.inputReason()).isEmpty(); assertThat(calls).hasValue(1);
            assertThat(probe.commits).isEqualTo(1); assertThat(probe.rollbacks).isZero();
            assertThat(probe.borrows).isEqualTo(1); assertThat(fixture.durableRows()).isNotEqualTo(before);
        }
        var committed = fixture.durableRows();
        try (var recovered = fixture.harness()) {
            var replay = recovered.service().accept(principal(actor), checkpoint -> {
                calls.incrementAndGet(); return input(command, requestKey);
            });
            assertThat(calls).hasValue(2); assertThat(replay.replay()).isTrue();
            assertThat(replay.kind()).isEqualTo(LegalAcceptanceReceipt.Kind.WITH_ACTS);
            assertNoDml(recovered); assertThat(fixture.durableRows()).isEqualTo(committed);
        }
    }

    private static LegalAcceptanceInput input(LegalAcceptanceCommand command, String requestKey) {
        return new LegalAcceptanceInput(requestKey, command.requiredSetRevision(), command.acceptances(), metadata());
    }

    private static void revoke(LegalActorSnapshot actor, LegalActorSnapshot other, String changed) {
        switch (changed) {
            case "user" -> fixture.owner.update("UPDATE users SET active=false WHERE id=?", actor.userId());
            case "workshop" -> fixture.owner.update("UPDATE talleres SET activo=false WHERE id=?", actor.tallerId());
            case "token" -> fixture.owner.update("UPDATE users SET token_version=token_version+1 WHERE id=?", actor.userId());
            case "role" -> fixture.owner.update("UPDATE users SET role='ADMIN' WHERE id=?", actor.userId());
            case "tenant" -> fixture.owner.update("UPDATE users SET taller_id=? WHERE id=?", other.tallerId(), actor.userId());
            default -> throw new AssertionError(changed);
        }
    }

    private static LegalAcceptanceFailure failure(Throwable thrown, LegalAcceptanceFailure.Reason reason,
            LegalAcceptanceFailure.Completion completion, LegalAcceptanceFailure.Persistence persistence) {
        assertThat(thrown).isInstanceOf(LegalAcceptanceFailure.class);
        var rejected = (LegalAcceptanceFailure) thrown;
        assertThat(rejected.reason()).isEqualTo(reason); assertThat(rejected.completion()).isEqualTo(completion);
        assertThat(rejected.persistence()).isEqualTo(persistence); assertThat(rejected.confirmedReceipt()).isEmpty();
        return rejected;
    }

    private static void assertNoDml(Harness harness) {
        assertThat(harness.metrics().snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isZero();
    }

    private static DataSource instrument(DataSource source, Probe probe) {
        return new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException {
                Connection delegate = source.getConnection(); probe.borrows++;
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                        (proxy, method, arguments) -> {
                            if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                                case "equals" -> proxy == arguments[0]; case "hashCode" -> System.identityHashCode(proxy);
                                case "toString" -> "DeferredAcceptanceCompletionProbe";
                                default -> throw new IllegalStateException("Unexpected Object method");
                            };
                            String operation = method.getName();
                            if (operation.equals("commit")) probe.commits++;
                            if (operation.equals("rollback") && method.getParameterCount() == 0) probe.rollbacks++;
                            Object result = invoke(delegate, method, arguments);
                            if (operation.equals("commit") && probe.fault == Fault.COMMIT_ACK
                                    || operation.equals("rollback") && method.getParameterCount() == 0 && probe.fault == Fault.ROLLBACK_ACK
                                    || operation.equals("close") && probe.fault == Fault.CLOSE_ACK) {
                                throw new SQLException("Synthetic lost completion acknowledgement", "08006");
                            }
                            return result;
                        });
            }
            @Override public Connection getConnection(String user, String password) throws SQLException {
                throw new SQLFeatureNotSupportedException("Fixed fixture credential");
            }
        };
    }

    private static Object invoke(Object target, Method method, Object[] arguments) throws Throwable {
        try { return method.invoke(target, arguments); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    private enum Fault { NONE, CLOSE_ACK, ROLLBACK_ACK, COMMIT_ACK }
    private static final class Probe {
        final Fault fault; int borrows; int commits; int rollbacks;
        Probe(Fault fault) { this.fault = fault; }
    }
}
