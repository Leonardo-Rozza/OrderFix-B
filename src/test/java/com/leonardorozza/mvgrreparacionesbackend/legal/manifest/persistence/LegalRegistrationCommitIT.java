package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalRegistrationServiceITSupport.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalRegistrationServiceITSupport.*;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalRegistrationServiceIT.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** Commit/cleanup fault injection surrounds real PostgreSQL writes; durable replay is separate evidence. */
class LegalRegistrationCommitIT {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_registration_commit").withUsername("ordenfix").withPassword("ordenfix");
    private static LegalRegistrationServiceITSupport fixture;
    @TempDir Path directory;

    @BeforeAll static void start() { POSTGRES.start(); fixture = new LegalRegistrationServiceITSupport(POSTGRES); }
    @AfterAll static void stop() { POSTGRES.stop(); }
    @BeforeEach void reset() throws Exception { fixture.reset(directory, getClass()); }
    @AfterEach void resourcesReleased() { assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty(); }

    @ParameterizedTest @EnumSource(value = Fault.class, names = {"BEFORE_COMMIT", "BEFORE_COMMIT_DEADLINE"})
    void failureBeforePhysicalCommitRollsBackTheEntireRegistration(Fault fault) throws Exception {
        var request = fixture.request(); var before = fixture.rows(); var probe = new Probe(); probe.fault = fault;
        try (var h = fixture.harness(probe)) {
            rolledBack(catchThrowable(() -> h.register(request, key())), LegalRegistrationFailure.Reason.UNAVAILABLE);
            assertThat(probe.commits).hasValue(0); assertThat(probe.rollbacks).hasValue(1);
            assertThat(probe.borrows).hasValue(1); assertThat(probe.hashes).hasValue(1);
        }
        assertThat(fixture.rows()).isEqualTo(before);
    }

    @ParameterizedTest @EnumSource(value = Fault.class, names = {"COMMIT_BEFORE_SERVER", "COMMIT_SQL_ACK", "COMMIT_RUNTIME_ACK"})
    void anInvokedButUnacknowledgedCommitIsUnknownWithoutAnAutomaticRetry(Fault fault) throws Exception {
        var request = fixture.request(); String key = key(); var before = fixture.rows(); var probe = new Probe(); probe.fault = fault;
        try (var h = fixture.harness(probe)) {
            var failure = failure(catchThrowable(() -> h.register(request, key)), LegalRegistrationFailure.Reason.UNAVAILABLE);
            assertThat(failure.completion()).isEqualTo(LegalRegistrationFailure.Completion.UNKNOWN);
            assertThat(failure.persistence()).isEqualTo(LegalRegistrationFailure.Persistence.UNKNOWN);
            assertThat(failure.confirmedReceipt()).isEmpty(); assertThat(failure.validation()).isEmpty();
            assertThat(probe.commits).hasValue(1); assertThat(probe.rollbacks).hasValue(0); assertThat(probe.borrows).hasValue(1);
        }
        if (fault == Fault.COMMIT_BEFORE_SERVER) {
            // Only this independent observer knows that the server did not receive COMMIT.
            assertThat(fixture.rows()).isEqualTo(before);
        } else {
            var committed = fixture.rows(); assertThat(committed).isNotEqualTo(before);
            try (var recovered = fixture.harness()) {
                var replay = recovered.register(request, key); assertThat(replay.replay()).isTrue();
                assertNoDml(recovered); assertThat(recovered.probe().hashes).hasValue(0);
            }
            assertThat(fixture.rows()).isEqualTo(committed);
        }
    }

    @ParameterizedTest @EnumSource(value = Fault.class, names = {
            "AFTER_COMMIT", "AFTER_COMMIT_DEADLINE", "CLOSE_SQL", "CLOSE_RUNTIME", "CLOSE_DEADLINE"})
    void confirmedCommitKeepsTheReceiptWhenDeliveryOrCleanupFails(Fault fault) throws Exception {
        var request = fixture.request(); String key = key(); var probe = new Probe(); probe.fault = fault;
        LegalRegistrationReceipt original;
        try (var h = fixture.harness(probe)) {
            var failure = failure(catchThrowable(() -> h.register(request, key)), LegalRegistrationFailure.Reason.UNAVAILABLE);
            assertThat(failure.completion()).isEqualTo(LegalRegistrationFailure.Completion.COMMITTED);
            assertThat(failure.persistence()).isEqualTo(LegalRegistrationFailure.Persistence.PERSISTED);
            original = failure.confirmedReceipt().orElseThrow(); assertThat(original.replay()).isFalse();
            assertThat(probe.commits).hasValue(1); assertThat(probe.rollbacks).hasValue(0);
            assertThat(probe.borrows).hasValue(1); assertThat(failure.validation()).isEmpty();
        }
        var committed = fixture.rows();
        try (var recovered = fixture.harness()) {
            var replay = recovered.register(request, key); sameIdentity(replay, original);
            assertThat(replay.replay()).isTrue(); assertNoDml(recovered); assertThat(recovered.probe().hashes).hasValue(0);
        }
        assertThat(fixture.rows()).isEqualTo(committed);
    }

