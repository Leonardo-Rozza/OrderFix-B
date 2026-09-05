package com.leonardorozza.mvgrreparacionesbackend.legal.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leonardorozza.mvgrreparacionesbackend.config.security.LegalPublicRequirementsRequestMatcher;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.GlobalExceptionHandler;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalPublicRegistrationRequirements;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalPublicRequirementsValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection.DocumentProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection.RequirementProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicRequirementsReadException;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicRequirementsReadService;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoActoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal;
import org.erdtman.jcs.JsonCanonicalizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LegalPublicRequirementsControllerTest {

    private static final String BASE = "/api/public/requisitos-legales";
    private static final String REVALIDATE = "public, max-age=0, must-revalidate";
    private static final String FIXTURE = "/legal/manifest/public-registration-requirements-v1/";
    private static final ObjectMapper JSON = new ObjectMapper();
    private LegalPublicRequirementsReadService service;
    private MockMvc mvc;

    @BeforeEach
    void buildTheControllerAndScopedAdviceWithTheHistoricalAdvicePresent() {
        service = mock(LegalPublicRequirementsReadService.class);
        mvc = MockMvcBuilders.standaloneSetup(new LegalPublicRequirementsController(service))
                .setControllerAdvice(new GlobalExceptionHandler(), new LegalPublicRequirementsExceptionHandler())
                .build();
    }

    @Test
    void fullWireMatchesTheIndependent14AGoldenAndBothCanonicalRevisions() throws Exception {
        LegalPublicRegistrationRequirements source = golden();
        when(service.readRegistration()).thenReturn(source);

        MvcResult result = mvc.perform(validRequest())
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, REVALIDATE))
                .andExpect(header().string(HttpHeaders.ETAG, tag(source))).andReturn();

        ObjectNode wire = (ObjectNode) body(result);
        assertThat(fields(wire)).containsExactlyInAnyOrder("contexto", "locale", "requiredSetRevision", "requisitos");
        assertThat(wire.path("requiredSetRevision").textValue()).isEqualTo(fixtureText("aggregate-sha256.txt").strip())
                .isEqualTo(source.requiredSetRevision()).isNotEqualTo(source.scopeRevision());
        ObjectNode scopeWire = wire.deepCopy();
        scopeWire.remove("requiredSetRevision");
        assertThat(scopeWire).isEqualTo(JSON.readTree(fixture("projection.json")));
        byte[] canonical = new JsonCanonicalizer(scopeWire.toString()).getEncodedUTF8();
        assertThat(canonical).containsExactly(fixture("scope-canonical.json"));
        assertThat(sha256(canonical)).isEqualTo(fixtureText("scope-sha256.txt").strip());

        LegalPublicRegistrationRequirements reconstructed = new LegalPublicRequirementsValidator()
                .validate(projectionFromWire(scopeWire, ZoneOffset.UTC));
        assertThat(reconstructed.projection()).isEqualTo(source.projection());
        assertThat(reconstructed.scopeRevision()).isEqualTo(source.scopeRevision());
        assertThat(reconstructed.requiredSetRevision()).isEqualTo(source.requiredSetRevision());
        ObjectNode aggregate = (ObjectNode) JSON.readTree(fixture("aggregate-canonical.json"));
        ((ObjectNode) aggregate.path("scopes").get(0)).put("requiredSetRevision", sha256(canonical));
        assertThat(sha256(new JsonCanonicalizer(aggregate.toString()).getEncodedUTF8()))
                .isEqualTo(wire.path("requiredSetRevision").textValue());

        assertThat(wire.path("requisitos").size()).isEqualTo(2);
        assertThat(wire.path("requisitos").get(1).path("requerido").booleanValue()).isFalse();
        for (JsonNode requirement : wire.path("requisitos")) {
            assertThat(fields(requirement)).containsExactlyInAnyOrder("id", "contexto", "tipoActo",
                    "afirmacion", "afirmacionSha256", "requerido", "documentos");
            for (JsonNode document : requirement.path("documentos")) {
                assertThat(fields(document)).containsExactlyInAnyOrder("id", "tipo", "version", "titulo",
                        "contenidoMarkdown", "sha256", "vigenteDesde", "estado", "locale");
                assertThat(document.path("estado").textValue()).isEqualTo("VIGENTE");
                assertThat(document.path("locale").textValue()).isEqualTo("es-AR");
                assertThat(document.path("id").textValue())
                        .isEqualTo(UUID.fromString(document.path("id").textValue()).toString());
                assertThat(sha256(document.path("contenidoMarkdown").textValue().getBytes(StandardCharsets.UTF_8)))
                        .isEqualTo("sha256:" + document.path("sha256").textValue());
            }
        }
        verify(service).readRegistration();
    }

    @Test
    void offsetDatesRenderAsTheSameUtcGoldenIncludingWholeSecondsAndMicroseconds() throws Exception {
        LegalPublicRegistrationRequirements shifted = new LegalPublicRequirementsValidator().validate(
                projectionFromWire(JSON.readTree(fixture("projection.json")), ZoneOffset.ofHours(-3)));
        when(service.readRegistration()).thenReturn(shifted);

        ObjectNode wire = (ObjectNode) body(mvc.perform(validRequest()).andExpect(status().isOk()).andReturn());
        wire.remove("requiredSetRevision");
        assertThat(wire).isEqualTo(JSON.readTree(fixture("projection.json")));
        assertThat(wire.path("requisitos").get(0).path("documentos").get(0).path("vigenteDesde").textValue())
                .isEqualTo("2026-09-01T03:00:00.123456Z");
        assertThat(wire.path("requisitos").get(0).path("documentos").get(1).path("vigenteDesde").textValue())
                .isEqualTo("2026-09-01T03:00:00Z");
        assertThat(wire.path("requisitos").get(0).path("documentos").get(2).path("vigenteDesde").textValue())
                .isEqualTo("2026-09-01T03:00:00.000001Z");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "es-ar", "ES-AR", "es", "en-US", " es-AR", "es-AR ", "es-AR,es-AR"})
    void localeIsRequiredExactAndPreservedInItsError(String locale) throws Exception {
        MockHttpServletRequestBuilder request = get(BASE).param("contexto", "REGISTRO");
        if (locale != null) {
            request.param("locale", locale);
        }
        assertParameterError(request.header(HttpHeaders.IF_NONE_MATCH, "*"), "locale", locale);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "registro", "REGISTRO ", " REGISTRO", "TODOS", "REGISTRO,REGISTRO"})
    void contextIsRequiredExactAndPreservedInItsError(String context) throws Exception {
        MockHttpServletRequestBuilder request = get(BASE).param("locale", "es-AR");
        if (context != null) {
            request.param("contexto", context);
        }
        assertParameterError(request, "contexto", context);
    }

    @ParameterizedTest
    @EnumSource(value = ContextoLegal.class, mode = EnumSource.Mode.EXCLUDE, names = "REGISTRO")
    void knownContextsOutsideRegistrationAreRejected(ContextoLegal context) throws Exception {
        assertParameterError(get(BASE).param("locale", "es-AR").param("contexto", context.name()),
                "contexto", context.name());
    }

    @ParameterizedTest
    @MethodSource("repeatedParameters")
    void repeatedParametersAreJoinedInArrivalOrderEvenWhenValuesAreIdentical(
            String parameter, List<String> values) throws Exception {
        MockHttpServletRequestBuilder request = get(BASE);
        request.queryParam(parameter.equals("locale") ? "contexto" : "locale",
                parameter.equals("locale") ? "REGISTRO" : "es-AR");
        request.queryParam(parameter, values.toArray(String[]::new));
        assertParameterError(request, parameter, String.join(",", values));
    }

    static Stream<Arguments> repeatedParameters() {
        return Stream.of(Arguments.of("locale", List.of("es-AR", "es-AR")),
                Arguments.of("locale", List.of("es-AR", "en-US", "")),
                Arguments.of("locale", List.of("en-US", "es-AR")),
                Arguments.of("contexto", List.of("REGISTRO", "REGISTRO")),
                Arguments.of("contexto", List.of("REGISTRO", "USO_CONTINUADO", "")),
                Arguments.of("contexto", List.of("USO_CONTINUADO", "REGISTRO")));
    }

    @Test
    void localeWinsWhenBothParametersAreAbsentInvalidOrRepeated() throws Exception {
        assertParameterError(get(BASE), "locale", null);
        assertParameterError(get(BASE).param("locale", "unsupported").param("contexto", "unsupported"),
                "locale", "unsupported");
        assertParameterError(get(BASE).param("locale", "es-AR", "es-AR")
                .param("contexto", "REGISTRO", "REGISTRO"), "locale", "es-AR,es-AR");
    }

    @Test
    void errorPathKeepsContextPathWithoutQueryOrInternalDetails() throws Exception {
        MvcResult result = mvc.perform(get("/ordenfix" + BASE).contextPath("/ordenfix")
                        .queryParam("locale", "unsupported").queryParam("contexto", "REGISTRO"))
                .andExpect(status().isBadRequest()).andReturn();
        assertThat(body(result).path("path").textValue()).isEqualTo("/ordenfix" + BASE).doesNotContain("?");
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"weak", "strong", "list", "wildcard", "multiple-headers"})
    void conditionalGetsStillResolveTheCompleteFacadeBefore304(String mode) throws Exception {
        LegalPublicRegistrationRequirements source = golden();
        when(service.readRegistration()).thenReturn(source);
        MockHttpServletRequestBuilder request = validRequest();
        addConditional(request, mode, tag(source));

        mvc.perform(request).andExpect(status().isNotModified()).andExpect(content().string(""))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, REVALIDATE))
                .andExpect(header().string(HttpHeaders.ETAG, tag(source)));
        verify(service).readRegistration();
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"stale\"", "W/\"stale\"", "unquoted-invalid-value", "\"unterminated"})
    void nonmatchingAndMalformedTagsReturnTheComplete200(String candidate) throws Exception {
        LegalPublicRegistrationRequirements source = golden();
        when(service.readRegistration()).thenReturn(source);
        MvcResult result = mvc.perform(validRequest().header(HttpHeaders.IF_NONE_MATCH, candidate))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.ETAG, tag(source))).andReturn();
        assertThat(body(result).path("requiredSetRevision").textValue()).isEqualTo(source.requiredSetRevision());
        verify(service).readRegistration();
    }

    @ParameterizedTest
    @ValueSource(strings = {"matching", "nonmatching"})
    void mixedIfMatchAndWildcardKeepTheEstablishedSpringSafeGetPreconditions(String mode) throws Exception {
        LegalPublicRegistrationRequirements source = golden();
        when(service.readRegistration()).thenReturn(source);
        String ifMatch = mode.equals("matching") ? tag(source).substring(2) : "\"stale\"";

        // Spring 7 skips If-Match on safe methods; preserve that evaluation before wildcard fallback.
        mvc.perform(validRequest().header(HttpHeaders.IF_MATCH, ifMatch).header(HttpHeaders.IF_NONE_MATCH, "*"))
                .andExpect(status().isNotModified()).andExpect(content().string(""))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, REVALIDATE))
                .andExpect(header().string(HttpHeaders.ETAG, tag(source)));
        verify(service).readRegistration();
    }

    @Test
    void ifMatchAloneKeepsTheEstablishedSpringSafeGetBehavior() throws Exception {
        LegalPublicRegistrationRequirements source = golden();
        when(service.readRegistration()).thenReturn(source);
        mvc.perform(validRequest().header(HttpHeaders.IF_MATCH, "\"stale\""))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.ETAG, tag(source)));
        verify(service).readRegistration();
    }

    @ParameterizedTest
    @ValueSource(strings = {"weak", "strong", "list", "wildcard", "multiple-headers"})
    void unavailableIs503WithoutSuccessEtagEvenWithAPreviouslyValidConditional(String mode) throws Exception {
        String previousTag = tag(golden());
        LegalPublicRequirementsReadException failure = new LegalPublicRequirementsReadException(
                new SQLException("SELECT secret_password FROM /private/internal_table"));
        when(service.readRegistration()).thenThrow(failure);
        MockHttpServletRequestBuilder request = validRequest();
        addConditional(request, mode, previousTag);

        MvcResult result = mvc.perform(request).andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().doesNotExist(HttpHeaders.ETAG)).andReturn();
        assertUnavailable(result);
        assertThat(result.getResolvedException()).isInstanceOf(LegalPublicRequirementsHttpException.class)
                .hasCause(failure);
        verify(service).readRegistration();
    }

    @Test
    void completeDtoConstructionMustSucceedBeforeAWildcardCanProduce304() throws Exception {
        LegalPublicRegistrationRequirements broken = mock(LegalPublicRegistrationRequirements.class);
        IllegalStateException mappingFailure = new IllegalStateException("/private/broken-projection");
        when(broken.projection()).thenThrow(mappingFailure);
        when(service.readRegistration()).thenReturn(broken);

        MvcResult result = mvc.perform(validRequest().header(HttpHeaders.IF_NONE_MATCH, "*"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().doesNotExist(HttpHeaders.ETAG)).andReturn();
        assertUnavailable(result);
        assertThat(result.getResolvedException()).hasCause(mappingFailure);
        verify(service).readRegistration();
        verify(broken).projection();
    }

    @Test
    void scopedAdviceClearsAnAlreadyAssignedSuccessTagForAPreconditionFailure() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", BASE);
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setHeader(HttpHeaders.ETAG, "W/\"old-success\"");
        response.setHeader(HttpHeaders.CACHE_CONTROL, REVALIDATE);

        var result = new LegalPublicRequirementsExceptionHandler()
                .handle(LegalPublicRequirementsHttpException.preconditionFailed(), request, response);

        assertThat(result.getStatusCode().value()).isEqualTo(412);
        assertThat(result.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.getHeader(HttpHeaders.ETAG)).isNull();
        assertThat(Objects.requireNonNull(result.getBody()).getStatus()).isEqualTo(412);
        assertThat(result.getBody().getCode()).isNull();
        assertThat(result.getBody().getDetails()).isNull();
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"absent", "false", "true", "TRUE", "True", "on", "yes", "1", " true ", ""})
    void mappingAndAdviceUseOnlyTheLiteralTrueFlagWithoutTrimming(String flag) throws Exception {
        try (AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            if (!flag.equals("absent")) {
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                        "requirements-http-flag", Map.of(LegalPublicRequirementsRequestMatcher.ENABLED_PROPERTY, flag)));
            }
            context.register(WebConfiguration.class, LegalPublicRequirementsController.class,
                    LegalPublicRequirementsExceptionHandler.class);
            context.refresh();
            boolean enabled = "true".equalsIgnoreCase(flag);
            assertThat(context.getBeansOfType(LegalPublicRequirementsController.class)).hasSize(enabled ? 1 : 0);
            assertThat(context.getBeansOfType(LegalPublicRequirementsExceptionHandler.class)).hasSize(enabled ? 1 : 0);
            MockMvc conditional = MockMvcBuilders.webAppContextSetup(context).build();

            conditional.perform(get(BASE)).andExpect(status().is(enabled ? 400 : 404));
            conditional.perform(get(BASE + "/child")).andExpect(status().isNotFound());
            conditional.perform(get(BASE + "/")).andExpect(status().isNotFound());
            verifyNoInteractions(context.getBean(LegalPublicRequirementsReadService.class));
        }
    }

    private void assertParameterError(MockHttpServletRequestBuilder request, String parameter,
                                      String rejected) throws Exception {
        MvcResult result = mvc.perform(request).andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().doesNotExist(HttpHeaders.ETAG)).andReturn();
        JsonNode error = body(result);
        boolean locale = parameter.equals("locale");
        assertError(error, 400, locale ? "LOCALE_LEGAL_NO_SOPORTADO" : "CONTEXTO_LEGAL_NO_SOPORTADO");
        String supported = locale ? "localesSoportados" : "contextosSoportados";
        assertThat(fields(error.path("details"))).containsExactlyInAnyOrder(parameter, supported);
        JsonNode value = error.path("details").path(parameter);
        assertThat(error.path("details").has(parameter)).isTrue();
        if (rejected == null) {
            assertThat(value.isNull()).isTrue();
        } else {
            assertThat(value.textValue()).isEqualTo(rejected);
        }
        assertThat(error.path("details").path(supported))
                .isEqualTo(JSON.readTree(locale ? "[\"es-AR\"]" : "[\"REGISTRO\"]"));
        verifyNoInteractions(service);
    }

    private static void assertUnavailable(MvcResult result) throws Exception {
        JsonNode error = body(result);
        assertError(error, 503, "CONTRATO_LEGAL_NO_DISPONIBLE");
        assertThat(error.path("message").textValue()).isEqualTo("El contrato legal no está disponible.");
        assertThat(error.path("details")).isEqualTo(JSON.readTree("{\"contexto\":\"REGISTRO\",\"locale\":\"es-AR\"}"));
        assertThat(result.getResponse().getContentAsString()).doesNotContain(
                "SELECT", "secret_password", "/private", "SQLException", "stackTrace", "cause");
    }

    private static void assertError(JsonNode error, int status, String code) {
        assertThat(fields(error)).containsExactlyInAnyOrder("timestamp", "status", "error", "message", "path", "code", "details");
        assertThat(error.path("timestamp").isTextual()).isTrue();
        assertThat(error.path("status").intValue()).isEqualTo(status);
        assertThat(error.path("code").textValue()).isEqualTo(code);
        assertThat(error.path("path").textValue()).isEqualTo(BASE);
        assertThat(error.path("message").textValue()).isNotBlank();
        assertThat(error.path("error").textValue()).isNotBlank();
    }

    private static MockHttpServletRequestBuilder validRequest() {
        return get(BASE).queryParam("locale", "es-AR").queryParam("contexto", "REGISTRO");
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

    private static String tag(LegalPublicRegistrationRequirements source) {
        return "W/\"" + source.requiredSetRevision() + "\"";
    }

    private static LegalPublicRegistrationRequirements golden() throws Exception {
        return new LegalPublicRequirementsValidator().validate(
                projectionFromWire(JSON.readTree(fixture("projection.json")), ZoneOffset.UTC));
    }

    private static LegalRequiredSetProjection projectionFromWire(JsonNode root, ZoneOffset offset) {
        List<RequirementProjection> requirements = new ArrayList<>();
        for (JsonNode member : root.path("requisitos")) {
            List<DocumentProjection> documents = new ArrayList<>();
            for (JsonNode document : member.path("documentos")) {
                documents.add(new DocumentProjection(UUID.fromString(document.path("id").textValue()),
                        TipoDocumentoLegal.valueOf(document.path("tipo").textValue()),
                        document.path("version").textValue(), document.path("titulo").textValue(),
                        document.path("contenidoMarkdown").textValue(), document.path("sha256").textValue(),
                        OffsetDateTime.parse(document.path("vigenteDesde").textValue()).withOffsetSameInstant(offset),
                        LocaleLegal.fromCodigo(document.path("locale").textValue())));
            }
            requirements.add(new RequirementProjection(UUID.fromString(member.path("id").textValue()),
                    ContextoLegal.valueOf(member.path("contexto").textValue()),
                    TipoActoLegal.valueOf(member.path("tipoActo").textValue()),
                    member.path("afirmacion").textValue(), member.path("afirmacionSha256").textValue(),
                    documents, member.path("requerido").booleanValue()));
        }
        return new LegalRequiredSetProjection(ContextoLegal.valueOf(root.path("contexto").textValue()),
                LocaleLegal.fromCodigo(root.path("locale").textValue()), requirements);
    }

    private static byte[] fixture(String name) throws Exception {
        try (InputStream stream = LegalPublicRequirementsControllerTest.class.getResourceAsStream(FIXTURE + name)) {
            return Objects.requireNonNull(stream, name).readAllBytes();
        }
    }

    private static String fixtureText(String name) throws Exception {
        return new String(fixture(name), StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static JsonNode body(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsByteArray());
    }

    private static List<String> fields(JsonNode node) {
        List<String> result = new ArrayList<>();
        node.fieldNames().forEachRemaining(result::add);
        return result;
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebMvc
    static class WebConfiguration {
        @Bean
        LegalPublicRequirementsReadService service() {
            return mock(LegalPublicRequirementsReadService.class);
        }
    }
}
