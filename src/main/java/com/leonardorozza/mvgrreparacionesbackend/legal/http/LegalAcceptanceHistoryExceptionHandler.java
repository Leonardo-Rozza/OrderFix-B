package com.leonardorozza.mvgrreparacionesbackend.legal.http;

import com.leonardorozza.mvgrreparacionesbackend.exceptions.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;

@RestControllerAdvice(assignableTypes = LegalAcceptanceHistoryController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
@Conditional(LegalPrivateRequirementsHttpConfiguration.Enabled.class)
public final class LegalAcceptanceHistoryExceptionHandler {

    @ExceptionHandler(LegalAcceptanceHistoryHttpException.class)
    public ResponseEntity<ApiError> handle(LegalAcceptanceHistoryHttpException exception,
                                          HttpServletRequest request, HttpServletResponse response) {
        response.setHeader(HttpHeaders.ETAG, null);
        ApiError error = new ApiError(LocalDateTime.now(), exception.status().value(), exception.error(),
                exception.getMessage(), request.getRequestURI(), exception.code(), exception.details());
        var result = ResponseEntity.status(exception.status()).header(HttpHeaders.CACHE_CONTROL, "no-store");
        if (exception.status() == HttpStatus.METHOD_NOT_ALLOWED) {
            result.header(HttpHeaders.ALLOW, "GET");
        }
        return result.body(error);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> forbidden(AccessDeniedException exception,
                                             HttpServletRequest request, HttpServletResponse response) {
        return handle(LegalAcceptanceHistoryHttpException.forbidden(exception), request, response);
    }
}
