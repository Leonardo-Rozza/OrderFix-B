package com.leonardorozza.mvgrreparacionesbackend;

import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.leonardorozza.mvgrreparacionesbackend.config.CorsConfig;
import com.leonardorozza.mvgrreparacionesbackend.config.SecurityConfig;
import com.leonardorozza.mvgrreparacionesbackend.config.filter.JwtFilter;
import com.leonardorozza.mvgrreparacionesbackend.config.filter.PublicEndpointRateLimitFilter;
import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.config.security.LegalPublicDocumentRequestMatcher;
import com.leonardorozza.mvgrreparacionesbackend.config.security.LegalPublicRequirementsRequestMatcher;
import com.leonardorozza.mvgrreparacionesbackend.config.security.RateLimitProperties;
import com.leonardorozza.mvgrreparacionesbackend.config.tenant.TenantContext;
import com.leonardorozza.mvgrreparacionesbackend.legal.http.LegalPublicDocumentController;
import com.leonardorozza.mvgrreparacionesbackend.legal.http.LegalPublicDocumentExceptionHandler;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalDocumentSummary;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicDocumentCatalog;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicDocumentReadService;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicDocumentVersion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoVersionLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.UserDetailsServiceImpl;
import com.leonardorozza.mvgrreparacionesbackend.utils.jwt.JwtUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LegalPublicDocumentSecurityTest {

    private static final String BASE = LegalPublicDocumentRequestMatcher.BASE_PATH;
    private static final UUID ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    private static final String REVISION = "sha256:" + "a".repeat(64);
    private static final String ORIGIN = "http://localhost:5173";

    @Test
    void bothExactGetsArePublicAndIgnoreAnInvalidBearerWithoutLoadingAnActor() throws Exception {
        try (SecurityHarness harness = security(true, 60)) {
            for (boolean bearer : new boolean[]{false, true}) {
                var catalog = get(BASE).param("locale", "es-AR");
                var document = get(BASE + "/" + ID);
                if (bearer) {
                    catalog.header("Authorization", "Bearer invalid-token");
                    document.header("Authorization", "Bearer invalid-token");
                }
                harness.mvc().perform(catalog).andExpect(status().isOk())
                        .andExpect(jsonPath("$.documentSetRevision").value(REVISION))
                        .andExpect(header().string("X-RateLimit-Limit", "60"));
                harness.mvc().perform(document).andExpect(status().isOk())
                        .andExpect(jsonPath("$.id").value(ID.toString()));
            }
            verifyNoInteractions(harness.jwt(), harness.users());
        }
    }

    @Test
    void malformedUuidRemainsAPublic404BeforeTheFacadeOrJwtAreConsulted() throws Exception {
        try (SecurityHarness harness = security(true, 60)) {
            harness.mvc().perform(get(BASE + "/not-a-uuid")
                            .header("Authorization", "Bearer invalid-token"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("DOCUMENTO_LEGAL_NO_ENCONTRADO"))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(header().string("X-RateLimit-Remaining", "59"));
            verifyNoInteractions(harness.service(), harness.jwt(), harness.users());
        }
    }

    @Test
    void headOtherMethodsAndNeighborsKeepAuthenticationAndNeverConsumeThePublicQuota() throws Exception {
        try (SecurityHarness harness = security(true, 60)) {
            for (String method : new String[]{"HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"}) {
                harness.mvc().perform(request(HttpMethod.valueOf(method), BASE)
                                .header("Authorization", "Bearer invalid-token"))
                        .andExpect(status().isForbidden())
                        .andExpect(header().doesNotExist("X-RateLimit-Limit"));
            }
            for (String path : new String[]{BASE + "/", BASE + "/id/extra", BASE + "-extra",
                    "/api/public/requisitos-legales", "/api/public/documentos-legales-admin"}) {
                harness.mvc().perform(get(path).header("Authorization", "Bearer invalid-token"))
                        .andExpect(status().isForbidden())
                        .andExpect(header().doesNotExist("X-RateLimit-Limit"));
            }
            verify(harness.jwt(), times(11)).verifyToken("invalid-token");
            verifyNoInteractions(harness.users(), harness.service());
        }
    }

    @Test
    void aVerifiedContextPathUsesTheSameSecurityJwtAndRateClassification() throws Exception {
        try (SecurityHarness harness = security(true, 60)) {
            harness.mvc().perform(get("/ordenfix" + BASE).contextPath("/ordenfix")
                            .param("locale", "es-AR").header("Authorization", "Bearer invalid-token"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-RateLimit-Remaining", "59"));
            harness.mvc().perform(get("/ordenfix" + BASE + "/bad").contextPath("/ordenfix")
                            .header("Authorization", "Bearer invalid-token"))
                    .andExpect(status().isNotFound())
                    .andExpect(header().string("X-RateLimit-Remaining", "58"));
            harness.mvc().perform(get("/ordenfix" + BASE + "/id/extra").contextPath("/ordenfix")
                            .header("Authorization", "Bearer invalid-token"))
                    .andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist("X-RateLimit-Limit"));
            verify(harness.jwt(), times(1)).verifyToken("invalid-token");
            verifyNoInteractions(harness.users());
        }
    }

    @Test
    void absentOrDisabledFlagAddsNeitherMappingsNorAuthenticationOrRateExceptions() throws Exception {
        for (Boolean flag : new Boolean[]{null, false}) {
            try (SecurityHarness harness = security(flag, 60)) {
                assertThat(harness.context().getBeansOfType(LegalPublicDocumentController.class)).isEmpty();
                assertThat(harness.context().getBeansOfType(LegalPublicDocumentExceptionHandler.class)).isEmpty();
                assertThat(harness.context().getBean(RequestMappingHandlerMapping.class).getHandlerMethods().values())
                        .noneMatch(handler -> handler.getBeanType() == LegalPublicDocumentController.class);
                for (String path : new String[]{BASE, BASE + "/bad"}) {
                    harness.mvc().perform(get(path).header("Authorization", "Bearer invalid-token"))
                            .andExpect(status().isForbidden())
                            .andExpect(header().doesNotExist("X-RateLimit-Limit"));
                }
                verify(harness.jwt(), times(2)).verifyToken("invalid-token");
                verifyNoInteractions(harness.users(), harness.service());
                harness.mvc().perform(options(BASE).header("Origin", ORIGIN)
                                .header("Access-Control-Request-Method", "GET"))
                        .andExpect(status().isOk());
            }
        }
    }

    @Test
    void corsHandlesPreflightWithoutJwtAndExposesAuthorizationAndAllRateHeaders() throws Exception {
        try (SecurityHarness harness = security(true, 60)) {
            harness.mvc().perform(options(BASE).header("Origin", ORIGIN)
                            .header("Access-Control-Request-Method", "GET")
                            .header("Access-Control-Request-Headers", "Authorization, If-None-Match"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", ORIGIN))
                    .andExpect(header().string("Access-Control-Allow-Methods", containsString("GET")))
                    .andExpect(header().doesNotExist("X-RateLimit-Limit"));
            harness.mvc().perform(get(BASE).param("locale", "es-AR").header("Origin", ORIGIN))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-RateLimit-Remaining", "59"))
                    .andExpect(header().string("Access-Control-Expose-Headers", containsString("Authorization")))
                    .andExpect(header().string("Access-Control-Expose-Headers", containsString("Retry-After")))
                    .andExpect(header().string("Access-Control-Expose-Headers", containsString("X-RateLimit-Limit")))
                    .andExpect(header().string("Access-Control-Expose-Headers", containsString("X-RateLimit-Remaining")));
            harness.mvc().perform(options(BASE).header("Origin", "https://untrusted.invalid")
                            .header("Access-Control-Request-Method", "GET"))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(harness.jwt(), harness.users());
        }
    }

    @Test
    void conditionalAndMalformedRequestsCountBeforeTheRealControllerAnd429StaysNoStore() throws Exception {
        try (SecurityHarness harness = security(true, 2)) {
            harness.mvc().perform(get(BASE).param("locale", "es-AR").header("If-None-Match", "*")
                            .header("Authorization", "Bearer invalid-token"))
                    .andExpect(status().isNotModified()).andExpect(content().string(""))
                    .andExpect(header().string("X-RateLimit-Limit", "2"))
                    .andExpect(header().string("X-RateLimit-Remaining", "1"));
            harness.mvc().perform(get(BASE + "/bad").header("If-None-Match", "*"))
                    .andExpect(status().isNotFound())
                    .andExpect(header().string("X-RateLimit-Remaining", "0"));
            harness.mvc().perform(get(BASE + "/" + ID).header("If-None-Match", "*")
                            .header("Origin", ORIGIN).header("Authorization", "Bearer invalid-token"))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(header().string("Retry-After", "60"))
                    .andExpect(header().string("X-RateLimit-Limit", "2"))
                    .andExpect(header().string("X-RateLimit-Remaining", "0"))
                    .andExpect(header().string("Access-Control-Expose-Headers", containsString("Retry-After")))
                    .andExpect(jsonPath("$.status").value(429))
                    .andExpect(jsonPath("$.error").value("Demasiadas solicitudes"));
            verify(harness.service(), times(1)).catalog(null, LocaleLegal.ES_AR, 0, 20);
            verify(harness.service(), never()).document(ID);
            verifyNoInteractions(harness.jwt(), harness.users());
        }
    }

    @Test
    void theHistoricalRegisterExceptionStillPassesAnInvalidBearerToItsExistingMappingLayer() throws Exception {
        try (SecurityHarness harness = security(false, 60)) {
            // This MVC fixture deliberately has no AuthController; 404 proves it passed authorization.
            harness.mvc().perform(post("/api/auth/register").servletPath("/api/auth/register")
                            .header("Authorization", "Bearer invalid-token"))
                    .andExpect(status().isNotFound());
            verifyNoInteractions(harness.jwt(), harness.users());
        }
    }

    @Test
    void protectedRequestsStillUseThePersistedPrincipalAndClearTheirTenant() throws Exception {
        JwtUtils jwt = mock(JwtUtils.class);
        UserDetailsServiceImpl users = mock(UserDetailsServiceImpl.class);
        DecodedJWT decoded = mock(DecodedJWT.class);
        AuthenticatedUserPrincipal principal = mock(AuthenticatedUserPrincipal.class);
        when(jwt.verifyToken("valid-token")).thenReturn(decoded);
        when(decoded.getSubject()).thenReturn("owner@fixture.invalid");
        when(users.loadUserByUsername("owner@fixture.invalid")).thenReturn(principal);
        when(jwt.validateToken(decoded, principal)).thenReturn(true);
        when(principal.getAuthorities()).thenReturn(List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        when(principal.getTallerId()).thenReturn(73L);
        JwtFilter filter = new JwtFilter(jwt, users, new LegalPublicDocumentRequestMatcher(true));
        MockHttpServletRequest protectedRequest = new MockHttpServletRequest("GET", "/api/protected");
        protectedRequest.addHeader("Authorization", "Bearer valid-token");
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        try {
            filter.doFilter(protectedRequest, new MockHttpServletResponse(), (request, response) -> {
                assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isSameAs(principal);
                assertThat(TenantContext.getTallerId()).isEqualTo(73L);
            });
            assertThat(TenantContext.getTallerId()).isNull();
            verify(users).loadUserByUsername("owner@fixture.invalid");
            verify(decoded, never()).getClaim(anyString());
        } finally {
            SecurityContextHolder.clearContext();
            TenantContext.clear();
        }
    }

    private static SecurityHarness security(Boolean enabled, int limit) {
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        if (enabled != null) {
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                    LegalPublicDocumentRequestMatcher.ENABLED_PROPERTY + "=" + enabled);
        }
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context, "fixture.document-limit=" + limit);
        context.register(WebFixture.class);
        context.refresh();
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        return new SecurityHarness(context, mvc, context.getBean(LegalPublicDocumentReadService.class),
                context.getBean(JwtUtils.class), context.getBean(UserDetailsServiceImpl.class));
    }

    private record SecurityHarness(AnnotationConfigWebApplicationContext context, MockMvc mvc,
                                   LegalPublicDocumentReadService service, JwtUtils jwt,
                                   UserDetailsServiceImpl users) implements AutoCloseable {
        @Override public void close() {
            context.close();
            SecurityContextHolder.clearContext();
            TenantContext.clear();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import({SecurityConfig.class, CorsConfig.class, JwtFilter.class, PublicEndpointRateLimitFilter.class,
            LegalPublicDocumentRequestMatcher.class, LegalPublicRequirementsRequestMatcher.class, LegalPublicDocumentController.class,
            LegalPublicDocumentExceptionHandler.class})
    static class WebFixture {
        @Bean Clock clock() {
            return Clock.fixed(Instant.parse("2026-09-05T00:00:00Z"), ZoneOffset.UTC);
        }

        @Bean RateLimitProperties rateLimitProperties(@Value("${fixture.document-limit}") int limit) {
            RateLimitProperties properties = new RateLimitProperties();
            properties.setPublicLegalDocuments(new RateLimitProperties.Limit(limit, Duration.ofMinutes(1)));
            return properties;
        }

        @Bean JwtUtils jwtUtils() {
            JwtUtils jwt = mock(JwtUtils.class);
            when(jwt.verifyToken("invalid-token")).thenThrow(new JWTVerificationException("fixture invalid JWT"));
            return jwt;
        }

        @Bean UserDetailsServiceImpl userDetailsService() {
            return mock(UserDetailsServiceImpl.class);
        }

        @Bean LegalPublicDocumentReadService legalPublicDocumentReadService() {
            LegalDocumentSummary summary = new LegalDocumentSummary(ID, TipoDocumentoLegal.values()[0],
                    "fixture-v1", "Documento sintético", "b".repeat(64),
                    Instant.parse("2026-09-05T00:00:00Z"), EstadoVersionLegal.VIGENTE, LocaleLegal.ES_AR);
            LegalPublicDocumentCatalog catalog = new LegalPublicDocumentCatalog(null, LocaleLegal.ES_AR,
                    REVISION, List.of(summary), 0, 20, 1, 1);
            LegalPublicDocumentReadService service = mock(LegalPublicDocumentReadService.class);
            when(service.catalog(isNull(), eq(LocaleLegal.ES_AR), anyInt(), anyInt())).thenReturn(catalog);
            when(service.document(ID)).thenReturn(Optional.of(new LegalPublicDocumentVersion(summary, "# Fixture")));
            return service;
        }
    }
}
