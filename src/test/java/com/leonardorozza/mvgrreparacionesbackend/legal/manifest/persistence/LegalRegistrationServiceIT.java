package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand.Acceptance;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand.Document;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequestMetadata;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalRegistrationServiceITSupport.*;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalRegistrationWriterITSupport.Request;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoActoLegal;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalRegistrationServiceITSupport.*;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalRegistrationWriterITSupport.metadata;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalRegistrationWriterITSupport.isInsert;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** PostgreSQL16 service semantics and causal request races, with real restricted writers and crypto. */
class LegalRegistrationServiceIT {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_registration_service").withUsername("ordenfix").withPassword("ordenfix");
    private static final String STALE = "sha256:" + "a".repeat(64);
    private static LegalRegistrationServiceITSupport fixture;
    @TempDir Path directory;

    @BeforeAll static void start() { POSTGRES.start(); fixture = new LegalRegistrationServiceITSupport(POSTGRES); }
    @AfterAll static void stop() { POSTGRES.stop(); }
    @BeforeEach void reset() throws Exception { fixture.reset(directory, getClass()); }
    @AfterEach void resourcesReleased() { assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty(); }

    @Test void oneServiceCallCommitsOneNewAdminAccountAndItsEntireGraph() throws Exception {
        var request = fixture.request();
        try (var h = fixture.harness()) {
            var receipt = h.register(request, key());
            assertThat(receipt.replay()).isFalse(); assertThat(receipt.acceptanceIds()).hasSize(1);
            assertThat(h.probe().borrows).hasValue(1); assertThat(h.probe().commits).hasValue(1);
            assertThat(h.probe().rollbacks).hasValue(0); assertThat(h.probe().hashes).hasValue(1);
            assertThat(h.probe().inserts("talleres")).isEqualTo(1); assertThat(h.probe().inserts("suscripciones")).isEqualTo(1);
            assertThat(h.probe().inserts("users")).isEqualTo(1);
            var actor = fixture.owner.queryForMap("SELECT email,password,role,active,email_verificado,token_version,taller_id FROM users WHERE id=?", receipt.userId());
            assertThat(actor).containsEntry("email", request.command().registration().email()).containsEntry("role", "ADMIN")
                    .containsEntry("active", true).containsEntry("email_verificado", false).containsEntry("token_version", 0L)
                    .containsEntry("taller_id", receipt.tallerId());
            assertThat(new BCryptPasswordEncoder().matches(request.command().registration().password(), actor.get("password").toString())).isTrue();
            assertThat(fixture.owner.queryForObject("SELECT count(*) FROM auth_tokens", Long.class)).isZero();
            assertThat(fixture.owner.queryForObject("SELECT count(*) FROM legal_idempotencia_sin_actos", Long.class)).isZero();
            assertThat(fixture.owner.queryForObject("SELECT count(*) FROM legal_idempotencia_resultados WHERE user_id=? AND taller_id=? AND lote_id=?",
                    Long.class, receipt.userId(), receipt.tallerId(), receipt.lotId())).isOne();
            Set<String> xids = new HashSet<>();
            for (String table : LegalRegistrationWriterITSupport.TABLES)
                xids.addAll(fixture.owner.queryForList("SELECT DISTINCT xmin::text FROM public." + table, String.class));
            assertThat(xids).hasSize(1);
            var encrypted = LegalAcceptanceMetadataITSupport.fields(fixture.owner, receipt.lotId());
            assertThat(encrypted).hasSize(2);
            assertThat(LegalAcceptanceMetadataITSupport.decrypt(receipt.lotId(), encrypted.getFirst())).isEqualTo(metadata().ipAddress());
        }
    }

    @Test void optionalOmissionAndThirtyFourActsPassTheRealReaderAndBoundedWriter() throws Exception {
        fixture.reset(directory.resolve("many"), getClass(), 34);
        var required = fixture.request("required@test.invalid", false);
        try (var h = fixture.harness()) { assertThat(h.register(required, key()).acceptanceIds()).hasSize(1); }
        var complete = fixture.request("complete@test.invalid", true);
        try (var h = fixture.harness()) {
            var receipt = h.register(complete, key());
            assertThat(receipt.acceptanceIds()).hasSize(34);
            assertThat(h.probe().inserts("legal_aceptaciones")).isEqualTo(2);
            assertThat(h.probe().inserts("legal_aceptacion_documentos")).isEqualTo(2);
            assertThat(h.probe().commits).hasValue(1);
        }
    }

