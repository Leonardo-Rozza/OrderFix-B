package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalRegistrationServiceITSupport.*;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalRegistrationWriterITSupport.Request;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalRegistrationServiceITSupport.*;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalRegistrationServiceIT.*;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalRegistrationWriterITSupport.metadata;
import static org.assertj.core.api.Assertions.*;

/** A deterministic owner clock surrounds real restricted PostgreSQL transactions and commit evidence. */
class LegalRegistrationSharedBudgetIT {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_registration_shared_budget").withUsername("ordenfix").withPassword("ordenfix");
    private static LegalRegistrationServiceITSupport fixture;
    @TempDir Path directory;

    @BeforeAll static void start() { POSTGRES.start(); fixture = new LegalRegistrationServiceITSupport(POSTGRES); }
    @AfterAll static void stop() { POSTGRES.stop(); }
    @BeforeEach void reset() throws Exception { fixture.reset(directory, getClass()); }
    @AfterEach void resourcesReleased() { assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty(); }

    @Test void timeConsumedBeforeAdoptionLimitsTheActualPostgresStatementTimeout() throws Exception {
        var clock = new AtomicLong(); var budget = LegalRegistrationBudget.start(clock::get);
        var request = fixture.request(); var timeout = new AtomicInteger();
        // The pool's own clock has not advanced. Resetting at service entry would allow 5000ms SQL.
        clock.set(28_000_000_000L);
        try (var h = fixture.harness()) {
            h.probe().afterHash = () -> {
                timeout.set(h.jdbc().queryForObject("SELECT (EXTRACT(EPOCH FROM current_setting('statement_timeout')::interval) * 1000)::int", Integer.class));
                assertThat(h.jdbc().queryForObject("SELECT current_user", String.class))
                        .isEqualTo(LegalRegistrationWriterITSupport.ROLE);
            };

            var receipt = register(h, request, key(), budget);

            assertThat(receipt.replay()).isFalse(); assertThat(timeout).hasValue(2_000);
            assertThat(budget.remainingMillis()).isEqualTo(2_000);
            assertThat(h.probe().borrows).hasValue(1); assertThat(h.probe().commits).hasValue(1);
            assertThat(h.probe().hashes).hasValue(1); assertThat(h.probe().rollbacks).hasValue(0);
            assertThat(fixture.owner.queryForObject("SELECT count(*) FROM users WHERE id = ? AND taller_id = ?",
                    Long.class, receipt.userId(), receipt.tallerId())).isEqualTo(1);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"null", "expired", "less-than-borrow", "cleanup"})
    void invalidOrExhaustedOwnerCannotBorrowOrStartARegistration(String defect) throws Exception {
        var clock = new AtomicLong(); var owner = LegalRegistrationBudget.start(clock::get);
        var request = fixture.request(); var before = fixture.rows();
        switch (defect) {
            case "expired" -> clock.set(30_000_000_000L);
            case "less-than-borrow" -> clock.set(29_001_000_000L);
            case "cleanup" -> owner.recordCleanupFailure(new SQLException("Synthetic earlier phase close failure"));
            case "null" -> { }
            default -> throw new AssertionError(defect);
        }
        var supplied = defect.equals("null") ? null : owner;
        try (var h = fixture.harness()) {
            assertNoAttempt(catchThrowable(() -> register(h, request, key(), supplied)));
            assertThat(h.probe().borrows).hasValue(0); assertThat(h.probe().hashes).hasValue(0);
            assertThat(h.probe().sql).isEmpty(); assertThat(h.probe().commits).hasValue(0);
            assertThat(h.probe().rollbacks).hasValue(0);
        }
        assertThat(fixture.rows()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"reader", "hash"})
    void externalExpiryDuringWorkRollsBackAndTheHistoricalApiDoesNotInheritItsOwner(String phase) throws Exception {
        var clock = new AtomicLong(); var budget = LegalRegistrationBudget.start(clock::get);
        var request = fixture.request(); var before = fixture.rows(); String requestKey = key();
        var expired = new AtomicBoolean();
        try (var h = fixture.harness()) {
            if (phase.equals("hash")) h.probe().afterHash = () -> { expired.set(true); clock.set(30_000_000_000L); };
            else h.probe().afterSql = sql -> {
                if (sql.contains("as markdown_octets") && expired.compareAndSet(false, true)) clock.set(30_000_000_000L);
            };

            rolledBack(catchThrowable(() -> register(h, request, requestKey, budget)), LegalRegistrationFailure.Reason.UNAVAILABLE);

            assertThat(expired).isTrue(); assertThat(h.probe().commits).hasValue(0); assertThat(h.probe().rollbacks).hasValue(1);
            assertThat(h.probe().inserts("talleres")).isZero(); assertThat(fixture.rows()).isEqualTo(before);
            assertThatThrownBy(budget::check).isInstanceOf(LegalRegistrationBudget.UnavailableException.class);
            h.probe().afterSql = ignored -> {}; h.probe().afterHash = () -> {};

            var recovered = h.register(request, requestKey);

            assertThat(recovered.replay()).isFalse(); assertThat(h.probe().commits).hasValue(1);
            assertThat(h.probe().borrows).hasValue(2); assertThat(h.probe().inserts("users")).isEqualTo(1);
            assertThatThrownBy(budget::check).isInstanceOf(LegalRegistrationBudget.UnavailableException.class);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void expiryAfterRealCommitKeepsFreshOrReplayEvidenceWithoutUndoingPersistence(boolean replay) throws Exception {
        var request = fixture.request(); String requestKey = key(); LegalRegistrationReceipt original = null;
        if (replay) try (var initial = fixture.harness()) { original = initial.register(request, requestKey); }
        var before = fixture.rows(); var clock = new AtomicLong(); var budget = LegalRegistrationBudget.start(clock::get);
        var installed = new AtomicBoolean(); var callback = new AtomicBoolean(); LegalRegistrationReceipt confirmed;
        try (var h = fixture.harness()) {
            h.probe().beforeSql = sql -> {
                if (TransactionSynchronizationManager.isSynchronizationActive() && installed.compareAndSet(false, true)) {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override public void afterCommit() { callback.set(true); clock.set(30_000_000_000L); }
                    });
                }
            };

            var failure = failure(catchThrowable(() -> register(h, request, requestKey, budget)), LegalRegistrationFailure.Reason.UNAVAILABLE);

            assertThat(callback).isTrue(); assertThat(failure.completion()).isEqualTo(LegalRegistrationFailure.Completion.COMMITTED);
            assertThat(failure.persistence()).isEqualTo(LegalRegistrationFailure.Persistence.PERSISTED);
            confirmed = failure.confirmedReceipt().orElseThrow(); assertThat(confirmed.replay()).isEqualTo(replay);
            if (replay) { sameIdentity(confirmed, original); assertNoDml(h); }
            assertThat(h.probe().commits).hasValue(1); assertThat(h.probe().rollbacks).hasValue(0);
            assertThat(h.probe().hashes).hasValue(replay ? 0 : 1);
            assertThatThrownBy(budget::check).isInstanceOf(LegalRegistrationBudget.UnavailableException.class);
        }
        if (replay) assertThat(fixture.rows()).isEqualTo(before);
        var committed = fixture.rows();
        try (var freshScope = fixture.harness()) {
            var recovered = register(freshScope, request, requestKey, LegalRegistrationBudget.start());
            sameIdentity(recovered, confirmed); assertThat(recovered.replay()).isTrue(); assertNoDml(freshScope);
            assertThat(freshScope.probe().hashes).hasValue(0);
        }
        assertThat(fixture.rows()).isEqualTo(committed);
    }

