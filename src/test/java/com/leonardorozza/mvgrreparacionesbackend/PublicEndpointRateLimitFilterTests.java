package com.leonardorozza.mvgrreparacionesbackend;

import com.leonardorozza.mvgrreparacionesbackend.config.filter.PublicEndpointRateLimitFilter;
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

import static org.assertj.core.api.Assertions.assertThat;

class PublicEndpointRateLimitFilterTests {

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
