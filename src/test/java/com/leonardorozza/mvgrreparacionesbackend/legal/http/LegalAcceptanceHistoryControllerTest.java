package com.leonardorozza.mvgrreparacionesbackend.legal.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.GlobalExceptionHandler;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceHistoryPage;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceHistoryPage.Acceptance;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceHistoryPage.Document;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceHistoryReadException;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceHistoryService;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalActorSnapshotException;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Taller;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.User;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoActoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LegalAcceptanceHistoryControllerTest {
    private static final String BASE = "/api/aceptaciones-legales";
    private static final ObjectMapper JSON = new ObjectMapper();
    private LegalAcceptanceHistoryService service;
    private AuthenticatedUserPrincipal principal;
    private MockMvc mvc;

    @BeforeEach void prepare() {
        service = mock(LegalAcceptanceHistoryService.class);
        principal = principal(UserRole.ADMIN);
        authenticate(principal);
        mvc = standalone(new LegalAcceptanceHistoryController(service));
    }

    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @ParameterizedTest @EnumSource(UserRole.class)
    void exactWirePreservesOriginalEvidenceAndDocumentOrderForBothRoles(UserRole role) throws Exception {
        principal = principal(role); authenticate(principal);
        LegalAcceptanceHistoryPage source = page(0, 20, 2);
        when(service.read(principal, null, 0, 20)).thenReturn(source);
        MvcResult result = mvc.perform(get(BASE)).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(header().doesNotExist(HttpHeaders.ETAG)).andReturn();

        JsonNode wire = body(result);
        assertThat(fields(wire)).containsExactlyInAnyOrder("content", "page");
        assertThat(wire.path("page")).isEqualTo(JSON.readTree("{\"size\":20,\"number\":0,\"totalElements\":2,\"totalPages\":1}"));
        assertThat(wire.path("content").size()).isEqualTo(2);
        assertThat(wire.path("content").get(0).path("id").textValue()).isEqualTo(id(31).toString());
        assertThat(wire.path("content").get(1).path("id").textValue()).isEqualTo(id(21).toString());
        for (int index = 0; index < 2; index++) {
            JsonNode item = wire.path("content").get(index);
            Acceptance original = source.content().get(index);
            assertThat(fields(item)).containsExactlyInAnyOrder("id", "requisitoVersionId", "contexto", "tipoActo",
                    "afirmacion", "afirmacionSha256", "documentos", "aceptadoEn");
            assertThat(item.path("requisitoVersionId").textValue()).isEqualTo(original.requirementVersionId().toString());
            assertThat(item.path("contexto").textValue()).isEqualTo(original.context().name());
            assertThat(item.path("tipoActo").textValue()).isEqualTo(original.actType().name());
            assertThat(item.path("afirmacion").textValue()).isEqualTo(original.statement());
            assertThat(item.path("afirmacionSha256").textValue()).isEqualTo(sha(original.statement()));
            assertThat(item.path("aceptadoEn").textValue()).isEqualTo(original.acceptedAt().toString());
            assertThat(item.path("documentos").size()).isEqualTo(2);
            for (int documentIndex = 0; documentIndex < 2; documentIndex++) {
                JsonNode document = item.path("documentos").get(documentIndex);
                Document snapshot = original.documents().get(documentIndex);
                assertThat(fields(document)).containsExactlyInAnyOrder("documentoVersionId", "tipo", "version", "titulo", "sha256");
                assertThat(document.path("documentoVersionId").textValue()).isEqualTo(snapshot.documentVersionId().toString());
                assertThat(document.path("tipo").textValue()).isEqualTo(snapshot.type().name());
                assertThat(document.path("version").textValue()).isEqualTo(snapshot.version());
                assertThat(document.path("titulo").textValue()).isEqualTo(snapshot.title());
                assertThat(document.path("sha256").textValue()).isEqualTo(snapshot.sha256());
            }
        }
        assertThat(result.getResponse().getContentAsString()).doesNotContain("userId", "tallerId", "role", "audience",
                "requiredSetRevision", "scopeRevision", "metadata", "userAgent", "contenidoMarkdown", "heredado", "estado");
        verify(service).read(principal, null, 0, 20);
    }

    @Test void requestedPageRetainsTotalAndOriginalPageNumberEvenBeyondTheEnd() throws Exception {
        when(service.read(principal, ContextoLegal.CIERRE_CUENTA, Integer.MAX_VALUE, 100))
                .thenReturn(new LegalAcceptanceHistoryPage(List.of(), Integer.MAX_VALUE, 100, 209L, 3));
        JsonNode wire = body(mvc.perform(get(BASE).queryParam("contexto", "CIERRE_CUENTA")
                        .queryParam("page", Integer.toString(Integer.MAX_VALUE)).queryParam("size", "100"))
                .andExpect(status().isOk()).andReturn());
        assertThat(wire.path("content").isEmpty()).isTrue();
        assertThat(wire.path("page").path("number").intValue()).isEqualTo(Integer.MAX_VALUE);
        assertThat(wire.path("page").path("totalElements").longValue()).isEqualTo(209L);
        assertThat(wire.path("page").path("totalPages").intValue()).isEqualTo(3);
        verify(service).read(principal, ContextoLegal.CIERRE_CUENTA, Integer.MAX_VALUE, 100);
    }

    @ParameterizedTest @EnumSource(ContextoLegal.class)
    void eachContractualContextIsAnOwnHistoryFilter(ContextoLegal context) throws Exception {
        when(service.read(principal, context, 0, 1)).thenReturn(new LegalAcceptanceHistoryPage(List.of(), 0, 1, 0, 0));
        JsonNode wire = body(mvc.perform(get(BASE).queryParam("contexto", context.name()).queryParam("size", "1"))
                .andExpect(status().isOk()).andReturn());
        assertThat(wire.path("content").isEmpty()).isTrue();
        assertThat(wire.path("page").path("totalPages").intValue()).isZero();
        verify(service).read(principal, context, 0, 1);
    }

    @ParameterizedTest @ValueSource(strings = {"*", "\"sha256:old\"", "W/\"sha256:old\"", "malformed"})
    void conditionalRequestsStillReadAndReturn200WithoutEtag(String validator) throws Exception {
        when(service.read(principal, null, 0, 20)).thenReturn(page(0, 20, 2));
        mvc.perform(get(BASE).header(HttpHeaders.IF_NONE_MATCH, validator).header(HttpHeaders.IF_MATCH, "\"old\""))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(header().doesNotExist(HttpHeaders.ETAG));
        verify(service).read(principal, null, 0, 20);
    }

    @ParameterizedTest @CsvSource({"page,-1", "page,+1", "page,2147483648", "page,1.0", "page,1e2", "page,１２",
            "size,0", "size,101", "size,-1", "size,2147483648", "size,1.0"})
    void rejectsInvalidDecimalOrOutOfBoundsPaginationWithoutService(String name, String value) throws Exception {
        MvcResult result = mvc.perform(get(BASE).queryParam(name, value)).andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().doesNotExist(HttpHeaders.ETAG)).andReturn();
        assertGenericError(result, 400); verifyNoInteractions(service);
    }

    @ParameterizedTest @ValueSource(strings = {"page", "size"})
    void rejectsBlankWhitespaceAndRepeatedPagination(String name) throws Exception {
        for (String value : List.of("", " ", " 1", "1 ", "\t1")) {
            mvc.perform(get(BASE).queryParam(name, value)).andExpect(status().isBadRequest());
        }
        mvc.perform(get(BASE).queryParam(name, "1", "1")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @ParameterizedTest @ValueSource(strings = {"actor", "userId", "tallerId", "tenant", "perfil", "audiencia", "rol", "contextos", "locale", "sort", "unknown"})
    void rejectsSelectorsAndUnknownInputsWithoutEchoingValues(String name) throws Exception {
        MvcResult result = mvc.perform(get(BASE).queryParam(name, "secret-selector"))
                .andExpect(status().isBadRequest()).andReturn();
        assertGenericError(result, 400);
        assertThat(result.getResponse().getContentAsString()).doesNotContain("secret-selector");
        verifyNoInteractions(service);
    }

    @ParameterizedTest @ValueSource(strings = {"", " ", "uso_continuado", " USO_CONTINUADO", "USO_CONTINUADO ", "OTRO"})
    void unsupportedContextReturnsTheTypedContractAndEverySupportedValue(String value) throws Exception {
        MvcResult result = mvc.perform(get(BASE).queryParam("contexto", value)).andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().doesNotExist(HttpHeaders.ETAG)).andReturn();
        JsonNode error = body(result);
        assertThat(error.path("code").textValue()).isEqualTo("CONTEXTO_LEGAL_NO_SOPORTADO");
        assertThat(error.path("details").path("contexto").textValue()).isEqualTo(value);
        assertThat(error.path("details").path("contextosSoportados"))
                .isEqualTo(JSON.valueToTree(Arrays.stream(ContextoLegal.values()).map(Enum::name).toList()));
        verifyNoInteractions(service);
    }

    @Test void repeatedContextIsAmbiguousAndCannotSelectAnyHistory() throws Exception {
        MvcResult result = mvc.perform(get(BASE).queryParam("contexto", "USO_CONTINUADO", "CIERRE_CUENTA"))
                .andExpect(status().isBadRequest()).andReturn();
        assertGenericError(result, 400); verifyNoInteractions(service);
    }

    @Test void implicitHeadDoesNotReadEvidence() throws Exception {
        mvc.perform(head(BASE)).andExpect(status().isMethodNotAllowed()).andExpect(header().string(HttpHeaders.ALLOW, "GET"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
        verifyNoInteractions(service);
    }

    @ParameterizedTest @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void unsupportedMethodsPreserveTheExistingUnmappedPolicyAndDoNotRead(String method) throws Exception {
        mvc.perform(request(HttpMethod.valueOf(method), BASE)).andExpect(status().isInternalServerError());
        verifyNoInteractions(service);
    }

    @ParameterizedTest @ValueSource(strings = {"/", "/child", "-otro"})
    void neighborsPreserveTheExistingUnmappedPolicyAndDoNotRead(String suffix) throws Exception {
        mvc.perform(get(BASE + suffix)).andExpect(status().isInternalServerError()); verifyNoInteractions(service);
    }

    @ParameterizedTest @ValueSource(strings = {"/api/aceptaciones%2dlegales", "/api/aceptaciones%2Dlegales"})
    void encodedAliasDecodedByMvcIsStillNotTheContractedPath(String path) throws Exception {
        mvc.perform(get(URI.create(path))).andExpect(status().isNotFound())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
        verifyNoInteractions(service);
    }

    @Test void unauthenticatedOrGenericPrincipalCannotStandInForServerPrincipal() throws Exception {
        SecurityContextHolder.clearContext();
        assertGenericError(mvc.perform(get(BASE)).andExpect(status().isUnauthorized()).andReturn(), 401);
        var generic = org.springframework.security.core.userdetails.User.withUsername("other").password("ignored").roles("ADMIN").build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(generic, null, generic.getAuthorities()));
        assertGenericError(mvc.perform(get(BASE)).andExpect(status().isUnauthorized()).andReturn(), 401);
        verifyNoInteractions(service);
    }

    @Test void staleLockedActorReturns401WithoutIdentityDetails() throws Exception {
        var failure = new LegalActorSnapshotException();
        when(service.read(principal, null, 0, 20)).thenThrow(failure);
        MvcResult result = mvc.perform(get(BASE)).andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().doesNotExist(HttpHeaders.ETAG)).andReturn();
        assertGenericError(result, 401); assertThat(result.getResolvedException()).hasCause(failure);
    }

    @ParameterizedTest @ValueSource(strings = {"reader", "unexpected", "null"})
    void everyReadOrMappingFailureIsASanitized503WithRequestedContext(String mode) throws Exception {
        if (mode.equals("reader")) when(service.read(principal, ContextoLegal.USO_CONTINUADO, 0, 20))
                .thenThrow(new LegalAcceptanceHistoryReadException(new SQLException("SELECT secret_password FROM /private/history")));
        else if (mode.equals("unexpected")) when(service.read(principal, ContextoLegal.USO_CONTINUADO, 0, 20))
                .thenThrow(new IllegalStateException("/private/history"));
        else when(service.read(principal, ContextoLegal.USO_CONTINUADO, 0, 20)).thenReturn(null);
        MvcResult result = mvc.perform(get(BASE).queryParam("contexto", "USO_CONTINUADO").header(HttpHeaders.IF_NONE_MATCH, "*"))
                .andExpect(status().isServiceUnavailable()).andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().doesNotExist(HttpHeaders.ETAG)).andReturn();
        JsonNode error = body(result);
        assertThat(fields(error)).containsExactlyInAnyOrder("timestamp", "status", "error", "message", "path", "code", "details");
        assertThat(error.path("code").textValue()).isEqualTo("CONTRATO_LEGAL_NO_DISPONIBLE");
        assertThat(error.path("message").textValue()).isEqualTo("El contrato legal no está disponible.");
        assertThat(error.path("details")).isEqualTo(JSON.readTree("{\"contexto\":\"USO_CONTINUADO\",\"locale\":\"es-AR\"}"));
        assertThat(result.getResponse().getContentAsString()).doesNotContain("SELECT", "secret_password", "/private", "SQLException", "cause", "stackTrace");
    }

    @Test void unfilteredUnavailableReadRetainsNullContext() throws Exception {
        when(service.read(principal, null, 0, 20)).thenThrow(new LegalAcceptanceHistoryReadException());
        JsonNode error = body(mvc.perform(get(BASE)).andExpect(status().isServiceUnavailable()).andReturn());
        assertThat(error.path("details")).isEqualTo(JSON.readTree("{\"contexto\":null,\"locale\":\"es-AR\"}"));
    }

    @Test void unsupportedAuthorityIsForbiddenBeforeAnyObservation() throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("history-test", Map.of("ordenfix.legal.account-read.enabled", "true")));
            context.register(MethodSecurity.class);
            context.registerBean(LegalAcceptanceHistoryService.class, () -> service);
            context.registerBean(LegalAcceptanceHistoryController.class, () -> new LegalAcceptanceHistoryController(service));
            context.refresh();
            var outsider = org.springframework.security.core.userdetails.User.withUsername("outsider").password("ignored").roles("AUDITOR").build();
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(outsider, null, outsider.getAuthorities()));
            MvcResult result = standalone(context.getBean(LegalAcceptanceHistoryController.class)).perform(get(BASE))
                    .andExpect(status().isForbidden()).andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store")).andReturn();
            assertGenericError(result, 403); verifyNoInteractions(service);
        }
    }

    @Test void adviceClearsSuccessTagAndDoesNotPublishInternalSecurityMessage() {
        var request = new MockHttpServletRequest("GET", BASE);
        var response = new MockHttpServletResponse(); response.setHeader(HttpHeaders.ETAG, "W/\"stale\"");
        var result = new LegalAcceptanceHistoryExceptionHandler().forbidden(new AccessDeniedException("private-role"), request, response);
        assertThat(result.getStatusCode().value()).isEqualTo(403);
        assertThat(result.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.getHeader(HttpHeaders.ETAG)).isNull();
        assertThat(result.getBody().getMessage()).doesNotContain("private-role");
    }

    private static LegalAcceptanceHistoryPage page(int page, int size, long total) throws Exception {
        var docs = List.of(new Document(id(200), TipoDocumentoLegal.TERMINOS_SERVICIO, "v1-original", "Términos originales", "a".repeat(64)),
                new Document(id(100), TipoDocumentoLegal.POLITICA_PRIVACIDAD, "v1", "Privacidad original", "b".repeat(64)));
        String first = "Acepto la afirmación original, antes de su reemplazo.";
        String second = "Acepto el acto real de cierre.\nSin evidencia heredada.";
        return new LegalAcceptanceHistoryPage(List.of(
                new Acceptance(id(31), id(30), ContextoLegal.USO_CONTINUADO, TipoActoLegal.ACEPTACION, first, sha(first), docs, Instant.parse("2026-09-06T03:00:00.123456Z")),
                new Acceptance(id(21), id(20), ContextoLegal.CIERRE_CUENTA, TipoActoLegal.ACEPTACION, second, sha(second), docs, Instant.parse("2026-09-06T03:00:00Z"))),
                page, size, total, (int) ((total + size - 1) / size));
    }

    private static MockMvc standalone(LegalAcceptanceHistoryController controller) {
        return MockMvcBuilders.standaloneSetup(controller).setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler(), new LegalAcceptanceHistoryExceptionHandler()).build();
    }
    private static void authenticate(AuthenticatedUserPrincipal principal) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
    private static AuthenticatedUserPrincipal principal(UserRole role) {
        return new AuthenticatedUserPrincipal(User.builder().id(51L).email("actor@example.invalid").username("Actor")
                .password("ignored").role(role).active(true).tokenVersion(7L)
                .taller(Taller.builder().id(72L).nombre("Taller").activo(true).build()).build());
    }
    private static UUID id(long value) { return new UUID(0, value); }
    private static String sha(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
    private static JsonNode body(MvcResult result) throws Exception { return JSON.readTree(result.getResponse().getContentAsByteArray()); }
    private static List<String> fields(JsonNode node) { List<String> result = new ArrayList<>(); node.fieldNames().forEachRemaining(result::add); return result; }
    private static void assertGenericError(MvcResult result, int status) throws Exception {
        JsonNode error = body(result);
        assertThat(fields(error)).containsExactlyInAnyOrder("timestamp", "status", "error", "message", "path");
        assertThat(error.path("status").intValue()).isEqualTo(status);
        assertThat(error.path("path").textValue()).isEqualTo(BASE);
        assertThat(error.path("message").textValue()).isNotBlank();
    }
    @Configuration(proxyBeanMethods = false) @EnableMethodSecurity static class MethodSecurity { }
}
