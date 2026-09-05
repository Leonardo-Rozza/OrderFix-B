package com.leonardorozza.mvgrreparacionesbackend.config.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;

/** One classification shared by authorization, JWT bypass and the documentary rate policy. */
@Component
public final class LegalPublicDocumentRequestMatcher implements RequestMatcher {

    public static final String ENABLED_PROPERTY = "ordenfix.legal.public-documents.enabled";
    public static final String BASE_PATH = "/api/public/documentos-legales";

    private final boolean enabled;

    @Autowired
    public LegalPublicDocumentRequestMatcher(@Value("${" + ENABLED_PROPERTY + ":false}") String enabled) {
        this("true".equalsIgnoreCase(enabled));
    }

    public LegalPublicDocumentRequestMatcher(boolean enabled) {
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
        if (BASE_PATH.equals(path)) {
            return true;
        }
        String documentPrefix = BASE_PATH + "/";
        if (!path.startsWith(documentPrefix)) {
            return false;
        }
        String segment = path.substring(documentPrefix.length());
        return !segment.isEmpty() && segment.indexOf('/') < 0;
    }
}
