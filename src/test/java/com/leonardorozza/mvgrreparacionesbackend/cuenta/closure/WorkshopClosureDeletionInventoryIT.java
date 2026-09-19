package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.WorkshopClosureDeletionInventory.*;
import static org.assertj.core.api.Assertions.*;

/** Disposable PostgreSQL, complete Flyway history and normal store/gates. No trigger or constraint bypass. */
@Testcontainers
class WorkshopClosureDeletionInventoryIT {
    @Container static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("closure_inventory_synthetic").withUsername("inventory_fixture").withPassword("fixture-only");
    private static JdbcTemplate jdbc;
    private static DataSourceTransactionManager manager;
    private static WorkshopClosureGate gate;
    private static WorkshopClosureStore store;
    private static final AdjustableClock clock = new AdjustableClock();
    private WorkshopClosureDeletionInventory inventory;
    private Actor own;

    @BeforeAll static void database() {
        var source = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").target("35").load().migrate();
        jdbc = new JdbcTemplate(source);
        manager = new DataSourceTransactionManager(source);
        gate = new WorkshopClosureGate(jdbc);
        store = new WorkshopClosureStore(jdbc, clock);
    }

    @BeforeEach void fixture() {
        clock.now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        inventory = new WorkshopClosureDeletionInventory(jdbc, manager, clock);
        own = actor();
        assertThat(jdbc.queryForObject("SHOW session_replication_role", String.class)).isEqualTo("origin");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_trigger WHERE tgname='aa_cuenta_guard_v33' AND tgenabled='O'", Long.class)).isEqualTo(28);
    }

    @Test void countsBelongToTheRequestedWorkshopAcrossOrdinaryParentAndChildRelations() {
        graph(own); Actor other = actor(); graph(other); graph(other);
        UUID reference = close(own); close(other);
        Report report = inventory.inspect(own.workshop(), reference);
        assertThat(report.generation()).isEqualTo(1);
        assertThat(report.closureReference()).isEqualTo(reference);
        for (Metric metric : List.of(Metric.CUSTOMERS, Metric.EQUIPMENT, Metric.REPAIRS, Metric.PARTS,
                Metric.BUDGETS, Metric.BUDGET_ITEMS, Metric.LEGACY_PHOTO_ROWS, Metric.AUTH_TOKENS,
                Metric.SUBSCRIPTIONS, Metric.PROVIDER_LINKS, Metric.SUBSCRIPTION_PAYMENTS))
            assertThat(report.count(metric)).as(metric.name()).isEqualTo(1);
        assertThat(report.count(Metric.USERS)).isEqualTo(2);
        assertThat(report.count(Metric.ACTIVE_USERS)).isEqualTo(2);
        assertThat(report.count(Metric.CLOSURE_HISTORY_ROWS)).isEqualTo(1);
        assertThat(report.categories().get(Category.LEGACY_PHOTOS).reviewReasons())
                .contains(ReviewReason.LEGACY_REMOTE_OWNERSHIP_UNPROVEN);
    }

    @Test void anEmptyCategoryIsOnlyAnObservationAndKeepsPolicyAndBackupReviewPending() {
        var report = inventory.inspect(own.workshop(), close(own));
        for (Category category : List.of(Category.OPERATIONAL, Category.PRIVATE_PHOTOS, Category.LEGACY_PHOTOS, Category.LEGAL_EVIDENCE)) {
            assertThat(report.categories().get(category).observation()).isEqualTo(Observation.NO_LOCAL_ROWS);
            assertThat(report.categories().get(category).reviewReasons()).contains(ReviewReason.RETENTION_POLICY_REQUIRED);
        }
        assertThat(report.count(Metric.PRIVATE_PHOTOS_PENDING_CLEANUP)).isZero();
        assertThat(report.count(Metric.PRIVATE_PHOTOS_WITH_LIVE_LEASE)).isZero();
        assertThat(report.count(Metric.PRIVATE_PHOTOS_WITH_ASSET_ID)).isZero();
        for (Metric metric : List.of(Metric.PRIVATE_PHOTO_DELETION_TARGETS, Metric.PRIVATE_PHOTO_DELETIONS_CONFIRMED,
                Metric.PRIVATE_PHOTO_ABSENCE_ONLY, Metric.PRIVATE_PHOTO_DELETIONS_PENDING,
                Metric.PRIVATE_PHOTOS_DELETED_WITHOUT_RECEIPT)) assertThat(report.count(metric)).as(metric.name()).isZero();
        assertThat(report.categories().get(Category.PRIVATE_PHOTOS).reviewReasons())
                .doesNotContain(ReviewReason.PHOTO_DELETION_RECONCILIATION_REQUIRED);
        assertThat(report.reviewReasons()).contains(ReviewReason.BACKUP_RESTORE_EVIDENCE_REQUIRED,
                ReviewReason.RETENTION_POLICY_REQUIRED, ReviewReason.RESTORATION_WINDOW_ACTIVE);
        assertThat(jdbc.queryForObject("SELECT cierre_estado FROM talleres WHERE id=?", String.class, own.workshop()))
                .isEqualTo("RESTRINGIDO");
    }

