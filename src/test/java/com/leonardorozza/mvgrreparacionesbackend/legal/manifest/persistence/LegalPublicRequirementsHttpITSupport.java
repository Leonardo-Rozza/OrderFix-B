package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.config.CorsConfig;
import com.leonardorozza.mvgrreparacionesbackend.config.SecurityConfig;
import com.leonardorozza.mvgrreparacionesbackend.config.filter.JwtFilter;
import com.leonardorozza.mvgrreparacionesbackend.config.filter.PublicEndpointRateLimitFilter;
import com.leonardorozza.mvgrreparacionesbackend.config.security.LegalPublicDocumentRequestMatcher;
import com.leonardorozza.mvgrreparacionesbackend.config.security.LegalPublicRequirementsRequestMatcher;
import com.leonardorozza.mvgrreparacionesbackend.config.security.RateLimitProperties;
import com.leonardorozza.mvgrreparacionesbackend.config.tenant.TenantContext;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.GlobalExceptionHandler;
import com.leonardorozza.mvgrreparacionesbackend.legal.http.LegalPublicRequirementsController;
import com.leonardorozza.mvgrreparacionesbackend.legal.http.LegalPublicRequirementsExceptionHandler;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.UserDetailsServiceImpl;
import com.leonardorozza.mvgrreparacionesbackend.utils.jwt.JwtUtils;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;

import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * Real requirements HTTP policies over a caller-owned, instrumented restricted PostgreSQL graph.
 * The caller closes its database graph; this helper owns only its separate MVC context.
 * The full production bridge is independently exercised by LegalPublicRequirementsHttpIT.
 */
final class LegalPublicRequirementsHttpITSupport {

    private LegalPublicRequirementsHttpITSupport() { }

    static HttpHarness openHttp(LegalPublicRequirementsReadService service) {
        Objects.requireNonNull(service, "service");
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        try {
            context.setServletContext(new MockServletContext());
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("legal-requirements-http-it",
                    Map.of(LegalPublicRequirementsRequestMatcher.ENABLED_PROPERTY, "true",
                            LegalPublicDocumentRequestMatcher.ENABLED_PROPERTY, "false")));
            context.addBeanFactoryPostProcessor(factory ->
                    factory.registerSingleton("legalPublicRequirementsReadService", service));
            context.register(WebFixture.class);
            context.refresh();
            return new HttpHarness(context, webAppContextSetup(context).apply(springSecurity()).build());
        } catch (RuntimeException | Error failure) {
            try {
                context.close();
            } catch (RuntimeException | Error cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    record HttpHarness(AnnotationConfigWebApplicationContext context, MockMvc mvc) implements AutoCloseable {
        @Override
        public void close() {
            try {
                context.close();
            } finally {
                SecurityContextHolder.clearContext();
                TenantContext.clear();
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import({SecurityConfig.class, CorsConfig.class, JwtFilter.class, PublicEndpointRateLimitFilter.class,
            LegalPublicDocumentRequestMatcher.class, LegalPublicRequirementsRequestMatcher.class, RateLimitProperties.class,
            LegalPublicRequirementsController.class, LegalPublicRequirementsExceptionHandler.class,
            GlobalExceptionHandler.class})
    static class WebFixture {
        @Bean Clock clock() { return Clock.systemUTC(); }
        @Bean JwtUtils jwtUtils() { return mock(JwtUtils.class); }
        @Bean UserDetailsServiceImpl users() { return mock(UserDetailsServiceImpl.class); }
    }
}
