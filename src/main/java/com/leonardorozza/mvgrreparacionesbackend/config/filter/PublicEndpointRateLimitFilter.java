package com.leonardorozza.mvgrreparacionesbackend.config.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leonardorozza.mvgrreparacionesbackend.config.security.RateLimitProperties;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.ApiError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
@RequiredArgsConstructor
public class PublicEndpointRateLimitFilter extends OncePerRequestFilter {

    private final RateLimitProperties properties;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final Map<String, SlidingWindow> windows = new ConcurrentHashMap<>();
    private final AtomicLong requestsSeen = new AtomicLong();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.isEnabled() || policy(request) == null;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Policy policy = policy(request);
        if (policy == null) {
            filterChain.doFilter(request, response);
            return;
        }

        long now = clock.millis();
        String key = policy.name() + ':' + clientAddress(request);
        SlidingWindow window = windows.computeIfAbsent(key, ignored -> new SlidingWindow());
        Decision decision = window.acquire(now, policy.limit().getWindow().toMillis(),
                policy.limit().getRequests());

        response.setHeader("X-RateLimit-Limit", String.valueOf(policy.limit().getRequests()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remaining()));
        if (!decision.allowed()) {
            response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getOutputStream(), new ApiError(
                    LocalDateTime.now(clock),
                    HttpStatus.TOO_MANY_REQUESTS.value(),
                    "Demasiadas solicitudes",
                    "Se alcanzó el límite temporal de solicitudes. Reintentá más tarde.",
                    request.getRequestURI()));
            cleanupOccasionally(now);
            return;
        }

        cleanupOccasionally(now);
        filterChain.doFilter(request, response);
    }

    private Policy policy(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        if ("POST".equals(method) && "/api/auth/login".equals(path)) {
            return new Policy("login", properties.getLogin());
        }
        if ("POST".equals(method) && "/api/auth/register".equals(path)) {
            return new Policy("register", properties.getRegister());
        }
        if ("POST".equals(method) && (path.startsWith("/api/auth/password/")
                || path.startsWith("/api/auth/verificar-email"))) {
            return new Policy("account", properties.getAccountRecovery());
        }
        if (path.startsWith("/api/seguimiento/") && "GET".equals(method)) {
            return new Policy("tracking-read", properties.getPublicTrackingRead());
        }
        if (path.startsWith("/api/seguimiento/") && "POST".equals(method)) {
            return new Policy("tracking-action", properties.getPublicTrackingAction());
        }
        if ("POST".equals(method) && "/api/pagos/webhook".equals(path)) {
            return new Policy("mp-webhook", properties.getMercadoPagoWebhook());
        }
        return null;
    }

    private String clientAddress(HttpServletRequest request) {
        if (properties.isTrustForwardedHeaders()) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return bounded(forwarded.split(",", 2)[0].trim());
            }
        }
        return bounded(request.getRemoteAddr());
    }

    private String bounded(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.length() <= 64 ? value : value.substring(0, 64);
    }

    private void cleanupOccasionally(long now) {
        if (requestsSeen.incrementAndGet() % 1_000 != 0) {
            return;
        }
        long oldestUseful = now - longestWindow().multipliedBy(2).toMillis();
        windows.entrySet().removeIf(entry -> entry.getValue().lastSeen() < oldestUseful);
    }

    private Duration longestWindow() {
        Duration longest = Duration.ZERO;
        for (RateLimitProperties.Limit limit : new RateLimitProperties.Limit[]{
                properties.getLogin(),
                properties.getRegister(),
                properties.getAccountRecovery(),
                properties.getPublicTrackingRead(),
                properties.getPublicTrackingAction(),
                properties.getMercadoPagoWebhook()}) {
            if (limit.getWindow().compareTo(longest) > 0) {
                longest = limit.getWindow();
            }
        }
        return longest;
    }

    private record Policy(String name, RateLimitProperties.Limit limit) {
    }

    private record Decision(boolean allowed, int remaining, long retryAfterSeconds) {
    }

    private static final class SlidingWindow {
        private final ArrayDeque<Long> timestamps = new ArrayDeque<>();
        private long lastSeen;

        synchronized Decision acquire(long now, long windowMillis, int limit) {
            long cutoff = now - windowMillis;
            while (!timestamps.isEmpty() && timestamps.peekFirst() <= cutoff) {
                timestamps.removeFirst();
            }
            lastSeen = now;
            if (timestamps.size() >= limit) {
                long waitMillis = Math.max(1, timestamps.peekFirst() + windowMillis - now);
                long waitSeconds = Math.max(1, (waitMillis + 999) / 1_000);
                return new Decision(false, 0, waitSeconds);
            }
            timestamps.addLast(now);
            return new Decision(true, limit - timestamps.size(), 0);
        }

        synchronized long lastSeen() {
            return lastSeen;
        }
    }
}