    @Test void malformedShapeIsRejectedBeforePoolAndBcrypt() throws Exception {
        var request = fixture.request(); var before = fixture.rows();
        try (var h = fixture.harness()) {
            var failure = failure(catchThrowable(() -> h.service().register(request.command().registration(), "not-a-key",
                    request.command().requiredSetRevision(), request.command().acceptances(), metadata())), LegalRegistrationFailure.Reason.INVALID_PAYLOAD);
            assertThat(failure.completion()).isEqualTo(LegalRegistrationFailure.Completion.NONE);
            assertThat(h.probe().borrows).hasValue(0); assertThat(h.probe().hashes).hasValue(0);
        }
        assertThat(fixture.rows()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"empty", "requirement-duplicate", "document-duplicate", "confirmation"})
    void staleRevisionHasPriorityOverSemanticDefects(String defect) throws Exception {
        var original = fixture.request(); var request = withAcceptances(original, STALE, defective(original, defect));
        var before = fixture.rows();
        try (var h = fixture.harness()) {
            var failure = rolledBack(catchThrowable(() -> h.register(request, key())), LegalRegistrationFailure.Reason.STALE);
            assertThat(failure.validation()).isPresent();
            assertThat(failure.validation().orElseThrow().currentRequirements().requiredSetRevision())
                    .isEqualTo(original.command().requiredSetRevision());
            assertThat(h.probe().hashes).hasValue(0); assertNoBusiness(h);
        }
        assertThat(fixture.rows()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"empty", "requirement-duplicate", "document-duplicate", "confirmation", "digest", "act", "missing-document", "unknown"})
    void semanticFailuresRollBackTheMaterializedAggregateWithoutAccountOrLedger(String defect) throws Exception {
        var original = fixture.request(); var request = withAcceptances(original, original.command().requiredSetRevision(), defective(original, defect));
        var before = fixture.rows();
        try (var h = fixture.harness()) {
            var failure = rolledBack(catchThrowable(() -> h.register(request, key())), LegalRegistrationFailure.Reason.INVALID);
            assertThat(failure.validation()).isPresent(); assertThat(failure.validation().orElseThrow().motivos()).isNotEmpty();
            assertThat(h.probe().hashes).hasValue(0); assertNoBusiness(h);
        }
        assertThat(fixture.rows()).isEqualTo(before);
    }

    @Test void unavailableCatalogPrecedesStaleAndInvalidSemantics() throws Exception {
        var original = fixture.request(); fixture.retireRegistration();
        var request = withAcceptances(original, STALE, List.of()); var before = fixture.rows();
        try (var h = fixture.harness()) {
            var failure = rolledBack(catchThrowable(() -> h.register(request, key())), LegalRegistrationFailure.Reason.UNAVAILABLE);
            assertThat(failure.validation()).isEmpty(); assertThat(h.probe().hashes).hasValue(0); assertNoBusiness(h);
        }
        assertThat(fixture.rows()).isEqualTo(before);
    }

    @Test void replayBypassesBcryptCurrentCatalogAndAnExclusiveEditorialGate() throws Exception {
        var request = fixture.request(); String key = key(); LegalRegistrationReceipt original;
        try (var h = fixture.harness()) { original = h.register(request, key); }
        fixture.replaceRegistration(); fixture.retireRegistration(); var before = fixture.rows();
        try (Connection lock = LegalAcceptanceProtocolFeasibilityITSupport.transaction(fixture.source);
             var h = fixture.harness()) {
            exclusiveEditorial(LegalAcceptanceProtocolFeasibilityITSupport.jdbc(lock));
            var replay = h.register(request, key, LegalRequestMetadata.of(LegalRequestMetadata.parseIpLiteral("198.51.100.21"), "changed transport"));
            sameIdentity(replay, original); assertThat(replay.replay()).isTrue();
            assertNoDml(h); assertThat(h.probe().hashes).hasValue(0);
            assertThat(h.probe().sql).noneMatch(sql -> sql.contains("legal_requisito_conjuntos_actuales")
                    && !sql.contains("pg_catalog") && !sql.contains("information_schema"));
            assertThat(h.probe().sql).noneMatch(sql -> sql.startsWith("select public.legal_exigir_lock_editorial_v28("));
        }
        assertThat(fixture.rows()).isEqualTo(before);
    }

