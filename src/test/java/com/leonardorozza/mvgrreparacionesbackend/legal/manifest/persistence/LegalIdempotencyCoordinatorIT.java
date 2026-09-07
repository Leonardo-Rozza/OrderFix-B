package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand.Acceptance;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand.Registration;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommandValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalActorSnapshot;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalIdempotencyCoordinatorITSupport.Harness;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalIdempotencyCoordinatorITSupport.LotWritten;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalIdempotencyResultStore.StoredResult;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PerfilAgregadoLegal;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceProtocolFeasibilityITSupport.jdbc;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceProtocolFeasibilityITSupport.transaction;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalIdempotencyCoordinatorITSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/** Real V29 preflight/coordinator/store under restricted Spring transactions; no mocked persistence or writer facade. */
class LegalIdempotencyCoordinatorIT {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_v29_idempotency").withUsername("ordenfix").withPassword("ordenfix");
    private static LegalIdempotencyCoordinatorITSupport fixture;
    @TempDir Path directory;
    private Harness acceptance;
    private Harness registration;

    @BeforeAll static void start() { POSTGRES.start(); fixture = new LegalIdempotencyCoordinatorITSupport(POSTGRES); }
    @AfterAll static void stop() { POSTGRES.stop(); }
    @BeforeEach void reset() throws Exception {
        fixture.reset(directory, getClass()); acceptance = fixture.harness(false, KEY_ONE); registration = fixture.harness(true, KEY_ONE);
    }

    @Test void missHoldsAllRetainedPhysicalLocksInTheCallerTransactionWithoutAnyDurableReservation() throws Exception {
        var command = fixture.acceptanceCommand(fixture.actor()); String key = key();
        Harness rotated = fixture.harness(false, ROTATED); var before = fixture.database.durableRows();
        int pid = inTransaction(rotated, (status, remaining) -> {
            var reserved = rotated.coordinator().reserve(command, key, remaining);
            assertThat(reserved.replay()).isEmpty(); assertThat(reserved.command()).isSameAs(command);
            assertThat(reserved.activeFingerprint().keyVersion()).isEqualTo(2);
            assertThat(reserved.candidates()).hasSize(2); assertThat(reserved.keyring()).isSameAs(ROTATED);
            for (var candidate : reserved.candidates()) rotated.jdbc().queryForList(
                    "SELECT legal_exigir_lock_idempotente_v29(?::varchar,?::varchar,?::varchar,?::varchar)",
                    candidate.operation().name(), candidate.routeTemplate(), candidate.scopeHmac(), candidate.idempotencyKeyHmac());
            assertThat(rotated.jdbc().queryForObject("SELECT current_setting('transaction_isolation')", String.class)).isEqualTo("read committed");
            assertThat(rotated.jdbc().queryForObject("SELECT current_setting('transaction_read_only')", String.class)).isEqualTo("off");
            return rotated.jdbc().queryForObject("SELECT pg_backend_pid()", Integer.class);
        });
        assertThat(fixture.database.durableRows()).isEqualTo(before); noDml(rotated); fixture.noLocksFor(pid);
        assertThat(rotated.metrics().snapshot().commits()).isEqualTo(1);
    }

    @Test void restrictedAcceptanceCommitsCanonicalEvidenceAndReplayIsStableAcrossPermutationsWithoutDml() throws Exception {
        var command = fixture.acceptanceCommand(fixture.actor()); String key = key();
        LotWritten written = inTransaction(acceptance, (status, remaining) -> {
            assertThat(acceptance.jdbc().queryForObject("SELECT current_user || ':' || session_user", String.class)).isEqualTo(ACCEPTOR + ":" + ACCEPTOR);
            var reserved = acceptance.coordinator().reserve(command, key, remaining);
            return write(acceptance, reserved, command.actor(), command.acceptances());
        });
        assertThat(fixture.database.counts()).containsEntry("legal_idempotencia_resultados", 1L).containsEntry("legal_aceptaciones", 2L);
        var before = fixture.database.durableRows(); acceptance.metrics().reset();
        var equivalent = withAcceptances(command, command.acceptances().reversed());
        StoredResult result = replay(acceptance, equivalent, key);
        assertThat(result.source()).isEqualTo(LegalIdempotencyResultStore.Source.WITH_ACTS);
        assertThat(result.result()).isEqualTo("WITH_ACTS"); assertThat(result.ledgerId()).isNotNull(); assertThat(result.supplementalId()).isNull();
        assertThat(result.userId()).isEqualTo(command.actor().userId()); assertThat(result.tallerId()).isEqualTo(command.actor().tallerId());
        assertThat(result.lotId()).isEqualTo(written.lotId()); assertThat(result.acceptanceIds()).isEqualTo(written.acceptanceIds());
        assertThat(result.expiresAt()).isAfterOrEqualTo(result.completedAt().plus(Duration.ofHours(24)));
        assertThat(fixture.database.durableRows()).isEqualTo(before); noDml(acceptance);
    }

