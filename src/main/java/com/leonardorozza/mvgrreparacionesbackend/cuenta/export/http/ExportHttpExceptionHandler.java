package com.leonardorozza.mvgrreparacionesbackend.cuenta.export.http;

import com.leonardorozza.mvgrreparacionesbackend.controller.ExportController;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.WorkshopClosureBlockedException;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.WorkshopClosureBusyException;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.export.ExportPackageException;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.ApiError;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.BadRequestException;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import java.time.LocalDateTime;

@RestControllerAdvice(assignableTypes={ExportHttpController.class,ExportController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ExportHttpExceptionHandler {
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handle(Exception error,HttpServletRequest request,HttpServletResponse response) {
        ExportHttpException failure=map(error);
        ExportHttpGuardFilter.noStore(response);
        var builder=ResponseEntity.status(failure.status).header(HttpHeaders.CACHE_CONTROL,"private, no-store").header("X-Content-Type-Options","nosniff");
        if(failure.retryAfter>0) builder.header(HttpHeaders.RETRY_AFTER,Long.toString(failure.retryAfter));
        return builder.body(new ApiError(LocalDateTime.now(),failure.status.value(),failure.status.getReasonPhrase(),failure.getMessage(),request.getRequestURI(),failure.code,null));
    }
    private static ExportHttpException map(Exception failure) {
        if(failure instanceof ExportHttpException known) return known;
        if(failure instanceof WorkshopClosureBlockedException) return new ExportHttpException(HttpStatus.LOCKED,"CUENTA_EN_CIERRE","El taller está en proceso de cierre.");
        if(failure instanceof WorkshopClosureBusyException) return ExportHttpException.unavailable();
        if(failure instanceof UnauthorizedException || failure instanceof org.springframework.security.core.AuthenticationException) return ExportHttpException.unauthorized();
        if(failure instanceof AccessDeniedException) return ExportHttpException.forbidden();
        if(failure instanceof BadRequestException bad) {
            if("PASSWORD_ACTUAL_INVALIDA".equals(bad.getCode())) return new ExportHttpException(HttpStatus.BAD_REQUEST,"PASSWORD_ACTUAL_INVALIDA","La contraseña actual no es correcta.");
            if("REAUTENTICACION_INVALIDA".equals(bad.getCode())) return new ExportHttpException(HttpStatus.BAD_REQUEST,"REAUTENTICACION_INVALIDA","La confirmación no es válida o ya venció. Volvé a confirmar tu contraseña.");
            return ExportHttpException.invalid();
        }
        if(failure instanceof ExportPackageException export) return switch(export.code()) {
            case ACCESS_DENIED -> ExportHttpException.missing();
            case CAPACITY_EXCEEDED -> ExportHttpException.limited(60);
            case INVALID_PACKAGE -> new ExportHttpException(HttpStatus.CONFLICT,"EXPORTACION_NO_DISPONIBLE","La exportación todavía no está lista o ya no está disponible.");
            case INCONSISTENT_RELATION,INVALID_EVIDENCE,UNAVAILABLE -> ExportHttpException.unavailable();
        };
        if(failure instanceof HttpMediaTypeNotSupportedException) return new ExportHttpException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"SOLICITUD_INVALIDA","El tipo de contenido de la solicitud no está soportado.");
        if(failure instanceof HttpRequestMethodNotSupportedException) return new ExportHttpException(HttpStatus.METHOD_NOT_ALLOWED,"SOLICITUD_INVALIDA","El método de la solicitud no está permitido.");
        if(failure instanceof org.springframework.http.converter.HttpMessageNotReadableException
                || failure instanceof org.springframework.web.method.annotation.MethodArgumentTypeMismatchException) return ExportHttpException.invalid();
        return ExportHttpException.unavailable();
    }
}
