package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leonardorozza.mvgrreparacionesbackend.config.CorsConfig;
import com.leonardorozza.mvgrreparacionesbackend.config.SecurityConfig;
import com.leonardorozza.mvgrreparacionesbackend.config.filter.JwtFilter;
import com.leonardorozza.mvgrreparacionesbackend.config.filter.PublicEndpointRateLimitFilter;
import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.config.security.LegalPrivateRequirementsAuthenticationEntryPoint;
import com.leonardorozza.mvgrreparacionesbackend.config.security.LegalPublicDocumentRequestMatcher;
import com.leonardorozza.mvgrreparacionesbackend.config.security.LegalPublicRequirementsRequestMatcher;
import com.leonardorozza.mvgrreparacionesbackend.config.security.RateLimitProperties;
import com.leonardorozza.mvgrreparacionesbackend.config.tenant.TenantContext;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.GlobalExceptionHandler;
import com.leonardorozza.mvgrreparacionesbackend.legal.http.LegalPrivateRequirementsController;
import com.leonardorozza.mvgrreparacionesbackend.legal.http.LegalPrivateRequirementsExceptionHandler;
import com.leonardorozza.mvgrreparacionesbackend.legal.http.LegalPrivateRequirementsHttpConfiguration;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAuthenticatedRequirements;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPrivateRequirementsITSupport.Actor;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Taller;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.User;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PerfilAgregadoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.UserDetailsServiceImpl;
import com.leonardorozza.mvgrreparacionesbackend.utils.jwt.JwtUtils;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceProtocolFeasibilityITSupport.insertActs;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceProtocolFeasibilityITSupport.insertDocuments;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceProtocolFeasibilityITSupport.insertLot;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceProtocolFeasibilityITSupport.insertMetadata;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceProtocolFeasibilityITSupport.jdbc;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceProtocolFeasibilityITSupport.materialize;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceProtocolFeasibilityITSupport.sharedBoundary;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceProtocolFeasibilityITSupport.transaction;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPrivateRequirementsITSupport.counts;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPrivateRequirementsITSupport.provision;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPrivateRequirementsITSupport.requireSafeEphemeralDatabase;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPrivateRequirementsITSupport.seedActor;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPrivateRequirementsITSupport.seedCatalog;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/** Real private HTTP bridge, security chain and restricted PostgreSQL graph; only JWT/user collaborators are mocked. */
class LegalPrivateRequirementsHttpIT {
    private static final String ROOT = "/api/requisitos-legales";
    private static final String ROLE = "ordenfix_legal_private_requirements_http";
    private static final String PASSWORD = "private-requirements-http-test-only";
    private static final String PREFIX = LegalPrivateRequirementsDatabaseConfiguration.PROPERTY_PREFIX;
    private static final String FLAG = LegalPrivateRequirementsDatabaseConfiguration.ENABLED_PROPERTY;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_private_requirements_http")
            .withUsername("ordenfix").withPassword("ordenfix");
    private static JdbcTemplate owner;
    private static DataSource ownerDataSource;

    @TempDir Path directory;
    private AnnotationConfigWebApplicationContext context;
    private AnnotationConfigApplicationContext requirementsContext;
    private HikariDataSource pool;
    private LegalJdbcMetricsSupport metrics;
    private MockMvc mvc;

    @BeforeAll static void provisionDedicatedDatabase() {
        POSTGRES.start();
        LegalManifestPersistenceITSupport.migrateLatest(POSTGRES);
        ownerDataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        owner = new JdbcTemplate(ownerDataSource);
        provision(owner, ROLE, PASSWORD);
    }

    @BeforeEach void seedOnlyTheDisposableDatabase() throws Exception {
        requireSafeEphemeralDatabase(owner);
        owner.execute("TRUNCATE legal_requisito_agregados, legal_publicaciones, legal_documento_reemplazo_lotes, talleres RESTART IDENTITY CASCADE");
        seedCatalog(owner, directory, getClass());
    }

    @AfterEach void closesTheOwnedPoolAndClearsBothSecurityContexts() {
        try {
            if (context != null) context.close();
        } finally {
            SecurityContextHolder.clearContext();
            TenantContext.clear();
        }
        if (requirementsContext != null) {
            assertThat(requirementsContext.isActive()).isFalse();
            assertThat(pool.isClosed()).isTrue();
        }
    }

