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
import com.leonardorozza.mvgrreparacionesbackend.legal.http.LegalAcceptanceHistoryController;
import com.leonardorozza.mvgrreparacionesbackend.legal.http.LegalAcceptanceHistoryExceptionHandler;
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
import java.time.OffsetDateTime;
import java.util.UUID;
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

/** Private own-history HTTP with the real security chain and restricted PostgreSQL reader; only JWT/users are mocked. */
class LegalAcceptanceHistoryHttpIT {
    private static final String ROOT = "/api/aceptaciones-legales";
    private static final String ROLE = "ordenfix_legal_private_requirements_history_http";
    private static final String PASSWORD = "acceptance-history-http-test-only";
    private static final String PREFIX = LegalPrivateRequirementsDatabaseConfiguration.PROPERTY_PREFIX;
    private static final String FLAG = LegalPrivateRequirementsDatabaseConfiguration.ENABLED_PROPERTY;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_private_requirements_history_http")
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
    void ownActualEvidenceHasExactHistoricalWireAndUsesTheRestrictedGraphWithoutDml(String role) throws Exception {
        Actor actor = seedActor(owner, role);
        acceptAsOwnerFixture(actor);
        openHttp(properties(true, false, false));
        var before = counts(owner);
        MockHttpServletResponse response = mvc.perform(authenticatedGet(actor)
                        .header("X-Taller-Id", Long.toString(actor.workshopId() + 900))
                        .header("X-User-Id", Long.toString(actor.userId() + 900)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(header().doesNotExist("ETag"))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andReturn().getResponse();

        assertThat(body(response)).isEqualTo(expectedWire(actor));
        assertReadOnlyCommit();
        assertThat(counts(owner)).isEqualTo(before);
        assertThat(response.getContentAsString()).doesNotContain("userId", "tallerId", "audiencia", "perfil",
                "scopeRevision", "scopeOrdinal", "provenance", "requiresReacceptance", "requiredSetRevision",
                "metadata", "userAgent", "contenidoMarkdown", "heredado", "estado");
        assertThat(requirementsContext.getParent()).isNull();
        assertThat(new JdbcTemplate(pool).queryForObject("SELECT current_user || ':' || session_user", String.class))
                .isEqualTo(ROLE + ":" + ROLE);
        assertNoWebDatabaseGraph(); assertPoolIdle();
        assertThat(TenantContext.getTallerId()).isNull();
        verify(context.getBean(UserDetailsServiceImpl.class)).loadUserByUsername(principal(actor).getUsername());
    }

    @Test void firstReadOfAnActorWithoutEvidenceReturnsAnEmptyPageWithoutMaterializingAnyAggregate() throws Exception {
        Actor actor = seedActor(owner, "USER");
        openHttp(properties(true, false, false));
        var before = counts(owner);
        mvc.perform(authenticatedGet(actor)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(header().doesNotExist("ETag"))
                .andExpect(jsonPath("$.content").isEmpty()).andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.size").value(20)).andExpect(jsonPath("$.page.totalElements").value(0))
                .andExpect(jsonPath("$.page.totalPages").value(0));
        assertReadOnlyCommit(); assertThat(counts(owner)).isEqualTo(before);
        assertThat(counts(owner)).containsEntry("legal_requisito_agregados", 0L);
    }

    @Test void pagesHaveStableTimestampUuidOrderAndTotalsForFiltersAndLargeOffsetsWithoutWrites() throws Exception {
        Actor actor = seedActor(owner, "ADMIN"); acceptAsOwnerFixture(actor);
        openHttp(properties(true, false, false));
        var before = counts(owner);
        JsonNode complete = expectedWire(actor).get("content");
        for (int page = 0; page < 3; page++) {
            metrics.reset();
            JsonNode wire = body(mvc.perform(authenticatedGet(actor).queryParam("page", Integer.toString(page))
                            .queryParam("size", "1").queryParam("contexto", "USO_CONTINUADO"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.page.number").value(page))
                    .andExpect(jsonPath("$.page.totalElements").value(2)).andExpect(jsonPath("$.page.totalPages").value(2))
                    .andReturn().getResponse());
            assertThat(wire.path("content").size()).isEqualTo(page < 2 ? 1 : 0);
            if (page < 2) assertThat(wire.path("content").get(0)).isEqualTo(complete.get(page));
            assertReadOnlyCommit();
        }
        metrics.reset();
        mvc.perform(authenticatedGet(actor).queryParam("contexto", "CIERRE_CUENTA"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.page.totalElements").value(0));
        assertReadOnlyCommit(); metrics.reset();
        mvc.perform(authenticatedGet(actor).queryParam("page", Integer.toString(Integer.MAX_VALUE)).queryParam("size", "100"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.page.number").value(Integer.MAX_VALUE))
                .andExpect(jsonPath("$.page.totalElements").value(2)).andExpect(jsonPath("$.page.totalPages").value(1));
        assertReadOnlyCommit(); assertThat(counts(owner)).isEqualTo(before);
    }

    @Test void anotherUserInTheSameWorkshopAndAnotherTenantAreNeverIncludedInOwnHistory() throws Exception {
        Actor actor = seedActor(owner, "USER");
        Actor colleague = seedActor(owner, "USER", actor.workshopId());
        Actor foreign = seedActor(owner, "ADMIN");
        acceptAsOwnerFixture(colleague); acceptAsOwnerFixture(foreign);
        openHttp(properties(true, false, false));
        var before = counts(owner);
        mvc.perform(authenticatedGet(actor)).andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.page.totalElements").value(0));
        assertReadOnlyCommit(); assertThat(counts(owner)).isEqualTo(before);
        acceptAsOwnerFixture(actor);
        before = counts(owner); metrics.reset();
        JsonNode wire = body(mvc.perform(authenticatedGet(actor)).andExpect(status().isOk()).andReturn().getResponse());
        assertThat(wire).isEqualTo(expectedWire(actor));
        assertThat(wire.path("page").path("totalElements").longValue()).isEqualTo(2);
        assertReadOnlyCommit(); assertThat(counts(owner)).isEqualTo(before);
    }

    @Test void changingCurrentRolePreservesTheActualHistoricalActWithoutCreatingAudienceAggregates() throws Exception {
        Actor employee = seedActor(owner, "USER"); acceptAsOwnerFixture(employee);
        JsonNode original = expectedWire(employee);
        owner.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", employee.userId());
        Actor changed = new Actor(employee.userId(), employee.workshopId(), "ADMIN", "ADMIN_TITULAR");
        var before = counts(owner); openHttp(properties(true, false, false));
        JsonNode wire = body(mvc.perform(authenticatedGet(changed)).andExpect(status().isOk()).andReturn().getResponse());
        assertThat(wire).isEqualTo(original); assertReadOnlyCommit(); assertThat(counts(owner)).isEqualTo(before);
        assertThat(owner.queryForObject("SELECT count(*) FROM legal_requisito_agregados WHERE audiencia = 'ADMIN_TITULAR'", Long.class)).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"*", "\"sha256:old\"", "W/\"sha256:old\"", "malformed"})
    void conditionalRequestsAlwaysReadActualEvidenceWithoutEtagOrNewDml(String validator) throws Exception {
        Actor actor = seedActor(owner, "USER"); acceptAsOwnerFixture(actor);
        var before = counts(owner); openHttp(properties(true, false, false));
        JsonNode wire = body(mvc.perform(authenticatedGet(actor).header("If-None-Match", validator).header("If-Match", "\"old\""))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(header().doesNotExist("ETag")).andReturn().getResponse());
        assertThat(wire).isEqualTo(expectedWire(actor)); assertReadOnlyCommit(); assertThat(counts(owner)).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"absent", "invalid", "revoked"})
    void unauthenticatedExactGetHasScoped401WithoutBorrowingTheLegalPool(String token) throws Exception {
        openHttp(properties(true, false, false)); var request = get(ROOT);
        if (token.equals("invalid")) {
            when(context.getBean(JwtUtils.class).verifyToken("invalid")).thenThrow(new JWTVerificationException("private failure"));
            request.header("Authorization", "Bearer invalid");
        } else if (token.equals("revoked")) {
            request = authenticatedGet(seedActor(owner, "USER"));
            when(context.getBean(JwtUtils.class).validateToken(org.mockito.ArgumentMatchers.any(DecodedJWT.class),
                    org.mockito.ArgumentMatchers.any(AuthenticatedUserPrincipal.class))).thenReturn(false);
        }
        MockHttpServletResponse response = mvc.perform(request).andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(header().doesNotExist("ETag"))
                .andReturn().getResponse();
        assertSanitized(response); assertNoLegalSql();
    }

    @Test void unsupportedAuthorityGets403BeforeAnyLegalSql() throws Exception {
        openHttp(properties(true, false, false));
        var auth = new UsernamePasswordAuthenticationToken(principal(seedActor(owner, "USER")), null,
                List.of(new SimpleGrantedAuthority("ROLE_AUDITOR")));
        mvc.perform(get(ROOT).with(authentication(auth))).andExpect(status().isForbidden())
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(header().doesNotExist("ETag"));
        assertNoLegalSql(); verifyNoInteractions(context.getBean(JwtUtils.class), context.getBean(UserDetailsServiceImpl.class));
    }

    @ParameterizedTest @ValueSource(strings = {"role", "token", "inactive", "workshop", "tenant", "missing"})
    void invalidOrStaleActorSnapshotReturns401AndRollsBackWithoutReadingAnotherIdentity(String mutation) throws Exception {
        Actor actor = seedActor(owner, "USER"); openHttp(properties(true, false, false));
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
        var before = counts(owner);
        MockHttpServletResponse response = mvc.perform(request).andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(header().doesNotExist("ETag")).andReturn().getResponse();
        assertSanitized(response); assertRollback(); assertThat(counts(owner)).isEqualTo(before);
        assertThat(metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isZero();
        assertThat(TenantContext.getTallerId()).isNull();
    }

    @ParameterizedTest @ValueSource(strings = {"locale", "perfil", "audiencia", "role", "userId", "tallerId", "unknown"})
    void noUnsupportedInputCanSelectEvidenceOrBorrowThePool(String parameter) throws Exception {
        openHttp(properties(true, false, false));
        mvc.perform(authenticatedGet(seedActor(owner, "USER")).queryParam(parameter, "selector-must-not-echo"))
                .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control", "no-store"));
        assertNoLegalSql();
    }

    @ParameterizedTest @CsvSource({"page,-1", "page,2147483648", "size,0", "size,101", "contexto,unsupported"})
    void invalidFiltersAndPaginationFailBeforeLegalSql(String name, String value) throws Exception {
        openHttp(properties(true, false, false));
        var response = mvc.perform(authenticatedGet(seedActor(owner, "USER")).queryParam(name, value))
                .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse();
        if (name.equals("contexto")) assertThat(body(response).path("code").textValue()).isEqualTo("CONTEXTO_LEGAL_NO_SOPORTADO");
        assertNoLegalSql();
    }

    @ParameterizedTest @ValueSource(strings = {"HEAD", "POST", "PUT", "PATCH", "DELETE"})
    void methodsOtherThanGetNeverReachTheHistoryReader(String method) throws Exception {
        openHttp(properties(true, false, false));
        mvc.perform(request(HttpMethod.valueOf(method), ROOT).header("Authorization", "Bearer " + authorize(seedActor(owner, "USER"))))
                .andExpect(status().is(method.equals("HEAD") ? 405 : 500));
        assertNoLegalSql();
    }

    @ParameterizedTest @ValueSource(strings = {"/api/aceptaciones-legales/", "/api/aceptaciones-legales/extra", "/api/aceptaciones-legales-extra"})
    void neighborsKeepExistingAuthenticationAndUnmappedBehaviorWithoutSql(String path) throws Exception {
        openHttp(properties(true, false, false));
        mvc.perform(get(path)).andExpect(status().isForbidden());
        mvc.perform(get(path).header("Authorization", "Bearer " + authorize(seedActor(owner, "USER"))))
                .andExpect(status().isInternalServerError());
        assertNoLegalSql();
    }

    @Test void encodedAliasDoesNotReadEvenWhenMvcDecodesItsPath() throws Exception {
        openHttp(properties(true, false, false));
        mvc.perform(get(URI.create("/api/aceptaciones%2dlegales"))
                        .header("Authorization", "Bearer " + authorize(seedActor(owner, "USER"))))
                .andExpect(status().isNotFound()); assertNoLegalSql();
    }

    @ParameterizedTest @ValueSource(strings = {"evidence-digest", "evidence-document"})
    void corruptOwnEvidenceReturnsSanitized503AndRollsBackWithoutWrites(String corruption) throws Exception {
        Actor actor = seedActor(owner, "USER"); acceptAsOwnerFixture(actor);
        LegalManifestPersistenceITSupport.withReplicaRole(owner, () -> {
            if (corruption.equals("evidence-digest")) owner.update("UPDATE legal_aceptaciones SET afirmacion_sha256 = ? WHERE user_id = ?", "0".repeat(64), actor.userId());
            else owner.update("DELETE FROM legal_aceptacion_documentos WHERE aceptacion_id IN (SELECT id FROM legal_aceptaciones WHERE user_id = ?)", actor.userId());
        });
        var before = counts(owner); openHttp(properties(true, false, false));
        assertUnavailable(authenticatedGet(actor).header("If-None-Match", "*"));
        assertRollback(); assertThat(metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isZero();
        assertThat(counts(owner)).isEqualTo(before);
    }

    @Test void ownerCredentialsFailRestrictedPreflightBeforeLocksAndDml() throws Exception {
        Actor actor = seedActor(owner, "USER"); Map<String, Object> properties = properties(true, false, false);
        properties.put(PREFIX + "username", POSTGRES.getUsername()); properties.put(PREFIX + "password", POSTGRES.getPassword());
        openHttp(properties); assertUnavailable(authenticatedGet(actor));
        assertThat(metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isZero();
        assertThat(metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.ADVISORY_LOCK)).isZero(); assertRollback();
    }

    @ParameterizedTest @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void historyRunsIndependentlyOfBothPublicFlags(boolean documents, boolean registration) throws Exception {
        openHttp(properties(true, documents, registration));
        mvc.perform(authenticatedGet(seedActor(owner, "USER"))).andExpect(status().isOk()); assertReadOnlyCommit();
        assertThat(requirementsContext.getEnvironment().getProperty(LegalPublicDocumentRequestMatcher.ENABLED_PROPERTY)).isNull();
        assertThat(requirementsContext.getEnvironment().getProperty(LegalPublicRequirementsRequestMatcher.ENABLED_PROPERTY)).isNull();
        assertNoWebDatabaseGraph();
    }

    @ParameterizedTest @ValueSource(strings = {"absent", "false"})
    void defaultOffHasNoHistoryRouteFacadeOrPoolEvenWithPublicFlagsOn(String flag) throws Exception {
        Map<String, Object> properties = properties(false, true, true);
        if (flag.equals("absent")) properties.remove(FLAG);
        properties.put("spring.datasource.url", POSTGRES.getJdbcUrl());
        properties.put("spring.datasource.username", POSTGRES.getUsername()); properties.put("spring.datasource.password", POSTGRES.getPassword());
        openHttp(properties);
        mvc.perform(get(ROOT)).andExpect(status().isForbidden());
        mvc.perform(authenticatedGet(seedActor(owner, "USER"))).andExpect(status().isInternalServerError());
        assertThat(context.getBeansOfType(LegalAcceptanceHistoryController.class)).isEmpty();
        assertThat(context.getBeansOfType(LegalAcceptanceHistoryService.class)).isEmpty();
        assertThat(context.getBeansOfType(LegalPrivateRequirementsReadService.class)).isEmpty();
        assertThat(context.getBeansOfType(LegalPrivateRequirementsAuthenticationEntryPoint.class)).isEmpty();
        assertThat(requirementsContext).isNull(); assertNoWebDatabaseGraph();
        assertThat(counts(owner)).containsEntry("legal_requisito_agregados", 0L);
    }

    private void openHttp(Map<String, Object> properties) {
        context = new AnnotationConfigWebApplicationContext();
        try {
            context.setServletContext(new MockServletContext());
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("private-http-it", properties));
            context.register(WebConfiguration.class, LegalPrivateRequirementsHttpConfiguration.class,
                    LegalPrivateRequirementsController.class, LegalPrivateRequirementsExceptionHandler.class,
                    LegalAcceptanceHistoryController.class, LegalAcceptanceHistoryExceptionHandler.class,
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
        AuthenticatedUserPrincipal principal = principal(actor);
        String token = "private-http-token-" + actor.userId();
        DecodedJWT decoded = mock(DecodedJWT.class);
        when(decoded.getSubject()).thenReturn(principal.getUsername());
        when(context.getBean(JwtUtils.class).verifyToken(token)).thenReturn(decoded);
        when(context.getBean(UserDetailsServiceImpl.class).loadUserByUsername(principal.getUsername())).thenReturn(principal);
        when(context.getBean(JwtUtils.class).validateToken(decoded, principal)).thenReturn(true);
        return token;
    }

    private static AuthenticatedUserPrincipal principal(Actor actor) {
        var workshop = new Taller(); workshop.setId(actor.workshopId());
        var user = User.builder().password("server-principal-test-only").build();
        user.setId(actor.userId()); user.setTaller(workshop);
        user.setEmail("actor-" + actor.userId() + "@ordenfix.test");
        user.setActive(true); user.setRole(UserRole.valueOf(actor.role())); user.setTokenVersion(0L);
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

    private void assertReadOnlyCommit() {
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
    }

    private void assertPoolIdle() { assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero(); }

    private void assertNoWebDatabaseGraph() {
        assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
        assertThat(context.getBeansOfType(JdbcTemplate.class)).isEmpty();
        assertThat(context.getBeansOfType(PlatformTransactionManager.class)).isEmpty();
        assertThat(context.getBeansOfType(LegalRequiredSetAggregateStore.class)).isEmpty();
        assertThat(context.getBeansOfType(LegalPrivateRequirementsReader.class)).isEmpty();
    }

    /** Independent expected wire is built only from the persisted evidence snapshots, never current versions. */
    private static JsonNode expectedWire(Actor actor) {
        ObjectNode result = JSON.createObjectNode();
        var content = result.putArray("content");
        owner.query("""
                SELECT act.id, act.requisito_version_id, act.contexto, act.tipo_acto,
                       act.afirmacion, act.afirmacion_sha256, lot.aceptado_en
                  FROM legal_aceptaciones act
                  JOIN legal_aceptacion_lotes lot ON lot.id = act.lote_id
                 WHERE act.user_id = ? AND act.taller_id = ?
                 ORDER BY lot.aceptado_en DESC, act.id DESC
                """, row -> {
            UUID id = row.getObject("id", UUID.class);
            ObjectNode output = content.addObject();
            output.put("id", id.toString());
            output.put("requisitoVersionId", row.getObject("requisito_version_id", UUID.class).toString());
            output.put("contexto", row.getString("contexto")); output.put("tipoActo", row.getString("tipo_acto"));
            output.put("afirmacion", row.getString("afirmacion")); output.put("afirmacionSha256", row.getString("afirmacion_sha256"));
            output.put("aceptadoEn", row.getObject("aceptado_en", OffsetDateTime.class).toInstant().toString());
            var documents = output.putArray("documentos");
            owner.query("""
                    SELECT documento_version_id, tipo, version, titulo, sha256
                      FROM legal_aceptacion_documentos WHERE aceptacion_id = ?
                     ORDER BY documento_ordinal
                    """, document -> {
                ObjectNode item = documents.addObject();
                item.put("documentoVersionId", document.getObject("documento_version_id", UUID.class).toString());
                item.put("tipo", document.getString("tipo")); item.put("version", document.getString("version"));
                item.put("titulo", document.getString("titulo")); item.put("sha256", document.getString("sha256"));
            }, id);
        }, actor.userId(), actor.workshopId());
        ObjectNode page = result.putObject("page");
        page.put("size", 20); page.put("number", 0); page.put("totalElements", content.size());
        page.put("totalPages", content.isEmpty() ? 0 : (content.size() + 19) / 20);
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
