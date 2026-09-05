package com.leonardorozza.mvgrreparacionesbackend.config.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;

/** One classification shared by authorization, JWT bypass and the registration requirements rate policy. */
@Component
public final class LegalPublicRequirementsRequestMatcher implements RequestMatcher {

    public static final String ENABLED_PROPERTY = "ordenfix.legal.public-requirements.enabled";
    public static final String BASE_PATH = "/api/public/requisitos-legales";

    private final boolean enabled;

    @Autowired
    public LegalPublicRequirementsRequestMatcher(@Value("${" + ENABLED_PROPERTY + ":false}") String enabled) {
        this("true".equalsIgnoreCase(enabled));
    }

    public LegalPublicRequirementsRequestMatcher(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public boolean matches(HttpServletRequest request) {
        if (!enabled || !"GET".equals(request.getMethod())) {
            return false;
        }
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (path == null || contextPath == null) {
            return false;
        }
        if (!contextPath.isEmpty()) {
            if (!contextPath.startsWith("/") || contextPath.endsWith("/")
                    || !path.startsWith(contextPath + "/")) {
                return false;
            }
            path = path.substring(contextPath.length());
        }
        return BASE_PATH.equals(path);
    }
}
