package com.leonardorozza.mvgrreparacionesbackend.config.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.ApiError;
import com.leonardorozza.mvgrreparacionesbackend.legal.http.LegalPrivateRequirementsHttpConfiguration;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.Http403ForbiddenEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;

/** Changes only anonymous responses for the two enabled private account GETs; grants no access. */
@Component
@Conditional(LegalPrivateRequirementsHttpConfiguration.Enabled.class)
public final class LegalPrivateRequirementsAuthenticationEntryPoint implements AuthenticationEntryPoint {
    public static final String BASE_PATH = "/api/requisitos-legales";
    public static final String HISTORY_PATH = "/api/aceptaciones-legales";
    private final AuthenticationEntryPoint fallback = new Http403ForbiddenEntryPoint();
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException failure) throws IOException, ServletException {
        if (!isPrivateGet(request) && !isHistoryGet(request)) {
            // Preserve the pre-existing security chain's default entry point for all other paths/methods.
            fallback.commence(request, response, failure);
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.ETAG, null);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(response.getOutputStream(), new ApiError(LocalDateTime.now(), 401,
                "No autorizado", "Se requiere una sesión válida", request.getRequestURI()));
    }

    public static boolean isPrivateGet(HttpServletRequest request) {
        return isExactGet(request, BASE_PATH);
    }

    public static boolean isHistoryGet(HttpServletRequest request) {
        return isExactGet(request, HISTORY_PATH);
    }

    private static boolean isExactGet(HttpServletRequest request, String expectedPath) {
        if (!"GET".equals(request.getMethod())) {
            return false;
        }
        String path = request.getRequestURI();
        String context = request.getContextPath();
        if (path == null || context == null) {
            return false;
        }
        if (!context.isEmpty()) {
            if (!context.startsWith("/") || context.endsWith("/") || !path.startsWith(context + "/")) {
                return false;
            }
            path = path.substring(context.length());
        }
        return expectedPath.equals(path);
    }
}
