package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommandValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalRegistrationWriterITSupport.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.*;
import java.util.*;

import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalRegistrationWriterITSupport.*;
import static org.assertj.core.api.Assertions.*;

/** Whole writer on PostgreSQL16: real crypto, account, canonical evidence and durable result. */
class LegalRegistrationWriterIT {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_registration_writer").withUsername("ordenfix").withPassword("ordenfix");
    private static LegalRegistrationWriterITSupport fixture;
    @TempDir Path directory;

    @BeforeAll static void start() { POSTGRES.start(); fixture = new LegalRegistrationWriterITSupport(POSTGRES); }
    @AfterAll static void stop() { POSTGRES.stop(); }
    @BeforeEach void reset() throws Exception { fixture.reset(directory, getClass(), 1); }

    @Test void oneRestrictedTransactionCreatesTheAccountCanonicalEvidenceMetadataAndLedger() throws Exception {
        var request = fixture.request(registration("Owner.Case@Example.INVALID"), true);
        var state = new LegalTransactionCompletionState<LegalRegistrationReceipt>();
        LegalRegistrationReceipt receipt;
        try (var harness = fixture.harness()) {
            receipt = harness.write(request, key(), state);
            assertThat(harness.probe().borrows).isEqualTo(1);
            assertThat(harness.probe().commits).isEqualTo(1);
            assertThat(harness.probe().rollbacks).isZero();
            assertThat(harness.probe().businessInserts()).isEqualTo(3);
        }
        assertThat(state.snapshot().completion()).isEqualTo(LegalTransactionCompletionState.Completion.COMMITTED);
        assertThat(state.snapshot().receipt()).contains(receipt);
        assertThat(receipt.replay()).isFalse();
        assertThat(receipt.acceptanceIds()).hasSize(request.command().acceptances().size());
        assertThat(receipt.toString()).doesNotContain(request.command().registration().email(), request.command().registration().password());
        assertAccount(receipt, request.command().registration());
        assertCanonicalEvidence(receipt, request.command());
        Set<String> xids = new HashSet<>();
        for (String table : TABLES) xids.addAll(fixture.owner.queryForList("SELECT DISTINCT xmin::text FROM public." + table, String.class));
        assertThat(xids).hasSize(1);
        assertThat(fixture.owner.queryForObject("SELECT count(*) FROM auth_tokens", Long.class)).isZero();
        assertThat(fixture.owner.queryForObject("SELECT count(*) FROM legal_idempotencia_sin_actos", Long.class)).isZero();

        var header = LegalAcceptanceMetadataITSupport.header(fixture.owner, receipt.lotId());
        var accepted = fixture.owner.queryForObject("SELECT aceptado_en FROM legal_aceptacion_lotes WHERE id=?", OffsetDateTime.class, receipt.lotId());
        assertThat(header.capturedAt()).isEqualTo(accepted.toInstant());
        assertThat(header.retainUntil()).isEqualTo(header.capturedAt().plus(RETENTION));
        var fields = LegalAcceptanceMetadataITSupport.fields(fixture.owner, receipt.lotId());
        assertThat(fields).extracting(LegalAcceptanceMetadataITSupport.CipherRow::type).containsExactly("IP", "USER_AGENT");
        assertThat(LegalAcceptanceMetadataITSupport.decrypt(receipt.lotId(), fields.getFirst())).isEqualTo(metadata().ipAddress());
        assertThat(LegalAcceptanceMetadataITSupport.decrypt(receipt.lotId(), fields.getLast())).isEqualTo(metadata().userAgent());
    }

