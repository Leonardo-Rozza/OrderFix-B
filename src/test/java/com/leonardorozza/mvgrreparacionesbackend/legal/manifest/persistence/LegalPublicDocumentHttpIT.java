package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leonardorozza.mvgrreparacionesbackend.config.CorsConfig;
import com.leonardorozza.mvgrreparacionesbackend.config.SecurityConfig;
import com.leonardorozza.mvgrreparacionesbackend.config.filter.JwtFilter;
import com.leonardorozza.mvgrreparacionesbackend.config.filter.PublicEndpointRateLimitFilter;
import com.leonardorozza.mvgrreparacionesbackend.config.security.LegalPublicDocumentRequestMatcher;
import com.leonardorozza.mvgrreparacionesbackend.config.security.LegalPublicRequirementsRequestMatcher;
import com.leonardorozza.mvgrreparacionesbackend.config.security.RateLimitProperties;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.GlobalExceptionHandler;
import com.leonardorozza.mvgrreparacionesbackend.legal.http.LegalPublicDocumentController;
import com.leonardorozza.mvgrreparacionesbackend.legal.http.LegalPublicDocumentExceptionHandler;
import com.leonardorozza.mvgrreparacionesbackend.legal.http.LegalPublicDocumentHttpConfiguration;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicDocumentReadITSupport.Seed;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicDocumentReadITSupport.StoredDocument;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.UserDetailsServiceImpl;
import com.leonardorozza.mvgrreparacionesbackend.utils.jwt.JwtUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;

import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicDocumentReadITSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/** Real HTTP composition, restricted PostgreSQL role and editorial fixtures; only JWT users are mocked. */
class LegalPublicDocumentHttpIT {

    private static final String ROOT = "/api/public/documentos-legales";
    private static final String REVALIDATE = "public, max-age=0, must-revalidate";
    private static final String IMMUTABLE = "public, max-age=31536000, immutable";
    private static final String ROLE = "ordenfix_legal_public_document_http_it";
    private static final String PASSWORD = "legal-public-document-http-test-only";
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_public_document_http")
            .withUsername("ordenfix").withPassword("ordenfix");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static JdbcTemplate owner;

    @TempDir
    Path directory;
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;

    @BeforeAll
    static void provision() {
        POSTGRES.start();
        LegalManifestPersistenceITSupport.migrateLatest(POSTGRES);
        owner = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        new LegalRestrictedPublicDocumentRoleFixture(owner, POSTGRES.getJdbcUrl(), ROLE, PASSWORD,
                POSTGRES.getDriverClassName()).provisionAndVerify();
    }