    @Test void observationsAndTheirStringRepresentationsNeverReturnSeededPersonalOrCredentialMaterial() throws Exception {
        graph(own); UUID reference = close(own);
        Report report = inventory.inspect(own.workshop(), reference);
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(report);
        assertThat(json).doesNotContain("private-fixture-name", "private-fixture-password", "synthetic.invalid",
                "private-fixture-remote", "private-fixture-token", "https://", "archive_cipher", "session_hash", "object_key");
        assertThat(report.toString()).isEqualTo("WorkshopClosureDeletionInventory.Report[redacted]");
        assertThat(report.categories().values()).allSatisfy(value -> assertThat(value.toString()).isEqualTo("CategoryObservation[redacted]"));
        assertThat(report.globalReview().toString()).isEqualTo("GlobalReview[redacted]");
        assertThatThrownBy(() -> report.categories().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void repeatedInspectionDoesNotConsumeProofsUpdateRowsOrRemoveArtifacts() {
        graph(own); UUID reference = closeWithReview(own);
        var before = fingerprint();
        Report first = inventory.inspect(own.workshop(), reference);
        Report second = inventory.inspect(own.workshop(), reference);
        assertThat(second).isEqualTo(first);
        assertThat(first.count(Metric.CLOSURE_OPERATIONS)).isEqualTo(1);
        assertThat(first.count(Metric.USED_CLOSURE_CONFIRMATIONS)).isEqualTo(1);
        assertThat(first.count(Metric.CLOSURE_EFFECTS)).isEqualTo(1);
        assertThat(first.count(Metric.UNCONFIRMED_RENEWAL_EFFECTS)).isEqualTo(1);
        assertThat(first.count(Metric.UNCERTAIN_EFFECTS)).isEqualTo(1);
        assertThat(first.count(Metric.EXPIRED_EFFECT_LEASES)).isZero();
        assertThat(first.count(Metric.CANCELLATIONS_WITHOUT_REMOTE_ID)).isZero();
        assertThat(first.count(Metric.EXHAUSTED_EFFECTS)).isZero();
        assertThat(first.categories().get(Category.SUBSCRIPTIONS_AND_EFFECTS).reviewReasons())
                .contains(ReviewReason.RENEWAL_COORDINATION_UNRESOLVED, ReviewReason.EFFECT_RECONCILIATION_REQUIRED);
        assertThat(fingerprint()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(longs = {0, -1, Long.MAX_VALUE})
    void missingOrInvalidWorkshopDoesNotProduceAnInventory(long workshop) {
        var before = fingerprint();
        assertRejected(() -> inventory.inspect(workshop, UUID.randomUUID()), Rejected.Code.INVALID_REFERENCE);
        assertThat(fingerprint()).isEqualTo(before);
    }

    @Test void openWorkshopsNullReferencesAndAnotherWorkshopsClosureAreRejected() {
        assertRejected(() -> inventory.inspect(own.workshop(), UUID.randomUUID()), Rejected.Code.INVALID_REFERENCE);
        UUID ownReference = close(own); Actor other = actor(); UUID otherReference = close(other);
        assertRejected(() -> inventory.inspect(own.workshop(), null), Rejected.Code.INVALID_REFERENCE);
        assertRejected(() -> inventory.inspect(own.workshop(), otherReference), Rejected.Code.INVALID_REFERENCE);
        assertRejected(() -> inventory.inspect(other.workshop(), ownReference), Rejected.Code.INVALID_REFERENCE);
    }

    @Test void restorationAndASecondClosureCannotReuseTheOldReferenceOrGeneration() {
        UUID old = close(own);
        tx(() -> { gate.lockExclusive(own.workshop()); return store.restore(own.workshop(), own.owner(), old); });
        assertRejected(() -> inventory.inspect(own.workshop(), old), Rejected.Code.INVALID_REFERENCE);
        UUID current = close(own);
        assertRejected(() -> inventory.inspect(own.workshop(), old), Rejected.Code.INVALID_REFERENCE);
        assertThat(inventory.inspect(own.workshop(), current).generation()).isEqualTo(3);
    }

    @Test void postGraceInspectionRemainsAvailableButClockBeforeConfirmationFailsClosed() {
        UUID reference = close(own); Instant confirmed = clock.now;
        clock.now = confirmed.plus(Duration.ofDays(7));
        assertThat(inventory.inspect(own.workshop(), reference).reviewReasons()).doesNotContain(ReviewReason.RESTORATION_WINDOW_ACTIVE);
        clock.now = confirmed.minusNanos(1000);
        assertRejected(() -> inventory.inspect(own.workshop(), reference), Rejected.Code.INVALID_REFERENCE);
    }

    @Test void requiresNewSuspendsUncommittedCallerDataAndPreservesItsRollback() {
        UUID reference = close(own); var before = fingerprint();
        rc().executeWithoutResult(status -> {
            jdbc.update("UPDATE users SET active=false WHERE id=?", own.employee());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE taller_id=? AND active", Long.class, own.workshop())).isEqualTo(1);
            assertThat(inventory.inspect(own.workshop(), reference).count(Metric.ACTIVE_USERS)).isEqualTo(2);
            status.setRollbackOnly();
        });
        assertThat(fingerprint()).isEqualTo(before);
    }

    @Test void aCommitAfterAnchorReadDoesNotExpandTheRepeatableReadObservation() throws Exception {
        UUID reference = close(own);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            AtomicBoolean checkpoint = new AtomicBoolean();
            JdbcTemplate checkpointJdbc = new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource())) {
                @Override public <T> List<T> query(String sql, RowMapper<T> mapper, Object... arguments) {
                    List<T> result = super.query(sql, mapper, arguments);
                    if (sql.contains("FOR SHARE OF t") && checkpoint.compareAndSet(false, true)) {
                        assertThat(queryForObject("SHOW transaction_isolation", String.class)).isEqualTo("repeatable read");
                        try {
                            executor.submit(() -> jdbc.update("UPDATE users SET active=false WHERE id=?", own.employee())).get(5, TimeUnit.SECONDS);
                        } catch (Exception failure) { throw new AssertionError("Concurrent fixture failed", failure); }
                    }
                    return result;
                }
            };
            var observing = new WorkshopClosureDeletionInventory(checkpointJdbc, manager, clock);
            assertThat(observing.inspect(own.workshop(), reference).count(Metric.ACTIVE_USERS)).isEqualTo(2);
            assertThat(checkpoint).isTrue();
            assertThat(inventory.inspect(own.workshop(), reference).count(Metric.ACTIVE_USERS)).isEqualTo(1);
        }
    }

    @Test void anExclusiveTransitionRejectsAdmissionBeforeAnyInventoryRead() throws Exception {
        UUID reference = close(own);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
            Future<?> holder = executor.submit(() -> tx(() -> { gate.lockExclusive(own.workshop()); locked.countDown(); await(release); return null; }));
            assertThat(locked.await(3, TimeUnit.SECONDS)).isTrue();
            try { assertThatThrownBy(() -> inventory.inspect(own.workshop(), reference)).isInstanceOf(WorkshopClosureBusyException.class); }
            finally { release.countDown(); }
            holder.get(5, TimeUnit.SECONDS);
        }
        assertThat(inventory.inspect(own.workshop(), reference).generation()).isEqualTo(1);
    }

