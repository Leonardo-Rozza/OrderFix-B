package com.leonardorozza.mvgrreparacionesbackend.legal.http;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestLimits;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalPublicRegistrationRequirements;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceValidationException.Motivo;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalRegistrationFailure;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalRegistrationValidationException;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import org.springframework.http.HttpStatus;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;

/** Fixed registration transport decisions; durable evidence and internal causes never supply response fields. */
final class LegalRegistrationHttpException extends RuntimeException {
    private final HttpStatus status;
    private final String error;
    private final String code;
    private final Map<String, Object> details;
    private final String retryAfter;

    private LegalRegistrationHttpException(HttpStatus status, String error, String message,
            String code, Map<String, Object> details, String retryAfter, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.error = error;
        this.code = code;
        this.details = details == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(details));
        this.retryAfter = retryAfter;
    }

    static LegalRegistrationHttpException requiredKey() {
        return key("IDEMPOTENCY_KEY_REQUERIDA", "La solicitud requiere una clave de idempotencia.");
    }

    static LegalRegistrationHttpException invalidKey() {
        return key("IDEMPOTENCY_KEY_INVALIDA", "La clave de idempotencia no tiene un formato válido.");
    }

    private static LegalRegistrationHttpException key(String code, String message) {
        return new LegalRegistrationHttpException(HttpStatus.BAD_REQUEST, "Solicitud inválida", message,
                code, Map.of("header", "Idempotency-Key"), null, null);
    }

    static LegalRegistrationHttpException invalidBody() {
        return new LegalRegistrationHttpException(HttpStatus.BAD_REQUEST, "Solicitud inválida",
                "El cuerpo de la solicitud es inválido o tiene un formato incorrecto.", null, null, null, null);
    }

    static LegalRegistrationHttpException invalidRegistration() {
        return new LegalRegistrationHttpException(HttpStatus.BAD_REQUEST, "Error de validación",
                "Los datos de registro no son válidos.", null, null, null, null);
    }

    static LegalRegistrationHttpException invalidPayload() {
        return invalid(EnumSet.of(Motivo.PAYLOAD_LEGAL_INCOMPLETO), null);
    }

    private static LegalRegistrationHttpException invalid(EnumSet<Motivo> motivos, Throwable cause) {
        return new LegalRegistrationHttpException(HttpStatus.BAD_REQUEST, "Solicitud inválida",
                "La aceptación legal no es válida.", "ACEPTACION_LEGAL_INVALIDA",
                Map.of("motivos", motivos.stream().map(Enum::name).toList()), null, cause);
    }

    static LegalRegistrationHttpException unavailable(Throwable cause) {
        return new LegalRegistrationHttpException(HttpStatus.SERVICE_UNAVAILABLE, "Servicio no disponible",
                "El contrato legal no está disponible.", "CONTRATO_LEGAL_NO_DISPONIBLE",
                Map.of("contexto", "REGISTRO", "locale", "es-AR"), null, cause);
    }

    static LegalRegistrationHttpException requiredAcceptance(LegalPublicRegistrationRequirements source) {
        final LegalPublicRequirementsResponses.Registration current;
        try {
            current = current(source);
        } catch (RuntimeException inconsistentSnapshot) {
            return unavailable(null);
        }
        return new LegalRegistrationHttpException(HttpStatus.PRECONDITION_REQUIRED, "Precondición requerida",
                "Tenés condiciones pendientes de revisión y aceptación.", "ACEPTACION_LEGAL_REQUERIDA",
                Map.of("requisitosActuales", current), null, null);
    }

    static LegalRegistrationHttpException from(LegalRegistrationFailure failure) {
        if (failure == null) return unavailable(null);
        try {
            return classify(failure);
        } catch (RuntimeException inconsistentObservation) {
            return unavailable(failure);
        }
    }

    private static LegalRegistrationHttpException classify(LegalRegistrationFailure failure) {
        // A failed delivery never turns a committed or uncertain operation into a client rejection.
        if ((failure.completion() != LegalRegistrationFailure.Completion.NONE
                && failure.completion() != LegalRegistrationFailure.Completion.ROLLED_BACK)
                || failure.persistence() != LegalRegistrationFailure.Persistence.NOT_PERSISTED
                || failure.confirmedReceipt().isPresent() || failure.reason() == null) {
            return unavailable(failure);
        }
        // L3 validates shape before borrowing. All semantic/idempotency decisions need observed rollback.
        if (failure.completion() == LegalRegistrationFailure.Completion.NONE
                && failure.reason() != LegalRegistrationFailure.Reason.INVALID_PAYLOAD) return unavailable(failure);
        if (failure.reason() == LegalRegistrationFailure.Reason.STALE
                || failure.reason() == LegalRegistrationFailure.Reason.INVALID) return validation(failure);
        if (failure.validation().isPresent()) return unavailable(failure);
        return switch (failure.reason()) {
            case INVALID_ACTOR -> authenticationRejected(failure);
            case INVALID_PAYLOAD -> invalidPayload();
            case KEY_REUSED -> conflict("IDEMPOTENCY_KEY_REUTILIZADA",
                    "La clave de idempotencia ya corresponde a otra solicitud.", null, failure);
            case IN_PROGRESS -> conflict("IDEMPOTENCY_EN_PROGRESO",
                    "La solicitud con esta clave de idempotencia sigue en curso.", "1", failure);
            case UNAVAILABLE, STALE, INVALID -> unavailable(failure);
        };
    }

    private static LegalRegistrationHttpException authenticationRejected(Throwable cause) {
        return new LegalRegistrationHttpException(HttpStatus.UNAUTHORIZED, "No autorizado",
                "Usuario o contraseña incorrectos", null, null, null, cause);
    }

    private static LegalRegistrationHttpException validation(LegalRegistrationFailure failure) {
        var validation = failure.validation().orElse(null);
        if (validation == null) return unavailable(failure);
        if (failure.reason() == LegalRegistrationFailure.Reason.INVALID) {
            if (validation.reason() != LegalRegistrationValidationException.Reason.INVALID
                    || validation.submittedRevision() != null || validation.currentRequirements() != null
                    || validation.motivos() == null || validation.motivos().isEmpty()
                    || validation.motivos().size() > Motivo.values().length
                    || validation.motivos().stream().anyMatch(value -> value == null)) return unavailable(failure);
            return invalid(EnumSet.copyOf(validation.motivos()), failure);
        }
        if (validation.reason() != LegalRegistrationValidationException.Reason.STALE
                || !canonicalRevision(validation.submittedRevision()) || validation.motivos() == null
                || !validation.motivos().isEmpty()) return unavailable(failure);
        var current = current(validation.currentRequirements());
        if (current.requiredSetRevision().equals(validation.submittedRevision())) return unavailable(failure);
        return new LegalRegistrationHttpException(HttpStatus.CONFLICT, "Conflicto",
                "Las condiciones legales cambiaron.", "DOCUMENTOS_LEGALES_DESACTUALIZADOS",
                Map.of("submittedRevision", validation.submittedRevision(), "requisitosActuales", current),
                null, failure);
    }

    private static LegalPublicRequirementsResponses.Registration current(LegalPublicRegistrationRequirements source) {
        var projection = source.projection();
        if (projection.context() != ContextoLegal.REGISTRO || projection.locale() != LocaleLegal.ES_AR
                || !canonicalRevision(source.requiredSetRevision()) || projection.requirements().isEmpty()
                || projection.requirements().size() > LegalManifestLimits.MAX_REQUIREMENTS
                || projection.requirements().stream().noneMatch(requirement -> requirement.required())) {
            throw new IllegalArgumentException("La observación pública no es coherente");
        }
        // The opaque core value already accredits canonical text and digests. Preserve its projection;
        // do not rehash Markdown or manufacture another profile, locale or revision during error delivery.
        for (var requirement : projection.requirements()) {
            if (requirement.context() != ContextoLegal.REGISTRO || requirement.documents().isEmpty()
                    || requirement.documents().size() > LegalManifestLimits.MAX_DOCUMENTS_PER_REQUIREMENT
                    || requirement.documents().stream().anyMatch(document -> document.locale() != LocaleLegal.ES_AR)) {
                throw new IllegalArgumentException("La observación pública no es coherente");
            }
        }
        return LegalPublicRequirementsResponses.registration(source);
    }

    private static boolean canonicalRevision(String revision) {
        return revision != null && revision.matches("sha256:[0-9a-f]{64}");
    }

    private static LegalRegistrationHttpException conflict(String code, String message,
            String retryAfter, Throwable cause) {
        return new LegalRegistrationHttpException(HttpStatus.CONFLICT, "Conflicto", message, code,
                Map.of("operacion", "REGISTRO"), retryAfter, cause);
    }

    HttpStatus status() { return status; }
    String error() { return error; }
    String code() { return code; }
    Map<String, Object> details() { return details; }
    String retryAfter() { return retryAfter; }
}
