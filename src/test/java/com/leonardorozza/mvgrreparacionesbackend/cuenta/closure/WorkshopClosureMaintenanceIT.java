package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;

/** Synthetic residual rows and legal V33 transitions; C authorization is covered by its command IT. */
@Testcontainers
class WorkshopClosureMaintenanceIT {
    @Container static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("closure_maintenance").withUsername("fixture").withPassword("fixture-password");
    static JdbcTemplate jdbc;
    static DataSourceTransactionManager manager;
    static DriverManagerDataSource source;
    WorkshopClosureGate gate;
    WorkshopClosureMaintenanceService service;
    Actor own;
    UUID reference;

    @BeforeAll static void database() {
        source = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(source);
        manager = new DataSourceTransactionManager(source);
    }
    @BeforeEach void setup() {
        gate = new WorkshopClosureGate(jdbc);
        service = new WorkshopClosureMaintenanceService(jdbc, manager, gate);
        own = actor();
        reference = UUID.randomUUID();
    }

    @Test void batchesAreBoundedAndRepeatedPassesBecomeReadOnlyWithoutErasingTheWorkshop() {
        Actor foreign = actor();
        for (int i=0; i<26; i++) {
            auth(own, false); exportProof(own); closureProof(own, false, false);
            archive(own, "EXPIRED", -8);
        }
        auth(foreign, false); exportProof(foreign); closureProof(foreign, false, false);
        String foreignBefore = fingerprint(foreign);
        String spent = closureProof(own, true, false);
        String future = closureProof(own, false, true);
        auth(own, true);
        close(own, reference, dbNow().minusDays(8));
        String anchor = immutableState(own);
        var first = service.cleanExpired(own.taller(), reference);
        assertThat(first.expiredAuthTokens()).isEqualTo(25);
        assertThat(first.expiredExportProofs()).isEqualTo(25);
        assertThat(first.expiredUnusedClosureProofs()).isEqualTo(25);
        assertThat(first.purgedExportMetadata()).isEqualTo(25);
        assertThat(first.moreEligibleAtObservation()).isTrue();
        var second = service.cleanExpired(own.taller(), reference);
        assertThat(second.expiredAuthTokens()).isEqualTo(1);
        assertThat(second.expiredExportProofs()).isEqualTo(1);
        assertThat(second.expiredUnusedClosureProofs()).isEqualTo(1);
        assertThat(second.purgedExportMetadata()).isEqualTo(1);
        assertThat(second.moreEligibleAtObservation()).isFalse();
        String before = fingerprint(own);
        var third = service.cleanExpired(own.taller(), reference);
        assertThat(third.expiredAuthTokens()+third.expiredExportProofs()+third.expiredUnusedClosureProofs()
                +third.expiredArchives()+third.purgedExportMetadata()).isZero();
        assertThat(fingerprint(own)).isEqualTo(before);
        assertThat(fingerprint(foreign)).isEqualTo(foreignBefore);
        assertThat(immutableState(own)).isEqualTo(anchor);
        assertThat(jdbc.queryForList("SELECT token_hash::text FROM cuenta_cierre_confirmaciones WHERE taller_id=?", String.class, own.taller()))
                .containsExactlyInAnyOrder(spent, future);
        assertThat(third.toString()).isEqualTo("ClosureMaintenanceBatch[redacted]");
    }

