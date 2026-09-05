package com.leonardorozza.mvgrreparacionesbackend.legal.http;

import com.leonardorozza.mvgrreparacionesbackend.config.security.LegalPublicDocumentRequestMatcher;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicDocumentCatalog;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicDocumentReadException;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicDocumentReadService;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicDocumentVersion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoVersionLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ETag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.ServletWebRequest;

import java.util.Enumeration;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Public transport over one already-committed, accredited document observation. */
@RestController
@RequestMapping(LegalPublicDocumentRequestMatcher.BASE_PATH)
@ConditionalOnProperty(name = LegalPublicDocumentRequestMatcher.ENABLED_PROPERTY, havingValue = "true")
public final class LegalPublicDocumentController {

    private static final String REVALIDATE = "public, max-age=0, must-revalidate";
    private static final String IMMUTABLE = "public, max-age=31536000, immutable";
    private static final Pattern UUID_SHAPE = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern UNSIGNED_INTEGER = Pattern.compile("[0-9]+");
    private final LegalPublicDocumentReadService service;

    public LegalPublicDocumentController(LegalPublicDocumentReadService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LegalPublicDocumentResponses.Catalog> catalog(
            @RequestParam(name = "locale", required = false) String rawLocale,
            @RequestParam(name = "contexto", required = false) String rawContext,
            @RequestParam(name = "page", required = false) String rawPage,
            @RequestParam(name = "size", required = false) String rawSize,
            HttpServletRequest request, HttpServletResponse response) {
        LocaleLegal locale = parseLocale(rawLocale);
        ContextoLegal context = parseContext(rawContext);
        int page = pageCoordinate(rawPage, "page", 0, 0, Integer.MAX_VALUE);
        int size = pageCoordinate(rawSize, "size", 20, 1, 100);
        final LegalPublicDocumentCatalog catalog;
        try {
            catalog = service.catalog(context, locale, page, size);
        } catch (LegalPublicDocumentReadException failure) {
            throw LegalPublicDocumentHttpException.unavailable(context, failure);
        }
        String etag = "W/\"" + catalog.documentSetRevision() + ":p=" + catalog.page()
                + ":s=" + catalog.size() + "\"";
        return representation(LegalPublicDocumentResponses.catalog(catalog), etag, REVALIDATE,
                request, response);
    }

    @GetMapping(path = "/{versionId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LegalPublicDocumentResponses.Document> document(
            @PathVariable("versionId") String rawVersionId,
            HttpServletRequest request, HttpServletResponse response) {
        UUID versionId = parseVersionId(rawVersionId);
        final LegalPublicDocumentVersion version;
        try {
            version = service.document(versionId)
                    .orElseThrow(() -> LegalPublicDocumentHttpException.notFound(rawVersionId));
        } catch (LegalPublicDocumentReadException failure) {
            throw LegalPublicDocumentHttpException.unavailable(null, failure);
        }
        var summary = version.summary();
        String etag = "W/\"doc:" + summary.versionId() + ":" + summary.state().name()
                + ":" + summary.sha256() + "\"";
        String cache = summary.state() == EstadoVersionLegal.VIGENTE ? REVALIDATE : IMMUTABLE;
        return representation(LegalPublicDocumentResponses.document(version), etag, cache,
                request, response);
    }

    private static LocaleLegal parseLocale(String candidate) {
        if (!LocaleLegal.ES_AR.getCodigo().equals(candidate)) {
            throw LegalPublicDocumentHttpException.unsupportedLocale(candidate);
        }
        return LocaleLegal.ES_AR;
    }

    private static ContextoLegal parseContext(String candidate) {
        if (candidate == null) {
            return null;
        }
        try {
            return ContextoLegal.valueOf(candidate);
        } catch (IllegalArgumentException failure) {
            throw LegalPublicDocumentHttpException.unsupportedContext(candidate);
        }
    }

    private static int pageCoordinate(String raw, String parameter, int defaultValue,
                                      int minimum, int maximum) {
        if (raw == null) {
            return defaultValue;
        }
        if (raw.length() > 10 || !UNSIGNED_INTEGER.matcher(raw).matches()) {
            throw LegalPublicDocumentHttpException.invalidPage(parameter);
        }
        try {
            int value = Integer.parseInt(raw);
            if (value < minimum || value > maximum) {
                throw LegalPublicDocumentHttpException.invalidPage(parameter);
            }
            return value;
        } catch (NumberFormatException failure) {
            throw LegalPublicDocumentHttpException.invalidPage(parameter);
        }
    }

    private static UUID parseVersionId(String candidate) {
        if (!UUID_SHAPE.matcher(candidate).matches()) {
            throw LegalPublicDocumentHttpException.notFound(candidate);
        }
        return UUID.fromString(candidate);
    }

    private static <T> ResponseEntity<T> representation(T body, String etag, String cache,
                                                       HttpServletRequest request,
                                                       HttpServletResponse response) {
        // Accreditation and complete DTO construction happen before any successful cache response.
        response.setHeader(HttpHeaders.CACHE_CONTROL, cache);
        response.setHeader(HttpHeaders.ETAG, etag);
        ServletWebRequest conditional = new ServletWebRequest(request, response);
        if (conditional.checkNotModified(etag)) {
            if (response.getStatus() == HttpStatus.PRECONDITION_FAILED.value()) {
                throw LegalPublicDocumentHttpException.preconditionFailed();
            }
            return ResponseEntity.status(response.getStatus())
                    .header(HttpHeaders.CACHE_CONTROL, cache).eTag(etag).build();
        }
        // Spring 7.0.x only special-cases wildcard tags for unsafe methods. Keep its full
        // conditional evaluation above, then use its parser for this successful GET fallback.
        if (response.getStatus() == HttpStatus.OK.value() && containsWildcard(request)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .header(HttpHeaders.CACHE_CONTROL, cache).eTag(etag).build();
        }
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, cache).eTag(etag).body(body);
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
