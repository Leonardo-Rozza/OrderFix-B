package com.leonardorozza.mvgrreparacionesbackend.legal.http;

import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.config.security.LegalPrivateRequirementsAuthenticationEntryPoint;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceHistoryService;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalActorSnapshotException;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.Set;

/** Own actual evidence only; no request field can choose an actor, workshop or legal audience. */
@RestController
@RequestMapping(LegalAcceptanceHistoryController.BASE_PATH)
@Conditional(LegalPrivateRequirementsHttpConfiguration.Enabled.class)
public class LegalAcceptanceHistoryController {

    public static final String BASE_PATH = LegalPrivateRequirementsAuthenticationEntryPoint.HISTORY_PATH;
    private static final Set<String> PARAMETERS = Set.of("page", "size", "contexto");
    private final LegalAcceptanceHistoryService service;

    public LegalAcceptanceHistoryController(LegalAcceptanceHistoryService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ResponseEntity<LegalAcceptanceHistoryResponses.History> history(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            HttpServletRequest request, HttpServletResponse response) {
        if (principal == null) throw LegalAcceptanceHistoryHttpException.unauthorized(null);
        // Spring also maps HEAD to GET; the contracted read is strictly GET.
        if (!"GET".equals(request.getMethod())) throw LegalAcceptanceHistoryHttpException.methodNotAllowed();
        if (!LegalPrivateRequirementsAuthenticationEntryPoint.isHistoryGet(request)) {
            throw LegalAcceptanceHistoryHttpException.notFound();
        }
        if (!PARAMETERS.containsAll(request.getParameterMap().keySet())
                || request.getParameterMap().values().stream().anyMatch(values -> values == null || values.length != 1)) {
            throw LegalAcceptanceHistoryHttpException.invalidParameters();
        }
        int page = integer(request.getParameter("page"), 0, 0, Integer.MAX_VALUE);
        int size = integer(request.getParameter("size"), 20, 1, 100);
        ContextoLegal context = context(request.getParameter("contexto"));
        final LegalAcceptanceHistoryResponses.History body;
        try {
            body = LegalAcceptanceHistoryResponses.history(service.read(principal, context, page, size));
        } catch (LegalActorSnapshotException failure) {
            throw LegalAcceptanceHistoryHttpException.unauthorized(failure);
        } catch (RuntimeException failure) {
            throw LegalAcceptanceHistoryHttpException.unavailable(context, failure);
        }
        response.setHeader(HttpHeaders.ETAG, null);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(body);
    }

    private static int integer(String source, int defaultValue, int minimum, int maximum) {
        if (source == null) return defaultValue;
        if (!source.matches("[0-9]+")) throw LegalAcceptanceHistoryHttpException.invalidParameters();
        final int result;
        try { result = Integer.parseInt(source); }
        catch (NumberFormatException failure) { throw LegalAcceptanceHistoryHttpException.invalidParameters(); }
        if (result < minimum || result > maximum) throw LegalAcceptanceHistoryHttpException.invalidParameters();
        return result;
    }

    private static ContextoLegal context(String source) {
        if (source == null) return null;
        try { return ContextoLegal.valueOf(source); }
        catch (IllegalArgumentException failure) { throw LegalAcceptanceHistoryHttpException.unsupportedContext(source); }
    }
}
