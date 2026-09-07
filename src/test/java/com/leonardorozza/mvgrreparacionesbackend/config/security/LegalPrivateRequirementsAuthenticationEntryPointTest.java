package com.leonardorozza.mvgrreparacionesbackend.config.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leonardorozza.mvgrreparacionesbackend.legal.http.LegalAcceptanceHttpConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegalPrivateRequirementsAuthenticationEntryPointTest {
    private static final String ROOT = LegalPrivateRequirementsAuthenticationEntryPoint.BASE_PATH;
    private final LegalPrivateRequirementsAuthenticationEntryPoint entryPoint =
            new LegalPrivateRequirementsAuthenticationEntryPoint();

    @ParameterizedTest
    @CsvSource({"'',/api/requisitos-legales", "/ordenfix,/api/requisitos-legales",
            "'',/api/aceptaciones-legales", "/ordenfix,/api/aceptaciones-legales"})
    void exactPrivateGetReturnsOnlyTheSanitized401Envelope(String context, String path) throws Exception {
        var request = new MockHttpServletRequest("GET", context + path);
        request.setContextPath(context);
        request.addHeader("Authorization", "Bearer jwt-secret");
        request.addHeader("If-None-Match", "*");
        request.addParameter("tenant", "another-tenant");
        var response = respond(request);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("ETag")).isNull();
        assertThat(response.getContentType()).isEqualTo("application/json");
        var body = new ObjectMapper().readTree(response.getContentAsByteArray());
        List<String> fields = new ArrayList<>();
        body.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("timestamp", "status", "error", "message", "path");
        assertThat(body.path("timestamp").isTextual()).isTrue();
        assertThat(java.time.LocalDateTime.parse(body.path("timestamp").asText())).isNotNull();
        assertThat(body.path("status").asInt()).isEqualTo(401);
        assertThat(body.path("path").asText()).isEqualTo(context + path);
        assertThat(body.path("message").asText()).isEqualTo("Se requiere una sesión válida");
        assertThat(response.getContentAsString()).doesNotContain("jwt-secret", "another-tenant", "internal-secret");
    }

    @ParameterizedTest
    @ValueSource(strings = {"HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "get"})
    void otherMethodsPreserveTheOriginalForbiddenEntryPoint(String method) throws Exception {
        assertFallback(new MockHttpServletRequest(method, ROOT));
        assertFallback(new MockHttpServletRequest(method, LegalPrivateRequirementsAuthenticationEntryPoint.HISTORY_PATH));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/requisitos-legales/", "/api/requisitos-legales/id", "/api/requisitos-legales-admin",
            "/api/public/requisitos-legales", "/api/aceptaciones-legales/",
            "/api/aceptaciones-legales/id", "/api/aceptaciones-legales-admin", "/api/aceptaciones%2dlegales", "/api/requisitos%2dlegales",
            "/api/requisitos-legales;anything", "/api/requisitos-legales//", "/api/auth/login"})
    void otherPathsPreserveTheOriginalForbiddenEntryPoint(String path) throws Exception {
        assertFallback(new MockHttpServletRequest("GET", path));
    }

    @Test
    void theTwoControllersHaveSeparateExactClassifications() {
        var requirements = new MockHttpServletRequest("GET", ROOT);
        var history = new MockHttpServletRequest("GET", LegalPrivateRequirementsAuthenticationEntryPoint.HISTORY_PATH);
        assertThat(LegalPrivateRequirementsAuthenticationEntryPoint.isPrivateGet(requirements)).isTrue();
        assertThat(LegalPrivateRequirementsAuthenticationEntryPoint.isPrivateGet(history)).isFalse();
        assertThat(LegalPrivateRequirementsAuthenticationEntryPoint.isHistoryGet(history)).isTrue();
        assertThat(LegalPrivateRequirementsAuthenticationEntryPoint.isHistoryGet(requirements)).isFalse();
    }

    @Test
    void inconsistentContextPathDoesNotBroadenThePrivateEntryPoint() throws Exception {
        var request = new MockHttpServletRequest("GET", "/ordenfix" + ROOT);
        request.setContextPath("/other");
        assertFallback(request);
        request.setContextPath("ordenfix");
        assertFallback(request);
        request.setContextPath("/ordenfix/");
        assertFallback(request);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/ordenfix"})
    void enabledExactPostReturnsSanitized401WithoutConsumingItsInput(String context) throws Exception {
        var enabled = enabledEntryPoint();
        var request = new MockHttpServletRequest("POST", context + LegalPrivateRequirementsAuthenticationEntryPoint.HISTORY_PATH) {
            @Override public jakarta.servlet.ServletInputStream getInputStream() {
                throw new AssertionError("Anonymous requests must not consume their body");
            }
        };
        request.setContextPath(context);
        request.addHeader("Authorization", "Bearer synthetic-jwt-secret");
        request.addHeader("Idempotency-Key", "synthetic-key-secret");
        request.addHeader("If-None-Match", "*");
        var response = new MockHttpServletResponse();
        response.setHeader("Retry-After", "99");
        enabled.commence(request, response, new BadCredentialsException("internal-secret"));
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("ETag")).isNull();
        assertThat(response.getHeader("Retry-After")).isNull();
        assertThat(response.getContentType()).isEqualTo("application/json");
        var body = new ObjectMapper().readTree(response.getContentAsByteArray());
        assertThat(body.path("status").asInt()).isEqualTo(401);
        assertThat(body.path("message").asText()).isEqualTo("Se requiere una sesión válida");
        assertThat(body.path("path").asText()).isEqualTo(request.getRequestURI());
        assertThat(response.getContentAsString()).doesNotContain("synthetic-jwt-secret", "synthetic-key-secret", "internal-secret");
        assertThat(LegalPrivateRequirementsAuthenticationEntryPoint.isAcceptancePost(request)).isTrue();
        assertThat(LegalPrivateRequirementsAuthenticationEntryPoint.isHistoryGet(request)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"absent", "false"})
    void acceptanceFlagOffPreservesBothPrivateGetsAndThePostFallback(String flag) throws Exception {
        var environment = new MockEnvironment();
        if (!flag.equals("absent")) environment.setProperty(LegalAcceptanceHttpConfiguration.ENABLED_PROPERTY, flag);
        var disabled = new LegalPrivateRequirementsAuthenticationEntryPoint(environment);
        for (String path : List.of(ROOT, LegalPrivateRequirementsAuthenticationEntryPoint.HISTORY_PATH)) {
            assertThat(respond(disabled, new MockHttpServletRequest("GET", path)).getStatus()).isEqualTo(401);
            assertThat(respond(disabled, new MockHttpServletRequest("POST", path)).getStatus()).isEqualTo(403);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"TRUE", "FALSE", "true ", " true", "1", "yes", ""})
    void acceptanceFlagCannotBeEnabledByCoercion(String flag) {
        var environment = new MockEnvironment().withProperty(LegalAcceptanceHttpConfiguration.ENABLED_PROPERTY, flag);
        assertThatThrownBy(() -> new LegalPrivateRequirementsAuthenticationEntryPoint(environment))
                .isInstanceOf(IllegalArgumentException.class).hasNoCause();
    }

    @ParameterizedTest
    @ValueSource(strings = {"HEAD", "PUT", "PATCH", "DELETE", "OPTIONS", "post", "get"})
    void enabledPostDoesNotBroadenOtherMethods(String method) throws Exception {
        var request = new MockHttpServletRequest(method, LegalPrivateRequirementsAuthenticationEntryPoint.HISTORY_PATH);
        assertThat(respond(enabledEntryPoint(), request).getStatus()).isEqualTo(403);
        assertThat(LegalPrivateRequirementsAuthenticationEntryPoint.isAcceptancePost(request)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/aceptaciones-legales/", "/api/aceptaciones-legales/id", "/api/aceptaciones-legales-admin",
            "/api/aceptaciones%2dlegales", "/api/aceptaciones-legales;anything", "/api/aceptaciones-legales//",
            "/api/requisitos-legales", "/api/auth/login", "/api/seguimiento/token"})
    void enabledPostDoesNotBroadenOtherPaths(String path) throws Exception {
        var request = new MockHttpServletRequest("POST", path);
        assertThat(respond(enabledEntryPoint(), request).getStatus()).isEqualTo(403);
        assertThat(LegalPrivateRequirementsAuthenticationEntryPoint.isAcceptancePost(request)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/other", "ordenfix", "/ordenfix/"})
    void enabledPostRejectsAnInconsistentContextPath(String context) throws Exception {
        var request = new MockHttpServletRequest("POST", "/ordenfix" + LegalPrivateRequirementsAuthenticationEntryPoint.HISTORY_PATH);
        request.setContextPath(context);
        assertThat(respond(enabledEntryPoint(), request).getStatus()).isEqualTo(403);
        assertThat(LegalPrivateRequirementsAuthenticationEntryPoint.isAcceptancePost(request)).isFalse();
    }

    @Test
    void springUsesTheEnvironmentConstructorWhileKeepingTheReadConditional() throws Exception {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.setEnvironment(new MockEnvironment()
                    .withProperty("ordenfix.legal.account-read.enabled", "true")
                    .withProperty(LegalAcceptanceHttpConfiguration.ENABLED_PROPERTY, "true"));
            context.register(LegalPrivateRequirementsAuthenticationEntryPoint.class);
            context.refresh();
            var configured = context.getBean(LegalPrivateRequirementsAuthenticationEntryPoint.class);
            assertThat(respond(configured, new MockHttpServletRequest("POST",
                    LegalPrivateRequirementsAuthenticationEntryPoint.HISTORY_PATH)).getStatus()).isEqualTo(401);
            assertThat(respond(configured, new MockHttpServletRequest("GET", ROOT)).getStatus()).isEqualTo(401);
            assertThat(respond(configured, new MockHttpServletRequest("GET",
                    LegalPrivateRequirementsAuthenticationEntryPoint.HISTORY_PATH)).getStatus()).isEqualTo(401);
        }
        try (var context = new AnnotationConfigApplicationContext()) {
            context.setEnvironment(new MockEnvironment());
            context.register(LegalPrivateRequirementsAuthenticationEntryPoint.class);
            context.refresh();
            assertThat(context.getBeansOfType(LegalPrivateRequirementsAuthenticationEntryPoint.class)).isEmpty();
        }
    }

    private static LegalPrivateRequirementsAuthenticationEntryPoint enabledEntryPoint() {
        return new LegalPrivateRequirementsAuthenticationEntryPoint(new MockEnvironment()
                .withProperty(LegalAcceptanceHttpConfiguration.ENABLED_PROPERTY, "true"));
    }

    private void assertFallback(MockHttpServletRequest request) throws Exception {
        var response = respond(request);
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getErrorMessage()).isEqualTo("Forbidden");
        assertThat(response.getContentAsByteArray()).isEmpty();
    }

    private MockHttpServletResponse respond(MockHttpServletRequest request) throws Exception {
        return respond(entryPoint, request);
    }

    private static MockHttpServletResponse respond(LegalPrivateRequirementsAuthenticationEntryPoint selected,
                                                   MockHttpServletRequest request) throws Exception {
        var response = new MockHttpServletResponse();
        selected.commence(request, response, new BadCredentialsException("internal-secret"));
        return response;
    }
}
