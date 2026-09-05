package com.leonardorozza.mvgrreparacionesbackend.legal.http;

import com.leonardorozza.mvgrreparacionesbackend.config.security.LegalPublicRequirementsRequestMatcher;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;

@RestControllerAdvice(assignableTypes = LegalPublicRequirementsController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name = LegalPublicRequirementsRequestMatcher.ENABLED_PROPERTY, havingValue = "true")
public final class LegalPublicRequirementsExceptionHandler {

    @ExceptionHandler(LegalPublicRequirementsHttpException.class)
    public ResponseEntity<ApiError> handle(LegalPublicRequirementsHttpException exception,
                                          HttpServletRequest request, HttpServletResponse response) {
        response.setHeader(HttpHeaders.ETAG, null);
        ApiError error = new ApiError(LocalDateTime.now(), exception.status().value(), exception.error(),
                exception.getMessage(), request.getRequestURI(), exception.code(), exception.details());
        return ResponseEntity.status(exception.status()).header(HttpHeaders.CACHE_CONTROL, "no-store").body(error);
    }
}