    @Test void optionalRequirementsCanBeOmittedAndThirtyFourActsUseBoundedBatches() throws Exception {
        fixture.reset(directory.resolve("many"), getClass(), 34);
        var requiredOnly = fixture.request(registration("required-only@test.invalid"), false);
        try (var h = fixture.harness()) {
            var result = h.write(requiredOnly, key(), new LegalTransactionCompletionState<>());
            assertThat(result.acceptanceIds()).hasSize(1);
            assertCanonicalEvidence(result, requiredOnly.command());
        }
        var all = fixture.request(registration("all-optional@test.invalid"), true);
        try (var h = fixture.harness()) {
            var result = h.write(all, key(), new LegalTransactionCompletionState<>());
            assertThat(result.acceptanceIds()).hasSize(34);
            assertThat(h.probe().executed.stream().filter(sql -> isInsert(sql, "legal_aceptaciones"))).hasSize(2);
            assertCanonicalEvidence(result, all.command());
            assertThat(h.probe().commits).isEqualTo(1);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"talleres", "suscripciones", "users", "legal_requisito_agregados",
            "legal_requisito_agregado_scopes", "legal_aceptacion_lotes", "legal_aceptaciones", "legal_aceptacion_documentos",
            "legal_aceptacion_metadatos", "legal_aceptacion_metadatos_cifrados", "legal_idempotencia_resultados"})
    void failureAfterEachPhysicalInsertRollsBackEveryNewRow(String table) throws Exception {
        var request = fixture.request(); var before = fixture.rows(); var probe = new Probe(); probe.afterInsert = table;
        var state = new LegalTransactionCompletionState<LegalRegistrationReceipt>();
        try (var h = fixture.harness(probe, null)) {
            Throwable failure = catchThrowable(() -> h.write(request, key(), state));
            assertSqlState(failure, "22012");
            assertThat(probe.injected).isTrue();
            assertThat(probe.commits).isZero(); assertThat(probe.rollbacks).isEqualTo(1); assertThat(probe.borrows).isEqualTo(1);
        }
        assertRolledBack(state); assertThat(fixture.rows()).isEqualTo(before);
    }

    @Test void duplicateEmailRollsBackNewWorkshopSubscriptionAndEvidenceWithoutChangingTheFirstAccount() throws Exception {
        var request = fixture.request(registration("duplicate@test.invalid"), true);
        try (var h = fixture.harness()) { h.write(request, key(), new LegalTransactionCompletionState<>()); }
        var before = fixture.rows(); var state = new LegalTransactionCompletionState<LegalRegistrationReceipt>();
        try (var h = fixture.harness()) {
            assertSqlState(catchThrowable(() -> h.write(request, key(), state)), "23505");
            assertThat(h.probe().businessInserts()).isEqualTo(3);
        }
        assertRolledBack(state); assertThat(fixture.rows()).isEqualTo(before);
    }

    @Test void anEmailBeyondWorkshopStorageCapacityIsNeverTruncatedOrNormalized() throws Exception {
        String email = "a".repeat(60) + "@" + "b".repeat(52) + ".invalid";
        assertThat(email).hasSize(121);
        var request = fixture.request(registration(email), true);
        var before = fixture.rows(); var state = new LegalTransactionCompletionState<LegalRegistrationReceipt>();
        try (var h = fixture.harness()) { assertSqlState(catchThrowable(() -> h.write(request, key(), state)), "22001"); }
        assertRolledBack(state); assertThat(fixture.rows()).isEqualTo(before);
    }

    @Test void physicalTextBoundariesAndUnicodePasswordPreserveTheOriginalInputs() throws Exception {
        var input = new LegalAcceptanceCommand.Registration("Ñ".repeat(120), "1".repeat(20), "Á".repeat(50),
                "b".repeat(59) + "@" + "d".repeat(52) + ".invalid", "ñ".repeat(20));
        var request = fixture.request(input, true);
        try (var h = fixture.harness()) {
            var result = h.write(request, key(), new LegalTransactionCompletionState<>());
            assertAccount(result, input);
        }
    }

    @Test void encryptionFailurePrecedesAllBusinessInserts() throws Exception {
        var request = fixture.request(); var before = fixture.rows(); var probe = new Probe();
        var codec = new LegalAcceptanceMetadataCodec(Map.of(7, AES), 7, new SecureRandom() {
            @Override public void nextBytes(byte[] bytes) { Arrays.fill(bytes, (byte) 0); }
        });
        var state = new LegalTransactionCompletionState<LegalRegistrationReceipt>();
        try (var h = fixture.harness(probe, codec)) {
            assertThatThrownBy(() -> h.write(request, key(), state)).isInstanceOf(LegalIdempotencyException.class);
            assertThat(probe.businessInserts()).isZero();
        }
        assertRolledBack(state); assertThat(fixture.rows()).isEqualTo(before);
    }

