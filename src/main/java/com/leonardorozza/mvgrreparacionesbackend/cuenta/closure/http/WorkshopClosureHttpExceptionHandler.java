package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.http;

import com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.*;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;

@RestControllerAdvice(assignableTypes=WorkshopClosureHttpController.class)
@Conditional(WorkshopClosureHttpConfiguration.Enabled.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class WorkshopClosureHttpExceptionHandler {
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handle(Exception error,HttpServletRequest request,HttpServletResponse response) {
        var failure=map(error); WorkshopClosureHttpGuardFilter.noStore(response);
        var builder=ResponseEntity.status(failure.status).header(HttpHeaders.CACHE_CONTROL,"private, no-store").header("X-Content-Type-Options","nosniff");
        if(failure.retryAfter>0) builder.header(HttpHeaders.RETRY_AFTER,Long.toString(failure.retryAfter));
        return builder.body(new ApiError(LocalDateTime.now(),failure.status.value(),failure.status.getReasonPhrase(),
                failure.getMessage(),request.getRequestURI(),failure.code,null));
    }
    private static WorkshopClosureHttpException map(Exception failure) {
        if(failure instanceof WorkshopClosureHttpException known) return known;
        if(failure instanceof UnauthorizedException || failure instanceof org.springframework.security.core.AuthenticationException) return WorkshopClosureHttpException.unauthorized();
        if(failure instanceof AccessDeniedException) return WorkshopClosureHttpException.forbidden();
        if(failure instanceof WorkshopClosureBlockedException) return new WorkshopClosureHttpException(HttpStatus.LOCKED,"CUENTA_EN_CIERRE","El taller está en proceso de cierre.");
        if(failure instanceof BadRequestException bad) {
            if("PASSWORD_ACTUAL_INVALIDA".equals(bad.getCode())) return new WorkshopClosureHttpException(HttpStatus.BAD_REQUEST,"PASSWORD_ACTUAL_INVALIDA","La contraseña actual no es correcta.");
            if("REAUTENTICACION_INVALIDA".equals(bad.getCode())) return WorkshopClosureHttpException.proof();
            return WorkshopClosureHttpException.invalid();
        }
        if(failure instanceof WorkshopClosureCommandService.Rejected command) return switch(command.code()) {
            case SESSION_INVALID -> WorkshopClosureHttpException.unauthorized();
            case FORBIDDEN -> WorkshopClosureHttpException.forbidden();
            case CONFIRMATION_INVALID -> WorkshopClosureHttpException.proof();
            case CONFLICT -> new WorkshopClosureHttpException(HttpStatus.CONFLICT,"CIERRE_CONFLICTO","El estado cambió o la operación no corresponde a este cierre. Volvé a consultar el estado del taller.");
            case CAPACITY,UNAVAILABLE -> WorkshopClosureHttpException.unavailable();
        };
        if(failure instanceof org.springframework.web.HttpMediaTypeNotSupportedException) return new WorkshopClosureHttpException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"SOLICITUD_INVALIDA","La solicitud requiere JSON UTF-8.");
        if(failure instanceof org.springframework.web.HttpRequestMethodNotSupportedException) return new WorkshopClosureHttpException(HttpStatus.METHOD_NOT_ALLOWED,"SOLICITUD_INVALIDA","El método de la solicitud no está permitido.");
        if(failure instanceof org.springframework.http.converter.HttpMessageNotReadableException) return WorkshopClosureHttpException.invalid();
        return WorkshopClosureHttpException.unavailable();
    }
}
