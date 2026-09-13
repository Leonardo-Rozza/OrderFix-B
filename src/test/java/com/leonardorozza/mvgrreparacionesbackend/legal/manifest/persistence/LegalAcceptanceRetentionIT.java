package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.WorkshopClosureGate;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.WorkshopClosureStore;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceProtocolFeasibilityITSupport.Actor;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalV29AcceptanceITSupport.Tuple;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceProtocolFeasibilityITSupport.Written;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceProtocolFeasibilityITSupport.*;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalV29AcceptanceITSupport.*;
import static org.assertj.core.api.Assertions.*;

/** PostgreSQL 16, the actual V34 history, frozen canonical writers and a separate maintenance credential. */
@Testcontainers
class LegalAcceptanceRetentionIT {
    @Container static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_maintenance_retention").withUsername("ordenfix").withPassword("fixture-owner-only");
    @TempDir static Path directory;
    private static LegalV29AcceptanceITSupport fixture;
    private static JdbcTemplate owner;
    private static LegalRestrictedMaintenanceRoleFixture.Credentials credentials;
    private Harness maintenance;

    @BeforeAll static void database() throws Exception {
        fixture = new LegalV29AcceptanceITSupport(PG);
        fixture.migrate("34"); owner = fixture.owner;
        // seedCatalog/committedAcceptance use the existing canonical row writers without capability bypass.
        fixture.seedCatalog(directory, LegalAcceptanceRetentionIT.class);
        credentials = LegalRestrictedMaintenanceRoleFixture.provision(owner, "ordenfix_legal_maintenance_retention", "fixture-maintenance-only");
    }
    @BeforeEach void reset() {
        requireDisposable();
        owner.execute("TRUNCATE public.talleres RESTART IDENTITY CASCADE");
        maintenance = harness(JdbcTemplate::new);
    }
    @AfterEach void closeHarness() { maintenance.close(); }

    @Test void activeRetentionAndResultsRemainUntouchedAndNoKeysAreConfigured() throws Exception {
        Actor actor = insertActor(owner); Written evidence = write(actor, true, null);
        fixture.committedDedup(actor, tuple(), evidence); fixture.committedEmpty(actor, tuple());
        var before = physicalRows();
        assertThat(maintenance.service().runNext()).isEqualTo(new LegalAcceptanceRetentionService.Batch(0,0,0,0,0,0,false));
        assertThat(physicalRows()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void expiredMetadataIsTombstonedAtomicallyAndNonceReservationIsRetained(boolean userAgent) throws Exception {
        Actor actor = insertActor(owner); Written evidence = write(actor, userAgent, null);
        UUID lot = evidence.lot().id(); var canonical = canonicalRows();
        var nonceRows = owner.queryForList("SELECT tipo,key_version,encode(nonce,'hex') AS nonce FROM legal_aceptacion_metadatos_cifrados WHERE lote_id=? ORDER BY tipo", lot);
        ageMetadata(lot, OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1));
        var batch = maintenance.service().runNext();
        assertThat(batch.metadataHeaders()).isEqualTo(1); assertThat(batch.metadataFields()).isEqualTo(userAgent ? 2 : 1);
        assertThat(batch.resultsWithActs()).isZero(); assertThat(batch.pending()).isFalse();
        assertThat(owner.queryForList("SELECT tipo,key_version,encode(nonce,'hex') AS nonce FROM legal_aceptacion_metadatos_cifrados WHERE lote_id=? ORDER BY tipo", lot)).isEqualTo(nonceRows);
        assertThat(owner.queryForObject("SELECT bool_and(ciphertext IS NULL AND tag IS NULL AND longitud_original IS NULL AND tombstone_en IS NOT NULL) FROM legal_aceptacion_metadatos_cifrados WHERE lote_id=?", Boolean.class, lot)).isTrue();
        assertThat(owner.queryForObject("SELECT purgado_en>=retener_hasta FROM legal_aceptacion_metadatos WHERE lote_id=?", Boolean.class, lot)).isTrue();
        // Aging is a fixture-only change to accepted_at; compare the immutable evidence columns after aging.
        assertThat(canonicalRows().get("legal_aceptaciones")).isEqualTo(canonical.get("legal_aceptaciones"));
        assertThat(canonicalRows().get("legal_aceptacion_documentos")).isEqualTo(canonical.get("legal_aceptacion_documentos"));
        var after = physicalRows();
        assertThat(maintenance.service().runNext()).isEqualTo(new LegalAcceptanceRetentionService.Batch(0,0,0,0,0,0,false));
        assertThat(physicalRows()).isEqualTo(after);
    }

    @Test void retainedNonceStillRejectsASecondCanonicalWriteAfterMaintenance() throws Exception {
        byte[] nonce = new byte[12]; Arrays.fill(nonce, (byte) 37);
        Written first = write(insertActor(owner), false, nonce);
        ageMetadata(first.lot().id(), OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1));
        assertThat(maintenance.service().runNext().metadataFields()).isEqualTo(1);
        long count = owner.queryForObject("SELECT count(*) FROM legal_aceptacion_metadatos_cifrados", Long.class);
        assertThatThrownBy(() -> write(insertActor(owner), false, nonce)).satisfies(failure -> assertThat(sqlState(failure)).isEqualTo("23505"));
        assertThat(owner.queryForObject("SELECT count(*) FROM legal_aceptacion_metadatos_cifrados", Long.class)).isEqualTo(count);
    }