    @Test void aNonceReusedAcrossLotsRollsBackTheSecondAccountAndPreservesConfirmedCiphertext() throws Exception {
        var codec = new LegalAcceptanceMetadataCodec(Map.of(7, AES), 7, new SecureRandom() {
            private int index;
            @Override public void nextBytes(byte[] bytes) { Arrays.fill(bytes, (byte) (index++ % 2)); }
        });
        var first = fixture.request();
        try (var h = fixture.harness(new Probe(), codec)) { h.write(first, key(), new LegalTransactionCompletionState<>()); }
        var second = fixture.request(); var before = fixture.rows(); var state = new LegalTransactionCompletionState<LegalRegistrationReceipt>();
        try (var h = fixture.harness(new Probe(), codec)) {
            assertSqlState(catchThrowable(() -> h.write(second, key(), state)), "23505");
        }
        assertRolledBack(state); assertThat(fixture.rows()).isEqualTo(before);
    }

    @Test void aMissingEncryptedFieldCannotEscapeTheDeferredGraphChecks() throws Exception {
        var request = fixture.request(); var before = fixture.rows(); var probe = new Probe(); probe.omitEncrypted = true;
        var state = new LegalTransactionCompletionState<LegalRegistrationReceipt>();
        try (var h = fixture.harness(probe, null)) {
            assertSqlState(catchThrowable(() -> h.write(request, key(), state)), "23514");
            assertThat(probe.injected).isTrue();
            assertThat(probe.commits).isZero();
        }
        assertRolledBack(state); assertThat(fixture.rows()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"id", "revision", "fingerprint", "createdAt"})
    void contradictoryAggregateReceiptsFailBeforeBusinessWrites(String field) throws Exception {
        var request = fixture.request(); var before = fixture.rows(); var state = new LegalTransactionCompletionState<LegalRegistrationReceipt>();
        try (var h = fixture.harness()) {
            assertThatThrownBy(() -> h.write(request, key(), state, r -> new LegalRequiredSetAggregateReceipt(r.outcome(),
                    field.equals("id") ? UUID.randomUUID() : r.aggregateId(),
                    field.equals("revision") ? "sha256:" + "f".repeat(64) : r.requiredSetRevision(),
                    field.equals("fingerprint") ? "sha256:" + "f".repeat(64) : r.provenanceFingerprint(),
                    r.provenance(), field.equals("createdAt") ? r.createdAt().minusSeconds(1) : r.createdAt())))
                    .isInstanceOf(LegalIdempotencyException.class);
            assertThat(h.probe().businessInserts()).isZero();
        }
        assertRolledBack(state); assertThat(fixture.rows()).isEqualTo(before);
    }

    @Test void aRealReplayReservationCannotCreateAnotherAccountThroughTheWriter() throws Exception {
        var request = fixture.request(); String key = key(); LegalRegistrationReceipt original;
        var recorded = new java.util.concurrent.atomic.AtomicReference<LegalRequiredSetAggregateReceipt>();
        try (var h = fixture.harness()) {
            original = h.write(request, key, new LegalTransactionCompletionState<>(), r -> { recorded.set(r); return r; });
        }
        var r = recorded.get();
        var aggregate = new LegalRequiredSetAggregateReceipt(LegalRequiredSetAggregateReceipt.Outcome.REUSED,
                r.aggregateId(), r.requiredSetRevision(), r.provenanceFingerprint(), r.provenance(), r.createdAt());
        var before = fixture.rows(); var state = new LegalTransactionCompletionState<LegalRegistrationReceipt>();
        try (var h = fixture.harness()) {
            assertThatThrownBy(() -> h.boundary().execute(state, (status, deadline) -> {
                var reservation = h.coordinator().reserve(request.command(), key, deadline::remainingMillis);
                var replay = reservation.replay().orElseThrow();
                assertThat(replay.userId()).isEqualTo(original.userId());
                assertThat(replay.tallerId()).isEqualTo(original.tallerId());
                assertThat(replay.lotId()).isEqualTo(original.lotId());
                var prepared = new LegalRegistrationPreparation(new BCryptPasswordEncoder(), APPLICATION_CLOCK, () -> AUDIT_AT, 14)
                        .prepare(request.command(), deadline);
                var selected = new LegalRegistrationSelection().select(request.command(), request.current());
                return h.writer().write(reservation, prepared, aggregate, selected, metadata());
            })).isInstanceOf(LegalIdempotencyException.class).hasNoCause();
            assertThat(h.probe().executed).noneMatch(sql -> sql.startsWith("insert "));
            assertThat(h.probe().executed).noneMatch(sql -> sql.startsWith("select public.legal_exigir_lock_editorial_v28("));
        }
        assertRolledBack(state); assertThat(fixture.rows()).isEqualTo(before);
    }

