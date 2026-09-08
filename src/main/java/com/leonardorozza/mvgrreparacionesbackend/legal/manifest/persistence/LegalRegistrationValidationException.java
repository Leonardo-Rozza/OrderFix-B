package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalPublicRegistrationRequirements;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceValidationException.Motivo;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;

/** Expected registration rejection; HTTP mapping and the public response belong to its caller. */
public final class LegalRegistrationValidationException extends RuntimeException {
    public enum Reason { STALE, INVALID }

    private final Reason reason;
    private final String submittedRevision;
    private final LegalPublicRegistrationRequirements currentRequirements;
    private final List<Motivo> motivos;

    private LegalRegistrationValidationException(Reason reason, String submittedRevision,
            LegalPublicRegistrationRequirements currentRequirements, List<Motivo> motivos) {
        super(reason == Reason.STALE ? "Las condiciones legales cambiaron." : "La aceptación legal no es válida.");
        this.reason = reason;
        this.submittedRevision = submittedRevision;
        this.currentRequirements = currentRequirements;
        this.motivos = List.copyOf(motivos);
    }

    static LegalRegistrationValidationException stale(String submittedRevision,
            LegalPublicRegistrationRequirements currentRequirements) {
        if (submittedRevision == null || !submittedRevision.matches("sha256:[0-9a-f]{64}")
                || currentRequirements == null) throw invalidObservation();
        return new LegalRegistrationValidationException(Reason.STALE, submittedRevision, currentRequirements, List.of());
    }

    static LegalRegistrationValidationException invalid(Collection<Motivo> motivos) {
        if (motivos == null || motivos.isEmpty() || motivos.size() > Motivo.values().length
                || motivos.stream().anyMatch(value -> value == null)) throw invalidObservation();
        return new LegalRegistrationValidationException(Reason.INVALID, null, null,
                List.copyOf(EnumSet.copyOf(motivos)));
    }

    public Reason reason() { return reason; }
    public String submittedRevision() { return submittedRevision; }
    public LegalPublicRegistrationRequirements currentRequirements() { return currentRequirements; }
    public List<Motivo> motivos() { return motivos; }

    @Override public String toString() { return "LegalRegistrationValidationException[" + reason + "]"; }

    private static IllegalStateException invalidObservation() {
        return new IllegalStateException("La observación legal no es válida.");
    }
}
