package com.leonardorozza.mvgrreparacionesbackend.config.security;

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

class LegalPublicDocumentRequestMatcherTest {

    private final LegalPublicDocumentRequestMatcher enabled = new LegalPublicDocumentRequestMatcher(true);
    private static final String BASE = LegalPublicDocumentRequestMatcher.BASE_PATH;

    @Test
    void classifiesTheCatalogAndOneDocumentSegmentBeforeUuidValidation() {
        for (String path : new String[]{BASE, BASE + "/1", BASE + "/not-a-uuid",
                BASE + "/123e4567-e89b-12d3-a456-426614174000"}) {
            MockHttpServletRequest request = request("GET", path);
            request.setQueryString("locale=es-AR&page=0&size=20");
            assertThat(enabled.matches(request)).as(path).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE", "get"})
    void noOtherMethodInheritsTheGetException(String method) {
        assertThat(enabled.matches(request(method, BASE))).isFalse();
        assertThat(enabled.matches(request(method, BASE + "/not-a-uuid"))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/public", "/api/public/requisitos-legales",
            "/api/public/documentos-legales-extra", "/api/public/documentos-legales/",
            "/api/public/documentos-legales//", "/api/public/documentos-legales/id/",
            "/api/public/documentos-legales/id/more", "/api/public/documentos-legales//id",
            "/prefix/api/public/documentos-legales", "/API/public/documentos-legales"})
    void neighborsAndAdditionalSegmentsStayProtected(String path) {
        assertThat(enabled.matches(request("GET", path))).isFalse();
    }

    @Test
    void removesAVerifiedContextPathExactlyOnce() {
        for (String context : new String[]{"/ordenfix", "/nested/ordenfix"}) {
            for (String suffix : new String[]{"", "/not-a-uuid"}) {
                MockHttpServletRequest request = request("GET", context + BASE + suffix);
                request.setContextPath(context);
                assertThat(enabled.matches(request)).isTrue();
            }
        }
        MockHttpServletRequest repeated = request("GET", "/ordenfix/ordenfix" + BASE);
        repeated.setContextPath("/ordenfix");
        assertThat(enabled.matches(repeated)).isFalse();
        MockHttpServletRequest partialPrefix = request("GET", "/ordenfix-other" + BASE);
        partialPrefix.setContextPath("/ordenfix");
        assertThat(enabled.matches(partialPrefix)).isFalse();
        MockHttpServletRequest absentPrefix = request("GET", BASE);
        absentPrefix.setContextPath("/ordenfix");
        assertThat(enabled.matches(absentPrefix)).isFalse();
    }

    @Test
    void servletPathCannotDisagreeWithTheSharedRequestUriClassification() {
        MockHttpServletRequest neighbor = request("GET", "/api/public/requisitos-legales");
        neighbor.setServletPath(BASE);
        assertThat(enabled.matches(neighbor)).isFalse();
        MockHttpServletRequest catalog = request("GET", BASE);
        catalog.setServletPath("/api/public/requisitos-legales");
        assertThat(enabled.matches(catalog)).isTrue();
    }

    @Test
    void thePropertyIsDisabledByDefaultAndOnlyItsEnabledValueOpensTheClassifier() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(LegalPublicDocumentRequestMatcher.class, ConditionalDocumentMarker.class);
        runner.run(context -> assertThat(context.getBean(LegalPublicDocumentRequestMatcher.class)
                .matches(request("GET", BASE))).isFalse());
        for (String value : new String[]{"true", "TRUE", "True", "false", "yes", "on", "1", " true "}) {
            boolean expected = "true".equalsIgnoreCase(value);
            runner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                            new MapPropertySource("exact-document-flag", Map.of(
                                    LegalPublicDocumentRequestMatcher.ENABLED_PROPERTY, value))))
                    .run(context -> {
                        assertThat(context.getBean(LegalPublicDocumentRequestMatcher.class)
                                .matches(request("GET", BASE + "/bad"))).as(value).isEqualTo(expected);
                        assertThat(!context.getBeansOfType(ConditionalDocumentMarker.class).isEmpty())
                                .as("matching controller condition for '%s'", value).isEqualTo(expected);
                    });
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = LegalPublicDocumentRequestMatcher.ENABLED_PROPERTY, havingValue = "true")
    static class ConditionalDocumentMarker { }

    private static MockHttpServletRequest request(String method, String path) {
        return new MockHttpServletRequest(method, path);
    }
}