    @ParameterizedTest @EnumSource(value = Fault.class, names = {"BEFORE_COMMIT", "COMMIT_SQL_ACK", "CLOSE_SQL"})
    void independentlyVerifiedReplaySurvivesRollbackUnknownCommitAndCleanupFailure(Fault fault) throws Exception {
        var request = fixture.request(); String key = key(); LegalRegistrationReceipt original;
        try (var h = fixture.harness()) { original = h.register(request, key); }
        var before = fixture.rows(); var probe = new Probe(); probe.fault = fault;
        try (var h = fixture.harness(probe)) {
            var failure = failure(catchThrowable(() -> h.register(request, key)), LegalRegistrationFailure.Reason.UNAVAILABLE);
            var expectedCompletion = switch (fault) {
                case BEFORE_COMMIT -> LegalRegistrationFailure.Completion.ROLLED_BACK;
                case COMMIT_SQL_ACK -> LegalRegistrationFailure.Completion.UNKNOWN;
                case CLOSE_SQL -> LegalRegistrationFailure.Completion.COMMITTED;
                default -> throw new AssertionError("Unexpected replay fault");
            };
            assertThat(failure.completion()).isEqualTo(expectedCompletion);
            assertThat(failure.persistence()).isEqualTo(LegalRegistrationFailure.Persistence.PERSISTED);
            var replay = failure.confirmedReceipt().orElseThrow(); assertThat(replay.replay()).isTrue(); sameIdentity(replay, original);
            assertNoDml(h); assertThat(probe.hashes).hasValue(0);
            assertThat(probe.commits).hasValue(fault == Fault.BEFORE_COMMIT ? 0 : 1);
            assertThat(probe.rollbacks).hasValue(fault == Fault.BEFORE_COMMIT ? 1 : 0);
        }
        assertThat(fixture.rows()).isEqualTo(before);
    }

    @ParameterizedTest @EnumSource(value = Fault.class, names = {"CLOSE_SQL", "ROLLBACK_ACK", "CLOSE_DEADLINE"})
    void cleanupOrRollbackFailureDominatesAClientSemanticRejection(Fault fault) throws Exception {
        var original = fixture.request(); var request = withAcceptances(original, original.command().requiredSetRevision(), List.of());
        var before = fixture.rows(); var probe = new Probe(); probe.fault = fault;
        try (var h = fixture.harness(probe)) {
            var failure = failure(catchThrowable(() -> h.register(request, key())), LegalRegistrationFailure.Reason.UNAVAILABLE);
            assertThat(failure.confirmedReceipt()).isEmpty();
            if (fault == Fault.ROLLBACK_ACK) {
                assertThat(failure.completion()).isEqualTo(LegalRegistrationFailure.Completion.UNKNOWN);
                assertThat(failure.persistence()).isEqualTo(LegalRegistrationFailure.Persistence.UNKNOWN);
            } else {
                assertThat(failure.completion()).isEqualTo(LegalRegistrationFailure.Completion.ROLLED_BACK);
                assertThat(failure.persistence()).isEqualTo(LegalRegistrationFailure.Persistence.NOT_PERSISTED);
            }
            assertThat(probe.commits).hasValue(0); assertThat(probe.rollbacks).hasValue(1); assertThat(probe.hashes).hasValue(0);
        }
        assertThat(fixture.rows()).isEqualTo(before);
    }

    @Test void registrationReaderContinuesAfterFifteenSecondsUsingTheOriginalThirtySecondBudget() throws Exception {
        var request = fixture.request(); var probe = new Probe(); var advanced = new AtomicBoolean();
        probe.afterSql = sql -> {
            if (sql.contains("insert into legal_requisito_agregados") && advanced.compareAndSet(false, true))
                probe.offsetNanos.set(Duration.ofSeconds(16).toNanos());
        };
        try (var h = fixture.harness(probe)) {
            assertThat(h.register(request, key()).replay()).isFalse();
            assertThat(advanced).isTrue(); assertThat(probe.hashes).hasValue(1); assertThat(probe.commits).hasValue(1);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"reader", "hash"})
    void expirationDuringReaderMetadataOrHashStopsBeforeBusinessWritesAndRollsBack(String phase) throws Exception {
        var request = fixture.request(); var before = fixture.rows(); var probe = new Probe(); var expired = new AtomicBoolean();
        if (phase.equals("hash")) probe.afterHash = () -> { expired.set(true); probe.expire(); };
        else probe.afterSql = sql -> {
            if (sql.contains("as markdown_octets") && expired.compareAndSet(false, true)) probe.expire();
        };
        try (var h = fixture.harness(probe)) {
            rolledBack(catchThrowable(() -> h.register(request, key())), LegalRegistrationFailure.Reason.UNAVAILABLE);
            assertThat(expired).isTrue(); assertThat(probe.commits).hasValue(0); assertThat(probe.rollbacks).hasValue(1);
            assertThat(probe.inserts("talleres")).isZero(); assertThat(probe.hashes).hasValue(phase.equals("hash") ? 1 : 0);
        }
        assertThat(fixture.rows()).isEqualTo(before);
    }
}