    @AfterAll static void stopDedicatedDatabase() { POSTGRES.stop(); }

    @ParameterizedTest @ValueSource(strings = {"ADMIN", "USER"})
    void derivesTheCompleteCanonicalWireFromTheServerPrincipalAndUsesTheRestrictedGraph(String role) throws Exception {
        Actor actor = seedActor(owner, role);
        openHttp(properties(true, false, false));
        var before = counts(owner);
        MockHttpServletResponse response = mvc.perform(authenticatedGet(actor)
                        .header("X-Taller-Id", Long.toString(actor.workshopId() + 900))
                        .header("X-User-Id", Long.toString(actor.userId() + 900)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(header().doesNotExist("ETag"))
                .andExpect(jsonPath("$.locale").value("es-AR"))
                .andExpect(jsonPath("$.requisitos.length()").value(2))
                .andReturn().getResponse();

        assertThat(metrics.snapshot().commits()).isEqualTo(1);
        assertThat(metrics.snapshot().rollbacks()).isZero();
        assertThat(metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isEqualTo(2);
        assertThat(counts(owner)).containsEntry("legal_requisito_agregados", 1L)
                .containsEntry("legal_aceptaciones", before.get("legal_aceptaciones"))
                .containsEntry("legal_aceptacion_metadatos", before.get("legal_aceptacion_metadatos"));
        LegalAuthenticatedRequirements canonical = requirementsContext.getBean(LegalPrivateRequirementsReadService.class)
                .read(principal(actor));
        assertThat(body(response)).isEqualTo(expectedWire(canonical));
        assertThat(body(response).get("requiredSetRevision").asText()).matches("sha256:[0-9a-f]{64}");
        assertThat(response.getContentAsString()).doesNotContain("userId", "tallerId", "audiencia", "perfil",
                "scopeRevision", "scopeOrdinal", "acceptanceId", "provenance", "decisions", "requiresReacceptance");
        assertThat(requirementsContext.getParent()).isNull();
        assertThat(new JdbcTemplate(pool).queryForObject("SELECT current_user || ':' || session_user", String.class))
                .isEqualTo(ROLE + ":" + ROLE);
        assertNoWebDatabaseGraph();
        assertPoolIdle();
        assertThat(TenantContext.getTallerId()).isNull();
        verify(context.getBean(UserDetailsServiceImpl.class)).loadUserByUsername(principal(actor).getUsername());
    }

    @Test void everyIfNoneMatchValueRecomputesPendingAndReusesTheAggregateWithoutDml() throws Exception {
        Actor actor = seedActor(owner, "ADMIN");
        openHttp(properties(true, false, false));
        JsonNode first = body(mvc.perform(authenticatedGet(actor)).andExpect(status().isOk()).andReturn().getResponse());
        String revision = first.get("requiredSetRevision").asText();
        var original = owner.queryForList("SELECT to_jsonb(t)::text || xmin::text FROM legal_requisito_agregados t");
        var before = counts(owner);
        for (String validator : List.of("*", "\"" + revision + "\"", "W/\"" + revision + "\"", "malformed")) {
            metrics.reset();
            MockHttpServletResponse response = mvc.perform(authenticatedGet(actor).header("If-None-Match", validator))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "private, no-store"))
                    .andExpect(header().doesNotExist("ETag")).andReturn().getResponse();
            assertThat(body(response)).isEqualTo(first);
            assertReusedCommit();
        }
        assertThat(owner.queryForList("SELECT to_jsonb(t)::text || xmin::text FROM legal_requisito_agregados t")).isEqualTo(original);
        assertThat(counts(owner)).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"ADMIN", "USER"})
    void exactOwnEvidenceReturnsLegitimateEmptyPendingWithTheCompleteRevisionAndNoNewEvidence(String role) throws Exception {
        Actor actor = seedActor(owner, role);
        openHttp(properties(true, false, false));
        String revision = body(mvc.perform(authenticatedGet(actor)).andExpect(status().isOk())
                .andReturn().getResponse()).get("requiredSetRevision").asText();
        acceptAsOwnerFixture(actor);
        var before = counts(owner);
        metrics.reset();
        mvc.perform(authenticatedGet(actor).header("If-None-Match", "*"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(header().doesNotExist("ETag"))
                .andExpect(jsonPath("$.requiredSetRevision").value(revision))
                .andExpect(jsonPath("$.requisitos").isEmpty());
        assertReusedCommit();
        assertThat(counts(owner)).isEqualTo(before);
    }

    @Test void evidenceFromAnotherUserInTheSameWorkshopAndAnotherTenantDoesNotSatisfyTheActor() throws Exception {
        Actor actor = seedActor(owner, "USER");
        Actor sameWorkshop = seedActor(owner, "USER", actor.workshopId());
        Actor otherTenant = seedActor(owner, "USER");
        acceptAsOwnerFixture(sameWorkshop);
        acceptAsOwnerFixture(otherTenant);
        openHttp(properties(true, false, false));
        var before = counts(owner);
        mvc.perform(authenticatedGet(actor)).andExpect(status().isOk()).andExpect(jsonPath("$.requisitos.length()").value(2));
        assertReusedCommit();
        assertThat(counts(owner)).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"role", "token", "inactive", "workshop", "tenant", "missing"})
    void persistedActorInvalidationOrForeignSnapshotReturns401AndRollsBack(String mutation)
            throws Exception {
        Actor actor = seedActor(owner, "USER");
        openHttp(properties(true, false, false));
        // A foreign workshop belongs only to the presented snapshot; persisted membership stays immutable.
        Actor presentedActor = mutation.equals("tenant")
                ? new Actor(actor.userId(), seedActor(owner, "ADMIN").workshopId(), actor.role(), actor.audience())
                : actor;
        MockHttpServletRequestBuilder request = authenticatedGet(presentedActor);
        switch (mutation) {
            case "role" -> owner.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", actor.userId());
            case "token" -> owner.update("UPDATE users SET token_version = 1 WHERE id = ?", actor.userId());
            case "inactive" -> owner.update("UPDATE users SET active = false WHERE id = ?", actor.userId());
            case "workshop" -> owner.update("UPDATE talleres SET activo = false WHERE id = ?", actor.workshopId());
            case "tenant" -> { /* The mismatching snapshot above must fail in the real actor reader. */ }
            case "missing" -> owner.update("DELETE FROM users WHERE id = ?", actor.userId());
            default -> throw new AssertionError(mutation);
        }
        MockHttpServletResponse response = mvc.perform(request).andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("ETag")).andReturn().getResponse();
        assertSanitized(response);
        assertThat(counts(owner)).containsEntry("legal_requisito_agregados", 0L);
        assertThat(metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isZero();
        assertRollback();
        assertThat(TenantContext.getTallerId()).isNull();
    }

    @ParameterizedTest @ValueSource(strings = {"absent", "invalid", "revoked"})
    void unauthenticatedExactGetReturns401WithoutBorrowingTheLegalPool(String token) throws Exception {
        openHttp(properties(true, false, false));
        var request = get(ROOT);
        if (token.equals("invalid")) {
            when(context.getBean(JwtUtils.class).verifyToken("invalid")).thenThrow(new JWTVerificationException("private failure"));
            request.header("Authorization", "Bearer invalid");
        } else if (token.equals("revoked")) {
            Actor actor = seedActor(owner, "USER");
            request = authenticatedGet(actor);
            when(context.getBean(JwtUtils.class).validateToken(org.mockito.ArgumentMatchers.any(DecodedJWT.class),
                    org.mockito.ArgumentMatchers.any(AuthenticatedUserPrincipal.class))).thenReturn(false);
        }
        MockHttpServletResponse response = mvc.perform(request).andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("ETag")).andReturn().getResponse();
        assertSanitized(response);
        assertNoLegalSql();
    }

