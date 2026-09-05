package com.leonardorozza.mvgrreparacionesbackend.legal.http;

import com.leonardorozza.mvgrreparacionesbackend.config.security.LegalPublicRequirementsRequestMatcher;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicRequirementsReadService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ETag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.ServletWebRequest;

import java.util.Enumeration;
import java.util.Objects;

/** Public transport over one complete, committed registration observation. */
@RestController
@RequestMapping(LegalPublicRequirementsRequestMatcher.BASE_PATH)
@ConditionalOnProperty(name = LegalPublicRequirementsRequestMatcher.ENABLED_PROPERTY, havingValue = "true")
public final class LegalPublicRequirementsController {

    private static final String REVALIDATE = "public, max-age=0, must-revalidate";
    private final LegalPublicRequirementsReadService service;

    public LegalPublicRequirementsController(LegalPublicRequirementsReadService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LegalPublicRequirementsResponses.Registration> registration(
            HttpServletRequest request, HttpServletResponse response) {
        requireParameter(request, "locale", "es-AR");
        requireParameter(request, "contexto", "REGISTRO");
        final LegalPublicRequirementsResponses.Registration body;
        try {
            body = LegalPublicRequirementsResponses.registration(service.readRegistration());
        } catch (RuntimeException failure) {
            throw LegalPublicRequirementsHttpException.unavailable(failure);
        }
        // A matching tag cannot bypass the service or construction of the complete wire value.
        String etag = "W/\"" + body.requiredSetRevision() + "\"";
        response.setHeader(HttpHeaders.CACHE_CONTROL, REVALIDATE);
        response.setHeader(HttpHeaders.ETAG, etag);
        ServletWebRequest conditional = new ServletWebRequest(request, response);
        if (conditional.checkNotModified(etag)) {
            if (response.getStatus() == HttpStatus.PRECONDITION_FAILED.value()) {
                throw LegalPublicRequirementsHttpException.preconditionFailed();
            }
            return ResponseEntity.status(response.getStatus())
                    .header(HttpHeaders.CACHE_CONTROL, REVALIDATE).eTag(etag).build();
        }
        // Preserve Spring's preconditions before its safe-GET wildcard fallback, as in cut 13.
        if (response.getStatus() == HttpStatus.OK.value() && containsWildcard(request)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .header(HttpHeaders.CACHE_CONTROL, REVALIDATE).eTag(etag).build();
        }
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, REVALIDATE).eTag(etag).body(body);
    }

    private static void requireParameter(HttpServletRequest request, String name, String expected) {
        String[] values = request.getParameterValues(name);
        if (values == null || values.length != 1 || !expected.equals(values[0])) {
            String rejected = values == null ? null : String.join(",", values);
            throw name.equals("locale") ? LegalPublicRequirementsHttpException.unsupportedLocale(rejected)
                    : LegalPublicRequirementsHttpException.unsupportedContext(rejected);
        }
    }

    private static boolean containsWildcard(HttpServletRequest request) {
        Enumeration<String> headers = request.getHeaders(HttpHeaders.IF_NONE_MATCH);
        while (headers.hasMoreElements()) {
            if (ETag.parse(headers.nextElement()).stream().anyMatch(ETag::isWildcard)) {
                return true;
            }
        }
        return false;
    }
}