    @Test void restrictedRegistrationReplaysOriginalDurableIdsAfterPasswordEmailAndTokenVersionChanges() throws Exception {
        var command = fixture.registrationCommand("first-registration-secret"); String key = key();
        LotWritten written = inTransaction(registration, (status, remaining) -> {
            assertThat(registration.jdbc().queryForObject("SELECT current_user || ':' || session_user", String.class)).isEqualTo(REGISTRAR + ":" + REGISTRAR);
            var reserved = registration.coordinator().reserve(command, key, remaining);
            var actor = registrationActor(registration.jdbc(), command.registration());
            return write(registration, reserved, actor, command.acceptances());
        });
        StoredResult original = replay(registration, command, key);
        fixture.owner.update("UPDATE users SET password='changed-hash',email='changed@example.invalid',token_version=token_version+1 WHERE id=?", written.actor().userId());
        var before = fixture.database.durableRows(); registration.metrics().reset();
        assertThat(replay(registration, command, key)).isEqualTo(original);
        assertThat(original.userId()).isEqualTo(written.actor().userId()); assertThat(original.tallerId()).isEqualTo(written.actor().tallerId());
        assertThat(fixture.database.durableRows()).isEqualTo(before); noDml(registration);
        assertThat(fixture.owner.queryForObject("SELECT has_column_privilege(?, 'public.users', 'password', 'SELECT')", Boolean.class, REGISTRAR)).isFalse();
        for (String sequence : LegalV29AcceptanceITSupport.SEQUENCES) assertThat(fixture.owner.queryForObject("SELECT has_sequence_privilege(?,?, 'USAGE')", Boolean.class, REGISTRAR, sequence)).isFalse();
    }

    @ParameterizedTest @ValueSource(strings = {"inactive", "workshop"})
    void registrationReplayRejectsADisabledUserOrWorkshopWithoutIssuingASuccess(String mutation) throws Exception {
        var command = fixture.registrationCommand("enabled-registration-secret"); String key = key();
        LotWritten written = inTransaction(registration, (status, remaining) -> {
            var reserved = registration.coordinator().reserve(command, key, remaining);
            var actor = registrationActor(registration.jdbc(), command.registration());
            return write(registration, reserved, actor, command.acceptances());
        });
        if (mutation.equals("inactive")) fixture.owner.update("UPDATE users SET active=false WHERE id=?", written.actor().userId());
        else fixture.owner.update("UPDATE talleres SET activo=false WHERE id=?", written.actor().tallerId());
        var before = fixture.database.durableRows(); registration.metrics().reset();
        reason(catchThrowable(() -> replay(registration, command, key)), "INVALID_ACTOR");
        assertThat(fixture.database.durableRows()).isEqualTo(before); noDml(registration); assertRolledBack(registration);
    }

    @Test void mixedResultReplaysBothPreviouslyCommittedAndNewActsWhileItsLotContainsOnlyTheNewAct() throws Exception {
        var command = fixture.acceptanceCommand(fixture.actor());
        LotWritten old = fixture.committedEvidence(command.actor(), List.of(command.acceptances().getFirst())); String key = key();
        LotWritten added = inTransaction(acceptance, (status, remaining) -> write(acceptance,
                acceptance.coordinator().reserve(command, key, remaining), command.actor(), List.of(command.acceptances().getLast())));
        var before = fixture.database.durableRows(); acceptance.metrics().reset();
        StoredResult result = replay(acceptance, command, key);
        List<UUID> expected = new ArrayList<>(old.acceptanceIds()); expected.addAll(added.acceptanceIds()); expected.sort(java.util.Comparator.comparing(UUID::toString));
        assertThat(result.acceptanceIds()).isEqualTo(expected); assertThat(result.lotId()).isEqualTo(added.lotId());
        assertThat(fixture.owner.queryForObject("SELECT count(*) FROM legal_aceptaciones WHERE lote_id=?", Long.class, result.lotId())).isEqualTo(1);
        assertThat(fixture.database.durableRows()).isEqualTo(before); noDml(acceptance);
    }

    @Test void emptySuccessAddsOnlyTheSupplementAndReplaysWithoutInventingEvidence() throws Exception {
        var full = fixture.acceptanceCommand(fixture.actor()); fixture.committedEvidence(full.actor(), full.acceptances());
        var command = withAcceptances(full, List.of()); String key = key(); var before = fixture.database.counts();
        inTransaction(acceptance, (status, remaining) -> { emptyOrDedup(acceptance, acceptance.coordinator().reserve(command, key, remaining), List.of()); return null; });
        var after = fixture.database.counts();
        before.forEach((table, count) -> assertThat(after.get(table)).as(table).isEqualTo(count + (table.equals("legal_idempotencia_sin_actos") ? 1 : 0)));
        var rows = fixture.database.durableRows(); acceptance.metrics().reset(); var result = replay(acceptance, command, key);
        assertThat(result.source()).isEqualTo(LegalIdempotencyResultStore.Source.WITHOUT_ACTS); assertThat(result.result()).isEqualTo("EMPTY");
        assertThat(result.ledgerId()).isNull(); assertThat(result.supplementalId()).isNotNull(); assertThat(result.lotId()).isNull(); assertThat(result.acceptanceIds()).isEmpty();
        assertThat(fixture.database.durableRows()).isEqualTo(rows); noDml(acceptance);
    }

