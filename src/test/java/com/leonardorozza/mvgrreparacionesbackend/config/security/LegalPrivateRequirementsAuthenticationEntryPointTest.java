package com.leonardorozza.mvgrreparacionesbackend.config.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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

    private void assertFallback(MockHttpServletRequest request) throws Exception {
        var response = respond(request);
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getErrorMessage()).isEqualTo("Forbidden");
        assertThat(response.getContentAsByteArray()).isEmpty();
    }

    private MockHttpServletResponse respond(MockHttpServletRequest request) throws Exception {
        var response = new MockHttpServletResponse();
        entryPoint.commence(request, response, new BadCredentialsException("internal-secret"));
        return response;
    }
}
