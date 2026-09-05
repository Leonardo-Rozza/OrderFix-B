package com.leonardorozza.mvgrreparacionesbackend.config.security;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LegalPublicRequirementsRequestMatcherTest {

    private static final String BASE = "/api/public/requisitos-legales";
    private final LegalPublicRequirementsRequestMatcher enabled = new LegalPublicRequirementsRequestMatcher(true);

    @Test
    void classifiesOnlyTheExactGetBeforeValidatingItsQueries() {
        assertThat(LegalPublicRequirementsRequestMatcher.BASE_PATH).isEqualTo(BASE);
        assertThat(LegalPublicRequirementsRequestMatcher.ENABLED_PROPERTY)
                .isEqualTo("ordenfix.legal.public-requirements.enabled");
        for (String query : new String[]{"contexto=REGISTRO&locale=es-AR", "locale=invalid", "", "unknown=value"}) {
            MockHttpServletRequest request = request("GET", BASE);
            request.setQueryString(query);
            assertThat(enabled.matches(request)).as(query).isTrue();
            assertThat(new LegalPublicRequirementsRequestMatcher(false).matches(request)).isFalse();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE", "get"})
    void noOtherMethodInheritsThePublicGetException(String method) {
        assertThat(enabled.matches(request(method, BASE))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/public", "/api/public/documentos-legales",
            "/api/public/requisitos-legales-extra", "/api/public/requisitos-legales/",
            "/api/public/requisitos-legales//", "/api/public/requisitos-legales/id",
            "/api/public/requisitos-legales/123e4567-e89b-12d3-a456-426614174000",
            "/api/public/requisitos-legales/id/more", "/api/public/requisitos-legales;jsessionid=fixture",
            "/api/public/requisitos-legales%2F", "/api/public/requisitos-legales/..",
            "/prefix/api/public/requisitos-legales", "/API/public/requisitos-legales"})
    void neighborsDocumentIdsAndAdditionalSegmentsStayProtected(String path) {
        assertThat(enabled.matches(request("GET", path))).isFalse();
    }

    @Test
    void removesAVerifiedContextPathExactlyOnce() {
        for (String context : new String[]{"/ordenfix", "/nested/ordenfix"}) {
            MockHttpServletRequest valid = request("GET", context + BASE);
            valid.setContextPath(context);
            assertThat(enabled.matches(valid)).as(context).isTrue();
        }
        for (String path : new String[]{"/ordenfix/ordenfix" + BASE, "/ordenfix-other" + BASE, BASE}) {
            MockHttpServletRequest invalid = request("GET", path);
            invalid.setContextPath("/ordenfix");
            assertThat(enabled.matches(invalid)).as(path).isFalse();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "ordenfix", "/ordenfix/"})
    void malformedContextPathsCannotOpenTheException(String context) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn(context + BASE);
        when(request.getContextPath()).thenReturn(context);
        assertThat(enabled.matches(request)).isFalse();
    }

    @Test
    void missingClassificationInputsFailClosed() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn(BASE);
        assertThat(enabled.matches(request)).isFalse(); // contextPath is null.
        when(request.getContextPath()).thenReturn("");
        when(request.getRequestURI()).thenReturn(null);
        assertThat(enabled.matches(request)).isFalse();
        when(request.getRequestURI()).thenReturn(BASE);
        when(request.getMethod()).thenReturn(null);
        assertThat(enabled.matches(request)).isFalse();
    }

    @Test
    void servletPathCannotOverrideTheAuthoritativeRequestUri() {
        MockHttpServletRequest neighbor = request("GET", "/api/public/documentos-legales");
        neighbor.setServletPath(BASE);
        assertThat(enabled.matches(neighbor)).isFalse();
        MockHttpServletRequest exact = request("GET", BASE);
        exact.setServletPath("/api/public/documentos-legales");
        assertThat(enabled.matches(exact)).isTrue();
    }

    @Test
    void literalTrueWithoutTrimmingMatchesTheControllerConditionAndDefaultsToDisabled() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(LegalPublicRequirementsRequestMatcher.class, ConditionalRequirementsMarker.class);
        runner.run(context -> {
            assertThat(context.getBean(LegalPublicRequirementsRequestMatcher.class)
                    .matches(request("GET", BASE))).isFalse();
            assertThat(context.getBeansOfType(ConditionalRequirementsMarker.class)).isEmpty();
        });
        for (String value : new String[]{"true", "TRUE", "TrUe", "false", "yes", "on", "1", "", " true", "true ", "\ttrue"}) {
            boolean expected = "true".equalsIgnoreCase(value);
            assertThat(new LegalPublicRequirementsRequestMatcher(value).matches(request("GET", BASE)))
                    .as("direct constructor '%s'", value).isEqualTo(expected);
            runner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                            new MapPropertySource("exact-requirements-flag", Map.of(
                                    LegalPublicRequirementsRequestMatcher.ENABLED_PROPERTY, value))))
                    .run(context -> {
                        assertThat(context.getBean(LegalPublicRequirementsRequestMatcher.class)
                                .matches(request("GET", BASE))).as(value).isEqualTo(expected);
                        assertThat(!context.getBeansOfType(ConditionalRequirementsMarker.class).isEmpty())
                                .as("matching controller condition for '%s'", value).isEqualTo(expected);
                    });
        }
        assertThat(new LegalPublicRequirementsRequestMatcher((String) null).matches(request("GET", BASE))).isFalse();
    }

    @Test
    void enablingTheDocumentOrInternalContextFlagDoesNotEnableRequirementsHttp() {
        new ApplicationContextRunner()
                .withUserConfiguration(LegalPublicRequirementsRequestMatcher.class, ConditionalRequirementsMarker.class)
                .withPropertyValues("ordenfix.legal.public-documents.enabled=true",
                        "ordenfix.legal.public-requirements-context.enabled=true")
                .run(context -> {
                    assertThat(context.getBean(LegalPublicRequirementsRequestMatcher.class)
                            .matches(request("GET", BASE))).isFalse();
                    assertThat(context.getBeansOfType(ConditionalRequirementsMarker.class)).isEmpty();
                });
    }

    @TestConfiguration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = LegalPublicRequirementsRequestMatcher.ENABLED_PROPERTY, havingValue = "true")
    static class ConditionalRequirementsMarker { }

    private static MockHttpServletRequest request(String method, String path) {
        return new MockHttpServletRequest(method, path);
    }
}
