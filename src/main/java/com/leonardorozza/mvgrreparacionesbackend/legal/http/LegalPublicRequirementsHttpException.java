package com.leonardorozza.mvgrreparacionesbackend.legal.http;

import org.springframework.http.HttpStatus;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Fixed public decisions; internal failures never supply the envelope's message. */
final class LegalPublicRequirementsHttpException extends RuntimeException {

    private final HttpStatus status;
    private final String error;
    private final String code;
    private final Map<String, Object> details;

    private LegalPublicRequirementsHttpException(HttpStatus status, String error, String message,
                                                String code, Map<String, Object> details, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.error = error;
        this.code = code;
        this.details = details == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    static LegalPublicRequirementsHttpException unsupportedLocale(String locale) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("locale", locale);
        details.put("localesSoportados", List.of("es-AR"));
        return new LegalPublicRequirementsHttpException(HttpStatus.BAD_REQUEST, "Solicitud inválida",
                "El idioma legal solicitado no está soportado.", "LOCALE_LEGAL_NO_SOPORTADO", details, null);
    }

    static LegalPublicRequirementsHttpException unsupportedContext(String context) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("contexto", context);
        details.put("contextosSoportados", List.of("REGISTRO"));
        return new LegalPublicRequirementsHttpException(HttpStatus.BAD_REQUEST, "Solicitud inválida",
                "El contexto legal solicitado no está soportado.", "CONTEXTO_LEGAL_NO_SOPORTADO", details, null);
    }

    static LegalPublicRequirementsHttpException unavailable(Throwable cause) {
        return new LegalPublicRequirementsHttpException(HttpStatus.SERVICE_UNAVAILABLE,
                "Servicio no disponible", "El contrato legal no está disponible.",
                "CONTRATO_LEGAL_NO_DISPONIBLE", Map.of("contexto", "REGISTRO", "locale", "es-AR"), cause);
    }

    static LegalPublicRequirementsHttpException preconditionFailed() {
        return new LegalPublicRequirementsHttpException(HttpStatus.PRECONDITION_FAILED,
                "Precondición fallida", "La precondición de la solicitud no se cumple.", null, null, null);
    }

    HttpStatus status() { return status; }
    String error() { return error; }
    String code() { return code; }
    Map<String, Object> details() { return details; }
}
