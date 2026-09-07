package com.leonardorozza.mvgrreparacionesbackend.legal.http;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import org.springframework.http.HttpStatus;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Fixed public envelope for private history decisions; internal failures never supply response text. */
final class LegalAcceptanceHistoryHttpException extends RuntimeException {
    private final HttpStatus status;
    private final String error;
    private final String code;
    private final Map<String, Object> details;

    private LegalAcceptanceHistoryHttpException(HttpStatus status, String error, String message,
                                                String code, Map<String, Object> details, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.error = error;
        this.code = code;
        this.details = details == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    static LegalAcceptanceHistoryHttpException unauthorized(Throwable cause) {
        return new LegalAcceptanceHistoryHttpException(HttpStatus.UNAUTHORIZED, "No autorizado",
                "La identidad legal no está disponible.", null, null, cause);
    }

    static LegalAcceptanceHistoryHttpException forbidden(Throwable cause) {
        return new LegalAcceptanceHistoryHttpException(HttpStatus.FORBIDDEN, "Acceso denegado",
                "No tenés permisos para realizar esta acción.", null, null, cause);
    }

    static LegalAcceptanceHistoryHttpException invalidParameters() {
        return new LegalAcceptanceHistoryHttpException(HttpStatus.BAD_REQUEST, "Solicitud inválida",
                "Los parámetros del historial legal no son válidos.", null, null, null);
    }

    static LegalAcceptanceHistoryHttpException unsupportedContext(String context) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("contexto", context);
        details.put("contextosSoportados", Arrays.stream(ContextoLegal.values()).map(Enum::name).toList());
        return new LegalAcceptanceHistoryHttpException(HttpStatus.BAD_REQUEST, "Solicitud inválida",
                "El contexto legal solicitado no está soportado.", "CONTEXTO_LEGAL_NO_SOPORTADO", details, null);
    }

    static LegalAcceptanceHistoryHttpException methodNotAllowed() {
        return new LegalAcceptanceHistoryHttpException(HttpStatus.METHOD_NOT_ALLOWED, "Método no permitido",
                "La consulta del historial legal requiere GET.", null, null, null);
    }

    static LegalAcceptanceHistoryHttpException notFound() {
        return new LegalAcceptanceHistoryHttpException(HttpStatus.NOT_FOUND, "Recurso no encontrado",
                "El recurso solicitado no está disponible.", null, null, null);
    }

    static LegalAcceptanceHistoryHttpException unavailable(ContextoLegal context, Throwable cause) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("contexto", context == null ? null : context.name());
        details.put("locale", "es-AR");
        return new LegalAcceptanceHistoryHttpException(HttpStatus.SERVICE_UNAVAILABLE, "Servicio no disponible",
                "El contrato legal no está disponible.", "CONTRATO_LEGAL_NO_DISPONIBLE", details, cause);
    }

    HttpStatus status() { return status; }
    String error() { return error; }
    String code() { return code; }
    Map<String, Object> details() { return details; }
}