    @Test void dedupReferencesExactCommittedActsAcrossTwoLotsAndPreservesAnOldSubmittedRevision() throws Exception {
        var source = fixture.acceptanceCommand(fixture.actor());
        var first = fixture.committedEvidence(source.actor(), List.of(source.acceptances().getFirst()));
        var second = fixture.committedEvidence(source.actor(), List.of(source.acceptances().getLast()));
        var command = LegalAcceptanceCommandValidator.authenticated(source.actor(), "sha256:" + "0".repeat(64), source.acceptances());
        List<UUID> ids = new ArrayList<>(first.acceptanceIds()); ids.addAll(second.acceptanceIds()); String key = key();
        inTransaction(acceptance, (status, remaining) -> { emptyOrDedup(acceptance, acceptance.coordinator().reserve(command, key, remaining), ids); return null; });
        var before = fixture.database.durableRows(); acceptance.metrics().reset(); StoredResult result = replay(acceptance, command, key);
        assertThat(result.result()).isEqualTo("DEDUP"); assertThat(result.acceptanceIds()).containsExactlyInAnyOrderElementsOf(ids);
        assertThat(fixture.owner.queryForObject("SELECT count(DISTINCT a.lote_id) FROM legal_idempotencia_sin_actos_referencias r JOIN legal_aceptaciones a ON a.id=r.aceptacion_id WHERE r.resultado_id=?", Long.class, result.supplementalId())).isEqualTo(2);
        assertThat(fixture.owner.queryForObject("SELECT submitted_revision <> observed_revision FROM legal_idempotencia_sin_actos WHERE id=?", Boolean.class, result.supplementalId())).isTrue();
        assertThat(fixture.database.durableRows()).isEqualTo(before); noDml(acceptance);
    }

    @ParameterizedTest @ValueSource(strings = {"confirmation", "revision", "digest"})
    void aSuccessfulKeyWithDifferentBusinessPayloadConflictsBeforeCurrentContractValidation(String change) throws Exception {
        var command = fixture.acceptanceCommand(fixture.actor()); String key = key();
        commitAcceptance(command, key); LegalAcceptanceCommand different;
        if (change.equals("revision")) different = LegalAcceptanceCommandValidator.authenticated(command.actor(), "sha256:" + "0".repeat(64), command.acceptances());
        else {
            Acceptance first = command.acceptances().getFirst(); List<Acceptance> values = new ArrayList<>(command.acceptances());
            values.set(0, new Acceptance(first.requisitoVersionId(), first.tipoActo(), change.equals("digest") ? "0".repeat(64) : first.afirmacionSha256(), first.documentos(), !change.equals("confirmation")));
            different = withAcceptances(command, values);
        }
        var before = fixture.database.durableRows(); acceptance.metrics().reset();
        reason(catchThrowable(() -> replay(acceptance, different, key)), "KEY_REUSED");
        assertThat(fixture.database.durableRows()).isEqualTo(before); noDml(acceptance); assertRolledBack(acceptance);
    }

