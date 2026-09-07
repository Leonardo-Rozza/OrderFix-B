package com.leonardorozza.mvgrreparacionesbackend.legal.http;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceInputException;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceFailure;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceValidationException;
import org.springframework.http.HttpStatus;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;

/** Fixed transport decisions. Causes and transaction evidence never supply response fields. */
final class LegalAcceptanceHttpException extends RuntimeException {
    private final HttpStatus status;
    private final String error;
    private final String code;
    private final Map<String, Object> details;
    private final String retryAfter;

    private LegalAcceptanceHttpException(HttpStatus status, String error, String message,
            String code, Map<String, Object> details, String retryAfter, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.error = error;
        this.code = code;
        this.details = details == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(details));
        this.retryAfter = retryAfter;
    }

    static LegalAcceptanceHttpException requiredKey() {
        return key("IDEMPOTENCY_KEY_REQUERIDA", "La solicitud requiere una clave de idempotencia.");
    }

    static LegalAcceptanceHttpException invalidKey() {
        return key("IDEMPOTENCY_KEY_INVALIDA", "La clave de idempotencia no tiene un formato válido.");
    }

    private static LegalAcceptanceHttpException key(String code, String message) {
        return new LegalAcceptanceHttpException(HttpStatus.BAD_REQUEST, "Solicitud inválida", message,
                code, Map.of("header", "Idempotency-Key"), null, null);
    }

    static LegalAcceptanceHttpException invalidPayload() {
        return invalid(EnumSet.of(LegalAcceptanceValidationException.Motivo.PAYLOAD_LEGAL_INCOMPLETO), null);
    }

    private static LegalAcceptanceHttpException invalid(
            EnumSet<LegalAcceptanceValidationException.Motivo> motivos, Throwable cause) {
        return new LegalAcceptanceHttpException(HttpStatus.BAD_REQUEST, "Solicitud inválida",
                "La aceptación legal no es válida.", "ACEPTACION_LEGAL_INVALIDA",
                Map.of("motivos", motivos.stream().map(Enum::name).toList()), null, cause);
    }

    static LegalAcceptanceHttpException notFound() {
        return new LegalAcceptanceHttpException(HttpStatus.NOT_FOUND, "Recurso no encontrado",
                "El recurso solicitado no existe.", null, null, null, null);
    }

    static LegalAcceptanceHttpException unauthorized(Throwable cause) {
        return new LegalAcceptanceHttpException(HttpStatus.UNAUTHORIZED, "No autorizado",
                "La identidad legal no está disponible.", null, null, null, cause);
    }

    static LegalAcceptanceHttpException forbidden(Throwable cause) {
        return new LegalAcceptanceHttpException(HttpStatus.FORBIDDEN, "Acceso denegado",
                "No tenés permisos para realizar esta acción.", null, null, null, cause);
    }

    static LegalAcceptanceHttpException unavailable(Throwable cause) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("contexto", null);
        details.put("locale", "es-AR");
        return new LegalAcceptanceHttpException(HttpStatus.SERVICE_UNAVAILABLE, "Servicio no disponible",
                "El contrato legal no está disponible.", "CONTRATO_LEGAL_NO_DISPONIBLE", details, null, cause);
    }

    static LegalAcceptanceHttpException from(LegalAcceptanceFailure failure) {
        if (failure == null) return unavailable(null);
        // A failure after COMMIT, or without conclusive persistence evidence, is never a rejection/replay.
        if ((failure.completion() != LegalAcceptanceFailure.Completion.NONE
                && failure.completion() != LegalAcceptanceFailure.Completion.ROLLED_BACK)
                || failure.persistence() != LegalAcceptanceFailure.Persistence.NOT_PERSISTED
                || failure.confirmedReceipt().isPresent() || failure.reason() == null) {
            return unavailable(failure);
        }
        // Only identity rejection can precede the transaction. Business rejections require observed rollback.
        if (failure.completion() == LegalAcceptanceFailure.Completion.NONE
                && failure.reason() != LegalAcceptanceFailure.Reason.INVALID_ACTOR) return unavailable(failure);
        if (failure.inputReason().isPresent()
                && failure.reason() != LegalAcceptanceFailure.Reason.INVALID_PAYLOAD) return unavailable(failure);
        if (failure.reason() == LegalAcceptanceFailure.Reason.STALE
                || failure.reason() == LegalAcceptanceFailure.Reason.INVALID) {
            return validation(failure);
        }
        if (failure.validation().isPresent()) return unavailable(failure);
        return switch (failure.reason()) {
            case INVALID_ACTOR -> unauthorized(failure);
            case INVALID_PAYLOAD -> switch (failure.inputReason().orElse(
                    LegalAcceptanceInputException.Reason.INVALID_PAYLOAD)) {
                case REQUIRED_KEY -> requiredKey();
                case INVALID_KEY -> invalidKey();
                case INVALID_PAYLOAD -> invalidPayload();
            };
            case KEY_REUSED -> conflict("IDEMPOTENCY_KEY_REUTILIZADA",
                    "La clave de idempotencia ya corresponde a otra solicitud.", null, failure);
            case IN_PROGRESS -> conflict("IDEMPOTENCY_EN_PROGRESO",
                    "La solicitud con esta clave de idempotencia sigue en curso.", "1", failure);
            case UNAVAILABLE, STALE, INVALID -> unavailable(failure);
        };
    }

    private static LegalAcceptanceHttpException validation(LegalAcceptanceFailure failure) {
        var validation = failure.validation().orElse(null);
        if (validation == null) return unavailable(failure);
        if (failure.reason() == LegalAcceptanceFailure.Reason.INVALID) {
            if (validation.reason() != LegalAcceptanceValidationException.Reason.INVALID
                    || validation.submittedRevision() != null || validation.currentRequirements() != null
                    || validation.motivos() == null || validation.motivos().isEmpty()
                    || validation.motivos().size() > LegalAcceptanceValidationException.Motivo.values().length
                    || validation.motivos().stream().anyMatch(value -> value == null)) return unavailable(failure);
            return invalid(EnumSet.copyOf(validation.motivos()), failure);
        }
        if (validation.reason() != LegalAcceptanceValidationException.Reason.STALE
                || validation.submittedRevision() == null
                || !validation.submittedRevision().matches("sha256:[0-9a-f]{64}")
                || validation.currentRequirements() == null || validation.motivos() == null
                || !validation.motivos().isEmpty()) return unavailable(failure);
        final LegalPrivateRequirementsResponses.Pending current;
        try {
            current = LegalPrivateRequirementsResponses.pending(validation.currentRequirements());
        } catch (RuntimeException inconsistentSnapshot) {
            return unavailable(failure);
        }
        if (!"es-AR".equals(current.locale()) || current.requiredSetRevision() == null
                || !current.requiredSetRevision().matches("sha256:[0-9a-f]{64}")
                || current.requiredSetRevision().equals(validation.submittedRevision())) return unavailable(failure);
        return new LegalAcceptanceHttpException(HttpStatus.CONFLICT, "Conflicto",
                "Las condiciones legales cambiaron.", "DOCUMENTOS_LEGALES_DESACTUALIZADOS",
                Map.of("submittedRevision", validation.submittedRevision(), "requisitosActuales", current),
                null, failure);
    }

    private static LegalAcceptanceHttpException conflict(String code, String message,
            String retryAfter, Throwable cause) {
        return new LegalAcceptanceHttpException(HttpStatus.CONFLICT, "Conflicto", message, code,
                Map.of("operacion", "ACEPTACION_LEGAL"), retryAfter, cause);
    }

    HttpStatus status() { return status; }
    String error() { return error; }
    String code() { return code; }
    Map<String, Object> details() { return details; }
    String retryAfter() { return retryAfter; }
}
