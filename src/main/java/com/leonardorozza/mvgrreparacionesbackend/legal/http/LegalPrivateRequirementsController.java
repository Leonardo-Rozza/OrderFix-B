package com.leonardorozza.mvgrreparacionesbackend.legal.http;

import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.config.security.LegalPrivateRequirementsAuthenticationEntryPoint;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalActorSnapshotException;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPrivateRequirementsReadService;
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

/** Private transport over the complete, committed observation of the authenticated account. */
@RestController
@RequestMapping(LegalPrivateRequirementsController.BASE_PATH)
@Conditional(LegalPrivateRequirementsHttpConfiguration.Enabled.class)
public class LegalPrivateRequirementsController {

    public static final String BASE_PATH = LegalPrivateRequirementsAuthenticationEntryPoint.BASE_PATH;
    private final LegalPrivateRequirementsReadService service;

    public LegalPrivateRequirementsController(LegalPrivateRequirementsReadService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ResponseEntity<LegalPrivateRequirementsResponses.Pending> requirements(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            HttpServletRequest request, HttpServletResponse response) {
        if (principal == null) {
            throw LegalPrivateRequirementsHttpException.unauthorized(null);
        }
        // Spring maps HEAD to GET by default; this read is enabled only for the contracted GET.
        if (!"GET".equals(request.getMethod())) {
            throw LegalPrivateRequirementsHttpException.methodNotAllowed();
        }
        if (!LegalPrivateRequirementsAuthenticationEntryPoint.isPrivateGet(request)) {
            throw LegalPrivateRequirementsHttpException.notFound();
        }
        if (!request.getParameterMap().isEmpty()) {
            throw LegalPrivateRequirementsHttpException.unsupportedParameters();
        }
        final LegalPrivateRequirementsResponses.Pending body;
        try {
            body = LegalPrivateRequirementsResponses.pending(service.read(principal));
        } catch (LegalActorSnapshotException failure) {
            throw LegalPrivateRequirementsHttpException.unauthorized(failure);
        } catch (RuntimeException failure) {
            throw LegalPrivateRequirementsHttpException.unavailable(failure);
        }
        response.setHeader(HttpHeaders.ETAG, null);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(body);
    }
}