    @Test void unsupportedAuthenticatedAuthorityReturns403BeforeCallingThePrivateGraph() throws Exception {
        openHttp(properties(true, false, false));
        var auth = new UsernamePasswordAuthenticationToken(principal(seedActor(owner, "USER")), null,
                List.of(new SimpleGrantedAuthority("ROLE_AUDITOR")));
        mvc.perform(get(ROOT).with(authentication(auth))).andExpect(status().isForbidden())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("ETag"));
        assertNoLegalSql();
        verifyNoInteractions(context.getBean(JwtUtils.class), context.getBean(UserDetailsServiceImpl.class));
    }

    @ParameterizedTest @ValueSource(strings = {"contexto", "locale", "perfil", "audiencia", "role", "userId", "tallerId", "unknown"})
    void rejectsEveryQueryParameterBeforeLegalSqlIncludingSelectionAndUnknownInputs(String name) throws Exception {
        Actor actor = seedActor(owner, "USER");
        openHttp(properties(true, false, false));
        mvc.perform(authenticatedGet(actor).param(name, "selector-must-never-echo"))
                .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("ETag"));
        assertNoLegalSql();
    }

    @ParameterizedTest @ValueSource(strings = {"HEAD", "POST", "PUT", "PATCH", "DELETE"})
    void methodsOtherThanExactGetNeverInvokeThePrivateGraph(String method) throws Exception {
        Actor actor = seedActor(owner, "USER");
        openHttp(properties(true, false, false));
        String token = authorize(actor, true);
        mvc.perform(request(HttpMethod.valueOf(method), ROOT).header("Authorization", "Bearer " + token))
                .andExpect(status().is(method.equals("HEAD") ? 405 : 500));
        // Unmapped methods retain the existing global MVC error envelope; HEAD is guarded by this controller.
        assertNoLegalSql();
    }

    @ParameterizedTest @ValueSource(strings = {"/api/requisitos-legales/", "/api/requisitos-legales/extra", "/api/aceptaciones-legales/extra"})
    void neighboringPathsKeepExistingAuthenticationPolicyAndDoNotBorrowThePrivatePool(String path) throws Exception {
        openHttp(properties(true, false, false));
        mvc.perform(get(path)).andExpect(status().isForbidden());
        mvc.perform(get(path).header("Authorization", "Bearer " + authorize(seedActor(owner, "USER"), true)))
                .andExpect(status().isInternalServerError());
        // The pre-existing global advice maps a missing handler to 500; it must never reach legal SQL.
        assertNoLegalSql();
    }

    @Test void anEncodedAliasNeverReachesTheLegalReaderEvenWhenMvcDecodesThePath() throws Exception {
        openHttp(properties(true, false, false));
        mvc.perform(get(URI.create("/api/requisitos%2dlegales"))
                        .header("Authorization", "Bearer " + authorize(seedActor(owner, "USER"), true)))
                .andExpect(status().isNotFound());
        assertNoLegalSql();
    }

    @ParameterizedTest @ValueSource(strings = {"current-digest", "evidence-digest", "evidence-document"})
    void corruptCanonicalOrOwnEvidenceReturnsSanitized503EvenForAConditionalRequest(String corruption) throws Exception {
        Actor actor = seedActor(owner, "USER");
        if (corruption.startsWith("evidence")) acceptAsOwnerFixture(actor);
        openHttp(properties(true, false, false));
        if (corruption.equals("current-digest")) {
            mvc.perform(authenticatedGet(actor)).andExpect(status().isOk());
        }
        var before = counts(owner);
        LegalManifestPersistenceITSupport.withReplicaRole(owner, () -> {
            switch (corruption) {
                case "current-digest" -> owner.update("UPDATE legal_requisito_versiones SET afirmacion_sha256 = ? WHERE requisito_linea_id IN (SELECT id FROM legal_requisito_lineas WHERE contexto = 'USO_CONTINUADO')", "0".repeat(64));
                case "evidence-digest" -> owner.update("UPDATE legal_aceptaciones SET afirmacion_sha256 = ? WHERE user_id = ?", "0".repeat(64), actor.userId());
                case "evidence-document" -> owner.update("DELETE FROM legal_aceptacion_documentos WHERE aceptacion_id IN (SELECT id FROM legal_aceptaciones WHERE user_id = ?)", actor.userId());
                default -> throw new AssertionError(corruption);
            }
        });
        var afterCorruption = counts(owner);
        metrics.reset();
        assertUnavailable(authenticatedGet(actor).header("If-None-Match", "*"));
        assertRollback();
        assertThat(counts(owner)).isEqualTo(afterCorruption);
        assertThat(counts(owner).get("legal_requisito_agregados")).isEqualTo(before.get("legal_requisito_agregados"));
    }

    @Test void readerFailureAfterCreatingANewAudienceAggregateRollsBackTheWholeHttpObservation() throws Exception {
        Actor employee = seedActor(owner, "USER");
        acceptAsOwnerFixture(employee); // Only the USER aggregate exists; history remains tied to its actual actor.
        owner.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", employee.userId());
        Actor ownerActor = new Actor(employee.userId(), employee.workshopId(), "ADMIN", "ADMIN_TITULAR");
        LegalManifestPersistenceITSupport.withReplicaRole(owner, () -> owner.update(
                "UPDATE legal_aceptaciones SET afirmacion_sha256 = ? WHERE user_id = ?",
                "0".repeat(64), employee.userId()));
        var before = counts(owner);
        var original = owner.queryForList("SELECT to_jsonb(t)::text || xmin::text FROM legal_requisito_agregados t");
        openHttp(properties(true, false, false));

        assertUnavailable(authenticatedGet(ownerActor).header("If-None-Match", "*"));

        // The real store inserts ADMIN header + scopes before the real reader rejects the actor's corrupt history.
        assertThat(metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isEqualTo(2);
        assertRollback();
        assertThat(counts(owner)).isEqualTo(before);
        assertThat(owner.queryForList("SELECT to_jsonb(t)::text || xmin::text FROM legal_requisito_agregados t"))
                .isEqualTo(original);
        assertThat(owner.queryForObject("SELECT count(*) FROM legal_requisito_agregados WHERE audiencia = 'ADMIN_TITULAR'",
                Long.class)).isZero();
    }

    @Test void ownerCredentialsFailTheRestrictedRolePreflightWith503BeforeLocksAndDml() throws Exception {
        Actor actor = seedActor(owner, "USER");
        Map<String, Object> properties = properties(true, false, false);
        properties.put(PREFIX + "username", POSTGRES.getUsername());
        properties.put(PREFIX + "password", POSTGRES.getPassword());
        openHttp(properties);
        assertUnavailable(authenticatedGet(actor));
        assertThat(metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isZero();
        assertThat(metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.ADVISORY_LOCK)).isZero();
        assertThat(counts(owner)).containsEntry("legal_requisito_agregados", 0L);
        assertRollback();
    }

    @ParameterizedTest @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void privateRouteRunsIndependentlyOfBothPublicFlags(boolean documents, boolean registration) throws Exception {
        openHttp(properties(true, documents, registration));
        mvc.perform(authenticatedGet(seedActor(owner, "USER"))).andExpect(status().isOk());
        assertThat(requirementsContext.getEnvironment().getProperty(LegalPublicDocumentRequestMatcher.ENABLED_PROPERTY)).isNull();
        assertThat(requirementsContext.getEnvironment().getProperty(LegalPublicRequirementsRequestMatcher.ENABLED_PROPERTY)).isNull();
        assertNoWebDatabaseGraph();
    }

    @ParameterizedTest @ValueSource(strings = {"absent", "false"})
    void privateDefaultOffHasNoRouteFacadeOrPoolEvenWithBothPublicFlagsEnabled(String flag) throws Exception {
        Map<String, Object> properties = properties(false, true, true);
        if (flag.equals("absent")) properties.remove(FLAG);
        properties.put("spring.datasource.url", POSTGRES.getJdbcUrl());
        properties.put("spring.datasource.username", POSTGRES.getUsername());
        properties.put("spring.datasource.password", POSTGRES.getPassword());
        openHttp(properties);
        mvc.perform(get(ROOT)).andExpect(status().isForbidden());
        mvc.perform(authenticatedGet(seedActor(owner, "USER"))).andExpect(status().isInternalServerError());
        assertThat(context.getBeansOfType(LegalPrivateRequirementsController.class)).isEmpty();
        assertThat(context.getBeansOfType(LegalPrivateRequirementsReadService.class)).isEmpty();
        assertThat(context.getBeansOfType(LegalPrivateRequirementsAuthenticationEntryPoint.class)).isEmpty();
        assertThat(requirementsContext).isNull();
        assertNoWebDatabaseGraph();
        assertThat(counts(owner)).containsEntry("legal_requisito_agregados", 0L);
    }

    private void openHttp(Map<String, Object> properties) {
        context = new AnnotationConfigWebApplicationContext();
        try {
            context.setServletContext(new MockServletContext());
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("private-http-it", properties));
            context.register(WebConfiguration.class, LegalPrivateRequirementsHttpConfiguration.class,
                    LegalPrivateRequirementsController.class, LegalPrivateRequirementsExceptionHandler.class,
                    LegalPrivateRequirementsAuthenticationEntryPoint.class, GlobalExceptionHandler.class,
                    SecurityConfig.class, CorsConfig.class, JwtFilter.class, PublicEndpointRateLimitFilter.class,
                    LegalPublicDocumentRequestMatcher.class, LegalPublicRequirementsRequestMatcher.class,
                    RateLimitProperties.class);
            context.refresh();
            if (!context.getBeansOfType(LegalPrivateRequirementsHttpConfiguration.class).isEmpty()) {
                requirementsContext = (AnnotationConfigApplicationContext) ReflectionTestUtils.getField(
                        context.getBean(LegalPrivateRequirementsHttpConfiguration.class), "requirementsContext");
                pool = Objects.requireNonNull(requirementsContext).getBean(HikariDataSource.class);
                metrics = LegalJdbcMetricsSupport.instrument(pool, Duration.ZERO);
                // Observe the real wrapper/JDBC/transaction manager/gate; the production bridge owns the pool.
                ReflectionTestUtils.setField(requirementsContext.getBean(LegalPrivateRequirementsDataSource.class),
                        "pool", metrics.dataSource());
            }
            mvc = webAppContextSetup(context).apply(springSecurity()).build();
        } catch (RuntimeException | Error failure) {
            try { context.close(); }
            catch (RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private static Map<String, Object> properties(boolean enabled, boolean documents, boolean registration) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(FLAG, Boolean.toString(enabled));
        properties.put(LegalPublicDocumentRequestMatcher.ENABLED_PROPERTY, Boolean.toString(documents));
        properties.put(LegalPublicRequirementsRequestMatcher.ENABLED_PROPERTY, Boolean.toString(registration));
        if (enabled) {
            properties.put(PREFIX + "jdbc-url", POSTGRES.getJdbcUrl());
            properties.put(PREFIX + "username", ROLE);
            properties.put(PREFIX + "password", PASSWORD);
        }
        return properties;
    }

    private MockHttpServletRequestBuilder authenticatedGet(Actor actor) {
        return get(ROOT).header("Authorization", "Bearer " + authorize(actor));
    }

    private String authorize(Actor actor) {
        return authorize(actor, false);
    }

    /** Routing probes use verified principals; canonical legal flows keep pending-email coverage. */
    private String authorize(Actor actor, boolean emailVerified) {
        AuthenticatedUserPrincipal principal = principal(actor, emailVerified);
        String token = "private-http-token-" + actor.userId();
        DecodedJWT decoded = mock(DecodedJWT.class);
        when(decoded.getSubject()).thenReturn(principal.getUsername());
        when(context.getBean(JwtUtils.class).verifyToken(token)).thenReturn(decoded);
        when(context.getBean(UserDetailsServiceImpl.class).loadUserByUsername(principal.getUsername())).thenReturn(principal);
        when(context.getBean(JwtUtils.class).validateToken(decoded, principal)).thenReturn(true);
        return token;
    }

    private static AuthenticatedUserPrincipal principal(Actor actor) {
        return principal(actor, false);
    }

    private static AuthenticatedUserPrincipal principal(Actor actor, boolean emailVerified) {
        var workshop = new Taller(); workshop.setId(actor.workshopId());
        var user = User.builder().password("server-principal-test-only").build();
        user.setId(actor.userId()); user.setTaller(workshop);
        user.setEmail("actor-" + actor.userId() + "@ordenfix.test");
        user.setActive(true); user.setRole(UserRole.valueOf(actor.role())); user.setTokenVersion(0L);
        user.setEmailVerificado(emailVerified);
        return new AuthenticatedUserPrincipal(user);
    }

    /** Canonical synthetic evidence is inserted only by the owner of this disposable fixture, never through HTTP. */
    private static void acceptAsOwnerFixture(Actor source) throws Exception {
        requireSafeEphemeralDatabase(owner);
        var actor = new LegalAcceptanceProtocolFeasibilityITSupport.Actor(source.userId(), source.workshopId(), source.role(), source.audience());
        try (var connection = transaction(ownerDataSource)) {
            JdbcTemplate jdbc = jdbc(connection);
            sharedBoundary(jdbc);
            jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(jsonb_build_array('ordenfix:legal-actor:v1', ?::bigint, ?::bigint)::text,0))", actor.workshopId(), actor.userId());
            jdbc.queryForList("SELECT id FROM talleres WHERE id = ? FOR SHARE", actor.workshopId());
            jdbc.queryForList("SELECT id FROM users WHERE id = ? AND taller_id = ? FOR SHARE", actor.userId(), actor.workshopId());
            var aggregate = materialize(jdbc, PerfilAgregadoLegal.AUTHENTICATED_PENDING, AudienciaLegal.valueOf(actor.audience()));
            var lot = insertLot(jdbc, actor, aggregate);
            var acts = insertActs(jdbc, lot);
            insertDocuments(jdbc, acts);
            insertMetadata(jdbc, lot);
            connection.commit();
        }
    }

    private void assertUnavailable(MockHttpServletRequestBuilder request) throws Exception {
        MockHttpServletResponse response = mvc.perform(request).andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("ETag"))
                .andExpect(jsonPath("$.code").value("CONTRATO_LEGAL_NO_DISPONIBLE"))
                .andReturn().getResponse();
        ObjectNode details = JSON.createObjectNode(); details.putNull("contexto"); details.put("locale", "es-AR");
        assertThat(body(response).get("details")).isEqualTo(details);
        assertSanitized(response);
    }

    private static void assertSanitized(MockHttpServletResponse response) throws Exception {
        assertThat(response.getContentAsString()).doesNotContain(ROLE, PASSWORD, POSTGRES.getJdbcUrl(),
                "SELECT", "legal_requisito_versiones", "private failure", "server-principal", "stackTrace");
    }

    private void assertReusedCommit() {
        assertThat(metrics.snapshot().commits()).isEqualTo(1);
        assertThat(metrics.snapshot().rollbacks()).isZero();
        assertThat(metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isZero();
        assertPoolIdle();
    }

    private void assertRollback() {
        assertThat(metrics.snapshot().commits()).isZero();
        assertThat(metrics.snapshot().rollbacks()).isEqualTo(1);
        assertPoolIdle();
    }

    private void assertNoLegalSql() {
        for (var category : LegalJdbcMetricsSupport.Category.values()) {
            assertThat(metrics.snapshot().executions(category)).as("private SQL %s", category).isZero();
        }
        assertThat(metrics.snapshot().commits()).isZero();
        assertThat(metrics.snapshot().rollbacks()).isZero();
        assertThat(counts(owner)).containsEntry("legal_requisito_agregados", 0L);
    }

    private void assertPoolIdle() { assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero(); }

    private void assertNoWebDatabaseGraph() {
        assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
        assertThat(context.getBeansOfType(JdbcTemplate.class)).isEmpty();
        assertThat(context.getBeansOfType(PlatformTransactionManager.class)).isEmpty();
        assertThat(context.getBeansOfType(LegalRequiredSetAggregateStore.class)).isEmpty();
        assertThat(context.getBeansOfType(LegalPrivateRequirementsReader.class)).isEmpty();
    }

    private static JsonNode expectedWire(LegalAuthenticatedRequirements canonical) {
        ObjectNode result = JSON.createObjectNode();
        result.put("locale", canonical.snapshot().applicableScopes().locale().getCodigo());
        result.put("requiredSetRevision", canonical.requiredSetRevision());
        var requirements = result.putArray("requisitos");
        canonical.requirements().forEach(requirement -> {
            ObjectNode output = requirements.addObject();
            output.put("id", requirement.versionId().toString());
            output.put("contexto", requirement.context().name());
            output.put("tipoActo", requirement.actType().name());
            output.put("afirmacion", requirement.statement());
            output.put("afirmacionSha256", requirement.statementSha256());
            output.put("requerido", requirement.required());
            var documents = output.putArray("documentos");
            requirement.documents().forEach(document -> {
                ObjectNode item = documents.addObject();
                item.put("id", document.versionId().toString());
                item.put("tipo", document.type().name());
                item.put("version", document.version());
                item.put("titulo", document.title());
                item.put("contenidoMarkdown", document.markdown());
                item.put("sha256", document.sha256());
                item.put("vigenteDesde", document.effectiveAt().toInstant().toString());
                item.put("estado", "VIGENTE");
                item.put("locale", document.locale().getCodigo());
            });
        });
        return result;
    }

    private static JsonNode body(MockHttpServletResponse response) throws Exception {
        return JSON.readTree(response.getContentAsByteArray());
    }

    @TestConfiguration(proxyBeanMethods = false) @EnableWebMvc
    static class WebConfiguration {
        @Bean Clock clock() { return Clock.systemUTC(); }
        @Bean JwtUtils jwtUtils() { return mock(JwtUtils.class); }
        @Bean UserDetailsServiceImpl users() { return mock(UserDetailsServiceImpl.class); }
    }
}
