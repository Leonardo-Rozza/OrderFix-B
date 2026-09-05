package com.leonardorozza.mvgrreparacionesbackend.legal.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leonardorozza.mvgrreparacionesbackend.config.security.LegalPublicDocumentRequestMatcher;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.GlobalExceptionHandler;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalDocumentSummary;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicDocumentCatalog;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicDocumentReadException;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicDocumentReadService;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicDocumentVersion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoVersionLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LegalPublicDocumentControllerTest {

    private static final String BASE = "/api/public/documentos-legales";
    private static final String REVISION = "sha256:" + "a".repeat(64);
    private static final String REVALIDATE = "public, max-age=0, must-revalidate";
    private static final String IMMUTABLE = "public, max-age=31536000, immutable";
    private static final UUID VERSION_ID = UUID.fromString("8abcdef0-1234-5678-9abc-def012345678");
    private static final String MARKDOWN = "# Términos de OrdenFix\n\nTexto original: reparación 🙂.\n";
    private static final ObjectMapper JSON = new ObjectMapper();
    private LegalPublicDocumentReadService service;
    private MockMvc mvc;

    @BeforeEach
    void buildOnlyTheDocumentTransportAndItsScopedAdvice() {
        service = mock(LegalPublicDocumentReadService.class);
        mvc = MockMvcBuilders.standaloneSetup(new LegalPublicDocumentController(service))
                .setControllerAdvice(new GlobalExceptionHandler(), new LegalPublicDocumentExceptionHandler())
                .build();
    }

    @Test
    void catalogUsesExactlyTheSpanishWireAndExplicitNullContext() throws Exception {
        when(service.catalog(null, LocaleLegal.ES_AR, 0, 20)).thenReturn(catalog(null, 0, 20));

        MvcResult result = mvc.perform(get(BASE).param("locale", "es-AR"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, REVALIDATE))
                .andExpect(header().string(HttpHeaders.ETAG, catalogTag(0, 20))).andReturn();

        JsonNode body = body(result);
        assertThat(fields(body)).containsExactlyInAnyOrder(
                "contexto", "locale", "documentSetRevision", "documentos", "page");
        assertThat(body.has("contexto")).isTrue();
        assertThat(body.path("contexto").isNull()).isTrue();
        assertThat(body.path("locale").textValue()).isEqualTo("es-AR");
        assertThat(body.path("documentSetRevision").textValue()).isEqualTo(REVISION);
        assertThat(body.path("documentos")).hasSize(3);
        assertSummary(body.path("documentos").get(0), summary(EstadoVersionLegal.VIGENTE));
        assertThat(body.path("documentos").get(1).path("estado").textValue()).isEqualTo("REEMPLAZADA");
        assertThat(body.path("documentos").get(2).path("estado").textValue()).isEqualTo("RETIRADA");
        assertThat(fields(body.path("page"))).containsExactlyInAnyOrder(
                "size", "number", "totalElements", "totalPages");
        assertThat(body.path("page").path("size").intValue()).isEqualTo(20);
        assertThat(body.path("page").path("number").intValue()).isZero();
        assertThat(body.path("page").path("totalElements").longValue()).isEqualTo(3);
        assertThat(body.path("page").path("totalPages").longValue()).isEqualTo(1);
        assertThat(result.getResponse().getContentAsString()).doesNotContain(
                "contenidoMarkdown", "requiredSetRevision", "publicationId", "publicacionId", "href",
                "actor", "userId", "tallerId", "versionId", "effectiveAt", "summary");
        verify(service).catalog(null, LocaleLegal.ES_AR, 0, 20);
    }

    @ParameterizedTest
    @EnumSource(ContextoLegal.class)
    void acceptsEveryDocumentContextWithoutNormalizingIt(ContextoLegal context) throws Exception {
        when(service.catalog(context, LocaleLegal.ES_AR, 0, 20)).thenReturn(catalog(context, 0, 20));

        MvcResult result = mvc.perform(get(BASE).param("locale", "es-AR")
                        .param("contexto", context.name())).andExpect(status().isOk()).andReturn();

        assertThat(body(result).path("contexto").textValue()).isEqualTo(context.name());
        verify(service).catalog(context, LocaleLegal.ES_AR, 0, 20);
    }

    @Test
    void pagesShareTheCompleteRevisionAndKeepDistinctEtagsIncludingAnEmptyLastPage() throws Exception {
        when(service.catalog(null, LocaleLegal.ES_AR, 0, 2)).thenReturn(catalog(null, 0, 2));
        when(service.catalog(null, LocaleLegal.ES_AR, 1, 2)).thenReturn(catalog(null, 1, 2));
        when(service.catalog(null, LocaleLegal.ES_AR, Integer.MAX_VALUE, 100))
                .thenReturn(catalog(null, Integer.MAX_VALUE, 100));

        MvcResult first = mvc.perform(get(BASE).param("locale", "es-AR").param("size", "2"))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.ETAG, catalogTag(0, 2)))
                .andReturn();
        MvcResult second = mvc.perform(get(BASE).param("locale", "es-AR").param("page", "1")
                        .param("size", "2").header(HttpHeaders.IF_NONE_MATCH, catalogTag(0, 2)))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.ETAG, catalogTag(1, 2)))
                .andReturn();
        MvcResult empty = mvc.perform(get(BASE).param("locale", "es-AR")
                        .param("page", "2147483647").param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, catalogTag(Integer.MAX_VALUE, 100)))
                .andReturn();

        assertThat(body(first).path("documentos")).hasSize(2);
        assertThat(body(second).path("documentos")).hasSize(1);
        assertThat(body(empty).path("documentos")).isEmpty();
        for (MvcResult result : List.of(first, second, empty)) {
            assertThat(body(result).path("documentSetRevision").textValue()).isEqualTo(REVISION);
            assertThat(body(result).path("page").path("totalElements").longValue()).isEqualTo(3);
        }
    }

    @Test
    void exposesSafeLongCountsWithoutTruncatingThemToInt() throws Exception {
        long maximum = 9_007_199_254_740_991L;
        when(service.catalog(null, LocaleLegal.ES_AR, 0, 1)).thenReturn(new LegalPublicDocumentCatalog(
                null, LocaleLegal.ES_AR, REVISION, List.of(summary(EstadoVersionLegal.VIGENTE)),
                0, 1, maximum, maximum));

        MvcResult result = mvc.perform(get(BASE).param("locale", "es-AR").param("size", "1"))
                .andExpect(status().isOk()).andReturn();

        assertThat(body(result).path("page").path("totalElements").longValue()).isEqualTo(maximum);
        assertThat(body(result).path("page").path("totalPages").longValue()).isEqualTo(maximum);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"es", "en-US", "ES-AR", "es-AR ", " es-AR"})
    void invalidOrMissingLocalePreservesItsExactWireValue(String locale) throws Exception {
        MockHttpServletRequestBuilder request = get(BASE);
        if (locale != null) {
            request.param("locale", locale);
        }
        JsonNode body = body(mvc.perform(request).andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().doesNotExist(HttpHeaders.ETAG)).andReturn());

        assertError(body, 400, "LOCALE_LEGAL_NO_SOPORTADO", BASE);
        assertThat(fields(body.path("details"))).containsExactlyInAnyOrder("locale", "localesSoportados");
        assertThat(body.path("details").has("locale")).isTrue();
        if (locale == null) {
            assertThat(body.path("details").path("locale").isNull()).isTrue();
        } else {
            assertThat(body.path("details").path("locale").textValue()).isEqualTo(locale);
        }
        assertThat(body.path("details").path("localesSoportados")).isEqualTo(JSON.readTree("[\"es-AR\"]"));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "registro", "REGISTRO ", "TODOS", "NO_EXISTE"})
    void invalidContextPreservesTheValueAndFreezesTheSupportedOrder(String context) throws Exception {
        JsonNode body = body(mvc.perform(get(BASE).param("locale", "es-AR").param("contexto", context))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().doesNotExist(HttpHeaders.ETAG)).andReturn());

        assertError(body, 400, "CONTEXTO_LEGAL_NO_SOPORTADO", BASE);
        assertThat(fields(body.path("details"))).containsExactlyInAnyOrder("contexto", "contextosSoportados");
        assertThat(body.path("details").path("contexto").textValue()).isEqualTo(context);
        assertThat(body.path("details").path("contextosSoportados")).isEqualTo(JSON.readTree("""
                ["REGISTRO", "PRIMER_INGRESO_EMPLEADO", "USO_CONTINUADO", "CONTRATACION_PRO",
                 "ATESTACION_FOTOS", "ATESTACION_CREDENCIALES", "CIERRE_CUENTA", "ARREPENTIMIENTO"]
                """));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "-1", "+1", "1.0", "1e2", "2147483648", "99999999999999999999"})
    void invalidPageUsesTheExistingValidationEnvelopeWithoutANewLegalCode(String page) throws Exception {
        assertPaginationError(mvc.perform(get(BASE).param("locale", "es-AR").param("page", page))
                .andExpect(status().isBadRequest()).andReturn());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "0", "101", "-1", "+1", "1.0", "1e2", "2147483648"})
    void invalidSizeNeverSilentlyFallsBackToTwenty(String size) throws Exception {
        assertPaginationError(mvc.perform(get(BASE).param("locale", "es-AR").param("size", size))
                .andExpect(status().isBadRequest()).andReturn());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @EnumSource(value = EstadoVersionLegal.class, names = {"VIGENTE", "REEMPLAZADA", "RETIRADA"})
    void exactVersionIsFlatOriginalAndUsesTheStateSpecificCache(EstadoVersionLegal state) throws Exception {
        when(service.document(VERSION_ID)).thenReturn(Optional.of(version(state)));

        MvcResult result = mvc.perform(get(BASE + "/{id}", VERSION_ID))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,
                        state == EstadoVersionLegal.VIGENTE ? REVALIDATE : IMMUTABLE))
                .andExpect(header().string(HttpHeaders.ETAG, documentTag(state))).andReturn();

        JsonNode body = body(result);
        assertThat(fields(body)).containsExactlyInAnyOrder("id", "tipo", "version", "titulo",
                "contenidoMarkdown", "sha256", "vigenteDesde", "estado", "locale");
        assertThat(body.path("id").textValue()).isEqualTo(VERSION_ID.toString());
        assertThat(body.path("contenidoMarkdown").textValue()).isEqualTo(MARKDOWN);
        assertThat(body.path("sha256").textValue()).isEqualTo("b".repeat(64));
        assertThat(body.path("vigenteDesde").textValue()).isEqualTo("2026-09-05T12:34:56.123456Z");
        assertThat(body.path("locale").textValue()).isEqualTo("es-AR");
        assertThat(body.path("estado").textValue()).isEqualTo(state.name());
        verify(service).document(VERSION_ID);
    }

    @Test
    void rendersWholeSecondsAndCanonicalUuidWithoutChangingTheOriginalMarkdown() throws Exception {
        LegalDocumentSummary seconds = new LegalDocumentSummary(VERSION_ID,
                TipoDocumentoLegal.TERMINOS_SERVICIO, "1.0", "Términos", "b".repeat(64),
                Instant.parse("2026-09-05T12:34:56Z"), EstadoVersionLegal.VIGENTE, LocaleLegal.ES_AR);
        when(service.document(VERSION_ID)).thenReturn(Optional.of(new LegalPublicDocumentVersion(seconds, MARKDOWN)));

        JsonNode body = body(mvc.perform(get(BASE + "/{id}", VERSION_ID.toString().toUpperCase(java.util.Locale.ROOT)))
                .andExpect(status().isOk()).andReturn());

        assertThat(body.path("id").textValue()).isEqualTo(VERSION_ID.toString());
        assertThat(body.path("vigenteDesde").textValue()).isEqualTo("2026-09-05T12:34:56Z");
        assertThat(body.path("contenidoMarkdown").textValue()).isEqualTo(MARKDOWN);
        verify(service).document(VERSION_ID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-uuid", "1-1-1-1-1", "8abcdef0123456789abcdef012345678",
            "8abcdef0-1234-5678-9abc-def01234567g", "8abcdef0-1234-5678-9abc-def0123456789"})
    void malformedUuidIsTheSame404DecisionAndNeverReachesTheFacade(String id) throws Exception {
        JsonNode body = body(mvc.perform(get(BASE + "/{id}", id).header(HttpHeaders.IF_NONE_MATCH, "*"))
                .andExpect(status().isNotFound())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().doesNotExist(HttpHeaders.ETAG)).andReturn());

        assertError(body, 404, "DOCUMENTO_LEGAL_NO_ENCONTRADO", BASE + "/" + id);
        assertThat(fields(body.path("details"))).containsExactly("versionId");
        assertThat(body.path("details").path("versionId").textValue()).isEqualTo(id);
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"*", "W/\"known-tag\""})
    void unknownOrHiddenVersionIsNeverTurnedInto304(String conditional) throws Exception {
        when(service.document(VERSION_ID)).thenReturn(Optional.empty());

        JsonNode body = body(mvc.perform(get(BASE + "/{id}", VERSION_ID)
                        .header(HttpHeaders.IF_NONE_MATCH, conditional))
                .andExpect(status().isNotFound())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().doesNotExist(HttpHeaders.ETAG)).andReturn());

        assertError(body, 404, "DOCUMENTO_LEGAL_NO_ENCONTRADO", BASE + "/" + VERSION_ID);
        assertThat(body.path("details").path("versionId").textValue()).isEqualTo(VERSION_ID.toString());
        verify(service).document(VERSION_ID);
    }

    @Test
    void catalogUnavailableUsesSupportedFilterDetailsAndHidesItsCauseEvenForWildcard() throws Exception {
        when(service.catalog(ContextoLegal.REGISTRO, LocaleLegal.ES_AR, 0, 20))
                .thenThrow(unavailable());

        MvcResult result = mvc.perform(get(BASE).param("locale", "es-AR").param("contexto", "REGISTRO")
                        .header(HttpHeaders.IF_NONE_MATCH, "*"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().doesNotExist(HttpHeaders.ETAG)).andReturn();

        assertUnavailable(result, "REGISTRO", BASE);
        verify(service).catalog(ContextoLegal.REGISTRO, LocaleLegal.ES_AR, 0, 20);
    }

    @Test
    void unfilteredCatalogUnavailableKeepsExplicitNullContext() throws Exception {
        when(service.catalog(null, LocaleLegal.ES_AR, 0, 20)).thenThrow(unavailable());

        MvcResult result = mvc.perform(get(BASE).param("locale", "es-AR")
                        .header(HttpHeaders.IF_NONE_MATCH, catalogTag(0, 20)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().doesNotExist(HttpHeaders.ETAG)).andReturn();

        assertUnavailable(result, null, BASE);
    }

    @Test
    void exactVersionUnavailableUsesNullContextAndV1Locale() throws Exception {
        when(service.document(VERSION_ID)).thenThrow(unavailable());

        MvcResult result = mvc.perform(get(BASE + "/{id}", VERSION_ID).header(HttpHeaders.IF_NONE_MATCH, "*"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().doesNotExist(HttpHeaders.ETAG)).andReturn();

        assertUnavailable(result, null, BASE + "/" + VERSION_ID);
        verify(service).document(VERSION_ID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"weak", "strong", "list", "wildcard", "multiple-headers"})
    void catalogConditionalGetsResolveTheFacadeBeforeReturning304(String mode) throws Exception {
        when(service.catalog(null, LocaleLegal.ES_AR, 0, 20)).thenReturn(catalog(null, 0, 20));
        MockHttpServletRequestBuilder request = get(BASE).param("locale", "es-AR");
        addConditional(request, mode, catalogTag(0, 20));

        mvc.perform(request).andExpect(status().isNotModified()).andExpect(content().string(""))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, REVALIDATE))
                .andExpect(header().string(HttpHeaders.ETAG, catalogTag(0, 20)));

        verify(service).catalog(null, LocaleLegal.ES_AR, 0, 20);
    }

    @ParameterizedTest
    @ValueSource(strings = {"weak", "strong", "list", "wildcard", "multiple-headers"})
    void exactConditionalGetsKeepTheirRealEtagAndImmutablePolicy(String mode) throws Exception {
        when(service.document(VERSION_ID)).thenReturn(Optional.of(version(EstadoVersionLegal.RETIRADA)));
        MockHttpServletRequestBuilder request = get(BASE + "/{id}", VERSION_ID);
        addConditional(request, mode, documentTag(EstadoVersionLegal.RETIRADA));

        mvc.perform(request).andExpect(status().isNotModified()).andExpect(content().string(""))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, IMMUTABLE))
                .andExpect(header().string(HttpHeaders.ETAG, documentTag(EstadoVersionLegal.RETIRADA)));

        verify(service).document(VERSION_ID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"stale\"", "W/\"stale\"", "unquoted-invalid-value", "\"unterminated"})
    void nonMatchingOrMalformedConditionalTagsReturnTheAccreditedRepresentation(String tag) throws Exception {
        when(service.catalog(null, LocaleLegal.ES_AR, 0, 20)).thenReturn(catalog(null, 0, 20));

        MvcResult result = mvc.perform(get(BASE).param("locale", "es-AR").header(HttpHeaders.IF_NONE_MATCH, tag))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.ETAG, catalogTag(0, 20)))
                .andReturn();

        assertThat(body(result).path("documentSetRevision").textValue()).isEqualTo(REVISION);
        verify(service).catalog(null, LocaleLegal.ES_AR, 0, 20);
    }

    @ParameterizedTest
    @ValueSource(strings = {"matching", "nonmatching"})
    void mixedIfMatchAndWildcardPreserveSpringGetPreconditionEvaluation(String mode) throws Exception {
        when(service.catalog(null, LocaleLegal.ES_AR, 0, 20)).thenReturn(catalog(null, 0, 20));
        String ifMatch = mode.equals("matching") ? catalogTag(0, 20).substring(2) : "\"stale\"";

        // Spring 7 skips If-Match on safe methods; the real ETag is still evaluated first.
        mvc.perform(get(BASE).param("locale", "es-AR").header(HttpHeaders.IF_MATCH, ifMatch)
                        .header(HttpHeaders.IF_NONE_MATCH, "*"))
                .andExpect(status().isNotModified()).andExpect(content().string(""))
                .andExpect(header().string(HttpHeaders.ETAG, catalogTag(0, 20)))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, REVALIDATE));

        verify(service).catalog(null, LocaleLegal.ES_AR, 0, 20);
    }

    @Test
    void ifMatchAloneKeepsTheSpringSafeGetBehavior() throws Exception {
        when(service.catalog(null, LocaleLegal.ES_AR, 0, 20)).thenReturn(catalog(null, 0, 20));

        mvc.perform(get(BASE).param("locale", "es-AR").header(HttpHeaders.IF_MATCH, "\"stale\""))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.ETAG, catalogTag(0, 20)));
    }

    @Test
    void errorPathsIncludeTheServletContextWithoutExposingQueryOrCause() throws Exception {
        MvcResult result = mvc.perform(get("/ordenfix" + BASE).contextPath("/ordenfix")
                        .param("locale", "unsupported"))
                .andExpect(status().isBadRequest()).andReturn();

        assertThat(body(result).path("path").textValue()).isEqualTo("/ordenfix" + BASE);
        assertThat(body(result).path("path").textValue()).doesNotContain("?");
    }

    @ParameterizedTest
    @ValueSource(strings = {"absent", "false", "true"})
    void springRegistersTheMappingsAndAdviceOnlyWithTheHttpFlag(String flag) throws Exception {
        try (AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            if (!flag.equals("absent")) {
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                        "http-flag", Map.of(LegalPublicDocumentRequestMatcher.ENABLED_PROPERTY, flag)));
            }
            context.register(WebConfiguration.class, LegalPublicDocumentController.class,
                    LegalPublicDocumentExceptionHandler.class);
            context.refresh();
            MockMvc conditional = MockMvcBuilders.webAppContextSetup(context).build();
            boolean enabled = flag.equals("true");
            assertThat(context.getBeansOfType(LegalPublicDocumentController.class)).hasSize(enabled ? 1 : 0);
            assertThat(context.getBeansOfType(LegalPublicDocumentExceptionHandler.class)).hasSize(enabled ? 1 : 0);

            conditional.perform(get(BASE)).andExpect(status().is(enabled ? 400 : 404));
            conditional.perform(get(BASE + "/not-a-uuid")).andExpect(status().isNotFound());
            verifyNoInteractions(context.getBean(LegalPublicDocumentReadService.class));
        }
    }

    private static void assertSummary(JsonNode actual, LegalDocumentSummary expected) {
        assertThat(fields(actual)).containsExactlyInAnyOrder("id", "tipo", "version", "titulo",
                "sha256", "vigenteDesde", "estado", "locale");
        assertThat(actual.path("id").textValue()).isEqualTo(expected.versionId().toString());
        assertThat(actual.path("tipo").textValue()).isEqualTo(expected.type().name());
        assertThat(actual.path("version").textValue()).isEqualTo(expected.version());
        assertThat(actual.path("titulo").textValue()).isEqualTo(expected.title());
        assertThat(actual.path("sha256").textValue()).isEqualTo(expected.sha256());
        assertThat(actual.path("vigenteDesde").textValue()).isEqualTo(expected.effectiveAtUtc());
        assertThat(actual.path("estado").textValue()).isEqualTo(expected.state().name());
        assertThat(actual.path("locale").textValue()).isEqualTo("es-AR");
    }

    private static void assertPaginationError(MvcResult result) throws Exception {
        JsonNode body = body(result);
        assertThat(body.path("status").intValue()).isEqualTo(400);
        assertThat(body.path("error").textValue()).isEqualTo("Solicitud inválida");
        assertThat(body.path("message").textValue()).isNotBlank();
        assertThat(body.has("code")).isFalse();
        assertThat(body.has("details")).isFalse();
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(result.getResponse().getHeader(HttpHeaders.ETAG)).isNull();
    }

    private static void assertError(JsonNode error, int status, String code, String path) {
        assertThat(fields(error)).containsExactlyInAnyOrder(
                "timestamp", "status", "error", "message", "path", "code", "details");
        assertThat(error.path("timestamp").isTextual()).isTrue();
        assertThat(error.path("status").intValue()).isEqualTo(status);
        assertThat(error.path("message").textValue()).isNotBlank();
        assertThat(error.path("error").textValue()).isNotBlank();
        assertThat(error.path("code").textValue()).isEqualTo(code);
        assertThat(error.path("path").textValue()).isEqualTo(path);
    }

    private static void assertUnavailable(MvcResult result, String context, String path) throws Exception {
        JsonNode body = body(result);
        assertError(body, 503, "CONTRATO_LEGAL_NO_DISPONIBLE", path);
        assertThat(body.path("message").textValue()).isEqualTo("El contrato legal no está disponible.");
        assertThat(fields(body.path("details"))).containsExactlyInAnyOrder("contexto", "locale");
        assertThat(body.path("details").has("contexto")).isTrue();
        if (context == null) {
            assertThat(body.path("details").path("contexto").isNull()).isTrue();
        } else {
            assertThat(body.path("details").path("contexto").textValue()).isEqualTo(context);
        }
        assertThat(body.path("details").path("locale").textValue()).isEqualTo("es-AR");
        assertThat(result.getResponse().getContentAsString()).doesNotContain(
                "SELECT", "secret_password", "/private", "SQLException", "stackTrace", "cause");
    }

    private static LegalPublicDocumentReadException unavailable() {
        return new LegalPublicDocumentReadException(new SQLException(
                "SELECT secret_password FROM /private/internal_table"));
    }

    private static LegalDocumentSummary summary(EstadoVersionLegal state) {
        return new LegalDocumentSummary(VERSION_ID, TipoDocumentoLegal.TERMINOS_SERVICIO,
                "1.0", "Términos \"OrdenFix\" 🙂", "b".repeat(64),
                Instant.parse("2026-09-05T12:34:56.123456Z"), state, LocaleLegal.ES_AR);
    }

    private static LegalPublicDocumentVersion version(EstadoVersionLegal state) {
        return new LegalPublicDocumentVersion(summary(state), MARKDOWN);
    }

    private static LegalPublicDocumentCatalog catalog(ContextoLegal context, int page, int size) {
        List<LegalDocumentSummary> all = List.of(summary(EstadoVersionLegal.VIGENTE),
                summary(EstadoVersionLegal.REEMPLAZADA), summary(EstadoVersionLegal.RETIRADA));
        long offset = (long) page * size;
        List<LegalDocumentSummary> documents = offset >= all.size() ? List.of()
                : all.subList((int) offset, (int) Math.min(offset + size, all.size()));
        return new LegalPublicDocumentCatalog(context, LocaleLegal.ES_AR, REVISION, documents,
                page, size, all.size(), 1 + (all.size() - 1L) / size);
    }

    private static String catalogTag(int page, int size) {
        return "W/\"" + REVISION + ":p=" + page + ":s=" + size + "\"";
    }

    private static String documentTag(EstadoVersionLegal state) {
        return "W/\"doc:" + VERSION_ID + ":" + state.name() + ":" + "b".repeat(64) + "\"";
    }

    private static void addConditional(MockHttpServletRequestBuilder request, String mode, String etag) {
        switch (mode) {
            case "weak" -> request.header(HttpHeaders.IF_NONE_MATCH, etag);
            case "strong" -> request.header(HttpHeaders.IF_NONE_MATCH, etag.substring(2));
            case "list" -> request.header(HttpHeaders.IF_NONE_MATCH, "\"stale\", " + etag);
            case "wildcard" -> request.header(HttpHeaders.IF_NONE_MATCH, "*");
            case "multiple-headers" -> request.header(HttpHeaders.IF_NONE_MATCH, "\"stale\"", etag);
            default -> throw new IllegalArgumentException(mode);
        }
    }

    private static JsonNode body(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsByteArray());
    }

    private static List<String> fields(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebMvc
    static class WebConfiguration {
        @Bean
        LegalPublicDocumentReadService service() {
            return mock(LegalPublicDocumentReadService.class);
        }
    }
}
