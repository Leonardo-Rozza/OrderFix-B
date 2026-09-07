package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leonardorozza.mvgrreparacionesbackend.legal.http.LegalAcceptanceController;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalIdempotencyFingerprint;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceHttpITSupport.Fault;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceHttpITSupport.Running;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceHttpITSupport.*;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceProtocolFeasibilityITSupport.jdbc;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceProtocolFeasibilityITSupport.transaction;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceServiceITSupport.key;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.fail;

/** Socket HTTP, production security/bridge and PostgreSQL; JWT identities alone are synthetic. */
class LegalAcceptanceHttpIT {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_acceptance_http").withUsername("ordenfix").withPassword("ordenfix");
    private static LegalAcceptanceServiceITSupport fixture;
    @TempDir Path directory;
    private Running http;

    @BeforeAll static void start() {
        POSTGRES.start(); fixture = new LegalAcceptanceServiceITSupport(POSTGRES); provisionReader(fixture);
    }
    @AfterAll static void stop() { POSTGRES.stop(); }
    @BeforeEach void reset() throws Exception { fixture.reset(directory, getClass()); }
    @AfterEach void closeServerAndBothConsumerPools() { if (http != null) http.close(); }

    @ParameterizedTest @EnumSource(UserRole.class)
    void eachAllowedRoleGetsAnEmpty204AndEncryptedOriginalSocketMetadata(UserRole role) throws Exception {
        var actor = fixture.actor(role); var command = fixture.command(actor); http = open(fixture, true, "", Fault.NONE);
        var request = body(command); var token = http.authorize(actor);
        var response = http.send("POST", PATH, token, JSON.writeValueAsBytes(request), headers(
                "Content-Type", "application/json", "Idempotency-Key", key(), "User-Agent", USER_AGENT,
                "X-Forwarded-For", "203.0.113.77", "Forwarded", "for=203.0.113.78",
                "X-Taller-Id", "999999", "X-User-Id", "999999"));
        success(response);
        assertThat(http.metrics.snapshot().commits()).isOne(); assertThat(http.metrics.snapshot().rollbacks()).isZero();
        UUID lot = fixture.owner.queryForObject("SELECT id FROM legal_aceptacion_lotes WHERE user_id=? AND taller_id=?",
                UUID.class, actor.userId(), actor.tallerId());
        assertThat(fixture.owner.queryForObject("SELECT count(*) FROM legal_aceptaciones WHERE user_id=? AND taller_id=?",
                Long.class, actor.userId(), actor.tallerId())).isEqualTo(command.acceptances().size());
        var fields = LegalAcceptanceMetadataITSupport.fields(fixture.owner, lot);
        assertThat(fields).extracting(LegalAcceptanceMetadataITSupport.CipherRow::type).containsExactly("IP", "USER_AGENT");
        assertThat(LegalAcceptanceMetadataITSupport.decrypt(lot, fields.getFirst())).isEqualTo("127.0.0.1");
        assertThat(LegalAcceptanceMetadataITSupport.decrypt(lot, fields.getLast())).isEqualTo(USER_AGENT);
        assertThat(fields.getFirst().nonce()).hasSize(12); assertThat(fields.getFirst().tag()).hasSize(16);
        assertThat(new JdbcTemplate(http.pool).queryForObject("SELECT current_user || ':' || session_user", String.class))
                .isEqualTo(LegalAcceptanceServiceITSupport.ROLE + ":" + LegalAcceptanceServiceITSupport.ROLE);
        assertThat(http.context.getBeansOfType(DataSource.class)).isEmpty();
        assertThat(http.context.getBeansOfType(JdbcTemplate.class)).isEmpty();
        assertThat(http.context.getBeansOfType(PlatformTransactionManager.class)).isEmpty();
        assertThat(http.acceptance.getParent()).isNull();
        assertThat(http.pool).isNotSameAs(http.readContext.getBean(com.zaxxer.hikari.HikariDataSource.class));
        assertThat(http.pool.getHikariPoolMXBean().getActiveConnections()).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"DEDUP", "EMPTY"})
    void noNewActResultsUseTheirLedgerAndEveryReplayLeavesAllDurableRowsUnchanged(String kind) throws Exception {
        var actor = fixture.actor(); var command = fixture.command(actor); http = open(fixture, true, "", Fault.NONE);
        String token = http.authorize(actor); success(http.post(token, key(), body(command)));
        var evidence = evidenceRows(); ObjectNode request = body(command);
        if (kind.equals("EMPTY")) request.putArray("aceptacionesLegales");
        String requestKey = key(); http.metrics.reset(); success(http.post(token, requestKey, request));
        assertThat(fixture.owner.queryForObject("SELECT resultado FROM legal_idempotencia_sin_actos", String.class)).isEqualTo(kind);
        assertThat(evidenceRows()).isEqualTo(evidence);
        var durable = fixture.durableRows(); http.metrics.reset();
        success(http.post(token, requestKey, request)); noDml();
        assertThat(fixture.durableRows()).isEqualTo(durable);
    }

    @Test void durableReplayPrecedesRetiredCatalogAndAnExclusivelyHeldEditorialGate() throws Exception {
        var actor = fixture.actor(); var command = fixture.command(actor); http = open(fixture, true, "", Fault.NONE);
        String token = http.authorize(actor), requestKey = key(); ObjectNode request = body(command);
        success(http.post(token, requestKey, request)); fixture.retireUsage(directory, getClass());
        var before = fixture.durableRows(); http.metrics.reset();
        try (Connection exclusive = transaction(fixture.dataSource)) {
            jdbc(exclusive).queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", LegalManifestDatabaseGate.EDITORIAL_LOCK_NAME);
            success(http.post(token, requestKey, request));
            assertThat(exclusive.getAutoCommit()).isFalse(); exclusive.rollback();
        }
        noDml(); assertThat(fixture.durableRows()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"anonymous", "bad-token", "revoked-user", "revoked-workshop"})
    void missingSessionOrPersistedRevocationWinsOverInvalidHeaderJsonTypeAndQuery(String identity) throws Exception {
        var actor = fixture.actor(); http = open(fixture, true, "", Fault.NONE);
        String token = identity.equals("anonymous") ? null : identity.equals("bad-token") ? "bad-token" : http.authorize(actor);
        if (identity.equals("revoked-user")) fixture.owner.update("UPDATE users SET active=false WHERE id=?", actor.userId());
        if (identity.equals("revoked-workshop")) fixture.owner.update("UPDATE talleres SET activo=false WHERE id=?", actor.tallerId());
        var before = fixture.durableRows();
        var response = http.send("POST", PATH + "?unexpected=1", token, bytes("{bad-json"),
                headers("Content-Type", "text/plain", "Idempotency-Key", "not-a-key"));
        error(response, 401, null); noDml(); assertThat(fixture.durableRows()).isEqualTo(before);
        if (identity.startsWith("revoked")) assertThat(http.metrics.snapshot().rollbacks()).isOne();
        else assertThat(http.metrics.snapshot().statementExecutions()).isZero();
    }

    @Test void unsupportedRoleGetsGeneric403WithoutReadingOrWritingTheLegalGraph() throws Exception {
        var actor = fixture.actor(); http = open(fixture, true, "", Fault.NONE); var before = fixture.durableRows();
        var response = http.send("POST", PATH, http.unsupportedRole(actor), bytes("{bad-json"),
                headers("Content-Type", "text/plain", "Idempotency-Key", "not-a-key"));
        error(response, 403, null); assertThat(http.metrics.snapshot().statementExecutions()).isZero();
        assertThat(fixture.durableRows()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"present-invalid", "repeated", "missing-bad-json", "missing-valid-json"})
    void exactIdempotencyHeaderAndJsonPrioritySurvivesTheRealServletBoundary(String variant) throws Exception {
        var actor = fixture.actor(); var command = fixture.command(actor); http = open(fixture, true, "", Fault.NONE);
        var headers = headers("Content-Type", "application/json");
        if (variant.equals("present-invalid")) headers.put("Idempotency-Key", List.of("not-a-key"));
        if (variant.equals("repeated")) headers.put("Idempotency-Key", List.of(key(), key()));
        byte[] payload = variant.equals("missing-valid-json") ? JSON.writeValueAsBytes(body(command)) : bytes("{bad-json");
        var before = fixture.durableRows(); var response = http.send("POST", PATH, http.authorize(actor), payload, headers);
        String code = variant.equals("missing-valid-json") ? "IDEMPOTENCY_KEY_REQUERIDA"
                : variant.equals("missing-bad-json") ? "ACEPTACION_LEGAL_INVALIDA" : "IDEMPOTENCY_KEY_INVALIDA";
        JsonNode rejected = error(response, 400, code);
        assertThat(rejected.get("details")).isEqualTo(variant.equals("missing-bad-json")
                ? JSON.valueToTree(Map.of("motivos", List.of("PAYLOAD_LEGAL_INCOMPLETO")))
                : JSON.valueToTree(Map.of("header", "Idempotency-Key")));
        noDml(); assertThat(http.metrics.snapshot().rollbacks()).isOne(); assertThat(fixture.durableRows()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"query", "content-type", "charset", "extra-property"})
    void unsupportedSelectorsMediaOrAuthorityFieldsRemainContractual400AfterActorValidation(String variant) throws Exception {
        var actor = fixture.actor(); var command = fixture.command(actor); http = open(fixture, true, "", Fault.NONE);
        ObjectNode request = body(command); if (variant.equals("extra-property")) request.put("userId", actor.userId());
        String type = variant.equals("content-type") ? "text/plain" : variant.equals("charset")
                ? "application/json;charset=ISO-8859-1" : "application/json";
        var before = fixture.durableRows();
        JsonNode rejected = error(http.send("POST", PATH + (variant.equals("query") ? "?locale=es-AR" : ""),
                http.authorize(actor), JSON.writeValueAsBytes(request), headers("Content-Type", type, "Idempotency-Key", key())),
                400, "ACEPTACION_LEGAL_INVALIDA");
        assertThat(rejected.path("details").path("motivos")).isEqualTo(JSON.valueToTree(List.of("PAYLOAD_LEGAL_INCOMPLETO")));
        noDml(); assertThat(fixture.durableRows()).isEqualTo(before);
    }

    @Test void staleRevisionWinsOverDuplicatesAndReturnsTheExactPrivatePendingShape() throws Exception {
        var actor = fixture.actor(); var command = fixture.command(actor); http = open(fixture, true, "", Fault.NONE);
        ObjectNode request = body(command); request.put("requiredSetRevision", "sha256:" + "a".repeat(64));
        request.withArray("aceptacionesLegales").add(request.withArray("aceptacionesLegales").get(0).deepCopy());
        var before = fixture.durableRows(); var rejected = error(http.post(http.authorize(actor), key(), request),
                409, "DOCUMENTOS_LEGALES_DESACTUALIZADOS");
        assertThat(rejected.path("details").path("submittedRevision").asText()).isEqualTo(request.path("requiredSetRevision").asText());
        JsonNode current = rejected.path("details").path("requisitosActuales");
        assertThat(fieldNames(current)).containsExactlyInAnyOrder("locale", "requiredSetRevision", "requisitos");
        assertThat(current.path("locale").asText()).isEqualTo("es-AR");
        assertThat(current.path("requiredSetRevision").asText()).isEqualTo(command.requiredSetRevision());
        assertThat(current.path("requisitos").size()).isEqualTo(command.acceptances().size());
        JsonNode first = current.path("requisitos").get(0);
        assertThat(fieldNames(first)).containsExactlyInAnyOrder("id", "contexto", "tipoActo", "afirmacion", "afirmacionSha256", "requerido", "documentos");
        assertThat(first.path("documentos").get(0).path("contenidoMarkdown").asText()).isNotEmpty();
        assertThat(http.metrics.snapshot().rollbacks()).isOne(); assertThat(fixture.durableRows()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"duplicate", "false", "documents"})
    void currentSemanticFailuresAreNotLostByTheTransportParser(String variant) throws Exception {
        var actor = fixture.actor(); var command = fixture.command(actor); http = open(fixture, true, "", Fault.NONE);
        ObjectNode request = body(command); var acts = request.withArray("aceptacionesLegales");
        if (variant.equals("duplicate")) acts.add(acts.get(0).deepCopy());
        if (variant.equals("false")) ((ObjectNode) acts.get(0)).put("confirmado", false);
        if (variant.equals("documents")) ((ObjectNode) acts.get(0)).putArray("documentos");
        var before = fixture.durableRows(); JsonNode rejected = error(http.post(http.authorize(actor), key(), request),
                400, "ACEPTACION_LEGAL_INVALIDA");
        String expected = switch (variant) { case "duplicate" -> "REQUISITO_DUPLICADO";
            case "false" -> "CONFIRMACION_REQUERIDA"; default -> "DOCUMENTO_FALTANTE"; };
        assertThat(rejected.path("details").path("motivos").toString()).contains('"' + expected + '"');
        assertThat(http.metrics.snapshot().rollbacks()).isOne(); assertThat(fixture.durableRows()).isEqualTo(before);
    }

    @Test void aReusedKeyWithDifferentBusinessInputReturns409WithoutDml() throws Exception {
        var actor = fixture.actor(); var command = fixture.command(actor); http = open(fixture, true, "", Fault.NONE);
        String token = http.authorize(actor), requestKey = key(); success(http.post(token, requestKey, body(command)));
        var changed = body(command); changed.putArray("aceptacionesLegales"); var before = fixture.durableRows(); http.metrics.reset();
        JsonNode rejected = error(http.post(token, requestKey, changed), 409, "IDEMPOTENCY_KEY_REUTILIZADA");
        assertThat(rejected.path("details")).isEqualTo(JSON.valueToTree(Map.of("operacion", "ACEPTACION_LEGAL")));
        noDml(); assertThat(fixture.durableRows()).isEqualTo(before);
    }

    @Test void anExplicitTrustedChainStoresTheFirstUntrustedHopAndOmitsAnEmptyUserAgent() throws Exception {
        var actor = fixture.actor(); var command = fixture.command(actor);
        http = open(fixture, true, "127.0.0.1/32,10.0.0.0/8", Fault.NONE);
        success(http.send("POST", PATH, http.authorize(actor), JSON.writeValueAsBytes(body(command)), headers(
                "Content-Type", "application/json", "Idempotency-Key", key(), "User-Agent", "",
                "X-Forwarded-For", "198.51.100.250, 203.0.113.19, 10.0.0.9")));
        UUID lot = fixture.owner.queryForObject("SELECT id FROM legal_aceptacion_lotes", UUID.class);
        var fields = LegalAcceptanceMetadataITSupport.fields(fixture.owner, lot);
        assertThat(fields).extracting(LegalAcceptanceMetadataITSupport.CipherRow::type).containsExactly("IP");
        assertThat(LegalAcceptanceMetadataITSupport.decrypt(lot, fields.getFirst())).isEqualTo("203.0.113.19");
    }

    @ParameterizedTest @ValueSource(strings = {"missing-forwarded", "repeated-user-agent"})
    void unavailableRequiredMetadataRollsBackBeforeAnyDml(String variant) throws Exception {
        var actor = fixture.actor(); var command = fixture.command(actor);
        http = open(fixture, true, variant.equals("missing-forwarded") ? "127.0.0.1/32" : "", Fault.NONE);
        var headers = headers("Content-Type", "application/json", "Idempotency-Key", key());
        if (variant.equals("repeated-user-agent")) headers.put("User-Agent", List.of("first", "second"));
        var before = fixture.durableRows(); unavailable(http.send("POST", PATH, http.authorize(actor), JSON.writeValueAsBytes(body(command)), headers));
        noDml(); assertThat(fixture.durableRows()).isEqualTo(before);
    }

    @Test void deadlineAdvancedByTheActualServletReadIs503AtTheNextCheckpointWithoutWrites() throws Exception {
        var actor = fixture.actor(); var command = fixture.command(actor); http = open(fixture, true, "", Fault.NONE);
        var before = fixture.durableRows(); unavailable(http.send("POST", PATH, http.authorize(actor), JSON.writeValueAsBytes(body(command)),
                headers("Content-Type", "application/json", "Idempotency-Key", key(), "X-Fixture-Expire-Body", "true")));
        assertThat(http.clock.get()).isPositive(); noDml(); assertThat(fixture.durableRows()).isEqualTo(before);
    }

    @Test void aRealIdempotencyLockHeldElsewhereProduces409AndRetryAfterOne() throws Exception {
        var actor = fixture.actor(); var command = fixture.command(actor); String requestKey = key();
        var fingerprint = LegalIdempotencyFingerprint.derive(command, requestKey, 1,
                Base64.getDecoder().decode(LegalAcceptanceServiceITSupport.HMAC_SECRET));
        http = open(fixture, true, "", Fault.NONE); var before = fixture.durableRows();
        try (Connection exclusive = transaction(fixture.dataSource)) {
            JdbcTemplate locker = jdbc(exclusive);
            Long physical = locker.queryForObject("""
                    SELECT hashtextextended(jsonb_build_array('ordenfix:legal-idempotencia:tupla:v29',
                      ?::text,?::text,?::text,?::text)::text,0)
                    """, Long.class, fingerprint.operation().name(), fingerprint.routeTemplate(), fingerprint.scopeHmac(), fingerprint.idempotencyKeyHmac());
            locker.queryForList("SELECT pg_advisory_xact_lock(?::bigint)", physical);
            var response = http.post(http.authorize(actor), requestKey, body(command));
            JsonNode rejected = error(response, 409, "IDEMPOTENCY_EN_PROGRESO");
            assertThat(response.headers().firstValue("Retry-After")).contains("1");
            assertThat(rejected.path("details")).isEqualTo(JSON.valueToTree(Map.of("operacion", "ACEPTACION_LEGAL")));
            exclusive.rollback();
        }
        noDml(); assertThat(fixture.durableRows()).isEqualTo(before);
    }

    @Test void aRealSqlFailureDuringMetadataWriteReturns503AndRollsBackTheEntireGraph() throws Exception {
        var actor = fixture.actor(); var command = fixture.command(actor); http = open(fixture, true, "", Fault.SQL_METADATA);
        var before = fixture.durableRows(); unavailable(http.post(http.authorize(actor), key(), body(command)));
        assertThat(http.probe.injected).isTrue(); assertThat(http.metrics.snapshot().rollbacks()).isOne();
        assertThat(fixture.durableRows()).isEqualTo(before);
    }

    @Test void lostCommitAcknowledgementIs503WithoutAReceiptAndTheSameRequestRecoversByReplay() throws Exception {
        var actor = fixture.actor(); var command = fixture.command(actor); http = open(fixture, true, "", Fault.COMMIT_ACK);
        String token = http.authorize(actor), requestKey = key(); var before = fixture.durableRows();
        unavailable(http.post(token, requestKey, body(command))); assertThat(http.probe.injected).isTrue();
        assertThat(http.metrics.snapshot().commits()).isOne(); assertThat(http.metrics.snapshot().rollbacks()).isZero();
        assertThat(fixture.durableRows()).isNotEqualTo(before);
        var committed = fixture.durableRows(); http.metrics.reset(); success(http.post(token, requestKey, body(command)));
        noDml(); assertThat(fixture.durableRows()).isEqualTo(committed);
    }

    @Test void corsAllowsTheExistingBrowserPreflightWithoutAuthenticationOrLegalSql() throws Exception {
        http = open(fixture, true, "", Fault.NONE);
        var response = http.send("OPTIONS", PATH, null, null, headers("Origin", ORIGIN,
                "Access-Control-Request-Method", "POST", "Access-Control-Request-Headers", "authorization,content-type,idempotency-key"));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).contains(ORIGIN);
        assertThat(response.headers().firstValue("Access-Control-Allow-Methods").orElseThrow()).contains("POST");
        assertThat(http.metrics.snapshot().statementExecutions()).isZero();
    }

    @Test void disablingAcceptanceLeavesTheExistingHistoryGetAndAnonymousPostFallbackUnchanged() throws Exception {
        var actor = fixture.actor(); http = open(fixture, false, "", Fault.NONE); var before = fixture.durableRows();
        assertThat(http.context.getBeansOfType(LegalAcceptanceController.class)).isEmpty(); assertThat(http.acceptance).isNull();
        var history = http.send("GET", PATH, http.authorize(actor), null, Map.of());
        assertThat(history.statusCode()).isEqualTo(200); assertThat(JSON.readTree(history.body()).path("content").isEmpty()).isTrue();
        assertThat(history.headers().firstValue("Cache-Control")).contains("private, no-store");
        var anonymousPost = http.send("POST", PATH, null, bytes("{bad-json"), headers("Content-Type", "application/json"));
        assertThat(anonymousPost.statusCode()).isEqualTo(403); assertThat(fixture.durableRows()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"valve", "forwarded-filter", "remote-filter-subclass"})
    void knownPeerRewritersAreRejectedByActualTomcatStartupBeforeServing(String variant) {
        Class<?> configuration = switch (variant) {
            case "valve" -> HostileValveConfiguration.class;
            case "forwarded-filter" -> HostileForwardedFilterConfiguration.class;
            default -> HostileRemoteIpFilterConfiguration.class;
        };
        Throwable failure = catchThrowable(() -> {
            try (Running unexpected = open(fixture, true, "", Fault.NONE, configuration)) {
                fail("El servidor no debe habilitar un reescritor de peer conocido");
            }
        });
        assertThat(failure).isInstanceOf(RuntimeException.class)
                .hasStackTraceContaining("La captura legal requiere el peer original del servidor.");
        assertThat(fixture.counts().get("legal_aceptacion_lotes")).isZero();
    }

    private static void success(HttpResponse<byte[]> response) {
        assertThat(response.statusCode()).isEqualTo(204); assertThat(response.body()).isEmpty();
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.headers().firstValue("ETag")).isEmpty();
        assertThat(response.headers().firstValue("Content-Type")).isEmpty();
        assertThat(response.headers().firstValue("Retry-After")).isEmpty();
    }

    private static JsonNode error(HttpResponse<byte[]> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.headers().firstValue("ETag")).isEmpty();
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/json");
        JsonNode body = JSON.readTree(response.body()); assertThat(body.path("status").asInt()).isEqualTo(status);
        assertThat(body.path("path").asText()).isEqualTo(PATH); assertThat(body.path("timestamp").asText()).isNotBlank();
        assertThat(body.path("message").asText()).isNotBlank();
        assertThat(body.path("error").asText()).isEqualTo(switch (status) { case 400 -> "Solicitud inválida";
            case 401 -> "No autorizado"; case 403 -> "Acceso denegado"; case 409 -> "Conflicto"; case 503 -> "Servicio no disponible";
            default -> throw new AssertionError(status); });
        if (code == null) { assertThat(body.hasNonNull("code")).isFalse(); assertThat(body.hasNonNull("details")).isFalse(); }
        else assertThat(body.path("code").asText()).isEqualTo(code);
        String wire = new String(response.body(), StandardCharsets.UTF_8);
        assertThat(wire).doesNotContain(LegalAcceptanceServiceITSupport.HMAC_SECRET, LegalAcceptanceServiceITSupport.AES_SECRET,
                fixture.credentials.password(), READ_PASSWORD, "synthetic-principal-hash", "Synthetic", "SQLException", "08006",
                "legal_aceptacion_metadatos", "confirmedReceipt", "acceptanceIds", "loteId", "lotId", "completion", "persistence",
                "userId", "tallerId", "tokenVersion", "scopeRevision");
        return body;
    }

    private static void unavailable(HttpResponse<byte[]> response) throws Exception {
        JsonNode error = error(response, 503, "CONTRATO_LEGAL_NO_DISPONIBLE");
        ObjectNode expected = JSON.createObjectNode(); expected.putNull("contexto"); expected.put("locale", "es-AR");
        assertThat(error.path("details")).isEqualTo(expected); assertThat(response.headers().firstValue("Retry-After")).isEmpty();
    }

    private void noDml() { assertThat(http.metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isZero(); }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static Set<String> fieldNames(JsonNode node) { var names = new java.util.HashSet<String>(); node.fieldNames().forEachRemaining(names::add); return names; }
    private static Map<String, List<String>> evidenceRows() {
        Map<String, List<String>> result = new LinkedHashMap<>(fixture.durableRows());
        result.keySet().removeIf(table -> table.startsWith("legal_idempotencia_")); return Map.copyOf(result);
    }
}