    @Test void anotherPayloadCannotReuseTheDurableRegistrationKey() throws Exception {
        var request = fixture.request(); String key = key();
        try (var h = fixture.harness()) { h.write(request, key, new LegalTransactionCompletionState<>()); }
        var r = request.command().registration();
        var changed = LegalAcceptanceCommandValidator.registration(new LegalAcceptanceCommand.Registration(
                r.nombreTaller(), r.telefonoTaller(), r.nombreAdmin(), r.email(), "changed-password"),
                request.command().requiredSetRevision(), request.command().acceptances());
        var before = fixture.rows(); var state = new LegalTransactionCompletionState<LegalRegistrationReceipt>();
        try (var h = fixture.harness()) {
            assertThatThrownBy(() -> h.write(new Request(changed, request.current()), key, state))
                    .isInstanceOfSatisfying(LegalIdempotencyException.class,
                            failure -> assertThat(failure.reason()).isEqualTo(LegalIdempotencyException.Reason.KEY_REUSED));
            assertThat(h.probe().executed).noneMatch(sql -> sql.startsWith("insert "));
        }
        assertRolledBack(state); assertThat(fixture.rows()).isEqualTo(before);
    }

    private static void assertAccount(LegalRegistrationReceipt receipt, LegalAcceptanceCommand.Registration input) {
        var account = fixture.owner.queryForMap("SELECT username,email,password,role,active,email_verificado,token_version,taller_id FROM users WHERE id=?", receipt.userId());
        assertThat(account).containsEntry("username", input.nombreAdmin()).containsEntry("email", input.email())
                .containsEntry("role", "ADMIN").containsEntry("active", true).containsEntry("email_verificado", false)
                .containsEntry("token_version", 0L).containsEntry("taller_id", receipt.tallerId());
        assertThat(new BCryptPasswordEncoder().matches(input.password(), (String) account.get("password"))).isTrue();
        assertThat(account.get("password").toString()).startsWith("$2a$10$");
        assertThat(fixture.owner.queryForObject("SELECT count(*) FROM users WHERE taller_id=? AND role='ADMIN'", Long.class, receipt.tallerId())).isEqualTo(1);
        assertThat(fixture.owner.queryForObject("""
                SELECT t.nombre=? AND t.email_contacto=? AND t.telefono=? AND t.activo AND t.secuencia_orden=0
                   AND t.mostrar_en_resumen AND t.anio_secuencia_orden IS NULL AND t.alias_cobro IS NULL
                   AND t.titular_cobro IS NULL AND t.entidad_cobro IS NULL
                   AND t.created_at=? AND t.updated_at=? AND s.created_at=? AND s.updated_at=?
                   AND s.plan='FREE' AND s.estado='TRIAL' AND s.fecha_inicio=? AND s.fecha_fin_trial=?
                   AND s.reparaciones_mes=0 AND s.consumo_mes IS NULL AND s.mp_preapproval_id IS NULL
                   AND s.mp_payer_id IS NULL AND s.proximo_cobro IS NULL AND s.mp_status IS NULL
                   AND s.mp_external_reference IS NULL AND s.mp_checkout_init_point IS NULL
                   AND s.mp_next_payment_at IS NULL AND s.mp_last_payment_at IS NULL AND s.mp_last_authorized_payment_id IS NULL
                  FROM talleres t JOIN suscripciones s ON s.taller_id=t.id WHERE t.id=?
                """, Boolean.class, input.nombreTaller(), input.email(), input.telefonoTaller(), AUDIT_AT, AUDIT_AT, AUDIT_AT, AUDIT_AT,
                LocalDate.now(APPLICATION_CLOCK), LocalDate.now(APPLICATION_CLOCK).plusDays(14), receipt.tallerId())).isTrue();
    }

