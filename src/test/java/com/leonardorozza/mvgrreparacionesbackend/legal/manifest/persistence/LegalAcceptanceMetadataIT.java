package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalActorSnapshot;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequestMetadata;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceMetadataITSupport.Graph;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalIdempotencyCoordinator.Reservation;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalIdempotencyCoordinatorITSupport.Harness;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PerfilAgregadoLegal;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceMetadataITSupport.*;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalIdempotencyCoordinatorITSupport.ACCEPTOR;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalIdempotencyCoordinatorITSupport.KEY_ONE;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalIdempotencyCoordinatorITSupport.REGISTRAR;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalIdempotencyCoordinatorITSupport.inTransaction;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalIdempotencyCoordinatorITSupport.key;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalIdempotencyCoordinatorITSupport.registrationActor;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalIdempotencyCoordinatorITSupport.writerBoundary;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** Real cipher and restricted PostgreSQL writes inside the caller's V29 reservation/transaction. */
class LegalAcceptanceMetadataIT {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_v29_metadata").withUsername("ordenfix").withPassword("ordenfix");
    private static LegalIdempotencyCoordinatorITSupport fixture;
    @TempDir Path directory;
    private Harness acceptance;

    @BeforeAll static void start() { POSTGRES.start(); fixture = new LegalIdempotencyCoordinatorITSupport(POSTGRES); }
    @AfterAll static void stop() { POSTGRES.stop(); }
    @BeforeEach void reset() throws Exception {
        fixture.reset(directory, getClass()); acceptance = fixture.harness(false, KEY_ONE);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void restrictedWriterPersistsRealIpAndOptionalUnicodeUserAgentWithServerDates(boolean includeUserAgent) throws Exception {
        var command = fixture.acceptanceCommand(fixture.actor());
        // 512 Unicode scalars deliberately occupy 2048 UTF-8 bytes; V27 stores character count.
        String userAgent = includeUserAgent ? "🛠".repeat(512) : null;
        var codec = codec(); var writer = writer(acceptance, codec); String idempotencyKey = key();
        Graph written = inTransaction(acceptance, (status, remaining) -> {
            assertThat(acceptance.jdbc().queryForObject("SELECT current_user || ':' || session_user", String.class))
                    .isEqualTo(ACCEPTOR + ":" + ACCEPTOR);
            var reserved = acceptance.coordinator().reserve(command, idempotencyKey, remaining);
            Graph graph = newGraph(acceptance, command.actor(), PerfilAgregadoLegal.AUTHENTICATED_PENDING);
            writer.persist(reserved, command.actor(), codec.prepare(graph.lot().id(), capture(userAgent)));
            assertThat(metadataInserts(acceptance)).isEqualTo(includeUserAgent ? 3 : 2);
            assertThat(acceptance.metrics().snapshot().commits()).isZero();
            assertThat(fixture.owner.queryForObject("SELECT count(*) FROM legal_aceptacion_metadatos WHERE lote_id=?",
                    Long.class, graph.lot().id())).isZero();
            reserved.requireNew(); // Metadata must not consume the reservation before its technical result.
            acceptance.store().persistWithActs(reserved, command.actor(), graph.lot().id());
            acceptance.jdbc().execute("SET CONSTRAINTS ALL IMMEDIATE"); return graph;
        });
        assertThat(acceptance.metrics().snapshot().commits()).isEqualTo(1);
        assertThat(acceptance.metrics().snapshot().rollbacks()).isZero();
        var header = header(fixture.owner, written.lot().id());
        assertThat(header.capturedAt()).isEqualTo(written.lot().acceptedAt().toInstant());
        assertThat(header.retainUntil()).isEqualTo(header.capturedAt().plus(ROUNDED_RETENTION));
        assertThat(header.purgedAt()).isNull();
        var rows = fields(fixture.owner, written.lot().id()); assertThat(rows).hasSize(includeUserAgent ? 2 : 1);
        for (var row : rows) {
            assertThat(row.keyVersion()).isEqualTo(AES_VERSION);
            assertThat(row.nonce()).hasSize(12); assertThat(row.tag()).hasSize(16);
            assertThat(row.ciphertext()).isNotEmpty(); assertThat(row.tombstoneAt()).isNull();
            String expected = row.type().equals("IP") ? IP : userAgent;
            assertThat(decrypt(written.lot().id(), row)).isEqualTo(expected);
            assertThat(row.originalLength()).isEqualTo(expected.codePointCount(0, expected.length()));
        }
        if (includeUserAgent) {
            assertThat(rows.get(0).nonce()).isNotEqualTo(rows.get(1).nonce());
            assertThat(rows.get(1).ciphertext()).hasSize(2048);
        }
        for (String column : List.of("id", "key_version", "nonce", "ciphertext", "tag", "longitud_original")) {
            assertThat(fixture.owner.queryForObject("SELECT has_column_privilege(?, 'public.legal_aceptacion_metadatos_cifrados', ?, 'SELECT')",
                    Boolean.class, ACCEPTOR, column)).isFalse();
        }
        assertThat(fixture.owner.queryForObject("SELECT has_table_privilege(?, 'public.legal_aceptacion_metadatos_cifrados', 'DELETE')",
                Boolean.class, ACCEPTOR)).isFalse();
        for (String sequence : LegalV29AcceptanceITSupport.SEQUENCES) {
            assertThat(fixture.owner.queryForObject("SELECT has_sequence_privilege(?,?, 'USAGE')", Boolean.class, ACCEPTOR, sequence)).isFalse();
        }
        var before = fixture.database.durableRows(); acceptance.metrics().reset();
        var replay = LegalIdempotencyCoordinatorITSupport.replay(acceptance, command, idempotencyKey);
        assertThat(replay.lotId()).isEqualTo(written.lot().id());
        assertThat(fixture.database.durableRows()).isEqualTo(before); noDml(acceptance);
    }

    @Test void registrationRolePersistsMetadataAndItsResultInTheSameAccountTransaction() throws Exception {
        var harness = fixture.harness(true, KEY_ONE);
        var command = fixture.registrationCommand("metadata-registration-password");
        var codec = codec(); var writer = writer(harness, codec); String idempotencyKey = key();
        Graph graph = inTransaction(harness, (status, remaining) -> {
            assertThat(harness.jdbc().queryForObject("SELECT current_user", String.class)).isEqualTo(REGISTRAR);
            var reserved = harness.coordinator().reserve(command, idempotencyKey, remaining);
            var actor = registrationActor(harness.jdbc(), command.registration());
            Graph created = newGraph(harness, actor, PerfilAgregadoLegal.REGISTRATION);
            writer.persist(reserved, actor, codec.prepare(created.lot().id(), capture(null)));
            harness.store().persistWithActs(reserved, actor, created.lot().id());
            harness.jdbc().execute("SET CONSTRAINTS ALL IMMEDIATE"); return created;
        });
        assertThat(harness.metrics().snapshot().commits()).isEqualTo(1);
        assertThat(decrypt(graph.lot().id(), fields(fixture.owner, graph.lot().id()).getFirst())).isEqualTo(IP);
        assertThat(LegalIdempotencyCoordinatorITSupport.replay(harness, command, idempotencyKey).lotId()).isEqualTo(graph.lot().id());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void secondFieldNonceCollisionRollsBackTheHeaderFirstFieldAndBusinessEvenAfterTombstoning(boolean purged) throws Exception {
        var firstCommand = fixture.acceptanceCommand(fixture.actor()); byte[] retainedNonce = nonce(41);
        Graph original = commitMetadata(firstCommand, codec(retainedNonce), null, false, key());
        if (purged) ageAndPurge(fixture, original.lot().id());
        var retained = fields(fixture.owner, original.lot().id()).getFirst();
        assertThat(retained.nonce()).isEqualTo(retainedNonce);
        if (purged) {
            assertThat(retained.ciphertext()).isNull(); assertThat(retained.tag()).isNull();
            assertThat(retained.originalLength()).isNull(); assertThat(retained.tombstoneAt()).isNotNull();
            assertThat(header(fixture.owner, original.lot().id()).purgedAt()).isNotNull();
        }
        var command = fixture.acceptanceCommand(fixture.actor());
        var codec = codec(nonce(42), retainedNonce); var writer = writer(acceptance, codec);
        var before = fixture.database.durableRows(); var attemptedLot = new AtomicReference<UUID>();
        Throwable failure = catchThrowable(() -> inTransaction(acceptance, (status, remaining) -> {
            var reserved = acceptance.coordinator().reserve(command, key(), remaining);
            Graph graph = newGraph(acceptance, command.actor(), PerfilAgregadoLegal.AUTHENTICATED_PENDING);
            attemptedLot.set(graph.lot().id());
            var prepared = codec.prepare(graph.lot().id(), capture("Second field deliberately collides"));
            acceptance.metrics().reset();
            writer.persist(reserved, command.actor(), prepared); return null;
        }));
        unavailable(failure); assertSqlState(failure, "23505");
        assertThat(metadataInserts(acceptance)).isEqualTo(3); assertThat(failedMetadataInserts(acceptance)).isEqualTo(1);
        assertThat(acceptance.metrics().snapshot().rollbacks()).isEqualTo(1);
        assertThat(fixture.database.durableRows()).isEqualTo(before);
        assertThat(fixture.owner.queryForObject("SELECT count(*) FROM legal_aceptacion_lotes WHERE id=?", Long.class, attemptedLot.get())).isZero();
        assertThat(fields(fixture.owner, original.lot().id()).getFirst().nonce()).isEqualTo(retainedNonce);
    }

    @ParameterizedTest @ValueSource(strings = {"actor", "foreign-codec", "null-prepared"})
    void invalidActorOrPreparedValueFailsBeforeMetadataDmlAndMarksTheCallerRollbackOnly(String invalid) throws Exception {
        var command = fixture.acceptanceCommand(fixture.actor());
        LegalActorSnapshot otherActor = fixture.actor();
        var codec = codec(); var writer = writer(acceptance, codec); var before = fixture.database.durableRows();
        Throwable failure = catchThrowable(() -> inTransaction(acceptance, (status, remaining) -> {
            var reserved = acceptance.coordinator().reserve(command, key(), remaining);
            Graph graph = newGraph(acceptance, command.actor(), PerfilAgregadoLegal.AUTHENTICATED_PENDING);
            var prepared = invalid.equals("null-prepared") ? null
                    : (invalid.equals("foreign-codec") ? codec() : codec).prepare(graph.lot().id(), capture(null));
            acceptance.metrics().reset();
            Throwable rejected = catchThrowable(() -> writer.persist(reserved,
                    invalid.equals("actor") ? otherActor : command.actor(), prepared));
            unavailable(rejected); noDml(acceptance); assertThat(status.isRollbackOnly()).isTrue();
            throw (LegalIdempotencyException) rejected;
        }));
        unavailable(failure); assertThat(fixture.database.durableRows()).isEqualTo(before);
    }

    @Test void anotherActorsNewLotCannotReceiveMetadataForTheReservedActor() throws Exception {
        var command = fixture.acceptanceCommand(fixture.actor()); var otherActor = fixture.actor();
        var codec = codec(); var writer = writer(acceptance, codec); var before = fixture.database.durableRows();
        Throwable failure = catchThrowable(() -> inTransaction(acceptance, (status, remaining) -> {
            var reserved = acceptance.coordinator().reserve(command, key(), remaining);
            writerBoundary(acceptance.jdbc(), command.actor());
            Graph foreign = newGraph(acceptance, otherActor, PerfilAgregadoLegal.AUTHENTICATED_PENDING);
            var prepared = codec.prepare(foreign.lot().id(), capture(null));
            acceptance.metrics().reset(); writer.persist(reserved, command.actor(), prepared); return null;
        }));
        unavailable(failure); noDml(acceptance); assertThat(fixture.database.durableRows()).isEqualTo(before);
    }

    @Test void committedHistoricalLotCannotBeGivenNewMetadata() throws Exception {
        var command = fixture.acceptanceCommand(fixture.actor());
        Graph historical = commitMetadata(command, codec(), null, false, key());
        var codec = codec(); var writer = writer(acceptance, codec); var before = fixture.database.durableRows();
        Throwable failure = catchThrowable(() -> inTransaction(acceptance, (status, remaining) -> {
            var reserved = acceptance.coordinator().reserve(command, key(), remaining);
            writerBoundary(acceptance.jdbc(), command.actor());
            var prepared = codec.prepare(historical.lot().id(), capture("New capture cannot rewrite history"));
            acceptance.metrics().reset(); writer.persist(reserved, command.actor(), prepared); return null;
        }));
        unavailable(failure); noDml(acceptance); assertThat(fixture.database.durableRows()).isEqualTo(before);
    }

    @Test void newLotWithoutActsFailsBeforeMetadataDml() throws Exception {
        var command = fixture.acceptanceCommand(fixture.actor()); var codec = codec();
        var writer = writer(acceptance, codec); var before = fixture.database.durableRows();
        Throwable failure = catchThrowable(() -> inTransaction(acceptance, (status, remaining) -> {
            var reserved = acceptance.coordinator().reserve(command, key(), remaining);
            var lot = emptyLot(acceptance, command.actor()); var prepared = codec.prepare(lot.id(), capture(null));
            acceptance.metrics().reset(); writer.persist(reserved, command.actor(), prepared); return null;
        }));
        unavailable(failure); noDml(acceptance); assertThat(fixture.database.durableRows()).isEqualTo(before);
    }

    @Test void repeatedHeaderInTheSameTransactionRejectsBeforeAnySecondMetadataInsert() throws Exception {
        var command = fixture.acceptanceCommand(fixture.actor()); var codec = codec();
        var writer = writer(acceptance, codec); var before = fixture.database.durableRows();
        Throwable failure = catchThrowable(() -> inTransaction(acceptance, (status, remaining) -> {
            var reserved = acceptance.coordinator().reserve(command, key(), remaining);
            Graph graph = newGraph(acceptance, command.actor(), PerfilAgregadoLegal.AUTHENTICATED_PENDING);
            writer.persist(reserved, command.actor(), codec.prepare(graph.lot().id(), capture(null)));
            var second = codec.prepare(graph.lot().id(), capture("Duplicate capture"));
            acceptance.metrics().reset();
            Throwable rejected = catchThrowable(() -> writer.persist(reserved, command.actor(), second));
            unavailable(rejected); noDml(acceptance); assertThat(status.isRollbackOnly()).isTrue();
            throw (LegalIdempotencyException) rejected;
        }));
        unavailable(failure); assertThat(fixture.database.durableRows()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"jdbc", "transaction"})
    void reservationFromAnotherJdbcOrCompletedTransactionCannotAuthorizeMetadata(String invalid) throws Exception {
        var command = fixture.acceptanceCommand(fixture.actor()); var codec = codec();
        var second = fixture.harness(false, KEY_ONE); var foreignWriter = writer(second, codec);
        var before = fixture.database.durableRows(); var saved = new AtomicReference<Reservation>();
        if (invalid.equals("transaction")) {
            inTransaction(acceptance, (status, remaining) -> {
                saved.set(acceptance.coordinator().reserve(command, key(), remaining)); return null;
            });
        }
        Harness active = invalid.equals("jdbc") ? acceptance : second;
        Throwable failure = catchThrowable(() -> inTransaction(active, (status, remaining) -> {
            Reservation reserved = invalid.equals("jdbc")
                    ? acceptance.coordinator().reserve(command, key(), remaining) : saved.get();
            var prepared = codec.prepare(UUID.randomUUID(), capture(null));
            acceptance.metrics().reset(); second.metrics().reset();
            foreignWriter.persist(reserved, command.actor(), prepared); return null;
        }));
        unavailable(failure); noDml(acceptance); noDml(second);
        assertThat(second.metrics().snapshot().statementExecutions()).isZero();
        assertThat(fixture.database.durableRows()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"replay", "completed-reservation"})
    void replayOrAnAlreadyCompletedReservationCannotCaptureMetadata(String invalid) throws Exception {
        var command = fixture.acceptanceCommand(fixture.actor()); var codec = codec();
        var writer = writer(acceptance, codec); String idempotencyKey = key();
        Graph historical = invalid.equals("replay") ? commitMetadata(command, codec, null, true, idempotencyKey) : null;
        var before = fixture.database.durableRows();
        Throwable failure = catchThrowable(() -> inTransaction(acceptance, (status, remaining) -> {
            var reserved = acceptance.coordinator().reserve(command, idempotencyKey, remaining);
            UUID lotId;
            if (historical != null) lotId = historical.lot().id();
            else {
                Graph graph = newGraph(acceptance, command.actor(), PerfilAgregadoLegal.AUTHENTICATED_PENDING);
                lotId = graph.lot().id();
                writer.persist(reserved, command.actor(), codec.prepare(lotId, capture(null)));
                acceptance.store().persistWithActs(reserved, command.actor(), lotId);
            }
            var prepared = codec.prepare(lotId, capture("No repeated capture"));
            acceptance.metrics().reset(); writer.persist(reserved, command.actor(), prepared); return null;
        }));
        unavailable(failure); noDml(acceptance); assertThat(fixture.database.durableRows()).isEqualTo(before);
    }

    @Test void expiredOuterDeadlineFailsBeforeAnotherSqlStatementOrMetadataInsert() throws Exception {
        var command = fixture.acceptanceCommand(fixture.actor()); var codec = codec();
        var writer = writer(acceptance, codec); var before = fixture.database.durableRows();
        var budget = new AtomicInteger(Integer.MAX_VALUE);
        Throwable failure = catchThrowable(() -> inTransaction(acceptance, (status, remaining) -> {
            var reserved = acceptance.coordinator().reserve(command, key(), () -> Math.min(budget.get(), remaining.getAsInt()));
            Graph graph = newGraph(acceptance, command.actor(), PerfilAgregadoLegal.AUTHENTICATED_PENDING);
            var prepared = codec.prepare(graph.lot().id(), capture(null));
            acceptance.metrics().reset(); budget.set(0);
            Throwable rejected = catchThrowable(() -> writer.persist(reserved, command.actor(), prepared));
            unavailable(rejected); assertThat(status.isRollbackOnly()).isTrue();
            throw (LegalIdempotencyException) rejected;
        }));
        unavailable(failure); noDml(acceptance);
        assertThat(acceptance.metrics().snapshot().statementExecutions()).isZero();
        assertThat(fixture.database.durableRows()).isEqualTo(before);
    }

    @Test void retentionAlreadyElapsedAtTheServerFailsBeforeMetadataDmlAndRollsBackTheGraph() throws Exception {
        var command = fixture.acceptanceCommand(fixture.actor()); var codec = codec();
        var writer = new LegalAcceptanceMetadataWriter(acceptance.jdbc(), codec,
                new LegalAcceptanceMetadataPolicy(Duration.ofNanos(1)));
        var before = fixture.database.durableRows();
        Throwable failure = catchThrowable(() -> inTransaction(acceptance, (status, remaining) -> {
            var reserved = acceptance.coordinator().reserve(command, key(), remaining);
            Graph graph = newGraph(acceptance, command.actor(), PerfilAgregadoLegal.AUTHENTICATED_PENDING);
            var prepared = codec.prepare(graph.lot().id(), capture(null));
            awaitMinimalRetentionElapsed(acceptance, graph.lot().id());
            acceptance.metrics().reset();
            Throwable rejected = catchThrowable(() -> writer.persist(reserved, command.actor(), prepared));
            unavailable(rejected); noDml(acceptance); assertThat(status.isRollbackOnly()).isTrue();
            throw (LegalIdempotencyException) rejected;
        }));
        unavailable(failure); assertThat(acceptance.metrics().snapshot().rollbacks()).isEqualTo(1);
        assertThat(fixture.database.durableRows()).isEqualTo(before);
    }

    @Test void missingIpPreparationAbortsTheCallerWithoutInsertingMetadata() throws Exception {
        var command = fixture.acceptanceCommand(fixture.actor()); var codec = codec();
        var before = fixture.database.durableRows();
        Throwable failure = catchThrowable(() -> inTransaction(acceptance, (status, remaining) -> {
            acceptance.coordinator().reserve(command, key(), remaining);
            Graph graph = newGraph(acceptance, command.actor(), PerfilAgregadoLegal.AUTHENTICATED_PENDING);
            acceptance.metrics().reset();
            codec.prepare(graph.lot().id(), LegalRequestMetadata.of(null, "Untrusted input without a peer")); return null;
        }));
        assertThat(failure).isInstanceOf(IllegalArgumentException.class).hasNoCause(); noDml(acceptance);
        assertThat(acceptance.metrics().snapshot().rollbacks()).isEqualTo(1);
        assertThat(fixture.database.durableRows()).isEqualTo(before);
    }

    @Test void callerFailureAfterSuccessfulMetadataInsertionRollsBackAllBusinessAndCiphertext() throws Exception {
        var command = fixture.acceptanceCommand(fixture.actor()); var codec = codec();
        var writer = writer(acceptance, codec); var before = fixture.database.durableRows();
        var deliberate = new IllegalStateException("Failure after metadata write");
        Throwable failure = catchThrowable(() -> inTransaction(acceptance, (status, remaining) -> {
            var reserved = acceptance.coordinator().reserve(command, key(), remaining);
            Graph graph = newGraph(acceptance, command.actor(), PerfilAgregadoLegal.AUTHENTICATED_PENDING);
            writer.persist(reserved, command.actor(), codec.prepare(graph.lot().id(), capture("Caller rollback")));
            acceptance.jdbc().execute("SET CONSTRAINTS ALL IMMEDIATE"); throw deliberate;
        }));
        assertThat(failure).isSameAs(deliberate); assertThat(metadataInserts(acceptance)).isEqualTo(3);
        assertThat(acceptance.metrics().snapshot().rollbacks()).isEqualTo(1);
        assertThat(fixture.database.durableRows()).isEqualTo(before);
    }

    private Graph commitMetadata(LegalAcceptanceCommand command, LegalAcceptanceMetadataCodec codec,
                                 String userAgent, boolean result, String idempotencyKey) {
        var harness = fixture.harness(false, KEY_ONE); var writer = writer(harness, codec);
        return inTransaction(harness, (status, remaining) -> {
            var reserved = harness.coordinator().reserve(command, idempotencyKey, remaining);
            Graph graph = newGraph(harness, command.actor(), PerfilAgregadoLegal.AUTHENTICATED_PENDING);
            writer.persist(reserved, command.actor(), codec.prepare(graph.lot().id(), capture(userAgent)));
            if (result) harness.store().persistWithActs(reserved, command.actor(), graph.lot().id());
            harness.jdbc().execute("SET CONSTRAINTS ALL IMMEDIATE"); return graph;
        });
    }

    private static void unavailable(Throwable failure) {
        assertThat(failure).isInstanceOf(LegalIdempotencyException.class);
        assertThat(((LegalIdempotencyException) failure).reason()).isEqualTo(LegalIdempotencyException.Reason.UNAVAILABLE);
    }

    private static void assertSqlState(Throwable failure, String state) {
        Throwable current = failure;
        while (current != null && !(current instanceof SQLException)) current = current.getCause();
        assertThat(current).isInstanceOf(SQLException.class);
        assertThat(((SQLException) current).getSQLState()).isEqualTo(state);
    }
}
