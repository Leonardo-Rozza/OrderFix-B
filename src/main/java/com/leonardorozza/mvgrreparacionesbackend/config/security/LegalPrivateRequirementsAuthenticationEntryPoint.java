package com.leonardorozza.mvgrreparacionesbackend.config.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.ApiError;
import com.leonardorozza.mvgrreparacionesbackend.legal.http.LegalAcceptanceHttpConfiguration;
import com.leonardorozza.mvgrreparacionesbackend.legal.http.LegalPrivateRequirementsHttpConfiguration;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.Http403ForbiddenEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;

/** Changes only enabled private legal requests' anonymous responses; grants no access. */
@Component
@Conditional(LegalPrivateRequirementsHttpConfiguration.Enabled.class)
public final class LegalPrivateRequirementsAuthenticationEntryPoint implements AuthenticationEntryPoint {
    public static final String BASE_PATH = "/api/requisitos-legales";
    public static final String HISTORY_PATH = "/api/aceptaciones-legales";
    private final boolean acceptanceEnabled;
    private final AuthenticationEntryPoint fallback = new Http403ForbiddenEntryPoint();
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /** Compatibility construction keeps the acceptance POST disabled. */
    public LegalPrivateRequirementsAuthenticationEntryPoint() {
        acceptanceEnabled = false;
    }

    @Autowired
    public LegalPrivateRequirementsAuthenticationEntryPoint(Environment environment) {
        String value = environment.getProperty(LegalAcceptanceHttpConfiguration.ENABLED_PROPERTY);
        if (value != null && !"true".equals(value) && !"false".equals(value)) {
            throw new IllegalArgumentException("El flag de aceptación legal requiere true o false exactos");
        }
        acceptanceEnabled = "true".equals(value);
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException failure) throws IOException, ServletException {
        if (!isPrivateGet(request) && !isHistoryGet(request)
                && !(acceptanceEnabled && isAcceptancePost(request))) {
            // Preserve the pre-existing security chain's default entry point for all other paths/methods.
            fallback.commence(request, response, failure);
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.ETAG, null);
        if (isAcceptancePost(request)) response.setHeader(HttpHeaders.RETRY_AFTER, null);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(response.getOutputStream(), new ApiError(LocalDateTime.now(), 401,
                "No autorizado", "Se requiere una sesión válida", request.getRequestURI()));
    }

    public static boolean isPrivateGet(HttpServletRequest request) {
        return isExact(request, "GET", BASE_PATH);
    }

    public static boolean isHistoryGet(HttpServletRequest request) {
        return isExact(request, "GET", HISTORY_PATH);
    }

    /** Pure route classification; enabling the POST remains a separate server-side decision. */
    public static boolean isAcceptancePost(HttpServletRequest request) {
        return isExact(request, "POST", HISTORY_PATH);
    }

    private static boolean isExact(HttpServletRequest request, String method, String expectedPath) {
        if (!method.equals(request.getMethod())) {
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