    @Test void expiredNewEmptyAndDedupResultsAreDeletedWithoutDeletingCanonicalActs() throws Exception {
        Actor actor = insertActor(owner); Written evidence = write(actor, true, null);
        UUID empty = fixture.committedEmpty(actor, tuple()), dedup = fixture.committedDedup(actor, tuple(), evidence);
        ageResult("legal_idempotencia_resultados", evidence.ledgerId());
        ageResult(PARENT, empty); ageResult(PARENT, dedup); var canonical = canonicalRows();
        var batch = maintenance.service().runNext();
        assertThat(batch.resultsWithActs()).isEqualTo(1); assertThat(batch.resultsWithoutActs()).isEqualTo(2);
        assertThat(batch.references()).isEqualTo(evidence.acts().size()); assertThat(batch.metadataHeaders()).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM legal_idempotencia_sin_actos_referencias", Long.class)).isZero();
        assertThat(canonicalRows()).isEqualTo(canonical);
    }

    @ParameterizedTest @ValueSource(ints = {-1, 0, 1})
    void metadataUsesTheDatabaseTransactionInstantAtTheExactMicrosecondBoundary(int micros) throws Exception {
        Written evidence = write(insertActor(owner), false, null);
        maintenance.close(); AtomicBoolean checkpoint = new AtomicBoolean();
        maintenance = harness(source -> new JdbcTemplate(source) {
            @Override public <T> List<T> query(String sql, RowMapper<T> mapper) {
                if (sql.contains("WHERE m.purgado_en") && checkpoint.compareAndSet(false, true)) {
                    OffsetDateTime cutoff = queryForObject("SELECT transaction_timestamp()", OffsetDateTime.class);
                    try { ageMetadata(evidence.lot().id(), cutoff.plusNanos(micros * 1000L)); }
                    catch (Exception failure) { throw new AssertionError(failure); }
                }
                return super.query(sql, mapper);
            }
        });
        var batch = maintenance.service().runNext();
        assertThat(checkpoint).isTrue(); assertThat(batch.metadataHeaders()).isEqualTo(micros <= 0 ? 1 : 0);
    }

