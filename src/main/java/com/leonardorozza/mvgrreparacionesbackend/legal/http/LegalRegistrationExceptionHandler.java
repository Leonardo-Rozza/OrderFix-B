package com.leonardorozza.mvgrreparacionesbackend.legal.http;

import com.leonardorozza.mvgrreparacionesbackend.controller.AuthController;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;

/** Registration's typed decisions only; the other authentication handlers retain their advice. */
@RestControllerAdvice(assignableTypes = AuthController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class LegalRegistrationExceptionHandler {
    @ExceptionHandler(LegalRegistrationHttpException.class)
    public ResponseEntity<ApiError> handle(LegalRegistrationHttpException exception,
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
}
