package com.leonardorozza.mvgrreparacionesbackend.legal.http;

import org.springframework.http.HttpStatus;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Fixed private decisions; neither request selectors nor internal causes supply response text. */
final class LegalPrivateRequirementsHttpException extends RuntimeException {

    private final HttpStatus status;
    private final String error;
    private final String code;
    private final Map<String, Object> details;

    private LegalPrivateRequirementsHttpException(HttpStatus status, String error, String message,
                                                 String code, Map<String, Object> details, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.error = error;
        this.code = code;
        this.details = details == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    static LegalPrivateRequirementsHttpException unauthorized(Throwable cause) {
        return new LegalPrivateRequirementsHttpException(HttpStatus.UNAUTHORIZED, "No autorizado",
                "La identidad legal no está disponible.", null, null, cause);
    }

    static LegalPrivateRequirementsHttpException forbidden(Throwable cause) {
        return new LegalPrivateRequirementsHttpException(HttpStatus.FORBIDDEN, "Acceso denegado",
                "No tenés permisos para realizar esta acción.", null, null, cause);
    }

    static LegalPrivateRequirementsHttpException unsupportedParameters() {
        return new LegalPrivateRequirementsHttpException(HttpStatus.BAD_REQUEST, "Solicitud inválida",
                "La consulta de requisitos legales no admite parámetros.", null, null, null);
    }

    static LegalPrivateRequirementsHttpException methodNotAllowed() {
        return new LegalPrivateRequirementsHttpException(HttpStatus.METHOD_NOT_ALLOWED, "Método no permitido",
                "La consulta de requisitos legales requiere GET.", null, null, null);
    }

    static LegalPrivateRequirementsHttpException notFound() {
        return new LegalPrivateRequirementsHttpException(HttpStatus.NOT_FOUND, "Recurso no encontrado",
                "El recurso solicitado no está disponible.", null, null, null);
    }

    static LegalPrivateRequirementsHttpException unavailable(Throwable cause) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("contexto", null);
        details.put("locale", "es-AR");
        return new LegalPrivateRequirementsHttpException(HttpStatus.SERVICE_UNAVAILABLE,
                "Servicio no disponible", "El contrato legal no está disponible.",
                "CONTRATO_LEGAL_NO_DISPONIBLE", details, cause);
    }

    HttpStatus status() { return status; }
    String error() { return error; }
    String code() { return code; }
    Map<String, Object> details() { return details; }
}
