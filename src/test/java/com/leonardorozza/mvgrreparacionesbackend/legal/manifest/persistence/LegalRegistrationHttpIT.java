package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leonardorozza.mvgrreparacionesbackend.legal.http.LegalRegistrationHttpConfiguration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.postgresql.PostgreSQLContainer;

import jakarta.persistence.EntityManagerFactory;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalRegistrationHttpITSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

/** HTTP contract through mock servlet transport, real restricted registration, JPA, BCrypt and JWT. */
class LegalRegistrationHttpIT {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_registration_http").withUsername("ordenfix").withPassword("ordenfix");
    static LegalRegistrationHttpITSupport fixture;
    @TempDir Path directory;
    @BeforeAll static void start() { POSTGRES.start(); fixture = new LegalRegistrationHttpITSupport(POSTGRES); }
    @AfterAll static void stop() { POSTGRES.stop(); }
    @BeforeEach void reset() throws Exception { fixture.reset(directory, getClass()); }
    @AfterEach void noAmbientResources() { assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty(); }

    @Test void createdResponseFollowsOneDurableAccountAndCanonicalEvidenceTransaction() throws Exception {
        var request = fixture.request();
        try (var h = fixture.harness()) {
            var response = h.post(body(request), UUID.randomUUID().toString());
            assertStatus(response, 201);
            var value = json(response);
            assertThat(value.properties()).extracting(java.util.Map.Entry::getKey)
                    .containsExactlyInAnyOrder("token", "type", "email", "emailVerificado");
            assertThat(value.path("type").asText()).isEqualTo("Bearer");
            assertThat(value.path("email").asText()).isEqualTo(request.command().registration().email());
            assertThat(value.path("emailVerificado").asBoolean()).isFalse();
            var jwt = h.jwt().verifyToken(value.path("token").asText());
            assertThat(jwt.getSubject()).isEqualTo(request.command().registration().email());
            assertThat(jwt.getClaim("tallerId").asLong()).isEqualTo(fixture.tallerId());
            assertThat(jwt.getClaim("role").asString()).isEqualTo("ROLE_ADMIN");
            assertThat(jwt.getClaim("tokenVersion").asLong()).isZero();
            assertThat(h.writer.probe().hashes).hasValue(1);
            assertThat(h.writer.probe().commits).hasValue(1);
            assertThat(h.writer.probe().rollbacks).hasValue(0);
            assertThat(h.sessions.sessionReads).hasValue(1);
            assertThat(h.sessions.signatures).hasValue(1);
            assertThat(h.sessions.recipients).containsExactly(request.command().registration().email());
            assertThat(fixture.count("auth_tokens")).isEqualTo(1);
            for (String table : List.of("users", "talleres", "suscripciones", "legal_aceptacion_lotes", "legal_aceptaciones",
                    "legal_aceptacion_metadatos", "legal_idempotencia_resultados")) assertThat(fixture.count(table)).as(table).isEqualTo(1);
            assertThat(fixture.owner.queryForObject("SELECT u.active AND t.activo AND u.role='ADMIN' AND s.plan='FREE' AND s.estado='TRIAL' FROM users u JOIN talleres t ON t.id=u.taller_id JOIN suscripciones s ON s.taller_id=t.id", Boolean.class)).isTrue();
            var xids = new HashSet<String>();
            for (String table : LegalRegistrationWriterITSupport.TABLES) {
                if (!table.equals("auth_tokens")) xids.addAll(fixture.owner.queryForList("SELECT DISTINCT xmin::text FROM public." + table, String.class));
            }
            assertThat(xids).hasSize(1);
            var lot = fixture.owner.queryForObject("SELECT id FROM legal_aceptacion_lotes", UUID.class);
            assertThat(fixture.owner.queryForList("SELECT requisito_version_id FROM legal_aceptaciones WHERE lote_id=?", UUID.class, lot))
                    .containsExactlyElementsOf(request.command().acceptances().stream().map(a -> a.requisitoVersionId()).toList());
            assertThat(fixture.owner.queryForList("SELECT afirmacion_sha256 FROM legal_aceptaciones WHERE lote_id=?", String.class, lot))
                    .containsExactlyElementsOf(request.command().acceptances().stream().map(a -> a.afirmacionSha256()).toList());
            var fields = LegalAcceptanceMetadataITSupport.fields(fixture.owner, lot);
            assertThat(fields).extracting(LegalAcceptanceMetadataITSupport.CipherRow::type).containsExactly("IP", "USER_AGENT");
            assertThat(LegalAcceptanceMetadataITSupport.decrypt(lot, fields.getFirst())).isEqualTo("192.0.2.31");
            assertThat(LegalAcceptanceMetadataITSupport.decrypt(lot, fields.getLast())).isEqualTo(USER_AGENT);
            assertThat(fixture.owner.queryForObject("SELECT operacion='REGISTRO' AND route_template='/api/auth/register' AND user_id=? AND taller_id=? FROM legal_idempotencia_resultados", Boolean.class, fixture.userId(), fixture.tallerId())).isTrue();
            h.assertReleased();
        }
    }

