package com.leonardorozza.mvgrreparacionesbackend.legal.http;

import com.leonardorozza.mvgrreparacionesbackend.exceptions.ApiError;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceFailure;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;

/** Applies fixed write decisions only to the enabled acceptance controller. */
@RestControllerAdvice(assignableTypes = LegalAcceptanceController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
@Conditional(LegalAcceptanceHttpConfiguration.Enabled.class)
public final class LegalAcceptanceExceptionHandler {
    @ExceptionHandler(LegalAcceptanceHttpException.class)
    public ResponseEntity<ApiError> handle(LegalAcceptanceHttpException exception,
                                          HttpServletRequest request, HttpServletResponse response) {
        response.setHeader(HttpHeaders.ETAG, null);
        response.setHeader(HttpHeaders.RETRY_AFTER, null);
        var error = new ApiError(LocalDateTime.now(), exception.status().value(), exception.error(),
                exception.getMessage(), request.getRequestURI(), exception.code(), exception.details());
        var result = ResponseEntity.status(exception.status()).contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CACHE_CONTROL, "no-store");
        if (exception.retryAfter() != null) result.header(HttpHeaders.RETRY_AFTER, exception.retryAfter());
        return result.body(error);
    }

    @ExceptionHandler(LegalAcceptanceFailure.class)
    public ResponseEntity<ApiError> failure(LegalAcceptanceFailure exception,
                                           HttpServletRequest request, HttpServletResponse response) {
        return handle(LegalAcceptanceHttpException.from(exception), request, response);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> forbidden(AccessDeniedException exception,
                                             HttpServletRequest request, HttpServletResponse response) {
        return handle(LegalAcceptanceHttpException.forbidden(exception), request, response);
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiError> unexpected(RuntimeException exception,
                                              HttpServletRequest request, HttpServletResponse response) {
        return handle(LegalAcceptanceHttpException.unavailable(exception), request, response);
    }
}