    private static void assertCanonicalEvidence(LegalRegistrationReceipt receipt, LegalAcceptanceCommand command) {
        assertThat(fixture.owner.queryForList("SELECT requisito_version_id FROM legal_aceptaciones WHERE lote_id=?", UUID.class, receipt.lotId()))
                .containsExactlyInAnyOrderElementsOf(command.acceptances().stream().map(LegalAcceptanceCommand.Acceptance::requisitoVersionId).toList());
        for (var acceptance : command.acceptances()) {
            assertThat(fixture.owner.queryForList("""
                    SELECT d.documento_version_id FROM legal_aceptacion_documentos d
                      JOIN legal_aceptaciones a ON a.id=d.aceptacion_id
                     WHERE a.lote_id=? AND a.requisito_version_id=?
                    """, UUID.class, receipt.lotId(), acceptance.requisitoVersionId()))
                    .containsExactlyInAnyOrderElementsOf(acceptance.documentos().stream()
                            .map(LegalAcceptanceCommand.Document::documentoVersionId).toList());
        }
        assertThat(fixture.owner.queryForList("SELECT id FROM legal_aceptaciones WHERE lote_id=? ORDER BY id", UUID.class, receipt.lotId()))
                .containsExactlyElementsOf(receipt.acceptanceIds());
        assertThat(fixture.owner.queryForObject("""
                SELECT user_id=? AND taller_id=? AND rol_wire='ADMIN' AND audiencia='ADMIN_TITULAR'
                   AND perfil='REGISTRATION' AND revision_scheme='AGGREGATE_V1' AND required_set_revision=?
                  FROM legal_aceptacion_lotes WHERE id=?
                """, Boolean.class, receipt.userId(), receipt.tallerId(), command.requiredSetRevision(), receipt.lotId())).isTrue();
        assertThat(fixture.owner.queryForObject("""
                SELECT bool_and(a.user_id=? AND a.taller_id=? AND a.contexto=l.contexto AND a.tipo_acto=l.tipo_acto
                  AND a.requisito_clave=l.clave AND a.requisito_version=v.version AND a.afirmacion=v.afirmacion
                  AND a.afirmacion_sha256=v.afirmacion_sha256 AND a.requerido=v.requerido)
                  FROM legal_aceptaciones a JOIN legal_requisito_versiones v ON v.id=a.requisito_version_id
                  JOIN legal_requisito_lineas l ON l.id=v.requisito_linea_id WHERE a.lote_id=?
                """, Boolean.class, receipt.userId(), receipt.tallerId(), receipt.lotId())).isTrue();
        assertThat(fixture.owner.queryForObject("""
                SELECT bool_and(d.documento_clave=l.clave AND d.tipo=l.tipo AND d.version=v.version AND d.titulo=v.titulo
                   AND d.sha256=v.sha256 AND d.documento_ordinal=ref.documento_ordinal)
                  FROM legal_aceptacion_documentos d JOIN legal_aceptaciones a ON a.id=d.aceptacion_id
                  JOIN legal_documento_versiones v ON v.id=d.documento_version_id
                  JOIN legal_documento_lineas l ON l.id=v.documento_linea_id
                  JOIN legal_requisito_documentos ref ON ref.requisito_version_id=a.requisito_version_id
                   AND ref.documento_version_id=d.documento_version_id WHERE a.lote_id=?
                """, Boolean.class, receipt.lotId())).isTrue();
        assertThat(fixture.owner.queryForObject("""
                SELECT operacion='REGISTRO' AND route_template='/api/auth/register' AND user_id=? AND taller_id=?
                  FROM legal_idempotencia_resultados WHERE lote_id=?
                """, Boolean.class, receipt.userId(), receipt.tallerId(), receipt.lotId())).isTrue();
    }

    private static String key() { return UUID.randomUUID().toString(); }
    private static void assertRolledBack(LegalTransactionCompletionState<?> state) {
        assertThat(state.snapshot().completion()).isEqualTo(LegalTransactionCompletionState.Completion.ROLLED_BACK);
        assertThat(state.snapshot().receipt()).isEmpty();
    }
    private static void assertSqlState(Throwable failure, String state) {
        assertThat(failure).isNotNull();
        Throwable current = failure;
        while (current != null && !(current instanceof SQLException)) current = current.getCause();
        assertThat(current).isInstanceOf(SQLException.class);
        assertThat(((SQLException) current).getSQLState()).isEqualTo(state);
    }
}