    @Test void changedPayloadCannotReuseTheKeyEvenWhenCurrentCatalogIsUnavailable() throws Exception {
        var request = fixture.request(); String key = key();
        try (var h = fixture.harness()) { h.register(request, key); }
        fixture.retireRegistration(); var before = fixture.rows();
        try (var h = fixture.harness()) {
            rolledBack(catchThrowable(() -> h.register(withPassword(request, "DifferentPassword123"), key)), LegalRegistrationFailure.Reason.KEY_REUSED);
            assertNoDml(h); assertThat(h.probe().hashes).hasValue(0);
        }
        assertThat(fixture.rows()).isEqualTo(before);
    }

    @Test void replayKeepsDurableIdsAfterEmailPasswordAndTokenChangesAndOldEmailReuse() throws Exception {
        var request = fixture.request(); String key = key(); LegalRegistrationReceipt original;
        try (var h = fixture.harness()) { original = h.register(request, key); }
        fixture.owner.update("UPDATE users SET email=?,password=?,token_version=9 WHERE id=?", "changed@test.invalid",
                new BCryptPasswordEncoder().encode("new-current-password"), original.userId());
        long otherWorkshop = fixture.owner.queryForObject("INSERT INTO talleres(nombre) VALUES('Another owner') RETURNING id", Long.class);
        long otherUser = fixture.owner.queryForObject("""
                INSERT INTO users(username,password,email,role,taller_id) VALUES('other','fixture',?,'ADMIN',?) RETURNING id
                """, Long.class, request.command().registration().email(), otherWorkshop);
        var before = fixture.rows();
        try (var h = fixture.harness()) {
            var replay = h.register(request, key); sameIdentity(replay, original);
            assertThat(replay.userId()).isNotEqualTo(otherUser); assertNoDml(h); assertThat(h.probe().hashes).hasValue(0);
        }
        assertThat(fixture.rows()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"user", "workshop"})
    void replayRejectsDisabledAccountStateWithoutTouchingEvidence(String disabled) throws Exception {
        var request = fixture.request(); String key = key(); LegalRegistrationReceipt original;
        try (var h = fixture.harness()) { original = h.register(request, key); }
        if (disabled.equals("user")) fixture.owner.update("UPDATE users SET active=false WHERE id=?", original.userId());
        else fixture.owner.update("UPDATE talleres SET activo=false WHERE id=?", original.tallerId());
        var before = fixture.rows();
        try (var h = fixture.harness()) {
            rolledBack(catchThrowable(() -> h.register(request, key)), LegalRegistrationFailure.Reason.INVALID_ACTOR);
            assertNoDml(h); assertThat(h.probe().hashes).hasValue(0);
        }
        assertThat(fixture.rows()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"statement", "document"})
    void corruptCanonicalSourcesFailClosedBeforeAccountCreation(String corrupt) throws Exception {
        var request = fixture.request();
        fixture.corrupt(() -> {
            if (corrupt.equals("statement")) fixture.owner.update("UPDATE legal_requisito_versiones SET afirmacion='Corrupt fixture' WHERE id=?",
                    request.command().acceptances().getFirst().requisitoVersionId());
            else fixture.owner.update("UPDATE legal_documento_versiones SET contenido_markdown='Corrupt fixture' WHERE id=?",
                    request.command().acceptances().getFirst().documentos().getFirst().documentoVersionId());
        });
        var before = fixture.rows();
        try (var h = fixture.harness()) {
            rolledBack(catchThrowable(() -> h.register(request, key())), LegalRegistrationFailure.Reason.UNAVAILABLE);
            assertNoBusiness(h); assertThat(h.probe().hashes).hasValue(0);
        }
        assertThat(fixture.rows()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"users", "legal_aceptacion_metadatos", "legal_idempotencia_resultados"})
    void sqlFailureInServiceWritePhasesRollsBackAccountEvidenceAndResult(String table) throws Exception {
        var request = fixture.request(); var before = fixture.rows(); var probe = new Probe(); probe.failAfterInsert = table;
        try (var h = fixture.harness(probe)) {
            rolledBack(catchThrowable(() -> h.register(request, key())), LegalRegistrationFailure.Reason.UNAVAILABLE);
            assertThat(probe.injected).isTrue(); assertThat(probe.commits).hasValue(0); assertThat(probe.rollbacks).hasValue(1);
        }
        assertThat(fixture.rows()).isEqualTo(before);
    }

    @Test void serviceForcesDeferredMetadataConstraintsBeforeCommit() throws Exception {
        var request = fixture.request(); var before = fixture.rows(); var probe = new Probe(); probe.omitEncrypted = true;
        try (var h = fixture.harness(probe)) {
            rolledBack(catchThrowable(() -> h.register(request, key())), LegalRegistrationFailure.Reason.UNAVAILABLE);
            assertThat(probe.injected).isTrue(); assertThat(probe.commits).hasValue(0);
        }
        assertThat(fixture.rows()).isEqualTo(before);
    }

