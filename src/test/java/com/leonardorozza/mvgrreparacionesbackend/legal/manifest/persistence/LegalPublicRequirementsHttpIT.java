package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leonardorozza.mvgrreparacionesbackend.config.CorsConfig;
import com.leonardorozza.mvgrreparacionesbackend.config.SecurityConfig;
import com.leonardorozza.mvgrreparacionesbackend.config.filter.JwtFilter;
import com.leonardorozza.mvgrreparacionesbackend.config.filter.PublicEndpointRateLimitFilter;
import com.leonardorozza.mvgrreparacionesbackend.config.security.LegalPublicDocumentRequestMatcher;
import com.leonardorozza.mvgrreparacionesbackend.config.security.LegalPublicRequirementsRequestMatcher;
import com.leonardorozza.mvgrreparacionesbackend.config.security.RateLimitProperties;
import com.leonardorozza.mvgrreparacionesbackend.config.tenant.TenantContext;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.GlobalExceptionHandler;
import com.leonardorozza.mvgrreparacionesbackend.legal.http.LegalPublicDocumentController;
import com.leonardorozza.mvgrreparacionesbackend.legal.http.LegalPublicDocumentExceptionHandler;
import com.leonardorozza.mvgrreparacionesbackend.legal.http.LegalPublicDocumentHttpConfiguration;
import com.leonardorozza.mvgrreparacionesbackend.legal.http.LegalPublicRequirementsController;
import com.leonardorozza.mvgrreparacionesbackend.legal.http.LegalPublicRequirementsExceptionHandler;
import com.leonardorozza.mvgrreparacionesbackend.legal.http.LegalPublicRequirementsHttpConfiguration;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalPublicRequirementsValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicRequirementsReadITSupport.Seed;
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
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicRequirementsReadITSupport.aggregateCounts;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicRequirementsReadITSupport.clearCatalog;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicRequirementsReadITSupport.seedRegistration;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicRequirementsReadITSupport.seedRegistrationWithOptional;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicRequirementsReadITSupport.sha256;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/** The production HTTP bridges, security and PostgreSQL graphs; only JWT/user collaborators are mocked. */
class LegalPublicRequirementsHttpIT {

    private static final String ROOT = "/api/public/requisitos-legales";
    private static final String DOCUMENT_ROOT = "/api/public/documentos-legales";
    private static final String REVALIDATE = "public, max-age=0, must-revalidate";
    private static final String ROLE = "ordenfix_legal_public_requirements_http_it";
    private static final String PASSWORD = "legal-public-requirements-http-test-only";
    private static final String DOCUMENT_ROLE = "ordenfix_legal_public_document_14d_http_it";
    private static final String DOCUMENT_PASSWORD = "legal-public-document-14d-http-test-only";
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_public_requirements_http")
            .withUsername("ordenfix").withPassword("ordenfix");
    // Each fixture deliberately preserves its own ephemeral-database prefix and restricted role.
    private static final PostgreSQLContainer DOCUMENT_POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_public_document_14d_http")
            .withUsername("ordenfix").withPassword("ordenfix");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static JdbcTemplate owner;
    private static JdbcTemplate documentOwner;

    @TempDir
    private Path directory;
    private AnnotationConfigWebApplicationContext context;
    private AnnotationConfigApplicationContext requirementsContext;
    private AnnotationConfigApplicationContext documentsContext;
    private HikariDataSource requirementsPool;
    private HikariDataSource documentsPool;
    private LegalJdbcMetricsSupport metrics;
    private MockMvc mvc;

    @BeforeAll
    static void provisionEachConsumerInItsOwnEphemeralDatabase() {
        POSTGRES.start();
        DOCUMENT_POSTGRES.start();
        LegalManifestPersistenceITSupport.migrateLatest(POSTGRES);
        LegalManifestPersistenceITSupport.migrateLatest(DOCUMENT_POSTGRES);
        owner = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        documentOwner = new JdbcTemplate(new DriverManagerDataSource(
                DOCUMENT_POSTGRES.getJdbcUrl(), DOCUMENT_POSTGRES.getUsername(), DOCUMENT_POSTGRES.getPassword()));
        new LegalRestrictedPublicRequirementsRoleFixture(owner, POSTGRES.getJdbcUrl(), ROLE, PASSWORD,
                POSTGRES.getDriverClassName()).provisionAndVerify();
        new LegalRestrictedPublicDocumentRoleFixture(documentOwner, DOCUMENT_POSTGRES.getJdbcUrl(), DOCUMENT_ROLE,
                DOCUMENT_PASSWORD, DOCUMENT_POSTGRES.getDriverClassName()).provisionAndVerify();
    }