    @Test void aBlockedCategoryHitsTheSqlBudgetReturnsNoPartialReportAndReleasesItsGate() throws Exception {
        UUID reference = close(own); var before = fingerprint();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
            Future<?> holder = executor.submit(() -> tx(() -> {
                jdbc.execute("LOCK TABLE public.clientes IN ACCESS EXCLUSIVE MODE"); locked.countDown(); await(release); return null;
            }));
            assertThat(locked.await(3, TimeUnit.SECONDS)).isTrue();
            try {
                Future<?> observed = executor.submit(() -> assertRejected(
                        () -> inventory.inspect(own.workshop(), reference), Rejected.Code.UNAVAILABLE));
                observed.get(7, TimeUnit.SECONDS);
                // Independent exclusive admission proves the failed RR transaction did not retain its shared lock.
                tx(() -> { gate.lockExclusive(own.workshop()); return null; });
            } finally { release.countDown(); }
            holder.get(5, TimeUnit.SECONDS);
        }
        assertThat(fingerprint()).isEqualTo(before);
    }

    @Test void providerEventsStayGlobalEvenWhenTheirDataIdLooksLikeThisWorkshopsProviderLink() {
        graph(own); UUID reference = close(own); Actor other = actor(); UUID otherReference = close(other);
        jdbc.update("""
                INSERT INTO payment_events(provider,provider_event_key,event_type,data_id,status,attempts,received_at,last_attempt_at)
                VALUES('MERCADO_PAGO',?,'subscription','private-fixture-remote','PENDING',0,clock_timestamp(),clock_timestamp())
                """, UUID.randomUUID().toString());
        Report first = inventory.inspect(own.workshop(), reference), second = inventory.inspect(other.workshop(), otherReference);
        assertThat(first.globalReview()).isEqualTo(second.globalReview());
        assertThat(first.globalReview().paymentEventsPresent()).isTrue();
        assertThat(first.globalReview().reviewReason()).isEqualTo(ReviewReason.PAYMENT_EVENT_OWNERSHIP_UNRESOLVED);
        assertThat(second.count(Metric.PROVIDER_LINKS)).isZero();
        assertThat(second.count(Metric.SUBSCRIPTION_PAYMENTS)).isZero();
    }

    private static Actor actor() {
        String marker = UUID.randomUUID().toString();
        long workshop = jdbc.queryForObject("INSERT INTO talleres(nombre) VALUES('private-fixture-name') RETURNING id", Long.class);
        long owner = user(workshop, "ADMIN", marker + "-owner");
        long employee = user(workshop, "USER", marker + "-employee");
        long subscription = jdbc.queryForObject("INSERT INTO suscripciones(taller_id,plan,estado,fecha_inicio) VALUES(?,'FREE','TRIAL',CURRENT_DATE) RETURNING id", Long.class, workshop);
        return new Actor(workshop, owner, employee, subscription);
    }
    private static long user(long workshop, String role, String marker) {
        return jdbc.queryForObject("""
                INSERT INTO users(username,email,password,role,taller_id,active,email_verificado,token_version)
                VALUES(?,?,'private-fixture-password',?,?,true,true,0) RETURNING id
                """, Long.class, marker, marker + "@synthetic.invalid", role, workshop);
    }
    private static void graph(Actor actor) {
        long workshop = actor.workshop(); String marker = UUID.randomUUID().toString();
        String phone = "5550100" + jdbc.queryForObject("SELECT count(*) FROM clientes WHERE taller_id=?", Long.class, workshop);
        long customer = jdbc.queryForObject("INSERT INTO clientes(nombre,apellido,telefono,taller_id) VALUES('private-fixture-name','Synthetic',?,?) RETURNING id", Long.class, phone, workshop);
        long equipment = jdbc.queryForObject("INSERT INTO equipos(marca,modelo,cliente_id,taller_id) VALUES('Synthetic','Fixture',?,?) RETURNING id", Long.class, customer, workshop);
        long repair = jdbc.queryForObject("INSERT INTO reparaciones(descripcion_problema,estado,equipo_id,taller_id) VALUES('Fixture','INGRESADA',?,?) RETURNING id", Long.class, equipment, workshop);
        jdbc.update("INSERT INTO repuestos(nombre,precio,cantidad,reparacion_id,taller_id) VALUES('Fixture',10,1,?,?)", repair, workshop);
        long budget = jdbc.queryForObject("INSERT INTO presupuestos(reparacion_id,taller_id,estado,total) VALUES(?,?,'BORRADOR',10) RETURNING id", Long.class, repair, workshop);
        jdbc.update("INSERT INTO presupuesto_items(presupuesto_id,descripcion,cantidad,precio_unitario) VALUES(?,'Fixture',1,10)", budget);
        jdbc.update("INSERT INTO reparacion_fotos(reparacion_id,url) VALUES(?,'https://synthetic.invalid/private-fixture-remote')", repair);
        jdbc.update("INSERT INTO auth_tokens(user_id,tipo,token_hash,expira_en) VALUES(?,'RESET_PASSWORD',?,CURRENT_TIMESTAMP+INTERVAL '1 hour')", actor.owner(), marker.replace("-", "").repeat(2));
        jdbc.update("""
                INSERT INTO subscription_provider_links(suscripcion_id,provider,external_subscription_id,external_reference,idempotency_key,status,is_current,created_at,updated_at)
                VALUES(?,'MERCADO_PAGO',?, ?,?,'cancelled',false,clock_timestamp(),clock_timestamp())
                """, actor.subscription(), "private-fixture-remote-" + marker, marker, marker);
        jdbc.update("""
                INSERT INTO subscription_payments(suscripcion_id,provider,external_authorized_payment_id,amount,currency,created_at,updated_at)
                VALUES(?,'MERCADO_PAGO',?,10,'ARS',clock_timestamp(),clock_timestamp())
                """, actor.subscription(), marker);
    }
    private static UUID close(Actor actor) {
        UUID reference = UUID.randomUUID();
        tx(() -> { gate.lockExclusive(actor.workshop()); return store.restrict(actor.workshop(), actor.owner(), reference); });
        return reference;
    }
    private static UUID closeWithReview(Actor actor) {
        UUID reference = UUID.randomUUID(); String hash = reference.toString().replace("-", "").repeat(2);
        tx(() -> {
            gate.lockExclusive(actor.workshop());
            OffsetDateTime now = clock.now.atOffset(ZoneOffset.UTC);
            jdbc.update("""
                    INSERT INTO cuenta_cierre_confirmaciones(token_hash,user_id,taller_id,token_version,session_hash,proposito,
                        operacion_id,cierre_referencia,cierre_version,creada_en,expira_en)
                    VALUES(?,?,?,0,repeat('a',64),'CERRAR',?,?,0,?,?)
                    """, hash, actor.owner(), actor.workshop(), reference, reference, now.minusSeconds(1), now.plusMinutes(4));
            jdbc.update("UPDATE cuenta_cierre_confirmaciones SET usada_en=? WHERE token_hash=?", now, hash);
            var receipt = store.restrict(actor.workshop(), actor.owner(), reference);
            jdbc.update("""
                    INSERT INTO cuenta_cierre_operaciones(operacion_id,taller_id,user_id,proposito,cierre_referencia,cierre_version,
                        request_digest,proof_hash,estado_resultante,politica,confirmado_en,reversible_hasta,eliminacion_prevista_en,registrada_en)
                    VALUES(?,?,?,'CERRAR',?,1,repeat('b',64),?,'RESTRINGIDO','ordenfix-cierre/1',?,?,?,?)
                    """, reference, actor.workshop(), actor.owner(), reference, hash,
                    receipt.confirmedAt().atOffset(ZoneOffset.UTC), receipt.reversibleUntil().atOffset(ZoneOffset.UTC),
                    receipt.deletionExpectedBy().atOffset(ZoneOffset.UTC), now);
            jdbc.update("""
                    INSERT INTO cuenta_cierre_efectos(efecto_id,operacion_id,cierre_referencia,taller_id,usuario_id,tipo,estado,available_at,created_at)
                    VALUES(?,?,?,?,?,'REVISAR_RENOVACION','INCIERTO',?,?)
                    """, UUID.randomUUID(), reference, reference, actor.workshop(), actor.owner(), now, now);
            return null;
        });
        return reference;
    }
    private static Map<String, String> fingerprint() {
        Map<String, String> values = new TreeMap<>();
        for (String table : jdbc.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename", String.class)) {
            // Physical row identities only: detects INSERT/UPDATE/DELETE without reading any payload column.
            values.put(table, jdbc.queryForObject("SELECT count(*)::text||':'||coalesce(md5(string_agg(xmin::text||':'||ctid::text,',' ORDER BY ctid)),'') FROM public.\"" + table.replace("\"", "\"\"") + "\"", String.class));
        }
        return values;
    }
    private static void assertRejected(Runnable work, Rejected.Code code) {
        assertThatThrownBy(work::run).isInstanceOfSatisfying(Rejected.class, failure -> {
            assertThat(failure.code()).isEqualTo(code);
            assertThat(failure.getCause()).isNull();
            assertThat(failure.getMessage()).isEqualTo("No se pudo observar el inventario del cierre.");
        });
    }
    private static TransactionTemplate rc() {
        var tx = new TransactionTemplate(manager); tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED); tx.setTimeout(15); return tx;
    }
    private static <T> T tx(Supplier<T> work) { return rc().execute(status -> work.get()); }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(12, TimeUnit.SECONDS)) throw new AssertionError("Fixture deadline"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
    private record Actor(long workshop, long owner, long employee, long subscription) { }
    private static final class AdjustableClock extends Clock {
        volatile Instant now = Instant.now();
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