    @Test void sameKeyConcurrentRequestsReturnOneAccountAndOneDurableReplay() throws Exception { sameKeyRace(false); }
    @Test void sameKeyConcurrentDifferentPasswordsFailWithoutAnotherAccount() throws Exception { sameKeyRace(true); }

    @Test void aKeyHeldByAnotherLiveRequestReturnsInProgressWithoutCreatingAnotherAccount() throws Exception {
        var request = fixture.request(); String key = key(); var arrived = new CountDownLatch(1); var release = new CountDownLatch(1);
        var firstProbe = new Probe(); var secondProbe = new Probe(); var held = new AtomicBoolean();
        firstProbe.afterSql = sql -> { if (isInsert(sql, "legal_idempotencia_resultados") && held.compareAndSet(false, true)) { arrived.countDown(); await(release); } };
        try (var first = fixture.harness(firstProbe); var second = fixture.harness(secondProbe);
             var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var firstResult = workers.submit(() -> first.register(request, key));
            try {
                await(arrived);
                var secondResult = workers.submit(() -> catchThrowable(() -> second.register(request, key)));
                awaitObserved(() -> waitingOn(secondProbe.pid.get(), "advisory", "ExclusiveLock"));
                rolledBack(secondResult.get(8, TimeUnit.SECONDS), LegalRegistrationFailure.Reason.IN_PROGRESS);
                assertThat(firstResult.isDone()).isFalse(); assertNoDml(second); assertThat(secondProbe.hashes).hasValue(0);
                release.countDown(); firstResult.get(10, TimeUnit.SECONDS);
                assertThat(fixture.owner.queryForObject("SELECT count(*) FROM users", Long.class)).isOne();
            } finally { release.countDown(); }
        }
    }

    private void sameKeyRace(boolean differentPassword) throws Exception {
        var request = fixture.request(); String key = key(); var arrived = new CountDownLatch(1); var release = new CountDownLatch(1);
        var firstProbe = new Probe(); var held = new AtomicBoolean();
        firstProbe.afterSql = sql -> { if (isInsert(sql, "legal_idempotencia_resultados") && held.compareAndSet(false, true)) { arrived.countDown(); await(release); } };
        var secondProbe = new Probe();
        try (var first = fixture.harness(firstProbe); var second = fixture.harness(secondProbe);
             var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<LegalRegistrationReceipt> firstResult = workers.submit(() -> first.register(request, key));
            try {
                await(arrived);
                Future<Object> secondResult = workers.submit(() -> {
                    try { return second.register(differentPassword ? withPassword(request, "different-password-123") : request, key); }
                    catch (LegalRegistrationFailure failure) { return failure; }
                });
                awaitObserved(() -> waitingOn(secondProbe.pid.get(), "advisory", "ExclusiveLock"));
                assertThat(secondProbe.inserts("users")).isZero(); release.countDown();
                var original = firstResult.get(10, TimeUnit.SECONDS); Object result = secondResult.get(10, TimeUnit.SECONDS);
                if (differentPassword) rolledBack((Throwable) result, LegalRegistrationFailure.Reason.KEY_REUSED);
                else { assertThat(result).isInstanceOf(LegalRegistrationReceipt.class); var replay = (LegalRegistrationReceipt) result;
                    sameIdentity(replay, original); assertThat(replay.replay()).isTrue(); }
                assertNoDml(second); assertThat(secondProbe.hashes).hasValue(0);
                assertThat(fixture.owner.queryForObject("SELECT count(*) FROM users", Long.class)).isOne();
                assertThat(fixture.owner.queryForObject("SELECT count(*) FROM legal_idempotencia_resultados", Long.class)).isOne();
            } finally { release.countDown(); }
        }
    }