    @BeforeEach
    void clearOnlyDedicatedFixtureDatabases() {
        clearCatalog(owner);
        LegalPublicDocumentReadITSupport.clearCatalog(documentOwner);
    }

    @AfterEach
    void closeTheWebOwnerAndBothOwnedGraphs() {
        try {
            if (context != null) {
                context.close();
            }
        } finally {
            SecurityContextHolder.clearContext();
            TenantContext.clear();
        }
        if (requirementsContext != null) {
            assertThat(requirementsContext.isActive()).isFalse();
            assertThat(requirementsPool.isClosed()).isTrue();
        }
        if (documentsContext != null) {
            assertThat(documentsContext.isActive()).isFalse();
            assertThat(documentsPool.isClosed()).isTrue();
        }
    }

    @AfterAll
    static void stopBothContainers() {
        try {
            POSTGRES.stop();
        } finally {
            DOCUMENT_POSTGRES.stop();
        }
    }

    @Test
    void exposesTheCompleteCanonicalWireWithOnlyTheDurableAggregateToken() throws Exception {
        Seed seed = seedRegistrationWithOptional(owner, directory, "requirements-http-wire");
        openHttp(true, false);
        var editorialCounts = LegalManifestPersistenceITSupport.editorialTableCounts(owner);
        var sequenceStates = LegalManifestPersistenceITSupport.editorialSequenceStates(owner);

        MockHttpServletResponse response = mvc.perform(registrationRequest()
                        .header("Authorization", "Bearer invalid-public-token"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", REVALIDATE))
                .andExpect(header().string("X-RateLimit-Limit", "60"))
                .andExpect(jsonPath("$.requisitos.length()").value(2))
                .andExpect(jsonPath("$.requisitos[1].requerido").value(false))
                .andReturn().getResponse();

        assertThat(body(response)).isEqualTo(expectedWire(seed));
        var validated = new LegalPublicRequirementsValidator().validate(seed.projection());
        assertThat(response.getHeader("ETag")).isEqualTo("W/\"" + validated.requiredSetRevision() + "\"");
        assertThat(validated.requiredSetRevision()).isNotEqualTo(validated.scopeRevision());
        assertAggregateCounts(1);
        assertThat(metrics.snapshot().commits()).isEqualTo(1);
        assertThat(metrics.snapshot().rollbacks()).isZero();
        assertThat(metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isEqualTo(2);
        assertThat(metrics.snapshot().advisoryLockExecutions(LegalJdbcMetricsSupport.AdvisoryLockMode.SHARED)).isEqualTo(1);
        assertNoWebDatabaseGraph();
        assertThat(requirementsContext.getParent()).isNull();
        assertThat(new JdbcTemplate(requirementsPool).queryForObject("SELECT current_user || ':' || session_user", String.class))
                .isEqualTo(ROLE + ":" + ROLE);
        assertThat(LegalManifestPersistenceITSupport.editorialTableCounts(owner)).isEqualTo(editorialCounts);
        assertThat(LegalManifestPersistenceITSupport.editorialSequenceStates(owner)).isEqualTo(sequenceStates);
        verifyNoInteractions(context.getBean(JwtUtils.class), context.getBean(UserDetailsServiceImpl.class));
    }

    @Test
    void weakStrongListAndWildcardValidatorsEachRevalidateTheWholeReusedGraphBefore304() throws Exception {
        Seed seed = seedRegistrationWithOptional(owner, directory, "requirements-http-conditionals");
        openHttp(true, false);
        String etag = mvc.perform(registrationRequest()).andExpect(status().isOk())
                .andReturn().getResponse().getHeader("ETag");
        var headers = owner.queryForList("SELECT * FROM public.legal_requisito_agregados");
        var scopes = owner.queryForList("SELECT * FROM public.legal_requisito_agregado_scopes");
        for (String validator : List.of(Objects.requireNonNull(etag), etag.substring(2), "\"other\", " + etag, "*")) {
            metrics.reset();
            mvc.perform(registrationRequest().header("If-None-Match", validator))
                    .andExpect(status().isNotModified()).andExpect(content().string(""))
                    .andExpect(header().string("ETag", etag))
                    .andExpect(header().string("Cache-Control", REVALIDATE))
                    .andExpect(header().exists("X-RateLimit-Remaining"));
            assertReusedCommit();
            // Measured rows include the last optional statement and all three distinct shared documents.
            assertThat(metrics.snapshot().rowsReadContaining("ELSE NULL END AS text_utf8", "v.afirmacion")).isEqualTo(2);
            assertThat(metrics.snapshot().rowsReadContaining("ELSE NULL END AS text_utf8", "v.contenido_markdown")).isEqualTo(3);
        }
        assertThat(owner.queryForList("SELECT * FROM public.legal_requisito_agregados")).isEqualTo(headers);
        assertThat(owner.queryForList("SELECT * FROM public.legal_requisito_agregado_scopes")).isEqualTo(scopes);
        assertAggregateCounts(1);
        assertThat(body(mvc.perform(registrationRequest()).andExpect(status().isOk()).andReturn().getResponse()))
                .isEqualTo(expectedWire(seed));
    }

    @Test
    void realEditorialReplacementReturnsTheNewCompleteContractInsteadOf304ForTheOldTag() throws Exception {
        Seed initial = seedRegistration(owner, directory, "requirements-http-replace-source");
        openHttp(true, false);
        String previous = mvc.perform(registrationRequest()).andExpect(status().isOk())
                .andReturn().getResponse().getHeader("ETag");
        Seed replaced = LegalPublicRequirementsReadITSupport.replaceDocument(owner, directory, initial,
                "terminos", "2.0.0", "requirements-http-replace-target");
        metrics.reset();

        MockHttpServletResponse changed = mvc.perform(registrationRequest().header("If-None-Match", previous))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", REVALIDATE))
                .andReturn().getResponse();

        assertThat(body(changed)).isEqualTo(expectedWire(replaced));
        assertThat(changed.getHeader("ETag")).isNotEqualTo(previous);
        assertAggregateCounts(2);
        assertThat(metrics.snapshot().commits()).isEqualTo(1);
        assertThat(metrics.snapshot().rollbacks()).isZero();
        assertThat(metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isEqualTo(2);
        metrics.reset();
        mvc.perform(registrationRequest().header("If-None-Match", changed.getHeader("ETag")))
                .andExpect(status().isNotModified()).andExpect(content().string(""));
        assertReusedCommit();
    }

    @ParameterizedTest
    @ValueSource(strings = {"previous", "wildcard"})
    void alteredLastOptionalStatementMakesTheWholeReusedRepresentation503DespiteTheClientValidator(String conditional)
            throws Exception {
        Seed seed = seedRegistrationWithOptional(owner, directory, "requirements-http-corrupt-" + conditional);
        openHttp(true, false);
        String etag = mvc.perform(registrationRequest()).andExpect(status().isOk())
                .andReturn().getResponse().getHeader("ETag");
        var headers = owner.queryForList("SELECT * FROM public.legal_requisito_agregados");
        var scopes = owner.queryForList("SELECT * FROM public.legal_requisito_agregado_scopes");
        var optional = seed.projection().requirements().getLast();
        String changed = optional.statement() + " Alteración controlada.";
        // Only the owner of this disposable fixture bypasses the append-only guards for corruption.
        LegalManifestPersistenceITSupport.withReplicaRole(owner, () -> owner.update("""
                UPDATE public.legal_requisito_versiones SET afirmacion = ?, afirmacion_sha256 = ? WHERE id = ?
                """, changed, sha256(changed), optional.versionId()));
        metrics.reset();

        assertUnavailable(registrationRequest().header("If-None-Match", conditional.equals("previous") ? etag : "*"));

        assertThat(metrics.snapshot().commits()).isZero();
        assertThat(metrics.snapshot().rollbacks()).isEqualTo(1);
        assertThat(metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isZero();
        assertThat(metrics.snapshot().rowsReadContaining("ELSE NULL END AS text_utf8", "v.afirmacion")).isEqualTo(2);
        assertThat(owner.queryForList("SELECT * FROM public.legal_requisito_agregados")).isEqualTo(headers);
        assertThat(owner.queryForList("SELECT * FROM public.legal_requisito_agregado_scopes")).isEqualTo(scopes);
        assertPoolIdle();
    }

    @Test
    void invalidOptionalContentBeforeTheFirstGetRollsBackBothNewAggregateRows() throws Exception {
        Seed seed = seedRegistrationWithOptional(owner, directory, "requirements-http-new-corrupt");
        var optional = seed.projection().requirements().getLast();
        LegalManifestPersistenceITSupport.withReplicaRole(owner, () -> owner.update("""
                UPDATE public.legal_requisito_versiones SET afirmacion_sha256 = ? WHERE id = ?
                """, "0".repeat(64), optional.versionId()));
        openHttp(true, false);

        assertUnavailable(registrationRequest().header("If-None-Match", "*"));

        assertAggregateCounts(0);
        assertThat(metrics.snapshot().commits()).isZero();
        assertThat(metrics.snapshot().rollbacks()).isEqualTo(1);
        assertThat(metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isEqualTo(2);
        assertPoolIdle();
    }

    @Test
    void absentRegistrationIs503EvenWithAWildcard() throws Exception {
        openHttp(true, false);
        assertUnavailable(registrationRequest().header("If-None-Match", "*"));
        assertAggregateCounts(0);
        assertThat(metrics.snapshot().rollbacks()).isEqualTo(1);
        assertThat(metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isZero();
        assertPoolIdle();
    }

    @ParameterizedTest
    @MethodSource("invalidQueries")
    void rawQueryErrorsPreserveRepeatedValuesAndValidateLocaleBeforeContext(QueryCase candidate) throws Exception {
        openHttp(true, false);
        MockHttpServletRequestBuilder request = get(ROOT).header("If-None-Match", "*");
        candidate.parameters().forEach((key, values) -> request.param(key, values));
        MockHttpServletResponse response = mvc.perform(request)
                .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("ETag"))
                .andExpect(header().string("X-RateLimit-Limit", "60"))
                .andExpect(jsonPath("$.code").value(candidate.field().equals("locale")
                        ? "LOCALE_LEGAL_NO_SOPORTADO" : "CONTEXTO_LEGAL_NO_SOPORTADO"))
                .andReturn().getResponse();
        ObjectNode details = JSON.createObjectNode();
        details.put(candidate.field(), candidate.rejected());
        details.putArray(candidate.field().equals("locale") ? "localesSoportados" : "contextosSoportados")
                .add(candidate.field().equals("locale") ? "es-AR" : "REGISTRO");
        assertThat(body(response).get("details")).isEqualTo(details);
        assertThat(metrics.snapshot().statementExecutions()).isZero();
        assertThat(metrics.snapshot().commits()).isZero();
        assertThat(metrics.snapshot().rollbacks()).isZero();
        assertAggregateCounts(0);
    }

    static Stream<QueryCase> invalidQueries() {
        return Stream.of(
                new QueryCase(Map.of(), "locale", null),
                new QueryCase(Map.of("contexto", new String[]{"REGISTRO"}), "locale", null),
                new QueryCase(Map.of("locale", new String[]{"es-AR"}), "contexto", null),
                new QueryCase(Map.of("locale", new String[]{"ES-ar"}, "contexto", new String[]{"USO_CONTINUADO"}), "locale", "ES-ar"),
                new QueryCase(Map.of("locale", new String[]{""}, "contexto", new String[]{""}), "locale", ""),
                new QueryCase(Map.of("locale", new String[]{" es-AR"}, "contexto", new String[]{"REGISTRO"}), "locale", " es-AR"),
                new QueryCase(Map.of("locale", new String[]{"es-AR", "es-AR"}, "contexto", new String[]{"REGISTRO"}), "locale", "es-AR,es-AR"),
                new QueryCase(Map.of("locale", new String[]{"es-AR", "es-ES"}, "contexto", new String[]{"OTRO", "REGISTRO"}), "locale", "es-AR,es-ES"),
                new QueryCase(Map.of("locale", new String[]{"es-AR"}, "contexto", new String[]{"REGISTRO", "REGISTRO"}), "contexto", "REGISTRO,REGISTRO"),
                new QueryCase(Map.of("locale", new String[]{"es-AR"}, "contexto", new String[]{"USO_CONTINUADO", "REGISTRO"}), "contexto", "USO_CONTINUADO,REGISTRO"),
                new QueryCase(Map.of("locale", new String[]{"es-AR"}, "contexto", new String[]{"registro"}), "contexto", "registro"),
                new QueryCase(Map.of("locale", new String[]{"es-AR"}, "contexto", new String[]{"REGISTRO "}), "contexto", "REGISTRO "));
    }

    @Test
    void lostRestrictedReadPrivilegesPrevent304AndNeverExposeTheInternalCause() throws Exception {
        seedRegistration(owner, directory, "requirements-http-privileges");
        openHttp(true, false);
        String etag = mvc.perform(registrationRequest()).andExpect(status().isOk())
                .andReturn().getResponse().getHeader("ETag");
        owner.execute("REVOKE SELECT ON public.legal_requisito_versiones FROM " + ROLE);
        try {
            metrics.reset();
            assertUnavailable(registrationRequest().header("If-None-Match", etag));
            assertThat(metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isZero();
            assertThat(metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.ADVISORY_LOCK)).isZero();
            assertThat(metrics.snapshot().rollbacks()).isEqualTo(1);
            assertAggregateCounts(1);
        } finally {
            owner.execute("GRANT SELECT ON public.legal_requisito_versiones TO " + ROLE);
        }
        metrics.reset();
        mvc.perform(registrationRequest().header("If-None-Match", etag)).andExpect(status().isNotModified());
        assertReusedCommit();
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void bothPublicFlagsKeepIndependentMappingsContextsCredentialsAndRealQueries(boolean requirements, boolean documents)
            throws Exception {
        Seed seed = seedRegistration(owner, directory, "requirements-http-flags");
        LegalPublicDocumentReadITSupport.seedCatalog(documentOwner, directory, "documents-http-flags");
        openHttp(requirements, documents);
        MockHttpServletRequestBuilder requiredRequest = get("/ordenfix" + ROOT).contextPath("/ordenfix")
                .param("locale", "es-AR").param("contexto", "REGISTRO");
        MockHttpServletRequestBuilder documentRequest = get("/ordenfix" + DOCUMENT_ROOT).contextPath("/ordenfix")
                .param("locale", "es-AR");
        if (requirements) {
            MockHttpServletResponse response = mvc.perform(requiredRequest.header("Authorization", "Bearer invalid-public-token"))
                    .andExpect(status().isOk()).andReturn().getResponse();
            assertThat(body(response)).isEqualTo(expectedWire(seed));
            assertThat(requirementsContext.getParent()).isNull();
            assertThat(new JdbcTemplate(requirementsPool).queryForObject("SELECT current_user", String.class)).isEqualTo(ROLE);
            assertAggregateCounts(1);
        } else {
            mvc.perform(requiredRequest).andExpect(status().isForbidden());
            assertThat(context.getBeansOfType(LegalPublicRequirementsController.class)).isEmpty();
            assertThat(context.getBeansOfType(LegalPublicRequirementsReadService.class)).isEmpty();
            assertThat(requirementsContext).isNull();
            assertAggregateCounts(0);
        }
        if (documents) {
            mvc.perform(documentRequest.header("Authorization", "Bearer invalid-public-token"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.page.totalElements").value(11));
            assertThat(documentsContext.getParent()).isNull();
            assertThat(new JdbcTemplate(documentsPool).queryForObject("SELECT current_user", String.class)).isEqualTo(DOCUMENT_ROLE);
        } else {
            mvc.perform(documentRequest).andExpect(status().isForbidden());
            assertThat(context.getBeansOfType(LegalPublicDocumentController.class)).isEmpty();
            assertThat(context.getBeansOfType(LegalPublicDocumentReadService.class)).isEmpty();
            assertThat(documentsContext).isNull();
        }
        if (requirements && documents) {
            assertThat(requirementsContext).isNotSameAs(documentsContext);
            assertThat(requirementsPool).isNotSameAs(documentsPool);
            assertThat(requirementsPool.getJdbcUrl()).isNotEqualTo(documentsPool.getJdbcUrl());
        }
        assertNoWebDatabaseGraph();
        verifyNoInteractions(context.getBean(JwtUtils.class), context.getBean(UserDetailsServiceImpl.class));
    }

    @Test
    void defaultOffNeedsNoDedicatedCredentialsEvenIfTheInternalFlagIsPresentInTheWebEnvironment() throws Exception {
        openHttp(Map.of(LegalPublicRequirementsDatabaseConfiguration.ENABLED_PROPERTY, "true",
                "spring.datasource.url", POSTGRES.getJdbcUrl(),
                "spring.datasource.username", POSTGRES.getUsername(),
                "spring.datasource.password", POSTGRES.getPassword()));
        mvc.perform(registrationRequest()).andExpect(status().isForbidden());
        assertThat(context.getBeansOfType(LegalPublicRequirementsReadService.class)).isEmpty();
        assertThat(context.getBeansOfType(LegalPublicRequirementsController.class)).isEmpty();
        assertNoWebDatabaseGraph();
        assertThat(requirementsContext).isNull();
        assertAggregateCounts(0);
    }

    @Test
    void enabledWithoutTheDedicatedCredentialFailsStartupInsteadOfUsingTheWebOwner() {
        assertThatThrownBy(() -> openHttp(Map.of(LegalPublicRequirementsRequestMatcher.ENABLED_PROPERTY, "true",
                "spring.datasource.url", POSTGRES.getJdbcUrl(),
                "spring.datasource.username", POSTGRES.getUsername(),
                "spring.datasource.password", POSTGRES.getPassword())))
                .hasRootCauseInstanceOf(IllegalStateException.class);
        assertThat(context.isActive()).isFalse();
        assertAggregateCounts(0);
    }

    @Test
    void anOwnerCredentialFailsClosedAtPreflightInsteadOfReceivingThePublicContract() throws Exception {
        seedRegistration(owner, directory, "requirements-http-wrong-owner");
        Map<String, Object> properties = properties(true, false);
        properties.put(LegalPublicRequirementsDatabaseConfiguration.PROPERTY_PREFIX + "username", POSTGRES.getUsername());
        properties.put(LegalPublicRequirementsDatabaseConfiguration.PROPERTY_PREFIX + "password", POSTGRES.getPassword());
        openHttp(properties);

        assertUnavailable(registrationRequest().header("If-None-Match", "*"));

        assertAggregateCounts(0);
        assertThat(metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isZero();
        assertThat(metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.ADVISORY_LOCK)).isZero();
        assertThat(metrics.snapshot().rollbacks()).isEqualTo(1);
        assertPoolIdle();
    }

    private void openHttp(boolean requirements, boolean documents) {
        openHttp(properties(requirements, documents));
    }

    private void openHttp(Map<String, Object> properties) {
        context = new AnnotationConfigWebApplicationContext();
        try {
            context.setServletContext(new MockServletContext());
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("requirements-http-it", properties));
            context.register(WebConfiguration.class, LegalPublicDocumentHttpConfiguration.class,
                    LegalPublicRequirementsHttpConfiguration.class, LegalPublicDocumentController.class,
                    LegalPublicRequirementsController.class, LegalPublicDocumentExceptionHandler.class,
                    LegalPublicRequirementsExceptionHandler.class, GlobalExceptionHandler.class,
                    SecurityConfig.class, CorsConfig.class, JwtFilter.class, PublicEndpointRateLimitFilter.class,
                    LegalPublicDocumentRequestMatcher.class, LegalPublicRequirementsRequestMatcher.class,
                    RateLimitProperties.class);
            context.refresh();
            if (!context.getBeansOfType(LegalPublicRequirementsHttpConfiguration.class).isEmpty()) {
                requirementsContext = (AnnotationConfigApplicationContext) ReflectionTestUtils.getField(
                        context.getBean(LegalPublicRequirementsHttpConfiguration.class), "requirementsContext");
                requirementsPool = Objects.requireNonNull(requirementsContext).getBean(HikariDataSource.class);
                metrics = LegalJdbcMetricsSupport.instrument(requirementsPool, Duration.ZERO);
                // Observation only: retain the exact production wrapper/JDBC/manager/gate graph and real pool.
                // No request has borrowed a connection yet; the web bridge still owns and closes the pool bean.
                ReflectionTestUtils.setField(requirementsContext.getBean(LegalPublicRequirementsDataSource.class),
                        "pool", metrics.dataSource());
            }
            if (!context.getBeansOfType(LegalPublicDocumentHttpConfiguration.class).isEmpty()) {
                documentsContext = (AnnotationConfigApplicationContext) ReflectionTestUtils.getField(
                        context.getBean(LegalPublicDocumentHttpConfiguration.class), "readerContext");
                documentsPool = Objects.requireNonNull(documentsContext).getBean(HikariDataSource.class);
            }
            mvc = webAppContextSetup(context).apply(springSecurity()).build();
        } catch (RuntimeException | Error failure) {
            try {
                context.close();
            } catch (RuntimeException | Error cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private static Map<String, Object> properties(boolean requirements, boolean documents) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(LegalPublicRequirementsRequestMatcher.ENABLED_PROPERTY, Boolean.toString(requirements));
        properties.put(LegalPublicDocumentRequestMatcher.ENABLED_PROPERTY, Boolean.toString(documents));
        if (requirements) {
            properties.put(LegalPublicRequirementsDatabaseConfiguration.PROPERTY_PREFIX + "jdbc-url", POSTGRES.getJdbcUrl());
            properties.put(LegalPublicRequirementsDatabaseConfiguration.PROPERTY_PREFIX + "username", ROLE);
            properties.put(LegalPublicRequirementsDatabaseConfiguration.PROPERTY_PREFIX + "password", PASSWORD);
        }
        if (documents) {
            properties.put(LegalPublicDocumentReadDatabaseConfiguration.PROPERTY_PREFIX + "jdbc-url", DOCUMENT_POSTGRES.getJdbcUrl());
            properties.put(LegalPublicDocumentReadDatabaseConfiguration.PROPERTY_PREFIX + "username", DOCUMENT_ROLE);
            properties.put(LegalPublicDocumentReadDatabaseConfiguration.PROPERTY_PREFIX + "password", DOCUMENT_PASSWORD);
        }
        return properties;
    }

    private static MockHttpServletRequestBuilder registrationRequest() {
        return get(ROOT).param("contexto", "REGISTRO").param("locale", "es-AR");
    }

    private void assertUnavailable(MockHttpServletRequestBuilder request) throws Exception {
        MockHttpServletResponse response = mvc.perform(request)
                .andExpect(status().isServiceUnavailable()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("ETag"))
                .andExpect(jsonPath("$.code").value("CONTRATO_LEGAL_NO_DISPONIBLE"))
                .andReturn().getResponse();
        assertThat(body(response).get("details")).isEqualTo(JSON.valueToTree(Map.of("contexto", "REGISTRO", "locale", "es-AR")));
        assertThat(response.getContentAsString()).doesNotContain(ROLE, PASSWORD, POSTGRES.getJdbcUrl(),
                "SELECT", "legal_requisito_versiones", "Alteración controlada");
    }

    private void assertReusedCommit() {
        assertThat(metrics.snapshot().commits()).isEqualTo(1);
        assertThat(metrics.snapshot().rollbacks()).isZero();
        assertThat(metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isZero();
        assertThat(metrics.snapshot().advisoryLockExecutions(LegalJdbcMetricsSupport.AdvisoryLockMode.SHARED)).isEqualTo(1);
        assertPoolIdle();
    }

    private void assertPoolIdle() {
        assertThat(requirementsPool.getHikariPoolMXBean().getActiveConnections()).isZero();
    }

    private static void assertAggregateCounts(long expected) {
        assertThat(aggregateCounts(owner)).containsEntry("legal_requisito_agregados", expected)
                .containsEntry("legal_requisito_agregado_scopes", expected);
    }

    private void assertNoWebDatabaseGraph() {
        assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
        assertThat(context.getBeansOfType(JdbcTemplate.class)).isEmpty();
        assertThat(context.getBeansOfType(PlatformTransactionManager.class)).isEmpty();
        assertThat(context.getBeansOfType(LegalRequiredSetAggregateStore.class)).isEmpty();
        assertThat(context.getBeansOfType(LegalPublicRequirementsReader.class)).isEmpty();
    }

    private static JsonNode expectedWire(Seed seed) {
        ObjectNode result = JSON.createObjectNode();
        result.put("contexto", seed.projection().context().name());
        result.put("locale", seed.projection().locale().getCodigo());
        result.put("requiredSetRevision", new LegalPublicRequirementsValidator().validate(seed.projection()).requiredSetRevision());
        var requirements = result.putArray("requisitos");
        seed.projection().requirements().forEach(requirement -> {
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

    private record QueryCase(Map<String, String[]> parameters, String field, String rejected) { }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebMvc
    static class WebConfiguration {
        @Bean Clock clock() { return Clock.systemUTC(); }
        @Bean JwtUtils jwtUtils() { return mock(JwtUtils.class); }
        @Bean UserDetailsServiceImpl users() { return mock(UserDetailsServiceImpl.class); }
    }
}
