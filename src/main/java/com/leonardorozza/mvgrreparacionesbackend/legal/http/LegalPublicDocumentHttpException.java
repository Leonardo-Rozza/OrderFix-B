package com.leonardorozza.mvgrreparacionesbackend.legal.http;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import org.springframework.http.HttpStatus;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A fixed HTTP decision; arbitrary persistence exception messages never enter the envelope. */
final class LegalPublicDocumentHttpException extends RuntimeException {

    private final HttpStatus status;
    private final String error;
    private final String code;
    private final Map<String, Object> details;

    private LegalPublicDocumentHttpException(HttpStatus status, String error, String message,
                                            String code, Map<String, Object> details, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.error = error;
        this.code = code;
        this.details = details == null ? null
                : Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    static LegalPublicDocumentHttpException unsupportedLocale(String locale) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("locale", locale);
        details.put("localesSoportados", List.of("es-AR"));
        return new LegalPublicDocumentHttpException(HttpStatus.BAD_REQUEST, "Solicitud inválida",
                "El idioma legal solicitado no está soportado.", "LOCALE_LEGAL_NO_SOPORTADO",
                details, null);
    }

    static LegalPublicDocumentHttpException unsupportedContext(String context) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("contexto", context);
        details.put("contextosSoportados", Arrays.stream(ContextoLegal.values()).map(Enum::name).toList());
        return new LegalPublicDocumentHttpException(HttpStatus.BAD_REQUEST, "Solicitud inválida",
                "El contexto legal solicitado no está soportado.", "CONTEXTO_LEGAL_NO_SOPORTADO",
                details, null);
    }

    static LegalPublicDocumentHttpException invalidPage(String parameter) {
        String message = parameter.equals("page")
                ? "page debe ser un entero entre 0 y 2147483647."
                : "size debe ser un entero entre 1 y 100.";
        return new LegalPublicDocumentHttpException(HttpStatus.BAD_REQUEST, "Solicitud inválida",
                message, null, null, null);
    }

    static LegalPublicDocumentHttpException notFound(String versionId) {
        return new LegalPublicDocumentHttpException(HttpStatus.NOT_FOUND, "Recurso no encontrado",
                "El documento legal no está disponible.", "DOCUMENTO_LEGAL_NO_ENCONTRADO",
                Map.of("versionId", versionId), null);
    }

    static LegalPublicDocumentHttpException unavailable(ContextoLegal context, Throwable cause) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("contexto", context == null ? null : context.name());
        details.put("locale", "es-AR");
        return new LegalPublicDocumentHttpException(HttpStatus.SERVICE_UNAVAILABLE,
                "Servicio no disponible", "El contrato legal no está disponible.",
                "CONTRATO_LEGAL_NO_DISPONIBLE", details, cause);
    }

    static LegalPublicDocumentHttpException preconditionFailed() {
        return new LegalPublicDocumentHttpException(HttpStatus.PRECONDITION_FAILED,
                "Precondición fallida", "La precondición de la solicitud no se cumple.",
                null, null, null);
    }

    HttpStatus status() { return status; }
    String error() { return error; }
    String code() { return code; }
    Map<String, Object> details() { return details; }
}