    @ParameterizedTest @ValueSource(strings={"QUEUED","RUNNING","READY"})
    void expiredArchiveLosesAllPayloadAndLeaseButPreservesIdentityAndOriginalDates(String state) {
        UUID job = archive(own, state, -1);
        close(own, reference, dbNow().minusDays(8));
        var before = jdbc.queryForMap("SELECT user_id,taller_id,token_version,session_hash,request_hash,creada_en,expira_en,intentos,capturada_en FROM cuenta_exportaciones WHERE id=?", job);
        var result = service.cleanExpired(own.taller(), reference);
        assertThat(result.expiredArchives()).isEqualTo(1);
        assertThat(result.purgedExportMetadata()).isZero();
        assertThat(jdbc.queryForMap("SELECT user_id,taller_id,token_version,session_hash,request_hash,creada_en,expira_en,intentos,capturada_en FROM cuenta_exportaciones WHERE id=?", job)).isEqualTo(before);
        var row = jdbc.queryForMap("SELECT estado,snapshot_cipher,archive_cipher,lease_id,lease_hasta,cardinality(foto_ids) AS photos FROM cuenta_exportaciones WHERE id=?", job);
        assertThat(row).containsEntry("estado", "EXPIRED").containsEntry("snapshot_cipher", null)
                .containsEntry("archive_cipher", null).containsEntry("lease_id", null).containsEntry("lease_hasta", null).containsEntry("photos", 0);
    }

    @Test void recentTerminalMetadataIsRetainedAndOldMetadataIsPurged() {
        UUID recent = archive(own, "REVOKED", -6);
        UUID old = archive(own, "FAILED", -8);
        close(own, reference, dbNow().minusDays(8));
        assertThat(service.cleanExpired(own.taller(), reference).purgedExportMetadata()).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT id FROM cuenta_exportaciones WHERE taller_id=?", UUID.class, own.taller()))
                .containsExactly(recent).doesNotContain(old);
    }

    @Test void terminalMetadataIsRetainedAtTheExactSevenDayBoundary() {
        UUID job = archive(own, "EXPIRED", -7);
        OffsetDateTime cutoff = dbNow();
        jdbc.update("UPDATE cuenta_exportaciones SET actualizada_en=? WHERE id=?", cutoff.minusDays(7), job);
        close(own, reference, cutoff.minusDays(8));
        var observed = new java.util.concurrent.atomic.AtomicReference<>(cutoff);
        var fixed = new JdbcTemplate(source) {
            @Override public <T> T queryForObject(String sql, Class<T> type) {
                if (sql.equals("SELECT clock_timestamp()")) return type.cast(observed.get());
                return super.queryForObject(sql, type);
            }
        };
        var fixedService = new WorkshopClosureMaintenanceService(fixed, manager, gate);
        assertThat(fixedService.cleanExpired(own.taller(), reference).purgedExportMetadata()).isZero();
        observed.set(cutoff.plusNanos(1000));
        assertThat(fixedService.cleanExpired(own.taller(), reference).purgedExportMetadata()).isEqualTo(1);
    }

