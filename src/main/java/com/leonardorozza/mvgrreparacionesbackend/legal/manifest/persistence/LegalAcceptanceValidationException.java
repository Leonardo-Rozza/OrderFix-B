package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAuthenticatedRequirements;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;

/** Expected acceptance rejection; transport owns the HTTP envelope, never the message inputs. */
public final class LegalAcceptanceValidationException extends RuntimeException {
    public enum Reason { STALE, INVALID }

    /** Frozen public reason order. PAYLOAD_LEGAL_INCOMPLETO belongs to the future transport boundary. */
    public enum Motivo {
        REQUISITO_FALTANTE,
        REQUISITO_DUPLICADO,
        REQUISITO_NO_PERTENECE_AL_CONJUNTO,
        DOCUMENTO_FALTANTE,
        DOCUMENTO_DUPLICADO,
        DOCUMENTO_NO_PERTENECE_AL_REQUISITO,
        DIGEST_NO_COINCIDE,
        ACTO_NO_COINCIDE,
        CONFIRMACION_REQUERIDA,
        PAYLOAD_LEGAL_INCOMPLETO
    }

    private final Reason reason;
    private final String submittedRevision;
    private final LegalAuthenticatedRequirements currentRequirements;
    private final List<Motivo> motivos;

    private LegalAcceptanceValidationException(Reason reason, String submittedRevision,
            LegalAuthenticatedRequirements currentRequirements, List<Motivo> motivos) {
        super(reason == Reason.STALE ? "Las condiciones legales cambiaron." : "La aceptación legal no es válida.");
        this.reason = reason;
        this.submittedRevision = submittedRevision;
        this.currentRequirements = currentRequirements;
        this.motivos = List.copyOf(motivos);
    }

    static LegalAcceptanceValidationException stale(String submittedRevision,
            LegalAuthenticatedRequirements currentRequirements) {
        if (submittedRevision == null || !submittedRevision.matches("sha256:[0-9a-f]{64}")
                || currentRequirements == null) throw invalidObservation();
        return new LegalAcceptanceValidationException(Reason.STALE, submittedRevision, currentRequirements, List.of());
    }

    static LegalAcceptanceValidationException invalid(Collection<Motivo> motivos) {
        if (motivos == null || motivos.isEmpty() || motivos.size() > Motivo.values().length
                || motivos.stream().anyMatch(value -> value == null)) throw invalidObservation();
        return new LegalAcceptanceValidationException(Reason.INVALID, null, null,
                List.copyOf(EnumSet.copyOf(motivos)));
    }

    public Reason reason() { return reason; }
    public String submittedRevision() { return submittedRevision; }
    public LegalAuthenticatedRequirements currentRequirements() { return currentRequirements; }
    public List<Motivo> motivos() { return motivos; }

    @Override public String toString() { return "LegalAcceptanceValidationException[" + reason + "]"; }

    private static IllegalStateException invalidObservation() {
        return new IllegalStateException("La observación legal no es válida.");
    }
}
