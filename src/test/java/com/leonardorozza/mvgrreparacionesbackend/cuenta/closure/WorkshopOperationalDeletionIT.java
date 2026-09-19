package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.WorkshopOperationalDeletionService.*;
import static org.assertj.core.api.Assertions.*;

/** Disposable PostgreSQL, synthetic operational records and all closure guards enabled. */
@Testcontainers
class WorkshopOperationalDeletionIT {
    @Container static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("operational_deletion").withUsername("fixture").withPassword("fixture-password");
    private static final String ENTRY = "public.cuenta_cierre_borrar_lote_v37(uuid,bigint,uuid,text)";
    private static final String SQL = """
            WITH result AS MATERIALIZED (
              SELECT public.cuenta_cierre_borrar_lote_v37(?,?,?,?) AS outcome)
            SELECT outcome->>'status' AS status,outcome->>'category' AS category,
                   (outcome->>'deleted')::integer AS deleted,
                   (outcome->>'remaining')::boolean AS remaining,
                   (outcome->>'receiptId')::uuid AS receipt_id FROM result
            """;
    private static final List<String> OPERATIONAL = List.of("presupuesto_items", "presupuestos", "cobros",
            "repuestos", "reparaciones", "equipos", "clientes", "articulos");
    static DriverManagerDataSource source;
    static JdbcTemplate jdbc, restricted;
    static DataSourceTransactionManager manager, restrictedManager;
    Actor own;
    UUID closure;
    WorkshopOperationalDeletionService service;

    @BeforeAll static void database() {
        source = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(source);
        manager = new DataSourceTransactionManager(source);
        jdbc.execute("CREATE ROLE erase_only LOGIN PASSWORD 'synthetic-erasure-password' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT");
        jdbc.execute("GRANT USAGE ON SCHEMA public TO erase_only");
        jdbc.execute("GRANT EXECUTE ON FUNCTION " + ENTRY + " TO erase_only");
        var restrictedSource = new DriverManagerDataSource(PG.getJdbcUrl(), "erase_only", "synthetic-erasure-password");
        restricted = new JdbcTemplate(restrictedSource);
        restrictedManager = new DataSourceTransactionManager(restrictedSource);
    }

    @BeforeEach void setup() {
        own = actor();
        closure = UUID.randomUUID();
        service = new WorkshopOperationalDeletionService(jdbc, manager, true);
    }

    @Test void boundedBatchesKeepOriginalReceiptAndReadOnlyReplayEvenAfterLaterProgress() {
        Actor foreign = actor();
        for (int i = 0; i < 26; i++) article(own);
        article(foreign);
        subscriptionEvidence(own);
        close(own, closure, dbNow().minusDays(8));
        String retained = retained(own), foreignBefore = fingerprint(foreign);
        UUID batch = UUID.randomUUID();
        Batch first = sql(own, closure, batch, Category.ARTICULOS);
        assertThat(first).isEqualTo(new Batch(Status.DELETED, Category.ARTICULOS, 25, true, batch));
        assertThat(count("articulos", own)).isEqualTo(1);
        String afterFirst = fingerprint(own);
        assertThat(sql(own, closure, batch, Category.ARTICULOS))
                .isEqualTo(new Batch(Status.REUSED, Category.ARTICULOS, 25, true, batch));
        assertThat(fingerprint(own)).isEqualTo(afterFirst);
        UUID last = UUID.randomUUID();
        assertThat(sql(own, closure, last, Category.ARTICULOS))
                .isEqualTo(new Batch(Status.DELETED, Category.ARTICULOS, 1, false, last));
        String afterLast = fingerprint(own);
        assertThat(sql(own, closure, batch, Category.ARTICULOS))
                .isEqualTo(new Batch(Status.REUSED, Category.ARTICULOS, 25, true, batch));
        assertThat(sql(own, closure, UUID.randomUUID(), Category.ARTICULOS))
                .isEqualTo(new Batch(Status.EMPTY, Category.ARTICULOS, 0, false, null));
        assertThat(fingerprint(own)).isEqualTo(afterLast);
        assertThat(count("cuenta_borrado_lotes", own)).isEqualTo(2);
        assertThat(fingerprint(foreign)).isEqualTo(foreignBefore);
        assertThat(retained(own)).isEqualTo(retained);
        assertNoContext();
    }

    @Test void indistinguishableBudgetItemsAreStillBoundedToTwentyFiveExactRows() {
        Graph graph = graph(own);
        for (int i = 1; i < 26; i++) item(graph.budget());
        close(own, closure, dbNow().minusDays(8));
        assertThat(sql(Category.ITEMS).deleted()).isEqualTo(25);
        assertThat(count("presupuesto_items", own)).isEqualTo(1);
        assertThat(count("presupuestos", own)).isEqualTo(1);
        assertThat(sql(Category.ITEMS).deleted()).isEqualTo(1);
        assertThat(sql(Category.ITEMS).status()).isEqualTo(Status.EMPTY);
        assertThat(count("presupuestos", own)).isEqualTo(1);
        assertNoContext();
    }

    @Test void dependentParentsRemainUntilEachExplicitLeafCategoryHasBeenDeleted() {
        graph(own);
        close(own, closure, dbNow().minusDays(8));
        for (Category blocked : List.of(Category.PRESUPUESTOS, Category.REPARACIONES,
                Category.EQUIPOS, Category.CLIENTES, Category.ARTICULOS)) {
            String before = fingerprint(own);
            assertThat(sql(blocked)).isEqualTo(new Batch(Status.EMPTY, blocked, 0, true, null));
            assertThat(fingerprint(own)).isEqualTo(before);
        }
        for (Category category : Category.values()) {
            Batch result = sql(category);
            assertThat(result.status()).as(category.name()).isEqualTo(Status.DELETED);
            assertThat(result.deleted()).as(category.name()).isEqualTo(1);
            assertThat(result.remaining()).as(category.name()).isFalse();
        }
        for (String table : OPERATIONAL) assertThat(count(table, own)).as(table).isZero();
        assertThat(count("cuenta_borrado_lotes", own)).isEqualTo(8);
        assertNoContext();
    }

    @Test void warrantyLeavesAreDeletedBeforeTheirOriginalRepairWithoutCascade() {
        long client = client(own), equipment = equipment(own, client);
        long original = repair(own, equipment), warranty = repair(own, equipment);
        jdbc.update("UPDATE reparaciones SET es_garantia=true,reparacion_origen_id=? WHERE id=?", original, warranty);
        close(own, closure, dbNow().minusDays(8));
        Batch first = sql(Category.REPARACIONES);
        assertThat(first.deleted()).isEqualTo(1);
        assertThat(first.remaining()).isTrue();
        assertThat(jdbc.queryForList("SELECT id FROM reparaciones WHERE taller_id=?", Long.class, own.taller()))
                .containsExactly(original);
        assertThat(sql(Category.REPARACIONES).deleted()).isEqualTo(1);
        assertNoContext();
    }

