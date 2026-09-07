package com.leonardorozza.mvgrreparacionesbackend.legal.http;

import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.config.security.LegalPrivateRequirementsAuthenticationEntryPoint;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/** Accepts only the server principal; transport and metadata are read after its persisted observation. */
@RestController
@RequestMapping(LegalAcceptanceController.BASE_PATH)
@Conditional(LegalAcceptanceHttpConfiguration.Enabled.class)
public class LegalAcceptanceController {
    public static final String BASE_PATH = LegalPrivateRequirementsAuthenticationEntryPoint.HISTORY_PATH;
    private final LegalAcceptanceService service;
    private final LegalRequestMetadataResolver metadataResolver;

    public LegalAcceptanceController(LegalAcceptanceService service, LegalRequestMetadataResolver metadataResolver) {
        this.service = Objects.requireNonNull(service, "service");
        this.metadataResolver = Objects.requireNonNull(metadataResolver, "metadataResolver");
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ResponseEntity<Void> accept(@AuthenticationPrincipal AuthenticatedUserPrincipal principal,
                                      HttpServletRequest request, HttpServletResponse response) {
        if (principal == null) throw LegalAcceptanceHttpException.unauthorized(null);
        if (!LegalPrivateRequirementsAuthenticationEntryPoint.isAcceptancePost(request)) {
            throw LegalAcceptanceHttpException.notFound();
        }
        Objects.requireNonNull(service.accept(principal, LegalAcceptanceRequestReader.from(request, metadataResolver)),
                "legal acceptance result");
        response.setHeader(HttpHeaders.ETAG, null);
        response.setHeader(HttpHeaders.RETRY_AFTER, null);
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }
}
