package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Path;
import java.util.UUID;

import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalRegistrationHttpITSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Replay crosses the real ledger, current JPA policy and JWT signing; servlet transport is mocked. */
class LegalRegistrationReplayIT {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_registration_http_replay").withUsername("ordenfix").withPassword("ordenfix");
    static LegalRegistrationHttpITSupport fixture;
    @TempDir Path directory;
    @BeforeAll static void start() { POSTGRES.start(); fixture = new LegalRegistrationHttpITSupport(POSTGRES); }
    @AfterAll static void stop() { POSTGRES.stop(); }
    @BeforeEach void reset() throws Exception { fixture.reset(directory, getClass()); }
    @AfterEach void noAmbientResources() { assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty(); }

    @Test void replayUsesDurableIdsAndCurrentEmailRoleVerificationAndTokenVersionWithoutRecreatingAnything() throws Exception {
        var request = fixture.request(); String key = UUID.randomUUID().toString();
        try (var h = fixture.harness()) {
            var first = h.post(body(request), key); assertStatus(first, 201);
            String originalToken = json(first).path("token").asText();
            long user = fixture.userId(), workshop = fixture.tallerId();
            String currentEmail = "current-" + UUID.randomUUID() + "@test.invalid";
            fixture.owner.update("UPDATE users SET email=?, role='USER', email_verificado=true, token_version=9 WHERE id=?", currentEmail, user);
            var beforeReplay = fixture.rows();
            long accountWrites = h.writer.probe().inserts("users");
            var replay = h.post(body(request), key); assertStatus(replay, 201);
            var response = json(replay);
            assertThat(response.path("email").asText()).isEqualTo(currentEmail);
            assertThat(response.path("emailVerificado").asBoolean()).isTrue();
            String freshToken = response.path("token").asText();
            assertThat(freshToken).isNotEqualTo(originalToken);
            var original = h.jwt().verifyToken(originalToken);
            var current = h.jwt().verifyToken(freshToken);
            assertThat(current.getId()).isNotEqualTo(original.getId());
            assertThat(current.getSubject()).isEqualTo(currentEmail);
            assertThat(current.getClaim("role").asString()).isEqualTo("ROLE_USER");
            assertThat(current.getClaim("tokenVersion").asLong()).isEqualTo(9L);
            assertThat(current.getClaim("tallerId").asLong()).isEqualTo(workshop);
            assertThat(fixture.userId()).isEqualTo(user);
            assertThat(fixture.rows()).isEqualTo(beforeReplay);
            assertThat(h.writer.probe().inserts("users")).isEqualTo(accountWrites);
            assertThat(h.writer.probe().hashes).hasValue(1); // No second registration BCrypt; K compares the password anew.
            assertThat(h.sessions.sessionReads).hasValue(2);
            assertThat(h.sessions.signatures).hasValue(2);
            assertThat(h.sessions.recipients).containsExactly(request.command().registration().email());
            h.assertReleased();
        }
    }

    @Test void completedReplaySurvivesReplacementAndRetirementWithoutReadingCurrentRequirements() throws Exception {
        var request = fixture.request(); String key = UUID.randomUUID().toString();
        try (var h = fixture.harness()) {
            assertStatus(h.post(body(request), key), 201);
            fixture.fixture.replaceRegistration(); fixture.fixture.retireRegistration();
            var before = fixture.rows();
            int firstSql = h.writer.probe().sql.size();
            var replay = h.post(body(request), key);
            assertStatus(replay, 201);
            assertThat(fixture.rows()).isEqualTo(before);
            assertThat(h.writer.probe().hashes).hasValue(1);
            assertThat(h.publicContext).isNull();
            assertThat(h.writer.probe().sql.subList(firstSql, h.writer.probe().sql.size()))
                    .noneMatch(sql -> sql.stripLeading().toUpperCase(java.util.Locale.ROOT).startsWith("INSERT "));
            assertThat(h.sessions.recipients).containsExactly(request.command().registration().email());
            assertThat(h.sessions.signatures).hasValue(2);
            h.assertReleased();
        }
    }

