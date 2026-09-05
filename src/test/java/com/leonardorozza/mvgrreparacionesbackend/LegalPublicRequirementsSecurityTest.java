package com.leonardorozza.mvgrreparacionesbackend;

import com.auth0.jwt.exceptions.JWTVerificationException;
import com.leonardorozza.mvgrreparacionesbackend.config.CorsConfig;
import com.leonardorozza.mvgrreparacionesbackend.config.SecurityConfig;
import com.leonardorozza.mvgrreparacionesbackend.config.filter.JwtFilter;
import com.leonardorozza.mvgrreparacionesbackend.config.filter.PublicEndpointRateLimitFilter;
import com.leonardorozza.mvgrreparacionesbackend.config.security.LegalPublicDocumentRequestMatcher;
import com.leonardorozza.mvgrreparacionesbackend.config.security.LegalPublicRequirementsRequestMatcher;
import com.leonardorozza.mvgrreparacionesbackend.config.security.RateLimitProperties;
import com.leonardorozza.mvgrreparacionesbackend.config.tenant.TenantContext;
import com.leonardorozza.mvgrreparacionesbackend.legal.http.LegalPublicDocumentController;
import com.leonardorozza.mvgrreparacionesbackend.legal.http.LegalPublicDocumentExceptionHandler;
import com.leonardorozza.mvgrreparacionesbackend.legal.http.LegalPublicRequirementsController;
import com.leonardorozza.mvgrreparacionesbackend.legal.http.LegalPublicRequirementsExceptionHandler;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalPublicRequirementsValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalDocumentSummary;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoVersionLegal;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicDocumentCatalog;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicDocumentReadService;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicRequirementsReadService;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoActoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.UserDetailsServiceImpl;
import com.leonardorozza.mvgrreparacionesbackend.utils.jwt.JwtUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LegalPublicRequirementsSecurityTest {
    private static final String BASE = LegalPublicRequirementsRequestMatcher.BASE_PATH;
    private static final String DOCUMENTS = LegalPublicDocumentRequestMatcher.BASE_PATH;
    private static final String ORIGIN = "http://localhost:5173";

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void flagsClassifyEachRouteIndependentlyAndEnabledReadsIgnoreInvalidBearer(boolean docs, boolean requirements)
            throws Exception {
        try (Harness h = security(Boolean.toString(requirements), docs, 60)) {
            h.mvc.perform(registration().header("Authorization", "Bearer invalid-token"))
                    .andExpect(status().is(requirements ? 200 : 403));
            h.mvc.perform(get(DOCUMENTS).param("locale", "es-AR")
                            .header("Authorization", "Bearer invalid-token"))
                    .andExpect(status().is(docs ? 200 : 403));
            verify(h.jwt, times((requirements ? 0 : 1) + (docs ? 0 : 1))).verifyToken("invalid-token");
            verify(h.requirements, times(requirements ? 1 : 0)).readRegistration();
            verify(h.documents, times(docs ? 1 : 0)).catalog(null, LocaleLegal.ES_AR, 0, 20);
            verifyNoInteractions(h.users);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"absent", "false", " true", "true ", "1", "yes", ""})
    void disabledLiteralHasNoMappingAdviceOrPublicExceptions(String flag) throws Exception {
        try (Harness h = security(flag, false, 1)) {
            assertThat(h.context.getBeansOfType(LegalPublicRequirementsController.class)).isEmpty();
            assertThat(h.context.getBeansOfType(LegalPublicRequirementsExceptionHandler.class)).isEmpty();
            assertThat(h.context.getBean(RequestMappingHandlerMapping.class).getHandlerMethods().values())
                    .noneMatch(method -> method.getBeanType() == LegalPublicRequirementsController.class);
            h.mvc.perform(registration().header("Authorization", "Bearer invalid-token"))
                    .andExpect(status().isForbidden()).andExpect(header().doesNotExist("X-RateLimit-Limit"));
            verify(h.jwt).verifyToken("invalid-token");
            verifyNoInteractions(h.requirements, h.users);
        }
    }

    @Test
    void nonGetAndNeighborsRemainProtectedAndDoNotChargeRequirementsQuota() throws Exception {
        try (Harness h = security("true", true, 1)) {
            for (String method : List.of("HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")) {
                h.mvc.perform(request(HttpMethod.valueOf(method), BASE)
                                .header("Authorization", "Bearer invalid-token"))
                        .andExpect(status().isForbidden()).andExpect(header().doesNotExist("X-RateLimit-Limit"));
            }
            for (String path : List.of(BASE + "/", BASE + "/id", BASE + "/id/extra", BASE + "-admin",
                    "/api/public/otro")) {
                h.mvc.perform(get(path).header("Authorization", "Bearer invalid-token"))
                        .andExpect(status().isForbidden()).andExpect(header().doesNotExist("X-RateLimit-Limit"));
            }
            h.mvc.perform(registration()).andExpect(status().isOk())
                    .andExpect(header().string("X-RateLimit-Remaining", "0"));
            verify(h.jwt, times(11)).verifyToken("invalid-token");
            verifyNoInteractions(h.users);
        }
    }

    @Test
    void contextPathSharesTheSamePublicClassificationAndQuota() throws Exception {
        try (Harness h = security("TRUE", false, 2)) {
            h.mvc.perform(get("/ordenfix" + BASE).contextPath("/ordenfix").param("locale", "es-AR")
                            .param("contexto", "REGISTRO").header("Authorization", "Bearer invalid-token"))
                    .andExpect(status().isOk()).andExpect(header().string("X-RateLimit-Remaining", "1"));
            h.mvc.perform(registration()).andExpect(status().isOk())
                    .andExpect(header().string("X-RateLimit-Remaining", "0"));
            h.mvc.perform(registration()).andExpect(status().isTooManyRequests());
            verifyNoInteractions(h.jwt, h.users);
        }
    }

    @Test
    void successConditionalMalformedAndUnavailableReadsAllConsumeOnlyTheirOwnQuota() throws Exception {
        try (Harness h = security("true", true, 4)) {
            h.mvc.perform(registration()).andExpect(status().isOk())
                    .andExpect(header().string("X-RateLimit-Remaining", "3"));
            h.mvc.perform(registration().header("If-None-Match", "*"))
                    .andExpect(status().isNotModified()).andExpect(content().string(""))
                    .andExpect(header().string("X-RateLimit-Remaining", "2"));
            h.mvc.perform(get(BASE).param("locale", "es-AR"))
                    .andExpect(status().isBadRequest()).andExpect(header().string("X-RateLimit-Remaining", "1"));
            when(h.requirements.readRegistration()).thenThrow(new IllegalStateException("internal JDBC fixture"));
            h.mvc.perform(registration().header("If-None-Match", "*"))
                    .andExpect(status().isServiceUnavailable()).andExpect(header().doesNotExist("ETag"))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(header().string("X-RateLimit-Remaining", "0"));
            h.mvc.perform(registration().header("If-None-Match", "*").header("Origin", ORIGIN))
                    .andExpect(status().isTooManyRequests()).andExpect(header().doesNotExist("ETag"))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(header().string("Retry-After", "60"))
                    .andExpect(header().string("X-RateLimit-Limit", "4"))
                    .andExpect(header().string("X-RateLimit-Remaining", "0"))
                    .andExpect(jsonPath("$.status").value(429))
                    .andExpect(header().string("Access-Control-Expose-Headers", containsString("Retry-After")));
            h.mvc.perform(get(DOCUMENTS).param("locale", "es-AR")).andExpect(status().isOk())
                    .andExpect(header().string("X-RateLimit-Remaining", "59"));
            verify(h.requirements, times(3)).readRegistration();
            verifyNoInteractions(h.jwt, h.users);
        }
    }

    @Test
    void corsRetainsExistingOriginsAndHeadersAndPreflightDoesNotChargeOrAuthenticate() throws Exception {
        try (Harness h = security("true", false, 1)) {
            h.mvc.perform(options(BASE).header("Origin", ORIGIN)
                            .header("Access-Control-Request-Method", "GET")
                            .header("Access-Control-Request-Headers", "Authorization, If-None-Match"))
                    .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", ORIGIN))
                    .andExpect(header().doesNotExist("X-RateLimit-Limit"));
            h.mvc.perform(options(BASE).header("Origin", "https://untrusted.invalid")
                            .header("Access-Control-Request-Method", "GET"))
                    .andExpect(status().isForbidden());
            h.mvc.perform(registration().header("Origin", ORIGIN)).andExpect(status().isOk())
                    .andExpect(header().string("X-RateLimit-Remaining", "0"))
                    .andExpect(header().string("Access-Control-Expose-Headers", containsString("Authorization")))
                    .andExpect(header().string("Access-Control-Expose-Headers", containsString("Retry-After")))
                    .andExpect(header().string("Access-Control-Expose-Headers", containsString("X-RateLimit-Limit")))
                    .andExpect(header().string("Access-Control-Expose-Headers", containsString("X-RateLimit-Remaining")))
                    .andExpect(header().string("Access-Control-Expose-Headers", not(containsString("ETag"))));
            verifyNoInteractions(h.jwt, h.users);
        }
    }

    @Test
    void historicalRegisterBypassRemainsAvailableWithBothFlagsOn() throws Exception {
        try (Harness h = security("true", true, 1)) {
            // No AuthController in this fixture: 404 proves authorization passed unchanged.
            h.mvc.perform(post("/api/auth/register").servletPath("/api/auth/register")
                            .header("Authorization", "Bearer invalid-token"))
                    .andExpect(status().isNotFound()).andExpect(header().string("X-RateLimit-Limit", "5"));
            verifyNoInteractions(h.jwt, h.users, h.requirements, h.documents);
        }
    }

    private static MockHttpServletRequestBuilder registration() {
        return get(BASE).param("locale", "es-AR").param("contexto", "REGISTRO");
    }

    private static Harness security(String flag, boolean docs, int limit) {
        var context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        Map<String, Object> properties = new LinkedHashMap<>();
        if (!flag.equals("absent")) properties.put(LegalPublicRequirementsRequestMatcher.ENABLED_PROPERTY, flag);
        properties.put(LegalPublicDocumentRequestMatcher.ENABLED_PROPERTY, Boolean.toString(docs));
        properties.put("fixture.requirements-limit", limit);
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture", properties));
        context.register(WebFixture.class);
        context.refresh();
        return new Harness(context, MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build(),
                context.getBean(LegalPublicRequirementsReadService.class), context.getBean(LegalPublicDocumentReadService.class),
                context.getBean(JwtUtils.class), context.getBean(UserDetailsServiceImpl.class));
    }

    private record Harness(AnnotationConfigWebApplicationContext context, MockMvc mvc,
                           LegalPublicRequirementsReadService requirements, LegalPublicDocumentReadService documents,
                           JwtUtils jwt, UserDetailsServiceImpl users) implements AutoCloseable {
        @Override public void close() {
            context.close(); SecurityContextHolder.clearContext(); TenantContext.clear();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import({SecurityConfig.class, CorsConfig.class, JwtFilter.class, PublicEndpointRateLimitFilter.class,
            LegalPublicDocumentRequestMatcher.class, LegalPublicRequirementsRequestMatcher.class,
            LegalPublicDocumentController.class, LegalPublicDocumentExceptionHandler.class,
            LegalPublicRequirementsController.class, LegalPublicRequirementsExceptionHandler.class})
    static class WebFixture {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-09-05T00:00:00Z"), ZoneOffset.UTC); }
        @Bean RateLimitProperties rateLimitProperties(@Value("${fixture.requirements-limit}") int limit) {
            var properties = new RateLimitProperties();
            properties.setPublicLegalRequirements(new RateLimitProperties.Limit(limit, Duration.ofMinutes(1)));
            return properties;
        }
        @Bean JwtUtils jwtUtils() {
            var jwt = mock(JwtUtils.class);
            when(jwt.verifyToken("invalid-token")).thenThrow(new JWTVerificationException("fixture invalid JWT"));
            return jwt;
        }
        @Bean UserDetailsServiceImpl userDetailsService() { return mock(UserDetailsServiceImpl.class); }
        @Bean LegalPublicDocumentReadService legalPublicDocumentReadService() {
            var service = mock(LegalPublicDocumentReadService.class);
            var summary = new LegalDocumentSummary(UUID.randomUUID(), TipoDocumentoLegal.values()[0],
                    "fixture-v1", "Documento sintético", "b".repeat(64),
                    Instant.parse("2026-09-05T00:00:00Z"), EstadoVersionLegal.VIGENTE, LocaleLegal.ES_AR);
            var catalog = new LegalPublicDocumentCatalog(null, LocaleLegal.ES_AR, "sha256:" + "a".repeat(64),
                    List.of(summary), 0, 20, 1, 1);
            when(service.catalog(isNull(), eq(LocaleLegal.ES_AR), anyInt(), anyInt())).thenReturn(catalog);
            return service;
        }
        @Bean LegalPublicRequirementsReadService legalPublicRequirementsReadService() throws Exception {
            String markdown = "# Registro\n";
            String statement = "Acepto el documento sintético.";
            var document = new LegalRequiredSetProjection.DocumentProjection(UUID.randomUUID(),
                    TipoDocumentoLegal.values()[0], "fixture-v1", "Documento sintético", markdown, sha256(markdown),
                    OffsetDateTime.parse("2026-09-05T00:00:00Z"), LocaleLegal.ES_AR);
            var requirement = new LegalRequiredSetProjection.RequirementProjection(UUID.randomUUID(),
                    ContextoLegal.REGISTRO, TipoActoLegal.ACEPTACION, statement, sha256(statement), List.of(document), true);
            var value = new LegalPublicRequirementsValidator().validate(
                    new LegalRequiredSetProjection(ContextoLegal.REGISTRO, LocaleLegal.ES_AR, List.of(requirement)));
            var service = mock(LegalPublicRequirementsReadService.class);
            when(service.readRegistration()).thenReturn(value);
            return service;
        }
        private static String sha256(String text) throws Exception {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        }
    }
}