    @Test void futureCredentialsUseJvmLocalTimeEvenWhenTheDatabaseUsesUtc() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Argentina/Buenos_Aires"));
            var utcSource = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()) {
                @Override public java.sql.Connection getConnection() throws java.sql.SQLException {
                    var connection = super.getConnection();
                    try (var statement = connection.createStatement()) { statement.execute("SET timezone='UTC'"); }
                    return connection;
                }
            };
            var utc = new JdbcTemplate(utcSource);
            assertThat(utc.queryForObject("SHOW timezone", String.class)).isEqualTo("UTC");
            var utcService = new WorkshopClosureMaintenanceService(utc, new DataSourceTransactionManager(utcSource), new WorkshopClosureGate(utc));
            auth(own, false); long future = auth(own, true);
            close(own, reference, dbNow().minusDays(8));
            assertThat(utcService.cleanExpired(own.taller(), reference).expiredAuthTokens()).isEqualTo(1);
            assertThat(jdbc.queryForList("SELECT id FROM auth_tokens WHERE taller_id=?", Long.class, own.taller())).containsExactly(future);
        } finally { TimeZone.setDefault(original); }
    }

    @Test void aFutureReadyArchiveIsNotPrematurelyExpired() {
        UUID job = archive(own, "READY", 1);
        close(own, reference, dbNow().minusDays(8));
        String before = fingerprint(own);
        assertThat(service.cleanExpired(own.taller(), reference).expiredArchives()).isZero();
        assertThat(fingerprint(own)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT estado FROM cuenta_exportaciones WHERE id=?", String.class, job)).isEqualTo("READY");
    }

    @Test void graceAndWrongReferencesRejectWithNoDml() {
        auth(own, false);
        close(own, reference, dbNow());
        String before = fingerprint(own);
        assertRejected(() -> service.cleanExpired(own.taller(), reference));
        assertRejected(() -> service.cleanExpired(own.taller(), UUID.randomUUID()));
        assertRejected(() -> service.cleanExpired(actor().taller(), reference));
        assertRejected(() -> service.cleanExpired(-1, reference));
        assertRejected(() -> service.cleanExpired(own.taller(), null));
        assertThat(fingerprint(own)).isEqualTo(before);
    }

    @Test void restoredReferenceCannotStartMaintenance() {
        close(own, reference, dbNow());
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            gate.lockExclusive(own.taller());
            new WorkshopClosureStore(jdbc, Clock.systemUTC()).restore(own.taller(), own.user(), reference);
        });
        String before = fingerprint(own);
        assertRejected(() -> service.cleanExpired(own.taller(), reference));
        assertThat(fingerprint(own)).isEqualTo(before);
    }

    @Test void finalFailureRollsBackAllEarlierCategoriesAndSanitizesTheError() {
        auth(own, false); exportProof(own); closureProof(own, false, false);
        archive(own, "EXPIRED", -8);
        close(own, reference, dbNow().minusDays(8));
        String before = fingerprint(own);
        var failing = new JdbcTemplate(source) {
            @Override public int update(String sql, Object... args) {
                if (sql.contains("DELETE FROM public.cuenta_exportaciones"))
                    throw new IllegalStateException("private SQL/provider/identity diagnostic");
                return super.update(sql, args);
            }
        };
        var failedService = new WorkshopClosureMaintenanceService(failing, manager, gate);
        assertThatThrownBy(() -> failedService.cleanExpired(own.taller(), reference))
                .isInstanceOf(WorkshopClosureMaintenanceService.Rejected.class)
                .hasMessage("No se pudo completar el mantenimiento del cierre.").hasNoCause();
        assertThat(fingerprint(own)).isEqualTo(before);
    }

    @Test void requiresNewCommitsCleanupIndependentlyOfAnUnrelatedCallerRollback() {
        auth(own, false); close(own, reference, dbNow().minusDays(8));
        var inspected = new AtomicBoolean();
        var observing = new JdbcTemplate(source) {
            @Override public int update(String sql, Object... args) {
                assertThat(queryForObject("SHOW transaction_isolation", String.class)).isEqualTo("read committed");
                inspected.set(true);
                return super.update(sql, args);
            }
        };
        var nestedService = new WorkshopClosureMaintenanceService(observing, manager, gate);
        Actor uncommitted = actor();
        var outer = new TransactionTemplate(manager);
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        outer.executeWithoutResult(status -> {
            jdbc.update("UPDATE users SET username='caller rollback fixture' WHERE id=?", uncommitted.user());
            assertThat(nestedService.cleanExpired(own.taller(), reference).expiredAuthTokens()).isEqualTo(1);
            status.setRollbackOnly();
        });
        assertThat(inspected).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_tokens WHERE taller_id=?", Long.class, own.taller())).isZero();
        assertThat(jdbc.queryForObject("SELECT username FROM users WHERE id=?", String.class, uncommitted.user())).isNotEqualTo("caller rollback fixture");
    }

    @Test void lockedRowsStayPendingAndAreCleanedOnTheNextPass() throws Exception {
        long token = auth(own, false); close(own, reference, dbNow().minusDays(8));
        try (var executor = Executors.newSingleThreadExecutor()) {
            CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
            var lock = executor.submit(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM auth_tokens WHERE id=? FOR UPDATE", Long.class, token);
                locked.countDown(); await(release);
            }));
            try {
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                var skipped = service.cleanExpired(own.taller(), reference);
                assertThat(skipped.expiredAuthTokens()).isZero();
                assertThat(skipped.moreEligibleAtObservation()).isTrue();
            } finally { release.countDown(); }
            lock.get(5, TimeUnit.SECONDS);
            assertThat(service.cleanExpired(own.taller(), reference).expiredAuthTokens()).isEqualTo(1);
        }
    }

    @Test void anAlreadyAdmittedWriterFinishesBeforeMaintenance() throws Exception {
        long token = auth(own, false); close(own, reference, dbNow().minusDays(8));
        try (var executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch admitted = new CountDownLatch(1), release = new CountDownLatch(1);
            var writer = executor.submit(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT public.cuenta_cierre_estado_v33(?)", String.class, own.taller());
                jdbc.update("DELETE FROM auth_tokens WHERE id=?", token);
                admitted.countDown(); await(release);
            }));
            Future<WorkshopClosureMaintenanceService.Batch> cleanup = null;
            try {
                assertThat(admitted.await(5, TimeUnit.SECONDS)).isTrue();
                cleanup = executor.submit(() -> service.cleanExpired(own.taller(), reference));
                // A pending advisory lock is evidence the contender has reached the gate.
                long until = System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
                while (System.nanoTime()<until && jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND NOT granted", Long.class)==0)
                    Thread.onSpinWait();
                assertThat(cleanup.isDone()).isFalse();
            } finally { release.countDown(); }
            writer.get(5, TimeUnit.SECONDS);
            assertThat(Objects.requireNonNull(cleanup).get(5, TimeUnit.SECONDS).expiredAuthTokens()).isZero();
        }
    }

    private Actor actor() {
        long taller = jdbc.queryForObject("INSERT INTO talleres(nombre) VALUES('maintenance synthetic') RETURNING id", Long.class);
        long user = jdbc.queryForObject("INSERT INTO users(username,email,password,role,taller_id,active,email_verificado,token_version) VALUES(?,?,?,'ADMIN',?,true,true,0) RETURNING id", Long.class,
                UUID.randomUUID().toString(), UUID.randomUUID()+"@synthetic.invalid", "not-a-login-secret", taller);
        return new Actor(taller, user);
    }
    /** Residual-row fixture using all real guards, with a historical closure date. No guard is disabled. */
    private void close(Actor actor, UUID ref, OffsetDateTime confirmed) {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            gate.lockExclusive(actor.taller());
            jdbc.update("UPDATE users SET token_version=token_version+1 WHERE taller_id=?", actor.taller());
            jdbc.update("""
                    INSERT INTO cuenta_cierres(referencia,taller_id,titular_id,generacion,estado,politica,confirmado_en,reversible_hasta,eliminacion_prevista_en)
                    VALUES(?,?,?,1,'RESTRINGIDO','ordenfix-cierre/1',?,?,?)
                    """, ref, actor.taller(), actor.user(), confirmed, confirmed.plusDays(7), confirmed.plusDays(37));
            jdbc.update("""
                    UPDATE talleres SET cierre_estado='RESTRINGIDO',cierre_version=1,cierre_referencia=?,cierre_confirmado_en=?,
                        cierre_reversible_hasta=?,cierre_eliminacion_prevista_en=? WHERE id=?
                    """, ref, confirmed, confirmed.plusDays(7), confirmed.plusDays(37), actor.taller());
        });
    }
    private long auth(Actor a, boolean future) {
        return jdbc.queryForObject("INSERT INTO auth_tokens(user_id,tipo,token_hash,expira_en,created_at) VALUES(?,'RESET_PASSWORD',?,?,?) RETURNING id", Long.class,
                a.user(), hex(), LocalDateTime.now().plusHours(future?1:-1), LocalDateTime.now().minusHours(2));
    }
    private void exportProof(Actor a) {
        OffsetDateTime at = dbNow().minusHours(1);
        jdbc.update("""
                INSERT INTO cuenta_reautenticaciones(token_hash,user_id,taller_id,token_version,session_hash,proposito,creada_en,expira_en)
                VALUES(?,?,?,0,?,'DESCARGAR_EXPORTACION',?,?)
                """, hex(), a.user(), a.taller(), hex(), at, at.plusMinutes(5));
    }
    private String closureProof(Actor a, boolean used, boolean future) {
        String hash = hex(); UUID operation = UUID.randomUUID(); OffsetDateTime at = future?dbNow():dbNow().minusHours(1);
        jdbc.update("""
                INSERT INTO cuenta_cierre_confirmaciones(token_hash,user_id,taller_id,token_version,session_hash,proposito,
                  operacion_id,cierre_referencia,cierre_version,creada_en,expira_en)
                VALUES(?,?,?,0,?,'CERRAR',?,?,0,?,?)
                """, hash, a.user(), a.taller(), hex(), operation, operation, at, at.plusMinutes(5));
        if (used) jdbc.update("UPDATE cuenta_cierre_confirmaciones SET usada_en=? WHERE token_hash=?", at.plusMinutes(1), hash);
        return hash;
    }
    private UUID archive(Actor a, String state, int updatedDays) {
        UUID id = UUID.randomUUID(); OffsetDateTime now = dbNow();
        boolean ready = state.equals("READY"), running = state.equals("RUNNING"), queued = state.equals("QUEUED");
        boolean active = ready || running || queued;
        OffsetDateTime created = now.minusDays(20);
        OffsetDateTime expires = active ? now.plusHours(updatedDays>0?1:-1) : created.plusHours(24);
        if (active) created = expires.minusHours(24);
        jdbc.update("""
                INSERT INTO cuenta_exportaciones(id,user_id,taller_id,token_version,session_hash,request_hash,estado,creada_en,actualizada_en,
                  expira_en,intentos,lease_id,lease_hasta,capturada_en,snapshot_cipher,archive_cipher)
                VALUES(?,?,?,0,?,?,?,?,?,?,1,?,?,?,?,?)
                """, id, a.user(), a.taller(), hex(), hex(), state, created, now.plusDays(updatedDays), expires,
                running?UUID.randomUUID():null, running?now.plusMinutes(1):null, active?created:null,
                running||queued?new byte[32]:null, ready?new byte[32]:null);
        return id;
    }
    private String immutableState(Actor a) {
        return jdbc.queryForObject("SELECT to_jsonb(t)::text||t.xmin::text||to_jsonb(u)::text||u.xmin::text||to_jsonb(h)::text||h.xmin::text FROM talleres t JOIN users u ON u.taller_id=t.id JOIN cuenta_cierres h ON h.taller_id=t.id WHERE t.id=?", String.class, a.taller());
    }
    private String fingerprint(Actor a) {
        StringBuilder result = new StringBuilder();
        for (String table : List.of("auth_tokens","cuenta_reautenticaciones","cuenta_cierre_confirmaciones","cuenta_exportaciones","users","cuenta_cierres"))
            result.append(jdbc.queryForObject("SELECT coalesce(string_agg(to_jsonb(r)::text||xmin::text,',' ORDER BY to_jsonb(r)::text),'') FROM public."+table+" r WHERE taller_id=?", String.class, a.taller()));
        result.append(jdbc.queryForObject("SELECT to_jsonb(t)::text||xmin::text FROM talleres t WHERE id=?", String.class, a.taller()));
        return result.toString();
    }
    private static OffsetDateTime dbNow() { return jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class); }
    private static String hex() { return UUID.randomUUID().toString().replace("-", "")+UUID.randomUUID().toString().replace("-", ""); }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("fixture latch timeout"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
    private static void assertRejected(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(WorkshopClosureMaintenanceService.Rejected.class)
                .hasMessage("No se pudo completar el mantenimiento del cierre.").hasNoCause();
    }
    private record Actor(long taller, long user) { }
}
