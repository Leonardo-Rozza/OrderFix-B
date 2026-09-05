package com.leonardorozza.mvgrreparacionesbackend;

import com.leonardorozza.mvgrreparacionesbackend.config.filter.PublicEndpointRateLimitFilter;
import com.leonardorozza.mvgrreparacionesbackend.config.security.LegalPublicDocumentRequestMatcher;
import com.leonardorozza.mvgrreparacionesbackend.config.security.LegalPublicRequirementsRequestMatcher;
import com.leonardorozza.mvgrreparacionesbackend.config.security.RateLimitProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class PublicEndpointRateLimitFilterTests {

    private static final String DOCUMENTS = LegalPublicDocumentRequestMatcher.BASE_PATH;

    private PublicEndpointRateLimitFilter filter;

    @BeforeEach
    void setUp() {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setLogin(new RateLimitProperties.Limit(2, Duration.ofMinutes(1)));
        filter = new PublicEndpointRateLimitFilter(
                properties,
                Clock.fixed(Instant.parse("2026-08-14T01:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void tercerLoginDeLaMismaIpEs429ConRetryAfter() throws Exception {
        assertThat(execute("10.0.0.1").getStatus()).isEqualTo(200);
        assertThat(execute("10.0.0.1").getStatus()).isEqualTo(200);

        MockHttpServletResponse limited = execute("10.0.0.1");
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getHeader("Retry-After")).isEqualTo("60");
        assertThat(limited.getContentAsString()).contains("Demasiadas solicitudes");
    }

    @Test
    void limitesSeAislanPorIpYNoConfianForwardedPorDefecto() throws Exception {
        execute("10.0.0.1", "198.51.100.1");
        execute("10.0.0.1", "198.51.100.2");
        assertThat(execute("10.0.0.1", "198.51.100.3").getStatus()).isEqualTo(429);

        assertThat(execute("10.0.0.2", "198.51.100.1").getStatus()).isEqualTo(200);
    }

    @Test
    void catalogConditionalAndMalformedUuidShareOneQuotaAnd429IsNeverCacheable() throws Exception {
        PublicEndpointRateLimitFilter documents = documentsFilter(new RateLimitProperties(), new MutableClock(), true);
        MockHttpServletRequest conditional = documentRequest("GET", DOCUMENTS, "10.0.1.1");
        conditional.addHeader("If-None-Match", "*");
        MockHttpServletResponse first = run(documents, conditional);
        assertThat(first.getHeader("X-RateLimit-Limit")).isEqualTo("2");
        assertThat(first.getHeader("X-RateLimit-Remaining")).isEqualTo("1");
        assertThat(run(documents, documentRequest("GET", DOCUMENTS + "/bad-uuid", "10.0.1.1"))
                .getStatus()).isEqualTo(200);

        MockHttpServletResponse limited = run(documents,
                documentRequest("GET", DOCUMENTS + "/123e4567-e89b-12d3-a456-426614174000", "10.0.1.1"));
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(limited.getHeader("Retry-After")).isEqualTo("60");
        assertThat(limited.getHeader("X-RateLimit-Limit")).isEqualTo("2");
        assertThat(limited.getHeader("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(limited.getContentAsString()).contains("Demasiadas solicitudes", "\"status\":429");
    }

    @Test
    void contextPathDoesNotCreateASeparateDocumentQuota() throws Exception {
        PublicEndpointRateLimitFilter documents = documentsFilter(new RateLimitProperties(), new MutableClock(), true);
        run(documents, documentRequest("GET", DOCUMENTS, "10.0.1.2"));
        MockHttpServletRequest contextual = documentRequest("GET", "/ordenfix" + DOCUMENTS + "/bad", "10.0.1.2");
        contextual.setContextPath("/ordenfix");
        assertThat(run(documents, contextual).getHeader("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(run(documents, documentRequest("GET", DOCUMENTS, "10.0.1.2")).getStatus()).isEqualTo(429);
    }

    @Test
    void flagOffAndNonDocumentRequestsDoNotConsumeTheDocumentPolicy() throws Exception {
        PublicEndpointRateLimitFilter disabled = documentsFilter(new RateLimitProperties(), new MutableClock(), false);
        for (int attempt = 0; attempt < 3; attempt++) {
            assertThat(run(disabled, documentRequest("GET", DOCUMENTS, "10.0.1.3"))
                    .getHeader("X-RateLimit-Limit")).isNull();
        }
        PublicEndpointRateLimitFilter documents = documentsFilter(new RateLimitProperties(), new MutableClock(), true);
        for (String method : new String[]{"HEAD", "POST", "OPTIONS", "PUT", "DELETE"}) {
            assertThat(run(documents, documentRequest(method, DOCUMENTS, "10.0.1.3"))
                    .getHeader("X-RateLimit-Limit")).isNull();
        }
        for (String path : new String[]{DOCUMENTS + "/", DOCUMENTS + "/id/extra",
                DOCUMENTS + "-extra", "/api/public/requisitos-legales"}) {
            assertThat(run(documents, documentRequest("GET", path, "10.0.1.3"))
                    .getHeader("X-RateLimit-Limit")).isNull();
        }
        assertThat(run(documents, documentRequest("GET", DOCUMENTS, "10.0.1.3"))
                .getHeader("X-RateLimit-Remaining")).isEqualTo("1");
    }

    @Test
    void theGlobalRateSwitchStillDisablesThisPolicy() throws Exception {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setEnabled(false);
        PublicEndpointRateLimitFilter documents = documentsFilter(properties, new MutableClock(), true);
        for (int attempt = 0; attempt < 3; attempt++) {
            assertThat(run(documents, documentRequest("GET", DOCUMENTS, "10.0.1.4"))
                    .getHeader("X-RateLimit-Limit")).isNull();
        }
    }

    @Test
    void documentaryWindowsExpireAtTheExactBoundaryAndRoundRetryAfterUp() throws Exception {
        MutableClock clock = new MutableClock();
        PublicEndpointRateLimitFilter documents = documentsFilter(new RateLimitProperties(), clock, true);
        run(documents, documentRequest("GET", DOCUMENTS, "10.0.1.5"));
        run(documents, documentRequest("GET", DOCUMENTS, "10.0.1.5"));
        clock.advance(Duration.ofMillis(59_999));
        assertThat(run(documents, documentRequest("GET", DOCUMENTS, "10.0.1.5"))
                .getHeader("Retry-After")).isEqualTo("1");
        clock.advance(Duration.ofMillis(1));
        MockHttpServletResponse nextWindow = run(documents, documentRequest("GET", DOCUMENTS, "10.0.1.5"));
        assertThat(nextWindow.getStatus()).isEqualTo(200);
        assertThat(nextWindow.getHeader("X-RateLimit-Remaining")).isEqualTo("1");
        assertThat(nextWindow.getHeader("Retry-After")).isNull();
    }

    @Test
    void documentRateLimitUsesTheRemoteIpAndPreserves304Headers() throws Exception {
        PublicEndpointRateLimitFilter documents = documentsFilter(new RateLimitProperties(), new MutableClock(), true);
        for (int attempt = 0; attempt < 2; attempt++) {
            MockHttpServletRequest request = documentRequest("GET", DOCUMENTS, "10.0.1.6");
            request.addHeader("X-Forwarded-For", "198.51.100." + attempt);
            request.addHeader("If-None-Match", "W/\"fixture\"");
            MockHttpServletResponse response = new MockHttpServletResponse();
            documents.doFilter(request, response, (ignored, downstream) ->
                    ((jakarta.servlet.http.HttpServletResponse) downstream).setStatus(304));
            assertThat(response.getStatus()).isEqualTo(304);
            assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("2");
            assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo(String.valueOf(1 - attempt));
        }
        assertThat(run(documents, documentRequest("GET", DOCUMENTS, "10.0.1.6")).getStatus()).isEqualTo(429);
        assertThat(run(documents, documentRequest("GET", DOCUMENTS, "10.0.1.7")).getStatus()).isEqualTo(200);
    }

    @Test
    void cleanupKeepsALongerDocumentWindowUntilItsOwnDeadline() throws Exception {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setPublicLegalDocuments(new RateLimitProperties.Limit(1, Duration.ofDays(1)));
        MutableClock clock = new MutableClock();
        PublicEndpointRateLimitFilter documents = new PublicEndpointRateLimitFilter(properties, clock,
                new LegalPublicDocumentRequestMatcher(true));
        run(documents, documentRequest("GET", DOCUMENTS, "10.0.2.1"));
        clock.advance(Duration.ofHours(3));
        for (int index = 0; index < 999; index++) {
            run(documents, documentRequest("GET", DOCUMENTS, "fixture-ip-" + index));
        }
        MockHttpServletResponse stillLimited = run(documents, documentRequest("GET", DOCUMENTS, "10.0.2.1"));
        assertThat(stillLimited.getStatus()).isEqualTo(429);
        assertThat(stillLimited.getHeader("Retry-After")).isEqualTo("75600");
    }

    @Test
    void requirementsQuotaIsIndependentFromDocumentsAndLoginAndSharesContextPaths() throws Exception {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setPublicLegalRequirements(new RateLimitProperties.Limit(2, Duration.ofMinutes(1)));
        PublicEndpointRateLimitFilter target = requirementsFilter(properties, new MutableClock());
        String base = LegalPublicRequirementsRequestMatcher.BASE_PATH;
        assertThat(run(target, documentRequest("GET", base, "10.0.3.1"))
                .getHeader("X-RateLimit-Remaining")).isEqualTo("1");
        MockHttpServletRequest contextual = documentRequest("GET", "/ordenfix" + base, "10.0.3.1");
        contextual.setContextPath("/ordenfix");
        assertThat(run(target, contextual).getHeader("X-RateLimit-Remaining")).isEqualTo("0");
        MockHttpServletResponse limited = run(target, documentRequest("GET", base, "10.0.3.1"));
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(limited.getHeader("ETag")).isNull();
        assertThat(limited.getHeader("Retry-After")).isEqualTo("60");
        assertThat(run(target, documentRequest("GET", DOCUMENTS, "10.0.3.1"))
                .getHeader("X-RateLimit-Remaining")).isEqualTo("59");
        assertThat(run(target, documentRequest("POST", "/api/auth/login", "10.0.3.1"))
                .getHeader("X-RateLimit-Remaining")).isEqualTo("9");
    }

    @Test
    void requirementsHonorsGlobalSwitchAndSkipsNonMatchingMethodsAndPaths() throws Exception {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setPublicLegalRequirements(new RateLimitProperties.Limit(1, Duration.ofMinutes(1)));
        PublicEndpointRateLimitFilter target = requirementsFilter(properties, new MutableClock());
        String base = LegalPublicRequirementsRequestMatcher.BASE_PATH;
        properties.setEnabled(false);
        assertThat(run(target, documentRequest("GET", base, "10.0.3.2"))
                .getHeader("X-RateLimit-Limit")).isNull();
        properties.setEnabled(true);
        for (String method : new String[]{"HEAD", "POST", "PUT", "DELETE", "OPTIONS"}) {
            assertThat(run(target, documentRequest(method, base, "10.0.3.2"))
                    .getHeader("X-RateLimit-Limit")).isNull();
        }
        for (String path : new String[]{base + "/", base + "/id", base + "-extra"}) {
            assertThat(run(target, documentRequest("GET", path, "10.0.3.2"))
                    .getHeader("X-RateLimit-Limit")).isNull();
        }
        assertThat(run(target, documentRequest("GET", base, "10.0.3.2"))
                .getHeader("X-RateLimit-Remaining")).isEqualTo("0");
    }

    @Test
    void requirementsPreservesExplicitProxyTrustAndDefaultsToRemoteAddress() throws Exception {
        for (boolean trusted : new boolean[]{false, true}) {
            RateLimitProperties properties = new RateLimitProperties();
            properties.setTrustForwardedHeaders(trusted);
            properties.setPublicLegalRequirements(new RateLimitProperties.Limit(1, Duration.ofMinutes(1)));
            PublicEndpointRateLimitFilter target = requirementsFilter(properties, new MutableClock());
            String base = LegalPublicRequirementsRequestMatcher.BASE_PATH;
            for (int index = 0; index < 2; index++) {
                MockHttpServletRequest request = documentRequest("GET", base, "10.0.3.3");
                request.addHeader("X-Forwarded-For", "198.51.100." + index + ", 192.0.2.1");
                assertThat(run(target, request).getStatus()).isEqualTo(index == 0 || trusted ? 200 : 429);
            }
        }
    }

    @Test
    void requirementsWindowExpiresExactlyAndRoundsRetryUp() throws Exception {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setPublicLegalRequirements(new RateLimitProperties.Limit(1, Duration.ofMinutes(1)));
        MutableClock clock = new MutableClock();
        PublicEndpointRateLimitFilter target = requirementsFilter(properties, clock);
        String base = LegalPublicRequirementsRequestMatcher.BASE_PATH;
        run(target, documentRequest("GET", base, "10.0.3.4"));
        clock.advance(Duration.ofMillis(59_999));
        assertThat(run(target, documentRequest("GET", base, "10.0.3.4"))
                .getHeader("Retry-After")).isEqualTo("1");
        clock.advance(Duration.ofMillis(1));
        assertThat(run(target, documentRequest("GET", base, "10.0.3.4")).getStatus()).isEqualTo(200);
    }

    @Test
    void cleanupRetainsTheRequirementsWindowWhenItOutlivesEveryOtherPolicy() throws Exception {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setPublicLegalRequirements(new RateLimitProperties.Limit(1, Duration.ofDays(1)));
        MutableClock clock = new MutableClock();
        PublicEndpointRateLimitFilter target = requirementsFilter(properties, clock);
        String base = LegalPublicRequirementsRequestMatcher.BASE_PATH;
        run(target, documentRequest("GET", base, "10.0.3.5"));
        clock.advance(Duration.ofHours(3));
        for (int index = 0; index < 999; index++) {
            run(target, documentRequest("GET", DOCUMENTS, "requirements-cleanup-" + index));
        }
        MockHttpServletResponse limited = run(target, documentRequest("GET", base, "10.0.3.5"));
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getHeader("Retry-After")).isEqualTo("75600");
    }

    private static PublicEndpointRateLimitFilter requirementsFilter(RateLimitProperties properties, Clock clock) {
        return new PublicEndpointRateLimitFilter(properties, clock, new LegalPublicDocumentRequestMatcher(true),
                new LegalPublicRequirementsRequestMatcher(true));
    }

    private static PublicEndpointRateLimitFilter documentsFilter(
            RateLimitProperties properties, Clock clock, boolean enabled) {
        properties.setPublicLegalDocuments(new RateLimitProperties.Limit(2, Duration.ofMinutes(1)));
        return new PublicEndpointRateLimitFilter(properties, clock, new LegalPublicDocumentRequestMatcher(enabled));
    }

    private static MockHttpServletRequest documentRequest(String method, String path, String address) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(address);
        return request;
    }

    private static MockHttpServletResponse run(PublicEndpointRateLimitFilter target, MockHttpServletRequest request)
            throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        target.doFilter(request, response, new MockFilterChain());
        return response;
    }

    private static final class MutableClock extends Clock {
        private Instant current = Instant.parse("2026-09-05T00:00:00Z");

        void advance(Duration duration) { current = current.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(current, zone); }
        @Override public Instant instant() { return current; }
    }

    private MockHttpServletResponse execute(String remoteAddress) throws Exception {
        return execute(remoteAddress, null);
    }

    private MockHttpServletResponse execute(String remoteAddress, String forwardedFor) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setRemoteAddr(remoteAddress);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