    @Test void sameKeyWithAnotherPasswordIsA409AndNeverStartsSessionOrRehashesTheAccount() throws Exception {
        var request = fixture.request(); String key = UUID.randomUUID().toString();
        try (var h = fixture.harness()) {
            assertStatus(h.post(body(request), key), 201); var before = fixture.rows();
            var changed = body(request).put("password", "another-password-456");
            var result = h.post(changed, key);
            assertError(result, 409, "IDEMPOTENCY_KEY_REUTILIZADA");
            assertThat(json(result).path("details").path("operacion").asText()).isEqualTo("REGISTRO");
            assertThat(fixture.rows()).isEqualTo(before);
            assertThat(h.sessions.sessionReads).hasValue(1);
            assertThat(h.sessions.signatures).hasValue(1);
            assertThat(h.writer.probe().hashes).hasValue(1);
            assertThat(h.sessions.recipients).hasSize(1);
            assertThat(h.writer.probe().rollbacks).hasValue(1);
        }
    }

    @Test void sessionFailureAfterCommittedRegistrationReturns503AndRetryReusesTheDurableAccount() throws Exception {
        var request = fixture.request(); String key = UUID.randomUUID().toString();
        try (var h = fixture.harness()) {
            h.sessions.failSignature = true;
            var failed = h.post(body(request), key);
            assertError(failed, 503, "CONTRATO_LEGAL_NO_DISPONIBLE");
            assertThat(json(failed).toString()).doesNotContain("Synthetic", "token", "userId", "tallerId");
            assertThat(h.writer.probe().commits).hasValue(1);
            assertThat(fixture.count("users")).isEqualTo(1);
            assertThat(fixture.count("legal_idempotencia_resultados")).isEqualTo(1);
            assertThat(fixture.count("auth_tokens")).isZero();
            assertThat(h.sessions.recipients).isEmpty();
            var durable = fixture.rows(); long user = fixture.userId(), workshop = fixture.tallerId();
            h.sessions.failSignature = false;
            var retry = h.post(body(request), key);
            assertStatus(retry, 201);
            assertThat(h.jwt().verifyToken(json(retry).path("token").asText()).getClaim("tallerId").asLong()).isEqualTo(workshop);
            assertThat(fixture.userId()).isEqualTo(user);
            assertThat(fixture.rows()).isEqualTo(durable);
            assertThat(h.writer.probe().hashes).hasValue(1);
            assertThat(h.sessions.signatures).hasValue(2);
            assertThat(h.sessions.recipients).isEmpty(); // A replay does not silently resend a missed welcome.
            h.assertReleased();
        }
    }

    @Test void replayDoesNotIssueSessionForAnOldPasswordAfterACommittedPasswordChange() throws Exception {
        var request = fixture.request(); String key = UUID.randomUUID().toString();
        try (var h = fixture.harness()) {
            assertStatus(h.post(body(request), key), 201);
            fixture.owner.update("UPDATE users SET password=?, token_version=token_version+1 WHERE id=?",
                    new BCryptPasswordEncoder().encode("changed-current-password-789"), fixture.userId());
            var before = fixture.rows();
            var rejected = h.post(body(request), key);
            assertError(rejected, 401, null);
            assertThat(json(rejected).path("message").asText()).isEqualTo("Usuario o contraseña incorrectos");
            assertThat(h.sessions.sessionReads).hasValue(2);
            assertThat(h.sessions.signatures).hasValue(1);
            assertThat(h.writer.probe().hashes).hasValue(1);
            assertThat(fixture.rows()).isEqualTo(before);
            assertThat(h.sessions.recipients).hasSize(1);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"user", "workshop"})
    void aDisabledCurrentActorCannotReceiveAnotherTokenFromReplay(String disabled) throws Exception {
        var request = fixture.request(); String key = UUID.randomUUID().toString();
        try (var h = fixture.harness()) {
            assertStatus(h.post(body(request), key), 201);
            if (disabled.equals("user")) fixture.owner.update("UPDATE users SET active=false WHERE id=?", fixture.userId());
            else fixture.owner.update("UPDATE talleres SET activo=false WHERE id=?", fixture.tallerId());
            var before = fixture.rows();
            var rejected = h.post(body(request), key);
            assertError(rejected, 401, null);
            assertThat(json(rejected).path("message").asText()).isEqualTo("Usuario o contraseña incorrectos");
            assertThat(h.writer.probe().rollbacks).hasValue(1);
            assertThat(h.sessions.sessionReads).hasValue(1);
            assertThat(h.sessions.signatures).hasValue(1);
            assertThat(fixture.rows()).isEqualTo(before);
            assertThat(h.sessions.recipients).hasSize(1);
        }
    }
}