    @Test void eachCategoryIsBoundedToTenAndLaterPassesFinishTheObservedBacklog() throws Exception {
        for (int i = 0; i < 11; i++) {
            Actor actor = insertActor(owner);
            Written evidence = write(actor, false, null);
            ageMetadata(evidence.lot().id(), OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1));
            ageResult("legal_idempotencia_resultados", evidence.ledgerId());
            ageResult(PARENT, fixture.committedEmpty(actor, tuple()));
        }
        var first = maintenance.service().runNext();
        assertThat(first).isEqualTo(new LegalAcceptanceRetentionService.Batch(10,10,10,10,0,0,true));
        assertThat(maintenance.service().runNext()).isEqualTo(new LegalAcceptanceRetentionService.Batch(1,1,1,1,0,0,false));
        assertThat(maintenance.service().runNext()).isEqualTo(new LegalAcceptanceRetentionService.Batch(0,0,0,0,0,0,false));
    }

    @Test void aFailureBetweenCipherTombstoneAndHeaderRollsBackTheWholeBatchThenCanRetry() throws Exception {
        Written evidence = write(insertActor(owner), true, null); ageMetadata(evidence.lot().id(), OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1));
        var before = physicalRows(); maintenance.close();
        maintenance = harness(source -> new JdbcTemplate(source) {
            @Override public int update(String sql, Object... arguments) {
                if (sql.contains("SET purgado_en=transaction_timestamp()")) throw new IllegalStateException("fixture failure, no external secret");
                return super.update(sql, arguments);
            }
        });
        unavailable(maintenance.service()::runNext); assertThat(physicalRows()).isEqualTo(before);
        maintenance.close(); maintenance = harness(JdbcTemplate::new);
        assertThat(maintenance.service().runNext().metadataFields()).isEqualTo(2);
    }

    @Test void aFailureAfterDeletingReferencesRestoresReferencesParentAndAnyEarlierMetadata() throws Exception {
        Actor actor = insertActor(owner); Written evidence = write(actor, false, null);
        UUID dedup = fixture.committedDedup(actor, tuple(), evidence);
        ageMetadata(evidence.lot().id(), OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1)); ageResult(PARENT, dedup);
        var before = physicalRows(); maintenance.close();
        maintenance = harness(source -> new JdbcTemplate(source) {
            @Override public int update(String sql, Object... arguments) {
                if (sql.startsWith("DELETE FROM public.legal_idempotencia_sin_actos WHERE")) throw new IllegalStateException("fixture failure");
                return super.update(sql, arguments);
            }
        });
        unavailable(maintenance.service()::runNext); assertThat(physicalRows()).isEqualTo(before);
        maintenance.close(); maintenance = harness(JdbcTemplate::new);
        assertThat(maintenance.service().runNext().references()).isEqualTo(evidence.acts().size());
    }

    @Test void aBusyHeaderIsSkippedWithoutTakingCipherLocksAndCanBeRetried() throws Exception {
        Written evidence = write(insertActor(owner), false, null); ageMetadata(evidence.lot().id(), OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1));
        try (Connection held = transaction(fixture.dataSource)) {
            jdbc(held).queryForList("SELECT lote_id FROM legal_aceptacion_metadatos WHERE lote_id=? FOR UPDATE", evidence.lot().id());
            assertThat(maintenance.service().runNext()).isEqualTo(new LegalAcceptanceRetentionService.Batch(0,0,0,0,0,1,true));
            held.rollback();
        }
        assertThat(maintenance.service().runNext().metadataHeaders()).isEqualTo(1);
    }

    @Test void theReplayTupleLockProtectsExpiredRowsRegardlessOfTheirStoredKeyVersion() throws Exception {
        Actor actor = insertActor(owner); Tuple tuple = tuple(); UUID result;
        try (Connection connection = transaction(fixture.dataSource)) {
            JdbcTemplate db = jdbc(connection); lock(db, tuple); var aggregate = aggregate(db, actor);
            result = result(db, actor, aggregate, tuple, "EMPTY", aggregate.requiredSetRevision(), 0, 99); connection.commit();
        }
        ageResult(PARENT, result);
        try (Connection replay = transaction(fixture.dataSource)) {
            lock(jdbc(replay), tuple);
            assertThat(maintenance.service().runNext()).isEqualTo(new LegalAcceptanceRetentionService.Batch(0,0,0,0,0,1,true));
            assertThat(jdbc(replay).queryForObject("SELECT count(*) FROM legal_idempotencia_sin_actos WHERE id=?", Long.class, result)).isEqualTo(1);
            replay.rollback();
        }
        assertThat(maintenance.service().runNext().resultsWithoutActs()).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings = {"workshop", "editorial"})
    void exclusiveCoordinatorsAreNeverWaitedOnAfterADataRowIsLocked(String kind) throws Exception {
        Written evidence = write(insertActor(owner), false, null); ageMetadata(evidence.lot().id(), OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1));
        try (Connection held = transaction(fixture.dataSource)) {
            String key = kind.equals("workshop") ? WorkshopClosureGate.LOCK_PREFIX + evidence.lot().actor().workshopId() : LegalManifestDatabaseGate.EDITORIAL_LOCK_NAME;
            jdbc(held).queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", key);
            assertThat(maintenance.service().runNext()).isEqualTo(new LegalAcceptanceRetentionService.Batch(0,0,0,0,0,1,true));
            held.rollback();
        }
        assertThat(maintenance.service().runNext().metadataHeaders()).isEqualTo(1);
    }

    @Test void twoWorkersCanObserveTheSameCandidatesButOnlyOnePurgesEachTarget() throws Exception {
        Written evidence = write(insertActor(owner), false, null); ageMetadata(evidence.lot().id(), OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1));
        ageResult("legal_idempotencia_resultados", evidence.ledgerId()); maintenance.close();
        var selected = new CountDownLatch(2);
        Function<LegalPrivateRequirementsDataSource, JdbcTemplate> instrument = source -> new JdbcTemplate(source) {
            private boolean reached;
            @Override public <T> List<T> query(String sql, RowMapper<T> mapper) {
                List<T> rows = super.query(sql, mapper);
                if (sql.contains("WHERE m.purgado_en") && !reached) { reached = true; selected.countDown(); await(selected); }
                return rows;
            }
        };
        maintenance = harness(instrument);
        try (Harness other = harness(instrument); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var one = executor.submit(maintenance.service()::runNext); var two = executor.submit(other.service()::runNext);
            var first = one.get(20, TimeUnit.SECONDS); var second = two.get(20, TimeUnit.SECONDS);
            assertThat(first.metadataHeaders() + second.metadataHeaders()).isEqualTo(1);
            assertThat(first.resultsWithActs() + second.resultsWithActs()).isEqualTo(1);
        }
    }

    @Test void metadataAndResultsCanExpireInAClosedWorkshopAfterItsRestorationWindow() throws Exception {
        Actor actor = insertActor(owner); Written evidence = write(actor, false, null);
        long titular = owner.queryForObject("""
                INSERT INTO users(username,email,password,role,taller_id,active,email_verificado,token_version)
                VALUES(?,?,'fixture-hash','ADMIN',?,true,true,0) RETURNING id
                """, Long.class, UUID.randomUUID().toString(), UUID.randomUUID()+"@synthetic.invalid", actor.workshopId());
        var tx = new TransactionTemplate(new DataSourceTransactionManager(fixture.dataSource)); tx.setIsolationLevel(Connection.TRANSACTION_READ_COMMITTED);
        tx.executeWithoutResult(status -> {
            new WorkshopClosureGate(owner).lockExclusive(actor.workshopId());
            new WorkshopClosureStore(owner, Clock.fixed(Instant.now().minus(Duration.ofDays(8)), ZoneOffset.UTC))
                    .restrict(actor.workshopId(), titular, UUID.randomUUID());
        });
        ageMetadata(evidence.lot().id(), OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1)); ageResult("legal_idempotencia_resultados", evidence.ledgerId());
        var batch = maintenance.service().runNext();
        assertThat(batch.metadataHeaders()).isEqualTo(1); assertThat(batch.resultsWithActs()).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT cierre_estado FROM talleres WHERE id=?", String.class, actor.workshopId())).isEqualTo("RESTRINGIDO");
    }

    @Test void theBatchAndFailuresNeverExposeTargetIdentitiesOrPersistedHmacs() throws Exception {
        Written evidence = write(insertActor(owner), false, null); ageResult("legal_idempotencia_resultados", evidence.ledgerId());
        var batch = maintenance.service().runNext();
        assertThat(batch.toString()).isEqualTo("LegalAcceptanceRetentionService.Batch[redacted]");
        assertThat(Arrays.stream(batch.getClass().getRecordComponents()).map(component -> component.getType()).toList())
                .allMatch(type -> type == long.class || type == boolean.class);
    }

    private Harness harness(Function<LegalPrivateRequirementsDataSource, JdbcTemplate> factory) {
        var config = new LegalAcceptanceMaintenanceConfiguration();
        var environment = new MockEnvironment().withProperty(LegalAcceptanceMaintenanceConfiguration.PROPERTY_PREFIX + "jdbc-url", credentials.jdbcUrl())
                .withProperty(LegalAcceptanceMaintenanceConfiguration.PROPERTY_PREFIX + "username", credentials.username())
                .withProperty(LegalAcceptanceMaintenanceConfiguration.PROPERTY_PREFIX + "password", credentials.password());
        var pool = config.legalMaintenancePool(environment); var source = config.legalMaintenanceSource(pool);
        JdbcTemplate jdbc = factory.apply(source); var transaction = config.legalMaintenanceTransaction(config.legalMaintenanceManager(source));
        var boundary = new LegalAcceptanceMaintenanceBoundary(jdbc, source, transaction,
                config.legalMaintenanceSchema(jdbc), config.legalMaintenancePrivileges(jdbc, environment));
        return new Harness(new LegalAcceptanceRetentionService(jdbc, boundary), source, pool);
    }
    private static Written write(Actor actor, boolean userAgent, byte[] fixedNonce) throws Exception {
        try (Connection connection = transaction(fixture.dataSource)) {
            JdbcTemplate db = jdbc(connection); Tuple tuple = tuple(); lock(db, tuple); var aggregate = aggregate(db, actor);
            var lot = insertLot(db, actor, aggregate); var acts = insertActs(db, lot); int documents = insertDocuments(db, acts);
            db.update("INSERT INTO legal_aceptacion_metadatos(lote_id,capturado_en,retener_hasta) VALUES(?,statement_timestamp(),statement_timestamp()+INTERVAL '30 days')", lot.id());
            for (String type : userAgent ? List.of("IP", "USER_AGENT") : List.of("IP")) {
                byte[] nonce = fixedNonce == null ? new byte[12] : fixedNonce.clone();
                if (fixedNonce == null) new java.security.SecureRandom().nextBytes(nonce);
                // Synthetic encrypted bytes exercise shape and global nonce uniqueness; no decryption is required by retention.
                db.update("""
                        INSERT INTO legal_aceptacion_metadatos_cifrados(lote_id,tipo,key_version,nonce,ciphertext,tag,longitud_original)
                        VALUES(?,?,17,?,?,?,9)
                        """, lot.id(), type, nonce, new byte[9], new byte[16]);
            }
            long ledger = ledger(db, lot, tuple); connection.commit(); return new Written(lot, acts, documents, ledger);
        }
    }
    /** Existing metadata IT aging pattern, isolated to this disposable DB; maintenance runs with all guards active. */
    private static void ageMetadata(UUID lot, OffsetDateTime expires) throws Exception {
        requireDisposable();
        try (Connection connection = transaction(fixture.dataSource)) {
            JdbcTemplate db = jdbc(connection); db.execute("SET LOCAL session_replication_role=replica");
            db.update("UPDATE legal_aceptacion_lotes SET aceptado_en=? WHERE id=?", expires.minusDays(30), lot);
            db.update("UPDATE legal_aceptacion_metadatos SET capturado_en=?,retener_hasta=? WHERE lote_id=?", expires.minusDays(30), expires, lot);
            db.execute("SET LOCAL session_replication_role=origin"); connection.commit();
        }
    }
    private static void ageResult(String table, Object id) throws Exception {
        requireDisposable(); if (!List.of("legal_idempotencia_resultados", PARENT).contains(table)) throw new AssertionError("Fixture table");
        try (Connection connection = transaction(fixture.dataSource)) {
            JdbcTemplate db = jdbc(connection); db.execute("SET LOCAL session_replication_role=replica");
            db.update("UPDATE " + table + " SET completed_at=statement_timestamp()-INTERVAL '50 hours',expires_at=statement_timestamp()-INTERVAL '25 hours' WHERE id=?", id);
            db.execute("SET LOCAL session_replication_role=origin"); connection.commit();
        }
    }
    private static Map<String, List<String>> canonicalRows() {
        Map<String, List<String>> result = new TreeMap<>();
        for (String table : List.of("legal_aceptacion_lotes", "legal_aceptaciones", "legal_aceptacion_documentos"))
            result.put(table, owner.queryForList("SELECT to_jsonb(r)::text||':'||xmin::text FROM " + table + " r ORDER BY 1", String.class));
        return result;
    }
    private static Map<String, String> physicalRows() {
        Map<String, String> result = new TreeMap<>();
        for (String table : List.of("talleres", "users", "legal_aceptacion_lotes", "legal_aceptaciones", "legal_aceptacion_documentos", "legal_aceptacion_metadatos", "legal_aceptacion_metadatos_cifrados", "legal_idempotencia_resultados", PARENT, REFERENCES))
            result.put(table, owner.queryForObject("SELECT count(*)::text||':'||coalesce(md5(string_agg(xmin::text||':'||ctid::text,',' ORDER BY ctid)),'') FROM " + table, String.class));
        return result;
    }
    private static void requireDisposable() {
        assertThat(owner.queryForObject("SELECT current_database()", String.class)).isEqualTo("ordenfix_legal_maintenance_retention");
        assertThat(owner.queryForObject("SHOW session_replication_role", String.class)).isEqualTo("origin");
    }
    private static void unavailable(Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(LegalAcceptanceMaintenanceException.class, failure -> {
            assertThat(failure.code()).isEqualTo(LegalAcceptanceMaintenanceException.Code.UNAVAILABLE);
            assertThat(failure.getCause()).isNull(); assertThat(failure.getSuppressed()).isEmpty();
        });
    }
    private static String sqlState(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) if (current instanceof SQLException sql) return sql.getSQLState();
        return null;
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(7, TimeUnit.SECONDS)) throw new AssertionError("Fixture deadline"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
    private record Harness(LegalAcceptanceRetentionService service, LegalPrivateRequirementsDataSource source,
                           com.zaxxer.hikari.HikariDataSource pool) implements AutoCloseable {
        @Override public void close() { source.close(); pool.close(); }
    }
}
