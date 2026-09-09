package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoActoLegal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Immutable, form-validated business input with deterministic list order.
 * This value does not authenticate an actor, accredit current membership or prove consent.
 */
public final class LegalAcceptanceCommand {
    public enum Operation { REGISTRATION, AUTHENTICATED_ACCEPTANCE }

    private final PhotoContext photo;
    private final Operation operation;
    private final LegalActorSnapshot actor;
    private final Registration registration;
    private final String requiredSetRevision;
    private final List<Acceptance> acceptances;

    /** Package construction belongs to the validator, after checking and copying the complete input. */
    LegalAcceptanceCommand(Operation operation, LegalActorSnapshot actor, Registration registration,
                           String requiredSetRevision, List<Acceptance> acceptances) {
        this(operation, actor, registration, requiredSetRevision, acceptances, null);
    }

    LegalAcceptanceCommand(Operation operation, LegalActorSnapshot actor, Registration registration,
            String requiredSetRevision, List<Acceptance> acceptances, PhotoContext photo) {
        this.photo = photo;
        this.operation = operation;
        this.actor = actor;
        this.registration = registration;
        this.requiredSetRevision = requiredSetRevision;
        this.acceptances = List.copyOf(acceptances);
    }

    public PhotoContext photo() { return photo; }
    public record PhotoContext(long reparacionId, String nombre, String mimeType, long bytes, String sha256, String momento) {
        @Override public String toString() { return "PhotoContext[redacted]"; }
    }

    public Operation operation() { return operation; }
    public LegalActorSnapshot actor() { return actor; }
    public Registration registration() { return registration; }
    public String requiredSetRevision() { return requiredSetRevision; }
    public List<Acceptance> acceptances() { return acceptances; }

    @Override public String toString() { return "LegalAcceptanceCommand[redacted]"; }

    /** Exact registration values, including password, retained solely as business input to the HMAC. */
    public record Registration(String nombreTaller, String telefonoTaller, String nombreAdmin,
                               String email, String password) {
        @Override public String toString() { return "Registration[redacted]"; }
    }

    /** Raw typed input can be incomplete; the validator owns shape checks, later stages own semantics. */
    public record Acceptance(UUID requisitoVersionId, TipoActoLegal tipoActo, String afirmacionSha256,
                             List<Document> documentos, boolean confirmado) {
        public Acceptance {
            if (documentos != null && documentos.size() > LegalAcceptanceCommandValidator.MAX_DOCUMENTS_PER_ACCEPTANCE) {
                throw new IllegalArgumentException("El comando legal no tiene un formato válido.");
            }
            // Keep a missing list or null member available for a single safe validator error.
            // A supplied list is always copied, including before construction of the command.
            documentos = documentos == null ? null : Collections.unmodifiableList(new ArrayList<>(documentos));
        }
        @Override public String toString() { return "Acceptance[redacted]"; }
    }

    public record Document(UUID documentoVersionId, String sha256) {
        @Override public String toString() { return "Document[redacted]"; }
    }
}