    @BeforeEach
    void createProductionHttpComposition() {
        clearCatalog(owner);
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("http-it", Map.of(
                "ordenfix.legal.public-documents.enabled", "true",
                LegalPublicDocumentReadDatabaseConfiguration.PROPERTY_PREFIX + "jdbc-url", POSTGRES.getJdbcUrl(),
                LegalPublicDocumentReadDatabaseConfiguration.PROPERTY_PREFIX + "username", ROLE,
                LegalPublicDocumentReadDatabaseConfiguration.PROPERTY_PREFIX + "password", PASSWORD)));
        context.register(WebConfiguration.class, LegalPublicDocumentHttpConfiguration.class,
                LegalPublicDocumentController.class, LegalPublicDocumentExceptionHandler.class,
                GlobalExceptionHandler.class, SecurityConfig.class, CorsConfig.class,
                JwtFilter.class, PublicEndpointRateLimitFilter.class,
                LegalPublicDocumentRequestMatcher.class, LegalPublicRequirementsRequestMatcher.class, RateLimitProperties.class);
        context.refresh();
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterEach
    void close() {
        if (context != null) {
            context.close();
        }
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @Test
    void returnsFullRevisionAcrossPagesAndOriginalMarkdownThroughTheRestrictedFacade() throws Exception {
        Seed seed = seedCatalog(owner, directory, "http-pages");
        Map<String, Long> counts = LegalManifestPersistenceITSupport.editorialTableCounts(owner);
        var sequences = LegalManifestPersistenceITSupport.editorialSequenceStates(owner);

        MockHttpServletResponse first = mvc.perform(get(ROOT).param("locale", "es-AR").param("size", "3")
                        .header("Authorization", "Bearer invalid-public-token"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", REVALIDATE))
                .andExpect(header().string("X-RateLimit-Limit", "60"))
                .andExpect(jsonPath("$.page.totalElements").value(11))
                .andExpect(jsonPath("$.page.totalPages").value(4))
                .andExpect(jsonPath("$.documentos.length()").value(3))
                .andExpect(jsonPath("$.documentos[0].contenidoMarkdown").doesNotExist())
                .andReturn().getResponse();
        JsonNode firstJson = body(first);
        assertThat(firstJson.has("contexto")).isTrue();
        assertThat(firstJson.get("contexto").isNull()).isTrue();
        String revision = firstJson.get("documentSetRevision").asText();
        assertThat(first.getHeader("ETag")).isEqualTo("W/\"" + revision + ":p=0:s=3\"");

        mvc.perform(get(ROOT).param("locale", "es-AR").param("page", "1").param("size", "3"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.documentSetRevision").value(revision))
                .andExpect(jsonPath("$.documentos[0].id").value(seed.documents().get(3).id().toString()));
        mvc.perform(get(ROOT).param("locale", "es-AR").param("page", "2147483647"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.documentSetRevision").value(revision))
                .andExpect(jsonPath("$.documentos").isEmpty())
                .andExpect(jsonPath("$.page.totalElements").value(11));

        StoredDocument expected = seed.documents().getFirst();
        MockHttpServletResponse exact = mvc.perform(get("/ordenfix" + ROOT + "/" + expected.id())
                        .contextPath("/ordenfix").header("Authorization", "Bearer invalid-public-token"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", REVALIDATE))
                .andExpect(jsonPath("$.id").value(expected.id().toString()))
                .andExpect(jsonPath("$.vigenteDesde").value(expected.effectiveAt().toString()))
                .andExpect(jsonPath("$.locale").value("es-AR"))
                .andReturn().getResponse();
        assertThat(body(exact).get("contenidoMarkdown").asText().getBytes(StandardCharsets.UTF_8))
                .containsExactly(expected.markdown().getBytes(StandardCharsets.UTF_8));
        assertThat(body(exact).get("sha256").asText()).isEqualTo(sha256(expected.markdown()));
        verifyNoInteractions(context.getBean(JwtUtils.class), context.getBean(UserDetailsServiceImpl.class));
        assertThat(LegalManifestPersistenceITSupport.editorialTableCounts(owner)).isEqualTo(counts);
        assertThat(LegalManifestPersistenceITSupport.editorialSequenceStates(owner)).isEqualTo(sequences);
    }

    @Test
    void conditionalsObserveRealReplacementBeforeChoosingStateAndCachePolicy() throws Exception {
        Seed seed = seedCatalog(owner, directory, "http-current");
        UUID old = seed.documents().getFirst().id();
        MockHttpServletResponse catalog = mvc.perform(get(ROOT).param("locale", "es-AR"))
                .andExpect(status().isOk()).andReturn().getResponse();
        MockHttpServletResponse document = mvc.perform(get(ROOT + "/" + old))
                .andExpect(status().isOk()).andReturn().getResponse();
        String catalogEtag = catalog.getHeader("ETag");
        String oldEtag = document.getHeader("ETag");
        mvc.perform(get(ROOT).param("locale", "es-AR").header("If-None-Match", "\"different\", " + catalogEtag))
                .andExpect(status().isNotModified()).andExpect(content().string(""))
                .andExpect(header().string("Cache-Control", REVALIDATE))
                .andExpect(header().string("ETag", catalogEtag))
                .andExpect(header().exists("X-RateLimit-Remaining"));
        mvc.perform(get(ROOT + "/" + old).header("If-None-Match", oldEtag.substring(2)))
                .andExpect(status().isNotModified()).andExpect(content().string(""));

        replaceCatalog(owner, directory, "http-replacement", seed);

        MockHttpServletResponse changed = mvc.perform(get(ROOT + "/" + old).header("If-None-Match", oldEtag))
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("REEMPLAZADA"))
                .andExpect(header().string("Cache-Control", IMMUTABLE)).andReturn().getResponse();
        assertThat(changed.getHeader("ETag")).isNotEqualTo(oldEtag).contains(":REEMPLAZADA:");
        mvc.perform(get(ROOT + "/" + old).header("If-None-Match", "*"))
                .andExpect(status().isNotModified()).andExpect(content().string(""))
                .andExpect(header().string("Cache-Control", IMMUTABLE));
        mvc.perform(get(ROOT).param("locale", "es-AR").header("If-None-Match", catalogEtag))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page.totalElements").value(22))
                .andExpect(header().string("Cache-Control", REVALIDATE));
    }

    @Test
    void malformedUnknownDraftAndNotYetCurrentVersionsShareThe404Contract() throws Exception {
        Seed draft = seedDraft(owner, directory, "http-hidden");
        UUID id = draft.documents().getFirst().id();
        for (String candidate : new String[]{"invalid-uuid", "1-1-1-1-1", UUID.randomUUID().toString(), id.toString()}) {
            assertUnavailableVersion(candidate);
        }
        inOwnerEditorialTransaction(() -> owner.update("""
                INSERT INTO public.legal_documento_transiciones
                    (documento_version_id, estado_anterior, estado_nuevo, ocurrido_en)
                SELECT documento_version_id, 'BORRADOR', 'PUBLICADA', transaction_timestamp()
                  FROM public.legal_publicacion_documentos WHERE publicacion_id = ?
                """, draft.publicationId()));
        assertUnavailableVersion(id.toString());
    }

    @Test
    void unsupportedQueriesAndEmptyCatalogNeverBecomeConditionalSuccess() throws Exception {
        mvc.perform(get(ROOT).header("If-None-Match", "*"))
                .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("LOCALE_LEGAL_NO_SOPORTADO"));
        mvc.perform(get(ROOT).param("locale", "es-AR").param("contexto", "OTRO").header("If-None-Match", "*"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CONTEXTO_LEGAL_NO_SOPORTADO"));
        mvc.perform(get(ROOT).param("locale", "es-AR").header("If-None-Match", "*"))
                .andExpect(status().isServiceUnavailable()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("ETag"))
                .andExpect(jsonPath("$.code").value("CONTRATO_LEGAL_NO_DISPONIBLE"))
                .andExpect(jsonPath("$.details.locale").value("es-AR"));
    }

    @Test
    void lostRestrictedPrivilegesFail503BeforeRevalidationOrNotFound() throws Exception {
        Seed seed = seedCatalog(owner, directory, "http-privileges");
        UUID id = seed.documents().getFirst().id();
        String etag = mvc.perform(get(ROOT + "/" + id)).andExpect(status().isOk())
                .andReturn().getResponse().getHeader("ETag");
        owner.execute("REVOKE SELECT ON public.legal_documento_contextos FROM " + ROLE);
        try {
            for (String candidate : new String[]{id.toString(), UUID.randomUUID().toString()}) {
                mvc.perform(get(ROOT + "/" + candidate).header("If-None-Match", etag))
                        .andExpect(status().isServiceUnavailable())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(header().doesNotExist("ETag"))
                        .andExpect(jsonPath("$.code").value("CONTRATO_LEGAL_NO_DISPONIBLE"))
                        .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                                .doesNotContain(ROLE, PASSWORD, "SELECT", "legal_documento_contextos"));
            }
            mvc.perform(get(ROOT).param("locale", "es-AR").header("If-None-Match", "*"))
                    .andExpect(status().isServiceUnavailable());
        } finally {
            owner.execute("GRANT SELECT ON public.legal_documento_contextos TO " + ROLE);
        }
        mvc.perform(get(ROOT + "/" + id).header("If-None-Match", etag))
                .andExpect(status().isNotModified());
    }

    @Test
    void corruptContentReturns503WithNoEtagEvenWhenTheClientHasThePreviousValidator() throws Exception {
        StoredDocument target = seedCatalog(owner, directory, "http-corruption").documents().getFirst();
        String etag = mvc.perform(get(ROOT + "/" + target.id())).andExpect(status().isOk())
                .andReturn().getResponse().getHeader("ETag");
        LegalManifestPersistenceITSupport.withReplicaRole(owner, () -> owner.update("""
                UPDATE public.legal_documento_versiones SET contenido_markdown = ? WHERE id = ?
                """, "# Deliberately corrupted fixture\n", target.id()));
        Map<String, Long> counts = LegalManifestPersistenceITSupport.editorialTableCounts(owner);
        mvc.perform(get(ROOT + "/" + target.id()).header("If-None-Match", etag))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("ETag"))
                .andExpect(jsonPath("$.code").value("CONTRATO_LEGAL_NO_DISPONIBLE"));
        assertThat(LegalManifestPersistenceITSupport.editorialTableCounts(owner)).isEqualTo(counts);
    }

    @Test
    void retirementKeepsAHistoricalCatalogAndAnImmutableExactDocument() throws Exception {
        StoredDocument target = seedCatalog(owner, directory, "http-retirement").documents().stream()
                .filter(document -> document.key().equals("cierre-cuenta")).findFirst().orElseThrow();
        inOwnerEditorialTransaction(() -> {
            owner.update("DELETE FROM public.legal_documento_vigentes WHERE documento_version_id = ?", target.id());
            owner.update("""
                    INSERT INTO public.legal_documento_transiciones
                        (documento_version_id, estado_anterior, estado_nuevo, ocurrido_en, motivo)
                    VALUES (?, 'VIGENTE', 'RETIRADA', transaction_timestamp(), 'Retiro fixture HTTP')
                    """, target.id());
        });
        mvc.perform(get(ROOT).param("locale", "es-AR").param("contexto", "CIERRE_CUENTA"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.documentos[0].estado").value("RETIRADA"))
                .andExpect(header().string("Cache-Control", REVALIDATE));
        mvc.perform(get(ROOT + "/" + target.id()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.contenidoMarkdown").value(target.markdown()))
                .andExpect(jsonPath("$.estado").value("RETIRADA"))
                .andExpect(header().string("Cache-Control", IMMUTABLE));
    }

    private void assertUnavailableVersion(String id) throws Exception {
        mvc.perform(get(ROOT + "/" + id).header("If-None-Match", "*")
                        .header("Authorization", "Bearer invalid-public-token"))
                .andExpect(status().isNotFound()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("ETag"))
                .andExpect(jsonPath("$.code").value("DOCUMENTO_LEGAL_NO_ENCONTRADO"))
                .andExpect(jsonPath("$.details.versionId").value(id));
    }

    private static JsonNode body(MockHttpServletResponse response) throws Exception {
        return JSON.readTree(response.getContentAsByteArray());
    }

    private static void inOwnerEditorialTransaction(Runnable work) {
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(owner.getDataSource()));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.executeWithoutResult(status -> {
            owner.queryForList("""
                    SELECT pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(?, 0))
                    """, LegalManifestDatabaseGate.EDITORIAL_LOCK_NAME);
            work.run();
            owner.execute("SET CONSTRAINTS ALL IMMEDIATE");
        });
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebMvc
    static class WebConfiguration {
        @Bean Clock clock() { return Clock.systemUTC(); }
        @Bean JwtUtils jwtUtils() { return mock(JwtUtils.class); }
        @Bean UserDetailsServiceImpl users() { return mock(UserDetailsServiceImpl.class); }
    }
}