    @Test void registrationPasswordDifferenceConflictsWithoutPersistingOrLoggingEitherPassword() throws Exception {
        var command = fixture.registrationCommand("first-registration-secret"); String key = key();
        inTransaction(registration, (status, remaining) -> {
            var reserved = registration.coordinator().reserve(command, key, remaining);
            var actor = registrationActor(registration.jdbc(), command.registration()); return write(registration, reserved, actor, command.acceptances());
        });
        Registration raw = command.registration();
        var changed = LegalAcceptanceCommandValidator.registration(new Registration(raw.nombreTaller(), raw.telefonoTaller(), raw.nombreAdmin(), raw.email(), "second-registration-secret"), command.requiredSetRevision(), command.acceptances());
        var before = fixture.database.durableRows(); registration.metrics().reset(); Throwable failure = catchThrowable(() -> replay(registration, changed, key));
        reason(failure, "KEY_REUSED"); assertThat(failure.toString()).doesNotContain("first-registration-secret", "second-registration-secret", key);
        assertThat(fixture.database.durableRows()).isEqualTo(before); noDml(registration);
        assertThat(fixture.database.durableRows().values().stream().flatMap(List::stream).toList())
                .noneSatisfy(row -> assertThat(row).contains("first-registration-secret"))
                .noneSatisfy(row -> assertThat(row).contains("second-registration-secret"));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void expiredUnpurgedResultsRemainAuthoritativeInBothLedgers(boolean supplemental) throws Exception {
        var full = fixture.acceptanceCommand(fixture.actor()); String key = key(); LegalAcceptanceCommand command = full;
        if (supplemental) {
            fixture.committedEvidence(full.actor(), full.acceptances()); command = withAcceptances(full, List.of());
            var finalCommand = command;
            inTransaction(acceptance, (status, remaining) -> { emptyOrDedup(acceptance, acceptance.coordinator().reserve(finalCommand, key, remaining), List.of()); return null; });
        } else commitAcceptance(command, key);
        StoredResult original = replay(acceptance, command, key); fixture.expire(original);
        var before = fixture.database.durableRows(); acceptance.metrics().reset(); StoredResult expired = replay(acceptance, command, key);
        assertThat(expired.ledgerId()).isEqualTo(original.ledgerId()); assertThat(expired.supplementalId()).isEqualTo(original.supplementalId());
        assertThat(expired.acceptanceIds()).isEqualTo(original.acceptanceIds()); assertThat(expired.expiresAt()).isBefore(Instant.now());
        assertThat(fixture.database.durableRows()).isEqualTo(before); noDml(acceptance);
    }

    @Test void activeNewVersionFindsTheRetainedOldResultInsteadOfWritingASecondSuccess() throws Exception {
        var command = fixture.acceptanceCommand(fixture.actor()); String key = key(); commitAcceptance(command, key);
        StoredResult original = replay(acceptance, command, key); Harness replica = fixture.harness(false, ROTATED);
        var before = fixture.database.durableRows(); assertThat(replay(replica, command, key)).isEqualTo(original); noDml(replica);
        assertThat(fixture.owner.queryForObject("SELECT hmac_key_version FROM legal_idempotencia_resultados WHERE id=?", Integer.class, original.ledgerId())).isEqualTo(1);
        assertThat(fixture.database.durableRows()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void waitingReplicaReReadsAfterWinnerCommitOrCanWriteAfterWinnerRollback(boolean winnerCommits) throws Exception {
        var command = fixture.acceptanceCommand(fixture.actor()); String key = key(); Harness replica = fixture.harness(false, ROTATED);
        long winnerKey = fixture.physicalKeys(KEY_ONE, command, key).getFirst();
        var waitingPid = new CompletableFuture<Integer>(); var waiting = new AtomicReference<Future<RaceResult>>();
        try (var executor = Executors.newSingleThreadExecutor()) {
            LotWritten first = inTransaction(acceptance, (status, remaining) -> {
                var reserved = acceptance.coordinator().reserve(command, key, remaining);
                LotWritten written = write(acceptance, reserved, command.actor(), command.acceptances());
                waiting.set(executor.submit(() -> inTransaction(replica, (otherStatus, otherRemaining) -> {
                    waitingPid.complete(replica.jdbc().queryForObject("SELECT pg_backend_pid()", Integer.class));
                    var next = replica.coordinator().reserve(command, key, otherRemaining);
                    if (next.replay().isPresent()) return new RaceResult(next.replay().orElseThrow(), null);
                    return new RaceResult(null, write(replica, next, command.actor(), command.acceptances()));
                })));
                fixture.awaitWait(waitingPid.get(5, TimeUnit.SECONDS), winnerKey);
                assertThat(waiting.get().isDone()).isFalse(); if (!winnerCommits) status.setRollbackOnly(); return written;
            });
            RaceResult second = waiting.get().get(10, TimeUnit.SECONDS);
            if (winnerCommits) {
                assertThat(second.replay()).isNotNull(); assertThat(second.written()).isNull();
                assertThat(second.replay().lotId()).isEqualTo(first.lotId()); noDml(replica);
            } else {
                assertThat(second.replay()).isNull(); assertThat(second.written()).isNotNull();
                assertThat(second.written().lotId()).isNotEqualTo(first.lotId());
                assertThat(fixture.owner.queryForObject("SELECT count(*) FROM legal_aceptacion_lotes WHERE id=?", Long.class, first.lotId())).isZero();
            }
            assertThat(fixture.database.counts()).containsEntry("legal_idempotencia_resultados", 1L).containsEntry("legal_aceptacion_lotes", 1L).containsEntry("legal_aceptaciones", 2L);
            fixture.noLocksFor(waitingPid.get());
        }
    }

    @Test void waitsOnTwoRetainedKeysConsumeOneFiveSecondBudgetAndRollBackWithoutLeakingLocks() throws Exception {
        var command = fixture.acceptanceCommand(fixture.actor()); String key = key(); Harness replica = fixture.harness(false, ROTATED);
        List<Long> keys = fixture.physicalKeys(ROTATED, command, key); assertThat(keys).hasSize(2);
        var pid = new CompletableFuture<Integer>(); var started = new CompletableFuture<Long>();
        try (var executor = Executors.newSingleThreadExecutor(); var first = transaction(fixture.database.dataSource); var second = transaction(fixture.database.dataSource)) {
            hold(jdbc(first), keys.getFirst()); hold(jdbc(second), keys.getLast());
            Future<Throwable> attempt = executor.submit(() -> catchThrowable(() -> inTransaction(replica, (status, remaining) -> {
                pid.complete(replica.jdbc().queryForObject("SELECT pg_backend_pid()", Integer.class)); started.complete(System.nanoTime());
                return replica.coordinator().reserve(command, key, remaining);
            })));
            int waitingPid = pid.get(5, TimeUnit.SECONDS); fixture.awaitWait(waitingPid, keys.getFirst());
            Thread.sleep(2_200); first.commit(); fixture.awaitWait(waitingPid, keys.getLast());
            Throwable failure = attempt.get(8, TimeUnit.SECONDS); long elapsed = System.nanoTime() - started.get();
            reason(failure, "IN_PROGRESS"); assertThat(Duration.ofNanos(elapsed)).isBetween(Duration.ofMillis(4_500), Duration.ofMillis(6_750));
            noDml(replica); assertRolledBack(replica); fixture.noLocksFor(waitingPid);
            second.rollback();
        }
    }

    @Test void reservationCannotBeUsedByAnotherTransactionEvenWhenTheSameJdbcAndActorAreReused() throws Exception {
        var full = fixture.acceptanceCommand(fixture.actor()); var command = withAcceptances(full, List.of()); String key = key();
        var escaped = inTransaction(acceptance, (status, remaining) -> acceptance.coordinator().reserve(command, key, remaining));
        var before = fixture.database.durableRows(); acceptance.metrics().reset();
        reason(catchThrowable(() -> inTransaction(acceptance, (status, remaining) -> {
            writerBoundary(acceptance.jdbc(), command.actor());
            acceptance.store().persistWithoutActs(escaped, command.actor(), observed(acceptance.jdbc(), command.actor()), List.of()); return null;
        })), "UNAVAILABLE");
        assertThat(fixture.database.durableRows()).isEqualTo(before); noDml(acceptance); assertRolledBack(acceptance);
    }

    @Test void failureAfterFullRegistrationGraphRollsBackBusinessEvidenceAndSuccessAndReleasesTheKey() throws Exception {
        var command = fixture.registrationCommand("rollback-registration-secret"); String key = key(); var before = fixture.database.durableRows();
        assertThatThrownBy(() -> inTransaction(registration, (status, remaining) -> {
            var reserved = registration.coordinator().reserve(command, key, remaining);
            var actor = registrationActor(registration.jdbc(), command.registration()); write(registration, reserved, actor, command.acceptances());
            throw new IllegalStateException("fixture rollback sentinel");
        })).isInstanceOf(IllegalStateException.class).hasMessage("fixture rollback sentinel");
        assertThat(fixture.database.durableRows()).isEqualTo(before); assertRolledBack(registration);
        registration.metrics().reset();
        inTransaction(registration, (status, remaining) -> { assertThat(registration.coordinator().reserve(command, key, remaining).replay()).isEmpty(); return null; });
        noDml(registration); assertThat(fixture.database.durableRows()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void replaySurvivesRealReplaceAndRetirementWithoutAnEditorialGateOrCurrentContractRead(boolean supplemental) throws Exception {
        var command = fixture.acceptanceCommand(fixture.actor()); String key = key();
        if (supplemental) {
            var evidence = fixture.committedEvidence(command.actor(), command.acceptances());
            inTransaction(acceptance, (status, remaining) -> { emptyOrDedup(acceptance, acceptance.coordinator().reserve(command, key, remaining), evidence.acceptanceIds()); return null; });
        } else commitAcceptance(command, key);
        StoredResult original = replay(acceptance, command, key);
        fixture.replaceAndRetire(directory, getClass(), () -> {
            acceptance.metrics().reset(); assertThat(replay(acceptance, command, key)).isEqualTo(original); noDml(acceptance); noCurrentContractRead(acceptance);
        });
        var before = fixture.database.durableRows(); acceptance.metrics().reset();
        try (var editorial = transaction(fixture.database.dataSource)) {
            jdbc(editorial).execute("SELECT pg_advisory_xact_lock(hashtextextended('ordenfix:legal-publicaciones:sello:v1',0))");
            // A replay must finish while another connection owns the exclusive editorial gate.
            assertThat(replay(acceptance, command, key)).isEqualTo(original); editorial.rollback();
        }
        assertThat(fixture.database.durableRows()).isEqualTo(before); noDml(acceptance); noCurrentContractRead(acceptance);
        assertThat(fixture.owner.queryForObject("SELECT count(*) FROM legal_requisito_transiciones WHERE estado_nuevo='RETIRADA'", Long.class)).isPositive();
    }

    @ParameterizedTest @ValueSource(strings = {"role", "token", "inactive", "workshop"})
    void currentActorInvalidationWinsOverAnOtherwiseDifferentFingerprint(String mutation) throws Exception {
        var command = fixture.acceptanceCommand(fixture.actor()); String key = key(); commitAcceptance(command, key);
        switch (mutation) {
            case "role" -> fixture.owner.update("UPDATE users SET role='ADMIN' WHERE id=?", command.actor().userId());
            case "token" -> fixture.owner.update("UPDATE users SET token_version=token_version+1 WHERE id=?", command.actor().userId());
            case "inactive" -> fixture.owner.update("UPDATE users SET active=false WHERE id=?", command.actor().userId());
            case "workshop" -> fixture.owner.update("UPDATE talleres SET activo=false WHERE id=?", command.actor().tallerId());
            default -> throw new AssertionError(mutation);
        }
        var different = withAcceptances(command, List.of()); var before = fixture.database.durableRows(); acceptance.metrics().reset();
        reason(catchThrowable(() -> replay(acceptance, different, key)), "INVALID_ACTOR");
        assertThat(fixture.database.durableRows()).isEqualTo(before); noDml(acceptance); assertRolledBack(acceptance);
    }

    @Test void sameUserScopeCannotReplayUnderAnotherWorkshopSnapshot() throws Exception {
        var command = fixture.acceptanceCommand(fixture.actor()); String key = key(); commitAcceptance(command, key);
        var anotherWorkshop = fixture.actor();
        var mismatched = withActor(command, new LegalActorSnapshot(command.actor().userId(), anotherWorkshop.tallerId(),
                command.actor().role(), command.actor().tokenVersion(), true, true));
        var before = fixture.database.durableRows(); acceptance.metrics().reset();
        reason(catchThrowable(() -> replay(acceptance, mismatched, key)), "INVALID_ACTOR");
        assertThat(fixture.database.durableRows()).isEqualTo(before); noDml(acceptance);
    }

    @ParameterizedTest @ValueSource(strings = {"key-version", "evidence-digest", "affirmation-text-both", "lot-revision", "missing-lot"})
    void corruptDurableHeaderOrEvidenceNeverProducesReplaySuccess(String corruption) throws Exception {
        var command = fixture.acceptanceCommand(fixture.actor()); String key = key(); commitAcceptance(command, key);
        StoredResult result = replay(acceptance, command, key);
        LegalManifestPersistenceITSupport.withReplicaRole(fixture.owner, () -> {
            switch (corruption) {
                case "key-version" -> fixture.owner.update("UPDATE legal_idempotencia_resultados SET hmac_key_version=99 WHERE id=?", result.ledgerId());
                case "evidence-digest" -> fixture.owner.update("UPDATE legal_aceptacion_documentos SET sha256=? WHERE aceptacion_id=?", "0".repeat(64), result.acceptanceIds().getFirst());
                case "affirmation-text-both" -> {
                    UUID requirement = command.acceptances().getFirst().requisitoVersionId();
                    String changed = "El texto cambió en snapshot y fuente, pero conserva un SHA incorrecto.";
                    fixture.owner.update("UPDATE legal_aceptaciones SET afirmacion=? WHERE requisito_version_id=? AND user_id=?", changed, requirement, command.actor().userId());
                    fixture.owner.update("UPDATE legal_requisito_versiones SET afirmacion=? WHERE id=?", changed, requirement);
                }
                case "lot-revision" -> fixture.owner.update("UPDATE legal_aceptacion_lotes SET required_set_revision=? WHERE id=?", "sha256:" + "0".repeat(64), result.lotId());
                case "missing-lot" -> fixture.owner.update("DELETE FROM legal_aceptacion_lotes WHERE id=?", result.lotId());
                default -> throw new AssertionError(corruption);
            }
        });
        var before = fixture.database.durableRows(); acceptance.metrics().reset();
        reason(catchThrowable(() -> replay(acceptance, command, key)), "UNAVAILABLE");
        assertThat(fixture.database.durableRows()).isEqualTo(before); noDml(acceptance); assertRolledBack(acceptance);
    }

    @Test void missingDedupReferenceFailsClosedInsteadOfReturningAPartialSuccess() throws Exception {
        var command = fixture.acceptanceCommand(fixture.actor()); var evidence = fixture.committedEvidence(command.actor(), command.acceptances()); String key = key();
        inTransaction(acceptance, (status, remaining) -> { emptyOrDedup(acceptance, acceptance.coordinator().reserve(command, key, remaining), evidence.acceptanceIds()); return null; });
        StoredResult result = replay(acceptance, command, key);
        LegalManifestPersistenceITSupport.withReplicaRole(fixture.owner, () -> fixture.owner.update(
                "DELETE FROM legal_idempotencia_sin_actos_referencias WHERE resultado_id=? AND aceptacion_id=?", result.supplementalId(), result.acceptanceIds().getFirst()));
        var before = fixture.database.durableRows(); acceptance.metrics().reset();
        reason(catchThrowable(() -> replay(acceptance, command, key)), "UNAVAILABLE");
        assertThat(fixture.database.durableRows()).isEqualTo(before); noDml(acceptance);
    }

    @Test void resultsFromTwoUncoordinatedKeyVersionsAreRejectedAsAnAmbiguousDurableOutcome() throws Exception {
        var full = fixture.acceptanceCommand(fixture.actor()); fixture.committedEvidence(full.actor(), full.acceptances());
        var command = withAcceptances(full, List.of()); String key = key();
        // Deliberately model an invalid deployment that dropped retained keys, without bypassing SQL guards.
        Harness isolatedNewKey = fixture.harness(false, new LegalIdempotencyKeyring(Map.of(2, SECRET_TWO), 2));
        for (Harness writer : List.of(acceptance, isolatedNewKey)) inTransaction(writer, (status, remaining) -> {
            emptyOrDedup(writer, writer.coordinator().reserve(command, key, remaining), List.of()); return null;
        });
        assertThat(fixture.database.counts()).containsEntry("legal_idempotencia_sin_actos", 2L);
        Harness combined = fixture.harness(false, ROTATED); var before = fixture.database.durableRows();
        reason(catchThrowable(() -> replay(combined, command, key)), "UNAVAILABLE");
        assertThat(fixture.database.durableRows()).isEqualTo(before); noDml(combined);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void currentTransactionResultInsideAReleasedSavepointCannotMasqueradeAsADurableReplay(boolean withActs) throws Exception {
        var full = fixture.acceptanceCommand(fixture.actor());
        var command = withActs ? full : withAcceptances(full, List.of()); String key = key();
        var before = fixture.database.durableRows();
        Throwable failure = catchThrowable(() -> inTransaction(acceptance, (status, remaining) -> {
            var reserved = acceptance.coordinator().reserve(command, key, remaining);
            // V27 requires the lot to belong to the outer transaction. Only its result header uses the child XID.
            LotWritten lot = withActs ? newLot(acceptance.jdbc(), command.actor(), PerfilAgregadoLegal.AUTHENTICATED_PENDING,
                    command.acceptances()) : null;
            acceptance.jdbc().execute("SAVEPOINT result_subtransaction");
            if (withActs) {
                acceptance.store().persistWithActs(reserved, command.actor(), lot.lotId());
                acceptance.jdbc().execute("SET CONSTRAINTS ALL IMMEDIATE");
            } else emptyOrDedup(acceptance, reserved, List.of());
            acceptance.jdbc().execute("RELEASE SAVEPOINT result_subtransaction");
            assertReleasedSubtransactionInProgress(acceptance.jdbc(), withActs);
            return acceptance.coordinator().reserve(command, key, remaining).replay();
        }));
        reason(failure, "UNAVAILABLE"); assertThat(fixture.database.durableRows()).isEqualTo(before); assertRolledBack(acceptance);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void aResultInsertedInsideASavepointBecomesReplayableOnlyAfterTheOuterCommit(boolean withActs) throws Exception {
        var full = fixture.acceptanceCommand(fixture.actor());
        var command = withActs ? full : withAcceptances(full, List.of()); String key = key();
        String resultXid = inTransaction(acceptance, (status, remaining) -> {
            var reserved = acceptance.coordinator().reserve(command, key, remaining);
            LotWritten lot = withActs ? newLot(acceptance.jdbc(), command.actor(), PerfilAgregadoLegal.AUTHENTICATED_PENDING,
                    command.acceptances()) : null;
            acceptance.jdbc().execute("SAVEPOINT result_subtransaction");
            if (withActs) {
                acceptance.store().persistWithActs(reserved, command.actor(), lot.lotId());
                acceptance.jdbc().execute("SET CONSTRAINTS ALL IMMEDIATE");
            } else emptyOrDedup(acceptance, reserved, List.of());
            acceptance.jdbc().execute("RELEASE SAVEPOINT result_subtransaction");
            return assertReleasedSubtransactionInProgress(acceptance.jdbc(), withActs);
        });
        assertThat(fixture.owner.queryForObject("SELECT pg_catalog.pg_xact_status(?::xid8)", String.class, resultXid)).isEqualTo("committed");
        var before = fixture.database.durableRows(); acceptance.metrics().reset();
        assertThat(replay(acceptance, command, key).result()).isEqualTo(withActs ? "WITH_ACTS" : "EMPTY");
        assertThat(fixture.database.durableRows()).isEqualTo(before); noDml(acceptance);
    }

    @Test void resultAfterSeventyReleasedChildTransactionsStillCannotReplayBeforeTheOuterCommit() throws Exception {
        var command = withAcceptances(fixture.acceptanceCommand(fixture.actor()), List.of()); String key = key();
        // Distinct previously committed rows force distinct child XIDs; repeated locking of one row would not.
        List<LegalActorSnapshot> lockRows = new ArrayList<>();
        for (int index = 0; index < 70; index++) lockRows.add(fixture.actor());
        var before = fixture.database.durableRows();
        Throwable failure = catchThrowable(() -> inTransaction(acceptance, (status, remaining) -> {
            var reserved = acceptance.coordinator().reserve(command, key, remaining);
            long topXid = acceptance.jdbc().queryForObject("SELECT mod(pg_catalog.pg_current_xact_id()::text::numeric,4294967296)::bigint", Long.class);
            var observedChildXids = new HashSet<Long>();
            for (LegalActorSnapshot row : lockRows) {
                acceptance.jdbc().execute("SAVEPOINT fill_subtransaction_cache");
                acceptance.jdbc().queryForList("SELECT id FROM users WHERE id=? FOR UPDATE", row.userId());
                List<Long> ownChildren = acceptance.jdbc().queryForList("""
                        SELECT held.transactionid::text::bigint FROM pg_catalog.pg_locks held
                         WHERE held.locktype='transactionid' AND held.pid=pg_catalog.pg_backend_pid()
                           AND held.mode='ExclusiveLock' AND held.granted
                           AND held.transactionid::text::bigint<>?
                        """, Long.class, topXid);
                assertThat(ownChildren).hasSize(1);
                assertThat(observedChildXids.add(ownChildren.getFirst())).as("Each row lock assigns a distinct child XID").isTrue();
                acceptance.jdbc().execute("RELEASE SAVEPOINT fill_subtransaction_cache");
            }
            assertThat(observedChildXids).hasSize(70);
            acceptance.jdbc().execute("SAVEPOINT result_after_cache_overflow"); emptyOrDedup(acceptance, reserved, List.of());
            acceptance.jdbc().execute("RELEASE SAVEPOINT result_after_cache_overflow");
            assertReleasedSubtransactionInProgress(acceptance.jdbc(), false);
            return acceptance.coordinator().reserve(command, key, remaining).replay();
        }));
        reason(failure, "UNAVAILABLE"); assertThat(fixture.database.durableRows()).isEqualTo(before); assertRolledBack(acceptance);
    }

    @Test void unsignedXidDistanceAndFullEpochArithmeticRemainExactAtBoundariesAndBeyondJavaLong() {
        var cases = fixture.owner.queryForList("""
                WITH cases(label,top_xid,row_xid,expected_delta,expected_full,expected_forward) AS (
                    VALUES ('same',100::numeric,100::numeric,0::numeric,100::numeric,true),
                           ('next',100,101,1,101,true),
                           ('half-minus-one',100,2147483747,2147483647,2147483747,true),
                           ('half',100,2147483748,2147483648,2147483748,false),
                           ('wrap',4294967294,3,5,4294967299,true),
                           ('beyond-long',9223372036854775808,1,1,9223372036854775809,true),
                           ('old-visible',4294967396,99,4294967295,8589934691,false)
                ), measured AS (
                    SELECT cases.*,%s AS actual_delta FROM cases
                )
                SELECT label,actual_delta=expected_delta AS exact_delta,
                       top_xid+actual_delta=expected_full AS exact_full,
                       (actual_delta<2147483648)=expected_forward AS exact_window
                  FROM measured
                """.formatted(LegalIdempotencyResultStore.FORWARD_XID_DISTANCE_SQL));
        assertThat(cases).hasSize(7).allSatisfy(value -> {
            assertThat(value.get("exact_delta")).as("delta %s", value.get("label")).isEqualTo(true);
            assertThat(value.get("exact_full")).as("full xid %s", value.get("label")).isEqualTo(true);
            assertThat(value.get("exact_window")).as("forward window %s", value.get("label")).isEqualTo(true);
        });
    }

    @Test void schemaHistoryDriftFailsBeforeAnyIdempotencyLockOrDml() throws Exception {
        var command = fixture.acceptanceCommand(fixture.actor()); String key = key();
        int checksum = fixture.owner.queryForObject("SELECT checksum FROM flyway_schema_history WHERE version='29'", Integer.class);
        fixture.owner.update("UPDATE flyway_schema_history SET checksum=checksum+1 WHERE version='29'");
        try {
            reason(catchThrowable(() -> inTransaction(acceptance, (status, remaining) -> acceptance.coordinator().reserve(command, key, remaining))), "UNAVAILABLE");
            assertThat(acceptance.metrics().snapshot().executions(LegalJdbcMetricsSupport.Category.ADVISORY_LOCK)).isZero(); noDml(acceptance); assertRolledBack(acceptance);
        } finally { fixture.owner.update("UPDATE flyway_schema_history SET checksum=? WHERE version='29'", checksum); }
    }

    /** A released child XID is still uncommitted; PostgreSQL no longer exposes its own transactionid lock. */
    private static String assertReleasedSubtransactionInProgress(JdbcTemplate jdbc, boolean withActs) {
        String table = withActs ? "legal_idempotencia_resultados" : "legal_idempotencia_sin_actos";
        var state = jdbc.queryForMap("""
                WITH top_transaction AS (
                    SELECT pg_catalog.pg_current_xact_id()::text::numeric AS full_xid
                ), expanded AS (
                    SELECT result.xmin::text::bigint AS row_xid,
                           mod(top_transaction.full_xid,4294967296)::bigint AS top_xid,
                           top_transaction.full_xid + mod(result.xmin::text::numeric
                               - mod(top_transaction.full_xid,4294967296) + 4294967296,4294967296) AS full_row_xid
                      FROM public.%s result CROSS JOIN top_transaction
                )
                SELECT row_xid,top_xid,full_row_xid::text AS full_row_xid,
                       pg_catalog.pg_xact_status(full_row_xid::text::xid8) AS transaction_status
                  FROM expanded
                """.formatted(table));
        assertThat(((Number) state.get("row_xid")).longValue()).isNotEqualTo(((Number) state.get("top_xid")).longValue());
        assertThat(state.get("transaction_status")).isEqualTo("in progress");
        return (String) state.get("full_row_xid");
    }

    private LotWritten commitAcceptance(LegalAcceptanceCommand command, String key) {
        return inTransaction(acceptance, (status, remaining) -> write(acceptance, acceptance.coordinator().reserve(command, key, remaining), command.actor(), command.acceptances()));
    }
    private static void reason(Throwable failure, String reason) {
        assertThat(failure).isInstanceOf(LegalIdempotencyException.class);
        assertThat(((LegalIdempotencyException) failure).reason().name()).isEqualTo(reason);
        assertThat(failure.toString()).doesNotContain("SELECT", "scope_hmac", "fingerprint_hmac", SECRET_ONE, SECRET_TWO, POSTGRES.getJdbcUrl());
    }
    private static void noDml(Harness harness) { assertThat(harness.metrics().snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isZero(); }
    private static void assertRolledBack(Harness harness) {
        assertThat(harness.metrics().snapshot().commits()).isZero(); assertThat(harness.metrics().snapshot().rollbacks()).isEqualTo(1);
    }
    private static void noCurrentContractRead(Harness harness) {
        assertThat(harness.metrics().snapshot().bySql().values()).noneSatisfy(sql -> assertThat(sql.sql().toLowerCase(java.util.Locale.ROOT)).contains("from public.legal_requisito_conjuntos_actuales"));
    }
    private record RaceResult(StoredResult replay, LotWritten written) { }
}