    @Test void differentKeysWithTheSameEmailWaitOnTheUserConstraintAndLeaveNoOrphans() throws Exception {
        var request = fixture.request("race@test.invalid", true); fixture.materializeCommittedAggregate();
        var arrived = new CountDownLatch(1); var release = new CountDownLatch(1); var once = new AtomicBoolean();
        var firstProbe = new Probe(); var secondProbe = new Probe();
        firstProbe.afterSql = sql -> { if (isInsert(sql, "users") && once.compareAndSet(false, true)) { arrived.countDown(); await(release); } };
        try (var first = fixture.harness(firstProbe); var second = fixture.harness(secondProbe);
             var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var firstResult = workers.submit(() -> first.register(request, key()));
            try {
                await(arrived);
                var secondResult = workers.submit(() -> catchThrowable(() -> second.register(request, key())));
                awaitObserved(() -> secondProbe.inserts("users") == 1 && waitingOn(secondProbe.pid.get(), "transactionid", "ShareLock")
                        && Boolean.TRUE.equals(fixture.owner.queryForObject("SELECT ?=ANY(pg_blocking_pids(?))", Boolean.class,
                        firstProbe.pid.get(), secondProbe.pid.get())));
                release.countDown(); firstResult.get(10, TimeUnit.SECONDS);
                var rejected = rolledBack(secondResult.get(10, TimeUnit.SECONDS), LegalRegistrationFailure.Reason.UNAVAILABLE);
                assertThat(sqlStates(rejected)).contains("23505").doesNotContain("55P03");
                for (String table : List.of("users", "talleres", "suscripciones", "legal_aceptacion_lotes", "legal_idempotencia_resultados"))
                    assertThat(fixture.owner.queryForObject("SELECT count(*) FROM public." + table, Long.class)).as(table).isOne();
            } finally { release.countDown(); }
        }
    }

    private static boolean waitingOn(int pid, String type, String mode) {
        return pid > 0 && Boolean.TRUE.equals(fixture.owner.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM pg_locks WHERE pid=? AND locktype=? AND mode=? AND NOT granted)
                """, Boolean.class, pid, type, mode));
    }

    private static Set<String> sqlStates(Throwable failure) {
        Set<String> states = new HashSet<>();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cursor = failure; cursor != null && visited.add(cursor); cursor = cursor.getCause()) {
            if (cursor instanceof java.sql.SQLException sql && sql.getSQLState() != null) states.add(sql.getSQLState());
        }
        return states;
    }

    static LegalRegistrationFailure failure(Throwable thrown, LegalRegistrationFailure.Reason reason) {
        assertThat(thrown).isInstanceOf(LegalRegistrationFailure.class);
        var failure = (LegalRegistrationFailure) thrown; assertThat(failure.reason()).isEqualTo(reason); return failure;
    }
    static LegalRegistrationFailure rolledBack(Throwable thrown, LegalRegistrationFailure.Reason reason) {
        var failure = failure(thrown, reason);
        assertThat(failure.completion()).isEqualTo(LegalRegistrationFailure.Completion.ROLLED_BACK);
        assertThat(failure.persistence()).isEqualTo(LegalRegistrationFailure.Persistence.NOT_PERSISTED);
        assertThat(failure.confirmedReceipt()).isEmpty(); return failure;
    }
    static void sameIdentity(LegalRegistrationReceipt replay, LegalRegistrationReceipt original) {
        assertThat(replay.userId()).isEqualTo(original.userId()); assertThat(replay.tallerId()).isEqualTo(original.tallerId());
        assertThat(replay.lotId()).isEqualTo(original.lotId()); assertThat(replay.acceptanceIds()).isEqualTo(original.acceptanceIds());
    }
    static void assertNoDml(Harness harness) {
        assertThat(harness.metrics().snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isZero();
    }
    private static void assertNoBusiness(Harness harness) {
        for (String table : List.of("users", "talleres", "suscripciones", "legal_aceptacion_lotes", "legal_idempotencia_resultados"))
            assertThat(harness.probe().inserts(table)).as(table).isZero();
    }
    private static List<Acceptance> defective(Request request, String defect) {
        var original = request.command().acceptances().getFirst(); var documents = original.documentos();
        if (defect.equals("empty")) return List.of();
        if (defect.equals("requirement-duplicate")) return List.of(original, original);
        if (defect.equals("document-duplicate")) {
            var duplicate = new ArrayList<>(documents); duplicate.add(documents.getFirst()); documents = duplicate;
        } else if (defect.equals("missing-document")) documents = List.of();
        return List.of(new Acceptance(defect.equals("unknown") ? UUID.randomUUID() : original.requisitoVersionId(),
                defect.equals("act") ? (original.tipoActo() == TipoActoLegal.ACEPTACION ? TipoActoLegal.LECTURA : TipoActoLegal.ACEPTACION) : original.tipoActo(),
                defect.equals("digest") ? "f".repeat(64) : original.afirmacionSha256(), documents, !defect.equals("confirmation")));
    }
}
