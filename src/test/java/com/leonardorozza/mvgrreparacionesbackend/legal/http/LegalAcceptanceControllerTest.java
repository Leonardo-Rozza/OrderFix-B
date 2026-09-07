package com.leonardorozza.mvgrreparacionesbackend.legal.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.GlobalExceptionHandler;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceInput;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceInputException;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceFailure;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceReceipt;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceService;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceValidationException;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Taller;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.User;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Transport-only service doubles; transaction/actor evidence belongs to the PostgreSQL HTTP tests. */
class LegalAcceptanceControllerTest {
    private static final String BASE = "/api/aceptaciones-legales";
    private static final String KEY = "64f89458-294c-4df5-a88d-833a1f1abcde";
    private static final String REVISION = "sha256:" + "a".repeat(64);
    private static final String BODY = "{\"requiredSetRevision\":\"" + REVISION + "\",\"aceptacionesLegales\":[]}";
    private static final String SECRET = "synthetic SQL password HMAC IP=192.0.2.91 UA=private-agent";
    private static final ObjectMapper JSON = new ObjectMapper();
    private LegalAcceptanceService service;
    private AuthenticatedUserPrincipal principal;
    private LegalRequestMetadataResolver resolver;
    private MockMvc mvc;

    @BeforeEach void prepare() {
        service = mock(LegalAcceptanceService.class);
        principal = principal(UserRole.ADMIN); authenticate(principal);
        resolver = new LegalRequestMetadataResolver(List.of());
        mvc = standalone(new LegalAcceptanceController(service, resolver));
    }
    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @ParameterizedTest @EnumSource(UserRole.class)
    void bothRolesUseOnlyTheDeferredServerPrincipalApiAndReceiveAnEmpty204(UserRole role) throws Exception {
        principal = principal(role); authenticate(principal);
        AtomicReference<LegalAcceptanceInput> observed = parseOnInvocation(receipt(LegalAcceptanceReceipt.Kind.EMPTY, false));
        var result = mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", KEY)
                        .header("User-Agent", "synthetic-agent").header("X-Forwarded-For", "malformed ignored header")
                        .header(HttpHeaders.IF_NONE_MATCH, "*").accept(MediaType.TEXT_HTML).content(BODY)
                        .with(request -> { request.setRemoteAddr("192.0.2.8"); return request; }))
                .andExpect(status().isNoContent()).andExpect(content().bytes(new byte[0]))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().doesNotExist(HttpHeaders.ETAG)).andExpect(header().doesNotExist(HttpHeaders.CONTENT_TYPE))
                .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER)).andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).isEmpty();
        assertThat(observed.get().idempotencyKey()).isEqualTo(KEY);
        assertThat(observed.get().requiredSetRevision()).isEqualTo(REVISION);
        assertThat(observed.get().metadata().ipAddress()).isEqualTo("192.0.2.8");
        verify(service).accept(eq(principal), any(LegalAcceptanceInput.Reader.class));
        verify(service, never()).accept(any(), anyString(), anyString(), anyList(), any());
    }

    @ParameterizedTest @EnumSource(LegalAcceptanceReceipt.Kind.class)
    void everyCommittedKindAndReplayHasTheSameWireWithoutRevealingTechnicalIds(LegalAcceptanceReceipt.Kind kind) throws Exception {
        for (boolean replay : List.of(false, true)) {
            var receipt = receipt(kind, replay);
            when(service.accept(eq(principal), any(LegalAcceptanceInput.Reader.class))).thenReturn(receipt);
            mvc.perform(post(BASE)).andExpect(status().isNoContent()).andExpect(content().bytes(new byte[0]))
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                    .andExpect(header().doesNotExist(HttpHeaders.ETAG));
        }
    }

    @ParameterizedTest @CsvSource({"INVALID_KEY,IDEMPOTENCY_KEY_INVALIDA", "REQUIRED_KEY,IDEMPOTENCY_KEY_REQUERIDA",
            "INVALID_PAYLOAD,ACEPTACION_LEGAL_INVALIDA"})
    void neutralInputDecisionsBecomeExact400OnlyAfterTheServiceAccreditsRollback(
            LegalAcceptanceInputException.Reason reason, String code) throws Exception {
        var failure = failure(LegalAcceptanceFailure.Reason.INVALID_PAYLOAD);
        when(failure.inputReason()).thenReturn(Optional.of(reason));
        when(service.accept(eq(principal), any(LegalAcceptanceInput.Reader.class))).thenThrow(failure);
        JsonNode error = error(mvc.perform(post(BASE).accept(MediaType.TEXT_HTML)).andExpect(status().isBadRequest()).andReturn(), 400, code);
        if (reason == LegalAcceptanceInputException.Reason.INVALID_PAYLOAD) {
            assertThat(error.path("details")).isEqualTo(JSON.readTree("{\"motivos\":[\"PAYLOAD_LEGAL_INCOMPLETO\"]}"));
        } else assertThat(error.path("details")).isEqualTo(JSON.readTree("{\"header\":\"Idempotency-Key\"}"));
    }

    @Test void deferredProtocolReaderDoesNotLetMvcMediaTypeChecksOvertakeAnInvalidKey() throws Exception {
        parseOnInvocation(receipt(LegalAcceptanceReceipt.Kind.EMPTY, false));
        JsonNode error = error(mvc.perform(post(BASE).contentType(MediaType.TEXT_PLAIN).queryParam("userId", "other")
                        .header("Idempotency-Key", "invalid").content("{"))
                .andExpect(status().isBadRequest()).andReturn(), 400, "IDEMPOTENCY_KEY_INVALIDA");
        assertThat(error.path("details").path("header").asText()).isEqualTo("Idempotency-Key");
    }

    @Test void completePayloadWithoutAKeyUsesTheRequiredHeaderDecisionThroughTheDeferredReader() throws Exception {
        parseOnInvocation(receipt(LegalAcceptanceReceipt.Kind.EMPTY, false));
        error(mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest()).andReturn(), 400, "IDEMPOTENCY_KEY_REQUERIDA");
    }

    @ParameterizedTest @CsvSource({"KEY_REUSED,IDEMPOTENCY_KEY_REUTILIZADA", "IN_PROGRESS,IDEMPOTENCY_EN_PROGRESO"})
    void idempotencyConflictsHaveTheExactOperationAndOnlyInProgressHasRetryAfter(
            LegalAcceptanceFailure.Reason reason, String code) throws Exception {
        var failure = failure(reason);
        when(service.accept(eq(principal), any(LegalAcceptanceInput.Reader.class))).thenThrow(failure);
        var result = mvc.perform(post(BASE)).andExpect(status().isConflict()).andReturn();
        JsonNode error = error(result, 409, code);
        assertThat(error.path("details")).isEqualTo(JSON.readTree("{\"operacion\":\"ACEPTACION_LEGAL\"}"));
        assertThat(result.getResponse().getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo(reason == LegalAcceptanceFailure.Reason.IN_PROGRESS ? "1" : null);
    }

    @Test void semanticRejectionKeepsOnlyTheCanonicalOrderedMotivos() throws Exception {
        var failure = failure(LegalAcceptanceFailure.Reason.INVALID);
        var validation = mock(LegalAcceptanceValidationException.class);
        when(validation.reason()).thenReturn(LegalAcceptanceValidationException.Reason.INVALID);
        when(validation.motivos()).thenReturn(List.of(LegalAcceptanceValidationException.Motivo.CONFIRMACION_REQUERIDA,
                LegalAcceptanceValidationException.Motivo.REQUISITO_FALTANTE));
        when(failure.validation()).thenReturn(Optional.of(validation));
        when(service.accept(eq(principal), any(LegalAcceptanceInput.Reader.class))).thenThrow(failure);
        JsonNode error = error(mvc.perform(post(BASE)).andExpect(status().isBadRequest()).andReturn(), 400, "ACEPTACION_LEGAL_INVALIDA");
        assertThat(error.path("details")).isEqualTo(JSON.readTree("{\"motivos\":[\"REQUISITO_FALTANTE\",\"CONFIRMACION_REQUERIDA\"]}"));
    }

    @ParameterizedTest @ValueSource(strings = {"unavailable", "runtime", "null", "unknown-commit"})
    void operationalAndUncertainResultsStay503AndJsonEvenWithIncompatibleAccept(String variant) throws Exception {
        if (variant.equals("runtime")) when(service.accept(eq(principal), any(LegalAcceptanceInput.Reader.class)))
                .thenThrow(new IllegalStateException(SECRET));
        else if (!variant.equals("null")) {
            var failure = failure(LegalAcceptanceFailure.Reason.UNAVAILABLE);
            if (variant.equals("unknown-commit")) {
                when(failure.reason()).thenReturn(LegalAcceptanceFailure.Reason.INVALID_PAYLOAD);
                when(failure.completion()).thenReturn(LegalAcceptanceFailure.Completion.UNKNOWN);
                when(failure.persistence()).thenReturn(LegalAcceptanceFailure.Persistence.UNKNOWN);
            }
            when(service.accept(eq(principal), any(LegalAcceptanceInput.Reader.class))).thenThrow(failure);
        }
        var result = mvc.perform(post(BASE).accept(MediaType.TEXT_PLAIN)).andExpect(status().isServiceUnavailable()).andReturn();
        JsonNode error = error(result, 503, "CONTRATO_LEGAL_NO_DISPONIBLE");
        assertThat(error.path("details")).isEqualTo(JSON.readTree("{\"contexto\":null,\"locale\":\"es-AR\"}"));
        assertThat(result.getResponse().getHeader(HttpHeaders.RETRY_AFTER)).isNull();
    }

    @Test void absentOrGenericPrincipalCannotBecomeTheServerActor() throws Exception {
        SecurityContextHolder.clearContext();
        error(mvc.perform(post(BASE)).andExpect(status().isUnauthorized()).andReturn(), 401, null);
        var generic = org.springframework.security.core.userdetails.User.withUsername("generic").password("unused").roles("ADMIN").build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(generic, null, generic.getAuthorities()));
        error(mvc.perform(post(BASE)).andExpect(status().isUnauthorized()).andReturn(), 401, null);
        verifyNoInteractions(service);
    }

    @Test void persistedActorRejectionCanBeReturnedWithoutTheControllerReadingThePayload() throws Exception {
        var failure = failure(LegalAcceptanceFailure.Reason.INVALID_ACTOR);
        when(service.accept(eq(principal), any(LegalAcceptanceInput.Reader.class))).thenThrow(failure);
        error(mvc.perform(post(BASE).contentType(MediaType.TEXT_PLAIN).header("Idempotency-Key", "invalid").content("{"))
                .andExpect(status().isUnauthorized()).andReturn(), 401, null);
    }

    @ParameterizedTest @ValueSource(strings = {"/api/aceptaciones%2dlegales", "/api/aceptaciones%2Dlegales"})
    void anAliasNormalizedByMvcCannotReachTheService(String alias) throws Exception {
        var result = mvc.perform(post(URI.create(alias))).andExpect(status().isNotFound()).andReturn();
        error(result, 404, null); verifyNoInteractions(service);
    }

    @Test void anUnsupportedRoleIsForbiddenBeforeTheReaderOrService() throws Exception {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("legal-test", Map.of(
                    "ordenfix.legal.account-read.enabled", "true", "ordenfix.legal.account-acceptance.enabled", "true")));
            context.register(MethodSecurity.class);
            context.registerBean(LegalAcceptanceController.class, () -> new LegalAcceptanceController(service, resolver));
            context.refresh();
            var outsider = org.springframework.security.core.userdetails.User.withUsername("outsider").password("unused").roles("AUDITOR").build();
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(outsider, null, outsider.getAuthorities()));
            error(standalone(context.getBean(LegalAcceptanceController.class)).perform(post(BASE))
                    .andExpect(status().isForbidden()).andReturn(), 403, null);
            verifyNoInteractions(service);
        }
    }

    @Test void adviceClearsPriorCacheAndRetryHeadersAndNeverEchoesSecurityDiagnostics() {
        var request = new MockHttpServletRequest("POST", BASE);
        var response = new MockHttpServletResponse();
        response.setHeader(HttpHeaders.ETAG, "old-tag"); response.setHeader(HttpHeaders.RETRY_AFTER, "100");
        var result = new LegalAcceptanceExceptionHandler().forbidden(new AccessDeniedException(SECRET), request, response);
        assertThat(result.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(result.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.getHeader(HttpHeaders.ETAG)).isNull(); assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isNull();
        assertThat(result.getBody().getMessage()).doesNotContain(SECRET);
    }

    private AtomicReference<LegalAcceptanceInput> parseOnInvocation(LegalAcceptanceReceipt receipt) {
        var observed = new AtomicReference<LegalAcceptanceInput>();
        doAnswer(invocation -> {
            try { observed.set(invocation.getArgument(1, LegalAcceptanceInput.Reader.class).read(() -> { })); }
            catch (LegalAcceptanceInputException rejected) {
                var failure = failure(LegalAcceptanceFailure.Reason.INVALID_PAYLOAD);
                when(failure.inputReason()).thenReturn(Optional.of(rejected.reason()));
                throw failure;
            }
            return receipt;
        }).when(service).accept(eq(principal), any(LegalAcceptanceInput.Reader.class));
        return observed;
    }

    private static LegalAcceptanceFailure failure(LegalAcceptanceFailure.Reason reason) {
        var failure = mock(LegalAcceptanceFailure.class);
        when(failure.reason()).thenReturn(reason); when(failure.completion()).thenReturn(LegalAcceptanceFailure.Completion.ROLLED_BACK);
        when(failure.persistence()).thenReturn(LegalAcceptanceFailure.Persistence.NOT_PERSISTED);
        when(failure.validation()).thenReturn(Optional.empty()); when(failure.confirmedReceipt()).thenReturn(Optional.empty());
        when(failure.inputReason()).thenReturn(Optional.empty()); when(failure.getMessage()).thenReturn(SECRET);
        return failure;
    }
    private static LegalAcceptanceReceipt receipt(LegalAcceptanceReceipt.Kind kind, boolean replay) {
        return new LegalAcceptanceReceipt(kind, replay, kind == LegalAcceptanceReceipt.Kind.WITH_ACTS ? new UUID(0, 40) : null,
                kind == LegalAcceptanceReceipt.Kind.EMPTY ? List.of() : List.of(new UUID(0, 41)));
    }
    private static AuthenticatedUserPrincipal principal(UserRole role) {
        return new AuthenticatedUserPrincipal(User.builder().id(51L).email("actor@example.invalid").username("Actor")
                .password("unused").role(role).active(true).tokenVersion(7L)
                .taller(Taller.builder().id(72L).nombre("Taller").activo(true).build()).build());
    }
    private static void authenticate(AuthenticatedUserPrincipal principal) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
    private static MockMvc standalone(LegalAcceptanceController controller) {
        return MockMvcBuilders.standaloneSetup(controller).setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler(), new LegalAcceptanceExceptionHandler()).build();
    }
    private static JsonNode error(MvcResult result, int status, String code) throws Exception {
        var response = result.getResponse();
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.getHeader(HttpHeaders.ETAG)).isNull();
        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_JSON_VALUE);
        JsonNode error = JSON.readTree(response.getContentAsByteArray());
        List<String> names = new ArrayList<>(); error.fieldNames().forEachRemaining(names::add);
        if (code == null) assertThat(names).containsExactlyInAnyOrder("timestamp", "status", "error", "message", "path");
        else assertThat(names).containsExactlyInAnyOrder("timestamp", "status", "error", "message", "path", "code", "details");
        assertThat(LocalDateTime.parse(error.path("timestamp").asText())).isNotNull();
        assertThat(error.path("status").asInt()).isEqualTo(status);
        assertThat(error.path("code").textValue()).isEqualTo(code);
        assertThat(error.path("path").asText()).isEqualTo(result.getRequest().getRequestURI());
        assertThat(error.path("message").asText()).isNotBlank();
        assertThat(response.getContentAsString()).doesNotContain(SECRET, "stackTrace", "cause", "suppressed", "password", "HMAC", "192.0.2.91");
        return error;
    }
    @TestConfiguration(proxyBeanMethods = false) @EnableMethodSecurity static class MethodSecurity { }
}