    @Test void productionHttpConfigurationOwnsRestrictedWriterAndImportsTheSingleJpaSessionRouter() throws Exception {
        var request = fixture.request();
        AnnotationConfigApplicationContext isolated;
        try (var h = fixture.productionHarness()) {
            assertStatus(h.post(body(request), UUID.randomUUID().toString()), 201);
            var capability = h.context.getBean(LegalRegistrationHttpConfiguration.Capability.class);
            isolated = (AnnotationConfigApplicationContext) ReflectionTestUtils.getField(capability, "registrationContext");
            assertThat(isolated).isNotNull();
            assertThat(isolated.getParent()).isNull();
            assertThat(isolated.getBeanNamesForType(EntityManagerFactory.class)).isEmpty();
            var bounded = isolated.getBean(LegalPrivateRequirementsDataSource.class);
            var jdbc = isolated.getBean(JdbcTemplate.class);
            String role = bounded.withinDeadline(deadline -> jdbc.queryForObject("SELECT current_user || ':' || session_user", String.class));
            assertThat(role).isEqualTo(LegalRegistrationWriterITSupport.ROLE + ":" + LegalRegistrationWriterITSupport.ROLE);
            assertThat(h.context.getBean(JdbcTemplate.class).queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
            assertThat(h.context.getBean(javax.sql.DataSource.class)).isInstanceOf(LegalRegistrationSessionDataSource.class);
            assertThat(h.writer.probe().borrows).hasValue(0); // The injected test service was not used by production configuration.
            assertThat(h.sessions.sessionReads).hasValue(1);
            assertThat(fixture.count("users")).isEqualTo(1);
            h.assertReleased();
        }
        assertThat(isolated.isActive()).isFalse();
    }

    @Test void enforcementReturnsPublic428WithoutCreatingAnAccountOrHashingPassword() throws Exception {
        var request = fixture.request();
        try (var h = fixture.harness(true, true)) {
            var result = h.post(legacyBody(request));
            assertError(result, 428, "ACEPTACION_LEGAL_REQUERIDA");
            var current = json(result).path("details").path("requisitosActuales");
            assertThat(current.path("requiredSetRevision").asText()).isEqualTo(request.current().requiredSetRevision());
            assertThat(current.path("contexto").asText()).isEqualTo("REGISTRO");
            assertThat(current.path("locale").asText()).isEqualTo("es-AR");
            assertThat(current.toString()).doesNotContain("userId", "tallerId", "pendiente", "aceptadoEn");
            assertThat(fixture.count("users")).isZero();
            assertThat(fixture.count("legal_idempotencia_resultados")).isZero();
            assertThat(h.writer.probe().borrows).hasValue(0);
            assertThat(h.sessions.signatures).hasValue(0);
            assertThat(h.sessions.recipients).isEmpty();
            var bounded = h.publicContext.getBean(LegalPublicRequirementsDataSource.class);
            String role = bounded.withinDeadline(deadline -> h.publicContext.getBean(JdbcTemplate.class).queryForObject("SELECT current_user", String.class));
            assertThat(role).isEqualTo(PUBLIC_ROLE);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void genuinelyAbsentLegalInputStillUsesCommittedLegacyWhenEnforcementIsOff(boolean consent) throws Exception {
        var request = fixture.request();
        try (var h = fixture.harness(consent, false)) {
            var result = h.post(legacyBody(request));
            assertStatus(result, 201);
            assertThat(json(result).path("email").asText()).isEqualTo(request.command().registration().email());
            assertThat(fixture.count("users")).isEqualTo(1);
            assertThat(fixture.count("suscripciones")).isEqualTo(1);
            assertThat(fixture.count("legal_aceptacion_lotes")).isZero();
            assertThat(fixture.count("legal_idempotencia_resultados")).isZero();
            assertThat(h.writer.probe().borrows).hasValue(0);
            assertThat(h.sessions.recipients).containsExactly(request.command().registration().email());
        }
    }

    @Test void completeLegalInputCannotFallBackToLegacyWhenConsentCapabilityIsOff() throws Exception {
        var request = fixture.request(); var before = fixture.rows();
        try (var h = fixture.harness(false, false)) {
            assertError(h.post(body(request), UUID.randomUUID().toString()), 503, "CONTRATO_LEGAL_NO_DISPONIBLE");
            assertThat(h.writer.probe().borrows).hasValue(0);
            assertThat(h.sessions.sessionReads).hasValue(0);
            assertThat(h.sessions.recipients).isEmpty();
        }
        assertThat(fixture.rows()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "malformed-before-json", "repeated"})
    void headerErrorsAreDefinitiveBeforeAnyAccountOrLegalTransaction(String scenario) throws Exception {
        var request = fixture.request(); var before = fixture.rows();
        try (var h = fixture.harness()) {
            var result = switch (scenario) {
                case "missing" -> h.post(body(request));
                case "malformed-before-json" -> h.post("{", "not-a-uuid");
                case "repeated" -> h.post(body(request), UUID.randomUUID().toString(), UUID.randomUUID().toString());
                default -> throw new AssertionError(scenario);
            };
            assertError(result, 400, scenario.equals("missing") ? "IDEMPOTENCY_KEY_REQUERIDA" : "IDEMPOTENCY_KEY_INVALIDA");
            assertThat(h.writer.probe().borrows).hasValue(0);
            assertThat(h.sessions.sessionReads).hasValue(0);
        }
        assertThat(fixture.rows()).isEqualTo(before);
    }

    @Test void semanticDuplicateIsRejectedAfterObservedRollbackWithoutBusinessWrites() throws Exception {
        var request = fixture.request(); var before = fixture.rows();
        ObjectNode submitted = body(request);
        submitted.withArray("aceptacionesLegales").add(submitted.withArray("aceptacionesLegales").get(0).deepCopy());
        try (var h = fixture.harness()) {
            var result = h.post(submitted, UUID.randomUUID().toString());
            assertError(result, 400, "ACEPTACION_LEGAL_INVALIDA");
            assertThat(json(result).path("details").path("motivos").toString()).contains("REQUISITO_DUPLICADO");
            assertThat(h.writer.probe().rollbacks).hasValue(1);
            assertThat(h.writer.probe().hashes).hasValue(0);
            assertThat(h.sessions.sessionReads).hasValue(0);
            assertThat(h.sessions.recipients).isEmpty();
        }
        assertThat(fixture.rows()).isEqualTo(before);
    }

    @Test void staleRevisionWinsOverDuplicateAndReturnsTheActuallyPublishedSnapshot() throws Exception {
        var old = fixture.request(); fixture.fixture.replaceRegistration();
        var current = fixture.request(); var before = fixture.rows();
        ObjectNode submitted = body(old);
        submitted.withArray("aceptacionesLegales").add(submitted.withArray("aceptacionesLegales").get(0).deepCopy());
        try (var h = fixture.harness()) {
            var result = h.post(submitted, UUID.randomUUID().toString());
            assertError(result, 409, "DOCUMENTOS_LEGALES_DESACTUALIZADOS");
            var snapshot = json(result).path("details").path("requisitosActuales");
            assertThat(snapshot.path("requiredSetRevision").asText()).isEqualTo(current.current().requiredSetRevision());
            assertThat(snapshot.path("requiredSetRevision").asText()).isNotEqualTo(old.current().requiredSetRevision());
            assertThat(h.writer.probe().rollbacks).hasValue(1);
            assertThat(h.writer.probe().hashes).hasValue(0);
            assertThat(h.sessions.signatures).hasValue(0);
        }
        assertThat(fixture.rows()).isEqualTo(before);
    }
}