    @Test void warrantyCyclesRemainBlockedWithoutInventingProgressOrReceipts() {
        long equipment = equipment(own, client(own));
        long first = repair(own, equipment), second = repair(own, equipment);
        jdbc.update("UPDATE reparaciones SET reparacion_origen_id=? WHERE id=?", first, second);
        jdbc.update("UPDATE reparaciones SET reparacion_origen_id=? WHERE id=?", second, first);
        close(own, closure, dbNow().minusDays(8));
        String before = fingerprint(own);
        assertThat(sql(Category.REPARACIONES))
                .isEqualTo(new Batch(Status.EMPTY, Category.REPARACIONES, 0, true, null));
        assertThat(fingerprint(own)).isEqualTo(before);
        assertNoContext();
    }

    @Test void batchIdentityCannotBeReboundToAnotherCategoryClosureOrWorkshop() {
        Actor foreign = actor(); UUID foreignClosure = UUID.randomUUID();
        article(own); article(foreign);
        close(own, closure, dbNow().minusDays(8));
        close(foreign, foreignClosure, dbNow().minusDays(8));
        UUID batch = UUID.randomUUID();
        sql(own, closure, batch, Category.ARTICULOS);
        String before = fingerprint(own), foreignBefore = fingerprint(foreign);
        assertSqlFailure(() -> sql(own, closure, batch, Category.CLIENTES));
        assertSqlFailure(() -> sql(own, UUID.randomUUID(), batch, Category.ARTICULOS));
        assertSqlFailure(() -> sql(foreign, foreignClosure, batch, Category.ARTICULOS));
        assertThat(fingerprint(own)).isEqualTo(before);
        assertThat(fingerprint(foreign)).isEqualTo(foreignBefore);
        assertNoContext();
    }

    @Test void openGraceAndForeignReferencesCannotDeleteOrRecordAReceipt() {
        article(own);
        assertSqlFailure(() -> sql(Category.ARTICULOS));
        close(own, closure, dbNow().minusDays(7).plusMinutes(1));
        String before = fingerprint(own);
        assertSqlFailure(() -> sql(Category.ARTICULOS));
        assertSqlFailure(() -> sql(own, UUID.randomUUID(), UUID.randomUUID(), Category.ARTICULOS));
        assertSqlFailure(() -> sql(actor(), closure, UUID.randomUUID(), Category.ARTICULOS));
        assertThat(fingerprint(own)).isEqualTo(before);
        assertNoContext();
    }