    @ParameterizedTest @EnumSource(value = Fault.class, names = {"CLOSE_SQL", "CLOSE_RUNTIME"})
    void connectionCleanupFailurePoisonsTheOwnerForLaterConsumersAndKeepsTheCommittedReceipt(Fault fault) throws Exception {
        var request = fixture.request(); String requestKey = key(); var budget = LegalRegistrationBudget.start();
        var probe = new Probe(); probe.fault = fault;
        try (var h = fixture.harness(probe)) {
            var failure = failure(catchThrowable(() -> register(h, request, requestKey, budget)), LegalRegistrationFailure.Reason.UNAVAILABLE);
            assertThat(failure.completion()).isEqualTo(LegalRegistrationFailure.Completion.COMMITTED);
            assertThat(failure.persistence()).isEqualTo(LegalRegistrationFailure.Persistence.PERSISTED);
            assertThat(failure.confirmedReceipt()).isPresent(); assertThat(probe.commits).hasValue(1);
            assertThat(probe.rollbacks).hasValue(0);
        }
        var committed = fixture.rows();
        assertThatThrownBy(budget::remainingMillis).isInstanceOf(LegalRegistrationBudget.UnavailableException.class)
                .hasCauseInstanceOf(fault == Fault.CLOSE_SQL ? SQLException.class : IllegalStateException.class);
        try (var later = fixture.harness()) {
            assertNoAttempt(catchThrowable(() -> register(later, request, requestKey, budget)));
            assertThat(later.probe().borrows).hasValue(0); assertThat(later.probe().sql).isEmpty();
        }
        assertThat(fixture.rows()).isEqualTo(committed);
    }

    @Test void lostCommitAcknowledgementStaysUnknownAndFreshOwnerCanAccreditTheExistingReplay() throws Exception {
        var request = fixture.request(); String requestKey = key(); var probe = new Probe(); probe.fault = Fault.COMMIT_SQL_ACK;
        try (var h = fixture.harness(probe)) {
            var failure = failure(catchThrowable(() -> register(h, request, requestKey, LegalRegistrationBudget.start())), LegalRegistrationFailure.Reason.UNAVAILABLE);
            assertThat(failure.completion()).isEqualTo(LegalRegistrationFailure.Completion.UNKNOWN);
            assertThat(failure.persistence()).isEqualTo(LegalRegistrationFailure.Persistence.UNKNOWN);
            assertThat(failure.confirmedReceipt()).isEmpty(); assertThat(probe.commits).hasValue(1);
            assertThat(probe.rollbacks).hasValue(0); assertThat(probe.borrows).hasValue(1);
        }
        var committed = fixture.rows();
        try (var recovered = fixture.harness()) {
            var receipt = register(recovered, request, requestKey, LegalRegistrationBudget.start());
            assertThat(receipt.replay()).isTrue(); assertNoDml(recovered); assertThat(recovered.probe().hashes).hasValue(0);
        }
        assertThat(fixture.rows()).isEqualTo(committed);
    }

    private static LegalRegistrationReceipt register(Harness h, Request request, String key, LegalRegistrationBudget budget) {
        var command = request.command();
        return h.service().register(command.registration(), key, command.requiredSetRevision(), command.acceptances(), metadata(), budget);
    }
    private static void assertNoAttempt(Throwable failure) {
        var typed = failure(failure, LegalRegistrationFailure.Reason.UNAVAILABLE);
        assertThat(typed.completion()).isEqualTo(LegalRegistrationFailure.Completion.NONE);
        assertThat(typed.persistence()).isEqualTo(LegalRegistrationFailure.Persistence.NOT_PERSISTED);
        assertThat(typed.confirmedReceipt()).isEmpty(); assertThat(typed.validation()).isEmpty();
    }
}
