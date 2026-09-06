package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;

import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceProtocolFeasibilityITSupport.*;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalV29AcceptanceITSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

@Testcontainers
class LegalV29AcceptancePersistenceIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_v29_persistence").withUsername("ordenfix").withPassword("ordenfix");
    @TempDir static Path directory;
    static LegalV29AcceptanceITSupport fixture;

    @BeforeAll static void prepare() throws Exception {
        fixture = new LegalV29AcceptanceITSupport(POSTGRES);
        fixture.migrate("29");
        fixture.seedCatalog(directory, LegalV29AcceptancePersistenceIT.class);
    }

    @Test void emptyCommitsOnlyTechnicalResultWithServerTimeAndNoFabricatedEvidence() throws Exception {
        Actor actor = insertActor(fixture.owner);
        var before = fixture.counts();
        UUID id = fixture.committedEmpty(actor, tuple());
        var after = fixture.counts();
        before.forEach((table, count) -> {
            if (!List.of("legal_requisito_agregados", "legal_requisito_agregado_scopes").contains(table))
                assertThat(after.get(table)).as(table).isEqualTo(count + (table.equals(PARENT) ? 1 : 0));
        });
        assertThat(fixture.owner.queryForObject("""
                SELECT resultado = 'EMPTY' AND referencia_count = 0
                   AND submitted_revision = observed_revision
                   AND expires_at >= completed_at + INTERVAL '24 hours'
                   AND completed_at <= statement_timestamp()
                   AND completed_at > statement_timestamp() - INTERVAL '1 minute'
                FROM legal_idempotencia_sin_actos WHERE id = ?
                """, Boolean.class, id)).isTrue();
        // SQL does not infer satisfaction: the application evaluator must authorize EMPTY.
    }

    @Test void dedupReferencesCommittedEvidenceAndPreservesItsBytesXminAndOldSubmittedRevision() throws Exception {
        Actor actor = insertActor(fixture.owner);
        Written evidence = fixture.committedAcceptance(actor);
        var before = fixture.durableRows();
        UUID id = fixture.committedDedup(actor, tuple(), evidence);
        for (String table : DURABLE_TABLES) assertThat(fixture.durableRows().get(table)).as(table)
                .isEqualTo(before.get(table));
        assertThat(fixture.owner.queryForObject("""
                SELECT resultado = 'DEDUP' AND submitted_revision <> observed_revision
                  AND referencia_count = (SELECT count(*) FROM legal_idempotencia_sin_actos_referencias WHERE resultado_id = r.id)
                FROM legal_idempotencia_sin_actos r WHERE id = ?
                """, Boolean.class, id)).isTrue();
    }

    @Test void dedupCanReferenceAllSubmittedEvidenceAcrossTwoPreviouslyCommittedLots() throws Exception {
        Actor actor = insertActor(fixture.owner);
        var evidence = new java.util.ArrayList<Written>();
        for (int ordinal = 0; ordinal < 2; ordinal++) {
            try (Connection connection = transaction(fixture.dataSource)) {
                evidence.add(singleActAcceptance(jdbc(connection), actor, tuple(), ordinal));
                connection.commit();
            }
        }
        var before = fixture.durableRows();
        UUID id;
        try (Connection connection = transaction(fixture.dataSource)) {
            JdbcTemplate jdbc = jdbc(connection); Tuple tuple = tuple(); lock(jdbc, tuple);
            id = result(jdbc, actor, aggregate(jdbc, actor), tuple, "DEDUP", revision(), 2);
            for (Written written : evidence) reference(jdbc, id, written.acts().getFirst().id(), actor);
            connection.commit();
        }
        assertThat(fixture.owner.queryForObject("""
                SELECT count(DISTINCT a.lote_id) FROM legal_idempotencia_sin_actos_referencias r
                JOIN legal_aceptaciones a ON a.id=r.aceptacion_id WHERE r.resultado_id=?
                """, Long.class, id)).isEqualTo(2L);
        for (String table : DURABLE_TABLES) assertThat(fixture.durableRows().get(table)).isEqualTo(before.get(table));
    }

    @Test void emptyRejectsOldRevisionAndDedupRequiresAtLeastOneReference() throws Exception {
        Actor actor = insertActor(fixture.owner);
        reject(jdbc -> {
            Tuple tuple = tuple(); lock(jdbc, tuple);
            var aggregate = aggregate(jdbc, actor);
            result(jdbc, actor, aggregate, tuple, "EMPTY", revision(), 0);
        }, "23514");
        reject(jdbc -> {
            Tuple tuple = tuple(); lock(jdbc, tuple);
            var aggregate = aggregate(jdbc, actor);
            result(jdbc, actor, aggregate, tuple, "DEDUP", revision(), 0);
        }, "23514");
        reject(jdbc -> {
            Tuple tuple = tuple(); lock(jdbc, tuple);
            result(jdbc, actor, aggregate(jdbc, actor), tuple, "DEDUP", revision(), 2049);
        }, "23514");
    }

    @Test void missingAndExcessReferencesFailDeferredAndRollbackPrecedingWrites() throws Exception {
        Actor actor = insertActor(fixture.owner);
        Written evidence = fixture.committedAcceptance(actor);
        reject(jdbc -> {
            jdbc.update("INSERT INTO talleres(nombre) VALUES ('V29 deferred rollback sentinel')");
            Tuple tuple = tuple(); lock(jdbc, tuple);
            var aggregate = aggregate(jdbc, actor);
            result(jdbc, actor, aggregate, tuple, "DEDUP", revision(), 1);
        }, "23514");
        reject(jdbc -> {
            Tuple tuple = tuple(); lock(jdbc, tuple);
            var aggregate = aggregate(jdbc, actor);
            UUID id = result(jdbc, actor, aggregate, tuple, "DEDUP", revision(), evidence.acts().size() + 1);
            evidence.acts().forEach(act -> reference(jdbc, id, act.id(), actor));
        }, "23514");
        reject(jdbc -> {
            Tuple tuple = tuple(); lock(jdbc, tuple);
            var aggregate = aggregate(jdbc, actor);
            UUID id = result(jdbc, actor, aggregate, tuple, "DEDUP", revision(), 1);
            reference(jdbc, id, evidence.acts().get(0).id(), actor);
            reference(jdbc, id, evidence.acts().get(1).id(), actor);
        }, "23514");
    }

    @Test void referencesRejectForeignActorCurrentTransactionAndLateChildren() throws Exception {
        Actor actor = insertActor(fixture.owner);
        Actor other = insertActor(fixture.owner);
        Written foreign = fixture.committedAcceptance(other);
        reject(jdbc -> {
            Tuple tuple = tuple(); lock(jdbc, tuple);
            var aggregate = aggregate(jdbc, actor);
            UUID id = result(jdbc, actor, aggregate, tuple, "DEDUP", revision(), 1);
            reference(jdbc, id, foreign.acts().getFirst().id(), actor);
        }, "23503");
        reject(jdbc -> {
            Written fresh = acceptance(jdbc, actor, tuple());
            Tuple tuple = tuple(); lock(jdbc, tuple);
            UUID id = result(jdbc, actor, fresh.lot().aggregate(), tuple, "DEDUP", revision(), 1);
            reference(jdbc, id, fresh.acts().getFirst().id(), actor);
        }, "23514");
        Written own = fixture.committedAcceptance(actor);
        UUID committed = fixture.committedDedup(actor, tuple(), own);
        reject(jdbc -> reference(jdbc, committed, own.acts().getFirst().id(), actor), "23514");
    }

    @Test void refsCannotBeAttachedToEmptyAndCannotRepeat() throws Exception {
        Actor actor = insertActor(fixture.owner);
        Written evidence = fixture.committedAcceptance(actor);
        reject(jdbc -> {
            Tuple tuple = tuple(); lock(jdbc, tuple);
            var aggregate = aggregate(jdbc, actor);
            UUID id = result(jdbc, actor, aggregate, tuple, "EMPTY", aggregate.requiredSetRevision(), 0);
            reference(jdbc, id, evidence.acts().getFirst().id(), actor);
        }, "23514");
        reject(jdbc -> {
            Tuple tuple = tuple(); lock(jdbc, tuple);
            var aggregate = aggregate(jdbc, actor);
            UUID id = result(jdbc, actor, aggregate, tuple, "DEDUP", revision(), 2);
            UUID act = evidence.acts().getFirst().id();
            reference(jdbc, id, act, actor); reference(jdbc, id, act, actor);
        }, "23505");
    }

    @Test void tupleLockMustBeExclusiveAndBelongToTheExactLogicalTuple() throws Exception {
        Actor actor = insertActor(fixture.owner);
        for (String mode : List.of("absent", "shared", "other")) reject(jdbc -> {
            Tuple tuple = tuple();
            if (mode.equals("shared")) jdbc.queryForList("SELECT pg_advisory_xact_lock_shared(?)", lockKey(jdbc, tuple));
            if (mode.equals("other")) lock(jdbc, tuple());
            var aggregate = aggregate(jdbc, actor);
            result(jdbc, actor, aggregate, tuple, "EMPTY", aggregate.requiredSetRevision(), 0);
        }, "55000");
    }

    @Test void resultRequiresSharedEditorialGateAndReadCommittedEvenWithTupleLock() throws Exception {
        Actor actor = insertActor(fixture.owner);
        Written evidence = fixture.committedAcceptance(actor);
        reject(jdbc -> {
            Tuple tuple = tuple(); lock(jdbc, tuple);
            var aggregate = evidence.lot().aggregate();
            result(jdbc, actor, aggregate, tuple, "EMPTY", aggregate.requiredSetRevision(), 0);
        }, "55000");
        try (Connection connection = fixture.dataSource.getConnection()) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            JdbcTemplate jdbc = jdbc(connection); Tuple tuple = tuple(); lock(jdbc, tuple);
            try {
                sqlState(catchThrowable(() -> jdbc.queryForList("SELECT legal_exigir_lock_idempotente_v29(?,?,?,?)",
                        tuple.operation(), tuple.route(), tuple.scope(), tuple.key())), "25001");
            } finally {
                connection.rollback();
            }
        }
    }

    @Test void evidenceCreatedInsideReleasedSavepointIsStillNotPreviouslyCommittedEvidence() throws Exception {
        Actor actor = insertActor(fixture.owner);
        var before = fixture.durableRows();
        try (Connection connection = transaction(fixture.dataSource)) {
            JdbcTemplate jdbc = jdbc(connection);
            var aggregate = aggregate(jdbc, actor);
            var lot = insertLot(jdbc, actor, aggregate);
            jdbc.execute("SAVEPOINT v29_new_evidence");
            var acts = insertActs(jdbc, lot);
            jdbc.execute("RELEASE SAVEPOINT v29_new_evidence");
            Tuple tuple = tuple(); lock(jdbc, tuple);
            UUID id = result(jdbc, actor, aggregate, tuple, "DEDUP", revision(), 1);
            try {
                Throwable failure = catchThrowable(() -> reference(jdbc, id, acts.getFirst().id(), actor));
                sqlState(failure, "23514");
                assertThat(failure).hasMessageContaining("transaccion anterior");
            } finally {
                connection.rollback();
            }
        }
        assertThat(fixture.durableRows()).isEqualTo(before);
    }

    @Test void crossLedgerTupleIsUniqueInBothDirectionsIncludingExpiredRows() throws Exception {
        Actor first = insertActor(fixture.owner);
        Tuple existingSupplement = tuple();
        UUID supplement = fixture.committedEmpty(first, existingSupplement);
        fixture.expire(PARENT, supplement);
        Actor newActor = insertActor(fixture.owner);
        reject(jdbc -> acceptance(jdbc, newActor, existingSupplement), "23505");

        Tuple existingEvidence = tuple();
        Written evidence;
        try (Connection connection = transaction(fixture.dataSource)) {
            evidence = acceptance(jdbc(connection), newActor, existingEvidence);
            connection.commit();
        }
        fixture.expire("legal_idempotencia_resultados", evidence.ledgerId());
        reject(jdbc -> {
            lock(jdbc, existingEvidence);
            var aggregate = aggregate(jdbc, first);
            result(jdbc, first, aggregate, existingEvidence, "EMPTY", aggregate.requiredSetRevision(), 0);
        }, "23505");
    }

    @Test void expiredTupleRemainsUniqueWhenTheSubmittedHmacKeyVersionChanges() throws Exception {
        Actor actor = insertActor(fixture.owner);
        Tuple tuple = tuple();
        UUID id = fixture.committedEmpty(actor, tuple);
        fixture.expire(PARENT, id);
        reject(jdbc -> {
            lock(jdbc, tuple);
            var aggregate = aggregate(jdbc, actor);
            result(jdbc, actor, aggregate, tuple, "EMPTY", aggregate.requiredSetRevision(), 0, 2);
        }, "23505");
    }

    @Test void resultRejectsRoleMismatchAndCrossTenantActor() throws Exception {
        Actor actor = insertActor(fixture.owner);
        Actor foreign = insertActor(fixture.owner);
        reject(jdbc -> {
            Tuple tuple = tuple(); lock(jdbc, tuple);
            var aggregate = aggregate(jdbc, actor);
            result(jdbc, new Actor(actor.userId(), actor.workshopId(), "ADMIN", "ADMIN_TITULAR"), aggregate,
                    tuple, "EMPTY", aggregate.requiredSetRevision(), 0);
        }, "23514");
        reject(jdbc -> {
            Tuple tuple = tuple(); lock(jdbc, tuple);
            var aggregate = aggregate(jdbc, actor);
            result(jdbc, new Actor(actor.userId(), foreign.workshopId(), "USER", "USER"), aggregate,
                    tuple, "EMPTY", aggregate.requiredSetRevision(), 0);
        }, "23503");
    }

    @Test void savepointEmptyStillRevalidatesItsObservationAtDeferredBoundary() throws Exception {
        Actor actor = insertActor(fixture.owner);
        fixture.committedEmpty(actor, tuple()); // Ensure aggregate itself is previously committed.
        try (Connection connection = transaction(fixture.dataSource)) {
            JdbcTemplate jdbc = jdbc(connection); Tuple tuple = tuple(); lock(jdbc, tuple);
            var aggregate = aggregate(jdbc, actor);
            jdbc.execute("SAVEPOINT v29_empty_observation");
            result(jdbc, actor, aggregate, tuple, "EMPTY", aggregate.requiredSetRevision(), 0);
            jdbc.execute("RELEASE SAVEPOINT v29_empty_observation");
            removeObservedPointerForFaultInjection(jdbc, aggregate.aggregateId());
            try {
                sqlState(catchThrowable(() -> jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE")), "23514");
            } finally {
                connection.rollback();
            }
        }
    }

    @Test void historicalResultPurgeDoesNotRequireItsObservedAggregateToRemainCurrent() throws Exception {
        Actor actor = insertActor(fixture.owner);
        Written evidence = fixture.committedAcceptance(actor);
        Tuple tuple = tuple();
        UUID id = fixture.committedDedup(actor, tuple, evidence);
        fixture.expire(PARENT, id);
        var before = fixture.durableRows();
        try (Connection connection = transaction(fixture.dataSource)) {
            JdbcTemplate jdbc = jdbc(connection); lock(jdbc, tuple);
            removeObservedPointerForFaultInjection(jdbc, evidence.lot().aggregate().aggregateId());
            jdbc.update("DELETE FROM legal_idempotencia_sin_actos_referencias WHERE resultado_id=?", id);
            jdbc.update("DELETE FROM legal_idempotencia_sin_actos WHERE id=?", id);
            jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
            // Roll back the deliberately invalid editorial fixture; the purge constraints all ran.
            connection.rollback();
        }
        assertThat(fixture.durableRows()).isEqualTo(before);
    }

    private static void removeObservedPointerForFaultInjection(JdbcTemplate jdbc, UUID aggregate) {
        // Fault injection only: do not fabricate a production editorial transition or commit it.
        jdbc.execute("SET LOCAL session_replication_role=replica");
        assertThat(jdbc.update("""
                DELETE FROM legal_requisito_conjuntos_actuales a USING legal_requisito_agregado_scopes s
                WHERE s.agregado_id=? AND s.contexto='USO_CONTINUADO'
                  AND a.contexto=s.contexto AND a.locale=s.locale AND a.audiencia=s.audiencia
                """, aggregate)).isOne();
        jdbc.execute("SET LOCAL session_replication_role=origin");
    }

    @Test void newLedgerStillCannotPointAtPreviouslyCommittedLot() throws Exception {
        Actor actor = insertActor(fixture.owner);
        Written existing = fixture.committedAcceptance(actor);
        reject(jdbc -> {
            jdbc.update("INSERT INTO talleres(nombre) VALUES ('V29 old lot rollback sentinel')");
            Tuple tuple = tuple(); lock(jdbc, tuple);
            ledger(jdbc, existing.lot(), tuple);
        }, "23514");
    }

    @Test void updatesAreImmutableIncludingZeroRowReferenceStatement() throws Exception {
        Actor actor = insertActor(fixture.owner);
        Written evidence = fixture.committedAcceptance(actor);
        UUID id = fixture.committedDedup(actor, tuple(), evidence);
        reject(jdbc -> jdbc.update("UPDATE legal_idempotencia_sin_actos SET fingerprint_hmac = fingerprint_hmac WHERE id = ?", id), "23514");
        for (String condition : List.of("resultado_id = ?", "resultado_id = ? AND false")) reject(jdbc ->
                jdbc.update("UPDATE legal_idempotencia_sin_actos_referencias SET aceptacion_id = aceptacion_id WHERE " + condition, id), "23514");
    }

    @Test void purgeRequiresExpiryTupleLockAndWholeParentGraphAtCommit() throws Exception {
        Actor actor = insertActor(fixture.owner);
        Written evidence = fixture.committedAcceptance(actor);
        Tuple tuple = tuple();
        UUID id = fixture.committedDedup(actor, tuple, evidence);
        reject(jdbc -> { lock(jdbc, tuple); jdbc.update("DELETE FROM legal_idempotencia_sin_actos_referencias WHERE resultado_id = ?", id); }, "23514");
        fixture.expire(PARENT, id);
        reject(jdbc -> jdbc.update("DELETE FROM legal_idempotencia_sin_actos_referencias WHERE resultado_id = ?", id), "55000");
        reject(jdbc -> { lock(jdbc, tuple); jdbc.update("DELETE FROM legal_idempotencia_sin_actos_referencias WHERE resultado_id = ?", id); }, "23514");
        var before = fixture.durableRows();
        try (Connection connection = transaction(fixture.dataSource)) {
            JdbcTemplate jdbc = jdbc(connection); lock(jdbc, tuple);
            jdbc.update("DELETE FROM legal_idempotencia_sin_actos_referencias WHERE resultado_id = ?", id);
            jdbc.update("DELETE FROM legal_idempotencia_sin_actos WHERE id = ?", id);
            connection.commit();
        }
        for (String table : DURABLE_TABLES) assertThat(fixture.durableRows().get(table)).as(table).isEqualTo(before.get(table));
        assertThat(fixture.owner.queryForObject("SELECT count(*) FROM legal_idempotencia_sin_actos WHERE id = ?", Long.class, id)).isZero();
    }

    private static void reject(SqlAction action, String state) throws Exception {
        var before = fixture.durableRows();
        try (Connection connection = transaction(fixture.dataSource)) {
            Throwable failure = catchThrowable(() -> { action.run(jdbc(connection)); connection.commit(); });
            sqlState(failure, state);
            connection.rollback();
        }
        assertThat(fixture.durableRows()).isEqualTo(before);
    }
    @FunctionalInterface interface SqlAction { void run(JdbcTemplate jdbc) throws Exception; }

    @Test void concurrentSupplementCommitRejectsWaitingEvidenceLedgerAndRollsBackItsGraph() throws Exception {
        crossLedgerRace(true);
    }

    @Test void concurrentEvidenceLedgerCommitRejectsWaitingSupplementAndRollsBackItsWrites() throws Exception {
        crossLedgerRace(false);
    }

    private static void crossLedgerRace(boolean supplementWins) throws Exception {
        Actor winnerActor = insertActor(fixture.owner);
        Actor loserActor = insertActor(fixture.owner);
        Tuple collision = tuple();
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        var waiterPid = new java.util.concurrent.CompletableFuture<Integer>();
        var observedWinner = new java.util.concurrent.CountDownLatch(1);
        var allowLosingInsert = new java.util.concurrent.CountDownLatch(1);

        try (Connection first = transaction(fixture.dataSource)) {
            JdbcTemplate firstJdbc = jdbc(first);
            lock(firstJdbc, collision);
            long key = lockKey(firstJdbc, collision);
            int firstPid = firstJdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
            insertRaceResult(firstJdbc, winnerActor, collision, supplementWins);

            java.util.concurrent.Future<Throwable> competingWriter = executor.submit(() -> {
                try (Connection second = transaction(fixture.dataSource)) {
                    JdbcTemplate secondJdbc = jdbc(second);
                    try {
                        // An earlier READ_COMMITTED statement cannot see the uncommitted winner.
                        assertThat(crossLedgerTupleCount(secondJdbc, collision)).isZero();
                        waiterPid.complete(secondJdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                        lock(secondJdbc, collision); // Separate statement; must wait for first COMMIT.
                        assertThat(crossLedgerTupleCount(secondJdbc, collision)).isOne();
                        observedWinner.countDown();
                        assertThat(allowLosingInsert.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

                        // A coordinator would replay here. Deliberately attempt DML to accredit
                        // the SQL guard, including rollback of writes preceding the conflicting INSERT.
                        return catchThrowable(() -> {
                            secondJdbc.update("INSERT INTO talleres(nombre) VALUES (?)",
                                    "V29 losing race sentinel " + UUID.randomUUID());
                            insertRaceResult(secondJdbc, loserActor, collision, !supplementWins);
                        });
                    } finally {
                        second.rollback();
                    }
                } catch (Exception | AssertionError failure) {
                    waiterPid.completeExceptionally(failure);
                    throw failure;
                }
            });

            int secondPid = waiterPid.get(5, java.util.concurrent.TimeUnit.SECONDS);
            awaitCrossLedgerTupleWait(firstJdbc, firstPid, secondPid, key);
            assertThat(competingWriter.isDone()).isFalse();
            first.commit();
            assertThat(observedWinner.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

            // The second connection is paused after its fresh lookup and before any DML.
            var committedWinnerRows = fixture.durableRows();
            allowLosingInsert.countDown();
            Throwable failure = competingWriter.get(10, java.util.concurrent.TimeUnit.SECONDS);
            sqlState(failure, "23505");
            Throwable sqlFailure = failure;
            while (sqlFailure != null && !(sqlFailure instanceof java.sql.SQLException)) {
                sqlFailure = sqlFailure.getCause();
            }
            assertThat(sqlFailure).isInstanceOf(org.postgresql.util.PSQLException.class);
            assertThat(((org.postgresql.util.PSQLException) sqlFailure)
                    .getServerErrorMessage().getConstraint()).isEqualTo("uk_legal_idempotencia_tupla_v29");
            assertThat(fixture.durableRows()).isEqualTo(committedWinnerRows);
            assertThat(crossLedgerTupleCount(fixture.owner, collision)).isOne();
        } finally {
            allowLosingInsert.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
    }

    private static void insertRaceResult(JdbcTemplate jdbc, Actor actor, Tuple tuple, boolean supplement) {
        if (supplement) {
            var observed = aggregate(jdbc, actor);
            result(jdbc, actor, observed, tuple, "EMPTY", observed.requiredSetRevision(), 0);
        } else {
            acceptanceWithLock(jdbc, actor, tuple);
        }
    }

    private static long crossLedgerTupleCount(JdbcTemplate jdbc, Tuple tuple) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM (
                    SELECT operacion, route_template, scope_hmac, idempotency_key_hmac
                    FROM legal_idempotencia_resultados
                    UNION ALL
                    SELECT operacion, route_template, scope_hmac, idempotency_key_hmac
                    FROM legal_idempotencia_sin_actos
                ) results
                WHERE operacion = ? AND route_template = ?
                  AND scope_hmac = ? AND idempotency_key_hmac = ?
                """, Long.class, tuple.operation(), tuple.route(), tuple.scope(), tuple.key());
    }

    private static void awaitCrossLedgerTupleWait(
            JdbcTemplate observer, int holderPid, int waiterPid, long key) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        do {
            Boolean waiting = observer.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1 FROM pg_catalog.pg_locks waiting
                        JOIN pg_catalog.pg_locks held
                          ON held.locktype = waiting.locktype
                         AND held.database = waiting.database
                         AND held.classid = waiting.classid
                         AND held.objid = waiting.objid
                         AND held.objsubid = waiting.objsubid
                        WHERE waiting.locktype = 'advisory'
                          AND waiting.pid = ? AND held.pid = ?
                          AND NOT waiting.granted AND held.granted
                          AND waiting.mode = 'ExclusiveLock' AND held.mode = 'ExclusiveLock'
                          AND waiting.objsubid = 1
                          AND waiting.database = (
                              SELECT oid FROM pg_catalog.pg_database WHERE datname = current_database())
                          AND waiting.classid::bigint = ? AND waiting.objid::bigint = ?
                    )
                    """, Boolean.class, waiterPid, holderPid, (key >>> 32) & 0xffffffffL, key & 0xffffffffL);
            if (Boolean.TRUE.equals(waiting)) return;
            // Bounded observation of a real PostgreSQL wait, not a scheduling assumption or busy spin.
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("El segundo escritor no espero el lock exclusivo de la tupla V29");
    }

}