    @Test void restorationRevokesTheOldDeletionReference() {
        article(own);
        close(own, closure, dbNow());
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            new WorkshopClosureGate(jdbc).lockExclusive(own.taller());
            new WorkshopClosureStore(jdbc, Clock.systemUTC()).restore(own.taller(), own.user(), closure);
        });
        String before = fingerprint(own);
        assertSqlFailure(() -> sql(Category.ARTICULOS));
        assertThat(fingerprint(own)).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"equipment", "repair", "budget", "payment", "spare", "warranty"})
    void crossWorkshopRelationshipsBlockDeletionBeforeAnyTargetIsRemoved(String relationship) {
        Actor foreign = actor(); Graph ownGraph = graph(own), foreignGraph = graph(foreign);
        switch (relationship) {
            case "equipment" -> jdbc.update("UPDATE equipos SET cliente_id=? WHERE id=?", foreignGraph.client(), ownGraph.equipment());
            case "repair" -> jdbc.update("UPDATE reparaciones SET equipo_id=? WHERE id=?", foreignGraph.equipment(), ownGraph.repair());
            case "budget" -> jdbc.update("UPDATE presupuestos SET reparacion_id=? WHERE id=?", foreignGraph.repair(), ownGraph.budget());
            case "payment" -> jdbc.update("UPDATE cobros SET reparacion_id=? WHERE taller_id=?", foreignGraph.repair(), own.taller());
            case "spare" -> jdbc.update("UPDATE repuestos SET articulo_id=? WHERE taller_id=?", foreignGraph.article(), own.taller());
            case "warranty" -> jdbc.update("UPDATE reparaciones SET reparacion_origen_id=? WHERE id=?", foreignGraph.repair(), ownGraph.repair());
            default -> throw new AssertionError(relationship);
        }
        close(own, closure, dbNow().minusDays(8));
        UUID foreignClosure = UUID.randomUUID();
        close(foreign, foreignClosure, dbNow().minusDays(8));
        String before = fingerprint(own), foreignBefore = fingerprint(foreign);
        assertSqlFailure(() -> sql(Category.ITEMS));
        assertSqlFailure(() -> sql(foreign, foreignClosure, UUID.randomUUID(), Category.ITEMS));
        assertThat(fingerprint(own)).isEqualTo(before);
        assertThat(fingerprint(foreign)).isEqualTo(foreignBefore);
        assertNoContext();
    }

    @ParameterizedTest @EnumSource(Category.class)
    void unresolvedLegacyPhotoBlocksEveryCategoryBeforeDml(Category category) {
        Graph graph = graph(own);
        jdbc.update("INSERT INTO reparacion_fotos(reparacion_id,url) VALUES(?,?)",
                graph.repair(), "https://synthetic.invalid/evidence-never-contacted.png");
        close(own, closure, dbNow().minusDays(8));
        String before = fingerprint(own);
        assertSqlFailure(() -> sql(category));
        assertThat(fingerprint(own)).isEqualTo(before);
        assertThat(count("reparacion_fotos", own)).isEqualTo(1);
        assertNoContext();
    }

    @Test void executionRoleCannotReadOrWriteTargetsReceiptsOrPrivateContextDirectly() {
        article(own); close(own, closure, dbNow().minusDays(8));
        String before = fingerprint(own);
        for (String table : List.of("articulos", "cuenta_borrado_lotes", "cuenta_borrado_contextos")) {
            assertSqlState(() -> restricted.execute("SELECT * FROM public." + table), "42501");
            assertSqlState(() -> restricted.execute("INSERT INTO public." + table + " DEFAULT VALUES"), "42501");
            assertSqlState(() -> restricted.execute("DELETE FROM public." + table), "42501");
            assertSqlState(() -> restricted.execute("TRUNCATE public." + table), "42501");
        }
        assertSqlState(() -> restricted.update("INSERT INTO articulos(taller_id,nombre) VALUES(?,'forbidden')", own.taller()), "42501");
        assertSqlState(() -> restricted.update("UPDATE articulos SET nombre='forbidden' WHERE taller_id=?", own.taller()), "42501");
        assertSqlState(() -> restricted.execute("UPDATE cuenta_borrado_contextos SET backend_pid=backend_pid"), "42501");
        assertThat(fingerprint(own)).isEqualTo(before);
        assertThat(sql(Category.ARTICULOS).status()).isEqualTo(Status.DELETED);
        assertNoContext();
    }

    @Test void tableOwnerCannotBypassClosureWithoutTheExactInternalDeletionContext() {
        article(own); close(own, closure, dbNow().minusDays(8));
        String before = fingerprint(own);
        assertSqlFailure(() -> jdbc.update("DELETE FROM articulos WHERE taller_id=?", own.taller()));
        assertThat(fingerprint(own)).isEqualTo(before);
        assertNoContext();
    }

    @Test void directSqlWithoutExclusiveGateRejectsBeforeLockingOrDeletingRows() {
        article(own); close(own, closure, dbNow().minusDays(8));
        String before = fingerprint(own);
        assertSqlState(() -> new TransactionTemplate(restrictedManager).executeWithoutResult(status ->
                restricted.queryForObject("SELECT public.cuenta_cierre_borrar_lote_v37(?,?,?,?)::text", String.class,
                        UUID.randomUUID(), own.taller(), closure, Category.ARTICULOS.name())), "P0037");
        assertThat(fingerprint(own)).isEqualTo(before);
        assertNoContext();
    }

    @ParameterizedTest @ValueSource(ints = {TransactionDefinition.ISOLATION_REPEATABLE_READ, TransactionDefinition.ISOLATION_SERIALIZABLE})
    void directSqlRejectsSnapshotIsolationInsteadOfWorkingWithStaleAdmission(int isolation) {
        article(own); close(own, closure, dbNow().minusDays(8));
        String before = fingerprint(own);
        var transaction = new TransactionTemplate(restrictedManager);
        transaction.setIsolationLevel(isolation);
        assertSqlState(() -> transaction.executeWithoutResult(status -> restricted.queryForObject(
                "SELECT public.cuenta_cierre_borrar_lote_v37(?,?,?,?)::text", String.class,
                UUID.randomUUID(), own.taller(), closure, Category.ARTICULOS.name())), "25001");
        assertThat(fingerprint(own)).isEqualTo(before);
        assertNoContext();
    }

    @Test void receiptInsertionFailureRollsBackSelectedRowsAndPrivateContext() {
        for (int i = 0; i < 26; i++) article(own);
        close(own, closure, dbNow().minusDays(8));
        String before = fingerprint(own);
        jdbc.execute("""
                CREATE FUNCTION public.test_reject_erasure_receipt() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'synthetic ledger failure' USING ERRCODE='P0001'; END $$
                """);
        jdbc.execute("CREATE TRIGGER zz_test_reject_erasure_receipt BEFORE INSERT ON public.cuenta_borrado_lotes FOR EACH ROW EXECUTE FUNCTION public.test_reject_erasure_receipt()");
        try {
            assertSqlState(() -> sql(Category.ARTICULOS), "P0001");
            assertThat(fingerprint(own)).isEqualTo(before);
            assertNoContext();
        } finally {
            jdbc.execute("DROP TRIGGER zz_test_reject_erasure_receipt ON public.cuenta_borrado_lotes");
            jdbc.execute("DROP FUNCTION public.test_reject_erasure_receipt()");
        }
        assertThat(sql(Category.ARTICULOS).deleted()).isEqualTo(25);
    }

    @Test void committedReceiptsAreImmutableAndTheWorkerCannotTruncateThem() {
        article(own); close(own, closure, dbNow().minusDays(8));
        sql(Category.ARTICULOS);
        String before = fingerprint(own);
        assertSqlFailure(() -> jdbc.update("UPDATE cuenta_borrado_lotes SET taller_id=taller_id WHERE taller_id=?", own.taller()));
        assertSqlFailure(() -> jdbc.update("DELETE FROM cuenta_borrado_lotes WHERE taller_id=?", own.taller()));
        assertSqlState(() -> restricted.execute("TRUNCATE cuenta_borrado_lotes"), "42501");
        assertThat(fingerprint(own)).isEqualTo(before);
    }

    @Test void javaServiceMapsTheExactSqlContractAndKeepsEmptyReplayReadOnly() {
        article(own); close(own, closure, dbNow().minusDays(8));
        UUID batch = UUID.randomUUID();
        assertThat(service.deleteBatch(own.taller(), closure, batch, Category.ARTICULOS))
                .isEqualTo(new Batch(Status.DELETED, Category.ARTICULOS, 1, false, batch));
        String before = fingerprint(own);
        assertThat(service.deleteBatch(own.taller(), closure, batch, Category.ARTICULOS).status()).isEqualTo(Status.REUSED);
        Batch empty = service.deleteBatch(own.taller(), closure, UUID.randomUUID(), Category.ARTICULOS);
        assertThat(empty).isEqualTo(new Batch(Status.EMPTY, Category.ARTICULOS, 0, false, null));
        assertThat(empty.toString()).isEqualTo("OperationalDeletionBatch[redacted]");
        assertThat(fingerprint(own)).isEqualTo(before);
    }

    @Test void disabledAndInvalidJavaRequestsDoNotConsultTheDatabase() {
        JdbcTemplate never = new JdbcTemplate(source) {
            @Override public void execute(String sql) { throw new AssertionError("Database must remain untouched"); }
        };
        var disabled = new WorkshopOperationalDeletionService(never, manager, false);
        reject(() -> disabled.deleteBatch(own.taller(), closure, UUID.randomUUID(), Category.ARTICULOS), Rejected.Code.DISABLED);
        var enabled = new WorkshopOperationalDeletionService(never, manager, true);
        reject(() -> enabled.deleteBatch(0, closure, UUID.randomUUID(), Category.ARTICULOS), Rejected.Code.INVALID_TARGET);
        reject(() -> enabled.deleteBatch(own.taller(), null, UUID.randomUUID(), Category.ARTICULOS), Rejected.Code.INVALID_TARGET);
        reject(() -> enabled.deleteBatch(own.taller(), closure, null, Category.ARTICULOS), Rejected.Code.INVALID_TARGET);
        reject(() -> enabled.deleteBatch(own.taller(), closure, UUID.randomUUID(), null), Rejected.Code.INVALID_TARGET);
    }

    @Test void failureAfterTheSqlFunctionReturnsRollsBackDeletionAndReceiptAndSanitizesItsCause() {
        article(own); close(own, closure, dbNow().minusDays(8));
        String before = fingerprint(own);
        AtomicBoolean deleted = new AtomicBoolean();
        JdbcTemplate failing = new JdbcTemplate(source) {
            @Override public <T> T queryForObject(String sql, RowMapper<T> mapper, Object... args) {
                T result = super.queryForObject(sql, mapper, args);
                if (sql.contains("cuenta_cierre_borrar_lote_v37(")) {
                    deleted.set(true);
                    assertThat(queryForObject("SELECT count(*) FROM articulos WHERE taller_id=?", Long.class, own.taller())).isZero();
                    throw new IllegalStateException("private database detail and client content");
                }
                return result;
            }
        };
        reject(() -> new WorkshopOperationalDeletionService(failing, manager, true)
                .deleteBatch(own.taller(), closure, UUID.randomUUID(), Category.ARTICULOS), Rejected.Code.UNAVAILABLE);
        assertThat(deleted).isTrue();
        assertThat(fingerprint(own)).isEqualTo(before);
        assertNoContext();
    }

    @Test void javaUsesNewReadCommittedTransactionIndependentOfCallerRollback() {
        article(own); close(own, closure, dbNow().minusDays(8));
        Actor unrelated = actor();
        String original = jdbc.queryForObject("SELECT username FROM users WHERE id=?", String.class, unrelated.user());
        AtomicBoolean observed = new AtomicBoolean();
        JdbcTemplate inspecting = new JdbcTemplate(source) {
            @Override public <T> T queryForObject(String sql, RowMapper<T> mapper, Object... args) {
                if (sql.contains("cuenta_cierre_borrar_lote_v37(")) {
                    assertThat(queryForObject("SHOW transaction_isolation", String.class)).isEqualTo("read committed");
                    observed.set(true);
                }
                return super.queryForObject(sql, mapper, args);
            }
        };
        var isolated = new WorkshopOperationalDeletionService(inspecting, manager, true);
        var outer = new TransactionTemplate(manager);
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        outer.executeWithoutResult(status -> {
            jdbc.update("UPDATE users SET username=? WHERE id=?", "rolled-back-" + UUID.randomUUID(), unrelated.user());
            assertThat(isolated.deleteBatch(own.taller(), closure, UUID.randomUUID(), Category.ARTICULOS).deleted()).isEqualTo(1);
            status.setRollbackOnly();
        });
        assertThat(observed).isTrue();
        assertThat(count("articulos", own)).isZero();
        assertThat(count("cuenta_borrado_lotes", own)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT username FROM users WHERE id=?", String.class, unrelated.user())).isEqualTo(original);
    }

    @Test void twoConcurrentRequestsForTheSameBatchHaveOneDeletionAndOneReadOnlyReplay() throws Exception {
        for (int i = 0; i < 26; i++) article(own);
        close(own, closure, dbNow().minusDays(8));
        UUID batch = UUID.randomUUID();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Batch> call = () -> { await(start); return sql(own, closure, batch, Category.ARTICULOS); };
            Future<Batch> first = executor.submit(call), second = executor.submit(call);
            start.countDown();
            List<Batch> results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertThat(results).extracting(Batch::status).containsExactlyInAnyOrder(Status.DELETED, Status.REUSED);
            assertThat(results).allSatisfy(result -> {
                assertThat(result.deleted()).isEqualTo(25);
                assertThat(result.receiptId()).isEqualTo(batch);
            });
        }
        assertThat(count("articulos", own)).isEqualTo(1);
        assertThat(count("cuenta_borrado_lotes", own)).isEqualTo(1);
        assertNoContext();
    }

    @Test void exclusiveGatePrecedesAnyDeletionAndSerializesAgainstAnInFlightWriter() throws Exception {
        article(own); close(own, closure, dbNow().minusDays(8));
        String before = fingerprint(own);
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> blocker = executor.submit(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
                new WorkshopClosureGate(jdbc).lockExclusive(own.taller());
                locked.countDown(); await(release);
            }));
            await(locked);
            Future<Batch> deletion = executor.submit(() -> sql(Category.ARTICULOS));
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (System.nanoTime() < deadline && !deletion.isDone()
                        && jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND NOT granted", Long.class) == 0)
                    Thread.onSpinWait();
                assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND NOT granted", Long.class)).isPositive();
                assertThat(fingerprint(own)).isEqualTo(before);
            } finally { release.countDown(); }
            blocker.get(5, TimeUnit.SECONDS);
            assertThat(deletion.get(5, TimeUnit.SECONDS).deleted()).isEqualTo(1);
        } finally { release.countDown(); }
        assertNoContext();
    }

    @Test void progressReadsAllEightCategoriesWithoutWritingAndKeepsTenantScope() {
        graph(own); Actor foreign = actor(); graph(foreign); graph(foreign);
        close(own, closure, dbNow().minusDays(8));
        String before = fingerprint(own), foreignBefore = fingerprint(foreign);
        var progress = new WorkshopOperationalDeletionProgress(jdbc, manager);
        var snapshot = progress.read(own.taller(), closure);
        assertThat(snapshot.tallerId()).isEqualTo(own.taller());
        assertThat(snapshot.closureReference()).isEqualTo(closure);
        assertThat(snapshot.generation()).isEqualTo(1);
        assertThat(snapshot.remaining()).hasSize(Category.values().length);
        for (Category category : Category.values()) assertThat(snapshot.remaining()).containsEntry(category, 1L);
        assertThat(snapshot.hasRows()).isTrue();
        assertThat(snapshot.photosPending()).isFalse();
        assertThat(snapshot.graceExpired()).isTrue();
        assertThat(snapshot.observedAt()).isAfter(snapshot.reversibleUntil());
        assertThatThrownBy(() -> snapshot.remaining().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(progress.read(own.taller(), closure).remaining()).isEqualTo(snapshot.remaining());
        assertThat(fingerprint(own)).isEqualTo(before);
        assertThat(fingerprint(foreign)).isEqualTo(foreignBefore);
        assertNoContext();
    }

    @Test void workerFinishesEightCategoriesWhileKeepingQrIdentityClosureAndSubscriptionEvidence() {
        graph(own); subscriptionEvidence(own);
        jdbc.update("INSERT INTO taller_qr_cobro(taller_id,png,sha256) VALUES(?,?,?)", own.taller(), new byte[]{1}, "0".repeat(64));
        Actor foreign = actor(); graph(foreign);
        close(own, closure, dbNow().minusDays(8));
        String retained = retained(own), qr = rows("taller_qr_cobro", "taller_id=?", own.taller());
        String foreignBefore = fingerprint(foreign);
        var result = worker(jdbc, 8).run(own.taller(), closure);
        assertThat(result.state()).isEqualTo(WorkshopOperationalDeletionWorker.State.NO_PENDING_ROWS);
        assertThat(result.attempts()).isEqualTo(8);
        assertThat(result.observed().hasRows()).isFalse();
        assertThat(result.observed().photosPending()).isFalse();
        for (String table : OPERATIONAL) assertThat(count(table, own)).as(table).isZero();
        assertThat(count("cuenta_borrado_lotes", own)).isEqualTo(8);
        assertThat(receiptTotal()).isEqualTo(8);
        assertThat(retained(own)).isEqualTo(retained);
        assertThat(rows("taller_qr_cobro", "taller_id=?", own.taller())).isEqualTo(qr);
        assertThat(fingerprint(foreign)).isEqualTo(foreignBefore);
        String complete = fingerprint(own);
        var repeated = worker(jdbc, 8).run(own.taller(), closure);
        assertThat(repeated.state()).isEqualTo(WorkshopOperationalDeletionWorker.State.NO_PENDING_ROWS);
        assertThat(repeated.attempts()).isZero();
        assertThat(fingerprint(own)).isEqualTo(complete);
        assertThat(rows("taller_qr_cobro", "taller_id=?", own.taller())).isEqualTo(qr);
        assertNoContext();
    }

    @ParameterizedTest @ValueSource(ints = {1, 2, 8})
    void workerHonorsItsBudgetAndANewInstanceResumesFromCommittedProgress(int budget) {
        for (int i = 0; i < 25 * budget + 1; i++) article(own);
        close(own, closure, dbNow().minusDays(8));
        var bounded = worker(jdbc, budget).run(own.taller(), closure);
        assertThat(bounded.state()).isEqualTo(WorkshopOperationalDeletionWorker.State.WORK_REMAINS);
        assertThat(bounded.attempts()).isEqualTo(budget);
        assertThat(bounded.observed().remaining()).containsEntry(Category.ARTICULOS, 1L);
        assertThat(count("cuenta_borrado_lotes", own)).isEqualTo(budget);
        assertThat(receiptTotal()).isEqualTo(25L * budget);
        var resumed = worker(jdbc, 1).run(own.taller(), closure);
        assertThat(resumed.state()).isEqualTo(WorkshopOperationalDeletionWorker.State.NO_PENDING_ROWS);
        assertThat(resumed.attempts()).isEqualTo(1);
        assertThat(count("cuenta_borrado_lotes", own)).isEqualTo(budget + 1L);
        assertThat(receiptTotal()).isEqualTo(25L * budget + 1);
        assertNoContext();
    }

    @Test void lostResponseAfterTheLastCommitIsResolvedByFreshObservationWithoutAnotherMutation() {
        article(own); close(own, closure, dbNow().minusDays(8));
        AtomicInteger attempts = new AtomicInteger();
        var result = worker(lostCommitResponse(attempts), 8).run(own.taller(), closure);
        assertThat(result.state()).isEqualTo(WorkshopOperationalDeletionWorker.State.NO_PENDING_ROWS);
        assertThat(result.attempts()).isEqualTo(1);
        assertThat(attempts).hasValue(1);
        assertThat(result.observed().hasRows()).isFalse();
        assertThat(count("cuenta_borrado_lotes", own)).isEqualTo(1);
        assertThat(receiptTotal()).isEqualTo(1);
        String committed = fingerprint(own);
        assertThat(worker(jdbc, 8).run(own.taller(), closure).attempts()).isZero();
        assertThat(fingerprint(own)).isEqualTo(committed);
        assertNoContext();
    }

    @Test void lostResponseWithRemainingRowsStopsAndANewInstanceContinuesDurably() {
        for (int i = 0; i < 26; i++) article(own);
        close(own, closure, dbNow().minusDays(8));
        AtomicInteger attempts = new AtomicInteger();
        var uncertain = worker(lostCommitResponse(attempts), 8).run(own.taller(), closure);
        assertThat(uncertain.state()).isEqualTo(WorkshopOperationalDeletionWorker.State.RETRY_LATER);
        assertThat(uncertain.attempts()).isEqualTo(1);
        assertThat(attempts).hasValue(1);
        assertThat(uncertain.observed().remaining()).containsEntry(Category.ARTICULOS, 1L);
        assertThat(count("cuenta_borrado_lotes", own)).isEqualTo(1);
        assertThat(receiptTotal()).isEqualTo(25);
        var resumed = worker(jdbc, 8).run(own.taller(), closure);
        assertThat(resumed.state()).isEqualTo(WorkshopOperationalDeletionWorker.State.NO_PENDING_ROWS);
        assertThat(resumed.attempts()).isEqualTo(1);
        assertThat(receiptTotal()).isEqualTo(26);
        assertNoContext();
    }

    @Test void failureBeforeTheSecondCommitKeepsTheFirstBatchAndRollsBackOnlyTheCurrentBatch() {
        for (int i = 0; i < 26; i++) article(own);
        close(own, closure, dbNow().minusDays(8));
        String retained = retained(own);
        AtomicInteger attempts = new AtomicInteger();
        JdbcTemplate failing = new JdbcTemplate(source) {
            @Override public <T> T queryForObject(String sql, RowMapper<T> mapper, Object... args) {
                T result = super.queryForObject(sql, mapper, args);
                if (sql.contains("cuenta_cierre_borrar_lote_v37(") && attempts.incrementAndGet() == 2)
                    throw new IllegalStateException("synthetic failure before current commit");
                return result;
            }
        };
        var interrupted = worker(failing, 8).run(own.taller(), closure);
        assertThat(interrupted.state()).isEqualTo(WorkshopOperationalDeletionWorker.State.RETRY_LATER);
        assertThat(interrupted.attempts()).isEqualTo(2);
        assertThat(attempts).hasValue(2);
        assertThat(interrupted.observed().remaining()).containsEntry(Category.ARTICULOS, 1L);
        assertThat(count("cuenta_borrado_lotes", own)).isEqualTo(1);
        assertThat(receiptTotal()).isEqualTo(25);
        assertThat(retained(own)).isEqualTo(retained);
        assertNoContext();
        assertThat(worker(jdbc, 8).run(own.taller(), closure).state())
                .isEqualTo(WorkshopOperationalDeletionWorker.State.NO_PENDING_ROWS);
        assertThat(receiptTotal()).isEqualTo(26);
    }

    @Test void workerReportsDependencyCyclesWithoutConsumingItsWholeBudgetOrInventingReceipts() {
        long equipment = equipment(own, client(own));
        long first = repair(own, equipment), second = repair(own, equipment);
        jdbc.update("UPDATE reparaciones SET reparacion_origen_id=? WHERE id=?", first, second);
        jdbc.update("UPDATE reparaciones SET reparacion_origen_id=? WHERE id=?", second, first);
        article(own);
        close(own, closure, dbNow().minusDays(8));
        String before = fingerprint(own);
        var blocked = worker(jdbc, 8).run(own.taller(), closure);
        assertThat(blocked.state()).isEqualTo(WorkshopOperationalDeletionWorker.State.DEPENDENCIES_PENDING);
        assertThat(blocked.attempts()).isEqualTo(1);
        assertThat(blocked.observed().remaining()).containsEntry(Category.REPARACIONES, 2L);
        assertThat(fingerprint(own)).isEqualTo(before);
        assertThat(worker(jdbc, 8).run(own.taller(), closure).state())
                .isEqualTo(WorkshopOperationalDeletionWorker.State.DEPENDENCIES_PENDING);
        assertThat(fingerprint(own)).isEqualTo(before);
        assertNoContext();
    }

    @Test void workerObservesPendingLegacyPhotosBeforeTryingAnyCategory() {
        Graph graph = graph(own);
        jdbc.update("INSERT INTO reparacion_fotos(reparacion_id,url) VALUES(?,?)",
                graph.repair(), "https://synthetic.invalid/legacy-photo-not-contacted.png");
        close(own, closure, dbNow().minusDays(8));
        String before = fingerprint(own);
        var blocked = worker(jdbc, 8).run(own.taller(), closure);
        assertThat(blocked.state()).isEqualTo(WorkshopOperationalDeletionWorker.State.PHOTOS_PENDING);
        assertThat(blocked.attempts()).isZero();
        assertThat(blocked.observed().photosPending()).isTrue();
        assertThat(fingerprint(own)).isEqualTo(before);
        assertNoContext();
    }

    @Test void workerWaitsForTheSevenDayWindowWithoutWritingEvenWhenThereAreNoRows() {
        close(own, closure, dbNow());
        String before = fingerprint(own);
        var waiting = worker(jdbc, 8).run(own.taller(), closure);
        assertThat(waiting.state()).isEqualTo(WorkshopOperationalDeletionWorker.State.WAITING_GRACE);
        assertThat(waiting.attempts()).isZero();
        assertThat(waiting.observed().graceExpired()).isFalse();
        assertThat(waiting.observed().hasRows()).isFalse();
        assertThat(fingerprint(own)).isEqualTo(before);
        assertNoContext();
    }

    @Test void workerNeverDeclaresAnOpenForeignOrRestoredReferenceComplete() {
        var worker = worker(jdbc, 8);
        workerReject(() -> worker.run(own.taller(), closure), WorkshopOperationalDeletionWorker.Rejected.Code.UNAVAILABLE);
        Actor foreign = actor(); UUID foreignClosure = UUID.randomUUID();
        close(foreign, foreignClosure, dbNow().minusDays(8));
        close(own, closure, dbNow());
        workerReject(() -> worker.run(own.taller(), foreignClosure), WorkshopOperationalDeletionWorker.Rejected.Code.UNAVAILABLE);
        workerReject(() -> worker.run(foreign.taller(), closure), WorkshopOperationalDeletionWorker.Rejected.Code.UNAVAILABLE);
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            new WorkshopClosureGate(jdbc).lockExclusive(own.taller());
            new WorkshopClosureStore(jdbc, Clock.systemUTC()).restore(own.taller(), own.user(), closure);
        });
        String restored = fingerprint(own), foreignBefore = fingerprint(foreign);
        workerReject(() -> worker.run(own.taller(), closure), WorkshopOperationalDeletionWorker.Rejected.Code.UNAVAILABLE);
        assertThat(fingerprint(own)).isEqualTo(restored);
        assertThat(fingerprint(foreign)).isEqualTo(foreignBefore);
        assertNoContext();
    }

    @Test void workerRejectsDisabledInvalidAndCallerTransactionInvocationsBeforeDatabaseAccess() {
        JdbcTemplate never = new JdbcTemplate(source) {
            @Override public void execute(String sql) { throw new AssertionError("Database must remain untouched"); }
        };
        var progress = new WorkshopOperationalDeletionProgress(never, manager);
        var deletion = new WorkshopOperationalDeletionService(never, manager, true);
        var disabled = new WorkshopOperationalDeletionWorker(progress, deletion, false, 8);
        workerReject(() -> disabled.run(own.taller(), closure), WorkshopOperationalDeletionWorker.Rejected.Code.DISABLED);
        var enabled = new WorkshopOperationalDeletionWorker(progress, deletion, true, 8);
        workerReject(() -> enabled.run(0, closure), WorkshopOperationalDeletionWorker.Rejected.Code.INVALID_TARGET);
        workerReject(() -> enabled.run(own.taller(), null), WorkshopOperationalDeletionWorker.Rejected.Code.INVALID_TARGET);
        new TransactionTemplate(manager).executeWithoutResult(status -> workerReject(() -> enabled.run(own.taller(), closure),
                WorkshopOperationalDeletionWorker.Rejected.Code.CALLER_TRANSACTION));
        assertThatThrownBy(() -> new WorkshopOperationalDeletionWorker(progress, deletion, true, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkshopOperationalDeletionWorker(progress, deletion, true, 9)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void concurrentWorkersLeaveOnlyDurableDisjointBatchesAndAFreshInvocationFinishes() throws Exception {
        for (int i = 0; i < 51; i++) article(own);
        close(own, closure, dbNow().minusDays(8));
        String retained = retained(own);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Object> call = () -> {
                var worker = worker(jdbc, 2);
                await(start);
                try { return worker.run(own.taller(), closure); }
                catch (WorkshopOperationalDeletionWorker.Rejected unavailable) { return unavailable; }
            };
            Future<Object> first = executor.submit(call), second = executor.submit(call);
            start.countDown();
            for (Object outcome : List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS))) {
                if (outcome instanceof WorkshopOperationalDeletionWorker.Result result)
                    assertThat(result.attempts()).isBetween(0, 2);
                else assertThat(outcome).isInstanceOfSatisfying(WorkshopOperationalDeletionWorker.Rejected.class,
                        rejected -> {
                            assertThat(rejected.code()).isEqualTo(WorkshopOperationalDeletionWorker.Rejected.Code.UNAVAILABLE);
                            assertThat(rejected).hasNoCause();
                        });
            }
        }
        assertThat(receiptTotal()).isBetween(1L, 51L);
        assertThat(receiptTotal() + count("articulos", own)).isEqualTo(51);
        var resumed = worker(jdbc, 8).run(own.taller(), closure);
        assertThat(resumed.state()).isEqualTo(WorkshopOperationalDeletionWorker.State.NO_PENDING_ROWS);
        assertThat(receiptTotal()).isEqualTo(51);
        assertThat(count("cuenta_borrado_lotes", own)).isEqualTo(3);
        assertThat(retained(own)).isEqualTo(retained);
        assertNoContext();
    }

    @Test void candidatePagesRotatePastBlockedWorkAndExcludeEmptyOpenAndGraceWorkshops() {
        article(own); close(own, closure, dbNow().minusDays(8));
        Actor empty = actor();
        jdbc.update("INSERT INTO taller_qr_cobro(taller_id,png,sha256) VALUES(?,?,?)", empty.taller(), new byte[]{1}, "0".repeat(64));
        close(empty, UUID.randomUUID(), dbNow().minusDays(8));
        Actor grace = actor(); article(grace); close(grace, UUID.randomUUID(), dbNow());
        Actor open = actor(); article(open);
        Actor legacy = actor(); Graph graph = graph(legacy);
        jdbc.update("INSERT INTO reparacion_fotos(reparacion_id,url) VALUES(?,?)",
                graph.repair(), "https://synthetic.invalid/pending-discovery-photo.png");
        UUID legacyClosure = UUID.randomUUID(); close(legacy, legacyClosure, dbNow().minusDays(8));
        Actor last = actor(); article(last);
        UUID lastClosure = UUID.randomUUID(); close(last, lastClosure, dbNow().minusDays(8));
        Map<Actor, String> before = new HashMap<>();
        for (Actor actor : List.of(own, empty, grace, open, legacy, last)) before.put(actor, fingerprint(actor));
        String emptyQr = rows("taller_qr_cobro", "taller_id=?", empty.taller());
        var candidates = new WorkshopOperationalDeletionCandidates(jdbc, manager);
        var first = candidates.next(own.taller() - 1);
        assertThat(first.hasMore()).isTrue();
        assertThat(first.candidates()).extracting(WorkshopOperationalDeletionCandidates.Candidate::tallerId)
                .containsExactly(own.taller(), legacy.taller());
        assertThat(first.candidates()).extracting(WorkshopOperationalDeletionCandidates.Candidate::closureReference)
                .containsExactly(closure, legacyClosure);
        var second = candidates.next(legacy.taller());
        assertThat(second.hasMore()).isFalse();
        assertThat(second.candidates()).extracting(WorkshopOperationalDeletionCandidates.Candidate::tallerId)
                .containsExactly(last.taller());
        assertThat(second.candidates()).extracting(WorkshopOperationalDeletionCandidates.Candidate::closureReference)
                .containsExactly(lastClosure);
        var end = candidates.next(last.taller());
        assertThat(end.candidates()).isEmpty();
        assertThat(end.hasMore()).isFalse();
        before.forEach((actor, fingerprint) -> assertThat(fingerprint(actor)).isEqualTo(fingerprint));
        assertThat(rows("taller_qr_cobro", "taller_id=?", empty.taller())).isEqualTo(emptyQr);
        assertNoContext();
    }

    private WorkshopOperationalDeletionWorker worker(JdbcTemplate batchJdbc, int budget) {
        return new WorkshopOperationalDeletionWorker(new WorkshopOperationalDeletionProgress(jdbc, manager),
                new WorkshopOperationalDeletionService(batchJdbc, manager, true), true, budget);
    }
    private JdbcTemplate lostCommitResponse(AtomicInteger attempts) {
        return new JdbcTemplate(source) {
            @Override public <T> T queryForObject(String sql, RowMapper<T> mapper, Object... args) {
                T result = super.queryForObject(sql, mapper, args);
                if (sql.contains("cuenta_cierre_borrar_lote_v37(")) {
                    attempts.incrementAndGet();
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override public void afterCommit() {
                            throw new IllegalStateException("synthetic response loss after durable commit");
                        }
                    });
                }
                return result;
            }
        };
    }
    private long receiptTotal() {
        return jdbc.queryForObject("SELECT coalesce(sum(eliminados),0) FROM cuenta_borrado_lotes WHERE taller_id=? AND cierre_referencia=?",
                Long.class, own.taller(), closure);
    }
    private static void workerReject(Runnable action, WorkshopOperationalDeletionWorker.Rejected.Code code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(WorkshopOperationalDeletionWorker.Rejected.class,
                rejected -> assertThat(rejected.code()).isEqualTo(code)).hasNoCause();
    }

    private Batch sql(Category category) { return sql(own, closure, UUID.randomUUID(), category); }
    private Batch sql(Actor actor, UUID reference, UUID batch, Category category) {
        var transaction = new TransactionTemplate(restrictedManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(10);
        return transaction.execute(status -> {
            new WorkshopClosureGate(restricted).lockExclusive(actor.taller());
            return restricted.queryForObject(SQL,
                (row, index) -> new Batch(Status.valueOf(row.getString("status")),
                        Category.valueOf(row.getString("category")), row.getInt("deleted"),
                        row.getBoolean("remaining"), row.getObject("receipt_id", UUID.class)),
                batch, actor.taller(), reference, category.name());
        });
    }

    private Actor actor() {
        long taller = jdbc.queryForObject("INSERT INTO talleres(nombre) VALUES('operational erasure synthetic') RETURNING id", Long.class);
        long user = jdbc.queryForObject("""
                INSERT INTO users(username,email,password,role,taller_id,active,email_verificado,token_version)
                VALUES(?,?,?,'ADMIN',?,true,true,0) RETURNING id
                """, Long.class, UUID.randomUUID().toString(), UUID.randomUUID() + "@synthetic.invalid", "not-a-login-secret", taller);
        return new Actor(taller, user);
    }

    /** Historical restriction uses the real V33 history/anchor protocol, as maintenance fixtures do. */
    private void close(Actor actor, UUID reference, OffsetDateTime confirmed) {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            new WorkshopClosureGate(jdbc).lockExclusive(actor.taller());
            jdbc.update("UPDATE users SET token_version=token_version+1 WHERE taller_id=?", actor.taller());
            jdbc.update("""
                    INSERT INTO cuenta_cierres(referencia,taller_id,titular_id,generacion,estado,politica,
                      confirmado_en,reversible_hasta,eliminacion_prevista_en)
                    VALUES(?,?,?,1,'RESTRINGIDO','ordenfix-cierre/1',?,?,?)
                    """, reference, actor.taller(), actor.user(), confirmed, confirmed.plusDays(7), confirmed.plusDays(37));
            jdbc.update("""
                    UPDATE talleres SET cierre_estado='RESTRINGIDO',cierre_version=1,cierre_referencia=?,
                      cierre_confirmado_en=?,cierre_reversible_hasta=?,cierre_eliminacion_prevista_en=? WHERE id=?
                    """, reference, confirmed, confirmed.plusDays(7), confirmed.plusDays(37), actor.taller());
        });
    }

    private long client(Actor actor) {
        return jdbc.queryForObject("INSERT INTO clientes(taller_id,nombre,apellido,telefono) VALUES(?,'Synthetic','Client',?) RETURNING id",
                Long.class, actor.taller(), UUID.randomUUID().toString().substring(0, 18));
    }
    private long equipment(Actor actor, long client) {
        return jdbc.queryForObject("INSERT INTO equipos(taller_id,cliente_id,marca,modelo,tipo,imei) VALUES(?,?,'Synthetic','TV fixture','TV','SERIE-SYNTHETIC') RETURNING id",
                Long.class, actor.taller(), client);
    }
    private long repair(Actor actor, long equipment) {
        return jdbc.queryForObject("INSERT INTO reparaciones(taller_id,equipo_id,descripcion_problema,estado) VALUES(?,?,'Synthetic repair','RECIBIDO') RETURNING id",
                Long.class, actor.taller(), equipment);
    }
    private long article(Actor actor) {
        return jdbc.queryForObject("INSERT INTO articulos(taller_id,nombre) VALUES(?,'Synthetic item') RETURNING id", Long.class, actor.taller());
    }
    private void item(long budget) {
        jdbc.update("INSERT INTO presupuesto_items(presupuesto_id,descripcion,cantidad,precio_unitario) VALUES(?,'Identical synthetic item',1,10)", budget);
    }
    private Graph graph(Actor actor) {
        long client = client(actor), equipment = equipment(actor, client), repair = repair(actor, equipment), article = article(actor);
        long budget = jdbc.queryForObject("INSERT INTO presupuestos(taller_id,reparacion_id,estado,total) VALUES(?,?,'PENDIENTE',10) RETURNING id", Long.class, actor.taller(), repair);
        item(budget);
        jdbc.update("INSERT INTO cobros(taller_id,reparacion_id,monto,metodo) VALUES(?,?,10,'EFECTIVO')", actor.taller(), repair);
        jdbc.update("INSERT INTO repuestos(taller_id,reparacion_id,articulo_id,nombre,precio) VALUES(?,?,?,'Synthetic spare',10)", actor.taller(), repair, article);
        return new Graph(client, equipment, repair, article, budget);
    }
    private void subscriptionEvidence(Actor actor) {
        long subscription = jdbc.queryForObject("INSERT INTO suscripciones(taller_id,plan,estado) VALUES(?,'FREE','TRIAL') RETURNING id", Long.class, actor.taller());
        jdbc.update("""
                INSERT INTO subscription_provider_links(suscripcion_id,provider,external_reference,external_subscription_id,
                  idempotency_key,status,is_current,created_at,updated_at)
                VALUES(?,'MERCADO_PAGO',?,?,?,'canceled',true,clock_timestamp(),clock_timestamp())
                """, subscription, "ref-" + UUID.randomUUID(), "external-" + UUID.randomUUID(), UUID.randomUUID().toString());
        jdbc.update("""
                INSERT INTO subscription_payments(suscripcion_id,provider,external_authorized_payment_id,amount,currency,
                  payment_status,created_at,updated_at)
                VALUES(?,'MERCADO_PAGO',?,100,'ARS','approved',clock_timestamp(),clock_timestamp())
                """, subscription, "authorized-" + UUID.randomUUID());
    }
    private String retained(Actor actor) {
        StringBuilder result = new StringBuilder();
        for (String table : List.of("users", "cuenta_cierres", "cuenta_cierre_operaciones", "cuenta_cierre_efectos",
                "suscripciones", "legal_aceptacion_lotes", "legal_aceptaciones"))
            result.append(rows(table, "taller_id=?", actor.taller()));
        result.append(rows("talleres", "id=?", actor.taller()));
        for (String table : List.of("subscription_provider_links", "subscription_payments"))
            result.append(rows(table, "suscripcion_id IN (SELECT id FROM suscripciones WHERE taller_id=?)", actor.taller()));
        return result.toString();
    }
    private String fingerprint(Actor actor) {
        StringBuilder result = new StringBuilder(retained(actor));
        for (String table : OPERATIONAL) result.append(rows(table, "taller_id=?", actor.taller()));
        for (String table : List.of("cuenta_borrado_lotes", "reparacion_fotos", "reparacion_fotos_privadas", "reparacion_foto_eliminaciones"))
            result.append(rows(table, "taller_id=?", actor.taller()));
        return result.toString();
    }
    private String rows(String table, String predicate, long id) {
        return jdbc.queryForObject("SELECT coalesce(string_agg(to_jsonb(r)::text||xmin::text,',' ORDER BY to_jsonb(r)::text),'') FROM public."
                + table + " r WHERE " + predicate, String.class, id);
    }
    private long count(String table, Actor actor) {
        return jdbc.queryForObject("SELECT count(*) FROM public." + table + " WHERE taller_id=?", Long.class, actor.taller());
    }
    private void assertNoContext() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.cuenta_borrado_contextos", Long.class)).isZero();
    }
    private static OffsetDateTime dbNow() { return jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class); }
    private static void reject(Runnable call, Rejected.Code code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(Rejected.class, failure -> assertThat(failure.code()).isEqualTo(code))
                .hasMessage("No se pudo completar el borrado operativo del cierre.").hasNoCause();
    }
    private static void assertSqlFailure(Runnable call) {
        Throwable failure = catchThrowable(call::run);
        assertThat(failure).isNotNull();
        while (failure.getCause() != null) failure = failure.getCause();
        assertThat(failure).isInstanceOfSatisfying(SQLException.class,
                sql -> assertThat(sql.getSQLState()).isIn("P0037", "P0033"));
    }
    private static void assertSqlState(Runnable call, String state) {
        Throwable failure = catchThrowable(call::run);
        assertThat(failure).isNotNull();
        while (failure.getCause() != null) failure = failure.getCause();
        assertThat(failure).isInstanceOfSatisfying(SQLException.class, sql -> assertThat(sql.getSQLState()).isEqualTo(state));
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("fixture latch timeout"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
    private record Actor(long taller, long user) { }
    private record Graph(long client, long equipment, long repair, long article, long budget) { }
}
